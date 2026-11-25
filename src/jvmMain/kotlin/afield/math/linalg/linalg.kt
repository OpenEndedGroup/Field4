@file:afield.runtime.completion.CompletionCategory("Math")

package afield.math.linalg

import afield.data.collections.get
import afield.data.collections.invoke
import afield.document.context.why
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.file
import afield.geometry.mesh.bounds
import afield.geometry.mesh.convexHull
import afield.geometry.mesh.normalized
import afield.geometry.mesh.rotated
import afield.geometry.mesh.scaled
import afield.geometry.mesh.volume
import afield.geometry.spatial.Cuboid
import afield.geometry.spatial.aabbBounds
import afield.geometry.spatial.center
import afield.geometry.spatial.convexHullPeelingMedian
import afield.geometry.spatial.medianVec
import afield.geometry.spatial.obbBounds
import afield.graphics.color.rgb
import afield.graphics.color.rgba
import afield.graphics.render.rangeTo
import afield.graphics.shader.mesh
import afield.graphics.text.layout
import afield.graphics.vulkan.buffers.add
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.mean
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.product
import afield.math.autodiff.sin
import afield.math.functions.sin
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.media.image.Component
import afield.media.image.normalized
import afield.media.image.rotate
import afield.study.ui.numbers

import afield.audio.synth.*
import afield.data.collections.s
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
 * The short names for Field's vector and rotation types ([Vec2], [Vec3], [Vec4], [Quat],
 * [DualQuat]) and the `vec`, `quat` and `dualquat` constructors that build them.
 *
 * `vec(...)` takes numbers, copies an existing vector, or concatenates a scalar with a smaller one.
 * `quat` turns an angle in radians and an axis into a rotation, and `dualquat` pairs a rotation with
 * a translation to give a rigid transform. `v3` lifts a 2D vector into 3D with `z = 0` and `v2`
 * drops the z again, for the float and the double types alike.
 *
 * The rest of the file is what the generated types do not cover: vector times quaternion, vector
 * times dual quaternion, `isNaN` for the 2D and 3D vectors, `%=` for [Quat] and [DualQuat], angles
 * as values (`90.deg`, `0.25.turns`, `30.deg.dir`, [TAU]), and the quaternion fields (`q.inv`,
 * `q.axis`, `q.angle`, `q.forward`, [facing], `90.deg about Vec3.Y`, `v rot q`, `q near r`).
 * `median(index)` reads a single component's median across a collection of vectors and returns that
 * one `Float`.
 *
 * These types are mutable and most methods write into the receiver, so copy first
 * (`Vec3(existing)`) when the original still matters; the operators and infix calls return new
 * values. Swizzles (float vectors), and `2 * v`, the `x` cross product, `dot`, `dist`, `near`,
 * `rot`, `about` and the per-vector `invoke`, `%=` and `len` (float and double), are absent here
 * because `tools/linalg-generator` emits them into `Swizzles.kt` and `VectorOperators.kt`; `%=` for [Quat]
 * and [DualQuat] is written out here because no generator covers those two.
 *
 * Point-cloud geometry built on these types lives elsewhere: centroids, medians and bounding boxes
 * in `linalg_spatial.kt`, convex hulls in `linalg_mesh.kt`.
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
// afield.math.linalg.Swizzles.kt by tools/linalg-generator.

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
// are generated for every float heap vector into afield.math.linalg.VectorOperators.kt by
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

// The infix cross product `a x b` is generated for Vector3fc/Vector3dc (and the scalar 2D form for
// Vector2fc/Vector2dc) into VectorOperators.kt, so a read-only operand such as `v.xyz x w` works.

// `v * 2` for a Vec3 is NOT declared here. The generated `Vector3fc.times(scalar: Number)` in
// afield.math.linalg.CompatTypes covers it, with the same body, and a second copy in this package
// made `v * 2` an overload resolution ambiguity in every box: box code star-imports both
// afield.stdlib and afield.math.linalg, so neither candidate wins. Code inside this package saw
// the same-package declaration win and compiled, which is why it went unnoticed. Vec2 and Vec4 have
// only the generated declaration and always worked.

