package dev.fachel.venuslens

import android.app.Activity
import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var endpoint: EditText
    private lateinit var token: EditText
    private lateinit var resolution: TextView
    private lateinit var dataHint: TextView
    private val fpsViews = mutableMapOf<Int, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        findViewById<View>(R.id.back).setOnClickListener { finish() }

        fpsViews[1] = findViewById(R.id.fps1); fpsViews[2] = findViewById(R.id.fps2); fpsViews[5] = findViewById(R.id.fps5)
        fpsViews.forEach { (fps, v) -> v.setOnClickListener { prefs.fps = fps; renderFps(); renderHint() } }

        resolution = findViewById(R.id.resolution)
        resolution.setOnClickListener { pickResolution() }
        dataHint = findViewById(R.id.dataHint)

        endpoint = findViewById<EditText>(R.id.endpoint).apply { setText(prefs.url) }
        token = findViewById<EditText>(R.id.token).apply { setText(prefs.token) }

        bindSwitch(R.id.swSpeak, prefs.speak) { prefs.speak = it }
        bindSwitch(R.id.swScreen, prefs.pauseScreenOff) { prefs.pauseScreenOff = it }
        bindSwitch(R.id.swScene, prefs.onlyOnSceneChange) { prefs.onlyOnSceneChange = it; renderHint() }
        bindSwitch(R.id.swHeadset, prefs.headsetButton) { prefs.headsetButton = it }

        findViewById<View>(R.id.addTile).setOnClickListener { requestTile() }
        findViewById<View>(R.id.openDebug).setOnClickListener { startActivity(Intent(this, DebugActivity::class.java)) }

        renderFps(); renderResolution(); renderHint()
    }

    override fun onPause() {
        super.onPause()
        val changed = endpoint.text.toString().trim() != prefs.url || token.text.toString().trim() != prefs.token
        prefs.url = endpoint.text.toString()
        prefs.token = token.text.toString()
        if (changed && LensService.running) toast("Desligue e ligue o assistente para usar o novo servidor")
    }

    private fun bindSwitch(id: Int, value: Boolean, set: (Boolean) -> Unit) {
        findViewById<Switch>(id).apply { isChecked = value; setOnCheckedChangeListener { _, c -> set(c) } }
    }

    private fun renderFps() {
        fpsViews.forEach { (fps, v) ->
            val on = prefs.fps == fps
            if (on) v.setBackgroundResource(R.drawable.seg_on10) else v.background = null
            v.setTextColor(getColor(if (on) R.color.bg else R.color.cream_74))
            v.fontVariationSettings = if (on) "'wght' 800" else "'wght' 700"
        }
    }

    private val resOptions = listOf(720 to "720p · recomendado", 480 to "480p · economiza dados", 1080 to "1080p · mais detalhe")

    private fun renderResolution() {
        resolution.text = resOptions.first { it.first == prefs.resolution }.second
    }

    private fun pickResolution() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Resolução enviada")
            .setItems(resOptions.map { it.second }.toTypedArray()) { _, i ->
                prefs.resolution = resOptions[i].first
                renderResolution(); renderHint()
                if (LensService.running) toast("Vale a partir da próxima vez que ligar")
            }.show()
    }

    /** Rough uplink estimate so the fps/resolution choice has a visible cost. */
    private fun renderHint() {
        val kb = when (prefs.resolution) { 480 -> 45; 1080 -> 220; else -> 110 }
        val mbPerMin = kb * prefs.fps * 60 / 1024.0
        val scene = if (prefs.onlyOnSceneChange) " Com \"só quando a cena mudar\", bem menos em cena parada." else ""
        dataHint.text = "No streaming, ~${"%.0f".format(mbPerMin)} MB por minuto de envio (estimativa).$scene " +
            "Foto única só envia uma imagem por pergunta."
    }

    private fun requestTile() {
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, LensTile::class.java), "Venus",
            Icon.createWithResource(this, R.drawable.ic_lens), mainExecutor) { code ->
            if (code == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                code == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) toast("Pronto, está no painel rápido")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
