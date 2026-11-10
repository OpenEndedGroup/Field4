package afield.ui.slides

import afield.app.protocol.ArrangeBoxesRequest
import afield.app.protocol.ArrangeBoxesResult
import afield.app.protocol.AudienceContent
import afield.app.protocol.Message
import afield.app.protocol.Notify
import afield.app.protocol.Set_rect
import afield.app.protocol.Set_rects
import afield.app.protocol._send
import afield.app.protocol.payload
import afield.app.server.SessionInformation
import afield.app.server.launchBoxesInOrder
import afield.document.graph.Box
import afield.document.graph.nestedIn
import afield.tools.automation.OperationTranscript
import afield.ui.output.displayOpen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Arrange Boxes by Presentation: put a talk's boxes in one row, in slide order, with room for what
 * they show.
 *
 * [arrangementOrder] is the Presentation box, then every figure box in the order the slides first
 * use it (shown in a slot, or named by a `control`), then the document's other boxes. Each box goes
 * after the previous one's footprint: its frame and every open output panel, plus a gap. A figure's
 * panels are most of that footprint, and only a client knows how big they are drawn, so the server
 * sends the order to the document's canvas clients (`arrange-boxes`); the first to answer measures
 * each box as Pack Boxes does and proposes frames (`arrange-boxes-result`, `BoxLayout.arrange` in
 * the frontend); the server applies them, records every move for one undo and tells every client,
 * so the arrangement is saved with the document.
 *
 * A figure that has not run has no panels yet, so [arrange] first runs the figure boxes that have
 * shown nothing, waits for their output, and stops those it started.
 */
object PresentationArrangement {

    private val pending = ConcurrentHashMap<String, CompletableDeferred<ArrangeBoxesResult>>()

    /** How a message reaches the document's clients; tests replace it. */
    internal var deliver: (SessionInformation, Message) -> Unit = { info, message -> info.sessions.forEach { _send(it, message) } }

    /**
     * Requests no canvas has answered yet, by document: the deck ran before any canvas had its
     * document open (a transcript runs it headless). Each is held as its message, nothing waiting on
     * it, and sent again to every client that attaches to that document; the first canvas to answer
     * completes it. A later arrangement of the same document replaces it.
     */
    private val parked = ConcurrentHashMap<SessionInformation, Pair<String, Message>>()

    /** A client answered; the first answer to a request wins and later ones are ignored. */
    fun complete(result: ArrangeBoxesResult) {
        pending.remove(result.id)?.let { it.complete(result); return }
        val entry = parked.entries.firstOrNull { it.value.first == result.id } ?: return
        if (!parked.remove(entry.key, entry.value)) return
        val moved = apply(entry.key, result)
        tell(entry.key, "Arranged ${result.frames.size} boxes in slide order; moved $moved")
    }

    /** The parked request for [info], if any: what a client attaching to that document is sent. */
    fun parkedRequestFor(info: SessionInformation): Message? = parked[info]?.second

    /**
     * Arranges [show]'s document and returns how many boxes moved, or -1 when no canvas answered: the
     * request then waits, costing nothing, for the next canvas to open the document.
     *
     * @param runFigures run figure boxes that have no output yet, so that their panels can be measured.
     * @param timeoutMs how long to wait for the figures' output (at least five seconds a figure), and
     * then for a client's answer.
     */
    suspend fun arrange(show: SlideShowInstance, runFigures: Boolean = true, timeoutMs: Long = 20_000): Int {
        val host = show.hostBox
        if (!host.isInsideInitialized()) return -1
        val info = host.inside
        val order = show.arrangementOrder()
        if (runFigures) runSilentFigures(show, order, timeoutMs)

        // A deck document shows its figures: lay out the footprint each has with its panels open,
        // and leave them open, rather than measure a collapsed header and be covered when it opens.
        order.mapNotNull { info.boxes[it] }.forEach { box -> box.output.retainedAreas.channels().forEach { box.displayOpen(it) } }

        val id = UUID.randomUUID().toString()
        val request = Message("arrange-boxes").payload(ArrangeBoxesRequest(id, order))
        val answer = CompletableDeferred<ArrangeBoxesResult>()
        pending[id] = answer
        parked.remove(info)
        deliver(info, request)
        val result = withTimeoutOrNull(timeoutMs) { answer.await() }
        pending.remove(id)
        if (result == null) {
            // No canvas has the document open yet; arrange it when one does.
            parked[info] = id to request
            return -1
        }
        val moved = apply(info, result)
        tell(info, "Arranged ${order.size} boxes in slide order; moved $moved")
        return moved
    }

