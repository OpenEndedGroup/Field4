package afield.ui.notation

import afield.geometry.path.FLine
import afield.geometry.path.byAtomizing
import afield.geometry.path.byWiggling
import afield.geometry.path.catmullRomCentripetal
import afield.graphics.color.cssHsla
import afield.graphics.render.color
import afield.math.linalg.Vec2
import afield.math.linalg.vec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The madp line demo's vocabulary as `eachStroke` boxes use it ([StrokeVerbs]), the FLine verbs it
 * added to the shared geometry, and the frame the stroke layer draws ([StrokeLayerFrames]).
 */
class StrokeVerbsTest {
    private val v = StrokeVerbs()
    private val zigzag = listOf(vec(0, 0), vec(10, 0), vec(10, 30), vec(60, 30))

    private fun d(a: Vec2, b: Vec2) = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())

    @Test
    fun resampleSpacesPointsEvenlyAlongTheCurveAndKeepsItsEnds() {
        val r = v.resample(zigzag, 10)
        assertEquals(10, r.size)
        assertEquals(zigzag.first(), r.first())
        assertEquals(zigzag.last().x, r.last().x, 1e-4f)
        val step = v.length(zigzag) / 9
        for (i in 1 until r.size) assertEquals(step, d(r[i - 1], r[i]), 0.6, "chord $i (corners cut it short)")
        assertEquals(v.length(zigzag), v.length(zigzag), 1e-9)
    }

    @Test
    fun curveMathsDisplaceByTheOtherCurvesShapeAboutItsCentroid() {
        val line = listOf(vec(0, 0), vec(100, 0))
        val bump = listOf(vec(0, -10), vec(0, 10))
        val up = v.add(line, bump)
        val down = v.subtract(line, bump)
        assertEquals(-10f, up[0].y, 1e-4f)
        assertEquals(10f, up[1].y, 1e-4f)
        assertEquals(10f, down[0].y, 1e-4f)
        assertEquals(-10f, down[1].y, 1e-4f)
        val doubled = v.scale(line, 2.0)
        assertEquals(-50f, doubled[0].x, 1e-4f)
        assertEquals(150f, doubled[1].x, 1e-4f)
        // Projected onto a vertical shape, a horizontal one collapses onto its centroid.
        v.project(line, bump).forEach { assertEquals(50f, it.x, 1e-4f); assertEquals(0f, it.y, 1e-4f) }
    }

    @Test
    fun transformToLandsTheEndsWhereAskedAndKeepsTheShape() {
        val moved = v.transformTo(zigzag, vec(100, 100), vec(100, 100 + 2 * d(zigzag.first(), zigzag.last())))
        assertEquals(100f, moved.first().x, 1e-3f)
        assertEquals(100f, moved.first().y, 1e-3f)
        assertEquals(100f, moved.last().x, 1e-3f)
        assertEquals(2 * v.length(zigzag), v.length(moved), 1e-3, "scaled by two, rigidly")
    }

    @Test
    fun theCentripetalSplinePassesThroughEveryPoint() {
        val f = catmullRomCentripetal(zigzag)
        assertEquals(zigzag.size, f.nodes.size)
        f.nodes.drop(1).forEachIndexed { i, node ->
            assertTrue(node is FLine.CubicTo)
            assertEquals(zigzag[i + 1].x, node.to.x, 1e-4f)
            assertEquals(zigzag[i + 1].y, node.to.y, 1e-4f)
        }
        assertEquals(2, catmullRomCentripetal(zigzag.take(2)).nodes.size, "two points: a straight line")
    }

    @Test
    fun atomizeMakesOneSubpathPerSegmentCarryingItsNodesProperties() {
        val line = v.polyline(zigzag)
        line.nodes[2].properties["color"] = cssHsla(0, 1, 0.5)
        val atoms = line.byAtomizing()
        assertEquals(6, atoms.nodes.size)
        assertEquals(3, atoms.nodes.count { it is FLine.MoveTo })
        assertEquals(cssHsla(0, 1, 0.5), atoms.nodes[3].properties["color"])
        assertEquals(atoms.nodes[2].to, atoms.nodes[1].to, "the second segment starts where the first ended")
    }

    @Test
    fun wiggleIsDeterministicBoundedAndMovesWithPhase() {
        val line = v.polyline(v.resample(zigzag, 20))
        val a = line.byWiggling(8.0, 0.05, 0.0)
        val b = line.byWiggling(8.0, 0.05, 0.0)
        val c = line.byWiggling(8.0, 0.05, 1.3)
        a.nodes.indices.forEach { i ->
            assertEquals(a.nodes[i].to, b.nodes[i].to)
            assertTrue(a.nodes[i].to.distance(line.nodes[i].to) <= 8f * 1.5f)
        }
        assertTrue(a.nodes.indices.any { a.nodes[it].to.distance(c.nodes[it].to) > 0.5f })
    }

    @Test
    fun heldNoiseHoldsBetweenStepsAndDriftNoiseDoesNot() {
        assertEquals(v.heldNoise(1.0, 0.21, 4.0), v.heldNoise(1.0, 0.24, 4.0))
        assertNotEquals(v.heldNoise(1.0, 0.24, 4.0), v.heldNoise(1.0, 0.26, 4.0))
        assertNotEquals(v.driftNoise(1.0, 0.21, 4.0), v.driftNoise(1.0, 0.24, 4.0))
        assertEquals(1.0, v.triWave(0.0), 1e-12)
        assertEquals(-1.0, v.triWave(0.5), 1e-12)
        assertEquals(0.0, v.perlin2D(3.0, 4.0), 1e-12, "Perlin noise is zero on the lattice")
    }

    @Test
    fun cssHslaIsTheBrowsersColour() {
        val red = cssHsla(0, 1, 0.5, 0.5)
        assertEquals(1f, red.x, 1e-6f); assertEquals(0f, red.y, 1e-6f); assertEquals(0.5f, red.w, 1e-6f)
        val green = cssHsla(480, 1, 0.25)   // 480 wraps to 120
        assertEquals(0f, green.x, 1e-6f); assertEquals(0.5f, green.y, 1e-6f); assertEquals(0f, green.z, 1e-6f)
        val sky = cssHsla(205, 0.96, 0.68)  // hsl(205, 96%, 68%) is rgb(97, 185, 251)
        assertEquals(97, Math.round(sky.x * 255)); assertEquals(185, Math.round(sky.y * 255)); assertEquals(251, Math.round(sky.z * 255))
    }

    @Test
    fun theFrameCarriesPathsColoursDotsAndOvershootTicks() {
        val line = v.polyline(listOf(vec(0, 0), vec(10, 0)))
        line.color = cssHsla(0, 1, 0.5)
        line.overshoot = 5.0
        val beads = v.dots(listOf(vec(1, 1), vec(2, 2)), radius = 4.5)
        val tinted = FLine().apply { with(v) { segment(0, 0, 0, 10, color = cssHsla(120, 1, 0.5)) } }
        val frame = Json.parseToJsonElement(StrokeLayerFrames.encode(0.5, listOf(listOf(line, beads, tinted) to 2.0))).jsonObject
        assertEquals(0.5, frame["trail"]!!.jsonPrimitive.double)
        val items = frame["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, items.size)

        val first = items[0]
        assertEquals("rgba(255,0,0,1)", first["c"]!!.jsonPrimitive.content)
        assertEquals(listOf(0.0, 0.0, 0.0, 1.0, 10.0, 0.0), nums(first["d"]!!.jsonArray))
        assertEquals(listOf(0.0, 0.0, -5.0, 0.0, 10.0, 0.0, 15.0, 0.0), nums(first["o"]!!.jsonArray), "a tick back from the start, one on from the end")

        val dots = items[1]
        assertEquals(1, dots["p"]!!.jsonPrimitive.int)
        assertEquals(0, dots["s"]!!.jsonPrimitive.int)
        assertEquals(4.5, dots["pr"]!!.jsonPrimitive.double, "radius is pointSize * 3")

        assertEquals(listOf("0", "rgba(0,255,0,1)"), items[2]["k"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun closestOutputLeavesOutIgnoredLines() {
        val seen = v.polyline(listOf(vec(1, 2), vec(3, 4)))
        val guide = v.polyline(listOf(vec(100, 100), vec(200, 200))).also { it.ignore = true }
        val positions = StrokeLayerFrames.indexedPositions(listOf(seen, guide)).toList()
        assertEquals(listOf(1f, 2f, 3f, 4f), positions)
        assertFalse(seen.ignore)
    }

    private fun nums(a: JsonArray) = a.map { it.jsonPrimitive.double }
}
