package afield.stdlib.noir.audioai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GestureBoardTest {
    private val board = gestureBoard(12.0, "suspended low strings") {
        prompt(4.0, 3.0, "brass begins to press inward")
        event(7.2, "impact", strength = 0.9, weight = 1.4)
        curve("breath", GestureCurveKind.ACTIVITY) {
            point(0.0, 0.08)
            point(6.0, 0.35)
            point(8.0, 0.92)
            point(12.0, 0.18)
        }
        curve("altitude", GestureCurveKind.REGISTER, weight = 0.7) {
            point(0.0, 0.2)
            point(12.0, 0.72)
        }
    }

    @Test
    fun dslCompilesIntoAnHonestBackendRequest() {
        val compilation = compileGestureBoard(board)

        assertEquals(
            "suspended low strings; then from 4s to 7s: brass begins to press inward",
            compilation.effectivePrompt,
        )
        assertEquals("direct", compilation.bindings.first().delivery.name.lowercase())
        assertTrue(compilation.bindings.any { it.name == "impact" && it.delivery == GestureDelivery.EVALUATIVE })
        assertEquals("12.0", compilation.parameters["duration_seconds"].toString())
    }

    @Test
    fun operationSerializesTheTypedGestureBoard() {
        val operation = gestureCandidates(
            AssetRegion("sha256:source", 18.0, 4.5), board, seeds = 3L..4L
        )
        val encoded = Json { encodeDefaults = true }.encodeToString(operation)

        assertTrue("\"gesture_board\"" in encoded)
        assertTrue("\"time_seconds\":7.2" in encoded)
        assertTrue("\"candidate_count\":2" in encoded)
        assertTrue("\"delivery\":\"evaluative\"" in encoded)
    }

    @Test
    fun validationRejectsOutOfRangeAndUnorderedGestures() {
        assertFailsWith<IllegalArgumentException> {
            gestureBoard(4.0) { event(5.0, "late") }
        }
        assertFailsWith<IllegalArgumentException> {
            gestureBoard(4.0) {
                curve("broken", GestureCurveKind.DENSITY) {
                    point(3.0, 0.2)
                    point(2.0, 0.8)
                }
            }
        }
    }

    @Test
    fun rendererIncludesTimelineBindingsAndInteraction() {
        val html = gestureBoardHtml(board, width = 900)

        assertTrue("gesture board" in html)
        assertTrue("effective prompt" in html)
        assertTrue("brass begins to press inward" in html)
        assertTrue("gb-cursor" in html)
        assertTrue("evaluative" in html)
        assertTrue("data-afield-intrinsic-width=\"938\"" in html)
        assertTrue("--board-width:938px" in html)
    }
}
