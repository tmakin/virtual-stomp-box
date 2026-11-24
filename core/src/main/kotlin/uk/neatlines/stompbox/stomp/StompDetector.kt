package uk.neatlines.stompbox.stomp

import uk.neatlines.stompbox.wit.WitSample
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

data class StompEvent(
    val sampleIndex: Long,
    /** Estimated time the impact sample was measured (SampleClock), System.nanoTime base. */
    val sampleNanos: Long,
    /** When the triggering sample reached the app. */
    val arrivalNanos: Long,
    val velocity: Int,      // 1..127
    val zone: Zone,
    val note: Int,
    val peak: Double,       // feature value, g
    val tiltDeg: Double,
)

/** Per-sample internals, for the live plot and the log. */
data class DebugFrame(
    val index: Long,
    val sampleNanos: Long,
    val feature: Double,
    val threshold: Double,
    val vertical: Double,   // acceleration along gravity minus 1 g
    val jerk: Double,
    val tiltDeg: Double,
    val gyroDps: Double,
    val armed: Boolean,
    val triggered: Boolean,
)

/**
 * Stomp detector for the WT901, run on the phone for every sample (200 Hz).
 *
 * Uses the accelerometer to find the impact and the orientation to (a) measure acceleration
 * along gravity whatever the sensor's angle on the strap, and (b) classify heel vs toe from the
 * foot's tilt just before the impact. Pure Kotlin, so the same code runs in unit tests and in the
 * desktop replay tool against recorded logs.
 *
 * Swap [config] at any time from the debug screen; filters keep their state.
 */
class StompDetector(
    @Volatile var config: StompConfig,
    @Volatile var placement: Placement = Placement.DEFAULT,
    /** Can change at runtime when the sensor rate is changed. */
    @Volatile var sampleRateHz: Double = 200.0,
    private val onStomp: (StompEvent) -> Unit,
    @Volatile var onDebug: ((DebugFrame) -> Unit)? = null,
) {
    private var index = -1L
    private var prev: WitSample? = null
    private var hpIn = 0.0
    private var hpOut = 0.0
    private var noise = 0.05            // running mean of |feature| while armed, g
    private var armed = true
    private var msSinceTrigger = 1e9
    private var msSinceGyro = 1e9
    private var pending: StompEvent? = null
    private var pendingLeft = 0
    private val history = ArrayDeque<Pair<Double, Double>>()   // (roll, pitch) for orientation lag

    var triggers = 0; private set

    fun onSample(s: WitSample, sampleNanos: Long, arrivalNanos: Long) {
        index++
        val c = config
        val p = placement
        val dtMs = 1000.0 / sampleRateHz

        // Orientation from a few samples back; the impact itself can disturb the fused angles.
        history.addLast(s.roll.toDouble() to s.pitch.toDouble())
        while (history.size > c.orientationLagSamples + 1) history.removeFirst()
        val (roll, pitch) = history.first()
        val useAngles = p.conventionErrorDeg < 10.0
        val g = if (useAngles) p.convention.gravity(roll, pitch) else p.rest
        val acc = V3(s.ax.toDouble(), s.ay.toDouble(), s.az.toDouble())
        val vertical = (acc dot g) - 1.0
        val tilt = if (useAngles) p.tiltDeg(g) else 0.0

        val q = prev
        val jerk = if (q == null) 0.0 else sqrt(
            sq(s.ax - q.ax) + sq(s.ay - q.ay) + sq(s.az - q.az)
        )
        prev = s

        // One-pole high-pass on the vertical acceleration.
        val rc = 1.0 / (2 * PI * c.highPassHz)
        val alpha = rc / (rc + dtMs / 1000.0)
        hpOut = alpha * (hpOut + vertical - hpIn); hpIn = vertical

        val feature = when (c.feature) { Feature.VERTICAL -> abs(hpOut); Feature.JERK -> jerk }
        val threshold = maxOf(c.minThresholdG, c.noiseFactor * noise)
        val gyro = sqrt(sq(s.gx) + sq(s.gy) + sq(s.gz))
        if (c.gyroGateDps <= 0 || gyro >= c.gyroGateDps) msSinceGyro = 0.0 else msSinceGyro += dtMs
        msSinceTrigger += dtMs

        if (!armed && msSinceTrigger >= c.deadTimeMs && feature < c.releaseRatio * threshold) armed = true
        if (armed) noise += (feature - noise) * (1 - exp(-dtMs / 1000.0))   // ~1 s time constant

        var triggered = false
        val pend = pending
        if (pend != null) {
            // Waiting for the peak: keep the largest value, fire when the wait is over.
            val better = if (feature > pend.peak) pend.copy(peak = feature) else pend
            if (--pendingLeft <= 0) { pending = null; emit(better.copy(velocity = velocity(better.peak, threshold, c))) }
            else pending = better
        } else if (armed && feature > threshold && msSinceGyro <= c.gyroGateMs) {
            armed = false; msSinceTrigger = 0.0; triggered = true; triggers++
            val zone = zone(tilt, c, p)
            val ev = StompEvent(index, sampleNanos, arrivalNanos, velocity(feature, threshold, c), zone, c.note(zone), feature, tilt)
            if (c.peakWaitSamples > 0) { pending = ev; pendingLeft = c.peakWaitSamples } else emit(ev)
        }
        onDebug?.invoke(DebugFrame(index, sampleNanos, feature, threshold, vertical, jerk, tilt, gyro, armed, triggered))
    }

    private fun emit(e: StompEvent) = onStomp(e)

    private fun zone(tilt: Double, c: StompConfig, p: Placement): Zone = when {
        !c.zonesEnabled || p.tiltAxis == null -> Zone.FLAT
        tilt >= c.heelTiltDeg -> Zone.HEEL
        tilt <= -c.toeTiltDeg -> Zone.TOE
        else -> Zone.FLAT
    }

    private fun velocity(peak: Double, threshold: Double, c: StompConfig): Int {
        val span = (c.velocityFullScaleG - threshold).coerceAtLeast(1e-3)
        val x = ((peak - threshold) / span).coerceIn(0.0, 1.0)
        return (1 + 126 * x.pow(c.velocityCurve)).toInt().coerceIn(1, 127)
    }

    private fun sq(v: Float) = (v * v).toDouble()
}
