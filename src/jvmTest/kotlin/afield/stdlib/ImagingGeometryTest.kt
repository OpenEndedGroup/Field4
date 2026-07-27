package afield.stdlib

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Invariants of the geometry and motion layer (`imaging_geometry.kt`): affine and projective
 * transforms, the DLT homography, RANSAC's outlier rejection, warping, and Lucas–Kanade /
 * block-matching optical flow.
 *
 * The homography tests are the interesting ones: a transform is *planted*, applied to known
 * points, and the estimator must recover it to within float noise. RANSAC is fed correspondences
 * half of which are deliberate garbage, and must still return the planted transform and correctly
 * partition the inliers from the outliers.
 */
class ImagingGeometryTest {

    private fun grid(n: Int = 5, spacing: Double = 20.0, x0: Double = 10.0, y0: Double = 10.0): List<Vec2> =
        (0 until n).flatMap { j -> (0 until n).map { i -> Vec2((x0 + i * spacing).toFloat(), (y0 + j * spacing).toFloat()) } }

    private fun textured(w: Int = 96, h: Int = 96): Img = Img.generate(w, h) { x, y ->
        // A deterministic, high-frequency-but-smooth pattern: plenty of trackable corners.
        (0.5 + 0.25 * kotlin.math.sin(x * 0.35) * kotlin.math.cos(y * 0.29) +
            0.2 * kotlin.math.sin((x + y) * 0.17)).toFloat().coerceIn(0f, 1f)
    }

    // ==========================================================================================
    // Affine
    // ==========================================================================================

    @Test
    fun affineInverseAndCompositionAreConsistent() {
        val t = Affine2D.translation(12.0, -5.0)
            .compose(Affine2D.rotation(0.4))
            .compose(Affine2D.scale(1.7, 0.8))
        val inv = t.inverse()
        for (p in grid(3)) {
            val (x, y) = t.apply(p.x.toDouble(), p.y.toDouble())
            val (bx, by) = inv.apply(x, y)
            assertEquals(p.x.toDouble(), bx, 1e-9, "round-trip x for $p")
            assertEquals(p.y.toDouble(), by, 1e-9, "round-trip y for $p")
        }
        assertEquals(1.7 * 0.8, t.determinant(), 1e-9, "determinant is the product of the scales")
    }

    @Test
    fun affineFromCorrespondencesRecoversAPlantedTransform() {
        val planted = Affine2D(1.3, 0.25, -0.4, 0.9, 17.0, -6.0)
        val src = grid(4)
        val dst = src.map { planted.apply(it) }
        val fit = Affine2D.fromCorrespondences(src, dst)
        assertEquals(planted.a, fit.a, 1e-4); assertEquals(planted.b, fit.b, 1e-4)
        assertEquals(planted.c, fit.c, 1e-4); assertEquals(planted.d, fit.d, 1e-4)
        assertEquals(planted.e, fit.e, 1e-3); assertEquals(planted.f, fit.f, 1e-3)
    }

    @Test
    fun affineFitRejectsTooFewPoints() {
        assertFailsWith<IllegalArgumentException> {
            Affine2D.fromCorrespondences(listOf(Vec2(0f, 0f), Vec2(1f, 0f)), listOf(Vec2(0f, 0f), Vec2(1f, 0f)))
        }
    }

    // ==========================================================================================
    // Homography / DLT
    // ==========================================================================================

    @Test
    fun dltRoundTripsAKnownProjectiveTransform() {
        // A genuine perspective transform (non-zero bottom row).
        val planted = Homography(doubleArrayOf(
            1.20, 0.15, 12.0,
            -0.10, 0.95, -7.0,
            0.0012, -0.0007, 1.0
        ))
        val src = grid(4, 25.0)
        val dst = src.map { planted.apply(it) }
        val fit = findHomography(src, dst)
        // Tolerances are set by the INPUT precision, not the solver: Vec2 is float-backed, so the
        // planted correspondences already carry ~1e-7 relative error before the DLT sees them.
        assertTrue(
            fit.maxCoefficientDifference(planted) < 1e-4,
            "DLT did not recover the planted homography; diff ${fit.maxCoefficientDifference(planted)}\n$fit\nvs $planted"
        )
        // ... and it maps every point back to where it should.
        for (i in src.indices) {
            val (x, y) = fit.apply(src[i].x.toDouble(), src[i].y.toDouble())
            assertEquals(dst[i].x.toDouble(), x, 1e-3, "mapped x for point $i")
            assertEquals(dst[i].y.toDouble(), y, 1e-3, "mapped y for point $i")
        }
    }

