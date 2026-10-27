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
 * The rest of the file is the arithmetic the generated types do not cover: scalar times vector,
 * vector times quaternion, vector times dual quaternion, the `x` infix cross product, `isNaN` for
 * the 2D and 3D vectors, and `%=` for [Quat] and [DualQuat]. `median(index)` reads a single
 * component's median across a collection of vectors and returns that one `Float`.
 *
 * These types are mutable and most operations write into the receiver, so copy first
 * (`Vec3(existing)`) when the original still matters. Swizzles and the per-vector `invoke`, `%=` and
 * `len` operators are absent here because `tools/linalg-generator` emits them for every float vector
 * into `Swizzles.kt` and `VectorOperators.kt`; `%=` for [Quat] and [DualQuat] is written out here
 * because no generator covers those two.
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

/**
 * Cross product as an infix operator: `a x b`, a vector perpendicular to both, right-handed.
 *
 * Returns a **new** vector and leaves both operands alone, unlike the `cross` method it wraps, which
 * writes into its receiver. Length is `|a||b|sin θ`, so normalize if you wanted just the direction, and
 * it collapses to zero for parallel inputs.
 */
infix fun Vector3f.x(b: Vector3f) = Vector3f(this).cross(b)


// `v * 2` for a Vec3 is NOT declared here. The generated `Vector3fc.times(scalar: Number)` in
// afield.math.linalg.CompatTypes covers it, with the same body, and a second copy in this package
// made `v * 2` an overload resolution ambiguity in every box: box code star-imports both
// afield.stdlib and afield.math.linalg, so neither candidate wins. Code inside this package saw
// the same-package declaration win and compiled, which is why it went unnoticed. Vec2 and Vec4 have
// only the generated declaration and always worked.

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

