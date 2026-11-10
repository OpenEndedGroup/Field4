package afield.ui.slides

import afield.app.protocol.ArrangeBoxesRequest
import afield.app.protocol.ArrangeBoxesResult
import afield.app.protocol.Message
import afield.app.protocol.Rect
import afield.app.protocol.Set_rect
import afield.app.protocol.Set_rects
import afield.document.graph.Box
import afield.ui.output.controlGroup
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Arrange Boxes by Presentation: the talk decides the order (the Presentation box, then figures in
 * order of first use, then everything else), a client decides the sizes, and the server applies
 * what the first client proposes.
 */
class PresentationArrangementTest {

    private val deliverBefore = PresentationArrangement.deliver

    @AfterTest fun restore() { PresentationArrangement.deliver = deliverBefore }

    private fun session(): afield.app.server.SessionInformation {
        val label = "arrange-${System.nanoTime()}"
        val root = Box("$label-root")
        return afield.app.server.SessionInformation(filename = "<$label>", documentName = label, root = root, ephemeral = true)
            .also { root.inside = it; it.loading = false; it.boxes[root.id] = root }
    }

    private fun afield.app.server.SessionInformation.box(name: String, y: Double = 0.0): Box =
        Box("$name-${System.nanoTime()}").also {
            it.inside = this
            boxes[it.id] = it
            it.name = name
            it.output.displayProxy = { _, _ -> }
            it.frame = Rect(0.0, y, 160.0, 50.0)
        }

    @Test fun theOrderIsTheDeckThenFiguresByFirstUseThenTheRest() {
        val s = session()
        val deck = s.box("deck", y = 500.0)
        val notes = s.box("notes", y = 300.0)
        val (a, b, c, d, e) = listOf("a", "b", "c", "d", "e").map { s.box(it) }
        val spare = s.box("spare", y = 100.0)
        c.controlGroup("controls") { slider("value", 0.0, 1.0) {} }
        val show = deck.presentation(title = "The talk") {
            step("Opening") { audience { figure("d") } }
            section("One") {
                step("First") { audience { figure("b") }; control("c", "value", 0.5) }
                step("Again") { audience { figure("d") } }
            }
            section("Two") { step("Second") { audience { figure("a") }; cue("More") { audience { figure("e") } } } }
            step("Further reading") { audienceMarkdown("# Books") }
        }
        assertEquals(
            listOf(deck, d, b, c, a, e, spare, notes).map { it.id },
            show.arrangementOrder(),
            "a box a control names counts as used; boxes no slide uses follow, top to bottom",
        )
    }

    @Test fun theFirstClientsFramesAreAppliedOnceAndEveryClientHearsThem() = runBlocking {
        val s = session()
        val deck = s.box("deck")
        val fig = s.box("fig")
        val show = deck.presentation(title = "Talk") { step("One") { audience { figure("fig") } } }
        val sent = mutableListOf<Message>()
        PresentationArrangement.deliver = { _, message ->
            sent += message
            if (message.name == "arrange-boxes") {
                val request = Json.decodeFromJsonElement<ArrangeBoxesRequest>(message.payload!!)
                assertEquals(listOf(deck.id, fig.id), request.boxes)
                val answer = ArrangeBoxesResult(request.id,
                    listOf(Set_rect(deck.id, "frame", Rect(10.0, 20.0, 160.0, 50.0)), Set_rect(fig.id, "frame", Rect(900.0, 20.0, 160.0, 50.0))))
                PresentationArrangement.complete(answer)
                // A second client's answer to the same request is ignored.
                PresentationArrangement.complete(answer.copy(frames = listOf(Set_rect(fig.id, "frame", Rect(0.0, 0.0, 1.0, 1.0)))))
            }
        }
        assertEquals(2, PresentationArrangement.arrange(show, runFigures = false, timeoutMs = 5_000))
        assertEquals(Rect(900.0, 20.0, 160.0, 50.0), fig.frame)
        val rects = sent.filter { it.name == "set-rects" }.map { Json.decodeFromJsonElement<Set_rects>(it.payload!!) }
        assertEquals(setOf(deck.id, fig.id), rects.single().values.map { it.uid }.toSet())
        assertEquals(0, PresentationArrangement.arrange(show, runFigures = false, timeoutMs = 5_000), "arranging again moves nothing")
    }

    @Test fun withNoCanvasTheRequestWaitsAndTheFirstCanvasToOpenTheDocumentArrangesIt() = runBlocking {
        val s = session()
        val deck = s.box("deck")
        val fig = s.box("fig")
        // A figure whose panel was left collapsed: it must be measured, and left, open.
        fig.output.retainedAreas.put("scene", "<div>scene</div>")
        fig.output.retainedAreas.control("close", "scene")
        val show = deck.presentation(title = "Talk") { step("One") { audience { figure("fig") } } }
        PresentationArrangement.deliver = { _, _ -> }
        assertEquals(-1, PresentationArrangement.arrange(show, runFigures = false, timeoutMs = 200), "no canvas answered")
        val opened = fig.output.retainedAreas.snapshot(fig.id).filter { it.name == "output-control" }
            .map { Json.decodeFromJsonElement<afield.app.protocol.OutputControl>(it.payload!!) }
        assertEquals(listOf("open"), opened.map { it.action }, "the collapsed panel is opened before measuring")

        // A canvas attaches later: it is sent the parked request, and its answer is applied.
        val parked = PresentationArrangement.parkedRequestFor(s)!!
        val request = Json.decodeFromJsonElement<ArrangeBoxesRequest>(parked.payload!!)
        PresentationArrangement.complete(ArrangeBoxesResult(request.id, listOf(Set_rect(fig.id, "frame", Rect(900.0, 0.0, 160.0, 50.0)))))
        assertEquals(Rect(900.0, 0.0, 160.0, 50.0), fig.frame)
        assertEquals(null, PresentationArrangement.parkedRequestFor(s), "answered once, it no longer waits")
    }
}
