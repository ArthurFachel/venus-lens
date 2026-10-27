package dev.fachel.venuslens

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("venus_lens", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString("url", BuildConfig.DEFAULT_URL)!!
        set(v) = sp.edit().putString("url", v.trim()).apply()

    var token: String
        get() = sp.getString("token", "")!!
        set(v) = sp.edit().putString("token", v.trim()).apply()

    /** "stream" = frames go up continuously at [fps]; "photo" = one frozen frame per question. */
    var mode: String
        get() = sp.getString("mode", MODE_STREAM)!!
        set(v) = sp.edit().putString("mode", v).apply()

    var fps: Int
        get() = sp.getInt("fps", 2)
        set(v) = sp.edit().putInt("fps", v).apply()

    /** Long side of the JPEG sent: 854 (480p), 1280 (720p) or 1920 (1080p). */
    var resolution: Int
        get() = sp.getInt("res", 720)
        set(v) = sp.edit().putInt("res", v).apply()

    var speak: Boolean
        get() = sp.getBoolean("speak", true)
        set(v) = sp.edit().putBoolean("speak", v).apply()

    var pauseScreenOff: Boolean
        get() = sp.getBoolean("pause_screen_off", true)
        set(v) = sp.edit().putBoolean("pause_screen_off", v).apply()

    var onlyOnSceneChange: Boolean
        get() = sp.getBoolean("scene_change", false)
        set(v) = sp.edit().putBoolean("scene_change", v).apply()

    var headsetButton: Boolean
        get() = sp.getBoolean("headset", true)
        set(v) = sp.edit().putBoolean("headset", v).apply()

    val longSide: Int get() = when (resolution) { 480 -> 854; 1080 -> 1920; else -> 1280 }

    companion object {
        const val MODE_STREAM = "stream"
        const val MODE_PHOTO = "photo"
    }
}
