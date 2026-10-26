package afield.ui.slides

import afield.app.protocol.AudienceContent
import afield.document.graph.Box
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class PresentationSearchTest {
    @Test fun indexingDoesNotEvaluateFutureCustomTemplates() = runBlocking {
        var calls = 0
        val marker = "search-test-${java.util.UUID.randomUUID()}"
        afield.ui.output.templates.templates[marker] = { _, _ -> calls++; "A computed value" }
        try {
            val show = Box("templates").presentation {
                step("First") { audienceMarkdown("# First") }
                step("Template") { note("<p>{{$marker}}</p>") }
            }
            show.command("start")
            assertEquals(0, calls)
            assertTrue(marker in show.presentationState()!!.searchTexts[1])
            show.command("next")
            assertTrue(calls > 0, "the template still evaluates when its slide actually renders")
        } finally { afield.ui.output.templates.templates.remove(marker) }
    }

    @Test fun indexesAudienceAndRevealsWithoutCssScriptsAttributesOrPrivateNotes() = runBlocking {
        var actions = 0
        val show = Box("search").presentation {
            step("First") {
                audienceHtml("""<style>secretStyleToken{color:red}</style><script>secretScriptToken()</script><h1>Caf&eacute; &amp; waves</h1><img src="privatePath.svg" alt="Fourier flower"><p title="privateAttribute">Prediction: wave<em>length</em></p>""")
                markdownNote("presenterOnly")
                cue("Explain") { audienceMarkdown("The propagation speed stays constant."); onEnter { actions++ } }
                cue("Notes only") { markdownNote("privateCueNote") }
            }
            step("Guide note on screen") { markdownNote("# This actually reaches the audience") }
        }
        show.command("start")
        val text = show.presentationState()!!.searchTexts
        assertTrue("Café & waves" in text[0] && "Fourier flower" in text[0] && "propagation speed" in text[0], text[0])
        assertTrue("Prediction: wavelength" in text[0], text[0])
        for (privateText in listOf("secretStyleToken", "secretScriptToken", "privatePath", "privateAttribute", "presenterOnly", "privateCueNote")) {
            assertFalse(privateText in text[0], privateText)
        }
        assertTrue("This actually reaches the audience" in text[1])
        assertEquals(0, actions, "indexing must never enter a cue")
    }

    @Test fun aHundredSlideIndexIsCachedUntilRefreshAndDoesNotEnterFutureSlides() = runBlocking {
        var reads = 0
        var enters = 0
        val show = Box("hundred").slideShow {
            repeat(100) { index -> step("Slide $index") {
                audienceMarkdown("# Study $index")
                if (index == 99) audienceProvider = { reads++; AudienceContent(html = "<p>Only in the final body</p>") }
                onEnter { enters++ }
            } }
        }
        show.command("start")
        show.presentationOptions = PresentationOptions("Metadata")
        assertTrue(show.presentationState(includeSearchText = false)!!.searchTexts.isEmpty())
        assertEquals(0, reads, "metadata inspection must not compute the text index")
        assertEquals(100, show.presentationState()!!.searchTexts.size)
        assertTrue("Only in the final body" in show.presentationState()!!.searchTexts[99])
        assertEquals(1, reads)
        assertEquals(1, enters)
        show.command("refresh")
        assertEquals(2, reads)
        assertEquals(1, enters)
    }

    @Test fun refreshUpdatesFileBackedBodyAndCueSearchTextWithoutRebuilding() = runBlocking {
        val root = createTempDirectory("slide-search").toFile()
        try {
            val files = PresentationSlideFiles(root)
            val before = PresentationSlides(slides = listOf(PresentationSlide("one", "One", "# Before",
                cues = listOf(PresentationReveal("Answer", "A wavelength explanation")))))
            val saved = files.write("files/talk.slides.json", "", before)
            val show = Box("file-search").presentation { slides(root.resolve("files/talk.slides.json")) }
            show.command("start")
            assertTrue("wavelength" in show.presentationState()!!.searchTexts[0])
            files.write("files/talk.slides.json", saved.revision, before.copy(slides = listOf(before.slides[0].copy(markdown = "# A revised prediction"))))
            show.command("refresh")
            val text = show.presentationState()!!.searchTexts[0]
            assertTrue("revised prediction" in text && "wavelength" in text)
            assertFalse("Before" in text)
        } finally { root.deleteRecursively() }
    }

    @Test fun aMissingFutureSourceDoesNotBlockSearchAndIndexEntriesAreBounded() = runBlocking {
        val show = Box("bounds").presentation {
            step("Readable") { audienceHtml("<p>" + "x".repeat(20_000) + "</p>") }
            step("Missing") { audienceProvider = { error("Source removed") } }
        }
        show.command("start")
        assertEquals(PresentationSearch.MAX_TEXT, show.presentationState()!!.searchTexts[0].length)
        assertEquals("", show.presentationState()!!.searchTexts[1])
    }
}
