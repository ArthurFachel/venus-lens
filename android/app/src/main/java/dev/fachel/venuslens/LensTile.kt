package dev.fachel.venuslens

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class LensTile : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        if (LensService.running) {
            LensService.stop(this)
            qsTile?.state = Tile.STATE_INACTIVE
            qsTile?.updateTile()
            return
        }
        // Camera/mic services can't start from the background on Android 14+, so hop through
        // an invisible activity. On the lock screen, ask for unlock first.
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, StartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        if (isLocked) unlockAndRun { startActivityAndCollapse(pi) } else startActivityAndCollapse(pi)
    }

    private fun refresh() {
        val t = qsTile ?: return
        t.state = if (LensService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.subtitle = if (LensService.running) "Ligada" else "Desligada"
        t.updateTile()
    }
}
