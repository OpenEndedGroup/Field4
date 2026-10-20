package afield.math.temporal

import afield.math.oracle.Improviser
import afield.math.oracle.JudgeMode
import afield.math.oracle.Performance
import afield.math.oracle.Policy
import afield.math.oracle.Reason
import afield.math.oracle.Trajectory
import afield.math.oracle.VariableMarkovOracle
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * The tiny-data, long-performance comparison from the Timestral/VMO proposal, run on demand:
 * `AFIELD_TEMPORAL_EXPERIMENTS=1 ./gradlew jvmTest --tests 'afield.math.temporal.TemporalExperiments'`
 * (add `AFIELD_TEMPORAL_MODELS=mistralai/Timestral-28M,mistralai/Timestral-209M` for the forecaster).
 * Writes trajectories and `summary.tsv` under build/temporal-experiments/.
 */
class TemporalExperiments {
    private val out = File("build/temporal-experiments").apply { mkdirs() }
    private val rate = 20.0

    private fun oscillators(): Trajectory {
        val n = (45 * rate).toInt()
        return Trajectory.of(rate,
            "x" to List(n) { val t = it / rate; sin(2 * PI * 0.5 * t) * (1 + 0.3 * sin(2 * PI * 0.05 * t)) },
            "y" to List(n) { val t = it / rate; 0.7 * sin(2 * PI * 0.75 * t + 0.6) },
            name = "oscillators")
    }

    private fun phrases(): Trajectory {
        val r = java.util.Random(11)
        fun rise() = List(30) { it / 29.0 }
        fun hes() = List(25) { 1.0 + 0.03 * r.nextGaussian() }
        fun burst() = List(30) { 1.0 + 0.7 * sin(it * 8 * PI / 29) }
        fun pause() = List(20 + r.nextInt(20)) { 0.0 }
        val eps = listOf(
            rise() + hes() + burst() + pause() + rise() + burst() + pause(),
            rise() + burst() + pause() + rise() + hes() + hes() + burst() + pause(),
            hes() + burst() + pause() + rise() + hes() + burst() + pause() + rise(),
        )
        return Trajectory.join(eps.mapIndexed { i, e -> Trajectory.of(rate, "v" to e, name = "phrase$i") }, "phrases")
    }

    private fun figureEight(): Trajectory {
        val n = (40 * rate).toInt()
        return Trajectory.of(rate, "x" to List(n) { sin(4 * PI * it / 120.0) }, "y" to List(n) { sin(2 * PI * it / 120.0) }, name = "figure8")
    }

    // --- measures ---------------------------------------------------------------------------

    private fun std(a: DoubleArray, from: Int = 0, to: Int = a.size): Double {
        val m = (from until to).sumOf { a[it] } / (to - from)
        return sqrt((from until to).sumOf { (a[it] - m) * (a[it] - m) } / (to - from))
    }

    /** Dominant frequency (Hz) by a direct DFT scan, 0.05–3 Hz. */
    private fun peakHz(a: DoubleArray): Double {
        val m = a.average()
        var best = 0.0; var bestP = -1.0
        var f = 0.05
        while (f <= 3.0) {
            var re = 0.0; var im = 0.0
            for (i in a.indices) { val ph = 2 * PI * f * i / rate; re += (a[i] - m) * kotlin.math.cos(ph); im += (a[i] - m) * sin(ph) }
            val p = re * re + im * im
            if (p > bestP) { bestP = p; best = f }
            f += 0.01
        }
        return best
    }

    /** Mean |step| ratio output/demo: 1 = same speed, <1 = washing out. */
    private fun speedRatio(o: DoubleArray, d: DoubleArray): Double {
        fun ms(a: DoubleArray) = (1 until a.size).sumOf { abs(a[it] - a[it - 1]) } / (a.size - 1)
        return ms(o) / ms(d)
    }

    private fun joinRoughness(demo: Trajectory, p: Performance) = p.joinRoughness(demo)
    private fun accelKs(demo: Trajectory, out: Trajectory) = Performance(out, IntArray(0), DoubleArray(0), emptyArray(), emptyList()).accelerationDistance(demo)

    private fun save(name: String, t: Trajectory, extra: Map<String, DoubleArray> = emptyMap()) {
        File(out, "$name.csv").printWriter().use { w ->
            w.println((t.channels + extra.keys).joinToString(","))
            for (i in 0 until t.length) w.println((t.channels.indices.map { t.values[it][i] } + extra.values.map { it[i] }).joinToString(","))
        }
    }

    private val rows = ArrayList<String>()

    private fun measure(fixture: Trajectory, label: String, o: Trajectory, extra: String = "") {
        val n = o.length
        val q = n / 4
        val cells = fixture.channels.indices.joinToString("\t") { c ->
            val early = std(o.values[c], 0, q); val late = std(o.values[c], n - q, n)
            "%s: std %.2f→%.2f (demo %.2f) peak %.2f Hz (demo %.2f) speed %.2f".format(
                fixture.channels[c], early, late, std(fixture.values[c]), peakHz(o.values[c].copyOfRange(n - q, n)), peakHz(fixture.values[c]), speedRatio(o.values[c].copyOfRange(n - q, n), fixture.values[c]))
        }
        rows.add("${fixture.name}\t$label\t$cells\t$extra")
        save("${fixture.name}-$label", o)
    }

