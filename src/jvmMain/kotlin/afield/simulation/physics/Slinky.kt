package afield.simulation.physics

import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.thickness
import afield.math.linalg.Vec3
import afield.math.linalg.Vec4
import afield.math.linalg.vec
import kotlin.math.abs
import kotlin.math.atan2
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
 *   contact is one-sided and measured along the coil's own normal at each rim, so a stack of shut
 *   coils is rigid in bending — bending it would push one side of a coil through the next — while
 *   an open stretch bends freely. Bending stiffness therefore comes from the contact and the
 *   preload together, as it does in the toy. Contact is inelastic and goes in [SimdVerlet.positionProjections]
 *   with the rest of the coil pass.
 * - **Coils stay coils.** Each coil's rung, `A` to `B`, is held at the [diameter] exactly, and
 *   [alignment] turns every coil a fraction of the way toward square to the local centreline per
 *   pass — the shear stiffness of the helix, which keeps its coils across the axis rather than
 *   along it.
 *
 * The coil pass is a projection, not a force, for the same reason as [Strand]'s bending: a rigid
 * rung or a contact as a spring would sit on the `stiffness * iterations` scale with a divergence
 * ceiling in `dt`. Corrections are applied to position *and* previous position (a teleport, so the
 * pass cannot inject velocity) and the approaching part of a contact's relative velocity is then
 * removed. Each correction is clamped to a fraction of the coil diameter per pass, so one bad frame
 * (a coil folded back over its neighbour by a violent start) recovers over a few frames instead of
 * exploding.
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
    /** Coil diameter: the ring is held at this size exactly. */
    val diameter: Float,
    /** Wire thickness: the closest two neighbouring coils can come, at each particle. */
    val pitch: Float,
    /** Mass of one particle. */
    val mass: Float,
    val constraints: SimdVerlet.DirectedConstraints4,
    val name: String,
    /**
     * Particles around each turn. `2` is the side-on model: the coil's two rims as seen edge-on,
     * in the xy plane. `4`, `6`, `8`, ... make each coil a ring in 3D, lying across the axis with
     * its first particle on +x and its second a quarter or less of the way round toward +z, so a
     * coil can carry a real bending moment and lands face to face on the next.
     */
    val perCoil: Int = 2,
) {
    private val lock = Any()
    private val state get() = sim.state
    private val ring = perCoil > 2

    init {
        require(perCoil == 2 || (perCoil >= 4 && perCoil % 2 == 0)) { "perCoil must be 2 or an even number >= 4, got $perCoil" }
    }

    /** One past this slinky's last particle index. */
    val end: Int get() = base + perCoil * coils

    /** Particle index of [coil]'s particle [k] (`0 until perCoil`; with 2, 0 = rim A and 1 = rim B). */
    operator fun get(coil: Int, k: Int): Int {
        require(coil in 0 until coils) { "coil $coil out of 0 until $coils" }
        require(k in 0 until perCoil) { "k must be in 0 until $perCoil" }
        return base + perCoil * coil + k
    }

    private fun first(c: Int) = base + perCoil * c

    /**
     * Fraction of the offset between neighbouring coils' centres, across the axis, taken out per
     * pass: the helix's shear stiffness. `0f` lets coils slide over each other freely.
     */
    var alignment: Float = 0.3f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

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

    /** Rail spring stiffness between neighbouring coils, per particle. Live: rewrites the rail slots. */
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

    internal fun wireRails() {
        val rest = pitch * (1f - preload)
        // Split across the particles of a turn, so the spring as a whole has the same constant
        // whatever perCoil is.
        val k = stiffness * 2f / perCoil
        for (c in 0 until coils) for (j in 0 until perCoil) {
            val i = first(c) + j
            constraints.setSlot(i, 1, if (c > 0) i - perCoil else -1, if (c > 0) rest else 0f, if (c > 0) k else 0f)
            constraints.setSlot(i, 2, if (c < coils - 1) i + perCoil else -1, if (c < coils - 1) rest else 0f, if (c < coils - 1) k else 0f)
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

    /** Particle [j]'s offset in its coil's own frame: along the coil's in-plane x, and along z. */
    private fun ringOffset(j: Int): Pair<Float, Float> {
        if (!ring) return Pair(if (j == 0) -diameter * 0.5f else diameter * 0.5f, 0f)
        val t = 2.0 * Math.PI * j / perCoil
        return Pair((cos(t) * diameter * 0.5).toFloat(), (sin(t) * diameter * 0.5).toFloat())
    }

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
        if (!ring) {
            val ux = s.x[a + 1] - s.x[a]
            val uy = s.y[a + 1] - s.y[a]
            val l = sqrt(ux * ux + uy * uy)
            if (l < 1e-9f) return false
            normal[0] = uy / l; normal[1] = -ux / l; normal[2] = 0f
            return true
        }
        val h = perCoil / 2
        val q = perCoil / 4
        // (p0 - p_half) x (p_quarter - p_threeQuarter): for the rest ring, x × z = -y, toward coil c + 1.
        val ax = s.x[a] - s.x[a + h]; val ay = s.y[a] - s.y[a + h]; val az = s.z[a] - s.z[a + h]
        val q1 = a + q; val q3 = a + (q + h) % perCoil
        val bx = s.x[q1] - s.x[q3]; val by = s.y[q1] - s.y[q3]; val bz = s.z[q1] - s.z[q3]
        val nx = ay * bz - az * by
        val ny = az * bx - ax * bz
        val nz = ax * by - ay * bx
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l < 1e-12f) return false
        normal[0] = nx / l; normal[1] = ny / l; normal[2] = nz / l
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

    /** Separates [i] and [j] by [c] along [normal], mass-weighted, on position only. */
    private fun push(i: Int, j: Int, c: Float) {
        val wi = w(i)
        val wj = w(j)
        val ws = wi + wj
        if (ws == 0f) return
        val fi = wi / ws
        val fj = wj / ws
        val s = state
        val nx = normal[0]; val ny = normal[1]; val nz = normal[2]
        s.x[i] -= nx * c * fi; s.y[i] -= ny * c * fi; s.z[i] -= nz * c * fi
        s.x[j] += nx * c * fj; s.y[j] += ny * c * fj; s.z[j] += nz * c * fj
    }

    private val chord1 by lazy { if (ring) (diameter * sin(Math.PI / perCoil)).toFloat() else diameter }
    private val chord2 by lazy { if (ring) (diameter * sin(2.0 * Math.PI / perCoil)).toFloat() else diameter }

    internal fun project() {
        val s = state
        val limit = pitch
        repeat(max(1, passes)) {
            // The ring: each coil held at its shape, by its edges, its second neighbours and its
            // diameters (with 2 particles, just the one rung).
            for (c in 0 until coils) {
                val a = first(c)
                if (!ring) { holdDistance(a, a + 1, diameter); continue }
                for (j in 0 until perCoil) {
                    holdDistance(a + j, a + (j + 1) % perCoil, chord1)
                    holdDistance(a + j, a + (j + 2) % perCoil, chord2)
                    if (j < perCoil / 2) holdDistance(a + j, a + j + perCoil / 2, diameter)
                }
            }
            // Shear: neighbouring centres stay on one axis. The offset of coil c + 1's centre from
            // coil c's, across coil c's normal, is taken out, a fraction `alignment` per pass.
            if (alignment > 0f) for (c in 0 until coils - 1) {
                if (!normalOf(c)) continue
                val c0 = centre(c)
                val c1 = centre(c + 1)
                var ox = c1.x - c0.x; var oy = c1.y - c0.y; var oz = c1.z - c0.z
                val along = ox * normal[0] + oy * normal[1] + oz * normal[2]
                ox -= along * normal[0]; oy -= along * normal[1]; oz -= along * normal[2]
                val ol = sqrt(ox * ox + oy * oy + oz * oz)
                if (ol < 1e-9f) continue
                val amount = min(ol * alignment, limit)
                shiftCoils(c, ox / ol, oy / ol, oz / ol, amount)
            }
            // Contact: particle to particle along the lower coil's normal, at least one pitch apart.
            for (c in 0 until coils - 1) {
                if (!normalOf(c)) continue
                val a = first(c)
                for (j in 0 until perCoil) {
                    val i = a + j
                    val k = i + perCoil
                    val gap = (s.x[k] - s.x[i]) * normal[0] + (s.y[k] - s.y[i]) * normal[1] + (s.z[k] - s.z[i]) * normal[2]
                    if (gap < pitch) push(i, k, min(pitch - gap, limit))
                }
            }
            restingOn?.let { set ->
                set.relax(s)
                relaxEdges(set)
            }
        }
    }

    /** Moves coil [c] by `+d * amount` and coil c + 1 by `-d * amount`, split by mass. */
    private fun shiftCoils(c: Int, dx: Float, dy: Float, dz: Float, amount: Float) {
        val s = state
        val a = first(c)
        val b = a + perCoil
        var wl = 0f
        var wu = 0f
        for (j in 0 until perCoil) { wl += w(a + j); wu += w(b + j) }
        val ws = wl + wu
        if (ws == 0f) return
        val fl = wl / ws * amount
        val fu = wu / ws * amount
        for (j in 0 until perCoil) {
            if (w(a + j) != 0f) { s.x[a + j] += dx * fl; s.y[a + j] += dy * fl; s.z[a + j] += dz * fl }
            if (w(b + j) != 0f) { s.x[b + j] -= dx * fu; s.y[b + j] -= dy * fu; s.z[b + j] -= dz * fu }
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

    /** Where between neighbouring particles of a coil the colliders also see it (2: along the rung). */
    private val edgeSamples: FloatArray get() = if (ring) floatArrayOf(0.5f) else floatArrayOf(0.25f, 0.5f, 0.75f)
    private val edgesPerCoil: Int get() = if (ring) perCoil else 1
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
            return if (ring) Pair(a + edge, a + (edge + 1) % perCoil) else Pair(a, a + 1)
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
        for (c in 0 until coils) for (edge in 0 until e) for (k in 0 until m) {
            val j = (c * e + edge) * m + k
            val dx = q.x[j] - bx[j]
            val dy = q.y[j] - by[j]
            if (dx == 0f && dy == 0f) continue
            val (i0, i1) = ends(c, edge)
            val t = ts[k]
            val wa = if (w(i0) == 0f) 0f else 1f - t
            val wb = if (w(i1) == 0f) 0f else t
            val norm = wa * wa + wb * wb
            if (norm == 0f) continue
            s.x[i0] += dx * wa / norm; s.y[i0] += dy * wa / norm
            s.x[i1] += dx * wb / norm; s.y[i1] += dy * wb / norm
        }
    }

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
     * view of a rainbow slinky. A ring is drawn as its outline projected onto the xy plane.
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
            if (ring) line.lineTo(state.x[a], state.y[a], 0f)
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
         * @param stiffness rail spring constant between neighbouring coils, for the whole turn.
         * @param preload fraction of [pitch] the rails are pre-shortened by.
         * @param perCoil particles around each turn; see [Slinky.perCoil].
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
            perCoil: Int = 2,
        ): Slinky {
            require(coils >= 2) { "a slinky needs at least 2 coils, got $coils" }
            require(diameter > 0f && pitch > 0f && mass > 0f) { "diameter, pitch and mass must be > 0" }
            require(mass == 1f || sim.state.invMass != null) { "mass != 1 needs a State3 with an invMass array" }
            val base = sim.size
            // Total mass is the same whatever perCoil is: each particle carries 2 / perCoil of a rim.
            val m = mass * 2f / perCoil
            sim.state.ensureCapacity(base + perCoil * coils)
            val set = (constraints ?: SimdVerlet.DirectedConstraints4.ofCapacity(base + perCoil * coils))
            val slinky = Slinky(sim, coils, base, diameter, pitch, m, set, name ?: "slinky@$base", perCoil)
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
    perCoil: Int = 2,
): Slinky = Slinky.addTo(this, coils, diameter, pitch, stiffness, preload, mass, x, y, constraints, name, perCoil)

/**
 * A [Slinky] on a [StaircaseCollider]: the scene for "walk down the stairs" and, with a
 * [stepPeriod], for "walk down an up escalator, forever".
 *
 * The slinky starts shut on step 0 near its edge and is tipped forward over it at [tipSpeed], the
 * flick a person gives one. From then on it is on its own.
 *
 * #### Why the air matters
 *
 * An endless escalator moving at a constant speed is an inertial frame: ride along with the treads
 * and they are still stairs. Gravity, the spring, the coil contacts and the friction against the
 * treads (computed relative to the moving surface; see [Collider.velocityX]) are all the same in
 * that frame, so *on its own the escalator's speed changes nothing about the walk*. The one force
 * that knows which frame is at rest is drag against the air, [Slinky.velocityKeptPerSecond]. It
 * is what couples the gait to the escalator: a slinky falling behind is carried up through the air
 * and the drag pushes it down the treads, one getting ahead is slowed. With enough of it, the
 * gait locks to the escalator over a range of speeds; with none, the slinky either stops or speeds
 * up step on step until it tumbles, as it would on still stairs. [air] sets it.
 *
 * #### Measuring
 *
 * [stepsDescended] is the score: how many steps down the flight the slinky's centre of mass has
 * gone *relative to the treads*. A slinky that matches the escalator keeps a constant world
 * position while this climbs by one per [stepPeriod]. [height] is the centre of mass's world
 * height, +y up, which is flat-with-a-ripple when the walk matches, rises when the escalator
 * carries a stalled slinky up, and falls when it tumbles away.
 *
 * The defaults are a matched walk, found by sweeping [stepPeriod]: `dev_docs/physics/SLINKY_ESCALATOR.md`
 * has the sweep.
 *
 * @param run tread depth, in coil diameters.
 * @param rise step height, in coil diameters.
 * @param stepPeriod seconds per step of escalator motion; `0f` is still stairs.
 * @param air the fraction of its world velocity a rim keeps per second; see the note above.
 */
class SlinkyOnStairs(
    run: Float = 2.5f,
    rise: Float = 1.5f,
    stepPeriod: Float = 0.575f,
    coils: Int = 40,
    stiffness: Float = 16000f,
    preload: Float = 0.7f,
    friction: Float = 0.6f,
    /** The speed the top of the stack is tipped forward at, in diameters per second, to start it. */
    val tipSpeed: Float = 6f,
    air: Float = 0.01f,
    // Particles around each turn; see Slinky.perCoil.
    perCoil: Int = 2,
) {
    val sim = SimdVerlet(SimdVerlet.State3.empty())
    val stairs = StaircaseCollider(run, rise).also {
        it.friction = friction
        it.margin = 0.0125f
        it.stepPeriod = stepPeriod
    }
    val slinky: Slinky

    /**
     * The stairs' projection pass. Registered *after* the slinky's coil pass, and handed to the
     * slinky as [Slinky.restingOn], so the stairs have the last word in every pass: the other way
     * round, the coil contact pushes coil 0 back into the tread a little every substep and the
     * stack sinks through the step under its own weight.
     */
    val colliders: ColliderSet

    private val startX: Float = run - 0.65f

    /** Simulated seconds since the start. */
    var time = 0f
        private set

    init {
        slinky = sim.addSlinky(coils = coils, stiffness = stiffness, preload = preload, x = startX, y = -stairs.margin, perCoil = perCoil)
        slinky.velocityKeptPerSecond = air
        colliders = sim.addColliders(stairs)
        slinky.restingOn = colliders
        if (tipSpeed != 0f) tip()
    }

    /** Sets every rim moving as if the stack were turning forward about its +x rim on coil 0. */
    private fun tip() {
        val pivotX = startX + slinky.diameter * 0.5f
        val pivotY = -stairs.margin
        val omega = tipSpeed / (slinky.coils * slinky.pitch)
        val h = 1f / (60f * slinky.substeps)
        val st = sim.state
        for (i in slinky.base until slinky.end) {
            // Clockwise on screen with y down: v = omega * (-ry, rx).
            val rx = st.x[i] - pivotX
            val ry = st.y[i] - pivotY
            st.px[i] = st.x[i] + ry * omega * h
            st.py[i] = st.y[i] - rx * omega * h
        }
    }

    /** The escalator's step period; `0f` stops it. Live. */
    var stepPeriod: Float
        get() = stairs.stepPeriod
        set(value) {
            travelled += stairsTravelSteps()
            travelStart = time
            stairs.stepPeriod = value
        }

    /** Fraction of its world velocity a rim keeps per second. Live; see the class KDoc. */
    var air: Float
        get() = slinky.velocityKeptPerSecond
        set(value) {
            slinky.velocityKeptPerSecond = value.coerceIn(0f, 1f)
        }

    private var travelled = 0f
    private var travelStart = 0f
    private fun stairsTravelSteps(): Float = if (stairs.stepPeriod == 0f) 0f else (time - travelStart) / stairs.stepPeriod

    /** Steps the escalator has carried a tread up since the start. */
    val stairsTravelled: Float get() = travelled + stairsTravelSteps()

    /** Steps down the flight, relative to the treads, that the slinky's centre of mass has gone. */
    val stepsDescended: Float get() = (slinky.centroid().x - startX) / stairs.run + stairsTravelled

    /** World height of the centre of mass, +y up (the negated solver y). */
    val height: Float get() = -slinky.centroid().y

    /** One frame of [dt], in [Slinky.substeps] substeps. */
    fun step(dt: Float = 1f / 60f, gravity: Float = 146f) {
        slinky.step(dt, gravity = gravity)
        time += dt
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
