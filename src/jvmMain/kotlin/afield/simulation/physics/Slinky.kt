package afield.simulation.physics

import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.thickness
import afield.math.linalg.Vec3
import afield.math.linalg.Vec4
import afield.math.linalg.vec
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A helical spring seen side-on, inside somebody else's [SimdVerlet]: the coil-spring sibling of
 * [Strand] and [Cloth]. `sim.addSlinky(...)` appends two particles per coil — the coil's two rims
 * as they appear edge-on, `A` and `B` — and hands back a handle that speaks coil indices.
 *
 *     val sim = SimdVerlet(SimdVerlet.State3.empty())
 *     val stairs = StaircaseCollider(run = 1.6f, rise = 1f)
 *     sim.addColliders(stairs)
 *     val slinky = sim.addSlinky(coils = 40, x = 0.8f, y = 0f)
 *     loop { slinky.step(); showPlane(slinky.lines() + stairs.outline()) }
 *
 * #### The model
 *
 * Three things make a coil spring a slinky rather than a rope, and each is one part of the model.
 *
 * - **Axial stiffness, with preload.** Coil `i`'s rims are tied to coil `i + 1`'s by two rail
 *   springs in [SimdVerlet.constraints4], one per side, at [stiffness]. Their rest length is a
 *   fraction `1 - preload` of [pitch], so a slinky at rest is pulled shut: the coils press on each
 *   other, and it takes a load to open them. That is a real slinky's *initial tension*, and it is
 *   why a hanging one stays shut at the bottom.
 * - **Coil–coil contact.** A coil cannot get closer to its neighbour than the wire is thick. The
 *   contact is one-sided and measured against the lower coil's face, so a stack of shut coils is
 *   rigid in bending — bending it would push one side of a coil through the next — while an open
 *   stretch bends freely. Bending stiffness therefore comes from the contact and the preload
 *   together, as it does in the toy. Contact is inelastic and goes in [SimdVerlet.positionProjections]
 *   with the rest of the coil pass.
 * - **Coils stay coils.** Each coil is held at its [diameter] exactly, and crossed diagonal
 *   springs between neighbouring coils, at [shearStiffness], resist the coils sliding sideways
 *   over each other: the shear stiffness of the helix, which keeps its coils across the axis
 *   rather than along it.
 *
 * The coil pass is a projection, not a force, for the same reason as [Strand]'s bending: a rigid
 * coil or a contact as a spring would sit on the `stiffness * iterations` scale with a divergence
 * ceiling in `dt`. Each correction is clamped to a pitch per pass, so one bad frame (a coil
 * folded back over its neighbour by a violent start) recovers over a few frames instead of
 * exploding.
 *
 * #### Energy
 *
 * A slinky walks on the energy of its fall and nothing else, so every part of the model is built
 * to keep energy or lose it, never to make it: the springs are springs, each projection moves
 * particles along its constraint's whole gradient (so it keeps momentum and angular momentum and
 * can only take energy out), and the collider corrections are averaged rather than summed. The
 * contact KDocs below say which shortcuts did the opposite. A slinky thrown in the air with no
 * drag loses energy every frame and never gains it, and a shut stack stood on a floor stays still.
 *
 * #### Units and steps
 *
 * Any consistent units. The defaults are a toy slinky with the diameter as the unit: 40 coils of
 * diameter 1 and pitch 0.025, unit masses, and a rail stiffness that stretches the hanging spring to
 * about fifteen diameters under a gravity of 146 (9.81 m/s² when a diameter is 6.7 cm). That spring
 * wants a time step near 1/600 s, so [step] takes a frame `dt` and divides it into [substeps].
 *
 * #### Stability
 *
 * The same contract as [Strand]: the coils are the contiguous particle range `[base, base + 2 * coils)`,
 * so do not remove particles below its top index.
 */
