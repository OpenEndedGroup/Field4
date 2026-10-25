package afield.runtime.execution

import afield.document.graph.Box
import afield.runtime.mainLanguage
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A compile error in a language root says the root's own code is Kotlin and the language goes in its children. */
class LanguageRootHintTest {
    @Test
    fun aLanguageRootIsNamedAndAnOrdinaryBoxIsNot() {
        val root = Box("py-root").also { it.mainLanguage = "python" }
        val hint = languageRootHint(root)
        assertTrue(hint != null && "python root" in hint && "child" in hint, hint)
        assertNull(languageRootHint(Box("plain")))
    }
}
