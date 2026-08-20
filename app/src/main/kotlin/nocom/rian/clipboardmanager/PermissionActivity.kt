package nocom.rian.clipboardmanager

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import nocom.rian.clipboardmanager.databinding.ActivityPermissionBinding
import nocom.rian.clipboardmanager.service.ClipboardService
import rikka.shizuku.Shizuku

class PermissionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionBinding
    private val SHIZUKU_CODE = 102

    // =========================================================================
    // 1. Shizuku & Permission Listeners / Launchers
    // =========================================================================

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            checkAndRefreshShizukuStatus()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            binding.statusText.text = "Shizuku service disconnected."
            binding.statusIcon.setImageResource(R.drawable.ic_warning)
            binding.statusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.color_status_error)
            )
        }
    }

    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_CODE) {
                runOnUiThread {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        onPermissionGranted()
                    } else {
                        binding.statusText.text = "Permission denied by user.\nOpen Shizuku and try again."
                    }
                }
            }
        }

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(
                this,
                "Notification permission denied — service status won't be visible",
                Toast.LENGTH_LONG
            ).show()
        }
        startClipboardService()
    }
    
    private val overlayPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        refreshOverlayStatus()
    }
    
    private val batteryOptLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        val pm = getSystemService(android.os.PowerManager::class.java)
        updateBatteryStatusUi(pm.isIgnoringBatteryOptimizations(packageName))
    }
    
    // =========================================================================
    // 2. Lifecycle & Navigation Methods
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionBinding.inflate(layoutInflater)
        setContentView(binding.root)
    
        setSupportActionBar(binding.shizukuToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
    
        setupClickListeners()
        
        // Initial Checks
        checkAndRefreshShizukuStatus()
        refreshNotificationStatus()
        refreshBatteryStatus()
        refreshOverlayStatus()
    }

    override fun onResume() {
        super.onResume()
        checkAndRefreshShizukuStatus()
        refreshNotificationStatus()
        refreshBatteryStatus()
        refreshOverlayStatus()
    }
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
    }

    // =========================================================================
    // 3. User Interaction / UI Initialization
    // =========================================================================

    private fun setupClickListeners() {
        binding.btnGrantShizuku.setOnClickListener {
            val btnText = binding.btnGrantShizuku.text.toString()
            if (btnText == "Install") {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=rikka.shizuku"))
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/tiann/Shizuku/releases"))
                    startActivity(intent)
                }
            } else if (btnText == "Open") {
                openShizukuApp()
            } else {
                checkAndRefreshShizukuStatus(requestIfNeeded = true)
            }
        }
        binding.btnGrantNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    val prefs = getSharedPreferences("permission_prefs", MODE_PRIVATE)
                    val requestedBefore = prefs.getBoolean("requested_notifications", false)
                    if (requestedBefore && !shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                        Toast.makeText(
                            this,
                            "Notification permission permanently denied. Please enable it in App Settings.",
                            Toast.LENGTH_LONG
                        ).show()
                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } else {
                        prefs.edit().putBoolean("requested_notifications", true).apply()
                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
    
        binding.btnExemptBattery.setOnClickListener {
            try {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                batteryOptLauncher.launch(intent)
            } catch (e: Exception) {
                batteryOptLauncher.launch(
                    Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            }
        }
        // Add click listener for your overlay permission button
        binding.btnGrantOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            }
        }
    }

    // =========================================================================
    // 4. Core Business Logic / Task Performers
    // =========================================================================
        
    private fun isShizukuInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo("rikka.shizuku", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun openShizukuApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage("rikka.shizuku")
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "Could not open Shizuku app", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRefreshShizukuStatus(requestIfNeeded: Boolean = false) {
        val binderAlive = try { Shizuku.pingBinder() } catch (e: Exception) { false }

        // "can_shizuku_be_used" — a live binder plus a granted permission, the same
        // precondition getUid() needs to succeed. It's package-name independent, so
        // If true, Shizuku is definitely usable — treat it as
        // connected and skip the installed-package lookup entirely rather than let a
        // stale/renamed package check override a working connection.
        val permissionGranted = binderAlive && try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }

        if (permissionGranted) {
            binding.btnGrantShizuku.visibility = View.GONE
            onPermissionGranted()
            return
        }

        when {
            // Only report "not installed" when we ALSO can't detect a live binder —
            !isShizukuInstalled() && !binderAlive -> {
                updateStatusUi(false, "Shizuku is not installed")
                binding.btnGrantShizuku.apply {
                    text = "Install"
                    visibility = View.VISIBLE
                }
            }
            Shizuku.isPreV11() -> {
                updateStatusUi(false, "Shizuku v11+ is required")
                binding.btnGrantShizuku.visibility = View.GONE
            }
            !binderAlive -> {
                updateStatusUi(false, "Not running — start it from the Shizuku app")
                binding.btnGrantShizuku.apply {
                    text = "Open"
                    visibility = View.VISIBLE
                }
            }
            else -> {
                // binderAlive is true here and permissionGranted is false
                updateStatusUi(false, "Permission not granted")
                binding.btnGrantShizuku.apply {
                    text = "Grant"
                    visibility = View.VISIBLE
                }
                if (requestIfNeeded) {
                    try {
                        Shizuku.requestPermission(SHIZUKU_CODE)
                    } catch (e: Exception) {
                        updateStatusUi(false, "Error requesting permission.")
                    }
                }
            }
        }
    }
    
    private fun onPermissionGranted() {
        val uid = try { Shizuku.getUid() } catch (e: Exception) { -1 }
        val identity = when (uid) {
            0    -> "Root (Full Access)"
            1000 -> "System"
            2000 -> "ADB / Shell"
            else -> "Unknown Identity"
        }
        updateStatusUi(true, "Connected · $identity")
        binding.btnGrantShizuku.visibility = View.GONE
        startClipboardService()
    }
    
    private fun startClipboardService() {
        val intent = Intent(this, ClipboardService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
    
    private fun refreshNotificationStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            updateNotificationStatusUi(granted)
        } else {
            updateNotificationStatusUi(true)
        }
    }
    
    private fun refreshBatteryStatus() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        updateBatteryStatusUi(pm.isIgnoringBatteryOptimizations(packageName))
    }
    
    // =========================================================================
    // 5. UI Helpers / State Updaters
    // =========================================================================

    private fun updateStatusUi(isConnected: Boolean, message: String) {
        binding.statusText.text = message
        if (isConnected) {
            binding.statusIcon.setImageResource(R.drawable.ic_check_circle)
            binding.statusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.color_status_success)
            )
        } else {
            binding.statusIcon.setImageResource(R.drawable.ic_warning)
            binding.statusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.color_status_error)
            )
        }
    }
    
    private fun updateNotificationStatusUi(isGranted: Boolean) {
        if (isGranted) {
            binding.notifStatusIcon.setImageResource(R.drawable.ic_check_circle)
            binding.notifStatusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.color_status_success)
            )
            binding.notifStatusText.text = "Granted"
            binding.btnGrantNotification.isEnabled = false
            binding.btnGrantNotification.text = "Granted"
        } else {
            binding.notifStatusIcon.setImageResource(R.drawable.ic_warning)
            binding.notifStatusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.color_status_error)
            )
            binding.notifStatusText.text = "Not granted — service notifications will be silent"
            binding.btnGrantNotification.isEnabled = true
            binding.btnGrantNotification.text = "Grant"
        }
    }
    
    private fun updateBatteryStatusUi(isExempt: Boolean) {
        if (isExempt) {
            binding.batteryStatusIcon.setImageResource(R.drawable.ic_check_circle)
            binding.batteryStatusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.color_status_success)
            )
            binding.batteryStatusText.text = "Exempt — service will not be killed in background"
            binding.btnExemptBattery.isEnabled = false
            binding.btnExemptBattery.text = "Exempt"
        } else {
            binding.batteryStatusIcon.setImageResource(R.drawable.ic_warning)
            binding.batteryStatusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.color_status_warning)
            )
            binding.batteryStatusText.text = "Not exempt — OS may kill the monitoring service"
            binding.btnExemptBattery.isEnabled = true
            binding.btnExemptBattery.text = "Exempt"
        }
    }
    
    private fun refreshOverlayStatus() {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(this)
        } else {
            true
        }
        updateOverlayStatusUi(granted)
    }
    
    private fun updateOverlayStatusUi(isGranted: Boolean) {
        if (isGranted) {
            binding.overlayStatusIcon.setImageResource(R.drawable.ic_check_circle)
            binding.overlayStatusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.color_status_success)
            )
            binding.overlayStatusText.text = "Granted — Floating search layout fully operational"
            binding.btnGrantOverlay.isEnabled = false
            binding.btnGrantOverlay.text = "Granted"
        } else {
            binding.overlayStatusIcon.setImageResource(R.drawable.ic_warning)
            binding.overlayStatusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.color_status_error)
            )
            binding.overlayStatusText.text = "Not granted — floating search menu remains disabled"
            binding.btnGrantOverlay.isEnabled = true
            binding.btnGrantOverlay.text = "Grant"
        }
    }
}
