package afield.math.oracle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VmoReferenceTest {

    /** Suffix links, repeated-suffix lengths and summed IR agree with the pinned Python `vmo` (see tools/vmo_reference_fixture.py). */
    @Test
    fun matchesReferencePackage() {
        val root = Json.parseToJsonElement(File("src/jvmTest/resources/afield/math/oracle/vmo_reference.json").readText()).jsonObject
        val cases = root["cases"]!!.jsonArray
        assertTrue(cases.size >= 10)
        for (c in cases) {
            val o = c.jsonObject
            val name = "${o["name"]!!.jsonPrimitive.content} @ ${o["threshold"]!!.jsonPrimitive.double}"
            val frames = o["frames"]!!.jsonArray.map { r -> r.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray() }.toTypedArray()
            val dummy = Trajectory(1.0, listOf("i"), arrayOf(DoubleArray(frames.size)))
            val f = GestureFeatures(DoubleArray(1), DoubleArray(1) { 1.0 }, DoubleArray(1) { 1.0 })
            val vmo = VariableMarkovOracle(dummy, f, frames, o["threshold"]!!.jsonPrimitive.double)
            val sfx = o["sfx"]!!.jsonArray.map { it.jsonPrimitive.int }
            val lrs = o["lrs"]!!.jsonArray.map { it.jsonPrimitive.int }
            assertEquals(sfx, vmo.sfx.toList(), "sfx for $name")
            assertEquals(lrs, vmo.lrs.toList(), "lrs for $name")
            assertEquals(o["irSum"]!!.jsonPrimitive.double, vmo.totalInformationRate(), 1e-6, "IR for $name")
        }
    }
}

class VariableMarkovOracleTest {

    private fun circle(n: Int, period: Int, rate: Double = 30.0) =
        Trajectory.of(rate, "x" to List(n) { sin(2 * PI * it / period) }, "y" to List(n) { cos(2 * PI * it / period) }, name = "circle")

    @Test
    fun repeatsAreFoundAndThresholdScanPeaksInside() {
        // a non-integer period with a little noise: no two samples repeat exactly
        val r = java.util.Random(1)
        val t = Trajectory.of(30.0, "x" to List(600) { sin(2 * PI * it / 37.3) + 0.01 * r.nextGaussian() }, "y" to List(600) { cos(2 * PI * it / 37.3) + 0.01 * r.nextGaussian() })
        val scan = VariableMarkovOracle.thresholdScan(t)
        val best = scan.indices.maxBy { scan[it].second }
        assertTrue(best in 1 until scan.size - 1, "IR should peak strictly inside the scanned thresholds, peaked at $best of ${scan.map { "%.0f".format(it.second) }}")
        val o = scan[best].first
        assertTrue(o.lrs.max() > 2 * 37, "the longest repeat should span more than two periods: ${o.lrs.max()}")
    }

    @Test
    fun episodesNeverRunIntoEachOther() {
        val a = Trajectory.of(20.0, "v" to List(100) { sin(it * 0.3) })
        val b = Trajectory.of(20.0, "v" to List(100) { 5 + sin(it * 0.3) })
        val t = Trajectory.join(listOf(a, b))
        assertEquals(listOf(0, 100), t.episodeStarts.toList())
        val o = VariableMarkovOracle.build(t)
        assertTrue(100 + 1 !in o.transitions(100).toList(), "no forward transition from the end of episode 0 to the start of episode 1")
        for (i in 101..200) assertTrue(o.lrs[i] <= i - 100, "context at state $i reaches back across the boundary")
        val p = Improviser(o, seed = 3).take(2000)
        for (k in 1 until p.source.size) {
            val s0 = p.source[k - 1]; val s1 = p.source[k]
            if (s0 >= 0 && s1 == s0 + 1) assertEquals(t.episodeOf(s0), t.episodeOf(s1), "continued across a boundary at output $k")
        }
    }

    @Test
    fun crossingsAreTellApartByVelocity() {
        // a figure-eight crosses the origin twice, in opposite vertical directions
        val n = 400
        val t = Trajectory.of(30.0, "x" to List(n) { sin(4 * PI * it / 200) }, "y" to List(n) { sin(2 * PI * it / 200) })
        val o = VariableMarkovOracle.build(t)
        val history = (90..100).map { t.frame(it) } // passing through the centre going down
        val cue = o.cue(history, window = history.size, count = 3)
        for (c in cue.take(2)) {
            val dy = t.values[1][c.position] - t.values[1][c.position - 1]
            val dyRef = t.values[1][100] - t.values[1][99]
            assertTrue(dy * dyRef > 0, "cue at ${c.position} matched the crossing going the other way")
        }
    }

    @Test
    fun deterministicForASeed() {
        val o = VariableMarkovOracle.build(circle(300, 37))
        val a = Improviser(o, seed = 9).take(500)
        val b = Improviser(o, seed = 9).take(500)
        assertEquals(a.source.toList(), b.source.toList())
        assertTrue(a.output.values[0].contentEquals(b.output.values[0]))
    }

