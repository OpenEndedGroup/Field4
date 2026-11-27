package afield.ui.output

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `userScript` patterns: what a bare host and a `*` pattern match. Read by Javascript's RegExp in the page. */
class UserScriptPatternTest {

    private fun matches(pattern: String, url: String) = Regex(userScriptPattern(pattern)).matches(url)

    @Test
    fun aBareHostIsEveryPageOnIt() {
        assertTrue(matches("en.wikipedia.org", "https://en.wikipedia.org/wiki/HTML"))
        assertTrue(matches("en.wikipedia.org", "http://en.wikipedia.org"))
        assertTrue(matches("en.wikipedia.org", "https://en.wikipedia.org:8443/?q=1"))
        assertFalse(matches("en.wikipedia.org", "https://en.wikipedia.org.evil.example/"))
        assertFalse(matches("en.wikipedia.org", "https://enXwikipedia.org/"))
        assertFalse(matches("en.wikipedia.org", "https://fr.wikipedia.org/"))
    }

    @Test
    fun starMatchesAnythingAndTheRestIsLiteral() {
        val star = "*"
        assertTrue(matches("https://github.com/$star", "https://github.com/OpenEndedGroup/Field4"))
        assertFalse(matches("https://github.com/$star", "http://github.com/x"))
        assertTrue(matches("$star://$star.wikipedia.org/wiki/$star", "https://fr.wikipedia.org/wiki/CSS"))
        assertFalse(matches("https://a.b/c?d=$star", "https://aXb/c?d=1"))
        assertTrue(matches("https://a.b/c?d=$star", "https://a.b/c?d=1"))
    }

    @Test
    fun anEmptyPatternIsRefused() {
        assertFailsWith<IllegalArgumentException> { userScriptPattern(" ") }
    }
}
