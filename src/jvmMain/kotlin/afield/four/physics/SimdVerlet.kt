@file:Suppress("NOTHING_TO_INLINE")

package afield.four.physics

import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.VectorMask
import jdk.incubator.vector.VectorOperators.SQRT
import jdk.incubator.vector.VectorSpecies
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * SIMD Verlet kernels and coarse chunk-parallel utilities for small/medium particle counts.
 *
 * Runtime requirement:
 *   --add-modules jdk.incubator.vector
 */
object SimdVerlet {

    /**
     * Particle state in SoA form. `invMass` is optional.
     * If present, integration treats ax/ay/az as forces and scales by invMass.
     * If absent, ax/ay/az are treated as accelerations.
     */
    class State3(
        val x: FloatArray,
        val y: FloatArray,
        val z: FloatArray,
        val px: FloatArray,
        val py: FloatArray,
        val pz: FloatArray,
        val ax: FloatArray,
        val ay: FloatArray,
        val az: FloatArray,
        val invMass: FloatArray? = null
    ) {
        val size: Int = x.size

        init {
            require(y.size == size && z.size == size) { "x/y/z size mismatch" }
            require(px.size == size && py.size == size && pz.size == size) { "prev position size mismatch" }
            require(ax.size == size && ay.size == size && az.size == size) { "accumulator size mismatch" }
            require(invMass == null || invMass.size == size) { "invMass size mismatch" }
        }
    }

    /**
     * Directed 3-slot constraint layout:
     *   i -> n0[i], i -> n1[i], i -> n2[i]
     * Unused slot: neighbor = -1 or amount = 0.
     */
    class DirectedConstraints3(
        val n0: IntArray,
        val n1: IntArray,
        val n2: IntArray,
        val r0: FloatArray,
        val r1: FloatArray,
        val r2: FloatArray,
        val a0: FloatArray,
        val a1: FloatArray,
        val a2: FloatArray
    ) {
        val size: Int = n0.size

        init {
            require(n1.size == size && n2.size == size) { "neighbor slot size mismatch" }
            require(r0.size == size && r1.size == size && r2.size == size) { "rest-length slot size mismatch" }
            require(a0.size == size && a1.size == size && a2.size == size) { "amount slot size mismatch" }
        }
    }

    /**
     * Scratch buffers for constraint gather maps, reusable across frames.
     * Keep one per worker thread to avoid allocations in the hot loop.
     */
    class ConstraintScratch(private val species: VectorSpecies<Float>) {
        internal val map0 = IntArray(species.length())
        internal val map1 = IntArray(species.length())
        internal val map2 = IntArray(species.length())
    }

    // Best default for contiguous, non-gather passes.
    private val SP: VectorSpecies<Float> = FloatVector.SPECIES_PREFERRED

    // Gather-heavy constraints are often better with fixed 128-bit vectors.
    private val SG: VectorSpecies<Float> = FloatVector.SPECIES_128

    private val scratchTLS = ThreadLocal.withInitial { ConstraintScratch(SG) }

    fun createConstraintScratch(): ConstraintScratch = ConstraintScratch(SG)

    // ----------------------------
    // 1) Zero accumulators (SIMD)
    // ----------------------------
    fun zero3(state: State3) = zero3(state.ax, state.ay, state.az)

    fun zero3(ax: FloatArray, ay: FloatArray, az: FloatArray) {
        require(ay.size == ax.size && az.size == ax.size) { "accumulator size mismatch" }
        zero3Range(ax, ay, az, 0, ax.size)
    }

    fun zero3Range(ax: FloatArray, ay: FloatArray, az: FloatArray, start: Int, endExclusive: Int) {
        require(ay.size == ax.size && az.size == ax.size) { "accumulator size mismatch" }
        val n = ax.size
        val startIdx = start.coerceIn(0, n)
        val endIdx = endExclusive.coerceIn(startIdx, n)

        val zero = FloatVector.zero(SP)
        val lanes = SP.length()

        var i = startIdx
        while (i + lanes <= endIdx) {
            zero.intoArray(ax, i)
            zero.intoArray(ay, i)
            zero.intoArray(az, i)
            i += lanes
        }
        while (i < endIdx) {
            ax[i] = 0f
            ay[i] = 0f
            az[i] = 0f
            i++
        }
    }