    @Test
    fun dltWithExactlyFourPointsIsExactToInputPrecision() {
        val planted = Homography(doubleArrayOf(0.9, 0.2, 5.0, -0.15, 1.1, 3.0, 0.002, 0.001, 1.0))
        val src = listOf(Vec2(0f, 0f), Vec2(50f, 0f), Vec2(50f, 40f), Vec2(0f, 40f))
        val dst = src.map { planted.apply(it) }
        val fit = findHomography(src, dst)
        assertTrue(fit.maxCoefficientDifference(planted) < 1e-5, "4-point DLT diff ${fit.maxCoefficientDifference(planted)}")
    }

    @Test
    fun homographyInverseRoundTripsPoints() {
        val h = Homography(doubleArrayOf(1.1, 0.3, 9.0, -0.2, 0.8, -4.0, 0.0015, 0.0009, 1.0))
        val inv = h.inverse()
        for (p in grid(3, 30.0)) {
            val (x, y) = h.apply(p.x.toDouble(), p.y.toDouble())
            val (bx, by) = inv.apply(x, y)
            assertEquals(p.x.toDouble(), bx, 1e-7, "inverse round-trip x")
            assertEquals(p.y.toDouble(), by, 1e-7, "inverse round-trip y")
        }
        assertTrue(h.compose(inv).maxCoefficientDifference(Homography.IDENTITY) < 1e-9)
    }

