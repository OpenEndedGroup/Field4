package afield

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeCaptureTargetTest {
    private fun node(vararg windows: String, parents: List<String> = emptyList()) =
        NativeCaptureBox(parents, windows.toList())

    @Test
    fun resolvesTargetRatherThanLastWindowInDocument() {
        val boxes = linkedMapOf("a" to node("window-a"), "b" to node("window-b"))
        assertEquals("window-a", resolveNativeCaptureTarget(boxes, "a"))
        assertEquals("window-b", resolveNativeCaptureTarget(boxes, "b"))
    }

    @Test
    fun identicalBoxIdsInSeparateDocumentsResolveSeparately() {
        val a = mapOf("renderer" to node("a-window"))
        val b = mapOf("renderer" to node("b-window"))
        assertEquals("a-window", resolveNativeCaptureTarget(a, "renderer"))
        assertEquals("b-window", resolveNativeCaptureTarget(b, "renderer"))
    }

    @Test
    fun nearestParentWinsAndCyclesTerminate() {
        val boxes = mapOf(
            "child" to node(parents = listOf("near")),
            "near" to node("near-window", parents = listOf("far", "child")),
            "far" to node("far-window")
        )
        assertEquals("near-window", resolveNativeCaptureTarget(boxes, "child"))
        assertFailsWith<IllegalStateException> {
            resolveNativeCaptureTarget(mapOf("a" to node(parents = listOf("b")),
                "b" to node(parents = listOf("a"))), "a")
        }
    }

    @Test
    fun unknownUidCannotFallBackToAnExistingWindow() {
        val error = assertFailsWith<NativeCaptureTargetException> {
            resolveNativeCaptureTarget(mapOf("local" to node("window")), "foreign")
        }
        assertTrue(error.message!!.contains("No box 'foreign' in this document"))
    }

    @Test
    fun untargetedCaptureRequiresOneDistinctWindow() {
        assertEquals("shared", resolveNativeCaptureTarget(mapOf(
            "a" to node("shared", "shared"), "b" to node("shared")), null))
        assertFailsWith<NativeCaptureTargetException> {
            resolveNativeCaptureTarget(mapOf("a" to node("a"), "b" to node("b")), null)
        }
        assertFailsWith<IllegalStateException> { resolveNativeCaptureTarget(emptyMap<String, NativeCaptureBox<String>>(), null) }
    }

    @Test
    fun ambiguousParentsFailRatherThanChoosingTraversalOrder() {
        assertFailsWith<NativeCaptureTargetException> {
            resolveNativeCaptureTarget(mapOf(
                "child" to node(parents = listOf("a", "b")),
                "a" to node("window-a"), "b" to node("window-b")
            ), "child")
        }
    }
}
