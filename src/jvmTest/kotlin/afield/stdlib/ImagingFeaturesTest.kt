package afield.stdlib

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Invariants of the detector layer (`imaging_features.kt`): Canny, Harris / Shi–Tomasi / FAST
 * corners, Hough lines and circles, morphology, connected components and the distance transform.
 *
 * Each test uses a synthetic image whose *correct answer is known analytically* — a square whose
 * corners are at exact coordinates, discs of exact area, a line at an exact `(rho, theta)` — so a
 * pass means the detector found the right thing, not merely "something".
 */
class ImagingFeaturesTest {

    private fun square(w: Int = 64, h: Int = 64, x0: Int = 16, y0: Int = 16, x1: Int = 47, y1: Int = 47) =
        Img.generate(w, h) { x, y -> if (x in x0..x1 && y in y0..y1) 1f else 0f }

    private fun disc(w: Int, h: Int, cx: Double, cy: Double, r: Double) =
        Img.generate(w, h) { x, y -> if (hypot(x - cx, y - cy) <= r) 1f else 0f }

    // ==========================================================================================
    // Canny
    // ==========================================================================================

    @Test
    fun cannyOnASquareProducesAThinClosedOutlineOnTheBoundary() {
        val img = square(64, 64, 16, 16, 47, 47)
        val edges = img.canny(0.05, 0.15, sigma = 1.0)
        val on = edges.data.count { it > 0.5f }
        // The true perimeter of a 32x32 square is 4 * 31 = 124 pixels; a thin detector should be
        // within a factor of ~1.6 of that (corners and the smoothing add a few).
        assertTrue(on in 80..220, "expected a thin square outline (~124 px), got $on edge pixels")

        // Every edge pixel must sit near the square's boundary, never deep inside or far outside.
        for (y in 0 until 64) for (x in 0 until 64) {
            if (edges.at(x, y) <= 0.5f) continue
            val dBoundary = minOf(
                if (x in 16..47) minOf(abs(y - 16), abs(y - 47)) else Int.MAX_VALUE,
                if (y in 16..47) minOf(abs(x - 16), abs(x - 47)) else Int.MAX_VALUE,
                maxOf(abs(x - 16), abs(y - 16)),
                maxOf(abs(x - 47), abs(y - 47))
            )
            assertTrue(dBoundary <= 3, "edge pixel at ($x, $y) is $dBoundary px from the square boundary")
        }
        // The interior of the square must be empty.
        for (y in 22..41) for (x in 22..41) assertEquals(0f, edges.at(x, y), "interior pixel ($x, $y) marked as edge")
    }

    @Test
    fun cannyIsThinnerThanARawGradientThreshold() {
        val img = square(64, 64)
        val canny = img.canny(0.05, 0.15).data.count { it > 0.5f }
        val raw = img.gaussianBlur(1.0).sobelMagnitude().threshold(0.05f).data.count { it > 0.5f }
        assertTrue(canny < raw / 2, "Canny ($canny) should be far thinner than a raw threshold ($raw)")
    }

    @Test
    fun cannyFindsNothingInAFlatImage() {
        val flat = Img.filled(32, 32, 1, 0.5f)
        assertEquals(0, flat.canny(0.05, 0.15).data.count { it > 0.5f })
    }

    @Test
    fun hysteresisKeepsAConnectedWeakChainAndDropsAnIsolatedWeakPixel() {
        val img = Img.zeros(16, 16, 1)
        img.set(2, 2, 0, 0.9f)              // strong seed
        for (x in 3..8) img.set(x, 2, 0, 0.3f) // weak, connected to the seed
        img.set(12, 12, 0, 0.3f)            // weak, isolated
        val out = hysteresis(img, 0.2f, 0.8f)
        assertEquals(1f, out.at(2, 2))
        for (x in 3..8) assertEquals(1f, out.at(x, 2), "connected weak pixel at x=$x should survive")
        assertEquals(0f, out.at(12, 12), "isolated weak pixel should be dropped")
    }

    // ==========================================================================================
    // Corners
    // ==========================================================================================

