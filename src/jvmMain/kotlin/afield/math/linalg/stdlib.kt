@file:afield.runtime.completion.CompletionCategory("Workspace")
@file:Suppress("UNCHECKED_CAST")

package afield.math.linalg

import afield.data.collections.alphas
import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.times
import afield.geometry.mesh.rotated
import afield.geometry.mesh.rounded
import afield.geometry.mesh.scaled
import afield.graphics.render.rangeTo
import afield.graphics.shader.vertex
import afield.graphics.vulkan.buffers.add
import afield.math.autodiff.clamp
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.simulation.agents.step
import afield.simulation.automata.step
import afield.study.ui.numbers

import afield.animation.keyframes.h
import afield.data.collections.m
import afield.data.collections.r
import afield.data.collections.s
import afield.lang.stacksl.h
import afield.media.image.rotate
import afield.ui.output.h

import afield.math.linalg.Vector2f
import afield.math.linalg.Vector3f
import afield.math.linalg.Vector4f
import afield.math.linalg.Matrix4f
import afield.math.linalg.Matrix3f
import afield.math.linalg.Quaternionf
import afield.ai.agent.*
import afield.ai.conversation.*
import afield.ai.tools.*
import afield.animation.gesture.*
import afield.animation.interpolation.*
import afield.animation.keyframes.*
import afield.app.diagnostics.*
import afield.app.notifications.*
import afield.app.protocol.*
import afield.app.server.*
import afield.app.settings.*
import afield.audio.synth.*
import afield.data.table.*
import afield.document.context.*
import afield.document.graph.*
import afield.document.history.*
import afield.document.persistence.*
import afield.document.properties.*
import afield.document.workspace.*
import afield.graphics.color.*
import afield.graphics.paint.*
import afield.graphics.vulkan.*
import afield.math.functions.*
import afield.media.capture.*
import afield.media.stream.*
import afield.runtime.completion.*
import afield.runtime.execution.*
import afield.runtime.kotlin.*
import afield.runtime.lifecycle.*
import afield.tools.automation.*
import afield.tools.docs.*
import afield.ui.controls.*
import afield.ui.layout.*
import afield.ui.notation.*
import afield.ui.output.*
import afield.ui.output.templates.*
import afield.ui.slides.*
import afield.ui.visualization.*
import afield.app.server.Server.Companion.stop
import afield.graphics.vulkan.util.FramePresentationLog
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.floatToHalfBits
import kotlinx.coroutines.DelicateCoroutinesApi
import afield.math.linalg.*
import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.reflect.KProperty

/**
 * The general-purpose helpers that every Field box already has in scope.
 *
 * Box code is compiled with the standard imports in `intro` in its preamble and with `Box` as an
 * implicit receiver, so everything here — including the `Box.` extensions — can be called
 * unqualified from inside a box.
 *
 * The substantial part is the property-delegate machinery that `prop` and `@exported` generate
 * against: [Delegation] (stores on the receiver's own property bag), [Inheritance] (walks up
 * `parents` until some ancestor has the property, otherwise throws
 * [MissingInheritedPropertyException] with a message naming the property and the box), and
 * [InheritanceFromContext] (same walk, but prefers each box's live Kotlin variables over raw
 * properties).
 *
 * The rest is small conveniences: graph access (`named`, `+=` / `-=` for
 * connect/disconnect, `__m` markers); execution control (`begin`, `end`, [start], [animate],
 * `aside`, `cell`); frame-presentation logging ([frameLogStart], [frameLogStop],
 * [frameLogMark], [frameLogLine]); matrix and buffer building ([mat4], [mat3], `FloatBuffer.add`,
 * `IntBuffer.add`, `ByteBuffer.putf16`); and terse numeric/collection/string shorthands
 * (`Number.f` / `.i` / `.l` / `.d`, [fa], [ia], `Int.alphas`, `String.times`, `List.m` / `.s`,
 * `String.r`, and [highlightSyntax] behind `String.h` / `String.ss`).
 */

/**
 * Implemented by anything that can hand out a 4×4 transform, so callers can take a pose without
 * caring how it was stored.
 *
 * The one implementation in the tree is glTF's `TRS` (translate / rotate / scale kept separately),
 * whose [m] composes the three on every read — so treat the property as a computation, not a field, and
 * hold the result if you need it more than once in a frame.
 */
interface HasMatrix4f {
    /** This object's transform as a 4×4 matrix. May be recomputed on every read. */
    val m: Matrix4f
}

private fun Matrix4f.duplicate(): Matrix4f {
    return Matrix4f(this)
}

private fun Matrix3f.duplicate(): Matrix3f {
    return Matrix3f(this)
}

