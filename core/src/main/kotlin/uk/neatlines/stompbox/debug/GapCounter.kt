package uk.neatlines.stompbox.debug

/**
 * Counts gaps between events that exceed [thresholdMs] and remembers the longest one. Written by
 * one thread, read (for display) by any.
 */
class GapCounter(val thresholdMs: Double) {
    @Volatile var count = 0; private set
    @Volatile var longestMs = 0.0; private set
    private var last = 0L

    /** An event at [nanos]; the first one after [restart] only sets the starting point. */
    fun mark(nanos: Long) {
        if (last != 0L) record((nanos - last) / 1e6)
        last = nanos
    }

    /** A gap measured some other way. */
    fun record(gapMs: Double) {
        if (gapMs > thresholdMs) count++
        if (gapMs > longestMs) longestMs = gapMs
    }

    /** Forget the last event, so a pause (reconnect, app in background) isn't counted. */
    fun restart() { last = 0L }

    fun reset() { restart(); count = 0; longestMs = 0.0 }

    /** "3 >250 ms (longest 410 ms)". */
    fun summary() = "%d >%.0f ms (longest %.0f ms)".format(count, thresholdMs, longestMs)
}
