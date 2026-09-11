package afield.four.physics

import afield.graphics.FLine
import afield.stdlib.Vec3
import afield.stdlib.linalg.Vector3fc
import afield.stdlib.vec
import kotlin.math.sqrt

/**
 * A rectangular cloth patch on a [SimdVerlet] simulation.
 *
 * [Cloth] manages a `width * height` grid of particles wired into axis-aligned distance
 * constraints, with an optional curvature-flattening pass and tearing support.
 * Create one with [SimdVerlet.addCloth].
 */

/**
 * A rectangular cloth patch living inside somebody else's [SimdVerlet].
 *
 * [Cloth.addTo] (or `sim.addCloth(...)`) appends `width * height` particles to a simulation
 * that may already contain other particles, wires them into a [SimdVerlet.DirectedConstraints4]
 * grid — every interior particle spends all four of its slots on its axis neighbors — and
 * registers the optional curvature force on the simulation. What comes back is a handle that
 * knows the grid: `cloth[col, row]` is a particle index, [lines] is the wireframe, [pin] /
 * [teleport] / [grabNearest] address particles by grid coordinate. For the *solver's* view rather
 * than the sheet's — a segment per constraint, the pins in their own colour — see [debugLines].
 *
 *   val sim = SimdVerlet(state)
 *   val cloth = sim.addCloth(width = 20, height = 20, spacing = 0.5f)
 *   while (true) {
 *       cloth.step(gravity = -9.8f)
 *       draw(cloth.lines())
 *   }
 *
 * **Constraints.** A 4-slot constraint set is indexed by *particle* index, and the solver walks
 * `0 until state.size`, so one set has to cover the whole simulation rather than just this
 * cloth. By default a cloth adopts [SimdVerlet.constraints4] if one is already there (so two
 * cloths, or a cloth plus hand-wired constraints, share a single set) and otherwise creates one
 * and publishes it as `sim.constraints4`. [step] grows it whenever the simulation has grown.
 * Driving the simulation yourself means passing that set in explicitly:
 * `sim.step(dt, damping, gravity, iterations, sim.constraints4)` — the no-constraints
 * `step(...)` overload solves nothing and the cloth simply falls apart.
 *
 * **Curvature.** [curvatureStrength] pulls each interior particle toward the mean of its four
 * axis neighbors, which flattens the sheet and takes the sag out of the distance-only solve.
 * It is one [SimdVerlet.bulkForces] pass over the whole patch, not a force generator per
 * particle, so the cost is a single scalar sweep rather than `width * height` coroutine
 * resumes. The field is live: set it to `0f` to switch the force off for a frame or forever
 * (the pass stays registered and returns immediately). A cloth *built* with
 * `curvatureStrength = 0f` registers nothing at all — [enableCurvature] adds it later.
 *
 * Its useful scale is set by the springs it competes with, and that is easy to get wrong: the
 * constraint solve is force-based (each of `step`'s `iterations` adds its correction to the same
 * accumulator without moving anything in between), so the effective spring constant is
 * `stiffness * iterations`, and the curvature force has to be within an order of magnitude or two
 * of *that* to bend anything. Measured on a 20 x 10 sheet at `stiffness = 60`, `iterations = 16`:
 * the 0.05 default is indistinguishable from off, 20 is visible, and 100 takes about 40 % of the
 * kink out. The same product decides stability — 60 x 16 settles, 150 x 16 diverges to NaN at
 * `dt = 1/60`.
 *
 * **Stability.** The cloth's particles are the contiguous range `[base, base + count)`, and
 * both the constraint slots and the curvature sweep are written in terms of those indices.
 * Queued removals compact the state (see [SimdVerlet.applyQueuedRemovalsSwap]) and would
 * renumber them, so a cloth does not survive particle removal anywhere below its own top
 * index. Adding particles after the cloth is fine.
 *
 * [step], [lines] and the grab controls serialize on this cloth's own monitor, so a render
 * thread pulling [frames] and an input thread calling [moveGrabbed] don't have to coordinate.
 */
