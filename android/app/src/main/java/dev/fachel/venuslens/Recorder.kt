package dev.fachel.venuslens

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Records one utterance as 16 kHz mono WAV. Stops after trailing silence, at maxMs, when
 * [finish] is called (send now), or returns null on [cancel] / nobody spoke.
 * [onLevel] gets a 0..1 loudness value every 20 ms for the waveform.
 */
class Recorder(
    private val onLevel: (Float) -> Unit = {},
    private val maxMs: Int = 15_000,
    private val noSpeechMs: Int = 6_000,
    private val trailingSilenceMs: Int = 1_100,
) {
    @Volatile private var cancelled = false
    @Volatile private var finished = false

    fun cancel() { cancelled = true }
    fun finish() { finished = true }

    @SuppressLint("MissingPermission") // checked before the service starts
    fun record(): ByteArray? {
        val rate = 16_000
        val frame = rate / 50 // 20 ms
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, frame * 2 * 4))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        val pcm = ByteArrayOutputStream()
        val buf = ShortArray(frame)
        var elapsed = 0
        var noise = 0.0
        var calib = 0
        var speech = false
        var silence = 0
        try {
            rec.startRecording()
            while (!cancelled && !finished && elapsed < maxMs) {
                val n = rec.read(buf, 0, frame)
                if (n <= 0) break
                elapsed += n * 1000 / rate
                val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                var sum = 0.0
                for (i in 0 until n) { bb.putShort(buf[i]); sum += buf[i].toDouble() * buf[i] }
                pcm.write(bb.array())
                val rms = sqrt(sum / n)
                onLevel(min(1f, (rms / 4000.0).toFloat()))
                if (calib < 15) { noise += rms; calib++; if (calib == 15) noise /= 15; continue }
                val threshold = max(noise * 3.0, 500.0)
                if (rms > threshold) { speech = true; silence = 0 } else if (speech) silence += 20
                if (speech && silence >= trailingSilenceMs) break
                if (!speech && elapsed >= noSpeechMs) return null
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            onLevel(0f)
        }
        if (cancelled) return null
        // "Enviar" pressed: send what we have even if the VAD never fired (quiet speaker).
        if (!speech && !(finished && elapsed > 600)) return null
        return wav(pcm.toByteArray(), rate)
    }

    private fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }
}
