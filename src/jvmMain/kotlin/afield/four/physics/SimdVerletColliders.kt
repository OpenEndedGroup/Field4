@file:Suppress("NOTHING_TO_INLINE")

package afield.four.physics

import afield.graphics.FLine
import afield.stdlib.Vec4
import afield.stdlib.color
import afield.stdlib.thickness
import afield.stdlib.vec
import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.VectorMask
import jdk.incubator.vector.VectorOperators
import jdk.incubator.vector.VectorSpecies
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Things for the particles to hit: the world layer of [SimdVerlet].
 *
 * A collider is a **post-solve position projection**, not a force. Register it through
 * [SimdVerlet.positionProjections] — normally by way of [SimdVerlet.addColliders], which wraps a
 * whole list of them in one [ColliderSet] and one registered pass — and it runs after Verlet
 * integration, moves any penetrating particle onto the surface, and rewrites that particle's
 * previous position so the Verlet velocity it implies is the post-contact one.
 *
 * ## Why not a penalty force
 *
 * Because this solver's constraint stage is force-based. `solveDirectedDist4InPlaceRange` runs
 * `iterations` passes that each *add* their correction to the same accumulator with nothing
 * moving in between, so a cloth spring of stiffness `k` acts at `k * iterations`. A penalty
 * spring pushing particles out of a sphere, registered in [SimdVerlet.bulkForces], runs once. At
 * the cloth starting place's `60 x 16` it is therefore sixteen times softer than the fabric it
 * has to hold up, so the sheet sinks through; multiply it by sixteen to compensate and it is at
 * the `k * dt^2 / m` divergence limit the stiffness table maps, so a fast particle overshoots and
 * is launched. There is no setting in between that works at every impact speed, because the
 * required stiffness scales with the *square* of the approach velocity and the stable stiffness
 * does not scale at all.
 *
 * A projection has no stiffness. It costs one square root per particle per collider and cannot
 * be tuned wrong. `SimdVerletCollidersTest.penaltyForceCollidersInheritTheIterationScaleTrap`
 * measures the failure it replaces, and `dev_docs/physics/SIMDVERLET_5_WORLD.md` prints the
 * table.
 *
 * ## Contact response
 *
 * Given an outward unit normal `n` and a penetration depth `d > 0`:
 *
 * - the position moves to the surface, `x += n * d`;
 * - the Verlet velocity `v = x - px` (a per-frame *displacement*, not a per-second velocity) is
 *   split into `v_n = (v . n) n` and `v_t = v - v_n`;
 * - an approaching normal component is reflected and scaled by [Collider.restitution]; a
 *   separating one is left alone, so a projection never adds energy along the normal;
 *   the tangential part loses at most `friction * d` of its length ([Collider.friction]) and then
 *   a further [Collider.tangentialDamping] fraction of what is left;
 * - `px` is rewritten to `x_new - v_new`.
 *
 * The friction is Coulomb and *position-based*: bounding the tangential correction by `mu * d`
 * is the right bound rather than a convenient one, because for a particle held against a surface
 * the per-frame penetration `d` is exactly the displacement the normal force would have produced
 * (`a_n * dt^2`), so `mu * d` is the Coulomb limit already expressed in displacement units. It
 * gives static friction for free: a sheet at rest on a sphere with `friction = 0.6` stays where
 * it is instead of creeping. [tangentialDamping] is the unconditional viscous alternative for a
 * deliberately sticky surface, and is off by default.
 *
 * Particles with zero inverse mass (pinned) are never moved. A state with no `invMass` array is
 * all unit masses and every particle collides.
 *
 * ## Vectorisation
 *
 * [PlaneCollider] and [SphereCollider] carry a JDK Incubator Vector path with a scalar remainder
 * tail, in the same shape as the rest of the solver; [CapsuleCollider], [BoxCollider] and
 * [SdfCollider] are scalar over a range (the segment clamp, the six-face least-penetration
 * choice and an opaque callback are all branch-divergent per lane, and a masked version was not
 * worth the second copy of the arithmetic). Every collider that has both paths also honours
 * [Collider.vectorized] `= false`, which exists so that
 * `SimdVerletCollidersTest.vectorAndScalarPathsAgree` can put the two side by side.
 */
sealed class Collider {

    /** Skipped entirely while false, without being unregistered. */
    var enabled: Boolean = true

    /**
     * Coulomb friction coefficient. The tangential correction applied on contact is bounded by
     * `friction * depth`, which is the Coulomb limit in Verlet displacement units — see the
     * class KDoc. `0f` is frictionless; `0.6f` holds a sheet on a sphere.
     */
    var friction: Float = 0.5f

