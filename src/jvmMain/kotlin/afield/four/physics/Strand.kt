package afield.four.physics

import afield.graphics.FLine
import afield.stdlib.Vec3
import afield.stdlib.linalg.Vector3fc
import afield.stdlib.vec
import kotlin.math.sqrt

/**
 * A rope inside somebody else's [SimdVerlet]: the one-dimensional sibling of [Cloth], and the same
 * shape of thing. `sim.addStrand(...)` (or `Strand.addTo`) appends `count` particles to a
 * simulation that may already contain others, chains them through a
 * [SimdVerlet.DirectedConstraints4], and hands back a handle that speaks node indices.
 *
 *     val sim = SimdVerlet(SimdVerlet.State3.empty())
 *     val rope = sim.addStrand(count = 40, spacing = 0.12f, stiffness = 60f)
 *     rope.pin(0)
 *     while (true) { rope.step(gravity = 9.8f, iterations = 16); draw(rope.lines()) }
 *
 * #### Why every node has two spare slots
 *
 * A chain node spends slot 0 on its successor and slot 1 on its predecessor; the ends use one
 * each. That leaves **slots 2 and 3 free on every node of every rope**, which is what
 * `Strand.attachTo` spends. A rope can be tied to a cloth corner, to another rope, or to any
 * particle in the simulation with a spare slot, and the join is an ordinary symmetric distance
 * constraint that the same solver, the same colliders and the same [Tearing] already understand.
 * So a tied rope tears at its knot under enough load.
 *
 * What it cannot tie to is a cloth's *interior*, which has all four slots on its axis neighbours.
 * `attachTo` throws there and names the particle index rather than ignoring the tie. Corners have
 * two free slots and edges have one.
 *
 * #### Bending
 *
 * `Strand.bendStiffness` straightens the rope by moving each interior node toward the midpoint of
 * its two neighbours: the 1-D case of [Cloth]'s curvature idea, but as a **position projection**
 * in `SimdVerlet.positionProjections` rather than a force.
 *
 * A projection rather than a force, because a curvature force would sit on the solver's
 * `stiffness * iterations` scale, where the product has a divergence ceiling in `dt`, and the
 * strength that holds a rope straight is above it: turned up far enough such a force thrashes
 * instead of stiffening. A projection has no stiffness, so it has no ceiling. The projection
 * itself is the standard mass-weighted one — `C = b - (a+c)/2`, corrections `Δb = +w_b·d/S`,
 * `Δa = Δc = -w·d/2S` with `S = w_b + (w_a + w_c)/4` — applied to `x` *and* `px` together so it
 * injects no velocity and can add no energy, and swept in **three colours** so that overlapping
 * stencils do not stack their corrections on top of each other. `bendStiffness` is clamped to
 * `0..1`, and `Strand.MAX_BEND_CORRECTION` is what `1` maps onto: the stiffest correction this
 * integrator holds, rather than a full one.
 *
 * What it controls is **how tightly the rope can fold**, which is not the same thing as how stiff
 * the rope is. `StrandTest` heaps a 50-node rope on a floor between two anchors and reads the mean
 * joint angle: it falls with every increase in `bendStiffness`, and by `1.0` it is under a third of
 * the limp rope's, with no sharp corner left.
 *
 * On a *cantilever* it does almost nothing, because a rope hanging under gravity is already
 * straight and the base moment a cantilever needs is not something a local three-node stencil can
 * supply. For a rod, add distance constraints across the gaps rather than turning this up.
 *
 * `0f` is the default: a rope is limp unless told otherwise.
 *
 * One ordering caveat. Projections run in registration order, so a strand created *before* its
 * colliders bends first and is then pushed out of the world. Create the strand after the colliders
 * and the last word belongs to bending, and a stiff rope can end a frame slightly inside a shape.
 *
 * #### Stability
 *
 * The same contract as [Cloth]: the rope's particles are the contiguous range
 * `[base, base + count)`, so it does not survive particle removal anywhere below its own top
 * index. Appending after it is fine.
 *
 * `Strand.step`, `Strand.lines` and the mutators serialize on this strand's own monitor.
 */

/**
 * The handle for one rope: node indices, the bending projection, and the ties to other particles.
 *
 * `strand[node]` is a particle index, [position] and [teleport] address nodes, [pin] nails one
 * down, [lines] is the polyline to draw and [frames] is the step-and-draw loop. [attachTo] ties a
 * node to any other particle that has a free constraint slot, and [remove] drops this rope's
 * constraints and its bending pass while leaving its particles where they are.
 *
 * Built by `sim.addStrand(...)` rather than directly: a rope is a contiguous range of particles
 * appended to a simulation, and [base] is where that range starts.
 */
