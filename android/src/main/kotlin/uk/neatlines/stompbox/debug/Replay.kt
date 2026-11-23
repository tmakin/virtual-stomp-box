package uk.neatlines.stompbox.debug

import uk.neatlines.stompbox.latency.LatencyStats
import uk.neatlines.stompbox.latency.TapPairer
import uk.neatlines.stompbox.stomp.StompConfig
import uk.neatlines.stompbox.stomp.StompEvent
import uk.neatlines.stompbox.stomp.StompPipeline
import java.io.File
import java.util.Locale

/**
 * Re-runs the detector on a log exported from the phone, with any settings changed, so you can
 * tune on the laptop without stomping again.
 *
 * ```
 * ./gradlew run --args="stomp-log.csv"                                  # same settings as live
 * ./gradlew run --args="stomp-log.csv minThresholdG=1.5 feature=JERK"   # try other settings
 * ./gradlew run --args="stomp-log.csv --sweep minThresholdG=0.6:2.0:0.2"
 * ./gradlew run --args="stomp-log.csv --frames frames.csv"              # per-sample trace for tools/plot_stomp_log.py
 * ```
 * When the log has mic onsets (recorded with the latency probe on), it also reports impact →
 * app latency and jitter for both timing modes, plus missed taps and false triggers.
 */
fun main(args: Array<String>) {
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"))
    if (args.isEmpty()) { System.err.println("usage: replay <log.csv> [key=value …] [--sweep key=from:to:step] [--frames out.csv]"); return }
    val log = File(args[0]).reader().use { StompLog.read(it) }
    val overrides = LinkedHashMap<String, String>()
    var sweep: String? = null
    var framesOut: String? = null
    var i = 1
    while (i < args.size) {
        when (val a = args[i]) {
            "--sweep" -> sweep = args[++i]
            "--frames" -> framesOut = args[++i]
            else -> overrides[a.substringBefore('=')] = a.substringAfter('=')
        }
        i++
    }
    val rate = log.info["rateHz"]?.toIntOrNull() ?: 200
    val base = log.config.with(overrides)
    println("log: ${log.samples.size} samples, ${log.onsets.size} mic onsets, ${log.stomps.size} live stomps; info ${log.info}")
    println("placement: rest=${log.placement.rest} convention error ${"%.1f".format(Locale.US, log.placement.conventionErrorDeg)}°, tilt axis ${log.placement.tiltAxis ?: "none"}")

    if (sweep != null) {
        val (key, range) = sweep.split('=')
        val (from, to, step) = range.split(':').map { it.toDouble() }
        println("%-14s %6s %7s %6s %8s %8s".format("$key", "stomps", "missed", "false", "med ms", "±ms"))
        var x = from
        while (x <= to + 1e-9) {
            val r = run(log, base.with(mapOf(key to fmt(x))), rate, null)
            val st = r.immediate?.stats
            println("%-14s %6d %7s %6s %8s %8s".format(fmt(x), r.events.size,
                r.immediate?.missed ?: "-", r.immediate?.falseTriggers ?: "-",
                st?.let { "%.1f".format(Locale.US, it.median) } ?: "-", st?.let { "%.1f".format(Locale.US, it.spread90 / 2) } ?: "-"))
            x += step
        }
        return
    }

    val r = run(log, base, rate, framesOut)
    println("config: ${base.toMap().filter { (k, v) -> StompConfig().toMap()[k] != v }.ifEmpty { "defaults" }}")
    println("link: ${r.link.format()}")
    println("clock resyncs: ${r.resyncs}")
    println("stomps: ${r.events.size} (live run had ${log.stomps.size})  zones ${r.events.groupingBy { it.first.zone }.eachCount()}")
    LatencyStats.of(r.events.map { it.first.velocity.toDouble() })?.let {
        println("velocity: min %.0f median %.0f max %.0f".format(Locale.US, it.min, it.median, it.max))
    }
    r.immediate?.let { println("IMMEDIATE impact→fire: ${it.stats?.format() ?: "no pairs"}  missed=${it.missed} false=${it.falseTriggers}") }
    r.steady?.let { println("STEADY (${base.steadyDelayMs} ms) impact→fire: ${it.stats?.format() ?: "no pairs"}  late=${r.late}") }
    if (log.onsets.isEmpty()) println("(no mic onsets in this log: record with the latency probe on to get latency figures)")
    framesOut?.let { println("wrote $it") }
}

private class ReplayResult(
    val events: List<Pair<StompEvent, Long>>,
    val link: LinkStats.Snapshot,
    val resyncs: Int,
    val late: Int,
    val immediate: TapPairer.Result?,
    val steady: TapPairer.Result?,
)

private fun run(log: StompLog, config: StompConfig, rate: Int, framesOut: String?): ReplayResult {
    val events = ArrayList<Pair<StompEvent, Long>>()
    val steadyFire = ArrayList<Long>()
    val pipeline = StompPipeline(config.copy(timing = uk.neatlines.stompbox.stomp.Timing.IMMEDIATE), log.placement, rate) { e, at ->
        events += e to at
    }
    // STEADY fire times are computed separately so one pass reports both modes.
    val steadyTiming = uk.neatlines.stompbox.stomp.StompTiming()
    val steadyCfg = config.copy(timing = uk.neatlines.stompbox.stomp.Timing.STEADY)
    val frames = framesOut?.let { File(it).printWriter() }
    frames?.println("index,sample_ms,arrival_ms,feature,threshold,vertical,jerk,tilt,gyro,armed,triggered")
    val t0 = log.samples.firstOrNull()?.first ?: 0L
    var arrival = 0L
    pipeline.detector.onDebug = { d ->
        frames?.println(String.format(Locale.US, "%d,%.3f,%.3f,%.4f,%.4f,%.4f,%.4f,%.2f,%.1f,%d,%d",
            d.index, (d.sampleNanos - t0) / 1e6, (arrival - t0) / 1e6, d.feature, d.threshold, d.vertical, d.jerk,
            d.tiltDeg, d.gyroDps, if (d.armed) 1 else 0, if (d.triggered) 1 else 0))
    }
    var seen = 0
    for ((t, s) in log.samples) {
        arrival = t
        pipeline.onSample(s, t)
        while (seen < events.size) { steadyFire += steadyTiming.fireAtNanos(events[seen].first, steadyCfg, t); seen++ }
    }
    frames?.close()
    val immediate = if (log.onsets.isEmpty()) null else TapPairer.pair(log.onsets, events.map { it.second })
    val steady = if (log.onsets.isEmpty()) null else TapPairer.pair(log.onsets, steadyFire)
    return ReplayResult(events, pipeline.link.snapshot(), pipeline.clock.resyncs, steadyTiming.late, immediate, steady)
}

private fun fmt(x: Double) = if (x == Math.rint(x)) x.toLong().toString() else "%.4f".format(Locale.US, x).trimEnd('0')