    @Test
    fun oracleRecombinesWhereLoopRepeats() {
        val t = phrases()
        val o = VariableMarkovOracle.build(t)
        val loop = Improviser(o, policy = Policy.Loop).take(3000)
        val orc = Improviser(o, seed = 5, jumpProbability = 0.3).take(3000)
        assertTrue(orc.segments.count { it.reason == Reason.SuffixLink } > 5, "oracle should take suffix-link jumps: ${orc.segments.groupingBy { it.reason }.eachCount()}")
        assertTrue(loop.segments.none { it.reason == Reason.SuffixLink })
        // every jump is grounded: its join distance is within the admitted bound
        for (s in orc.segments.filter { it.reason == Reason.SuffixLink }) assertTrue(s.distance <= 2.0 * o.threshold + 1e-9)
    }

    @Test
    fun takeoverIsAuthoritativeAndReleaseFades() {
        val o = VariableMarkovOracle.build(circle(600, 60))
        val imp = Improviser(o, seed = 2, joinTime = 0.2)
        val p = imp.take(400) { i ->
            if (i in 100 until 200) perform("x", 0.25)
            if (i == 200) release("x")
        }
        for (i in 100 until 200) { assertEquals(0.25, p.output.values[0][i]); assertTrue(p.owned[0][i]) }
        assertTrue(!p.owned[0][200])
        // no step larger than the recording's own largest step plus the fade's share
        val maxStep = (1 until 600).maxOf { abs(o.trajectory.values[0][it] - o.trajectory.values[0][it - 1]) }
        assertTrue(abs(p.output.values[0][200] - 0.25) < 3 * maxStep + 0.2, "release jumped from 0.25 to ${p.output.values[0][200]}")
        assertTrue(p.segments.any { it.reason == Reason.Takeover || it.reason == Reason.Cue })
    }

    @Test
    fun outOfSupportTakeoverDoesNotJitterTheFreeChannels() {
        // two oscillators; x held at a constant it never rests at in the recording
        val rate = 20.0
        val t = Trajectory.of(rate, "x" to List(900) { sin(2 * PI * 0.5 * it / rate) }, "y" to List(900) { 0.7 * sin(2 * PI * 0.75 * it / rate + 0.6) })
        val o = VariableMarkovOracle.build(t)
        val p = Improviser(o, seed = 2).take(500) { i -> if (i in 200 until 300) perform("x", 0.8) else if (i == 300) release("x") }
        val takeovers = p.segments.count { it.reason == Reason.Takeover && it.outputStart in 200 until 300 }
        assertTrue(takeovers <= 100 / (rate * 1.0).toInt() + 1, "free channel re-cued $takeovers times in 5 s")
        val maxStep = (1 until 900).maxOf { abs(t.values[1][it] - t.values[1][it - 1]) }
        val reversals = (202 until 300).count { i -> val a = p.output.values[1][i] - p.output.values[1][i - 1]; val b = p.output.values[1][i - 1] - p.output.values[1][i - 2]; a * b < 0 && abs(a) > 0.5 * maxStep }
        assertTrue(reversals < 10, "y zigzags during the takeover: $reversals fast reversals")
    }

    private fun phrases(): Trajectory {
        val rise = List(20) { it / 19.0 }
        val hes = List(10) { 1.0 + 0.02 * sin(it.toDouble()) }
        val burst = List(24) { 1.0 + 0.6 * sin(it * 6 * PI / 23) }
        val pause = List(12) { 0.0 }
        val seq = listOf(rise, hes, burst, pause, rise, burst, pause, rise, hes, hes, burst, pause).flatten()
        return Trajectory.of(20.0, "v" to seq, name = "phrases")
    }
}

class SeveralTakesTest {
    // three takes of smoothed noise, each starting at rest and sharing one 2-second motif twice
    private fun takes() = Trajectory.join((0 until 3).map { k ->
        val r = java.util.Random(100L + k)
        var a = 0.0; var b = 0.0; var va = 0.0; var vb = 0.0
        val xs = DoubleArray(300); val ys = DoubleArray(300)
        for (i in 0 until 300) {
            va = 0.9 * va + 0.03 * r.nextGaussian() - 0.01 * a; vb = 0.9 * vb + 0.03 * r.nextGaussian() - 0.01 * b
            a += va; b += vb; xs[i] = a; ys[i] = b
        }
        for (at in [60 + 20 * k, 200 - 10 * k]) for (i in 0 until 40) { xs[at + i] = sin(2 * PI * i / 40); ys[at + i] = cos(2 * PI * i / 40) - 1 }
        Trajectory.of(20.0, "a" to xs.toList(), "b" to ys.toList())
    })

