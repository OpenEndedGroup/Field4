package afield.ui.slides

import afield.app.server.home
import afield.app.server.sessions
import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem

import afield.app.server.SessionInformation
import afield.document.graph.Box
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The slideshow DSL without a browser: which guide a note link drives, where a note is anchored,
 * and what the `[[...]]` shortcuts expand to. Messages go nowhere (no websocket sessions exist).
 */
class SlideShowTest {

    private fun document(label: String): Pair<SessionInformation, Box> {
        val root = Box("$label-root")
        val s = SessionInformation(filename = "<slideshow-$label>", documentName = label, root = root, ephemeral = true)
        root.inside = s
        s.loading = false
        return s to root
    }

    private fun boxIn(s: SessionInformation, id: String): Box =
        Box(id).also {
            it.inside = s
            s.boxes[id] = it
        }

    @Test
    fun linksDriveTheGuideOnScreenNotTheFirstGuideInTheDocument() = runBlocking {
        val (s, root) = document("two-guides")
        val first = boxIn(s, "guide-a").slideShow { step("a1") { note("a1") }; step("a2") { note("a2") } }
        val second = boxIn(s, "guide-b").slideShow { step("b1") { note("b1") }; step("b2") { note("b2") } }

        assertNull(SlideShowInstance.activeIn(root))
        first.start()
        assertSame(first, SlideShowInstance.activeIn(root))
        second.start()
        assertSame(second, SlideShowInstance.activeIn(root), "the most recently shown guide owns the links")

        second.stop()
        assertNull(SlideShowInstance.activeIn(root), "stopping the guide on screen releases the links")
        first.stop() // stopping one that is no longer active must not clear another's claim
        second.start(); first.stop()
        assertSame(second, SlideShowInstance.activeIn(root))
    }

    @Test
    fun anchorNoteToSurvivesALaterNote() {
        val (s, _) = document("anchor")
        val host = boxIn(s, "host")
        val other = boxIn(s, "other")
        val before = host.slideShow { step("x") { anchorNoteTo(other); markdownNote("hello") } }
        assertSame(other, before.steps[0].noteAnchorBox)
        val after = host.slideShow { step("x") { markdownNote("hello"); anchorNoteTo(other) } }
        assertSame(other, after.steps[0].noteAnchorBox)
        val default = host.slideShow { step("x") { markdownNote("hello") } }
        assertSame(host, default.steps[0].noteAnchorBox)
    }

    @Test
    fun navShortcutsExpandToSlideshowLinks() {
        val html = SlideMarkdown.render("[[back]] [[next:Onward]] [[home]] [[finish]]")
        assertTrue("href=\"#slideshow:previous\"" in html, html)
        assertTrue("href=\"#slideshow:next\">Onward<" in html, html)
        assertTrue("href=\"#slideshow:home\"" in html, html)
        assertTrue("href=\"#slideshow:stop\">Finish<" in html, html)
        assertEquals(1, Regex("#slideshow:next").findAll(html).count())
    }

    @Test
    fun templateValuesFromPropertiesAreHtmlEscaped() {
        val (s, _) = document("escape")
        val host = boxIn(s, "host")
        host.properties["who"] = "<img src=x onerror=alert(1)> & \"friends\""
        host.properties["count"] = 3
        val html = slideTemplateValues(host, "<p>Hello {{who}}, {{count}} visits</p>")
        assertEquals("<p>Hello &lt;img src=x onerror=alert(1)&gt; &amp; &quot;friends&quot;, 3 visits</p>", html)
    }
}
