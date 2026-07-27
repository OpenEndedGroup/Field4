package afield.stdlib

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The SIMD separable-convolution path against the scalar definition it replaced.
 *
 * [convolveSeparable] splits into a vectorized interior and a scalar edge, and resolves the
 * vertical border once per row rather than once per sample. That is three chances to get an index
 * wrong in a way the existing filter tests would not catch — they use one border, one channel count
 * and even dimensions. So this sweeps the combinations that actually exercise the seams:
 *
 * - **every [Border]**, because the vertical pass resolves each one by a different route;
 * - **1, 3 and 4 channels**, because the horizontal tap offset is `(t - r) * channels`;
 * - **odd widths and heights**, so the span length is not a whole number of SIMD lanes and the
 *   scalar tail runs;
 * - **images narrower or shorter than the kernel**, where the interior span is empty and every
 *   column must fall back to the accessor;
 * - **asymmetric kernels** (`kx != ky`), which is what `sobel()` actually passes.
 *
 * The bound is `2e-6f` absolute, not exact equality: the SIMD path fuses its multiply-add, so it
 * rounds once per tap where the scalar path rounds twice. It is the more accurate of the two.
 */
class ImagingSimdTest {

    private fun textured(w: Int, h: Int, ch: Int): Img {
        val data = FloatArray(w * h * ch)
        var i = 0
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until ch) {
            // Deterministic, non-separable, and different per channel so a channel mix-up shows.
            data[i++] = (((x * 37 + y * 61 + c * 97) % 101) / 101f) + 0.001f * c
        }
        return Img(w, h, ch, data)
    }

    private fun assertMatchesReference(
        img: Img,
        kx: Kernel1D,
        ky: Kernel1D,
        border: Border,
        label: String
    ) {
        val fast = img.convolveSeparable(kx, ky, border)
        val slow = img.convolveSeparableReference(kx, ky, border)
        assertTrue(fast.sameShapeAs(slow), "$label: shape ${fast.width}x${fast.height}x${fast.channels}")
        var worst = 0f
        var worstAt = -1
        for (i in fast.data.indices) {
            val d = abs(fast.data[i] - slow.data[i])
            if (d > worst) { worst = d; worstAt = i }
        }
        assertTrue(worst < 2e-6f, "$label: SIMD and scalar differ by $worst at flat index $worstAt")
    }

    @Test
    fun simdSeparableMatchesTheScalarReferenceAcrossBordersChannelsAndSizes() {
        val gauss = gaussianKernel1D(1.3)
        var cases = 0
        for (border in Border.entries) {
            for (ch in listOf(1, 3, 4)) {
                // Even, odd, prime and lane-hostile dimensions.
                for ((w, h) in listOf(16 to 16, 17 to 13, 31 to 5, 8 to 23, 33 to 32)) {
                    val img = textured(w, h, ch)
                    assertMatchesReference(img, gauss, gauss, border, "gauss ${w}x$h x$ch $border")
                    assertMatchesReference(img, SOBEL_DERIV, SOBEL_SMOOTH, border, "sobel-x ${w}x$h x$ch $border")
                    assertMatchesReference(img, SOBEL_SMOOTH, SOBEL_DERIV, border, "sobel-y ${w}x$h x$ch $border")
                    cases += 3
                }
            }
        }
        assertTrue(cases == 180, "expected 180 comparisons, ran $cases")
    }

    @Test
    fun simdSeparableIsCorrectWhenTheKernelIsWiderThanTheImage() {
        // The interior span is empty here, so every column takes the scalar edge path — the branch
        // that a purely "big image" benchmark would never execute.
        val wide = gaussianKernel1D(4.0)   // radius >= 12
        assertTrue(wide.radius >= 12, "expected a wide kernel, got radius ${wide.radius}")
        for (border in Border.entries) {
            for (ch in listOf(1, 3)) {
                for ((w, h) in listOf(3 to 3, 5 to 2, 1 to 1, 2 to 9)) {
                    assertMatchesReference(
                        textured(w, h, ch), wide, wide, border, "narrow ${w}x$h x$ch $border"
                    )
                }
            }
        }
    }

    @Test
    fun simdSeparableHandlesASingleTapKernelAsAnIdentityScale() {
        // radius 0: the horizontal interior covers the whole row and the offset arithmetic must
        // degenerate cleanly to zero.
        val single = Kernel1D(floatArrayOf(2f))
        val img = textured(9, 7, 3)
        for (border in Border.entries) {
            assertMatchesReference(img, single, single, border, "single-tap $border")
        }
        val out = img.convolveSeparable(single, single, Border.CLAMP)
        for (i in img.data.indices) {
            assertTrue(
                abs(out.data[i] - 4f * img.data[i]) < 1e-6f,
                "single tap of 2 twice should scale by 4 at $i"
            )
        }
    }

    @Test
    fun simdSeparableToleratesZeroWeightTapsIncludingTheFirst() {
        // The horizontal pass skips zero taps but must still *write* on tap 0, and SOBEL_DERIV has
        // a zero centre tap, so a leading-zero kernel is the case that catches a missing write.
        val leadingZero = Kernel1D(floatArrayOf(0f, 0.5f, 0.5f))
        val trailingZero = Kernel1D(floatArrayOf(0.5f, 0.5f, 0f))
        val allZero = Kernel1D(floatArrayOf(0f, 0f, 0f))
        val img = textured(12, 11, 3)
        for (border in Border.entries) {
            assertMatchesReference(img, leadingZero, trailingZero, border, "leading-zero $border")
            assertMatchesReference(img, trailingZero, leadingZero, border, "trailing-zero $border")
            assertMatchesReference(img, allZero, allZero, border, "all-zero $border")
        }
        val zeroed = img.convolveSeparable(allZero, allZero, Border.CLAMP)
        assertTrue(zeroed.data.all { it == 0f }, "an all-zero kernel must produce an all-zero image")
    }

    @Test
    fun simdDense2DMatchesTheScalarReferenceAcrossBordersChannelsAndSizes() {
        // convolve2D folds (ky, kx) into flat offsets and drops zero taps, so the things that can
        // break are: the offset arithmetic, the interior rectangle, and the zero-tap dropping.
        val g = gaussianKernel1D(1.1)
        val gauss5 = g.outer(g)
        val kernels = listOf(
            "gauss" to gauss5,
            "laplacian4" to LAPLACIAN_4,   // 5 of 9 taps are zero
            "laplacian8" to LAPLACIAN_8,   // none are zero
            // Deliberately non-square and asymmetric: catches a transposed offset.
            "asym3x5" to Kernel2D.of(
                floatArrayOf(0f, 1f, 0f, -2f, 0.5f),
                floatArrayOf(1f, -4f, 0f, 0f, 0.25f),
                floatArrayOf(0f, 0f, 3f, 1f, 0f)
            )
        )
        for (border in Border.entries) {
            for (ch in listOf(1, 3, 4)) {
                for ((w, h) in listOf(16 to 16, 17 to 13, 31 to 5, 8 to 23)) {
                    val img = textured(w, h, ch)
                    for ((name, k) in kernels) {
                        val fast = img.convolve2D(k, border)
                        val slow = img.convolve2DReference(k, border)
                        var worst = 0f
                        for (i in fast.data.indices) worst = maxOf(worst, abs(fast.data[i] - slow.data[i]))
                        assertTrue(
                            worst < 2e-6f,
                            "$name ${w}x$h x$ch $border: dense SIMD and scalar differ by $worst"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun simdDense2DIsCorrectWhenTheKernelIsLargerThanTheImage() {
        // Interior rectangle empty in one or both axes — every pixel is border.
        val g = gaussianKernel1D(3.5)
        val big = g.outer(g)
        assertTrue(big.width >= 15, "expected a wide kernel, got ${big.width}")
        for (border in Border.entries) {
            for ((w, h) in listOf(3 to 3, 1 to 1, 40 to 2, 2 to 40)) {
                val img = textured(w, h, 1)
                val fast = img.convolve2D(big, border)
                val slow = img.convolve2DReference(big, border)
                var worst = 0f
                for (i in fast.data.indices) worst = maxOf(worst, abs(fast.data[i] - slow.data[i]))
                assertTrue(worst < 2e-6f, "big-kernel ${w}x$h $border differs by $worst")
            }
        }
    }

    @Test
    fun theHighLevelFiltersStillAgreeWithTheirScalarDefinitions() {
        // Guards the callers, not just the primitive: gaussianBlur/sobel/scharr all route through
        // convolveSeparable with different kernel pairings.
        val img = textured(29, 19, 3)
        for (border in Border.entries) {
            val blurFast = img.gaussianBlur(1.7, border = border)
            val blurSlow = img.convolveSeparableReference(gaussianKernel1D(1.7), gaussianKernel1D(1.7), border)
            var worst = 0f
            for (i in blurFast.data.indices) worst = maxOf(worst, abs(blurFast.data[i] - blurSlow.data[i]))
            assertTrue(worst < 2e-6f, "gaussianBlur $border differs by $worst")

            val g = img.luma()
            val sobel = g.sobel(border)
            val dxSlow = g.convolveSeparableReference(SOBEL_DERIV, SOBEL_SMOOTH, border)
            val dySlow = g.convolveSeparableReference(SOBEL_SMOOTH, SOBEL_DERIV, border)
            var worstDx = 0f
            var worstDy = 0f
            for (i in dxSlow.data.indices) {
                worstDx = maxOf(worstDx, abs(sobel.dx.data[i] - dxSlow.data[i]))
                worstDy = maxOf(worstDy, abs(sobel.dy.data[i] - dySlow.data[i]))
            }
            assertTrue(worstDx < 2e-6f, "sobel dx $border differs by $worstDx")
            assertTrue(worstDy < 2e-6f, "sobel dy $border differs by $worstDy")
        }
    }

    @Test
    fun fusedPyrDownMatchesBlurThenSubsample() {
        // pyrDown fuses the 2x decimation into both passes and swaps their order, which is three
        // separate chances to be off by one: the polyphase even/odd split, the interior column bounds,
        // and the clamped edge columns. pyrDownReference is the naive "blur everything, keep a
        // quarter" definition, so this is the differential test that the fusion changed nothing.
        //
        // Odd widths and heights matter most here: an odd width makes the even and odd phases
        // different lengths, which is exactly where a polyphase split goes wrong.
        for (ch in listOf(1, 3, 4)) {
            for ((w, h) in listOf(
                64 to 64, 63 to 61, 17 to 9, 8 to 33, 2 to 2, 1 to 1, 5 to 1, 1 to 7, 31 to 30
            )) {
                for (sigma in listOf(0.6, 1.0, 2.3)) {
                    val img = textured(w, h, ch)
                    val fused = img.pyrDown(sigma)
                    val naive = img.pyrDownReference(sigma)
                    assertTrue(
                        fused.sameShapeAs(naive),
                        "${w}x$h x$ch sigma=$sigma: shape ${fused.width}x${fused.height}x${fused.channels} " +
                            "vs ${naive.width}x${naive.height}x${naive.channels}"
                    )
                    var worst = 0f
                    var worstAt = -1
                    for (i in fused.data.indices) {
                        val d = abs(fused.data[i] - naive.data[i])
                        if (d > worst) { worst = d; worstAt = i }
                    }
                    // Looser than the 2e-6f used elsewhere: the fused path sums the two passes in the
                    // opposite order, so this is genuinely a different (equally valid) rounding, not
                    // the same arithmetic done in fewer instructions.
                    assertTrue(
                        worst < 1e-5f,
                        "pyrDown ${w}x$h x$ch sigma=$sigma: fused and naive differ by $worst at $worstAt"
                    )
                }
            }
        }
    }
}
