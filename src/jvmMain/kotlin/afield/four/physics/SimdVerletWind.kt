package afield.four.physics

import afield.graphics.FLine
import afield.stdlib.Vec4
import afield.stdlib.color
import afield.stdlib.thickness
import afield.stdlib.vec
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Wind for [SimdVerlet]: per-particle drag, per-quad cloth aerodynamics, and the reproducible
 * gust field both of them sample.
 *
 * The two force models answer different questions, and a sheet that is meant to flutter rather
 * than lean needs the second one.
 *
 * **Per-particle drag** ([SimdVerlet.addWind]) is `F = drag * (w - v)` per particle, where `w` is
 * the wind velocity at that point and `v = (x - px) / dt` is the Verlet velocity. It applies to
 * anything made of particles (a rope, a point cloud, a cloth), and it cannot see which way fabric
 * is facing, because a particle has no orientation: a sheet edge-on to the wind is pushed exactly
 * as hard as a sheet face-on, and under a steady wind such a cloth reaches an equilibrium lean and
 * stays there.
 *
 * **Per-quad aerodynamics** ([Cloth.addWind]) reads the sheet's own topology, which costs nothing:
 * a [Cloth] is a contiguous row-major grid, so the quad at `(col, row)` is four adds. For each quad
 * it takes the normal from the diagonals, the area from half that normal's length, the relative
 * velocity from the mean of the four corners, and applies
 *
 *     F = 0.5 * rho * Cd * A * (n . v_rel) * |n . v_rel| * n
 *
 * quartered onto the corners, plus an optional tangential term. Flutter comes out of the signed
 * `n . v_rel` factor: a quad turning edge-on stops being pushed, overshoots, catches the wind on
 * its other face, and the instability travels down the sheet as a wave.
 *
 * `SimdVerletWindTest` measures both models through the same measurement. Two identical 12 x 12
 * sheets at rest in the same wind, one face-on and one edge-on, differ by more than twenty times
 * in the force the per-quad pass puts into them, and what little the edge-on sheet gets is all
 * skin friction: set `ClothWind.tangentialCoefficient` to zero and its normal force drops below a
 * millionth of the face-on total. The drag pass gives the two sheets the same force to within a
 * part in 100000, because a particle has no orientation to have.
 *
 * The other measurement is sustained motion: a 20 x 12 flag in an 8-unit wind with 60 % gusts,
 * scored as trailing-edge travel per frame once the transient has passed, with the per-quad pass
 * on and off. What the test asserts is the shape rather than the figures. Per-quad always adds
 * motion; it adds more than 2.5 times as much at `Wind.drag = 0.05`, and less than 1.5 times as
 * much by `0.5`. So the advantage is a low-drag one. Turn drag up and the drag pass is doing the
 * forcing, and the sheet is being carried rather than flying.
 *
 * #### The gust field
 *
 * `Wind.turbulence` adds gusts on top of the mean `Wind.velocityX`/`velocityY`/`velocityZ`. The
 * field is a small closed-form spectrum: a few sines per axis, with directions, spatial
 * frequencies, temporal frequencies and phases all derived from the seed. It is not a noise
 * lattice and not a fluid simulation, because what it has to be is reproducible and grid-free.
 * Two runs at the same seed produce the same sheet, so a test can assert something about it and a
 * capture can be re-taken; and a lattice can align its cells with the cloth's own grid spacing and
 * stand a pattern in the sheet. A sum of sines has no lattice to align, and costs three sines per
 * octave per sample.
 *
 * Gust amplitude is `turbulence` times the mean wind's speed, so `turbulence = 0.4f` is gusts of
 * about 40 % whatever the wind is set to, and `0f` is a steady stream.
 *
 * #### Wind time
 *
 * `Wind.time` advances by `dt` on every pass, so a registered wind is a running one. Set it to
 * drive the gusts from somewhere else (a transport, a scrub bar); reset it to zero to replay a
 * gust exactly.
 */

/**
 * One wind: a mean velocity, a gust field, and the per-particle drag pass those two feed.
 *
 * [SimdVerlet.addWind] builds one and registers the pass; every field here is live, so a
 * direction, a drag or a gust setting can be changed between frames. [accumulate] is the pass
 * itself for a caller who would rather drive it than leave it registered, [sampleInto] reads the
 * field at a point, [detach] unregisters it. A wind with `drag = 0f` applies no force at all,
 * which is how to get one purely as a field for something else to sample: a [ClothWind], the
 * whiskers of [windLines], or a caller's own pass.
 *
 * [range] limits the drag pass to a slice of the simulation, so two winds can blow on two
 * different groups of particles in the same state.
 */
