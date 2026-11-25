package uk.neatlines.stompbox.audio

/**
 * JNI bridge to `aaudio_drum.cpp`: an AAudio output stream that asks for EXCLUSIVE (MMAP) mode,
 * mixing preloaded sounds in its data callback. Used by [DrumSynth] when its engine is AAUDIO.
 */
internal object AAudioDrum {
    init { System.loadLibrary("aaudiodrum") }

    @JvmStatic external fun nativeLoad(slot: Int, data: FloatArray)
    @JvmStatic external fun nativeOpen(deviceId: Int, exclusive: Boolean): Boolean
    @JvmStatic external fun nativeClose()
    @JvmStatic external fun nativePlay(slot: Int, gain: Float)
    @JvmStatic external fun nativeInfo(out: DoubleArray): Boolean

    /** What [nativeInfo] reports. */
    class Info(a: DoubleArray) {
        val exclusive = a[0] != 0.0
        val bufferMs = a[1]
        val latencyMs = a[2]
        val xruns = a[3].toInt()
        val burst = a[4].toInt()
        val deviceId = a[6].toInt()
        val disconnected = a[7] != 0.0
    }

    fun info(): Info? = DoubleArray(8).let { if (nativeInfo(it)) Info(it) else null }
}
