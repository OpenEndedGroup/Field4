@file:afield.CompletionCategory("Imaging")

package afield.stdlib

import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.VectorSpecies

/**
 * The SIMD span kernels the imaging filters are built out of.
 *
 * ### Why this file exists
 *
 * The scalar filter layer reads every sample through [Img.get], which per multiply-add costs a
 * range check, a channel clamp and a `when` over [Border]. That is correct and readable, and it is
 * also unvectorizable: C2 cannot hoist a branch whose outcome depends on the loop index out of the
 * inner loop, so a 3-tap Sobel pays three bounds-checked calls per sample.
 *
 * The fix is not to make the accessor faster — it is to stop calling it in the interior. Because
 * [Img] stores samples interleaved (`data[(y * width + x) * channels + c]`), *both* passes of a
 * separable convolution address contiguous memory:
 *
 * - **Vertical.** For a fixed output row, tap `t` reads the whole of source row `y + t - r`. The
 *   border decision is therefore *per row*, not per pixel: resolve the row index once and the
 *   entire row is a straight span. Every row takes the fast path.
 * - **Horizontal.** Adding `(t - r) * channels` to a flat index moves to the same channel of the
 *   pixel `t - r` columns over. So for a fixed tap the whole interior of a row is, again, a
 *   straight span — only the `r` columns at each end need the general accessor.
 *
 * Which reduces a separable convolution to one primitive, applied once per tap: `dst += w * src`
 * over a contiguous span. That is [axpy], and it is what this file provides.
 *
 * ### On FMA
 *
 * [axpy] uses a fused multiply-add: one rounding per tap instead of two. Results therefore differ
 * from the scalar reference in the last ulp — more accurate, not less, but not bit-identical.
 * The filter tests assert agreement to `1e-5f`, which is four orders of magnitude above this.
 *
 * @see convolveSeparable the main consumer
 * @see afield.four.synth.Buf the same idea for audio buffers
 */
internal object ImgSimd {

    @JvmField
    val SPECIES: VectorSpecies<Float> = FloatVector.SPECIES_PREFERRED

    @JvmField
    val LANES: Int = SPECIES.length()

    /**
     * `dst[dstOff + i] = w * src[srcOff + i]` for `i in 0 until len`.
     *
     * The *first* tap of an accumulation, so the caller never needs a separate zero-fill pass over
     * the destination.
     */
    fun mulSet(dst: FloatArray, dstOff: Int, src: FloatArray, srcOff: Int, len: Int, w: Float) {
        val vw = FloatVector.broadcast(SPECIES, w)
        val bound = SPECIES.loopBound(len)
        var i = 0
        while (i < bound) {
            FloatVector.fromArray(SPECIES, src, srcOff + i).mul(vw).intoArray(dst, dstOff + i)
            i += LANES
        }
        while (i < len) { dst[dstOff + i] = w * src[srcOff + i]; i++ }
    }

    /**
     * `dst[dstOff + i] += w * src[srcOff + i]` for `i in 0 until len`, fused.
     *
     * The inner loop of every separable convolution in the family.
     */
    fun axpy(dst: FloatArray, dstOff: Int, src: FloatArray, srcOff: Int, len: Int, w: Float) {
        val vw = FloatVector.broadcast(SPECIES, w)
        val bound = SPECIES.loopBound(len)
        var i = 0
        while (i < bound) {
            val d = dstOff + i
            FloatVector.fromArray(SPECIES, src, srcOff + i)
                .fma(vw, FloatVector.fromArray(SPECIES, dst, d))
                .intoArray(dst, d)
            i += LANES
        }
        while (i < len) { dst[dstOff + i] = Math.fma(w, src[srcOff + i], dst[dstOff + i]); i++ }
    }

    /**
     * `dst[dstOff + i] = sum over t of weights[t] * src[srcOff + i + offsets[t]]`, for
     * `i in 0 until len`.
     *
     * A multi-tap stencil evaluated with the accumulator kept in a vector register across all taps,
     * so a `k`-tap kernel costs `k` reads and **one** write per output chunk. Repeatedly calling
     * [axpy] instead would cost `k` reads *and* `k` read-modify-writes, which for a dense 2-D
     * kernel is the difference between memory-bound and not: a 9x9 kernel would do 81 passes over
     * the destination.
     *
     * [offsets] are flat element offsets into [src], already folded from `(dy, dx, channels)`, so
     * this does not need to know the image geometry. Callers must have established that every
     * `srcOff + i + offsets[t]` is in bounds — i.e. this is the *interior* path, and borders are
     * the caller's problem.
     */
    fun stencil(
        dst: FloatArray,
        dstOff: Int,
        src: FloatArray,
        srcOff: Int,
        len: Int,
        weights: FloatArray,
        offsets: IntArray
    ) {
        val n = weights.size
        val bound = SPECIES.loopBound(len)
        var i = 0
        while (i < bound) {
            var acc = FloatVector.zero(SPECIES)
            val s = srcOff + i
            for (t in 0 until n) {
                acc = FloatVector.fromArray(SPECIES, src, s + offsets[t])
                    .fma(FloatVector.broadcast(SPECIES, weights[t]), acc)
            }
            acc.intoArray(dst, dstOff + i)
            i += LANES
        }
        while (i < len) {
            var acc = 0f
            val s = srcOff + i
            for (t in 0 until n) acc = Math.fma(weights[t], src[s + offsets[t]], acc)
            dst[dstOff + i] = acc
            i++
        }
    }
}