// `2 * v` (a number on the left) is generated for every float and double vector into
// VectorOperators.kt; a copy here would be the same overload ambiguity as `v * 2` above.

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
 * `q * r` — the Hamilton product, into a new [Quat]; neither operand is modified.
 *
 * For unit quaternions this composes rotations (`r` is applied first, then `q`), the same as
 * `Quat(q).mul(r)`. It is also plain algebra on any four numbers: `i * j == k` but `j * i == -k`, so
 * order matters, and a power series such as `exp` can be summed with nothing but `*` and `+`.
 */
operator fun Quaternionfc.times(r: Quaternionfc): Quat = Quaternionf(this).mul(r)

/** `q * s` — every component scaled by [s], into a new [Quat]. Not a rotation unless renormalized. */
operator fun Quaternionfc.times(s: Number): Quat =
    s.toFloat().let { Quaternionf(x() * it, y() * it, z() * it, w() * it) }

/** `q + r` — the component-wise sum, into a new [Quat]. Not a unit quaternion in general. */
operator fun Quaternionfc.plus(r: Quaternionfc): Quat =
    Quaternionf(x() + r.x(), y() + r.y(), z() + r.z(), w() + r.w())

/** `q - r` — the component-wise difference, into a new [Quat]. */
operator fun Quaternionfc.minus(r: Quaternionfc): Quat =
    Quaternionf(x() - r.x(), y() - r.y(), z() - r.z(), w() - r.w())


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
fun quat(angle: Number = 0.0, axis: Vector3fc = Vector3f.Z) =
    Quaternionf().setAngleAxis(angle.toFloat(), axis.x(), axis.y(), axis.z())

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


// ---------------------------------------------------------------------------------------------
// Angles as values. Each returns radians as a Float, the unit every rotation method takes, so
// `q.rotateX(90.deg)` and `quat(45.deg, Vec3.Y)` need no conversion.

/** One full turn in radians, 2π. */
const val TAU: Double = 2.0 * kotlin.math.PI

/** This many degrees, in radians: `90.deg` is π/2. */
val Number.deg: Float get() = (toDouble() * kotlin.math.PI / 180.0).toFloat()

/** This many whole turns, in radians: `0.25.turns` is a quarter turn, π/2. */
val Number.turns: Float get() = (toDouble() * TAU).toFloat()

/** This many radians, as a Float: the spelling that says so, `1.5.rad`. */
val Number.rad: Float get() = toFloat()

/** The unit [Vec2] at this angle in radians from +x, `(cos, sin)`: `30.deg.dir`. The inverse of `v.angle`. */
val Number.dir: Vec2 get() = toDouble().let { Vector2f(kotlin.math.cos(it).toFloat(), kotlin.math.sin(it).toFloat()) }

// ---------------------------------------------------------------------------------------------
// Quaternion fields. All return new values; `invert()`, `conjugate()` and the other methods are the
// in-place layer.

/** The inverse rotation, as a new [Quat]: `q.inv * q` is the identity. `invert()` does the same in place. */
val Quaternionfc.inv: Quat get() = Quaternionf(this).invert()

/** The conjugate (−x, −y, −z, w), as a new [Quat]; for a unit quaternion it is the inverse. */
val Quaternionfc.conj: Quat get() = Quaternionf(this).conjugate()

/**
 * The axis this rotation turns about, as a new unit [Vec3]. With no rotation at all there is no
 * axis, and this is +z, the same default `quat()` uses.
 */
val Quaternionfc.axis: Vec3 get() {
    val q = Quaternionf(this).normalize()
    val s = kotlin.math.sqrt(kotlin.math.max(0f, 1f - q.w * q.w))
    return if (s < 1e-6f) Vector3f(0f, 0f, 1f) else Vector3f(q.x / s, q.y / s, q.z / s)
}