    @Test
    fun harrisFindsExactlyTheFourCornersOfASquare() {
        val img = square(80, 80, 20, 20, 59, 59)
        val corners = img.harrisCorners(threshold = 0.05, minDistance = 8, maxCount = 20)
        assertEquals(4, corners.size, "expected 4 corners, got ${corners.size}: $corners")
        val expected = listOf(20.0 to 20.0, 59.0 to 20.0, 20.0 to 59.0, 59.0 to 59.0)
        for ((ex, ey) in expected) {
            val hit = corners.minByOrNull { hypot(it.x - ex, it.y - ey) }
            assertNotNull(hit)
            val d = hypot(hit.x - ex, hit.y - ey)
            assertTrue(d < 3.0, "no Harris corner within 3 px of ($ex, $ey); nearest was $hit at $d px")
        }
    }

    @Test
    fun shiTomasiFindsTheSameFourCorners() {
        val img = square(80, 80, 20, 20, 59, 59)
        val corners = img.shiTomasiCorners(threshold = 0.05, minDistance = 8, maxCount = 20)
        assertEquals(4, corners.size, "expected 4 corners, got ${corners.size}")
        for ((ex, ey) in listOf(20.0 to 20.0, 59.0 to 20.0, 20.0 to 59.0, 59.0 to 59.0)) {
            assertTrue(corners.any { hypot(it.x - ex, it.y - ey) < 3.0 }, "no corner near ($ex, $ey)")
        }
    }

    @Test
    fun harrisResponseIsNegativeOnAnEdgeAndNearZeroOnFlatGround() {
        val img = square(80, 80, 20, 20, 59, 59)
        val r = img.harrisResponse()
        assertTrue(r.at(40, 20) < 0f, "response on a straight edge should be negative, got ${r.at(40, 20)}")
        assertTrue(abs(r.at(70, 70)) < 1e-4f, "response on flat ground should be ~0, got ${r.at(70, 70)}")
        assertTrue(r.at(20, 20) > 0f, "response at a corner should be positive, got ${r.at(20, 20)}")
    }

    @Test
    fun shiTomasiRespondsToTheApertureProblemCorrectly() {
        // A pure vertical edge is localisable in x but not y -> the minimum eigenvalue is ~0.
        val edge = Img.generate(48, 48) { x, _ -> if (x >= 24) 1f else 0f }
        val r = edge.shiTomasiResponse()
        assertTrue(r.at(24, 24) < 1e-4f, "an edge should have a near-zero min-eigenvalue, got ${r.at(24, 24)}")
        // ... whereas a corner is localisable in both.
        val corner = square(48, 48, 12, 12, 35, 35)
        assertTrue(corner.shiTomasiResponse().at(12, 12) > 1e-3f)
    }

    @Test
    fun fastFindsTheFourCornersOfASquare() {
        val img = square(80, 80, 20, 20, 59, 59)
        val corners = img.fastCorners(threshold = 0.2, contiguous = 9, minDistance = 10, maxCount = 20)
        assertTrue(corners.size in 4..8, "expected ~4 FAST corners, got ${corners.size}: $corners")
        for ((ex, ey) in listOf(20.0 to 20.0, 59.0 to 20.0, 20.0 to 59.0, 59.0 to 59.0)) {
            assertTrue(
                corners.any { hypot(it.x - ex, it.y - ey) < 4.0 },
                "no FAST corner within 4 px of ($ex, $ey); got $corners"
            )
        }
    }

    @Test
    fun fastFindsNothingOnAStraightEdgeOrFlatGround() {
        val edge = Img.generate(48, 48) { x, _ -> if (x >= 24) 1f else 0f }
        assertEquals(0, edge.fastCorners(threshold = 0.2).size, "FAST fired on a straight edge")
        assertEquals(0, Img.filled(48, 48, 1, 0.5f).fastCorners(threshold = 0.2).size, "FAST fired on flat ground")
    }

    // ==========================================================================================
    // Hough
    // ==========================================================================================

