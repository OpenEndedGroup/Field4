package afield.examples

import afield.four.physics.SimdVerlet
import afield.stdlib.linalg.bulk.addRange
import afield.stdlib.linalg.bulk.crossRange
import afield.stdlib.linalg.bulk.dotRange
import afield.stdlib.linalg.bulk.lengthSquaredRange
import afield.stdlib.linalg.bulk.lerpRange
import afield.stdlib.linalg.bulk.normalizeRange
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Worked examples of the generated bulk/SIMD vector kernels
 * (`afield.stdlib.linalg.bulk.*`) and their intended pairing with [SimdVerlet].
 *
 * Part 1 is a standalone tour of the kernel-calling convention: every function is
 * `<op>Range(selfComponents…, [otherComponents…], [scalar], destComponents…, start, endExclusive)`
 * over flat structure-of-arrays `FloatArray`s. This is the reference for "how do I call
 * these?" — the correctness contract itself is covered by `BulkVectorsTest`.
 *
 * Part 2 drives a real [SimdVerlet] cloth/cloud with two of the kernels surfaced as
 * physics helpers on the simulator: [SimdVerlet.Companion.addSpringForceToTarget3] pulls
 * every particle toward a moving target pose in force-space, and
 * [SimdVerlet.Companion.velocity3] recovers per-particle velocity (which Verlet otherwise
 * hides behind the previous-position array) so we can print total kinetic energy each frame
 * via the raw `lengthSquaredRange` reduction kernel.
 *
 * Run: `./gradlew runSimdVerletBulkKernelsExample`
 */
object SimdVerletBulkKernelsExample {

    @JvmStatic
    fun main(args: Array<String>) {
        part1StandaloneKernelTour()
        println()
        part2SpringToTargetSimulation()
    }

    // ------------------------------------------------------------------
    // Part 1 — calling the generated kernels directly on SoA FloatArrays
    // ------------------------------------------------------------------
    private fun part1StandaloneKernelTour() {
        val n = 10
        println("== Part 1: bulk kernel tour (Vector3f over $n elements) ==")

        // Structure-of-arrays: one FloatArray per component, not an array of Vec3.
        val ax = FloatArray(n) { it.toFloat() }
        val ay = FloatArray(n) { 2f * it }
        val az = FloatArray(n) { -it.toFloat() }

        val bx = FloatArray(n) { 1f }
        val by = FloatArray(n) { 0f }
        val bz = FloatArray(n) { 1f }

        // Destinations. `0, n` is the [start, endExclusive) range; the kernel handles the
        // SIMD body and the scalar tail for whatever `n` happens to be.
        val sumX = FloatArray(n); val sumY = FloatArray(n); val sumZ = FloatArray(n)
        addRange(ax, ay, az, bx, by, bz, sumX, sumY, sumZ, 0, n)

        val crossX = FloatArray(n); val crossY = FloatArray(n); val crossZ = FloatArray(n)
        crossRange(ax, ay, az, bx, by, bz, crossX, crossY, crossZ, 0, n)

        // Kernels tolerate dest === self, so normalize can write back in place.
        normalizeRange(sumX, sumY, sumZ, sumX, sumY, sumZ, 0, n)

        val dots = FloatArray(n)
        dotRange(sumX, sumY, sumZ, bx, by, bz, dots, 0, n)

        // Scalar-broadcast overload (the add/sub scalar kernels completed in this pass):
        // adds the same value to every component of every element, in place.
        addRange(crossX, crossY, crossZ, 0.5f, crossX, crossY, crossZ, 0, n)

        for (i in listOf(0, n / 2, n - 1)) {
            println(
                "  i=$i  a+b(normalized)=(${fmt(sumX[i])}, ${fmt(sumY[i])}, ${fmt(sumZ[i])})  " +
                    "a×b=(${fmt(crossX[i])}, ${fmt(crossY[i])}, ${fmt(crossZ[i])})  " +
                    "dot=${fmt(dots[i])}"
            )
        }
    }

    // ------------------------------------------------------------------
    // Part 2 — the kernels as SimdVerlet physics helpers
    // ------------------------------------------------------------------
    private fun part2SpringToTargetSimulation() {
        println("== Part 2: SimdVerlet cloud springing toward a moving target ==")
        val count = 4096

        val state = SimdVerlet.State3(
            x = FloatArray(count), y = FloatArray(count), z = FloatArray(count),
            px = FloatArray(count), py = FloatArray(count), pz = FloatArray(count),
            ax = FloatArray(count), ay = FloatArray(count), az = FloatArray(count),
            invMass = FloatArray(count) { 1f }
        )

        // Scatter the cloud on a lattice; px == x means "starts at rest".
        val side = Math.round(Math.cbrt(count.toDouble())).toInt().coerceAtLeast(1)
        for (i in 0 until count) {
            val gx = (i % side) - side / 2f
            val gy = ((i / side) % side) - side / 2f
            val gz = (i / (side * side)) - side / 2f
            state.x[i] = gx; state.y[i] = gy; state.z[i] = gz
            state.px[i] = gx; state.py[i] = gy; state.pz[i] = gz
        }

        val sim = SimdVerlet(state)

        // Per-particle target pose the cloud is pulled toward. Reused, mutated per frame.
        val targetX = FloatArray(count); val targetY = FloatArray(count); val targetZ = FloatArray(count)
        // Persistent scratch for the spring helper and velocity read-out — reused every
        // frame so the hot loop allocates nothing.
        val sx = FloatArray(count); val sy = FloatArray(count); val sz = FloatArray(count)
        val vx = FloatArray(count); val vy = FloatArray(count); val vz = FloatArray(count)
        val speedSq = FloatArray(count)

        val dt = 1f / 60f
        val damping = 0.98f
        val stiffness = 40f

        repeat(240) { step ->
            // A target sphere that rotates and pulses — pure per-particle FloatArray writes.
            val angle = step * 0.05f
            val radius = 6f + 2f * sin(step * 0.03f)
            for (i in 0 until count) {
                val u = (i * 2.399963f) % 6.2831855f          // golden-angle spiral on a sphere
                val v = ((i * 0.5f) % count) / count - 0.5f
                targetX[i] = radius * cos(u + angle) * sqrt(1f - 4f * v * v)
                targetY[i] = radius * (2f * v)
                targetZ[i] = radius * sin(u + angle) * sqrt(1f - 4f * v * v)
            }

            // step() zeroes the accumulators at its start, so bulk forces are injected through its
            // accumulateForces hook (which runs after that zero) rather than pre-accumulated. Stack
            // more bulk kernels in this lambda to combine force fields in one frame.
            sim.step(dt = dt, damping = damping, gravity = 0f, iterations = 0) { s, _ ->
                SimdVerlet.addSpringForceToTarget3(s, targetX, targetY, targetZ, stiffness, sx, sy, sz)
            }

            if (step % 40 == 0 || step == 239) {
                // Recover velocity (Verlet hides it) then reduce speed² with the raw kernel.
                SimdVerlet.velocity3(state, dt, vx, vy, vz)
                lengthSquaredRange(vx, vy, vz, speedSq, 0, count)
                var ke = 0.0
                for (i in 0 until count) ke += 0.5 * speedSq[i]   // unit mass
                println("  step=%3d  totalKE=%s  p0=(%s, %s, %s)".format(
                    step, fmt(ke.toFloat()), fmt(state.x[0]), fmt(state.y[0]), fmt(state.z[0])
                ))
            }
        }
        println("  (cloud is chasing the rotating target sphere; KE stays non-zero because it never stops moving)")
    }

    private fun fmt(v: Float): String = "%.3f".format(v)
}