    /**
     * Fraction of the *remaining* tangential velocity removed on every contact, unconditionally.
     * The viscous companion to [friction], for a surface that should be sticky rather than
     * merely rough. `0f` (the default) leaves Coulomb friction as the only tangential effect.
     */
    var tangentialDamping: Float = 0f

    /**
     * Bounce: the fraction of an approaching normal velocity returned. `0f` is a dead surface,
     * `1f` is lossless. A separating normal velocity is never touched, so this can only remove
     * energy. Note that a high restitution never lets a particle come fully to rest — gravity
     * re-penetrates by `g * dt^2` every frame and gets that fraction of it back.
     */
    var restitution: Float = 0f

    /**
     * Extra clearance held between a particle's centre and the surface — cloth half-thickness,
     * or a particle radius. Positive values keep the sheet visibly off the shape, which is also
     * what stops a wireframe from z-fighting the thing it is draped over.
     */
    var margin: Float = 0f

    /** Use the vector path where there is one. `false` forces the scalar path; see the class KDoc. */
    var vectorized: Boolean = true

    /**
     * Number of particles the last [projectRange] found penetrating. Not a physical quantity —
     * it is the "is this collider doing anything at all" readout, and what
     * `SimdVerletCollidersTest` asserts a floor on so that a silently-missing contact fails
     * loudly rather than passing as a cloth that happens to hang in the right place.
     */
    var contacts: Int = 0
        protected set

    /**
     * Projects the particles in `[start, endExclusive)` out of this shape and returns how many
     * were penetrating. Writes `x/y/z` and `px/py/pz`; reads nothing else. Non-overlapping
     * ranges touch disjoint particles, so this is range-parallel safe for one collider — several
     * *colliders* over the same range are not, and [ColliderSet] runs them in series.
     */
    abstract fun projectRange(state: SimdVerlet.State3, start: Int, endExclusive: Int): Int

    /** [projectRange] over the whole active state. */
    fun project(state: SimdVerlet.State3): Int = projectRange(state, 0, state.size)

    /**
     * The shape as `FLine`s, for drawing next to [SimdVerlet.debugLines]. Not a renderer and not
     * a mesh: a sphere is its silhouette circle in the XY plane, because the plane views these
     * are for (`Box.showPlane`) project x/y and throw z away, and a great circle is the one
     * curve that reads as "the thing the cloth is lying on" in that projection.
     */
    abstract fun outline(color: Vec4 = DEFAULT_OUTLINE_COLOR, thickness: Double = 1.6): List<FLine>

    companion object {
        /** The outline colour the debug views use unless told otherwise: a warm amber. */
        val DEFAULT_OUTLINE_COLOR: Vec4 = vec(1.0, 0.72, 0.28, 0.85)

        internal val SP: VectorSpecies<Float> = FloatVector.SPECIES_PREFERRED

        /** How many lanes the vector paths use here — asserted by the tests, so they can't go tautological. */
        val laneCount: Int get() = SP.length()
    }
}

// ==============================================================================================
// Shared contact response
// ==============================================================================================

/**
 * The scalar contact response, for one particle: push out along `n` by `depth`, then rewrite
 * `px/py/pz` so the implied Verlet velocity is the post-contact one. See [Collider]'s KDoc for
 * the model. `n` must be unit length and `depth` must be `> 0`.
 */
private inline fun applyContactScalar(
    state: SimdVerlet.State3,
    i: Int,
    nx: Float, ny: Float, nz: Float,
    depth: Float,
    friction: Float,
    tangentialDamping: Float,
    restitution: Float
) {
    // The velocity is read BEFORE the push-out. Measuring it after would silently charge the
    // particle for the distance it penetrated -- an object dropped from three units and bounced
    // with restitution 0.9 came back 0.46 rather than 2.4, because the frame's approach
    // displacement had been quietly cut down by the correction before being reflected. Taken
    // before, restitution 1.0 preserves speed exactly, which is what it has to mean.
    val vx = state.x[i] - state.px[i]
    val vy = state.y[i] - state.py[i]
    val vz = state.z[i] - state.pz[i]

    val x = state.x[i] + nx * depth
    val y = state.y[i] + ny * depth
    val z = state.z[i] + nz * depth
    state.x[i] = x
    state.y[i] = y
    state.z[i] = z

    val vn = vx * nx + vy * ny + vz * nz
    // Only an *approaching* normal component is reflected. Doing this unconditionally would turn
    // a particle already leaving the surface around, which is how a projection collider ends up
    // adding energy.
    val vnNew = if (vn < 0f) -restitution * vn else vn

    var tx = vx - vn * nx
    var ty = vy - vn * ny
    var tz = vz - vn * nz
    val tLen = sqrt(tx * tx + ty * ty + tz * tz)
    if (tLen > 1e-12f) {
        val coulomb = max(0f, 1f - (friction * depth) / tLen)
        val scale = coulomb * (1f - tangentialDamping)
        tx *= scale
        ty *= scale
        tz *= scale
    }

    state.px[i] = x - (tx + vnNew * nx)
    state.py[i] = y - (ty + vnNew * ny)
    state.pz[i] = z - (tz + vnNew * nz)
}

