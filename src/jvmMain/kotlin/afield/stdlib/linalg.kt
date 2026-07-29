@file:afield.CompletionCategory("Math")

package afield.stdlib

import com.github.quickhull3d.QuickHull3D
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector2fc
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector3fc
import afield.stdlib.linalg.Vector4f
import afield.stdlib.linalg.Vector4fc
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Quaternionfc
import afield.stdlib.linalg.Matrix3f
import afield.stdlib.linalg.Matrix3fc
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Matrix4fc
import afield.stdlib.linalg.DualQuaternionf
import afield.stdlib.linalg.*
import kotlin.math.max
import kotlin.math.min

/**
 * The short vector aliases ([Vec2], [Vec3], [Vec4], [Quat], [DualQuat]) plus the constructors and
 * point-cloud helpers that sit on top of Field's generated `afield.stdlib.linalg` types.
 *
 * `vec(...)` is the usual way to make one — from numbers, by copying, or by concatenating a
 * scalar with a smaller vector — and `quat` / `dualquat` do the same for rotations. Collections
 * of vectors gain `center`, `median` / `medianVec` and `aabbBounds` in 2D, 3D and 4D; 3D also
 * gets `obbBounds` (PCA by power iteration, an explicit approximation — the code says so),
 * `convexHull`, which goes through the QuickHull3D library and returns the input unchanged if the
 * hull cannot be built, and `convexHullPeelingMedian`, which peels hulls away and returns the
 * centre of what is left — a single point, not a cloud. [Cuboid] is a separate plain 3D
 * box-with-size type used for layout-style union / inset / intersects work.
 *
 * These vector types are mutable and most operations mutate the receiver, so copy first
 * (`Vec3(existing)`) when you need to keep the original. Swizzles and the *vector* `invoke`, `%=`
 * and `len` operators are deliberately absent here: they are generated for every float vector into
 * `Swizzles.kt` and `VectorOperators.kt` by `tools/linalg-generator`. `%=` for [Quat] and
 * [DualQuat] is defined here, because no generator emits those.
 */

/**
 * Field's 4D vector alias, backed by Field's generated mutable [Vector4f].
 *
 * Use `vec(x, y, z, w)` to construct one. These methods generally mutate the receiver;
 * copy first with `Vec4(existing)` when you want to preserve the original value.
 */
typealias Vec4 = Vector4f

/**
 * Field's 3D vector alias, backed by Field's generated mutable [Vector3f].
 *
 * Use `vec(x, y, z)` to construct one. The public `x`, `y`, and `z` components are
 * mutable floats, and most operations mutate the receiver unless you pass a destination vector.
 */
typealias Vec3 = Vector3f

/**
 * Field's 2D vector alias, backed by Field's generated mutable [Vector2f].
 *
 * Use `vec(x, y)` to construct one. The public `x` and `y` components are mutable floats;
 * copy with `Vec2(existing)` when you want a non-mutating workflow.
 */
typealias Vec2 = Vector2f
/**
 * Field's rotation alias, backed by the generated mutable [Quaternionf] — a rotation with no
 * position, and no gimbal lock.
 *
 * Build one with `quat(angle, axis)`, where the angle is in **radians**. Multiplying a `Vec3` by a
 * `Quat` rotates the vector (`p * q`), composing two quaternions composes their rotations, and `%=`
 * assigns one in place. Unit length is what makes a quaternion a rotation, so `normalize()` after
 * accumulating a long chain of them.
 */
typealias Quat = Quaternionf

/**
 * Field's rigid-transform alias, backed by the generated mutable [DualQuaternionf] — a rotation *and*
 * a translation in one value.
 *
 * Build one with `dualquat(rotation, translation)`. The reason to prefer this over a `Matrix4f` is
 * that dual quaternions interpolate: blending two of them gives a sensible intermediate pose, where
 * blending two matrices does not. Multiplying a `Vec3` by one applies the whole transform.
 */
typealias DualQuat = DualQuaternionf

/**
 * Oriented Bounding Box (OBB) representation.
 *
 * @param center The centroid of the bounding box
 * @param axes Array of 3 orthonormal principal component axes
 * @param extents Half-widths along each corresponding axis
 */
data class OrientedBoundingBox(
    val center: Vec3,
    val axes: Array<Vec3>,
    val extents: FloatArray
)

/**
 * Axis-Aligned Bounding Box for 2D vectors.
 *
 * @param min Minimum corner (lower-left)
 * @param max Maximum corner (upper-right)
 */
data class AxisAlignedBoundingBox2D(
    val min: Vec2,
    val max: Vec2
) {
    /** Width and height of the box, as `max - min`. Zero on both axes for a single point. */
    val size: Vec2 get() = Vec2(max).sub(min)

    /** The midpoint of the box — not the centroid of the points it was built from. */
    val center: Vec2 get() = Vec2(min).add(size.mul(0.5f, Vec2()))
}

/**
 * Axis-Aligned Bounding Box for 3D vectors.
 *
 * @param min Minimum corner
 * @param max Maximum corner
 */
