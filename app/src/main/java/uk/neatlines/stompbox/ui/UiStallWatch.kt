package uk.neatlines.stompbox.ui

import android.view.Choreographer

/**
 * Counts UI freezes: gaps between display frames over [FREEZE_MS] while a screen is showing.
 * Each screen calls [start] in onResume and [stop] in onPause, so time in the background isn't counted.
 */
object UiStallWatch : Choreographer.FrameCallback {
    private const val FREEZE_MS = 200.0
    @Volatile var freezes = 0; private set
    @Volatile var longestMs = 0.0; private set
    private var last = 0L
    private var running = false

    fun start() { if (!running) { running = true; last = 0L; Choreographer.getInstance().postFrameCallback(this) } }
    fun stop() { running = false; Choreographer.getInstance().removeFrameCallback(this) }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (last != 0L) {
            val gap = (frameTimeNanos - last) / 1e6
            if (gap > FREEZE_MS) freezes++
            if (gap > longestMs) longestMs = gap
        }
        last = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }
}
