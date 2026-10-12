package afield.math.gp

import kotlin.math.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpOptimizerTest {

    private fun branin(x: Double, y: Double): Double {
        val b = 5.1 / (4 * PI * PI); val c = 5 / PI; val t = 1 / (8 * PI)
        return (y - b * x * x + c * x - 6).pow(2) + 10 * (1 - t) * cos(x) + 10
    }

    @Test
    fun minimisesBraninNearItsGlobalMinimum() {
        // Global minimum 0.397887 at three points. 30 evaluations, against random search's typical ~2.
        for (seed in listOf(1L, 2L, 3L)) {
            val opt = GpOptimizer("x" to -5.0..10.0, "y" to 0.0..15.0, minimize = true, seed = seed)
            val best = opt.run(30) { p -> branin(p["x"]!!, p["y"]!!) }
            println("branin seed=$seed best=${best.value} at ${best.parameters} hyper=${opt.hyperparameters}")
            assertTrue(best.value < 0.6, "seed $seed: best ${best.value}")
        }
    }

    @Test
    fun beatsRandomSearchOnTheSameBudget() {
        val budget = 25
        var gpWins = 0
        for (seed in 1L..5L) {
            val opt = GpOptimizer("x" to -5.0..10.0, "y" to 0.0..15.0, minimize = true, seed = seed)
            val bo = opt.run(budget) { p -> branin(p["x"]!!, p["y"]!!) }.value
            val r = java.util.Random(seed)
            val rs = (0 until budget).minOf { branin(-5 + 15 * r.nextDouble(), 15 * r.nextDouble()) }
            if (bo < rs) gpWins++
        }
        assertEquals(5, gpWins)
    }

    @Test
    fun maximisesAOneDimensionalToyWithUcb() {
        val f = { x: Double -> sin(3 * x) + 0.5 * cos(7 * x) - 0.1 * x * x }
        val truth = (0..20000).map { -2.0 + 4.0 * it / 20000 }.maxOf(f)
        val opt = GpOptimizer("x" to -2.0..2.0, acquisition = GpAcquisition.UpperConfidenceBound(2.0), seed = 7)
        val best = opt.run(15) { f(it["x"]!!) }
        assertTrue(truth - best.value < 1e-2, "gap ${truth - best.value}")
        // Best-so-far is monotone and ends at best.
        val trace = opt.bestSoFar
        assertTrue(trace.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(best.value, trace.last())
    }

    @Test
    fun isDeterministicForASeed() {
        fun once() = GpOptimizer("x" to 0.0..1.0, "y" to 0.0..1.0, seed = 11).let { o ->
            o.run(10) { p -> -(p["x"]!! - 0.3).pow(2) - (p["y"]!! - 0.6).pow(2) }; o.history
        }
        assertEquals(once(), once())
    }

    @Test
    fun batchSuggestionsAreDistinctAndInBounds() {
        val opt = GpOptimizer("a" to 1.0..2.0, "b" to -3.0..3.0, seed = 5)
        repeat(6) { opt.step { p -> p["a"]!! - abs(p["b"]!!) } }
        val batch = opt.suggest(4)
        assertEquals(4, batch.size)
        for (p in batch) { assertTrue(p["a"]!! in 1.0..2.0); assertTrue(p["b"]!! in -3.0..3.0) }
        for (i in batch.indices) for (j in i + 1 until batch.size)
            assertTrue(abs(batch[i]["a"]!! - batch[j]["a"]!!) + abs(batch[i]["b"]!! - batch[j]["b"]!!) > 1e-3)
        // Prediction is in the objective's units: near an observation it is close to the observed value.
        val t = opt.history.first()
        assertTrue(abs(opt.predict(t.parameters)!!.mean - t.value) < 0.2)
    }

    @Test
    fun normalCdfIsAccurate() {
        assertEquals(0.5, gpNormalCdf(0.0), 1e-7)
        assertEquals(0.9750021048517795, gpNormalCdf(1.96), 1e-7)
        assertEquals(1.2798125438858352e-12, gpNormalCdf(-7.0), 1e-12 * 1e-5)
        // The inverse Mills ratio switches branch at -8 without a jump.
        assertEquals(gpInverseMills(-7.999999), gpInverseMills(-8.000001), 1e-3)
        assertEquals(gpLogNormalCdf(-7.999999), gpLogNormalCdf(-8.000001), 1e-3)
    }
}

class GpPreferenceTest {

    @Test
    fun recoversTheFavouriteOfASyntheticJudge() {
        // A hidden taste with one peak; the judge answers truthfully. After 20 questions the model's
        // favourite should be near the peak, and its ranking should agree with the hidden utility.
        val peak = doubleArrayOf(0.7, 0.25)
        fun taste(p: Map<String, Double>) = -((p["a"]!! - peak[0]).pow(2) + (p["b"]!! - peak[1]).pow(2))
        for (seed in listOf(1L, 2L, 3L)) {
            val pref = GpPreference("a" to 0.0..1.0, "b" to 0.0..1.0, seed = seed)
            repeat(20) {
                val pair = pref.nextPair()
                pref.choose(pair, aWins = taste(pair.first) > taste(pair.second))
            }
            val best = pref.best!!
            val d = hypot(best["a"]!! - peak[0], best["b"]!! - peak[1])
            println("preference seed=$seed best=$best distance=$d newton=${pref.newtonIterations}")
            assertTrue(d < 0.15, "seed $seed: favourite $best is $d from the peak")
            val ranked = pref.ranking()
            assertTrue(spearman(ranked.map { it.second }, ranked.map { taste(it.first) }) > 0.7)
        }
    }

    @Test
    fun oneAnswerOrdersTheTwoPointsAndLeavesFarPointsAtThePrior() {
        val pref = GpPreference("x" to 0.0..1.0)
        val a = mapOf("x" to 0.2); val b = mapOf("x" to 0.6)
        pref.prefer(a, b)
        assertTrue(pref.utility(a).mean > 0 && pref.utility(b).mean < 0)
        assertEquals(-pref.utility(a).mean, pref.utility(b).mean, 1e-9) // symmetric pair, symmetric mode
        assertTrue(pref.utility(a).variance < 1.0)
        val far = GpPreference("x" to 0.0..10.0).also { it.prefer(mapOf("x" to 0.0), mapOf("x" to 0.5)) }.utility(mapOf("x" to 10.0))
        // Unit distance 0.95 at length scale 0.25: k = exp(-7.2) ≈ 7e-4 of the prior, so within 1% of the prior.
        assertEquals(0.0, far.mean, 1e-2); assertEquals(1.0, far.variance, 1e-2)
    }

    @Test
    fun relativeUtilityIsCertainAtTheReferenceAndNarrowerThanAbsolute() {
        val pref = GpPreference("x" to 0.0..1.0, seed = 4)
        repeat(10) { val p = pref.nextPair(); pref.choose(p, aWins = -(p.first["x"]!! - 0.6).pow(2) > -(p.second["x"]!! - 0.6).pow(2)) }
        val best = pref.best!!
        val self = pref.utilityRelativeTo(best, best)
        assertEquals(0.0, self.mean, 1e-12); assertEquals(0.0, self.variance, 1e-9)
        val near = mapOf("x" to best["x"]!! + 0.05)
        assertTrue(pref.utilityRelativeTo(best, near).variance < pref.utility(near).variance)
    }

    @Test
    fun contradictoryAnswersStayFinite() {
        val pref = GpPreference("x" to 0.0..1.0, noise = 0.01)
        val a = mapOf("x" to 0.3); val b = mapOf("x" to 0.7)
        repeat(5) { pref.prefer(a, b); pref.prefer(b, a) }
        assertEquals(pref.utility(a).mean, pref.utility(b).mean, 1e-6)
        val (p, q) = pref.nextPair()
        assertTrue(p != q)
    }

    private fun spearman(a: List<Double>, b: List<Double>): Double {
        fun ranks(v: List<Double>) = v.indices.sortedBy { v[it] }.withIndex().associate { it.value to it.index.toDouble() }
        val ra = ranks(a); val rb = ranks(b); val n = a.size
        val ma = (n - 1) / 2.0
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in 0 until n) { val x = ra[i]!! - ma; val y = rb[i]!! - ma; num += x * y; da += x * x; db += y * y }
        return num / sqrt(da * db)
    }
}
