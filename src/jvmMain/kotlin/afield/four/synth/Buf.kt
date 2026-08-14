@file:afield.CompletionCategory("Synthesis")
@file:Suppress("DEPRECATION")

package afield.four.synth

import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.VectorOperators
import jdk.incubator.vector.VectorSpecies

/**
 * SIMD-accelerated buffer operations using the Java Vector API.
 *
 * Every public function processes data in bulk, falling back to a scalar
 * tail loop for the final elements that don't fill a full SIMD lane.
 * All operations are in-place on [FloatArray] buffers – zero allocation
 * on the hot path.
 */
object Buf {

    @JvmField val SPECIES: VectorSpecies<Float> = FloatVector.SPECIES_PREFERRED
    @JvmField val LANES: Int = SPECIES.length()

    // ── fill / clear / copy ──────────────────────────────────────────

    /** Fill [dst] with a constant [value]. */
    fun fill(dst: FloatArray, value: Float, off: Int = 0, len: Int = dst.size - off) {
        val end = off + len
        val vec = FloatVector.broadcast(SPECIES, value)
        var i = off
        val bound = SPECIES.loopBound(len) + off
        while (i < bound) { vec.intoArray(dst, i); i += LANES }
        while (i < end) { dst[i++] = value }
    }

    /** Zero the buffer. */
    fun clear(dst: FloatArray, off: Int = 0, len: Int = dst.size - off) = fill(dst, 0f, off, len)

    /** Copy [src] into [dst]. */
    fun copy(dst: FloatArray, src: FloatArray, off: Int = 0, len: Int = dst.size - off) {
        System.arraycopy(src, off, dst, off, len)
    }

    // ── arithmetic (buffer ⊕ buffer) ────────────────────────────────

    /** dst[i] += src[i] */
    fun add(dst: FloatArray, src: FloatArray, off: Int = 0, len: Int = dst.size - off) {
        val end = off + len; var i = off; val bound = SPECIES.loopBound(len) + off
        while (i < bound) {
            FloatVector.fromArray(SPECIES, dst, i)
                .add(FloatVector.fromArray(SPECIES, src, i))
                .intoArray(dst, i); i += LANES
        }
        while (i < end) { dst[i] += src[i]; i++ }
    }

    /** dst[i] -= src[i] */
    fun sub(dst: FloatArray, src: FloatArray, off: Int = 0, len: Int = dst.size - off) {
        val end = off + len; var i = off; val bound = SPECIES.loopBound(len) + off
        while (i < bound) {
            FloatVector.fromArray(SPECIES, dst, i)
                .sub(FloatVector.fromArray(SPECIES, src, i))
                .intoArray(dst, i); i += LANES
        }
        while (i < end) { dst[i] -= src[i]; i++ }
    }

    /** dst[i] *= src[i] */
    fun mul(dst: FloatArray, src: FloatArray, off: Int = 0, len: Int = dst.size - off) {
        val end = off + len; var i = off; val bound = SPECIES.loopBound(len) + off
        while (i < bound) {
            FloatVector.fromArray(SPECIES, dst, i)
                .mul(FloatVector.fromArray(SPECIES, src, i))
                .intoArray(dst, i); i += LANES
        }
        while (i < end) { dst[i] *= src[i]; i++ }
    }

    /** dst[i] /= src[i], guarding against near-zero divisors. */
    fun div(dst: FloatArray, src: FloatArray, off: Int = 0, len: Int = dst.size - off, epsilon: Float = 1e-15f) {
        val end = off + len
        val veps = FloatVector.broadcast(SPECIES, epsilon)
        val vzero = FloatVector.broadcast(SPECIES, 0f)
        var i = off
        val bound = SPECIES.loopBound(len) + off
        while (i < bound) {
            val numer = FloatVector.fromArray(SPECIES, dst, i)
            val denom = FloatVector.fromArray(SPECIES, src, i)
            val safe = denom.abs().compare(VectorOperators.GT, veps)
            numer.div(denom)
                .blend(vzero, safe.not())
                .intoArray(dst, i)
            i += LANES
        }
        while (i < end) {
            val d = src[i]
            dst[i] = if (kotlin.math.abs(d) > epsilon) dst[i] / d else 0f
            i++
        }
    }

