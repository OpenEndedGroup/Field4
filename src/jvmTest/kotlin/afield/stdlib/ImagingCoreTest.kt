package afield.stdlib

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Invariants of the [Img] value type and the filter layer (`imaging.kt`, `imaging_filters.kt`).
 *
 * These are properties that must hold *mathematically*, not "the output looked plausible":
 * a normalised Gaussian preserves the image mean; the separable Gaussian equals the naive dense
 * 2-D convolution of its outer product; Sobel on a synthetic step edge produces the analytically
 * correct magnitude and orientation; the Laplacian pyramid reconstructs its source; a colour
 * round-trip through HSV is the identity.
 */
class ImagingCoreTest {

    // ---- synthetic test images (shared with the other imaging tests via internal visibility) ----

    private fun ramp(w: Int = 32, h: Int = 32) = Img.generate(w, h) { x, _ -> x / (w - 1).toFloat() }

    /** A bright square on a dark ground — the reference shape for edges/corners/components. */
    private fun square(w: Int = 64, h: Int = 64, x0: Int = 16, y0: Int = 16, x1: Int = 47, y1: Int = 47) =
        Img.generate(w, h) { x, y -> if (x in x0..x1 && y in y0..y1) 1f else 0f }

    /** A vertical step edge: dark left of [at], bright from [at] rightwards. */
    private fun stepEdge(w: Int = 32, h: Int = 32, at: Int = 16) =
        Img.generate(w, h) { x, _ -> if (x >= at) 1f else 0f }

    // ==========================================================================================
    // Img basics
    // ==========================================================================================

    @Test
    fun shapeAndLayoutAreInterleavedRowMajor() {
        val img = Img(3, 2, 3, FloatArray(18) { it.toFloat() })
        assertEquals(6, img.pixelCount)
        assertEquals(0, img.index(0, 0, 0))
        assertEquals(2, img.index(0, 0, 2))
        assertEquals(3, img.index(1, 0, 0))
        assertEquals(9, img.index(0, 1, 0))
        assertEquals(9f, img.at(0, 1, 0))
        assertEquals(17f, img.at(2, 1, 2))
    }

    @Test
    fun constructorRejectsBadShapes() {
        assertFailsWith<IllegalArgumentException> { Img(0, 4, 1, FloatArray(0)) }
        assertFailsWith<IllegalArgumentException> { Img(4, 4, 2, FloatArray(32)) }
        assertFailsWith<IllegalArgumentException> { Img(4, 4, 1, FloatArray(15)) }
    }

    @Test
    fun borderModesResolveOutOfRangeReadsCorrectly() {
        val img = Img(4, 1, 1, floatArrayOf(0f, 1f, 2f, 3f))
        assertEquals(0f, img.get(-1, 0, 0, Border.ZERO))
        assertEquals(0f, img.get(-1, 0, 0, Border.CLAMP))
        assertEquals(3f, img.get(4, 0, 0, Border.CLAMP))
        // reflect-101: index -1 mirrors to 1, index 4 mirrors to 2
        assertEquals(1f, img.get(-1, 0, 0, Border.REFLECT))
        assertEquals(2f, img.get(4, 0, 0, Border.REFLECT))
        assertEquals(3f, img.get(-1, 0, 0, Border.WRAP))
        assertEquals(0f, img.get(4, 0, 0, Border.WRAP))
    }

    @Test
    fun bilinearSamplingIsExactAtPixelCentresAndLinearBetween() {
        val img = Img(2, 2, 1, floatArrayOf(0f, 1f, 2f, 3f))
        assertEquals(0f, img.sampleBilinear(0.0, 0.0), 1e-6f)
        assertEquals(1f, img.sampleBilinear(1.0, 0.0), 1e-6f)
        assertEquals(3f, img.sampleBilinear(1.0, 1.0), 1e-6f)
        assertEquals(0.5f, img.sampleBilinear(0.5, 0.0), 1e-6f)
        assertEquals(1.5f, img.sampleBilinear(0.5, 0.5), 1e-6f)
    }

