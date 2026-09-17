@file:afield.CompletionCategory("Imaging")

package afield.stdlib

import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.VectorOperators
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
 * ### The one primitive that matters: [stencil]
 *
 * Both observations reduce a convolution — separable *or* dense — to the same shape: a destination
 * span is a weighted sum of `k` source spans at fixed element offsets. That is [stencil], and it
 * keeps the running sum **in a vector register** across all taps, so `k` taps cost `k` reads and
 * **one** write.
 *
 * The alternative — accumulate in memory, one [axpy] per tap — costs `k` reads *and* `k`
 * read-modify-writes. For the sigma-2 Gaussian used in the benchmark (`radius 8`, 17 taps) that is
 * 17 write passes over the image where one would do, and it is why [stencil] rather than [axpy] is
 * the inner loop of everything here. [axpy] survives only for the handful of border rows where each
 * tap resolves to a *different*, border-dependent source row and there is no single offset to fold.
 *
 * ### Why the main loop keeps four accumulators
 *
 * [stencil] is a chain of `k` dependent FMAs into one accumulator. On Apple silicon an FMA has ~4
 * cycles of latency and can issue ~4 per cycle, so a single-accumulator 17-tap chain runs at
 * latency, not throughput — about 16x off peak. The main loop therefore keeps **four** independent
 * accumulators (four output chunks in flight), which converts the chain into something the machine
 * can pipeline, then falls back to one chunk at a time and finally to scalar for the tail. This is
 * the difference between "uses the Vector API" and "is fast".
 *
 * ### Decimation, without a gather
 *
 * A pass that throws half its output away should not compute it. Halving *rows* is free (just skip
 * them), but halving *columns* makes the reads stride-2, which on NEON means a gather. [splitByPhase]
 * avoids it: output `x` reads source pixel `2x + s`, whose parity is fixed by the parity of `s`. So
 * even-offset taps only ever read even-indexed pixels and odd-offset taps only odd ones. Split the
 * row into its even and odd pixels once — into one scratch buffer, laid out even-half then odd-half,
 * so a single [stencil] call can address both — and every tap is unit-stride again. This is the
 * polyphase decomposition, and it is what makes [Img.pyrDown] a half-resolution filter rather than a
 * full-resolution one with a discard at the end.
 *
 * ### On FMA
 *
 * These kernels use fused multiply-add: one rounding per tap instead of two. Results therefore
 * differ from the scalar reference in the last ulp — more accurate, not less, but not bit-identical.
 * `ImagingSimdTest` asserts agreement to `2e-6f`, which is four orders of magnitude above this.
 *
 * @see convolveSeparable the main consumer
 * @see afield.four.synth.Buf the same idea for audio buffers
 */

internal object ImgSimd {

    @JvmField
    val SPECIES: VectorSpecies<Float> = FloatVector.SPECIES_PREFERRED

    @JvmField
    val LANES: Int = SPECIES.length()

    /** How many floats the unrolled main loop of [stencil] consumes per iteration. */
    private val BLOCK: Int = 4 * LANES

    /**
     * `dst[dstOff + i] = w * src[srcOff + i]` for `i in 0 until len`.
     *
     * The *first* tap of an in-memory accumulation, so the caller never needs a separate zero-fill
     * pass over the destination. Only border rows need this; see [stencil] for the interior.
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
     * Accumulates in *memory*, so prefer [stencil] whenever the taps share one base pointer. This is
     * for the border rows of the vertical pass, where each tap resolves to an unrelated source row.
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
     * The inner loop of every convolution in the family — separable passes and dense 2-D alike. The
     * accumulator lives in vector registers across all taps, so a `k`-tap kernel costs `k` reads and
     * one write per output chunk, and four output chunks are kept in flight so the FMA chain
     * pipelines (see the class comment).
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
        val l = LANES
        var i = 0

        // Four accumulators: the point is instruction-level parallelism, not fewer instructions.
        while (i + BLOCK <= len) {
            var a0 = FloatVector.zero(SPECIES)
            var a1 = FloatVector.zero(SPECIES)
            var a2 = FloatVector.zero(SPECIES)
            var a3 = FloatVector.zero(SPECIES)
            val s = srcOff + i
            for (t in 0 until n) {
                val vw = FloatVector.broadcast(SPECIES, weights[t])
                val o = s + offsets[t]
                a0 = FloatVector.fromArray(SPECIES, src, o).fma(vw, a0)
                a1 = FloatVector.fromArray(SPECIES, src, o + l).fma(vw, a1)
                a2 = FloatVector.fromArray(SPECIES, src, o + 2 * l).fma(vw, a2)
                a3 = FloatVector.fromArray(SPECIES, src, o + 3 * l).fma(vw, a3)
            }
            val d = dstOff + i
            a0.intoArray(dst, d)
            a1.intoArray(dst, d + l)
            a2.intoArray(dst, d + 2 * l)
            a3.intoArray(dst, d + 3 * l)
            i += BLOCK
        }
        while (i + l <= len) {
            var acc = FloatVector.zero(SPECIES)
            val s = srcOff + i
            for (t in 0 until n) {
                acc = FloatVector.fromArray(SPECIES, src, s + offsets[t])
                    .fma(FloatVector.broadcast(SPECIES, weights[t]), acc)
            }
            acc.intoArray(dst, dstOff + i)
            i += l
        }
        while (i < len) {
            var acc = 0f
            val s = srcOff + i
            for (t in 0 until n) acc = Math.fma(weights[t], src[s + offsets[t]], acc)
            dst[dstOff + i] = acc
            i++
        }
    }

    /**
     * Split one row of `pixels` pixels x [channels] channels into its even- and odd-indexed pixels,
     * written into [dst] as the even half followed by the odd half.
     *
     * The return value is the element offset of the odd half, i.e. `((pixels + 1) / 2) * channels`,
     * which is what a caller folds into the tap offsets of the odd phase. [dst] must hold at least
     * `pixels * channels` floats.
     *
     * Why one buffer rather than two: a decimating [stencil] then needs a single base pointer, so
     * both phases of the kernel can be evaluated in one call with one register accumulator instead
     * of two passes over the destination.
     */
    fun splitByPhase(
        src: FloatArray,
        srcOff: Int,
        dst: FloatArray,
        pixels: Int,
        channels: Int
    ): Int {
        val evenCount = (pixels + 1) / 2
        val oddBase = evenCount * channels
        if (channels == 1) {
            // The common case (gradients, pyramids, masks) and the only one worth a tight loop.
            var p = 0
            var e = 0
            var o = oddBase
            while (p + 1 < pixels) {
                dst[e++] = src[srcOff + p]
                dst[o++] = src[srcOff + p + 1]
                p += 2
            }
            if (p < pixels) dst[e] = src[srcOff + p]
        } else {
            var p = 0
            var e = 0
            var o = oddBase
            while (p < pixels) {
                val base = srcOff + p * channels
                val d = if (p and 1 == 0) e.also { e += channels } else o.also { o += channels }
                for (c in 0 until channels) dst[d + c] = src[base + c]
                p++
            }
        }
        return oddBase
    }

