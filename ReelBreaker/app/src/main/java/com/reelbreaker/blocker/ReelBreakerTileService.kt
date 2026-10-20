package com.reelbreaker.blocker

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.reelbreaker.data.Store

/**
 * Quick Settings Status Bar Tile for ReelBreaker.
 * Allows one-tap status check and toggle directly from the Android pull-down notification shade.
 */
@RequiresApi(Build.VERSION_CODES.N)
class ReelBreakerTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isServiceRunning = ReelAccessibilityService.instance != null
        val store = Store.get(this)

        if (!isServiceRunning) {
            // Jump directly to Accessibility Settings to arm
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ pending intent launcher for TileService
                val pendingIntent = android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } else {
            // Toggle active state
            store.blockerActive = !store.blockerActive
            updateTileState()
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isServiceRunning = ReelAccessibilityService.instance != null
        val store = Store.get(this)
        val armed = isServiceRunning && store.blockerActive

        if (armed) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "ReelBreaker"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val total = store.todayTotal()
                val budget = store.dailyBudget
                tile.subtitle = "$total / $budget reels"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "ReelBreaker"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (!isServiceRunning) "Tap to Arm" else "Paused"
            }
        }
        tile.updateTile()
    }
}
