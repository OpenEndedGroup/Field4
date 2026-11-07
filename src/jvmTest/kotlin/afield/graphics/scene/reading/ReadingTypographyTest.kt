package afield.graphics.scene.reading

import afield.geometry.path.FLine
import afield.graphics.text.*
import afield.graphics.text.slug.SlugPath
import afield.math.linalg.Vector3f
import kotlin.math.PI
import kotlin.test.*

class ReadingTypographyTest {
    private val font = Typefaces.named("Serif", 100)

    // Only CPU geometry/anchor tests use this existing native atlas fixture; no image is captured.
    private fun atlas() =
        SlugPath.decode(
            requireNotNull(javaClass.getResource("/afield/slug/curved-hole.bin")).readBytes(),
        )

    private fun prepared(run: DiagramText): ReadingText {
        val key = readingPreparationKey(run, font)
        val layout = prepareReadingLayout(run, key)
        return ReadingText(
            run.text,
            layout.advanceWidth.toFloat(),
            layout.toFLine(),
            atlas(),
            layout,
            (listOf(key.face) + key.ranges.map { it.face }).distinct(),
        )
    }

    private fun player(graph: ReadingDiagram): ReadingDiagramPlayer {
        val runs = graph.texts.associateWith { prepared(it) }
        return ReadingDiagramPlayer(
                graph,
                font,
                runs.mapKeys { it.key.id },
                diagramScore(graph, 10.0) {},
                runs,
            )
            .apply {
                wind = 0f
                gravity = 0f
                playback.playing = false
            }
    }

    @Test
    fun identicalWordsCanHaveDifferentFontsAndInlineStyles() {
        val graph = readingDiagram {
            val p = point("p", 0, 0, pinned = true)
            text("regular", "still water", p)
            text("italic", "still water", p) { italic = true }
            text("mixed", "still water", p) {
                word("water").style {
                    bold = true
                    italic = true
                }
            }
            text("other", "still water", p) { font = Typefaces.named("Monospaced", 100) }
        }
        val p = player(graph)
        p.update(0.0)
        assertEquals(4, p.labels.size)
        assertEquals(4, p.labels.map { it.shape }.distinct().size)
        val mixed = p.shape(graph.text("mixed"))
        assertEquals(2, mixed.faces.size)
        assertTrue(mixed.faces.last().awtFont.isBold && mixed.faces.last().awtFont.isItalic)
        val italicGallery =
            ReadingGallery(listOf(ReadingExample("a", "", p), ReadingExample("b", "", p)))
        assertEquals(8, italicGallery.shapes.size)
    }

    @Test
    fun wordMetricsKeepWhitespaceAdvancesAndSeparateDescenders() {
        val graph = readingDiagram {
            val p = point("p", 0, 0, pinned = true)
            text("run", "  pygmy water  ", p) { italic = true }
        }
        val p = player(graph)
        val run = graph.text("run")
        val m = p.frame().measure(run.word("pygmy"))
        assertTrue(m.hasInk)
        assertTrue(m.ink.bottom < m.baseline)
        assertEquals(m.baseline, m.noDescenders.bottom)
        assertTrue(m.capHeight > m.xHeight && m.xHeight > 0f)
        assertTrue(m.advance.left > 0f)
        val full = p.frame().measure(run)
        assertTrue(full.width > full.ink.width)
        assertEquals(2, run.words.size)
        assertTrue(p.frame().measure(run.characters(0, 2)).let { !it.hasInk && it.width > 0 })
    }

