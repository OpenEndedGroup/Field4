package afield.stdlib

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * That [EquivariantWeights] is equivariant *by construction* rather than approximately — and that
 * buying the symmetry costs nothing in fidelity.
 *
 * The plain [DeepSetsWeights] form learns a map from Cartesian components to Cartesian components,
 * and nothing makes that map commute with rotation. The consequence was visible on screen before
 * it was measured: in the `learned_boids` scene the learned flock drifted steadily out of its
 * containment while the written flock, under the same containment, stayed put. Measured on the
 * plain form, a perfectly symmetric ring of neighbours — true force exactly zero by symmetry —
 * produces 0.14 at twelve neighbours and 0.50 at forty-eight, and a ninety-degree rotation changes
 * the answer by 18% of a typical force.
 *
 * Both of those are *identically* zero here, up to fp32, and the two tests that assert it are the
 * point of the class. The third test is the one that would have stopped it being worth shipping:
 * an equivariant model that fitted the rule worse would be a bad trade.
 */
class EquivariantInteractionTest {

    private val radius = 2f / 64f
    private val minSep = radius * 0.05f
    private val vScale = 0.4f
    private val accelScale = 20f
    private val sumScale = 8f
    private val wSep = 0.02f
    private val wAli = 3.0f
    private val wCoh = 200f

    /** The short-range-separation rule the `learned_boids` scene actually runs. */
    private fun rule(v: FloatArray, ns: List<FloatArray>): FloatArray {
        var sx = 0f; var sy = 0f; var dvx = 0f; var dvy = 0f; var dx = 0f; var dy = 0f
        for (q in ns) {
            val d = hypot(q[0].toDouble(), q[1].toDouble()).toFloat()
            if (d <= radius * 0.25f) {
                val soft = maxOf(d, minSep)
                sx -= q[0] / (soft * soft); sy -= q[1] / (soft * soft)
            }
            dvx += q[2]; dvy += q[3]; dx += q[0]; dy += q[1]
        }
        val c = ns.size.toFloat()
        return floatArrayOf(wSep * sx + wAli * dvx / c + wCoh * dx / c, wSep * sy + wAli * dvy / c + wCoh * dy / c)
    }

    private fun sample(r: Random): InteractionExample {
        val v = floatArrayOf((r.nextDouble() * 2 - 1).toFloat() * vScale, (r.nextDouble() * 2 - 1).toFloat() * vScale)
        val ns = List(1 + r.nextInt(70)) {
            val rr = radius * sqrt(r.nextDouble()).toFloat()
            val a = r.nextDouble() * 2 * Math.PI
            floatArrayOf(
                (rr * cos(a)).toFloat(), (rr * sin(a)).toFloat(),
                (r.nextDouble() * 2 - 1).toFloat() * vScale, (r.nextDouble() * 2 - 1).toFloat() * vScale,
            )
        }
        return InteractionExample(v, ns, rule(v, ns))
    }

    private fun trainedEquivariant(steps: Int = 4000): EquivariantWeights {
        val r = Random(5)
        return trainEquivariant(
            radius, vScale, accelScale, sumScale, minSep,
            DeepSetsOptions(steps = steps, batch = 16, seed = 5, messageWidth = 4, psiHidden = intArrayOf(8, 8)),
        ) { List(16) { sample(r) } }
    }

    @Test
    fun aSymmetricNeighbourhoodProducesExactlyNoForce() {
        // The claim in its sharpest form. Neighbours evenly spaced on a ring with no relative
        // motion: the direction vectors cancel before any network sees them, so the answer is zero
        // by construction rather than by fitting. The plain form scores 0.14 and 0.50 here, and it
        // is that residual — fixed in direction, growing with crowding — that drifts a flock.
        val weights = trainedEquivariant(steps = 600)
        for (n in listOf(3, 12, 48)) {
            val ring = (0 until n).map {
                val a = 2.0 * Math.PI * it / n
                floatArrayOf((radius * 0.6f * cos(a)).toFloat(), (radius * 0.6f * sin(a)).toFloat(), 0f, 0f)
            }
            val (ax, ay) = weights.evaluate(0f, 0f, ring)
            val magnitude = hypot(ax.toDouble(), ay.toDouble())
            println("symmetric ring n=$n: |a| $magnitude")
            assertTrue(magnitude < 1e-4, "a symmetric ring of $n should give no force, got $magnitude")
        }
    }

