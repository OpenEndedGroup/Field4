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
 * `convexHull` and `convexHullPeelingMedian`, which go through the QuickHull3D library and
 * return the input unchanged if the hull cannot be built. [Cuboid] is a separate plain 3D
 * box-with-size type used for layout-style union / inset / intersects work.
 *
 * These vector types are mutable and most operations mutate the receiver, so copy first
 * (`Vec3(existing)`) when you need to keep the original. Swizzles and the `invoke`, `%=` and
 * `len` operators are deliberately absent here: they are generated for every float vector into
 * `Swizzles.kt` and `VectorOperators.kt` by `tools/linalg-generator`.
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
typealias Quat = Quaternionf
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
    val size: Vec2 get() = Vec2(max).sub(min)
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
    val size: Vec3 get() = Vec3(max).sub(min)
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
    val size: Vec4 get() = Vec4(max).sub(min)
    val center: Vec4 get() = Vec4(min).add(size.mul(0.5f, Vec4()))
}

fun Vec3.closest(a: Iterable<Vec3>) = a.minByOrNull { this.distance(it) }!!
fun Vec4.closest(a: Iterable<Vec4>) = a.minByOrNull { this.distance(it) }!!
fun Vec2.closest(a: Iterable<Vec2>) = a.minByOrNull { this.distance(it) }!!

// ============================================================================
// Collection<Vec2> extensions
// ============================================================================

fun Collection<Vec2>.center(): Vec2 {
    if (isEmpty()) return Vec2(0f, 0f)
    val sum = Vec2(0f, 0f)
    for (p in this) sum.add(p)
    return sum.mul(1f / size)
}

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

fun Collection<Vec2>.medianVec(): Vec2 {
    return Vec2(median(0), median(1))
}