    @Test
    fun decorationsUseTheSameScaleRotationAndScoreOffsetAsGlyphs() {
        val graph = readingDiagram {
            val p = point("p", .3, -.2, pinned = true)
            val t =
                text("run", "hanging", p) {
                    rotation = (PI / 2).toFloat()
                    scale = 1.3f
                }
            rectangle("box", t.all.inkBounds, padding = .01f)
            underline("under", t.all, gap = .02f)
            textPath(
                "ruler",
                t,
                shape = { FLine().moveTo(advance.left, baseline).lineTo(advance.right, baseline) },
            )
        }
        val p = player(graph)
        p.playback.score =
            diagramScore(graph, 10.0) {
                move(
                    graph.text("run"),
                    Vector3f(.1f, .2f, .05f),
                    Vector3f(.1f, .2f, .05f),
                    0.0,
                    1.0,
                )
                resize(graph.text("run"), 2f, 2f, 0.0, 1.0)
            }
        val run = graph.text("run")
        val f = p.frame()
        val left = f.position(run.all.baselineLeft)
        val right = f.position(run.all.baselineRight)
        assertEquals(0f, right.x - left.x, 1e-5f)
        assertEquals(f.measure(run).width * p.textScale * run.scale * 2f, right.y - left.y, 1e-5f)
        // Metre offsets rotate with the run, but do not grow with font/score scale.
        val beside = f.position(run.all.baselineRight.alongText(run, x = .015f, z = .003f))
        assertEquals(right.x, beside.x, 1e-5f)
        assertEquals(.015f, beside.y - right.y, 1e-5f)
        assertEquals(.003f, beside.z - right.z, 1e-5f)
        val under = f.path(graph.path("under")).nodes.first().to
        assertEquals(.02f, under.x - left.x, 1e-5f)
        assertEquals(left.y, under.y, 1e-5f)
        val ruler = f.path(graph.path("ruler"))
        assertTrue(ruler.nodes.first().to.distance(left) < 1e-5f)
        assertTrue(ruler.nodes.last().to.distance(right) < 1e-5f)
        val corner = f.position(run.all.inkBounds.bottomLeft)
        val padded = f.path(graph.path("box")).nodes.first().to
        assertEquals(.01f, padded.x - corner.x, 1e-5f)
        assertEquals(-.01f, padded.y - corner.y, 1e-5f)
    }

    @Test
    fun multilineBoundsAndIndividualBaselinesAgree() {
        val graph = readingDiagram {
            val p = point("p", 0, 0, pinned = true)
            text("run", "first\nsecond", p)
        }
        val p = player(graph)
        val run = graph.text("run")
        assertTrue(p.frame().measure(run.line(1)).baseline < 0)
        val whole = p.frame().measure(run)
        assertEquals(p.frame().measure(run.line(1)).baseline, whole.lastBaseline)
        assertTrue(whole.ink.height > whole.ascent)
        assertEquals(0f, p.frame().measure(run.line(0)).baseline, 1e-6f)
        val f = p.frame()
        assertTrue(f.position(run.caret(5)).distance(f.position(run.line(0).baselineRight)) < 1e-5f)
        assertTrue(
            f.position(run.caret(run.text.length)).distance(f.position(run.line(1).baselineRight)) <
                1e-5f,
        )
    }

    @Test
    fun explicitFacesRetainTheirStyleAndSurrogatePairsCannotBeSplit() {
        val style = DiagramTypography().apply { italic = true }
        val face = style.resolve(font.withStyle(bold = true), false, false, null)
        assertTrue(face.awtFont.isBold && face.awtFont.isItalic)
        val graph = readingDiagram {
            val p = point("p", 0, 0, pinned = true)
            text("run", "x\uD83D\uDE00y", p) { characters(2, 3).style { bold = true } }
        }
        val run = graph.text("run")
        assertFailsWith<IllegalArgumentException> {
            prepareReadingLayout(run, readingPreparationKey(run, font))
        }
    }

    @Test
    fun numericWeightsValidateAndReachTheFontRequest() {
        assertFailsWith<IllegalArgumentException> { font.withWeight(99) }
        assertFailsWith<IllegalArgumentException> { font.withWeight(Double.NaN) }
        val light = font.withWeight(300)
        val heavy = font.withWeight(700)
        assertNotEquals(light.awtFont.attributes, heavy.awtFont.attributes)
        assertTrue(
            layoutText("waves", heavy).advanceWidth > layoutText("waves", light).advanceWidth,
        )
    }

    @Test
    fun cubicBoundsUseActualExtremaRatherThanControlPointBounds() {
        val line =
            FLine()
                .moveTo(0, 0)
                .cubicTo(Vector3f(0f, 4f, 0f), Vector3f(1f, 4f, 0f), Vector3f(1f, 0f, 0f))
        val b = requireNotNull(readingInkBounds(line))
        assertEquals(3f, b.top, 1e-5f)
        assertEquals(1f, b.right)
    }
}
