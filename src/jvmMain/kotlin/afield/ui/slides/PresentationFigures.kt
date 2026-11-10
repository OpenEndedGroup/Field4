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
import kotlinx.html.FIGURE
import kotlinx.html.attributesMapOf
import kotlinx.html.div
import kotlinx.html.figcaption
import kotlinx.html.style
import kotlinx.html.visit
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
 * running, unless this talk has launched it before and it finished by itself (a static drawing need
 * not be drawn twice); leaving a slide stops the figures the deck launched that are still running
 * and that the next slide does not show, and those run again when a slide shows them. An
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
 * The live figure slot [AudienceBuilder.figure] writes. Written with kotlinx.html's tag classes rather
 * than its `figure { }` builder, whose name the member shadows.
 */
internal fun AudienceBuilder.writeFigure(name: String, channels: Array<out String>, height: Int?, caption: String) {
    require(name.isNotBlank() && name.length <= 256) { "A figure needs the name of a box" }
    require(channels.size <= 16 && channels.all { it.isNotBlank() && it.length <= 128 && it.none { c -> c.code < 32 || c == ',' } }) {
        "Name at most 16 output channels"
    }
    require(height == null || height in 60..1080) { "A figure's height is between 60 and 1080 slide pixels" }
    require(figures.size < 8) { "Use at most eight figures on one slide" }
    val id = "figure-${figureSlotCounter.incrementAndGet()}"
    figures += AudienceFigure(id = id, box = "", name = name, channels = channels.toList())
    FIGURE(attributesMapOf("class", "field-live-figure"), consumer).visit {
        if (height != null) style = "height:${height}px;flex:none"
        div(classes = "field-live-figure-slot") { attributes["data-field-figure"] = id }
        if (caption.isNotBlank()) figcaption { +caption }
    }
}

/**
 * Prose beside a live figure, with the figure on the right by default. The figure takes about two
 * thirds of the slide's width and its full height, and the prose is set smaller than a text slide's,
 * at a comfortable measure: the figure is the thing to look at. [height] pins the figure's height.
 */
fun AudienceBuilder.figureBeside(
    name: String, vararg channels: String, figureOnLeft: Boolean = false, height: Int? = null, caption: String = "",
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
    fun launch(boxes: List<Box>)
    fun stop(box: Box)
    /** What identifies the run that registered [box]'s control [label]: see [controlRegistration]. */
    fun controlRegistration(box: Box, channel: String, label: String): Any? = null
}

internal object FieldFigureRunner : FigureRunner {
    override fun box(host: Box, uid: String): Box? =
        if (host.isInsideInitialized()) host.inside.boxes[uid] else null
    override fun isRunning(box: Box): Boolean = box.jobs.any { it.isActive && !it.isCancelled }
    override fun launch(boxes: List<Box>) = launchBoxesInOrder(boxes)
    override fun stop(box: Box) = Server.stop(box, "presentation-figure")
    override fun controlRegistration(box: Box, channel: String, label: String): Any? =
        afield.ui.output.controlRegistration(box, channel, label)
}

/**
 * The figure side of a running slideshow: which boxes it launched, which it stopped, and the glides
 * in flight. One per [SlideShowInstance]; navigation and the retry of a control whose box is still
 * starting reach it from different coroutines, so its entry points are synchronized.
 */
internal class FigureStage(private val host: Box, var runner: FigureRunner = FieldFigureRunner) {
    private val launched = LinkedHashSet<String>()
    private val stopped = LinkedHashSet<String>()
    /** Every box this talk has launched; one that finished by itself is not launched again. */
    private val seen = LinkedHashSet<String>()
    private val glides = mutableListOf<Job>()
    /**
     * Controls on a box this stage has just launched, with the registration its previous run left
     * behind. Launching is asynchronous: until the new run draws its controls, setting one would move
     * the old run's control, and the new run would then start from its own initial value.
     */
    private val previousRun = HashMap<Triple<String, String, String>, Any>()

    private fun key(c: FigureControl) = Triple(c.box.id, c.channel, c.label)

