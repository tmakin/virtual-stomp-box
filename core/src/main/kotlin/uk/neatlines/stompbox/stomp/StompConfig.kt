package uk.neatlines.stompbox.stomp

/** What the detector thresholds. Both are computed and plotted; the debug screen picks one. */
enum class Feature {
    /** High-passed acceleration along gravity (from the orientation). Best on the shoe. */
    VERTICAL,
    /** Size of the change in the acceleration vector between samples. Ignores orientation; picks up the attenuated impact on the shin. */
    JERK,
}

enum class Zone { FLAT, HEEL, TOE }

/** How the app turns a detection into a sound. */
enum class Timing {
    /** Play as soon as the sample arrives. Lowest delay, but jitter of about a whole BLE connection interval. */
    IMMEDIATE,
    /** Play at the estimated impact time + [StompConfig.steadyDelayMs]. Constant delay, little jitter. */
    STEADY,
}

/**
 * Every tunable in one place, so the debug screen, presets, the log header and the replay tool
 * all use the same names. Units are in the names.
 */
data class StompConfig(
    val mode: Mode = Mode.SHOE,
    val feature: Feature = Feature.VERTICAL,
    val minThresholdG: Double = 1.2,
    val noiseFactor: Double = 6.0,       // threshold = max(min, noiseFactor × running noise level)
    val releaseRatio: Double = 0.5,      // re-arm once the feature falls below this × threshold …
    val deadTimeMs: Double = 90.0,       // … and at least this long after a trigger
    val highPassHz: Double = 4.0,        // VERTICAL only: removes slow leg motion
    val peakWaitSamples: Int = 0,        // wait this many samples for a higher peak (better velocity, +5 ms each at 200 Hz)
    val eagerRatio: Double = 0.0,        // >0: at the end of a Bluetooth packet, fire if its last sample is over this × threshold
    val velocityFullScaleG: Double = 6.0,
    val velocityCurve: Double = 0.6,     // <1 makes soft stomps louder
    val gyroGateDps: Double = 0.0,       // >0: require this much rotation in the last [gyroGateMs] (rejects bumps)
    val gyroGateMs: Double = 150.0,
    val zonesEnabled: Boolean = false,   // heel/toe split; needs a tilt pose in the Placement. Off for the first iteration.
    val heelTiltDeg: Double = 8.0,       // toes up at least this much → HEEL
    val toeTiltDeg: Double = 8.0,        // toes down at least this much → TOE
    val orientationLagSamples: Int = 2,  // read orientation from before the impact disturbed it
    val timing: Timing = Timing.IMMEDIATE,
    val steadyDelayMs: Double = 100.0,
    val noteFlat: Int = 36, val noteHeel: Int = 36, val noteToe: Int = 38,
) {
    fun note(zone: Zone) = when (zone) { Zone.FLAT -> noteFlat; Zone.HEEL -> noteHeel; Zone.TOE -> noteToe }

    /** `key=value` pairs for the log header and presets. */
    fun toMap(): Map<String, String> = linkedMapOf(
        "mode" to mode.name, "feature" to feature.name,
        "minThresholdG" to "$minThresholdG", "noiseFactor" to "$noiseFactor", "releaseRatio" to "$releaseRatio",
        "deadTimeMs" to "$deadTimeMs", "highPassHz" to "$highPassHz", "peakWaitSamples" to "$peakWaitSamples",
        "eagerRatio" to "$eagerRatio",
        "velocityFullScaleG" to "$velocityFullScaleG", "velocityCurve" to "$velocityCurve",
        "gyroGateDps" to "$gyroGateDps", "gyroGateMs" to "$gyroGateMs", "zonesEnabled" to "$zonesEnabled",
        "heelTiltDeg" to "$heelTiltDeg", "toeTiltDeg" to "$toeTiltDeg", "orientationLagSamples" to "$orientationLagSamples",
        "timing" to timing.name, "steadyDelayMs" to "$steadyDelayMs",
        "noteFlat" to "$noteFlat", "noteHeel" to "$noteHeel", "noteToe" to "$noteToe",
    )

    /** Returns a copy with the given keys overridden; unknown keys throw so typos show up. */
    fun with(overrides: Map<String, String>): StompConfig {
        var c = this
        for ((k, v) in overrides) c = when (k) {
            "mode" -> c.copy(mode = Mode.valueOf(v.uppercase()))
            "feature" -> c.copy(feature = Feature.valueOf(v.uppercase()))
            "minThresholdG" -> c.copy(minThresholdG = v.toDouble())
            "noiseFactor" -> c.copy(noiseFactor = v.toDouble())
            "releaseRatio" -> c.copy(releaseRatio = v.toDouble())
            "deadTimeMs" -> c.copy(deadTimeMs = v.toDouble())
            "highPassHz" -> c.copy(highPassHz = v.toDouble())
            "peakWaitSamples" -> c.copy(peakWaitSamples = v.toInt())
            "eagerRatio" -> c.copy(eagerRatio = v.toDouble())
            "velocityFullScaleG" -> c.copy(velocityFullScaleG = v.toDouble())
            "velocityCurve" -> c.copy(velocityCurve = v.toDouble())
            "gyroGateDps" -> c.copy(gyroGateDps = v.toDouble())
            "gyroGateMs" -> c.copy(gyroGateMs = v.toDouble())
            "zonesEnabled" -> c.copy(zonesEnabled = v.toBooleanStrict())
            "heelTiltDeg" -> c.copy(heelTiltDeg = v.toDouble())
            "toeTiltDeg" -> c.copy(toeTiltDeg = v.toDouble())
            "orientationLagSamples" -> c.copy(orientationLagSamples = v.toInt())
            "timing" -> c.copy(timing = Timing.valueOf(v.uppercase()))
            "steadyDelayMs" -> c.copy(steadyDelayMs = v.toDouble())
            "noteFlat" -> c.copy(noteFlat = v.toInt())
            "noteHeel" -> c.copy(noteHeel = v.toInt())
            "noteToe" -> c.copy(noteToe = v.toInt())
            else -> throw IllegalArgumentException("unknown config key '$k'")
        }
        return c
    }

    companion object {
        /**
         * Starting points, chosen for the lowest latency: VERTICAL feature, IMMEDIATE timing (play
         * on arrival, no steady delay), no peak wait, no gyro gate. The app also uses 200 Hz, the
         * 2M PHY, a 247-byte MTU and CONNECTION_PRIORITY_HIGH. Measured: 86 ms impact → detected.
         * LEG uses JERK because the shin sees a weaker, less vertical impact.
         */
        val SHOE = StompConfig()
        val LEG = StompConfig(
            mode = Mode.LEG, feature = Feature.JERK, minThresholdG = 0.8, noiseFactor = 5.0,
            velocityFullScaleG = 3.0,
        )
        fun forMode(m: Mode) = if (m == Mode.SHOE) SHOE else LEG
    }
}