class Slinky internal constructor(
    val sim: SimdVerlet,
    /** Number of coils. */
    val coils: Int,
    /** First particle index; coil `c` is particles `base + c * perCoil` up to `base + (c + 1) * perCoil`. */
    val base: Int,
    /** Coil diameter: each coil's rung is held at this length exactly. */
    val diameter: Float,
    /** Wire thickness: the closest two neighbouring coils can come, at each rim. */
    val pitch: Float,
    /** Mass of one particle. */
    val mass: Float,
    val constraints: SimdVerlet.DirectedConstraints4,
    val name: String,
) {
    private val lock = Any()
    private val state get() = sim.state

    /** Particles per coil: its two rims, `A` and `B`. */
    val perCoil: Int = 2

    /** One past this slinky's last particle index. */
    val end: Int get() = base + perCoil * coils

    /** Particle index of [coil]'s rim [k]: 0 is rim A, 1 is rim B. */
    operator fun get(coil: Int, k: Int): Int {
        require(coil in 0 until coils) { "coil $coil out of 0 until $coils" }
        require(k in 0 until perCoil) { "k must be in 0 until $perCoil" }
        return base + perCoil * coil + k
    }

    private fun first(c: Int) = base + perCoil * c

    /** Coil passes per substep. More passes make the shut stack stiffer and cost linearly. */
    var passes: Int = 4

    /** Whether touching coils stop dead against each other; see [stopTouchingCoils]. */
    var inelasticCoils: Boolean = true

    /** Fraction of the sliding between two touching coils removed per substep: coil-on-coil grip. */
    var coilFriction: Float = 0f

    /** How far past [pitch], as a fraction of it, two coils still count as touching. */
    var contactSlack: Float = 0.1f

    /**
     * Colliders the coil pass hands the last word to after each of its [passes], through
     * [ColliderSet.relax]. Without it a shut stack standing on a floor sinks: the coil contact
     * pushes coil 0 into the floor every pass and the floor only pushes back once per substep.
     * The set still advances (and moves, if kinematic) through its own registered pass.
     */
    var restingOn: ColliderSet? = null

    /** Frame substeps [step] divides its `dt` into. */
    var substeps: Int = 10

    /** Fraction of its velocity a particle keeps per second against the world: air drag. `1f` is none. */
    var velocityKeptPerSecond: Float = 1f

    /**
     * Fraction per second of the *relative* velocity between neighbouring coils that the spring
     * loses: the wire's internal friction. It cannot slow the slinky as a whole, only its
     * wobbling.
     */
    var internalDamping: Float = 0.9f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    /** Rail spring stiffness between neighbouring coils, per rim. Live: rewrites the rail slots. */
    var stiffness: Float = 0f
        set(value) {
            field = value
            wireRails()
        }

    /** Fraction of [pitch] the rails are pre-shortened by: the slinky's initial tension. Live. */
    var preload: Float = 0.7f
        set(value) {
            field = value
            wireRails()
        }

    /**
     * Stiffness of the crossed diagonals between neighbouring coils, per rim: the helix's
     * resistance to its coils sliding sideways over each other. Each particle is tied to the
     * opposite particle of the coil above and the coil below, at the length that pair has when
     * the two coils are shut, so a sideways slide stretches one diagonal and shortens the other
     * while opening or bending the spring barely changes either. They are springs, so unlike a
     * projection they conserve energy. Live: rewrites the diagonal slots.
     */
    var shearStiffness: Float = 16000f
        set(value) {
            field = value
            wireRails()
        }

    internal fun wireRails() {
        val rest = pitch * (1f - preload)
        val k = stiffness
        val ks = shearStiffness
        val diagonal = sqrt(diameter * diameter + pitch * pitch)
        for (c in 0 until coils) for (j in 0 until perCoil) {
            val i = first(c) + j
            val opposite = 1 - j
            constraints.setSlot(i, 1, if (c > 0) i - perCoil else -1, if (c > 0) rest else 0f, if (c > 0) k else 0f)
            constraints.setSlot(i, 2, if (c < coils - 1) i + perCoil else -1, if (c < coils - 1) rest else 0f, if (c < coils - 1) k else 0f)
            val below = c > 0 && ks > 0f
            val above = c < coils - 1 && ks > 0f
            constraints.setSlot(i, 0, if (below) first(c - 1) + opposite else -1, if (below) diagonal else 0f, if (below) ks else 0f)
            constraints.setSlot(i, 3, if (above) first(c + 1) + opposite else -1, if (above) diagonal else 0f, if (above) ks else 0f)
        }
    }

    /** Centre of [coil]: the mean of its particles. */
    fun centre(coil: Int): Vec3 {
        val a = first(coil)
        var x = 0f
        var y = 0f
        var z = 0f
        for (j in 0 until perCoil) {
            x += state.x[a + j]; y += state.y[a + j]; z += state.z[a + j]
        }
        return vec(x / perCoil, y / perCoil, z / perCoil)
    }

    /** Mean position of every particle: the slinky's centre of mass, since they have equal mass. */
    fun centroid(): Vec3 = synchronized(lock) {
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for (i in base until end) {
            x += state.x[i]; y += state.y[i]; z += state.z[i]
        }
        val n = (perCoil * coils).toDouble()
        vec((x / n).toFloat(), (y / n).toFloat(), (z / n).toFloat())
    }

    /** Total kinetic energy per unit mass this frame, from the Verlet displacements and [dt]. */
    fun kineticEnergy(dt: Float): Float = synchronized(lock) {
        var e = 0.0
        for (i in base until end) {
            val vx = (state.x[i] - state.px[i]) / dt
            val vy = (state.y[i] - state.py[i]) / dt
            val vz = (state.z[i] - state.pz[i]) / dt
            e += 0.5 * (vx * vx + vy * vy + vz * vz)
        }
        e.toFloat()
    }

    /** True once any particle has stopped being finite; see [SimdVerlet.diverged]. */
    val diverged: Boolean get() = (base until end).any { !state.x[it].isFinite() || !state.y[it].isFinite() || !state.z[it].isFinite() }

    /** Holds [coil] in a hand: zero inverse mass on all its particles. [release] lets go. */
    fun hold(coil: Int) = synchronized(lock) {
        val invMass = requireNotNull(state.invMass) { "cannot hold a coil: this State3 has no invMass array" }
        for (j in 0 until perCoil) invMass[first(coil) + j] = 0f
    }

    /** Gives [coil] back its mass, keeping whatever velocity the hand gave it on the last [moveHeld]. */
    fun release(coil: Int) = synchronized(lock) {
        val invMass = requireNotNull(state.invMass) { "cannot release a coil: this State3 has no invMass array" }
        for (j in 0 until perCoil) invMass[first(coil) + j] = 1f / mass
    }

    /** True if [coil] is held. */
    fun isHeld(coil: Int): Boolean = (state.invMass?.get(first(coil)) ?: 1f) == 0f

    /**
     * Places [coil] with its centre at ([x], [y], 0) and its first particle at [angle] radians from
     * +x in the xy plane. The previous position is left where it was, so a held coil moved a little
     * every frame carries the velocity of the hand that moves it.
     */
    fun moveHeld(coil: Int, x: Float, y: Float, angle: Float) = synchronized(lock) {
        val ux = cos(angle)
        val uy = sin(angle)
        for (j in 0 until perCoil) {
            val (a, b) = ringOffset(j)
            val i = first(coil) + j
            state.x[i] = x + ux * a
            state.y[i] = y + uy * a
            state.z[i] = b
        }
    }

    /** Rim [j]'s offset from its coil's centre along the coil, in the xy plane. */
    private fun ringOffset(j: Int): Pair<Float, Float> = Pair(if (j == 0) -diameter * 0.5f else diameter * 0.5f, 0f)

    /**
     * Steps the whole simulation through one frame of [dt] in [substeps] substeps with this
     * slinky's constraint set — like [Strand.step], it steps *everything* in [sim].
     */
    fun step(
        dt: Float = 1f / 60f,
        gravity: Float = 146f,
        iterations: Int = 1
    ) = synchronized(lock) {
        constraints.ensureCapacity(state.size)
        val h = dt / max(1, substeps)
        val damping = Math.pow(velocityKeptPerSecond.toDouble(), h.toDouble()).toFloat()
        // Fraction of relative velocity removed per substep, from the per-second figure.
        val internal = 1f - Math.pow((1f - internalDamping).toDouble(), h.toDouble()).toFloat()
        repeat(max(1, substeps)) {
            sim.step(h, damping, gravity, iterations, constraints)
            if (internal > 0f) dampInternally(internal * 0.5f)
            if (inelasticCoils) stopTouchingCoils()
        }
    }

    // ------------------------------------------------------------------------------------------
    // The coil pass
    // ------------------------------------------------------------------------------------------

    private fun w(i: Int): Float = state.invMass?.get(i) ?: 1f

    private val normal = FloatArray(3)

    /** Coil [c]'s unit normal, pointing toward coil c + 1, into [normal]. False if degenerate. */
    private fun normalOf(c: Int): Boolean {
        val s = state
        val a = first(c)
        val ux = s.x[a + 1] - s.x[a]
        val uy = s.y[a + 1] - s.y[a]
        val l = sqrt(ux * ux + uy * uy)
        if (l < 1e-9f) return false
        normal[0] = uy / l; normal[1] = -ux / l; normal[2] = 0f
        return true
    }

    /** Equal-and-opposite distance correction between [i] and [j] toward [rest], position only. */
    private fun holdDistance(i: Int, j: Int, rest: Float) {
        val s = state
        val wi = w(i)
        val wj = w(j)
        if (wi + wj == 0f) return
        val dx = s.x[j] - s.x[i]; val dy = s.y[j] - s.y[i]; val dz = s.z[j] - s.z[i]
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        if (l < 1e-9f) return
        val corr = (l - rest) / l / (wi + wj)
        s.x[i] += dx * corr * wi; s.y[i] += dy * corr * wi; s.z[i] += dz * corr * wi
        s.x[j] -= dx * corr * wj; s.y[j] -= dy * corr * wj; s.z[j] -= dz * corr * wj
    }

    internal fun project() {
        val s = state
        val limit = pitch
        repeat(max(1, passes)) {
            // Each coil's rung held at the diameter.
            for (c in 0 until coils) holdDistance(first(c), first(c) + 1, diameter)
            // Contact: each particle of coil c + 1 rests at least one pitch above coil c's face.
            for (c in 0 until coils - 1) contactOnFace(c, limit)
            restingOn?.let { set ->
                set.relax(s)
                relaxEdges(set)
            }
        }
    }

    /**
     * The side-on contact between coil [c] and coil c + 1, as a constraint with its whole gradient.
     *
     * Rim `k` of coil c + 1 must sit at least [pitch] above coil c's face: `C = (x_k - x_i) · n - pitch >= 0`,
     * with `x_i` the matching rim of coil c and `n` coil c's normal. That normal turns with coil c's
     * rung, so the constraint has a gradient at *both* of coil c's rims, not only at `x_i`: pushing
     * on the face at a point `s = (x_k - x_i) · t` along the rung from `x_i` turns the rung like a
     * lever. Every gradient lies along `n` and they sum to zero, so the push conserves momentum and
     * angular momentum and, being a projection, can only take energy out.
     *
     * The first version pushed `x_i` and `x_k` apart along `n` and left the rung's turning out. It
     * did work on the spring at every contact — measured, a free slinky's energy grew twentyfold in
     * two seconds — and that work, not the physics, was what kept the old gait going. Measuring
     * the gap as a plain distance instead conserves energy but is blind to direction: the coils of
     * a shut stack roll round each other's rims and the stack slides flat like a deck of cards.
     */
    private fun contactOnFace(c: Int, limit: Float) {
        val s = state
        val a = first(c)
        val b = a + 1
        for (side in 0 until 2) {
            val ux = s.x[b] - s.x[a]
            val uy = s.y[b] - s.y[a]
            val len = sqrt(ux * ux + uy * uy)
            if (len < 1e-9f) return
            val tx = ux / len
            val ty = uy / len
            val nx = ty
            val ny = -tx
            val i = a + side
            val k = i + perCoil
            val dx = s.x[k] - s.x[i]
            val dy = s.y[k] - s.y[i]
            val gap = dx * nx + dy * ny
            if (gap >= pitch) continue
            // d(gap)/d(rung) = -(d · t) n / |u|; the rung is u = x_b - x_a.
            val lever = (dx * tx + dy * ty) / len
            val gk = 1f
            val ga = (if (side == 0) -1f else 0f) + lever
            val gb = (if (side == 1) -1f else 0f) - lever
            val wa = w(a)
            val wb = w(b)
            val wk = w(k)
            val denom = wa * ga * ga + wb * gb * gb + wk * gk * gk
            if (denom < 1e-12f) continue
            val lambda = min(pitch - gap, limit) / denom
            s.x[a] += nx * lambda * wa * ga; s.y[a] += ny * lambda * wa * ga
            s.x[b] += nx * lambda * wb * gb; s.y[b] += ny * lambda * wb * gb
            s.x[k] += nx * lambda * wk * gk; s.y[k] += ny * lambda * wk * gk
        }
    }

    /**
     * Pulls each pair of neighbouring coils' matching particles toward their common velocity by
     * [fraction] — equal and opposite, so momentum is kept and only the relative motion is lost.
     */
    private fun dampInternally(fraction: Float) {
        val s = state
        for (i in base until end - perCoil) {
            val j = i + perCoil
            if (w(i) == 0f || w(j) == 0f) continue
            val dvx = ((s.x[j] - s.px[j]) - (s.x[i] - s.px[i])) * fraction
            val dvy = ((s.y[j] - s.py[j]) - (s.y[i] - s.py[i])) * fraction
            val dvz = ((s.z[j] - s.pz[j]) - (s.z[i] - s.pz[i])) * fraction
            s.px[i] -= dvx * 0.5f; s.py[i] -= dvy * 0.5f; s.pz[i] -= dvz * 0.5f
            s.px[j] += dvx * 0.5f; s.py[j] += dvy * 0.5f; s.pz[j] += dvz * 0.5f
        }
    }

    /** Where along each coil's rung the colliders also see it. */
    private val edgeSamples = floatArrayOf(0.25f, 0.5f, 0.75f)
    private val edgesPerCoil = 1
    private val samples: SimdVerlet.State3 by lazy { SimdVerlet.State3.empty(coils * edgesPerCoil * edgeSamples.size, withInvMass = false) }

    /**
     * The colliders against the coil between its particles, not only at them: a step's nosing
     * that slips between two particles would otherwise sit inside the coil with both of them
     * legal, and the slinky hangs across the riser like a coat on a peg. Each edge is sampled, the
     * samples are projected as particles of their own, and each correction goes back to the edge's
     * two ends by their barycentric weights — the least-squares split.
     */
    private fun relaxEdges(set: ColliderSet) {
        val s = state
        val q = samples
        val ts = edgeSamples
        val m = ts.size
        val e = edgesPerCoil
        val n = coils * e * m
        if (q.size != n) q.resize(n)
        fun ends(c: Int, edge: Int): Pair<Int, Int> {
            val a = first(c)
            return Pair(a, a + 1)
        }
        for (c in 0 until coils) for (edge in 0 until e) for (k in 0 until m) {
            val (i0, i1) = ends(c, edge)
            val t = ts[k]
            val j = (c * e + edge) * m + k
            q.x[j] = s.x[i0] + (s.x[i1] - s.x[i0]) * t
            q.y[j] = s.y[i0] + (s.y[i1] - s.y[i0]) * t
            q.z[j] = s.z[i0] + (s.z[i1] - s.z[i0]) * t
            q.px[j] = s.px[i0] + (s.px[i1] - s.px[i0]) * t
            q.py[j] = s.py[i0] + (s.py[i1] - s.py[i0]) * t
            q.pz[j] = s.pz[i0] + (s.pz[i1] - s.pz[i0]) * t
        }
        val bx = q.x.copyOf(n)
        val by = q.y.copyOf(n)
        set.relax(q)
        // Every sample that a collider moved votes for a correction at the two particles its edge
        // runs between, weighted by how near it is to each; a particle takes the weighted *mean* of
        // its votes. Adding them instead (the least-squares split per sample, summed) moved a rung
        // lying flat on a tread out by up to 2.6 times its penetration — three samples, each
        // pushing both ends the whole way — and every landing bounced back up with energy it never had.
        val cx = corrX
        val cy = corrY
        val cw = corrW
        for (c in 0 until coils) for (edge in 0 until e) for (k in 0 until m) {
            val j = (c * e + edge) * m + k
            val dx = q.x[j] - bx[j]
            val dy = q.y[j] - by[j]
            if (dx == 0f && dy == 0f) continue
            val (i0, i1) = ends(c, edge)
            val t = ts[k]
            val wa = 1f - t
            val wb = t
            val a = i0 - base
            val b = i1 - base
            cx[a] += dx * wa; cy[a] += dy * wa; cw[a] += wa
            cx[b] += dx * wb; cy[b] += dy * wb; cw[b] += wb
        }
        for (p in 0 until perCoil * coils) {
            val wsum = cw[p]
            if (wsum == 0f) continue
            val i = base + p
            if (w(i) != 0f) {
                s.x[i] += cx[p] / wsum
                s.y[i] += cy[p] / wsum
            }
            cx[p] = 0f; cy[p] = 0f; cw[p] = 0f
        }
    }

    private val corrX by lazy { FloatArray(perCoil * coils) }
    private val corrY by lazy { FloatArray(perCoil * coils) }
    private val corrW by lazy { FloatArray(perCoil * coils) }

    /**
     * Coils that touch stop against each other: for every particle pair at contact distance, the
     * approaching part of their relative velocity along the lower coil's normal is removed, and
     * [coilFriction] of their sliding. Without this the shut stack is slightly springy and hands
     * back the energy every landing should lose.
     */
    private fun stopTouchingCoils() {
        val s = state
        val touch = pitch * (1f + contactSlack)
        for (c in 0 until coils - 1) {
            if (!normalOf(c)) continue
            val nx = normal[0]; val ny = normal[1]; val nz = normal[2]
            val a = first(c)
            for (jj in 0 until perCoil) {
                val i = a + jj
                val j = i + perCoil
                val gap = (s.x[j] - s.x[i]) * nx + (s.y[j] - s.y[i]) * ny + (s.z[j] - s.z[i]) * nz
                if (gap > touch) continue
                val wi = w(i)
                val wj = w(j)
                val ws = wi + wj
                if (ws == 0f) continue
                val rvx = (s.x[j] - s.px[j]) - (s.x[i] - s.px[i])
                val rvy = (s.y[j] - s.py[j]) - (s.y[i] - s.py[i])
                val rvz = (s.z[j] - s.pz[j]) - (s.z[i] - s.pz[i])
                val vn = rvx * nx + rvy * ny + rvz * nz
                val cn = if (vn < 0f) vn else 0f
                val ex = nx * cn + (rvx - vn * nx) * coilFriction
                val ey = ny * cn + (rvy - vn * ny) * coilFriction
                val ez = nz * cn + (rvz - vn * nz) * coilFriction
                // v = x - px: i takes on its share of the relative velocity removed, j gives up its share.
                s.px[i] -= ex * wi / ws; s.py[i] -= ey * wi / ws; s.pz[i] -= ez * wi / ws
                s.px[j] += ex * wj / ws; s.py[j] += ey * wj / ws; s.pz[j] += ez * wj / ws
            }
        }
    }

    /**
     * The spring as coloured lines, one per coil, running red to violet from coil 0 — the side
     * view of a rainbow slinky.
     */
    fun lines(thickness: Double = 2.2, rails: Boolean = true): List<FLine> = synchronized(lock) {
        val out = ArrayList<FLine>(coils + 2)
        if (rails) for (j in listOf(0, perCoil / 2)) {
            val rail = FLine()
            for (c in 0 until coils) {
                val i = first(c) + j
                if (c == 0) rail.moveTo(state.x[i], state.y[i], 0f) else rail.lineTo(state.x[i], state.y[i], 0f)
            }
            rail.color = vec(0.55, 0.6, 0.7, 0.35)
            rail.thickness = thickness * 0.4
            out += rail
        }
        for (c in 0 until coils) {
            val a = first(c)
            val line = FLine()
            line.moveTo(state.x[a], state.y[a], 0f)
            for (j in 1 until perCoil) line.lineTo(state.x[a + j], state.y[a + j], 0f)
            line.color = rainbow(c.toFloat() / max(1, coils - 1))
            line.thickness = thickness
            out += line
        }
        out
    }

    /** Unregisters the coil pass and clears this slinky's constraint slots; the particles stay. */
    fun remove() = synchronized(lock) {
        sim.removePositionProjection(name)
        for (i in base until end) constraints.clearSlots(i)
    }

    companion object {
        /** Red at 0 through to violet at 1, at full saturation. */
        fun rainbow(t: Float): Vec4 {
            val h = (t.coerceIn(0f, 1f) * 0.8f) * 6f
            val k = h.toInt().coerceIn(0, 5)
            val f = h - k
            val (r, g, b) = when (k) {
                0 -> Triple(1f, f, 0f)
                1 -> Triple(1f - f, 1f, 0f)
                2 -> Triple(0f, 1f, f)
                3 -> Triple(0f, 1f - f, 1f)
                4 -> Triple(f, 0f, 1f)
                else -> Triple(1f, 0f, 1f - f)
            }
            return vec(r.toDouble(), g.toDouble(), b.toDouble(), 1.0)
        }

        /**
         * Appends a shut slinky standing on its coil 0, centred on [x] with coil 0 at height [y]
         * and the stack rising toward -y (the solver's "up", against `gravity`). See [Slinky].
         *
         * @param stiffness rail spring constant between neighbouring rims.
         * @param preload fraction of [pitch] the rails are pre-shortened by.
         * @param shearStiffness the crossed diagonals' spring constant; see [Slinky.shearStiffness].
         * @param constraints constraint set to wire into; defaults to [SimdVerlet.constraints4].
         */
        fun addTo(
            sim: SimdVerlet,
            coils: Int = 40,
            diameter: Float = 1f,
            pitch: Float = 0.025f,
            stiffness: Float = 7000f,
            preload: Float = 0.7f,
            mass: Float = 1f,
            x: Float = 0f,
            y: Float = 0f,
            constraints: SimdVerlet.DirectedConstraints4? = sim.constraints4,
            name: String? = null,
            shearStiffness: Float = 16000f,
        ): Slinky {
            require(coils >= 2) { "a slinky needs at least 2 coils, got $coils" }
            require(diameter > 0f && pitch > 0f && mass > 0f) { "diameter, pitch and mass must be > 0" }
            require(mass == 1f || sim.state.invMass != null) { "mass != 1 needs a State3 with an invMass array" }
            val base = sim.size
            val perCoil = 2
            val m = mass
            sim.state.ensureCapacity(base + perCoil * coils)
            val set = (constraints ?: SimdVerlet.DirectedConstraints4.ofCapacity(base + perCoil * coils))
            val slinky = Slinky(sim, coils, base, diameter, pitch, m, set, name ?: "slinky@$base")
            for (c in 0 until coils) {
                val cy = y - pitch * 0.5f - c * pitch
                for (j in 0 until perCoil) sim.addParticle(x0 = x, y0 = cy, invMass0 = 1f / m)
                slinky.moveHeld(c, x, cy, 0f)
                for (j in 0 until perCoil) {
                    val i = base + perCoil * c + j
                    sim.state.px[i] = sim.state.x[i]; sim.state.py[i] = sim.state.y[i]; sim.state.pz[i] = sim.state.z[i]
                }
            }
            set.ensureCapacity(sim.size)
            sim.constraints4 = set
            for (i in base until base + perCoil * coils) set.clearSlots(i)
            slinky.preload = preload
            slinky.shearStiffness = shearStiffness
            slinky.stiffness = stiffness
            sim.addPositionProjection(slinky.name) { _, _ -> slinky.project() }
            return slinky
        }
    }
}

