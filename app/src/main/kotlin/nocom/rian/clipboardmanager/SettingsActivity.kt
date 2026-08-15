package nocom.rian.clipboardmanager
 
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nocom.rian.clipboardmanager.databinding.ActivitySettingsBinding
import nocom.rian.clipboardmanager.db.AppDatabase
import nocom.rian.clipboardmanager.db.BackupManager
import nocom.rian.clipboardmanager.db.ClipboardRepository
import android.content.ComponentName
import android.service.quicksettings.TileService
import nocom.rian.clipboardmanager.service.ClipboardTileService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.Any
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

 
private data class AppEntry(
    val displayLabel: String,
    val packageName: String,
    val userId: Int
)
 
class SettingsActivity : AppCompatActivity() {
 
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences
    private var prefetchedApps: List<AppEntry>? = null
 
    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == KEY_MONITORING_ENABLED) {
            val newValue = sharedPreferences.getBoolean(KEY_MONITORING_ENABLED, true)
            
            // 1. Update the activity UI on the main thread if it changed from the tile
            runOnUiThread {
                // Replace 'switchMonitoring' with the actual ID of your toggle switch in activity_settings.xml
                if (binding.switchMonitoring.isChecked != newValue) {
                    binding.switchMonitoring.isChecked = newValue
                }
            }
 
            // 2. Request tile update (keeps things synced if triggered from activity)
            try {
                TileService.requestListeningState(
                    this,
                    ComponentName(this, ClipboardTileService::class.java)
                )
            } catch (e: Exception) {
                android.util.Log.e("SettingsActivity", "Failed to request tile update", e)
            }
        }
    }
    
    // ── SAF launchers ─────────────────────────────────────────────────────────
 
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) performExport(uri)
    }
    
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) performImport(uri)
    }
 
    // ── Lifecycle ─────────────────────────────────────────────────────────────
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
    
        setSupportActionBar(binding.settingsToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
        loadCurrentValues()
        setupSliderListeners()
        setupExpandableCards()
        setupBackupButtons()
        setupDisplayFieldToggles()
        setupLoggingFieldToggles()
        setupSensitiveApps()
        setupExcludedApps()
    
        // Pre-fetch app list so there's no delay when the picker dialog opens
        lifecycleScope.launch(Dispatchers.IO) {
            prefetchedApps = queryAppsAcrossUsers(includeSystemApps = false)
        }
    }
 
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
    
    override fun onPause() {
        super.onPause()
        // Unregister when it goes to the background to save lifecycle resources and prevent leaks
        prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
    }
    
    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
        
        // Refresh UI state dynamically in case Shizuku status changed
        checkAndApplyMonitoringStatus()
    }

    
    override fun onDestroy() {
        super.onDestroy()
        // Unregister to avoid memory leaks
        prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
    }
    
    // ── UI Initialization & Preference Loading ────────────────────────────────
 
    private fun loadCurrentValues() {
        val days = prefs.getInt(KEY_AUTO_DELETE_DAYS, 0)
        binding.sliderAutoDelete.value = days.toFloat()
        binding.tvAutoDeleteValue.text = formatDays(days)
    
        val cap = prefs.getInt(KEY_CLIP_CAP, 0)
        binding.sliderClipCap.value = cap.toFloat()
        binding.tvClipCapValue.text = formatCap(cap)
    
        checkAndApplyMonitoringStatus()
    
        binding.switchManualEntry.isChecked = prefs.getBoolean(KEY_SHOW_MANUAL_ENTRY, false)
        binding.switchSyncSensitive.isChecked = prefs.getBoolean(KEY_SYNC_SENSITIVE_FROM_CLIPBOARD, false)
        
        // Display toggles — all on by default
        binding.switchShowAppName.isChecked     = prefs.getBoolean(KEY_SHOW_FIELD_APP_NAME,      true)
        binding.switchShowPackage.isChecked     = prefs.getBoolean(KEY_SHOW_FIELD_PACKAGE,       true)
        binding.switchShowWindow.isChecked      = prefs.getBoolean(KEY_SHOW_FIELD_WINDOW,        true)
        binding.switchShowActivity.isChecked    = prefs.getBoolean(KEY_SHOW_FIELD_ACTIVITY,      true)
        binding.switchShowLabel.isChecked       = prefs.getBoolean(KEY_SHOW_FIELD_LABEL,         true)
        binding.switchShowTech.isChecked        = prefs.getBoolean(KEY_SHOW_FIELD_TECH,          true)
        binding.switchShowPrevAppName.isChecked = prefs.getBoolean(KEY_SHOW_FIELD_PREV_APP_NAME, true)
        binding.switchShowPrevPackage.isChecked = prefs.getBoolean(KEY_SHOW_FIELD_PREV_PACKAGE,  true)
        binding.switchShowPrevWindow.isChecked  = prefs.getBoolean(KEY_SHOW_FIELD_PREV_WINDOW,   true)
        binding.switchShowPrevActivity.isChecked= prefs.getBoolean(KEY_SHOW_FIELD_PREV_ACTIVITY, true)
    
        // Logging toggles — all on by default
        binding.switchLogAppName.isChecked     = prefs.getBoolean(KEY_LOG_FIELD_APP_NAME,      true)
        binding.switchLogPackage.isChecked     = prefs.getBoolean(KEY_LOG_FIELD_PACKAGE,       true)
        binding.switchLogWindow.isChecked      = prefs.getBoolean(KEY_LOG_FIELD_WINDOW,        true)
        binding.switchLogActivity.isChecked    = prefs.getBoolean(KEY_LOG_FIELD_ACTIVITY,      true)
        binding.switchLogPrevAppName.isChecked = prefs.getBoolean(KEY_LOG_FIELD_PREV_APP_NAME, true)
        binding.switchLogPrevPackage.isChecked = prefs.getBoolean(KEY_LOG_FIELD_PREV_PACKAGE,  true)
        binding.switchLogPrevWindow.isChecked  = prefs.getBoolean(KEY_LOG_FIELD_PREV_WINDOW,   true)
        binding.switchLogPrevActivity.isChecked= prefs.getBoolean(KEY_LOG_FIELD_PREV_ACTIVITY, true)
    }
    
    private fun checkAndApplyMonitoringStatus() {
        val isShizukuAlive = try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) { false }

        val prefEnabled = prefs.getBoolean(KEY_MONITORING_ENABLED, true)
        
        // 1. Always set the visual switch to the user's actual saved preference.
        // (Because we use '!buttonView.isPressed' in the listener, this programmatic 
        // change won't accidentally trigger a service restart).
        binding.switchMonitoring.isChecked = prefEnabled

        if (!isShizukuAlive) {
            // 2. Lock the switch so it can't be interacted with directly
            binding.switchMonitoring.isEnabled = false
            
            binding.iconMonitoringWarning.visibility = View.VISIBLE
            binding.tvMonitoringStatus.text = "⚠ Shizuku disconnected or permission denied. Tap to fix."
            binding.tvMonitoringStatus.setTextColor(getColor(R.color.md_theme_error))
        } else {
            // 3. Restore full functionality once Shizuku is back
            binding.switchMonitoring.isEnabled = true
            binding.iconMonitoringWarning.visibility = View.GONE
            
            // Restore normal text color
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true)
            binding.tvMonitoringStatus.setTextColor(typedValue.data)
            
            if (prefEnabled && nocom.rian.clipboardmanager.service.ClipboardService.isRunning) {
                binding.tvMonitoringStatus.text = "Active — capturing clipboard changes"
            } else {
                binding.tvMonitoringStatus.text = "Paused — service is stopped"
            }
        }
    }

    
    // ── Interaction Listeners (Sliders & Configuration Switches) ──────────────
 
    private fun setupSliderListeners() {
        binding.switchMonitoring.setOnCheckedChangeListener { buttonView, isChecked ->
            // 1. Prevent Android's view state restoration from triggering this programmatically
            if (!buttonView.isPressed) return@setOnCheckedChangeListener
        
            // 2. Use .commit() to write to disk instantly before the app can be killed
            prefs.edit().putBoolean(KEY_MONITORING_ENABLED, isChecked).commit()
            
            checkAndApplyMonitoringStatus() // Updated line
            
            val intent = Intent(this, nocom.rian.clipboardmanager.service.ClipboardService::class.java)
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } else {
                stopService(intent)
            }
        }

        // Add this to make the whole row clickable to fix Shizuku issues
        binding.monitoringContainer.setOnClickListener {
            val isShizukuAlive = try {
                Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) { false }
            
            if (!isShizukuAlive) {
                startActivity(Intent(this, PermissionActivity::class.java))
            } else {
                binding.switchMonitoring.toggle()
            }
        }

        binding.switchFloatingOverlay.apply {
            isChecked = prefs.getBoolean(KEY_FLOATING_OVERLAY_ENABLED, true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(KEY_FLOATING_OVERLAY_ENABLED, isChecked).apply()
                
                // Fire local control broadcast directly targeting the ClipboardService lifecycle loop
                val intent = Intent("nocom.rian.ACTION_TOGGLE_OVERLAY").apply {
                    putExtra("enabled", isChecked)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
        }

        binding.tvOverlayIntentAction.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Overlay Action Intent", binding.tvOverlayIntentAction.text))
            Toast.makeText(this, "Action copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestOverlayIntent.setOnClickListener {
            val intent = Intent("nocom.rian.clipboardmanager.ACTION_SHOW_OVERLAY").apply {
                setPackage(packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        }
 
        binding.sliderAutoDelete.addOnChangeListener { _, value, _ ->
            val days = value.toInt()
            prefs.edit().putInt(KEY_AUTO_DELETE_DAYS, days).apply()
            binding.tvAutoDeleteValue.text = formatDays(days)
        }
 
        binding.sliderClipCap.addOnChangeListener { _, value, _ ->
            val cap = value.toInt()
            prefs.edit().putInt(KEY_CLIP_CAP, cap).apply()
            binding.tvClipCapValue.text = formatCap(cap)
        }
 
        binding.switchManualEntry.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SHOW_MANUAL_ENTRY, isChecked).apply()
        }
        
        binding.switchSyncSensitive.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SYNC_SENSITIVE_FROM_CLIPBOARD, isChecked).apply()
        }
    }
    
    private fun setupDisplayFieldToggles() {
        binding.switchShowAppName.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_SHOW_FIELD_APP_NAME, checked).apply()
        }
        binding.switchShowPackage.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_SHOW_FIELD_PACKAGE, checked).apply()
        }
        binding.switchShowWindow.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_SHOW_FIELD_WINDOW, checked).apply()
        }
        binding.switchShowActivity.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_SHOW_FIELD_ACTIVITY, checked).apply()
        }
        binding.switchShowLabel.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_SHOW_FIELD_LABEL, checked).apply()
        }
        binding.switchShowTech.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_SHOW_FIELD_TECH, checked).apply()
        }
        binding.switchShowPrevAppName.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_SHOW_FIELD_PREV_APP_NAME, checked).apply()
        }
        binding.switchShowPrevPackage.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_SHOW_FIELD_PREV_PACKAGE, checked).apply()
        }
        binding.switchShowPrevWindow.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_SHOW_FIELD_PREV_WINDOW, checked).apply()
        }
        binding.switchShowPrevActivity.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_SHOW_FIELD_PREV_ACTIVITY, checked).apply()
        }
    }
    
    private fun setupLoggingFieldToggles() {
        binding.switchLogAppName.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_LOG_FIELD_APP_NAME, checked).apply()
        }
        binding.switchLogPackage.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_LOG_FIELD_PACKAGE, checked).apply()
        }
        binding.switchLogWindow.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_LOG_FIELD_WINDOW, checked).apply()
        }
        binding.switchLogActivity.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_LOG_FIELD_ACTIVITY, checked).apply()
        }
        binding.switchLogPrevAppName.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_LOG_FIELD_PREV_APP_NAME, checked).apply()
        }
        binding.switchLogPrevPackage.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_LOG_FIELD_PREV_PACKAGE, checked).apply()
        }
        binding.switchLogPrevWindow.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_LOG_FIELD_PREV_WINDOW, checked).apply()
        }
        binding.switchLogPrevActivity.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_LOG_FIELD_PREV_ACTIVITY, checked).apply()
        }
    }
 
    // ── Expandable UI Cards ───────────────────────────────────────────────────
 
    private fun setupExpandableCards() {
        setupExpandable(binding.headerAutoDelete,    binding.contentAutoDelete,    binding.arrowAutoDelete)
        setupExpandable(binding.headerClipCap,       binding.contentClipCap,       binding.arrowClipCap)
        setupExpandable(binding.headerDisplayFields, binding.contentDisplayFields, binding.arrowDisplayFields)
        setupExpandable(binding.headerLoggingFields, binding.contentLoggingFields, binding.arrowLoggingFields)
    }
    
    private fun setupExpandable(
        header: android.view.View,
        content: android.view.View,
        arrow: android.widget.ImageView
    ) {
        header.setOnClickListener {
            val expanding = content.visibility != View.VISIBLE
            content.visibility = if (expanding) View.VISIBLE else View.GONE
            arrow.animate()
                .rotation(if (expanding) 180f else 0f)
                .setDuration(200)
                .start()
        }
    }
 
    // ── Multi-User Android Package Resolution ─────────────────────────────────
 
    private fun queryAppsAcrossUsers(includeSystemApps: Boolean = false): List<AppEntry> {
        val userManager = getSystemService(Context.USER_SERVICE) as UserManager
        val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as android.content.pm.LauncherApps
        val appMap = LinkedHashMap<String, AppEntry>()
        val profiles = userManager.userProfiles
    
        for (userHandle in profiles) {
            val userId = try {
                val getIdentifier = userHandle.javaClass.getDeclaredMethod("getIdentifier")
                getIdentifier.invoke(userHandle) as Int
            } catch (e: Exception) { userHandle.hashCode() }
    
            val userLabel = getUserLabel(userId)
    
            // Always fetch launcher (non-system) apps per profile — best quality labels
            try {
                val activityList = launcherApps.getActivityList(null, userHandle)
                for (activity in activityList) {
                    val pkg = activity.applicationInfo.packageName
                    if (pkg == packageName) continue
                    val baseLabel = activity.label.toString()
                    val displayLabel = if (userLabel.isEmpty()) baseLabel else "$baseLabel ($userLabel)"
                    appMap["$pkg:$userId"] = AppEntry(displayLabel, pkg, userId)
                }
            } catch (e: Exception) {
                android.util.Log.e("SettingsActivity", "Failed to query apps for user $userId", e)
            }
        }
    
        // When system apps are requested, append any installed packages not already in the map
        if (includeSystemApps) {
            val myUserId = try {
                android.os.Process.myUserHandle().javaClass
                    .getDeclaredMethod("getIdentifier")
                    .invoke(android.os.Process.myUserHandle()) as Int
            } catch (e: Exception) { 0 }
    
            val myUserLabel = getUserLabel(myUserId)
    
            try {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0).forEach { appInfo ->
                    val pkg = appInfo.packageName
                    if (pkg == packageName) return@forEach
                    val key = "$pkg:$myUserId"
                    if (!appMap.containsKey(key)) {
                        val baseLabel = try {
                            packageManager.getApplicationLabel(appInfo).toString()
                        } catch (e: Exception) { pkg }
                        val displayLabel = if (myUserLabel.isEmpty()) baseLabel else "$baseLabel ($myUserLabel)"
                        appMap[key] = AppEntry(displayLabel, pkg, myUserId)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SettingsActivity", "Failed to query system apps", e)
            }
        }
    
        return appMap.values.sortedBy { it.displayLabel.lowercase() }
    }
 
    private fun getUserLabel(userId: Int): String {
        val userManager = getSystemService(android.os.UserManager::class.java)
        
        // Find out the ID of the profile this app instance is currently running in
        val myUserHandle = android.os.Process.myUserHandle()
        val myUserId = try {
            val getIdentifier = myUserHandle.javaClass.getDeclaredMethod("getIdentifier")
            getIdentifier.invoke(myUserHandle) as Int
        } catch (e: Exception) {
            0
        }
    
        // If the target app belongs to the current profile context, don't append a label
        if (userId == myUserId) return ""
        
        // If we are in a Work Profile and looking at User 0, explicitly call it Personal/Main
        if (userId == 0) return "Personal"
    
        return try {
            val getUserInfo = android.os.UserManager::class.java.getDeclaredMethod(
                "getUserInfo", java.lang.Integer.TYPE
            )
            val info = getUserInfo.invoke(userManager, userId) ?: return "User $userId"
                
            val infoAny = info
            val flags = infoAny.javaClass.getDeclaredField("flags").apply { isAccessible = true }.getInt(info)
            val name = (infoAny.javaClass.getDeclaredField("name").apply { isAccessible = true }.get(info) as? String).orEmpty()
    
            when {
                name.equals("Secure Folder", ignoreCase = true) -> "Secure Folder"
                (flags and 0x00000020) != 0 -> "Work $userId"
                (flags and 0x00000800) != 0 -> "Private $userId"
                (flags and 0x00001000) != 0 -> "Profile $userId"
                name.isNotBlank()           -> "$name $userId"
                else                        -> "User $userId"
            }
        } catch (e: Exception) {
            "User $userId"
        }
    }
 
    // ── App Picker Engine (Shared Dialog Building) ────────────────────────────
 
    private fun showAppPickerDialogInternal(
        dialogTitle: String,
        loadSelected: () -> Set<String>,
        onSave: (Set<String>) -> Unit,
        onClear: () -> Unit
    ) {
        lifecycleScope.launch {
            val initialApps = prefetchedApps
                ?: withContext(Dispatchers.IO) {
                    queryAppsAcrossUsers(includeSystemApps = false)
                }.also { prefetchedApps = it }
    
            val dp = resources.displayMetrics.density
            val listHeight = (resources.displayMetrics.heightPixels * 0.45).toInt()
            val selectedKeys = loadSelected().toMutableSet()
            var cachedApps = initialApps
            var currentFilteredApps = cachedApps
    
            if (cachedApps.isEmpty()) {
                Toast.makeText(this@SettingsActivity, "No apps found", Toast.LENGTH_SHORT).show()
                return@launch
            }
    
            fun AppEntry.packageKey() = "${packageName}:${userId}"
            fun AppEntry.isSelected() = selectedKeys.contains(packageKey()) ||
                    (userId == 0 && selectedKeys.contains(packageName))
    
            val root = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
    
            val searchEdit = android.widget.EditText(this@SettingsActivity).apply {
                hint = "Search apps…"
                isSingleLine = true
                setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    android.R.drawable.ic_menu_search, 0, 0, 0)
                compoundDrawablePadding = (8 * dp).toInt()
            }
            root.addView(searchEdit, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    
            val sysRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((16 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
                isClickable = true
                isFocusable = true
            }
            val sysLabel = android.widget.TextView(this@SettingsActivity).apply {
                text = "Show system apps"
                textSize = 14f
            }
            val sysCheck = com.google.android.material.checkbox.MaterialCheckBox(this@SettingsActivity).apply {
                isChecked = false
                isClickable = false
                isFocusable = false
            }
            sysRow.addView(sysLabel, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            sysRow.addView(sysCheck, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            root.addView(sysRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    
            root.addView(android.view.View(this@SettingsActivity).apply {
                setBackgroundColor(getColor(R.color.md_theme_outlineVariant))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
    
            val listView = android.widget.ListView(this@SettingsActivity).apply {
                choiceMode = android.widget.AbsListView.CHOICE_MODE_MULTIPLE
            }
            root.addView(listView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, listHeight))
    
            fun applyFilter(query: String) {
                currentFilteredApps = if (query.isBlank()) cachedApps
                else cachedApps.filter { it.displayLabel.contains(query, ignoreCase = true) }
    
                listView.adapter = android.widget.ArrayAdapter(
                    this@SettingsActivity,
                    android.R.layout.simple_list_item_multiple_choice,
                    currentFilteredApps.map { it.displayLabel }.toMutableList()
                )
                currentFilteredApps.forEachIndexed { i, app ->
                    listView.setItemChecked(i, app.isSelected())
                }
            }
    
            applyFilter("")
    
            listView.setOnItemClickListener { _, _, pos, _ ->
                val app = currentFilteredApps.getOrNull(pos) ?: return@setOnItemClickListener
                if (listView.isItemChecked(pos)) {
                    selectedKeys.add(app.packageKey())
                } else {
                    selectedKeys.remove(app.packageKey())
                    selectedKeys.remove(app.packageName)
                }
            }
    
            searchEdit.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    applyFilter(s?.toString() ?: "")
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
    
            sysRow.setOnClickListener {
                sysCheck.isChecked = !sysCheck.isChecked
                val includeSystem = sysCheck.isChecked
                lifecycleScope.launch {
                    cachedApps = withContext(Dispatchers.IO) {
                        queryAppsAcrossUsers(includeSystemApps = includeSystem)
                    }
                    applyFilter(searchEdit.text.toString())
                }
            }
    
            androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                .setTitle(dialogTitle)
                .setView(root)
                .setPositiveButton("Save") { _, _ -> onSave(selectedKeys.toSet()) }
                .setNeutralButton("Clear All") { _, _ -> onClear() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
 
    // ── Sensitive Apps Feature Layer ──────────────────────────────────────────
 
    private fun setupSensitiveApps() {
        updateSensitiveAppsCount()
        binding.btnManageSensitiveApps.setOnClickListener {
            showSensitiveAppPickerDialog()
        }
    }
    
    private fun showSensitiveAppPickerDialog() {
        showAppPickerDialogInternal(
            dialogTitle = "Auto-Sensitive Apps",
            loadSelected = ::loadSensitivePackages,
            onSave    = { saveSensitivePackages(it); updateSensitiveAppsCount() },
            onClear   = { saveSensitivePackages(emptySet()); updateSensitiveAppsCount() }
        )
    }
 
    private fun updateSensitiveAppsCount() {
        val sensitive = loadSensitivePackages()
        binding.tvSensitiveAppsCount.text = if (sensitive.isEmpty())
            "No apps configured"
        else
            "${sensitive.size} app${if (sensitive.size == 1) "" else "s"} configured"
    }
    
    private fun loadSensitivePackages(): Set<String> {
        val json = prefs.getString(KEY_SENSITIVE_PACKAGES, null) ?: return emptySet()
        return try {
            val array = org.json.JSONArray(json)
            buildSet { for (i in 0 until array.length()) add(array.getString(i)) }
        } catch (e: Exception) {
            emptySet()
        }
    }
    
    private fun saveSensitivePackages(packages: Set<String>) {
        val json = org.json.JSONArray(packages.toList()).toString()
        prefs.edit().putString(KEY_SENSITIVE_PACKAGES, json).apply()
    }
 
    // ── Excluded Apps Feature Layer ───────────────────────────────────────────
    
    private fun setupExcludedApps() {
        updateExcludedAppsCount()
        binding.btnManageExcludedApps.setOnClickListener {
            showAppPickerDialog()
        }
    }
 
    private fun showAppPickerDialog() {
        showAppPickerDialogInternal(
            dialogTitle = "Exclude Apps",
            loadSelected = ::loadExcludedPackages,
            onSave    = { saveExcludedPackages(it); updateExcludedAppsCount() },
            onClear   = { saveExcludedPackages(emptySet()); updateExcludedAppsCount() }
        )
    }
    
    private fun updateExcludedAppsCount() {
        val excluded = loadExcludedPackages()
        binding.tvExcludedAppsCount.text = if (excluded.isEmpty())
            "No apps excluded"
        else
            "${excluded.size} app${if (excluded.size == 1) "" else "s"} excluded"
    }
    
    private fun loadExcludedPackages(): Set<String> {
        val json = prefs.getString(KEY_EXCLUDED_PACKAGES, null) ?: return emptySet()
        return try {
            val array = org.json.JSONArray(json)
            buildSet { for (i in 0 until array.length()) add(array.getString(i)) }
        } catch (e: Exception) {
            emptySet()
        }
    }
    
    private fun saveExcludedPackages(packages: Set<String>) {
        val json = org.json.JSONArray(packages.toList()).toString()
        prefs.edit().putString(KEY_EXCLUDED_PACKAGES, json).apply()
    }
 
    // ── Backup, Export, & Import Subsystem ────────────────────────────────────
 
    private fun setupBackupButtons() {
        binding.btnExport.setOnClickListener {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            exportLauncher.launch("clipboard_backup_$timestamp.zip")
        }
    
        binding.btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }
    
        binding.btnWipeDatabase.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Wipe All Data?")
                .setMessage("This will permanently delete every clip and saved image. Export a backup first if you want to keep your history.")
                .setPositiveButton("Wipe Everything") { _, _ -> performWipe() }
                .setNegativeButton("Cancel", null)
                .show()
        }
        refreshDatabaseSizeInfo()
    }
 
    private fun performExport(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val clips = AppDatabase.getDatabase(applicationContext)
                        .clipboardDao()
                        .getAllClipsOnce()
    
                    // Load automation rules JSON string from prefs
                    val rulesJson = prefs.getString(
                        nocom.rian.clipboardmanager.db.PREFS_KEY_AUTOMATION_RULES, null
                    )
    
                    contentResolver.openOutputStream(uri)?.use { stream ->
                        BackupManager.exportZip(clips, stream, rulesJson)
                    } ?: Result.failure(Exception("Could not open output stream"))
    
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
    
            result.fold(
                onSuccess = { count ->
                    Snackbar.make(
                        binding.root,
                        "Exported $count clip${if (count == 1) "" else "s"} to ZIP",
                        Snackbar.LENGTH_LONG
                    ).show()
                },
                onFailure = { e ->
                    Snackbar.make(
                        binding.root,
                        "Export failed: ${e.message}",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            )
        }
    }
    
    private fun performImport(uri: Uri) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Import Clips")
            .setMessage("Imported clips will be added alongside your existing history. Duplicates may appear. Continue?")
            .setPositiveButton("Import") { _, _ -> doImport(uri) }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun doImport(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val imageDir = File(getExternalFilesDir(null), "clipboard_images")
    
                    val backupResult = contentResolver.openInputStream(uri)?.use { stream ->
                        BackupManager.importZip(stream, imageDir)
                    } ?: Result.failure(Exception("Could not open file"))
    
                    backupResult.map { br ->
                        val dao = AppDatabase.getDatabase(applicationContext).clipboardDao()
                        val repo = ClipboardRepository(dao)
                        br.clips.sortedBy { it.timestamp }.forEach { repo.insert(it) }
    
                        // Merge imported automation rules safely
                        if (!br.rulesJson.isNullOrBlank()) {
                            val existingRules = nocom.rian.clipboardmanager.db.loadRules(prefs).toMutableList()
                            
                            val importedRules: List<nocom.rian.clipboardmanager.db.AutomationRule> = try {
                                val type = nocom.rian.clipboardmanager.db.AutomationRuleListTypeToken().type
                                com.google.gson.Gson().fromJson(br.rulesJson, type) ?: emptyList()
                            } catch (e: Exception) {
                                android.util.Log.e("SettingsActivity", "Failed to parse imported rules JSON", e)
                                emptyList()
                            }
    
                            // Merge by checking existing IDs to prevent duplicates
                            val mergedIds = existingRules.map { it.id }.toSet()
                            val newRules = importedRules.filter { it.id !in mergedIds }
                            
                            // Only save if there are actually new rules to add
                            if (newRules.isNotEmpty()) {
                                nocom.rian.clipboardmanager.db.saveRules(prefs, existingRules + newRules)
                                android.util.Log.d("SettingsActivity", "Merged ${newRules.size} new automation rule(s) from backup")
                            }
                        }
    
                        br.clips.size
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
    
            result.fold(
                onSuccess = { count ->
                    Snackbar.make(
                        binding.root,
                        "Imported $count clip${if (count == 1) "" else "s"} successfully",
                        Snackbar.LENGTH_LONG
                    ).show()
                },
                onFailure = { e ->
                    Snackbar.make(
                        binding.root,
                        "Import failed: ${e.message}",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            )
        }
    }
 
    // ── Data Destruction (Wipe & Cleanup) ─────────────────────────────────────
 
    private fun performWipe() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val imageDir = File(getExternalFilesDir(null), "clipboard_images")
                    imageDir.listFiles()?.forEach { it.delete() }
    
                    val dao = AppDatabase.getDatabase(applicationContext).clipboardDao()
                    dao.clearUnpinnedHistory()
                    AppDatabase.getDatabase(applicationContext)
                        .clipboardDao()
                        .deleteAll()
                } catch (e: Exception) {
                    android.util.Log.e("SettingsActivity", "Wipe failed", e)
                }
            }
            Snackbar.make(binding.root, "All clipboard data wiped.", Snackbar.LENGTH_LONG).show()
            refreshDatabaseSizeInfo()
        }
    }
 
    // ── Storage Analytics & Information Helpers ───────────────────────────────
 
    private fun refreshDatabaseSizeInfo() {
        lifecycleScope.launch(Dispatchers.IO) {
            fun dirSize(dir: java.io.File): Long =
                dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
 
            val internalDataDir = filesDir.parentFile  
            val internalBytes = if (internalDataDir?.exists() == true) dirSize(internalDataDir) else 0L
 
            val imageDir = java.io.File(getExternalFilesDir(null), "clipboard_images")
            val imageBytes = if (imageDir.exists()) dirSize(imageDir) else 0L
 
            val externalBase = getExternalFilesDir(null)
            val otherExternalBytes = externalBase
                ?.walkTopDown()
                ?.filter { it.isFile && !it.absolutePath.startsWith(imageDir.absolutePath) }
                ?.sumOf { it.length() } ?: 0L
 
            val textDbBytes = internalBytes + otherExternalBytes
            val totalBytes  = textDbBytes + imageBytes
 
            withContext(Dispatchers.Main) {
                binding.tvDatabaseSize.text = buildString {
                    append("Storage used: ${formatFileSize(totalBytes)}")
                    append("\n  • Text / DB:  ${formatFileSize(textDbBytes)}")
                    append("\n  • Images:      ${formatFileSize(imageBytes)}")
                }
            }
        }
    }
 
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1_024L     -> "%.1f KB".format(bytes / 1_024.0)
            else                -> "$bytes B"
        }
    }
 
    private fun formatDays(days: Int) = if (days == 0) "Disabled" else "$days days"
    private fun formatCap(cap: Int)  = if (cap  == 0) "Unlimited" else "$cap clips"
 
    // ── Companion Object ──────────────────────────────────────────────────────
 
    companion object {
        const val PREFS_NAME             = "clipboard_settings"
        const val KEY_AUTO_DELETE_DAYS   = "auto_delete_days"
        const val KEY_CLIP_CAP           = "clip_cap"
        const val KEY_SHOW_MANUAL_ENTRY  = "show_manual_entry"
        const val KEY_SYNC_SENSITIVE_FROM_CLIPBOARD = "sync_sensitive_from_clipboard"
        const val KEY_MONITORING_ENABLED = "monitoring_enabled"
        const val KEY_SENSITIVE_PACKAGES = "sensitive_packages"
        const val KEY_EXCLUDED_PACKAGES  = "excluded_packages"
        const val KEY_FLOATING_OVERLAY_ENABLED = "floating_overlay_enabled"
        // Display field toggles (expanded clip view)
        const val KEY_SHOW_FIELD_APP_NAME      = "show_field_app_name"
        const val KEY_SHOW_FIELD_PACKAGE       = "show_field_package"
        const val KEY_SHOW_FIELD_WINDOW        = "show_field_window"
        const val KEY_SHOW_FIELD_ACTIVITY      = "show_field_activity"
        const val KEY_SHOW_FIELD_LABEL         = "show_field_label"
        const val KEY_SHOW_FIELD_TECH          = "show_field_tech"
        const val KEY_SHOW_FIELD_PREV_APP_NAME = "show_field_prev_app_name"
        const val KEY_SHOW_FIELD_PREV_PACKAGE  = "show_field_prev_package"
        const val KEY_SHOW_FIELD_PREV_WINDOW   = "show_field_prev_window"
        const val KEY_SHOW_FIELD_PREV_ACTIVITY = "show_field_prev_activity"
    
        // Logging field toggles (what gets written to DB)
        const val KEY_LOG_FIELD_APP_NAME      = "log_field_app_name"
        const val KEY_LOG_FIELD_PACKAGE       = "log_field_package"
        const val KEY_LOG_FIELD_WINDOW        = "log_field_window"
        const val KEY_LOG_FIELD_ACTIVITY      = "log_field_activity"
        const val KEY_LOG_FIELD_PREV_APP_NAME = "log_field_prev_app_name"
        const val KEY_LOG_FIELD_PREV_PACKAGE  = "log_field_prev_package"
        const val KEY_LOG_FIELD_PREV_WINDOW   = "log_field_prev_window"
        const val KEY_LOG_FIELD_PREV_ACTIVITY = "log_field_prev_activity"
        
        /**
        * Checks if the floating overlay feature should be displayed based on 
        * user settings configuration and Android system hardware permissions.
        */
        fun isOverlayFeatureAllowed(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isSettingEnabled = prefs.getBoolean(KEY_FLOATING_OVERLAY_ENABLED, true)
            
            val hasSystemPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.provider.Settings.canDrawOverlays(context)
            } else {
                true
            }
            
            return isSettingEnabled && hasSystemPermission
        }
    }
}
