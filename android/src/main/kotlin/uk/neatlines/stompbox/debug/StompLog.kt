package uk.neatlines.stompbox.debug

import uk.neatlines.stompbox.stomp.AngleConvention
import uk.neatlines.stompbox.stomp.Placement
import uk.neatlines.stompbox.stomp.StompConfig
import uk.neatlines.stompbox.stomp.StompEvent
import uk.neatlines.stompbox.stomp.V3
import uk.neatlines.stompbox.wit.WitSample
import java.io.BufferedReader
import java.io.Reader
import java.io.Writer
import java.util.Locale

/**
 * The recording format exported from the phone: everything needed to replay a session offline.
 *
 * ```
 * # stomp-log 1
 * # config mode=SHOE feature=VERTICAL minThresholdG=1.2 …
 * # placement rest=0.01,0.02,1.00 conv=1,1 convErr=1.3 tilt=0.00,1.00,0.00
 * # info rateHz=200 bandwidthReg=2 batteryV=3.95 phone=Pixel 8
 * type,t_ns,a,b,c,d,e,f,g,h,i
 * s,<arrival_ns>,ax,ay,az,gx,gy,gz,roll,pitch,yaw   raw sample (g, °/s, °)
 * o,<onset_ns>                                       impact heard by the mic (LatencyProbe)
 * t,<fired_ns>,<sample_ns>,<arrival_ns>,velocity,zone,note,peak,tilt   stomp the phone played
 * ```
 * Every time is System.nanoTime, in nanoseconds. Times are absolute, so subtract the first one to
 * plot.
 */
class StompLogWriter(private val out: Writer) {
    @Synchronized
    fun header(config: StompConfig, placement: Placement, info: Map<String, String>) {
        out.write("# stomp-log 1\n")
        out.write("# config ${config.toMap().entries.joinToString(" ") { "${it.key}=${it.value}" }}\n")
        val p = placement
        out.write("# placement rest=${v(p.rest)} conv=${p.convention.rollSign},${p.convention.pitchSign} " +
            "convErr=${f(p.conventionErrorDeg)}${p.tiltAxis?.let { " tilt=${v(it)}" } ?: ""}\n")
        out.write("# info ${info.entries.joinToString(" ") { "${it.key}=${it.value.replace(' ', '_')}" }}\n")
        out.write("type,t_ns,a,b,c,d,e,f,g,h,i\n")
    }

    @Synchronized
    fun sample(arrivalNanos: Long, s: WitSample) {
        out.write("s,$arrivalNanos,${f(s.ax)},${f(s.ay)},${f(s.az)},${f(s.gx)},${f(s.gy)},${f(s.gz)},${f(s.roll)},${f(s.pitch)},${f(s.yaw)}\n")
    }

    @Synchronized fun onset(nanos: Long) = out.write("o,$nanos\n")

    @Synchronized
    fun stomp(e: StompEvent, firedNanos: Long) {
        out.write("t,$firedNanos,${e.sampleNanos},${e.arrivalNanos},${e.velocity},${e.zone},${e.note},${f(e.peak)},${f(e.tiltDeg)}\n")
    }

    @Synchronized fun flush() = out.flush()
    @Synchronized fun close() = out.close()

    private fun f(x: Number) = String.format(Locale.US, "%.4f", x.toDouble())
    private fun v(x: V3) = "${f(x.x)},${f(x.y)},${f(x.z)}"
}

data class StompLog(
    val config: StompConfig,
    val placement: Placement,
    val info: Map<String, String>,
    val samples: List<Pair<Long, WitSample>>,
    val onsets: List<Long>,
    /** Fired time, then the event, as recorded live. */
    val stomps: List<Pair<Long, StompEvent>>,
) {
    companion object {
        fun read(reader: Reader): StompLog {
            var config = StompConfig()
            var placement = Placement.DEFAULT
            val info = LinkedHashMap<String, String>()
            val samples = ArrayList<Pair<Long, WitSample>>()
            val onsets = ArrayList<Long>()
            val stomps = ArrayList<Pair<Long, StompEvent>>()
            BufferedReader(reader).forEachLine { line ->
                when {
                    line.startsWith("# config ") -> config = StompConfig().with(kv(line.removePrefix("# config ")))
                    line.startsWith("# placement ") -> placement = parsePlacement(kv(line.removePrefix("# placement ")))
                    line.startsWith("# info ") -> info += kv(line.removePrefix("# info "))
                    line.startsWith("s,") -> {
                        val p = line.split(',')
                        val x = FloatArray(9) { p[2 + it].toFloat() }
                        samples += p[1].toLong() to WitSample(x[0], x[1], x[2], x[3], x[4], x[5], x[6], x[7], x[8])
                    }
                    line.startsWith("o,") -> onsets += line.split(',')[1].toLong()
                    line.startsWith("t,") -> {
                        val p = line.split(',')
                        stomps += p[1].toLong() to StompEvent(
                            -1, p[2].toLong(), p[3].toLong(), p[4].toInt(),
                            uk.neatlines.stompbox.stomp.Zone.valueOf(p[5]), p[6].toInt(), p[7].toDouble(), p[8].toDouble(),
                        )
                    }
                }
            }
            return StompLog(config, placement, info, samples, onsets, stomps)
        }

        private fun kv(s: String): Map<String, String> =
            s.trim().split(' ').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

        private fun v3(s: String): V3 = s.split(',').map { it.toDouble() }.let { V3(it[0], it[1], it[2]) }

        private fun parsePlacement(m: Map<String, String>): Placement {
            val conv = m["conv"]?.split(',')?.map { it.toInt() } ?: listOf(1, 1)
            return Placement(
                rest = m["rest"]?.let(::v3) ?: V3(0.0, 0.0, 1.0),
                convention = AngleConvention(conv[0], conv[1]),
                conventionErrorDeg = m["convErr"]?.toDouble() ?: 0.0,
                tiltAxis = m["tilt"]?.let(::v3),
            )
        }
    }
}