    @Test
    fun run() {
        if (System.getenv("AFIELD_TEMPORAL_EXPERIMENTS") != "1") return
        val minutes = System.getenv("AFIELD_TEMPORAL_MINUTES")?.toDouble() ?: 5.0
        val samples = (minutes * 60 * rate).toInt()
        val models = System.getenv("AFIELD_TEMPORAL_MODELS")?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        val skipOracle = System.getenv("AFIELD_TEMPORAL_SKIP_ORACLE") == "1"
        val only = System.getenv("AFIELD_TEMPORAL_FIXTURES")?.split(",")?.toSet()
        for (fixture in listOf(oscillators(), phrases(), figureEight()).filter { only == null || it.name in only }) {
            save("${fixture.name}-demo", fixture)
            val o = VariableMarkovOracle.build(fixture)
            rows.add("${fixture.name}\toracle\t$o")
            for (rw in listOf(3.0, 6.0)) if (!skipOracle) {
                val p = Improviser(o, seed = 1, jumpProbability = 0.15, reuseWeight = rw).take(samples)
                measure(fixture, "Oracle-reuse${rw.toInt()}", p.output, "segments %d reuse %.2f coverage %.2f joinRough %.2f accelKS %.3f".format(
                    p.segments.size, p.reuse(), p.coverage(fixture.length), joinRoughness(fixture, p), accelKs(fixture, p.output)))
            }
            for (policy in if (skipOracle) emptyList() else Policy.entries) {
                val p = Improviser(o, seed = 1, policy = policy, jumpProbability = 0.15).take(samples)
                measure(fixture, policy.name, p.output, "segments %d reuse %.2f coverage %.2f joinRough %.2f accelKS %.3f maxCorr %.2f holds %d".format(
                    p.segments.size, p.reuse(), p.coverage(fixture.length), joinRoughness(fixture, p), accelKs(fixture, p.output), p.correction.max(), p.segments.count { it.reason == Reason.Hold }))
                val big = p.segments.filter { it.correction > 0.5 }.groupingBy { it.reason }.eachCount()
                if (big.isNotEmpty()) rows.add("${fixture.name}\t${policy.name}\tcorrections > 0.5 by reason: $big")
                save("${fixture.name}-${policy.name}-source", Trajectory.of(rate, "source" to p.source.toList()))
            }
            // single-episode history for the forecaster: the last episode
            val history = fixture.episode(fixture.episodeStarts.size - 1)
            for (m in models) {
                val short = m.substringAfterLast('-')
                TimestralSession(m).use { s ->
                    // A1a: one long forecast
                    val t0 = System.nanoTime()
                    val long = s.propose(ContinuationRequest(0, history, samples.coerceAtMost(4096))).get()
                    val longSec = (System.nanoTime() - t0) / 1e9
                    measure(fixture, "$short-long", Trajectory(rate, fixture.channels, long.proposal), "one call %.2f s for %d samples".format(longSec, long.horizon))
                    // A1b: the receding-horizon loop, generated values fed back
                    val rh = RecedingHorizon(s, history, history = 20.0, horizon = 4.0, commit = 1.0, lockstep = true)
                    val p = rh.take(samples)
                    // the hybrid: the oracle proposes, the forecaster judges
                    val modes = listOf(Triple("prefer-w1", JudgeMode.Prefer, 1.0), Triple("veto-1.2", JudgeMode.Veto, 1.2), Triple("veto-2", JudgeMode.Veto, 2.0), Triple("prefer-w1-reuse3", JudgeMode.Prefer, 1.0), Triple("prefer-w1-reuse6", JudgeMode.Prefer, 1.0))
                    for ((label, mode, k) in modes) {
                        val judge = ForecastJudge(s, o, lockstep = true)
                        val reuseWeight = label.substringAfter("reuse", "1").toDouble()
                        val imp = Improviser(o, seed = 1, jumpProbability = 0.15, judge = judge, judgeMode = mode, judgeWeight = k, vetoRatio = k, reuseWeight = reuseWeight)
                        val hp = imp.take(samples)
                        measure(fixture, "Oracle+$short-$label", hp.output, "segments %d reuse %.2f coverage %.2f joinRough %.2f accelKS %.3f judged %d changed %d vetoed %d stayed %d".format(
                            hp.segments.size, hp.reuse(), hp.coverage(fixture.length), joinRoughness(fixture, hp), accelKs(fixture, hp.output),
                            imp.judgedDecisions, imp.judgeChanged, imp.judgeVetoed, imp.judgeStayed))
                        save("${fixture.name}-Oracle+$short-$label-source", Trajectory.of(rate, "source" to hp.source.toList()))
                    }
                    measure(fixture, "$short-receding", p.output, "p50 %.0f ms p95 %.0f ms (%d plans) load %.1f s".format(
                        p.latency(50.0) * 1000, p.latency(95.0) * 1000, p.latencies.size, s.info["loadSeconds"].toString().toDouble()))
                }
            }
        }
        File(out, System.getenv("AFIELD_TEMPORAL_SUMMARY") ?: "summary.tsv").writeText(rows.joinToString("\n") + "\n")
        println(rows.joinToString("\n"))
    }
}