/**
 * The vector twin of [applyContactScalar], for one full lane group starting at [i]. Lanes outside
 * [active] are left exactly as they were. `depth` is only read where [active] is set.
 *
 * The two must agree; `SimdVerletCollidersTest.vectorAndScalarPathsAgree` is what says so, and it
 * asserts `Collider.laneCount > 1` first so that a machine without vectors turns the check into a
 * failure rather than into a tautology.
 */
private inline fun applyContactVector(
    x: FloatArray, y: FloatArray, z: FloatArray,
    px: FloatArray, py: FloatArray, pz: FloatArray,
    i: Int,
    xv: FloatVector, yv: FloatVector, zv: FloatVector,
    nx: FloatVector, ny: FloatVector, nz: FloatVector,
    depth: FloatVector,
    active: VectorMask<Float>,
    friction: Float,
    tangentialDamping: Float,
    restitution: Float
) {
    val sp = Collider.SP
    val zero = FloatVector.zero(sp)

    val nxOut = xv.add(nx.mul(depth))
    val nyOut = yv.add(ny.mul(depth))
    val nzOut = zv.add(nz.mul(depth))

    val pxv = FloatVector.fromArray(sp, px, i)
    val pyv = FloatVector.fromArray(sp, py, i)
    val pzv = FloatVector.fromArray(sp, pz, i)

    // Pre-projection velocity; see the note in applyContactScalar.
    val vx = xv.sub(pxv)
    val vy = yv.sub(pyv)
    val vz = zv.sub(pzv)

    val vn = vx.mul(nx).add(vy.mul(ny)).add(vz.mul(nz))
    val approaching = vn.lt(0f)
    val vnNew = vn.mul(-restitution).blend(vn, approaching.not())

    val tx0 = vx.sub(vn.mul(nx))
    val ty0 = vy.sub(vn.mul(ny))
    val tz0 = vz.sub(vn.mul(nz))
    val tLen = tx0.mul(tx0).add(ty0.mul(ty0)).add(tz0.mul(tz0)).lanewise(VectorOperators.SQRT)

    // max(0, 1 - mu*depth/|v_t|) * (1 - tangentialDamping), and 0 where |v_t| is under the scalar
    // path's 1e-12 cut-off -- there the scalar path leaves v_t alone, and v_t is zero anyway.
    val moving = tLen.lt(1e-12f).not()
    val coulomb = FloatVector.broadcast(sp, 1f)
        .sub(depth.mul(friction).div(tLen.max(1e-12f)))
        .max(zero)
    val scale = coulomb.mul(1f - tangentialDamping).blend(1f, moving.not())
    val tx = tx0.mul(scale)
    val ty = ty0.mul(scale)
    val tz = tz0.mul(scale)

    nxOut.blend(xv, active.not()).intoArray(x, i)
    nyOut.blend(yv, active.not()).intoArray(y, i)
    nzOut.blend(zv, active.not()).intoArray(z, i)

    nxOut.sub(tx.add(vnNew.mul(nx))).blend(pxv, active.not()).intoArray(px, i)
    nyOut.sub(ty.add(vnNew.mul(ny))).blend(pyv, active.not()).intoArray(py, i)
    nzOut.sub(tz.add(vnNew.mul(nz))).blend(pzv, active.not()).intoArray(pz, i)
}

/** Lanes of `[i, i + lanes)` that are allowed to move: inside the range and not pinned. */
private inline fun movableMask(state: SimdVerlet.State3, i: Int, endExclusive: Int): VectorMask<Float> {
    val sp = Collider.SP
    val inRange = sp.indexInRange(i, endExclusive)
    val invMass = state.invMass ?: return inRange
    return FloatVector.fromArray(sp, invMass, i).eq(0f).not().and(inRange)
}

/** Whether particle [i] may be moved by a collider: unpinned, or in a state with no masses at all. */
private inline fun movable(state: SimdVerlet.State3, i: Int): Boolean {
    val invMass = state.invMass ?: return true
    return invMass[i] != 0f
}

// ==============================================================================================
// The shapes
// ==============================================================================================

