package dev.fachel.venuslens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class DebugActivity : Activity() {
    private lateinit var frames: TextView
    private lateinit var latency: TextView
    private lateinit var queue: TextView
    private lateinit var lastReq: TextView
    private lateinit var log: LinearLayout
    private lateinit var logScroll: ScrollView
    private var logCount = -1
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        frames = findViewById(R.id.statFrames); latency = findViewById(R.id.statLatency)
        queue = findViewById(R.id.statQueue); lastReq = findViewById(R.id.lastReq)
        log = findViewById(R.id.log); logScroll = findViewById(R.id.logScroll)
        findViewById<View>(R.id.copyReq).setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("request", LensState.lastRequest))
            Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() { super.onResume(); LensState.addListener(listener); logCount = -1; render() }
    override fun onPause() { super.onPause(); LensState.removeListener(listener) }

    private fun render() {
        frames.text = LensState.framesSent.toString()
        latency.text = if (LensState.answerMs > 0) "${LensState.answerMs} ms" else "— ms"
        val q = LensState.queueBytes
        queue.text = if (q < 1024) q.toString() else "${q / 1024} KB"
        lastReq.text = LensState.lastRequest.ifBlank { "(nenhuma ainda)" }

        val lines = LensState.logLines()
        // The log is capped at 200 lines, so size alone stops changing; key on the last line too.
        val key = lines.size * 31 + System.identityHashCode(lines.lastOrNull())
        if (key == logCount) return
        logCount = key
        log.removeAllViews()
        val d = resources.displayMetrics.density
        lines.takeLast(100).forEach { l ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.dot)
                imageTintList = ColorStateList.valueOf(getColor(if (l.ok) R.color.green else R.color.terracota))
            }, LinearLayout.LayoutParams((8 * d).toInt(), (8 * d).toInt()))
            row.addView(TextView(this).apply {
                text = l.time; typeface = android.graphics.Typeface.MONOSPACE; textSize = 11f
                setTextColor(getColor(R.color.cream_74))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (10 * d).toInt() })
            row.addView(TextView(this).apply {
                text = l.text; textSize = 13f
                setTextColor(getColor(if (l.ok) R.color.cream else R.color.err_text))
                fontVariationSettings = if (l.ok) "'wght' 600" else "'wght' 700"
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (10 * d).toInt() })
            log.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * d).toInt() })
        }
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
