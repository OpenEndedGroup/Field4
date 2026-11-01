package afield.graphics.vulkan.buffers

import afield.ai.learning.*
import afield.ai.retrieval.*
import afield.animation.interpolation.*
import afield.animation.keyframes.*
import afield.animation.motion.*
import afield.app.commands.*
import afield.app.diagnostics.*
import afield.audio.analysis.*
import afield.audio.synth.*
import afield.data.collections.*
import afield.data.formats.*
import afield.data.graph.*
import afield.data.sorting.*
import afield.document.context.*
import afield.document.graph.*
import afield.document.properties.*
import afield.document.workspace.*
import afield.geometry.mesh.*
import afield.geometry.path.*
import afield.geometry.spatial.*
import afield.graphics.color.*
import afield.graphics.interaction.*
import afield.graphics.render.*
import afield.graphics.shader.*
import afield.graphics.text.*
import afield.graphics.vulkan.buffers.*
import afield.io.osc.*
import afield.io.process.*
import afield.lang.lambda.*
import afield.lang.python.*
import afield.lang.remotejs.*
import afield.lang.stacksl.*
import afield.math.autodiff.*
import afield.math.control.*
import afield.math.estimation.*
import afield.math.functions.*
import afield.math.linalg.*
import afield.math.optimization.*
import afield.math.statistics.*
import afield.math.units.*
import afield.media.image.*
import afield.media.video.*
import afield.runtime.execution.*
import afield.runtime.languages.*
import afield.runtime.lifecycle.*
import afield.simulation.agents.*
import afield.simulation.automata.*
import afield.simulation.particles.*
import afield.study.build.*
import afield.study.evidence.*
import afield.study.search.*
import afield.study.source.*
import afield.study.ui.*
import afield.ui.controls.*
import afield.ui.output.*
import afield.ui.visualization.*
import java.lang.foreign.MemorySegment
import java.nio.BufferOverflowException
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * The cursor a Vulkan buffer is written through: std430 padding, integers packed into float slots,
 * and a buffer that grows under a write that would run past its end. [DynamicBuffer] is all of it.
 * FLine geometry on its way to the GPU, the fabricated scene's shapes and a box's own
 * `VulkanBuffer.write { … }` block all put their floats here.
 */

/**
 * A write cursor over GPU memory that pads as std430 does and grows when it runs out. This is what
 * `VulkanBuffer.write { … }` and `append { … }` hand to their block, so a box streaming geometry or
 * particle state writes `put(…)` calls and never computes an offset.
 *
 * Alignment is std430's: a `vec4` and a `vec3` both start on a four-float boundary, a `vec3` writing
 * a fourth zero to fill its slot; a `vec2` starts on a two-float boundary; a `float` goes wherever
 * the cursor is. The bulk overloads pad before writing unless told not to, which is how an array of
 * structures stays aligned without the caller tracking where it is. `at` is that position.
 *
 * Integers share the float buffer: `putInt` stores the integer's bits in a float slot, so a `uint` or
 * `int` member of the matching shader struct reads back the same thirty-two bits.
 *
 * Running out is not an error. Any write past the end calls `onOverrun`, whose job is to produce a
 * larger buffer holding what the old one held; the write is then replayed into it, and `target` is
 * the new buffer from then on. `VulkanBuffer.dynamicResizer` is the implementation that reallocates
 * the GPU allocation and remaps it.
 */
class DynamicBuffer(var target: FloatBuffer, var onOverrun: (FloatBuffer, Int) -> FloatBuffer) {

    /** How far the cursor has been written, counted in floats rather than bytes. */
    val at: Int get() = target.position()

    /**
     * Copies a buffer's remaining contents in bulk, after padding to a four-float boundary unless
     * [padding] is false. A copy that would run past the end grows the target and is replayed, so
     * it is never partial. The `IntBuffer` overload copies the integers' bits into the same float
     * slots rather than converting them.
     */
    fun put(f: FloatBuffer, padding: Boolean = true): DynamicBuffer {
        if (padding) pad4(0f)
        val a = target.position()
        val b = f.position()
        try {
            target.put(f)
        } catch (_: BufferOverflowException) {
            target = onOverrun(target, f.capacity())
            target.position(a)
            f.position(b)
            put(f, false)
        }
        return this
    }

    /** Copies a float array from [offset] onwards, padding to a four-float boundary first unless [padding] is false. */
    fun put(f: FloatArray, padding: Boolean = true, offset : Int = 0): DynamicBuffer {
        if (padding) pad4(0f)
        val a = target.position()
        try {
            target.put(f, offset, f.size - offset)
        } catch (_: BufferOverflowException) {
            target = onOverrun(target, f.size)
            target.position(a)
            put(f, padding, offset)
        }
        return this
    }

    fun put(f: IntBuffer, padding: Boolean = true): DynamicBuffer {
        if (padding) pad4(0f)
        val a = target.position()
        val b = f.position()
        if (target.remaining()<f.remaining())
        {
            target = onOverrun(target, f.capacity())
            target.position(a)
            f.position(b)
            put(f, false)
        }
        else {
            putIntBitsIntoFloatBuffer(f, target)
        }
        return this
    }

    /** Writes each double narrowed to a float, one at a time, with no padding anywhere. */
    fun put(d: DoubleArray): DynamicBuffer {
        d.forEach { put(it.f) }
        return this
    }