class Wind internal constructor(
    val sim: SimdVerlet,
    val name: String,
    var velocityX: Float,
    var velocityY: Float,
    var velocityZ: Float,
    var drag: Float,
    var turbulence: Float,
    var turbulenceScale: Float,
    var turbulenceSpeed: Float,
    var range: IntRange?,
    val gusts: Turbulence
) {
    /** Seconds of wind elapsed. Advanced by `dt` on every pass; assignable, so gusts can be scrubbed. */
    @Volatile
    var time: Float = 0f

    /** Speed of the mean wind, which is what [turbulence] is a fraction of. */
    val speed: Float get() = sqrt(velocityX * velocityX + velocityY * velocityY + velocityZ * velocityZ)

    // Written by sampleInto, read by the callers immediately. One pass, one thread.
    private val sample = FloatArray(3)

    /**
     * The wind velocity at a world point and the current [time], mean plus gust, into `out[0..2]`.
     *
     * Public because it is the thing a caller wants to *draw*: a field of little arrows sampled on
     * a grid says more about why a sheet is doing what it is doing than any number of frames of
     * the sheet. See [windLines].
     */
    fun sampleInto(x: Float, y: Float, z: Float, out: FloatArray, t: Float = time) {
        out[0] = velocityX
        out[1] = velocityY
        out[2] = velocityZ
        if (turbulence == 0f) return
        val amp = turbulence * speed
        if (amp == 0f) return
        val sx = x * turbulenceScale
        val sy = y * turbulenceScale
        val sz = z * turbulenceScale
        val st = t * turbulenceSpeed
        out[0] += amp * gusts.sample(0, sx, sy, sz, st)
        out[1] += amp * gusts.sample(1, sx, sy, sz, st)
        out[2] += amp * gusts.sample(2, sx, sy, sz, st)
    }

    /** The per-particle drag pass, exposed for callers who would rather drive it than register it. */
    fun accumulate(state: SimdVerlet.State3, dt: Float) {
        time += dt
        if (drag == 0f || dt <= 0f) return
        val r = range
        val start = (r?.first ?: 0).coerceAtLeast(0)
        val end = (if (r == null) state.size else r.last + 1).coerceAtMost(state.size)
        if (end <= start) return
        val invDt = 1f / dt
        val steady = turbulence == 0f || speed == 0f
        val out = sample
        if (steady) {
            out[0] = velocityX
            out[1] = velocityY
            out[2] = velocityZ
        }
        for (i in start until end) {
            if (!steady) sampleInto(state.x[i], state.y[i], state.z[i], out)
            val vx = (state.x[i] - state.px[i]) * invDt
            val vy = (state.y[i] - state.py[i]) * invDt
            val vz = (state.z[i] - state.pz[i]) * invDt
            state.ax[i] += drag * (out[0] - vx)
            state.ay[i] += drag * (out[1] - vy)
            state.az[i] += drag * (out[2] - vz)
        }
    }

    /** Unregisters the pass from [sim]. */
    fun detach() {
        sim.removeBulkForce(name)
    }
}

/**
 * A small closed-form gust spectrum: three independent scalar fields over `(x, y, z, t)`, each a
 * sum of [octaves] sines whose direction, frequency and phase come from [seed].
 *
 * Deliberately not a noise lattice. What this has to be is reproducible and grid-free — a value
 * or gradient noise on an integer lattice can align its cells with a cloth's grid spacing and put
 * a standing pattern into the sheet, which is a bug that looks like a feature. A sum of sines has
 * no lattice, is smooth to all orders, and is the same field on every machine and every run for a
 * given seed.
 *
 * Each successive octave doubles the frequency and halves the amplitude, and the result is
 * normalised by the amplitude sum, so [sample] stays within `[-1, 1]` whatever [octaves] is.
 */
class Turbulence(val seed: Int = 1, val octaves: Int = 3) {

    private val dirX = FloatArray(3 * octaves)
    private val dirY = FloatArray(3 * octaves)
    private val dirZ = FloatArray(3 * octaves)
    private val omega = FloatArray(3 * octaves)
    private val phase = FloatArray(3 * octaves)
    private val amplitude = FloatArray(3 * octaves)
    private val normalisation: Float