    // ------------------------------------
    // 2) Verlet integrate + damping (SIMD)
    // new = x + (x - px)*damping + accel*dt^2
    // px = old x
    // ------------------------------------
    fun integrateVerlet3(state: State3, dt: Float, damping: Float) {
        integrateVerlet3(
            x = state.x, y = state.y, z = state.z,
            px = state.px, py = state.py, pz = state.pz,
            ax = state.ax, ay = state.ay, az = state.az,
            dt = dt, damping = damping, invMass = state.invMass
        )
    }

    fun integrateVerlet3(
        x: FloatArray, y: FloatArray, z: FloatArray,
        px: FloatArray, py: FloatArray, pz: FloatArray,
        ax: FloatArray, ay: FloatArray, az: FloatArray,
        dt: Float,
        damping: Float,
        invMass: FloatArray? = null
    ) {
        val n = x.size
        require(y.size == n && z.size == n) { "position size mismatch" }
        require(px.size == n && py.size == n && pz.size == n) { "prev position size mismatch" }
        require(ax.size == n && ay.size == n && az.size == n) { "accumulator size mismatch" }
        require(invMass == null || invMass.size == n) { "invMass size mismatch" }

        integrateVerlet3Range(
            x, y, z,
            px, py, pz,
            ax, ay, az,
            start = 0,
            endExclusive = n,
            dt = dt,
            damping = damping,
            invMass = invMass
        )
    }

    fun integrateVerlet3Range(
        x: FloatArray, y: FloatArray, z: FloatArray,
        px: FloatArray, py: FloatArray, pz: FloatArray,
        ax: FloatArray, ay: FloatArray, az: FloatArray,
        start: Int,
        endExclusive: Int,
        dt: Float,
        damping: Float,
        invMass: FloatArray? = null
    ) {
        val n = x.size
        require(y.size == n && z.size == n) { "position size mismatch" }
        require(px.size == n && py.size == n && pz.size == n) { "prev position size mismatch" }
        require(ax.size == n && ay.size == n && az.size == n) { "accumulator size mismatch" }
        require(invMass == null || invMass.size == n) { "invMass size mismatch" }

        val startIdx = start.coerceIn(0, n)
        val endIdx = endExclusive.coerceIn(startIdx, n)
        val dt2 = dt * dt

        val lanes = SP.length()
        val vDamping = FloatVector.broadcast(SP, damping)
        val vDt2 = FloatVector.broadcast(SP, dt2)

        var i = startIdx
        if (invMass == null) {
            while (i + lanes <= endIdx) {
                val vx = FloatVector.fromArray(SP, x, i)
                val vy = FloatVector.fromArray(SP, y, i)
                val vz = FloatVector.fromArray(SP, z, i)

                val vpx = FloatVector.fromArray(SP, px, i)
                val vpy = FloatVector.fromArray(SP, py, i)
                val vpz = FloatVector.fromArray(SP, pz, i)

                val vax = FloatVector.fromArray(SP, ax, i)
                val vay = FloatVector.fromArray(SP, ay, i)
                val vaz = FloatVector.fromArray(SP, az, i)

                val velx = vx.sub(vpx).mul(vDamping)
                val vely = vy.sub(vpy).mul(vDamping)
                val velz = vz.sub(vpz).mul(vDamping)

                val nx = vx.add(velx).add(vax.mul(vDt2))
                val ny = vy.add(vely).add(vay.mul(vDt2))
                val nz = vz.add(velz).add(vaz.mul(vDt2))

                vx.intoArray(px, i)
                vy.intoArray(py, i)
                vz.intoArray(pz, i)

                nx.intoArray(x, i)
                ny.intoArray(y, i)
                nz.intoArray(z, i)

                i += lanes
            }

            while (i < endIdx) {
                val ox = x[i]
                val oy = y[i]
                val oz = z[i]

                val velx = (ox - px[i]) * damping
                val vely = (oy - py[i]) * damping
                val velz = (oz - pz[i]) * damping

                px[i] = ox
                py[i] = oy
                pz[i] = oz

                x[i] = ox + velx + ax[i] * dt2
                y[i] = oy + vely + ay[i] * dt2
                z[i] = oz + velz + az[i] * dt2
                i++
            }
            return
        }

        while (i + lanes <= endIdx) {
            val vx = FloatVector.fromArray(SP, x, i)
            val vy = FloatVector.fromArray(SP, y, i)
            val vz = FloatVector.fromArray(SP, z, i)

            val vpx = FloatVector.fromArray(SP, px, i)
            val vpy = FloatVector.fromArray(SP, py, i)
            val vpz = FloatVector.fromArray(SP, pz, i)

            val vax = FloatVector.fromArray(SP, ax, i)
            val vay = FloatVector.fromArray(SP, ay, i)
            val vaz = FloatVector.fromArray(SP, az, i)

            val vim = FloatVector.fromArray(SP, invMass, i)
            val accelScale = vim.mul(vDt2)

            val velx = vx.sub(vpx).mul(vDamping)
            val vely = vy.sub(vpy).mul(vDamping)
            val velz = vz.sub(vpz).mul(vDamping)

            val nx = vx.add(velx).add(vax.mul(accelScale))
            val ny = vy.add(vely).add(vay.mul(accelScale))
            val nz = vz.add(velz).add(vaz.mul(accelScale))

            vx.intoArray(px, i)
            vy.intoArray(py, i)
            vz.intoArray(pz, i)

            nx.intoArray(x, i)
            ny.intoArray(y, i)
            nz.intoArray(z, i)

            i += lanes
        }

        while (i < endIdx) {
            val ox = x[i]
            val oy = y[i]
            val oz = z[i]
            val im = invMass[i]

            val velx = (ox - px[i]) * damping
            val vely = (oy - py[i]) * damping
            val velz = (oz - pz[i]) * damping

            px[i] = ox
            py[i] = oy
            pz[i] = oz

            x[i] = ox + velx + ax[i] * dt2 * im
            y[i] = oy + vely + ay[i] * dt2 * im
            z[i] = oz + velz + az[i] * dt2 * im
            i++
        }
    }

