package afield.stdlib

import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The regime the learned interaction cannot do — measured, rather than asserted from memory.
 *
 * [DeepSetsInteractionTest] trains and scores on neighbourhoods drawn from uniform discs, and
 * there the Deep Sets form recovers boids to about a tenth relative. A flock that has been left
 * to run is a different question and the honest one, because it is the state the rule actually
 * runs in: cohesion has pulled a clump together until separation balances it, the two largest
 * terms very nearly cancel, and the acceleration that survives is a small difference of big
 * numbers.
 *
 * That difference is what this file scores, against a baseline chosen so the outcome cannot be
 * read as anything else: **predicting exactly zero**, which by construction scores a relative
 * error of exactly 1.000. A model that has learned the residual beats it comfortably. A model
 * that has only learned that the answer is small does not, and that is what comes out.
 *
 * The flock is the rule `afield.four.physics.ShaderBoids` implements with
 * `separationRadiusFraction = 0.25` — separation acting over a quarter of the interaction radius
 * while alignment and cohesion act over all of it, which is the setting that lets a flock clump
 * at all rather than settling into one uniform disc. Note that this state is *steady*, not
 * *still*: the clump churns, so the neighbour count and the residual's size both fluctuate, and
 * three seeds are run to give the spread rather than one number that looks more precise than it
 * is.
 *
 * This is the committed reproducer for the "What does not work yet" figures in
 * `public/docs/neural_particles.md`. It replaces a scratch test that was never committed.
 */
class EquilibriumInteractionTest {

    private val count = 700
    private val radius = 2f / 64f
    private val separationRadius = radius * 0.25f
    private val separation = 0.02f
    private val alignment = 3.0f
    private val cohesion = 200f
    private val minSeparation = radius * 0.05f
    private val maxSpeed = 0.35f
    private val dt = 1f / 60f

    /** Positions and velocities, interleaved x,y — the flock, on the CPU. */
    private class Flock(val position: FloatArray, val velocity: FloatArray, val count: Int)

    private fun seedFlock(random: Random): Flock {
        val p = FloatArray(count * 2)
        val v = FloatArray(count * 2)
        for (i in 0 until count) {
            p[i * 2] = (random.nextDouble() * 2 - 1).toFloat() * 0.5f
            p[i * 2 + 1] = (random.nextDouble() * 2 - 1).toFloat() * 0.5f
            v[i * 2] = (random.nextDouble() * 2 - 1).toFloat() * 0.1f
            v[i * 2 + 1] = (random.nextDouble() * 2 - 1).toFloat() * 0.1f
        }
        return Flock(p, v, count)
    }

    /**
     * The rule, for one particle, over every other — the same law as
     * `ShaderBoids.referenceAcceleration`, written out here because that one needs a live GPU
     * renderer to exist and this test needs none.
     */
    private fun acceleration(flock: Flock, i: Int): FloatArray {
        val px = flock.position[i * 2]
        val py = flock.position[i * 2 + 1]
        val vx = flock.velocity[i * 2]
        val vy = flock.velocity[i * 2 + 1]
        var sepX = 0f; var sepY = 0f
        var velX = 0f; var velY = 0f
        var posX = 0f; var posY = 0f
        var n = 0f
        for (j in 0 until flock.count) {
            if (j == i) continue
            val dx = flock.position[j * 2] - px
            val dy = flock.position[j * 2 + 1] - py
            val distance = sqrt(dx * dx + dy * dy)
            if (distance > radius || distance <= 0f) continue
            if (distance <= separationRadius) {
                val soft = maxOf(distance, minSeparation)
                sepX -= dx / (soft * soft); sepY -= dy / (soft * soft)
            }
            velX += flock.velocity[j * 2]; velY += flock.velocity[j * 2 + 1]
            posX += flock.position[j * 2]; posY += flock.position[j * 2 + 1]
            n += 1f
        }
        if (n == 0f) return floatArrayOf(0f, 0f)
        return floatArrayOf(
            separation * sepX + alignment * (velX / n - vx) + cohesion * (posX / n - px),
            separation * sepY + alignment * (velY / n - vy) + cohesion * (posY / n - py),
        )
    }

    /** Step the whole flock once, accelerations first so the step is a function of one state. */
    private fun step(flock: Flock) {
        val accel = FloatArray(flock.count * 2)
        for (i in 0 until flock.count) {
            val a = acceleration(flock, i)
            accel[i * 2] = a[0]; accel[i * 2 + 1] = a[1]
        }
        for (i in 0 until flock.count) {
            var vx = flock.velocity[i * 2] + accel[i * 2] * dt
            var vy = flock.velocity[i * 2 + 1] + accel[i * 2 + 1] * dt
            val speed = sqrt(vx * vx + vy * vy)
            if (speed > maxSpeed) { vx *= maxSpeed / speed; vy *= maxSpeed / speed }
            flock.velocity[i * 2] = vx; flock.velocity[i * 2 + 1] = vy
            flock.position[i * 2] += vx * dt; flock.position[i * 2 + 1] += vy * dt
        }
    }