    @Test
    fun bridgesPointIntoOtherTakes() {
        val t = takes()
        val o = VariableMarkovOracle.build(t)
        for (i in listOf(10, 350, 700)) {
            val b = o.bridges(i)
            assertTrue(b.isNotEmpty())
            assertTrue(b.all { t.episodeOf(it) != t.episodeOf(i) && !t.endsEpisode(it) })
        }
        assertTrue(VariableMarkovOracle.build(t.episode(0)).bridges(10).isEmpty(), "one take has nothing to bridge to")
    }

    @Test
    fun everyTakeIsPlayedAndNothingFreezes() {
        val t = takes()
        val p = Improviser(VariableMarkovOracle.build(t), seed = 3).take(240.0)
        for (e in 0 until 3) assertTrue(p.source.any { it >= 0 && t.episodeOf(it) == e }, "take $e never played")
        assertTrue(p.segments.none { it.reason == Reason.Hold }, "held: ${p.segments.groupingBy { it.reason }.eachCount()}")
        // no jump ever lands with less than a phrase of its take left
        for (s in p.segments.filter { it.reason != Reason.Start && it.source >= 0 }) {
            val ep = t.episodeOf(s.source)
            val end = if (ep + 1 < t.episodeStarts.size) t.episodeStarts[ep + 1] else t.length
            assertTrue(end - s.source >= 20, "jump at ${s.outputStart} landed ${end - s.source} samples from the end of take $ep")
        }
    }

    @Test
    fun outputRepetitionSeesALoopThatReuseCannot() {
        val t = takes()
        val o = VariableMarkovOracle.build(t)
        val loop = Improviser(o, policy = Policy.Loop).take(120.0)
        assertTrue(loop.outputRepetition() > 0.8, "a loop repeats itself: ${loop.outputRepetition()}")
        // the takes played once each repeat only their shared motif
        val once = Performance(t, IntArray(t.length) { it }, DoubleArray(t.length), arrayOf(BooleanArray(t.length), BooleanArray(t.length)), emptyList())
        assertTrue(once.outputRepetition() < 0.4, "one pass of the takes: ${once.outputRepetition()}")
    }
}

class ContinuationJudgeTest {
    /** Rates a path by how quiet it is: a judge that wants the bursts. */
    private class Loud : ContinuationJudge {
        override fun observe(index: Long, frame: DoubleArray) {}
        override fun loss(index: Long, path: Array<DoubleArray>): Double = 1.0 / (path[0].sumOf { abs(it) } / maxOf(1, path[0].size) + 0.1)
    }

    @Test
    fun judgeSteersButEverySampleStillComesFromMemory() {
        // the same burst recurs four times, each followed by a pause of a different length
        val burst = List(30) { 1.0 + 0.7 * sin(it * 8 * PI / 29) }
        val seq = listOf(burst, List(10) { 0.0 }, burst, List(40) { 0.0 }, burst, List(20) { 0.0 }, burst, List(60) { 0.0 }).flatten()
        val t = Trajectory.of(20.0, "v" to seq)
        val o = VariableMarkovOracle.build(t)
        val plain = Improviser(o, seed = 7, jumpProbability = 0.5).take(4000)
        val judged = Improviser(o, seed = 7, jumpProbability = 0.5, judge = Loud(), judgeWeight = 6.0)
        val p = judged.take(4000)
        // flat zeros match each other everywhere, so unjudged playback lingers in the pauses
        fun loudness(x: DoubleArray) = x.count { abs(it) > 0.05 }.toDouble() / x.size
        assertTrue(judged.judgedDecisions > 20, "judge should be consulted: ${judged.judgedDecisions}")
        assertTrue(judged.judgeChanged > 0)
        assertTrue(loudness(p.output.values[0]) > loudness(plain.output.values[0]) + 0.1,
            "a judge preferring the bursts should yield more of them: ${loudness(p.output.values[0])} vs ${loudness(plain.output.values[0])}")
        for (i in p.source.indices) if (p.source[i] >= 0 && p.correction[i] == 0.0) assertEquals(t.values[0][p.source[i]], p.output.values[0][i], 1e-12)
    }

    @Test
    fun vetoOnlyRemovesCandidates() {
        val burst = List(30) { 1.0 + 0.7 * sin(it * 8 * PI / 29) }
        val seq = listOf(burst, List(10) { 0.0 }, burst, List(40) { 0.0 }, burst, List(20) { 0.0 }, burst, List(60) { 0.0 }).flatten()
        val o = VariableMarkovOracle.build(Trajectory.of(20.0, "v" to seq))
        fun loudness(x: DoubleArray) = x.count { abs(it) > 0.05 }.toDouble() / x.size
        val plain = Improviser(o, seed = 7, jumpProbability = 0.5).take(4000)
        val imp = Improviser(o, seed = 7, jumpProbability = 0.5, judge = Loud(), judgeMode = JudgeMode.Veto, vetoRatio = 1.0)
        val p = imp.take(4000)
        assertTrue(imp.judgeVetoed > 0, "veto should remove some jumps into quieter passages")
        assertTrue(loudness(p.output.values[0]) >= loudness(plain.output.values[0]), "vetoing quieter jumps must not make it quieter")
    }
}