    init {
        require(octaves in 1..16) { "octaves must be in 1..16, got $octaves" }
        var ampSum = 0f
        for (component in 0 until 3) {
            for (o in 0 until octaves) {
                val k = component * octaves + o
                // A deterministic direction on the sphere from three hashes, then normalised. Not
                // uniformly distributed and it does not need to be: it needs to be the same
                // direction every time and not axis-aligned.
                var dx = hashUnit(k, 0)
                var dy = hashUnit(k, 1)
                var dz = hashUnit(k, 2)
                var len = sqrt(dx * dx + dy * dy + dz * dz)
                if (len < 1e-6f) {
                    dx = 1f; dy = 0f; dz = 0f; len = 1f
                }
                val freq = (1 shl o).toFloat() * (0.75f + 0.5f * (hashUnit(k, 3) * 0.5f + 0.5f))
                dirX[k] = dx / len * freq
                dirY[k] = dy / len * freq
                dirZ[k] = dz / len * freq
                omega[k] = freq * (0.6f + 0.8f * (hashUnit(k, 4) * 0.5f + 0.5f))
                phase[k] = hashUnit(k, 5) * 3.14159265f
                amplitude[k] = 1f / (1 shl o)
                if (component == 0) ampSum += amplitude[k]
            }
        }
        normalisation = 1f / ampSum
    }

    /**
     * Component [component] (0, 1, 2) of the gust field at `(x, y, z)` and time [t], in `[-1, 1]`.
     * The coordinates are expected to be pre-scaled by the caller's spatial frequency.
     */
    fun sample(component: Int, x: Float, y: Float, z: Float, t: Float): Float {
        var acc = 0f
        val first = component * octaves
        for (o in 0 until octaves) {
            val k = first + o
            acc += amplitude[k] * sin(dirX[k] * x + dirY[k] * y + dirZ[k] * z + omega[k] * t + phase[k])
        }
        return acc * normalisation
    }

    /** A stable float in `[-1, 1]` from two ints and the seed. Integer-only, so it is bit-exact everywhere. */
    private fun hashUnit(a: Int, b: Int): Float {
        var h = seed * 0x9E3779B1.toInt()
        h = h xor (a * 0x85EBCA6B.toInt())
        h = h xor (b * 0xC2B2AE35.toInt())
        h = h xor (h ushr 15)
        h *= 0x2545F491
        h = h xor (h ushr 13)
        return ((h and 0xFFFFFF) / 8388608.0f) - 1f
    }
}

/**
 * Registers per-particle drag on this simulation and hands back the live handle.
 *
 *     val wind = sim.addWind(velocityX = 6f, drag = 0.9f, turbulence = 0.5f)
 *
 * Read [Wind]'s KDoc before using this on a cloth: per-particle drag has no notion of which way
 * the fabric is facing, so it leans a sheet rather than fluttering it. [Cloth.addWind] is the one
 * that flutters.
 *
 * @param drag force per unit of velocity difference. `0f` registers the pass but applies nothing,
 *   which is how you get a [Wind] purely as a *field* to sample from something else.
 * @param turbulence gust amplitude as a fraction of the mean wind's speed.
 * @param turbulenceScale spatial frequency of the gusts, in inverse world units — the reciprocal
 *   is roughly a gust's size, so `0.35f` on a 10-unit-wide sheet gives gusts of about a third of it.
 * @param turbulenceSpeed how fast the gust field evolves, in radians per second at the base octave.
 * @param seed everything about the gust field. Same seed, same gusts, forever.
 */
fun SimdVerlet.addWind(
    velocityX: Float = 0f,
    velocityY: Float = 0f,
    velocityZ: Float = 0f,
    drag: Float = 0.5f,
    turbulence: Float = 0f,
    turbulenceScale: Float = 0.35f,
    turbulenceSpeed: Float = 1.6f,
    octaves: Int = 3,
    seed: Int = 1,
    range: IntRange? = null,
    name: String = "wind"
): Wind {
    val wind = Wind(
        sim = this, name = name,
        velocityX = velocityX, velocityY = velocityY, velocityZ = velocityZ,
        drag = drag,
        turbulence = turbulence, turbulenceScale = turbulenceScale, turbulenceSpeed = turbulenceSpeed,
        range = range, gusts = Turbulence(seed, octaves)
    )
    addBulkForce(name) { state, dt -> wind.accumulate(state, dt) }
    return wind
}