    @Test
    fun anAffineHomographyIsRecognisedAsAffine() {
        assertTrue(Affine2D(1.4, 0.2, -0.3, 1.1, 5.0, 2.0).toHomography().isAffine())
        assertTrue(!Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.003, 0.0, 1.0)).isAffine())
    }

    @Test
    fun dltRejectsTooFewCorrespondences() {
        val p = listOf(Vec2(0f, 0f), Vec2(1f, 0f), Vec2(1f, 1f))
        assertFailsWith<IllegalArgumentException> { findHomography(p, p) }
    }

    @Test
    fun smallestEigenvectorFindsTheKnownNullDirection() {
        // A rank-2 symmetric 3x3 whose null space is (0, 0, 1).
        val m = arrayOf(
            doubleArrayOf(4.0, 1.0, 0.0),
            doubleArrayOf(1.0, 3.0, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0)
        )
        val v = smallestEigenvector(m)
        assertEquals(1.0, abs(v[2]), 1e-9, "null direction should be the third axis, got ${v.toList()}")
        assertTrue(abs(v[0]) < 1e-9 && abs(v[1]) < 1e-9)
    }

    @Test
    fun denseSolverSolvesAKnownSystem() {
        // 3x + 2y - z = 1 ; 2x - 2y + 4z = -2 ; -x + 0.5y - z = 0  =>  (1, -2, -2)
        val a = arrayOf(
            doubleArrayOf(3.0, 2.0, -1.0),
            doubleArrayOf(2.0, -2.0, 4.0),
            doubleArrayOf(-1.0, 0.5, -1.0)
        )
        val x = solveDenseSystem(a, doubleArrayOf(1.0, -2.0, 0.0))
        assertEquals(1.0, x[0], 1e-9); assertEquals(-2.0, x[1], 1e-9); assertEquals(-2.0, x[2], 1e-9)
    }

    // ==========================================================================================
    // RANSAC
    // ==========================================================================================

    @Test
    fun ransacRejectsPlantedOutliersAndRecoversTheTransform() {
        val planted = Homography(doubleArrayOf(1.15, 0.10, 20.0, -0.08, 0.92, -11.0, 0.0010, -0.0006, 1.0))
        val rng = Random(42)
        val src = ArrayList<Vec2>()
        val dst = ArrayList<Vec2>()
        val trueInliers = HashSet<Int>()
        // 40 good correspondences...
        for (i in 0 until 40) {
            val p = Vec2(rng.nextDouble(5.0, 120.0).toFloat(), rng.nextDouble(5.0, 120.0).toFloat())
            src.add(p)
            dst.add(planted.apply(p))
            trueInliers.add(i)
        }
        // ... and 25 pure garbage ones.
        for (i in 0 until 25) {
            src.add(Vec2(rng.nextDouble(5.0, 120.0).toFloat(), rng.nextDouble(5.0, 120.0).toFloat()))
            dst.add(Vec2(rng.nextDouble(5.0, 120.0).toFloat(), rng.nextDouble(5.0, 120.0).toFloat()))
        }
        val fit = findHomographyRansac(src, dst, threshold = 2.0, iterations = 2000, seed = 7L)
        assertTrue(fit.inlierCount >= 40, "expected >= 40 inliers, got ${fit.inlierCount}")
        // Every one of the 40 planted correspondences must be classified as an inlier...
        for (i in trueInliers) assertTrue(i in fit.inliers, "planted inlier $i was rejected")
        // ... and essentially none of the garbage.
        val falsePositives = fit.inliers.count { it >= 40 }
        assertTrue(falsePositives <= 2, "$falsePositives outliers were accepted as inliers")
        assertTrue(
            fit.homography.maxCoefficientDifference(planted) < 1e-4,
            "RANSAC's model differs from the planted one by ${fit.homography.maxCoefficientDifference(planted)}"
        )
    }

    @Test
    fun ransacIsDeterministicForAGivenSeed() {
        val planted = Homography(doubleArrayOf(1.05, 0.05, 4.0, -0.03, 1.02, -2.0, 0.0004, 0.0002, 1.0))
        val rng = Random(11)
        val src = (0 until 30).map { Vec2(rng.nextDouble(0.0, 100.0).toFloat(), rng.nextDouble(0.0, 100.0).toFloat()) }
        val dst = src.mapIndexed { i, p ->
            if (i % 3 == 0) Vec2(rng.nextDouble(0.0, 100.0).toFloat(), rng.nextDouble(0.0, 100.0).toFloat())
            else planted.apply(p)
        }
        val a = findHomographyRansac(src, dst, seed = 5L)
        val b = findHomographyRansac(src, dst, seed = 5L)
        assertEquals(a.inliers, b.inliers, "same seed must give the same consensus set")
        assertEquals(0.0, a.homography.maxCoefficientDifference(b.homography), 1e-12)
    }

    // ==========================================================================================
    // Warping
    // ==========================================================================================

    @Test
    fun warpByTheIdentityIsTheIdentity() {
        val src = textured(48, 48)
        val out = src.warpAffine(Affine2D.IDENTITY)
        for (i in src.data.indices) assertEquals(src.data[i], out.data[i], 1e-5f, "sample $i")
    }

    @Test
    fun integerTranslationMovesContentExactly() {
        val src = Img.generate(40, 40) { x, y -> if (x in 10..19 && y in 10..19) 1f else 0f }
        val moved = src.warpAffine(Affine2D.translation(7.0, -3.0), border = Border.ZERO)
        // The block should now sit at x 17..26, y 7..16.
        assertEquals(1f, moved.at(17, 7), 1e-5f)
        assertEquals(1f, moved.at(26, 16), 1e-5f)
        assertEquals(0f, moved.at(16, 7), 1e-5f)
        assertEquals(0f, moved.at(27, 16), 1e-5f)
        assertEquals(src.data.count { it > 0.5f }, moved.data.count { it > 0.5f }, "translation must preserve area")
    }

    @Test
    fun warpAndInverseWarpRoundTripsTheInterior() {
        val src = textured(64, 64)
        val t = Affine2D.rotationAbout(0.3, 32.0, 32.0).compose(Affine2D.translation(3.0, -2.0))
        val there = src.warpAffine(t, border = Border.CLAMP)
        val back = there.warpAffine(t.inverse(), border = Border.CLAMP)
        // Interpolating twice blurs, so compare a generous interior crop with a loose tolerance.
        val a = src.crop(16, 16, 32, 32)
        val b = back.crop(16, 16, 32, 32)
        assertTrue(a.meanAbsDiff(b) < 0.03, "warp round-trip error ${a.meanAbsDiff(b)}")
    }

    @Test
    fun rotatingByNinetyDegreesFourTimesReturnsTheImage() {
        val src = textured(64, 64)
        var cur = src
        repeat(4) { cur = cur.rotate(PI / 2, border = Border.CLAMP) }
        assertTrue(src.crop(8, 8, 48, 48).meanAbsDiff(cur.crop(8, 8, 48, 48)) < 0.02)
    }

    @Test
    fun warpQuadToRectRectifiesAPlantedPerspectiveView() {
        // Take a rectangle, project it through a known homography, then rectify it back.
        val flat = Img.generate(80, 60) { x, y -> if (((x / 10) + (y / 10)) % 2 == 0) 0.85f else 0.15f }
        val h = Homography(doubleArrayOf(1.0, 0.12, 4.0, 0.05, 1.0, 6.0, 0.0018, 0.0011, 1.0))
        val projected = flat.warpPerspective(h, 120, 100, border = Border.ZERO)
        val quad = listOf(Vec2(0f, 0f), Vec2(79f, 0f), Vec2(79f, 59f), Vec2(0f, 59f)).map { h.apply(it) }
        val rectified = projected.warpQuadToRect(quad, 80, 60)
        // The rectified interior must match the original checkerboard.
        val a = flat.crop(8, 8, 64, 44)
        val b = rectified.crop(8, 8, 64, 44)
        assertTrue(a.meanAbsDiff(b) < 0.09, "rectification error ${a.meanAbsDiff(b)}")
    }

    @Test
    fun nearestNeighbourWarpPreservesDiscreteLabelValues() {
        val labels = Img.generate(32, 32) { x, _ -> (x / 8).toFloat() }
        val moved = labels.warpAffine(Affine2D.translation(4.0, 0.0), bilinear = false, border = Border.CLAMP)
        for (v in moved.data) assertTrue(v == kotlin.math.floor(v), "nearest warp produced a fractional label $v")
    }

    // ==========================================================================================
    // Optical flow
    // ==========================================================================================

    @Test
    fun lucasKanadeRecoversAKnownSmallTranslation() {
        val a = textured(96, 96)
        val dx = 2.0
        val dy = -1.0
        val b = a.warpAffine(Affine2D.translation(dx, dy), border = Border.CLAMP)
        val pts = a.goodFeaturesToTrack(maxCount = 60, minDistance = 6)
        assertTrue(pts.size >= 20, "expected trackable features, got ${pts.size}")
        val seeds = pts.filter { it.x > 16 && it.y > 16 && it.x < 80 && it.y < 80 }.map { it.toVec2() }
        assertTrue(seeds.size >= 10, "expected interior seeds, got ${seeds.size}")
        val flow = opticalFlowLK(a, b, seeds, windowRadius = 7, levels = 3)
        assertTrue(flow.tracked().size >= seeds.size * 3 / 4, "only ${flow.tracked().size}/${seeds.size} tracked")
        val (mdx, mdy) = flow.medianDisplacement()
        assertEquals(dx, mdx, 0.25, "median dx")
        assertEquals(dy, mdy, 0.25, "median dy")
    }

    @Test
    fun lucasKanadePyramidHandlesALargeTranslationThatOneLevelCannot() {
        val a = textured(128, 128)
        val dx = 9.0
        val b = a.warpAffine(Affine2D.translation(dx, 0.0), border = Border.CLAMP)
        val seeds = a.goodFeaturesToTrack(maxCount = 80, minDistance = 8)
            .filter { it.x > 30 && it.x < 100 && it.y > 24 && it.y < 104 }
            .map { it.toVec2() }
        assertTrue(seeds.size >= 8, "expected interior seeds, got ${seeds.size}")
        val pyramidal = opticalFlowLK(a, b, seeds, windowRadius = 7, levels = 4)
        val singleLevel = opticalFlowLK(a, b, seeds, windowRadius = 7, levels = 1)
        val ePyr = abs(pyramidal.medianDisplacement().first - dx)
        val eOne = abs(singleLevel.medianDisplacement().first - dx)
        assertTrue(ePyr < 0.5, "pyramidal LK missed a 9 px shift by $ePyr")
        assertTrue(ePyr < eOne, "the pyramid ($ePyr) should beat a single level ($eOne)")
    }

    @Test
    fun lucasKanadeReportsFailureInAFeaturelessRegion() {
        val flat = Img.filled(64, 64, 1, 0.5f)
        val flow = opticalFlowLK(flat, flat, listOf(Vec2(32f, 32f), Vec2(20f, 20f)))
        assertTrue(flow.tracked().isEmpty(), "a flat image has no trackable structure; got ${flow.tracked()}")
    }

    @Test
    fun blockMatchingRecoversALargeTranslation() {
        val a = textured(128, 128)
        val b = a.warpAffine(Affine2D.translation(6.0, -4.0), border = Border.CLAMP)
        val flow = opticalFlowBlockMatch(a, b, step = 16, blockRadius = 8, maxShift = 10)
        assertTrue(flow.vectors.size >= 25, "expected a grid of vectors, got ${flow.vectors.size}")
        val (mdx, mdy) = flow.medianDisplacement()
        assertEquals(6.0, mdx, 1.0, "median dx")
        assertEquals(-4.0, mdy, 1.0, "median dy")
    }

    @Test
    fun flowVectorsBecomeDrawableArrows() {
        val a = textured(96, 96)
        val b = a.warpAffine(Affine2D.translation(3.0, 2.0), border = Border.CLAMP)
        val seeds = a.goodFeaturesToTrack(maxCount = 40, minDistance = 8)
            .filter { it.x in 16.0..80.0 && it.y in 16.0..80.0 }.map { it.toVec2() }
        val flow = opticalFlowLK(a, b, seeds)
        val lines = flow.toFLines(scale = 4.0, minMagnitude = 0.5)
        assertTrue(lines.isNotEmpty(), "no arrows produced from ${flow.tracked().size} tracks")
        // Each arrow is a shaft plus a two-stroke head, so >= 4 nodes.
        for (l in lines) assertTrue(l.nodes.size >= 4, "arrow FLine has only ${l.nodes.size} nodes")
    }

    @Test
    fun homographyFromTrackedFlowRecoversAPlantedWarp() {
        // The full pipeline: features -> flow -> RANSAC homography -> compare to the planted warp.
        val a = textured(128, 128)
        val planted = Affine2D.rotationAbout(0.06, 64.0, 64.0).compose(Affine2D.translation(3.0, -2.0))
        val b = a.warpAffine(planted, border = Border.CLAMP)
        val seeds = a.goodFeaturesToTrack(maxCount = 120, minDistance = 6)
            .filter { it.x in 20.0..108.0 && it.y in 20.0..108.0 }.map { it.toVec2() }
        assertTrue(seeds.size >= 20, "expected seeds, got ${seeds.size}")
        val flow = opticalFlowLK(a, b, seeds, windowRadius = 7, levels = 3)
        assertTrue(flow.tracked().size >= 15, "only ${flow.tracked().size} tracks survived")
        val fit = findHomographyRansac(flow.sourcePoints(), flow.targetPoints(), threshold = 1.5, iterations = 1500, seed = 3L)
        assertTrue(fit.inlierCount >= flow.tracked().size / 2, "RANSAC kept only ${fit.inlierCount} inliers")
        // Compare by how the fitted transform maps a set of probe points versus the planted one.
        var worst = 0.0
        for (p in grid(4, 25.0, 25.0, 25.0)) {
            val (fx, fy) = fit.homography.apply(p.x.toDouble(), p.y.toDouble())
            val (tx, ty) = planted.apply(p.x.toDouble(), p.y.toDouble())
            worst = maxOf(worst, hypot(fx - tx, fy - ty))
        }
        assertTrue(worst < 2.0, "recovered transform disagrees with the planted one by up to $worst px")
    }

    @Test
    fun frameDifferenceIsolatesWhatMoved() {
        val bg = Img.filled(64, 64, 1, 0.3f)
        val f1 = bg.copy()
        for (y in 10..19) for (x in 10..19) f1.set(x, y, 0, 0.9f)
        val f2 = bg.copy()
        for (y in 10..19) for (x in 30..39) f2.set(x, y, 0, 0.9f)
        val mask = frameDifference(f1, f2, threshold = 0.2)
        assertEquals(200, mask.data.count { it > 0.5f }, "both the old and the new block position should light up")
        val cc = mask.connectedComponents()
        assertEquals(2, cc.count, "expected two motion blobs")
        assertTrue(cc.components.all { it.area == 100 })
    }

    @Test
    fun runningBackgroundConvergesToAStaticScene() {
        val scene = textured(48, 48)
        var bg = Img.filled(48, 48, 1, 0.0f)
        repeat(120) { bg = updateBackground(bg, scene, 0.1) }
        assertTrue(bg.meanAbsDiff(scene) < 0.01, "background did not converge: ${bg.meanAbsDiff(scene)}")
    }
}
