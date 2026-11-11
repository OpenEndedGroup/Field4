package afield.graphics.scene.reading

import afield.graphics.text.*
import afield.graphics.text.slug.SlugPath
import afield.math.linalg.*
import kotlin.math.*
import kotlin.test.*

class ReadingMechanicalHolderTest {
    private val font = Typefaces.named("Serif", 100)

    private fun player(graph: ReadingDiagram, score: DiagramScore = diagramScore(graph, 12.0) {}): ReadingDiagramPlayer {
        val atlas = SlugPath.decode(requireNotNull(javaClass.getResource("/afield/slug/curved-hole.bin")).readBytes())
        val runs = graph.texts.associateWith { run ->
            val layout = layoutText(run.text, font)
            ReadingText(run.text, layout.advanceWidth.toFloat(), layout.toFLine(), atlas, layout)
        }
        return ReadingDiagramPlayer(graph, font, runs.mapKeys { it.key.id }, score, runs)
            .apply { wind = 0f; gravity = 0f; playback.playing = false }
    }

    @Test fun overrunCombinesEachSideLengthWithAnAbsoluteMetreDistance() {
        val bounds = readingRectletUnion(listOf(ReadingRectlet(0.0, 0.0, 2.0, 1.0, 0)), 0.0, false)
        val corners = readingFrameOverstrokes(bounds, .2, .05)
        assertEquals(8, corners.size)
        fun length(pair: Pair<ReadingFramePoint, ReadingFramePoint>) = hypot(pair.second.x - pair.first.x, pair.second.y - pair.first.y)
        assertEquals(.45, length(corners[0]), 1e-9)
        assertEquals(.25, length(corners[2]), 1e-9)
        assertTrue(readingFrameOverstrokes(bounds, 0.0, 0.0).isEmpty())
    }

    @Test fun unionsKeepTheirFrostMaskAndSlidingBoundaryWhenOverstroked() {
        lateinit var border: DiagramTextFrame
        val graph = readingDiagram {
            val run = text("words", "a much wider line\ngyp", point("p", 0, 0))
            border = textFrame("frame", run, .01f, DiagramTextFrameMode.LINE_UNION)
            border.mergeSmallSteps = false
        }
        val f = player(graph).frame()
        val original = border.geometry(f)
        val attachment = border.attach()
        val toward = Vector3f(10f, 0f, 0f)
        val originalAttachment = attachment.resolve(f, toward)
        border.overstrokeFraction = .2f
        border.overstrokeDistance = .03f
        assertSame(original, border.geometry(f), "Decoration must not inflate the frosted silhouette")
        assertEquals(originalAttachment, attachment.resolve(f, toward))
        val strokes = readingFrameOverstrokes(original, .2, .03)
        assertEquals(8, strokes.size)
        for ((a, b) in strokes) {
            val middle = ReadingFramePoint((a.x + b.x) / 2, (a.y + b.y) / 2)
            assertFalse(original.contains(middle), "No added stroke may lie inside the union")
            assertTrue(a.x == b.x && (a.x == original.left || a.x == original.right) ||
                a.y == b.y && (a.y == original.bottom || a.y == original.top))
        }
    }

    @Test fun overstrokeCuesAreSeekableAndLeaveAuthoredValuesUntouched() {
        lateinit var border: DiagramTextFrame
        val graph = readingDiagram {
            val run = text("words", "gyp", point("p", 0, 0))
            border = textFrame("frame", run, overstrokeFraction = .1f, overstrokeDistance = .02f)
        }
        val score = diagramScore(graph, 12.0) {
            overstroke(border, .1f, .3f, at = 2.0, seconds = 4.0, fromDistance = .02f, toDistance = .04f)
        }
        val p = player(graph, score)
        val middle = p.frame(4.0)
        assertEquals(.2f, middle.pose(border.path).overstrokeFraction!!, 1e-6f)
        assertEquals(.03f, middle.pose(border.path).overstrokeDistance!!, 1e-6f)
        val before = middle.path(border.path).nodes.mapNotNull { it.toOrNull?.let(::Vector3f) }
        p.frame(9.0).path(border.path)
        assertEquals(before, p.frame(4.0).path(border.path).nodes.mapNotNull { it.toOrNull?.let(::Vector3f) })
        assertEquals(.1f, border.overstrokeFraction)
        assertEquals(.02f, border.overstrokeDistance)
        assertFailsWith<IllegalArgumentException> { border.overstrokeDistance = Float.NaN }
        assertFailsWith<IllegalArgumentException> { border.overstrokeFraction = -.1f }
    }