/**
 * The aerodynamic surface of one cloth: registers a per-quad pass that reads its wind from [wind]
 * and hands back the handle for it.
 *
 * The two passes are independent and composable. A cloth with only this one has no skin drag at
 * all and can slide sideways through still air forever; a cloth with both has the sheet-shaped
 * force *and* a little damping. The usual pairing is a low [Wind.drag] (0.1 or so) and this.
 *
 * @param wind where the air comes from. Making a separate one per cloth is fine and normal; two
 *   cloths in the same weather should share.
 * @param density `rho` in the force law, folded together with `Cd`, so this and [dragCoefficient]
 *   are one knob split in two for readability.
 * @param dragCoefficient the normal-facing coefficient. 1.28 is a flat plate.
 * @param tangentialCoefficient skin friction along the surface, which stops a sheet from sliding
 *   frictionlessly across a steady wind. Small: a few percent of [dragCoefficient].
 */
fun Cloth.addWind(
    wind: Wind,
    density: Float = 1.2f,
    dragCoefficient: Float = 1.28f,
    tangentialCoefficient: Float = 0.05f,
    passName: String = "$name.aero"
): ClothWind {
    val aero = ClothWind(this, wind, density, dragCoefficient, tangentialCoefficient, passName)
    sim.addBulkForce(passName) { state, dt -> aero.accumulate(state, dt) }
    return aero
}

/**
 * The per-quad aerodynamic pass for one [Cloth]. See [Cloth.addWind] and [Wind].
 *
 * The quad normal comes from the diagonals, `n = (d10 x d01)` where `d10` is corner (1,1) minus
 * corner (0,0) and `d01` is corner (0,1) minus corner (1,0). Using the diagonals rather than two
 * edges is not a micro-optimisation: it is the average of the two triangulations of the quad, so a
 * quad creased along one diagonal does not get a normal that depends on which way the code happens
 * to have split it. `|n| / 2` is the area, exactly for a planar quad and close enough for a
 * creased one.
 */
