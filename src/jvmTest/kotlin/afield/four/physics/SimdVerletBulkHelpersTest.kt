package afield.four.physics

import jdk.incubator.vector.FloatVector
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Covers the [SimdVerlet] helpers that delegate to the generated bulk kernels
 * (velocity read-out and the spring-to-target force). The oracle is the obvious
 * scalar loop; `n` is swept across the SIMD lane boundary since the vector-loop /
 * scalar-tail split inside the kernels is where a boundary bug would hide.
 */
class SimdVerletBulkHelpersTest {
    private val lanes = FloatVector.SPECIES_PREFERRED.length()
    private val boundaryLengths = listOf(1, lanes - 1, lanes, lanes + 1, 2 * lanes, 2 * lanes + 3).filter { it >= 1 }

    private fun randomFloats(random: Random, n: Int, from: Float = -10f, to: Float = 10f) =
        FloatArray(n) { from + random.nextFloat() * (to - from) }

    private fun assertClose(expected: FloatArray, actual: FloatArray, tol: Float, label: String) {
        for (i in expected.indices) {
            assertTrue(abs(expected[i] - actual[i]) <= tol, "$label[$i]: expected=${expected[i]} actual=${actual[i]}")
        }
    }

    private fun stateOf(
        random: Random, n: Int,
        ax: FloatArray = FloatArray(n), ay: FloatArray = FloatArray(n), az: FloatArray = FloatArray(n),
    ): SimdVerlet.State3 = SimdVerlet.State3(
        x = randomFloats(random, n), y = randomFloats(random, n), z = randomFloats(random, n),
        px = randomFloats(random, n), py = randomFloats(random, n), pz = randomFloats(random, n),
        ax = ax, ay = ay, az = az,
    )

    @Test
    fun velocity3MatchesScalarAcrossBoundaryLengths() {
        val random = Random(0x5117ed)
        for (n in boundaryLengths) {
            val state = stateOf(random, n)
            val dt = 1f / 60f
            // velocity3's contract is a reciprocal multiply (matching the scalar-mul kernel), so the
            // oracle multiplies by 1/dt too rather than dividing — otherwise the ~x60 scale amplifies
            // the reciprocal-vs-divide rounding difference.
            val invDt = 1f / dt
            val expX = FloatArray(n) { (state.x[it] - state.px[it]) * invDt }
            val expY = FloatArray(n) { (state.y[it] - state.py[it]) * invDt }
            val expZ = FloatArray(n) { (state.z[it] - state.pz[it]) * invDt }

            val vx = FloatArray(n); val vy = FloatArray(n); val vz = FloatArray(n)
            SimdVerlet.velocity3(state, dt, vx, vy, vz)

            assertClose(expX, vx, 1e-4f, "vx")
            assertClose(expY, vy, 1e-4f, "vy")
            assertClose(expZ, vz, 1e-4f, "vz")
        }
    }

    @Test
    fun springForceAccumulatesOntoExistingForcesAcrossBoundaryLengths() {
        val random = Random(0x5F1276)
        for (n in boundaryLengths) {
            val ax0 = randomFloats(random, n); val ay0 = randomFloats(random, n); val az0 = randomFloats(random, n)
            val state = stateOf(random, n, ax = ax0.copyOf(), ay = ay0.copyOf(), az = az0.copyOf())
            val tx = randomFloats(random, n); val ty = randomFloats(random, n); val tz = randomFloats(random, n)
            val k = 12.5f

            // Oracle: accumulate onto the pre-existing forces, not overwrite them.
            val expAx = FloatArray(n) { ax0[it] + k * (tx[it] - state.x[it]) }
            val expAy = FloatArray(n) { ay0[it] + k * (ty[it] - state.y[it]) }
            val expAz = FloatArray(n) { az0[it] + k * (tz[it] - state.z[it]) }

            val sx = FloatArray(n); val sy = FloatArray(n); val sz = FloatArray(n)
            SimdVerlet.addSpringForceToTarget3(state, tx, ty, tz, k, sx, sy, sz)

            assertClose(expAx, state.ax, 2e-4f, "ax")
            assertClose(expAy, state.ay, 2e-4f, "ay")
            assertClose(expAz, state.az, 2e-4f, "az")
        }
    }
}