    // -----------------------------------------------------------
    // 3) Directed 3-slot distance constraints (SIMD, in-place)
    // Each i has up to 3 outgoing constraints to neighbor j.
    // We update only i (no scatter), so range-parallel calls are safe.
    // -----------------------------------------------------------
    fun solveDirectedDist3InPlace(
        state: State3,
        constraints: DirectedConstraints3,
        globalStiffness: Float,
        iterations: Int,
        scratch: ConstraintScratch = scratchTLS.get()
    ) {
        solveDirectedDist3InPlaceRange(
            x = state.x, y = state.y, z = state.z,
            n0 = constraints.n0, n1 = constraints.n1, n2 = constraints.n2,
            r0 = constraints.r0, r1 = constraints.r1, r2 = constraints.r2,
            a0 = constraints.a0, a1 = constraints.a1, a2 = constraints.a2,
            start = 0,
            endExclusive = state.size,
            globalStiffness = globalStiffness,
            iterations = iterations,
            scratch = scratch
        )
    }

    fun solveDirectedDist3InPlace(
        x: FloatArray, y: FloatArray, z: FloatArray,
        n0: IntArray, n1: IntArray, n2: IntArray,
        r0: FloatArray, r1: FloatArray, r2: FloatArray,
        a0: FloatArray, a1: FloatArray, a2: FloatArray,
        globalStiffness: Float,
        iterations: Int,
        scratch: ConstraintScratch = scratchTLS.get()
    ) {
        solveDirectedDist3InPlaceRange(
            x, y, z,
            n0, n1, n2,
            r0, r1, r2,
            a0, a1, a2,
            start = 0,
            endExclusive = x.size,
            globalStiffness = globalStiffness,
            iterations = iterations,
            scratch = scratch
        )
    }

