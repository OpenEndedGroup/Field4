package afield.examples.stdlib

import afield.app.protocol.Rect
import afield.audio.engine.WavFloatArrayWriter
import afield.audio.synth.FaustPatternRender
import afield.data.graph.INTERACTION_STAGE_MARGIN
import afield.data.graph.Lambda
import afield.data.graph.net
import afield.data.graph.stage
import afield.data.graph.showInteractionStages
import afield.document.graph.Box
import afield.geometry.path.FLine
import afield.graphics.render.grid
import afield.graphics.render.color
import afield.graphics.render.rect
import afield.lang.rectsl.Origin
import afield.lang.rectsl.UnitKind
import afield.lang.rectsl.WallSpec
import afield.lang.rectsl.worldToClip
import afield.math.linalg.Vector4f
import kotlin.test.*

class BoxCodeHelpersTest {
    @Test fun gridAppendsIndependentSegmentsAndKeepsExistingGeometryAndStyle() {
        val line = FLine().rect(-2, -2, 1, 1)
        line.properties["sentinel"] = "style"
        val original = line.nodes.toList()
        assertSame(line, line.grid(Rect(10.0, 20.0, 8.0, 6.0), nx = 2, ny = 3))
        assertEquals(original, line.nodes.take(original.size))
        assertEquals(original.size + 2 * (3 + 4), line.nodes.size)
        val added = line.nodes.drop(original.size)
        assertTrue(added.chunked(2).all { it[0] is FLine.MoveTo && it[1] is FLine.LineTo })
        val first = added.first().storage.single()
        val last = added.last().storage.single()
        assertEquals(10f, first.x); assertEquals(20f, first.y)
        assertEquals(18f, last.x); assertEquals(26f, last.y)
        assertEquals("style", line.properties["sentinel"])
        assertFailsWith<IllegalArgumentException> { line.grid(Rect(0.0, 0.0, 1.0, 1.0), 0, 3) }
    }

    @Test fun wallProjectionHonorsBothOrigins() {
        for (origin in Origin.entries) {
            val matrix = WallSpec(8.0, 6.0, UnitKind.Meters, origin).worldToClip()
            val low = if (origin == Origin.Center) Vector4f(-4f, -3f, 0f, 1f) else Vector4f(0f, 0f, 0f, 1f)
            val high = if (origin == Origin.Center) Vector4f(4f, 3f, 0f, 1f) else Vector4f(8f, 6f, 0f, 1f)
            matrix.transform(low); matrix.transform(high)
            assertEquals(-1f, low.x, 1e-6f); assertEquals(1f, high.x, 1e-6f)
            assertEquals(if (origin == Origin.Center) -1f else 1f, low.y, 1e-6f)
            assertEquals(-low.y, high.y, 1e-6f)
        }
    }

    @Test fun faustWavEncodesExistingSamplesWithoutChangingThem() {
        val left = floatArrayOf(0.1f, -0.25f, 0.5f)
        val right = floatArrayOf(0.2f, 0f, -0.5f)
        val render = FaustPatternRender(left, right, 24000, emptyList(), 1, 0, 0, 0, 1)
        for (normalization in listOf(null, 0.9f)) {
            assertContentEquals(WavFloatArrayWriter.encodeStereo16(left, right, 24000, normalization), render.wav(normalization))
        }
        assertContentEquals(floatArrayOf(0.1f, -0.25f, 0.5f), left)
        assertContentEquals(floatArrayOf(0.2f, 0f, -0.5f), right)
    }

    @Test fun interactionStagesCaptureDrawingsIndependentlyOfLaterMutations() {
        val source = FLine().rect(0, 0, 10, 10)
        source.color = Vector4f(0.2f, 0.3f, 0.4f, 1f)
        val captured = stage("Before", listOf(source), "summary")
        source.color.x = 1f
        source.nodes.clear()
        assertTrue(captured.lines.single().nodes.isNotEmpty())
        assertEquals(0.2f, captured.lines.single().color.x)
        captured.lines.single().nodes.clear()
        assertTrue(captured.lines.single().nodes.isNotEmpty())
    }

    @Test fun interactionStagesDisplayEscapedTitlesAndAllPanelsThroughTheOverview() {
        val box = Box("helper-stages")
        var displayed: Any? = null
        box.output.displayProxy = { _, html -> displayed = html }
        val drawing = listOf(FLine().rect(0, 0, 10, 10))
        val html = box.showInteractionStages(
            stage("<Before>", drawing, "<p>before summary</p>"),
            stage("After", drawing, "<p>after summary</p>"),
        )
        assertEquals(html, displayed)
        assertTrue("&lt;Before&gt;" in html)
        assertTrue("<Before>" !in html)
        assertTrue("<svg" in html)
        assertTrue("before summary" in html && "after summary" in html)
        assertFalse("linear-gradient" in html)
        assertFailsWith<IllegalArgumentException> { box.showInteractionStages() }
    }

    /** Every node table a summary card emits has at least one row; a "+N more" note never stands alone. */
    @Test fun interactionSummaryCardsNeverEmitAZeroRowTable() {
        val populated = net {
            node(Lambda.ROOT, id = "r1"); node(Lambda.ROOT, id = "r2"); node(Lambda.DUP, id = "d")
            node(Lambda.ROOT, id = "r3"); node(Lambda.ROOT, id = "r4")
        }
        for (subject in listOf(populated, net { })) {
            for (maxNodes in listOf(-1, 0, 1, 3, 100)) {
                val html = subject.summaryHtml("card", maxNodes = maxNodes)
                val tables = Regex("<table[\\s\\S]*?</table>").findAll(html).map { it.value }.toList()
                tables.forEach { assertTrue("<td" in it, "zero-row table at maxNodes=$maxNodes: $it") }
                if ("more nodes not shown" in html) assertTrue(tables.isNotEmpty())
                if (subject.nodes.isEmpty() || maxNodes <= 0) assertTrue(tables.isEmpty())
            }
        }
    }

    /** The stage SVG is exactly the frames plus a thin margin, so no stage is zero-height and no empty band shows. */
    @Test fun interactionStagesSvgHasNoEmptyBand() {
        val box = Box("helper-stages-band")
        box.output.displayProxy = { _, _ -> }
        val drawing = listOf(FLine().rect(0, 0, 10, 10))
        for (count in 1..3) {
            val html = box.showInteractionStages(
                *Array(count) { stage("s$it", drawing, "<p>card $it</p>") },
                stageWidth = 200.0, stageHeight = 120.0, gap = 40.0, padding = 10.0,
            )
            val svg = Regex("<svg[^>]*>").find(html)!!.value
            fun attr(name: String) = Regex("\\b$name=\"([0-9.]+)").find(svg)!!.groupValues[1].toDouble()
            val m = INTERACTION_STAGE_MARGIN
            assertEquals(120.0 + 2 * m, attr("height"), 0.5)
            assertEquals(2 * m + count * 200.0 + (count - 1) * 40.0, attr("width"), 0.5)
            assertTrue(attr("height") > 0)
        }
    }
}
