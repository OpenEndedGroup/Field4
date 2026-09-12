@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Modelling with functions: a [ScalarField3D] is `(Vec3) -> Float`, the signed-distance primitives
 * and combinators that let you write a shape as an expression, and [marchingCubes], which turns any
 * such expression into a watertight [TriMesh].
 *
 * This is the 3D counterpart of `contours.kt`. There, a [ScalarField2D] is marched by
 * [generateIsoContours] into `FLine` polylines; here a [ScalarField3D] is marched into triangles.
 * The two files are parallel, and reading the 2D one first is the fastest way to
 * understand this one.
 *
 * ```kotlin
 * val body = sdfSmoothUnion(
 *     sdfSphere(0.8),
 *     sdfBox(vec(1.4, 0.25, 0.25)),
 *     k = 0.25,
 * )
 * val drilled = sdfSubtract(body, sdfCylinder(0.22, 3.0))
 * val mesh = marchingCubes(drilled, boundsOf(vec(-1.6, -1.6, -1.6), vec(1.6, 1.6, 1.6)), resolution = 96)
 * println("${mesh.triangleCount} tris, watertight ${mesh.isWatertight()}")
 * ```
 *
 * # What [marchingCubes] here actually does
 *
 * It is a **topologically consistent** marching cubes, not the 1987 lookup table:
 *
 *  - The field is sampled once per grid corner, so the cost is `(n+1)^3` evaluations however many
 *    triangles come out.
 *  - Every crossing sits on a grid *edge*, and edges are keyed globally, so a vertex is created once
 *    and **shared** by all the cubes that touch it. That is what makes the output watertight
 *    (`χ = 2` for a marched sphere) rather than a soup of unwelded triangles needing [TriMesh.weld].
 *  - Instead of a 256-entry triangle table, each cube's crossings are joined into closed loops on the
 *    cube's own boundary and each loop is triangulated. On a face where four edges cross — the
 *    ambiguous case the classic table resolves arbitrarily — the pairing is chosen by the
 *    **asymptotic decider** (the sign of the bilinear saddle value). Because that decision is a pure
 *    function of the face's four corner values, the two cubes sharing the face always agree, which is
 *    exactly the property the classic table lacks and the reason plain marching cubes leaks.
 *  - Winding comes from the field itself: each triangle is oriented against the gradient of the
 *    trilinear interpolant, so normals point toward increasing field value — outward, for the
 *    signed-distance convention where outside is positive.
 *
 * Known limits, stated rather than hidden: a cube containing two separate sheets of surface can be
 * oriented from one gradient and get the other sheet backwards, so a field with structure much finer
 * than the grid wants more resolution; and the loop triangulation reproduces the *sign pattern*
 * faithfully but is not guaranteed to reproduce the trilinear interpolant's exact topology in the
 * rarest configurations.
 *
 * # A note on mutation
 *
 * [Vec3] is mutable and the march reuses one sample vector for speed. A [ScalarField3D] must
 * therefore **read** the vector it is handed and not write to it. Every combinator below copies
 * before transforming, so composing them is safe; a hand-written field that does `p.mul(2f)` will
 * corrupt the march.
 */

/** A scalar field in space: `f(x, y, z)`. For a signed distance function, negative is inside. */
typealias ScalarField3D = (Vec3) -> Float

/**
 * A grid of samples as a [ScalarField3D], read back by trilinear interpolation.
 *
 * The bridge for a field that is expensive, stateful or not thread-safe to evaluate: sample it once
 * into an array in whatever order suits it, then hand the array to [marchingCubes] or
 * [afield.manifold.Solid.levelSetOf], which sample in an order of their own choosing and, in
 * Manifold's case, from several threads at once.
 *
 * ```kotlin
 * // A RectSL scene's signed distance over (x, y, time), one autodiff frame per time slice.
 * val grid = FloatArray(nx * ny * nt)
 * for (k in 0 until nt) {
 *     val frame = field.frameAt(duration * k / (nt - 1.0))
 *     for (j in 0 until ny) for (i in 0 until nx) {
 *         grid[(k * ny + j) * nx + i] = frame.distanceAt(x(i), y(j)).value.toFloat()
 *     }
 * }
 * val solid = Solid.levelSetOf(sampledField3D(grid, nx, ny, nt, bounds), bounds, edgeLength = 0.1)
 * ```
 *
 * Indexing is `(k * ny + j) * nx + i`, which is [IntVolume]'s convention, so a volume and a sample
 * grid over the same lattice are laid out the same way.
 *
 * Two limits, both of which reach a measurement taken off the result. Interpolating a sampled signed
 * distance is not the same function as the distance itself: it is exact at the grid corners and an
 * approximation between them, largest where the true field has a crease. And outside [bounds] this
 * returns [outside] rather than extrapolating, so a surface that reaches the wall comes out cut off
 * there, the same way a march does.
 *
 * @param values one sample per lattice point, `nx * ny * nz` of them.
 * @param bounds the box the lattice spans, corner sample to corner sample.
 * @param outside what to return for a point outside [bounds]. The default is far-outside for a
 *   negative-inside field; pass a negative value for a positive-inside one.
 */
