package uk.neatlines.stompbox.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import uk.neatlines.stompbox.debug.GapCounter

/**
 * Counts UI freezes: a heartbeat posted to the main thread every [PERIOD_MS] that runs more than
 * 200 ms late. Runs only while one of the app's screens is in front (see [install]).
 */
object UiStallWatch {
    private const val PERIOD_MS = 100L
    val freezes = GapCounter(thresholdMs = 200.0)

    private val main = Handler(Looper.getMainLooper())
    private var due = 0L
    private var resumed = 0
    private val beat = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            freezes.record((now - due).toDouble())
            due = now + PERIOD_MS
            main.postDelayed(this, PERIOD_MS)
        }
    }

    /** Starts and stops the heartbeat as the app's screens come and go. */
    fun install(app: Application) = app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: Activity) {
            if (resumed++ == 0) { due = SystemClock.uptimeMillis() + PERIOD_MS; main.postDelayed(beat, PERIOD_MS) }
        }
        override fun onActivityPaused(a: Activity) { if (--resumed == 0) main.removeCallbacks(beat) }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    })
}
