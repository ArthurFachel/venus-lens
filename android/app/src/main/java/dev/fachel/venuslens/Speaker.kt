package dev.fachel.venuslens

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Speaks streamed text sentence by sentence so the first words come out before the answer ends. */
class Speaker(ctx: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(ctx.applicationContext, this)
    private var ready = false
    private val pending = StringBuilder()
    private val queuedBeforeReady = mutableListOf<String>()
    private var seq = 0

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale.forLanguageTag("pt-BR")
        tts.setSpeechRate(1.1f)
        ready = true
        queuedBeforeReady.forEach { say(it) }
        queuedBeforeReady.clear()
    }

    fun feed(delta: String) {
        pending.append(delta)
        while (true) {
            val idx = sentenceEnd(pending) ?: break
            val sentence = pending.substring(0, idx + 1).trim()
            pending.delete(0, idx + 1)
            if (sentence.isNotEmpty()) say(sentence)
        }
    }

    fun flush() {
        val rest = pending.toString().trim()
        pending.clear()
        if (rest.isNotEmpty()) say(rest)
    }

    fun stop() {
        pending.clear()
        queuedBeforeReady.clear()
        if (ready) tts.stop()
    }

    fun say(text: String) {
        if (!ready) { queuedBeforeReady += text; return }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "u${seq++}")
    }

    fun shutdown() { tts.stop(); tts.shutdown() }

    private fun sentenceEnd(sb: StringBuilder): Int? {
        for (i in 0 until sb.length - 1) {
            val c = sb[i]
            if ((c == '.' || c == '!' || c == '?') && sb[i + 1].isWhitespace()) return i
        }
        return null
    }
}
