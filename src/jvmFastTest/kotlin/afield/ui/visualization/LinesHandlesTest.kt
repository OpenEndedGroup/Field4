package afield.ui.visualization

import afield.app.protocol.LinesDrag
import afield.document.graph.Box
import afield.graphics.render.fline
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class LinesHandlesTest {
    private val shown = mutableListOf<String>()
    private fun box() = Box("handles-${System.nanoTime()}").also { it.output.displayProxy = { _, html -> shown += html.toString() } }
    private fun attr(name: String) = Regex("$name=\"([^\"]+)\"").find(shown.last())!!.groupValues[1].replace("&quot;", "\"").replace("&amp;", "&")

    /** Two vertical lines 0.02 apart, drawn 80 wide into 800 pixels: a fifth of a pixel between them. */
    private class Figure(val box: Box) {
        val at = linkedMapOf("near" to 22.30, "far" to 22.32)
        val events = mutableListOf<LinesDragEvent>()
        fun draw() {
            val drawing = at.values.map { x -> fline { moveTo(x, 0.0); lineTo(x, 30.0) } } + fline { moveTo(0.0, 0.0); lineTo(80.0, 30.0) }
            box.lines(drawing, channel = "figure", w = 800.0, h = 300.0, fitToSize = true,
                handles = listOf(LinesHandle.x("near", at.getValue("near")), LinesHandle.x("far", at.getValue("far"), priority = 1)),
                onDrag = { e ->
                    events += e
                    // The drawing follows the drag, and redraws (re-registering) inside its own callback.
                    if (e.handle != null) { at[e.handle] = e.value; draw() }
                })
        }
    }

    @Test fun aDragByNameAlwaysTakesThatHandleEvenWhenTwoNearlyCoincide() {
        val fig = Figure(box()).also { it.draw() }
        // "near" sits under "far" (higher priority, a fifth of a pixel away); by name, "near" is still the one dragged.
        val times = fig.box.dragHandle("near", listOf(22.31, 22.3, 22.29), channel = "figure")
        assertEquals(3, times.size)
        assertEquals(List(5) { "near" }, fig.events.map { it.handle })
        assertEquals(listOf(22.30, 22.31, 22.30, 22.29, 22.29), fig.events.map { it.value }, "exact values, no pixels")
        assertEquals(listOf(LinesDragEvent.Phase.START, LinesDragEvent.Phase.MOVE, LinesDragEvent.Phase.MOVE, LinesDragEvent.Phase.MOVE, LinesDragEvent.Phase.END), fig.events.map { it.phase })
        assertEquals(22.29, fig.at["near"])
        assertEquals(22.32, fig.at["far"], "the other handle never moved")
        // And "far" by name, the other way round.
        fig.events.clear()
        fig.box.dragHandle("far", to = 30.0, channel = "figure")
        assertEquals(setOf("far"), fig.events.map { it.handle }.toSet())
        assertEquals(30.0, fig.at["far"])
        assertEquals(22.29, fig.at["near"])
    }

    @Test fun theHandlesAreSentInTheDrawnSvgsCoordinatesWithTheirOwnValues() {
        val fig = Figure(box()).also { it.draw() }
        val handles = Json.parseToJsonElement(attr("data-afield-handles")).jsonArray.map { it.jsonObject }
        assertEquals(listOf("near", "far"), handles.map { it["name"]!!.jsonPrimitive.content })
        assertEquals(listOf(0, 1), handles.map { it["priority"]!!.jsonPrimitive.content.toInt() })
        assertEquals(22.32, handles[1]["vx"]!!.jsonPrimitive.double)
        // A hand's press reports SVG coordinates; at the handle's SVG x the server hands back its own x.
        val svgX = handles[0]["x"]!!.jsonPrimitive.double
        val id = attr("data-afield-drag")
        assertTrue(deliverLinesDrag(LinesDrag(id, "start", svgX, 100.0, handle = "near")))
        assertEquals("near", fig.events.single().handle)
        assertEquals(22.30, fig.events.single().value, 1e-5)
        // The two handles are under a pixel apart on screen, which is why a hand needs priority to choose.
        assertTrue(handles[1]["x"]!!.jsonPrimitive.double - svgX < 1.0)
    }

    @Test fun aHandleBoundToYReportsY() {
        val b = box()
        val seen = mutableListOf<LinesDragEvent>()
        b.lines(fline { moveTo(0.0, 5.0); lineTo(10.0, 5.0) }, channel = "c", handles = listOf(LinesHandle.y("floor", 5.0)), onDrag = { seen += it })
        b.dragHandle("floor", to = 7.25, channel = "c")
        assertEquals(listOf(5.0, 7.25, 7.25), seen.map { it.value })
        assertEquals(listOf(5.0, 7.25, 7.25), seen.map { it.y })
        val p = mutableListOf<LinesDragEvent>()
        b.lines(fline { moveTo(0.0, 0.0); lineTo(10.0, 10.0) }, channel = "p", handles = listOf(LinesHandle.xy("tip", 10.0, 10.0)), onDrag = { p += it })
        b.dragHandle("tip", 3.5, 4.5, channel = "p")
        assertEquals(listOf(10.0 to 10.0, 3.5 to 4.5, 3.5 to 4.5), p.map { it.x to it.y })
    }

    @Test fun anUnknownHandleOrDuplicateNameIsAnErrorThatSaysWhatThereIs() {
        val fig = Figure(box()).also { it.draw() }
        val e = assertFailsWith<IllegalStateException> { fig.box.dragHandle("missing", to = 1.0, channel = "figure") }
        assertTrue(e.message!!.contains("near, far"), e.message)
        assertFailsWith<IllegalArgumentException> {
            box().lines(fline { moveTo(0.0, 0.0); lineTo(1.0, 1.0) }, handles = listOf(LinesHandle.x("a", 0.0), LinesHandle.x("a", 1.0)), onDrag = { })
        }
        assertEquals(setOf("near", "far"), fig.box.linesHandles("figure").keys)
    }

    @Test fun viewerDragsArriveInOrderAndAQueuedMoveGivesWayToANewerOne() {
        val b = box()
        val seen = mutableListOf<String>()
        b.lines(fline { moveTo(0.0, 0.0); lineTo(10.0, 0.0) }, channel = "q", handles = listOf(LinesHandle.x("h", 0.0)), onDrag = { seen += "${it.phase}:${it.x}" })
        val id = attr("data-afield-drag")
        val queued = mutableListOf<Runnable>()
        // Nothing runs until the executor is drained: the start and three moves queue, and the moves collapse.
        receiveLinesDrag(LinesDrag(id, "start", 0.0, 0.0, "h", own = true)) { queued += it }
        for (x in listOf(1.0, 2.0, 3.0)) receiveLinesDrag(LinesDrag(id, "move", x, 0.0, "h", own = true)) { queued += it }
        receiveLinesDrag(LinesDrag(id, "end", 3.0, 0.0, "h", own = true)) { queued += it }
        assertEquals(1, queued.size, "one drain for the whole queue")
        queued.single().run()
        assertEquals(listOf("START:0.0", "MOVE:3.0", "END:3.0"), seen)
    }
}
