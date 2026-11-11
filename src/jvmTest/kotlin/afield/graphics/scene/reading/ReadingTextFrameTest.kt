package afield.graphics.scene.reading

import afield.graphics.text.*
import afield.graphics.text.slug.SlugPath
import afield.math.linalg.*
import kotlin.math.*
import kotlin.test.*

class ReadingTextFrameTest {
    private val font = Typefaces.named("Serif", 100)
    private fun player(graph: ReadingDiagram): ReadingDiagramPlayer {
        val atlas = SlugPath.decode(requireNotNull(javaClass.getResource("/afield/slug/curved-hole.bin")).readBytes())
        val runs = graph.texts.associateWith { run ->
            val layout = layoutText(run.text, font)
            ReadingText(run.text, layout.advanceWidth.toFloat(), layout.toFLine(), atlas, layout)
        }
        return ReadingDiagramPlayer(graph, font, runs.mapKeys { it.key.id }, diagramScore(graph, 10.0) {}, runs)
            .apply { wind = 0f; gravity = 0f; playback.playing = false }
    }

    @Test fun descendersUseActualInkAndPlainTextDoesNotReserveUnusedFontDescent() {
        val graph = readingDiagram {
            text("plain", "HI", point("a", 0, 0))
            text("descenders", "gyp", point("b", 1, 0))
            text("multiline", "HI\ngyp", point("c", 2, 0))
        }
        val frame = player(graph).frame()
        val plain = frame.measure(graph.text("plain"))
        val descenders = frame.measure(graph.text("descenders"))
        val multiline = frame.measure(graph.text("multiline"))
        assertEquals(plain.lastBaseline, plain.bounds(DiagramBoundsKind.FRAME).bottom)
        assertTrue(descenders.ink.bottom < descenders.lastBaseline)
        assertEquals(descenders.ink.bottom, descenders.bounds(DiagramBoundsKind.FRAME).bottom)
        assertEquals(multiline.ink.bottom, multiline.bounds(DiagramBoundsKind.FRAME).bottom)
        assertTrue(plain.bounds(DiagramBoundsKind.FRAME).bottom > plain.lastBaseline - plain.descent)
    }

    @Test fun borderAndSlidingAttachmentShareLiveMetreClearanceUnderFullTextRotation() {
        lateinit var border: DiagramTextFrame
        val graph = readingDiagram {
            val run = text("text", "HI\ngyp", point("p", .2, .3, .1)) {
                scale = 1.7f
                rotation = .45f
                orientation.rotateY(.3f)
            }
            border = textFrame("frame", run, standoff = .02f)
        }
        val player = player(graph)
        val attachment = border.attach()
        for (gap in listOf(.02f, .08f, 0f)) {
            border.standoff = gap
            val f = player.frame()
            val run = graph.text("text")
            val r = f.measure(run).bounds(DiagramBoundsKind.FRAME)
            val expected = f.textPoint(run, r.right, r.centerY).add(f.textDirection(run, gap, 0f, 0f))
            val toward = Vector3f(expected).add(f.textDirection(run, .2f, 0f, .03f))
            assertTrue(attachment.resolve(f, toward).distance(expected) < 1e-5f)
            val corners = f.path(border.path).nodes.take(4).map { it.to }
            val rightMiddle = Vector3f(corners[1]).lerp(corners[2], .5f)
            assertTrue(rightMiddle.distance(expected) < 1e-5f)
        }
        assertFailsWith<IllegalArgumentException> { border.standoff = -.01f }
        assertFailsWith<IllegalArgumentException> { attachment.standoff = Float.NaN }
    }

    @Test fun ordinaryMultilineAttachmentsKeepTheirOwnLineMarginsWithStandoff() {
        val graph = readingDiagram {
            text("text", "a wider line\nHI", point("p", 0, 0)) { rotation = .3f }
        }
        val f = player(graph).frame()
        val run = graph.text("text")
        val r = f.measure(run.line(1)).ink
        val expected = f.textPoint(run, r.right, r.centerY).add(f.textDirection(run, .03f, 0f, 0f))
        val toward = Vector3f(expected).add(f.textDirection(run, .02f, 0f, 0f))
        val actual = run.attach(DiagramTextSite.RIGHT_MARGIN, standoff = .03f).resolve(f, toward)
        assertTrue(actual.distance(expected) < 1e-5f)
    }
}