    @Test fun fittedFrustumEndsRailsFrostAndAssemblyMovementAgree() {
        lateinit var holder: DiagramTextFrustum
        val graph = readingDiagram {
            val a = text("front", "a longer line\ngyp", point("a", 0, 0, .2)) { orientation.rotateY(.2f) }
            val b = text("back", "HI", point("b", .05, .02, -.2)) {
                scale = .6f
                orientation.rotateY(.2f)
            }
            holder = textFrustum("holder", a, b) {
                aspectRatio = 2.8f
                frost { enabled = true }
            }
        }
        val score = diagramScore(graph, 12.0) {
            fadeIn(holder, at = 0.0, seconds = 2.0)
            blurIn(holder, at = 0.0, seconds = 2.0, radius = .6f)
            move(holder, Vector3f(), Vector3f(.1f, .05f, .03f), at = 2.0, seconds = 4.0)
            resize(holder.back, 1f, .7f, at = 2.0, seconds = 4.0)
        }
        val p = player(graph, score)
        val before = p.frame(2.0)
        val f = p.frame(6.0)
        for (border in listOf(holder.frontFrame, holder.backFrame)) {
            val g = border.geometry(f)
            assertEquals(2.8, (g.right - g.left) / (g.top - g.bottom), 1e-6)
            assertTrue(g.rectlets.isNotEmpty())
        }
        val displacement = Vector3f(holder.frontFrame.corners(f)[0]).sub(holder.frontFrame.corners(before)[0])
        assertTrue(displacement.distance(Vector3f(.1f, .05f, .03f)) < 1e-5f, "A holder translation must apply once")
        val near = holder.frontFrame.corners(f)
        val far = holder.backFrame.corners(f)
        val railNodes = f.path(holder.rails).nodes.map { it.to }
        assertEquals(8, railNodes.size)
        for (i in 0..3) {
            assertTrue(near[i].distance(railNodes[2 * i]) < 1e-6f)
            assertTrue(far.any { it.distance(railNodes[2 * i + 1]) < 1e-6f })
        }
        assertEquals(setOf(holder.front, holder.back), score.blurCapacities.keys)
        assertEquals(holder, graph.textFrustum("holder"))
        assertEquals(holder.group, graph.group("holder"))
        assertEquals(holder.frontFrame, graph.textFrame("holder-front-frame"))
        for (item in holder.group.items) assertEquals(.5f, score.pose(item, 1.0).opacity, 1e-6f)
        p.playback.seek(6.0)
        assertEquals(2, p.frostSurfaces.size)
        assertFailsWith<IllegalArgumentException> { holder.aspectRatio = 0f }
    }

    @Test fun backFacingEndKeepsItsPhysicalCornersRatherThanCrossingTheRails() {
        val near = listOf(Vector3f(-1f, -1f, 1f), Vector3f(1f, -1f, 1f), Vector3f(1f, 1f, 1f), Vector3f(-1f, 1f, 1f))
        val far = listOf(Vector3f(.5f, -.5f, -1f), Vector3f(-.5f, -.5f, -1f), Vector3f(-.5f, .5f, -1f), Vector3f(.5f, .5f, -1f))
        val matched = readingMatchFrustumCorners(near, far)
        near.indices.forEach { i -> assertEquals(Vector3f(near[i].x * .5f, near[i].y * .5f, -1f), matched[i]) }
    }

    @Test fun holderTransitionsFadeCompleteLinesRatherThanDrawingThemIn() {
        lateinit var first: DiagramTextFrustum
        lateinit var second: DiagramTextFrustum
        val graph = readingDiagram {
            first = textFrustum("a",
                text("a-front", "HI", point("a1", 0, 0, .2)),
                text("a-back", "gyp", point("a2", 0, 0, -.2)))
            second = textFrustum("b",
                text("b-front", "gyp", point("b1", 1, 0, .2)),
                text("b-back", "HI", point("b2", 1, 0, -.2)))
        }
        val score = diagramScore(graph, 12.0) { transition(first, second, at = 2.0, seconds = 4.0) }
        for (item in second.group.items) {
            assertEquals(.5f, score.pose(item, 4.0).opacity, 1e-6f)
            assertEquals(1f, score.pose(item, 4.0).reveal)
        }
        assertEquals(.5f, score.pose(first.rails, 4.0).opacity, 1e-6f)
    }

    @Test fun scoringAFrameTranslationMovesItsRailJunctionAndSlidingAttachmentTogether() {
        lateinit var holder: DiagramTextFrustum
        val graph = readingDiagram {
            val a = text("front", "gyp", point("a", 0, 0, .2))
            val b = text("back", "HI", point("b", 0, 0, -.2))
            holder = textFrustum("holder", a, b)
        }
        val score = diagramScore(graph, 12.0) {
            move(holder.frontFrame.path, Vector3f(), Vector3f(.1f, .02f, .03f), at = 0.0, seconds = 2.0)
        }
        val p = player(graph, score)
        val attach = holder.frontFrame.attach(DiagramTextSite.RIGHT)
        val original = attach.resolve(p.frame(0.0), Vector3f(1f, 0f, 0f))
        val f = p.frame(2.0)
        val shifted = attach.resolve(f, Vector3f(1f, 0f, 0f))
        assertTrue(Vector3f(shifted).sub(original).distance(Vector3f(.1f, .02f, .03f)) < 1e-5f)
        val corner = holder.frontFrame.corners(f)[0].add(f.pose(holder.frontFrame.path).offset)
        assertTrue(corner.distance(f.path(holder.rails).nodes[0].to) < 1e-6f)
    }
}