/** Appends a slinky to this simulation; see [Slinky.addTo]. */
fun SimdVerlet.addSlinky(
    coils: Int = 40,
    diameter: Float = 1f,
    pitch: Float = 0.025f,
    stiffness: Float = 7000f,
    preload: Float = 0.7f,
    mass: Float = 1f,
    x: Float = 0f,
    y: Float = 0f,
    constraints: SimdVerlet.DirectedConstraints4? = this.constraints4,
    name: String? = null,
    shearStiffness: Float = 16000f,
): Slinky = Slinky.addTo(this, coils, diameter, pitch, stiffness, preload, mass, x, y, constraints, name, shearStiffness)

/**
 * A [Slinky] on a [StaircaseCollider]: the scene for "walk down the stairs" and, with a
 * [stepPeriod], for "walk down an up escalator".
 *
 * The slinky starts shut on step 0 near its edge. For the first [handTime] seconds a hand holds
 * its top coil and lifts it over in an arc onto the next step down, the way a person starts one;
 * then it lets go, and from there the slinky is on its own. If the scene has a [stepPeriod], the
 * escalator starts at that moment.
 *
 *     val scene = SlinkyOnStairs()
 *     loop { scene.step(); showPlane(scene.lines()) }
 *
 * #### The walk
 *
 * The top coils fall over the edge and pull the rest after them; the bottom coils lift, arc over
 * and land past the others, and that end is now the top. Each flip spends the drop in height on
 * the spring, the landing and the air. On these stairs the walk is self-sustaining: the slinky
 * speeds up into a stride that covers two or three steps a flip, at about ten steps a second, and
 * keeps that rhythm for a hundred flips or more. With taller steps, longer treads or a weaker
 * spring it can also lose the rhythm, stand still on a tread, or tumble over the nosing — the
 * same ways a real slinky fails.
 *
 * #### The escalator
 *
 * An escalator moving at a constant speed is an inertial frame: ride along with the treads and
 * they are still stairs. Gravity, the spring, the coil contacts and the friction against the
 * treads (computed relative to the moving surface; see [Collider.velocityX]) are the same in that
 * frame, so the escalator is started with every particle given the treads' velocity, and the one
 * force that tells the two frames apart is drag against the air, [air]. It is weak, so the walk
 * keeps its own rhythm, and an escalator that is not matched to it either carries the slinky up
 * or is outrun by it.
 *
 * #### Measuring
 *
 * [flips] counts the times the two end coils swap which one is lower by more than half a rise.
 * [stepsDescended] is how many steps down the flight the slinky's centre of mass has gone
 * *relative to the treads*; on still stairs that is simply steps walked. [height] is the centre of
 * mass's world height, +y up.
 *
 * @param run tread depth, in coil diameters.
 * @param rise step height, in coil diameters.
 * @param stepPeriod seconds per step of escalator motion, from the moment the hand lets go; `0f`
 *   is still stairs.
 * @param air the fraction of its world velocity a particle keeps per second.
 * @param reach how far forward of the stack, in diameters, the hand puts the top coil down.
 */