data class AxisAlignedBoundingBox3D(
    val min: Vec3,
    val max: Vec3
) {
    /** Width, height and depth of the box, as `max - min`. */
    val size: Vec3 get() = Vec3(max).sub(min)

    /** The midpoint of the box — not the centroid of the points it was built from. */
    val center: Vec3 get() = Vec3(min).add(size.mul(0.5f, Vec3()))
}

/**
 * Axis-Aligned Bounding Box for 4D vectors.
 *
 * @param min Minimum corner
 * @param max Maximum corner
 */
data class AxisAlignedBoundingBox4D(
    val min: Vec4,
    val max: Vec4
) {
    /** Extent of the box on each of the four axes, as `max - min`. */
    val size: Vec4 get() = Vec4(max).sub(min)

    /** The midpoint of the box — not the centroid of the points it was built from. */
    val center: Vec4 get() = Vec4(min).add(size.mul(0.5f, Vec4()))
}

/**
 * The member of [a] nearest this point by Euclidean distance.
 *
 * A linear scan, so fine for a handful of candidates and the wrong tool for thousands per frame —
 * build a `Vec3NNIndex` (`smile_neighbors.kt`) for that. **Throws** if [a] is empty, and returns the
 * element itself, not a copy, so mutating the result mutates the collection's point.
 */
fun Vec3.closest(a: Iterable<Vec3>) = a.minByOrNull { this.distance(it) }!!

/** The member of [a] nearest this point in 4D. Linear scan; **throws** if [a] is empty. */
fun Vec4.closest(a: Iterable<Vec4>) = a.minByOrNull { this.distance(it) }!!

/**
 * The member of [a] nearest this point in the plane. Linear scan; **throws** if [a] is empty, and the
 * result is the collection's own instance rather than a copy.
 */
fun Vec2.closest(a: Iterable<Vec2>) = a.minByOrNull { this.distance(it) }!!

// ============================================================================
// Collection<Vec2> extensions
// ============================================================================

/**
 * The centroid — the plain mean of every point, in a fresh [Vec2].
 *
 * Returns `(0, 0)` for an empty collection rather than throwing. The mean is pulled around by
 * outliers; [medianVec] is the robust alternative when a few stray points should not move the answer.
 */
fun Collection<Vec2>.center(): Vec2 {
    if (isEmpty()) return Vec2(0f, 0f)
    val sum = Vec2(0f, 0f)
    for (p in this) sum.add(p)
    return sum.mul(1f / size)
}

/**
 * The median of one component across the collection: [index] 0 for x, 1 for y.
 *
 * Even-sized collections get the mean of the two middle values, so the result need not be one of the
 * input coordinates. **Throws** `IllegalArgumentException` for an index outside `0..1`, and on an empty
 * collection. Sorts a copy per call, so [medianVec] is two sorts, not one.
 */
@JvmName("medianVec2")
fun Collection<Vec2>.median(index: Int = 0): Float {
    require(index in 0..1) { "index must be 0 (x) or 1 (y)" }
    val sorted = if (index == 0) sortedBy { it.x } else sortedBy { it.y }
    return if (sorted.size % 2 == 0) {
        (sorted[sorted.size / 2 - 1].let { if (index == 0) it.x else it.y } +
         sorted[sorted.size / 2].let { if (index == 0) it.x else it.y }) / 2f
    } else {
        sorted[sorted.size / 2].let { if (index == 0) it.x else it.y }
    }
}

/**
 * The component-wise median, as a point — the outlier-resistant answer to "where is this cloud".
 *
 * Each axis is medianed independently, so the result is generally **not** one of the input points and
 * need not lie inside their convex hull. That is usually fine for centring something on a noisy blob;
 * when it is not, [center] is the true mean.
 */
fun Collection<Vec2>.medianVec(): Vec2 {
    return Vec2(median(0), median(1))
}

/**
 * Axis-aligned bounds of the points, as an [AxisAlignedBoundingBox2D].
 *
 * One pass, no allocation per point. An empty collection gives a degenerate box at the origin rather
 * than an exception — check `isEmpty()` first if that would be indistinguishable from real data.
 */
@JvmName("aabbBoundsVec2")
fun Collection<Vec2>.aabbBounds(): AxisAlignedBoundingBox2D {
    if (isEmpty()) return AxisAlignedBoundingBox2D(Vec2(0f, 0f), Vec2(0f, 0f))
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    for (p in this) {
        if (p.x < minX) minX = p.x
        if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y
        if (p.y > maxY) maxY = p.y
    }
    return AxisAlignedBoundingBox2D(Vec2(minX, minY), Vec2(maxX, maxY))
}

// ============================================================================
// Collection<Vec3> extensions
// ============================================================================

/**
 * The centroid — the plain mean of every point, in a fresh [Vec3]. `(0, 0, 0)` when empty.
 *
 * See [medianVec] or [convexHullPeelingMedian] when outliers must not drag the answer around.
 */