    @Test
    fun bufferedImageRoundTripPreservesEightBitValues() {
        // Values chosen to land exactly on 8-bit codes so quantisation is lossless.
        val src = Img.generateRGB(8, 4) { x, y ->
            Triple(x * 32 / 255f, y * 64 / 255f, ((x + y) * 16) / 255f)
        }
        val back = src.toBufferedImage().toImg(3)
        assertEquals(src.width, back.width)
        assertEquals(src.height, back.height)
        for (i in src.data.indices) assertEquals(src.data[i], back.data[i], 1f / 255f, "sample $i")
    }

    @Test
    fun pngRoundTripThroughImageIoPreservesPixels() {
        val src = square(16, 16, 4, 4, 11, 11).toRGB()
        val bytes = src.toPngBytes()
        assertTrue(bytes.size > 8, "PNG encoder produced ${bytes.size} bytes")
        // PNG magic
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals('P'.code.toByte(), bytes[1])
        val back = imgFromBytes(bytes, 3)
        assertEquals(src.width, back.width)
        for (i in src.data.indices) assertEquals(src.data[i], back.data[i], 1f / 255f, "sample $i")
    }

    @Test
    fun channelSplitAndMergeRoundTrips() {
        val src = Img.generateRGB(9, 7) { x, y -> Triple(x / 9f, y / 7f, 0.25f) }
        val merged = mergeChannels(*src.split().toTypedArray())
        assertTrue(src.sameShapeAs(merged))
        for (i in src.data.indices) assertEquals(src.data[i], merged.data[i], 1e-7f)
    }

    @Test
    fun lumaUsesBt709Weights() {
        val red = Img(1, 1, 3, floatArrayOf(1f, 0f, 0f))
        val green = Img(1, 1, 3, floatArrayOf(0f, 1f, 0f))
        val blue = Img(1, 1, 3, floatArrayOf(0f, 0f, 1f))
        assertEquals(0.2126f, red.luma().data[0], 1e-6f)
        assertEquals(0.7152f, green.luma().data[0], 1e-6f)
        assertEquals(0.0722f, blue.luma().data[0], 1e-6f)
        // A neutral grey stays put.
        val grey = Img(1, 1, 3, floatArrayOf(0.4f, 0.4f, 0.4f))
        assertEquals(0.4f, grey.luma().data[0], 1e-6f)
    }

    @Test
    fun srgbAndLinearAreMutualInverses() {
        for (i in 0..100) {
            val v = i / 100f
            assertEquals(v, linearToSrgb(srgbToLinear(v)), 1e-5f, "srgb->linear->srgb at $v")
            assertEquals(v, srgbToLinear(linearToSrgb(v)), 1e-5f, "linear->srgb->linear at $v")
        }
    }

    @Test
    fun hsvRoundTripsRgb() {
        val src = Img.generateRGB(16, 16) { x, y ->
            Triple(x / 15f, y / 15f, ((x * y) % 16) / 15f)
        }
        val back = src.rgbToHsv().hsvToRgb()
        for (i in src.data.indices) assertEquals(src.data[i], back.data[i], 1e-5f, "sample $i")
    }

    @Test
    fun areaResizeAveragesAndPreservesTheMean() {
        // A 32x32 checkerboard downsampled 2x by area averaging is uniformly 0.5.
        val checker = Img.generate(32, 32) { x, y -> if ((x + y) % 2 == 0) 1f else 0f }
        val half = checker.resize(16, 16)
        assertEquals(16, half.width)
        for (v in half.data) assertEquals(0.5f, v, 1e-5f)
        assertEquals(checker.mean(), half.mean(), 1e-5f)
    }

    @Test
    fun flipsAndRotationsAreInvolutionsOrCycles() {
        val src = Img.generate(7, 5) { x, y -> (x * 5 + y) / 35f }
        for (i in src.data.indices) assertEquals(src.data[i], src.flipX().flipX().data[i], 1e-7f)
        for (i in src.data.indices) assertEquals(src.data[i], src.flipY().flipY().data[i], 1e-7f)
        val r4 = src.rotate90().rotate90().rotate90().rotate90()
        assertEquals(src.width, r4.width)
        assertEquals(src.height, r4.height)
        for (i in src.data.indices) assertEquals(src.data[i], r4.data[i], 1e-7f, "rotate90 x4 at $i")
        val t2 = src.transpose().transpose()
        for (i in src.data.indices) assertEquals(src.data[i], t2.data[i], 1e-7f)
    }

