package nocom.rian.clipboardmanager

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import nocom.rian.clipboardmanager.databinding.ActivityShizukuBinding
import nocom.rian.clipboardmanager.service.ClipboardService
import rikka.shizuku.Shizuku

class ShizukuActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShizukuBinding
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
            binding.statusIcon.setImageResource(android.R.drawable.stat_notify_error)
            binding.statusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(android.R.color.holo_red_dark)
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
        binding = ActivityShizukuBinding.inflate(layoutInflater)
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
            checkAndRefreshShizukuStatus(requestIfNeeded = true)
        }
    
        binding.btnGrantNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
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
    }

    // =========================================================================
    // 4. Core Business Logic / Task Performers
    // =========================================================================
        
    private fun checkAndRefreshShizukuStatus(requestIfNeeded: Boolean = false) {
        when {
            Shizuku.isPreV11() -> {
                updateStatusUi(false, "Shizuku v11+ is required")
                binding.btnGrantShizuku.visibility = View.GONE
            }
            !Shizuku.pingBinder() -> {
                updateStatusUi(false, "Not running — start it from the Shizuku app")
                binding.btnGrantShizuku.visibility = View.GONE
            }
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> {
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
            else -> {
                binding.btnGrantShizuku.visibility = View.GONE
                onPermissionGranted()
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
    
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startClipboardService()
            }
        } else {
            startClipboardService()
        }
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
            binding.statusIcon.setImageResource(android.R.drawable.checkbox_on_background)
            binding.statusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(android.R.color.holo_green_dark)
            )
        } else {
            binding.statusIcon.setImageResource(android.R.drawable.ic_dialog_alert)
            binding.statusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(android.R.color.holo_red_light)
            )
        }
    }
    
    private fun updateNotificationStatusUi(isGranted: Boolean) {
        if (isGranted) {
            binding.notifStatusIcon.setImageResource(android.R.drawable.checkbox_on_background)
            binding.notifStatusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(android.R.color.holo_green_dark)
            )
            binding.notifStatusText.text = "Granted"
            binding.btnGrantNotification.isEnabled = false
            binding.btnGrantNotification.text = "Granted"
        } else {
            binding.notifStatusIcon.setImageResource(android.R.drawable.ic_dialog_alert)
            binding.notifStatusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(android.R.color.holo_red_light)
            )
            binding.notifStatusText.text = "Not granted — service notifications will be silent"
            binding.btnGrantNotification.isEnabled = true
            binding.btnGrantNotification.text = "Grant"
        }
    }
    
    private fun updateBatteryStatusUi(isExempt: Boolean) {
        if (isExempt) {
            binding.batteryStatusIcon.setImageResource(android.R.drawable.checkbox_on_background)
            binding.batteryStatusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(android.R.color.holo_green_dark)
            )
            binding.batteryStatusText.text = "Exempt — service will not be killed in background"
            binding.btnExemptBattery.isEnabled = false
            binding.btnExemptBattery.text = "Exempt"
        } else {
            binding.batteryStatusIcon.setImageResource(android.R.drawable.ic_dialog_alert)
            binding.batteryStatusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(android.R.color.holo_orange_light)
            )
            binding.batteryStatusText.text = "Not exempt — OS may kill the monitoring service"
            binding.btnExemptBattery.isEnabled = true
            binding.btnExemptBattery.text = "Exempt"
        }
    }
}