    @Test
    fun rotatingTheConfigurationRotatesTheAnswer() {
        // a(R x) = R a(x), for arbitrary angles and arbitrary configurations. The plain form is 18%
        // out on a single ninety-degree turn.
        val weights = trainedEquivariant(steps = 600)
        val random = Random(3)
        var worst = 0.0
        repeat(40) {
            val e = sample(random)
            val (ax, ay) = weights.evaluate(e.velocity[0], e.velocity[1], e.neighbours)
            val scale = maxOf(hypot(ax.toDouble(), ay.toDouble()), 1e-6)
            for (turn in listOf(0.3, 1.0, 2.2, 4.7)) {
                val c = cos(turn).toFloat()
                val s = sin(turn).toFloat()
                fun rot(x: Float, y: Float) = floatArrayOf(c * x - s * y, s * x + c * y)
                val v = rot(e.velocity[0], e.velocity[1])
                val ns = e.neighbours.map { q ->
                    val p = rot(q[0], q[1]); val dv = rot(q[2], q[3])
                    floatArrayOf(p[0], p[1], dv[0], dv[1])
                }
                val (bx, by) = weights.evaluate(v[0], v[1], ns)
                val expected = rot(ax, ay)
                worst = maxOf(worst, hypot((bx - expected[0]).toDouble(), (by - expected[1]).toDouble()) / scale)
            }
        }
        println("worst relative equivariance error over 160 rotations: $worst")
        // fp32 only: the invariants are recomputed from rotated coordinates, so the features differ by
        // rounding and nothing more.
        assertTrue(worst < 1e-3, "equivariance should hold to fp32, worst was $worst")
    }

    @Test
    fun itFitsTheRuleAtLeastAsWellAsTheUnconstrainedForm() {
        // The trade that has to not exist. Constraining a model usually costs accuracy; here the
        // constraint removes a class of error the unconstrained model was spending capacity on, so
        // it should be a wash or better at equal budget and equal width.
        val testRandom = Random(999)
        val test = List(300) { sample(testRandom) }
        var squaredTarget = 0.0
        var meanX = 0.0
        var meanY = 0.0
        for (c in test) { meanX += c.target[0]; meanY += c.target[1] }
        meanX /= test.size; meanY /= test.size
        var squaredCeiling = 0.0
        for (c in test) {
            squaredTarget += c.target[0].toDouble().let { it * it } + c.target[1].toDouble().let { it * it }
            squaredCeiling += (meanX - c.target[0]).let { it * it } + (meanY - c.target[1]).let { it * it }
        }
        val ceiling = sqrt(squaredCeiling / squaredTarget)

        val plainRandom = Random(5)
        val plain = trainDeepSets(
            radius, vScale, accelScale, sumScale, minSep,
            DeepSetsOptions(steps = 4000, batch = 16, seed = 5, messageWidth = 4, psiHidden = intArrayOf(8, 8)),
        ) { List(16) { sample(plainRandom) } }

        fun score(evaluate: (FloatArray, List<FloatArray>) -> Pair<Float, Float>): Double {
            var se = 0.0
            for (c in test) {
                val (ax, ay) = evaluate(c.velocity, c.neighbours)
                se += (ax - c.target[0]).toDouble().let { it * it } + (ay - c.target[1]).toDouble().let { it * it }
            }
            return sqrt(se / squaredTarget)
        }

        val equivariant = trainedEquivariant()
        val plainError = score { v, ns -> plain.evaluate(v[0], v[1], ns) }
        val equivariantError = score { v, ns -> equivariant.evaluate(v[0], v[1], ns) }
        println(
            "relative error: unconstrained ${"%.3f".format(plainError)} " +
                "(${plain.parameterCount} params), equivariant ${"%.3f".format(equivariantError)} " +
                "(${equivariant.parameterCount} params), ceiling ${"%.3f".format(ceiling)}"
        )
        assertTrue(equivariantError < ceiling * 0.6, "equivariant error $equivariantError vs ceiling $ceiling")
        assertTrue(
            equivariantError < plainError * 1.15,
            "the symmetry should not cost fidelity: $equivariantError vs $plainError"
        )
    }

