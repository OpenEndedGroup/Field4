package afield.math.distribution

import afield.graphics.shader.ShaderKind
import afield.graphics.shader.ShaderSPIRVUtils
import java.util.SplittableRandom
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [Posterior], sequential Monte Carlo, against what is known exactly: the conjugate normal mean
 * and coin, the marginal likelihood of normal readings, and the Kalman filter for a random walk
 * seen through noise. Then the bridges back to distributions, and a tempo followed from onsets.
 */
class SequentialTest {

    private fun near(label: String, e: Estimate, exact: Double, tol: Double) =
        assertTrue(abs(e.value - exact) <= tol, "$label: $e vs $exact (tolerance $tol)")

    @Test
    fun readingsOneAtATimeGiveTheConjugatePosterior() {
        val mu = normal(0, 10)
        val post = Posterior(mu, particles = 5000, seed = 3).apply { rejuvenateAfterResample = 0.9 }
        val data = doubleArrayOf(2.9, 3.3, 3.1, 2.7, 3.4)
        for (y in data) post.observe(normal(mu, 1), y)
        val precision = 1 / 100.0 + data.size
        near("E(mu)", post.E(mu), data.sum() / precision, 0.03)
        near("Var(mu)", post.Var(mu), 1 / precision, 0.03)
        assertEquals(data.size, post.observations)
        assertTrue(post.resamples >= 1, "a wide prior and five readings must collapse the weights at least once")
    }

    @Test
    fun aCoinFlipByFlipIsBeta() {
        val p = uniform(0, 1)
        val post = Posterior(p, particles = 5000, seed = 5).apply { rejuvenateAfterResample = 0.95 }
        for (f in listOf(1, 1, 0, 1, 1, 1, 0, 1, 1, 0)) post.observe(bernoulli(p), f)
        near("E(p)", post.E(p), 8.0 / 12, 0.01)
        near("Var(p)", post.Var(p), 32.0 / (144 * 13), 0.003)
        // a condition works as an observation too
        val q = Posterior(p, particles = 4000, seed = 6).observe(p gt 0.5)
        near("E(p | p > 0.5)", q.E(p), 0.75, 0.01)
    }

    @Test
    fun theLogEvidenceIsTheMarginalLikelihood() {
        // y_i = mu + e_i, mu ~ N(0, 10), e ~ N(0, 1): y ~ N(0, 100·11ᵀ + I), whose density is closed form
        val data = doubleArrayOf(2.9, 3.3, 3.1)
        val n = data.size
        val s = data.sum(); val ss = data.sumOf { it * it }
        val tau2 = 100.0
        // det(I + tau2 11ᵀ) = 1 + n tau2; inverse = I - tau2/(1 + n tau2) 11ᵀ
        val quad = ss - tau2 / (1 + n * tau2) * s * s
        val exact = -0.5 * (n * ln(2 * PI) + ln(1 + n * tau2) + quad)
        val mu = normal(0, 10)
        val post = Posterior(mu, particles = 20_000, seed = 7)
        for (y in data) post.observe(normal(mu, 1), y)
        assertEquals(exact, post.logEvidence, 0.1, "log p(data)")
        // a model that gets the noise wrong explains the data worse
        val wrong = Posterior(normal(0, 10).let { m -> m }, particles = 20_000, seed = 7)
        val m2 = wrong.state[0]
        for (y in data) wrong.observe(normal(m2, 0.05), y)
        assertTrue(post.logEvidence > wrong.logEvidence, "${post.logEvidence} should beat ${wrong.logEvidence}")
    }

    @Test
    fun aRandomWalkThroughNoiseMatchesTheKalmanFilter() {
        val q = 0.1; val r = 0.5
        val rng = SplittableRandom(11)
        var truth = 0.0
        val ys = DoubleArray(40) { truth += sqrt(q) * stdNormal(rng); truth + sqrt(r) * stdNormal(rng) }
        // Kalman, exactly
        var m = 0.0; var p = 1.0
        val x = normal(0, 1)
        val post = Posterior(x, particles = 5000, seed = 12)
        for (y in ys) {
            p += q
            val k = p / (p + r); m += k * (y - m); p *= (1 - k)
            post.evolve(x to x + normal(0, sqrt(q)))
            post.observe(normal(x, sqrt(r)), y)
        }
        near("E(x) against Kalman", post.E(x), m, 0.05)
        near("Var(x) against Kalman", post.Var(x), p, 0.25 * p)
    }

    @Test
    fun impossibleObservationsAreAnError() {
        val p = uniform(0, 1)
        val post = Posterior(p, particles = 100)
        assertFailsWith<EvidenceImpossibleException> { post.observe(p gt 2) }
        assertFailsWith<IllegalArgumentException> { post.evolve(normal() to normal()) }
    }

    @Test
    fun thePosteriorAsADistribution() {
        val mu = normal(0, 10)
        val post = Posterior(mu, particles = 4000, seed = 2)
        val live = post.live(mu * 2)
        val before = E(live, n = 20_000).value
        for (y in doubleArrayOf(2.9, 3.3, 3.1)) post.observe(normal(mu, 1), y)
        val snapshot = post.dist(mu)
        near("E(snapshot)", E(snapshot, n = 50_000), post.E(mu).value, 0.05)
        near("E(live) follows", E(live, n = 50_000), 2 * post.E(mu).value, 0.1)
        assertTrue(abs(before) < 1.0, "the live view started from the prior: $before")
        // the snapshot lowers to GLSL: a table and a binary search
        val src = "#version 450\nlayout(local_size_x = 64) in;\nlayout(std430, binding = 0) buffer B { float v[]; } b;\n" +
            (snapshot * 2 + normal(0, 0.1)).toGlslInclude("tempo").source +
            "void main() { uint s = nlSeed(gl_GlobalInvocationID, 0u); b.v[gl_GlobalInvocationID.x] = tempo(s); }\n"
        val spirv = ShaderSPIRVUtils.compileShader("noiselang-empirical.comp", src, ShaderKind.COMPUTE_SHADER)
        assertTrue((spirv.bytecode()?.remaining() ?: 0) > 0)
        // an empirical distribution of a few values has their pmf
        val e = empiricalDist(doubleArrayOf(1.0, 2.0, 2.0), doubleArrayOf(1.0, 1.0, 2.0))
        assertEquals(0.75, exactPmf(e)!![2.0]!!, 1e-12)
    }

    @Test
    fun aTempoFollowedFromOnsets() {
        // 40 beats from 90 to 130 bpm, each ±10 ms
        val rng = SplittableRandom(4)
        val onsets = ArrayList<Double>()
        var at = 0.5
        for (k in 0 until 40) { onsets += at + 0.01 * stdNormal(rng); at += 60.0 / (90.0 + k) }
        val period = uniform(0.3, 1.0)
        val beat = known(onsets[0])
        val post = Posterior(period, beat, particles = 3000, seed = 9)
        assertFailsWith<IllegalArgumentException> { Posterior(period, Dist.dirac(1)) }
        val errors = ArrayList<Double>()
        for (t in onsets.drop(1)) {
            val predicted = post.E(beat + period).value
            errors += abs(predicted - t)
            post.evolve(beat to beat + period + normal(0, 0.004), period to period * normal(0, 0.03).exp())
            post.observe(normal(beat, 0.015), t)
        }
        val bpm = post.E(60 / period).value
        assertEquals(129.0, bpm, 6.0, "the tempo at the end")
        val late = errors.drop(10).sorted()
        assertTrue(late[late.size / 2] < 0.03, "median prediction error after ten beats: ${late[late.size / 2]} s")
    }

}