fun sampledField3D(
    values: FloatArray,
    nx: Int,
    ny: Int,
    nz: Int,
    bounds: AxisAlignedBoundingBox3D,
    outside: Float = 1e6f,
): ScalarField3D {
    require(nx >= 2 && ny >= 2 && nz >= 2) { "a sampled field needs at least 2 samples per axis, got ${nx}x${ny}x$nz" }
    require(values.size == nx * ny * nz) {
        "values must be ${nx * ny * nz} samples for a ${nx}x${ny}x$nz lattice, got ${values.size}"
    }
    val size = bounds.size
    require(size.x > 0f && size.y > 0f && size.z > 0f) { "sampled-field bounds must have positive extent, got $size" }
    val minX = bounds.min.x
    val minY = bounds.min.y
    val minZ = bounds.min.z
    val stepX = size.x / (nx - 1)
    val stepY = size.y / (ny - 1)
    val stepZ = size.z / (nz - 1)

    fun at(i: Int, j: Int, k: Int): Float = values[(k * ny + j) * nx + i]

    return { p ->
        val gx = (p.x - minX) / stepX
        val gy = (p.y - minY) / stepY
        val gz = (p.z - minZ) / stepZ
        if (gx < 0f || gy < 0f || gz < 0f || gx > nx - 1f || gy > ny - 1f || gz > nz - 1f) {
            outside
        } else {
            // Truncation is floor here, because the branch above has already established that all
            // three are non-negative.
            val i0 = gx.toInt().coerceAtMost(nx - 2)
            val j0 = gy.toInt().coerceAtMost(ny - 2)
            val k0 = gz.toInt().coerceAtMost(nz - 2)
            val tx = gx - i0
            val ty = gy - j0
            val tz = gz - k0
            val c00 = at(i0, j0, k0) + (at(i0 + 1, j0, k0) - at(i0, j0, k0)) * tx
            val c10 = at(i0, j0 + 1, k0) + (at(i0 + 1, j0 + 1, k0) - at(i0, j0 + 1, k0)) * tx
            val c01 = at(i0, j0, k0 + 1) + (at(i0 + 1, j0, k0 + 1) - at(i0, j0, k0 + 1)) * tx
            val c11 = at(i0, j0 + 1, k0 + 1) + (at(i0 + 1, j0 + 1, k0 + 1) - at(i0, j0 + 1, k0 + 1)) * tx
            val c0 = c00 + (c10 - c00) * ty
            val c1 = c01 + (c11 - c01) * ty
            c0 + (c1 - c0) * tz
        }
    }
}

/**
 * How far a marched crossing is kept from a grid corner, as a fraction of the cell edge.
 *
 * See the comment at the interpolation site in [marchingCubes]: a corner sample that is exactly at the
 * iso level would otherwise collapse several crossings onto one point and tear the surface.
 */
private const val EDGE_CROSSING_EPSILON = 1e-3f

// ==============================================================================================
// Bounds
// ==============================================================================================

/** An [AxisAlignedBoundingBox3D] from two corners, sorted so `min <= max` on every axis. */
fun boundsOf(a: Vec3, b: Vec3): AxisAlignedBoundingBox3D = AxisAlignedBoundingBox3D(
    Vec3(min(a.x, b.x), min(a.y, b.y), min(a.z, b.z)),
    Vec3(max(a.x, b.x), max(a.y, b.y), max(a.z, b.z)),
)

/** A cube of side `2 * radius` centred on [center] — the usual march window for a centred shape. */
fun boundsAround(center: Vec3 = Vec3(0f, 0f, 0f), radius: Number = 1.0): AxisAlignedBoundingBox3D {
    val r = radius.toFloat()
    return AxisAlignedBoundingBox3D(
        Vec3(center.x - r, center.y - r, center.z - r),
        Vec3(center.x + r, center.y + r, center.z + r),
    )
}

/** This mesh's axis-aligned bounds. A unit box centred on the origin for an empty mesh. */
fun TriMesh.bounds(): AxisAlignedBoundingBox3D {
    val b = boundsArray()
    return AxisAlignedBoundingBox3D(
        Vec3(b[0].toFloat(), b[1].toFloat(), b[2].toFloat()),
        Vec3(b[3].toFloat(), b[4].toFloat(), b[5].toFloat()),
    )
}

/** These bounds grown outward by [margin] on every axis — room for a march not to clip the shape. */
fun AxisAlignedBoundingBox3D.expanded(margin: Number): AxisAlignedBoundingBox3D {
    val m = margin.toFloat()
    return AxisAlignedBoundingBox3D(
        Vec3(min.x - m, min.y - m, min.z - m),
        Vec3(max.x + m, max.y + m, max.z + m),
    )
}

// ==============================================================================================
// SDF primitives
// ==============================================================================================

/** A sphere of [radius] centred on [center]. Exact signed distance. */
fun sdfSphere(radius: Number = 1.0, center: Vec3 = Vec3(0f, 0f, 0f)): ScalarField3D {
    val r = radius.toFloat()
    val cx = center.x; val cy = center.y; val cz = center.z
    return { p ->
        val dx = p.x - cx; val dy = p.y - cy; val dz = p.z - cz
        sqrt(dx * dx + dy * dy + dz * dz) - r
    }
}