/** How far this rotation turns about [axis], in radians, 0..2π. */
val Quaternionfc.angle: Float get() = Quaternionf(this).normalize().let { 2f * kotlin.math.acos(it.w.coerceIn(-1f, 1f)) }

/**
 * Where a camera with this orientation looks: the rotated −z, as a new [Vec3].
 *
 * −z because Field's camera is a right-handed look-at whose eye looks down −z. Build an orientation
 * from a direction with [facing].
 */
val Quaternionfc.forward: Vec3 get() = transform(Vector3f(0f, 0f, -1f), Vector3f())

/** The rotated +y, as a new [Vec3]: a camera's up with this orientation. */
val Quaternionfc.up: Vec3 get() = transform(Vector3f(0f, 1f, 0f), Vector3f())

/** The rotated +x, as a new [Vec3]: a camera's right with this orientation. */
val Quaternionfc.right: Vec3 get() = transform(Vector3f(1f, 0f, 0f), Vector3f())

/**
 * The orientation whose [forward] is [direction] and whose [up] leans towards [up], as a new [Quat].
 *
 * `facing(d).forward` is `d.n`. This is what `lookAlong` is usually reached for, without its JOML
 * convention of turning the direction onto +z. When [direction] is parallel to [up] there is no
 * sideways, so +x (or +z, if [direction] is along x) stands in for [up].
 */
fun facing(direction: Vector3fc, up: Vector3fc = Vector3f.Y): Quat {
    val f = direction.n
    var r = f x up
    if (r.lengthSquared() < 1e-12f) r = f x (if (kotlin.math.abs(f.x) < 0.9f) Vector3f.X else Vector3f.Z)
    r.normalize()
    val u = r x f
    return Quaternionf().setFromNormalized(Matrix3f().set(r.x, r.y, r.z, u.x, u.y, u.z, -f.x, -f.y, -f.z))
}

/** A rotation of this many radians about [axis], read aloud: `90.deg about Vec3.Y` is `quat(90.deg, Vec3.Y)`. */
infix fun Number.about(axis: Vector3fc): Quat = quat(this, axis)

/** This vector turned by [q], as a new [Vec3]: the same as `q * v`, spelled the way the 2D `v rot θ` is. */
infix fun Vector3fc.rot(q: Quaternionfc): Vector3f = q.transform(this, Vector3f())

/**
 * True when the two describe the same rotation within 1e-5 per component. `q` and `−q` are the
 * same rotation, so either sign matches; `==` compares the four numbers exactly.
 */
infix fun Quaternionfc.near(r: Quaternionfc): Boolean = near(r, 1e-5f)

/** True when the two describe the same rotation within [tolerance] per component, either sign. */
fun Quaternionfc.near(r: Quaternionfc, tolerance: Number): Boolean {
    val t = tolerance.toFloat()
    fun close(sign: Float) = kotlin.math.abs(x() - sign * r.x()) <= t && kotlin.math.abs(y() - sign * r.y()) <= t &&
        kotlin.math.abs(z() - sign * r.z()) <= t && kotlin.math.abs(w() - sign * r.w()) <= t
    return close(1f) || close(-1f)
}

// ---------------------------------------------------------------------------------------------
// Composition read in the order it happens, quaternion interpolation and powers.

/**
 * "This rotation, then [r]", as a new [Quat]: `r * this`.
 *
 * The Hamilton product reads right to left (`q * r` is r first), which is the most common ordering
 * bug in rotation code; `a then b then c` is applied in the order it is written.
 */
infix fun Quaternionfc.then(r: Quaternionfc): Quat = Quaternionf(r).mul(this)

/** "This transform, then [u]", as a new [DualQuat]: `u * this`, applied in the order written. */
infix fun DualQuaternionf.then(u: DualQuaternionf): DualQuaternionf = u * this

/** "This matrix, then [m]", as a new [Matrix4f]: `m * this`, so `a then b` transforms by `a` first. */
infix fun Matrix4fc.then(m: Matrix4fc): Matrix4f = m * this

