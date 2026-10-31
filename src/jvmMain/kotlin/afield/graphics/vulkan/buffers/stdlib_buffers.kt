@file:afield.runtime.completion.CompletionCategory("Workspace")
@file:Suppress("UNCHECKED_CAST")

package afield.graphics.vulkan.buffers

import afield.data.collections.alphas
import afield.data.collections.invoke
import afield.data.collections.m
import afield.data.collections.r
import afield.data.collections.s
import afield.data.collections.times
import afield.graphics.interaction.groups
import afield.graphics.render.rangeTo
import afield.graphics.shader.vertex
import afield.lang.stacksl.h
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times

import afield.data.collections.get
import afield.math.linalg.f
import afield.math.linalg.x
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
 * `add` for `FloatBuffer` and `IntBuffer`: append components at the current position and return
 * the buffer, so writes chain.
 *
 * The vertex-writing idiom, `buf.add(x, y).add(x2, y2)`. There are fixed-arity overloads for two
 * and three floats and for a [Vec2], [Vec3] or [Vec4], and a vararg one that converts any
 * `Number`, which boxes each argument and is therefore the wrong one for a per-frame loop over
 * thousands of vertices. `IntBuffer.add` is the index-buffer counterpart, truncating rather than
 * rounding; it is called `add` because `IntBuffer.put(index, data)` already means writing at an
 * absolute index.
 *
 * One of the `stdlib` files, which the `intro` preamble star-imports into every box, so everything
 * here can be called unqualified, with `Box` as the implicit receiver.
 */

/**
 * Appends two floats at the buffer's current position, advances it by two, and returns the buffer so calls
 * chain.
 *
 * The vertex-writing idiom: `buf.add(x, y).add(x2, y2)`. Running past the limit throws with a message that
 * reports the buffer's **whole limit in two-float groups** — "limit 512x2" means the buffer holds 512 pairs
 * in total, not that 512 more calls were available (by then, none were).
 */
fun FloatBuffer.add(x: Float, y: Float): FloatBuffer {
    try {
        this.put(x).put(y)
        return this
    } catch (f: BufferOverflowException) {
        throw Exception("overflow - tried to put two much data into a buffer of limit ${limit() / 2}x2").initCause(f)
    }
}

/**
 * Appends a [Vec2]'s two components at the current position, advances by two, and returns the buffer.
 *
 * Unlike the scalar overloads this writes through the vector's own absolute-position `get`, so overrunning the
 * limit surfaces as an `IndexOutOfBoundsException` rather than the friendly overflow message.
 */
fun FloatBuffer.add(a: Vector2f): FloatBuffer {
    try {
        a.get(this)
        this.position(this.position() + 2)
        return this
    } catch (f: BufferOverflowException) {
        throw Exception("overflow - tried to put two much data into a buffer of limit ${limit() / 2}x2").initCause(f)
    }
}

/** Appends a [Vec3]'s three components at the current position and advances by three. Chainable. */
fun FloatBuffer.add(a: Vector3f): FloatBuffer {
    try {
        a.get(this)
        this.position(this.position() + 3)
        return this
    } catch (f: BufferOverflowException) {
        throw Exception("overflow - tried to put two much data into a buffer of limit ${limit() / 3}x3").initCause(f)
    }
}

/** Appends a [Vec4]'s four components at the current position and advances by four. Chainable. */
fun FloatBuffer.add(a: Vector4f): FloatBuffer {
    try {
        a.get(this)
        this.position(this.position() + 4)
        return this
    } catch (f: BufferOverflowException) {
        throw Exception("overflow - tried to put two much data into a buffer of limit ${limit() / 4}x4").initCause(f)
    }
}

/**
 * Appends three float components and returns this buffer for chaining.
 *
 * Throws if there is not enough remaining space; the overflow message reports
 * the buffer limit in three-float groups.
 */
fun FloatBuffer.add(x: Float, y: Float, z: Float): FloatBuffer {
    try {

        this.put(x).put(y).put(z)
        return this
    } catch (f: BufferOverflowException) {
        throw Exception("overflow - tried to put two much data into a buffer of limit ${limit() / 3}x3").initCause(f)
    }
}

/**
 * Appends any number of values, each converted to `Float`, and returns the buffer.
 *
 * The convenient one — `buf.add(1, 2.5, 3f)` — at the cost of boxing every argument, so prefer the fixed-arity
 * overloads in a per-frame loop over thousands of vertices.
 */
fun FloatBuffer.add(vararg x: Number): FloatBuffer {
    try {
        x.forEach { this.put(it.toFloat()) }
        return this
    } catch (f: BufferOverflowException) {
        throw Exception("overflow - tried to put two much data into a buffer of limit ${limit()}").initCause(f)
    }
}

/**
 * Appends any number of values, each converted to `Int` (truncating, not rounding), and returns the buffer for
 * chaining — the index-buffer counterpart of `FloatBuffer.add`.
 *
 * Named `add` rather than `put`, because `IntBuffer.put(index, data)` already means "write at an absolute
 * index", and reusing it here would have shadowed that.
 */
fun IntBuffer.add(vararg x: Number): IntBuffer {
    try {
        x.forEach { this.put(it.toInt()) }
        return this
    } catch (f: BufferOverflowException) {
        throw Exception("overflow - tried to put two much data into a buffer of limit ${limit()}").initCause(f)
    }
}