/**
 * An axis-aligned box of full extents [size] centred on [center]. Exact signed distance (the
 * standard `length(max(q,0)) + min(max(q.x,q.y,q.z),0)` form, correct outside *and* inside).
 */
fun sdfBox(size: Vec3 = Vec3(1f, 1f, 1f), center: Vec3 = Vec3(0f, 0f, 0f)): ScalarField3D {
    val hx = size.x / 2f; val hy = size.y / 2f; val hz = size.z / 2f
    val cx = center.x; val cy = center.y; val cz = center.z
    return { p ->
        val qx = abs(p.x - cx) - hx
        val qy = abs(p.y - cy) - hy
        val qz = abs(p.z - cz) - hz
        val ox = max(qx, 0f); val oy = max(qy, 0f); val oz = max(qz, 0f)
        sqrt(ox * ox + oy * oy + oz * oz) + min(max(qx, max(qy, qz)), 0f)
    }
}

/** A box with its edges and corners rounded off by [radius] — [sdfBox] shrunk then offset. */
fun sdfRoundBox(size: Vec3 = Vec3(1f, 1f, 1f), radius: Number = 0.1, center: Vec3 = Vec3(0f, 0f, 0f)): ScalarField3D {
    val r = radius.toFloat()
    val inner = sdfBox(Vec3(size.x - 2f * r, size.y - 2f * r, size.z - 2f * r), center)
    return { p -> inner(p) - r }
}

/** A torus in the `xz` plane: [majorRadius] to the tube centre, [minorRadius] the tube. Exact. */
fun sdfTorus(majorRadius: Number = 1.0, minorRadius: Number = 0.3, center: Vec3 = Vec3(0f, 0f, 0f)): ScalarField3D {
    val big = majorRadius.toFloat(); val small = minorRadius.toFloat()
    val cx = center.x; val cy = center.y; val cz = center.z
    return { p ->
        val dx = p.x - cx; val dy = p.y - cy; val dz = p.z - cz
        val q = sqrt(dx * dx + dz * dz) - big
        sqrt(q * q + dy * dy) - small
    }
}

/** A half-space: the signed distance to the plane through `normal * offset` with that [normal]. */
fun sdfPlane(normal: Vec3 = Vec3(0f, 1f, 0f), offset: Number = 0.0): ScalarField3D {
    val n = Vec3(normal).normalize()
    val d = offset.toFloat()
    return { p -> p.x * n.x + p.y * n.y + p.z * n.z - d }
}

/** A finite cylinder along `y`: [radius] across, [height] tall, centred on [center]. Exact. */
fun sdfCylinder(radius: Number = 0.5, height: Number = 1.0, center: Vec3 = Vec3(0f, 0f, 0f)): ScalarField3D {
    val r = radius.toFloat(); val hy = height.toFloat() / 2f
    val cx = center.x; val cy = center.y; val cz = center.z
    return { p ->
        val dx = p.x - cx; val dz = p.z - cz
        val qx = sqrt(dx * dx + dz * dz) - r
        val qy = abs(p.y - cy) - hy
        val ox = max(qx, 0f); val oy = max(qy, 0f)
        sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f)
    }
}

/** A capsule: the [radius]-thick tube around the segment from [a] to [b]. Exact. */
fun sdfCapsule(a: Vec3, b: Vec3, radius: Number = 0.25): ScalarField3D {
    val ax = a.x; val ay = a.y; val az = a.z
    val bax = b.x - ax; val bay = b.y - ay; val baz = b.z - az
    val len2 = (bax * bax + bay * bay + baz * baz).coerceAtLeast(1e-20f)
    val r = radius.toFloat()
    return { p ->
        val px = p.x - ax; val py = p.y - ay; val pz = p.z - az
        val t = ((px * bax + py * bay + pz * baz) / len2).coerceIn(0f, 1f)
        val dx = px - bax * t; val dy = py - bay * t; val dz = pz - baz * t
        sqrt(dx * dx + dy * dy + dz * dz) - r
    }
}

/**
 * The gyroid, `sin x cos y + sin y cos z + sin z cos x`, thresholded to a sheet of [thickness].
 *
 * Not a distance function (its gradient is not unit length), which is exactly why it is here: a
 * marcher that only needs *signs* handles it fine, and the result — a triply-periodic minimal
 * surface, one connected sheet of arbitrarily high genus — is the most convincing single test that
 * the topology bookkeeping is real.
 */
fun sdfGyroid(scale: Number = 3.0, thickness: Number = 0.2): ScalarField3D {
    val s = scale.toFloat(); val t = thickness.toFloat()
    return { p ->
        val x = p.x * s; val y = p.y * s; val z = p.z * s
        abs(sin(x) * cos(y) + sin(y) * cos(z) + sin(z) * cos(x)) - t
    }
}

/**
 * A sum of smooth radial blobs — the classic metaball field, `iso` at the surface.
 *
 * Signs are inverted relative to a density field so the usual convention holds (negative inside), so
 * this composes with the `sdf…` combinators directly.
 *
 * @param centers blob positions. @param radius influence radius of each blob.
 */
