package afield.stdlib

import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * That a learned interaction can reproduce a known one — the first question tier 2 has to answer,
 * and the only one with an exact oracle.
 *
 * Learning a flocking rule *from recorded motion* is the eventual goal and it has no ground truth:
 * you get a flock that looks like a flock, which this work has established several times over is
 * no evidence at all. Learning a rule we already have is a different and much sharper problem. The
 * target is computable to machine precision for any configuration, the error is a number, and if
 * the Deep Sets form cannot recover classical boids — which is *provably inside* the family, being
 * one particular choice of `psi` and `phi` — then nothing downstream is worth attempting.
 *
 * Every figure is quoted against two references, as everywhere else in this pilot:
 *
 * - a **floor** of zero, since the target is exact;
 * - a **ceiling**, the error of predicting the batch's mean acceleration, which is what a model
 *   that learned nothing but the average scores.
 */
class DeepSetsInteractionTest {

    // The rule being imitated, matching ShaderBoids' defaults exactly.
    private val radius = 2f / 24f
    private val separation = 0.02f
    private val alignment = 1.2f
    private val cohesion = 0.8f
    private val minSeparation = radius * 0.05f
    private val velocityScale = 0.4f
    private val accelScale = 20f
    private val sumScale = 8f

    /** Classical boids for one neighbourhood, with the centre at the origin. */
    private fun boids(velocity: FloatArray, neighbours: List<FloatArray>): FloatArray {
        var sepX = 0f; var sepY = 0f
        var dvX = 0f; var dvY = 0f
        var dX = 0f; var dY = 0f
        for (n in neighbours) {
            val distance = hypot(n[0].toDouble(), n[1].toDouble()).toFloat()
            val soft = maxOf(distance, minSeparation)
            sepX -= n[0] / (soft * soft); sepY -= n[1] / (soft * soft)
            dvX += n[2]; dvY += n[3]
            dX += n[0]; dY += n[1]
        }
        if (neighbours.isEmpty()) return floatArrayOf(0f, 0f)
        val n = neighbours.size.toFloat()
        return floatArrayOf(
            separation * sepX + alignment * (dvX / n) + cohesion * (dX / n),
            separation * sepY + alignment * (dvY / n) + cohesion * (dY / n),
        )
    }

    /** A neighbourhood of between one and [maxNeighbours] others, uniformly within the radius. */
    private fun example(random: Random, maxNeighbours: Int = 14): InteractionExample {
        val velocity = floatArrayOf(
            (random.nextDouble() * 2 - 1).toFloat() * velocityScale,
            (random.nextDouble() * 2 - 1).toFloat() * velocityScale,
        )
        val count = 1 + random.nextInt(maxNeighbours)
        val neighbours = List(count) {
            // sqrt for a uniform disc rather than a centre-heavy one; the separation term is most
            // sensitive exactly where a naive polar draw over-samples.
            val r = radius * sqrt(random.nextDouble()).toFloat()
            val a = random.nextDouble() * 2 * Math.PI
            floatArrayOf(
                (r * kotlin.math.cos(a)).toFloat(),
                (r * kotlin.math.sin(a)).toFloat(),
                (random.nextDouble() * 2 - 1).toFloat() * velocityScale,
                (random.nextDouble() * 2 - 1).toFloat() * velocityScale,
            )
        }
        return InteractionExample(velocity, neighbours, boids(velocity, neighbours))
    }

    private fun train(steps: Int, sum: Float, seed: Long = 5L): DeepSetsWeights {
        val random = Random(seed)
        return trainDeepSets(
            radius = radius,
            velocityScale = velocityScale,
            accelScale = accelScale,
            sumScale = sum,
            minSeparation = minSeparation,
            options = DeepSetsOptions(steps = steps, batch = 16, seed = seed),
        ) { List(16) { example(random) } }
    }

    /** Held-out relative error, and the "predicted the mean" ceiling beside it. */
    private fun score(weights: DeepSetsWeights, samples: Int = 400): Pair<Double, Double> {
        val random = Random(999)
        val cases = List(samples) { example(random) }
        var squaredError = 0.0
        var squaredTarget = 0.0
        var meanX = 0.0
        var meanY = 0.0
        for (c in cases) { meanX += c.target[0]; meanY += c.target[1] }
        meanX /= samples; meanY /= samples
        var squaredMean = 0.0
        for (c in cases) {
            val (ax, ay) = weights.evaluate(c.velocity[0], c.velocity[1], c.neighbours)
            squaredError += (ax - c.target[0]).toDouble().let { it * it } +
                (ay - c.target[1]).toDouble().let { it * it }
            squaredTarget += c.target[0].toDouble().let { it * it } + c.target[1].toDouble().let { it * it }
            squaredMean += (meanX - c.target[0]).let { it * it } + (meanY - c.target[1]).let { it * it }
        }
        return sqrt(squaredError / squaredTarget) to sqrt(squaredMean / squaredTarget)
    }

