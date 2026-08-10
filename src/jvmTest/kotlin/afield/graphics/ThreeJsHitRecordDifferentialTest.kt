package afield.graphics

import afield.stdlib.filled
import afield.stdlib.fline
import afield.stdlib.label
import afield.stdlib.pointed
import afield.stdlib.stroked
import afield.stdlib.vec
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three.js viewer's half of the FLine hit record — **executing the viewer's own JavaScript**.
 *
 * Until this file the three.js click path reported a point and the nearest vertex, and nothing about
 * *which element* was hit; every one of the human's 2026-08-09 `panel-*` documents renders through this
 * viewer, so the 2D work did not reach a single one of them. The viewer now names the element, and this
 * is the test that says so — it lifts the `fline-hit-picking` region out of `threejs_viewer.html` with
 * [ViewerJavaScript] and runs it on GraalJS against topology produced by the **real** Kotlin producer,
 * [flineTopologyJson], the same string the interactive SVG carries in `data-fline-elements`.
 *
 * Why it is arranged this way, given the lesson of `ThreeJsEditableFillParityTest`: both sides of an
 * assertion computed in Kotlin agree with themselves forever. Here the *answer* comes from the
 * viewer's JavaScript and the *expectation* comes from the geometry the figure was built from — a click
 * placed 30% along a segment by construction has to come back as `u = 0.3`.
 *
 * The kind needs no tolerance in 3D, which is the one respect in which the viewer has it easier than
 * the SVG: three.js has already told the caller whether the ray hit the line geometry, the fill or the
 * point sprites, so `role` is an input here rather than a guess. What the region has to work out is
 * *which* element of that kind, and where on it.
 *
 * See also `FLineHitRecordProbeTest`, which runs the *other* implementation (`sliders.js`, under node)
 * and additionally holds the two to each other on a planar face.
 */
class ThreeJsHitRecordDifferentialTest {

    private fun session() = ViewerJavaScript.Session.open(
        regionName = "fline-hit-picking",
        exports = ViewerJavaScript.KNOWN_REGIONS.getValue("fline-hit-picking"),
    )

    /** A wall panel in 3D: a quad tilted out of every axis plane, so the plane projection is exercised. */
    private fun panel(): FLine = fline {
        moveTo(0.0, 0.0, 0.0)
        lineTo(100.0, 10.0, 40.0)
        lineTo(110.0, 90.0, 44.0)
        lineTo(5.0, 80.0, 2.0)
        close()
        label = "panel"
        filled = true
        stroked = true
    }

    private fun corner(line: FLine, index: Int) = when (val node = line.nodes[index]) {
        is FLine.MoveTo -> node.to
        is FLine.LineTo -> node.to
        is FLine.CubicTo -> node.to
        is FLine.Close -> error("node $index is a close")
    }

    private fun json(line: FLine) = flineTopologyJson(line)

    @Test
    fun `a hit on the line geometry names the segment and where along it`() {
        val line = panel()
        session().use { js ->
            val topology = js.fromJson(json(line))
            val a = corner(line, 1)
            val b = corner(line, 2)
            // 30% along the second segment, in 3D.
            val point = js.fromJson(
                "[${a.x + 0.3 * (b.x - a.x)}, ${a.y + 0.3 * (b.y - a.y)}, ${a.z + 0.3 * (b.z - a.z)}]"
            )
            val hit = js.call("flinePickClassify", topology, "edge", point)
            assertEquals("edge", hit.getMember("kind").asString())
            assertEquals(2, hit.getMember("nodeIndex").asInt())
            assertEquals(1, hit.getMember("elementIndex").asInt())
            assertEquals(0, hit.getMember("subPathIndex").asInt())
            assertTrue(
                abs(hit.getMember("u").asDouble() - 0.3) < 1e-6,
                "u came back as ${hit.getMember("u").asDouble()}"
            )
            val on = hit.getMember("onElement")
            assertTrue(
                abs(on.getArrayElement(2).asDouble() - (a.z + 0.3 * (b.z - a.z))) < 1e-4,
                "the point on the element lost its z: ${on.getArrayElement(2).asDouble()}"
            )
        }
    }