    fun solveDirectedDist3InPlaceRange(
        x: FloatArray, y: FloatArray, z: FloatArray,
        n0: IntArray, n1: IntArray, n2: IntArray,
        r0: FloatArray, r1: FloatArray, r2: FloatArray,
        a0: FloatArray, a1: FloatArray, a2: FloatArray,
        start: Int,
        endExclusive: Int,
        globalStiffness: Float,
        iterations: Int,
        scratch: ConstraintScratch = scratchTLS.get()
    ) {
        require(iterations >= 0) { "iterations must be >= 0" }
        if (iterations == 0) return

        val n = x.size
        require(y.size == n && z.size == n) { "position size mismatch" }
        require(n0.size == n && n1.size == n && n2.size == n) { "neighbor size mismatch" }
        require(r0.size == n && r1.size == n && r2.size == n) { "rest size mismatch" }
        require(a0.size == n && a1.size == n && a2.size == n) { "amount size mismatch" }

        val startIdx = start.coerceIn(0, n)
        val endIdx = endExclusive.coerceIn(startIdx, n)

        val lanes = SG.length()
        val zero = FloatVector.zero(SG)
        val eps = 1.0e-8f
        val vEps = FloatVector.broadcast(SG, eps)
        val vOne = FloatVector.broadcast(SG, 1f)
        val vStiffness = FloatVector.broadcast(SG, globalStiffness)

        val map0 = scratch.map0
        val map1 = scratch.map1
        val map2 = scratch.map2
        require(map0.size == lanes && map1.size == lanes && map2.size == lanes) {
            "ConstraintScratch species mismatch: expected lane count $lanes"
        }

        repeat(iterations) {
            var i = startIdx
            while (i + lanes <= endIdx) {
                var xi = FloatVector.fromArray(SG, x, i)
                var yi = FloatVector.fromArray(SG, y, i)
                var zi = FloatVector.fromArray(SG, z, i)

                var bits0 = 0L
                var bits1 = 0L
                var bits2 = 0L

                for (lane in 0 until lanes) {
                    val idx = i + lane
                    val j0 = n0[idx]
                    val j1 = n1[idx]
                    val j2 = n2[idx]

                    if (j0 in 0 until n) {
                        bits0 = bits0 or (1L shl lane)
                        map0[lane] = j0
                    } else {
                        map0[lane] = 0
                    }
                    if (j1 in 0 until n) {
                        bits1 = bits1 or (1L shl lane)
                        map1[lane] = j1
                    } else {
                        map1[lane] = 0
                    }
                    if (j2 in 0 until n) {
                        bits2 = bits2 or (1L shl lane)
                        map2[lane] = j2
                    } else {
                        map2[lane] = 0
                    }
                }

                var cx = FloatVector.zero(SG)
                var cy = FloatVector.zero(SG)
                var cz = FloatVector.zero(SG)

                if (bits0 != 0L) {
                    val m0 = VectorMask.fromLong(SG, bits0)
                    val xj = FloatVector.fromArray(SG, x, 0, map0, 0)
                    val yj = FloatVector.fromArray(SG, y, 0, map0, 0)
                    val zj = FloatVector.fromArray(SG, z, 0, map0, 0)

                    val dx = xj.sub(xi)
                    val dy = yj.sub(yi)
                    val dz = zj.sub(zi)

                    val d2 = dx.fma(dx, dy.fma(dy, dz.mul(dz)))
                    val dist = d2.lanewise(SQRT)

                    val rest = FloatVector.fromArray(SG, r0, i)
                    val amt = FloatVector.fromArray(SG, a0, i).mul(vStiffness)
                    val invDist = vOne.div(dist.add(vEps))
                    val s = dist.sub(rest).mul(invDist).mul(amt)

                    cx = cx.add(dx.mul(s).blend(zero, m0.not()))
                    cy = cy.add(dy.mul(s).blend(zero, m0.not()))
                    cz = cz.add(dz.mul(s).blend(zero, m0.not()))
                }

                if (bits1 != 0L) {
                    val m1 = VectorMask.fromLong(SG, bits1)
                    val xj = FloatVector.fromArray(SG, x, 0, map1, 0)
                    val yj = FloatVector.fromArray(SG, y, 0, map1, 0)
                    val zj = FloatVector.fromArray(SG, z, 0, map1, 0)

                    val dx = xj.sub(xi)
                    val dy = yj.sub(yi)
                    val dz = zj.sub(zi)

                    val d2 = dx.fma(dx, dy.fma(dy, dz.mul(dz)))
                    val dist = d2.lanewise(SQRT)

                    val rest = FloatVector.fromArray(SG, r1, i)
                    val amt = FloatVector.fromArray(SG, a1, i).mul(vStiffness)
                    val invDist = vOne.div(dist.add(vEps))
                    val s = dist.sub(rest).mul(invDist).mul(amt)

                    cx = cx.add(dx.mul(s).blend(zero, m1.not()))
                    cy = cy.add(dy.mul(s).blend(zero, m1.not()))
                    cz = cz.add(dz.mul(s).blend(zero, m1.not()))
                }

                if (bits2 != 0L) {
                    val m2 = VectorMask.fromLong(SG, bits2)
                    val xj = FloatVector.fromArray(SG, x, 0, map2, 0)
                    val yj = FloatVector.fromArray(SG, y, 0, map2, 0)
                    val zj = FloatVector.fromArray(SG, z, 0, map2, 0)

                    val dx = xj.sub(xi)
                    val dy = yj.sub(yi)
                    val dz = zj.sub(zi)

                    val d2 = dx.fma(dx, dy.fma(dy, dz.mul(dz)))
                    val dist = d2.lanewise(SQRT)

                    val rest = FloatVector.fromArray(SG, r2, i)
                    val amt = FloatVector.fromArray(SG, a2, i).mul(vStiffness)
                    val invDist = vOne.div(dist.add(vEps))
                    val s = dist.sub(rest).mul(invDist).mul(amt)

                    cx = cx.add(dx.mul(s).blend(zero, m2.not()))
                    cy = cy.add(dy.mul(s).blend(zero, m2.not()))
                    cz = cz.add(dz.mul(s).blend(zero, m2.not()))
                }

                xi = xi.add(cx)
                yi = yi.add(cy)
                zi = zi.add(cz)

                xi.intoArray(x, i)
                yi.intoArray(y, i)
                zi.intoArray(z, i)

                i += lanes
            }

            while (i < endIdx) {
                var xiS = x[i]
                var yiS = y[i]
                var ziS = z[i]

                val j0 = n0[i]
                if (j0 in 0 until n) {
                    val w = a0[i] * globalStiffness
                    if (w != 0f) {
                        val dx = x[j0] - xiS
                        val dy = y[j0] - yiS
                        val dz = z[j0] - ziS
                        val dist = sqrt(dx * dx + dy * dy + dz * dz + eps)
                        val s = (dist - r0[i]) / dist * w
                        xiS += dx * s
                        yiS += dy * s
                        ziS += dz * s
                    }
                }

                val j1 = n1[i]
                if (j1 in 0 until n) {
                    val w = a1[i] * globalStiffness
                    if (w != 0f) {
                        val dx = x[j1] - xiS
                        val dy = y[j1] - yiS
                        val dz = z[j1] - ziS
                        val dist = sqrt(dx * dx + dy * dy + dz * dz + eps)
                        val s = (dist - r1[i]) / dist * w
                        xiS += dx * s
                        yiS += dy * s
                        ziS += dz * s
                    }
                }

                val j2 = n2[i]
                if (j2 in 0 until n) {
                    val w = a2[i] * globalStiffness
                    if (w != 0f) {
                        val dx = x[j2] - xiS
                        val dy = y[j2] - yiS
                        val dz = z[j2] - ziS
                        val dist = sqrt(dx * dx + dy * dy + dz * dz + eps)
                        val s = (dist - r2[i]) / dist * w
                        xiS += dx * s
                        yiS += dy * s
                        ziS += dz * s
                    }
                }

                x[i] = xiS
                y[i] = yiS
                z[i] = ziS
                i++
            }
        }
    }

