package uk.neatlines.stompbox.stomp

import uk.neatlines.stompbox.debug.LinkStats
import uk.neatlines.stompbox.wit.SampleClock
import uk.neatlines.stompbox.wit.WitSample

/**
 * Sensor sample in, timed stomp out. The phone and the desktop replay tool both run exactly this,
 * so a recording replayed with the same config reproduces what happened live.
 *
 * [onStomp] gets the event and the System.nanoTime at which to play it ([StompTiming]).
 */
class StompPipeline(
    config: StompConfig,
    placement: Placement = Placement.DEFAULT,
    rateHz: Int = 200,
    private val onStomp: (StompEvent, fireAtNanos: Long) -> Unit,
) {
    val clock = SampleClock(rateHz.toDouble())
    val link = LinkStats()
    val timing = StompTiming()
    private var now = 0L
    val detector = StompDetector(config, placement, rateHz.toDouble(), onStomp = { e ->
        onStomp(e, timing.fireAtNanos(e, this.config, now))
    })

    var config: StompConfig
        get() = detector.config
        set(v) { detector.config = v }
    var placement: Placement
        get() = detector.placement
        set(v) { detector.placement = v }

    /** Call for every parsed sample with System.nanoTime() taken when its notification arrived. */
    fun onSample(s: WitSample, arrivalNanos: Long) {
        now = arrivalNanos
        val t = clock.onSample(arrivalNanos)
        link.add(arrivalNanos, clock.lastLagNanos)
        detector.onSample(s, t, arrivalNanos)
    }
}