    // ── arithmetic (buffer ⊕ scalar) ────────────────────────────────

    /** dst[i] += scalar */
    fun addScalar(dst: FloatArray, scalar: Float, off: Int = 0, len: Int = dst.size - off) {
        val end = off + len; val v = FloatVector.broadcast(SPECIES, scalar)
        var i = off; val bound = SPECIES.loopBound(len) + off
        while (i < bound) {
            FloatVector.fromArray(SPECIES, dst, i).add(v).intoArray(dst, i); i += LANES
        }
        while (i < end) { dst[i] += scalar; i++ }
    }

    /** dst[i] *= scalar */
    fun scale(dst: FloatArray, scalar: Float, off: Int = 0, len: Int = dst.size - off) {
        val end = off + len; val v = FloatVector.broadcast(SPECIES, scalar)
        var i = off; val bound = SPECIES.loopBound(len) + off
        while (i < bound) {
            FloatVector.fromArray(SPECIES, dst, i).mul(v).intoArray(dst, i); i += LANES
        }
        while (i < end) { dst[i] *= scalar; i++ }
    }

    // ── fused multiply-add ──────────────────────────────────────────

    /** dst[i] = a[i] * b[i] + c[i]  (FMA) */
    fun fma(
        dst: FloatArray, a: FloatArray, b: FloatArray, c: FloatArray,
        off: Int = 0, len: Int = dst.size - off
    ) {
        val end = off + len; var i = off; val bound = SPECIES.loopBound(len) + off
        while (i < bound) {
            FloatVector.fromArray(SPECIES, a, i)
                .fma(FloatVector.fromArray(SPECIES, b, i), FloatVector.fromArray(SPECIES, c, i))
                .intoArray(dst, i); i += LANES
        }
        while (i < end) { dst[i] = a[i] * b[i] + c[i]; i++ }
    }

    /** dst[i] = dst[i] * scalar + addend[i] */
    fun scaleAndAdd(
        dst: FloatArray, scalar: Float, addend: FloatArray,
        off: Int = 0, len: Int = dst.size - off
    ) {
        val end = off + len; val sv = FloatVector.broadcast(SPECIES, scalar)
        var i = off; val bound = SPECIES.loopBound(len) + off
        while (i < bound) {
            FloatVector.fromArray(SPECIES, dst, i)
                .fma(sv, FloatVector.fromArray(SPECIES, addend, i))
                .intoArray(dst, i); i += LANES
        }
        while (i < end) { dst[i] = dst[i] * scalar + addend[i]; i++ }
    }

    // ── clamp / abs ─────────────────────────────────────────────────

    /** dst[i] = clamp(dst[i], lo, hi) */
    fun clamp(dst: FloatArray, lo: Float, hi: Float, off: Int = 0, len: Int = dst.size - off) {
        val end = off + len
        val vlo = FloatVector.broadcast(SPECIES, lo)
        val vhi = FloatVector.broadcast(SPECIES, hi)
        var i = off; val bound = SPECIES.loopBound(len) + off
        while (i < bound) {
            FloatVector.fromArray(SPECIES, dst, i).max(vlo).min(vhi).intoArray(dst, i); i += LANES
        }
        while (i < end) { dst[i] = dst[i].coerceIn(lo, hi); i++ }
    }

    /** dst[i] = |dst[i]| */
    fun abs(dst: FloatArray, off: Int = 0, len: Int = dst.size - off) {
        val end = off + len; var i = off; val bound = SPECIES.loopBound(len) + off
        while (i < bound) {
            FloatVector.fromArray(SPECIES, dst, i).abs().intoArray(dst, i); i += LANES
        }
        while (i < end) { dst[i] = kotlin.math.abs(dst[i]); i++ }
    }