    @Test
    fun `a hit on the fill names the face and a bilinear position on it`() {
        val line = panel()
        val p = (0..3).map { corner(line, it) }
        val u = 0.25
        val v = 0.75
        val x = (1 - u) * (1 - v) * p[0].x + u * (1 - v) * p[1].x + u * v * p[2].x + (1 - u) * v * p[3].x
        val y = (1 - u) * (1 - v) * p[0].y + u * (1 - v) * p[1].y + u * v * p[2].y + (1 - u) * v * p[3].y
        val z = (1 - u) * (1 - v) * p[0].z + u * (1 - v) * p[1].z + u * v * p[2].z + (1 - u) * v * p[3].z
        session().use { js ->
            val hit = js.call("flinePickClassify", js.fromJson(json(line)), "face", js.fromJson("[$x, $y, $z]"))
            assertEquals("face", hit.getMember("kind").asString())
            assertEquals(4, hit.getMember("nodeIndex").asInt())
            assertEquals(0, hit.getMember("elementIndex").asInt())
            assertEquals("bilinear", hit.getMember("faceCoordinates").asString())
            // The bilinear surface of a skewed 3D quad is not flat, so the constructed point is a little
            // off the polygon's own plane; the projection absorbs that, and 1e-3 is a real floor — the
            // same assertion at 1e-6 fails, and at 1e-1 it would prove nothing about a 110-unit panel.
            assertTrue(abs(hit.getMember("u").asDouble() - u) < 1e-3, "u = ${hit.getMember("u").asDouble()}")
            assertTrue(abs(hit.getMember("v").asDouble() - v) < 1e-3, "v = ${hit.getMember("v").asDouble()}")
        }
    }

    @Test
    fun `a hit on the point sprites names the node`() {
        val line = panel()
        line.pointed = true
        val target = corner(line, 2)
        session().use { js ->
            val hit = js.call(
                "flinePickClassify",
                js.fromJson(json(line)),
                "point",
                js.fromJson("[${target.x + 0.01}, ${target.y - 0.01}, ${target.z}]")
            )
            assertEquals("point", hit.getMember("kind").asString())
            assertEquals(2, hit.getMember("nodeIndex").asInt())
            assertEquals(0, hit.getMember("subPathIndex").asInt())
        }
    }

    /**
     * The `role` three.js hands in is authoritative, and a figure with no topology still answers.
     *
     * A hover-only interactive object carries no topology (there is nothing to name), and a browser
     * holding a cached viewer sends none either. Both have to degrade to "this line, no element" rather
     * than to an exception in a click handler.
     */
    @Test
    fun `no topology still yields a usable record`() {
        session().use { js ->
            val hit = js.call("flinePickClassify", null, "face", js.fromJson("[1,2,3]"))
            assertEquals("face", hit.getMember("kind").asString())
            assertEquals(-1, hit.getMember("nodeIndex").asInt())
            assertEquals("none", hit.getMember("faceCoordinates").asString())
            assertTrue(hit.getMember("onElement").isNull)
        }
    }

    /**
     * A 28-gon disc — `PetriVisual`'s clickable shape — gets bounding-box coordinates, and says so.
     *
     * The point of the assertion is the label, not the numbers: `(u, v)` on a face nobody chose is a
     * different quantity from `(u, v)` on a panel, and the record has to distinguish them.
     */
    @Test
    fun `a face that is not a quad reports bounding-box coordinates`() {
        val disc = fline {
            for (i in 0 until 28) {
                val a = 2.0 * Math.PI * i / 28
                if (i == 0) moveTo(100.0 + 26.0 * cos(a), 100.0 + 26.0 * sin(a), 5.0)
                else lineTo(100.0 + 26.0 * cos(a), 100.0 + 26.0 * sin(a), 5.0)
            }
            close()
            filled = true
        }
        session().use { js ->
            val hit = js.call("flinePickClassify", js.fromJson(json(disc)), "face", js.fromJson("[100, 100, 5]"))
            assertEquals("face", hit.getMember("kind").asString())
            assertEquals("bbox", hit.getMember("faceCoordinates").asString())
            assertTrue(abs(hit.getMember("u").asDouble() - 0.5) < 1e-3)
            assertTrue(abs(hit.getMember("v").asDouble() - 0.5) < 1e-3)
        }
    }

