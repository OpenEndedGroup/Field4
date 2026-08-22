@file:Suppress("NOTHING_TO_INLINE")

package afield.four.physics

import afield.Generator
import afield.four.util.MapOfGenerators
import afield.generator2
import afield.stdlib.linalg.bulk.fmaRange
import afield.stdlib.linalg.bulk.mulRange
import afield.stdlib.linalg.bulk.subRange
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
class SimdVerlet(
    val state: State3,
    var constraints: DirectedConstraints3? = null
) {

    /**
     * The 4-slot constraint set this simulation belongs to, if any. [step]'s 4-slot overload
     * still takes its constraints explicitly (defaulting it would make the two overloads
     * ambiguous), so this is a place for a builder -- e.g. [Cloth] -- to hand its constraint
     * set to whoever ends up driving the simulation:
     *
     *   sim.step(dt, damping, gravity, iterations, sim.constraints4)
     *
     * Only one of [constraints] / [constraints4] can be solved per step; setting this one does
     * not make the 3-slot overload see it.
     */
    var constraints4: DirectedConstraints4? = null

    /**
     * Named whole-state force passes, run on every [step] after gravity and the per-particle
     * force generators and before the constraint solve -- the same slot as step()'s
     * `accumulateForces` argument, but registered on the simulation rather than per call, so a
     * force that belongs to a *group* of particles (see [Cloth]'s curvature force) can be added
     * once and then survive plain `sim.step(...)` calls made by somebody else.
     *
     * Each pass accumulates into `state.ax/ay/az` exactly like `accumulateForces`; see [step]
     * for the accumulator contract. Register with [addBulkForce], drop with [removeBulkForce].
     * Don't add or remove entries from inside a pass -- the map is iterated live.
     */
    val bulkForces: MutableMap<String, (state: State3, dt: Float) -> Unit> = LinkedHashMap()

    class ForceContext(
        val state: State3,
        var index: Int,
        var dt: Float
    )

    val size: Int
        get() = state.size

    fun addZeroed(count: Int = 1): Int = state.addZeroed(count)

    fun addParticle(
        x0: Float = 0f,
        y0: Float = 0f,
        z0: Float = 0f,
        px0: Float = x0,
        py0: Float = y0,
        pz0: Float = z0,
        ax0: Float = 0f,
        ay0: Float = 0f,
        az0: Float = 0f,
        invMass0: Float = 1f
    ): Int = state.addParticle(x0, y0, z0, px0, py0, pz0, ax0, ay0, az0, invMass0)

    fun addForceGenerator(index: Int, name: String, generator: Generator<ForceContext, Unit>) {
        require(index in 0 until state.size) { "index out of bounds" }
        state.forceGenerators[index][name] = generator
    }

    /** Drops the [name]d force generator from particle [index], if it has one. */
    fun removeForceGenerator(index: Int, name: String) {
        require(index in 0 until state.size) { "index out of bounds" }
        state.forceGenerators[index].remove(name)
    }

    /** Registers (or replaces) a named whole-state force pass. See [bulkForces]. */
    fun addBulkForce(name: String, force: (state: State3, dt: Float) -> Unit) {
        bulkForces[name] = force
    }

    /** Drops the [name]d whole-state force pass, if it is registered. See [bulkForces]. */
    fun removeBulkForce(name: String) {
        bulkForces.remove(name)
    }

    fun addPidPositionController(
        index: Int,
        name: String,
        targetX: () -> Float,
        targetY: () -> Float,
        targetZ: () -> Float,
        kp: Float = 5f,
        ki: Float = 0.1f,
        kd: Float = 1.5f,
        integralLimit: Float = 10f,
        maxForce: Float = 1000f,
    ) {
        addForceGenerator(
            index = index,
            name = name,
            generator = generator2 {
                var ix = 0f
                var iy = 0f
                var iz = 0f
                var prevEx = 0f
                var prevEy = 0f
                var prevEz = 0f

                while (true) {
                    val ctx = yield()
                    val dt = ctx.dt
                    if (dt <= 0f) continue

                    val i = ctx.index
                    val sx = ctx.state.x[i]
                    val sy = ctx.state.y[i]
                    val sz = ctx.state.z[i]

                    val ex = targetX() - sx
                    val ey = targetY() - sy
                    val ez = targetZ() - sz

                    ix += ex * dt
                    iy += ey * dt
                    iz += ez * dt

                    if (integralLimit.isFinite()) {
                        if (ix > integralLimit) ix = integralLimit else if (ix < -integralLimit) ix = -integralLimit
                        if (iy > integralLimit) iy = integralLimit else if (iy < -integralLimit) iy = -integralLimit
                        if (iz > integralLimit) iz = integralLimit else if (iz < -integralLimit) iz = -integralLimit
                    }

                    val dx = (ex - prevEx) / dt
                    val dy = (ey - prevEy) / dt
                    val dz = (ez - prevEz) / dt
                    prevEx = ex
                    prevEy = ey
                    prevEz = ez

                    var fx = kp * ex + ki * ix + kd * dx
                    var fy = kp * ey + ki * iy + kd * dy
                    var fz = kp * ez + ki * iz + kd * dz

                    if (maxForce.isFinite()) {
                        if (fx > maxForce) fx = maxForce else if (fx < -maxForce) fx = -maxForce
                        if (fy > maxForce) fy = maxForce else if (fy < -maxForce) fy = -maxForce
                        if (fz > maxForce) fz = maxForce else if (fz < -maxForce) fz = -maxForce
                    }

                    ctx.state.ax[i] += fx
                    ctx.state.ay[i] += fy
                    ctx.state.az[i] += fz
                }
            }
        )
    }

    fun resize(newSize: Int, zeroNew: Boolean = true) {
        state.resize(newSize, zeroNew)
    }

    fun queueRemove(index: Int) {
        state.queueRemove(index)
    }

    fun queueRemoveRange(start: Int, count: Int) {
        state.queueRemoveRange(start, count)
    }

    fun applyQueuedRemovalsSwap() {
        val c4 = constraints4
        if (c4 != null) {
            state.applyQueuedRemovalsSwap(c4)
        } else {
            state.applyQueuedRemovalsSwap(constraints)
        }
    }

    /**
     * Advances the simulation one frame. The force accumulators (ax/ay/az) are treated as
     * scratch: they are **zeroed at the start of every step**, then rebuilt from gravity, the
     * per-particle force generators, [accumulateForces], and the constraint solve, and finally
     * consumed by integration. So anything written to ax/ay/az *before* calling step() is
     * discarded — inject per-frame forces through a force generator (per particle) or through
     * [accumulateForces] (whole-range, e.g. the bulk SIMD kernels), never by pre-accumulating.
     *
     * @param accumulateForces optional bulk force pass invoked as `(state, dt)` after gravity and
     *   the per-particle generators but before the constraint solve, so its contribution feeds the
     *   solve and integration. This is the hook for the generated bulk kernels, e.g.
     *   `sim.step(dt, damping, g, iters) { s, _ -> SimdVerlet.addSpringForceToTarget3(s, tx, ty, tz, k, sx, sy, sz) }`.
     *   Stack several bulk kernels here to combine force fields in one frame.
     */
    fun step(
        dt: Float,
        damping: Float,
        gravity: Float,
        iterations: Int,
        constraints: DirectedConstraints3? = this.constraints,
        accumulateForces: ((state: State3, dt: Float) -> Unit)? = null
    ) {
        state.applyQueuedRemovalsSwap(constraints)
        zero3(state)

        if (gravity != 0f) {
            val ay = state.ay
            val n = state.size
            for (i in 0 until n) {
                ay[i] += gravity
            }
        }

        val forceGenerators = state.forceGenerators
        val forceContexts = state.forceContexts
        for (i in 0 until state.size) {
            val ctx = forceContexts[i]
            ctx.index = i
            ctx.dt = dt
            forceGenerators[i].updateNoCollect(ctx)
        }

        for (force in bulkForces.values) {
            force(state, dt)
        }

        accumulateForces?.invoke(state, dt)

        if (constraints != null && iterations > 0) {
            solveDirectedDist3InPlace(
                state = state,
                constraints = constraints,
                iterations = iterations
            )
        }

        integrateVerlet3(state, dt = dt, damping = damping)
    }

    /** 4-slot-constraint variant of [step]; see that overload for the force-accumulation contract. */
    fun step(
        dt: Float,
        damping: Float,
        gravity: Float,
        iterations: Int,
        constraints: DirectedConstraints4?,
        accumulateForces: ((state: State3, dt: Float) -> Unit)? = null
    ) {
        state.applyQueuedRemovalsSwap(constraints)
        zero3(state)

        if (gravity != 0f) {
            val ay = state.ay
            val n = state.size
            for (i in 0 until n) {
                ay[i] += gravity
            }
        }

        val forceGenerators = state.forceGenerators
        val forceContexts = state.forceContexts
        for (i in 0 until state.size) {
            val ctx = forceContexts[i]
            ctx.index = i
            ctx.dt = dt
            forceGenerators[i].updateNoCollect(ctx)
        }

        for (force in bulkForces.values) {
            force(state, dt)
        }

        accumulateForces?.invoke(state, dt)

        if (constraints != null && iterations > 0) {
            solveDirectedDist4InPlace(
                state = state,
                constraints = constraints,
                iterations = iterations
            )
        }

        integrateVerlet3(state, dt = dt, damping = damping)
    }

    /**
     * Particle state in SoA form. `invMass` is optional.
     * If present, integration treats ax/ay/az as forces and scales by invMass.
     * If absent, ax/ay/az are treated as accelerations.
     *
     * `size` is the active particle count; `limit` is the backing array capacity.
     */
    class State3(
        var x: FloatArray,
        var y: FloatArray,
        var z: FloatArray,
        var px: FloatArray,
        var py: FloatArray,
        var pz: FloatArray,
        var ax: FloatArray,
        var ay: FloatArray,
        var az: FloatArray,
        var invMass: FloatArray? = null
    ) {
        var size: Int = x.size
            private set

        var limit: Int = x.size
            private set

        private var pendingRemovals = IntArray(16)
        private var pendingRemovalCount = 0
        internal var forceGenerators: Array<MapOfGenerators<ForceContext, Unit>> =
            Array(limit) { MapOfGenerators() }
        internal var forceContexts: Array<ForceContext>

        init {
            require(y.size == size && z.size == size) { "x/y/z size mismatch" }
            require(px.size == size && py.size == size && pz.size == size) { "prev position size mismatch" }
            require(ax.size == size && ay.size == size && az.size == size) { "accumulator size mismatch" }
            require(invMass == null || invMass!!.size == size) { "invMass size mismatch" }
            forceContexts = Array(limit) { ForceContext(this, it, 0f) }
        }

        fun ensureCapacity(minCapacity: Int) {
            if (minCapacity <= limit) return

            val grown = limit + max(1, limit / 2)
            val newLimit = max(minCapacity, grown)

            fun grow(src: FloatArray): FloatArray {
                val dst = FloatArray(newLimit)
                System.arraycopy(src, 0, dst, 0, size)
                return dst
            }

            x = grow(x)
            y = grow(y)
            z = grow(z)
            px = grow(px)
            py = grow(py)
            pz = grow(pz)
            ax = grow(ax)
            ay = grow(ay)
            az = grow(az)
            invMass = invMass?.let { grow(it) }
            if (forceGenerators.size < newLimit) {
                val next = arrayOfNulls<MapOfGenerators<ForceContext, Unit>>(newLimit)
                System.arraycopy(forceGenerators, 0, next, 0, forceGenerators.size)
                for (i in forceGenerators.size until newLimit) {
                    next[i] = MapOfGenerators()
                }
                @Suppress("UNCHECKED_CAST")
                val updated = next as Array<MapOfGenerators<ForceContext, Unit>>
                forceGenerators = updated
            }
            if (forceContexts.size < newLimit) {
                val next = arrayOfNulls<ForceContext>(newLimit)
                System.arraycopy(forceContexts, 0, next, 0, forceContexts.size)
                for (i in forceContexts.size until newLimit) {
                    next[i] = ForceContext(this, i, 0f)
                }
                @Suppress("UNCHECKED_CAST")
                val updated = next as Array<ForceContext>
                forceContexts = updated
            }
            limit = newLimit
        }

        fun resize(newSize: Int, zeroNew: Boolean = true) {
            require(newSize >= 0) { "size must be >= 0" }
            if (newSize > limit) {
                ensureCapacity(newSize)
            }

            if (zeroNew && newSize > size) {
                for (i in size until newSize) {
                    x[i] = 0f
                    y[i] = 0f
                    z[i] = 0f
                    px[i] = 0f
                    py[i] = 0f
                    pz[i] = 0f
                    ax[i] = 0f
                    ay[i] = 0f
                    az[i] = 0f
                    invMass?.let { it[i] = 1f }
                }
            }

            size = newSize
        }

        fun addZeroed(count: Int = 1): Int {
            require(count >= 0) { "count must be >= 0" }
            if (count == 0) return size
            val start = size
            resize(size + count, zeroNew = true)
            return start
        }

        fun addParticle(
            x0: Float = 0f,
            y0: Float = 0f,
            z0: Float = 0f,
            px0: Float = x0,
            py0: Float = y0,
            pz0: Float = z0,
            ax0: Float = 0f,
            ay0: Float = 0f,
            az0: Float = 0f,
            invMass0: Float = 1f
        ): Int {
            val index = addZeroed(1)
            x[index] = x0
            y[index] = y0
            z[index] = z0
            px[index] = px0
            py[index] = py0
            pz[index] = pz0
            ax[index] = ax0
            ay[index] = ay0
            az[index] = az0
            invMass?.let { it[index] = invMass0 }
            return index
        }

        fun removeAt(index: Int, count: Int = 1, preserveOrder: Boolean = true) {
            require(index >= 0 && count >= 0 && index + count <= size) {
                "remove range out of bounds"
            }
            if (count == 0) return

            if (preserveOrder) {
                val tailStart = index + count
                val tailCount = size - tailStart
                if (tailCount > 0) {
                    System.arraycopy(x, tailStart, x, index, tailCount)
                    System.arraycopy(y, tailStart, y, index, tailCount)
                    System.arraycopy(z, tailStart, z, index, tailCount)
                    System.arraycopy(px, tailStart, px, index, tailCount)
                    System.arraycopy(py, tailStart, py, index, tailCount)
                    System.arraycopy(pz, tailStart, pz, index, tailCount)
                    System.arraycopy(ax, tailStart, ax, index, tailCount)
                    System.arraycopy(ay, tailStart, ay, index, tailCount)
                    System.arraycopy(az, tailStart, az, index, tailCount)
                    System.arraycopy(forceGenerators, tailStart, forceGenerators, index, tailCount)
                    invMass?.let { System.arraycopy(it, tailStart, it, index, tailCount) }
                }
            } else {
                var dst = index
                var src = size - 1
                var remaining = count
                while (remaining > 0 && dst <= src) {
                    if (dst != src) {
                        x[dst] = x[src]
                        y[dst] = y[src]
                        z[dst] = z[src]
                        px[dst] = px[src]
                        py[dst] = py[src]
                        pz[dst] = pz[src]
                        ax[dst] = ax[src]
                        ay[dst] = ay[src]
                        az[dst] = az[src]
                        forceGenerators[dst] = forceGenerators[src]
                        invMass?.let { it[dst] = it[src] }
                    }
                    dst++
                    src--
                    remaining--
                }
            }

            size -= count
        }

        fun queueRemove(index: Int) {
            require(index in 0 until size) { "remove index out of bounds" }
            ensureRemovalCapacity(pendingRemovalCount + 1)
            pendingRemovals[pendingRemovalCount] = index
            pendingRemovalCount++
        }

        fun queueRemoveRange(start: Int, count: Int) {
            require(count >= 0) { "count must be >= 0" }
            require(start >= 0 && start + count <= size) { "remove range out of bounds" }
            if (count == 0) return
            ensureRemovalCapacity(pendingRemovalCount + count)
            for (i in 0 until count) {
                pendingRemovals[pendingRemovalCount + i] = start + i
            }
            pendingRemovalCount += count
        }

        fun applyQueuedRemovalsSwap(constraints: DirectedConstraints3? = null) {
            if (pendingRemovalCount == 0) return

            val oldSize = size
            val deleted = BooleanArray(oldSize)
            for (i in 0 until pendingRemovalCount) {
                val idx = pendingRemovals[i]
                if (idx in 0 until oldSize) {
                    deleted[idx] = true
                }
            }

            val indexMap = IntArray(oldSize) { it }
            var last = oldSize - 1
            var i = 0
            while (i <= last) {
                if (!deleted[i]) {
                    i++
                    continue
                }

                while (last >= i && deleted[last]) {
                    last--
                }
                if (last < i) {
                    break
                }

                swapParticles(i, last)
                constraints?.swapConstraints(i, last)
                val tmp = indexMap[i]
                indexMap[i] = indexMap[last]
                indexMap[last] = tmp
                deleted[i] = false
                last--
                i++
            }

            while (last >= 0 && deleted[last]) {
                last--
            }

            val newSize = last + 1
            val remap = IntArray(oldSize) { -1 }
            for (pos in 0 until newSize) {
                remap[indexMap[pos]] = pos
            }

            constraints?.remapNeighbors(remap, newSize)
            size = newSize
            pendingRemovalCount = 0
        }

        fun applyQueuedRemovalsSwap(constraints: DirectedConstraints4? = null) {
            if (pendingRemovalCount == 0) return

            val oldSize = size
            val deleted = BooleanArray(oldSize)
            for (i in 0 until pendingRemovalCount) {
                val idx = pendingRemovals[i]
                if (idx in 0 until oldSize) {
                    deleted[idx] = true
                }
            }

            val indexMap = IntArray(oldSize) { it }
            var last = oldSize - 1
            var i = 0
            while (i <= last) {
                if (!deleted[i]) {
                    i++
                    continue
                }

                while (last >= i && deleted[last]) {
                    last--
                }
                if (last < i) {
                    break
                }

                swapParticles(i, last)
                constraints?.swapConstraints(i, last)
                val tmp = indexMap[i]
                indexMap[i] = indexMap[last]
                indexMap[last] = tmp
                deleted[i] = false
                last--
                i++
            }

            while (last >= 0 && deleted[last]) {
                last--
            }

            val newSize = last + 1
            val remap = IntArray(oldSize) { -1 }
            for (pos in 0 until newSize) {
                remap[indexMap[pos]] = pos
            }

            constraints?.remapNeighbors(remap, newSize)
            size = newSize
            pendingRemovalCount = 0
        }

        private fun ensureRemovalCapacity(minCapacity: Int) {
            if (minCapacity <= pendingRemovals.size) return
            var newCap = pendingRemovals.size
            while (newCap < minCapacity) {
                newCap = max(1, newCap * 2)
            }
            val next = IntArray(newCap)
            System.arraycopy(pendingRemovals, 0, next, 0, pendingRemovalCount)
            pendingRemovals = next
        }

        private fun swapParticles(i: Int, j: Int) {
            if (i == j) return
            swap(x, i, j)
            swap(y, i, j)
            swap(z, i, j)
            swap(px, i, j)
            swap(py, i, j)
            swap(pz, i, j)
            swap(ax, i, j)
            swap(ay, i, j)
            swap(az, i, j)
            swap(forceGenerators, i, j)
            invMass?.let { swap(it, i, j) }
        }
    }

    /**
     * Directed 3-slot constraint layout:
     *   i -> n0[i], i -> n1[i], i -> n2[i]
      * Unused slot: neighbor = -1 or stiffness = 0.
     */
    class DirectedConstraints3(
        val n0: IntArray,
        val n1: IntArray,
        val n2: IntArray,
        val r0: FloatArray,
        val r1: FloatArray,
        val r2: FloatArray,
        val s0: FloatArray,
        val s1: FloatArray,
        val s2: FloatArray
    ) {
        val size: Int = n0.size

        init {
            require(n1.size == size && n2.size == size) { "neighbor slot size mismatch" }
            require(r0.size == size && r1.size == size && r2.size == size) { "rest-length slot size mismatch" }
            require(s0.size == size && s1.size == size && s2.size == size) { "stiffness slot size mismatch" }
        }

        internal fun swapConstraints(i: Int, j: Int) {
            if (i == j) return
            swap(n0, i, j)
            swap(n1, i, j)
            swap(n2, i, j)
            swap(r0, i, j)
            swap(r1, i, j)
            swap(r2, i, j)
            swap(s0, i, j)
            swap(s1, i, j)
            swap(s2, i, j)
        }

        internal fun remapNeighbors(remap: IntArray, newSize: Int) {
            for (i in 0 until newSize) {
                val j0 = n0[i]
                n0[i] = if (j0 in remap.indices) remap[j0] else -1
                val j1 = n1[i]
                n1[i] = if (j1 in remap.indices) remap[j1] else -1
                val j2 = n2[i]
                n2[i] = if (j2 in remap.indices) remap[j2] else -1
            }
        }

        fun setSlot(
            index: Int,
            slot: Int,
            neighbor: Int,
            restLength: Float,
            stiffness: Float
        ) {
            require(index in 0 until size) { "index out of bounds" }
            when (slot) {
                0 -> {
                    n0[index] = neighbor
                    r0[index] = restLength
                    s0[index] = stiffness
                }
                1 -> {
                    n1[index] = neighbor
                    r1[index] = restLength
                    s1[index] = stiffness
                }
                2 -> {
                    n2[index] = neighbor
                    r2[index] = restLength
                    s2[index] = stiffness
                }
                else -> error("slot must be 0, 1, or 2")
            }
        }
    }

    /**
     * Directed distance constraints with up to 4 neighbor slots per particle
     * (the 4-slot sibling of [DirectedConstraints3]). Each particle `i` owns
     * outgoing constraints to `n0[i], n1[i], n2[i], n3[i]`; an unused slot is
     * marked with `neighbor = -1` or `stiffness = 0`. Slot arrays are parallel:
     * `n*` are neighbor indices, `r*` rest lengths, `s*` stiffnesses, all of
     * length [size]. The 4th slot is what lets a regular cloth grid attach all
     * four of its axis neighbors (up/down/left/right) to a single particle.
     *
     * API surface:
     * - [setSlot] (index, slot 0..3, neighbor, restLength, stiffness) — populate one slot.
     * - [clearSlots] (index) — return one particle to having no outgoing constraints.
     * - [ofCapacity] / [ensureCapacity] — allocate, and grow in place as the state grows.
     * - `swapConstraints` / `remapNeighbors` (internal) — keep the swap-remove
     *   compaction in [State.applyQueuedRemovalsSwap] consistent when particles
     *   are deleted.
     *
     * Solved by [solveDirectedDist4InPlace] / [solveDirectedDist4InPlaceRange]
     * (JDK Incubator Vector API): neighbor positions are gathered through the
     * per-slot maps in [ConstraintScratch] (`map0`..`map3`), distances use SIMD
     * sqrt, and a scalar remainder-lane fallback finishes the tail. The solver is
     * range-parallel safe because each particle only accumulates onto its own
     * position — there are no scatter writes to neighbors — so non-overlapping
     * particle ranges can be solved on separate threads with one scratch each.
     *
     * The reusable consumer is [Cloth], which wires a whole grid patch of these slots
     * into an existing simulation (optional curvature flattening force, interactive
     * grabbing via PID position controllers). Runnable usage:
     * `afield.examples.ClothSimulationExample` (a thin demo over [Cloth]),
     * `afield.examples.TestDirectedConstraints4`, and
     * `afield.examples.SimdVerletTinyExample` (the 3-slot warmup).
     */
    class DirectedConstraints4(
        var n0: IntArray,
        var n1: IntArray,
        var n2: IntArray,
        var n3: IntArray,
        var r0: FloatArray,
        var r1: FloatArray,
        var r2: FloatArray,
        var r3: FloatArray,
        var s0: FloatArray,
        var s1: FloatArray,
        var s2: FloatArray,
        var s3: FloatArray
    ) {
        var size: Int = n0.size
            private set

        init {
            require(n1.size == size && n2.size == size && n3.size == size) { "neighbor slot size mismatch" }
            require(r0.size == size && r1.size == size && r2.size == size && r3.size == size) { "rest-length slot size mismatch" }
            require(s0.size == size && s1.size == size && s2.size == size && s3.size == size) { "stiffness slot size mismatch" }
        }

        internal fun swapConstraints(i: Int, j: Int) {
            if (i == j) return
            swap(n0, i, j)
            swap(n1, i, j)
            swap(n2, i, j)
            swap(n3, i, j)
            swap(r0, i, j)
            swap(r1, i, j)
            swap(r2, i, j)
            swap(r3, i, j)
            swap(s0, i, j)
            swap(s1, i, j)
            swap(s2, i, j)
            swap(s3, i, j)
        }

        internal fun remapNeighbors(remap: IntArray, newSize: Int) {
            for (i in 0 until newSize) {
                val j0 = n0[i]
                n0[i] = if (j0 in remap.indices) remap[j0] else -1
                val j1 = n1[i]
                n1[i] = if (j1 in remap.indices) remap[j1] else -1
                val j2 = n2[i]
                n2[i] = if (j2 in remap.indices) remap[j2] else -1
                val j3 = n3[i]
                n3[i] = if (j3 in remap.indices) remap[j3] else -1
            }
        }

        fun setSlot(
            index: Int,
            slot: Int,
            neighbor: Int,
            restLength: Float,
            stiffness: Float
        ) {
            require(index in 0 until size) { "index out of bounds" }
            when (slot) {
                0 -> {
                    n0[index] = neighbor
                    r0[index] = restLength
                    s0[index] = stiffness
                }
                1 -> {
                    n1[index] = neighbor
                    r1[index] = restLength
                    s1[index] = stiffness
                }
                2 -> {
                    n2[index] = neighbor
                    r2[index] = restLength
                    s2[index] = stiffness
                }
                3 -> {
                    n3[index] = neighbor
                    r3[index] = restLength
                    s3[index] = stiffness
                }
                else -> error("slot must be 0, 1, 2, or 3")
            }
        }

        /** Clears every slot of particle [index] back to unused (neighbor -1, zero stiffness). */
        fun clearSlots(index: Int) {
            require(index in 0 until size) { "index out of bounds" }
            for (slot in 0 until 4) {
                setSlot(index, slot, -1, 0f, 0f)
            }
        }

        /**
         * Grows every slot array so it covers at least [minCapacity] particles, which is what
         * [solveDirectedDist4InPlace] requires of a constraint set stepped alongside a growing
         * [State3] (it solves `0 until state.size` and indexes these arrays by particle index).
         *
         * Existing entries and *this object's identity* are preserved -- the arrays are `var` so
         * that everyone already holding this constraint set (a [SimdVerlet.constraints4], several
         * [Cloth]es sharing one set) keeps seeing the grown arrays. New entries are the unused
         * slot: neighbor -1, zero rest length, zero stiffness.
         */
        fun ensureCapacity(minCapacity: Int) {
            if (minCapacity <= size) return
            val newSize = max(minCapacity, size + max(1, size / 2))
            val oldSize = size

            fun growNeighbors(src: IntArray): IntArray {
                val dst = IntArray(newSize) { -1 }
                System.arraycopy(src, 0, dst, 0, oldSize)
                return dst
            }

            fun growFloats(src: FloatArray): FloatArray {
                val dst = FloatArray(newSize)
                System.arraycopy(src, 0, dst, 0, oldSize)
                return dst
            }

            n0 = growNeighbors(n0)
            n1 = growNeighbors(n1)
            n2 = growNeighbors(n2)
            n3 = growNeighbors(n3)
            r0 = growFloats(r0)
            r1 = growFloats(r1)
            r2 = growFloats(r2)
            r3 = growFloats(r3)
            s0 = growFloats(s0)
            s1 = growFloats(s1)
            s2 = growFloats(s2)
            s3 = growFloats(s3)
            size = newSize
        }

        companion object {
            /** An all-slots-unused constraint set covering [capacity] particles. */
            fun ofCapacity(capacity: Int): DirectedConstraints4 {
                require(capacity >= 0) { "capacity must be >= 0" }
                return DirectedConstraints4(
                    n0 = IntArray(capacity) { -1 },
                    n1 = IntArray(capacity) { -1 },
                    n2 = IntArray(capacity) { -1 },
                    n3 = IntArray(capacity) { -1 },
                    r0 = FloatArray(capacity),
                    r1 = FloatArray(capacity),
                    r2 = FloatArray(capacity),
                    r3 = FloatArray(capacity),
                    s0 = FloatArray(capacity),
                    s1 = FloatArray(capacity),
                    s2 = FloatArray(capacity),
                    s3 = FloatArray(capacity)
                )
            }
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
        internal val map3 = IntArray(species.length())
    }

    companion object {
        // Best default for contiguous, non-gather passes.
        private val SP: VectorSpecies<Float> = FloatVector.SPECIES_PREFERRED

        // Gather-heavy constraints are often better with fixed 128-bit vectors.
        private val SG: VectorSpecies<Float> = FloatVector.SPECIES_128

        private val scratchTLS = ThreadLocal.withInitial { ConstraintScratch(SG) }

        fun createConstraintScratch(): ConstraintScratch = ConstraintScratch(SG)

    // ----------------------------
    // 1) Zero accumulators (SIMD)
    // ----------------------------
    fun zero3(state: State3) = zero3Range(state.ax, state.ay, state.az, 0, state.size)

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
        integrateVerlet3Range(
            x = state.x, y = state.y, z = state.z,
            px = state.px, py = state.py, pz = state.pz,
            ax = state.ax, ay = state.ay, az = state.az,
            start = 0,
            endExclusive = state.size,
            dt = dt,
            damping = damping,
            invMass = state.invMass
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
    // 3) Directed 3-slot distance constraints (SIMD, force accumulation)
    // Each i has up to 3 outgoing constraints to neighbor j.
    // We update only i's accumulators (no scatter), so range-parallel calls are safe.
    // -----------------------------------------------------------
    fun solveDirectedDist3InPlace(
        state: State3,
        constraints: DirectedConstraints3,
        iterations: Int,
        scratch: ConstraintScratch = scratchTLS.get()
    ) {
        solveDirectedDist3InPlaceRange(
            x = state.x, y = state.y, z = state.z,
            ax = state.ax, ay = state.ay, az = state.az,
            n0 = constraints.n0, n1 = constraints.n1, n2 = constraints.n2,
            r0 = constraints.r0, r1 = constraints.r1, r2 = constraints.r2,
            s0 = constraints.s0, s1 = constraints.s1, s2 = constraints.s2,
            start = 0,
            endExclusive = state.size,
            iterations = iterations,
            scratch = scratch
        )
    }

    fun solveDirectedDist3InPlace(
        x: FloatArray, y: FloatArray, z: FloatArray,
        ax: FloatArray, ay: FloatArray, az: FloatArray,
        n0: IntArray, n1: IntArray, n2: IntArray,
        r0: FloatArray, r1: FloatArray, r2: FloatArray,
        s0: FloatArray, s1: FloatArray, s2: FloatArray,
        iterations: Int,
        scratch: ConstraintScratch = scratchTLS.get()
    ) {
        solveDirectedDist3InPlaceRange(
            x, y, z,
            ax, ay, az,
            n0, n1, n2,
            r0, r1, r2,
            s0, s1, s2,
            start = 0,
            endExclusive = x.size,
            iterations = iterations,
            scratch = scratch
        )
    }

    fun solveDirectedDist3InPlaceRange(
        x: FloatArray, y: FloatArray, z: FloatArray,
        ax: FloatArray, ay: FloatArray, az: FloatArray,
        n0: IntArray, n1: IntArray, n2: IntArray,
        r0: FloatArray, r1: FloatArray, r2: FloatArray,
        s0: FloatArray, s1: FloatArray, s2: FloatArray,
        start: Int,
        endExclusive: Int,
        iterations: Int,
        scratch: ConstraintScratch = scratchTLS.get()
    ) {
        require(iterations >= 0) { "iterations must be >= 0" }
        if (iterations == 0) return

        val n = x.size
        require(y.size == n && z.size == n) { "position size mismatch" }
        require(ax.size == n && ay.size == n && az.size == n) { "accumulator size mismatch" }
        require(endExclusive <= n) { "endExclusive exceeds position size" }
        require(n0.size >= endExclusive && n1.size >= endExclusive && n2.size >= endExclusive) {
            "neighbor size mismatch"
        }
        require(r0.size >= endExclusive && r1.size >= endExclusive && r2.size >= endExclusive) {
            "rest size mismatch"
        }
        require(s0.size >= endExclusive && s1.size >= endExclusive && s2.size >= endExclusive) {
            "stiffness size mismatch"
        }

        val startIdx = start.coerceIn(0, endExclusive)
        val endIdx = endExclusive.coerceIn(startIdx, endExclusive)

        val lanes = SG.length()
        val zero = FloatVector.zero(SG)
        val eps = 1.0e-8f
        val vEps = FloatVector.broadcast(SG, eps)
        val vOne = FloatVector.broadcast(SG, 1f)

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
                    val stiff = FloatVector.fromArray(SG, s0, i)
                    val invDist = vOne.div(dist.add(vEps))
                    val s = dist.sub(rest).mul(invDist).mul(stiff)

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
                    val stiff = FloatVector.fromArray(SG, s1, i)
                    val invDist = vOne.div(dist.add(vEps))
                    val s = dist.sub(rest).mul(invDist).mul(stiff)

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
                    val stiff = FloatVector.fromArray(SG, s2, i)
                    val invDist = vOne.div(dist.add(vEps))
                    val s = dist.sub(rest).mul(invDist).mul(stiff)

                    cx = cx.add(dx.mul(s).blend(zero, m2.not()))
                    cy = cy.add(dy.mul(s).blend(zero, m2.not()))
                    cz = cz.add(dz.mul(s).blend(zero, m2.not()))
                }

                val vax = FloatVector.fromArray(SG, ax, i).add(cx)
                val vay = FloatVector.fromArray(SG, ay, i).add(cy)
                val vaz = FloatVector.fromArray(SG, az, i).add(cz)

                vax.intoArray(ax, i)
                vay.intoArray(ay, i)
                vaz.intoArray(az, i)

                i += lanes
            }

            while (i < endIdx) {
                val xiS = x[i]
                val yiS = y[i]
                val ziS = z[i]

                var fx = 0f
                var fy = 0f
                var fz = 0f

                val j0 = n0[i]
                if (j0 in 0 until n) {
                    val w = s0[i]
                    if (w != 0f) {
                        val dx = x[j0] - xiS
                        val dy = y[j0] - yiS
                        val dz = z[j0] - ziS
                        val dist = sqrt(dx * dx + dy * dy + dz * dz + eps)
                        val s = (dist - r0[i]) / dist * w
                        fx += dx * s
                        fy += dy * s
                        fz += dz * s
                    }
                }

                val j1 = n1[i]
                if (j1 in 0 until n) {
                    val w = s1[i]
                    if (w != 0f) {
                        val dx = x[j1] - xiS
                        val dy = y[j1] - yiS
                        val dz = z[j1] - ziS
                        val dist = sqrt(dx * dx + dy * dy + dz * dz + eps)
                        val s = (dist - r1[i]) / dist * w
                        fx += dx * s
                        fy += dy * s
                        fz += dz * s
                    }
                }

                val j2 = n2[i]
                if (j2 in 0 until n) {
                    val w = s2[i]
                    if (w != 0f) {
                        val dx = x[j2] - xiS
                        val dy = y[j2] - yiS
                        val dz = z[j2] - ziS
                        val dist = sqrt(dx * dx + dy * dy + dz * dz + eps)
                        val s = (dist - r2[i]) / dist * w
                        fx += dx * s
                        fy += dy * s
                        fz += dz * s
                    }
                }

                ax[i] += fx
                ay[i] += fy
                az[i] += fz
                i++
            }
        }
    }

    // -----------------------------------------------------------
    // 3b) Directed 4-slot distance constraints (SIMD, force accumulation)
    // Each i has up to 4 outgoing constraints to neighbor j.
    // We update only i's accumulators (no scatter), so range-parallel calls are safe.
    // -----------------------------------------------------------
    fun solveDirectedDist4InPlace(
        state: State3,
        constraints: DirectedConstraints4,
        iterations: Int,
        scratch: ConstraintScratch = scratchTLS.get()
    ) {
        solveDirectedDist4InPlaceRange(
            x = state.x, y = state.y, z = state.z,
            ax = state.ax, ay = state.ay, az = state.az,
            n0 = constraints.n0, n1 = constraints.n1, n2 = constraints.n2, n3 = constraints.n3,
            r0 = constraints.r0, r1 = constraints.r1, r2 = constraints.r2, r3 = constraints.r3,
            s0 = constraints.s0, s1 = constraints.s1, s2 = constraints.s2, s3 = constraints.s3,
            start = 0,
            endExclusive = state.size,
            iterations = iterations,
            scratch = scratch
        )
    }

    fun solveDirectedDist4InPlace(
        x: FloatArray, y: FloatArray, z: FloatArray,
        ax: FloatArray, ay: FloatArray, az: FloatArray,
        n0: IntArray, n1: IntArray, n2: IntArray, n3: IntArray,
        r0: FloatArray, r1: FloatArray, r2: FloatArray, r3: FloatArray,
        s0: FloatArray, s1: FloatArray, s2: FloatArray, s3: FloatArray,
        iterations: Int,
        scratch: ConstraintScratch = scratchTLS.get()
    ) {
        solveDirectedDist4InPlaceRange(
            x, y, z,
            ax, ay, az,
            n0, n1, n2, n3,
            r0, r1, r2, r3,
            s0, s1, s2, s3,
            start = 0,
            endExclusive = x.size,
            iterations = iterations,
            scratch = scratch
        )
    }

    fun solveDirectedDist4InPlaceRange(
        x: FloatArray, y: FloatArray, z: FloatArray,
        ax: FloatArray, ay: FloatArray, az: FloatArray,
        n0: IntArray, n1: IntArray, n2: IntArray, n3: IntArray,
        r0: FloatArray, r1: FloatArray, r2: FloatArray, r3: FloatArray,
        s0: FloatArray, s1: FloatArray, s2: FloatArray, s3: FloatArray,
        start: Int,
        endExclusive: Int,
        iterations: Int,
        scratch: ConstraintScratch = scratchTLS.get()
    ) {
        require(iterations >= 0) { "iterations must be >= 0" }
        if (iterations == 0) return

        val n = x.size
        require(y.size == n && z.size == n) { "position size mismatch" }
        require(ax.size == n && ay.size == n && az.size == n) { "accumulator size mismatch" }
        require(endExclusive <= n) { "endExclusive exceeds position size" }
        require(n0.size >= endExclusive && n1.size >= endExclusive && n2.size >= endExclusive && n3.size >= endExclusive) {
            "neighbor size mismatch"
        }
        require(r0.size >= endExclusive && r1.size >= endExclusive && r2.size >= endExclusive && r3.size >= endExclusive) {
            "rest size mismatch"
        }
        require(s0.size >= endExclusive && s1.size >= endExclusive && s2.size >= endExclusive && s3.size >= endExclusive) {
            "stiffness size mismatch"
        }

        val startIdx = start.coerceIn(0, endExclusive)
        val endIdx = endExclusive.coerceIn(startIdx, endExclusive)

        val lanes = SG.length()
        val zero = FloatVector.zero(SG)
        val eps = 1.0e-8f
        val vEps = FloatVector.broadcast(SG, eps)
        val vOne = FloatVector.broadcast(SG, 1f)

        val map0 = scratch.map0
        val map1 = scratch.map1
        val map2 = scratch.map2
        val map3 = scratch.map3
        require(map0.size == lanes && map1.size == lanes && map2.size == lanes && map3.size == lanes) {
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
                var bits3 = 0L

                for (lane in 0 until lanes) {
                    val idx = i + lane
                    val j0 = n0[idx]
                    val j1 = n1[idx]
                    val j2 = n2[idx]
                    val j3 = n3[idx]

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
                    if (j3 in 0 until n) {
                        bits3 = bits3 or (1L shl lane)
                        map3[lane] = j3
                    } else {
                        map3[lane] = 0
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
                    val stiff = FloatVector.fromArray(SG, s0, i)
                    val invDist = vOne.div(dist.add(vEps))
                    val s = dist.sub(rest).mul(invDist).mul(stiff)

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
                    val stiff = FloatVector.fromArray(SG, s1, i)
                    val invDist = vOne.div(dist.add(vEps))
                    val s = dist.sub(rest).mul(invDist).mul(stiff)

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
                    val stiff = FloatVector.fromArray(SG, s2, i)
                    val invDist = vOne.div(dist.add(vEps))
                    val s = dist.sub(rest).mul(invDist).mul(stiff)

                    cx = cx.add(dx.mul(s).blend(zero, m2.not()))
                    cy = cy.add(dy.mul(s).blend(zero, m2.not()))
                    cz = cz.add(dz.mul(s).blend(zero, m2.not()))
                }

                if (bits3 != 0L) {
                    val m3 = VectorMask.fromLong(SG, bits3)
                    val xj = FloatVector.fromArray(SG, x, 0, map3, 0)
                    val yj = FloatVector.fromArray(SG, y, 0, map3, 0)
                    val zj = FloatVector.fromArray(SG, z, 0, map3, 0)

                    val dx = xj.sub(xi)
                    val dy = yj.sub(yi)
                    val dz = zj.sub(zi)

                    val d2 = dx.fma(dx, dy.fma(dy, dz.mul(dz)))
                    val dist = d2.lanewise(SQRT)

                    val rest = FloatVector.fromArray(SG, r3, i)
                    val stiff = FloatVector.fromArray(SG, s3, i)
                    val invDist = vOne.div(dist.add(vEps))
                    val s = dist.sub(rest).mul(invDist).mul(stiff)

                    cx = cx.add(dx.mul(s).blend(zero, m3.not()))
                    cy = cy.add(dy.mul(s).blend(zero, m3.not()))
                    cz = cz.add(dz.mul(s).blend(zero, m3.not()))
                }

                val vax = FloatVector.fromArray(SG, ax, i).add(cx)
                val vay = FloatVector.fromArray(SG, ay, i).add(cy)
                val vaz = FloatVector.fromArray(SG, az, i).add(cz)

                vax.intoArray(ax, i)
                vay.intoArray(ay, i)
                vaz.intoArray(az, i)

                i += lanes
            }

            while (i < endIdx) {
                val xiS = x[i]
                val yiS = y[i]
                val ziS = z[i]

                var fx = 0f
                var fy = 0f
                var fz = 0f

                val j0 = n0[i]
                if (j0 in 0 until n) {
                    val w = s0[i]
                    if (w != 0f) {
                        val dx = x[j0] - xiS
                        val dy = y[j0] - yiS
                        val dz = z[j0] - ziS
                        val dist = sqrt(dx * dx + dy * dy + dz * dz + eps)
                        val s = (dist - r0[i]) / dist * w
                        fx += dx * s
                        fy += dy * s
                        fz += dz * s
                    }
                }

                val j1 = n1[i]
                if (j1 in 0 until n) {
                    val w = s1[i]
                    if (w != 0f) {
                        val dx = x[j1] - xiS
                        val dy = y[j1] - yiS
                        val dz = z[j1] - ziS
                        val dist = sqrt(dx * dx + dy * dy + dz * dz + eps)
                        val s = (dist - r1[i]) / dist * w
                        fx += dx * s
                        fy += dy * s
                        fz += dz * s
                    }
                }

                val j2 = n2[i]
                if (j2 in 0 until n) {
                    val w = s2[i]
                    if (w != 0f) {
                        val dx = x[j2] - xiS
                        val dy = y[j2] - yiS
                        val dz = z[j2] - ziS
                        val dist = sqrt(dx * dx + dy * dy + dz * dz + eps)
                        val s = (dist - r2[i]) / dist * w
                        fx += dx * s
                        fy += dy * s
                        fz += dz * s
                    }
                }

                val j3 = n3[i]
                if (j3 in 0 until n) {
                    val w = s3[i]
                    if (w != 0f) {
                        val dx = x[j3] - xiS
                        val dy = y[j3] - yiS
                        val dz = z[j3] - ziS
                        val dist = sqrt(dx * dx + dy * dy + dz * dz + eps)
                        val s = (dist - r3[i]) / dist * w
                        fx += dx * s
                        fy += dy * s
                        fz += dz * s
                    }
                }

                ax[i] += fx
                ay[i] += fy
                az[i] += fz
                i++
            }
        }
    }

    // -----------------------------------------------------------
    // 4) Derived kinematics & bulk force helpers
    //
    // These delegate to the auto-generated, correctness-tested bulk kernels in
    // afield.stdlib.linalg.bulk, which operate on exactly the flat SoA FloatArray
    // layout this class already uses. They add capability the hand-tuned kernels
    // above deliberately don't cover, without touching them: integrateVerlet3 stays
    // a single fused pass (a chain of per-op kernel passes would re-stream the same
    // arrays through memory), and the constraint solve needs the gather+masking the
    // gather-free elementwise kernels don't model.
    //
    // Force-space helpers (e.g. addSpringForceToTarget3) accumulate into ax/ay/az, so
    // run them through step()'s accumulateForces hook -- step() zeroes the accumulators
    // at its start, so a force written before step() is discarded. See step()'s KDoc.
    //
    // Note on uniform quantities: the kernels only carry scalar overloads for mul/div,
    // so a broadcast add (uniform gravity, clamp-to-box) isn't expressible here yet
    // without a filler array. That's a clean future generator extension, not a reason
    // to hand-roll it -- the existing scalar gravity loop in step() stays as-is.
    // -----------------------------------------------------------

    /**
     * Recovers per-particle velocity `v = (x - px) / dt` into [vx]/[vy]/[vz] for the
     * whole active range, in two vectorized passes (subtract, then scale by `1/dt`).
     *
     * Verlet stores the previous position rather than an explicit velocity, so callers
     * that want momentum/kinetic-energy/speed-gating had to reconstruct it by hand; this
     * does it over the whole state with the SIMD kernels. The output arrays may alias
     * `px/py/pz` but must be distinct from `x/y/z`, and sized `>= state.size`.
     */
    fun velocity3(state: State3, dt: Float, vx: FloatArray, vy: FloatArray, vz: FloatArray) =
        velocity3Range(state.x, state.y, state.z, state.px, state.py, state.pz, dt, vx, vy, vz, 0, state.size)

    fun velocity3Range(
        x: FloatArray, y: FloatArray, z: FloatArray,
        px: FloatArray, py: FloatArray, pz: FloatArray,
        dt: Float,
        vx: FloatArray, vy: FloatArray, vz: FloatArray,
        start: Int,
        endExclusive: Int
    ) {
        require(dt != 0f) { "dt must be non-zero to recover velocity" }
        subRange(x, y, z, px, py, pz, vx, vy, vz, start, endExclusive)
        mulRange(vx, vy, vz, 1f / dt, vx, vy, vz, start, endExclusive)
    }

    /**
     * Accumulates a per-particle Hookean pull toward a target pose into the force
     * accumulators: `a += stiffness * (target - position)` across the active range,
     * as two vectorized passes (subtract into scratch, then fused-multiply-add).
     *
     * It writes ax/ay/az (force space, not position), so it composes correctly with
     * gravity, force generators, distance constraints and integration inside [step] --
     * unlike moving positions directly, which would silently inject Verlet velocity.
     *
     * [scratchX]/[scratchY]/[scratchZ] are working buffers (sized `>= endExclusive`);
     * keep one triple per worker and reuse it across frames to stay allocation-free.
     */
    fun addSpringForceToTarget3(
        state: State3,
        targetX: FloatArray, targetY: FloatArray, targetZ: FloatArray,
        stiffness: Float,
        scratchX: FloatArray, scratchY: FloatArray, scratchZ: FloatArray
    ) = addSpringForceToTarget3Range(
        state.x, state.y, state.z,
        state.ax, state.ay, state.az,
        targetX, targetY, targetZ,
        stiffness,
        scratchX, scratchY, scratchZ,
        0, state.size
    )

    fun addSpringForceToTarget3Range(
        x: FloatArray, y: FloatArray, z: FloatArray,
        ax: FloatArray, ay: FloatArray, az: FloatArray,
        targetX: FloatArray, targetY: FloatArray, targetZ: FloatArray,
        stiffness: Float,
        scratchX: FloatArray, scratchY: FloatArray, scratchZ: FloatArray,
        start: Int,
        endExclusive: Int
    ) {
        subRange(targetX, targetY, targetZ, x, y, z, scratchX, scratchY, scratchZ, start, endExclusive)
        fmaRange(ax, ay, az, stiffness, scratchX, scratchY, scratchZ, ax, ay, az, start, endExclusive)
    }

        // ---------------------------------------------------------
        // 5) Parallel range execution with coarse chunking
        // ---------------------------------------------------------
    }

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

private fun swap(a: FloatArray, i: Int, j: Int) {
    val tmp = a[i]
    a[i] = a[j]
    a[j] = tmp
}

private fun swap(a: IntArray, i: Int, j: Int) {
    val tmp = a[i]
    a[i] = a[j]
    a[j] = tmp
}

private fun swap(a: Array<MapOfGenerators<SimdVerlet.ForceContext, Unit>>, i: Int, j: Int) {
    val tmp = a[i]
    a[i] = a[j]
    a[j] = tmp
}
