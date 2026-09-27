package afield.stdlib

import afield.Box
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The names a study card's sliders are shown by ([KnobNames]): kept by the program's shape, read from a
 * model's reply, stored beside the study — and never asked for here, where no box is in a document.
 */
class KnobNamesTest {
    private val folders = mutableListOf<File>()

    @AfterTest
    fun removeFolders() = folders.forEach { it.deleteRecursively() }

    private val knobs = listOf(KnobNames.Knob(0, 1, "5", "multiplies"), KnobNames.Knob(1, 1, "0.2", "adds"))

    @Test
    fun turningANumberKeepsTheShapeButRewritingALineDoesNot() {
        val shape = KnobNames.shape("StackSL", "uv 5 * 0.2 +\n", knobs)
        assertEquals(shape, KnobNames.shape("StackSL", "uv 7.5 * -0.25 +\n", knobs), "the names outlive a kept number")
        assertNotEquals(shape, KnobNames.shape("StackSL", "uv 5 * 0.2 - \n", knobs))
        assertNotEquals(shape, KnobNames.shape("RectSL", "uv 5 * 0.2 +\n", knobs))
        assertEquals(KnobNames.shape("StackSL", "fbm3 x2\n", emptyList()), KnobNames.shape("StackSL", "fbm3 x2\n", emptyList()),
            "digits inside a word are the word, not a number")
        assertNotEquals(KnobNames.shape("StackSL", "fbm3\n", emptyList()), KnobNames.shape("StackSL", "fbm4\n", emptyList()))
    }

    @Test
    fun aReplyIsReadForTheKnobsAskedAbout() {
        val reply = "Here you are:\n```json\n{\"0\": \"wave count\", \"k1\": \"drift speed.\", \"7\": \"not asked\", \"x\": \"junk\"}\n```"
        assertEquals(mapOf(0 to "wave count", 1 to "drift speed"), KnobNames.parse(reply, setOf(0, 1)))
        assertEquals(emptyMap(), KnobNames.parse("no json here", setOf(0)))
    }

    @Test
    fun thePromptCarriesTheProgramAndWhatReadsEachNumber() {
        val prompt = KnobNames.prompt("StackSL", "StackSL is a stack language.", "# waves\nuv 5 * 0.2 +\n", knobs)
        assertTrue("   2  uv 5 * 0.2 +" in prompt, prompt)
        assertTrue("k0 · line 1 · 5 · multiplies" in prompt && "k1 · line 1 · 0.2 · adds" in prompt, prompt)
        assertTrue("Return only JSON" in prompt)
    }

    @Test
    fun storedNamesAreShownAndNothingIsAskedForABoxInNoDocument() {
        val directory = Files.createTempDirectory("knob-names").toFile().also { folders += it }
        val source = "uv 5 * 0.2 +\n"
        assertNull(KnobNames.names("StackSL", "", source, knobs, directory, ask = false) {}, "nothing stored, nothing asked")
        val key = KnobNames.shape("StackSL", source, knobs)
        File(directory, "knob-names.json").writeText("""{"$key": {"model": "test", "names": {"0": "wave count", "1": "drift"}}}""")
        assertEquals(mapOf(0 to "wave count", 1 to "drift"), KnobNames.names("StackSL", "", "uv 9 * 0.1 +\n", knobs, directory, ask = false) {})
        assertFalse(KnobNames.shouldAsk(Box("knob-names-test")), "a box in no open document never asks")
    }
}