fun sdfMetaballs(centers: List<Vec3>, radius: Number = 0.5, iso: Number = 1.0): ScalarField3D {
    val r2 = (radius.toFloat() * radius.toFloat()).coerceAtLeast(1e-12f)
    val level = iso.toFloat()
    val xs = FloatArray(centers.size) { centers[it].x }
    val ys = FloatArray(centers.size) { centers[it].y }
    val zs = FloatArray(centers.size) { centers[it].z }
    return { p ->
        var sum = 0f
        for (i in xs.indices) {
            val dx = p.x - xs[i]; val dy = p.y - ys[i]; val dz = p.z - zs[i]
            val d2 = (dx * dx + dy * dy + dz * dz) / r2
            // Wyvill's finite-support falloff: zero beyond the influence radius, so a blob is local.
            if (d2 < 1f) {
                val k = 1f - d2
                sum += k * k * k
            }
        }
        level - sum
    }
}

// ==============================================================================================
// Combinators
// ==============================================================================================

/** Everything inside any of [fields] — pointwise `min`. */
fun sdfUnion(vararg fields: ScalarField3D): ScalarField3D {
    require(fields.isNotEmpty()) { "sdfUnion needs at least one field" }
    val list = fields.toList()
    return { p ->
        var v = Float.MAX_VALUE
        for (f in list) v = min(v, f(p))
        v
    }
}

/** Everything inside all of [fields] — pointwise `max`. */
fun sdfIntersect(vararg fields: ScalarField3D): ScalarField3D {
    require(fields.isNotEmpty()) { "sdfIntersect needs at least one field" }
    val list = fields.toList()
    return { p ->
        var v = -Float.MAX_VALUE
        for (f in list) v = max(v, f(p))
        v
    }
}

/** [a] with [b] drilled out of it — `max(a, -b)`. */
fun sdfSubtract(a: ScalarField3D, b: ScalarField3D): ScalarField3D = { p -> max(a(p), -b(p)) }

/** Inside and outside swapped. */
fun sdfInvert(f: ScalarField3D): ScalarField3D = { p -> -f(p) }

/**
 * A union with a fillet of radius roughly [k] where the two surfaces meet — the polynomial smooth
 * minimum, the operator that makes SDF modelling feel like clay rather than CSG.
 */
fun sdfSmoothUnion(a: ScalarField3D, b: ScalarField3D, k: Number = 0.2): ScalarField3D {
    val kk = k.toFloat().coerceAtLeast(1e-6f)
    return { p ->
        val da = a(p); val db = b(p)
        val h = (0.5f + 0.5f * (db - da) / kk).coerceIn(0f, 1f)
        db + (da - db) * h - kk * h * (1f - h)
    }
}

/** [sdfSmoothUnion]'s counterpart for intersection: a filleted `max`. */
fun sdfSmoothIntersect(a: ScalarField3D, b: ScalarField3D, k: Number = 0.2): ScalarField3D {
    val kk = k.toFloat().coerceAtLeast(1e-6f)
    return { p ->
        val da = a(p); val db = b(p)
        val h = (0.5f - 0.5f * (db - da) / kk).coerceIn(0f, 1f)
        db + (da - db) * h + kk * h * (1f - h)
    }
}

/** [sdfSubtract] with a fillet, for a drilled hole whose rim is not a knife edge. */
fun sdfSmoothSubtract(a: ScalarField3D, b: ScalarField3D, k: Number = 0.2): ScalarField3D =
    sdfSmoothIntersect(a, sdfInvert(b), k)

/**
 * The exponential smooth minimum — smoother than [sdfSmoothUnion] and, unlike it, associative, so
 * blending many shapes at once gives the same answer whatever order you fold them in.
 */
fun sdfExpUnion(fields: List<ScalarField3D>, k: Number = 8.0): ScalarField3D {
    require(fields.isNotEmpty()) { "sdfExpUnion needs at least one field" }
    val kk = k.toFloat().coerceAtLeast(1e-3f)
    return { p ->
        var sum = 0f
        for (f in fields) sum += exp(-kk * f(p))
        -ln(sum.coerceAtLeast(1e-30f)) / kk
    }
}

/** A hollow shell of wall thickness `2 * thickness` around this field's surface. */
fun ScalarField3D.shell(thickness: Number = 0.05): ScalarField3D {
    val t = thickness.toFloat()
    return { p -> abs(this(p)) - t }
}

/** This shape grown outward by [radius], which rounds off every convex edge it has. */
fun ScalarField3D.rounded(radius: Number = 0.05): ScalarField3D {
    val r = radius.toFloat()
    return { p -> this(p) - r }
}

/** This field moved by [offset]. */
fun ScalarField3D.translated(offset: Vec3): ScalarField3D {
    val ox = offset.x; val oy = offset.y; val oz = offset.z
    val scratch = Vec3()
    return { p -> this(scratch.set(p.x - ox, p.y - oy, p.z - oz)) }
}

/** This field uniformly scaled about the origin. Distances are rescaled too, so it stays an SDF. */
fun ScalarField3D.scaled(factor: Number): ScalarField3D {
    val s = factor.toFloat()
    require(abs(s) > 1e-9f) { "scale factor must not be zero" }
    val scratch = Vec3()
    return { p -> this(scratch.set(p.x / s, p.y / s, p.z / s)) * s }
}

