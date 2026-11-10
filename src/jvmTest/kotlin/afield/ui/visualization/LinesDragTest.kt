package afield.ui.visualization

import afield.app.protocol.LinesDrag
import afield.document.graph.Box
import afield.graphics.render.fline
import afield.graphics.render.onClick
import kotlin.test.*

class LinesDragTest {
    private val shown = mutableListOf<String>()
    private fun box() = Box("drag-${System.nanoTime()}").also { it.output.displayProxy = { _, html -> shown += html.toString() } }
    private fun string(y: Double) = fline { moveTo(0.0, 150.0); lineTo(320.0, y); lineTo(640.0, 150.0) }
    private fun dragId() = Regex("data-afield-drag=\"([^\"]+)\"").find(shown.last())!!.groupValues[1]

    @Test fun aDragReachesTheLatestCallbackInTheLinesCoordinates() {
        val b = box()
        val seen = mutableListOf<Pair<Int, LinesDragEvent>>()
        // An animation redisplays every frame, each time with a new callback (it closes over the frame).
        for (frame in 0 until 100) b.lines(string(150.0 + frame), channel = "string", w = 640.0, h = 300.0,
            onDrag = { seen += frame to it })
        val id = dragId()
        assertTrue(deliverLinesDrag(LinesDrag(id, "start", 100.0, 80.0)))
        assertTrue(deliverLinesDrag(LinesDrag(id, "move", 120.0, 90.0)))
        assertTrue(deliverLinesDrag(LinesDrag(id, "end", 130.0, 95.0)))
        assertEquals(listOf(99, 99, 99), seen.map { it.first }, "only the latest display's callback, once per event")
        assertEquals(LinesDragEvent(100.0, 80.0, LinesDragEvent.Phase.START), seen[0].second)
        assertEquals(LinesDragEvent.Phase.END, seen[2].second.phase)
        assertFalse(deliverLinesDrag(LinesDrag("lines-drag:nobody:none", "start", 0.0, 0.0)))
    }

    @Test fun aFittedDrawingHandsBackTheLinesOwnCoordinates() {
        val b = box()
        var got: LinesDragEvent? = null
        // Lines 0..64 wide, fitted into 640 x 300: about ten times larger on screen.
        b.lines(fline { moveTo(0.0, 0.0); lineTo(64.0, 30.0) }, channel = "fit", w = 640.0, h = 300.0, fitToSize = true,
            onDrag = { got = it })
        val svg = shown.last()
        val x0 = Regex("data-afield-bounds-x=\"([0-9.eE+-]+)\"").find(svg)!!.groupValues[1].toDouble()
        val w = Regex("data-afield-bounds-w=\"([0-9.eE+-]+)\"").find(svg)!!.groupValues[1].toDouble()
        // The right-hand end of the drawn line, in SVG coordinates, is x0 + w; it is x = 64 in the lines'.
        deliverLinesDrag(LinesDrag(dragId(), "start", x0 + w, 0.0))
        assertEquals(64.0, got!!.x, 1e-6)
    }

    @Test fun aClickableDrawingRedisplayedEveryFrameKeepsOneCallbackPerLine() {
        val b = box()
        repeat(50) { b.lines(fline { moveTo(0.0, 0.0); lineTo(10.0, 10.0); onClick = { } }, channel = "c", w = 100.0, h = 100.0) }
        val ids = shown.map { Regex("data-fline-callback-id=\"([^\"]+)\"").find(it)!!.groupValues[1] }.toSet()
        assertEquals(1, ids.size, "the same callback id every frame, so each registration replaces the last")
    }
}