class Strand private constructor(
    /** The simulation these particles live in. */
    val sim: SimdVerlet,
    /** Number of nodes in the chain. */
    val count: Int,
    /** Index of this strand's first particle. Its particles are `base until base + count`. */
    val base: Int,
    /** Nominal segment length: the default layout's step and the default rest length. */
    val spacing: Float,
    /** Distance-constraint stiffness this strand wired into its slots. */
    val stiffness: Float,
    /** Particle mass used by [pin] when it un-pins a node. */
    val mass: Float,
    /** The constraint set carrying this strand — shared with the rest of [sim]. */
    val constraints: SimdVerlet.DirectedConstraints4,
    /** Key prefix for everything this strand registered on [sim] (`"$name.bend"`, ...). */
    val name: String,
    bendStiffness: Float
) {
    /**
     * How much of the way toward straight each interior node is moved by the registered bending
     * projection, per step, in `0..1`. `0f` disables it live; the file overview has why this is a
     * projection and not a force.
     */
    @Volatile
    var bendStiffness: Float = bendStiffness

    private val lock = Any()

    /** One past this strand's last particle index. */
    val end: Int get() = base + count

    private val state: SimdVerlet.State3 get() = sim.state

    /** Particle index of chain node [node]. */
    operator fun get(node: Int): Int {
        require(node in 0 until count) { "node $node outside a $count-node strand" }
        return base + node
    }

    /** True if [particle] is one of this strand's. */
    operator fun contains(particle: Int): Boolean = particle in base until end

    /** Current position of chain node [node]. */
    fun position(node: Int): Vec3 {
        val i = this[node]
        return vec(state.x[i], state.y[i], state.z[i])
    }

    /**
     * Pins ([pinned] `= true`, inverse mass 0) or releases one node. Requires the state to carry
     * an `invMass` array; without one every particle has unit mass and nothing can be pinned.
     */
    fun pin(node: Int, pinned: Boolean = true) {
        val invMass = requireNotNull(state.invMass) { "cannot pin: this State3 has no invMass array" }
        invMass[this[node]] = if (pinned) 0f else 1f / mass
    }

    /** True if [node] has zero inverse mass. */
    fun isPinned(node: Int): Boolean {
        val invMass = state.invMass ?: return false
        return invMass[this[node]] == 0f
    }

    /**
     * Moves one node. By default the previous position moves with it, so the integrator sees a
     * teleport rather than a one-frame velocity spike; `keepVelocity = true` throws it instead.
     */
    fun teleport(node: Int, to: Vector3fc, keepVelocity: Boolean = false) = synchronized(lock) {
        val i = this[node]
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
     * Ties node [node] to an arbitrary [particle] with a symmetric distance constraint, spending
     * one free slot on each. Returns the rest length used.
     *
     * The join is an ordinary constraint, which is the point: the solver, the colliders and
     * [Tearing] need to know nothing about it. Tie a rope to a cloth corner and the corner is
     * pulled through the same solve as everything else, and the knot tears under enough load
     * because a knot is an edge.
     *
     * @param restLength defaults to the current distance, so tying does not yank.
     * @throws IllegalStateException if either side has no free slot — a cloth's *interior* node
     *   has all four spent on its axis neighbours, and failing quietly there would look like a
     *   rope that inexplicably ignores the thing it is tied to.
     */
    fun attachTo(
        particle: Int,
        node: Int = count - 1,
        restLength: Float = -1f,
        stiffness: Float = this.stiffness
    ): Float = synchronized(lock) {
        val i = this[node]
        require(particle in 0 until state.size) { "attach target $particle out of bounds" }
        require(particle !in this) { "a strand cannot be tied to itself with attachTo; use pin or a second strand" }
        constraints.ensureCapacity(state.size)
        val here = freeSlot(i) ?: error(
            "strand node $node (particle $i) has no free constraint slot to tie with"
        )
        val there = freeSlot(particle) ?: error(
            "particle $particle has no free constraint slot to tie to -- a Cloth's interior nodes " +
                "spend all four on their axis neighbours; tie to a corner (two free) or an edge (one)"
        )
        val rest = if (restLength >= 0f) restLength else {
            val dx = state.x[particle] - state.x[i]
            val dy = state.y[particle] - state.y[i]
            val dz = state.z[particle] - state.z[i]
            sqrt(dx * dx + dy * dy + dz * dz)
        }
        constraints.setSlot(i, here, particle, rest, stiffness)
        constraints.setSlot(particle, there, i, rest, stiffness)
        rest
    }

    /** The lowest-numbered unused slot of [particle], or null if all four are spent. */
    private fun freeSlot(particle: Int): Int? {
        if (particle >= constraints.size) return null
        if (constraints.n0[particle] < 0 || constraints.s0[particle] == 0f) return 0
        if (constraints.n1[particle] < 0 || constraints.s1[particle] == 0f) return 1
        if (constraints.n2[particle] < 0 || constraints.s2[particle] == 0f) return 2
        if (constraints.n3[particle] < 0 || constraints.s3[particle] == 0f) return 3
        return null
    }

    /**
     * The bending projection, exposed for callers who would rather drive it than leave it
     * registered. Moves `x` and `px` together for every interior node, so it injects no velocity;
     * touches only interior nodes, so a 2-node strand gets nothing from it.
     *
     * @param strength how much of the correction to apply, `0..1`. Values above 1 overshoot and
     *   are clamped, because a projection that overshoots is a force with extra steps.
     */
    fun projectBend(state: SimdVerlet.State3, strength: Float = bendStiffness) {
        if (strength == 0f || count < 3) return
        require(state.size >= end) { "state has ${state.size} particles, strand needs $end" }
        // `bendStiffness` is 0..1 and 1 means "the stiffest this integrator holds", which is not
        // a full correction. Measured on both the fold scene and the cantilever, a full one
        // oscillates against the distance constraints and diverges (a 20-node cantilever at 1.0
        // reached a tip of -17539, -94947); 0.6 settles on both. So the field is scaled onto the
        // band that works rather than documenting a range with a cliff in it.
        val alpha = strength.coerceIn(0f, 1f) * MAX_BEND_CORRECTION
        val x = state.x
        val y = state.y
        val z = state.z
        val px = state.px
        val py = state.py
        val pz = state.pz
        val invMass = state.invMass

        // Three colours, not one sweep. Consecutive stencils overlap -- the one at i moves i-1, i
        // and i+1, the one at i+1 moves i, i+1 and i+2 -- so a straight walk applies overlapping
        // corrections on top of each other and over-relaxes: measured, a plain sweep is stable to
        // alpha 0.6 and diverges to NaN at 0.9. Stencils three apart are disjoint, so within a
        // colour alpha = 1 is an exact projection and cannot overshoot.
        for (colour in 0 until 3) {
        var i = base + 1 + colour
        while (i < end - 1) {
            val wa = invMass?.get(i - 1) ?: 1f
            val wb = invMass?.get(i) ?: 1f
            val wc = invMass?.get(i + 1) ?: 1f
            val denom = wb + 0.25f * (wa + wc)
            if (denom <= 0f) {
                i += 3
                continue
            }

            val dx = (x[i - 1] + x[i + 1]) * 0.5f - x[i]
            val dy = (y[i - 1] + y[i + 1]) * 0.5f - y[i]
            val dz = (z[i - 1] + z[i + 1]) * 0.5f - z[i]
            val sx = alpha * dx / denom
            val sy = alpha * dy / denom
            val sz = alpha * dz / denom

            // Mass-weighted and momentum-conserving: the middle node moves toward the chord, the
            // two ends move half as far the other way, each scaled by its own inverse mass, so a
            // pinned neighbour (w = 0) simply does not move and takes the whole correction into
            // the free node. `px` moves with `x`, so no Verlet velocity is created.
            if (wb != 0f) {
                val bx = wb * sx; val by = wb * sy; val bz = wb * sz
                x[i] += bx; y[i] += by; z[i] += bz
                px[i] += bx; py[i] += by; pz[i] += bz
            }
            if (wa != 0f) {
                val ax = -0.5f * wa * sx; val ay = -0.5f * wa * sy; val az = -0.5f * wa * sz
                x[i - 1] += ax; y[i - 1] += ay; z[i - 1] += az
                px[i - 1] += ax; py[i - 1] += ay; pz[i - 1] += az
            }
            if (wc != 0f) {
                val cx = -0.5f * wc * sx; val cy = -0.5f * wc * sy; val cz = -0.5f * wc * sz
                x[i + 1] += cx; y[i + 1] += cy; z[i + 1] += cz
                px[i + 1] += cx; py[i + 1] += cy; pz[i + 1] += cz
            }
            i += 3
        }
        }
    }

    /** Registers the bending projection on [sim] at [strength], replacing it if already there. */
    fun enableBend(strength: Float = bendStiffness) {
        bendStiffness = strength
        sim.addPositionProjection("$name.bend") { state, _ -> projectBend(state) }
    }

    /** Drops the bending projection from [sim] altogether. */
    fun disableBend() {
        sim.removePositionProjection("$name.bend")
    }

    /**
     * Steps the whole simulation once with this strand's constraint set, growing that set first if
     * particles were added since the last step — the same convenience, and the same caveat, as
     * [Cloth.step]: it steps *everything* in [sim], not just the rope.
     */
    fun step(
        dt: Float = 1f / 60f,
        damping: Float = 0.99f,
        gravity: Float = 0f,
        iterations: Int = 8,
        accumulateForces: ((state: SimdVerlet.State3, dt: Float) -> Unit)? = null
    ) = synchronized(lock) {
        constraints.ensureCapacity(state.size)
        sim.step(dt, damping, gravity, iterations, constraints, accumulateForces)
    }

    /** The rope as a single polyline. */
    fun lines(): List<FLine> = synchronized(lock) {
        val line = FLine()
        for (n in 0 until count) {
            val i = base + n
            if (n == 0) line.moveTo(state.x[i], state.y[i], state.z[i])
            else line.lineTo(state.x[i], state.y[i], state.z[i])
        }
        listOf(line)
    }

    /** [step] then [lines], forever — the shape a Field box's animation loop consumes. */
    fun frames(
        dt: Float = 1f / 60f,
        damping: Float = 0.99f,
        gravity: Float = 0f,
        iterations: Int = 8
    ): Sequence<List<FLine>> = sequence {
        while (true) {
            step(dt, damping, gravity, iterations)
            yield(lines())
        }
    }

    /**
     * Mean angle in degrees between successive segments — the "how limp is this rope" readout, and
     * what [bendStiffness]'s documented scale is measured in. `0` for a rope in a straight line.
     */
    fun meanKinkDegrees(): Float = synchronized(lock) {
        if (count < 3) return@synchronized 0f
        var total = 0.0
        var counted = 0
        for (n in 1 until count - 1) {
            val a = base + n - 1
            val b = base + n
            val c = base + n + 1
            val ux = state.x[b] - state.x[a]
            val uy = state.y[b] - state.y[a]
            val uz = state.z[b] - state.z[a]
            val vx = state.x[c] - state.x[b]
            val vy = state.y[c] - state.y[b]
            val vz = state.z[c] - state.z[b]
            val lu = sqrt(ux * ux + uy * uy + uz * uz)
            val lv = sqrt(vx * vx + vy * vy + vz * vz)
            if (lu < 1e-9f || lv < 1e-9f) continue
            val cos = ((ux * vx + uy * vy + uz * vz) / (lu * lv)).coerceIn(-1f, 1f)
            total += Math.toDegrees(kotlin.math.acos(cos).toDouble())
            counted++
        }
        if (counted == 0) 0f else (total / counted).toFloat()
    }

    /**
     * Detaches this strand from [sim]: drops the bending pass and clears its constraint slots. The
     * particles stay, as with [Cloth.remove]. Anything tied to it with [attachTo] keeps its own
     * half of the join, so untie first if that matters.
     */
    fun remove() = synchronized(lock) {
        sim.removePositionProjection("$name.bend")
        for (i in base until minOf(end, constraints.size)) {
            constraints.clearSlots(i)
        }
    }

    companion object {
        /**
         * The largest per-pass correction [projectBend] will apply, and what `bendStiffness = 1`
         * maps onto. See the note in [projectBend].
         */
        const val MAX_BEND_CORRECTION: Float = 0.6f

        /**
         * Appends a `count`-node rope to [sim] and returns the handle.
         *
         * @param spacing step of the default layout (a straight line along `x`) and the rest
         *   length of every segment unless [restLengthFromLayout] measures them from [position].
         * @param stiffness per-slot distance-constraint stiffness. As everywhere in this solver the
         *   number that matters is `stiffness * iterations`; see `SIMDVERLET_4_CONSTRAINTS.md`.
         * @param bendStiffness how much of the way toward straight each interior node is moved
         *   per step, `0..1`; `0f` (the default — a rope is limp unless told otherwise) registers
         *   nothing. A projection, not a force: the file overview has why.
         * @param pinned which nodes get inverse mass 0. Nothing, by default: a strand that is
         *   pinned by default would be a surprise in the way a hanging cloth is not.
         * @param constraints constraint set to wire into; defaults to [SimdVerlet.constraints4],
         *   creating and publishing one if the simulation hasn't got one.
         */
        fun addTo(
            sim: SimdVerlet,
            count: Int,
            spacing: Float = 0.2f,
            stiffness: Float = 60f,
            mass: Float = 1f,
            bendStiffness: Float = 0f,
            position: (node: Int) -> Vector3fc = { n -> vec(n * spacing, 0f, 0f) },
            restLengthFromLayout: Boolean = false,
            pinned: (node: Int) -> Boolean = { false },
            constraints: SimdVerlet.DirectedConstraints4? = sim.constraints4,
            name: String? = null
        ): Strand {
            require(count >= 2) { "a strand needs at least 2 nodes, got $count" }
            require(mass > 0f) { "mass must be > 0" }

            val base = sim.size
            val px = FloatArray(count)
            val py = FloatArray(count)
            val pz = FloatArray(count)
            var anyPinned = false
            for (n in 0 until count) {
                val p = position(n)
                px[n] = p.x()
                py[n] = p.y()
                pz[n] = p.z()
                if (pinned(n)) anyPinned = true
            }
            require(!(anyPinned && sim.state.invMass == null)) {
                "cannot pin particles: this State3 has no invMass array"
            }
            require(mass == 1f || sim.state.invMass != null) {
                "mass != 1 needs a State3 with an invMass array"
            }

            sim.state.ensureCapacity(base + count)
            for (n in 0 until count) {
                val index = sim.addParticle(
                    x0 = px[n], y0 = py[n], z0 = pz[n],
                    invMass0 = if (pinned(n)) 0f else 1f / mass
                )
                check(index == base + n) { "strand particles must be contiguous: got $index, expected ${base + n}" }
            }

            val set = (constraints ?: SimdVerlet.DirectedConstraints4.ofCapacity(base + count))
                .also { it.ensureCapacity(sim.size) }
            sim.constraints4 = set

            fun rest(from: Int, to: Int): Float {
                if (!restLengthFromLayout) return spacing
                val dx = px[to] - px[from]
                val dy = py[to] - py[from]
                val dz = pz[to] - pz[from]
                return sqrt(dx * dx + dy * dy + dz * dz)
            }

            // Slot 0 forward, slot 1 back; 2 and 3 stay free for attachTo.
            for (n in 0 until count) {
                val index = base + n
                set.setSlot(index, 0, if (n < count - 1) index + 1 else -1, if (n < count - 1) rest(n, n + 1) else 0f, if (n < count - 1) stiffness else 0f)
                set.setSlot(index, 1, if (n > 0) index - 1 else -1, if (n > 0) rest(n, n - 1) else 0f, if (n > 0) stiffness else 0f)
                set.setSlot(index, 2, -1, 0f, 0f)
                set.setSlot(index, 3, -1, 0f, 0f)
            }

            val strand = Strand(
                sim = sim, count = count, base = base, spacing = spacing, stiffness = stiffness,
                mass = mass, constraints = set, name = name ?: "strand@$base",
                bendStiffness = bendStiffness
            )
            if (bendStiffness != 0f && count >= 3) strand.enableBend()
            return strand
        }
    }
}

/** Appends a rope to this simulation; see [Strand.addTo]. */
fun SimdVerlet.addStrand(
    count: Int,
    spacing: Float = 0.2f,
    stiffness: Float = 60f,
    mass: Float = 1f,
    bendStiffness: Float = 0f,
    position: (node: Int) -> Vector3fc = { n -> vec(n * spacing, 0f, 0f) },
    restLengthFromLayout: Boolean = false,
    pinned: (node: Int) -> Boolean = { false },
    constraints: SimdVerlet.DirectedConstraints4? = this.constraints4,
    name: String? = null
): Strand = Strand.addTo(
    sim = this, count = count, spacing = spacing, stiffness = stiffness, mass = mass,
    bendStiffness = bendStiffness, position = position, restLengthFromLayout = restLengthFromLayout,
    pinned = pinned, constraints = constraints, name = name
)
