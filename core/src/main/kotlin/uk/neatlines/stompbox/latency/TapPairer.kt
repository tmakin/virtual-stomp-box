package uk.neatlines.stompbox.latency

/**
 * Pairs impact sounds heard by the microphone with sensor events that arrived in the app,
 * and reports the delay between them.
 *
 * Pairing is done after the run, not live, because microphone audio reaches the app in
 * buffers some milliseconds after it was captured, so a sensor event can arrive before the
 * onset that caused it has been detected.
 *
 * Both lists must use the same clock (System.nanoTime / CLOCK_MONOTONIC). Each event pairs with
 * the earliest unpaired onset in the window [event − [lateNanos], event + [earlyNanos]]. A slightly
 * negative delay is allowed because sound reaches the mic ~0.3 ms per 10 cm after the impact.
 */
object TapPairer {

    data class Result(
        val delaysMs: List<Double>,
        /** Impacts heard by the mic with no sensor event: missed taps. */
        val missed: Int,
        /** Sensor events with no impact sound: false triggers. */
        val falseTriggers: Int,
    ) {
        val stats: LatencyStats? get() = LatencyStats.of(delaysMs)
    }

    fun pair(
        onsetNanos: List<Long>,
        eventNanos: List<Long>,
        earlyNanos: Long = 3_000_000L,
        lateNanos: Long = 60_000_000L,
    ): Result {
        val onsets = onsetNanos.sorted()
        val events = eventNanos.sorted()
        val delays = ArrayList<Double>()
        var missed = 0
        var falseTriggers = 0
        var i = 0
        for (e in events) {
            // Onsets too old to belong to this or any later event were missed.
            while (i < onsets.size && e - onsets[i] > lateNanos) { missed++; i++ }
            if (i < onsets.size && e - onsets[i] >= -earlyNanos) {
                delays += (e - onsets[i]) / 1e6
                i++
            } else {
                falseTriggers++
            }
        }
        missed += onsets.size - i
        return Result(delays, missed, falseTriggers)
    }
}
