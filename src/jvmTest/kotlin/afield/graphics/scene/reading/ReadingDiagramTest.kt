package afield.graphics.scene.reading

import afield.geometry.path.FLine
import afield.graphics.text.Typefaces
import afield.graphics.text.slug.SlugPath
import afield.math.linalg.*
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.*

class ReadingDiagramTest {
    @Test
    fun contoursNeverCreateBridgesAndClosedPointsAreSafe() {
        val line = FLine().moveTo(0, 0).lineTo(1, 0).moveTo(10, 0).lineTo(11, 0)
        val g = DiagramGeometry()
        g.stroke(line)
        assertEquals(2, g.segments.size)
        assertTrue(g.segments.all { it.a.p.distance(it.b.p) == 1f && it.first && it.last })
        g.clear()
        val closed = FLine().moveTo(0, 0).lineTo(1, 0).lineTo(1, 1).close()
        g.stroke(closed)
        g.markers(closed)
        assertEquals(3, g.segments.size)
        assertEquals(3, g.points.size)
        assertTrue(g.segments.none { it.first || it.last })
    }

    @Test
    fun cubicSamplingTracksColorWidthAndArclength() {
        val line =
            FLine()
                .moveTo(0, 0)
                .cubicTo(Vector3f(0f, 1f, 0f), Vector3f(1f, 1f, 0f), Vector3f(1f, 0f, 0f))
        line.nodes.first().properties["color"] = Vector4f(1f, 0f, 0f, 1f)
        line.nodes.last().properties["color"] = Vector4f(0f, 0f, 1f, .5f)
        line.nodes.first().properties["thickness"] = .001f
        line.nodes.last().properties["thickness"] = .003f
        val g = DiagramGeometry(.0005f)
        g.stroke(line)
        assertTrue(g.segments.size > 8)
        assertEquals(Vector3f(0f, 0f, 0f), g.segments.first().a.p)
        assertEquals(Vector3f(1f, 0f, 0f), g.segments.last().b.p)
        assertTrue(abs(g.segments.last().s1 - 2f) < .002f)
        assertEquals(.001f, g.segments.first().a.width)
        assertEquals(.003f, g.segments.last().b.width)
        assertEquals(.5f, g.segments.last().b.color.w)
        val bytes = ByteBuffer.allocate(g.segments.size * 128)
        g.writeSegments(bytes)
        assertEquals(bytes.capacity(), bytes.position())
    }

    @Test
    fun zeroLengthAndHiddenStrokesProduceNoTriangles() {
        val g = DiagramGeometry()
        g.stroke(FLine().moveTo(1, 1).lineTo(1, 1))
        g.stroke(FLine().moveTo(0, 0).lineTo(1, 1), DiagramInk(drawFrom = .5f, drawTo = .5f))
        assertTrue(g.segments.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            g.stroke(FLine().moveTo(0, 0).lineTo(Float.NaN, 0))
        }
    }

    private fun model(): ReadingDiagramPlayer {
        // CPU tests do not sample the atlas. Native rendering prepares the actual run below.
        val atlas =
            SlugPath.decode(
                requireNotNull(javaClass.getResource("/afield/slug/curved-hole.bin")).readBytes(),
            )
        val font = Typefaces.named("Serif", 100)
        val graph = readingDiagram {
            val root = point("root", -.8, .4, pinned = true)
            val baseline = point("baseline", 0, 0)
            link(root, baseline)
            val sentence = text("sentence", "one breath and another breath", baseline)
            val mark = underline("underline", sentence.word("breath", occurrence = 1))
            dot("beside", sentence.word("breath").right.offset(x = .02f))
            dot("on-line", mark.at(.65f))
        }
        val text = graph.texts.single().text
        val layout = afield.graphics.text.layoutText(text, font)
        val shape = ReadingText(text, layout.advanceWidth.toFloat(), FLine(), atlas, layout)
        val score =
            diagramScore(graph, 10.0) {
                enter(graph.text("sentence"), at = 0.0, seconds = 1.0)
                drawIn(graph.path("underline"), at = 1.0, seconds = 1.0)
                exitAll(at = 8.0, seconds = 2.0)
            }
        return ReadingDiagramPlayer(graph, font, mapOf(text to shape), score).apply {
            gravity = 0f
            wind = 0f
            playback.playing = false
        }
    }

    @Test
    fun fixedStepSpringsStayAtRestAndClockDoesNotLoseElapsedTime() {
        val p = model()
        repeat(600) { p.update(1.0 / 60) }
        for ((i, node) in p.diagram.particles.withIndex()) {
            assertTrue(abs(p.state.x[i] - node.rest.x) < 1e-5f)
            assertTrue(abs(p.state.y[i] - node.rest.y) < 1e-5f)
            assertTrue(p.state.z[i].isFinite())
        }
        assertEquals(0.0, p.playback.time)
        assertTrue(p.physicsSteps >= 1199)
        p.playback.playing = true
        p.update(2.0)
        assertEquals(2.0, p.playback.time)
        // Catch-up remains bounded despite the two-second score jump.
        assertTrue(p.physicsSteps <= 1212)
    }

