package nocom.rian.clipboardmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import nocom.rian.clipboardmanager.service.ClipboardService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return
    
        val monitoringEnabled = context.getSharedPreferences(
            "clipboard_settings", Context.MODE_PRIVATE
        ).getBoolean("monitoring_enabled", true)
    
        if (!monitoringEnabled) {
            Log.d("BootReceiver", "Monitoring disabled — skipping service start on boot")
            return
        }
    
        Log.d("BootReceiver", "Boot completed — starting ClipboardService")
        val serviceIntent = Intent(context, ClipboardService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}    