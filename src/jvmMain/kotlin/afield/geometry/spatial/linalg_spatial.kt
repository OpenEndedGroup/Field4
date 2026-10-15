@file:afield.runtime.completion.CompletionCategory("Math")

package afield.geometry.spatial

import afield.data.collections.invoke
import afield.data.collections.s
import afield.data.collections.times
import afield.document.context.why
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.file
import afield.document.workspace.path
import afield.geometry.mesh.bounds
import afield.geometry.mesh.centroid
import afield.geometry.mesh.normalized
import afield.geometry.mesh.volume
import afield.graphics.render.rangeTo
import afield.graphics.shader.compute
import afield.graphics.shader.mesh
import afield.graphics.text.layout
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.mean
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.product
import afield.math.autodiff.round
import afield.math.autodiff.times
import afield.math.statistics.Vec3NNIndex
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.media.image.close
import afield.media.image.normalized
import afield.media.image.open
import afield.runtime.lifecycle.once
import afield.study.ui.numbers

import afield.animation.keyframes.h
import afield.audio.synth.*
import afield.data.collections.get
import afield.data.collections.r
import afield.geometry.mesh.convexHull
import afield.graphics.render.rect
import afield.graphics.vulkan.buffers.add
import afield.lang.stacksl.h
import afield.math.autodiff.abs
import afield.math.autodiff.dot
import afield.math.autodiff.minOf
import afield.math.autodiff.sum
import afield.math.linalg.Vec2
import afield.math.linalg.Vec3
import afield.math.linalg.Vec4
import afield.math.linalg.d
import afield.math.linalg.i
import afield.math.linalg.len
import afield.math.linalg.median
import afield.math.linalg.x
import afield.media.image.median
import afield.runtime.execution.end
import afield.runtime.execution.start
import afield.ui.output.h
import com.github.quickhull3d.QuickHull3D
import afield.math.linalg.Vector2f
import afield.math.linalg.Vector2fc
import afield.math.linalg.Vector3f
import afield.math.linalg.Vector3fc
import afield.math.linalg.Vector4f
import afield.math.linalg.Vector4fc
import afield.math.linalg.Quaternionf
import afield.math.linalg.Quaternionfc
import afield.math.linalg.Matrix3f
import afield.math.linalg.Matrix3fc
import afield.math.linalg.Matrix4f
import afield.math.linalg.Matrix4fc
import afield.math.linalg.DualQuaternionf
import afield.math.linalg.*
import kotlin.math.max
import kotlin.math.min

/**
 * The short vector aliases ([Vec2], [Vec3], [Vec4], [Quat], [DualQuat]) plus the constructors and
 * point-cloud helpers that sit on top of Field's generated `afield.math.linalg` types.
 *
 * `vec(...)` is the usual way to make one — from numbers, by copying, or by concatenating a
 * scalar with a smaller vector — and `quat` / `dualquat` do the same for rotations. Collections
 * of vectors gain `center`, `median` / `medianVec` and `aabbBounds` in 2D, 3D and 4D; 3D also
 * gets `obbBounds` (a box aligned to the covariance matrix's principal axes, found by power
 * iteration, so a PCA fit rather than a minimal-volume one),
 * `convexHull`, which goes through the QuickHull3D library and returns the input unchanged if the
 * hull cannot be built, and `convexHullPeelingMedian`, which peels hulls away and returns the
 * centre of what is left — a single point, not a cloud. [Cuboid] is a separate plain 3D
 * box-with-size type used for layout-style union / inset / intersects work.
 *
 * These vector types are mutable and most operations mutate the receiver, so copy first
 * (`Vec3(existing)`) when you need to keep the original. Swizzles and the *vector* `invoke`, `%=`
 * and `len` operators are absent here: they are generated for every float vector into
 * `Swizzles.kt` and `VectorOperators.kt` by `tools/linalg-generator`. `%=` for [Quat] and
 * [DualQuat] is defined here, because no generator emits those.
 */

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
 * A deeply outlier-resistant centre: strip the convex hull off the cloud [peelSteps] times, then take
 * the mean of whatever is left.
 *
 * Each peel discards every point on the current outside, so a handful of far-flung strays are gone by
 * the second round and cannot influence the answer at all — unlike [center], which averages them in,
 * or [medianVec], which is per-axis. Stops early once four or fewer points remain (and returns
 * [center] outright at that size), so on a small or hollow cloud this degrades to the mean.
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


/**
 * An axis-aligned 3D box held as a corner plus a size — `x, y, z` and `w, h, d` — and immutable.
 *
 * *Not* the same shape as [AxisAlignedBoundingBox3D], which stores two corners: this is
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
