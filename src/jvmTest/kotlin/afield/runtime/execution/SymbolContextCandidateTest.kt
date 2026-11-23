package afield.runtime.execution

import kotlin.test.Test
import kotlin.test.assertEquals

/** The editor's ambient help describes the word under the cursor, not some other name that starts with it. */
class SymbolContextCandidateTest {
    private val names = listOf("importMetadataAsNest", "forEach", "position", "pos")

    @Test
    fun aKeywordIsNeverStoodInForByANameThatStartsWithIt() {
        // The cursor at the start of an import line, and just after typing `import`.
        assertEquals(-1, symbolContextCandidateIndex(names, "import", cursorAtEnd = false))
        assertEquals(-1, symbolContextCandidateIndex(names, "import", cursorAtEnd = true))
        assertEquals(-1, symbolContextCandidateIndex(names, "for", cursorAtEnd = true))
    }

    @Test
    fun anExactNameWinsOverAnEarlierPrefixMatch() {
        assertEquals(3, symbolContextCandidateIndex(names, "pos", cursorAtEnd = false))
        assertEquals(3, symbolContextCandidateIndex(names, "pos", cursorAtEnd = true))
    }

    @Test
    fun aPrefixMatchStandsInOnlyWhileTheWordIsBeingTyped() {
        assertEquals(2, symbolContextCandidateIndex(names, "posi", cursorAtEnd = true))
        assertEquals(-1, symbolContextCandidateIndex(names, "posi", cursorAtEnd = false))
    }
}