    @Test
    fun houghRecoversAKnownLineAtItsPolarCoordinates() {
        // Draw y = 20 (a horizontal line): normal angle theta = pi/2, rho = 20.
        val img = Img.zeros(96, 96, 1)
        for (x in 5 until 90) img.set(x, 20, 0, 1f)
        val lines = houghLines(img, thetaSteps = 360, threshold = 40, maxLines = 4)
        assertTrue(lines.isNotEmpty(), "Hough found no lines")
        val best = lines.first()
        assertEquals(PI / 2, best.theta, 0.03, "theta of a horizontal line")
        assertEquals(20.0, best.rho, 1.5, "rho of the line y = 20")
        assertTrue(best.votes >= 80, "expected ~85 votes, got ${best.votes}")
    }

    @Test
    fun houghRecoversTwoPerpendicularLinesAndClipsThemToTheImage() {
        val img = Img.zeros(96, 96, 1)
        for (x in 5 until 90) img.set(x, 30, 0, 1f)   // horizontal, rho = 30, theta = pi/2
        for (y in 5 until 90) img.set(60, y, 0, 1f)   // vertical,   rho = 60, theta = 0
        val lines = houghLines(img, thetaSteps = 360, threshold = 40, maxLines = 6)
        assertTrue(lines.size >= 2, "expected at least 2 lines, got ${lines.size}: $lines")
        assertTrue(
            lines.any { abs(it.theta - PI / 2) < 0.05 && abs(it.rho - 30.0) < 2.0 },
            "horizontal line not recovered: $lines"
        )
        assertTrue(
            lines.any { (it.theta < 0.05 || abs(it.theta - PI) < 0.05) && abs(abs(it.rho) - 60.0) < 2.0 },
            "vertical line not recovered: $lines"
        )
        for (l in lines.take(2)) {
            val seg = l.clipTo(96, 96)
            assertNotNull(seg, "line $l did not clip to the image")
            val (a, b) = seg
            assertTrue(hypot(a.x - b.x, a.y - b.y) > 50f, "clipped segment for $l is suspiciously short")
        }
    }

    @Test
    fun houghCirclesRecoversADrawnRing() {
        val img = Img.zeros(80, 80, 1)
        val cx = 40.0; val cy = 38.0; val r = 20.0
        for (i in 0 until 720) {
            val a = 2.0 * PI * i / 720
            val x = (cx + r * cos(a)).toInt()
            val y = (cy + r * sin(a)).toInt()
            if (x in 0 until 80 && y in 0 until 80) img.set(x, y, 0, 1f)
        }
        val circles = houghCircles(img, minRadius = 14, maxRadius = 26, radiusStep = 2, threshold = 0.6, maxCircles = 3)
        assertTrue(circles.isNotEmpty(), "Hough found no circles")
        val best = circles.first()
        assertTrue(hypot(best.x - cx, best.y - cy) < 3.0, "centre off by ${hypot(best.x - cx, best.y - cy)}: $best")
        assertTrue(abs(best.radius - r) <= 2.0, "radius off: $best")
    }

    // ==========================================================================================
    // Morphology
    // ==========================================================================================

    @Test
    fun erodeAndDilateShrinkAndGrowByExactlyTheRadius() {
        val img = square(40, 40, 10, 10, 29, 29) // 20x20 block
        val e = img.erode(2, StructuringElement.SQUARE)
        assertEquals(16 * 16, e.data.count { it > 0.5f }, "erode by 2 should leave a 16x16 block")
        val d = img.dilate(2, StructuringElement.SQUARE)
        assertEquals(24 * 24, d.data.count { it > 0.5f }, "dilate by 2 should give a 24x24 block")
    }

    @Test
    fun openRemovesSpecksAndCloseFillsHoles() {
        val img = square(48, 48, 12, 12, 35, 35).copy()
        img.set(3, 3, 0, 1f)   // isolated speck
        img.set(4, 3, 0, 1f)
        img.set(24, 24, 0, 0f) // pinhole in the block
        val opened = img.open(2)
        assertEquals(0f, opened.at(3, 3), "open should delete the 2-pixel speck")
        assertEquals(0f, opened.at(24, 24), "open (erode then dilate) leaves a pinhole open")
        assertEquals(1f, opened.at(20, 20), "open must not eat the block itself")
        val closed = img.close(1)
        assertEquals(1f, closed.at(24, 24), "close should fill the pinhole")
        assertEquals(1f, closed.at(3, 3), "close does not remove the speck (it is dilate-first)")
    }