    private fun launching(boxes: List<Box>, controls: List<FigureControl>) {
        val ids = boxes.map { it.id }.toSet()
        controls.filter { it.box.id in ids }.forEach { c ->
            val old = runner.controlRegistration(c.box, c.channel, c.label)
            if (old != null) previousRun[key(c)] = old else previousRun.remove(key(c))
        }
        runner.launch(boxes)
    }

    /** True while [c]'s box is still showing the controls of the run before the one this stage launched. */
    private fun waitingForNewRun(c: FigureControl): Boolean {
        val old = previousRun[key(c)] ?: return false
        if (runner.controlRegistration(c.box, c.channel, c.label) == old) return true
        previousRun.remove(key(c))
        return false
    }

    private fun boxes(figures: List<AudienceFigure>, known: List<FigureControl>): List<Box> =
        figures.map { it.box }.distinct().mapNotNull { uid ->
            runner.box(host, uid) ?: known.firstOrNull { it.box.id == uid }?.box
        }

    /** Stop what the deck launched and the incoming view does not show; launch what it needs. */
    @Synchronized
    fun enter(figures: List<AudienceFigure>, controls: List<FigureControl>) {
        val showing = figures.map { it.box }.toSet() + controls.map { it.box.id }
        launched.filter { it !in showing }.forEach { uid ->
            val box = runner.box(host, uid) ?: controls.firstOrNull { it.box.id == uid }?.box ?: return@forEach
            if (runner.isRunning(box)) { runner.stop(box); stopped += uid }
            launched -= uid
        }
        val needed = (boxes(figures, controls) + controls.map { it.box }).distinct().filter { box ->
            !runner.isRunning(box) && (box.id in stopped || box.id !in seen)
        }
        if (needed.isNotEmpty()) {
            needed.forEach { stopped -= it.id; launched += it.id; seen += it.id }
            launching(needed, controls)
        }
    }

    /** Apply [controls] in order, last value per control winning; returns the labels that could not be set yet. */
    @Synchronized
    fun apply(controls: List<FigureControl>, cancelGlides: Boolean = true): List<FigureControl> {
        if (cancelGlides) {
            glides.forEach { it.cancel() }
            glides.clear()
        }
        val latest = LinkedHashMap<Triple<String, String, String>, FigureControl>()
        controls.forEach { latest.remove(Triple(it.box.id, it.channel, it.label)); latest[Triple(it.box.id, it.channel, it.label)] = it }
        return latest.values.filterNot { setting ->
            when {
                waitingForNewRun(setting) -> false
                setting.flag != null -> setting.box.setControl(setting.label, setting.flag, setting.channel)
                setting.glideMs > 0 -> setting.box.glideControl(setting.label, setting.number!!, setting.glideMs, setting.channel)
                    ?.also { glides += it } != null
                else -> setting.box.setControl(setting.label, setting.number!!, setting.channel)
            }
        }
    }

    /** Forget remembered control values, run the figures again, and leave applying controls to the caller. */
    @Synchronized
    fun reset(figures: List<AudienceFigure>, controls: List<FigureControl>) {
        val boxes = (boxes(figures, controls) + controls.map { it.box }).distinct()
        boxes.forEach { box ->
            if (runner.isRunning(box)) runner.stop(box)
            box.forgetControlValues()
            launched += box.id
            stopped -= box.id
        }
        if (boxes.isNotEmpty()) launching(boxes, controls)
    }

    /** The slideshow stopped: stop every figure it launched that is still running. */
    @Synchronized
    fun leave() {
        glides.forEach { it.cancel() }
        glides.clear()
        launched.forEach { uid ->
            val box = runner.box(host, uid) ?: return@forEach
            if (runner.isRunning(box)) runner.stop(box)
        }
        launched.clear()
        stopped.clear()
        seen.clear()
        previousRun.clear()
    }

    internal fun log(missing: List<FigureControl>) {
        missing.forEach { lprintln("warning,slideshow", "Figure '${it.figure}' has no control '${it.label}' in '${it.channel}' yet") }
    }
}
