package afield.ui.slides

import afield.document.graph.Box
import afield.ui.output.controlGroup
import afield.ui.output.setControl
import kotlinx.coroutines.runBlocking
import kotlinx.html.*
import kotlin.test.*

class PresentationFiguresTest {
    /** Launch and stop as the deck asks, without a Kotlin engine: a launched box is "running". */
    private class FakeRunner(val boxes: List<Box>) : FigureRunner {
        val running = mutableSetOf<String>()
        val launches = mutableListOf<String>()
        val stops = mutableListOf<String>()
        override fun box(host: Box, uid: String) = boxes.firstOrNull { it.id == uid }
        override fun isRunning(box: Box) = box.id in running
        override fun launch(boxes: List<Box>) { boxes.forEach { running += it.id; launches += it.name } }
        override fun stop(box: Box) { running -= box.id; stops += box.name }
    }

    private fun session(): afield.app.server.SessionInformation {
        val label = "figures-${System.nanoTime()}"
        val root = Box("$label-root")
        return afield.app.server.SessionInformation(filename = "<$label>", documentName = label, root = root, ephemeral = true)
            .also { root.inside = it; it.loading = false }
    }

    private fun afield.app.server.SessionInformation.box(name: String): Box =
        Box("$name-${System.nanoTime()}").also {
            it.inside = this
            boxes[it.id] = it
            it.name = name
            it.output.displayProxy = { _, _ -> }
        }

    /** A deck box and figure boxes called [names], in one document. */
    private fun deck(vararg names: String): Pair<Box, List<Box>> {
        val s = session()
        return s.box("deck") to names.map { s.box(it) }
    }

    private fun Box.toneControls(seen: MutableList<Double>) = controlGroup("controls") {
        slider("frequency", 100.0, 1000.0, initialValue = 220.0) { seen += it }
    }

    @Test fun aFigureSlotNamesItsBoxAndATypoFailsTheBuild() {
        val (host, figures) = deck("tone")
        val show = host.presentation {
            step("Tone") { audience { +"# A sine wave".md; figure("tone", "main", caption = "Drag the slider") } }
        }
        runBlocking { show.command("start") }
        val content = show.presentationState()!!.content!!
        val figure = content.figures.single()
        assertEquals(figures[0].id, figure.box)
        assertEquals("tone", figure.name)
        assertEquals(listOf("main"), figure.channels)
        assertTrue("data-field-figure=\"${figure.id}\"" in content.html)
        assertTrue("Drag the slider" in content.html)
        assertTrue("tone" in PresentationSearch.text(show.steps[0]))
        assertFailsWith<IllegalArgumentException> { host.presentation { step("Typo") { audience { figure("tome") } } } }
        host.inside.box("tone")
        assertFailsWith<IllegalArgumentException> { host.presentation { step("Twice") { audience { figure("tone") } } } }
    }

    @Test fun stepsAndCuesSetControlsOnEveryNavigationButRefreshLeavesTheAudiencesValue() = runBlocking {
        val (host, figures) = deck("tone")
        val tone = figures[0]
        val seen = mutableListOf<Double>()
        tone.toneControls(seen)
        val show = host.presentation {
            step("Low", id = "low") {
                audience { figure("tone") }
                control("tone", "frequency", 440.0)
                cue("Higher") { control("tone", "frequency", 880.0) }
            }
            step("Elsewhere", id = "elsewhere") { audienceMarkdown("# No figure") }
        }
        show.figureStage.runner = FakeRunner(figures)
        show.command("start")
        assertEquals(440.0, seen.last())
        show.command("next")
        assertEquals(880.0, seen.last())
        show.command("previous")
        assertEquals(440.0, seen.last())
        assertTrue(tone.setControl("frequency", 500.0))
        show.command("refresh")
        assertEquals(500.0, seen.last())
        show.command("next")
        assertEquals(880.0, seen.last())
        show.command("next")
        assertEquals(1, show.currentStepIndex)
        tone.setControl("frequency", 300.0)
        show.command("refresh")
        assertEquals(300.0, seen.last(), "a step that declares no value leaves the control alone")
        show.command("previous")
        assertEquals(880.0, seen.last(), "Back to the cue applies the step's value and then the cue's")
        assertFalse(tone.setControl("missing", 1.0))
    }

