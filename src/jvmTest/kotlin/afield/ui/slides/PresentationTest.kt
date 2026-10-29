package afield.ui.slides

import afield.document.graph.Box
import afield.ui.output.md
import afield.ui.output.mdEscape
import kotlinx.coroutines.runBlocking
import kotlinx.html.h1
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.style
import kotlinx.html.ul
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresentationTest {
    @Test
    fun nestedSectionsPreservePlaybackOrderAndPublishTheirPaths() = runBlocking {
        val show = Box("outline").presentation {
            step("Opening") { }
            section("Movement") {
                step("Position") { }
                section("Experiments") { repeat(100) { step("Trial $it") { } } }
                step("Discuss") { }
            }
            step("Closing") { }
        }
        assertEquals(104, show.steps.size)
        assertEquals(listOf("Movement", "Experiments"), show.steps[101].sectionPath)
        assertEquals(listOf("Movement"), show.steps[102].sectionPath)
        assertTrue(show.steps.last().sectionPath.isEmpty())
        show.command("101")
        assertEquals("Trial 99", show.currentStep!!.title)
        assertEquals(show.steps.map { it.sectionPath }, show.presentationState()!!.sectionPaths)
        show.command("next")
        assertEquals("Discuss", show.currentStep!!.title)
    }

    @Test
    fun markdownFragmentsRenderAlongsideEscapedHtmlAndImages() {
        assertEquals(mdEscape("_literal_"), "_literal_".md)
        val show = Box("mixed").presentation {
            step("Mixed") {
                audience {
                    style = "color:white"
                    +"""
                        # Some Markdown
                        Here is _proper_ formatting.

                        1. foo
                        2. bar
                    """.md
                    p { +"<plain text>" }
                    div { +"## Nested Markdown".md }
                    imageBeside("wave.svg", alt = "A wave", citation = "A & B") {
                        +"## Beside the figure".md
                    }
                }
            }
        }
        val html = show.steps.single().audience!!.html
        assertTrue("<h1>Some Markdown</h1>" in html, html)
        assertTrue("<em>proper</em>" in html, html)
        assertTrue("<ol>" in html && "<li>foo</li>" in html, html)
        assertTrue("&lt;plain text&gt;" in html, html)
        assertTrue("<h2>Nested Markdown</h2>" in html, html)
        assertTrue("<h2>Beside the figure</h2>" in html, html)
        assertTrue("<figcaption>A &amp; B</figcaption>" in html, html)
    }

    @Test
    fun imageLayoutsAndLinkedStylesheetsKeepTheirAttributes() {
        val show = Box("image").presentation {
            step("Full image") {
                audience {
                    stylesheet("presentation.css")
                    fullScreenImage("a b.svg", alt = "<wave>", citation = "Field", fit = AudienceImageFit.Cover)
                }
            }
        }
        val html = show.steps.single().audience!!.html
        assertTrue("data-field-presentation-style" in html, html)
        assertTrue("href=\"presentation.css\"" in html, html)
        assertTrue("field-image-full" in html && "object-fit:cover" in html, html)
        assertTrue("alt=\"&lt;wave&gt;\"" in html, html)
    }

    @Test
    fun standardHtmlBuildersEscapeTextAndKeepKotlinControlFlow() {
        val show = Box("html").presentation {
            step("Hello") {
                audience {
                    h1 { +"<hello> & friends" }
                    ul { repeat(3) { li { +"item $it" } } }
                }
            }
        }
        val html = show.steps.single().audience!!.html
        assertTrue("&lt;hello&gt; &amp; friends" in html, html)
        assertEquals(3, Regex("<li>").findAll(html).count())
    }

    @Test
    fun presentationUsesGuideLifecycleAndRemembersTheStepWhenRebuilt() = runBlocking {
        val host = Box("talk")
        val actions = mutableListOf<String>()
        val show = host.presentation(title = "Teaching") {
            step("One") { audienceMarkdown("# Hello"); onEnter { actions += "enter one" }; onExit { actions += "exit one" } }
            step("Two") { audienceYouTube("M7lc1UVf-VE"); onEnter { actions += "enter two" } }
        }
        show.command("start")
        assertEquals("html", show.presentationState()!!.content!!.type)
        show.command("next")
        show.command("next") // Boundary navigation must not replay callbacks.
        assertEquals(listOf("enter one", "exit one", "enter two"), actions)
        assertEquals(listOf("One", "Two"), show.presentationState()!!.titles)
        assertEquals("youtube", show.presentationState()!!.content!!.type)
        val revised = host.presentation { step("Only") { audienceMarkdown("# Revised") } }
        assertEquals(show.slideshowId, revised.slideshowId)
        assertEquals(1, revised.resumeStep)
        show.command("stop")
        assertEquals(-1, show.presentationState()!!.index)
        assertNull(show.presentationState()!!.content)
    }

    @Test
    fun existingGuidesRemainLocalAndCanSupplyTheirNotesToAnAudience() = runBlocking {
        val show = Box("guide").slideShow { step("Existing note") { markdownNote("# Guide") } }
        show.start()
        assertNull(show.presentationState())
        show.presentationOptions = PresentationOptions("Guide on stage")
        assertTrue("<h1>Guide</h1>" in show.presentationState()!!.content!!.html)
    }

    @Test
    fun presenterCommandsKeepTheHostContextAndRestoreTheCaller() = runBlocking {
        val host = Box("talk-context")
        val caller = Box("caller")
        val observed = mutableListOf<Box?>()
        val show = host.presentation {
            step("One") { onEnter { observed += Box.context.get() }; onExit { observed += Box.context.get() } }
            step("Two") { onEnter { observed += Box.context.get() } }
        }
        Box.withBoxContext(caller) {
            show.command("start")
            show.command("next")
            assertEquals(caller, Box.context.get())
        }
        assertEquals(listOf<Box?>(host, host, host), observed)
    }

    @Test
    fun rejectInvalidExternalContentAndEmptyTalksBeforeTheyReachElectron() {
        val host = Box("validation")
        assertFailsWith<IllegalArgumentException> { host.presentation { } }
        assertFailsWith<IllegalArgumentException> { PresentationOptions("bad", -1) }
        assertFailsWith<IllegalArgumentException> { host.presentation { step("bad") { audienceWebpage("file:///tmp/private") } } }
        assertFailsWith<IllegalArgumentException> { host.presentation { step("bad") { audienceDocument("../other") } } }
        assertFailsWith<IllegalArgumentException> { host.presentation { step("bad") { audienceYouTube("not an ID") } } }
        assertFailsWith<IllegalArgumentException> { host.presentation { step("bad") { audienceWebpage("https://example.org", listOf(.5, .5, .8, .8)) } } }
    }
}
