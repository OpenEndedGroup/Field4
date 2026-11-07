package afield.ai.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ai4ChatAutocompleteTest {
    private fun suggest(raw: String, draft: String) = Ai4ChatAutocomplete.suggestion(raw, draft)

    @Test
    fun aContinuationIsAppendedAsTheModelWroteIt() {
        assertEquals(" red and twice as large", suggest(" red and twice as large", "make the circle"))
        assertEquals("rcle red", suggest("rcle red", "make the ci"))
    }

    @Test
    fun whatTheModelEchoedOfTheDraftIsDropped() {
        assertEquals(" red", suggest("make the circle red", "make the circle"))
        assertEquals(" red", suggest("the circle red", "make the circle"))
        // The draft ends mid-word and the model wrote the whole word.
        assertEquals("rcle red", suggest("circle red", "make the ci"))
        assertEquals(" outline", suggest("shape outline", "draw the shape"))
        // A finished word that merely shares letters with the draft is kept.
        assertEquals("ape", suggest("ape", "draw the sh"))
    }

    @Test
    fun spacingFollowsTheDraft() {
        assertEquals("red", suggest(" red", "make the circle "))
        assertEquals(" red", suggest(" red", "make the circle"))
    }

    @Test
    fun onlyTheFirstLineSurvivesWithoutQuotesOrFences() {
        assertEquals(" red", suggest("\" red\"", "make the circle"))
        assertEquals(" red", suggest("```\n red\n```", "make the circle"))
        assertEquals(" red", suggest(" red\nand then something else", "make the circle"))
        assertEquals("", suggest("   \n", "make the circle"))
    }

    @Test
    fun aRunawayReplyIsCutAtAWord() {
        val long = (1..60).joinToString(" ") { "word$it" }
        val cut = suggest(" $long", "and")
        assertTrue(cut.length <= 120, cut)
        assertFalse(cut.endsWith("word"), cut)
    }

    @Test
    fun thePromptCarriesTheBoxTheRecentTurnsAndTheDraftButNoImageData() {
        val history = "<<you>>\nwhy is it black\n![image 1](data:image/png;base64,AAAA)\n<<ai>>\nThe fragment shader writes zero alpha."
        val prompt = Ai4ChatAutocomplete.prompt("blur", "fragment", "void main() {}", history, "ok so set al")
        assertTrue("Box: blur" in prompt, prompt)
        assertTrue("void main() {}" in prompt, prompt)
        assertTrue("Person: why is it black" in prompt, prompt)
        assertTrue("Assistant: The fragment shader writes zero alpha." in prompt, prompt)
        assertTrue(prompt.endsWith("<<<ok so set al>>>"), prompt)
        assertFalse("base64" in prompt, prompt)
    }
}