    /**
     * The reproducer for the "cannot predict the equilibrium residual" claim in
     * `dev_docs/NEURAL_PARTICLES_MEASUREMENTS.md`.
     *
     * On the neighbourhoods it was trained on — uniform discs — the rule fits well. Relax a flock
     * under the true rule until it stops rearranging, and the regime changes completely:
     * separation and cohesion become large and nearly cancelling, and what survives is a small
     * difference of big terms. Scored *there*, the model is no better than predicting exactly
     * zero, which is the honest reading of "it learned to be small rather than learning the
     * residual".
     *
     * The assertion is deliberately loose in the direction of the claim, not against it. The ratio
     * moves with the seed and with how the separation window is specified, so a tight bound here
     * would be a flake dressed as a finding; what is stable, and what the claim rests on, is that
     * the model does not *beat* the zero baseline by a meaningful margin in this regime.
     */
    @Test
    fun inTheEquilibriumRegimeTheModelIsNoBetterThanPredictingZero() {
        val learned = trainedEquivariant()

        // A small flock, relaxed under the true rule until it stops rearranging. Positions only:
        // the claim is about the spatial configuration a settled flock produces.
        val r = Random(11)
        val n = 220
        val px = FloatArray(n) { (r.nextDouble() * 2 - 1).toFloat() * radius * 3 }
        val py = FloatArray(n) { (r.nextDouble() * 2 - 1).toFloat() * radius * 3 }
        val vx = FloatArray(n) { (r.nextDouble() * 2 - 1).toFloat() * vScale * 0.1f }
        val vy = FloatArray(n) { (r.nextDouble() * 2 - 1).toFloat() * vScale * 0.1f }

        fun neighboursOf(i: Int): List<FloatArray> = (0 until n).mapNotNull { j ->
            if (j == i) return@mapNotNull null
            val dx = px[j] - px[i]
            val dy = py[j] - py[i]
            if (hypot(dx.toDouble(), dy.toDouble()) > radius) null
            else floatArrayOf(dx, dy, vx[j] - vx[i], vy[j] - vy[i])
        }

        val dt = 1f / 240f
        repeat(600) {
            val ax = FloatArray(n)
            val ay = FloatArray(n)
            for (i in 0 until n) {
                val ns = neighboursOf(i)
                if (ns.isEmpty()) continue
                val a = rule(floatArrayOf(vx[i], vy[i]), ns)
                ax[i] = a[0]; ay[i] = a[1]
            }
            for (i in 0 until n) {
                vx[i] = (vx[i] + ax[i] * dt) * 0.98f
                vy[i] = (vy[i] + ay[i] * dt) * 0.98f
                px[i] += vx[i] * dt
                py[i] += vy[i] * dt
            }
        }

        // Score on the settled configuration: model error and zero-baseline error, same denominator.
        var squaredTarget = 0.0
        var squaredModel = 0.0
        var sampled = 0
        for (i in 0 until n) {
            val ns = neighboursOf(i)
            if (ns.size < 4) continue
            sampled++
            val v = floatArrayOf(vx[i], vy[i])
            val t = rule(v, ns)
            val (ax, ay) = learned.evaluate(v[0], v[1], ns)
            squaredTarget += t[0].toDouble() * t[0] + t[1].toDouble() * t[1]
            squaredModel += (ax - t[0]).toDouble().let { it * it } + (ay - t[1]).toDouble().let { it * it }
        }

        // Predicting zero scores exactly 1.0 by construction — the target's own magnitude.
        val modelError = sqrt(squaredModel / squaredTarget)
        println(
            "equilibrium regime: $sampled neighbourhoods, model ${"%.3f".format(modelError)} " +
                "relative, predicting zero 1.000"
        )

        assertTrue(sampled > 40, "the relaxed flock gave only $sampled usable neighbourhoods")
        assertTrue(
            modelError > 0.5,
            "the page claims the model does not learn the equilibrium residual; if this is now " +
                "well below the zero baseline of 1.0 (got $modelError) then it does, and " +
                "dev_docs/NEURAL_PARTICLES_MEASUREMENTS.md needs rewriting rather than this test relaxing"
        )
    }
}