fun Collection<Vec3>.center(): Vec3 {
    if (isEmpty()) return Vec3(0f, 0f, 0f)
    val sum = Vec3(0f, 0f, 0f)
    for (p in this) sum.add(p)
    return sum.mul(1f / size)
}

/**
 * The median of one component: [index] 0 for x, 1 for y, 2 for z.
 *
 * Even-sized collections get the mean of the two middle values. **Throws** for an index outside
 * `0..2`, and on an empty collection.
 */
@JvmName("medianVec3")
fun Collection<Vec3>.median(index: Int = 0): Float {
    require(index in 0..2) { "index must be 0 (x), 1 (y), or 2 (z)" }
    val sorted = when (index) {
        0 -> sortedBy { it.x }
        1 -> sortedBy { it.y }
        else -> sortedBy { it.z }
    }
    val getter: (Vec3) -> Float = when (index) {
        0 -> { v -> v.x }
        1 -> { v -> v.y }
        else -> { v -> v.z }
    }
    return if (sorted.size % 2 == 0) {
        (getter(sorted[sorted.size / 2 - 1]) + getter(sorted[sorted.size / 2])) / 2f
    } else {
        getter(sorted[sorted.size / 2])
    }
}

/**
 * The component-wise median, as a point — robust to outliers, and generally **not** one of the input
 * points, since each axis is medianed independently. [center] is the true mean.
 */
fun Collection<Vec3>.medianVec(): Vec3 {
    return Vec3(median(0), median(1), median(2))
}

/**
 * Axis-aligned bounds of the points, as an [AxisAlignedBoundingBox3D] — the cheap bounding volume,
 * one pass and no rotation.
 *
 * Degenerate box at the origin when empty. [obbBounds] is the version that turns with the data, at
 * considerably more cost.
 */
@JvmName("aabbBoundsVec3")
fun Collection<Vec3>.aabbBounds(): AxisAlignedBoundingBox3D {
    if (isEmpty()) return AxisAlignedBoundingBox3D(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 0f))
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    for (p in this) {
        if (p.x < minX) minX = p.x
        if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y
        if (p.y > maxY) maxY = p.y
        if (p.z < minZ) minZ = p.z
        if (p.z > maxZ) maxZ = p.z
    }
    return AxisAlignedBoundingBox3D(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
}

/**
 * Bounds that turn with the data: an [OrientedBoundingBox] whose axes come from a PCA of the point
 * cloud, so an elongated diagonal cluster gets a tight box where [aabbBounds] would get a loose one.
 *
 * The box is `center + extents[i] * axes[i]` on each axis, with `extents` as **half**-widths, and it
 * really does contain every input point: the centre is the middle of the projections rather than the
 * cloud's centroid, which for a skewed cloud is not the same place. Axes are ordered most-spread first.
 *
 * An **approximation**: the eigenvectors come from ten steps of power iteration with an *unscaled*
 * deflation, not a real eigendecomposition. The first axis is reliable; the second comes out of a
 * Gram-Schmidt residual against it, and for a strongly elongated cloud that residual is small enough
 * that which of the two minor axes is which is close to arbitrary. Fine for fitting a crate round a
 * mesh; not a basis to build a coordinate frame on. A collinear or near-collinear cloud has no second
 * principal direction at all; some perpendicular is substituted (its extent is zero either way) rather
 * than letting the normalisation of a zero-length residual put NaN into the result. Empty input gives the
 * identity axes and zero extents.
 */
