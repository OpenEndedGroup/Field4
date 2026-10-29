package afield.math.temporal

import afield.io.mocap.Bvh
import afield.math.oracle.Improviser
import afield.math.oracle.JudgeMode
import afield.math.oracle.Policy
import afield.math.oracle.Trajectory
import afield.math.oracle.VariableMarkovOracle
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test

/**
 * The phrase fixture's verdict, checked on the output itself, then the same comparison on real
 * motion capture (CMU subject 05, six modern-dance takes).
 * `AFIELD_TEMPORAL_EXPERIMENTS=1 ./gradlew jvmTest --tests 'afield.math.temporal.DanceExperiments'`
 */
class DanceExperiments {
    private val dir = File("public/docs/assets/temporal_memory/cmu_05")
    private val takes = ["05_02", "05_07", "05_09", "05_12", "05_14", "05_18"]

    @Test
    fun run() {
        if (System.getenv("AFIELD_TEMPORAL_EXPERIMENTS") != "1") return
        val rows = ArrayList<String>()

        // 1. the old phrase fixture, now measured on the output
        val r = java.util.Random(11)
        fun rise() = List(30) { it / 29.0 }
        fun hes() = List(25) { 1.0 + 0.03 * r.nextGaussian() }
        fun burst() = List(30) { 1.0 + 0.7 * sin(it * 8 * PI / 29) }
        fun pause() = List(20 + r.nextInt(20)) { 0.0 }
        val phrases = Trajectory.join(listOf(
            rise() + hes() + burst() + pause() + rise() + burst() + pause(),
            rise() + burst() + pause() + rise() + hes() + hes() + burst() + pause(),
            hes() + burst() + pause() + rise() + hes() + burst() + pause() + rise(),
        ).map { Trajectory.of(20.0, "v" to it) })
        val po = VariableMarkovOracle.build(phrases)
        for (policy in Policy.entries) {
            val p = Improviser(po, seed = 1, policy = policy, jumpProbability = 0.15).take(180.0)
            rows += "phrases\t$policy\treuse %.2f coverage %.2f outputRepetition %.2f".format(p.sourceReuse(), p.coverage(phrases.length), p.outputRepetition())
        }

        // 2. dance: six takes, all joints in the dancer's frame, 30 Hz
        val bvhs = takes.map { Bvh.read(File(dir, "$it.bvh.gz")) }
        val dance = Trajectory.join(bvhs.map { it.toTrajectory(rate = 30.0) }, "cmu05")
        val key = ["LeftHand", "RightHand", "Head", "LeftToeBase", "RightToeBase"]
            .flatMap { j -> listOf("$j.x", "$j.y", "$j.z").map { dance.channels.indexOf(it) } }
        val t0 = System.nanoTime()
        val o = VariableMarkovOracle.build(dance)
        rows += "dance\toracle\t$o built in %.1f s, ${dance.width} channels, ${"%.0f".format(dance.duration)} s".format((System.nanoTime() - t0) / 1e9)
        rows += "dance\tdemo\toutputRepetition of the takes themselves %.2f".format(
            afield.math.oracle.Performance(dance, IntArray(0), DoubleArray(0), emptyArray(), emptyList()).outputRepetition(channels = key))
        val configs = listOf(Triple(Policy.Loop, 1.0, 10.0), Triple(Policy.NearestHistory, 1.0, 10.0), Triple(Policy.Oracle, 1.0, 10.0),
            Triple(Policy.Oracle, 3.0, 30.0), Triple(Policy.Oracle, 3.0, 60.0), Triple(Policy.Oracle, 6.0, 60.0))
        for ((policy, rw, mem) in configs) {
            val p = Improviser(o, seed = 1, policy = policy, jumpProbability = 0.1, reuseWeight = rw, reuseMemory = mem).take(180.0)
            val perTake = dance.episodeStarts.indices.map { e -> "%.2f".format(p.coverage(dance.length).let { _ ->
                val ep = (dance.episodeStarts[e] until (if (e + 1 < dance.episodeStarts.size) dance.episodeStarts[e + 1] else dance.length))
                p.source.filter { it in ep }.toSet().size.toDouble() / ep.count() }) }
            rows += "dance\t$policy rw$rw mem$mem\tper-take coverage $perTake"
            rows += "dance\t$policy rw$rw mem$mem\treasons ${p.segments.groupingBy { it.reason }.eachCount()}"
            rows += "dance\t$policy rw$rw mem$mem\tsegments %d reuse %.2f coverage %.2f outputRepetition %.2f joinRough %.2f maxCorr %.2f holds %d".format(
                p.segments.size, p.sourceReuse(), p.coverage(dance.length), p.outputRepetition(channels = key), p.joinRoughness(dance), p.correction.max(),
                p.segments.count { it.reason == afield.math.oracle.Reason.Hold })
        }
        val models = System.getenv("AFIELD_TEMPORAL_MODELS")?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        for (m in models) TimestralSession(m).use { s ->
            for ((label, mode) in listOf("prefer" to JudgeMode.Prefer, "veto" to JudgeMode.Veto)) {
                val judge = ForecastJudge(s, o, lockstep = true)
                val imp = Improviser(o, seed = 1, jumpProbability = 0.1, judge = judge, judgeMode = mode)
                val p = imp.take(180.0)
                rows += "dance\t${m.substringAfterLast('-')} $label\tsegments %d reuse %.2f coverage %.2f outputRepetition %.2f joinRough %.2f judged %d changed %d p95 %.0f ms".format(
                    p.segments.size, p.sourceReuse(), p.coverage(dance.length), p.outputRepetition(channels = key), p.joinRoughness(dance),
                    imp.judgedDecisions, imp.judgeChanged, judge.latencies.sorted().let { if (it.isEmpty()) Double.NaN else it[(it.size * 0.95).toInt().coerceAtMost(it.size - 1)] * 1000 })
            }
        }
        File("build/temporal-experiments").mkdirs()
        File("build/temporal-experiments/dance.tsv").writeText(rows.joinToString("\n") + "\n")
        println(rows.joinToString("\n"))
    }
}