    @Test
    fun otsuSplitsAKnownBimodalImage() {
        // Two populations at 0.2 and 0.8; the threshold must land between them.
        val img = Img.generate(64, 64) { x, _ -> if (x < 32) 0.2f else 0.8f }
        val t = img.otsuThreshold()
        assertTrue(t > 0.2f && t < 0.8f, "Otsu threshold $t not between the two modes")
        val mask = img.thresholdOtsu()
        assertEquals(64 * 32, mask.data.count { it > 0.5f }, "half the pixels should be foreground")
    }

    @Test
    fun histogramCountsEveryPixelAndEqualizeFlattensARamp() {
        val r = ramp(64, 64)
        val h = r.histogram(64)
        assertEquals(64 * 64, h.sum(), "histogram must account for every pixel")
        // A linear ramp is already uniform, so equalisation is close to the identity (up to binning).
        val eq = r.equalize(256)
        assertTrue(eq.meanAbsDiff(r) < 0.03, "equalising a uniform ramp moved it by ${eq.meanAbsDiff(r)}")
        // A low-contrast image must gain range.
        val flat = r.map { 0.4f + it * 0.1f }
        val eqFlat = flat.equalize(256)
        assertTrue(
            (eqFlat.max() - eqFlat.min()) > 0.9f,
            "equalisation should stretch a low-contrast image, got range ${eqFlat.max() - eqFlat.min()}"
        )
    }

    @Test
    fun normalizedPercentileIgnoresAnOutlier() {
        val img = Img.generate(32, 32) { x, _ -> x / 31f }.copy()
        img.data[0] = 1000f // one hot pixel
        val n = img.normalizedPercentile(0.0, 0.98)
        assertTrue(n.max() <= 1f + 1e-6f)
        // Plain normalisation would squash everything to ~0; percentile normalisation must not.
        assertTrue(n.mean() > 0.3f, "percentile normalisation squashed the image, mean=${n.mean()}")
        assertTrue(img.normalized().mean() < 0.01f, "plain normalisation should be squashed by the outlier")
    }

    // ==========================================================================================
    // Convolution and filters
    // ==========================================================================================

    @Test
    fun gaussianKernelIsNormalisedAndSymmetric() {
        for (sigma in listOf(0.5, 1.0, 2.5, 5.0)) {
            val k = gaussianKernel1D(sigma)
            assertEquals(1f, k.sum(), 1e-5f, "sigma=$sigma kernel must sum to 1")
            for (i in k.taps.indices) {
                assertEquals(k.taps[i], k.taps[k.taps.size - 1 - i], 1e-7f, "sigma=$sigma symmetry at $i")
            }
        }
    }

    @Test
    fun gaussianBlurPreservesTheImageMean() {
        // A normalised kernel + REFLECT borders is exactly DC-preserving.
        val src = Img.generate(48, 48) { x, y -> 0.3f + 0.4f * sin(x * 0.3).toFloat() * cos(y * 0.2).toFloat() }
        val before = src.mean()
        for (sigma in listOf(0.8, 1.5, 3.0)) {
            val after = src.gaussianBlur(sigma, border = Border.REFLECT).mean()
            assertEquals(before, after, 1e-4f, "sigma=$sigma changed the DC from $before to $after")
        }
    }

    @Test
    fun separableGaussianEqualsTheNaiveTwoDimensionalConvolution() {
        val src = Img.generate(24, 20) { x, y -> ((x * 7 + y * 13) % 17) / 17f }
        val k = gaussianKernel1D(1.4)
        val separable = src.convolveSeparable(k, k, Border.REFLECT)
        val dense = src.convolve2D(k.outer(k), Border.REFLECT)
        assertTrue(separable.sameShapeAs(dense))
        var worst = 0f
        for (i in separable.data.indices) worst = maxOf(worst, abs(separable.data[i] - dense.data[i]))
        assertTrue(worst < 1e-5f, "separable and dense Gaussian differ by $worst")
    }

