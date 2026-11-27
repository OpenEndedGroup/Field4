package afield.ui.output

import java.io.File
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `web(...)`'s HTML and where a profile name puts its directory. */
class WebPanelTest {

    @Test
    fun bareProfileNamesLiveUnderWebProfiles() {
        assertEquals("web-profiles/github", webProfileSpec("github"))
        assertEquals("web-profiles/work", webProfileSpec("  work "))
        assertEquals("%project/web-profiles/github", webProfileSpec("%project/web-profiles/github"))
        assertEquals("logins/github", webProfileSpec("logins/github"))
        assertEquals("~/Profiles/x", webProfileSpec("~/Profiles/x"))
        assertFailsWith<IllegalArgumentException> { webProfileSpec("  ") }
    }

    @Test
    fun withoutAProfileThePageIsAnIframe() {
        val html = webPanel("https://example.com/?a=1&b=2", 800, 500)
        assertTrue("<iframe class=\"field-web-page\"" in html)
        assertTrue("src=\"https://example.com/?a=1&amp;b=2\"" in html)
        assertFalse("webview" in html)
        assertFalse("data-afield-intrinsic" in html)
    }

    @Test
    fun withAProfileThePageIsAWebviewNamingItsDirectory() {
        val directory = File("/tmp/doc/files/web-profiles/git hub")
        val html = webPanel("https://github.com", 800, 500, directory)
        assertTrue("<webview class=\"field-web-page\"" in html)
        assertFalse("<iframe" in html)
        val partition = Regex("partition=\"([^\"]+)\"").find(html)!!.groupValues[1]
        assertTrue(partition.startsWith(WEB_PROFILE_PARTITION))
        assertEquals(directory.absolutePath, URLDecoder.decode(partition.removePrefix(WEB_PROFILE_PARTITION), Charsets.UTF_8))
        assertTrue("data-afield-intrinsic-width=\"800\"" in html)
        assertTrue("data-afield-intrinsic-height=\"533\"" in html)
        assertTrue(">git hub</span>" in html)
        assertTrue("data-field-live-name=\"field-live-iframe-web-" in html)
    }
}
