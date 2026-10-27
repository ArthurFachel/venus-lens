package dev.fachel.venuslens

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** UI-facing state shared by the service and the activities. Listeners run on the main thread. */
object LensState {
    enum class Phase { OFF, CONNECTING, READY, LISTENING, THINKING, ERROR }

    data class LogLine(val time: String, val ok: Boolean, val text: String)

    @Volatile var phase = Phase.OFF
        private set
    @Volatile var detail = ""
        private set

    // current exchange
    @Volatile var question = ""
    @Volatile var answer = ""
    @Volatile var answerFrame = 0
    @Volatile var answerMs = 0

    // listening
    @Volatile var micLevel = 0f

    // photo mode: the frozen frame questions refer to
    @Volatile var frozenJpeg: ByteArray? = null

    // debug
    @Volatile var framesSent = 0
    @Volatile var queueBytes = 0L
    @Volatile var lastRequest = ""
    private val log = ArrayDeque<LogLine>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var posted = false

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun set(p: Phase, d: String = "") { phase = p; detail = d; changed() }

    fun log(text: String, ok: Boolean = true) {
        synchronized(log) {
            log.addLast(LogLine(fmt.format(Date()), ok, text))
            while (log.size > 200) log.removeFirst()
        }
        changed()
    }

    fun logLines(): List<LogLine> = synchronized(log) { log.toList() }

    /** Coalesces bursts (stream deltas, mic levels) into one UI pass per frame. */
    fun changed() {
        synchronized(this) { if (posted) return; posted = true }
        main.post {
            synchronized(this) { posted = false }
            listeners.toList().forEach { it() }
        }
    }
}
