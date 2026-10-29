package afield.ui.slides

import afield.document.graph.Box
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PresentationLessonsTest {
    @Test fun cueBackRefreshAndSlideJumpsDoNotReplayActions() = runBlocking {
        var actions = 0
        var resets = 0
        val show = Box("lesson").presentation {
            step("Predict", id = "predict") {
                audienceMarkdown("# Predict")
                cue("Reveal") { audienceMarkdown("# Explain"); onEnter { actions++ } }
                cue("Discuss") { audienceMarkdown("# Discuss") }
                checkpoint("Baseline") { resets++ }
            }
            step("Try") { audienceMarkdown("# Try") }
        }
        show.command("start")
        show.command("next")
        assertEquals(1, actions)
        assertEquals(0, show.currentStepIndex)
        assertEquals(1, show.currentCueIndex)
        assertTrue("Explain" in show.presentationState()!!.content!!.html)
        show.command("refresh")
        show.command("previous")
        show.command("next")
        assertEquals(1, actions)
        show.command("run-cue")
        assertEquals(2, actions)
        show.command("next")
        show.command("next")
        assertEquals(1, show.currentStepIndex)
        show.command("previous")
        assertEquals(2, show.currentCueIndex)
        assertEquals(2, actions)
        show.command("reset-checkpoint")
        assertEquals(0, show.currentCueIndex)
        assertEquals(1, resets)
        show.command("next")
        assertEquals(3, actions)
    }

    @Test fun failedCueDoesNotAdvanceAndRefreshNeverCallsOnEnter() = runBlocking {
        var enters = 0
        var fail = true
        val show = Box("failure").presentation {
            step("One") {
                onEnter { enters++ }
                cue("Action") { onEnter { check(!fail) { "Try again" } } }
            }
        }
        show.command("start")
        assertFailsWith<IllegalStateException> { show.command("next") }
        assertEquals(0, show.currentCueIndex)
        show.command("refresh")
        assertEquals(1, enters)
        fail = false
        show.command("next")
        assertEquals(1, show.currentCueIndex)
        assertFalse(show.hasNext())
        assertTrue(show.hasPrevious())
    }

    @Test fun sourceCheckpointRestoresOnlyDeclaredTextAndKeepsOtherEdits() {
        val box = Box("experiment")
        box.setPropertyRaw("code", "var frequency = 1.0", doResync = false)
        box.setPropertyRaw("caption", "before", doResync = false)
        val baseline = LessonCheckpoint.capture(box)
        box.setPropertyRaw("code", "var frequency = 2.0", doResync = false)
        box.setPropertyRaw("caption", "keep this discovery", doResync = false)
        baseline.restore()
        assertEquals("var frequency = 1.0", box.getPropertyRaw("code"))
        assertEquals("keep this discovery", box.getPropertyRaw("caption"))
        assertFailsWith<IllegalStateException> { LessonCheckpoint.capture(box, properties = listOf("missing")) }
    }

    @Test fun stableSlideIdentitiesAreUniqueAndSurviveInsertedSlides() = runBlocking {
        val host = Box("stable")
        val old = host.presentation { step("One", id = "one") { }; step("Two", id = "two") { } }
        old.command("1")
        val revised = host.presentation {
            step("Inserted", id = "inserted") { }
            step("One", id = "one") { }
            step("Two revised", id = "two") { }
        }
        assertEquals("two", revised.resumeStepId)
        assertEquals(2, revised.steps.indexOfFirst { it.id == revised.resumeStepId })
        assertFailsWith<IllegalArgumentException> { host.presentation { step("One", id = "same") { }; step("Two", id = "same") { } } }
        Unit
    }

    @Test fun aRemovedCheckpointTargetRefusesTheWholeReset() {
        val root = Box("checkpoint-root")
        val info = afield.app.server.SessionInformation(filename = "<checkpoint>", documentName = "checkpoint", root = root, ephemeral = true)
        root.inside = info
        val a = Box("a").also { it.inside = info; info.boxes[it.id] = it; it.setPropertyRaw("code", "before", doResync = false) }
        val b = Box("b").also { it.inside = info; info.boxes[it.id] = it; it.setPropertyRaw("code", "before", doResync = false) }
        val checkpoint = LessonCheckpoint.capture(a, b)
        a.setPropertyRaw("code", "keep", doResync = false)
        info.boxes.remove(b.id)
        assertFailsWith<IllegalStateException> { checkpoint.restore() }
        assertEquals("keep", a.getPropertyRaw("code"))
    }

    @Test fun editableSlideBodiesReloadWithoutRunningAnActionOrRebuildingTheTalk() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("editable-slides").toFile()
        try {
            val files = PresentationSlideFiles(root)
            val deck = PresentationSlides(slides = listOf(PresentationSlide("one", "One", "# Before",
                cues = listOf(PresentationReveal("Reveal", "# Reveal before")))))
            val saved = files.write("files/talk.slides.json", "", deck)
            val show = Box("file-slides").presentation { slides(root.resolve("files/talk.slides.json")) }
            show.command("start")
            assertTrue("Before" in show.presentationState()!!.content!!.html)
            files.write("files/talk.slides.json", saved.revision, deck.copy(slides = listOf(deck.slides[0].copy(markdown = "# Human edit"))))
            show.command("refresh")
            assertTrue("Human edit" in show.presentationState()!!.content!!.html)
            show.command("next")
            assertTrue("Reveal before" in show.presentationState()!!.content!!.html)
        } finally { root.deleteRecursively() }
    }
}