fun Collection<Vec3>.obbBounds(): OrientedBoundingBox {
    if (isEmpty()) {
        return OrientedBoundingBox(
            center = Vec3(0f, 0f, 0f),
            axes = arrayOf(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f)),
            extents = floatArrayOf(0f, 0f, 0f)
        )
    }

    // 1) Compute centroid
    val center = center()

    // 2) Build covariance matrix
    val cov = Array(3) { FloatArray(3) }
    for (p in this) {
        val dx = p.x - center.x
        val dy = p.y - center.y
        val dz = p.z - center.z

        cov[0][0] += dx * dx
        cov[0][1] += dx * dy
        cov[0][2] += dx * dz

        cov[1][1] += dy * dy
        cov[1][2] += dy * dz

        cov[2][2] += dz * dz
    }

    // Symmetrize
    cov[1][0] = cov[0][1]
    cov[2][0] = cov[0][2]
    cov[2][1] = cov[1][2]

    // Normalize
    val n = size.toFloat()
    for (i in 0..2) {
        for (j in 0..2) {
            cov[i][j] /= n
        }
    }

    // 3) Power iteration to find dominant eigenvector (simple approach)
    // For production, use proper eigenvalue decomposition. This is a quick approximation.
    val axes = Array(3) { Vec3() }
    axes[0] = dominantEigenvector(cov)?.takeIf { it.isFinite() } ?: Vec3(1f, 0f, 0f)

    // Deflate and find second eigenvector (orthogonal to first).
    // The orthogonalised residual can be zero-length — a collinear or near-collinear cloud has no second
    // principal direction to find — and normalizing that gives NaN, which used to reach the extents and,
    // through them, every coordinate the caller read back. Any orthogonal direction will do there: the
    // extent along it is zero either way.
    deflateCovariance(cov, axes[0])
    val second = dominantEigenvector(cov) ?: Vec3(0f, 1f, 0f)
    second.sub(second.dot(axes[0]) * axes[0])
    axes[1] = if (second.length() > 1e-6f && second.isFinite()) second.normalize() else anyPerpendicular(axes[0])

    // Third is cross product
    axes[2] = Vec3(axes[0]).cross(axes[1])

    // 4) Project all points onto each axis to find extents *and* where the box's centre has to sit.
    // The centroid is not the middle of the projections unless the cloud is symmetric, so taking
    // half-widths about the centroid produced a box that did not contain its own input: for
    // x = 0, 0, 0, 10 the centroid is 2.5, the half-width 5, and the point at 10 fell outside it.
    val extents = FloatArray(3)
    val offsets = FloatArray(3)
    for (i in 0..2) {
        var minProj = Float.POSITIVE_INFINITY
        var maxProj = Float.NEGATIVE_INFINITY
        for (p in this) {
            val rel = Vec3(p).sub(center)
            val proj = axes[i].dot(rel)
            if (proj < minProj) minProj = proj
            if (proj > maxProj) maxProj = proj
        }
        extents[i] = (maxProj - minProj) / 2f
        offsets[i] = (maxProj + minProj) / 2f
    }

    val boxCenter = Vec3(center)
    for (i in 0..2) boxCenter.add(offsets[i] * axes[i])

    return OrientedBoundingBox(
        center = boxCenter,
        axes = axes,
        extents = extents
    )
}

private fun dominantEigenvector(cov: Array<FloatArray>): Vec3? {
    var v = Vec3(1f, 1f, 1f).normalize()
    repeat(10) {
        val av = Vec3(
            cov[0][0] * v.x + cov[0][1] * v.y + cov[0][2] * v.z,
            cov[1][0] * v.x + cov[1][1] * v.y + cov[1][2] * v.z,
            cov[2][0] * v.x + cov[2][1] * v.y + cov[2][2] * v.z
        )
        val len = av.length()
        if (len < 1e-6f) return null
        av.normalize()
        v = av
    }
    return v
}

/**
 * Some unit vector at right angles to [a], chosen by crossing with whichever basis axis [a] leans on least
 * so the cross product is never degenerate.
 */
private fun anyPerpendicular(a: Vec3): Vec3 {
    val basis = when (minOf(kotlin.math.abs(a.x), kotlin.math.abs(a.y), kotlin.math.abs(a.z))) {
        kotlin.math.abs(a.x) -> Vec3(1f, 0f, 0f)
        kotlin.math.abs(a.y) -> Vec3(0f, 1f, 0f)
        else -> Vec3(0f, 0f, 1f)
    }
    val out = Vec3(a).cross(basis)
    return if (out.length() > 1e-9f) out.normalize() else Vec3(0f, 1f, 0f)
}

private fun deflateCovariance(cov: Array<FloatArray>, v: Vec3) {
    for (i in 0..2) {
        for (j in 0..2) {
            cov[i][j] -= v.get(i) * v.get(j)
        }
    }
}

/**
 * The vertices of the 3D convex hull, via QuickHull3D — the points on the outside, with the interior
 * ones dropped.
 *
 * Vertices only, in the library's own order, with no faces or winding; and they are fresh [Vec3]s, not
 * the receiver's instances. Fewer than four points, or a degenerate cloud QuickHull cannot triangulate
 * (all coplanar, all collinear, duplicates), returns **the input unchanged** rather than throwing — so
 * a result the same size as the input means "hull, or gave up", and the two are not distinguishable
 * from the return value alone.
 */
fun List<Vec3>.convexHull(): List<Vec3> {
    if (size < 4) return this.toList()  // Need at least 4 non-coplanar points
    
    val points = DoubleArray(size * 3)
    for (i in indices) {
        points[i * 3] = this[i].x.toDouble()
        points[i * 3 + 1] = this[i].y.toDouble()
        points[i * 3 + 2] = this[i].z.toDouble()
    }
    
    return try {
        val qh = QuickHull3D()
        qh.build(points)
        
        val hullVerts = qh.vertices
        hullVerts.map { Vec3(it[0].toFloat(), it[1].toFloat(), it[2].toFloat()) }
    } catch (e: Exception) {
        // If hull computation fails (coplanar points, etc.), return original
        this.toList()
    }
}

/**
 * A deeply outlier-resistant centre: strip the convex hull off the cloud [peelSteps] times, then take
 * the mean of whatever is left.
 *
 * Each peel discards every point on the current outside, so a handful of far-flung strays are gone by
 * the second round and cannot influence the answer at all — unlike [center], which averages them in,
 * or [medianVec], which is per-axis. Stops early once four or fewer points remain (and returns
 * [center] outright at that size), so on a small or hollow cloud this quietly degrades to the mean.
 *
 * Costs one [convexHull] per peel; expensive enough to keep out of a per-frame path on a large cloud.
 *
 * @param peelSteps how many hulls to strip at most, default 5
 */