/** This field rotated by [q]. Rigid, so it stays an SDF. */
fun ScalarField3D.rotated(q: Quat): ScalarField3D {
    val inv = Quat(-q.x, -q.y, -q.z, q.w)
    val scratch = Vec3()
    return { p ->
        // v' = v + 2 * cross(inv.xyz, cross(inv.xyz, v) + inv.w * v), written out to avoid a dest arg.
        val qx = inv.x; val qy = inv.y; val qz = inv.z; val qw = inv.w
        val tx = qy * p.z - qz * p.y + qw * p.x
        val ty = qz * p.x - qx * p.z + qw * p.y
        val tz = qx * p.y - qy * p.x + qw * p.z
        this(
            scratch.set(
                p.x + 2f * (qy * tz - qz * ty),
                p.y + 2f * (qz * tx - qx * tz),
                p.z + 2f * (qx * ty - qy * tx),
            )
        )
    }
}

/**
 * This shape tiled infinitely on a lattice of [period] — one primitive, unlimited copies, at no
 * extra cost per copy. Zero on an axis leaves that axis untiled.
 */
fun ScalarField3D.repeated(period: Vec3): ScalarField3D {
    val scratch = Vec3()
    fun wrap(v: Float, p: Float): Float = if (p <= 1e-9f) v else v - p * Math.round(v / p)
    return { p -> this(scratch.set(wrap(p.x, period.x), wrap(p.y, period.y), wrap(p.z, period.z))) }
}

/** This shape twisted about `y` by [turnsPerUnit] full turns per unit of height. */
fun ScalarField3D.twisted(turnsPerUnit: Number = 0.25): ScalarField3D {
    val k = (turnsPerUnit.toDouble() * 2.0 * PI).toFloat()
    val scratch = Vec3()
    return { p ->
        val a = k * p.y
        val c = cos(a); val s = sin(a)
        this(scratch.set(c * p.x - s * p.z, p.y, s * p.x + c * p.z))
    }
}

/**
 * This shape's surface pushed in and out by a sine lattice of amplitude [amplitude] and spatial
 * [frequency] — the cheapest interesting texture, and no longer a true distance function (which the
 * marcher does not mind).
 */
fun ScalarField3D.displaced(amplitude: Number = 0.05, frequency: Number = 6.0): ScalarField3D {
    val a = amplitude.toFloat(); val f = frequency.toFloat()
    return { p -> this(p) + a * sin(f * p.x) * sin(f * p.y) * sin(f * p.z) }
}

/**
 * Central-difference gradient of this field at [p], step [h]. Six evaluations.
 *
 * For a true signed distance function this is (close to) the unit surface normal near the surface,
 * which is what an SDF ray-marcher shades with.
 */
fun ScalarField3D.gradient(p: Vec3, h: Number = 1e-3): Vec3 {
    val e = h.toFloat()
    val s = Vec3()
    val dx = this(s.set(p.x + e, p.y, p.z)) - this(s.set(p.x - e, p.y, p.z))
    val dy = this(s.set(p.x, p.y + e, p.z)) - this(s.set(p.x, p.y - e, p.z))
    val dz = this(s.set(p.x, p.y, p.z + e)) - this(s.set(p.x, p.y, p.z - e))
    return Vec3(dx / (2f * e), dy / (2f * e), dz / (2f * e))
}

/** The unit outward normal of this field's iso-surface at [p] — [gradient], normalised. */
fun ScalarField3D.normalAt(p: Vec3, h: Number = 1e-3): Vec3 {
    val g = gradient(p, h)
    val l = g.length()
    return if (l < 1e-20f) Vec3(0f, 1f, 0f) else g.mul(1f / l)
}

// ==============================================================================================
// Marching cubes
// ==============================================================================================

/**
 * The `iso` level set of [field] over [bounds], as a watertight [TriMesh] with shared edge vertices.
 *
 * See this file's overview for how it works and where its limits are. The short
 * version: `(resolution+1)^3` field samples, one shared vertex per crossed grid edge, the
 * asymptotic decider on ambiguous faces so neighbouring cubes never disagree, and winding taken from
 * the field's own gradient.
 *
 * ```kotlin
 * val ball = marchingCubes(sdfSphere(1.0), boundsAround(radius = 1.4), resolution = 64)
 * ball.signedVolume()   // -> 4.187…, against 4/3 pi = 4.18879
 * ball.isWatertight()   // -> true
 * ```
 *
 * @param field sampled at grid corners. Must not mutate the vector it is given.
 * @param bounds the window to march. A shape touching the window's wall comes out open there.
 * @param resolution cubes along the longest axis of [bounds]; the other axes get a proportional
 *   count so the cells stay near-cubic. Cost and triangle count both grow as the square of this.
 * @param iso the level to extract.
 * @param positiveOutside `true` for the signed-distance convention (outside is positive), which
 *   makes normals point outward. Pass `false` for a density field where inside is the larger value.
 * @param vertexNormals attach per-vertex normals interpolated from the trilinear gradient. Cheap and
 *   much better looking than face normals on a marched surface, so it defaults on.
 */