/**
 * Writes one element of the matrix by a single **row-major** index: `n / 4` is the row and `n % 4` the
 * column.
 *
 * Note that the generated `Matrix4f` now carries its own `operator set(index, value)` member which indexes
 * the *other* way (`column * 4 + row`), and in Kotlin a member wins over an extension — so `m[5] = 1f` in a
 * box reaches the member, and this extension only applies where the member does not. The two agree on the
 * diagonal and disagree everywhere else, so index a matrix by `m[column, row]` and avoid the question.
 */
operator fun Matrix4f.set(n: Int, v: Float) {
    this[n % 4, n / 4] = v
}

/**
 * Builds a 4×4 transform from any combination of a starting matrix, a translation, a rotation and a scale —
 * everything optional, so `mat4()` is the identity.
 *
 * Composed as `base · S · R`, so a point is **rotated first and scaled afterwards** — with a non-uniform
 * [s] that is visibly not the same as scaling in the object's own frame, so the argument order here is not
 * the order the transforms apply in. [base] is **copied**, never modified. The translation is *set*, not
 * composed: whatever [base] had in its translation column is replaced by [t], and the rotation does not
 * turn [t]. So this builds a pose ("here, facing this way, this big") rather than concatenating transforms —
 * for that, multiply matrices yourself.
 */
fun mat4(base: Matrix4f? = null, t: Vector3f? = null, r: Quaternionf? = null, s: Vector3f? = null): Matrix4f {
    var m = base?.duplicate() ?: Matrix4f()
    if (s != null) m.scale(s)
    if (r != null) m.rotate(r)
    if (t != null) m.setTranslation(t)
    return m
}

/**
 * Builds a 3×3 matrix from an optional starting matrix, rotation and scale — the linear part of [mat4], for
 * normals and 2D transforms.
 *
 * Composed as `base · S · R` — like [mat4], so a point is rotated first and scaled afterwards — with
 * [base] copied rather than modified. The `t` parameter is accepted and **ignored**: a 3×3 matrix has
 * nowhere to put a translation. Use [mat4] if you need one.
 */
fun mat3(base: Matrix3f? = null, t: Vector3f? = null, r: Quaternionf? = null, s: Vector3f? = null): Matrix3f {
    var m = base?.duplicate() ?: Matrix3f()
    if (s != null) m.scale(s)
    if (r != null) m.rotate(r)
    return m
}

// buffer help

/** A `FloatArray` from any numbers, converted for you — `fa(1, 2.5, 3f)`. The terse array literal. */
fun fa(vararg f: Number): FloatArray = f.map { it.toFloat() }.toFloatArray()

/** An `IntArray` from any numbers, **truncated** toward zero rather than rounded — `ia(1, 2.9)` is `[1, 2]`. */
fun ia(vararg f: Number): IntArray = f.map { it.toInt() }.toIntArray()

/**
 * This number as a `Float` — `3.d.f`, `x.f` — the shorthand that keeps Kotlin's numeric conversions out of the
 * way in graphics code.
 *
 * Narrowing from `Double` loses precision, as always.
 */
val Number.f
    get() = this.toFloat()

/** This number as an `Int`, **truncated** toward zero: `1.9.i` is 1 and `(-1.9).i` is -1. */
val Number.i
    get() = this.toInt()

/** This `Long` as an `Int`, wrapping silently if it does not fit — the low 32 bits, not a clamp. */
val Long.i
    get() = this.toInt()

/** This number as a `Long`, truncated toward zero if it was fractional. */
val Number.l
    get() = this.toLong()

/** This number as a `Double`. Widening, so nothing is lost — except that a `Float`'s error comes with it. */
val Number.d
    get() = this.toDouble()


/**
 * Appends [value] as a 16-bit half float, **little-endian**, two bytes, and returns the buffer for chaining.
 *
 * The packing step for fp16 vertex attributes and push constants. Half floats top out around ±65504 and carry
 * roughly three decimal digits, so a value past that range becomes an infinity and a very small one becomes zero —
 * fine for colours and normals, not for large world coordinates. Byte order is written explicitly here and does
 * **not** follow the buffer's own `order()`.
 */
fun ByteBuffer.putf16(value: Float): ByteBuffer {
    val h = floatToHalfBits(value).toInt() and 0xFFFF
    put((h and 0xFF).toByte())
    put(((h ushr 8) and 0xFF).toByte())
    return this
}


/** A pair of 16-bit half floats, for packed vertex attributes and uniforms. Half the bytes of a [Vec2], a
 * fraction of the precision — see `ByteBuffer.putf16` for the range. */
typealias Vec2h = afield.graphics.vulkan.util.Vec2h

/** Three 16-bit half floats — the packed transit form of a [Vec3] for GPU buffers. */
typealias Vec3h = afield.graphics.vulkan.util.Vec3h

/** Four 16-bit half floats — the packed transit form of a [Vec4], and enough precision for a colour. */
typealias Vec4h = afield.graphics.vulkan.util.Vec4h
// Vec8h dropped: the 8-lane fp16 vector is the real generated afield.math.linalg.Vector8h.


