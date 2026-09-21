package nocom.rian.clipboardmanager

import android.app.Application
import org.lsposed.hiddenapibypass.HiddenApiBypass
import android.os.Build
import android.content.pm.PackageManager
import android.content.Intent
import rikka.shizuku.ShizukuProvider
import rikka.shizuku.Shizuku

class MainApplication : Application() {

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                val monitoringEnabled = getSharedPreferences(
                    "clipboard_settings", android.content.Context.MODE_PRIVATE
                ).getBoolean("monitoring_enabled",true)
    
                if (monitoringEnabled) {
                    val intent = Intent(this, nocom.rian.clipboardmanager.service.ClipboardService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainApplication", "Failed to start service after Shizuku reconnect", e)
        }
    }
    
    override fun onCreate() {
        super.onCreate()

        ShizukuProvider.enableMultiProcessSupport(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
                android.util.Log.i("MainApplication", "HiddenApiBypass applied successfully")
            } catch (t: Throwable) {
                android.util.Log.e("MainApplication", "HiddenApiBypass failed; falling back to direct Shizuku IPC wrappers", t)
            }
        }

        // Sticky = fires immediately if binder is already live, AND fires on
        // every future reconnect — including when Shizuku wakes the process
        // via ShizukuProvider after a force-stop.
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
    }

    override fun onTerminate() {
        super.onTerminate()
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
    }
}