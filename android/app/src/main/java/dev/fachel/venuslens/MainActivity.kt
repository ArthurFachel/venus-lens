package dev.fachel.venuslens

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.view.PreviewView
import dev.fachel.venuslens.LensState.Phase

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var viewport: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var frozen: ImageView
    private lateinit var brackets: BracketsView
    private lateinit var offPanel: View
    private lateinit var offTitle: TextView
    private lateinit var offText: TextView
    private lateinit var listenPanel: View
    private lateinit var ring: View
    private lateinit var wave: WaveView
    private lateinit var listenText: TextView
    private lateinit var statusDot: ImageView
    private lateinit var statusCam: ImageView
    private lateinit var statusText: TextView
    private lateinit var infoPill: TextView
    private lateinit var discard: ImageButton
    private lateinit var respOverlay: View
    private lateinit var respMeta: TextView
    private lateinit var respText: TextView
    private lateinit var respQuestion: TextView
    private lateinit var photoPanel: View
    private lateinit var photoMeta: TextView
    private lateinit var photoQuestion: TextView
    private lateinit var photoAnswer: TextView
    private lateinit var controls: LinearLayout
    private lateinit var seg: View
    private lateinit var segStream: TextView
    private lateinit var segPhoto: TextView
    private lateinit var mainRow: View
    private lateinit var mainBtn: TextView
    private lateinit var secondaryBtn: TextView
    private lateinit var inputRow: View
    private lateinit var orType: TextView
    private lateinit var input: EditText
    private lateinit var mic: ImageButton
    private lateinit var send: ImageButton

    private var ringAnim: AnimatorSet? = null
    private var shownFrozen: ByteArray? = null
    private var previewAttached = false
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)
        bind()

        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        viewport.clipToOutline = true

        findViewById<View>(R.id.settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        segStream.setOnClickListener { setMode(Prefs.MODE_STREAM) }
        segPhoto.setOnClickListener { setMode(Prefs.MODE_PHOTO) }
        mainBtn.setOnClickListener { onMain() }
        secondaryBtn.setOnClickListener { onSecondary() }
        mic.setOnClickListener {
            if (!LensService.running) { toast("Ligue o assistente primeiro"); return@setOnClickListener }
            LensService.instance?.ask()
        }
        send.setOnClickListener { sendText() }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendText(); true } else false }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { styleSend() }
        })
        discard.setOnClickListener { LensService.instance?.discardPhoto() }
        findViewById<View>(R.id.copy).setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Venus", LensState.answer))
            toast("Resposta copiada")
        }
        findViewById<View>(R.id.listen).setOnClickListener { LensService.instance?.speakLast() }

        if (!hasPerms()) requestPermissions(PERMS, 1)
        if (prefs.token.isBlank()) startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        LensState.addListener(listener)
        render()
    }

    override fun onPause() {
        super.onPause()
        LensState.removeListener(listener)
        detachPreview()
    }

    // --- actions ----------------------------------------------------------------------------

    private fun setMode(m: String) {
        if (prefs.mode == m) return
        prefs.mode = m
        if (m == Prefs.MODE_STREAM) LensService.instance?.discardPhoto()
        LensState.log("modo: ${if (m == Prefs.MODE_STREAM) "streaming" else "foto única"}")
        render()
    }

    private fun onMain() {
        val phase = currentPhase()
        when {
            phase == Phase.LISTENING -> LensService.instance?.finishListening()
            !LensService.running -> {
                if (!hasPerms()) { requestPermissions(PERMS, 1); return }
                if (prefs.token.isBlank()) { startActivity(Intent(this, SettingsActivity::class.java)); return }
                LensService.start(this)
            }
            prefs.mode == Prefs.MODE_STREAM -> LensService.stop(this)
            else -> { // photo mode: "Tirar foto" / "Tirar nova foto" both freeze the current frame
                val svc = LensService.instance ?: return
                svc.capturePhoto()
                render()
            }
        }
    }

    private fun onSecondary() {
        if (currentPhase() == Phase.LISTENING) LensService.instance?.cancelListening()
        else LensService.stop(this) // photo mode "Desligar"
    }

    private fun sendText() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        val svc = LensService.instance
        if (svc == null) { toast("Ligue o assistente primeiro"); return }
        if (currentPhase() == Phase.LISTENING) svc.cancelListening()
        svc.askText(q)
        input.setText("")
        input.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
    }

    // --- rendering --------------------------------------------------------------------------

    private fun currentPhase() = if (LensService.running) LensState.phase else Phase.OFF

    private fun render() {
        val phase = currentPhase()
        val on = phase != Phase.OFF
        val stream = prefs.mode == Prefs.MODE_STREAM
        val listening = phase == Phase.LISTENING
        val frozenJpeg = LensState.frozenJpeg
        val photoFrozen = !stream && frozenJpeg != null && on

        // segmented control
        styleSeg(segStream, stream, R.drawable.seg_on)
        styleSeg(segPhoto, !stream, R.drawable.seg_on)

        // viewport sizing: photo mode with a frozen frame shares the space with the answer panel.
        // The mock uses a fixed 300dp photo on an 844dp screen; on a real phone (status + nav bars,
        // ~760dp usable) that left the answer one line tall, so split by weight instead (0.8 : 1).
        val vlp = viewport.layoutParams as LinearLayout.LayoutParams
        vlp.height = 0
        vlp.weight = if (photoFrozen && !listening) 0.8f else 1f
        viewport.layoutParams = vlp
        photoPanel.visibility = if (photoFrozen && !listening) View.VISIBLE else View.GONE

        // live preview
        val showLive = on && !listening && !photoFrozen
        if (showLive) attachPreview() else detachPreview()
        previewView.visibility = if (showLive) View.VISIBLE else View.GONE

        // frozen frame
        if (photoFrozen && frozenJpeg !== shownFrozen) {
            shownFrozen = frozenJpeg
            frozen.setImageBitmap(BitmapFactory.decodeByteArray(frozenJpeg, 0, frozenJpeg!!.size))
        }
        frozen.visibility = if (photoFrozen && !listening) View.VISIBLE else View.GONE
        discard.visibility = frozen.visibility

        offPanel.visibility = if (!on) View.VISIBLE else View.GONE
        if (!on) { offTitle.text = "Câmera desligada" }

        listenPanel.visibility = if (listening) View.VISIBLE else View.GONE
        if (listening) {
            wave.setLevel(LensState.micLevel)
            startRing()
            listenText.text = "Fale sua pergunta. Eu paro de ouvir quando você fizer uma pausa."
        } else stopRing()

        // pills
        val (dotColor, label) = when {
            !on -> R.color.white_55 to "Desligado"
            phase == Phase.CONNECTING -> R.color.warn to "CONECTANDO"
            phase == Phase.ERROR -> R.color.terracota to "ERRO"
            listening -> R.color.green to "AO VIVO"
            photoFrozen -> 0 to "FOTO CAPTURADA"
            stream -> R.color.green to "AO VIVO"
            else -> R.color.green to "PRONTO"
        }
        statusCam.visibility = if (dotColor == 0) View.VISIBLE else View.GONE
        statusDot.visibility = if (dotColor == 0) View.GONE else View.VISIBLE
        if (dotColor != 0) statusDot.imageTintList = color(dotColor)
        (statusText.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        statusText.text = label
        infoPill.visibility = if (on && stream && !listening) View.VISIBLE else View.GONE
        infoPill.text = "${prefs.fps} fps · ${prefs.resolution}p"
        brackets.visibility = if (showLive && stream) View.VISIBLE else View.GONE

        // answer surfaces
        val hasAnswer = LensState.answer.isNotBlank() || phase == Phase.THINKING
        val answerText = LensState.answer.ifBlank { if (phase == Phase.THINKING) "Pensando…" else "" }
        val q = LensState.question.takeIf { it.isNotBlank() && it != "…" }
        respOverlay.visibility = if (on && stream && hasAnswer && !listening) View.VISIBLE else View.GONE
        respText.text = answerText
        respQuestion.text = q?.let { "Pergunta: “$it”" } ?: ""
        respQuestion.visibility = if (q != null) View.VISIBLE else View.GONE
        respMeta.text = if (LensState.answerFrame > 0) "frame #${LensState.answerFrame}" else ""
        respOverlay.post { brackets.bottomInset = if (respOverlay.visibility == View.VISIBLE) respOverlay.height + dp(40).toFloat() else 0f }

        photoQuestion.text = q?.let { "Pergunta: “$it”" } ?: "Faça uma pergunta sobre esta foto por voz ou texto."
        photoAnswer.text = answerText
        photoMeta.text = if (phase == Phase.READY && LensState.answerMs > 0 && LensState.answer.isNotBlank()) "${LensState.answerMs} ms" else if (phase == Phase.THINKING) "…" else ""
        findViewById<View>(R.id.photoActions).visibility = if (LensState.answer.isNotBlank() && phase != Phase.THINKING) View.VISIBLE else View.GONE

        // controls
        renderControls(on, stream, listening, photoFrozen)
    }

    private fun renderControls(on: Boolean, stream: Boolean, listening: Boolean, photoFrozen: Boolean) {
        // order: listening -> [buttons, input]; photo(on) -> [seg, input, button]; else -> [seg, button, input]
        val order: List<View> = when {
            listening -> listOf(mainRow, inputRow)
            on && !stream -> listOf(seg, inputRow, mainRow)
            else -> listOf(seg, mainRow, inputRow)
        }
        if ((0 until controls.childCount).map { controls.getChildAt(it) } != order ||
            seg.visibility != (if (listening) View.GONE else View.VISIBLE)) {
            controls.removeAllViews()
            order.forEachIndexed { i, v ->
                (v.layoutParams as ViewGroup.MarginLayoutParams).topMargin = if (i == 0) 0 else dp(12)
                controls.addView(v)
            }
            seg.visibility = if (listening) View.GONE else View.VISIBLE
        }

        orType.visibility = if (listening) View.VISIBLE else View.GONE
        mic.visibility = if (listening) View.GONE else View.VISIBLE
        send.visibility = if (listening) View.GONE else if (on && !stream) View.GONE else View.VISIBLE
        input.hint = when {
            listening -> "Escreva uma pergunta…"
            photoFrozen -> "Perguntar outra coisa sobre esta foto…"
            else -> "Escreva uma pergunta…"
        }
        styleSend()

        when {
            listening -> {
                secondaryBtn.visibility = View.VISIBLE
                secondaryBtn.text = "Cancelar"
                styleMain("Enviar", R.drawable.ic_send, R.drawable.btn_gold, R.color.on_gold, 16f)
            }
            !on -> {
                secondaryBtn.visibility = View.GONE
                styleMain("Ligar assistente", R.drawable.ic_power, R.drawable.btn_gold, R.color.on_gold, 17f)
            }
            stream -> {
                secondaryBtn.visibility = View.GONE
                styleMain("Parar e desligar", R.drawable.ic_stop, R.drawable.btn_terracota, R.color.white, 17f)
            }
            else -> {
                secondaryBtn.visibility = View.VISIBLE
                secondaryBtn.text = "Desligar"
                styleMain(if (photoFrozen) "Tirar nova foto" else "Tirar foto", R.drawable.ic_camera,
                    R.drawable.btn_gold, R.color.on_gold, 17f)
            }
        }
        val lp = secondaryBtn.layoutParams as LinearLayout.LayoutParams
        lp.weight = if (listening) 1f else 0.55f
        secondaryBtn.layoutParams = lp
    }

    private fun styleMain(text: String, icon: Int, bg: Int, fg: Int, size: Float) {
        mainBtn.text = text
        mainBtn.textSize = size
        mainBtn.setBackgroundResource(bg)
        mainBtn.setTextColor(getColor(fg))
        val d = getDrawable(icon)!!.mutate()
        val s = dp(20)
        d.setBounds(0, 0, s, s)
        d.setTint(getColor(fg))
        mainBtn.setCompoundDrawables(d, null, null, null)
    }

    private fun styleSeg(v: TextView, on: Boolean, bg: Int) {
        if (on) v.setBackgroundResource(bg) else v.background = null
        val fg = getColor(if (on) R.color.bg else R.color.cream_74)
        v.setTextColor(fg)
        v.fontVariationSettings = if (on) "'wght' 800" else "'wght' 700"
        v.compoundDrawablesRelative[0]?.let { d ->
            val m = d.mutate(); val s = dp(18); m.setBounds(0, 0, s, s); m.setTint(fg)
            v.setCompoundDrawablesRelative(m, null, null, null)
        }
    }

    private fun styleSend() {
        val has = input.text.isNotBlank()
        send.setBackgroundResource(if (has) R.drawable.sq_soft else R.drawable.sq_outline)
        send.imageTintList = color(if (has) R.color.cream else R.color.cream_50)
        input.isActivated = has
    }

    private fun attachPreview() {
        if (previewAttached) return
        val svc = LensService.instance ?: return
        svc.setPreviewSurface(previewView.surfaceProvider)
        previewAttached = true
    }

    private fun detachPreview() {
        if (!previewAttached) return
        LensService.instance?.setPreviewSurface(null)
        previewAttached = false
    }

    private fun startRing() {
        if (ringAnim != null) return
        ringAnim = AnimatorSet().apply {
            val sx = ObjectAnimator.ofFloat(ring, View.SCALE_X, 1f, 1.45f)
            val sy = ObjectAnimator.ofFloat(ring, View.SCALE_Y, 1f, 1.45f)
            val a = ObjectAnimator.ofFloat(ring, View.ALPHA, 1f, 0f)
            listOf(sx, sy, a).forEach { it.repeatCount = ValueAnimator.INFINITE; it.duration = 2000 }
            playTogether(sx, sy, a)
            start()
        }
    }

    private fun stopRing() { ringAnim?.cancel(); ringAnim = null }

    // --- helpers ----------------------------------------------------------------------------

    private fun bind() {
        viewport = findViewById(R.id.viewport); previewView = findViewById(R.id.preview)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        frozen = findViewById(R.id.frozen); brackets = findViewById(R.id.brackets)
        offPanel = findViewById(R.id.offPanel); offTitle = findViewById(R.id.offTitle); offText = findViewById(R.id.offText)
        listenPanel = findViewById(R.id.listenPanel); ring = findViewById(R.id.ring); wave = findViewById(R.id.wave)
        listenText = findViewById(R.id.listenText)
        statusDot = findViewById(R.id.statusDot); statusCam = findViewById(R.id.statusCam); statusText = findViewById(R.id.statusText)
        infoPill = findViewById(R.id.infoPill); discard = findViewById(R.id.discard)
        respOverlay = findViewById(R.id.respOverlay); respMeta = findViewById(R.id.respMeta)
        respText = findViewById(R.id.respText); respQuestion = findViewById(R.id.respQuestion)
        photoPanel = findViewById(R.id.photoPanel); photoMeta = findViewById(R.id.photoMeta)
        photoQuestion = findViewById(R.id.photoQuestion); photoAnswer = findViewById(R.id.photoAnswer)
        controls = findViewById(R.id.controls); seg = findViewById(R.id.seg)
        segStream = findViewById(R.id.segStream); segPhoto = findViewById(R.id.segPhoto)
        mainRow = findViewById(R.id.mainRow); mainBtn = findViewById(R.id.mainBtn); secondaryBtn = findViewById(R.id.secondaryBtn)
        inputRow = findViewById(R.id.inputRow); orType = findViewById(R.id.orType)
        input = findViewById(R.id.input); mic = findViewById(R.id.mic); send = findViewById(R.id.send)
    }

    private fun hasPerms() = PERMS.take(2).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun color(id: Int) = ColorStateList.valueOf(getColor(id))
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private val PERMS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS)
    }
}