    /**
     * `dst[dstOff + i] = min(dst[dstOff + i], src[...])` / `max(...)` in one pass — the
     * compare-exchange of a sorting network, applied to a whole span at a time.
     *
     * `lo` receives the smaller of the two spans and `hi` the larger, which is exactly a
     * [SortingComparator] with each SIMD lane carrying a different pixel. Used by [Img.median]: for a
     * fixed window every tap is a constant element offset, so a median is a fixed, branchless
     * sequence of these — no per-pixel sort, no gather, and no data-dependent control flow.
     */
    fun compareExchange(lo: FloatArray, hi: FloatArray, off: Int, len: Int) {
        val bound = SPECIES.loopBound(len)
        var i = 0
        while (i < bound) {
            val a = FloatVector.fromArray(SPECIES, lo, off + i)
            val b = FloatVector.fromArray(SPECIES, hi, off + i)
            a.min(b).intoArray(lo, off + i)
            a.max(b).intoArray(hi, off + i)
            i += LANES
        }
        while (i < len) {
            val a = lo[off + i]
            val b = hi[off + i]
            if (b < a) { lo[off + i] = b; hi[off + i] = a }
            i++
        }
    }

    /**
     * `acc[i] += plus[plusOff + i] - minus[minusOff + i]`, for `i in 0 until len`.
     *
     * The sliding-window update of a running-sum box blur, done **across** a whole row rather than
     * along one. A row-wise running sum is a serial dependency chain one element long per step and
     * cannot vectorize; running the *columns* in parallel — one lane per column, the accumulator
     * being a whole row of partial sums — makes the same O(1)-per-pixel algorithm a straight
     * elementwise vector update. See [Img.boxBlur].
     */
    fun addSub(
        acc: FloatArray,
        plus: FloatArray,
        plusOff: Int,
        minus: FloatArray,
        minusOff: Int,
        len: Int
    ) {
        val bound = SPECIES.loopBound(len)
        var i = 0
        while (i < bound) {
            FloatVector.fromArray(SPECIES, acc, i)
                .add(FloatVector.fromArray(SPECIES, plus, plusOff + i))
                .sub(FloatVector.fromArray(SPECIES, minus, minusOff + i))
                .intoArray(acc, i)
            i += LANES
        }
        while (i < len) { acc[i] += plus[plusOff + i] - minus[minusOff + i]; i++ }
    }

    /** `acc[i] += src[srcOff + i]` for `i in 0 until len` — priming a running sum. */
    fun addInto(acc: FloatArray, src: FloatArray, srcOff: Int, len: Int) {
        val bound = SPECIES.loopBound(len)
        var i = 0
        while (i < bound) {
            FloatVector.fromArray(SPECIES, acc, i)
                .add(FloatVector.fromArray(SPECIES, src, srcOff + i))
                .intoArray(acc, i)
            i += LANES
        }
        while (i < len) { acc[i] += src[srcOff + i]; i++ }
    }

    /**
     * `acc[i] -= src[srcOff + i]` for `i in 0 until len`.
     *
     * Only reachable under [Border.ZERO], where the row entering the sliding window is outside the
     * image and contributes nothing while the row leaving it still has to be subtracted.
     */
    fun subInto(acc: FloatArray, src: FloatArray, srcOff: Int, len: Int) {
        val bound = SPECIES.loopBound(len)
        var i = 0
        while (i < bound) {
            FloatVector.fromArray(SPECIES, acc, i)
                .sub(FloatVector.fromArray(SPECIES, src, srcOff + i))
                .intoArray(acc, i)
            i += LANES
        }
        while (i < len) { acc[i] -= src[srcOff + i]; i++ }
    }

    /** `dst[dstOff + i] = src[i] * w` for `i in 0 until len` — a running sum's normalising store. */
    fun scaleInto(dst: FloatArray, dstOff: Int, src: FloatArray, len: Int, w: Float) {
        val vw = FloatVector.broadcast(SPECIES, w)
        val bound = SPECIES.loopBound(len)
        var i = 0
        while (i < bound) {
            FloatVector.fromArray(SPECIES, src, i).mul(vw).intoArray(dst, dstOff + i)
            i += LANES
        }
        while (i < len) { dst[dstOff + i] = src[i] * w; i++ }
    }

}