    @Test
    fun boxBlurRunningSumEqualsTheSeparableBoxKernel() {
        val src = Img.generate(30, 26) { x, y -> ((x * 3 + y * 5) % 11) / 11f }
        for (r in listOf(1, 3, 7)) {
            val running = src.boxBlur(r, Border.CLAMP)
            val kernel = src.convolveSeparable(boxKernel1D(r), boxKernel1D(r), Border.CLAMP)
            var worst = 0f
            for (i in running.data.indices) worst = maxOf(worst, abs(running.data[i] - kernel.data[i]))
            assertTrue(worst < 1e-5f, "radius=$r running-sum box differs from the kernel box by $worst")
        }
    }

    @Test
    fun medianRemovesSaltAndPepperThatTheMeanOnlySmears() {
        val clean = Img.generate(40, 40) { x, _ -> if (x < 20) 0.2f else 0.8f }
        val noisy = clean.copy()
        val rng = kotlin.random.Random(7)
        repeat(120) {
            val i = rng.nextInt(noisy.data.size)
            noisy.data[i] = if (rng.nextBoolean()) 0f else 1f
        }
        val med = noisy.median(1)
        val mean = noisy.boxBlur(1)
        assertTrue(
            med.meanAbsDiff(clean) < mean.meanAbsDiff(clean),
            "median (${med.meanAbsDiff(clean)}) should beat the mean (${mean.meanAbsDiff(clean)}) on impulse noise"
        )
        assertTrue(med.meanAbsDiff(clean) < 0.02, "median left ${med.meanAbsDiff(clean)} residual error")
    }

    @Test
    fun bilateralSmoothsFlatRegionsWhileKeepingTheEdgeStep() {
        val step = stepEdge(48, 48, 24)
        val rng = kotlin.random.Random(3)
        val noisy = step.map { (it + (rng.nextFloat() - 0.5f) * 0.08f) }
        val bil = noisy.bilateral(sigmaSpace = 3.0, sigmaRange = 0.1)
        val gauss = noisy.gaussianBlur(3.0)
        // Edge contrast across the step at mid-height.
        fun contrast(img: Img) = img.at(30, 24) - img.at(18, 24)
        assertTrue(
            contrast(bil) > contrast(gauss),
            "bilateral kept ${contrast(bil)} of the step, Gaussian only ${contrast(gauss)}"
        )
        assertTrue(contrast(bil) > 0.9f, "bilateral should keep nearly the whole unit step, kept ${contrast(bil)}")
        // ... and it still de-noises the flat right half.
        val flatBefore = noisy.crop(32, 4, 12, 40).stdDev()
        val flatAfter = bil.crop(32, 4, 12, 40).stdDev()
        assertTrue(flatAfter < flatBefore * 0.5f, "bilateral barely de-noised: $flatBefore -> $flatAfter")
    }

    @Test
    fun bilateralRangeLutMatchesTheDirectGaussianDefinition() {
        fun direct(img: Img, sigmaSpace: Double, sigmaRange: Double, border: Border): Img {
            val radius = maxOf(1, kotlin.math.ceil(sigmaSpace * 2.0).toInt())
            val inv2ss = 1.0 / (2.0 * sigmaSpace * sigmaSpace)
            val inv2sr = 1.0 / (2.0 * sigmaRange * sigmaRange)
            val out = FloatArray(img.data.size)
            for (y in 0 until img.height) for (x in 0 until img.width) for (c in 0 until img.channels) {
                val centre = img.at(x, y, c)
                var numerator = 0.0
                var denominator = 0.0
                for (dy in -radius..radius) for (dx in -radius..radius) {
                    val sample = img.get(x + dx, y + dy, c, border)
                    val difference = sample - centre
                    val weight = kotlin.math.exp(
                        -(dx * dx + dy * dy) * inv2ss - difference * difference * inv2sr
                    )
                    numerator += weight * sample
                    denominator += weight
                }
                out[img.index(x, y, c)] = (numerator / denominator).toFloat()
            }
            return Img(img.width, img.height, img.channels, out)
        }

        val scalar = Img.generate(9, 7) { x, y ->
            ((x * 17 + y * 31 + x * y * 7) % 101) / 100f
        }
        val rgb = Img.generateRGB(7, 6) { x, y ->
            Triple(
                ((x * 13 + y * 5) % 29) / 28f,
                ((x * 3 + y * 19) % 37) / 36f,
                ((x * 23 + y * 11) % 43) / 42f,
            )
        }
        val cases = listOf(
            Triple(scalar, 0.5 to 0.04, Border.CLAMP),  // radius 1, narrow range kernel
            Triple(scalar, 1.4 to 0.12, Border.REFLECT), // radius 3, ordinary photographic range
            Triple(rgb, 2.2 to 0.35, Border.WRAP),       // radius 5, RGB and a broad range kernel
        )

        for ((image, sigmas, border) in cases) {
            val (sigmaSpace, sigmaRange) = sigmas
            val expected = direct(image, sigmaSpace, sigmaRange, border)
            val actual = image.bilateral(sigmaSpace, sigmaRange, border)
            var worst = 0f
            for (i in actual.data.indices) worst = maxOf(worst, abs(actual.data[i] - expected.data[i]))
            assertTrue(
                worst < 1e-5f,
                "LUT drifted from direct bilateral by $worst at sigmaSpace=$sigmaSpace, " +
                    "sigmaRange=$sigmaRange, radius=${maxOf(1, kotlin.math.ceil(sigmaSpace * 2.0).toInt())}, " +
                    "border=$border, channels=${image.channels}"
            )
        }
    }