/** The first half of `q slerp t to r`; finish it with [to]. */
class QuatSlerp internal constructor(private val from: Quaternionfc, private val t: Float) {
    /** The rotation [t] of the way from the left-hand one to [r], turning at a constant rate the short way round. A new [Quat]. */
    infix fun to(r: Quaternionfc): Quat = Quaternionf(from).slerp(r, t)
}

/** Starts `q slerp t to r`: the rotation [t] of the way to `r`. Unlike the `slerp` method it leaves `q` alone. */
infix fun Quaternionfc.slerp(t: Number): QuatSlerp = QuatSlerp(this, t.toFloat())

/**
 * The quaternion logarithm, as a new [Quat]: `(axis · θ/2, ln |q|)` for a rotation of θ about axis.
 * For a unit quaternion the real part is zero and the rest is half the rotation vector.
 */
fun Quaternionfc.ln(): Quat {
    val vl = kotlin.math.sqrt(x() * x() + y() * y() + z() * z())
    val n = kotlin.math.sqrt(vl * vl + w() * w())
    val k = if (vl < 1e-12f) 0f else kotlin.math.atan2(vl, w()) / vl
    return Quaternionf(x() * k, y() * k, z() * k, kotlin.math.ln(n))
}

/** The quaternion exponential, as a new [Quat]: `e^w (cos |v| + v̂ sin |v|)`, the inverse of [ln]. */
fun Quaternionfc.exp(): Quat {
    val vl = kotlin.math.sqrt(x() * x() + y() * y() + z() * z())
    val e = kotlin.math.exp(w())
    val k = if (vl < 1e-12f) e else e * kotlin.math.sin(vl) / vl
    return Quaternionf(x() * k, y() * k, z() * k, e * kotlin.math.cos(vl))
}

/**
 * This rotation [t] times over, as a new [Quat]: `q.pow(0.5)` is half the turn about the same axis,
 * `q.pow(2)` twice it, `q.pow(-1)` the inverse. Computed as `(ln q · t).exp()`.
 */
fun Quaternionfc.pow(t: Number): Quat = (ln() * t).exp()

// ---------------------------------------------------------------------------------------------
// Easing curves for `a ease (t to curve) to b`.

/**
 * Easing curves: functions from 0..1 to 0..1 that start at 0 and end at 1, for
 * `a ease (t to Ease.inOutCubic) to b` or anywhere else a shaped `t` is wanted (`Ease.outBack(t)`).
 * Any `(Double) -> Double` works in their place.
 */
object Ease {
    /** No easing: `t` itself. */
    val linear: (Double) -> Double = { it }
    /** Smoothstep, `t²(3 − 2t)`: eases in and out with zero speed at both ends. The curve `smooth` uses. */
    val smooth: (Double) -> Double = { it * it * (3 - 2 * it) }
    /** Smootherstep, `t³(6t² − 15t + 10)`: like [smooth] but with zero acceleration at the ends too. */
    val smoother: (Double) -> Double = { it * it * it * (it * (it * 6 - 15) + 10) }
    /** Starts slowly, `t²`. */
    val inQuad: (Double) -> Double = { it * it }
    /** Ends slowly, `1 − (1 − t)²`. */
    val outQuad: (Double) -> Double = { 1 - (1 - it) * (1 - it) }
    /** Slow at both ends, quadratic. */
    val inOutQuad: (Double) -> Double = { if (it < 0.5) 2 * it * it else 1 - 2 * (1 - it) * (1 - it) }
    /** Starts slowly, `t³`. */
    val inCubic: (Double) -> Double = { it * it * it }
    /** Ends slowly, `1 − (1 − t)³`. */
    val outCubic: (Double) -> Double = { 1 - (1 - it) * (1 - it) * (1 - it) }
    /** Slow at both ends, cubic. */
    val inOutCubic: (Double) -> Double = { if (it < 0.5) 4 * it * it * it else 1 - 4 * (1 - it) * (1 - it) * (1 - it) }
    /** Slow at both ends along half a cosine, `(1 − cos πt) / 2`. */
    val inOutSine: (Double) -> Double = { (1 - kotlin.math.cos(kotlin.math.PI * it)) / 2 }
    /** Overshoots the end by about 10% and settles back, the "back" ease. */
    val outBack: (Double) -> Double = { val c = 1.70158; val u = it - 1; 1 + (c + 1) * u * u * u + c * u * u }
}