    // ── reductions ──────────────────────────────────────────────────

    /** Peak absolute value. */
    fun peak(src: FloatArray, off: Int = 0, len: Int = src.size - off): Float {
        val end = off + len; var mx = 0f
        var i = off; val bound = SPECIES.loopBound(len) + off
        var vmx = FloatVector.broadcast(SPECIES, 0f)
        while (i < bound) {
            vmx = vmx.max(FloatVector.fromArray(SPECIES, src, i).abs()); i += LANES
        }
        mx = vmx.reduceLanes(VectorOperators.MAX)
        while (i < end) { val a = kotlin.math.abs(src[i]); if (a > mx) mx = a; i++ }
        return mx
    }

    /** RMS (root-mean-square) level. */
    fun rms(src: FloatArray, off: Int = 0, len: Int = src.size - off): Float {
        val end = off + len; var sum = 0f
        var i = off; val bound = SPECIES.loopBound(len) + off
        var vsum = FloatVector.broadcast(SPECIES, 0f)
        while (i < bound) {
            val v = FloatVector.fromArray(SPECIES, src, i)
            vsum = v.fma(v, vsum); i += LANES
        }
        sum = vsum.reduceLanes(VectorOperators.ADD)
        while (i < end) { sum += src[i] * src[i]; i++ }
        return kotlin.math.sqrt(sum / len)
    }

    /** Sum all elements. */
    fun sum(src: FloatArray, off: Int = 0, len: Int = src.size - off): Float {
        val end = off + len; var s = 0f
        var i = off; val bound = SPECIES.loopBound(len) + off
        var vs = FloatVector.broadcast(SPECIES, 0f)
        while (i < bound) {
            vs = vs.add(FloatVector.fromArray(SPECIES, src, i)); i += LANES
        }
        s = vs.reduceLanes(VectorOperators.ADD)
        while (i < end) { s += src[i]; i++ }
        return s
    }

    // ── windowing ───────────────────────────────────────────────────

    /** Apply a Hann window in-place. */
    fun hann(dst: FloatArray, off: Int = 0, len: Int = dst.size - off) {
        val end = off + len
        val factor = 2.0 * Math.PI / (len - 1).toDouble()
        for (i in off until end) {
            dst[i] *= (0.5 - 0.5 * Math.cos(factor * (i - off))).toFloat()
        }
    }

    /** Generate a Hann window into [dst]. */
    fun hannWindow(dst: FloatArray, off: Int = 0, len: Int = dst.size - off) {
        val factor = 2.0 * Math.PI / (len - 1).toDouble()
        for (i in off until end(off, len)) {
            dst[i] = (0.5 - 0.5 * Math.cos(factor * (i - off))).toFloat()
        }
    }

    // ── mix helpers ─────────────────────────────────────────────────

    /** dst[i] = a[i] * (1-mix) + b[i] * mix   (cross-fade) */
    fun crossfade(
        dst: FloatArray, a: FloatArray, b: FloatArray, mix: Float,
        off: Int = 0, len: Int = dst.size - off
    ) {
        val end = off + len
        val vm = FloatVector.broadcast(SPECIES, mix)
        val v1m = FloatVector.broadcast(SPECIES, 1f - mix)
        var i = off; val bound = SPECIES.loopBound(len) + off
        while (i < bound) {
            val va = FloatVector.fromArray(SPECIES, a, i)
            val vb = FloatVector.fromArray(SPECIES, b, i)
            va.mul(v1m).add(vb.mul(vm)).intoArray(dst, i); i += LANES
        }
        val om = 1f - mix
        while (i < end) { dst[i] = a[i] * om + b[i] * mix; i++ }
    }

    private fun end(off: Int, len: Int) = off + len
}