    @Test
    fun morphologicalGradientOutlinesTheShape() {
        val img = square(40, 40, 12, 12, 27, 27)
        val g = img.morphologicalGradient(1)
        assertEquals(0f, g.at(20, 20), "interior should be flat")
        assertEquals(1f, g.at(12, 20), "boundary should be marked")
        assertEquals(0f, g.at(2, 2), "far background should be flat")
    }

    @Test
    fun discStructuringElementIsIsotropic() {
        // Dilating a single pixel by a disc of radius 5 produces the discrete disc's pixel count.
        val dot = Img.zeros(41, 41, 1)
        dot.set(20, 20, 0, 1f)
        val grown = dot.dilate(5, StructuringElement.DISC)
        var expected = 0
        for (dy in -5..5) for (dx in -5..5) if (dx * dx + dy * dy <= 25) expected++
        assertEquals(expected, grown.data.count { it > 0.5f })
        // A cross of radius 5 is the Manhattan ball: 2r^2 + 2r + 1 = 61 pixels.
        assertEquals(61, dot.dilate(5, StructuringElement.CROSS).data.count { it > 0.5f })
    }

    // ==========================================================================================
    // Connected components and distance transform
    // ==========================================================================================

    @Test
    fun connectedComponentsCountsKnownBlobsWithTheRightAreasAndCentroids() {
        val img = Img.zeros(100, 60, 1)
        // Three disjoint filled rectangles of known area and centre.
        val specs = listOf(
            Triple(intArrayOf(5, 5, 14, 14), 10 * 10, 9.5 to 9.5),
            Triple(intArrayOf(30, 20, 49, 29), 20 * 10, 39.5 to 24.5),
            Triple(intArrayOf(70, 40, 74, 44), 5 * 5, 72.0 to 42.0)
        )
        for ((box, _, _) in specs) {
            for (y in box[1]..box[3]) for (x in box[0]..box[2]) img.set(x, y, 0, 1f)
        }
        val cc = img.connectedComponents(8)
        assertEquals(3, cc.count, "expected 3 blobs, got ${cc.count}: ${cc.components}")
        for ((_, area, centre) in specs) {
            val c = cc.components.firstOrNull { it.area == area }
            assertNotNull(c, "no component with area $area; got ${cc.components.map { it.area }}")
            assertEquals(centre.first, c.centroidX, 1e-6, "centroid x of the area-$area blob")
            assertEquals(centre.second, c.centroidY, 1e-6, "centroid y of the area-$area blob")
            assertEquals(1.0, c.fillRatio, 1e-9, "a filled rectangle must have fillRatio 1")
        }
        assertEquals(200, cc.largest()!!.area)
    }

    @Test
    fun fourAndEightConnectivityDisagreeOnADiagonalTouch() {
        // Two pixels touching only at a corner: 1 blob under 8-connectivity, 2 under 4.
        val img = Img.zeros(8, 8, 1)
        img.set(2, 2, 0, 1f)
        img.set(3, 3, 0, 1f)
        assertEquals(1, img.connectedComponents(8).count)
        assertEquals(2, img.connectedComponents(4).count)
    }

    @Test
    fun connectedComponentsHandlesAUShapeThatNeedsLabelMerging() {
        // A "U": the two arms get different provisional labels and must merge at the base.
        val img = Img.zeros(20, 20, 1)
        for (y in 4..14) { img.set(4, y, 0, 1f); img.set(12, y, 0, 1f) }
        for (x in 4..12) img.set(x, 14, 0, 1f)
        val cc = img.connectedComponents(4)
        assertEquals(1, cc.count, "the U must be one component, got ${cc.count}")
        assertEquals(11 + 11 + 7, cc.components[0].area)
    }