fun marchingCubes(
    field: ScalarField3D,
    bounds: AxisAlignedBoundingBox3D = boundsAround(),
    resolution: Int = 48,
    iso: Double = 0.0,
    positiveOutside: Boolean = true,
    vertexNormals: Boolean = true,
): TriMesh {
    require(resolution >= 1) { "resolution must be >= 1, got $resolution" }
    val size = Vec3(bounds.max).sub(bounds.min)
    require(size.x > 0f && size.y > 0f && size.z > 0f) { "march bounds must have positive extent, got $size" }
    val longest = max(size.x, max(size.y, size.z))
    val nx = max(1, Math.round(resolution * size.x / longest))
    val ny = max(1, Math.round(resolution * size.y / longest))
    val nz = max(1, Math.round(resolution * size.z / longest))
    return marchingCubes(field, bounds, nx, ny, nz, iso, positiveOutside, vertexNormals)
}

/** [marchingCubes] with the cell count given per axis, when the shape wants an anisotropic grid. */
fun marchingCubes(
    field: ScalarField3D,
    bounds: AxisAlignedBoundingBox3D,
    nx: Int,
    ny: Int,
    nz: Int,
    iso: Double = 0.0,
    positiveOutside: Boolean = true,
    vertexNormals: Boolean = true,
): TriMesh {
    require(nx >= 1 && ny >= 1 && nz >= 1) { "cell counts must be >= 1, got ${nx}x${ny}x$nz" }
    val level = iso.toFloat()
    val ox = bounds.min.x; val oy = bounds.min.y; val oz = bounds.min.z
    val dx = (bounds.max.x - ox) / nx
    val dy = (bounds.max.y - oy) / ny
    val dz = (bounds.max.z - oz) / nz

    // ---- sample once per grid corner
    val sx = nx + 1; val sy = ny + 1; val sz = nz + 1
    val values = FloatArray(sx * sy * sz)
    val probe = Vec3()
    var w = 0
    for (k in 0 until sz) {
        val z = oz + k * dz
        for (j in 0 until sy) {
            val y = oy + j * dy
            for (i in 0 until sx) {
                values[w++] = field(probe.set(ox + i * dx, y, z)) - level
            }
        }
    }
    fun value(i: Int, j: Int, k: Int): Float = values[(k * sy + j) * sx + i]

    // ---- shared vertices, keyed by grid edge (corner + axis)
    val vertexOf = HashMap<Long, Int>()
    val px = ArrayList<Float>(); val py = ArrayList<Float>(); val pz = ArrayList<Float>()
    val out = ArrayList<Int>()

    /** The 8 cube corners as `(di, dj, dk)`, in the order the bit index of the corner implies. */
    val cornerDi = intArrayOf(0, 1, 1, 0, 0, 1, 1, 0)
    val cornerDj = intArrayOf(0, 0, 1, 1, 0, 0, 1, 1)
    val cornerDk = intArrayOf(0, 0, 0, 0, 1, 1, 1, 1)
    /** The 12 cube edges as corner-index pairs, in the conventional marching-cubes order. */
    val edgeA = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3)
    val edgeB = intArrayOf(1, 2, 3, 0, 5, 6, 7, 4, 4, 5, 6, 7)
    /**
     * The 6 cube faces as 4 edge indices in cyclic order, paired with the 4 corner indices in the
     * same cyclic order — so `faceCorner[f][t]` and `faceCorner[f][t+1]` are the endpoints of
     * `faceEdge[f][t]`. This adjacency is what the loop walk and the asymptotic decider both need.
     */
    val faceEdge = arrayOf(
        intArrayOf(0, 1, 2, 3),      // k = 0, corners 0 1 2 3
        intArrayOf(4, 5, 6, 7),      // k = 1, corners 4 5 6 7
        intArrayOf(0, 9, 4, 8),      // j = 0, corners 0 1 5 4
        intArrayOf(2, 11, 6, 10),    // j = 1, corners 2 3 7 6
        intArrayOf(3, 11, 7, 8),     // i = 0, corners 0 3 7 4
        intArrayOf(1, 10, 5, 9),     // i = 1, corners 1 2 6 5
    )
    val faceCorner = arrayOf(
        intArrayOf(0, 1, 2, 3),
        intArrayOf(4, 5, 6, 7),
        intArrayOf(0, 1, 5, 4),
        intArrayOf(2, 3, 7, 6),
        intArrayOf(0, 3, 7, 4),
        intArrayOf(1, 2, 6, 5),
    )

    val cornerValue = FloatArray(8)
    val cornerX = FloatArray(8); val cornerY = FloatArray(8); val cornerZ = FloatArray(8)
    val edgeVertex = IntArray(12)
    val neighborA = IntArray(12)
    val neighborB = IntArray(12)
    val loop = ArrayList<Int>(12)

    /** Interpolated crossing on cube edge [e], created once per grid edge and cached. */
    fun crossing(i: Int, j: Int, k: Int, e: Int): Int {
        val a = edgeA[e]; val b = edgeB[e]
        // Normalise to (low corner, axis) so both cubes sharing the edge produce the same key.
        val ai = i + cornerDi[a]; val aj = j + cornerDj[a]; val ak = k + cornerDk[a]
        val bi = i + cornerDi[b]; val bj = j + cornerDj[b]; val bk = k + cornerDk[b]
        val axis = when {
            ai != bi -> 0
            aj != bj -> 1
            else -> 2
        }
        val li = min(ai, bi); val lj = min(aj, bj); val lk = min(ak, bk)
        val key = (((lk.toLong() * sy + lj) * sx + li) * 3L) + axis
        return vertexOf.getOrPut(key) {
            val va = cornerValue[a]; val vb = cornerValue[b]
            val denom = vb - va
            // t is nudged off the endpoints on purpose. A grid corner whose sample is *exactly* at the
            // iso level is not exotic — a sphere of radius 1 marched on a grid with 0.2 spacing has
            // corners at (1,0,0) and (0.6,0.8,0) sitting precisely on it — and every crossing on every
            // edge out of that corner would otherwise interpolate to the corner itself. Several
            // coincident vertices make the triangles between them degenerate, they get dropped, and the
            // surface leaks: 24 boundary loops in an otherwise perfect marched sphere. A relative nudge
            // of 1e-3 of a cell is far below the marcher's own O(h^2) accuracy and removes the whole
            // failure mode.
            val t = if (abs(denom) < 1e-20f) 0.5f else (-va / denom).coerceIn(EDGE_CROSSING_EPSILON, 1f - EDGE_CROSSING_EPSILON)
            px.add(cornerX[a] + (cornerX[b] - cornerX[a]) * t)
            py.add(cornerY[a] + (cornerY[b] - cornerY[a]) * t)
            pz.add(cornerZ[a] + (cornerZ[b] - cornerZ[a]) * t)
            px.size - 1
        }
    }

    for (k in 0 until nz) for (j in 0 until ny) for (i in 0 until nx) {
        var inside = 0
        for (c in 0..7) {
            val v = value(i + cornerDi[c], j + cornerDj[c], k + cornerDk[c])
            cornerValue[c] = v
            cornerX[c] = ox + (i + cornerDi[c]) * dx
            cornerY[c] = oy + (j + cornerDj[c]) * dy
            cornerZ[c] = oz + (k + cornerDk[c]) * dz
            if (v < 0f) inside = inside or (1 shl c)
        }
        if (inside == 0 || inside == 255) continue

        // Which of the 12 edges cross, and where.
        java.util.Arrays.fill(edgeVertex, -1)
        java.util.Arrays.fill(neighborA, -1)
        java.util.Arrays.fill(neighborB, -1)
        for (e in 0..11) {
            val a = edgeA[e]; val b = edgeB[e]
            if (((inside shr a) and 1) != ((inside shr b) and 1)) edgeVertex[e] = crossing(i, j, k, e)
        }

        fun link(e1: Int, e2: Int) {
            if (neighborA[e1] < 0) neighborA[e1] = e2 else neighborB[e1] = e2
            if (neighborA[e2] < 0) neighborA[e2] = e1 else neighborB[e2] = e1
        }

        // Join crossings into segments face by face.
        for (f in 0..5) {
            val fe = faceEdge[f]; val fc = faceCorner[f]
            val active = (0..3).filter { edgeVertex[fe[it]] >= 0 }
            when (active.size) {
                0 -> {}
                2 -> link(fe[active[0]], fe[active[1]])
                4 -> {
                    // Diagonally opposite corners share a sign: ambiguous. The bilinear saddle value
                    // decides, and because it depends only on these four corner values the cube on the
                    // other side of this face makes the identical choice.
                    val a = cornerValue[fc[0]]; val b = cornerValue[fc[1]]
                    val c = cornerValue[fc[2]]; val d = cornerValue[fc[3]]
                    val denom = a + c - b - d
                    val saddle = if (abs(denom) < 1e-20f) 0f else (a * c - b * d) / denom
                    // Same sign as a/c means the a-c region is joined through the middle, so the
                    // surface must cut off b and d one at a time: pair (e0,e1) and (e2,e3).
                    val joinAC = (saddle >= 0f) == (a >= 0f)
                    if (joinAC) {
                        link(fe[0], fe[1]); link(fe[2], fe[3])
                    } else {
                        link(fe[1], fe[2]); link(fe[3], fe[0])
                    }
                }
                else -> {
                    // A face can only have 0, 2 or 4 crossings; anything else means a NaN corner.
                    error("marchingCubes: face $f of cell ($i,$j,$k) has ${active.size} crossings — non-finite field?")
                }
            }
        }

        // Trilinear gradient at the cube centre, for orienting this cube's triangles.
        var gx = 0f; var gy = 0f; var gz = 0f
        for (c in 0..7) {
            val sgnX = if (cornerDi[c] == 1) 1f else -1f
            val sgnY = if (cornerDj[c] == 1) 1f else -1f
            val sgnZ = if (cornerDk[c] == 1) 1f else -1f
            gx += sgnX * cornerValue[c]; gy += sgnY * cornerValue[c]; gz += sgnZ * cornerValue[c]
        }
        if (!positiveOutside) { gx = -gx; gy = -gy; gz = -gz }

        fun emit(a: Int, b: Int, c: Int) {
            if (a == b || b == c || a == c) return
            val ux = px[b] - px[a]; val uy = py[b] - py[a]; val uz = pz[b] - pz[a]
            val vx = px[c] - px[a]; val vy = py[c] - py[a]; val vz = pz[c] - pz[a]
            val cx = uy * vz - uz * vy
            val cy = uz * vx - ux * vz
            val cz = ux * vy - uy * vx
            if (cx * cx + cy * cy + cz * cz < 1e-24f) return
            if (cx * gx + cy * gy + cz * gz >= 0f) {
                out.add(a); out.add(b); out.add(c)
            } else {
                out.add(a); out.add(c); out.add(b)
            }
        }

        // Walk the segment graph into closed loops and triangulate each.
        val done = BooleanArray(12)
        for (start in 0..11) {
            if (edgeVertex[start] < 0 || done[start] || neighborA[start] < 0) continue
            loop.clear()
            var current = start
            var previous = -1
            var guard = 0
            while (guard++ <= 12) {
                done[current] = true
                loop.add(edgeVertex[current])
                val next = if (neighborA[current] != previous) neighborA[current] else neighborB[current]
                if (next < 0 || next == start) break
                previous = current
                current = next
            }
            when {
                loop.size < 3 -> {}
                loop.size == 3 -> emit(loop[0], loop[1], loop[2])
                loop.size == 4 -> {
                    // Split on the shorter diagonal: the fan a quad gets from corner 0 can be a sliver.
                    val d02 = dist2(px, py, pz, loop[0], loop[2])
                    val d13 = dist2(px, py, pz, loop[1], loop[3])
                    if (d02 <= d13) {
                        emit(loop[0], loop[1], loop[2]); emit(loop[0], loop[2], loop[3])
                    } else {
                        emit(loop[1], loop[2], loop[3]); emit(loop[1], loop[3], loop[0])
                    }
                }
                else -> {
                    // 5- and 6-gons around a cube are not planar or convex, so fan from the loop's own
                    // centroid rather than from a corner.
                    var mx = 0f; var my = 0f; var mz = 0f
                    for (v in loop) { mx += px[v]; my += py[v]; mz += pz[v] }
                    val inv = 1f / loop.size
                    px.add(mx * inv); py.add(my * inv); pz.add(mz * inv)
                    val hub = px.size - 1
                    for (t in loop.indices) emit(hub, loop[t], loop[(t + 1) % loop.size])
                }
            }
        }
    }

    if (out.isEmpty()) return TriMesh.empty
    val pos = FloatArray(px.size * 3)
    for (v in px.indices) { pos[v * 3] = px[v]; pos[v * 3 + 1] = py[v]; pos[v * 3 + 2] = pz[v] }
    val mesh = TriMesh(pos, out.toIntArray()).removeUnusedVertices()
    return if (vertexNormals) mesh.withVertexNormals() else mesh
}