// ---------------------------------------------------------------------------------------------
// `a damp k to b`: the frame-rate-independent follow, reading the runloop's frame time. The
// explicit-step form `a.damp(k, dt)` is generated beside the other `lerp` variants.

/**
 * Starts `a damp k to b`, the frame-rate-independent follow: each frame moves `1 − e^(−k·dt)` of the
 * gap, with dt the runloop's frame time ([afield.runtime.lifecycle.AnimationClock.frameDt]), so
 * `pos = pos damp 6 to target` closes the same fraction per second at any frame rate, and frame by
 * frame during a capture. Larger [k] follows more tightly.
 */
infix fun Vector2fc.damp(k: Number): Vector2fLerp = damp(k, afield.runtime.lifecycle.AnimationClock.frameDt)
/** `a damp k to b` for [Vector3fc]; see the [Vector2fc] form. */
infix fun Vector3fc.damp(k: Number): Vector3fLerp = damp(k, afield.runtime.lifecycle.AnimationClock.frameDt)
/** `a damp k to b` for [Vector4fc]; see the [Vector2fc] form. */
infix fun Vector4fc.damp(k: Number): Vector4fLerp = damp(k, afield.runtime.lifecycle.AnimationClock.frameDt)
/** `a damp k to b` for [Vector2dc]; see the [Vector2fc] form. */
infix fun Vector2dc.damp(k: Number): Vector2dLerp = damp(k, afield.runtime.lifecycle.AnimationClock.frameDt)
/** `a damp k to b` for [Vector3dc]; see the [Vector2fc] form. */
infix fun Vector3dc.damp(k: Number): Vector3dLerp = damp(k, afield.runtime.lifecycle.AnimationClock.frameDt)
/** `a damp k to b` for [Vector4dc]; see the [Vector2fc] form. */
infix fun Vector4dc.damp(k: Number): Vector4dLerp = damp(k, afield.runtime.lifecycle.AnimationClock.frameDt)

// ---------------------------------------------------------------------------------------------
// Matrices: the operators Matrix4fc already has, on the other matrices, and `then` for each.

/**
 * Field's 4x4 matrix alias, backed by [Matrix4f]. `Mat4.translate(v)`, `Mat4.rotate(q)` and
 * `Mat4.scale(2)` build one-step matrices that compose in reading order with `then`.
 */
typealias Mat4 = Matrix4f

/** `m * v`: the 2D vector transformed by this matrix, as a new vector. */
operator fun Matrix2fc.times(v: Vector2fc): Vector2f = Vector2f(m00() * v.x() + m10() * v.y(), m01() * v.x() + m11() * v.y())
/** `m * v` in double precision. */
operator fun Matrix2dc.times(v: Vector2dc): Vector2d = Vector2d(m00() * v.x() + m10() * v.y(), m01() * v.x() + m11() * v.y())
/** `m * v`: the 3D vector transformed by this matrix, as a new vector. */
operator fun Matrix3fc.times(v: Vector3fc): Vector3f = transform(v, Vector3f())
/** `m * v` in double precision. */
operator fun Matrix3dc.times(v: Vector3dc): Vector3d = Vector3d(
    m00() * v.x() + m10() * v.y() + m20() * v.z(),
    m01() * v.x() + m11() * v.y() + m21() * v.z(),
    m02() * v.x() + m12() * v.y() + m22() * v.z(),
)
/** `m * v`: the 4D vector transformed by this matrix, as a new vector. */
operator fun Matrix4dc.times(v: Vector4dc): Vector4d = Vector4d(
    m00() * v.x() + m10() * v.y() + m20() * v.z() + m30() * v.w(),
    m01() * v.x() + m11() * v.y() + m21() * v.z() + m31() * v.w(),
    m02() * v.x() + m12() * v.y() + m22() * v.z() + m32() * v.w(),
    m03() * v.x() + m13() * v.y() + m23() * v.z() + m33() * v.w(),
)
/** `m * p`: the 3D point transformed by this matrix (w = 1, so translation applies), as a new vector, like `Matrix4fc * Vector3fc`. */
operator fun Matrix4dc.times(p: Vector3dc): Vector3d = Vector3d(
    m00() * p.x() + m10() * p.y() + m20() * p.z() + m30(),
    m01() * p.x() + m11() * p.y() + m21() * p.z() + m31(),
    m02() * p.x() + m12() * p.y() + m22() * p.z() + m32(),
)