    /** One particle's neighbourhood, in the `(dx, dy, dvx, dvy)` relative form `psi` reads. */
    private fun neighbourhood(flock: Flock, i: Int): InteractionExample {
        val px = flock.position[i * 2]
        val py = flock.position[i * 2 + 1]
        val vx = flock.velocity[i * 2]
        val vy = flock.velocity[i * 2 + 1]
        val neighbours = ArrayList<FloatArray>()
        for (j in 0 until flock.count) {
            if (j == i) continue
            val dx = flock.position[j * 2] - px
            val dy = flock.position[j * 2 + 1] - py
            val distance = sqrt(dx * dx + dy * dy)
            if (distance > radius || distance <= 0f) continue
            neighbours += floatArrayOf(dx, dy, flock.velocity[j * 2] - vx, flock.velocity[j * 2 + 1] - vy)
        }
        return InteractionExample(floatArrayOf(vx, vy), neighbours, acceleration(flock, i))
    }

    /** Root-mean-square target magnitude — the natural scale for a batch of accelerations. */
    private fun rmsMagnitude(cases: List<InteractionExample>): Float {
        var squared = 0.0
        for (c in cases) squared += c.target[0].toDouble().let { it * it } + c.target[1].toDouble().let { it * it }
        return sqrt(squared / cases.size).toFloat()
    }

    /**
     * Relative error of [weights] on [cases]: `sqrt(sum |a - a'|^2 / sum |a|^2)`. Predicting zero
     * gives exactly 1 by construction, so any figure near 1 is a model that has learned nothing
     * the baseline did not already know.
     */
    private fun relativeError(weights: DeepSetsWeights, cases: List<InteractionExample>): Double {
        var squaredError = 0.0
        var squaredTarget = 0.0
        for (c in cases) {
            val (ax, ay) = weights.evaluate(c.velocity[0], c.velocity[1], c.neighbours)
            squaredError += (ax - c.target[0]).toDouble().let { it * it } +
                (ay - c.target[1]).toDouble().let { it * it }
            squaredTarget += c.target[0].toDouble().let { it * it } + c.target[1].toDouble().let { it * it }
        }
        return sqrt(squaredError / squaredTarget)
    }

    /** Everything one seed produces, so the three can be summarised together. */
    private class Measured(
        val meanNeighbours: Double,
        val trueMagnitude: Float,
        val learnedMagnitude: Double,
        val error: Double,
    )

    private fun measure(seed: Long): Measured {
        val flock = seedFlock(Random(seed))
        // Long enough that the clump has formed and the flock is in its steady state rather than
        // in the opening collapse; the claim is about the settled regime, not about a transient.
        repeat(1500) { step(flock) }

        val order = (0 until count).shuffled(Random(seed * 7 + 3))
        val train = order.take(350).map { neighbourhood(flock, it) }
        val held = order.drop(350).map { neighbourhood(flock, it) }

        // Scale the output by the data rather than by a constant, so "the targets were the wrong
        // size for the network to reach" is not available as an explanation of the result.
        val accelScale = maxOf(rmsMagnitude(train), 1e-6f)
        val batchRandom = Random(seed * 31 + 11)
        val weights = trainDeepSets(
            radius = radius,
            velocityScale = maxSpeed,
            accelScale = accelScale,
            sumScale = 8f,
            minSeparation = minSeparation,
            options = DeepSetsOptions(steps = 1200, batch = 8, seed = seed),
        ) { List(8) { train[batchRandom.nextInt(train.size)] } }

        var learnedSquared = 0.0
        for (c in held) {
            val (ax, ay) = weights.evaluate(c.velocity[0], c.velocity[1], c.neighbours)
            learnedSquared += ax.toDouble() * ax + ay.toDouble() * ay
        }
        return Measured(
            meanNeighbours = held.sumOf { it.neighbours.size.toDouble() } / held.size,
            trueMagnitude = rmsMagnitude(held),
            learnedMagnitude = sqrt(learnedSquared / held.size),
            error = relativeError(weights, held),
        )
    }

    @Test
    fun theLearnedRuleCannotPredictTheEquilibriumResidual() {
        val measured = listOf(17L, 23L, 41L).map { seed ->
            val m = measure(seed)
            println(
                "seed $seed: mean ${"%.1f".format(m.meanNeighbours)} neighbours, " +
                    "true |a| ${"%.4f".format(m.trueMagnitude)}, " +
                    "learned |a| ${"%.4f".format(m.learnedMagnitude)}, " +
                    "relative ${"%.3f".format(m.error)}"
            )
            m
        }
        val worst = measured.maxOf { it.error }
        val best = measured.minOf { it.error }
        println(
            "settled flock: relative ${"%.2f".format(best)} to ${"%.2f".format(worst)} " +
                "across ${measured.size} seeds, where predicting exactly zero scores 1.000"
        )

        for (m in measured) {
            // The headline, stated as what was measured rather than what was wanted. The learned
            // rule lands between the two bounds below: not *at* the predict-zero baseline,
            // because it does pick up something, and nowhere near the tenth-relative it reaches
            // on the uniform-disc neighbourhoods DeepSetsInteractionTest uses.
            //
            // The lower bound is deliberately set where it will fail if that gap is ever closed,
            // so the numbers quoted in public/docs/neural_particles.md have to be revisited
            // rather than quietly drifting -- the same convention
            // MixtureSourceTest.aMixtureSourceMakesAFiveComponentTargetReachableWhenMoreTrainingDoesNot
            // uses for its floor.
            assertTrue(
                m.error > 0.5,
                "relative error ${m.error} is now well under the predict-zero baseline of 1.000 " +
                    "-- better than expected; re-measure and update the quoted numbers"
            )
            assertTrue(
                m.error < 1.1,
                "relative error ${m.error} is worse than predicting zero, which is a broken " +
                    "model rather than the known limitation"
            )
        }
    }
}
