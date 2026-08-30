package afield

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `kotlin.Pair` is a data class, so the reflective fallback in [ValueVisualizer] used to expand it
 * into `Pair { first -> a, second -> b }` — three times the width of the `a to b` that built it, and
 * the field names carry no information. These pin the compact form, and pin that it survives the two
 * places a value is rendered from: the top-level dispatch and the nested struct-field path.
 */
class PairDisplayTest {

    private data class Sample(val name: String, val calls: Int)

    private fun textOf(html: String) = html.replace(Regex("<[^>]*>"), "")

    @Test
    fun `a pair renders as the to that built it`() {
        val text = textOf(("contourAlpha" to 1).h)
        assertTrue("contourAlpha to 1" in text, "expected `a to b`, got: $text")
        assertFalse("first" in text, "the field names should be gone: $text")
    }

    @Test
    fun `a list of pairs stays inline`() {
        val text = textOf(listOf("a" to 1, "b" to 2).h)
        assertTrue("a to 1" in text && "b to 2" in text, "both pairs, compact: $text")
        assertFalse("second" in text, "no reflective expansion inside a list: $text")
    }

    @Test
    fun `a pair inside a struct field takes the same route`() {
        // The nested path (renderFieldValue) has its own struct-like test, and a data class
        // matches it — so this is the case that regresses independently of the top-level one.
        val text = textOf(mapOf("range" to (0 to 10)).h)
        assertTrue("0 to 10" in text, "expected the compact form as a map value, got: $text")
        assertFalse("first" in text, "the field names should be gone: $text")
    }

    @Test
    fun `only the pair wrapper disappears — its sides still render fully`() {
        val text = textOf(("run" to Sample("a", 7)).h)
        assertTrue("run to" in text, "the pair is still a `to`: $text")
        assertTrue("name" in text && "calls" in text, "the struct on the right still expands: $text")
    }
}
