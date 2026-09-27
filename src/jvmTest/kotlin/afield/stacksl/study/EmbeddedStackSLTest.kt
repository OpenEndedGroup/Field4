package afield.stacksl.study

import afield.Box
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** StackSL inside a Kotlin box's code ([EmbeddedStackSL]): where it is, what completes there, what a line shows. */
class EmbeddedStackSLTest {
    private fun box() = Box("embedded_stacksl_test").also {
        it.setPropertyRaw("fragment", "#version 450\n{{ stack.auto.program }}\n{{ stack.auto.warp:bend }}\nvoid main() {}\n")
    }

    private val code = """
        val module = moduleHere { }
        module.program = ""${'"'}
        # soft bands
        0.35 0.9 uv 4.0 * fbm smoothstep
        palette saturate
        ""${'"'}
        module.warp = "uv 3 * sin"
        val label = "uv fbm is not StackSL here"
        val notes = ""${'"'}
        #stacksl
        uv 2 * fbm
        ""${'"'}
        val prose = ""${'"'}uv fbm palette""${'"'}
    """.trimIndent()

    @Test
    fun aSlotsProgramAndAHeadedStringAreStackSLAndOtherStringsAreNot() {
        val regions = EmbeddedStackSL.regions(code, EmbeddedStackSL.slots(box()))
        assertEquals(listOf("program", "warp", null), regions.map { it.slot })
        assertTrue(regions[0].text.contains("0.35 0.9 uv 4.0 * fbm smoothstep"))
        assertEquals("uv 3 * sin", regions[1].text)
        assertEquals("uv 2 * fbm\n", regions[2].text, "a #stacksl string starts after its header line")
    }

    @Test
    fun completionInsideTheStringIsTheStackSLVocabularyInTheCodesOffsets() {
        val at = code.indexOf("palette saturate") + "pal".length
        val completion = assertNotNull(EmbeddedStackSL.complete(box(), code, at))
        assertTrue(completion.metadata.any { it.text == "palette" }, completion.metadata.map { it.text }.toString())
        assertEquals(code.indexOf("palette saturate"), completion.boundsStart, "the word being typed is replaced where it is in the code")
        assertNull(EmbeddedStackSL.complete(box(), code, code.indexOf("not StackSL")), "an ordinary string completes as Kotlin")
    }

    @Test
    fun aLineInsideTheStringShowsItsStageDrawnAlone() {
        val line = code.lines().indexOfFirst { it.contains("smoothstep") } + 1
        val note = assertNotNull(EmbeddedStackSL.lineNote(box(), code, line))
        assertTrue("stack.auto.program" in note.caption && "drawn alone" in note.caption, note.caption)
        assertNotNull(note.png)
        assertNull(EmbeddedStackSL.lineNote(box(), code, 1), "a Kotlin line has no StackSL note")
    }
}