    /**
     * Writes each integer's bits into its own float slot, one after another, with no padding before
     * or between them. The shader side reads them as a run of `int` or `uint` members.
     */
    fun putInt(d: List<Int>): DynamicBuffer {
        d.forEach { putInt(it) }
        return this
    }

    /**
     * Returns a callback that appends a line record as `x, y, z, breakFlag`.
     *
     * `breakBefore` is encoded as `1f` when a polyline break should precede
     * `v`, otherwise `0f`.
     */
    fun lines(): (Boolean, Vec3) -> Unit {
        return { breakBefore, v ->
            put(v.x, v.y, v.z, if (breakBefore) 1f else 0f)
        }
    }

    /** Writes one integer's thirty-two bits into the next float slot, with no padding. The shader reads the slot as an `int` or `uint`. */
    fun putInt(n: Int): DynamicBuffer {
        try {
            target.put(Float.fromBits(n))
        } catch (_: BufferOverflowException) {
            target = onOverrun(target, 1)
            putInt(n)
        }
        return this
    }

    fun putScalar(n: Float): DynamicBuffer {
        try {
            target.put(n)
        } catch (_: BufferOverflowException) {
            target = onOverrun(target, 1)
            putScalar(n)
        }
        return this
    }

    /** Writes one float at the cursor, with no padding. The `Vec2` and `Vec4` overloads grouped here pad to their own alignment first. */
    fun put(n: Float): DynamicBuffer {
        return putScalar(n)
    }

    fun put(n: Float, m: Float): DynamicBuffer {
        pad2(0f)
        putScalar(n)
        putScalar(m)
        return this
    }

    fun put(n: Float, m: Float, p: Float, zero: Float = 0f): DynamicBuffer {
        putScalar(n)
        putScalar(m)
        putScalar(p)
        putScalar(zero)
        return this
    }

    /** Writes one value, narrowed to a float, wherever the cursor is. No alignment and no padding, which is what makes it the scalar case. */
    fun putScalar(n: Double): DynamicBuffer {
        putScalar(n.f)
        return this
    }

    /** Writes a `vec2`: pads to a two-float boundary, then the two values narrowed to floats. */
    fun put(n: Double, m: Double): DynamicBuffer {
        pad2(0f)
        putScalar(n.f)
        putScalar(m.f)
        return this
    }

    /**
     * Writes three floats after padding to a four-float boundary, leaving the fourth slot of the
     * `vec4` for whatever is written next. The `Float` overload grouped with it is not the same:
     * it does no padding, and it does write a fourth value, [zero].
     */
    fun put(n: Double, m: Double, p: Double, zero: Float = 0f): DynamicBuffer {
        pad4(zero)
        putScalar(n.f)
        putScalar(m.f)
        putScalar(p.f)
        return this
    }

    /** Writes a padded `vec3`: aligns to a four-float boundary, then x, y, z and [zero] in the fourth slot, as std430 expects. */
    fun put(n: Vec3, zero: Float = 0f): DynamicBuffer {
        pad4(zero)
        putScalar(n.x.f)
        putScalar(n.y.f)
        putScalar(n.z.f)
        putScalar(zero)
        return this
    }

    fun put(n: Vec4): DynamicBuffer {
        pad4(0f)
        if (target.position() + 4 <= target.capacity()) {
            target.add(n)
        } else {
            putScalar(n.x.f)
            putScalar(n.y.f)
            putScalar(n.z.f)
            putScalar(n.w.f)
        }
        return this
    }

    fun put(n: Vec2): DynamicBuffer {
        pad2(0f)
        putScalar(n.x.f)
        putScalar(n.y.f)
        return this
    }

    /**
     * Advances the cursor to the next four-float boundary by writing up to three copies of [zero],
     * and writes nothing when it is already there. This is the alignment a `vec4` or a `vec3` needs,
     * and the bulk `put` overloads call it for you unless passed `padding = false`.
     */
    fun pad4(zero: Float = 0f) {
        for (n in 0 until (4 - this.target.position() % 4) % 4) {
            this.putScalar(zero)
        }
    }

    /**
     * Advances the cursor to the next two-float boundary, writing one [zero] when it is at an odd
     * position. This is the alignment a `vec2` needs, and the `vec2` overloads of `put` call it.
     */
    fun pad2(zero: Float = 0f) {
        if (this.target.position() % 2 == 1)
            this.putScalar(zero)
    }

    /** todo, we need to realloc if it doesn't fit */
    fun putIntBitsIntoFloatBuffer(src: IntBuffer, dst: FloatBuffer) {
        require(src.isDirect) { "src must be a direct IntBuffer" }
        require(dst.isDirect) { "dst must be a direct FloatBuffer" }

        val count = src.remaining()
        require(dst.remaining() >= count) {
            "dst has only ${dst.remaining()} floats remaining, need $count"
        }

        // Work on duplicates so we don't disturb mark/limit of the originals while computing offsets.
        val s = src.duplicate()
        val d = dst.duplicate()

        // Wrap the buffers with MemorySegments and byte-copy the payload.
        val sSeg = MemorySegment.ofBuffer(s)
        val dSeg = MemorySegment.ofBuffer(d)

        val byteCount = count.toLong() * 4L
        val sOffset   = s.position().toLong() * 4L
        val dOffset   = d.position().toLong() * 4L

        // Raw byte copy: preserves the 32-bit patterns exactly.
        MemorySegment.copy(sSeg, sOffset, dSeg, dOffset, byteCount)

        // Put-like semantics: advance the original buffers' positions.
        src.position(src.position() + count)
        dst.position(dst.position() + count)
    }
}
