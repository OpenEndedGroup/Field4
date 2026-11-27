package afield.lang.remotejs

import afield.ui.output.WEB_PAGE_NAME_PREFIX
import afield.ui.output.webPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which box a RemoteJS head's page belongs to, read from the head's id (the page's name). */
class WebPageHeadsTest {

    @Test
    fun aPageHeadNamesItsBoxAndAnOrdinaryHeadNamesNone() {
        assertEquals("page_root", WebPageHeads.pageBoxOf("${WEB_PAGE_NAME_PREFIX}page_root~7600192d"))
        assertEquals("1d771d57-9747-4777-871c-ef61916b03ec",
            WebPageHeads.pageBoxOf("${WEB_PAGE_NAME_PREFIX}1d771d57-9747-4777-871c-ef61916b03ec~abc"))
        assertNull(WebPageHeads.pageBoxOf("3f0c2a44-5c7e-4d4b-9a51-0d0c7a0e1b2f"))
        assertNull(WebPageHeads.pageBoxOf("${WEB_PAGE_NAME_PREFIX}d0b0f3e2-no-box"))
    }

    @Test
    fun webNamesItsPageWithItsBox() {
        val html = webPanel("https://example.com", 800, 500, boxId = "page_root")
        val name = Regex("data-field-live-name=\"([^\"]+)\"").find(html)!!.groupValues[1]
        assertEquals("page_root", WebPageHeads.pageBoxOf(name))
    }
}
