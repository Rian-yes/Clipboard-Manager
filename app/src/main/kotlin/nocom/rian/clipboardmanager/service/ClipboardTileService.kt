package nocom.rian.clipboardmanager.service

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import nocom.rian.clipboardmanager.SettingsActivity
import android.util.Log

class ClipboardTileService : TileService() {

    private val prefs: SharedPreferences
        get() = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)

    // =========================================================================
    // TileService Lifecycle
    // =========================================================================

    /** Called when the tile becomes visible in the quick settings panel. */
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    /** Called when the user taps the tile. */
    override fun onClick() {
        super.onClick()
        val isCurrentlyEnabled = prefs.getBoolean(SettingsActivity.KEY_MONITORING_ENABLED, true)
        toggleMonitoring(newState = !isCurrentlyEnabled)
    }

    // =========================================================================
    // Core Logic
    // =========================================================================

    private fun toggleMonitoring(newState: Boolean) {
        prefs.edit().putBoolean(SettingsActivity.KEY_MONITORING_ENABLED, newState).commit()

        val serviceIntent = Intent(this, ClipboardService::class.java)

        if (newState) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Modern approach: Use the explicit TileService foreground start handler 
                    // to safely pass background start restriction tokens to the system
                    startForegroundService(serviceIntent)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e("ClipboardTileService", "Failed to start service from tile", e)
            }
        } else {
            try {
                stopService(serviceIntent)
            } catch (e: Exception) {
                Log.e("ClipboardTileService", "Failed to stop service from tile", e)
            }
        }

        // Post the UI update to the main thread queue slightly later 
        // to ensure the system Binder thread registers the service state switch
        handler?.post {
            updateTile()
        }
    }

    // =========================================================================
    // Tile UI
    // =========================================================================

    private fun updateTile() {
        // Safe check to avoid operating on a stale or unbound Tile context
        val tile = qsTile ?: return
        val prefEnabled  = prefs.getBoolean(SettingsActivity.KEY_MONITORING_ENABLED, true)
        val serviceAlive = ClipboardService.isRunning

        when {
            prefEnabled && serviceAlive -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Clipboard"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Monitoring"
                }
            }
            prefEnabled && !serviceAlive -> {
                // Enabled in prefs but service not running — Shizuku likely offline
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "Clipboard"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "No Shizuku"
                }
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Clipboard"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Paused"
                }
            }
        }

        try {
            tile.updateTile()
        } catch (e: Exception) {
            Log.e("ClipboardTileService", "Failed to commit tile UI update", e)
        }
    }
    
    // Fallback accessor for background thread safety handling
    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
}