fun List<Vec3>.convexHullPeelingMedian(peelSteps: Int = 5): Vec3 {
    if (isEmpty()) return Vec3(0f, 0f, 0f)
    if (size <= 4) return center()
    
    var remaining = this.toMutableList()
    
    repeat(peelSteps) {
        if (remaining.size <= 4) return@repeat  // Stop if too few points
        
        val hull = remaining.convexHull()
        if (hull.isEmpty() || hull.size == remaining.size) {
            // No more peeling possible or all points are on hull
            return@repeat
        }
        
        val hullSet = hull.toSet()
        remaining = remaining.filter { p -> p !in hullSet }.toMutableList()
        
        if (remaining.isEmpty()) {
            // Fallback: return center of previous hull
            remaining = hull.toMutableList()
            return@repeat
        }
    }
    
    return remaining.center()
}

// ============================================================================
// QuickHull3D convenience wrapper
// ============================================================================

/**
 * Flattens the points into the packed `[x0, y0, z0, x1, …]` `DoubleArray` that QuickHull3D and other
 * plain-array libraries expect.
 *
 * A copy, in iteration order, three doubles per point — `Float` precision widened, not gained.
 */
fun Collection<Vec3>.asQuickHullPoints(): DoubleArray {
    val points = DoubleArray(size * 3)
    var idx = 0
    for (p in this) {
        points[idx++] = p.x.toDouble()
        points[idx++] = p.y.toDouble()
        points[idx++] = p.z.toDouble()
    }
    return points
}

// ============================================================================
// Collection<Vec4> extensions
// ============================================================================

/**
 * The component-wise mean of every element, in a fresh [Vec4]. `(0, 0, 0, 0)` when empty.
 *
 * Note that on a collection of *colours* this averages alpha along with the channels, which is rarely
 * the blend you meant.
 */
fun Collection<Vec4>.center(): Vec4 {
    if (isEmpty()) return Vec4(0f, 0f, 0f, 0f)
    val sum = Vec4(0f, 0f, 0f, 0f)
    for (p in this) sum.add(p)
    return sum.mul(1f / size)
}

/**
 * The median of one component: [index] 0 for x, 1 for y, 2 for z, 3 for w.
 *
 * Even-sized collections get the mean of the two middle values. **Throws** for an index outside
 * `0..3`, and on an empty collection.
 */
@JvmName("medianVec4")
fun Collection<Vec4>.median(index: Int = 0): Float {
    require(index in 0..3) { "index must be 0 (x), 1 (y), 2 (z), or 3 (w)" }
    val sorted = when (index) {
        0 -> sortedBy { it.x }
        1 -> sortedBy { it.y }
        2 -> sortedBy { it.z }
        else -> sortedBy { it.w }
    }
    val getter: (Vec4) -> Float = when (index) {
        0 -> { v -> v.x }
        1 -> { v -> v.y }
        2 -> { v -> v.z }
        else -> { v -> v.w }
    }
    return if (sorted.size % 2 == 0) {
        (getter(sorted[sorted.size / 2 - 1]) + getter(sorted[sorted.size / 2])) / 2f
    } else {
        getter(sorted[sorted.size / 2])
    }
}

/** The component-wise median across all four axes, as one [Vec4]. Robust to outliers; see [medianVec]
 * on `Collection<Vec2>` for why the result is generally not one of the inputs. */
fun Collection<Vec4>.medianVec(): Vec4 {
    return Vec4(median(0), median(1), median(2), median(3))
}

/**
 * Axis-aligned bounds on all four axes, as an [AxisAlignedBoundingBox4D]. One pass; degenerate box at
 * the origin when empty.
 */
@JvmName("aabbBoundsVec4")
fun Collection<Vec4>.aabbBounds(): AxisAlignedBoundingBox4D {
    if (isEmpty()) return AxisAlignedBoundingBox4D(Vec4(0f, 0f, 0f, 0f), Vec4(0f, 0f, 0f, 0f))
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var minW = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    var maxW = Float.NEGATIVE_INFINITY
    for (p in this) {
        if (p.x < minX) minX = p.x
        if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y
        if (p.y > maxY) maxY = p.y
        if (p.z < minZ) minZ = p.z
        if (p.z > maxZ) maxZ = p.z
        if (p.w < minW) minW = p.w
        if (p.w > maxW) maxW = p.w
    }
    return AxisAlignedBoundingBox4D(Vec4(minX, minY, minZ, minW), Vec4(maxX, maxY, maxZ, maxW))
}


/** This 2D double vector lifted into 3D with `z = 0`, as a new mutable vector. */
val Vector2dc.v3: Vector3d
    get() = Vector3d(this.x(), this.y(), 0.0)