/** Squared distance between two accumulated march vertices — used to pick a quad's shorter diagonal. */
private fun dist2(px: List<Float>, py: List<Float>, pz: List<Float>, a: Int, b: Int): Float {
    val dx = px[a] - px[b]; val dy = py[a] - py[b]; val dz = pz[a] - pz[b]
    return dx * dx + dy * dy + dz * dz
}

/** [marchingCubes] as a method on the field, for `sdfSphere(1.0).march(...)`-style chains. */
fun ScalarField3D.march(
    bounds: AxisAlignedBoundingBox3D = boundsAround(),
    resolution: Int = 48,
    iso: Double = 0.0,
    positiveOutside: Boolean = true,
): TriMesh = marchingCubes(this, bounds, resolution, iso, positiveOutside)

/**
 * An [Img] as a 3D field, one z-slice per… no — this is the *2D* bridge: the image's value at
 * `(x, z)`, independent of `y`, minus `y`. Marching it gives the same surface [Img.toHeightfieldMesh]
 * builds directly, which is a useful cross-check and occasionally a useful modelling primitive (you
 * can subtract a heightfield from a solid).
 *
 * @param scale multiplies the sampled value before comparing it to `y`.
 * @param bounds the `xz` window the image is stretched across.
 */
fun Img.asHeightSdf(scale: Number = 0.25, bounds: AxisAlignedBoundingBox3D = boundsAround()): ScalarField3D {
    val src = if (channels == 1) this else luma()
    val s = scale.toFloat()
    val x0 = bounds.min.x; val z0 = bounds.min.z
    val w = (bounds.max.x - x0).coerceAtLeast(1e-9f)
    val d = (bounds.max.z - z0).coerceAtLeast(1e-9f)
    return { p ->
        val u = ((p.x - x0) / w).coerceIn(0f, 1f)
        val v = ((p.z - z0) / d).coerceIn(0f, 1f)
        val ix = (u * (src.width - 1)).toInt().coerceIn(0, src.width - 1)
        val iy = (v * (src.height - 1)).toInt().coerceIn(0, src.height - 1)
        p.y - src.at(ix, iy) * s
    }
}
