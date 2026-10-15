@file:afield.runtime.completion.CompletionCategory("Workspace")
@file:Suppress("UNCHECKED_CAST")

package afield.data.collections

import afield.geometry.mesh.repeated
import afield.graphics.render.rangeTo
import afield.graphics.vulkan.buffers.add
import afield.lang.stacksl.h
import afield.math.autodiff.compareTo
import afield.math.autodiff.cos
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.sin
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus

import afield.math.autodiff.ln
import afield.math.linalg.i
import afield.simulation.automata.slice
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
 * JavaScript-ish truthiness for a value out of a property bag, where you have an `Any?` and want a
 * yes/no.
 *
 * True for `true`, for a non-zero `Int`, and for a non-empty `String`. Everything else — null, `false`,
 * 0, and **every other type**, including a non-empty list or a `Long` of 1 — is false. That last part is
 * the trap: this is not a general-purpose "is set" test.
 */
fun Any?.isTrue(): Boolean {
    if (this == null) return false
    return when (this) {
        is Boolean -> this
        is Int -> if (this != 0) return true else false
        is String -> this.length > 0
        else -> false
    }
}

/**
 * `(0..9) { i -> … }` — calls the block once per value in the range and collects the results.
 *
 * `map` written as a call, which reads better when the whole point of the loop is to build geometry:
 * `(0..9) { i -> vec(i, 0) }`. Inclusive of both ends, like any Kotlin range.
 */
inline operator fun <T> IntRange.invoke(to: (Int) -> T): List<T> = this.map { to(it) }

/**
 * `10 { i -> … }` — calls the block for `i` in `0 until 10` and collects the results.
 *
 * The terse "do this n times and keep the answers" form. Note the range is **exclusive** of the number
 * itself, so `10 { it }` is `0..9`.
 */
inline operator fun <T> Int.invoke(to: (Int) -> T): List<T> = (0 until this).map { to(it) }

/**
 * `10 alphas { a -> … }` — calls the block with that many evenly spaced values from `0.0` to `1.0`
 * **inclusive**, and collects the results.
 *
 * The parameterise-a-curve loop: `20 alphas { a -> vec(cos(a * 2 * PI), sin(a * 2 * PI)) }` closes exactly,
 * because the last value really is 1.0. Beware `1 alphas { }`: it divides zero by zero and hands the block
 * a single **NaN** — which will not be caught by an `isInfinite` guard. The no-argument [alphas] does not
 * have that edge.
 */
inline infix fun <T> Int.alphas(to: (Double) -> T): List<T> = (0 until this).map { to(it / (this - 1.0)) }

/**
 * This many values evenly spaced from `0.0` to `1.0` **inclusive**, as a lazy sized sequence.
 *
 * `for (a in 20.alphas())` when you want the fractions themselves rather than a mapped list. Nothing is
 * allocated per element, and the `size` is known up front, so it can drive a buffer allocation.
 *
 * Two edges to know: `1.alphas()` yields a single `0.0` (rather than the NaN the infix `alphas` would
 * give), and a negative count yields nothing instead of throwing.
 */
fun Int.alphas(): SizedIterable<Double> {
    val n = this.coerceAtLeast(0)
    return sizedIterable(n) {
        var i = 0
        object : Iterator<Double> {
            override fun hasNext(): Boolean = i < n

            override fun next(): Double {
                if (!hasNext()) throw NoSuchElementException("No more alpha values")
                val value = if (n <= 1) 0.0 else i / (n - 1.0)
                i++
                return value
            }
        }
    }
}

/** `"ab" * 3` — the string repeated [ln] times. Zero or fewer gives the empty string. */
operator fun String.times(ln: Int): String {
    // Not just an early-out: `StringBuilder(negative)` throws NegativeArraySizeException before the loop
    // runs, so without this `"ab" * -1` crashed instead of returning the documented empty string.
    if (ln <= 0) return ""

    var s = StringBuilder(this.length * ln)
    val thisString = this // why has this@String stopped working?
    ln { s.append(this) }
    return s.toString()
}


/**
 * `list[2..5]` — a new list of the elements in that **inclusive** range, Python-slice syntax with Kotlin's range
 * semantics.
 *
 * A copy, not a view. **Throws** `IndexOutOfBoundsException` if the range runs off the end, unlike Python, which
 * would return a short list with no error.
 */
operator fun <T> List<T>.get(range: IntRange) = this.slice(range)


/** This list as a `MutableList` — a **copy**, so editing it leaves the original alone. */
val <T> List<T>.m: MutableList<T>
    get() = this.toMutableList()

/** This list as a `Set`, dropping duplicates and keeping first-seen order. A copy. */
val <T> List<T>.s: Set<T>
    get() = this.toSet()


/**
 * This string compiled as a [Regex] — `"\\d+".r`.
 *
 * Compiles on every read, so hoist it out of a loop. **Throws** `PatternSyntaxException` on a malformed pattern.
 */
val String.r: Regex
    get() = Regex(this)