class SlinkyOnStairs(
    run: Float = 1.5f,
    rise: Float = 1.1f,
    stepPeriod: Float = 0f,
    coils: Int = 40,
    stiffness: Float = 32000f,
    preload: Float = 0.5f,
    shearStiffness: Float = 16000f,
    friction: Float = 1f,
    air: Float = 0.9f,
    /** Seconds the hand takes to lift the top coil over; `0f` lets the stack stand as it is. */
    val handTime: Float = 0.6f,
    val reach: Float = 0.5f * run + 0.4f,
    /** How far above the straight line from start to finish the hand's arc rises, in diameters. */
    val lift: Float = 1.5f,
) {
    val sim = SimdVerlet(SimdVerlet.State3.empty())
    val stairs = StaircaseCollider(run, rise).also {
        it.friction = friction
        it.margin = 0.0125f
    }
    val slinky: Slinky

    /**
     * The stairs' projection pass, also handed to the slinky as [Slinky.restingOn] so the stairs
     * have the last word after each coil pass; otherwise the coil contact pushes coil 0 back into
     * the tread a little every pass and the stack sinks through the step under its own weight.
     */
    val colliders: ColliderSet

    private val startX: Float = run - 0.65f

    /** The escalator's step period once the hand has let go. */
    private var escalatorPeriod = stepPeriod

    /** Simulated seconds since the start. */
    var time = 0f
        private set

    init {
        // The stairs' pass is registered first, so they move before the coil pass projects
        // against them; the other way round, the coil pass meets the treads where they were a
        // substep ago, and on an escalator every contact is a third of a coil off.
        colliders = sim.addColliders(stairs)
        slinky = sim.addSlinky(coils = coils, stiffness = stiffness, preload = preload, x = startX, y = -stairs.margin,
            shearStiffness = shearStiffness)
        slinky.velocityKeptPerSecond = air
        slinky.restingOn = colliders
        if (handTime > 0f) slinky.hold(coils - 1)
    }

    private val top get() = slinky.coils - 1
    private val from by lazy { slinky.centre(top) }
    private var letGo = false

    /** Where the hand has the top coil's centre and turn at time [t]: an arc over onto the next step. */
    private fun hand(t: Float): Triple<Float, Float, Float> {
        val u = (t / handTime).coerceIn(0f, 1f)
        val e = 0.5f - 0.5f * cos(Math.PI.toFloat() * u)
        val x = from.x + reach * e
        val y = from.y + (stairs.rise - slinky.pitch * 0.5f - from.y) * e - lift * sin(Math.PI.toFloat() * u)
        return Triple(x, y, Math.PI.toFloat() * e)
    }

    /**
     * Moves the held top coil one substep of [h] further along the hand's arc. A held particle
     * still integrates its own velocity, so it is given exactly the velocity that carries it from
     * where it is to the hand's next position in one substep.
     */
    private fun carry(h: Float) {
        val st = sim.state
        val n = slinky.perCoil
        val ox = FloatArray(n) { st.x[slinky[top, it]] }
        val oy = FloatArray(n) { st.y[slinky[top, it]] }
        val oz = FloatArray(n) { st.z[slinky[top, it]] }
        val (x, y, angle) = hand(time + h)
        slinky.moveHeld(top, x, y, angle)
        for (k in 0 until n) {
            val i = slinky[top, k]
            st.px[i] = 2f * ox[k] - st.x[i]; st.py[i] = 2f * oy[k] - st.y[i]; st.pz[i] = 2f * oz[k] - st.z[i]
            st.x[i] = ox[k]; st.y[i] = oy[k]; st.z[i] = oz[k]
        }
    }

    /** Lets go of the top coil and, on an escalator, starts it, giving everything the treads' velocity. */
    private fun release(h: Float) {
        letGo = true
        if (slinky.isHeld(top)) slinky.release(top)
        if (escalatorPeriod == 0f) return
        stepPeriod = escalatorPeriod
        val st = sim.state
        for (i in slinky.base until slinky.end) {
            st.px[i] -= stairs.velocityX * h
            st.py[i] -= stairs.velocityY * h
        }
    }

    /** The escalator's step period; `0f` stops it. Live, once the hand has let go. */
    var stepPeriod: Float
        get() = if (letGo) stairs.stepPeriod else escalatorPeriod
        set(value) {
            if (!letGo) { escalatorPeriod = value; return }
            travelled += stairsTravelSteps()
            travelStart = time
            stairs.stepPeriod = value
        }

    /** Fraction of its world velocity a particle keeps per second. Live. */
    var air: Float
        get() = slinky.velocityKeptPerSecond
        set(value) {
            slinky.velocityKeptPerSecond = value.coerceIn(0f, 1f)
        }

    private var travelled = 0f
    private var travelStart = 0f
    private fun stairsTravelSteps(): Float = if (stairs.stepPeriod == 0f) 0f else (time - travelStart) / stairs.stepPeriod

    /** Steps the escalator has carried a tread up since the hand let go. */
    val stairsTravelled: Float get() = travelled + stairsTravelSteps()

    /** Steps down the flight, relative to the treads, that the slinky's centre of mass has gone. */
    val stepsDescended: Float get() = (slinky.centroid().x - startX) / stairs.run + stairsTravelled

    /** World height of the centre of mass, +y up (the negated solver y). */
    val height: Float get() = -slinky.centroid().y

    /** End-over-end flips so far; see the class KDoc. */
    var flips = 0
        private set

    /** The time of each flip, in simulated seconds. */
    val flipTimes = ArrayList<Float>()

    private var lowerEnd = 0

    /**
     * Substeps per simulated second. [step] runs whatever frame it is given as substeps of this
     * length, and the hand moves per substep, so the motion does not depend on the frame: a frame
     * of 1/300 s, shown at 60 frames a second, is the same walk as frames of 1/60 s at a fifth of
     * the speed.
     */
    var substepRate = 600f

    /** One frame of [dt], rounded to a whole number of substeps of `1 / substepRate`. */
    fun step(dt: Float = 1f / 60f, gravity: Float = 146f) {
        val n = max(1, Math.round(dt * substepRate))
        val h = 1f / substepRate
        slinky.substeps = 1
        repeat(n) {
            if (!letGo) {
                if (time < handTime) carry(h) else release(h)
            }
            slinky.step(h, gravity = gravity)
            time += h
        }
        val y0 = slinky.centre(0).y
        val yN = slinky.centre(top).y
        val half = 0.5f * stairs.rise
        val now = if (y0 > yN + half) 1 else if (yN > y0 + half) -1 else lowerEnd
        if (lowerEnd != 0 && now != lowerEnd) {
            flips++
            flipTimes += time
        }
        lowerEnd = now
    }

    /**
     * The stairs as a profile around the slinky and the slinky in rainbow. [halfWidth] is how
     * far either side of the slinky's centre the stairs are drawn.
     */
    fun lines(halfWidth: Float = 5f): List<FLine> {
        val c = slinky.centroid()
        stairs.drawFromX = c.x - halfWidth
        stairs.drawToX = c.x + halfWidth
        return stairs.outline(vec(0.62, 0.68, 0.76, 0.9), 1.6) + slinky.lines()
    }
}
