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

/* std430 rules  vec3 -> vec4 _aligned_, vec4 is _aligned_, vec2 is aligned to 2, floats are floats*/
class DynamicBuffer(var target: FloatBuffer, var onOverrun: (FloatBuffer, Int) -> FloatBuffer) {

    val at: Int get() = target.position()

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

    fun put(d: DoubleArray): DynamicBuffer {
        d.forEach { put(it.f) }
        return this
    }

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

    fun putScalar(n: Double): DynamicBuffer {
        putScalar(n.f)
        return this
    }

    fun put(n: Double, m: Double): DynamicBuffer {
        pad2(0f)
        putScalar(n.f)
        putScalar(m.f)
        return this
    }

    fun put(n: Double, m: Double, p: Double, zero: Float = 0f): DynamicBuffer {
        pad4(zero)
        putScalar(n.f)
        putScalar(m.f)
        putScalar(p.f)
        return this
    }

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

    fun pad4(zero: Float = 0f) {
        for (n in 0 until (4 - this.target.position() % 4) % 4) {
            this.putScalar(zero)
        }
    }

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