    @Test
    fun unsharpMaskIncreasesEdgeContrast() {
        val soft = stepEdge(32, 32, 16).gaussianBlur(2.0)
        val sharp = soft.unsharpMask(sigma = 2.0, amount = 1.0)
        val before = soft.at(20, 16) - soft.at(12, 16)
        val after = sharp.at(20, 16) - sharp.at(12, 16)
        assertTrue(after > before, "unsharp mask reduced edge contrast: $before -> $after")
    }

    // ==========================================================================================
    // Gradients
    // ==========================================================================================

    @Test
    fun sobelOnAVerticalStepEdgeHasTheAnalyticMagnitudeAndOrientation() {
        val img = stepEdge(32, 32, 16)
        val grad = img.sobel()
        val mag = grad.magnitude()
        val ori = grad.orientation()
        // The normalised Sobel gives a unit gradient across a unit step, split over the two
        // columns straddling the transition (x = 15 and x = 16), each 0.5.
        assertEquals(0.5f, mag.at(15, 16), 1e-4f, "magnitude just left of the edge")
        assertEquals(0.5f, mag.at(16, 16), 1e-4f, "magnitude just right of the edge")
        // Total gradient across the edge is exactly 1 for a unit step.
        var across = 0f
        for (x in 0 until 32) across += grad.dx.at(x, 16)
        assertEquals(1f, across, 1e-4f, "summed dx across a unit step must be 1")
        // The step is dark→bright rightwards, so the gradient points along +x: orientation 0.
        assertEquals(0f, ori.at(16, 16), 1e-4f, "orientation of a vertical edge")
        // ... and dy is identically zero away from the top/bottom rows.
        for (y in 4 until 28) assertEquals(0f, grad.dy.at(16, y), 1e-6f, "dy at y=$y")
    }

    @Test
    fun sobelOnAHorizontalStepEdgePointsDown() {
        val img = Img.generate(32, 32) { _, y -> if (y >= 16) 1f else 0f }
        val grad = img.sobel()
        // y grows downwards, so a dark→bright downwards step has orientation +pi/2.
        assertEquals((PI / 2).toFloat(), grad.orientation().at(16, 16), 1e-4f)
        assertEquals(0.5f, grad.magnitude().at(16, 16), 1e-4f)
    }

    @Test
    fun sobelOnALinearRampRecoversTheSlope() {
        // f(x) = 0.1 * x -> df/dx = 0.1 everywhere in the interior.
        val img = Img.generate(32, 32) { x, _ -> 0.1f * x }
        val dx = img.sobel().dx
        for (y in 4 until 28) for (x in 4 until 28) {
            assertEquals(0.1f, dx.at(x, y), 1e-4f, "slope at ($x, $y)")
        }
    }