/** This 3D double vector with its z **dropped**, as a new mutable 2D vector — a projection, not a copy. */
val Vector3dc.v2: Vector2d
    get() = Vector2d(this.x(), this.y())

/**
 * This [Vec2] lifted into 3D with `z = 0`, as a new mutable vector.
 *
 * The idiomatic way to hand a planar point to something that wants a `Vec3`. Reading it allocates, so
 * hoist it out of a tight loop.
 */
val Vector2fc.v3: Vector3f
    get() = Vector3f(this.x(), this.y(), 0.0f)

/** This [Vec3] with its z **dropped**, as a new mutable [Vec2] — flattening, not a perspective divide. */
val Vector3fc.v2: Vector2f
    get() = Vector2f(this.x(), this.y())

// Component swizzles (xyzw and rgba, read + write) are generated exhaustively into
// afield.stdlib.Swizzles.kt by tools/linalg-generator.

/**
 * Creates a mutable 2D [Vec2] from numeric coordinates, converting each component to [Float].
 */
fun vec(x: Number, y: Number) = Vector2f(x.toFloat(), y.toFloat())

/** Creates a mutable 3D [Vec3] from numeric coordinates, converting each component to [Float]. */
fun vec(x: Number, y: Number, z: Number) = Vector3f(x.toFloat(), y.toFloat(), z.toFloat())

/** Creates a mutable 4D [Vec4] from numeric coordinates, converting each component to [Float]. */
fun vec(x: Number, y: Number, z: Number, w: Number) = Vector4f(x.toFloat(), y.toFloat(), z.toFloat(), w.toFloat())

/** Copies a 2D vector into a new mutable [Vec2]. */
fun vec(v: Vector2fc) = Vector2f(v)

/** Copies a 3D vector into a new mutable [Vec3]. */
fun vec(v: Vector3fc) = Vector3f(v)

/** Copies a 4D vector into a new mutable [Vec4]. */
fun vec(v: Vector4fc) = Vector4f(v)

/** A [Vec3] from a [Vec2] and a z — the "give this planar point a height" constructor. Copies [v]. */
fun vec(v: Vec2, z: Number) = Vector3f(v.x, v.y, z.toFloat())

/** A [Vec3] from a leading scalar and a [Vec2] tail, GLSL's `vec3(x, yz)`. Note the argument order. */
fun vec(x : Number, yz: Vec2) = Vector3f(x.toFloat(), yz.x, yz.y)

/**
 * A [Vec4] from a leading scalar and a [Vec3] tail, GLSL's `vec4(x, yzw)`.
 *
 * Watch the order if you are reaching for this to add alpha to a colour: the scalar goes in **front**,
 * so this is `vec(alpha, rgb)`, not `vec(rgb, alpha)`.
 */
fun vec(x: Number, yzw: Vec3) = Vector4f(x.toFloat(), yzw.x, yzw.y, yzw.z)

// The vector `invoke` (copy-with-changes and copy-then-block), `%=` (remAssign) and `len` rescale
// are generated for every float heap vector into afield.stdlib.VectorOperators.kt by
// tools/linalg-generator. The transform types keep their hand-written in-place assignment below.

/**
 * `q %= other` — overwrite this rotation **in place** with another's, keeping the same instance.
 *
 * The point of it is exactly that it does not allocate and does not rebind: anything already holding a
 * reference to `q` (a uniform, a mesh property) sees the new value. `q = other` would swap the
 * reference instead, and those holders would keep the old rotation.
 */
operator fun Quat.remAssign(a: Quat) {
    this.set(a)
}

/** `t %= other` — overwrite this rigid transform **in place**, for the same reasons as [Quat.remAssign]. */
operator fun DualQuat.remAssign(a: DualQuat) {
    this.set(a)
}

/**
 * Cross product as an infix operator: `a x b`, a vector perpendicular to both, right-handed.
 *
 * Returns a **new** vector and leaves both operands alone, unlike the `cross` method it wraps, which
 * writes into its receiver. Length is `|a||b|sin θ`, so normalize if you wanted just the direction, and
 * it collapses to zero for parallel inputs.
 */
infix fun Vector3f.x(b: Vector3f) = Vector3f(this).cross(b)


/** Scales the vector by a number, into a new [Vec3]. Neither operand is touched. */
operator fun Vector3fc.times(s: Number): Vector3f = mul(s.toFloat(), Vector3f())

/** `2 * v` — scalar on the left, so the arithmetic reads the way it does on paper. New [Vec3]. */
operator fun Number.times(s: Vector3fc): Vector3f = s.mul(this.toFloat(), Vector3f())

/** `2 * v` with the scalar on the left, for [Vec2]. New vector; operands untouched. */
operator fun Number.times(s: Vector2fc): Vector2f = s.mul(this.toFloat(), Vector2f())

/** `2 * v` with the scalar on the left, for [Vec4]. New vector; operands untouched. */
operator fun Number.times(s: Vector4fc): Vector4f = s.mul(this.toFloat(), Vector4f())