/**
 * A half-space: everything with `n . x >= offset + margin` is legal, everything below the plane
 * is pushed up onto it. The ground, a wall, a slope.
 *
 * @param normalX the plane normal, pointing into the *legal* side. Normalised on construction.
 * @param offset the plane's signed distance from the origin along that normal, so a floor at
 *   `y = -2` with `n = (0, 1, 0)` has `offset = -2f`.
 */
class PlaneCollider(
    normalX: Float,
    normalY: Float,
    normalZ: Float,
    var offset: Float
) : Collider() {

    var normalX: Float = normalX
        private set
    var normalY: Float = normalY
        private set
    var normalZ: Float = normalZ
        private set

    init {
        setNormal(normalX, normalY, normalZ)
    }

    /** Re-aims the plane; the vector is normalised here so the projection can assume unit length. */
    fun setNormal(nx: Float, ny: Float, nz: Float) {
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        require(len > 1e-12f) { "a plane collider needs a non-degenerate normal" }
        normalX = nx / len
        normalY = ny / len
        normalZ = nz / len
    }

    override fun projectRange(state: SimdVerlet.State3, start: Int, endExclusive: Int): Int {
        contacts = 0
        if (!enabled) return 0
        val n = state.size
        var i = start.coerceIn(0, n)
        val end = endExclusive.coerceIn(i, n)
        val nx = normalX
        val ny = normalY
        val nz = normalZ
        val limit = offset + margin

        if (vectorized) {
            val sp = SP
            val lanes = sp.length()
            val vnx = FloatVector.broadcast(sp, nx)
            val vny = FloatVector.broadcast(sp, ny)
            val vnz = FloatVector.broadcast(sp, nz)
            val vLimit = FloatVector.broadcast(sp, limit)
            while (i + lanes <= end) {
                val xv = FloatVector.fromArray(sp, state.x, i)
                val yv = FloatVector.fromArray(sp, state.y, i)
                val zv = FloatVector.fromArray(sp, state.z, i)
                val depth = vLimit.sub(xv.mul(vnx).add(yv.mul(vny)).add(zv.mul(vnz)))
                val active = depth.compare(VectorOperators.GT, 0f).and(movableMask(state, i, end))
                if (active.anyTrue()) {
                    contacts += active.trueCount()
                    applyContactVector(
                        state.x, state.y, state.z, state.px, state.py, state.pz, i,
                        xv, yv, zv, vnx, vny, vnz, depth, active,
                        friction, tangentialDamping, restitution
                    )
                }
                i += lanes
            }
        }

        while (i < end) {
            if (movable(state, i)) {
                val depth = limit - (state.x[i] * nx + state.y[i] * ny + state.z[i] * nz)
                if (depth > 0f) {
                    contacts++
                    applyContactScalar(state, i, nx, ny, nz, depth, friction, tangentialDamping, restitution)
                }
            }
            i++
        }
        return contacts
    }

    /**
     * A 40-unit cross of two perpendicular in-plane directions, centred on the point of the plane
     * nearest the origin — a plane has no extent, so the outline picks one.
     *
     * A cross rather than a single segment because the debug views are axis-aligned projections,
     * and one tangent can land edge-on in the one you are looking through: a floor at `y = -2.4`
     * first drew its trace along `z`, which is a single pixel in the XY view a draping cloth is
     * watched in, so the floor was invisible in exactly the picture it mattered for. Two
     * perpendicular tangents cannot both vanish.
     */
    override fun outline(color: Vec4, thickness: Double): List<FLine> {
        val nx = normalX.toDouble()
        val ny = normalY.toDouble()
        val nz = normalZ.toDouble()
        // Any direction perpendicular to the normal will do; cross with whichever axis the normal
        // is least aligned to, so the cross is never near-degenerate.
        val hx: Double
        val hy: Double
        val hz: Double
        if (abs(ny) < 0.9) {
            hx = 0.0; hy = 1.0; hz = 0.0
        } else {
            hx = 1.0; hy = 0.0; hz = 0.0
        }
        var tx = ny * hz - nz * hy
        var ty = nz * hx - nx * hz
        var tz = nx * hy - ny * hx
        val tLen = sqrt(tx * tx + ty * ty + tz * tz)
        tx /= tLen; ty /= tLen; tz /= tLen
        val cx = nx * offset
        val cy = ny * offset
        val cz = nz * offset
        // The second tangent: n x t, already unit length because n and t are and are orthogonal.
        val ux = ny * tz - nz * ty
        val uy = nz * tx - nx * tz
        val uz = nx * ty - ny * tx
        val line = FLine()
        line.moveTo(cx - tx * 20.0, cy - ty * 20.0, cz - tz * 20.0)
        line.lineTo(cx + tx * 20.0, cy + ty * 20.0, cz + tz * 20.0)
        line.moveTo(cx - ux * 20.0, cy - uy * 20.0, cz - uz * 20.0)
        line.lineTo(cx + ux * 20.0, cy + uy * 20.0, cz + uz * 20.0)
        line.color = color
        line.thickness = thickness
        return listOf(line)
    }
}

