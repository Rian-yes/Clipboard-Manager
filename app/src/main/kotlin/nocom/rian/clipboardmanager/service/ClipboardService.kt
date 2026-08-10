package nocom.rian.clipboardmanager.service

import nocom.rian.clipboardmanager.R
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import androidx.core.app.NotificationCompat
import android.service.quicksettings.TileService
import nocom.rian.clipboardmanager.MainActivity
import nocom.rian.clipboardmanager.SettingsActivity
import nocom.rian.clipboardmanager.db.AppDatabase
import nocom.rian.clipboardmanager.db.ClipboardItem
import nocom.rian.clipboardmanager.db.ClipboardRepository
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.ShizukuProvider
import android.content.IClipboard
import android.content.IOnPrimaryClipChangedListener
import android.app.IActivityManager
import android.content.pm.IPackageManager // Ensure this import matches your AIDL package

private data class ImageSaveResult(val filePath: String, val hash: String)

private data class ClipSource(
    val appName: String,
    val packageName: String,
    val userId: Int = 0,
    val windowLabel: String? = null,
    val activityName: String? = null,
    val prevAppName: String? = null,
    val prevPackageName: String? = null,
    val prevWindowLabel: String? = null,
    val prevActivityName: String? = null
)

class ClipboardService : Service() {

    // region Properties, Fields & Configurations
    // Cached once, reused on every clip event
    private lateinit var repository: ClipboardRepository

    // Pref caches — nulled out by the listener when the relevant key changes
    @Volatile private var cachedExcludedPackages: Set<String>? = null
    @Volatile private var cachedSensitivePackages: Set<String>? = null
    @Volatile private var cachedLogPrefs: LogPrefs? = null
    @Volatile private var cachedAutomationRules: List<nocom.rian.clipboardmanager.db.AutomationRule>? = null

    // Debounce handle for notification updates
    private var notificationUpdateJob: kotlinx.coroutines.Job? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var clipboardService: IClipboard? = null
    private var activityManager: IActivityManager? = null
    private var iPackageManager: IPackageManager? = null
    private var overlayWindowManager: OverlayWindowManager? = null

    private var lastCopiedText: String? = null
    private var lastTimestamp: Long = 0
    private val CHANNEL_ID = "clipboard_monitor_channel"

    private var isListenerRegistered = false
    private var isMonitoringActive = false
    private var pendingClipRead = false

    private data class LogPrefs(
        val logAppName: Boolean, val logPackage: Boolean,
        val logWindow: Boolean,  val logActivity: Boolean,
        val logPrevAppName: Boolean, val logPrevPackage: Boolean,
        val logPrevWindow: Boolean,  val logPrevActivity: Boolean,
        val syncSensitiveFromClipboard: Boolean          // ← add this
    )

    // region Companion Object
    companion object {
        @Volatile var isRunning = false
        const val ACTION_STOP_MONITORING = "nocom.rian.clipboardmanager.STOP_MONITORING"
        const val ACTION_SET_TRIGGER_VISIBILITY = "nocom.rian.ACTION_SET_TRIGGER_VISIBILITY"
        const val EXTRA_IS_VISIBLE = "extra_is_visible"
    }
    