/** True if any component of this 3D double vector is `NaN` — the check `==` cannot do for you. */
fun Vector3dc.isNaN() = this.x().isNaN() || this.y().isNaN() || this.z().isNaN()

/** True if either component of this 2D double vector is `NaN`. */
fun Vector2dc.isNaN() = this.x().isNaN() || this.y().isNaN()

/**
 * True if any component of this [Vec3] is `NaN`.
 *
 * Worth a guard before handing geometry to a renderer or a solver: one `NaN` point silently poisons
 * bounds, centroids and normals, and `NaN != NaN` means an equality check will not find it. Does not
 * test for infinities.
 */
fun Vector3fc.isNaN() = this.x().isNaN() || this.y().isNaN() || this.z().isNaN()

/** True if either component of this [Vec2] is `NaN`. Infinities are not covered. */
fun Vector2fc.isNaN() = this.x().isNaN() || this.y().isNaN()

/** `p * q` — the point rotated by the quaternion, into a new [Vec3]. Neither operand is modified. */
operator fun Vector3fc.times(v: Quaternionfc): Vector3f = v.transform(this, Vector3f())

/**
 * `p * q` for a planar point: rotates `(x, y, 0)` by the quaternion and returns the xy of the result.
 *
 * Only a rotation about z keeps the point in the plane; with any other axis this is the **projection**
 * of the rotated point, so it can shorten, and a 90° tilt collapses it to the origin.
 */
operator fun Vector2fc.times(v: Quaternionfc): Vector2fc =
    v.transform(Vector3f(x(), y(), 0f), Vector3f()).xy

/**
 * `p * t` — the point moved by the rigid transform (rotate then translate), into a new [Vec3].
 *
 * Unlike the quaternion overload this shifts position as well as direction, so do not use it on a
 * normal or any other direction vector.
 */
operator fun Vector3fc.times(v: DualQuaternionf): Vector3f = v.transform(this, Vector3f())


/**
 * A rotation of [angle] **radians** about [axis], as a [Quat].
 *
 * Radians, not degrees — `quat(PI / 2)` is a quarter turn. The default axis is `+z`, the one that turns
 * things in the screen plane, and the default angle is no rotation at all, so bare `quat()` is the
 * identity. The axis is **normalised for you** — `setAngleAxis` divides through by its length — so
 * `quat(a, vec(0, 0, 5))` gives exactly the same unit quaternion as `quat(a, vec(0, 0, 1))`, and a
 * merely scaled axis can never produce a non-rotation. The one case to avoid is a zero-length axis:
 * that divides by zero and every component comes back `NaN`.
 */
fun quat(angle: Number = 0.0, axis: Vector3f = vec(0, 0, 1)) =
    Quaternionf().setAngleAxis(angle.toFloat(), axis.x, axis.y, axis.z)

/**
 * A rigid transform from a rotation and a translation — the everyday [DualQuat] constructor.
 *
 * Both arguments default to "nothing", so bare `dualquat()` is the identity pose. Order of application
 * when transforming a point is rotate, then translate.
 */
fun dualquat(
    rotation: Quaternionfc = Quaternionf(),
    translation: Vector3fc = vec(0, 0, 0)
) = DualQuaternionf(rotation, translation)

/**
 * A [DualQuat] from its raw real and dual parts, normalized on the way out.
 *
 * The low-level constructor, for when you already hold the two quaternions of the algebra — for a
 * rotation plus a translation, which is what most callers have, use the other overload; passing a
 * *translation* as `dual` here will not do what you want.
 */
fun dualquat(real: Quaternionfc, dual: Quaternionfc) =
    DualQuaternionf(real, dual).normalize()

/**
 * An axis-aligned 3D box held as a corner plus a size — `x, y, z` and `w, h, d` — and immutable.
 *
 * Deliberately *not* the same shape as [AxisAlignedBoundingBox3D], which stores two corners: this is
 * the corner-and-extent form that layout arithmetic wants, and it is the type behind Field's
 * box-packing and region work rather than anything geometric. Every operation returns a new `Cuboid`.
 *
 * The size fields are taken as given and never checked, so a negative `w`, `h` or `d` is representable,
 * and it breaks the two tests in *opposite* directions. [inside] gets more permissive: it asks
 * `x + w < end`, and a negative width shrinks `x + w`, so it answers true more readily rather than
 * less. [intersects] gets permanently false: it asks `point.x >= x && point.x <= x + w`, which with
 * `x + w < x` is unsatisfiable for every point. [inset] by more than half the size is the usual way to
 * end up with one.
 */
class Cuboid(val x: Float, val y: Float, val z: Float, val w: Float, val h: Float, val d: Float) {