/**
 * A sphere. `inside = false` (the default) keeps particles out of it — the thing a cloth drapes
 * over; `inside = true` keeps them within it, which is a bowl.
 */
class SphereCollider(
    var centerX: Float,
    var centerY: Float,
    var centerZ: Float,
    var radius: Float,
    var inside: Boolean = false
) : Collider() {

    /** Moves the sphere. Nothing is cached, so this is live between frames. */
    fun moveTo(cx: Float, cy: Float, cz: Float) {
        centerX = cx
        centerY = cy
        centerZ = cz
    }

    override fun projectRange(state: SimdVerlet.State3, start: Int, endExclusive: Int): Int {
        contacts = 0
        if (!enabled) return 0
        val n = state.size
        var i = start.coerceIn(0, n)
        val end = endExclusive.coerceIn(i, n)
        val cx = centerX
        val cy = centerY
        val cz = centerZ
        // Outside-keeping wants centre-to-surface >= radius + margin; inside-keeping wants
        // <= radius - margin, and the normal flips.
        val target = if (inside) radius - margin else radius + margin
        val sign = if (inside) -1f else 1f

        if (vectorized) {
            val sp = SP
            val lanes = sp.length()
            val vcx = FloatVector.broadcast(sp, cx)
            val vcy = FloatVector.broadcast(sp, cy)
            val vcz = FloatVector.broadcast(sp, cz)
            while (i + lanes <= end) {
                val xv = FloatVector.fromArray(sp, state.x, i)
                val yv = FloatVector.fromArray(sp, state.y, i)
                val zv = FloatVector.fromArray(sp, state.z, i)
                val dx = xv.sub(vcx)
                val dy = yv.sub(vcy)
                val dz = zv.sub(vcz)
                val dist = dx.mul(dx).add(dy.mul(dy)).add(dz.mul(dz)).lanewise(VectorOperators.SQRT)
                // A particle exactly at the centre has no direction to be pushed in; +y is the
                // scalar path's arbitrary choice too, so the two agree on the degenerate case.
                val degenerate = dist.lt(1e-12f)
                val invDist = FloatVector.broadcast(sp, 1f).div(dist.max(1e-12f))
                val nx = dx.mul(invDist).mul(sign).blend(0f, degenerate)
                val ny = dy.mul(invDist).mul(sign).blend(sign, degenerate)
                val nz = dz.mul(invDist).mul(sign).blend(0f, degenerate)
                val depth = dist.sub(target).mul(-sign)
                val active = depth.compare(VectorOperators.GT, 0f).and(movableMask(state, i, end))
                if (active.anyTrue()) {
                    contacts += active.trueCount()
                    applyContactVector(
                        state.x, state.y, state.z, state.px, state.py, state.pz, i,
                        xv, yv, zv, nx, ny, nz, depth, active,
                        friction, tangentialDamping, restitution
                    )
                }
                i += lanes
            }
        }

        while (i < end) {
            if (movable(state, i)) {
                val dx = state.x[i] - cx
                val dy = state.y[i] - cy
                val dz = state.z[i] - cz
                val dist = sqrt(dx * dx + dy * dy + dz * dz)
                val depth = (dist - target) * -sign
                if (depth > 0f) {
                    contacts++
                    if (dist < 1e-12f) {
                        applyContactScalar(state, i, 0f, sign, 0f, depth, friction, tangentialDamping, restitution)
                    } else {
                        val inv = sign / dist
                        applyContactScalar(
                            state, i, dx * inv, dy * inv, dz * inv, depth,
                            friction, tangentialDamping, restitution
                        )
                    }
                }
            }
            i++
        }
        return contacts
    }

    /** The great circle in the XY plane, 64 segments. */
    override fun outline(color: Vec4, thickness: Double): List<FLine> =
        listOf(circleXY(centerX, centerY, centerZ, radius, color, thickness))
}

/**
 * A capsule: everything within [radius] of the segment `A`-`B`. A limb, a branch, a rail. As with
 * [SphereCollider], `inside = true` inverts it into a tube to stay within.
 */
