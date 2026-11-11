package afield.graphics.scene.reading

import afield.graphics.text.*
import afield.graphics.text.slug.SlugPath
import kotlin.test.*

class ReadingDropCapTest {
    private val font = Typefaces.named("Serif", 100)
    private val text = "The river carries\nsmall things\ninto the light\nand then returns"

    private fun shape(run: DiagramText): ReadingText {
        val key = readingPreparationKey(run, font)
        val layout = prepareReadingLayout(run, key)
        return ReadingText(run.text, layout.advanceWidth.toFloat(), layout.toFLine(),
            SlugPath.decode(requireNotNull(javaClass.getResource("/afield/slug/curved-hole.bin")).readBytes()),
            layout, (listOf(key.face) + key.ranges.map { it.face }).distinct(),
            layout.glyphs.associate { g -> g.charIndex to (key.ranges.lastOrNull { g.charIndex in it.start until it.end }?.face ?: key.face) })
    }

    @Test fun initialFitsThreeLinesAndLaterLinesReturnToMargin() {
        val graph = readingDiagram {
            text("paragraph", text, point("p", 0, 0)) { dropCap(lines = 3, gapEm = .2f) { bold = true } }
        }
        val run = graph.text("paragraph")
        val shape = shape(run)
        val initial = shape.measure(run.initial.start, run.initial.endExclusive)
        val baseH = readingInkBounds(layoutText("H", font).toFLine().transform { afield.math.linalg.Vector3f(it.x,-it.y,0f) })!!.top
        assertEquals(baseH, initial.ink.top, .1f) // font-outline quantization is below a tenth of a font unit
        assertEquals((-2 * font.lineHeight).toFloat(), initial.ink.bottom, .02f)
        assertEquals(initial.ink.bottom, initial.noDescenders.bottom, .02f)
        val layout = shape.layout!!
        val starts = layout.lines.mapIndexed { i, line -> line.glyphs.first { i != 0 || it.charIndex >= 1 }.origin.x }
        assertTrue(starts[0] > initial.ink.right)
        assertEquals(starts[0], starts[1], .001f)
        assertEquals(starts[0], starts[2], .001f)
        assertEquals(0f, starts[3], .001f)
        assertEquals(text, layout.text)
        val river = run.word("river")
        assertTrue(shape.measure(river.start, river.endExclusive).ink.left > initial.ink.right)
        // The dropped initial must survive no-descender bounds instead of being clipped at row 0.
        assertTrue(shape.measure().noDescenders.bottom <= initial.ink.bottom + .1f)
    }

    @Test fun fontAndDropSettingsParticipateInPreparationCacheKeys() {
        val graph = readingDiagram {
            val p = point("p", 0, 0)
            text("plain", text, p)
            text("two", text, p) { dropCap(lines = 2) }
            text("three", text, p) { dropCap(lines = 3) }
            text("italic", text, p) { dropCap(lines = 3) { italic = true } }
            text("gap", text, p) { dropCap(lines = 3, gapEm = .5f) }
        }
        assertEquals(5, graph.texts.map { readingPreparationKey(it, font) }.toSet().size)
    }

    @Test fun composedInitialKeepsUnicodeIndicesAndStylesAcrossLines() {
        val source = "A\u0301 quiet river\nreturns softly"
        val graph = readingDiagram {
            text("unicode", source, point("p", 0, 0)) {
                dropCap(lines = 2)
                word("softly").style { italic = true }
            }
        }
        val run = graph.text("unicode")
        assertEquals(2, run.initial.endExclusive)
        val shape = shape(run)
        assertTrue(shape.measure(0, 2).hasInk)
        val span = run.word("softly")
        assertTrue(shape.measure(span.start, span.endExclusive).hasInk)
        assertTrue(shape.glyphFaces.getValue(span.start).awtFont.isItalic)
    }

    @Test fun oneLineDescenderInitialDoesNotMoveTheBodyBaseline() {
        val graph = readingDiagram {
            text("p", "gentle water", point("a", 0, 0)) { dropCap(lines = 1) }
        }
        val run = graph.text("p")
        val shape = shape(run)
        assertEquals(0f, shape.measure().baseline, .001f)
        val initial = shape.measure(0, 1)
        assertEquals(0f, initial.ink.bottom, .1f)
        assertTrue(initial.baseline > 0f)
    }

    @Test fun renderPartsSharePoseAndBlurWhileMeasurementsStayLogical() {
        val graph = readingDiagram {
            text("p", text, point("a", 0, 0)) { dropCap(); blurCapacity = 1f; blur = .4f }
        }
        val run = graph.text("p")
        val logical = shape(run)
        val first = logical.copy(text = "initial render part")
        val second = logical.copy(text = "body render part")
        val partitioned = logical.copy(renderParts = listOf(first, second))
        val player = ReadingDiagramPlayer(graph, font, mapOf(run.id to partitioned),
            diagramScore(graph, 10.0) {}, mapOf(run to partitioned))
        player.update(0.0)
        assertEquals(2, player.maximumLabels)
        assertEquals(listOf(first, second), player.labels.map { it.shape })
        assertEquals(player.labels[0].position, player.labels[1].position)
        assertEquals(listOf(.4f, .4f), player.labels.map { it.blur })
        assertEquals(setOf(first, second), player.blurRequests.keys)
        assertEquals(logical.measure(), player.frame().measure(run))
    }

    @Test fun invalidInitialAndParametersFailBeforeNativePreparation() {
        assertFailsWith<IllegalArgumentException> {
            readingDiagram { text("p", " leading space", point("a", 0, 0)) { dropCap() } }
        }
        assertFailsWith<IllegalArgumentException> {
            readingDiagram { text("p", "T\nrest", point("a", 0, 0)) { dropCap(characters = 2) } }
        }
        assertFailsWith<IllegalArgumentException> {
            readingDiagram { text("p", "Text", point("a", 0, 0)) { dropCap(lines = 0) } }
        }
    }
}