    @Test
    fun gaussianGradientRecoversTheSlopeOfARampAtEveryScale() {
        val img = Img.generate(48, 48) { x, _ -> 0.05f * x }
        for (sigma in listOf(1.0, 2.0, 3.0)) {
            val dx = img.gaussianGradient(sigma).dx
            assertEquals(0.05f, dx.at(24, 24), 1e-4f, "sigma=$sigma slope")
        }
    }

    @Test
    fun scharrAgreesWithSobelOnAnAxisAlignedEdgeAndIsMoreIsotropic() {
        val vertical = stepEdge(48, 48, 24)
        assertEquals(vertical.sobel().magnitude().at(24, 24), vertical.scharr().magnitude().at(24, 24), 1e-4f)
        // On a 45-degree edge the orientation estimate should be closer to pi/4 for Scharr.
        val diagonal = Img.generate(64, 64) { x, y -> if (x + y >= 64) 1f else 0f }
        val target = (PI / 4).toFloat()
        val sobelErr = abs(diagonal.sobel().orientation().at(32, 31) - target)
        val scharrErr = abs(diagonal.scharr().orientation().at(32, 31) - target)
        assertTrue(sobelErr < 0.05f, "sobel diagonal orientation error $sobelErr")
        assertTrue(scharrErr <= sobelErr + 1e-6f, "scharr ($scharrErr) should be no worse than sobel ($sobelErr)")
    }

    @Test
    fun laplacianIsZeroOnALinearRampAndSignedAcrossAnEdge() {
        val ramp = Img.generate(32, 32) { x, _ -> 0.03f * x }
        val lap = ramp.laplacian()
        for (y in 2 until 30) for (x in 2 until 30) {
            assertEquals(0f, lap.at(x, y), 1e-5f, "Laplacian of a linear ramp at ($x, $y)")
        }
        val edge = stepEdge(32, 32, 16).laplacian()
        assertTrue(edge.at(15, 16) > 0f, "dark side of the step should be positive, got ${edge.at(15, 16)}")
        assertTrue(edge.at(16, 16) < 0f, "bright side of the step should be negative, got ${edge.at(16, 16)}")
    }

    @Test
    fun differenceOfGaussiansIsZeroMeanOnAFlatField() {
        val flat = Img.filled(32, 32, 1, 0.5f)
        val dog = flat.differenceOfGaussians(1.0, 2.0, Border.REFLECT)
        for (v in dog.data) assertEquals(0f, v, 1e-5f)
    }

    // ==========================================================================================
    // Pyramids
    // ==========================================================================================

    @Test
    fun gaussianPyramidHalvesEachOctaveAndStopsAtMinSize() {
        val src = Img.generate(64, 64) { x, y -> ((x xor y) % 32) / 32f }
        val p = src.gaussianPyramid(levels = 6, minSize = 8)
        assertEquals(64, p[0].width)
        assertEquals(32, p[1].width)
        assertEquals(16, p[2].width)
        // 16 -> 8 is allowed (8 >= minSize), 8 -> 4 is not.
        assertEquals(4, p.size, "pyramid should stop at the 8x8 level, got ${p.levels.map { it.width }}")
        assertEquals(8, p.levels.last().width)
    }

    @Test
    fun laplacianPyramidReconstructsItsSource() {
        val src = Img.generate(64, 64) { x, y ->
            (0.5 + 0.3 * sin(x * 0.4) * cos(y * 0.25) + 0.15 * sin(x * 0.05)).toFloat()
        }
        val lp = src.laplacianPyramid(levels = 4, minSize = 8)
        assertTrue(lp.size >= 3, "expected at least 3 bands, got ${lp.size}")
        val back = lp.reconstruct()
        assertTrue(back.sameShapeAs(src), "reconstruction shape ${back.width}x${back.height}")
        var worst = 0f
        for (i in src.data.indices) worst = maxOf(worst, abs(src.data[i] - back.data[i]))
        assertTrue(worst < 1e-4f, "Laplacian pyramid round-trip error $worst")
    }

