package uk.neatlines.stompbox.stomp

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small 3-vector helpers; allocation is fine at 200 Hz. */
data class V3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = V3(x * k, y * k, z * k)
    infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val norm get() = sqrt(this dot this)
    fun unit(): V3 { val n = norm; return if (n < 1e-9) V3(0.0, 0.0, 1.0) else this * (1 / n) }
    fun angleDeg(o: V3): Double = Math.toDegrees(acos((unit() dot o.unit()).coerceIn(-1.0, 1.0)))
}

/**
 * Which way the WT901's roll/pitch angles turn. The manual does not pin down the sign
 * convention, so it is worked out at calibration by comparing the angle-derived gravity with the
 * measured gravity while standing still ([AngleConvention.fit]).
 */
data class AngleConvention(val rollSign: Int = 1, val pitchSign: Int = 1) {
    /** Unit "up" vector in sensor axes (what the accelerometer reads at rest), from Z-Y-X Euler angles. */
    fun gravity(rollDeg: Double, pitchDeg: Double): V3 {
        val r = Math.toRadians(rollSign * rollDeg)
        val p = Math.toRadians(pitchSign * pitchDeg)
        return V3(-sin(p), sin(r) * cos(p), cos(r) * cos(p))
    }

    companion object {
        val ALL = listOf(AngleConvention(1, 1), AngleConvention(1, -1), AngleConvention(-1, 1), AngleConvention(-1, -1))

        /** The convention whose gravity best matches [measured] (mean accel at rest). Returns it and the error in degrees. */
        fun fit(measured: V3, rollDeg: Double, pitchDeg: Double): Pair<AngleConvention, Double> =
            ALL.map { it to it.gravity(rollDeg, pitchDeg).angleDeg(measured) }.minBy { it.second }
    }
}

/**
 * Where and how the sensor is strapped on, from a two-pose calibration:
 *  1. **Rest**: stand normally for ~1 s.
 *  2. **Tilt** (optional): shoe mode, lift the toes with the heel on the floor; leg mode, swing the
 *     lower leg forward. This gives the axis the foot or shin pitches about, so the app can tell a
 *     heel stomp from a toe tap however the sensor is turned on the strap.
 */
data class Placement(
    val rest: V3,                       // unit gravity at rest, sensor axes
    val convention: AngleConvention,
    val conventionErrorDeg: Double,     // > ~10° means the angle data cannot be trusted; detector falls back to accel
    val tiltAxis: V3? = null,           // unit; positive rotation = towards the tilt pose
) {
    /** Signed angle (°) of [g] from rest about [tiltAxis]: + towards the tilt pose (toes up), − the other way. */
    fun tiltDeg(g: V3): Double {
        val axis = tiltAxis ?: return 0.0
        val gu = g.unit()
        return Math.toDegrees(atan2((rest cross gu) dot axis, rest dot gu))
    }

    /** Guess the mode: on a shoe the sensor lies roughly flat, on the shin it stands on edge. */
    val suggestedMode: Mode get() = if (abs(rest.z) > 0.7) Mode.SHOE else Mode.LEG

    companion object {
        val DEFAULT = Placement(V3(0.0, 0.0, 1.0), AngleConvention(), 0.0)

        /**
         * [restAcc]/[restRoll]/[restPitch]: means over the rest pose. [tiltAcc]: mean accel over the
         * tilt pose, or null to skip it. Returns null when the tilt pose moved too little to define an axis.
         */
        fun calibrate(restAcc: V3, restRoll: Double, restPitch: Double, tiltAcc: V3? = null): Placement {
            val rest = restAcc.unit()
            val (conv, err) = AngleConvention.fit(rest, restRoll, restPitch)
            val axis = tiltAcc?.let { val c = rest cross it.unit(); if (c.norm < sin(Math.toRadians(5.0))) null else c.unit() }
            return Placement(rest, conv, err, axis)
        }
    }
}

enum class Mode { SHOE, LEG }
