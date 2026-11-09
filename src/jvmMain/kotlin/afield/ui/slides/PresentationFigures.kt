package afield.ui.slides

import afield.app.protocol.AudienceContent
import afield.app.protocol.AudienceFigure
import afield.app.diagnostics.logging.lprintln
import afield.app.server.Server
import afield.app.server.launchBoxesInOrder
import afield.document.graph.Box
import afield.ui.output.forgetControlValues
import afield.ui.output.glideControl
import afield.ui.output.setControl
import kotlinx.coroutines.Job
import kotlinx.html.div
import kotlinx.html.figcaption
import kotlinx.html.figure
import kotlinx.html.style
import java.util.concurrent.atomic.AtomicLong

/**
 * Live figures on presentation slides: a box's output, with its controls, placed beside prose.
 *
 * An explainer mixes linking text with figures the audience can change. The figures are ordinary
 * boxes in the presentation's own document, addressed by name. [AudienceBuilder.figure] writes a
 * slot into an HTML slide and names the box whose output fills it; [AudienceBuilder.figureBeside]
 * is the prose-and-figure layout. In Field's Electron app each slot is a native view of that box's
 * output alone (the Field page's `?figure=` mode), laid over the slot the audience window measured,
 * so sliders, drawings and plots are the real, live output and a slider moved by the audience moves
 * the box. Two slots naming one box with different channels share that box's state, which is how
 * one slider drives two figures.
 *
 * [SlideStepBuilder.control] sets a figure's control as part of a step or cue: Next, Back and a jump
 * all apply the values the view declares, in source order, and a `glideMs` slides a slider there.
 * Refresh leaves the controls where the audience put them.
 *
 * The deck runs its figures. Entering a view launches every figure box on it that is not already
 * running and has not shown any output, or that the deck itself stopped; leaving a slide stops the
 * figures the deck launched that are still running and that the next slide does not show. An
 * animated or sounding figure is therefore live only while its slide is up, and a box launched by
 * hand is never stopped by the deck. A slide with figures and no checkpoint of its own offers
 * **Reset: figures**, which forgets every remembered control value of its figures, runs them again
 * and applies the slide's control values.
 *
 * Each view's content (HTML plus [AudienceFigure]s) and its control values are everything an
 * export to the public site needs: prose, then each figure captured at those values.
 * `dev_docs/EXPLAINER_PRESENTATIONS.md` records that design.
 */

private val figureSlotCounter = AtomicLong()

/**
 * A live figure: the output of the box called [name] fills this slot on the audience display.
 *
 * [channels] shows only those output channels (empty shows all of them); [height] is the slot's
 * height in the 1920 × 1080 slide frame, and its width is that of its container. A [caption] is
 * plain text beneath it. The box is looked up by name when the slide is attached to a step, so a
 * typo fails the Presentation box rather than showing an empty slot.
 */
fun AudienceBuilder.figure(name: String, vararg channels: String, height: Int = 720, caption: String = "") {
    require(name.isNotBlank() && name.length <= 256) { "A figure needs the name of a box" }
    require(channels.size <= 16 && channels.all { it.isNotBlank() && it.length <= 128 && it.none { c -> c.code < 32 || c == ',' } }) {
        "Name at most 16 output channels"
    }
    require(height in 60..1080) { "A figure's height is between 60 and 1080 slide pixels" }
    require(figures.size < 8) { "Use at most eight figures on one slide" }
    val id = "figure-${figureSlotCounter.incrementAndGet()}"
    figures += AudienceFigure(id = id, box = "", name = name, channels = channels.toList())
    figure(classes = "field-live-figure") {
        style = "height:${height}px"
        div(classes = "field-live-figure-slot") { attributes["data-field-figure"] = id }
        if (caption.isNotBlank()) figcaption { +caption }
    }
}

/** Prose beside a live figure: the slide's two-column layout, with the figure on the right by default. */
fun AudienceBuilder.figureBeside(
    name: String, vararg channels: String, figureOnLeft: Boolean = false, height: Int = 800, caption: String = "",
    block: AudienceBuilder.() -> Unit
) {
    div(classes = "field-figure-beside") {
        fun figureColumn() = div(classes = "field-figure-column") { figure(name, *channels, height = height, caption = caption) }
        fun textColumn() = div(classes = "field-figure-text") { block() }
        if (figureOnLeft) { figureColumn(); textColumn() } else { textColumn(); figureColumn() }
    }
}

/** A control value a step or cue declares for one of its figures. */
data class FigureControl(
    val figure: String, val box: Box, val label: String, val channel: String,
    val number: Double? = null, val flag: Boolean? = null, val glideMs: Int = 0
)

/**
 * On this view, the control labelled [label] in the [channel] control group of the box called
 * [figure] is [value]. Applied on Next, Back and jumps (not Refresh), after the view's figures run;
 * [glideMs] slides it there along the control's own travel.
 */
fun SlideStepBuilder.control(figure: String, label: String, value: Double, channel: String = "controls", glideMs: Int = 0) {
    require(value.isFinite()) { "A control value must be finite" }
    require(glideMs in 0..60_000) { "glideMs must be between 0 and 60000" }
    controls += FigureControl(figure, findFigureBox(hostBox, figure), label, channel, number = value, glideMs = glideMs)
}

/** On this view, the checkbox labelled [label] in the box called [figure] is [value]. */
fun SlideStepBuilder.control(figure: String, label: String, value: Boolean, channel: String = "controls") {
    controls += FigureControl(figure, findFigureBox(hostBox, figure), label, channel, flag = value)
}