/** `m * n`: the matrix product, as a new matrix; `(m * n) * v` is `m * (n * v)`. */
operator fun Matrix2fc.times(n: Matrix2fc): Matrix2f = Matrix2f(this).mul(n)
/** `m * n` in double precision. */
operator fun Matrix2dc.times(n: Matrix2dc): Matrix2d = Matrix2d(this).mul(n)
/** `m * n`: the matrix product, as a new matrix. */
operator fun Matrix3fc.times(n: Matrix3fc): Matrix3f = Matrix3f(this).mul(n)
/** `m * n` in double precision. */
operator fun Matrix3dc.times(n: Matrix3dc): Matrix3d = Matrix3d(this).mul(n)
/** `m * n`: the matrix product, as a new matrix. */
operator fun Matrix4dc.times(n: Matrix4dc): Matrix4d = Matrix4d(this).mul(n)

/** "This matrix, then [n]": `n * this`, applied in the order written. */
infix fun Matrix2fc.then(n: Matrix2fc): Matrix2f = n * this
/** "This matrix, then [n]" in double precision. */
infix fun Matrix2dc.then(n: Matrix2dc): Matrix2d = n * this
/** "This matrix, then [n]": `n * this`, applied in the order written. */
infix fun Matrix3fc.then(n: Matrix3fc): Matrix3f = n * this
/** "This matrix, then [n]" in double precision. */
infix fun Matrix3dc.then(n: Matrix3dc): Matrix3d = n * this
/** "This matrix, then [n]" in double precision. */
infix fun Matrix4dc.then(n: Matrix4dc): Matrix4d = n * this

// ---------------------------------------------------------------------------------------------
// Scalar shaping and integer grids.

/**
 * This number mapped linearly from the range [from] onto [to]: `t.remap(0.0..1.0, -1.0..1.0)`.
 * It is not clamped, so values outside [from] land outside [to]; a reversed range flips the map.
 */
fun Number.remap(from: ClosedRange<Double>, to: ClosedRange<Double>): Double =
    to.start + (toDouble() - from.start) / (from.endInclusive - from.start) * (to.endInclusive - to.start)

/** The fractional part `x - floor(x)`, in 0..1 also for negative numbers, GLSL's `fract`. */
fun Number.fract(): Double = toDouble().let { it - kotlin.math.floor(it) }

/** This number clamped to 0..1. */
fun Number.clamp01(): Double = toDouble().coerceIn(0.0, 1.0)

/** Smoothstep of this number, `t²(3 − 2t)` after clamping to 0..1: an ease in and out. */
fun Number.smoothstep(): Double = clamp01().let { it * it * (3 - 2 * it) }

/**
 * The grid of integer points, `(0 until 10) x (0 until 8)`, as a list of [Vec2]: x from this range
 * and y from [ys], with x varying fastest, so the points come row by row.
 */
infix fun IntRange.x(ys: IntRange): List<Vec2> = ys.flatMap { y -> map { x -> Vector2f(x.toFloat(), y.toFloat()) } }