    @Test
    fun aDeepSetsInteractionLearnsClassicalBoidsFromTheRuleItself() {
        val weights = train(steps = 4000, sum = sumScale)
        val (error, ceiling) = score(weights)
        println(
            "learned boids: relative error ${"%.4f".format(error)} " +
                "(predicting the mean scores ${"%.4f".format(ceiling)}), " +
                "${weights.parameterCount} parameters"
        )
        // Measured 0.106 against a ceiling of 0.999. Not exact, and it should not be read as
        // exact: a relative error of a tenth on a force is a flock that behaves like boids
        // rather than a flock that *is* boids. The value of the number is that it is a number.
        assertTrue(error < 0.18, "relative error $error should be well under the $ceiling ceiling")
        assertTrue(error < ceiling * 0.25, "error $error is not far enough below the ceiling $ceiling")
    }

    @Test
    fun theSummedBranchIsWhatMakesSeparationRepresentable() {
        // The design claim, as an ablation. Separation is a sum over neighbours and alignment and
        // cohesion are means, so a mean-only aggregator is missing a term it cannot manufacture
        // however wide it is. Disabling the branch by dividing it into irrelevance needs no second
        // code path, which is the point -- the two models are otherwise identical.
        val withSum = train(steps = 2500, sum = sumScale)
        val meanOnly = train(steps = 2500, sum = 1e9f)
        val (withSumError, ceiling) = score(withSum)
        val (meanOnlyError, _) = score(meanOnly)
        println(
            "sum+mean ${"%.4f".format(withSumError)} vs mean-only ${"%.4f".format(meanOnlyError)} " +
                "(ceiling ${"%.4f".format(ceiling)})"
        )
        // Measured 0.11 against 0.49 -- the summed branch is worth a factor of four, which is
        // separation being representable at all rather than a marginal gain.
        assertTrue(
            withSumError < meanOnlyError * 0.5,
            "the summed branch should earn its inputs: $withSumError vs $meanOnlyError"
        )
    }

    @Test
    fun oneNeighbourAndManyNeighboursBothWork() {
        // The property the Deep Sets form exists for: one set of weights, any neighbour count.
        // A model that had quietly learned "a fixed number of neighbours" would fail at the edges.
        val weights = train(steps = 3000, sum = sumScale)
        for (n in listOf(1, 3, 30)) {
            val random = Random(n.toLong() * 17)
            val cases = List(150) { example(random, maxNeighbours = n).let {
                if (n == 1) InteractionExample(it.velocity, it.neighbours.take(1), boids(it.velocity, it.neighbours.take(1))) else it
            } }
            var squaredError = 0.0
            var squaredTarget = 0.0
            for (c in cases) {
                val (ax, ay) = weights.evaluate(c.velocity[0], c.velocity[1], c.neighbours)
                squaredError += (ax - c.target[0]).toDouble().let { it * it } +
                    (ay - c.target[1]).toDouble().let { it * it }
                squaredTarget += c.target[0].toDouble().let { it * it } + c.target[1].toDouble().let { it * it }
            }
            val error = sqrt(squaredError / squaredTarget)
            println("  $n neighbours: relative error ${"%.4f".format(error)}")
            // 30 is beyond anything it trained on (up to 14), so it is extrapolation and allowed
            // to be worse -- but not unrecognisable.
            assertTrue(error < 0.5, "$n neighbours gave $error")
        }
    }

    @Test
    fun theWeightsComeOutInTheLayoutTheShaderReads() {
        // Both halves are plain MlpWeights, so step one's GLSL emitter and its parity smoke apply
        // unchanged. This is the only structural coupling worth asserting.
        val weights = train(steps = 50, sum = sumScale)
        assertTrue(weights.psi.inputs == DeepSetsWeights.PSI_INPUTS && weights.psi.outputs == weights.messageWidth)
        assertTrue(weights.phi.inputs == 2 + 2 * weights.messageWidth && weights.phi.outputs == 2)
        assertTrue(weights.psi.activation == MlpActivation.TANH)
        val source = weights.psi.glsl("psi")
        assertTrue("void psi(psiWeights nw, float x[8], out float y[${weights.messageWidth}])" in source, source)
    }
}
