package afield.graphics.scene.reading

import afield.geometry.path.FLine
import afield.graphics.text.*
import afield.graphics.text.slug.SlugPath
import afield.math.linalg.*
import kotlin.math.*
import kotlin.test.*

class ReadingRectletUnionTest {
    private fun squaredDistance(a: ReadingFramePoint, b: ReadingFramePoint) =
        (a.x - b.x).pow(2) + (a.y - b.y).pow(2)

    @Test fun unionDropsInternalEdgesAndPreservesConcaveSteps() {
        val shape = readingRectletUnion(listOf(
            ReadingRectlet(0.0, 1.0, 4.0, 2.0, 0),
            ReadingRectlet(0.0, 0.0, 1.0, 1.2, 1)), 0.0, true)
        assertEquals(1, shape.contours.size)
        assertEquals(6, shape.contours.single().size)
        val onBuriedEdge = ReadingFramePoint(1.0, 1.1)
        assertTrue(shape.contains(onBuriedEdge))
        assertTrue(squaredDistance(shape.project(onBuriedEdge, DiagramTextSite.BOUNDARY), onBuriedEdge) > .009)
        assertEquals(onBuriedEdge, shape.project(onBuriedEdge, DiagramTextSite.CLOSEST))
        val inConcavity = ReadingFramePoint(2.5, .95)
        assertFalse(shape.contains(inConcavity))
        assertEquals(ReadingFramePoint(2.5, 1.0), shape.project(inConcavity, DiagramTextSite.BOUNDARY))
    }

    @Test fun smallStepsSnapOutwardsWhileLargeStepsAndSeparatedRowsRemain() {
        fun shape(shortRight: Double, shortLeft: Double = 0.0, lowerTop: Double = 1.2, merge: Boolean = true) =
            readingRectletUnion(listOf(ReadingRectlet(0.0, 1.0, 2.0, 2.0, 0),
                ReadingRectlet(shortLeft, 0.0, shortRight, lowerTop, 1)), .025, merge)
        val snapped = shape(1.97, .01)
        assertEquals(4, snapped.contours.single().size)
        assertEquals(2.025, snapped.rectlets[1].right, 1e-12)
        assertEquals(-.025, snapped.rectlets[1].left, 1e-12)
        assertEquals(6, shape(1.94).contours.single().size)
        assertEquals(6, shape(1.97, merge = false).contours.single().size)
        val separated = shape(1.97, lowerTop = .8)
        assertEquals(2, separated.contours.size)
        assertEquals(1.995, separated.rectlets[1].right, 1e-12)
    }

    @Test fun blankRowsDoNotSnapAndHolesKeepTheirOwnExposedBoundary() {
        val blank = readingRectletUnion(listOf(ReadingRectlet(0.0, 1.0, 2.0, 2.0, 0),
            ReadingRectlet(0.0, 0.0, 1.97, 1.2, 2)), .025, true)
        assertEquals(1.995, blank.rectlets[1].right, 1e-12)
        val hole = readingRectletUnion(listOf(
            ReadingRectlet(0.0, 0.0, 3.0, 1.0, 0), ReadingRectlet(0.0, 2.0, 3.0, 3.0, 1),
            ReadingRectlet(0.0, 1.0, 1.0, 2.0, 2), ReadingRectlet(2.0, 1.0, 3.0, 2.0, 3)), 0.0, false)
        assertEquals(2, hole.contours.size)
        val center = ReadingFramePoint(1.5, 1.5)
        assertFalse(hole.contains(center))
        assertEquals(.25, squaredDistance(center, hole.project(center, DiagramTextSite.BOUNDARY)), 1e-12)
    }

    private fun player(graph: ReadingDiagram): ReadingDiagramPlayer {
        val font = Typefaces.named("Serif", 100)
        val atlas = SlugPath.decode(requireNotNull(javaClass.getResource("/afield/slug/curved-hole.bin")).readBytes())
        val runs = graph.texts.associateWith { run ->
            val layout = layoutText(run.text, font)
            ReadingText(run.text, layout.advanceWidth.toFloat(), layout.toFLine(), atlas, layout)
        }
        return ReadingDiagramPlayer(graph, font, runs.mapKeys { it.key.id }, diagramScore(graph, 10.0) {}, runs)
            .apply { wind = 0f; gravity = 0f; playback.playing = false }
    }

    @Test fun liveModesShareOutlineAndPortAfterRotationScaleAndClearanceChanges() {
        lateinit var border: DiagramTextFrame
        val graph = readingDiagram {
            val run = text("text", "A considerably wider line\nHI", point("p", 0, 0)) {
                orientation.rotateY(.4f)
                rotation = .3f
            }
            border = textFrame("frame", run, standoff = .015f)
        }
        val p = player(graph)
        val port = border.attach()
        fun checkPort() {
            val f = p.frame()
            val run = graph.text("text")
            val last = f.measure(run.line(1))
            val toward = f.textPoint(run, last.ink.right, last.ink.centerY)
                .add(f.textDirection(run, .1f, 0f, .03f))
            val point = port.resolve(f, toward)
            val line = f.path(border.path)
            var first: Vector3f? = null
            var previous: Vector3f? = null
            var closest = Float.POSITIVE_INFINITY
            fun edge(a: Vector3f, b: Vector3f) {
                val ab = Vector3f(b).sub(a)
                val t = if (ab.lengthSquared() > 1e-12f) Vector3f(point).sub(a).dot(ab) / ab.lengthSquared() else 0f
                closest = min(closest, Vector3f(a).fma(t.coerceIn(0f, 1f), ab).distance(point))
            }
            for (node in line.nodes) when (node) {
                is FLine.MoveTo -> { first = node.to; previous = node.to }
                is FLine.LineTo -> { edge(requireNotNull(previous), node.to); previous = node.to }
                is FLine.Close -> edge(requireNotNull(previous), requireNotNull(first))
                else -> fail("Unexpected curved union boundary")
            }
            assertTrue(closest < 1e-5f, "Port detached from the visible union: $closest")
        }
        checkPort()
        border.mode = DiagramTextFrameMode.LINE_UNION
        assertTrue(border.geometry(p.frame()).contours.sumOf { it.size } > 4)
        checkPort()
        border.standoff = .04f
        graph.text("text").scale = 1.8f
        checkPort()
        graph.text("text").scale = 0f
        border.standoff = 0f
        assertTrue(port.resolve(p.frame(), Vector3f(1f)).isFinite())
    }

    @Test fun movingAndTurningTextReusesUnionButScaleAndPaddingInvalidateIt() {
        lateinit var border: DiagramTextFrame
        val graph = readingDiagram {
            border = textFrame("frame", text("text", "wide\nHI", point("p", 0, 0)),
                mode = DiagramTextFrameMode.LINE_UNION)
        }
        val p = player(graph)
        val before = border.geometry(p.frame())
        p.state.x[0] += 1f
        graph.text("text").orientation.rotateY(.3f)
        assertSame(before, border.geometry(p.frame()))
        graph.text("text").scale = 2f
        assertNotSame(before, border.geometry(p.frame()))
        val scaled = border.geometry(p.frame())
        border.standoff = .06f
        assertNotSame(scaled, border.geometry(p.frame()))
    }
}