/** The single live box called [name] in [host]'s document; a missing or ambiguous name fails here. */
internal fun findFigureBox(host: Box, name: String): Box {
    val candidates = if (host.isInsideInitialized()) {
        (host.inside.boxes.values + host).distinct()
    } else {
        val seen = LinkedHashSet<Box>()
        val queue = ArrayDeque(listOf(host))
        while (queue.isNotEmpty()) { val next = queue.removeFirst(); if (seen.add(next)) queue.addAll(next.children) }
        seen.toList()
    }
    val matches = candidates.filter { it.name == name && it.getPropertyRaw("_tombstone") != true }
    require(matches.isNotEmpty()) { "No box called '$name' for this figure; name the box in this document that draws it" }
    require(matches.size == 1) { "${matches.size} boxes are called '$name'; a figure needs a unique box name" }
    return matches.single()
}

/** Fill in each figure's box uid from its name; a content with no figures is returned unchanged. */
internal fun resolveFigures(host: Box, content: AudienceContent): AudienceContent {
    if (content.figures.isEmpty()) return content
    require(content.type == "html") { "Figures belong on HTML slides" }
    return content.copy(figures = content.figures.map { if (it.box.isNotEmpty()) it else it.copy(box = findFigureBox(host, it.name).id) })
}

/**
 * How a slideshow starts, stops and inspects its figure boxes. The default is Field's own launch and
 * stop; tests replace it.
 */
internal interface FigureRunner {
    fun box(host: Box, uid: String): Box?
    fun isRunning(box: Box): Boolean
    fun hasOutput(box: Box): Boolean
    fun launch(boxes: List<Box>)
    fun stop(box: Box)
}

internal object FieldFigureRunner : FigureRunner {
    override fun box(host: Box, uid: String): Box? =
        if (host.isInsideInitialized()) host.inside.boxes[uid] else null
    override fun isRunning(box: Box): Boolean = box.jobs.any { it.isActive && !it.isCancelled }
    override fun hasOutput(box: Box): Boolean = box.output.retainedAreas.channels().isNotEmpty()
    override fun launch(boxes: List<Box>) = launchBoxesInOrder(boxes)
    override fun stop(box: Box) = Server.stop(box, "presentation-figure")
}

/**
 * The figure side of a running slideshow: which boxes it launched, which it stopped, and the glides
 * in flight. One per [SlideShowInstance].
 */
internal class FigureStage(private val host: Box, var runner: FigureRunner = FieldFigureRunner) {
    private val launched = LinkedHashSet<String>()
    private val stopped = LinkedHashSet<String>()
    private val glides = mutableListOf<Job>()

    private fun boxes(figures: List<AudienceFigure>, known: List<FigureControl>): List<Box> =
        figures.map { it.box }.distinct().mapNotNull { uid ->
            runner.box(host, uid) ?: known.firstOrNull { it.box.id == uid }?.box
        }

    /** Stop what the deck launched and the incoming view does not show; launch what it needs. */
    fun enter(figures: List<AudienceFigure>, controls: List<FigureControl>) {
        val showing = figures.map { it.box }.toSet() + controls.map { it.box.id }
        launched.filter { it !in showing }.forEach { uid ->
            val box = runner.box(host, uid) ?: controls.firstOrNull { it.box.id == uid }?.box ?: return@forEach
            if (runner.isRunning(box)) { runner.stop(box); stopped += uid }
            launched -= uid
        }
        val needed = (boxes(figures, controls) + controls.map { it.box }).distinct().filter { box ->
            !runner.isRunning(box) && (box.id in stopped || !runner.hasOutput(box))
        }
        if (needed.isNotEmpty()) {
            needed.forEach { stopped -= it.id; launched += it.id }
            runner.launch(needed)
        }
    }

    /** Apply [controls] in order, last value per control winning; returns the labels that could not be set yet. */
    fun apply(controls: List<FigureControl>, cancelGlides: Boolean = true): List<FigureControl> {
        if (cancelGlides) {
            glides.forEach { it.cancel() }
            glides.clear()
        }
        val latest = LinkedHashMap<Triple<String, String, String>, FigureControl>()
        controls.forEach { latest.remove(Triple(it.box.id, it.channel, it.label)); latest[Triple(it.box.id, it.channel, it.label)] = it }
        return latest.values.filterNot { setting ->
            when {
                setting.flag != null -> setting.box.setControl(setting.label, setting.flag, setting.channel)
                setting.glideMs > 0 -> setting.box.glideControl(setting.label, setting.number!!, setting.glideMs, setting.channel)
                    ?.also { glides += it } != null
                else -> setting.box.setControl(setting.label, setting.number!!, setting.channel)
            }
        }
    }

    /** Forget remembered control values, run the figures again, and leave applying controls to the caller. */
    fun reset(figures: List<AudienceFigure>, controls: List<FigureControl>) {
        val boxes = (boxes(figures, controls) + controls.map { it.box }).distinct()
        boxes.forEach { box ->
            if (runner.isRunning(box)) runner.stop(box)
            box.forgetControlValues()
            launched += box.id
            stopped -= box.id
        }
        if (boxes.isNotEmpty()) runner.launch(boxes)
    }

    /** The slideshow stopped: stop every figure it launched that is still running. */
    fun leave() {
        glides.forEach { it.cancel() }
        glides.clear()
        launched.forEach { uid ->
            val box = runner.box(host, uid) ?: return@forEach
            if (runner.isRunning(box)) { runner.stop(box); stopped += uid }
        }
        launched.clear()
    }

    internal fun log(missing: List<FigureControl>) {
        missing.forEach { lprintln("warning,slideshow", "Figure '${it.figure}' has no control '${it.label}' in '${it.channel}' yet") }
    }
}