    private val overlayToggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "nocom.rian.ACTION_TOGGLE_OVERLAY") {
                val enabled = intent.getBooleanExtra("enabled", true)
                if (enabled && SettingsActivity.isOverlayFeatureAllowed(context)) {
                    overlayWindowManager?.showFloatingTrigger()
                } else {
                    overlayWindowManager?.removeAll()
                }
            }
        }
    }
    
    private val overlayVisibilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SET_TRIGGER_VISIBILITY) {
                val isVisible = intent.getBooleanExtra(EXTRA_IS_VISIBLE, true)
                // Use standard null-checks instead of .isInitialized
                overlayWindowManager?.setTriggerVisibility(isVisible)
            }
        }
    }
    
    // region Asynchronous Callbacks & Broadcast Receivers
    private val prefChangeListener =
    android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when {
            key == SettingsActivity.KEY_EXCLUDED_PACKAGES             -> cachedExcludedPackages = null
            key == SettingsActivity.KEY_SENSITIVE_PACKAGES            -> cachedSensitivePackages = null
            key == SettingsActivity.KEY_SYNC_SENSITIVE_FROM_CLIPBOARD -> cachedLogPrefs = null
            key == nocom.rian.clipboardmanager.db.PREFS_KEY_AUTOMATION_RULES -> cachedAutomationRules = null
            key?.startsWith("log_field") == true                      -> cachedLogPrefs = null
        }
    }

    private val screenUnlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT && pendingClipRead) {
                android.util.Log.d("ClipboardService", "Screen unlocked — retrying pending clip read")
                pendingClipRead = false
                fetchAndSaveClipboard()
            }
        }
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        android.util.Log.w("ClipboardService", "Shizuku binder died — clearing services")
        // Dispatch to IO — teardown makes synchronous IPC calls that must NOT run on main thread
        serviceScope.launch(Dispatchers.IO) {
            teardownShizukuServices()
        }
    }
    
    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        android.util.Log.d("ClipboardService", "Shizuku binder restored — reconnecting")
        serviceScope.launch(Dispatchers.IO) {
            setupShizukuServices()
        }
    }
    
    // Add this — handles permission revocation when Shizuku binder stays alive
    private val shizukuPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult != PackageManager.PERMISSION_GRANTED) {
                android.util.Log.w("ClipboardService", "Shizuku permission revoked — tearing down")
                serviceScope.launch(Dispatchers.IO) {
                    teardownShizukuServices()
                }
            }
        }
    
    private val clipListener = object : IOnPrimaryClipChangedListener.Stub() {
        override fun dispatchPrimaryClipChanged() {
            // This runs on a system Binder thread. An uncaught Throwable here
            // will kill the process before logcat has time to flush — hence the
            // Throwable (not Exception) catch and the explicit permission guard.
            try {
                if (!isShizukuAvailable()) {
                    android.util.Log.w("ClipboardService", "Permission lost — triggering teardown from callback")
                    serviceScope.launch(Dispatchers.IO) { teardownShizukuServices() }
                    return
                }
                fetchAndSaveClipboard()
            } catch (e: Throwable) {
                android.util.Log.e("ClipboardService", "dispatchPrimaryClipChanged crashed", e)
            }
        }
    }

    // region 1. Core Android Service Lifecycle
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        
        createNotificationChannel()
        startForeground(1, createNotification())

        repository = ClipboardRepository(AppDatabase.getDatabase(applicationContext).clipboardDao())

        getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefChangeListener)

         // Add this right below where repository = ClipboardRepository(...) is assigned
        overlayWindowManager = OverlayWindowManager(this, repository, serviceScope)
        
        if (SettingsActivity.isOverlayFeatureAllowed(this)) {
            overlayWindowManager?.showFloatingTrigger()
        }
        val visibilityFilter = IntentFilter(ACTION_SET_TRIGGER_VISIBILITY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(overlayVisibilityReceiver, visibilityFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(overlayVisibilityReceiver, visibilityFilter)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(overlayToggleReceiver, IntentFilter("nocom.rian.ACTION_TOGGLE_OVERLAY"), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(overlayToggleReceiver, IntentFilter("nocom.rian.ACTION_TOGGLE_OVERLAY"))
        }

        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.addBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        val filter = android.content.IntentFilter(Intent.ACTION_USER_PRESENT)
        registerReceiver(screenUnlockReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle stop action from notification button
        if (intent?.action == ACTION_STOP_MONITORING) {
            getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(SettingsActivity.KEY_MONITORING_ENABLED, false).commit()
            stopSelf()
            return START_NOT_STICKY
        }
    
        updateNotification()
    
        if (clipboardService == null && Shizuku.pingBinder()) {
            setupShizukuServices()
        }
        
        try {
            TileService.requestListeningState(
                this,
                ComponentName(this, ClipboardTileService::class.java)
            )
        } catch (e: Exception) {
            android.util.Log.e("ClipboardService", "Failed to update tile on startup", e)
        }
    
        serviceScope.launch {
            val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val days = prefs.getInt(SettingsActivity.KEY_AUTO_DELETE_DAYS, 0)
            if (days > 0) {
                val cutoff = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000)
                val db = AppDatabase.getDatabase(applicationContext)
                val repository = ClipboardRepository(db.clipboardDao())
                repository.deleteOlderThanWithImages(
                    cutoffMs = cutoff,
                    onEachDeleted = { item -> deleteImageFileIfNeeded(item) },
                    database = db
                )
                android.util.Log.d("ClipboardService", "Auto-delete on start: safely removed clips and images older than $days days")
            }
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        overlayWindowManager?.removeAll()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)?.cancel(1)
        getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        serviceScope.cancel()
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
        teardownShizukuServices()
        try { unregisterReceiver(screenUnlockReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(overlayToggleReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(overlayVisibilityReceiver) } catch (e: Exception) {}
        try {
            TileService.requestListeningState(
            this,
            ComponentName(this, ClipboardTileService::class.java)
            )
        } catch (e: Exception) {
            android.util.Log.e("ClipboardService", "Failed to update tile on destroy", e)
        }
        super.onDestroy()
    }

    // region 2. Shizuku & IPC Lifecycle Management
    private fun isBinderAlive(): Boolean {
        return try {
            clipboardService?.asBinder()?.isBinderAlive == true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun isShizukuAvailable(): Boolean {
        return try {
            // Use the Shizuku imports already present in your file
            if (Shizuku.pingBinder()) {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun setupShizukuServices() {
        if (!Shizuku.pingBinder()) {
            android.util.Log.w("ClipboardService", "Shizuku not available, aborting setup")
            return
        }
    
        // Must check permission BEFORE touching any Shizuku-wrapped binder.
        // After a revocation the binder is alive but calls will be rejected.
        if (!isShizukuAvailable()) {
            android.util.Log.w("ClipboardService", "Shizuku permission not granted — aborting setup")
            return
        }
    
        if (isListenerRegistered && isBinderAlive()) {
            android.util.Log.d("ClipboardService", "Binder already alive, skipping setup")
            return
        }
    
        if (clipboardService != null && !isBinderAlive()) {
            android.util.Log.w("ClipboardService", "Stale binder detected — tearing down before reconnect")
            teardownShizukuServices()
        }
    
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
    
            val clipBinder = getServiceMethod.invoke(null, Context.CLIPBOARD_SERVICE) as IBinder
            clipboardService = IClipboard.Stub.asInterface(ShizukuBinderWrapper(clipBinder) as IBinder)
    
            val amBinder = getServiceMethod.invoke(null, Context.ACTIVITY_SERVICE) as IBinder
            activityManager = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(amBinder) as IBinder)
    
            val pmBinder = getServiceMethod.invoke(null, "package") as IBinder
            iPackageManager = IPackageManager.Stub.asInterface(ShizukuBinderWrapper(pmBinder) as IBinder)
    
            invokeClipboard("addPrimaryClipChangedListener", clipListener)
            isListenerRegistered = true
            isMonitoringActive = true
            android.util.Log.d("ClipboardService", "Shizuku services connected successfully")
    
            updateNotification()
    
        } catch (e: Exception) {
            android.util.Log.e("ClipboardService", "Setup failed", e)
            teardownShizukuServices()
        }
    }
    
    private fun teardownShizukuServices() {
        try {
            if (isListenerRegistered) {
                invokeClipboard("removePrimaryClipChangedListener", clipListener)
            }
        } catch (e: Exception) {
            android.util.Log.w("ClipboardService", "Error removing listener during teardown", e)
        } finally {
            clipboardService = null
            activityManager = null
            iPackageManager = null
            isListenerRegistered = false
            isMonitoringActive = false
        }
        // Reflect the inactive state in the notification immediately
        updateNotification()
    }

    // region 3. Main Transaction Logic (Clipboard Processing)
    private fun fetchAndSaveClipboard() {
        serviceScope.launch {
            try {
                val keyguardManager = getSystemService(android.app.KeyguardManager::class.java)
                if (keyguardManager.isKeyguardLocked) {
                    android.util.Log.d("ClipboardService", "Screen locked — deferring clip read until unlock")
                    pendingClipRead = true
                    return@launch
                }
    
                val clipData = invokeClipboard("getPrimaryClip") as? ClipData
                if (clipData == null || clipData.itemCount == 0) return@launch
    
                // Guard: skip any clipboard write that our own app triggered internally to prevent self-duplication
//             // if (clipData.description.extras?.getBoolean("IS_INTERNAL_COPY", false) == true) {
//             //     android.util.Log.d("ClipboardService", "Skipping self-originated internal copy event")
//             //     return@launch
//             // }
    
                val mimeType    = clipData.description.getMimeType(0) ?: "text/plain"
                val isImage     = mimeType.startsWith("image/")
                val currentTime = System.currentTimeMillis()
    
                val source     = getTopAppInfo()
                val rawPackage = source.packageName
                val packageKey = "${source.packageName}:${source.userId}"
    
                val lp = loadLogPrefs()
                
                val excludedPackages = loadExcludedPackages()
                val isExcluded = excludedPackages.any { entry ->
                    entry == packageKey || (!entry.contains(':') && entry == rawPackage)
                }
                if (isExcluded) {
                    android.util.Log.d("ClipboardService", "Skipping clip from excluded app/profile context: $packageKey")
                    return@launch
                }
    
                val sensitivePackages = loadSensitivePackages()
                val autoSensitive = sensitivePackages.any { entry ->
                    entry == packageKey || (!entry.contains(':') && entry == rawPackage)
                }
    
                val isSystemSensitive = lp.syncSensitiveFromClipboard &&
                        (clipData.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true)
    
                val isSensitive = autoSensitive || isSystemSensitive
    
                if (isSystemSensitive) {
                    android.util.Log.d("ClipboardService", "Clip flagged sensitive by source app")
                }
    
                if (isImage) {
                    val uri       = clipData.getItemAt(0).uri ?: return@launch
                    val uriString = uri.toString()
    
                    if (uriString == lastCopiedText && (currentTime - lastTimestamp) < 1500) {
                        android.util.Log.d("ClipboardService", "Skipped: rapid duplicate image URI")
                        return@launch
                    }
                    lastCopiedText = uriString
                    lastTimestamp  = currentTime
    
                    val result = saveImageFromUri(uri, mimeType, currentTime) ?: run {
                        android.util.Log.e("ClipboardService", "Image save failed, aborting insert")
                        return@launch
                    }
                    //Removed hash+package method to avoid duplication
                   // val existing = repository.findByHashAndPackage(result.hash, source.packageName)
                    val existing = repository.findByHash(result.hash)

                    if (existing != null) {
                        java.io.File(result.filePath).delete()
                        repository.refreshExistingClip(id = existing.id, timestamp = currentTime)
                        android.util.Log.d("ClipboardService", "Refreshed duplicate image id=${existing.id}")
                    } else {
                        var imageItem = ClipboardItem(
                            clip                 = null,
                            filePath             = result.filePath,
                            mimeType             = mimeType,
                            imageHash            = result.hash,
                            appName              = if (lp.logAppName)      source.appName          else null,
                            packageName          = if (lp.logPackage)      source.packageName      else null,
                            windowLabel          = if (lp.logWindow)       source.windowLabel      else null,
                            activityName         = if (lp.logActivity)     source.activityName     else null,
                            previousAppName      = if (lp.logPrevAppName)  source.prevAppName      else null,
                            previousPackageName  = if (lp.logPrevPackage)  source.prevPackageName  else null,
                            previousWindowLabel  = if (lp.logPrevWindow)   source.prevWindowLabel  else null,
                            previousActivityName = if (lp.logPrevActivity) source.prevActivityName else null,
                            isSensitive          = isSensitive,
                            timestamp            = currentTime
                        )
                        imageItem = applyAutomationRules(imageItem, null, source.packageName, source.appName, source.windowLabel, source.prevAppName, source.prevWindowLabel)
                        repository.insert(imageItem)
                        android.util.Log.d("ClipboardService", "Inserted image from ${source.packageName}")
                        enforcePolicies(repository)
                    }
    
                } else {
                    val text = clipData.getItemAt(0)?.text?.toString()
                    if (text.isNullOrBlank()) return@launch
    
                    if (text == lastCopiedText && (currentTime - lastTimestamp) < 1500) {
                        android.util.Log.d("ClipboardService", "Skipped: rapid duplicate text")
                        return@launch
                    }
                    lastCopiedText = text
                    lastTimestamp  = currentTime
    
                    val existing = repository.findByContent(text)
                    if (existing != null) {
                        repository.refreshExistingClip(id = existing.id, timestamp = currentTime)
                        android.util.Log.d("ClipboardService", "Refreshed existing clip id=${existing.id}")
                    } else {
                        var textItem = ClipboardItem(
                            clip                 = text,
                            appName              = if (lp.logAppName)      source.appName          else null,
                            packageName          = if (lp.logPackage)      source.packageName      else null,
                            windowLabel          = if (lp.logWindow)       source.windowLabel      else null,
                            activityName         = if (lp.logActivity)     source.activityName     else null,
                            previousAppName      = if (lp.logPrevAppName)  source.prevAppName      else null,
                            previousPackageName  = if (lp.logPrevPackage)  source.prevPackageName  else null,
                            previousWindowLabel  = if (lp.logPrevWindow)   source.prevWindowLabel  else null,
                            previousActivityName = if (lp.logPrevActivity) source.prevActivityName else null,
                            isSensitive          = isSensitive,
                            timestamp            = currentTime
                        )
                        textItem = applyAutomationRules(textItem, text, source.packageName, source.appName, source.windowLabel, source.prevAppName, source.prevWindowLabel)
                        repository.insert(textItem)
                        android.util.Log.d("ClipboardService", "Inserted text clip from ${source.packageName}")
                        enforcePolicies(repository)

                        // Send broadcast intent if enabled in settings
                        try {
                            val prefs = getSharedPreferences("clipboard_settings", Context.MODE_PRIVATE)
                            val enabled = prefs.getBoolean("intent_sender_enabled", false)
                            val action = prefs.getString("intent_sender_action", "") ?: ""
                            val extraKey = prefs.getString("intent_sender_extra_key", "") ?: ""
                            val pkg = prefs.getString("intent_sender_package", "") ?: ""
                            if (enabled && action.isNotEmpty() && extraKey.isNotEmpty()) {
                                val intent = Intent(action).apply {
                                    putExtra(extraKey, text)
                                    if (pkg.isNotEmpty()) {
                                        setPackage(pkg)
                                    }
                                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                                }
                                sendBroadcast(intent)
                                android.util.Log.d("ClipboardService", "Sent broadcast intent: action=$action, extraKey=$extraKey, package=$pkg")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ClipboardService", "Failed to send broadcast intent", e)
                        }
                        enforcePolicies(repository)
                    }
                }
    
                updateNotification()
    
            } catch (e: Exception) {
                android.util.Log.e("ClipboardService", "Save failed", e)
            }
        }
    }

    // region 4. Task & App Package Enrichment
    private fun getTaskUserId(task: Any): Int {
        return try {
            // getField (not getDeclaredField) walks up to TaskInfo where userId is actually declared
            task.javaClass.getField("userId").get(task) as Int
        } catch (e: Exception) {
            0
        }
    }
        
    private fun getTopAppInfo(): ClipSource {
        var confirmedPackage: String? = null
    
        val currentForegroundUserId = try {
            val getCurrentUserMethod = activityManager?.javaClass?.getMethod("getCurrentUser")
            getCurrentUserMethod?.invoke(activityManager) as? Int ?: 0
        } catch (e: Exception) {
            0
        }
        
        try {
            val sourcePackage = invokeClipboard("getPrimaryClipSource") as? String
            android.util.Log.d("ClipboardService", "getPrimaryClipSource returned: $sourcePackage")
            if (!sourcePackage.isNullOrBlank()) {
                confirmedPackage = sourcePackage
            }
        } catch (e: Exception) {
            android.util.Log.w("ClipboardService", "getPrimaryClipSource failed: ${e.message}")
        }
    
        try {
            val tasks = activityManager?.getTasks(10)
    
            // Fix: use getTaskUserId() which correctly searches the superclass TaskInfo
            val activeForegroundUserId = tasks?.firstOrNull()?.let { task ->
                getTaskUserId(task)
            } ?: currentForegroundUserId
    
            val topTask: android.app.ActivityManager.RunningTaskInfo?
            val prevTask: android.app.ActivityManager.RunningTaskInfo?
    
            if (confirmedPackage != null) {
                topTask = tasks?.firstOrNull { task ->
                    val pkg = task.topActivity?.packageName ?: task.baseActivity?.packageName
                    // Fix: use getTaskUserId() here too
                    val tUserId = getTaskUserId(task)
                    pkg == confirmedPackage && tUserId == activeForegroundUserId
                } ?: tasks?.firstOrNull { task ->
                    val pkg = task.topActivity?.packageName ?: task.baseActivity?.packageName
                    pkg == confirmedPackage
                }
                
                prevTask = tasks?.firstOrNull { task ->
                    val pkg = task.topActivity?.packageName ?: task.baseActivity?.packageName
                    pkg != null && !isSelfOrSystem(pkg) && pkg != confirmedPackage
                }
            } else {
                topTask = tasks?.firstOrNull { task ->
                    val pkg = task.topActivity?.packageName ?: task.baseActivity?.packageName
                    pkg != null && !isSelfOrSystem(pkg)
                }
                val topPkg = topTask?.topActivity?.packageName ?: topTask?.baseActivity?.packageName
                prevTask = tasks?.firstOrNull { task ->
                    val pkg = task.topActivity?.packageName ?: task.baseActivity?.packageName
                    pkg != null && !isSelfOrSystem(pkg) && pkg != topPkg
                }
            }
    
            if (topTask != null) {
                val topPackage = topTask.topActivity?.packageName
                    ?: topTask.baseActivity?.packageName
                    ?: confirmedPackage ?: ""
            
                // Fix: use getTaskUserId() instead of inline getDeclaredField
                val taskUserId = getTaskUserId(topTask)
            
                val activityName = topTask.topActivity?.className
                    ?.substringAfterLast('.')
                    ?.takeIf { it.isNotBlank() }
            
                val windowLabel: String? = topTask.taskDescription?.label
                    ?.takeIf { it.isNotBlank() }
                    ?: topTask.topActivity?.let { component ->
                        try {
                            packageManager.getActivityInfo(component, 0)
                                .loadLabel(packageManager)
                                .toString()
                                .takeIf { it.isNotBlank() }
                        } catch (e: Exception) { null }
                    }
            
                val appLabel = getSafeAppLabel(topPackage)
            
                var prevAppName: String? = null
                var prevPackageName: String? = null
                var prevWindowLabel: String? = null
                var prevActivityName: String? = null
            
                if (prevTask != null) {
                    val prevPackage = prevTask.topActivity?.packageName
                        ?: prevTask.baseActivity?.packageName ?: ""
                    if (prevPackage.isNotBlank()) {
                        prevPackageName  = prevPackage
                        prevAppName      = getSafeAppLabel(prevPackage)
                        prevActivityName = prevTask.topActivity?.className
                            ?.substringAfterLast('.')
                            ?.takeIf { it.isNotBlank() }
                        prevWindowLabel  = prevTask.taskDescription?.label
                            ?.takeIf { it.isNotBlank() }
                            ?: prevTask.topActivity?.let { component ->
                                try {
                                    packageManager.getActivityInfo(component, 0)
                                        .loadLabel(packageManager)
                                        .toString()
                                        .takeIf { it.isNotBlank() }
                                } catch (e: Exception) { null }
                            }
                    }
                }
            
                android.util.Log.d(
                    "ClipboardService",
                    "Source: $topPackage (user=$taskUserId, $appLabel) | window: $windowLabel | activity: $activityName | prev: $prevPackageName ($prevAppName)"
                )
            
                return ClipSource(
                    appName          = appLabel,
                    packageName      = topPackage,
                    userId           = taskUserId,
                    windowLabel      = windowLabel,
                    activityName     = activityName,
                    prevAppName      = prevAppName,
                    prevPackageName  = prevPackageName,
                    prevWindowLabel  = prevWindowLabel,
                    prevActivityName = prevActivityName
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("ClipboardService", "getTasks failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    
        if (confirmedPackage != null) {
            val label = getSafeAppLabel(confirmedPackage)
            android.util.Log.d("ClipboardService", "Enrichment unavailable, using confirmed package: $confirmedPackage")
            return ClipSource(appName = label, packageName = confirmedPackage, userId = currentForegroundUserId)
        }
    
        android.util.Log.d("ClipboardService", "Source unknown, defaulting to System")
        return ClipSource(appName = "System", packageName = "android", userId = currentForegroundUserId)
    }
    
    /**
    * Returns true if the package belongs to our app, a launcher, or core system,
    * so we never tag a clip with our own service or the homescreen.
    */
    private fun isSelfOrSystem(pkg: String): Boolean {
        return pkg == packageName
            || pkg == "android"
            || pkg.startsWith("com.android.systemui")
            || pkg.startsWith("com.android.launcher")
            || pkg.startsWith("com.google.android.apps.nexuslauncher")
            || pkg.startsWith("com.miui.home")
            || pkg.startsWith("com.sec.android.app.launcher")
            || pkg.contains("launcher3", ignoreCase = true)
    }

    private fun getSafeAppLabel(pkg: String): String {
        val magicFlags = 0x00000400 or 0x00002000 // MATCH_ANY_USER | MATCH_UNINSTALLED_PACKAGES
    
        // Strategy 1: Standard PackageManager (Fastest, works for most user-space apps)
        try {
            val appInfo = packageManager.getApplicationInfo(pkg, magicFlags)
            val label = packageManager.getApplicationLabel(appInfo).toString()
            if (label.isNotBlank()) return label
        } catch (e: Exception) {
            android.util.Log.w("ClipboardService", "Standard PM failed for $pkg: ${e.message}")
        }
    
        // Strategy 2: Shizuku Fallback (Uses Shell UID to bypass cross-user/system restrictions)
        if (isShizukuAvailable()) {
            try {
                val label = getLabelViaShizuku(pkg, magicFlags)
                if (!label.isNullOrBlank()) {
                    android.util.Log.i("ClipboardService", "[SUCCESS] Shizuku retrieved label for $pkg")
                    return label
                }
            } catch (e: Exception) {
                android.util.Log.w("ClipboardService", "Shizuku fallback failed for $pkg: ${e.message}")
            }
        }
    
        // Strategy 3: Final Fallback (String parsing for "com.android.settings" -> "Settings")
        return pkg.substringAfterLast(".")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun getLabelViaShizuku(pkg: String, flags: Int): String? {
        // If our service's iPackageManager is null/dead, try to restore it
        if (iPackageManager == null || !isBinderAlive()) {
            setupShizukuServices()
        }
    
        val pm = iPackageManager ?: return null
    
        return try {
            /**
            * Find the getApplicationInfo method. We use 'find' which returns a nullable Method?
            */
            val getAppInfoMethod = pm.javaClass.methods.find { it.name == "getApplicationInfo" }
            
            // Use .let to safely handle the nullable 'getAppInfoMethod'
            getAppInfoMethod?.let { method ->
                val appInfo = if (method.parameterTypes.getOrNull(1) == Long::class.javaPrimitiveType) {
                    method.invoke(pm, pkg, flags.toLong(), 0)
                } else {
                    method.invoke(pm, pkg, flags, 0)
                } as? android.content.pm.ApplicationInfo
    
                appInfo?.let { packageManager.getApplicationLabel(it).toString() }
            }
        } catch (e: Exception) {
            android.util.Log.e("ClipboardService", "getLabelViaShizuku reflection failed", e)
            null
        }
    }

    // region 5. Foreground UI & Notifications
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Clipboard Monitor", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(clipCount: Int = 0): Notification {
    val intent = Intent(this, MainActivity::class.java)
    val pendingIntent = PendingIntent.getActivity(
        this, 0, intent, PendingIntent.FLAG_IMMUTABLE
    )

    val stopIntent = Intent(this, ClipboardService::class.java).apply {
        action = ACTION_STOP_MONITORING
    }
    val stopPendingIntent = PendingIntent.getService(
        this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
    )

    val contentTitle = when {
        !isMonitoringActive -> "Monitoring Stopped"
        else -> "Monitoring Clipboard"
    }
    val contentText = when {
        !isMonitoringActive -> "⚠ Shizuku disconnected"
        clipCount == 0 -> "Monitoring system clipboard..."
        else -> "$clipCount clip${if (clipCount == 1) "" else "s"} saved"
    }

    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(contentTitle)
        .setContentText(contentText)
        .setSmallIcon(R.drawable.ic_clipboard)
        .setContentIntent(pendingIntent)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setShowWhen(false)
        .addAction(android.R.drawable.ic_delete, "Turn Off", stopPendingIntent)
        .build()
}
    
    private fun updateNotification() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = serviceScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(500) // coalesce rapid clip saves into one update
            val count = AppDatabase.getDatabase(applicationContext)
                .clipboardDao().getTotalClipCount()
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(1, createNotification(count))
        }
    }

    // region 6. File System, Data Storage & Preferences
    private fun loadAutomationRules(): List<nocom.rian.clipboardmanager.db.AutomationRule> {
        cachedAutomationRules?.let { return it }
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        return nocom.rian.clipboardmanager.db.loadRules(prefs)
            .also { cachedAutomationRules = it }
    }
    
    private fun loadLogPrefs(): LogPrefs {
        cachedLogPrefs?.let { return it }
        val p = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        return LogPrefs(
            logAppName     = p.getBoolean(SettingsActivity.KEY_LOG_FIELD_APP_NAME,      true),
            logPackage     = p.getBoolean(SettingsActivity.KEY_LOG_FIELD_PACKAGE,       true),
            logWindow      = p.getBoolean(SettingsActivity.KEY_LOG_FIELD_WINDOW,        true),
            logActivity    = p.getBoolean(SettingsActivity.KEY_LOG_FIELD_ACTIVITY,      true),
            logPrevAppName = p.getBoolean(SettingsActivity.KEY_LOG_FIELD_PREV_APP_NAME, true),
            logPrevPackage = p.getBoolean(SettingsActivity.KEY_LOG_FIELD_PREV_PACKAGE,  true),
            logPrevWindow  = p.getBoolean(SettingsActivity.KEY_LOG_FIELD_PREV_WINDOW,   true),
            logPrevActivity= p.getBoolean(SettingsActivity.KEY_LOG_FIELD_PREV_ACTIVITY, true),
            syncSensitiveFromClipboard = p.getBoolean(SettingsActivity.KEY_SYNC_SENSITIVE_FROM_CLIPBOARD, false)
        ).also { cachedLogPrefs = it }
    }

    private fun loadExcludedPackages(): Set<String> {
        cachedExcludedPackages?.let { return it }
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(SettingsActivity.KEY_EXCLUDED_PACKAGES, null) ?: return emptySet<String>().also { cachedExcludedPackages = it }
        return try {
            val array = org.json.JSONArray(json)
            buildSet { for (i in 0 until array.length()) add(array.getString(i)) }
        } catch (e: Exception) { emptySet() }.also { cachedExcludedPackages = it }
    }
    
    private fun loadSensitivePackages(): Set<String> {
        cachedSensitivePackages?.let { return it }
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(SettingsActivity.KEY_SENSITIVE_PACKAGES, null) ?: return emptySet<String>().also { cachedSensitivePackages = it }
        return try {
            val array = org.json.JSONArray(json)
            buildSet { for (i in 0 until array.length()) add(array.getString(i)) }
        } catch (e: Exception) { emptySet() }.also { cachedSensitivePackages = it }
    }

        /**
    * Runs each enabled automation rule against the just-captured clip.
    * Rules are evaluated in order; all matching rules are applied so that
    * multiple rules can each contribute a different action.
    *
    * @param item          The ClipboardItem about to be inserted.
    * @param text          The raw text content (null for image clips).
    * @param sourcePackage The source app package name.
    */
    private fun applyAutomationRules(
        item: ClipboardItem,
        text: String?,
        sourcePackage: String?,
        sourceAppName: String?,
        sourceWindowLabel: String?,
        preSourceAppName: String?,
        preSourceWindowLabel: String?
    ): ClipboardItem {
        val rules = loadAutomationRules().filter { it.enabled }
        if (rules.isEmpty()) return item

        var result = item
        val isImage = item.mimeType?.startsWith("image/") == true

        for (rule in rules) {
            // ── Condition: content type ────────────────────────────────────────
            val typeMatch = when (rule.conditionContentType) {
                "text"  -> !isImage
                "image" -> isImage
                else    -> true
            }
            if (!typeMatch) continue

            // ── Condition: app name substring ──────────────────────────────────
            if (rule.conditionAppName.isNotBlank()) {
                if (sourceAppName == null || !sourceAppName.contains(rule.conditionAppName, ignoreCase = true)) continue
            }

            // ── Condition: source package substring ────────────────────────────
            if (rule.conditionSourcePackage.isNotBlank()) {
                if (sourcePackage == null || !sourcePackage.contains(rule.conditionSourcePackage, ignoreCase = true)) continue
            }

            // ── Condition: previous app name substring ──────────────────────────────────
            if (rule.conditionPreAppName.isNotBlank()) {
                if (preSourceAppName == null || !preSourceAppName.contains(rule.conditionPreAppName, ignoreCase = true)) continue
            }

            // ── Condition: source window label substring ────────────────────────────
            if (rule.conditionSourceWindowLabel.isNotBlank()) {
                if (sourceWindowLabel == null || !sourceWindowLabel.contains(rule.conditionSourceWindowLabel, ignoreCase = true)) continue
            }
            
            // ── Condition: previous source window label substring ────────────────────────────
            if (rule.conditionPreSourceWindowLabel.isNotBlank()) {
                if (preSourceWindowLabel == null || !preSourceWindowLabel.contains(rule.conditionPreSourceWindowLabel, ignoreCase = true)) continue
            }
            
            // ── Condition: content regex (text clips only) ─────────────────────
            if (rule.conditionContentPattern.isNotBlank()) {
                if (text == null) continue
                val matched = try {
                    Regex(rule.conditionContentPattern).containsMatchIn(text)
                } catch (e: Exception) {
                    android.util.Log.w("ClipboardService", "Bad regex in rule '${rule.name}': ${e.message}")
                    false
                }
                if (!matched) continue
            }

            // ── All conditions passed → apply actions ──────────────────────────
            android.util.Log.d("ClipboardService", "Automation rule matched: '${rule.name}'")
            if (rule.actionLabel.isNotBlank()) result = result.copy(label = rule.actionLabel)
            if (rule.actionSensitive != -1)    result = result.copy(isSensitive = rule.actionSensitive == 1)
            if (rule.actionFavorite != -1)     result = result.copy(isFavorite  = rule.actionFavorite == 1)
            if (rule.actionPinned != -1)       result = result.copy(isPinned    = rule.actionPinned    == 1)
        }
        return result
    }


    private fun saveImageFromUri(uri: android.net.Uri, mimeType: String, timestamp: Long): ImageSaveResult? {
        return try {
            // Resolve extension dynamically using Android's MimeTypeMap, falling back to "png" if unresolved
            var ext = android.webkit.MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mimeType)
                ?.lowercase() ?: "png"
    
            // Normalize jpeg to standard jpg extension if preferred
            if (ext == "jpeg") ext = "jpg"
    
            val dir = java.io.File(getExternalFilesDir(null), "clipboard_images").also { it.mkdirs() }
            val file   = java.io.File(dir, "img_$timestamp.$ext")
            val digest = java.security.MessageDigest.getInstance("MD5")
    
            // Strategy 1: direct ContentResolver — works for MediaStore and other accessible URIs.
            val stream: java.io.InputStream? = try {
                contentResolver.openInputStream(uri)
            } catch (e: SecurityException) {
                android.util.Log.d("ClipboardService", "Direct access denied — falling back to IContentProvider via Shizuku")
                null
            }
    
            // Strategy 2: IContentProvider via ShizukuBinderWrapper (shell identity).
            val input = stream ?: openUriViaIContentProvider(uri) ?: run {
                android.util.Log.e("ClipboardService", "All open strategies failed for URI=$uri")
                return null
            }
    
            input.use { raw ->
                java.security.DigestInputStream(raw, digest).use { hashed ->
                    file.outputStream().use { out -> hashed.copyTo(out) }
                }
            }
    
            if (file.exists() && file.length() > 0) {
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                android.util.Log.d("ClipboardService", "Image saved: hash=$hash path=${file.absolutePath}")
                ImageSaveResult(file.absolutePath, hash)
            } else {
                android.util.Log.e("ClipboardService", "File empty after write — URI=$uri")
                file.delete()
                null
            }
    
        } catch (e: Exception) {
            android.util.Log.e("ClipboardService", "saveImageFromUri failed", e)
            null
        }
    }
    
    private fun saveMediaFromUri(uri: android.net.Uri, mimeType: String, timestamp: Long): ImageSaveResult? {
        return try {
            // 1. Dynamically resolve the extension using the system MimeTypeMap
            var ext = android.webkit.MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mimeType)
                ?.lowercase()
    
            // 2. Fallback: If MimeTypeMap returns null, try parsing the subtype (e.g., "image/heic" -> "heic")
            if (ext.isNullOrBlank()) {
                ext = mimeType.substringAfter('/', "").substringBefore(';').trim().lowercase()
            }
    
            // 3. Absolute Fallback: If it's still empty or generic, use a default binary/stream extension
            if (ext.isBlank() || ext == "*" || ext == "octet-stream") {
                ext = when {
                    mimeType.startsWith("image/") -> "png"
                    mimeType.startsWith("video/") -> "mp4"
                    mimeType.startsWith("audio/") -> "mp3"
                    else -> "bin"
                }
            }
    
            // Sanitize typical edge cases (convert jpeg back to standard jpg if preferred)
            if (ext == "jpeg") ext = "jpg"
    
            // Changed directory name to 'clipboard_media' since we support more than images now
            val dir = java.io.File(getExternalFilesDir(null), "clipboard_media").also { it.mkdirs() }
            val file   = java.io.File(dir, "file_$timestamp.$ext")
            val digest = java.security.MessageDigest.getInstance("MD5")
    
            // Strategy 1: direct ContentResolver — works for MediaStore and other accessible URIs.
            val stream: java.io.InputStream? = try {
                contentResolver.openInputStream(uri)
            } catch (e: SecurityException) {
                android.util.Log.d("ClipboardService", "Direct access denied — falling back to IContentProvider via Shizuku")
                null
            }
    
            // Strategy 2: IContentProvider via ShizukuBinderWrapper (shell identity).
            val input = stream ?: openUriViaIContentProvider(uri) ?: run {
                android.util.Log.e("ClipboardService", "All open strategies failed for URI=$uri")
                return null
            }
    
            input.use { raw ->
                java.security.DigestInputStream(raw, digest).use { hashed ->
                    file.outputStream().use { out -> hashed.copyTo(out) }
                }
            }
    
            if (file.exists() && file.length() > 0) {
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                android.util.Log.d("ClipboardService", "File saved successfully: hash=$hash path=${file.absolutePath}")
                ImageSaveResult(file.absolutePath, hash)
            } else {
                android.util.Log.e("ClipboardService", "File empty after write — URI=$uri")
                file.delete()
                null
            }
    
        } catch (e: Exception) {
            android.util.Log.e("ClipboardService", "saveImageFromUri failed", e)
            null
        }
    }
    
    private fun openUriViaIContentProvider(uri: android.net.Uri): java.io.InputStream? {
        return try {
            val cmd = arrayOf("content", "read", "--uri", uri.toString())
            
            // 1. Reflect into Shizuku to access the "private" newProcess
            val shizukuClass = rikka.shizuku.Shizuku::class.java
            val newProcessMethod = shizukuClass.getDeclaredMethod(
                "newProcess", 
                Array<String>::class.java, 
                Array<String>::class.java, 
                String::class.java
            )
            newProcessMethod.isAccessible = true
    
            // 2. Invoke the process (Acting as Shell)
            val remoteProcess = newProcessMethod.invoke(null, cmd, null, null) as java.lang.Process
            val stdout = remoteProcess.inputStream
    
            // 3. Check for data
            val bis = java.io.BufferedInputStream(stdout)
            bis.mark(10)
            if (bis.read() == -1) {
                android.util.Log.e("ClipboardService", "Shell 'content read' returned no data")
                remoteProcess.destroy()
                return null
            }
            bis.reset()
            
            // Note: The process stays alive as long as you are reading the stream.
            // It will be cleaned up when you close the stream in saveImageFromUri.
            return bis
    
        } catch (e: Exception) {
            android.util.Log.e("ClipboardService", "Shizuku reflection process failed", e)
            null
        }
    }

    // Replace deleteImageFileIfNeeded entirely:
    private fun deleteImageFileIfNeeded(item: ClipboardItem) {
        if (item.mimeType?.startsWith("image/") == true) {
            val file = java.io.File(item.filePath ?: return)
            if (file.exists()) {
                val deleted = file.delete()
                android.util.Log.d("ClipboardService", "Image file deleted=$deleted: ${item.filePath}")
            }
        }
    }
    
    private suspend fun enforcePolicies(repository: ClipboardRepository) {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val db = AppDatabase.getDatabase(applicationContext)
    
        val cap = prefs.getInt(SettingsActivity.KEY_CLIP_CAP, 0)
        if (cap > 0) {
            repository.enforceClipCapWithImages(
                maxClips = cap,
                onEachDeleted = { item -> deleteImageFileIfNeeded(item) },
                database = db
            )
            android.util.Log.d("ClipboardService", "Clip cap enforced: keeping newest $cap clips (Images purged safely)")
        }
    
        val days = prefs.getInt(SettingsActivity.KEY_AUTO_DELETE_DAYS, 0)
        if (days > 0) {
            val cutoff = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000)
            repository.deleteOlderThanWithImages(
                cutoffMs = cutoff,
                onEachDeleted = { item -> deleteImageFileIfNeeded(item) },
                database = db
            )
            android.util.Log.d("ClipboardService", "Auto-delete enforced: clips older than $days days removed along with images")
        }
    }
    

    // region 7. Low-Level Reflection Utilities
    private fun invokeDynamic(
        service: Any?, 
        methodName: String, 
        vararg preferredArgs: Any?
    ): Any? {
        if (service == null) return null
    
        val methods = service.javaClass.methods
        val targetMethod = methods.find { it.name == methodName } ?: run {
            android.util.Log.e("DynamicInvoker", "Method $methodName not found")
            return null
        }
    
        val expectedCount = targetMethod.parameterTypes.size
        
        // Create an array of arguments that matches exactly what the system wants
        val finalArgs = arrayOfNulls<Any>(expectedCount)
        
        for (i in 0 until expectedCount) {
            if (i < preferredArgs.size) {
                // Fill with your provided arguments (listener, package name, etc.)
                finalArgs[i] = preferredArgs[i]
            } else {
                // If the system wants more params than you gave (like userId or deviceId),
                // we default to 0 or null.
                val paramType = targetMethod.parameterTypes[i]
                finalArgs[i] = when {
                    paramType == Int::class.javaPrimitiveType -> 0
                    paramType == Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }
        }
    
        return try {
            targetMethod.invoke(service, *finalArgs)
        } catch (e: Exception) {
            android.util.Log.e("DynamicInvoker", "Failed to invoke $methodName", e)
            null
        }
    }

    /**
    * Dynamically invokes an IClipboard method, auto-filling the standard
    * Android clipboard parameters (callingPackage, attributionTag, userId, deviceId)
    * regardless of which subset the current OS version's signature actually contains.
    *
    * Rules per parameter type:
    * String  → 1st occurrence = "com.android.shell", 2nd = "clipboard"
    * Int     → always 0  (userId / deviceId)
    * Anything else → pulled in-order from [specialArgs]
    * (e.g. IOnPrimaryClipChangedListener, ClipData)
    *
    * @param methodName  Exact name of the IClipboard method.
    * @param specialArgs Non-standard args (listener, ClipData, …) in the order
    * they appear in the target method signature.
    * @return The method's return value, or null on any failure.
    */
    private fun invokeClipboard(methodName: String, vararg specialArgs: Any?): Any? {
        val service = clipboardService ?: run {
            android.util.Log.w("ClipboardService", "invokeClipboard($methodName): service is null")
            return null
        }
    
        val method = service.javaClass.methods.find { it.name == methodName } ?: run {
            android.util.Log.w("ClipboardService", "invokeClipboard: method '$methodName' not found")
            return null
        }
    
        val builtArgs  = mutableListOf<Any?>()
        val specialQueue = ArrayDeque(specialArgs.toList())
        var stringCount = 0
    
        for (type in method.parameterTypes) {
            when {
                type == String::class.java -> {
                    builtArgs.add(if (stringCount++ == 0) "com.android.shell" else "clipboard")
                }
                type == Int::class.javaPrimitiveType || type == Integer::class.java -> {
                    builtArgs.add(0)
                }
                else -> {
                    // Pull the first queued arg whose runtime type is assignable to this param
                    val match = specialQueue.firstOrNull { arg ->
                        arg == null || type.isAssignableFrom(arg.javaClass)
                    }
                    specialQueue.remove(match)
                    builtArgs.add(match)
                }
            }
        }
    
        return try {
            method.invoke(service, *builtArgs.toTypedArray())
        } catch (e: Exception) {
            android.util.Log.e("ClipboardService", "invokeClipboard($methodName) failed", e)
            null
        }
    }

    /**
    * Calls IActivityManager.getContentProviderExternal() through the Shizuku-wrapped
    * activityManager binder (shell uid 2000), so the system grants access to
    * non-exported FileProviders that gave their URI grant to shell.
    */
    private fun getIContentProviderBinder(authority: String): IBinder? {
        val amBinder = activityManager?.asBinder() ?: return null
    
        // Resolve the transaction code via reflection (HiddenApiBypass is already enabled)
        val transCode = try {
            Class.forName("android.app.IActivityManager\$Stub")
                .getDeclaredField("TRANSACTION_getContentProviderExternal")
                .apply { isAccessible = true }
                .getInt(null)
        } catch (e: Exception) {
            android.util.Log.e("ClipboardService", "TRANSACTION_getContentProviderExternal not found", e)
            return null
        }
    
        val data  = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken("android.app.IActivityManager")
            data.writeString(authority)   // name
            data.writeInt(0)              // userId (0 = primary user)
            data.writeStrongBinder(null)  // token
            data.writeString(authority)   // tag
    
            // This transact routes through ShizukuBinderWrapper → executes as shell uid 2000
            amBinder.transact(transCode, data, reply, 0)
            reply.readException()
    
            if (reply.readInt() == 0) {
                android.util.Log.e("ClipboardService", "getContentProviderExternal: null holder for $authority")
                return null
            }
    
            // Parse ContentProviderHolder from reply Parcel:
            //   Field 1: ProviderInfo          → skip by consuming it
            //   Field 2: IContentProvider binder
            //            API 29-30: writeInt(hasProvider=1) + writeStrongBinder
            //            API 31+:   writeStrongBinder directly (writeStrongInterface)
            //   Field 3: IBinder connection    → not needed
            //   Field 4: int noReleaseNeeded   → not needed
    
            android.content.pm.ProviderInfo.CREATOR.createFromParcel(reply) // consume ProviderInfo
    
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                reply.readStrongBinder()
            } else {
                if (reply.readInt() != 0) reply.readStrongBinder() else null
            }
    
        } catch (e: Exception) {
            android.util.Log.e("ClipboardService", "getIContentProviderBinder failed for $authority", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