/**
 * Compute axis-aligned bounds as an AxisAlignedBoundingBox2D.
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

fun Collection<Vec3>.center(): Vec3 {
    if (isEmpty()) return Vec3(0f, 0f, 0f)
    val sum = Vec3(0f, 0f, 0f)
    for (p in this) sum.add(p)
    return sum.mul(1f / size)
}

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

fun Collection<Vec3>.medianVec(): Vec3 {
    return Vec3(median(0), median(1), median(2))
}

/**
 * Compute axis-aligned bounds as an AxisAlignedBoundingBox3D.
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
 * Compute oriented bounding box (OBB) using PCA.
 *
 * @return OrientedBoundingBox with center, axes, and extents
 *
 * The OBB is defined by: center + extent[i] * axes[i] for each axis i.
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
    axes[0] = dominantEigenvector(cov) ?: Vec3(1f, 0f, 0f)

    // Deflate and find second eigenvector (orthogonal to first)
    deflateCovariance(cov, axes[0])
    axes[1] = dominantEigenvector(cov) ?: Vec3(0f, 1f, 0f)
    axes[1].sub(axes[1].dot(axes[0]) * axes[0]).normalize()

    // Third is cross product
    axes[2] = Vec3(axes[0]).cross(axes[1])

    // 4) Project all points onto each axis to find extents
    val extents = FloatArray(3)
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
    }

    return OrientedBoundingBox(
        center = center,
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

private fun deflateCovariance(cov: Array<FloatArray>, v: Vec3) {
    for (i in 0..2) {
        for (j in 0..2) {
            cov[i][j] -= v.get(i) * v.get(j)
        }
    }
}

/**
 * Compute convex hull of Vec3 points using QuickHull3D.
 *
 * @return List of Vec3 points forming the convex hull (may be reordered)
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
 * Compute convex hull peeling median.
 *
 * Iteratively removes the convex hull from the point set until a small core remains.
 * Returns the center of the final core set.
 *
 * @param peelSteps Maximum number of peeling iterations (default 5)
 * @return Median-like point from iterative convex hull peeling
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
 * Wrap a collection of Vec3 as points for QuickHull3D convex hull computation.
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

fun Collection<Vec4>.center(): Vec4 {
    if (isEmpty()) return Vec4(0f, 0f, 0f, 0f)
    val sum = Vec4(0f, 0f, 0f, 0f)
    for (p in this) sum.add(p)
    return sum.mul(1f / size)
}

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

fun Collection<Vec4>.medianVec(): Vec4 {
    return Vec4(median(0), median(1), median(2), median(3))
}

/**
 * Compute axis-aligned bounds as an AxisAlignedBoundingBox4D.
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


val Vector2dc.v3: Vector3d
    get() = Vector3d(this.x(), this.y(), 0.0)

val Vector3dc.v2: Vector2d
    get() = Vector2d(this.x(), this.y())

val Vector2fc.v3: Vector3f
    get() = Vector3f(this.x(), this.y(), 0.0f)

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

fun vec(v: Vec2, z: Number) = Vector3f(v.x, v.y, z.toFloat())
fun vec(x : Number, yz: Vec2) = Vector3f(x.toFloat(), yz.x, yz.y)
fun vec(x: Number, yzw: Vec3) = Vector4f(x.toFloat(), yzw.x, yzw.y, yzw.z)

// The vector `invoke` (copy-with-changes and copy-then-block), `%=` (remAssign) and `len` rescale
// are generated for every float heap vector into afield.stdlib.VectorOperators.kt by
// tools/linalg-generator. The transform types keep their hand-written in-place assignment below.

operator fun Quat.remAssign(a: Quat) {
    this.set(a)
}

operator fun DualQuat.remAssign(a: DualQuat) {
    this.set(a)
}

infix fun Vector3f.x(b: Vector3f) = Vector3f(this).cross(b)


operator fun Vector3fc.times(s: Number): Vector3f = mul(s.toFloat(), Vector3f())

operator fun Number.times(s: Vector3fc): Vector3f = s.mul(this.toFloat(), Vector3f())
operator fun Number.times(s: Vector2fc): Vector2f = s.mul(this.toFloat(), Vector2f())
operator fun Number.times(s: Vector4fc): Vector4f = s.mul(this.toFloat(), Vector4f())

fun Vector3dc.isNaN() = this.x().isNaN() || this.y().isNaN() || this.z().isNaN()
fun Vector2dc.isNaN() = this.x().isNaN() || this.y().isNaN()

fun Vector3fc.isNaN() = this.x().isNaN() || this.y().isNaN() || this.z().isNaN()
fun Vector2fc.isNaN() = this.x().isNaN() || this.y().isNaN()

operator fun Vector3fc.times(v: Quaternionfc): Vector3f = v.transform(this, Vector3f())
operator fun Vector2fc.times(v: Quaternionfc): Vector2fc =
    v.transform(Vector3f(x(), y(), 0f), Vector3f()).xy
operator fun Vector3fc.times(v: DualQuaternionf): Vector3f = v.transform(this, Vector3f())


fun quat(angle: Number = 0.0, axis: Vector3f = vec(0, 0, 1)) =
    Quaternionf().setAngleAxis(angle.toFloat(), axis.x, axis.y, axis.z)

fun dualquat(
    rotation: Quaternionfc = Quaternionf(),
    translation: Vector3fc = vec(0, 0, 0)
) = DualQuaternionf(rotation, translation)

fun dualquat(real: Quaternionfc, dual: Quaternionfc) =
    DualQuaternionf(real, dual).normalize()

class Cuboid(val x: Float, val y: Float, val z: Float, val w: Float, val h: Float, val d: Float) {

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

    fun translate(by: Vector2d): Cuboid {
        return Cuboid((x + by.x).toFloat(), (y + by.y).toFloat(), z, w, h, d)
    }

    fun translate(by: Vector3d): Cuboid {
        return Cuboid((x + by.x).toFloat(), (y + by.y).toFloat(), (z + by.z).toFloat(), w, h, d)
    }

    fun intersects(point: Vector3d): Boolean {
        return (point.x >= x && point.x <= x + w) && point.y >= y && point.y <= (y + h) && point.z >= z && point.z <= (z + d)
    }

    fun intersects(r: Cuboid): Boolean {
        return ((r.x < this.x + this.w) && r.w + r.x > this.x && r.y < this.y + this.h && r.y + r.h > this.y && r.z < this.z + this.d && r.z + r.d > this.z)
    }


    override fun toString(): String {
        return "Cuboid{" + "x=" + x + ", y=" + y + ", z=" + z + ", w=" + w + ", h=" + h + ", d=" + d + '}'
    }


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

    fun intersectsX(x: Double): Boolean {
        return (x >= this.x && x < this.x + this.w)
    }

    fun inside(start: Double, end: Double): Boolean {
        return this.x >= start && this.x + this.w < end
    }

    fun convert(x: Double, y: Double, z: Double): Vector3d {
        return Vector3d(this.x + x * this.w, this.y + y * this.h, this.z + z * this.d)
    }

    fun duplicate(): Cuboid {
        return Cuboid(x, y, z, w, h, d)
    }

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
        fun union(r: Cuboid?, rect: Cuboid?): Cuboid? {
            if (r == null) return rect
            if (rect == null) return r
            return r.union(rect)
        }
    }
}
