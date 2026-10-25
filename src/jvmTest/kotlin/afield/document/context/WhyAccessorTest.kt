package afield.document.context

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.properties.prop
import afield.geometry.mesh.summary
import afield.graphics.render.label
import afield.graphics.render.rangeTo
import afield.lang.stacksl.evolution.render
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times

import afield.data.collections.s
import afield.document.graph.Marker
import afield.document.properties.clear
import afield.document.workspace.path
import afield.math.linalg.set
import afield.document.graph.Box
import afield.document.context.ContextResolution
import afield.ui.visualization.LazyValueVisualizer
import afield.app.server.SessionInformation
import afield.ui.visualization.h
import afield.ui.visualization.h2
import afield.document.graph.needsResync
import afield.app.server.thisSessionInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a `prop<Double>(speed)` writes into a preamble for [Why]; declared here the same way. */
private val Why.speed by Whying()

/**
 * `why.speed` (the accessor `prop` generates) and `ContextResolution.explain(box, "speed")` (the
 * spelling for everything else) are one answer, with one `.h` and one `.h2`; the accessor adds no
 * walk of its own. The graph is the one
 * `ContextWhyTest` uses: a child with two equidistant parents that disagree, one of which has a parent
 * of its own.
 */
class WhyAccessorTest {
    class Marker(val label: String)

    private val made = mutableListOf<Box>()

    @AfterTest
    fun cleanUp() {
        val mine = made.toSet()
        needsResync.removeAll { (box, _) -> box in mine }
        made.clear()
        thisSessionInfo.remove()
    }

    private fun child(): Box {
        val root = Box("why-accessor-root")
        val s = SessionInformation(filename = "<why-accessor>", documentName = "why-accessor", root = root, ephemeral = true)
        root.inside = s
        s.loading = false
        thisSessionInfo.set(s)
        fun box(id: String) = Box(id).also { it.inside = s; it.name = id; s.boxes[id] = it; made += it }
        val grand = box("grand"); val a = box("a"); val b = box("b"); val child = box("child")
        a.connect(child); b.connect(child); grand.connect(a)
        a.setPropertyRaw("speed", 1.0); b.setPropertyRaw("speed", 2.0); grand.setPropertyRaw("speed", 9.0)
        grand.setPropertyRaw("marker", Marker("grand"))
        made += root
        return child
    }

    @Test
    fun theAccessorTheCallAndExplainAgree() {
        val child = child()
        val explained = ContextResolution.explain(child, name = "speed").json
        assertEquals(explained, child.why.speed.json, "why.speed is explain's answer")
        assertEquals(explained, afield.document.context.ContextWhy.explain(child, "speed"), "explain adds nothing to the JSON the backdoor sends")
        assertEquals(ContextResolution.explain(child, "speed").h, child.why.speed.h, "one .h for both spellings")

        val answer = child.why.speed
        assertTrue(answer.found)
        assertEquals("a", answer.supplier)
        assertEquals(listOf("child", "a"), answer.path)
        assertEquals(listOf("b"), answer.competing)
        assertTrue(answer.shadowed.containsAll(listOf("b", "grand")), answer.shadowed.toString())
        assertEquals(explained["summary"]!!.toString().trim('"'), answer.toString(), "print(why.speed) is the summary")
    }

    @Test
    fun aTypeIsAskedThroughExplainAndRendersTheSame() {
        val child = child()
        val answer = ContextResolution.explain(child, type = Marker::class.java.name)
        assertEquals("grand", answer.supplier)
        assertTrue("grand" in answer.h)
    }

    @Test
    fun hIsAMarkdownCardOfTheSameAnswer() {
        val answer = child().why.speed
        val md = answer.markdown
        assertTrue(md.startsWith("| why `speed` | |\n| --- | --- |\n"), md)
        assertTrue("| supplier | a (property) = 1\\.0 |" in md, md)
        assertTrue("| competing | b |" in md, md)
        assertTrue(md.trimEnd().endsWith(answer.summaryMarkdown), md)
        assertTrue("`speed`" in answer.summaryMarkdown, "the sentence's code spans stay code: " + answer.summaryMarkdown)
        assertFalse("\\`" in answer.summaryMarkdown, answer.summaryMarkdown)
        val html = answer.h
        assertTrue("class=\"field-md\"" in html && "<table" in html, html)
        assertFalse("<pre" in html, "rendered from Markdown, not a hand-built <pre>")
        assertTrue("grand" in html)
        assertTrue("<code>speed</code>" in html, "backticks in the summary render as inline code")
    }

    @Test
    fun h2ExploresTheFieldsUnderTheSummary() {
        afield.document.context.registerWhyH2()
        val answer = child().why.speed
        val html = answer.h2
        assertTrue("reads `speed`" in html, html.take(400))
        assertTrue(LazyValueVisualizer.visualize(answer).isNotBlank())
    }

    @Test
    fun anUnsuppliedNameSaysSo() {
        val answer = ContextResolution.explain(child(), "nothingHasThis")
        assertFalse(answer.found)
        assertTrue("nothing supplies" in answer.markdown, answer.markdown)
    }
}
