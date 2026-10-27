package dev.fachel.venuslens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/** Invisible trampoline used by the tile: starts the service from the foreground and closes. */
class StartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ok = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (ok && Prefs(this).token.isNotBlank()) {
            LensService.start(this)
        } else {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}
