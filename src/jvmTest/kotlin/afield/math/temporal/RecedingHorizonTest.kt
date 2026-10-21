package afield.math.temporal

import afield.math.oracle.Trajectory
import afield.math.oracle.VariableMarkovOracle
import java.util.concurrent.CompletableFuture
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecedingHorizonTest {

    /** Replies only when the test says so: proposes `history.last + k` per channel. */
    private class ManualSession : TemporalSession {
        override val capabilities = TemporalCapabilities()
        val pending = ArrayList<Pair<ContinuationRequest, CompletableFuture<Continuation>>>()
        override fun propose(request: ContinuationRequest): CompletableFuture<Continuation> =
            CompletableFuture<Continuation>().also { pending.add(request to it) }
        fun answer(i: Int = pending.size - 1) {
            val (r, f) = pending.removeAt(i)
            val last = r.history.frame(r.history.length - 1)
            f.complete(Continuation(r.revision, Array(r.history.width) { c -> DoubleArray(r.horizon) { last[c] + it + 1 } }))
        }
    }

    private val seed = Trajectory.of(10.0, "a" to List(50) { it.toDouble() }, "b" to List(50) { 0.0 })

    @Test
    fun neverBlocksAndCountsUnderruns() {
        val s = ManualSession()
        val rh = RecedingHorizon(s, seed, horizon = 2.0, commit = 0.5)
        assertEquals(1, s.pending.size, "one request in flight from the start")
        repeat(5) { rh.step() }
        assertEquals(5, rh.underruns)
        assertEquals(1, s.pending.size, "no second request while one is in flight")
        s.answer()
        val f = rh.step()
        // plan was made from history ending at sample 49 (value 49) when the player was at index 0
        assertTrue(f[0] > 49.0)
    }

    @Test
    fun takeoverInvalidatesInFlightReplies() {
        val s = ManualSession()
        val rh = RecedingHorizon(s, seed, horizon = 2.0, commit = 0.5)
        rh.perform("b", 7.0) // revision bump while the first request computes
        s.answer()
        val f = rh.step()
        assertEquals(1, rh.staleReplies)
        assertEquals(7.0, f[1], "performer value is authoritative")
        assertTrue(s.pending.size == 1 && s.pending[0].first.owned[1], "re-requested with ownership")
    }

    @Test
    fun releaseFadesFromThePerformedValue() {
        val s = ManualSession()
        val rh = RecedingHorizon(s, seed, horizon = 5.0, commit = 5.0, releaseTime = 1.0)
        s.answer()
        rh.perform("b", 100.0); s.pending.clear()
        rh.step()
        rh.release("b"); s.answer()
        val first = rh.step()
        assertTrue(abs(first[1] - 100.0) < 15.0, "first released sample should start near the performed value, was ${first[1]}")
        repeat(12) { rh.step() }
        assertTrue(rh.releaseCorrection == 0.0)
    }

    @Test
    fun oracleSessionContinuesFromTheCue() {
        val t = Trajectory.of(20.0, "x" to List(400) { sin(2 * PI * it / 40.0) })
        val o = VariableMarkovOracle.build(t)
        val rh = RecedingHorizon(OracleSession(o), t.episode(0).let { Trajectory(20.0, it.channels, arrayOf(it.values[0].copyOfRange(0, 60))) }, lockstep = true)
        val p = rh.take(200)
        assertEquals(0, p.underruns)
        // a sine continued from memory keeps its period: compare against a shifted copy of itself
        val out = p.output.values[0]
        var err = 0.0
        for (i in 40 until 200) err += abs(out[i] - out[i - 40])
        assertTrue(err / 160 < 0.1, "continuation lost the period: mean error ${err / 160}")
    }
}

/** Opt-in: needs the Timestral worker installed (`TimestralSession.install()`); set AFIELD_TIMESTRAL_TEST=1. */
class TimestralSessionTest {
    @Test
    fun forecastsASine() {
        if (System.getenv("AFIELD_TIMESTRAL_TEST") != "1") return
        TimestralSession().use { s ->
            val h = Trajectory.of(20.0, "x" to List(400) { sin(2 * PI * it / 40.0) })
            val c = s.propose(ContinuationRequest(0, h, 80)).get()
            assertEquals(21, c.quantiles!![0][0].size)
            var err = 0.0
            for (k in 0 until 80) err += abs(c.proposal[0][k] - sin(2 * PI * (400 + k) / 40.0))
            assertTrue(err / 80 < 0.2, "median should continue a clean sine: ${err / 80}")
        }
    }

    /** A2: y is x delayed by 1 s; x's future is given as a known covariate, so y's forecast should follow it. */
    @Test
    fun knownCovariateRelationshipIsUsed() {
        if (System.getenv("AFIELD_TIMESTRAL_TEST") != "1") return
        val r = java.util.Random(3)
        // an irregular control signal: smoothed noise, so y cannot be predicted from y's own history
        val n = 600; val hz = 100; val lag = 20
        val raw = DoubleArray(n + hz + lag) { r.nextGaussian() }
        val x = DoubleArray(raw.size).also { var v = 0.0; for (i in it.indices) { v = 0.9 * v + 0.1 * raw[i]; it[i] = v * 3 } }
        val xs = x.copyOfRange(lag, lag + n + hz) // known over history and future
        val ys = x.copyOfRange(0, n + hz)          // y[t] = xs[t - lag]
        TimestralSession().use { s ->
            val h = Trajectory.of(20.0, "y" to ys.copyOfRange(0, n).toList())
            val withKnown = s.propose(ContinuationRequest(0, h, hz, known = arrayOf(xs))).get().proposal[0]
            val without = s.propose(ContinuationRequest(0, h, hz)).get().proposal[0]
            fun mae(p: DoubleArray) = (0 until hz).sumOf { abs(p[it] - ys[n + it]) } / hz
            println("A2 known-covariate: MAE with x known %.3f, without %.3f, signal std %.3f".format(mae(withKnown), mae(without), kotlin.math.sqrt(ys.sumOf { it * it } / ys.size)))
            assertTrue(mae(withKnown) < 0.7 * mae(without), "known future x should help forecast lagged y: ${mae(withKnown)} vs ${mae(without)}")
        }
    }
}
