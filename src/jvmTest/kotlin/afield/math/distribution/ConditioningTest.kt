package afield.math.distribution

import afield.graphics.shader.ShaderKind
import afield.graphics.shader.ShaderSPIRVUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Conditioning against posteriors known in closed form: rejection on dice (exact by enumeration),
 * a coin with a uniform prior (Beta), a normal mean with a normal prior, and a Poisson rate with a
 * gamma prior. As in [DistTest], an estimate must be within four standard errors of the exact value
 * and its standard error must be small, so a vast SE cannot pass.
 */
class ConditioningTest {

    private fun check(label: String, e: Estimate, exact: Double, maxSe: Double) {
        assertTrue(e.within(exact, 4.0), "$label: $e is ${"%.2f".format(e.zFrom(exact))} SE from $exact")
        assertTrue(e.se in 0.0..maxSe, "$label: SE ${e.se} larger than $maxSe")
    }

    @Test
    fun rejectionOnDiceIsExactByEnumeration() {
        val x = die(6); val y = die(6)
        val firstIsSix = (x eq 6) given (x + y ge 10)
        // six ways to make 10 or more: (4,6) (5,5) (5,6) (6,4) (6,5) (6,6); three start with a 6
        assertEquals(0.5, exactPmf(firstIsSix)!![1.0]!!, 1e-12)
        val p = P(firstIsSix)
        check("P(x = 6 | x + y >= 10)", p, 0.5, 0.005)
        // one sample in six is accepted, and the ESS says so
        assertEquals(200_000.0 / 6, p.ess, 600.0)
        assertTrue("ess=" in p.toString(), p.toString())
        // the posterior of x itself
        val pmf = exactPmf(x given (x + y ge 10))!!
        assertEquals(listOf(4.0, 5.0, 6.0), pmf.keys.toList())
        assertEquals(1.0 / 6, pmf[4.0]!!, 1e-12)
        assertEquals(2.0 / 6, pmf[5.0]!!, 1e-12)
        assertEquals(3.0 / 6, pmf[6.0]!!, 1e-12)
    }

    @Test
    fun sharedLeavesAreNowExact() {
        val x = die(6)
        val pmf = assertNotNull(exactPmf(x + x))
        assertEquals(listOf(2.0, 4.0, 6.0, 8.0, 10.0, 12.0), pmf.keys.toList())
        assertEquals(1.0 / 6, pmf[8.0]!!, 1e-12)
        val sq = assertNotNull(exactPmf(x * x lt 10))
        assertEquals(0.5, sq[1.0]!!, 1e-12)
        // a mixture of constants (categorical) is exact too
        assertEquals(0.5, exactPmf(categorical(listOf(1, 2, 3), listOf(1, 1, 2)))!![3.0]!!, 1e-12)
        // too many joint values is still "not known", not a slow answer
        assertEquals(null, exactPmf(hasDuplicates(draws(23, uniformInt(1, 365)))))
    }

    @Test
    fun aWeightIsSoftEvidence() {
        // a die weighted by its own value: P(k) proportional to k, mean 91 / 21
        val x = die(6)
        val biased = x given x
        assertEquals(91.0 / 21, exactPmf(biased)!!.entries.sumOf { it.key * it.value }, 1e-12)
        check("E(size-biased die)", E(biased), 91.0 / 21, 0.01)
    }

    @Test
    fun coinWithUniformPriorIsBeta() {
        val p = uniform(0, 1)
        val flips = listOf(1, 1, 0, 1, 1, 1, 0, 1, 1, 0) // 7 heads in 10
        val post = p given observed(bernoulli(p), flips)
        // Beta(8, 4): mean 2/3, variance 8*4 / (12^2 * 13)
        check("E(p | 7 of 10)", E(post), 8.0 / 12, 0.002)
        check("Var(p | 7 of 10)", Var(post), 32.0 / (144 * 13), 0.0005)
        // the same posterior from a beta prior and no data
        check("E(beta(8, 4))", E(beta(8, 4)), 8.0 / 12, 0.002)
    }