    @Test fun figuresRunWhileTheirSlideIsUpAndAHandLaunchedBoxIsLeftAlone() = runBlocking {
        val (host, figures) = deck("tone", "wave", "clock")
        val runner = FakeRunner(figures)
        runner.running += figures[2].id
        val show = host.presentation {
            step("Tone") { audience { figure("tone"); figure("clock") } }
            step("Wave") { audience { figure("wave") } }
            step("Tone again") { audience { figure("tone") } }
            step("Plain") { audienceMarkdown("# Words") }
        }
        show.figureStage.runner = runner
        show.command("start")
        assertEquals(listOf("tone"), runner.launches, "clock was already running")
        show.command("next")
        assertEquals(listOf("tone", "wave"), runner.launches)
        assertEquals(listOf("tone"), runner.stops)
        show.command("next")
        assertEquals(listOf("tone", "wave", "tone"), runner.launches, "the deck stopped tone, so it runs again")
        assertEquals(listOf("tone", "wave"), runner.stops)
        show.command("next")
        show.command("stop")
        assertEquals(listOf("tone", "wave", "tone"), runner.stops)
        assertTrue(figures[2].id in runner.running, "a box launched by hand keeps running")
    }

    @Test fun resetFiguresForgetsRememberedValuesAndRunsTheSlideAgain() = runBlocking {
        val (host, figures) = deck("tone")
        val tone = figures[0]
        val seen = mutableListOf<Double>()
        tone.toneControls(seen)
        val runner = object : FigureRunner by FakeRunner(figures) {
            var relaunched = 0
            override fun launch(boxes: List<Box>) { relaunched++; boxes.forEach { it.toneControls(seen) } }
        }
        val show = host.presentation {
            step("Tone") { audience { figure("tone") }; cue("Higher") { control("tone", "frequency", 880.0) } }
        }
        show.figureStage.runner = runner
        show.command("start")
        assertEquals("figures", show.presentationState()!!.checkpoint)
        show.command("next")
        tone.setControl("frequency", 600.0)
        tone.toneControls(seen)
        assertEquals(600.0, seen.last(), "a rerun keeps the audience's value")
        show.command("reset-checkpoint")
        assertEquals(0, show.currentCueIndex)
        assertEquals(220.0, afield.ui.output.controllerControlsForBox(tone.id).single().value,
            "after a reset the figure starts at its initial value")
        assertTrue(runner.relaunched >= 1)
    }

    @Test fun figureIsTheLiveFigureEvenWithKotlinxHtmlImported() = runBlocking {
        // kotlinx.html has its own figure(classes) builder; box code imports kotlinx.html.*, and a
        // slide written `figure("tone")` once silently became an empty <figure class="tone">.
        val (host, figures) = deck("tone")
        val show = host.presentation { step("Bleed") { audience { +"## A heading".md; figure("tone") } } }
        show.figureStage.runner = FakeRunner(figures)
        show.command("start")
        val content = show.presentationState()!!.content!!
        assertEquals(figures[0].id, content.figures.single().box)
        assertTrue("field-live-figure" in content.html && "class=\"tone\"" !in content.html)
    }

    @Test fun aRevealOverAFigureKeepsItLive() = runBlocking {
        val (host, _) = deck("tone")
        val show = host.presentation {
            step("Tone") {
                audience { figureBeside("tone") { +"# Listen".md } }
                reveal("Why") { +"Pitch is how often the wave repeats.".md }
            }
        }
        show.figureStage.runner = FakeRunner(emptyList())
        show.command("start")
        show.command("next")
        val content = show.presentationState()!!.content!!
        assertEquals(1, content.figures.size)
        assertTrue("field-figure-beside" in content.html && "how often" in content.html)
    }
}