    /**
     * A degenerate face — every corner in one place — has no plane, and the region refuses it.
     *
     * `none` and `NaN`, not a number. This is the case that would otherwise return whatever the first
     * division by zero produced.
     */
    @Test
    fun `a face with no plane refuses to give coordinates`() {
        val degenerate = fline {
            moveTo(10.0, 10.0, 10.0)
            lineTo(10.0, 10.0, 10.0)
            lineTo(10.0, 10.0, 10.0)
            close()
            filled = true
        }
        session().use { js ->
            val hit = js.call("flinePickClassify", js.fromJson(json(degenerate)), "face", js.fromJson("[10,10,10]"))
            assertEquals("face", hit.getMember("kind").asString())
            assertEquals("none", hit.getMember("faceCoordinates").asString())
            assertTrue(hit.getMember("u").asDouble().isNaN(), "u should refuse, got ${hit.getMember("u")}")
        }
    }

    /**
     * A **non-convex** quad, which is where a bilinear inverse stops being trustworthy.
     *
     * A dart — one corner pulled back past the diagonal — folds the bilinear patch, so for some points
     * inside the polygon the Newton solve either diverges or lands on the wrong branch. The region checks
     * its own answer, so what comes back is either a *verified* bilinear coordinate or the labelled
     * bounding-box fallback. What must never happen is `bilinear` with a coordinate that does not map back
     * to where the click was, and that is what this asserts — over every interior point of a grid, not at
     * one lucky point.
     *
     * The measurement corrected an assumption worth recording: the checked solve is *robust*, not
     * fragile. On a finer 2-unit grid over this same dart, 939 of 941 interior points get a verified
     * bilinear coordinate and only two — the extreme corners either side of the notch — fall back. The
     * coarse grid below has proportionally more near-corner points, hence its 47/10.
     *
     * Measured 2026-08-10 over the 57 interior points of a 19x19 grid: **47 verified bilinear, 10
     * fallbacks**, and not one claimed coordinate failed the reproduce check. The floors below sit just
     * under those two numbers, so a regression that stops verifying — or stops falling back, which would
     * mean the fold is no longer being exercised — fails.
     */
    @Test
    fun `a non-convex quad never claims a bilinear coordinate it cannot reproduce`() {
        val dart = fline {
            moveTo(0.0, 0.0, 0.0)
            lineTo(100.0, 0.0, 0.0)
            lineTo(20.0, 20.0, 0.0)   // pulled back inside the triangle: the quad is non-convex
            lineTo(0.0, 100.0, 0.0)
            close()
            filled = true
        }
        val corners = (0..3).map { corner(dart, it) }

        /** Even-odd, so only points the face really contains are judged. */
        fun inside(x: Double, y: Double): Boolean {
            var inside = false
            var j = corners.size - 1
            for (i in corners.indices) {
                val xi = corners[i].x.toDouble()
                val yi = corners[i].y.toDouble()
                val xj = corners[j].x.toDouble()
                val yj = corners[j].y.toDouble()
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }

        var bilinear = 0
        var bbox = 0
        session().use { js ->
            val topology = js.fromJson(json(dart))
            for (i in 1..19) {
                for (j in 1..19) {
                    val x = i * 100.0 / 20.0
                    val y = j * 100.0 / 20.0
                    if (!inside(x, y)) continue
                    val hit = js.call("flinePickClassify", topology, "face", js.fromJson("[$x, $y, 0]"))
                    when (val mode = hit.getMember("faceCoordinates").asString()) {
                        "bilinear" -> {
                            bilinear++
                            val u = hit.getMember("u").asDouble()
                            val v = hit.getMember("v").asDouble()
                            val bx = (1 - u) * (1 - v) * corners[0].x + u * (1 - v) * corners[1].x +
                                u * v * corners[2].x + (1 - u) * v * corners[3].x
                            val by = (1 - u) * (1 - v) * corners[0].y + u * (1 - v) * corners[1].y +
                                u * v * corners[2].y + (1 - u) * v * corners[3].y
                            assertTrue(
                                abs(bx - x) < 0.02 && abs(by - y) < 0.02,
                                "claimed bilinear ($u, $v) for ($x, $y) but the map returns ($bx, $by)"
                            )
                        }
                        "bbox" -> bbox++
                        else -> error("unexpected face-coordinate mode `$mode` at ($x, $y)")
                    }
                }
            }
        }
        println("non-convex dart, interior grid points: $bilinear verified bilinear, $bbox fell back")
        assertTrue(bilinear >= 40, "only $bilinear interior points got a verified bilinear coordinate")
        assertTrue(bbox >= 5, "only $bbox fell back — the fold is no longer being exercised")
    }
}
