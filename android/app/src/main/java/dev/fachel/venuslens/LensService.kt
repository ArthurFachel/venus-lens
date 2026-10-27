package dev.fachel.venuslens

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.PowerManager
import android.service.quicksettings.TileService
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class LensService : LifecycleService() {

    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private var socket: LensSocket? = null
    private var camera: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var media: MediaSession? = null
    private var wake: PowerManager.WakeLock? = null
    private val analysisExec = Executors.newSingleThreadExecutor()
    private val audioExec = Executors.newSingleThreadExecutor()
    private val recording = AtomicBoolean(false)
    private var recorder: Recorder? = null
    private var tone: ToneGenerator? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var screenOff = false

    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile private var latestAt = 0L
    private var lastEncodeAt = 0L
    private var lastSentAt = 0L
    private var lastThumb: IntArray? = null
    private var askStartedAt = 0L
    private var statusLine = "Conectando..."

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOff = i.action == Intent.ACTION_SCREEN_OFF
            if (prefs.pauseScreenOff && prefs.mode == Prefs.MODE_STREAM) {
                LensState.log(if (screenOff) "tela apagada · stream pausado" else "tela acesa · stream retomado")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        speaker = Speaker(this)
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_ASK -> { if (running) ask(); return START_NOT_STICKY }
        }
        if (running) return START_NOT_STICKY
        startForeground(NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        running = true
        instance = this
        LensState.framesSent = 0
        LensState.answer = ""
        LensState.question = ""
        LensState.frozenJpeg = null
        LensState.set(LensState.Phase.CONNECTING, "Conectando ao servidor")
        LensState.log("assistente ligado · modo ${if (prefs.mode == Prefs.MODE_STREAM) "streaming" else "foto única"}")
        TileService.requestListeningState(this, ComponentName(this, LensTile::class.java))
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "venuslens:run").apply { acquire(4 * 3600_000L) }
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
        })
        startSocket()
        watchNetwork()
        startCamera()
        if (prefs.headsetButton) startMediaSession()
        return START_NOT_STICKY
    }

    // --- camera -----------------------------------------------------------------------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            camera = future.get()
            val target = if (prefs.longSide > 1280) Size(1920, 1080) else Size(1280, 720)
            analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                ).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also { it.setAnalyzer(analysisExec) { img -> onFrame(img) } }
            bindCamera()
            LensState.log("câmera aberta · ${prefs.resolution}p")
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = camera ?: return
        val a = analysis ?: return
        try {
            provider.unbindAll()
            val p = preview
            if (p != null) provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, a, p)
            else provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, a)
        } catch (e: Exception) {
            Log.e(TAG, "camera bind failed", e)
            LensState.log("câmera falhou: ${e.message}", ok = false)
            setStatus("Câmera falhou", LensState.Phase.ERROR)
        }
    }

    /** The activity shows the live image while it is visible; null detaches it. */
    fun setPreviewSurface(provider: Preview.SurfaceProvider?) {
        preview = provider?.let { sp -> Preview.Builder().build().also { it.surfaceProvider = sp } }
        bindCamera()
    }

    private fun onFrame(img: ImageProxy) {
        img.use {
            val now = System.currentTimeMillis()
            val stream = prefs.mode == Prefs.MODE_STREAM
            val interval = if (stream) 1000L / prefs.fps.coerceIn(1, 10) else 1000L
            if (now - lastEncodeAt < interval) return
            lastEncodeAt = now
            val bmp = scaled(it) ?: return
            val jpeg = jpeg(bmp)
            latestJpeg = jpeg
            latestAt = now
            if (stream && !(prefs.pauseScreenOff && screenOff) && !recording.get()) {
                val changed = !prefs.onlyOnSceneChange || sceneChanged(bmp)
                if (changed && socket?.connected == true && socket?.sendFrame(jpeg) == true) {
                    lastSentAt = now
                    LensState.framesSent++
                }
            }
            bmp.recycle()
            LensState.queueBytes = socket?.queueBytes() ?: 0
            LensState.changed()
        }
    }

    private fun scaled(img: ImageProxy): Bitmap? = runCatching {
        val src = img.toBitmap()
        val rot = img.imageInfo.rotationDegrees
        val scale = prefs.longSide.toFloat() / maxOf(src.width, src.height)
        val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); if (rot != 0) postRotate(rot.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        out
    }.onFailure { Log.w(TAG, "scale failed", it) }.getOrNull()

    private fun jpeg(bmp: Bitmap): ByteArray = ByteArrayOutputStream().use { bos ->
        bmp.compress(Bitmap.CompressFormat.JPEG, 78, bos)
        bos.toByteArray()
    }

    /** Mean absolute difference of a 16x12 grey thumbnail against the last frame sent. */
    private fun sceneChanged(bmp: Bitmap): Boolean {
        val t = Bitmap.createScaledBitmap(bmp, 16, 12, true)
        val px = IntArray(16 * 12)
        t.getPixels(px, 0, 16, 0, 0, 16, 12)
        t.recycle()
        val grey = IntArray(px.size) { i -> val c = px[i]; ((c shr 16 and 255) * 3 + (c shr 8 and 255) * 6 + (c and 255)) / 10 }
        val prev = lastThumb
        val diff = if (prev == null) 255.0 else grey.indices.sumOf { abs(grey[it] - prev[it]) }.toDouble() / grey.size
        return if (diff > 10.0) { lastThumb = grey; true } else false
    }

    // --- network ----------------------------------------------------------------------------

    private fun startSocket() {
        socket = LensSocket(prefs.url, prefs.token, ::onServer) { up ->
            if (up) { LensState.log("conectado ao servidor"); setStatus("Pronta", LensState.Phase.READY) }
            else {
                if (LensState.phase != LensState.Phase.CONNECTING) LensState.log("falha de rede · reconectando", ok = false)
                setStatus("Sem conexão, tentando de novo", LensState.Phase.CONNECTING)
            }
        }.also { it.connect() }
    }

    /**
     * Wi-Fi changes (home <-> eduroam <-> mobile data) kill the TCP socket silently; reconnect as
     * soon as a new default network shows up. The Tailscale IP is the same on every network.
     */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        var current: Network? = cm.activeNetwork
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network == current) return
                current = network
                LensState.log("rede mudou · reconectando")
                socket?.reconnectNow()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && socket?.connected == false) {
                    socket?.reconnectNow()
                }
            }

            override fun onLost(network: Network) {
                if (network == current) setStatus("Sem rede", LensState.Phase.CONNECTING)
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        netCallback = cb
    }

    private fun onServer(m: JSONObject) {
        when (m.optString("type")) {
            "transcript" -> {
                LensState.question = m.optString("text")
                LensState.log("pergunta por voz transcrita")
                LensState.changed()
            }
            "delta" -> {
                val t = m.optString("text")
                if (prefs.speak) speaker.feed(t)
                LensState.answer += t
                LensState.changed()
            }
            "answer" -> {
                if (prefs.speak) speaker.flush()
                LensState.answer = m.optString("text")
                LensState.question = m.optString("question", LensState.question)
                LensState.answerMs = m.optJSONObject("latency_ms")?.optInt("total_ms")
                    ?: (System.currentTimeMillis() - askStartedAt).toInt()
                LensState.log("resposta recebida · ${LensState.answerMs} ms")
                setStatus("Pronta", LensState.Phase.READY)
            }
            "error" -> {
                speaker.stop()
                if (prefs.speak) speaker.say("Deu erro.")
                LensState.answer = ""
                LensState.log("erro do servidor: ${m.optString("message")}", ok = false)
                setStatus(m.optString("message"), LensState.Phase.ERROR)
            }
        }
    }

    // --- asking -----------------------------------------------------------------------------

    private fun freshFrame(): ByteArray? =
        latestJpeg?.takeIf { System.currentTimeMillis() - latestAt < 3000 }

    /** Frame a question refers to: the frozen photo in photo mode (taken now if needed). */
    private fun questionFrame(): ByteArray? {
        if (prefs.mode == Prefs.MODE_PHOTO) {
            if (LensState.frozenJpeg == null) LensState.frozenJpeg = freshFrame()
            return LensState.frozenJpeg
        }
        return freshFrame()
    }

    fun capturePhoto() {
        LensState.frozenJpeg = freshFrame() ?: latestJpeg
        LensState.answer = ""
        LensState.question = ""
        LensState.log(if (LensState.frozenJpeg != null) "foto capturada" else "sem imagem para capturar", LensState.frozenJpeg != null)
    }

    fun discardPhoto() {
        LensState.frozenJpeg = null
        LensState.answer = ""
        LensState.question = ""
        LensState.changed()
    }

    fun speakLast() {
        speaker.stop()
        LensState.answer.takeIf { it.isNotBlank() }?.let { speaker.say(it) }
    }

    fun ask() {
        if (!recording.compareAndSet(false, true)) { recorder?.finish(); return } // 2nd press sends
        speaker.stop()
        val frame = questionFrame() // snapshot at the moment of the tap, not after speaking
        LensState.answer = ""
        LensState.question = ""
        setStatus("Ouvindo", LensState.Phase.LISTENING)
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        audioExec.execute {
            val r = Recorder(onLevel = { LensState.micLevel = it; LensState.changed() }).also { recorder = it }
            val wav = runCatching { r.record() }.onFailure { Log.e(TAG, "record", it) }.getOrNull()
            recording.set(false)
            recorder = null
            tone?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
            val s = socket
            when {
                wav == null -> setStatus("Pronta", LensState.Phase.READY)
                s == null || !s.connected -> {
                    LensState.log("sem conexão para enviar a pergunta", ok = false)
                    setStatus("Sem conexão", LensState.Phase.CONNECTING)
                    if (prefs.speak) speaker.say("Estou sem conexão.")
                }
                else -> {
                    sendWithFrame(s, frame, voiceBytes = wav.size, text = null)
                    s.sendAudio(wav)
                    LensState.question = "…"
                    setStatus("Pensando", LensState.Phase.THINKING)
                }
            }
        }
    }

    fun finishListening() { recorder?.finish() }
    fun cancelListening() { recorder?.cancel() }

    fun askText(q: String) {
        val s = socket ?: return
        speaker.stop()
        LensState.question = q
        LensState.answer = ""
        if (!s.connected) { setStatus("Sem conexão", LensState.Phase.CONNECTING); return }
        sendWithFrame(s, questionFrame(), voiceBytes = 0, text = q)
        s.sendText(q)
        LensState.log("pergunta por texto enviada")
        setStatus("Pensando", LensState.Phase.THINKING)
    }

    private fun sendWithFrame(s: LensSocket, frame: ByteArray?, voiceBytes: Int, text: String?) {
        askStartedAt = System.currentTimeMillis()
        if (frame != null && s.sendFrame(frame)) LensState.framesSent++
        LensState.answerFrame = LensState.framesSent
        LensState.lastRequest = JSONObject().apply {
            put("rota", "WS " + prefs.url.substringAfter("//").substringAfter("/", "").let { "/$it" })
            put("modo", if (prefs.mode == Prefs.MODE_STREAM) "streaming" else "foto")
            if (prefs.mode == Prefs.MODE_STREAM) put("fps", prefs.fps)
            put("mime", "image/jpeg")
            put("frame_bytes", frame?.size ?: 0)
            put("largura", prefs.longSide)
            if (text != null) put("pergunta", text) else put("audio_wav_bytes", voiceBytes)
        }.toString(2)
    }

    private fun startMediaSession() {
        media = MediaSession(this, "VenusLens").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(intent: Intent): Boolean {
                    val ev = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    if (ev?.action == KeyEvent.ACTION_UP && ev.keyCode in HEADSET_KEYS) { ask(); return true }
                    return ev?.keyCode in HEADSET_KEYS
                }
            })
            setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PAUSED, 0, 1f).build())
            isActive = true
        }
    }

    // --- notification / state ---------------------------------------------------------------

    private fun setStatus(s: String, phase: LensState.Phase) {
        statusLine = s
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        LensState.set(phase, s)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Venus Lens", NotificationManager.IMPORTANCE_LOW))
        }
        fun svc(action: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, LensService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val last = LensState.answer
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_lens)
            .setContentTitle("Venus ligada")
            .setContentText(statusLine)
            .setStyle(Notification.BigTextStyle().bigText(
                listOf(statusLine, last).filter { it.isNotBlank() }.joinToString("\n")))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Perguntar", svc(ACTION_ASK, 1)).build())
            .addAction(Notification.Action.Builder(null, "Desligar", svc(ACTION_STOP, 2)).build())
            .build()
    }

    override fun onDestroy() {
        running = false
        instance = null
        recorder?.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        media?.release()
        camera?.unbindAll()
        socket?.close()
        speaker.shutdown()
        tone?.release()
        wake?.takeIf { it.isHeld }?.release()
        analysisExec.shutdown()
        audioExec.shutdown()
        latestJpeg = null
        LensState.frozenJpeg = null
        LensState.micLevel = 0f
        LensState.log("assistente desligado")
        LensState.set(LensState.Phase.OFF)
        TileService.requestListeningState(this, ComponentName(this, LensTile::class.java))
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }

    companion object {
        private const val TAG = "LensService"
        private const val CHANNEL = "lens"
        private const val NOTIF_ID = 7
        const val ACTION_STOP = "dev.fachel.venuslens.STOP"
        const val ACTION_ASK = "dev.fachel.venuslens.ASK"
        private val HEADSET_KEYS = setOf(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)

        @Volatile var running = false
            private set
        /** Same process: the activity calls the running service directly. */
        @Volatile var instance: LensService? = null
            private set

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, LensService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, LensService::class.java).setAction(ACTION_STOP))
    }
}
