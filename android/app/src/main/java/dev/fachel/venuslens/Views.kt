package dev.fachel.venuslens

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Seven rounded bars; height follows the real mic level plus a travelling wave. */
class WaveView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt() }
    private val rect = RectF()
    private var phase = 0f
    private var level = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    fun setLevel(v: Float) { level = level * 0.6f + v * 0.4f }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) anim.start() else anim.cancel()
    }

    override fun onDraw(c: Canvas) {
        val bars = 7
        val d = resources.displayMetrics.density
        val bw = 5 * d
        val gap = (width - bars * bw) / (bars - 1)
        for (i in 0 until bars) {
            val wave = (sin(2 * PI * (phase - i * 0.11)).toFloat() + 1f) / 2f // 0..1
            val amp = 0.25f + 0.75f * max(level * 1.4f, 0.15f).coerceAtMost(1f)
            val h = height * (0.18f + 0.82f * wave * amp)
            val x = i * (bw + gap)
            rect.set(x, (height - h) / 2, x + bw, (height + h) / 2)
            c.drawRoundRect(rect, 3 * d, 3 * d, paint)
        }
    }
}

/** Four corner marks framing the live preview. */
class BracketsView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 2 * d
        strokeCap = Paint.Cap.ROUND
    }
    /** Space kept free at the bottom for the answer card. */
    var bottomInset = 0f
        set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        val s = 32 * d
        val r = 6 * d
        val l = 28 * d
        val t = 70 * d
        val rgt = width - 28 * d
        val b = height - max(bottomInset, 40 * d)
        if (b - t < 3 * s) return
        corner(c, l, t, 1, 1, s, r)
        corner(c, rgt, t, -1, 1, s, r)
        corner(c, l, b, 1, -1, s, r)
        corner(c, rgt, b, -1, -1, s, r)
    }

    private fun corner(c: Canvas, x: Float, y: Float, dx: Int, dy: Int, s: Float, r: Float) {
        val p = android.graphics.Path()
        p.moveTo(x, y + dy * s)
        p.lineTo(x, y + dy * r)
        p.quadTo(x, y, x + dx * r, y)
        p.lineTo(x + dx * s, y)
        c.drawPath(p, paint)
    }
}