    @Test
    fun distanceTransformOfADiscPeaksAtTheCentreWithTheRadius() {
        val r = 15.0
        val img = disc(64, 64, 32.0, 32.0, r)
        val dt = img.distanceTransform()
        // The centre's distance to the nearest background pixel is r+1 (the first pixel outside).
        assertEquals(r + 1.0, dt.at(32, 32).toDouble(), 1.0, "centre distance")
        assertEquals(0f, dt.at(2, 2), "background must be zero")
        // Distance falls off linearly along a radius.
        for (d in 0..12) {
            val expected = (r + 1.0) - d
            assertEquals(expected, dt.at(32 + d, 32).toDouble(), 1.5, "distance $d px from the centre")
        }
    }

    @Test
    fun distanceTransformIsExactlyEuclideanForASinglePixelHole() {
        // One background pixel in a sea of foreground: every distance is the true Euclidean one.
        val img = Img.filled(21, 21, 1, 1f)
        img.set(10, 10, 0, 0f)
        val dt = img.distanceTransform()
        for (y in 0 until 21) for (x in 0 until 21) {
            val expected = hypot((x - 10).toDouble(), (y - 10).toDouble())
            assertEquals(expected, dt.at(x, y).toDouble(), 1e-4, "distance at ($x, $y)")
        }
    }

    @Test
    fun traceContoursFollowsASquaresBoundary() {
        val img = square(48, 48, 12, 12, 35, 35)
        val contours = img.traceContours(minPerimeter = 8)
        assertEquals(1, contours.size, "expected one contour, got ${contours.size}")
        val pts = contours[0]
        assertTrue(pts.size >= 80, "a 24x24 square boundary should be ~92 pixels, got ${pts.size}")
        for (p in pts) {
            val x = p.x.toInt(); val y = p.y.toInt()
            val onBoundary = (x == 12 || x == 35 || y == 12 || y == 35) && x in 12..35 && y in 12..35
            assertTrue(onBoundary, "contour point ($x, $y) is not on the square boundary")
        }
        // ... and after simplification the square is a handful of points.
        val simple = simplifyPolyline(pts, 1.0)
        assertTrue(simple.size in 4..10, "RDP should reduce a square to ~5 points, got ${simple.size}")
    }

    @Test
    fun contourFLinesCarryTheGeometry() {
        val img = square(48, 48, 12, 12, 35, 35)
        val flines = img.toContourFLines(minPerimeter = 8, simplifyTolerance = 1.0)
        assertEquals(1, flines.size)
        val bounds = flines[0].fast2DBounds()
        assertEquals(12.0, bounds.x, 1.0)
        assertEquals(12.0, bounds.y, 1.0)
        assertEquals(23.0, bounds.w, 1.5)
        assertEquals(23.0, bounds.h, 1.5)
    }

    @Test
    fun colormapProducesRgbWithTheRampEndpoints() {
        val ramp = Img.generate(32, 4) { x, _ -> x / 31f }
        val cm = ramp.colormap("viridis")
        assertEquals(3, cm.channels)
        // viridis(0) is dark purple, viridis(1) is yellow.
        assertTrue(cm.at(0, 0, 2) > cm.at(0, 0, 1), "viridis(0) should be more blue than green")
        assertTrue(cm.at(31, 0, 0) > 0.8f && cm.at(31, 0, 1) > 0.7f && cm.at(31, 0, 2) < 0.4f, "viridis(1) not yellow")
    }

    @Test
    fun isoContourFLinesTraceADiscsBoundaryAtTheRightRadius() {
        // A smooth radial field; the iso-contour at 0.5 should be a circle of a known radius.
        val cx = 32.0; val cy = 32.0
        val field = Img.generate(64, 64) { x, y ->
            val d = hypot(x - cx, y - cy)
            (1.0 / (1.0 + kotlin.math.exp((d - 16.0) * 2.0))).toFloat()
        }
        val lines = field.isoContourFLines(0.5, cells = 64)
        assertTrue(lines.isNotEmpty(), "no iso-contour extracted")
        var n = 0
        var worst = 0.0
        for (l in lines) for (node in l.nodes) {
            val d = hypot(node.to.x - cx, node.to.y - cy)
            worst = maxOf(worst, abs(d - 16.0)); n++
        }
        assertTrue(n > 40, "iso-contour has only $n points")
        assertTrue(worst < 1.5, "iso-contour deviates from radius 16 by up to $worst px")
    }
}