class CapsuleCollider(
    var ax: Float, var ay: Float, var az: Float,
    var bx: Float, var by: Float, var bz: Float,
    var radius: Float,
    var inside: Boolean = false
) : Collider() {

    override fun projectRange(state: SimdVerlet.State3, start: Int, endExclusive: Int): Int {
        contacts = 0
        if (!enabled) return 0
        val n = state.size
        var i = start.coerceIn(0, n)
        val end = endExclusive.coerceIn(i, n)
        val ex = bx - ax
        val ey = by - ay
        val ez = bz - az
        val eLen2 = ex * ex + ey * ey + ez * ez
        val target = if (inside) radius - margin else radius + margin
        val sign = if (inside) -1f else 1f

        while (i < end) {
            if (movable(state, i)) {
                // Closest point on the segment, clamped to its ends -- the whole reason this one
                // is scalar: the clamp is a per-particle branch, and masking it costs more than
                // the lanes buy at the collider counts this layer is for.
                val t = if (eLen2 < 1e-20f) 0f else {
                    val raw = ((state.x[i] - ax) * ex + (state.y[i] - ay) * ey + (state.z[i] - az) * ez) / eLen2
                    min(1f, max(0f, raw))
                }
                val qx = ax + ex * t
                val qy = ay + ey * t
                val qz = az + ez * t
                val dx = state.x[i] - qx
                val dy = state.y[i] - qy
                val dz = state.z[i] - qz
                val dist = sqrt(dx * dx + dy * dy + dz * dz)
                val depth = (dist - target) * -sign
                if (depth > 0f) {
                    contacts++
                    if (dist < 1e-12f) {
                        applyContactScalar(state, i, 0f, sign, 0f, depth, friction, tangentialDamping, restitution)
                    } else {
                        val inv = sign / dist
                        applyContactScalar(
                            state, i, dx * inv, dy * inv, dz * inv, depth,
                            friction, tangentialDamping, restitution
                        )
                    }
                }
            }
            i++
        }
        return contacts
    }

    /** Two end circles and the two tangent lines between them, in the XY plane. */
    override fun outline(color: Vec4, thickness: Double): List<FLine> {
        val out = ArrayList<FLine>(3)
        out += circleXY(ax, ay, az, radius, color, thickness)
        out += circleXY(bx, by, bz, radius, color, thickness)
        val dx = (bx - ax).toDouble()
        val dy = (by - ay).toDouble()
        val len = sqrt(dx * dx + dy * dy)
        if (len > 1e-9) {
            val px = -dy / len * radius
            val py = dx / len * radius
            val sides = FLine()
            sides.moveTo(ax + px, ay + py, az.toDouble())
            sides.lineTo(bx + px, by + py, bz.toDouble())
            sides.moveTo(ax - px, ay - py, az.toDouble())
            sides.lineTo(bx - px, by - py, bz.toDouble())
            sides.color = color
            sides.thickness = thickness
            out += sides
        }
        return out
    }
}

/**
 * An axis-aligned box. `inside = true` (the default) is a room to stay within — the cheapest way
 * to keep a simulation from wandering off; `inside = false` is a crate to stay out of.
 *
 * The outside-keeping case pushes along the axis of *least* penetration, which is the standard
 * choice and the one that makes a box behave like six planes rather than like a point attractor.
 * It is also the reason a very fast particle can pass through a thin box in one frame: the
 * projection only sees where the particle ended up. Give a thin obstacle a [margin], or use a
 * [SdfCollider] with a swept test, if that matters.
 */