    @Test
    fun normalMeanWithNormalPrior() {
        val mu = normal(0, 10)
        val data = doubleArrayOf(2.9, 3.3, 3.1)
        val post = mu given observed(normal(mu, 1), data)
        val precision = 1.0 / 100 + 3
        val mean = data.sum() / precision
        check("E(mu | data)", E(post, n = 400_000), mean, 0.01)
        check("Var(mu | data)", Var(post, n = 400_000), 1 / precision, 0.01)
        check("median(mu | data)", quantile(post, 0.5, n = 400_000), mean, 0.02)
        // samples() of a posterior are resampled draws from it
        val s = post.samples(100_000)
        assertEquals(mean, s.average(), 0.03)
        // the prior is the same variable, unconditioned
        assertEquals(mu.describe(), post.prior().describe())
        check("E(prior)", E(post.prior()), 0.0, 0.05)
    }

    @Test
    fun evidenceJoinsWithAnd() {
        val mu = normal(0, 10)
        val a = mu given (observed(normal(mu, 1), 2.9, 3.3) and observed(normal(mu, 1), 3.1))
        val b = mu given observed(normal(mu, 1), 2.9, 3.3, 3.1)
        assertEquals(E(b).value, E(a).value, 1e-9)
    }

    @Test
    fun aLogLikelihoodOfYourOwn() {
        // a flat prior scored by -x²/2 is a standard normal (cut at ±6, which moves nothing measurable)
        val x = uniform(-6, 6)
        val post = x given logLikelihood(-x.squared() / 2)
        check("E(scored)", E(post), 0.0, 0.01)
        check("Var(scored)", Var(post), 1.0, 0.02)
    }

    @Test
    fun poissonRateWithGammaPrior() {
        val rate = gamma(2, 1)
        val post = rate given observed(poisson(rate), 3, 5, 4)
        // Gamma(2 + 12, 1 + 3): mean 3.5, variance 14 / 16
        check("E(rate | counts)", E(post, n = 400_000), 3.5, 0.01)
        check("Var(rate | counts)", Var(post, n = 400_000), 14.0 / 16, 0.02)
    }

    @Test
    fun familiesSampleTheirMoments() {
        check("E(poisson 4)", E(poisson(4)), 4.0, 0.01)
        check("Var(poisson 4)", Var(poisson(4)), 4.0, 0.03)
        check("E(poisson 50)", E(poisson(50)), 50.0, 0.03)
        check("Var(poisson 50)", Var(poisson(50)), 50.0, 0.4)
        assertEquals(1.0, exactPmf(poisson(3))!!.values.sum(), 1e-12)
        // a rate too large to list exactly is "not known", never an empty pmf
        assertEquals(null, exactPmf(poisson(100_000)))
        check("E(gamma 3, 2)", E(gamma(3, 2)), 1.5, 0.005)
        check("Var(gamma 3, 2)", Var(gamma(3, 2)), 0.75, 0.01)
        check("E(gamma 0.5)", E(gamma(0.5)), 0.5, 0.005)
        check("E(beta 2, 5)", E(beta(2, 5)), 2.0 / 7, 0.002)
        // a random mean: normal(normal(0, 2), 1) has variance 4 + 1
        check("Var(hierarchical)", Var(normal(normal(0, 2), 1)), 5.0, 0.05)
        check("E(normal(mu: Dist))", E(normal(die(6), 1)), 3.5, 0.01)
        // constant parameters give the ordinary leaf, with its exact density
        assertNotNull(exactPdf(normal(Dist.dirac(1), 2)))
        assertEquals(lgamma(5.0), kotlin.math.ln(24.0), 1e-12)
        assertEquals(lgamma(0.5), kotlin.math.ln(kotlin.math.sqrt(Math.PI)), 1e-12)
    }

