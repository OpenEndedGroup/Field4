package afield

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Field adds one snippet of its own to every Kotlin completion: `go { }`. It was added unconditionally,
 * filtered only by the typed prefix, so at a dot — where the typed prefix is empty — it was offered as a
 * member of whatever was to the left: `"hello world".` suggested `go { }`. `go` is `Box.go`, a bare call
 * inside a box, so it is never a member of anything.
 *
 * These measure the gate against the same [getTokenBounds] the server uses to find the typed token.
 */
class CompletionSnippetPositionTest {

    /** Caret marker, matching the other completion tests. */
    private val caret = "‸"

    private fun snippetsAt(sourceWithCaret: String): List<Metadata> {
        val position = sourceWithCaret.indexOf(caret)
        require(position >= 0) { "Missing $caret" }
        val allCode = sourceWithCaret.replace(caret, "")
        val bounds = getTokenBounds(allCode, position)
        return KotlinCompletionSupport.fieldSnippetCompletions(
            activeToken = allCode.substring(bounds.start, bounds.end),
            allCode = allCode,
            tokenStart = bounds.start
        )
    }

    private fun assertOffered(sourceWithCaret: String) {
        val snippets = snippetsAt(sourceWithCaret)
        assertEquals(listOf("go { }"), snippets.map { it.displayText }, sourceWithCaret)
    }

    private fun assertNotOffered(sourceWithCaret: String) {
        assertTrue(snippetsAt(sourceWithCaret).isEmpty(), sourceWithCaret)
    }

    /** The reported case, verbatim. */
    @Test
    fun `a member completion on a string offers no snippet`() {
        assertNotOffered("\"hello world\".$caret")
    }

    @Test
    fun `a partly typed member that happens to start with g offers no snippet`() {
        assertNotOffered("\"hello world\".g$caret")
    }

    @Test
    fun `a safe call offers no snippet`() {
        assertNotOffered("val s: String? = null\ns?.$caret")
    }

    @Test
    fun `a callable reference offers no snippet`() {
        assertNotOffered("val f = String::$caret")
    }

    /**
     * The deliberate limit: only spaces and tabs are skipped looking back for the dot, never a
     * newline. `frame.` then a newline really is one expression to Kotlin and the snippet is wrong
     * here — but reading back across the newline would make `// done.` suppress the whole next line,
     * and a comment ending in a full stop is far commoner than this style.
     */
    @Test
    fun `a dot left hanging at the end of a line still offers the snippet`() {
        assertOffered("frame.\n    $caret")
    }

    /** The case that limit protects. */
    @Test
    fun `a comment ending in a full stop is not a member position`() {
        assertOffered("// set the frame first.\n$caret")
    }

    /** A leading dot on the caret's own line — the other way a chained call is written. */
    @Test
    fun `a chained call broken before the dot offers no snippet`() {
        assertNotOffered("frame\n    .$caret")
    }

    @Test
    fun `an empty caret at the start of a statement offers the snippet`() {
        assertOffered("val a = 1\n$caret")
    }

    @Test
    fun `a typed prefix of go offers the snippet`() {
        assertOffered("g$caret")
    }

    @Test
    fun `a typed prefix that is not go offers no snippet`() {
        assertNotOffered("wid$caret")
    }

    /** An argument position is a new expression, not a member of the call to its left. */
    @Test
    fun `an argument position offers the snippet`() {
        assertOffered("launch($caret)")
    }
}