class BoxCollider(
    var minX: Float, var minY: Float, var minZ: Float,
    var maxX: Float, var maxY: Float, var maxZ: Float,
    var inside: Boolean = true
) : Collider() {

    override fun projectRange(state: SimdVerlet.State3, start: Int, endExclusive: Int): Int {
        contacts = 0
        if (!enabled) return 0
        val n = state.size
        var i = start.coerceIn(0, n)
        val end = endExclusive.coerceIn(i, n)

        if (inside) {
            val lox = minX + margin
            val loy = minY + margin
            val loz = minZ + margin
            val hix = maxX - margin
            val hiy = maxY - margin
            val hiz = maxZ - margin
            while (i < end) {
                if (movable(state, i)) {
                    // Each wall independently -- a corner is two or three contacts, counted once.
                    var hit = false
                    if (state.x[i] < lox) {
                        applyContactScalar(state, i, 1f, 0f, 0f, lox - state.x[i], friction, tangentialDamping, restitution); hit = true
                    } else if (state.x[i] > hix) {
                        applyContactScalar(state, i, -1f, 0f, 0f, state.x[i] - hix, friction, tangentialDamping, restitution); hit = true
                    }
                    if (state.y[i] < loy) {
                        applyContactScalar(state, i, 0f, 1f, 0f, loy - state.y[i], friction, tangentialDamping, restitution); hit = true
                    } else if (state.y[i] > hiy) {
                        applyContactScalar(state, i, 0f, -1f, 0f, state.y[i] - hiy, friction, tangentialDamping, restitution); hit = true
                    }
                    if (state.z[i] < loz) {
                        applyContactScalar(state, i, 0f, 0f, 1f, loz - state.z[i], friction, tangentialDamping, restitution); hit = true
                    } else if (state.z[i] > hiz) {
                        applyContactScalar(state, i, 0f, 0f, -1f, state.z[i] - hiz, friction, tangentialDamping, restitution); hit = true
                    }
                    if (hit) contacts++
                }
                i++
            }
            return contacts
        }

        val lox = minX - margin
        val loy = minY - margin
        val loz = minZ - margin
        val hix = maxX + margin
        val hiy = maxY + margin
        val hiz = maxZ + margin
        while (i < end) {
            if (movable(state, i)) {
                val x = state.x[i]
                val y = state.y[i]
                val z = state.z[i]
                if (x > lox && x < hix && y > loy && y < hiy && z > loz && z < hiz) {
                    // Least penetration wins: the face the particle is nearest to leaving by.
                    var depth = x - lox
                    var nx = -1f
                    var ny = 0f
                    var nz = 0f
                    fun consider(d: Float, ax: Float, ay: Float, az: Float) {
                        if (d < depth) {
                            depth = d; nx = ax; ny = ay; nz = az
                        }
                    }
                    consider(hix - x, 1f, 0f, 0f)
                    consider(y - loy, 0f, -1f, 0f)
                    consider(hiy - y, 0f, 1f, 0f)
                    consider(z - loz, 0f, 0f, -1f)
                    consider(hiz - z, 0f, 0f, 1f)
                    contacts++
                    applyContactScalar(state, i, nx, ny, nz, depth, friction, tangentialDamping, restitution)
                }
            }
            i++
        }
        return contacts
    }

    /** The XY rectangle. */
    override fun outline(color: Vec4, thickness: Double): List<FLine> {
        val line = FLine()
        line.moveTo(minX.toDouble(), minY.toDouble(), minZ.toDouble())
        line.lineTo(maxX.toDouble(), minY.toDouble(), minZ.toDouble())
        line.lineTo(maxX.toDouble(), maxY.toDouble(), minZ.toDouble())
        line.lineTo(minX.toDouble(), maxY.toDouble(), minZ.toDouble())
        line.lineTo(minX.toDouble(), minY.toDouble(), minZ.toDouble())
        line.color = color
        line.thickness = thickness
        return listOf(line)
    }
}

/**
 * Anything you can write down as a signed distance: negative inside, zero on the surface,
 * positive outside, and roughly unit gradient. The escape hatch, and the way to collide against a
 * shape the other four don't cover — a torus, a heightfield, an implicit surface, the output of a
 * `Manifold` level set.
 *
 * The normal is the gradient by central differences at [epsilon], so the field needs to be smooth
 * at that scale; a field with a crease exactly on the contact set will chatter. [maxSteps] `> 1`
 * re-evaluates after each correction, which is what makes a field whose gradient is *not* unit
 * length (a squared distance, an unnormalised implicit) converge onto the surface instead of
 * stopping short of it.
 *
 * @param sdf the field; called `3 * maxSteps + 1` times per penetrating particle, so keep it cheap
 *   or keep the particle count small.
 * @param outlineLines what [outline] should draw, since a callback has no silhouette to derive.
 */
class SdfCollider(
    val sdf: (x: Float, y: Float, z: Float) -> Float,
    var epsilon: Float = 1e-3f,
    var maxSteps: Int = 2,
    private val outlineLines: (() -> List<FLine>)? = null
) : Collider() {

    override fun projectRange(state: SimdVerlet.State3, start: Int, endExclusive: Int): Int {
        contacts = 0
        if (!enabled) return 0
        require(maxSteps >= 1) { "maxSteps must be >= 1" }
        val n = state.size
        var i = start.coerceIn(0, n)
        val end = endExclusive.coerceIn(i, n)
        val e = epsilon

        while (i < end) {
            if (movable(state, i)) {
                var counted = false
                repeat(maxSteps) {
                    val x = state.x[i]
                    val y = state.y[i]
                    val z = state.z[i]
                    val d = sdf(x, y, z)
                    val depth = margin - d
                    if (depth > 0f) {
                        if (!counted) {
                            contacts++
                            counted = true
                        }
                        var gx = sdf(x + e, y, z) - sdf(x - e, y, z)
                        var gy = sdf(x, y + e, z) - sdf(x, y - e, z)
                        var gz = sdf(x, y, z + e) - sdf(x, y, z - e)
                        val gLen = sqrt(gx * gx + gy * gy + gz * gz)
                        if (gLen < 1e-20f) {
                            gx = 0f; gy = 1f; gz = 0f
                        } else {
                            gx /= gLen; gy /= gLen; gz /= gLen
                        }
                        applyContactScalar(state, i, gx, gy, gz, depth, friction, tangentialDamping, restitution)
                    }
                }
            }
            i++
        }
        return contacts
    }

    override fun outline(color: Vec4, thickness: Double): List<FLine> =
        outlineLines?.invoke()?.map { it.copy { this.color = color; this.thickness = thickness } } ?: emptyList()
}

