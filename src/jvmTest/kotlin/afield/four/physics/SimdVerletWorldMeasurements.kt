package afield.four.physics

import afield.stdlib.vec
import kotlin.test.Test

/**
 * Timing for the world layer: what a projection costs, where the vector paths pay, and at what
 * collider count the "it is only a square root per particle" argument stops being true.
 *
 * A `*Measurements` class, so `check` excludes it and `./gradlew jvmMeasurements` runs it. It makes
 * no assertions on purpose — a wall-clock floor on a shared machine is a flake generator, and the
 * numbers here are for a person reading a table, not for CI. The *correctness* of the same code
 * paths is held down by `SimdVerletCollidersTest`, including that the vector and scalar
 * implementations agree.
 *
 * Everything is reported per particle per collider per step, because that is the number that
 * answers "can I afford another shape".
 */
class SimdVerletWorldMeasurements {

    private fun scatter(count: Int, seed: Long): SimdVerlet {
        val sim = SimdVerlet(SimdVerlet.State3.empty(count))
        val rng = java.util.Random(seed)
        repeat(count) {
            val x = (rng.nextFloat() - 0.5f) * 8f
            val y = (rng.nextFloat() - 0.5f) * 8f
            val z = (rng.nextFloat() - 0.5f) * 8f
            sim.addParticle(
                x0 = x, y0 = y, z0 = z,
                px0 = x - (rng.nextFloat() - 0.5f) * 0.2f,
                py0 = y - (rng.nextFloat() - 0.5f) * 0.2f,
                pz0 = z - (rng.nextFloat() - 0.5f) * 0.2f
            )
        }
        return sim
    }

    /** Median of `reps` timings of [body], in nanoseconds, after a warmup. */
    private fun timeNanos(warmup: Int, reps: Int, body: () -> Unit): Long {
        repeat(warmup) { body() }
        val samples = LongArray(reps)
        for (n in 0 until reps) {
            val t0 = System.nanoTime()
            body()
            samples[n] = System.nanoTime() - t0
        }
        samples.sort()
        return samples[reps / 2]
    }

    @Test
    fun projectionCostPerShape() {
        val n = 40_000
        println("one projectRange over $n scattered particles, median of 41, after 20 warmup calls.")
        println("The warmup resolves the contacts, so these are *steady-state* costs -- a shape that")
        println("particles are resting against rather than arriving at, which is the common case.")
        val shapes: List<Pair<String, () -> Collider>> = listOf(
            "plane (vector)" to { PlaneCollider(0.2f, 1f, -0.1f, offset = -0.5f) },
            "plane (scalar)" to { PlaneCollider(0.2f, 1f, -0.1f, offset = -0.5f).also { it.vectorized = false } },
            "sphere (vector)" to { SphereCollider(0f, 0f, 0f, radius = 5f) },
            "sphere (scalar)" to { SphereCollider(0f, 0f, 0f, radius = 5f).also { it.vectorized = false } },
            "capsule" to { CapsuleCollider(-2f, 0f, 0f, 2f, 0f, 0f, radius = 4f) },
            "box (outside)" to { BoxCollider(-2f, -2f, -2f, 2f, 2f, 2f, inside = false) },
            "box (inside)" to { BoxCollider(-3f, -3f, -3f, 3f, 3f, 3f, inside = true) },
            "sdf (sphere, 1 step)" to {
                SdfCollider(
                    { x, y, z -> kotlin.math.sqrt(x * x + y * y + z * z) - 5f },
                    maxSteps = 1
                )
            }
        )
        for ((name, make) in shapes) {
            val sim = scatter(n, 99L)
            val collider = make()
            var contacts = 0
            val t = timeNanos(warmup = 20, reps = 41) {
                contacts = collider.projectRange(sim.state, 0, n)
            }
            println("  %-22s %10d ns   %6.2f ns/particle   (%d contacts)".format(name, t, t.toDouble() / n, contacts))
        }
    }

