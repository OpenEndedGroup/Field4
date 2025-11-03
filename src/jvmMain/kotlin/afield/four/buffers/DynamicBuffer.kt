package afield.four.buffers

import afield.stdlib.*
import java.nio.BufferOverflowException
import java.nio.BufferUnderflowException
import java.nio.FloatBuffer

/* std430 rules  vec3 -> vec4 _aligned_, vec4 is _aligned_, vec2 is aligned to 2, floats are floats*/
class DynamicBuffer(var target: FloatBuffer, var onOverrun: (FloatBuffer, Int) -> FloatBuffer) {

    val at: Int get() = target.position()

    fun put(f: FloatBuffer): DynamicBuffer {
        pad4(0f)
        val a = target.position()
        val b = f.position()
        try {
            target.put(f)
        } catch (_: BufferUnderflowException) {
            target = onOverrun(target, f.capacity())
            target.position(a)
            f.position(b)
            put(f)
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

}