// ==============================================================================================
// A set of them, as one registered pass
// ==============================================================================================

/**
 * Several [Collider]s applied as a single [SimdVerlet.positionProjections] pass.
 *
 * They run in list order, in series, because they are not independent: a particle in the crease
 * between a sphere and the floor is pushed off the sphere and then off the floor, and the second
 * projection has to see the first one's result. [relaxations] runs the whole list more than once
 * for exactly that case — one pass leaves a particle in a two-sided corner slightly inside
 * whichever shape came first, two passes converge it to about `1e-4` of the corner. It is cheap
 * (nothing penetrates on the second pass except the handful of particles that are genuinely in a
 * crease), and the default is 1 because most scenes have no creases.
 *
 * @param range which particles to project; `null` (the default) is the whole state, re-read every
 *   frame, so particles added later are collided too. Pass `cloth.base until cloth.end` to collide
 *   one cloth out of a busier simulation.
 */
class ColliderSet internal constructor(
    val sim: SimdVerlet,
    val name: String,
    var range: IntRange?
) {
    /** The colliders, in the order they are applied. Mutable; the pass reads it live. */
    val colliders: MutableList<Collider> = ArrayList()

    /** How many times to run the whole list per frame. See the class KDoc. */
    var relaxations: Int = 1

    /** Total penetrating-particle contacts across every collider in the last frame. */
    var contacts: Int = 0
        private set

    /** Appends [collider] and returns it, so `val ground = set.add(PlaneCollider(...))` reads well. */
    fun <T : Collider> add(collider: T): T {
        colliders.add(collider)
        return collider
    }

    /** Drops [collider] from the list. Returns whether it was there. */
    fun remove(collider: Collider): Boolean = colliders.remove(collider)

    /** The pass itself, exposed for callers who would rather drive it than leave it registered. */
    fun project(state: SimdVerlet.State3) {
        contacts = 0
        if (colliders.isEmpty()) return
        val r = range
        val start = r?.first?.coerceAtLeast(0) ?: 0
        val end = if (r == null) state.size else (r.last + 1).coerceAtMost(state.size)
        if (end <= start) return
        repeat(max(1, relaxations)) {
            for (collider in colliders) {
                contacts += collider.projectRange(state, start, end)
            }
        }
    }

    /** Every collider's [Collider.outline], concatenated. */
    fun outline(color: Vec4 = Collider.DEFAULT_OUTLINE_COLOR, thickness: Double = 1.6): List<FLine> =
        colliders.filter { it.enabled }.flatMap { it.outline(color, thickness) }

    /** Unregisters the pass from [sim]. The colliders themselves are left alone. */
    fun detach() {
        sim.removePositionProjection(name)
    }
}

/**
 * Registers [colliders] on this simulation as one post-integration projection pass and hands back
 * the set, which stays live: add to it, retune a collider's [Collider.friction], move a
 * [SphereCollider], all between frames.
 *
 *     val world = sim.addColliders(
 *         SphereCollider(2.5f, 3.0f, 0f, radius = 1.2f).also { it.friction = 0.6f },
 *         PlaneCollider(0f, -1f, 0f, offset = -6f),
 *     )
 *     cloth.step(gravity = 2f, iterations = 16)   // the projection runs inside step()
 *     showPlane(sim.debugLines() + world.outline())
 *
 * @param name key this registers under in [SimdVerlet.positionProjections]; a second call with the
 *   same name replaces the first set.
 */
fun SimdVerlet.addColliders(
    vararg colliders: Collider,
    name: String = "colliders",
    range: IntRange? = null
): ColliderSet {
    val set = ColliderSet(this, name, range)
    colliders.forEach { set.add(it) }
    addPositionProjection(name) { state, _ -> set.project(state) }
    return set
}

/** A 64-segment circle in the XY plane at `z`, for the collider outlines. */
private fun circleXY(cx: Float, cy: Float, cz: Float, radius: Float, color: Vec4, thickness: Double): FLine {
    val line = FLine()
    val steps = 64
    for (s in 0..steps) {
        val a = 2.0 * PI * s / steps
        val x = cx + cos(a) * radius
        val y = cy + sin(a) * radius
        if (s == 0) line.moveTo(x, y, cz.toDouble()) else line.lineTo(x, y, cz.toDouble())
    }
    line.color = color
    line.thickness = thickness
    return line
}
