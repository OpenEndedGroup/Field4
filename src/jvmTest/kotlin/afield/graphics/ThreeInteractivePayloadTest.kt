package afield.graphics

import afield.stdlib.FLineIntersection
import afield.stdlib.filled
import afield.stdlib.fline
import afield.stdlib.hoverColor
import afield.stdlib.label
import afield.stdlib.onClick
import afield.stdlib.stroked
import afield.stdlib.vec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The seam between the Kotlin payload and the viewer's picking: does a clickable FLine arrive in the
 * browser with what `flinePickClassify` needs?
 *
 * [ThreeJsHitRecordDifferentialTest] executes the viewer's picking maths, and this asserts the other
 * half — that [buildThreeGeometryPayload] actually *sends* the topology, the label and the line index,
 * that the topology is the same string the SVG output carries, and that a hover-only line does not pay
 * for a topology it cannot use. Between them the only unverified link left in the three.js path is the
 * raycast itself, which needs a real browser with a real camera; that is stated as a gap in
 * `dev_docs/fline_hit_records.md` rather than papered over.
 */
class ThreeInteractivePayloadTest {

    private val sink: (FLineIntersection) -> Unit = { }

    private fun panel(name: String): FLine = fline {
        moveTo(0.0, 0.0, 0.0)
        lineTo(100.0, 0.0, 40.0)
        lineTo(100.0, 100.0, 40.0)
        lineTo(0.0, 100.0, 0.0)
        close()
        label = name
        filled = true
        stroked = true
    }

    @Test
    fun `a clickable line arrives with its topology, its label and its index`() {
        val first = panel("panel 0").also { it.onClick = sink }
        val second = panel("panel 1").also { it.onClick = sink }
        val payload = buildThreeGeometryPayload(
            uid = "test",
            lines = listOf(first, second),
            samplesPerCubic = 8,
            autoFrame = true,
            editable = false
        )
        assertEquals(2, payload.interactive.size)
        payload.interactive.forEachIndexed { index, obj ->
            assertEquals(index, obj.lineIndex, "the viewer needs to know which line it is reporting")
            assertEquals("panel $index", obj.label)
            assertTrue(obj.topology.isNotEmpty(), "line $index arrived with no topology to name elements with")
            // Byte-identical to what the SVG output puts in `data-fline-elements` — one producer.
            assertEquals(flineTopologyJson(if (index == 0) first else second), obj.topology)
            assertTrue(obj.topology.contains("\"faces\":[{"), "the panel's face is missing from the topology")
            // In the line's own coordinates, not in anything the viewer scaled.
            assertTrue(obj.topology.contains(",40]"), "the topology lost the panel's own z")
        }
    }

    /** Hover with no click has nothing to name, and should not carry a topology at all. */
    @Test
    fun `a hover-only line carries no topology`() {
        val hoverOnly = panel("hover only").also { it.hoverColor = vec(1.0, 1.0, 0.0, 1.0) }
        val payload = buildThreeGeometryPayload(
            uid = "test",
            lines = listOf(hoverOnly),
            samplesPerCubic = 8,
            autoFrame = true,
            editable = false
        )
        assertEquals(1, payload.interactive.size)
        assertEquals("", payload.interactive[0].topology)
        assertEquals("hover only", payload.interactive[0].label)
    }

    /** The editable scene carries it too, so an editable figure's clicks name elements as well. */
    @Test
    fun `the editable scene carries the same topology`() {
        val line = panel("editable panel").also { it.onClick = sink }
        val payload = buildThreeGeometryPayload(
            uid = "test",
            lines = listOf(line),
            samplesPerCubic = 8,
            autoFrame = true,
            editable = true
        )
        val scene = assertNotNull(payload.scene, "editable mode should produce a scene")
        assertEquals(1, scene.lines.size)
        assertEquals("editable panel", scene.lines[0].label)
        assertEquals(flineTopologyJson(line), scene.lines[0].topology)
    }
}
