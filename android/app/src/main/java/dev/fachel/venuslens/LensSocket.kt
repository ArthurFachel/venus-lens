package dev.fachel.venuslens

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** WebSocket to the backend with automatic reconnect. Callbacks arrive on the main thread. */
class LensSocket(
    private val url: String,
    private val token: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onState: (Boolean) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var closed = false
    private var backoffMs = 1000L

    @Volatile var connected = false
        private set

    fun connect() {
        closed = false
        val req = Request.Builder().url(url).header("X-API-Key", token).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== ws) return
                connected = true
                backoffMs = 1000L
                main.post { onState(true) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== ws) return
                val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
                main.post { onMessage(obj) }
            }

            // Callbacks from a socket we already replaced (network change) are ignored.
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket === ws) drop("closed $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket === ws) drop("failure ${response?.code} ${t.message}")
            }
        })
    }

    private fun drop(why: String) {
        Log.w(TAG, why)
        connected = false
        main.post { onState(false) }
        if (closed) return
        main.postDelayed({ if (!closed) connect() }, backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
    }

    /** Network changed (Wi-Fi <-> eduroam <-> 4G): drop the old socket and dial again now. */
    fun reconnectNow() {
        if (closed) return
        main.removeCallbacksAndMessages(null)
        val old = ws
        ws = null
        old?.cancel()
        connected = false
        backoffMs = 1000L
        main.post { onState(false) }
        connect()
    }

    /** Bytes OkHttp still has to push out (frames waiting on a slow uplink). */
    fun queueBytes(): Long = ws?.queueSize() ?: 0L

    fun sendFrame(jpeg: ByteArray): Boolean = sendBinary(KIND_FRAME, jpeg)
    fun sendAudio(wav: ByteArray): Boolean = sendBinary(KIND_AUDIO, wav)

    fun sendText(question: String): Boolean =
        ws?.send(JSONObject().put("type", "ask").put("text", question).toString()) ?: false

    private fun sendBinary(kind: Int, data: ByteArray): Boolean {
        val buf = ByteArray(data.size + 1)
        buf[0] = kind.toByte()
        System.arraycopy(data, 0, buf, 1, data.size)
        return ws?.send(buf.toByteString()) ?: false
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        ws?.close(1000, "bye")
        ws = null
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        private const val TAG = "LensSocket"
        const val KIND_FRAME = 0x01
        const val KIND_AUDIO = 0x02
    }
}
