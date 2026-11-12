package afield.math.distribution

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The estimators against values known exactly. Every Monte Carlo claim is "within k standard
 * errors" with k = 4 (a false alarm about once in 16 000 runs; the seeds are fixed, so a run is
 * deterministic anyway), and every test also checks the standard error is the right size, so a
 * claimed SE of 10 cannot make any estimate pass.
 */
class DistTest {

    private fun check(label: String, e: Estimate, exact: Double, maxSe: Double) {
        assertTrue(e.within(exact, 4.0), "$label: $e is ${"%.2f".format(e.zFrom(exact))} SE from $exact")
        assertTrue(e.se in 0.0..maxSe, "$label: SE ${e.se} larger than $maxSe")
    }

    @Test
    fun aNameIsAFixedNode() {
        val x = normal(3, 2)
        val zero = (x - x).samples(10_000)
        assertTrue(zero.all { it == 0.0 }, "x - x must be exactly 0 on every sample")
        val twice = jointSamples(x + x, x, n = 1000)
        for (i in 0 until 1000) assertEquals(2 * twice[1][i], twice[0][i], 1e-12)
        // an iid copy is a different variable: x - x' has variance 8
        check("Var(x - iid x)", Var(x - x.iid()), 8.0, 0.1)
    }

    @Test
    fun iidPreservesSharingInsideTheCopy() {
        val x = die(6)
        val y = (x - x).iid()
        assertTrue(y.samples(1000).all { it == 0.0 })
    }

    @Test
    fun diracIsASpike() {
        val a = Dist.dirac(5)
        assertEquals(mapOf(5.0 to 1.0), exactPmf(a))
        val e = E(a, n = 1000)
        assertEquals(5.0, e.value); assertEquals(0.0, e.se)
    }

    @Test
    fun theFirstFigure_E_of_S_is_12() {
        val s = 5 + die(6) + die(6)
        check("E(5 + d6 + d6)", E(s), 12.0, 0.01)
        val pmf = exactPmf(s)!!
        assertEquals(11, pmf.size)
        assertEquals(6.0 / 36, pmf[12.0]!!, 1e-12)
        assertEquals(1.0 / 36, pmf[7.0]!!, 1e-12)
    }

    @Test
    fun twoDiceUnderTen() {
        val x = die(6); val y = die(6)
        check("P(X+Y<10)", P(x + y lt 10), 30.0 / 36, 0.002)
        assertEquals(30.0 / 36, exactPmf(x + y lt 10)!![1.0]!!, 1e-12)
        // shared leaves are enumerated rather than wrongly convolved: x + x is 2, 4, ..., 12
        assertEquals(6, exactPmf(x + x)!!.size)
        assertNull(exactPmf(x + uniform(0, 1)))
    }

    @Test
    fun birthdayParadox() {
        val exact = 1.0 - (0 until 23).fold(1.0) { acc, k -> acc * (365.0 - k) / 365.0 }
        check("birthday", P(hasDuplicates(draws(23, uniformInt(1, 365)))), exact, 0.002)
        assertEquals(0.5073, exact, 1e-4)
    }

    @Test
    fun piFromTheUnitCircle() {
        val x = uniform(-1, 1); val y = uniform(-1, 1)
        check("4 P(C)", P(x * x + y * y lt 1) * 4, PI, 0.01)
    }

    @Test
    fun varianceAndQuantiles() {
        check("Var(N(0,3))", Var(normal(0, 3)), 9.0, 0.05)
        check("Var(d6)", Var(die(6)), 35.0 / 12, 0.01)
        check("median exp(1)", quantile(exponential(1), 0.5), ln(2.0), 0.005)
        check("q90 N(0,1)", quantile(normal(), 0.9), 1.2815516, 0.01)
    }

    @Test
    fun mixturesAndBernoulli() {
        check("E(bern .3)", E(bernoulli(0.3)), 0.3, 0.002)
        val m = mixture(1 to Dist.dirac(0), 3 to Dist.dirac(10))
        check("E(mix)", E(m), 7.5, 0.02)
        check("E(categorical)", E(categorical(listOf(1, 2, 3), listOf(1, 1, 2))), 2.25, 0.005)
    }

    @Test
    fun progressiveSettles() {
        val p = Progressive(5 + die(6) + die(6))
        repeat(20) { p.draw(5000) }
        assertEquals(100_000L, p.estimate.n)
        check("progressive", p.estimate, 12.0, 0.01)
        val tr = p.trace
        assertEquals(20, tr.size)
        assertTrue(tr.last().third < tr.first().third / 3, "SE should shrink like 1/sqrt(n)")
        // block merge agrees with sample-by-sample
        val m = Moments(); val s = (die(6) * 1.5).samples(1000)
        m.addBlock(500, s.take(500).sum(), s.take(500).sumOf { it * it })
        m.addBlock(500, s.drop(500).sum(), s.drop(500).sumOf { it * it })
        val direct = Moments().apply { addAll(s) }
        assertEquals(direct.mean().value, m.mean().value, 1e-9)
        assertEquals(direct.variance(), m.variance(), 1e-9)
    }

    @Test
    fun exactDensityOfNormalSums() {
        val pdf = assertNotNull(exactPdf(normal(1, 1) + normal(2, 2)))
        val s = kotlin.math.sqrt(5.0)
        assertEquals(1 / (s * kotlin.math.sqrt(2 * PI)), pdf(3.0), 1e-9)
    }

    @Test
    fun lowersToGlsl() {
        val x = die(6)
        val g = (x - x + uniformInt(1, 365)).toGlsl()
        // x is one local, read twice
        assertEquals(1, Regex("nlRnd\\(s\\) \\* 6\\.0").findAll(g).count(), g)
        assertTrue("return n" in g)
        val bday = hasDuplicates(draws(23, uniformInt(1, 365))).toGlsl()
        assertEquals(23 * 22 / 2, Regex("==").findAll(bday).count())
        assertFailsWith<UnsupportedOperationException> { x.map { it * 2 }.toGlsl() }
    }

    @Test
    fun svgDrawsWithoutData() {
        val svg = distributionSvg(Progressive(die(6)))
        assertTrue("no samples yet" in svg)
        val p = Progressive(die(6) + die(6)).draw(2000).draw(2000)
        val s2 = distributionSvg(p, title = "2d6", exact = 7.0)
        assertTrue("exact pmf" in s2 && abs(s2.length) > 1000)
        assertTrue("kernel estimate" in distributionSvg(Progressive(normal().map { it * it }).draw(3000).draw(3000)))
    }
}