    @Test
    fun laplacianPyramidBandsAreZeroMeanBandPass() {
        val src = Img.generate(64, 64) { x, y -> (0.5 + 0.3 * sin(x * 0.5) * sin(y * 0.5)).toFloat() }
        val lp = src.laplacianPyramid(levels = 3, minSize = 8)
        for ((i, b) in lp.bands.withIndex()) {
            assertTrue(abs(b.mean()) < 0.02f, "band $i mean ${b.mean()} should be near zero (band-pass)")
        }
        // The residual carries the DC.
        assertEquals(src.mean(), lp.residual.mean(), 0.05f)
    }

    // ==========================================================================================
    // Presentation helpers (montage / stacking) — cheap, but they run in every live box
    // ==========================================================================================

    @Test
    fun montageLaysPanelsOutOnAGridAndKeepsTheirPixels() {
        val a = Img.filled(40, 30, 1, 0.9f)
        val b = Img.filled(40, 30, 1, 0.2f)
        val c = Img.filled(20, 30, 1, 0.5f)
        val m = montage(listOf("a" to a, "b" to b, "c" to c), columns = 2, cellSide = 64, gap = 4)
        assertEquals(3, m.channels, "montage returns RGB")
        // 2 columns of 40px cells + 3 gaps; 2 rows of (30 + a 22px caption band) + 3 gaps.
        val captionH = 7 * 2 + 8
        assertEquals(2 * 40 + 3 * 4, m.width)
        assertEquals(2 * (30 + captionH) + 3 * 4, m.height)
        // The tiles' pixels survive verbatim at their cell origins — the caption pass is pure
        // float writes through a bitmap font, with no 8-bit round-trip anywhere.
        assertEquals(0.9f, m.at(4 + 5, 4 + 5, 0), 1e-6f, "panel a pixel")
        assertEquals(0.2f, m.at(4 + 40 + 4 + 5, 4 + 5, 0), 1e-6f, "panel b pixel")
        // Captions are burned in, so pixels in the caption band differ from the background.
        val band = m.crop(0, 4 + 30, m.width, captionH)
        assertTrue(band.max() > 0.08f + 1e-3f, "no caption text was drawn into the montage")
    }

    @Test
    fun withLabelBurnsTextWithNoAwt() {
        // The built-in 5x7 bitmap font must actually mark pixels — and only where the text is.
        val blank = Img.filled(120, 24, 3, 0f)
        val labelled = blank.withLabel("Hello 123", x = 2, y = 4, scale = 2)
        assertTrue(labelled.max() > 0.5f, "withLabel drew nothing")
        // The glyph band is 7*2 = 14 px tall starting at y = 4; below it must stay black.
        for (y in 19 until 24) for (x in 0 until 120) {
            assertEquals(0f, labelled.at(x, y, 0), 1e-6f, "text bled outside its band at ($x, $y)")
        }
        // Unmapped characters degrade rather than throw, and typographic ones fold to ASCII.
        assertTrue(blank.withLabel("240×180 — σ · ∞", scale = 1).max() > 0.5f)
    }

    @Test
    fun montageNormalisesASignedPanelForDisplay() {
        val signed = Img.generate(20, 20) { x, _ -> x - 10f }   // range -10..9
        val m = montage(listOf("signed" to signed), columns = 1, cellSide = 64, displayRange = -10f..10f)
        assertTrue(m.max() <= 1f + 1e-5f, "montage emitted out-of-range samples: ${m.max()}")
        assertTrue(m.min() >= 0f - 1e-5f, "montage emitted negative samples: ${m.min()}")
    }

    @Test
    fun montageUsesOneRequestedDisplayRangeForEveryPanel() {
        val ordinary = Img(2, 1, 1, floatArrayOf(0.2f, 0.8f))
        val overshooting = Img(2, 1, 1, floatArrayOf(0.2f, 1.2f))
        val m = montage(
            listOf("ordinary" to ordinary, "overshoot" to overshooting),
            columns = 2,
            cellSide = 8,
            gap = 1,
            displayRange = 0f..1f,
        )

        assertEquals(0.2f, m.at(1, 1, 0), 1e-6f, "ordinary panel did not use the requested domain")
        assertEquals(0.2f, m.at(1 + 2 + 1, 1, 0), 1e-6f, "overshooting panel was rescaled independently")
        assertEquals(1f, m.at(1 + 2 + 1 + 1, 1, 0), 1e-6f, "requested domain should clamp overshoot")
    }

