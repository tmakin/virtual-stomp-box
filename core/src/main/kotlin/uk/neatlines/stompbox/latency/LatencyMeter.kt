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
 * [settleNanos] have passed, so late mic onsets are in.
 *
 * The impact must come after the previous stomp's drum (and its ringing). Otherwise, when the mic
 * misses an impact, the previous drum is taken for it and *detect* reads as the stomp period minus
 * the output delay. Builds up to #11 had that flaw. Such stomps are counted in [unpaired].
 */
class LatencyMeter(
    private val searchNanos: Long = 1_000_000_000L,
    private val settleNanos: Long = 1_200_000_000L,
    private val keep: Int = 30,
    private val clusterNanos: Long = 80_000_000L,
    private val joltG: Double = 0.5,
    private val joltWindowNanos: Long = 150_000_000L,
) {
    /**
     * [firstJoltMs]: when the first sensor sample with a jolt over [joltG] arrived, after the
     * impact. Far below [detectMs] means the impact *is* in the data and the detector fired on
     * something later (the foot lifting). Close to it means the data itself arrives late.
     * [maxJoltG]: the biggest jolt in the [joltWindowNanos] after that first one.
     */
    data class Measurement(
        val detectMs: Double, val outputMs: Double?, val totalMs: Double?,
        val firstJoltMs: Double? = null, val maxJoltG: Double? = null,
    )

    private class Stomp(val detectedNanos: Long, val playNanos: Long)

    private val onsets = ArrayList<Long>()
    private val stomps = ArrayList<Stomp>()
    private val results = ArrayDeque<Measurement>()
    var unpaired = 0; private set
    /** No impact may be taken from before this: the end of the previous stomp's drum. */
    private var floorNanos = Long.MIN_VALUE

    private val sampleTimes = ArrayList<Long>()
    private val sampleJolts = ArrayList<Double>()

    /** Every sensor sample: arrival time and jolt (size of the change in acceleration, g). */
    @Synchronized fun sample(arrivalNanos: Long, joltG: Double) { sampleTimes += arrivalNanos; sampleJolts += joltG }

    @Synchronized fun onset(nanos: Long) { onsets += nanos; onsets.sort() }

    /** [detectedNanos]: when the triggering sample arrived. [playNanos]: when play() was (or will be) called. */
    @Synchronized fun stomp(detectedNanos: Long, playNanos: Long) { stomps += Stomp(detectedNanos, playNanos) }

    @Synchronized fun poll(nowNanos: Long): List<Measurement> {
        val due = stomps.filter { nowNanos - it.playNanos >= settleNanos }.sortedBy { it.detectedNanos }
        val all = stomps.sortedBy { it.detectedNanos }
        stomps.removeAll(due.toSet())
        for (s in due) {
            val next = all.getOrNull(all.indexOf(s) + 1)?.detectedNanos
            // Impact: the last sound before the detection. Drum: the first sound after play().
            // An impact can ring and re-trigger the onset detector; step back to the first onset of the cluster.
            val floor = floorNanos
            var i = onsets.indexOfLast { it <= s.detectedNanos && s.detectedNanos - it <= searchNanos && it > floor }
            while (i > 0 && onsets[i] - onsets[i - 1] < clusterNanos && onsets[i - 1] > floor) i--
            val impact = onsets.getOrNull(i)
            val drum = drumFor(s, next)
            floorNanos = maxOf(floor, (drum ?: s.playNanos) + clusterNanos)
            if (impact == null) { unpaired++; continue }
            val first = sampleTimes.indices.firstOrNull { sampleTimes[it] >= impact && sampleJolts[it] >= joltG }
            val maxJolt = first?.let { f ->
                sampleTimes.indices.filter { sampleTimes[it] >= sampleTimes[f] && sampleTimes[it] - sampleTimes[f] <= joltWindowNanos }
                    .maxOf { sampleJolts[it] }
            }
            results.addLast(Measurement(
                detectMs = (s.detectedNanos - impact) / 1e6,
                outputMs = drum?.let { (it - s.playNanos) / 1e6 },
                totalMs = drum?.let { (it - impact) / 1e6 },
                firstJoltMs = first?.let { (sampleTimes[it] - impact) / 1e6 },
                maxJoltG = maxJolt,
            ))
            while (results.size > keep) results.removeFirst()
        }
        val oldest = stomps.minOfOrNull { it.detectedNanos } ?: nowNanos
        onsets.removeAll { oldest - it > 2 * searchNanos && nowNanos - it > 2 * searchNanos }
        val drop = sampleTimes.indexOfFirst { oldest - it <= 2 * searchNanos || nowNanos - it <= 2 * searchNanos }
            .let { if (it < 0) sampleTimes.size else it }
        if (drop > 0) { sampleTimes.subList(0, drop).clear(); sampleJolts.subList(0, drop).clear() }
        return results.toList()
    }

    /**
     * The first sound after play(), unless it is really the next stomp's impact: with the drum
     * unheard (sound off, or the mic missed it), taking that impact as this drum would also put the
     * floor past it and leave the next stomp with no impact. Between play() and the next detection:
     * two or more separate sounds are drum, then impact; a lone sound is whichever it is nearer.
     */
    private fun drumFor(s: Stomp, nextDetected: Long?): Long? {
        val window = onsets.filter { it >= s.playNanos && it - s.playNanos <= searchNanos && (nextDetected == null || it < nextDetected) }
        val first = window.firstOrNull() ?: return null
        if (nextDetected == null) return first
        val laterSound = window.any { it - first >= clusterNanos }
        return if (laterSound || first - s.playNanos <= nextDetected - first) first else null
    }

    @Synchronized fun clear() { sampleTimes.clear(); sampleJolts.clear(); onsets.clear(); stomps.clear(); results.clear(); unpaired = 0; floorNanos = Long.MIN_VALUE }

    companion object {
        /** "detect 380 ms · output 32 ms · total 412 ms (median of 12)". */
        fun summary(r: List<Measurement>, unpaired: Int = 0): String {
            if (r.isEmpty()) return if (unpaired > 0) "no stomps measured yet ($unpaired with no impact heard)" else "no stomps measured yet"
            fun med(v: List<Double>) = v.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
            val d = med(r.map { it.detectMs })
            val o = med(r.mapNotNull { it.outputMs })
            val t = med(r.mapNotNull { it.totalMs })
            fun f(v: Double?) = v?.let { "%.0f ms".format(java.util.Locale.US, it) } ?: "—"
            val last = r.last()
            val j = med(r.mapNotNull { it.firstJoltMs })
            val g = med(r.mapNotNull { it.maxJoltG })
            val miss = if (unpaired > 0) " ($unpaired more with no impact heard)" else ""
            return "median of ${r.size}$miss: detect ${f(d)} · output ${f(o)} · total ${f(t)}\n" +
                "last: detect ${f(last.detectMs)} · output ${f(last.outputMs)} · total ${f(last.totalMs)}\n" +
                "impact in sensor data: first jolt at ${f(j)}, max %s".format(g?.let { "%.2f g".format(java.util.Locale.US, it) } ?: "—")
        }
    }
}