    @Test
    fun collidersAgainstTheRestOfAStep() {
        // The question this answers: at what collider count does the world layer stop being free
        // next to the constraint solve it sits behind. Spoiler, and the reason the predicted column
        // is here: at these sizes it never does. Thirty-two spheres over a 4096-particle cloth is a
        // predicted 2.8 % of the step, which is inside the run-to-run spread of the measurement --
        // measured, the "% over bare" column wanders between -5 and +1 with no trend. The honest
        // reading is "below the noise floor of the solve", not "free", and the per-particle numbers
        // in projectionCostPerShape are what to extrapolate from.
        val cloth = 64
        println("a $cloth x $cloth cloth (${cloth * cloth} particles), stiffness 60 x 16, median of 25 steps")
        var baseline = 1L
        for (colliderCount in listOf(0, 1, 2, 4, 8, 16, 32)) {
            val sim = SimdVerlet(SimdVerlet.State3.empty())
            val patch = sim.addCloth(
                width = cloth, height = cloth, spacing = 0.1f, stiffness = 60f, curvatureStrength = 0f,
                position = { c, r -> vec((c - cloth * 0.5f) * 0.1f, 1f, (r - cloth * 0.5f) * 0.1f) },
                pinned = { _, _ -> false }
            )
            if (colliderCount > 0) {
                val shapes = Array<Collider>(colliderCount) { k ->
                    val a = k * 2.4f
                    SphereCollider(kotlin.math.cos(a) * 1.6f, -0.6f, kotlin.math.sin(a) * 1.6f, radius = 0.5f)
                }
                sim.addColliders(*shapes)
            }
            // Settle first, so every configuration is timed in contact rather than in free fall.
            repeat(120) { patch.step(dt = 1f / 60f, damping = 0.99f, gravity = -9.8f, iterations = 16) }
            val t = timeNanos(warmup = 10, reps = 25) {
                patch.step(dt = 1f / 60f, damping = 0.99f, gravity = -9.8f, iterations = 16)
            }
            if (colliderCount == 0) baseline = t
            // A predicted cost from projectionCostPerShape's steady-state per-particle number, so
            // that the measured column can be read against something: at ~1.1 ns per particle per
            // sphere, 32 spheres over 4096 particles is about 144 us against a 5 ms step.
            val predicted = colliderCount * cloth * cloth * 1.1
            println("  %2d colliders   %9d ns/step   %+8.1f %% over bare   (predicted +%.0f ns, %+.1f %%)".format(
                colliderCount, t, 100.0 * (t - baseline) / baseline, predicted, 100.0 * predicted / baseline
            ))
        }
    }

    @Test
    fun windAndTearingAgainstTheSameStep() {
        val cloth = 48
        println("a $cloth x $cloth cloth (${cloth * cloth} particles), median of 25 steps, each pass added in turn")
        val stages = listOf("bare", "+ drag", "+ per-quad aero", "+ tearing", "+ one sphere")
        var bare = 1L
        for (upTo in stages.indices) {
            val sim = SimdVerlet(SimdVerlet.State3.empty())
            val patch = sim.addCloth(
                width = cloth, height = cloth, spacing = 0.12f, stiffness = 60f, curvatureStrength = 0f,
                position = { c, r -> vec(c * 0.12f, -r * 0.12f, 0f) },
                pinned = { _, r -> r == 0 }
            )
            var wind: Wind? = null
            if (upTo >= 1) wind = sim.addWind(velocityZ = 6f, drag = 0.2f, turbulence = 0.5f)
            if (upTo >= 2) patch.addWind(wind!!)
            if (upTo >= 3) patch.enableTearing(threshold = 5f)  // high, so nothing tears: this is the scan cost
            if (upTo >= 4) sim.addColliders(SphereCollider(2f, -2f, 0.5f, radius = 1f))
            repeat(60) { patch.step(dt = 1f / 60f, damping = 0.99f, gravity = -4f, iterations = 16) }
            val t = timeNanos(warmup = 10, reps = 51) {
                patch.step(dt = 1f / 60f, damping = 0.99f, gravity = -4f, iterations = 16)
            }
            if (upTo == 0) bare = t
            println("  %-18s %9d ns/step   %+7.1f %% over bare".format(stages[upTo], t, 100.0 * (t - bare) / bare))
        }
    }
}
