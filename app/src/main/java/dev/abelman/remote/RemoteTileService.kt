package dev.abelman.remote

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Opens the same remote and reconnects to the last selected TV. No overlay permission. */
class RemoteTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val repository = (application as RemoteApplication).remote
        qsTile?.apply {
            label = getString(R.string.tile_name)
            icon = Icon.createWithResource(this@RemoteTileService, R.drawable.ic_remote)
            state = if (repository.state == ConnectionState.CONNECTED) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) subtitle = repository.selected?.takeIf { it.credentials.isNotEmpty() }?.name ?: "Открыть пульт"
            contentDescription = "Открыть пульт Apple TV"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { openRemote() } else openRemote()
    }

    // The deprecated overload is required on API 26–33; API 34+ uses PendingIntent.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openRemote() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra("open_remote", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
