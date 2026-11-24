package uk.neatlines.stompbox.debug

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import uk.neatlines.stompbox.stomp.DebugFrame

/**
 * Scrolling trace of the last few seconds of detector internals:
 *  - white: the feature being thresholded (g), with the threshold dashed in red;
 *  - a green dot on each trigger.
 * Feed it from any thread with [add]; it redraws at display rate.
 */
class StompPlotView(context: Context) : View(context) {
    private val capacity = 200 * 4                        // 4 s at 200 Hz
    private val frames = arrayOfNulls<DebugFrame>(capacity)
    private var head = 0
    var maxG = 4.0

    private val featurePaint = paint(Color.WHITE, 3f)
    private val thresholdPaint = paint(Color.RED, 2f).apply { pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f) }
    private val gridPaint = paint(Color.DKGRAY, 1f)
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 28f }

    fun add(f: DebugFrame) { synchronized(frames) { frames[head] = f; head = (head + 1) % capacity } }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); postOnAnimation(tick) }
    private val tick = object : Runnable { override fun run() { invalidate(); if (isAttachedToWindow) postOnAnimation(this) } }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val w = width.toFloat(); val h = height.toFloat()
        val snap = synchronized(frames) { Array(capacity) { frames[(head + it) % capacity] } }
        fun x(i: Int) = i * w / (capacity - 1)
        fun yG(g: Double) = (h - (g / maxG).coerceIn(0.0, 1.0) * h).toFloat()
        for (k in 1..3) c.drawLine(0f, yG(maxG * k / 4), w, yG(maxG * k / 4), gridPaint)
        for (s in 1..3) c.drawLine(w * s / 4, 0f, w * s / 4, h, gridPaint)       // 1 s grid
        val feature = Path(); val thr = Path()
        var started = false
        snap.forEachIndexed { i, f ->
            f ?: return@forEachIndexed
            if (!started) { feature.moveTo(x(i), yG(f.feature)); thr.moveTo(x(i), yG(f.threshold)); started = true }
            else { feature.lineTo(x(i), yG(f.feature)); thr.lineTo(x(i), yG(f.threshold)) }
        }
        c.drawPath(thr, thresholdPaint); c.drawPath(feature, featurePaint)
        snap.forEachIndexed { i, f ->
            if (f?.triggered == true) { c.drawCircle(x(i), yG(f.feature), 12f, marker) }
        }
        c.drawText("feature (0–%.0f g)   threshold (dashed)   1 s grid".format(maxG), 12f, 32f, text)
    }

    private fun paint(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; strokeWidth = width; style = Paint.Style.STROKE
    }
}