class Cloth private constructor(
    /** The simulation these particles live in. */
    val sim: SimdVerlet,
    /** Columns; the `x`/`col` axis of the grid. */
    val width: Int,
    /** Rows; the `y`/`row` axis of the grid. Row `height - 1` is the one pinned by default. */
    val height: Int,
    /** Index of this cloth's first particle. Its particles are `base until base + count`. */
    val base: Int,
    /** Nominal edge length: the default layout's grid step and the default rest length. */
    val spacing: Float,
    /** Distance-constraint stiffness this cloth wired into its slots. */
    val stiffness: Float,
    /** Particle mass used by [pin] when it un-pins a particle. */
    val mass: Float,
    /** The constraint set carrying this cloth's grid — shared with the rest of [sim]. */
    val constraints: SimdVerlet.DirectedConstraints4,
    /** Key prefix for everything this cloth registered on [sim] (`"$name.curvature"`, ...). */
    val name: String,
    curvatureStrength: Float
) {
    /**
     * Strength of the flattening force toward the mean of each interior particle's four axis
     * neighbors, applied every step by the registered bulk pass. `0f` disables it live.
     */
    @Volatile
    var curvatureStrength: Float = curvatureStrength

    private val lock = Any()

    /**
     * Nodes whose curvature stencil has been switched off, indexed `particle - base`. Allocated by
     * the first [suppressCurvatureAt] and null until then, so a cloth that never tears pays
     * nothing for it and [accumulateCurvature] keeps its unconditional inner loop.
     */
    @Volatile
    private var curvatureSuppressed: BooleanArray? = null

    private var grabbed = -1
    private var targetX = 0f
    private var targetY = 0f
    private var targetZ = 0f

    /** Number of particles in the patch. */
    val count: Int get() = width * height

    /** One past this cloth's last particle index. */
    val end: Int get() = base + count

    private val state: SimdVerlet.State3 get() = sim.state

    /** Particle index of the grid node at ([col], [row]). */
    fun index(col: Int, row: Int): Int {
        require(col in 0 until width && row in 0 until height) { "($col, $row) outside ${width}x$height cloth" }
        return base + row * width + col
    }

    /** Particle index of the grid node at ([col], [row]). */
    operator fun get(col: Int, row: Int): Int = index(col, row)

    /** True if [particle] is one of this cloth's. */
    operator fun contains(particle: Int): Boolean = particle in base until end

    /** Current position of the grid node at ([col], [row]). */
    fun position(col: Int, row: Int): Vec3 {
        val i = index(col, row)
        return vec(state.x[i], state.y[i], state.z[i])
    }

    /**
     * Moves one node to [to]. By default the previous position moves with it, so the Verlet
     * integrator sees a teleport rather than a one-frame velocity spike; pass
     * `keepVelocity = true` to throw the node instead.
     */
    fun teleport(col: Int, row: Int, to: Vector3fc, keepVelocity: Boolean = false) = synchronized(lock) {
        val i = index(col, row)
        val dx = to.x() - state.x[i]
        val dy = to.y() - state.y[i]
        val dz = to.z() - state.z[i]
        state.x[i] = to.x()
        state.y[i] = to.y()
        state.z[i] = to.z()
        if (!keepVelocity) {
            state.px[i] += dx
            state.py[i] += dy
            state.pz[i] += dz
        }
    }

    /**
     * Pins ([pinned] `= true`, inverse mass 0 — immovable) or releases (inverse mass `1 / mass`)
     * one node. Requires the state to carry an `invMass` array; without one every particle has
     * unit mass and nothing can be pinned.
     */
    fun pin(col: Int, row: Int, pinned: Boolean = true) {
        val invMass = requireNotNull(state.invMass) { "cannot pin: this State3 has no invMass array" }
        invMass[index(col, row)] = if (pinned) 0f else 1f / mass
    }

    /** True if the node at ([col], [row]) has zero inverse mass. */
    fun isPinned(col: Int, row: Int): Boolean {
        val invMass = state.invMass ?: return false
        return invMass[index(col, row)] == 0f
    }

    /**
     * The curvature pass, exposed for callers who would rather run it through `step`'s
     * `accumulateForces` hook than leave it registered in [SimdVerlet.bulkForces]. Accumulates
     * into `state.ax/ay/az` and touches only interior nodes, so a 1- or 2-wide cloth gets
     * nothing from it.
     */
    fun accumulateCurvature(state: SimdVerlet.State3, strength: Float = curvatureStrength) {
        if (strength == 0f || width < 3 || height < 3) return
        require(state.size >= end) { "state has ${state.size} particles, cloth needs $end" }

        val x = state.x
        val y = state.y
        val z = state.z
        val ax = state.ax
        val ay = state.ay
        val az = state.az
        val suppressed = curvatureSuppressed

        for (row in 1 until height - 1) {
            var i = base + row * width + 1
            for (col in 1 until width - 1) {
                if (suppressed != null && suppressed[i - base]) {
                    i++
                    continue
                }
                val left = i - 1
                val right = i + 1
                val below = i - width
                val above = i + width
                ax[i] += ((x[left] + x[right] + x[below] + x[above]) * 0.25f - x[i]) * strength
                ay[i] += ((y[left] + y[right] + y[below] + y[above]) * 0.25f - y[i]) * strength
                az[i] += ((z[left] + z[right] + z[below] + z[above]) * 0.25f - z[i]) * strength
                i++
            }
        }
    }

    /**
     * Switches the curvature stencil off at one particle, permanently.
     *
     * This exists for tearing (see [enableTearing]) and is the hook that keeps a rip open. The
     * curvature force is written from the *grid*, not from the constraint set, so retiring the
     * distance constraint across an edge leaves the flattening force still pulling the two lips of
     * the tear toward each other with no spring behind it — the sheet heals in the picture while
     * the solver says it is torn. Suppressing both endpoints of a broken edge removes exactly the
     * two stencils that spanned it.
     *
     * A no-op for a particle outside this cloth, and for the boundary nodes the curvature pass
     * never touched anyway. There is no un-suppress: an intact stencil is the invariant, and a
     * cloth that has been cut has lost it.
     */
    fun suppressCurvatureAt(particle: Int) {
        if (particle !in this) return
        val mask = curvatureSuppressed ?: BooleanArray(count).also { curvatureSuppressed = it }
        mask[particle - base] = true
    }

    /** Whether [suppressCurvatureAt] has switched the curvature stencil off at [particle]. */
    fun isCurvatureSuppressed(particle: Int): Boolean {
        if (particle !in this) return false
        return curvatureSuppressed?.get(particle - base) == true
    }

    /**
     * Registers the curvature pass on [sim] at [strength] (default: whatever
     * [curvatureStrength] is now), replacing it if it is already registered. Only needed for a
     * cloth that was built with `curvatureStrength = 0f`; otherwise just assign the field.
     */
    fun enableCurvature(strength: Float = curvatureStrength) {
        curvatureStrength = strength
        sim.addBulkForce("$name.curvature") { state, _ -> accumulateCurvature(state) }
    }

    /**
     * Drops the curvature pass from [sim] altogether. [accumulateCurvature] still works if you
     * would rather run it from `step`'s `accumulateForces` hook.
     */
    fun disableCurvature() {
        sim.removeBulkForce("$name.curvature")
    }

    /**
     * Steps the whole simulation once with this cloth's constraint set, growing that set first
     * if particles were added since the last step. [accumulateForces] is forwarded to
     * [SimdVerlet.step] and runs alongside the registered bulk forces.
     *
     * This steps *everything* in [sim], not just the cloth — it is a convenience for the common
     * case where the cloth is the simulation. With other systems in the same simulation, drive
     * it yourself and pass `sim.constraints4`.
     */
    fun step(
        dt: Float = 1f / 60f,
        damping: Float = 0.99f,
        gravity: Float = 0f,
        iterations: Int = 8,
        accumulateForces: ((state: SimdVerlet.State3, dt: Float) -> Unit)? = null
    ) = synchronized(lock) {
        constraints.ensureCapacity(state.size)
        sim.step(
            dt = dt,
            damping = damping,
            gravity = gravity,
            iterations = iterations,
            constraints = constraints,
            accumulateForces = accumulateForces
        )
    }

    /**
     * The cloth as a wireframe: one [FLine] per row followed by one per column, so
     * `width + height` lines in all.
     */
    fun lines(): List<FLine> = synchronized(lock) {
        val x = state.x
        val y = state.y
        val z = state.z
        val out = ArrayList<FLine>(width + height)

        for (row in 0 until height) {
            val line = FLine()
            var i = base + row * width
            for (col in 0 until width) {
                if (col == 0) line.moveTo(x[i], y[i], z[i]) else line.lineTo(x[i], y[i], z[i])
                i++
            }
            out.add(line)
        }

        for (col in 0 until width) {
            val line = FLine()
            var i = base + col
            for (row in 0 until height) {
                if (row == 0) line.moveTo(x[i], y[i], z[i]) else line.lineTo(x[i], y[i], z[i])
                i += width
            }
            out.add(line)
        }

        out
    }

    /** [step] then [lines], forever — the shape a Field box's animation loop consumes. */
    fun frames(
        dt: Float = 1f / 60f,
        damping: Float = 0.99f,
        gravity: Float = 0f,
        iterations: Int = 8,
        accumulateForces: ((state: SimdVerlet.State3, dt: Float) -> Unit)? = null
    ): Sequence<List<FLine>> = sequence {
        while (true) {
            step(dt, damping, gravity, iterations, accumulateForces)
            yield(lines())
        }
    }

    /** The particle currently held by [grabNearest], or `-1`. */
    val grabbedIndex: Int get() = synchronized(lock) { grabbed }

    /**
     * Grabs this cloth's particle nearest to the given world point, provided it is within
     * [radius], and starts driving it with a PID position controller — so the grab pulls
     * through the constraint solve instead of teleporting the particle out of the sheet.
     * Returns the grabbed particle index, or `-1` if nothing was near enough (in which case any
     * previous grab is left alone). A successful grab replaces a previous one.
     */
    fun grabNearest(
        worldX: Float,
        worldY: Float,
        worldZ: Float = 0f,
        radius: Float = spacing * 2f,
        kp: Float = 100f,
        ki: Float = 0f,
        kd: Float = 10f,
        maxForce: Float = 1000f
    ): Int = synchronized(lock) {
        var nearest = -1
        var nearestDist = Float.POSITIVE_INFINITY
        for (i in base until minOf(end, state.size)) {
            val dx = state.x[i] - worldX
            val dy = state.y[i] - worldY
            val dz = state.z[i] - worldZ
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist < nearestDist) {
                nearestDist = dist
                nearest = i
            }
        }
        if (nearest < 0 || nearestDist >= radius) return@synchronized -1

        releaseGrabbedLocked()
        grabbed = nearest
        targetX = state.x[nearest]
        targetY = state.y[nearest]
        targetZ = state.z[nearest]
        sim.addPidPositionController(
            index = nearest,
            name = "$name.grab",
            targetX = { targetX },
            targetY = { targetY },
            targetZ = { targetZ },
            kp = kp,
            ki = ki,
            kd = kd,
            maxForce = maxForce
        )
        nearest
    }

    /** Moves the grab target. Returns false if nothing is grabbed. */
    fun moveGrabbed(worldX: Float, worldY: Float, worldZ: Float = 0f): Boolean = synchronized(lock) {
        if (grabbed < 0) return@synchronized false
        targetX = worldX
        targetY = worldY
        targetZ = worldZ
        true
    }

    /** Releases the grabbed particle. Returns false if nothing was grabbed. */
    fun releaseGrabbed(): Boolean = synchronized(lock) { releaseGrabbedLocked() }

    private fun releaseGrabbedLocked(): Boolean {
        val i = grabbed
        if (i < 0) return false
        sim.removeForceGenerator(i, "$name.grab")
        grabbed = -1
        return true
    }

    /**
     * Detaches this cloth from [sim]: releases any grab, drops the curvature pass and clears
     * the cloth's constraint slots. The particles stay (removing them would renumber the rest
     * of the simulation); pass them to [SimdVerlet.queueRemove] yourself if you want them gone.
     */
    fun remove() = synchronized(lock) {
        releaseGrabbedLocked()
        sim.removeBulkForce("$name.curvature")
        for (i in base until minOf(end, constraints.size)) {
            constraints.clearSlots(i)
        }
    }

    companion object {
        /**
         * Appends a `width * height` cloth to [sim] and returns the handle. See [Cloth] for the
         * constraint-set, curvature and index-stability contracts.
         *
         * @param spacing grid step of the default layout, and the rest length of every edge
         *   unless [restLengthFromLayout] measures them from the initial positions instead.
         * @param stiffness per-slot distance-constraint stiffness.
         * @param mass particle mass; unpinned particles get inverse mass `1 / mass`.
         * @param curvatureStrength flattening force toward the mean of the four axis neighbors;
         *   `0f` registers nothing.
         * @param position initial position of node (col, row); defaults to a flat grid in `z = 0`
         *   with `x` along the columns and `y` along the rows.
         * @param restLengthFromLayout measure each edge's rest length from [position] rather than
         *   using [spacing] — what you want when [position] lays the cloth out non-uniformly.
         * @param pinned which nodes get inverse mass 0; defaults to the whole top row
         *   (`row == height - 1`), i.e. a hanging sheet.
         * @param constraints constraint set to wire into; defaults to [SimdVerlet.constraints4],
         *   creating and publishing one if the simulation hasn't got one yet.
         * @param name key prefix for what this cloth registers on [sim]; defaults to
         *   `"cloth@<first particle index>"`, which is unique per cloth.
         */
        fun addTo(
            sim: SimdVerlet,
            width: Int,
            height: Int,
            spacing: Float = 0.5f,
            stiffness: Float = 0.8f,
            mass: Float = 1f,
            curvatureStrength: Float = 0.05f,
            position: (col: Int, row: Int) -> Vector3fc = { col, row -> vec(col * spacing, row * spacing, 0f) },
            restLengthFromLayout: Boolean = false,
            pinned: (col: Int, row: Int) -> Boolean = { _, row -> row == height - 1 },
            constraints: SimdVerlet.DirectedConstraints4? = sim.constraints4,
            name: String? = null
        ): Cloth {
            require(width >= 2 && height >= 2) { "a cloth needs at least 2x2 particles, got ${width}x$height" }
            require(mass > 0f) { "mass must be > 0" }

            val count = width * height
            val base = sim.size

            // Lay the grid out first: rest lengths and pinning both need the positions, and the
            // position lambda is not required to be cheap or pure.
            val px = FloatArray(count)
            val py = FloatArray(count)
            val pz = FloatArray(count)
            var anyPinned = false
            for (row in 0 until height) {
                for (col in 0 until width) {
                    val p = position(col, row)
                    val at = row * width + col
                    px[at] = p.x()
                    py[at] = p.y()
                    pz[at] = p.z()
                    if (pinned(col, row)) anyPinned = true
                }
            }
            require(!(anyPinned && sim.state.invMass == null)) {
                "cannot pin particles: this State3 has no invMass array"
            }
            require(mass == 1f || sim.state.invMass != null) {
                "mass != 1 needs a State3 with an invMass array"
            }

            sim.state.ensureCapacity(base + count)
            for (row in 0 until height) {
                for (col in 0 until width) {
                    val at = row * width + col
                    val index = sim.addParticle(
                        x0 = px[at], y0 = py[at], z0 = pz[at],
                        invMass0 = if (pinned(col, row)) 0f else 1f / mass
                    )
                    check(index == base + at) { "cloth particles must be contiguous: got $index, expected ${base + at}" }
                }
            }

            val set = (constraints ?: SimdVerlet.DirectedConstraints4.ofCapacity(base + count))
                .also { it.ensureCapacity(sim.size) }
            sim.constraints4 = set

            fun rest(fromAt: Int, toAt: Int): Float {
                if (!restLengthFromLayout) return spacing
                val dx = px[toAt] - px[fromAt]
                val dy = py[toAt] - py[fromAt]
                val dz = pz[toAt] - pz[fromAt]
                return sqrt(dx * dx + dy * dy + dz * dz)
            }

            // Four outgoing slots per particle: right, below, left, above. An interior particle
            // fills all four; edges and corners leave the rest unused.
            for (row in 0 until height) {
                for (col in 0 until width) {
                    val at = row * width + col
                    val index = base + at
                    var slot = 0
                    if (col < width - 1) {
                        set.setSlot(index, slot++, index + 1, rest(at, at + 1), stiffness)
                    }
                    if (row > 0) {
                        set.setSlot(index, slot++, index - width, rest(at, at - width), stiffness)
                    }
                    if (col > 0) {
                        set.setSlot(index, slot++, index - 1, rest(at, at - 1), stiffness)
                    }
                    if (row < height - 1) {
                        set.setSlot(index, slot++, index + width, rest(at, at + width), stiffness)
                    }
                    while (slot < 4) {
                        set.setSlot(index, slot++, -1, 0f, 0f)
                    }
                }
            }

            val cloth = Cloth(
                sim = sim,
                width = width,
                height = height,
                base = base,
                spacing = spacing,
                stiffness = stiffness,
                mass = mass,
                constraints = set,
                name = name ?: "cloth@$base",
                curvatureStrength = curvatureStrength
            )

            if (curvatureStrength != 0f && width >= 3 && height >= 3) {
                cloth.enableCurvature()
            }

            return cloth
        }
    }
}

/** Appends a cloth patch to this simulation; see [Cloth.addTo]. */
fun SimdVerlet.addCloth(
    width: Int,
    height: Int,
    spacing: Float = 0.5f,
    stiffness: Float = 0.8f,
    mass: Float = 1f,
    curvatureStrength: Float = 0.05f,
    position: (col: Int, row: Int) -> Vector3fc = { col, row -> vec(col * spacing, row * spacing, 0f) },
    restLengthFromLayout: Boolean = false,
    pinned: (col: Int, row: Int) -> Boolean = { _, row -> row == height - 1 },
    constraints: SimdVerlet.DirectedConstraints4? = this.constraints4,
    name: String? = null
): Cloth = Cloth.addTo(
    sim = this,
    width = width,
    height = height,
    spacing = spacing,
    stiffness = stiffness,
    mass = mass,
    curvatureStrength = curvatureStrength,
    position = position,
    restLengthFromLayout = restLengthFromLayout,
    pinned = pinned,
    constraints = constraints,
    name = name
)