class ClothWind internal constructor(
    val cloth: Cloth,
    val wind: Wind,
    var density: Float,
    var dragCoefficient: Float,
    var tangentialCoefficient: Float,
    val name: String
) {
    /**
     * Total force magnitude the last pass put into the sheet — the "is the wind on" readout, and
     * what the tests floor so that a silently-dead aero pass fails instead of passing as a sheet
     * that happens to hang the right way. Zeroed by [detach], so it means "what the air did last
     * frame" rather than "the last time the air did anything".
     */
    var lastImpulse: Float = 0f
        private set

    private val sample = FloatArray(3)

    /** The pass itself, exposed for callers who would rather drive it than leave it registered. */
    fun accumulate(state: SimdVerlet.State3, dt: Float) {
        lastImpulse = 0f
        if (dt <= 0f) return
        if (density == 0f || (dragCoefficient == 0f && tangentialCoefficient == 0f)) return
        val w = cloth.width
        val h = cloth.height
        val base = cloth.base
        if (state.size < base + w * h) return

        val x = state.x
        val y = state.y
        val z = state.z
        val px = state.px
        val py = state.py
        val pz = state.pz
        val ax = state.ax
        val ay = state.ay
        val az = state.az
        val invDt = 1f / dt
        val kNormal = 0.5f * density * dragCoefficient
        val kTangent = 0.5f * density * tangentialCoefficient
        val out = sample

        for (row in 0 until h - 1) {
            for (col in 0 until w - 1) {
                val i00 = base + row * w + col
                val i10 = i00 + 1
                val i01 = i00 + w
                val i11 = i01 + 1

                // Diagonals, and the normal as their cross product.
                val ux = x[i11] - x[i00]
                val uy = y[i11] - y[i00]
                val uz = z[i11] - z[i00]
                val vx = x[i01] - x[i10]
                val vy = y[i01] - y[i10]
                val vz = z[i01] - z[i10]
                var nx = uy * vz - uz * vy
                var ny = uz * vx - ux * vz
                var nz = ux * vy - uy * vx
                val nLen = sqrt(nx * nx + ny * ny + nz * nz)
                if (nLen < 1e-12f) continue
                val area = 0.5f * nLen
                nx /= nLen; ny /= nLen; nz /= nLen

                // Mean corner velocity and the centroid the wind is sampled at.
                val cvx = ((x[i00] - px[i00]) + (x[i10] - px[i10]) + (x[i01] - px[i01]) + (x[i11] - px[i11])) * 0.25f * invDt
                val cvy = ((y[i00] - py[i00]) + (y[i10] - py[i10]) + (y[i01] - py[i01]) + (y[i11] - py[i11])) * 0.25f * invDt
                val cvz = ((z[i00] - pz[i00]) + (z[i10] - pz[i10]) + (z[i01] - pz[i01]) + (z[i11] - pz[i11])) * 0.25f * invDt
                val cx = (x[i00] + x[i10] + x[i01] + x[i11]) * 0.25f
                val cy = (y[i00] + y[i10] + y[i01] + y[i11]) * 0.25f
                val cz = (z[i00] + z[i10] + z[i01] + z[i11]) * 0.25f
                wind.sampleInto(cx, cy, cz, out)

                val rx = out[0] - cvx
                val ry = out[1] - cvy
                val rz = out[2] - cvz
                val vn = rx * nx + ry * ny + rz * nz

                // Quadratic in the normal component, signed: a quad turning edge-on stops being
                // pushed, which is the flutter.
                val fn = kNormal * area * vn * abs(vn)
                var fx = fn * nx
                var fy = fn * ny
                var fz = fn * nz
                if (kTangent != 0f) {
                    val tx = rx - vn * nx
                    val ty = ry - vn * ny
                    val tz = rz - vn * nz
                    val tLen = sqrt(tx * tx + ty * ty + tz * tz)
                    val ft = kTangent * area * tLen
                    fx += ft * tx
                    fy += ft * ty
                    fz += ft * tz
                }
                lastImpulse += sqrt(fx * fx + fy * fy + fz * fz)

                val qx = fx * 0.25f
                val qy = fy * 0.25f
                val qz = fz * 0.25f
                ax[i00] += qx; ay[i00] += qy; az[i00] += qz
                ax[i10] += qx; ay[i10] += qy; az[i10] += qz
                ax[i01] += qx; ay[i01] += qy; az[i01] += qz
                ax[i11] += qx; ay[i11] += qy; az[i11] += qz
            }
        }
    }

    /** Unregisters the pass from the cloth's simulation, and zeroes [lastImpulse]. */
    fun detach() {
        cloth.sim.removeBulkForce(name)
        lastImpulse = 0f
    }
}

/**
 * The wind field itself, as `FLine` whiskers on a grid — what to draw underneath a fluttering
 * sheet so that the gusts are visible rather than inferred.
 *
 * The grid runs corner to corner between the two points, [columns] steps along `x` and [rows]
 * steps along `y` *and* `z` together. That covers both views a plane figure of this ever wants
 * without a projection argument: hold `z` fixed for the front view, hold `y` fixed and let `z`
 * run for the view from above — which is the only one a flag flapping in `z` is visible in, and
 * the one where a front-view grid collapses every row onto the same point and draws fans.
 *
 * @param scale seconds of wind travel a whisker represents, so the arrows are in the same units as
 *   the sheet they sit behind.
 */
fun Wind.windLines(
    x0: Float, y0: Float, z0: Float,
    x1: Float, y1: Float, z1: Float,
    columns: Int = 14,
    rows: Int = 10,
    scale: Float = 0.06f,
    color: Vec4 = vec(0.45, 0.95, 0.72, 0.30),
    thickness: Double = 1.0
): List<FLine> {
    require(columns >= 1 && rows >= 1) { "windLines needs at least a 1 x 1 grid" }
    val line = FLine()
    val out = FloatArray(3)
    for (r in 0 until rows) {
        for (c in 0 until columns) {
            val fx = if (columns == 1) 0.5f else c / (columns - 1f)
            val fy = if (rows == 1) 0.5f else r / (rows - 1f)
            val sx = x0 + (x1 - x0) * fx
            val sy = y0 + (y1 - y0) * fy
            val sz = z0 + (z1 - z0) * fy
            sampleInto(sx, sy, sz, out)
            val len = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
            if (len < 1e-6f) continue
            line.moveTo(sx, sy, sz)
            line.lineTo(sx + out[0] * scale, sy + out[1] * scale, sz + out[2] * scale)
        }
    }
    if (line.nodes.isEmpty()) return emptyList()
    line.color = color
    line.thickness = thickness
    return listOf(line)
}