    @Test
    fun hstackAndVstackConcatenateWithGaps() {
        val a = Img.filled(10, 8, 1, 1f)
        val b = Img.filled(6, 8, 1, 0.5f)
        val h = hstack(a, b, gap = 3, background = 0f)
        assertEquals(19, h.width)
        assertEquals(8, h.height)
        assertEquals(1f, h.at(0, 0, 0), 1e-5f)
        assertEquals(0f, h.at(11, 0, 0), 1e-5f, "the gap should carry the background")
        assertEquals(0.5f, h.at(13, 0, 0), 1e-5f)
        val v = vstack(a, a, gap = 2, background = 0f)
        assertEquals(18, v.height)
    }

    @Test
    fun pyrDownIsAntiAliasedNotABareDecimation() {
        // A 1-pixel checkerboard decimated without a blur aliases to a constant; with the blur
        // the result is near-uniform 0.5 with much lower variance than the source.
        val checker = Img.generate(64, 64) { x, y -> if ((x + y) % 2 == 0) 1f else 0f }
        val down = checker.pyrDown(1.0)
        assertEquals(32, down.width)
        assertEquals(0.5f, down.mean(), 0.05f)
        assertTrue(down.stdDev() < 0.15f, "pyrDown aliased: stdDev ${down.stdDev()}")
    }

    @Test
    fun adaptiveThresholdBeatsAGlobalOneUnderUnevenIllumination() {
        // thresholdAdaptive was the one consumer of boxBlur with no test of its own, which matters
        // more than it looks: it *thresholds* the local mean, so unlike a blur it can turn a small
        // numeric change in that mean into a flipped pixel. It is precisely the caller that a change
        // to the running sums could break silently.
        //
        // Note the polarity, which is the thing to get right here: the comparison is
        // `v > m - bias`, so a pixel stays foreground (1) unless it is *darker* than its
        // neighbourhood. That is the dark-ink-on-lit-paper case, and on a linear ramp the mean of a
        // symmetric window equals the centre value exactly, so bare background is 1 by construction.
        //
        // The scene is therefore five dark marks on paper whose illumination ramps from 0.05 to 0.85
        // across the frame. Every mark is 0.18 below its LOCAL background, but a mark on the bright
        // side (0.67) is *lighter* than the bare background on the dark side (0.05) — so no single
        // global threshold can separate marks from paper, while a local-mean one can. Marks are kept
        // at least radius+3 px clear of the probes so each probe's window contains no mark.
        val w = 96
        val h = 64
        val marks = listOf(12 to 32, 32 to 20, 50 to 40, 68 to 24, 84 to 36)
        val img = Img.generate(w, h) { x, y ->
            val illum = 0.05f + 0.80f * (x / (w - 1).toFloat())
            val onMark = marks.any { (mx, my) -> abs(x - mx) <= 3 && abs(y - my) <= 3 }
            if (onMark) illum - 0.18f else illum
        }

        val probes = listOf(20 to 4, 40 to 59, 60 to 4, 78 to 59)
        val adaptive = img.thresholdAdaptive(radius = 8, bias = 0.05f)

        // Marks come out dark (0), paper comes out light (1) — at every illumination level.
        for ((mx, my) in marks) {
            assertEquals(0f, adaptive.at(mx, my), "adaptive threshold missed the dark mark at ($mx, $my)")
        }
        for ((x, y) in probes) {
            assertEquals(1f, adaptive.at(x, y), "adaptive threshold darkened bare paper at ($x, $y)")
        }

        // And a global threshold really does fail on the same nine probes — otherwise this test
        // proves nothing about being *adaptive*. Otsu can only split by absolute brightness, so it
        // must either call the dark-side paper a mark or the bright-side marks paper.
        val global = img.thresholdOtsu()
        val globalWrong = marks.count { (mx, my) -> global.at(mx, my) != 0f } +
            probes.count { (x, y) -> global.at(x, y) != 1f }
        assertTrue(
            globalWrong > 0,
            "a global threshold was expected to misclassify some of the 9 probes, but it got all of them right"
        )
    }
}