    /**
     * The smallest cuboid containing both this one and [r] — with a null [r] treated as "nothing", so
     * this is safe to fold over a nullable accumulator.
     *
     * Note that it unions the *bounds*: the result covers the gap between two distant boxes too.
     */
    fun union(r: Cuboid?): Cuboid {
        if (r == null) return this
        val minx = min(r.x, x)
        val miny = min(r.y, y)
        val minz = min(r.z, z)

        val maxx = max((r.x + r.w), (x + w))
        val maxy = max((r.y + r.h), (y + h))
        val maxz = max((r.z + r.d), (z + d))

        return Cuboid(
            minx, miny, minz, (maxx - minx), (maxy - miny), (maxz - minz)
        )
    }

    /** A copy moved in x and y only, leaving z where it was. Size is unchanged. */
    fun translate(by: Vector2d): Cuboid {
        return Cuboid((x + by.x).toFloat(), (y + by.y).toFloat(), z, w, h, d)
    }

    /** A copy moved on all three axes. Size is unchanged. */
    fun translate(by: Vector3d): Cuboid {
        return Cuboid((x + by.x).toFloat(), (y + by.y).toFloat(), (z + by.z).toFloat(), w, h, d)
    }

    /**
     * Does this box contain [point]? **Closed** on every side, so a point exactly on a face or corner
     * counts as inside — two boxes sharing a face therefore both claim the points on it.
     */
    fun intersects(point: Vector3d): Boolean {
        return (point.x >= x && point.x <= x + w) && point.y >= y && point.y <= (y + h) && point.z >= z && point.z <= (z + d)
    }

    /**
     * Do the two boxes overlap in volume? **Open** here, unlike the point overload: boxes that merely
     * touch — one's face flush against the other's — do *not* intersect, which is what you want when
     * packing things edge to edge.
     */
    fun intersects(r: Cuboid): Boolean {
        return ((r.x < this.x + this.w) && r.w + r.x > this.x && r.y < this.y + this.h && r.y + r.h > this.y && r.z < this.z + this.d && r.z + r.d > this.z)
    }


    /** `Cuboid{x=… y=… z=… w=… h=… d=…}` — the corner and the size, for debugging. */
    override fun toString(): String {
        return "Cuboid{" + "x=" + x + ", y=" + y + ", z=" + z + ", w=" + w + ", h=" + h + ", d=" + d + '}'
    }


    /**
     * A copy shrunk by [by] on **every** side: the corner moves in by that much and each dimension
     * loses twice it. Negative values grow the box instead.
     *
     * Not clamped — insetting a 10-unit box by 6 gives a width of −2, which no `intersects` call will
     * ever be true for. Check the size yourself if the amount is data-driven.
     */
    fun inset(by: Double): Cuboid {
        return Cuboid(
            (x + by).toFloat(),
            (y + by).toFloat(),
            (z + by).toFloat(),
            (w - by * 2).toFloat(),
            (h - by * 2).toFloat(),
            (d - by * 2).toFloat()
        )
    }

    /**
     * Does the box's x span cover the coordinate [x]? Half-open — `[this.x, this.x + w)` — so
     * neighbouring spans laid end to end claim each coordinate exactly once.
     */
    fun intersectsX(x: Double): Boolean {
        return (x >= this.x && x < this.x + this.w)
    }

    /** Is the box's x span **entirely within** `start..end`? Containment, not overlap; y and z are ignored. */
    fun inside(start: Double, end: Double): Boolean {
        return this.x >= start && this.x + this.w < end
    }

    /**
     * Turns a normalized position in the box into a world one: `(0,0,0)` is the corner and `(1,1,1)` the
     * far corner.
     *
     * Unclamped, so values outside `0..1` land outside the box, which is often what you want for a
     * margin. The inverse of the fraction you would compute by hand as `(p - corner) / size`.
     */
    fun convert(x: Double, y: Double, z: Double): Vector3d {
        return Vector3d(this.x + x * this.w, this.y + y * this.h, this.z + z * this.d)
    }

    /** An identical copy. `Cuboid` is immutable, so this is only needed for identity, not for safety. */
    fun duplicate(): Cuboid {
        return Cuboid(x, y, z, w, h, d)
    }

    /** Value equality on all six fields — exact `Float` comparison, so arithmetic drift will fail it. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Cuboid

        if (x != other.x) return false
        if (y != other.y) return false
        if (z != other.z) return false
        if (w != other.w) return false
        if (h != other.h) return false
        if (d != other.d) return false

        return true
    }

    /** Consistent with [equals]: all six fields, so a `Cuboid` is safe as a map key. */
    override fun hashCode(): Int {
        var result = x.hashCode()
        result = 31 * result + y.hashCode()
        result = 31 * result + z.hashCode()
        result = 31 * result + w.hashCode()
        result = 31 * result + h.hashCode()
        result = 31 * result + d.hashCode()
        return result
    }


    companion object {
        /**
         * Null-tolerant [union] for folding: null in either position means "nothing", and two nulls
         * give null. `boxes.fold(null as Cuboid?) { acc, b -> Cuboid.union(acc, b) }` is the idiom.
         */
        fun union(r: Cuboid?, rect: Cuboid?): Cuboid? {
            if (r == null) return rect
            if (rect == null) return r
            return r.union(rect)
        }
    }
}
