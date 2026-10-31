package afield.ui.slides

import afield.app.server.SessionInformation
import afield.app.server.documents
import afield.document.graph.Box
import kotlinx.coroutines.runBlocking
import kotlinx.html.h1
import kotlinx.html.p
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class PresentationBuildsTest {
    @Test fun revealsRetainTheActualPreviousViewAndBackRestoresIt() = runBlocking {
        val show = Box("layers").presentation {
            step("Layers") {
                audience { h1 { +"Question" } }
                reveal("Explain", dimPrevious = 0.2) { fadeIn { p { +"Answer" } } }
                reveal("Apply", dimPrevious = 0.4) { p { +"Application" } }
            }
        }
        show.command("start")
        show.command("next")
        val firstBuild = show.presentationState()!!.content!!.html
        assertTrue("Question" in firstBuild && "Answer" in firstBuild)
        assertTrue("opacity:0.2" in firstBuild && "field-retained" in firstBuild)
        show.command("next")
        val secondBuild = show.presentationState()!!.content!!.html
        assertTrue("Application" in secondBuild && firstBuild in secondBuild)
        show.command("previous")
        assertEquals(firstBuild, show.presentationState()!!.content!!.html)
        show.command("previous")
        assertFalse("Answer" in show.presentationState()!!.content!!.html)
    }

    @Test fun aReusableFragmentKeepsEscapingAndSharedImagePaths() {
        val previous = audienceFragment { fullScreenImage("one.svg", "A <wave>") }
        val show = Box("reuse").presentation {
            step("First") { audience(previous) }
            step("Then") { audienceOver(previous) { fadeIn(600, 100) { h1 { +"A new thought" } } } }
        }
        assertEquals(previous, show.steps.first().audience)
        val html = show.steps.last().audience!!.html
        assertTrue("A &lt;wave&gt;" in html && "one.svg" in html)
        assertTrue("--field-fade-duration:600ms;--field-fade-delay:100ms" in html)
        assertFailsWith<IllegalArgumentException> { audienceFragment { fadeIn(-1) { } } }
        assertFailsWith<IllegalArgumentException> { layerAudience(previous, previous, Double.NaN) }
        assertFailsWith<IllegalArgumentException> { Box("web").presentation {
            step("Web") { audienceWebpage("https://example.org"); reveal("Invalid") { } }
        } }
    }

    @Test fun markdownBuildsReloadTheirRetainedContentAndValidateAnimationBounds() = runBlocking {
        val root = createTempDirectory("markdown-builds").toFile()
        try {
            val files = PresentationSlideFiles(root)
            val deck = PresentationSlides(slides = listOf(PresentationSlide("one", "One", "# Before",
                cues = listOf(PresentationReveal("Add", "## New", retainPrevious = true, fadeMs = 500)))))
            val saved = files.write("files/talk.slides.json", "", deck)
            val show = Box("markdown-builds").presentation { slides(root.resolve("files/talk.slides.json")) }
            show.command("start"); show.command("next")
            assertTrue("Before" in show.presentationState()!!.content!!.html)
            files.write("files/talk.slides.json", saved.revision, deck.copy(slides = listOf(deck.slides[0].copy(markdown = "# Revised"))))
            show.command("refresh")
            val html = show.presentationState()!!.content!!.html
            assertTrue("Revised" in html && "New" in html && "field-fade-in" in html)
            assertFalse("Before" in html)
            assertFailsWith<IllegalArgumentException> { deck.copy(slides = listOf(deck.slides[0].copy(
                cues = listOf(PresentationReveal("Bad", "text", fadeMs = 10_001))))).validated() }
            Unit
        } finally { root.deleteRecursively() }
    }

    @Test fun theFileProducerRefusesReadOnlyDocumentsBeforeCreatingDirectories() {
        val root = createTempDirectory("slide-readonly").toFile()
        val session = SessionInformation(root.absolutePath, root.name, root = Box("root"), readOnly = true)
        documents[session.filename] = session
        try {
            assertFailsWith<IllegalStateException> {
                PresentationSlideFiles(root.resolve(".")).write("files/talk.slides.json", "", PresentationSlides())
            }
            assertEquals(emptyList(), root.listFiles()!!.toList())
        } finally { documents.remove(session.filename, session); root.deleteRecursively() }
    }
}