    @Test
    fun impossibleEvidenceIsAnErrorNotNaN() {
        val x = die(6)
        assertFailsWith<EvidenceImpossibleException> { E(x given (x gt 7)) }
        assertFailsWith<EvidenceImpossibleException> { exactPmf(x given (x gt 7)) }
        assertFailsWith<IllegalArgumentException> { E(x given (x - 4)) } // negative weights
        assertFailsWith<IllegalArgumentException> { observed(x + normal(), 1.0) }
    }

    @Test
    fun progressivePosterior() {
        val mu = normal(0, 10)
        val post = mu given observed(normal(mu, 1), 2.9, 3.3, 3.1)
        val p = Progressive(post)
        repeat(20) { p.draw(10_000) }
        assertTrue(p.weighted)
        assertEquals(200_000L, p.estimate.n)
        check("progressive posterior", p.estimate, 9.3 / 3.01, 0.02)
        assertTrue(p.estimate.ess < 50_000, "most prior draws are far from the data: ${p.estimate}")
        // the progressive sums agree with a one-shot weighted mean of the same kind
        val prior = Progressive(post.prior(), seed = 2L).draw(5000)
        val svg = distributionSvg(p, prior = prior)
        assertTrue("prior" in svg && "ess" in svg, svg.take(400))
        // a die given an impossible condition draws, and says nothing has been accepted yet
        val none = Progressive(die(6) given (die(6) gt 9)).draw(1000)
        assertTrue("no sample consistent" in distributionSvg(none))
    }

    @Test
    fun weightedMomentsMergeBlocks() {
        val ws = (normal(0, 10) given observed(normal(normal(0, 10), 1), 3.0)).weightedSamples(5000)
        // the model's mean here is a *different* variable, so the evidence is a constant-in-expectation weight
        val x = ws.values[0]; val lw = ws.logWeights!!
        val a = WeightedMoments().apply { for (i in x.indices) add(x[i], lw[i]) }
        val w = normalizeLogWeights(lw)
        val direct = weightedMean(x, w)
        assertEquals(direct.value, a.mean().value, 1e-9)
        assertEquals(direct.se, a.mean().se, 1e-9)
        assertEquals(direct.ess, a.mean().ess, 1e-6)
    }

    @Test
    fun posteriorsLowerToGlslThatCompiles() {
        val mu = normal(0, 10)
        val rate = gamma(2, 1)
        val x = die(6); val y = die(6)
        val graphs = mapOf(
            "normal-mean" to (mu given observed(normal(mu, 1), 2.9, 3.3, 3.1)),
            "poisson-rate" to (rate given observed(poisson(rate), 3, 5, 4)),
            "rejection" to ((x eq 6) given (x + y ge 10)),
            "families" to (poisson(4) + poisson(50) + gamma(0.5) + beta(2, 5) + normal(die(6), 1)),
            "mixture" to mixture(1 to normal(-2, 1), 3 to normal(4, 0.5)),
        )
        for ((name, d) in graphs) {
            val g = d.toGlsl()
            if (d.hasEvidence) assertTrue("inout float lw" in g && "lw += " in g, g)
            assertEquals(g.split("float nlRnd(").size - 1, 1, "one RNG definition in $name")
            for (src in listOf(
                if (d.hasEvidence) d.weightedMonteCarloComputeShader() else d.monteCarloComputeShader(),
                if (d.hasEvidence) d.rawWeightedSamplesComputeShader() else d.rawSamplesComputeShader(),
            )) {
                val spirv = ShaderSPIRVUtils.compileShader("noiselang-$name.comp", src, ShaderKind.COMPUTE_SHADER)
                assertTrue((spirv.bytecode()?.remaining() ?: 0) > 0, "$name: empty SPIR-V\n$src")
            }
        }
        // each helper is emitted once, however many nodes use it
        val g = (gamma(2, 1) + gamma(3, 1) + beta(2, 2)).toGlsl()
        assertEquals(1, Regex("float nlGamma\\(").findAll(g).count(), g)
    }
}