    /** Sets the frames a client proposed, recording each move for undo, and tells every client. */
    internal fun apply(info: SessionInformation, result: ArrangeBoxesResult): Int {
        val applied = mutableListOf<Set_rect>()
        result.frames.forEach { update ->
            val box = info.boxes[update.uid] ?: return@forEach
            val old = box.frame
            if (old == update.value) return@forEach
            box.frame = update.value
            OperationTranscript.recordMove(box, old, box.frame)
            applied += Set_rect(update.uid, "frame", box.frame)
        }
        if (applied.isNotEmpty()) deliver(info, Message("set-rects").payload(Set_rects(applied)))
        return applied.size
    }

    private suspend fun runSilentFigures(show: SlideShowInstance, order: List<String>, timeoutMs: Long) {
        val info = show.hostBox.inside
        val figures = show.steps.flatMap { listOf(it) + it.cues }.flatMap { it.figureBoxIds() }.toSet()
        val quiet = order.filter { it in figures }.mapNotNull { info.boxes[it] }
            .filter { it.output.retainedAreas.channels().isEmpty() && !FieldFigureRunner.isRunning(it) }
        if (quiet.isEmpty()) return
        launchBoxesInOrder(quiet)
        // The boxes compile one after another, so allow each its own few seconds.
        val deadline = System.currentTimeMillis() + maxOf(timeoutMs, 5_000L * quiet.size)
        while (System.currentTimeMillis() < deadline && quiet.any { it.output.retainedAreas.channels().isEmpty() }) delay(100)
        // Let late panels (a second channel, a control group) arrive before anything is measured.
        delay(600)
        val showing = show.currentStep?.let { step -> (listOf(step) + step.cues).flatMap { it.figureBoxIds() }.toSet() }.orEmpty()
        quiet.filter { it.id !in showing && FieldFigureRunner.isRunning(it) }.forEach { FieldFigureRunner.stop(it) }
    }

    private fun tell(info: SessionInformation, text: String) =
        deliver(info, Message("notify").payload(Notify(text, "layout")))
}

/** The figure boxes [this] step's audience shows and its controls name, in that order. */
internal fun SlideStep.figureBoxIds(): List<String> {
    val content: AudienceContent? = audience ?: runCatching { audienceProvider?.invoke() }.getOrNull()
    return (content?.figures.orEmpty().map { it.box } + controls.map { it.box.id }).filter { it.isNotEmpty() }
}

/**
 * The order Arrange Boxes by Presentation lays boxes out in: the Presentation box, every figure box in
 * the order the slides first use it (a step, then its cues), then the document's other top-level
 * boxes, top to bottom and left to right as they are now.
 */
fun SlideShowInstance.arrangementOrder(): List<String> {
    val order = LinkedHashSet<String>()
    order += hostBox.id
    steps.forEach { step -> (listOf(step) + step.cues).flatMap { it.figureBoxIds() }.forEach { order += it } }
    if (hostBox.isInsideInitialized()) {
        val info = hostBox.inside
        info.boxes.values
            .filter { it !== info.root && it.id !in order && it.nestedIn == null && it.getPropertyRaw("_tombstone") != true && it.frame.w > 0.0 }
            .sortedWith(compareBy({ it.frame.y }, { it.frame.x }))
            .forEach { order += it.id }
    }
    return order.toList()
}

/**
 * Puts this presentation's boxes in one row in slide order (see [PresentationArrangement]): write
 * `presentation { ... }.arrangeBoxes().present()` and the document opens with every figure's output
 * beside the next, none covering another. It works in the background once a canvas has the
 * document open, and returns at once.
 */
@OptIn(DelicateCoroutinesApi::class)
fun SlideShowInstance.arrangeBoxes(runFigures: Boolean = true): SlideShowInstance {
    GlobalScope.launch { PresentationArrangement.arrange(this@arrangeBoxes, runFigures) }
    return this
}