    // ---------------------------------------------------------
    // 4) Parallel range execution with coarse chunking
    // ---------------------------------------------------------
    class ForceExecutor(threads: Int) : AutoCloseable {
        private val workerCount = max(1, threads)
        private val pool: ExecutorService = Executors.newFixedThreadPool(workerCount)

        /**
         * Generic contiguous range execution utility.
         */
        fun runRanges(
            n: Int,
            minChunk: Int = 256,
            task: (start: Int, endExclusive: Int) -> Unit
        ) {
            if (n <= 0) return
            val tasks = min(workerCount * 2, max(1, n / max(1, minChunk)))
            if (tasks <= 1) {
                task(0, n)
                return
            }

            val chunk = (n + tasks - 1) / tasks
            val futures = ArrayList<Future<*>>(tasks)

            var start = 0
            while (start < n) {
                val endExclusive = min(n, start + chunk)
                val s = start
                futures += pool.submit { task(s, endExclusive) }
                start = endExclusive
            }

            for (future in futures) {
                future.get()
            }
        }

        /**
         * Force accumulation helper (serial per point, parallel by range).
         * `accumulate` must only write to indices inside [start, endExclusive).
         */
        fun runForces(
            n: Int,
            minChunk: Int = 256,
            accumulate: (start: Int, endExclusive: Int) -> Unit
        ) {
            runRanges(n = n, minChunk = minChunk, task = accumulate)
        }

        override fun close() {
            pool.shutdown()
        }
    }
}