    @Test
    fun namedAccelerationsAddAtFixedStepAndRespectPinnedParticles() {
        val p = model().apply { spring = 0f; constraintStrength = 0f }
        val called = mutableListOf<Int>()
        p.externalAccelerations["first"] = { index, position, output ->
            called += index
            assertEquals(p.state.x[index], position.x)
            assertEquals(0f, output.length())
            output.z = 2f
        }
        p.externalAccelerations["second"] = { _, _, output ->
            assertEquals(0f, output.length())
            output.z = 1f
        }
        p.externalAccelerations["invalid"] = { _, _, output -> output.x = Float.NaN }
        p.update(1.0 / 120.0)
        assertEquals(listOf(1), called)
        assertEquals(0f, p.state.z[0])
        assertEquals(3f / (120f * 120f), p.state.z[1], 1e-7f)
        assertTrue(p.state.x[1].isFinite())
        p.externalAccelerations.remove("first")
        p.externalAccelerations.remove("second")
        p.resetPositions()
        p.update(1.0 / 120.0)
        assertEquals(0f, p.state.z[1])
    }

    @Test
    fun spanAnchorsFollowTheShapedRunAndDotUsesFullLineArclength() {
        val p = model()
        p.playback.seek(3.0)
        p.update(0.0)
        val frame = p.currentFrame()
        val text = p.diagram.text("sentence")
        val span = text.word("breath", occurrence = 1)
        val a = frame.position(span.baselineLeft)
        val b = frame.position(span.baselineRight)
        val layout = p.shapes.getValue(text.text).layout!!
        val first = layout.glyphs.first { it.charIndex == span.start }.origin.x
        assertEquals((first - layout.advanceWidth.toFloat() * .5f) * p.textScale, a.x, 1e-6f)
        assertTrue(b.x > a.x)
        val dot = p.geometry.points.last()
        assertEquals(a.x + (b.x - a.x) * .65f, dot.p.x, 1e-5f)
        assertEquals(-.014f, dot.p.y, 1e-6f)
        assertTrue(text.word("breath").start < span.start)
        assertFailsWith<IllegalArgumentException> { text.word("he") }
    }

    @Test
    fun scoreSeekingIsDeterministicAndAnchorsFollowAnimatedText() {
        val p = model()
        val text = p.diagram.text("sentence")
        val score =
            diagramScore(p.diagram, 10.0) {
                move(text, Vector3f(), Vector3f(.4f, .1f, 0f), at = 0.0, seconds = 2.0)
                fadeIn(text, at = 0.0, seconds = 2.0)
                drawIn(p.diagram.path("underline"), at = 0.0, seconds = 2.0)
            }
        p.playback.score = score
        p.playback.seek(1.0)
        p.update(0.0)
        val first = Vector3f(p.geometry.points.last().p)
        assertEquals(.5f, p.labels.single().opacity, 1e-6f)
        p.playback.seek(2.0)
        p.update(0.0)
        assertEquals(.2f, p.geometry.points.last().p.x - first.x, 1e-6f)
        assertEquals(.05f, p.geometry.points.last().p.y - first.y, 1e-6f)
        p.playback.seek(1.0)
        p.update(0.0)
        assertEquals(first, p.geometry.points.last().p)
        assertTrue(p.geometry.segments.all { it.ink.drawTo == .5f })
    }

    @Test
    fun graphRejectsForeignAnchorsDuplicateNamesAndConstraintOverflow() {
        val other = readingDiagram { point("outside", 0, 0) }
        assertFailsWith<IllegalArgumentException> {
            readingDiagram {
                point("a", 0, 0)
                dot("foreign", other.point("outside").anchor)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            readingDiagram {
                point("same", 0, 0)
                point("same", 1, 1)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            readingDiagram {
                val center = point("center", 0, 0)
                repeat(5) { link(center, point("p$it", it + 1, 1)) }
            }
        }
    }

    @Test
    fun groupedTransitionCrossfadesAndDrawsIncomingMaterial() {
        val graph = readingDiagram {
            group("one") {
                val p = point("p1", 0, 0)
                text("t1", "one", p)
            }
            group("two") {
                val p = point("p2", 0, .2)
                text("t2", "two", p)
                line("l2", p.anchor, p.anchor.offset(x = .2f))
            }
        }
        val score =
            diagramScore(graph, 8.0) {
                transition(graph.group("one"), graph.group("two"), at = 2.0, seconds = 2.0)
            }
        assertEquals(1f, score.pose(graph.text("t1"), 1.0).opacity)
        assertEquals(0f, score.pose(graph.text("t2"), 1.0).opacity)
        assertEquals(.5f, score.pose(graph.text("t1"), 3.0).opacity)
        assertEquals(.5f, score.pose(graph.text("t2"), 3.0).opacity)
        assertEquals(.5f, score.pose(graph.path("l2"), 3.0).reveal)
        assertEquals(0f, score.pose(graph.text("t1"), 4.0).opacity)
        assertEquals(1f, score.pose(graph.text("t2"), 4.0).opacity)
    }
}
