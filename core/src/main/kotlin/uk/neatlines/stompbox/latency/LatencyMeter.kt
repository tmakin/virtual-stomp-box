package uk.neatlines.stompbox.latency

/**
 * Live end-to-end latency from the phone's microphone, with no recording to export.
 *
 * Put the phone on the floor near the foot and turn the sound on. For each stomp the mic hears
 * two sounds: the foot hitting the floor, then the app's drum from the speaker. With the app's
 * own times for "stomp detected" and "play() called", that splits the delay in three:
 *
 *  - **detect**: impact heard → stomp detected in the app (sensor, BLE, detector);
 *  - **output**: play() → drum heard (audio buffer, mixer, speaker);
 *  - **total**: impact heard → drum heard. Both are mic times, so the mic's own input delay
 *    cancels and this one is exact. detect and output each carry that input delay (a few ms) with
 *    opposite signs.
 *
 * Feed it from any thread; call [poll] now and then (the UI refresh). A stomp is scored once
 * [settleNanos] have passed, so late mic onsets are in. Stomp at most about once a second, or an
 * impact can be paired with the previous stomp's drum.
 */
class LatencyMeter(
    private val searchNanos: Long = 1_000_000_000L,
    private val settleNanos: Long = 1_200_000_000L,
    private val keep: Int = 30,
    private val clusterNanos: Long = 80_000_000L,
) {
    data class Measurement(val detectMs: Double, val outputMs: Double?, val totalMs: Double?)

    private class Stomp(val detectedNanos: Long, val playNanos: Long)

    private val onsets = ArrayList<Long>()
    private val stomps = ArrayList<Stomp>()
    private val results = ArrayDeque<Measurement>()
    var unpaired = 0; private set

    @Synchronized fun onset(nanos: Long) { onsets += nanos; onsets.sort() }

    /** [detectedNanos]: when the triggering sample arrived. [playNanos]: when play() was (or will be) called. */
    @Synchronized fun stomp(detectedNanos: Long, playNanos: Long) { stomps += Stomp(detectedNanos, playNanos) }

    @Synchronized fun poll(nowNanos: Long): List<Measurement> {
        val due = stomps.filter { nowNanos - it.playNanos >= settleNanos }
        stomps.removeAll(due.toSet())
        for (s in due) {
            // Impact: the last sound before the detection. Drum: the first sound after play().
            // An impact can ring and re-trigger the onset detector; step back to the first onset of the cluster.
            var i = onsets.indexOfLast { it <= s.detectedNanos && s.detectedNanos - it <= searchNanos }
            while (i > 0 && onsets[i] - onsets[i - 1] < clusterNanos) i--
            val impact = onsets.getOrNull(i)
            val drum = onsets.firstOrNull { it >= s.playNanos && it - s.playNanos <= searchNanos }
            if (impact == null) { unpaired++; continue }
            results.addLast(Measurement(
                detectMs = (s.detectedNanos - impact) / 1e6,
                outputMs = drum?.let { (it - s.playNanos) / 1e6 },
                totalMs = drum?.let { (it - impact) / 1e6 },
            ))
            while (results.size > keep) results.removeFirst()
        }
        val oldest = stomps.minOfOrNull { it.detectedNanos } ?: nowNanos
        onsets.removeAll { oldest - it > 2 * searchNanos && nowNanos - it > 2 * searchNanos }
        return results.toList()
    }

    @Synchronized fun clear() { onsets.clear(); stomps.clear(); results.clear(); unpaired = 0 }

    companion object {
        /** "detect 380 ms · output 32 ms · total 412 ms (median of 12)". */
        fun summary(r: List<Measurement>): String {
            if (r.isEmpty()) return "no stomps measured yet"
            fun med(v: List<Double>) = v.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
            val d = med(r.map { it.detectMs })
            val o = med(r.mapNotNull { it.outputMs })
            val t = med(r.mapNotNull { it.totalMs })
            fun f(v: Double?) = v?.let { "%.0f ms".format(java.util.Locale.US, it) } ?: "—"
            val last = r.last()
            return "median of ${r.size}: detect ${f(d)} · output ${f(o)} · total ${f(t)}\n" +
                "last: detect ${f(last.detectMs)} · output ${f(last.outputMs)} · total ${f(last.totalMs)}"
        }
    }
}
