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
    /** First particle index; coil `c` is particles `base + 2c` (rim A) and `base + 2c + 1` (rim B). */
    val base: Int,
    /** Coil diameter: the rung length, held exactly. */
    val diameter: Float,
    /** Wire thickness: the closest two neighbouring coils can come, at each rim. */
    val pitch: Float,
    /** Mass of one rim particle. */
    val mass: Float,
    val constraints: SimdVerlet.DirectedConstraints4,
    val name: String
) {
    private val lock = Any()
    private val state get() = sim.state

    /** One past this slinky's last particle index. */
    val end: Int get() = base + 2 * coils

    /** Particle index of [coil]'s rim [side] (0 = A, 1 = B). */
    operator fun get(coil: Int, side: Int): Int {
        require(coil in 0 until coils) { "coil $coil out of 0 until $coils" }
        require(side == 0 || side == 1) { "side must be 0 (A) or 1 (B)" }
        return base + 2 * coil + side
    }

    /**
     * Fraction of the way each coil is turned toward square to the local centreline, per pass.
     * `0f` lets coils shear over each other freely; `1f` snaps them square every pass.
     */
    var alignment: Float = 0.3f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    /** Coil passes per substep. More passes make the shut stack stiffer and cost linearly. */
    var passes: Int = 4

    /** Whether touching coils stop dead against each other; see [stopTouchingCoils]. */
    var inelasticCoils: Boolean = true

    /** How far past [pitch], as a fraction of it, two rims still count as touching. */
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

    /**
     * Fraction of its velocity a rim keeps per second against the world — air drag. `1f` (the
     * default) is none. It is the one force here that knows which frame is at rest, so it is also
     * the only thing that tells an escalator apart from still stairs; see [SlinkyOnStairs].
     */
    var velocityKeptPerSecond: Float = 1f

    /**
     * Fraction per second of the *relative* velocity between neighbouring coils that the spring
     * loses: the wire's internal friction. It cannot slow the slinky as a whole, only its
     * wobbling, so it is the same on a moving escalator as on still stairs.
     */
    var internalDamping: Float = 0.9f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    /** Rail spring stiffness between neighbouring coils. Live: rewrites the rail slots. */
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
        for (c in 0 until coils) for (side in 0..1) {
            val i = base + 2 * c + side
            constraints.setSlot(i, 1, if (c > 0) i - 2 else -1, if (c > 0) rest else 0f, if (c > 0) stiffness else 0f)
            constraints.setSlot(i, 2, if (c < coils - 1) i + 2 else -1, if (c < coils - 1) rest else 0f, if (c < coils - 1) stiffness else 0f)
        }
    }

    /** Centre of [coil]: the midpoint of its two rims. */
    fun centre(coil: Int): Vec3 {
        val a = this[coil, 0]
        val b = a + 1
        return vec((state.x[a] + state.x[b]) * 0.5f, (state.y[a] + state.y[b]) * 0.5f, 0f)
    }

    /** Mean position of every rim: the slinky's centre of mass, since the rims have equal mass. */
    fun centroid(): Vec3 = synchronized(lock) {
        var x = 0.0
        var y = 0.0
        for (i in base until end) {
            x += state.x[i]
            y += state.y[i]
        }
        val n = 2.0 * coils
        vec((x / n).toFloat(), (y / n).toFloat(), 0f)
    }

    /** Total kinetic energy per unit mass this frame, from the Verlet displacements and [dt]. */
    fun kineticEnergy(dt: Float): Float = synchronized(lock) {
        var e = 0.0
        for (i in base until end) {
            val vx = (state.x[i] - state.px[i]) / dt
            val vy = (state.y[i] - state.py[i]) / dt
            e += 0.5 * (vx * vx + vy * vy)
        }
        e.toFloat()
    }

    /** True once any rim has stopped being finite; see [SimdVerlet.diverged]. */
    val diverged: Boolean get() = (base until end).any { !state.x[it].isFinite() || !state.y[it].isFinite() }

    /**
     * Holds [coil] in a hand: zero inverse mass on both rims, so the simulation moves everything
     * else and [moveHeld] moves this. [release] lets go.
     */
    fun hold(coil: Int) = synchronized(lock) {
        val invMass = requireNotNull(state.invMass) { "cannot hold a coil: this State3 has no invMass array" }
        invMass[this[coil, 0]] = 0f
        invMass[this[coil, 1]] = 0f
    }

    /** Gives [coil] back its mass, keeping whatever velocity the hand gave it on the last [moveHeld]. */
    fun release(coil: Int) = synchronized(lock) {
        val invMass = requireNotNull(state.invMass) { "cannot release a coil: this State3 has no invMass array" }
        invMass[this[coil, 0]] = 1f / mass
        invMass[this[coil, 1]] = 1f / mass
    }

    /** True if [coil] is held. */
    fun isHeld(coil: Int): Boolean = (state.invMass?.get(this[coil, 0]) ?: 1f) == 0f

    /**
     * Places [coil] with its centre at ([x], [y]) and its rung at [angle] radians from +x (rim A
     * at `-diameter / 2` along it). The previous position is left where it was, so a held coil
     * moved a little every frame carries the velocity of the hand that moves it.
     */
    fun moveHeld(coil: Int, x: Float, y: Float, angle: Float) = synchronized(lock) {
        val a = this[coil, 0]
        val ux = cos(angle) * diameter * 0.5f
        val uy = sin(angle) * diameter * 0.5f
        state.x[a] = x - ux
        state.y[a] = y - uy
        state.x[a + 1] = x + ux
        state.y[a + 1] = y + uy
    }

    /**
     * Steps the whole simulation through one frame of [dt] in [substeps] substeps with this
     * slinky's constraint set — like [Strand.step], it steps *everything* in [sim].
     *
     * Damping is [velocityKeptPerSecond], converted per substep.
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

    /** Moves coil (a, b) by `+n * c` and coil (c0, c1) by `-n * c`, split by the coils' masses. */
    private fun shift(a: Int, b: Int, c0: Int, c1: Int, nx: Float, ny: Float, c: Float) {
        val wl = w(a) + w(b)
        val wu = w(c0) + w(c1)
        val ws = wl + wu
        if (ws == 0f) return
        val fl = wl / ws
        val fu = wu / ws
        val s = state
        if (w(a) != 0f) { s.x[a] += nx * c * fl; s.y[a] += ny * c * fl }
        if (w(b) != 0f) { s.x[b] += nx * c * fl; s.y[b] += ny * c * fl }
        if (w(c0) != 0f) { s.x[c0] -= nx * c * fu; s.y[c0] -= ny * c * fu }
        if (w(c1) != 0f) { s.x[c1] -= nx * c * fu; s.y[c1] -= ny * c * fu }
    }

    /**
     * Separates rims [i] and [j] by [c] along ([nx], [ny]), mass-weighted, on position only — the
     * standard position-based contact, so the Verlet velocity the correction implies is what stops
     * the approach and what carries support up a stack.
     */
    private fun push(i: Int, j: Int, nx: Float, ny: Float, c: Float) {
        val wi = w(i)
        val wj = w(j)
        val ws = wi + wj
        if (ws == 0f) return
        val fi = wi / ws
        val fj = wj / ws
        val s = state
        s.x[i] -= nx * c * fi; s.y[i] -= ny * c * fi
        s.x[j] += nx * c * fj; s.y[j] += ny * c * fj
    }

    internal fun project() {
        val s = state
        val limit = pitch
        repeat(max(1, passes)) {
            // Rungs: each coil exactly one diameter across. An equality constraint on position only,
            // which is the standard projection; it is small every frame because nothing else moves A
            // and B apart.
            for (c in 0 until coils) {
                val a = base + 2 * c
                val b = a + 1
                val wa = w(a)
                val wb = w(b)
                if (wa + wb == 0f) continue
                val dx = s.x[b] - s.x[a]
                val dy = s.y[b] - s.y[a]
                val l = sqrt(dx * dx + dy * dy)
                if (l < 1e-9f) continue
                val corr = (l - diameter) / l / (wa + wb)
                s.x[a] += dx * corr * wa; s.y[a] += dy * corr * wa
                s.x[b] -= dx * corr * wb; s.y[b] -= dy * corr * wb
            }
            // Shear: neighbouring coils' centres stay on their common axis. The offset of coil c + 1's
            // centre from coil c's, measured along the pair's mean rung direction, is taken out — a
            // fraction `alignment` of it per pass, split by mass, on all four rims alike.
            if (alignment > 0f) for (c in 0 until coils - 1) {
                val a = base + 2 * c
                val b = a + 1
                var mx = (s.x[b] - s.x[a]) + (s.x[b + 2] - s.x[a + 2])
                var my = (s.y[b] - s.y[a]) + (s.y[b + 2] - s.y[a + 2])
                val ml = sqrt(mx * mx + my * my)
                // Coils folded back on each other have no common direction; leave them to contact.
                if (ml < diameter) continue
                mx /= ml
                my /= ml
                val ox = (s.x[a + 2] + s.x[b + 2] - s.x[a] - s.x[b]) * 0.5f
                val oy = (s.y[a + 2] + s.y[b + 2] - s.y[a] - s.y[b]) * 0.5f
                val off = ((ox * mx + oy * my) * alignment).coerceIn(-limit, limit)
                shift(a, b, a + 2, b + 2, mx, my, off)
            }
            // Contact: rim to rim along the lower coil's normal, at least one pitch apart.
            for (c in 0 until coils - 1) {
                val a = base + 2 * c
                val b = a + 1
                val ux = s.x[b] - s.x[a]
                val uy = s.y[b] - s.y[a]
                val ul = sqrt(ux * ux + uy * uy)
                if (ul < 1e-9f) continue
                val nx = uy / ul
                val ny = -ux / ul
                val gapA = (s.x[a + 2] - s.x[a]) * nx + (s.y[a + 2] - s.y[a]) * ny
                if (gapA < pitch) push(a, a + 2, nx, ny, min(pitch - gapA, limit))
                val gapB = (s.x[b + 2] - s.x[b]) * nx + (s.y[b + 2] - s.y[b]) * ny
                if (gapB < pitch) push(b, b + 2, nx, ny, min(pitch - gapB, limit))
            }
            restingOn?.let { set ->
                set.relax(s)
                relaxRungs(set)
            }
        }
    }

    /**
     * Pulls each pair of neighbouring rims toward their common velocity by [fraction] — equal and
     * opposite changes, so momentum is kept and only the relative motion is lost.
     */
    private fun dampInternally(fraction: Float) {
        val s = state
        for (i in base until end - 2) {
            val j = i + 2
            if (w(i) == 0f || w(j) == 0f) continue
            val dvx = ((s.x[j] - s.px[j]) - (s.x[i] - s.px[i])) * fraction
            val dvy = ((s.y[j] - s.py[j]) - (s.y[i] - s.py[i])) * fraction
            // v = x - px: raise i's velocity by dv/2... toward j's, lower j's toward i's.
            s.px[i] -= dvx * 0.5f; s.py[i] -= dvy * 0.5f
            s.px[j] += dvx * 0.5f; s.py[j] += dvy * 0.5f
        }
    }

    /** Where along each rung the [restingOn] colliders also see the coil, besides its two rims. */
    private val rungSamples = floatArrayOf(0.25f, 0.5f, 0.75f)
    private val samples: SimdVerlet.State3 by lazy { SimdVerlet.State3.empty(coils * rungSamples.size, withInvMass = false) }

    /**
     * The colliders against the inside of each coil, not only its rims: a step's nosing that
     * slips between A and B would otherwise sit inside the coil with both rims legal, and the
     * slinky hangs across the riser like a coat on a peg. Each rung is sampled at [rungSamples],
     * the samples are projected as particles of their own, and each sample's correction goes back
     * to the two rims by its barycentric weights — the least-squares split, so the rung moves the
     * sample by exactly the correction.
     */
    private fun relaxRungs(set: ColliderSet) {
        val s = state
        val q = samples
        val m = rungSamples.size
        if (q.size != coils * m) q.resize(coils * m)
        for (c in 0 until coils) for (k in 0 until m) {
            val a = base + 2 * c
            val t = rungSamples[k]
            val j = c * m + k
            q.x[j] = s.x[a] + (s.x[a + 1] - s.x[a]) * t
            q.y[j] = s.y[a] + (s.y[a + 1] - s.y[a]) * t
            q.z[j] = 0f
            q.px[j] = s.px[a] + (s.px[a + 1] - s.px[a]) * t
            q.py[j] = s.py[a] + (s.py[a + 1] - s.py[a]) * t
            q.pz[j] = 0f
        }
        val bx = q.x.copyOf(q.size)
        val by = q.y.copyOf(q.size)
        set.relax(q)
        for (c in 0 until coils) for (k in 0 until m) {
            val j = c * m + k
            val dx = q.x[j] - bx[j]
            val dy = q.y[j] - by[j]
            if (dx == 0f && dy == 0f) continue
            val a = base + 2 * c
            val t = rungSamples[k]
            val wa = if (w(a) == 0f) 0f else 1f - t
            val wb = if (w(a + 1) == 0f) 0f else t
            val norm = wa * wa + wb * wb
            if (norm == 0f) continue
            s.x[a] += dx * wa / norm; s.y[a] += dy * wa / norm
            s.x[a + 1] += dx * wb / norm; s.y[a + 1] += dy * wb / norm
        }
    }

    /**
     * Coils that touch stop against each other: for every rim pair at contact distance, the
     * approaching part of their relative velocity along the lower coil's normal is removed. The
     * position passes alone leave a shut stack slightly springy, and a springy stack hands back
     * the energy every landing should lose — the slinky then gains speed on every step until it
     * tumbles. With this, each landing is a dead stop, as the toy's clattering coils are.
     */
    private fun stopTouchingCoils() {
        val s = state
        val touch = pitch * (1f + contactSlack)
        for (c in 0 until coils - 1) {
            val a = base + 2 * c
            val b = a + 1
            val ux = s.x[b] - s.x[a]
            val uy = s.y[b] - s.y[a]
            val ul = sqrt(ux * ux + uy * uy)
            if (ul < 1e-9f) continue
            val nx = uy / ul
            val ny = -ux / ul
            for (i in intArrayOf(a, b)) {
                val j = i + 2
                val gap = (s.x[j] - s.x[i]) * nx + (s.y[j] - s.y[i]) * ny
                if (gap > touch) continue
                val wi = w(i)
                val wj = w(j)
                val ws = wi + wj
                if (ws == 0f) continue
                val vn = ((s.x[j] - s.px[j]) - (s.x[i] - s.px[i])) * nx + ((s.y[j] - s.py[j]) - (s.y[i] - s.py[i])) * ny
                if (vn >= 0f) continue
                // v = x - px: i takes on its share of the approach, j gives up its share.
                s.px[i] -= nx * vn * wi / ws; s.py[i] -= ny * vn * wi / ws
                s.px[j] += nx * vn * wj / ws; s.py[j] += ny * vn * wj / ws
            }
        }
    }

    /** Rotates coil (a, b) about its centre by [ang]. */
    private fun rotate(a: Int, b: Int, ang: Float) {
        if (abs(ang) < 1e-7f) return
        val s = state
        val ca = cos(ang)
        val sa = sin(ang)
        fun turn(xs: FloatArray, ys: FloatArray) {
            val cx = (xs[a] + xs[b]) * 0.5f
            val cy = (ys[a] + ys[b]) * 0.5f
            for (i in intArrayOf(a, b)) {
                val dx = xs[i] - cx
                val dy = ys[i] - cy
                xs[i] = cx + dx * ca - dy * sa
                ys[i] = cy + dx * sa + dy * ca
            }
        }
        // Position only, like every other correction in the pass: the turn shows up as angular
        // velocity, which is what stops a coil that is shearing over its neighbour. Turning the
        // previous position too (a teleport) leaves that velocity in place, and the pass then
        // fights it every substep.
        turn(s.x, s.y)
    }

    /**
     * The spring as coloured line segments, one per coil, running red to violet from coil 0 —
     * the side view of a rainbow slinky — with the two rails drawn faintly behind them.
     */
    fun lines(thickness: Double = 2.2, rails: Boolean = true): List<FLine> = synchronized(lock) {
        val out = ArrayList<FLine>(coils + 2)
        if (rails) for (side in 0..1) {
            val rail = FLine()
            for (c in 0 until coils) {
                val i = base + 2 * c + side
                if (c == 0) rail.moveTo(state.x[i], state.y[i], 0f) else rail.lineTo(state.x[i], state.y[i], 0f)
            }
            rail.color = vec(0.55, 0.6, 0.7, 0.35)
            rail.thickness = thickness * 0.4
            out += rail
        }
        for (c in 0 until coils) {
            val a = base + 2 * c
            val line = FLine()
            line.moveTo(state.x[a], state.y[a], 0f)
            line.lineTo(state.x[a + 1], state.y[a + 1], 0f)
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
         * Appends a shut slinky standing on its coil 0, centred on [x] with coil 0's rims at height
         * [y] and the stack rising toward -y (the solver's "up", against `gravity`). See [Slinky].
         *
         * @param stiffness rail spring constant between neighbouring coils.
         * @param preload fraction of [pitch] the rails are pre-shortened by.
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
            name: String? = null
        ): Slinky {
            require(coils >= 2) { "a slinky needs at least 2 coils, got $coils" }
            require(diameter > 0f && pitch > 0f && mass > 0f) { "diameter, pitch and mass must be > 0" }
            require(mass == 1f || sim.state.invMass != null) { "mass != 1 needs a State3 with an invMass array" }
            val base = sim.size
            sim.state.ensureCapacity(base + 2 * coils)
            for (c in 0 until coils) {
                val cy = y - pitch * 0.5f - c * pitch
                sim.addParticle(x0 = x - diameter * 0.5f, y0 = cy, invMass0 = 1f / mass)
                sim.addParticle(x0 = x + diameter * 0.5f, y0 = cy, invMass0 = 1f / mass)
            }
            val set = (constraints ?: SimdVerlet.DirectedConstraints4.ofCapacity(base + 2 * coils))
                .also { it.ensureCapacity(sim.size) }
            sim.constraints4 = set
            for (i in base until base + 2 * coils) set.clearSlots(i)
            val slinky = Slinky(sim, coils, base, diameter, pitch, mass, set, name ?: "slinky@$base")
            // Slot 0 draws the rung in debugLines without pulling on it; the pass holds it.
            for (c in 0 until coils) {
                val a = base + 2 * c
                set.setSlot(a, 0, a + 1, diameter, 0f)
                set.setSlot(a + 1, 0, a, diameter, 0f)
            }
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
    name: String? = null
): Slinky = Slinky.addTo(this, coils, diameter, pitch, stiffness, preload, mass, x, y, constraints, name)

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
        slinky = sim.addSlinky(coils = coils, stiffness = stiffness, preload = preload, x = startX, y = -stairs.margin)
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
