package afield.ai.agent

import afield.document.graph.Box
import afield.app.diagnostics.BoxOutputLog
import afield.app.protocol.Execute
import afield.app.protocol.ExecutionReport
import afield.runtime.execution.ExecutionReports
import afield.app.protocol.Message
import afield.app.protocol.Rect
import afield.runtime.lifecycle.Runloop
import afield.app.server.SessionInformation
import afield.document.graph.actionTable
import afield.app.server.activeRunningStrands
import afield.ai.tools.ToolRegistry
import afield.ai.models.uniqueBoxOrRefuse
import afield.app.server.applyAgentPropertyRecorded
import afield.app.server.createAgentBox
import afield.document.graph.doResync
import afield.app.server.executeAllRequest
import afield.document.graph.isTrue
import afield.app.server.launchAllRequest
import afield.document.graph.needsResync
import afield.app.server.stopBoxRequest
import afield.app.server.thisSessionInfo
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The box graph and execution tools of Field's own agent: list, create, delete, edit, connect,
 * run, launch, stop and read the output of boxes in the current document.
 *
 * These are the operations an external agent reaches through `tools/field_backdoor.py` (new-box,
 * set, connect, run, launch, stop, wait-output), registered as native tools in the AI4 registry.
 * Each one calls the same server function the websocket and backdoor handlers call
 * (`createAgentBox`, `executeAllRequest`, `launchAllRequest`, `stopBoxRequest`, and the action
 * table's `-connect`, `-disconnect` and `-delete-box`), so box execution is not reimplemented here.
 *
 * A box that fails to compile or throws comes back as `success: false` with its diagnostics,
 * never as a silent success. [FieldGraphHost] is how these tools reach the server:
 * [LiveFieldGraphHost] is the real one, and tests such as `InFieldAgentHarnessTest` supply a
 * scripted host.
 */

@JsonClassDescription("List every box in this Field document. Returns, per box: uid, name, parents, children, whether it is running, the length of its code, and the verdict of its last run. Use it when a box the task names is not one you know; other tools accept the names it lists (e.g. box = `Sun`). It takes no arguments and does not fail; an empty list means no boxes yet.")
@afield.ai.tools.AgentToolMeta("graph", effects = [afield.ai.tools.ToolEffect.READ_DOCUMENT])
data class ListBoxesRequest(val unused: Int = 0)

@JsonClassDescription("Create a new box in this Field document. Returns the new box's uid. Use it when the task needs a box that does not exist yet; optionally give it Kotlin code (not run until RunBox/LaunchBox) and a parent (uid or name, e.g. parent = `Settings`) to connect it under. Fails if the parent is unknown or ambiguous.")
@afield.ai.tools.AgentToolMeta("graph", effects = [afield.ai.tools.ToolEffect.MUTATE_DOCUMENT])
data class NewBoxRequest(
    @JsonPropertyDescription("Box name, shown on the canvas. Prefer unique names; tools accept a unique name wherever they take a box.")
    val name: String,
    @JsonPropertyDescription("Initial Kotlin source for the box's `code` property. The box is the implicit receiver: use println(...) and display(\"channel\", \"<html>\").")
    val code: String = "",
    @JsonPropertyDescription("Optional parent box (uid or unique name). The new box becomes its child and inherits from it.")
    val parent: String = "",
)

@JsonClassDescription("Delete a box (and anything nested in it) from this Field document. Returns success and the deleted box's uid and name. Use it only when the task asks for a box to go; to change a box, edit it instead. box is a uid or unique name, e.g. `Scratch`. An unknown or ambiguous box fails with the list of known boxes.")
@afield.ai.tools.AgentToolMeta("graph", effects = [afield.ai.tools.ToolEffect.MUTATE_DOCUMENT])
data class DeleteBoxRequest(val box: String)

@JsonClassDescription("Replace a whole text property on a box, most often `code`, e.g. property = `code`, value = `println(1)`. Returns success and the length set. Use it for a rewrite; for a small change prefer PatchBoxTextRequest, which resends nothing else. Does not run it: call RunBox or LaunchBox afterwards. An unknown or ambiguous box fails with the list of known boxes.")
@afield.ai.tools.AgentToolMeta("graph", effects = [afield.ai.tools.ToolEffect.MUTATE_DOCUMENT])
data class SetBoxPropertyRequest(
    val box: String,
    @JsonPropertyDescription("Property name; `code` for Kotlin source, `name` to rename.")
    val property: String = "code",
    val value: String,
)

@JsonClassDescription("Edit part of a box's text property in place, without resending the whole text. Either find/replace (`find` must match exactly; matches are counted without overlap; `occurrence` N = the Nth match, 0 = every match; the call fails if `find` is absent or, with occurrence 1, ambiguous unless allowMultiple) or a line range (`startLine`..`endLine`, 1-based, inclusive, replaced by `replacement`; endLine 0 means endLine = startLine; `insert` = true inserts `replacement` before startLine instead of replacing). Returns success and the length before and after. Recorded like SetBoxPropertyRequest. Does not run it.")
@afield.ai.tools.AgentToolMeta("graph", effects = [afield.ai.tools.ToolEffect.MUTATE_DOCUMENT])
data class PatchBoxTextRequest(
    val box: String,
    val property: String = "code",
    val find: String = "",
    val replace: String = "",
    val occurrence: Int = 1,
    val allowMultiple: Boolean = false,
    val startLine: Int = 0,
    val endLine: Int = 0,
    val replacement: String = "",
    val insert: Boolean = false,
)

@JsonClassDescription("Read a text property of a box, most often `code` (e.g. box = `Primes`, property = `code`). Returns its text as `value`; a property the box does not have, or one that is not text, fails with success=false. Use it when you need a box's current text; a failed RunBox already reports its errors and lines. An unknown or ambiguous box fails with the list of known boxes.")
@afield.ai.tools.AgentToolMeta("graph", effects = [afield.ai.tools.ToolEffect.READ_DOCUMENT])
data class GetBoxPropertyRequest(val box: String, val property: String = "code")

@JsonClassDescription("Connect two boxes: `child` becomes a child of `parent` (e.g. parent = `Settings`, child = `Plot`). Children inherit properties and can see their parents' variables. Returns success and the child's uid and name. Use it when one box must read another's values; the child sees them on its next run. Fails if either box is unknown or ambiguous.")
@afield.ai.tools.AgentToolMeta("graph", effects = [afield.ai.tools.ToolEffect.MUTATE_DOCUMENT])
data class ConnectBoxesRequest(val parent: String, val child: String)

@JsonClassDescription("Remove the parent→child connection between two boxes (e.g. parent = `Settings`, child = `Plot`). Returns success and the child's uid and name. Use it when a box should stop inheriting from another. Fails if either box is unknown or ambiguous.")
@afield.ai.tools.AgentToolMeta("graph", effects = [afield.ai.tools.ToolEffect.MUTATE_DOCUMENT])
data class DisconnectBoxesRequest(val parent: String, val child: String)

@JsonClassDescription("Run a box's code once to completion (like the Run command) and wait for its verdict. It compiles the code itself, so there is no need to compile first. Returns success, what it printed and displayed, and on failure success=false with the compile/runtime errors and their lines. Use it for code that finishes; for loops or animations that keep running use LaunchBoxRequest. If `code` is given it replaces the box's code first (e.g. code = `println((1..5).toList())`). An unknown or ambiguous box fails with the list of known boxes.")
@afield.ai.tools.AgentToolMeta("graph", effects = [afield.ai.tools.ToolEffect.EXECUTE_CODE])
data class RunBoxRequest(
    val box: String,
    @JsonPropertyDescription("Optional replacement source; omitted or blank = run the box's current code.")
    val code: String? = null,
    val timeoutSeconds: Double = 30.0,
)

@JsonClassDescription("Launch a box (like the Launch command): run its setup, which may start loops or animations that keep running. Waits for setup to complete or fail, then returns whether it is still running and what it said so far; compile or setup errors come back as success=false. Use it for code with `start {}`/frame loops; use RunBoxRequest for code that finishes, and StopBoxRequest to end it. e.g. box = `Ticker`, settleSeconds = 1. An unknown or ambiguous box fails with the list of known boxes.")
@afield.ai.tools.AgentToolMeta("graph", effects = [afield.ai.tools.ToolEffect.EXECUTE_CODE, afield.ai.tools.ToolEffect.CONTROL_RUNTIME])
data class LaunchBoxRequest(
    val box: String,
    val code: String? = null,
    val timeoutSeconds: Double = 30.0,
    @JsonPropertyDescription("Seconds to keep collecting output after setup completes.")
    val settleSeconds: Double = 0.5,
)

@JsonClassDescription("Stop a running box and everything it launched (e.g. box = `Ticker`). Returns success=true once it has stopped, success=false if it is still running. Use it when a task asks for a loop or animation to end, or before relaunching. Stopping a box that is not running is not an error. An unknown or ambiguous box fails with the list of known boxes.")
@afield.ai.tools.AgentToolMeta("graph", effects = [afield.ai.tools.ToolEffect.CONTROL_RUNTIME])
data class StopBoxRequest(val box: String)

@JsonClassDescription("Read what a box has printed, displayed, or reported since a marker (0 = everything retained). Returns the entries as text plus a new marker to pass next time. Use it to follow a launched box's output, optionally waiting until it contains some text (e.g. contains = `done`, waitSeconds = 5); a wait that times out returns what there was, not an error. To see what a box draws, use ViewBoxOutputRequest. An unknown or ambiguous box fails with the list of known boxes.")
@afield.ai.tools.AgentToolMeta("graph", effects = [afield.ai.tools.ToolEffect.READ_DOCUMENT])
data class ReadBoxOutputRequest(
    val box: String,
    @JsonPropertyDescription("Return entries after this marker; use the marker from a previous RunBox/LaunchBox/ReadBoxOutput result.")
    val since: Long = 0,
    @JsonPropertyDescription("If non-empty, wait (up to waitSeconds) until some output contains this text.")
    val contains: String = "",
    val waitSeconds: Double = 0.0,
    val maxEntries: Int = 60,
)

@JsonClassDescription("Look at what a box SHOWS: capture its output area (the panels its display(...) calls fill) as a picture. Returns the picture, attached for you to inspect, plus measured facts (size, dominant colours, how much of it is not background) and viewerStates, the geometry state actually captured. It uses an independent browser, so its camera, visibility and interactive state may differ from the user's. Use it after RunBox/LaunchBox whenever a box displays HTML, SVG, a table, a chart or any drawing, to check that it looks the way it was asked to; fix it if it does not. This, not CaptureRenderWindowRequest, is the tool for display(...) output. Printed text is not in the picture: read that with ReadBoxOutputRequest. A box with no panel fails with that said, and a mostly empty capture says BLANK. e.g. box = `Swatch`, note = `is the square red?`")
@afield.ai.tools.AgentToolMeta("graph", effects = [afield.ai.tools.ToolEffect.CAPTURE_IMAGE])
data class ViewBoxOutputRequest(
    val box: String,
    @JsonPropertyDescription("Milliseconds to wait before capturing, for a late script or an animation; clamped to 0..5000. 0 = a short default.")
    val settleMs: Int = 0,
    @JsonPropertyDescription("What you are looking for. Travels with the picture as its caption.")
    val note: String = "",
)

// ---- results ---------------------------------------------------------------------------------

data class FieldBoxSummary(
    val uid: String,
    val name: String,
    val parents: List<String>,
    val children: List<String>,
    val running: Boolean,
    val codeChars: Int,
    val lastRunSuccess: Boolean?,
    val lastRunError: String = "",
)

data class FieldOutputEntry(val seq: Long, val kind: String, val text: String, val channel: String = "", val line: Int = 0)

/** One result shape for every graph tool, so the model always finds `success` and `message`. */
data class FieldGraphResult(
    val success: Boolean,
    val message: String,
    val uid: String = "",
    val name: String = "",
    val property: String = "",
    val value: String? = null,
    val stage: String = "",
    val error: String = "",
    val running: Boolean? = null,
    val stdout: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val displays: Map<String, String> = emptyMap(),
    val entries: List<FieldOutputEntry> = emptyList(),
    val boxes: List<FieldBoxSummary> = emptyList(),
    val outputMarker: Long = 0,
    val found: Boolean? = null,
    val timedOut: Boolean = false,
)

/**
 * What [ViewBoxOutputRequest] returns: the picture (as a tool image, not in the JSON) plus what was
 * measured from it, so the model gets numbers alongside the pixels and the tool log shows whether a
 * picture was attached at all.
 */
data class BoxOutputView(
    val success: Boolean,
    val message: String,
    val uid: String = "",
    val name: String = "",
    val channels: List<String> = emptyList(),
    val width: Int = 0,
    val height: Int = 0,
    /** Share of pixels that are not the background colour. */
    val inkFraction: Double = 0.0,
    /** `#rrggbb share%`, most common first. */
    val dominantColours: List<String> = emptyList(),
    /** Where the full-resolution PNG was kept, as evidence. */
    val path: String = "",
    val imageAttached: Boolean = false,
    /** Geometry state actually shown in the independent capture browser. */
    val viewerStates: List<Map<String, Any?>> = emptyList(),
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val dataUrl: String = "",
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val note: String = "",
) : afield.ai.tools.CarriesToolImages, afield.ai.tools.SummarisesForToolLog {
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    override val toolImages: List<afield.ai.tools.ToolImage>
        get() = if (dataUrl.isBlank()) emptyList() else listOf(afield.ai.tools.ToolImage(dataUrl,
            "Output of box '$name' (${channels.joinToString()}), ${width}x$height, captured in an independent browser. " +
                captureReadingInstruction(note)))

    override fun toolLogSummary(): String =
        "success=$success images=${toolImages.size} box=$name ${width}x$height ink=${"%.3f".format(inkFraction)} " +
            "colours=${dominantColours.take(3)} path=$path" + (if (!success) " message=${message.take(160)}" else "")
}

/**
 * How a capture's caption asks to be read. The expectation comes *after* the request to describe, and is
 * labelled as the model's own: on 2026-10-22 a local model's note said "diagonal lines", and it reported
 * "the signature diagonal lines" of a picture whose strongest feature was a horizontal and vertical cross
 * (its code had placed only odd numbers). Models also copy the instruction into the note, which then
 * appeared twice; it is stripped here.
 */
internal fun captureReadingInstruction(note: String): String {
    val instruction = "Describe what you actually see, then say whether it matches what was asked for."
    val expectation = note.replace(instruction, "").trim().trimEnd('.', ' ')
    return "First describe what the picture shows in your own words — shapes, lines and their directions, where colour falls, " +
        "what is empty — then compare it with what was asked for" +
        (if (expectation.isNotBlank()) " and with your own expectation, written before you saw it (\"$expectation\")" else "") +
        ". Say plainly where they differ; a picture confirms what was drawn, not that the code computed the right thing."
}

// ---- host seam ---------------------------------------------------------------------------------

/**
 * Where the graph tools meet Field. [LiveFieldGraphHost] is the real server; tests supply a host
 * whose owner thread is the calling thread and whose "execution" is scripted, because no jvmTest
 * can run the K2 REPL.
 */
interface FieldGraphHost {
    /** Run [action] where box graph mutations belong, with [info] bound as the current session. */
    fun <T> onOwner(info: SessionInformation, action: () -> T): T
    fun newBox(info: SessionInformation, name: String, frame: Rect, properties: Map<String, String>): Box
    fun delete(info: SessionInformation, box: Box)
    /** Start a whole-property run; the verdict arrives through [reply] as `execution-verdict`. */
    fun run(info: SessionInformation, box: Box, exec: Execute, reply: (Message) -> Unit)
    fun launch(info: SessionInformation, box: Box, exec: Execute, reply: (Message) -> Unit)
    fun stop(info: SessionInformation, box: Box)
    /** A picture of [box]'s output area. The default host cannot take one and says so. */
    fun captureOutput(info: SessionInformation, box: Box, settleMs: Int): BoxOutputShot =
        BoxOutputShot.failed("This Field host cannot capture box output.")
}

object LiveFieldGraphHost : FieldGraphHost {
    override fun <T> onOwner(info: SessionInformation, action: () -> T): T {
        val bound = {
            val previous = thisSessionInfo.get()
            thisSessionInfo.set(info)
            try { action() } finally { if (previous == null) thisSessionInfo.remove() else thisSessionInfo.set(previous) }
        }
        // Already on the Runloop (a box calling a tool directly): dispatching to it and blocking
        // would wait for ourselves.
        if (Runloop.isMainThread) return bound()
        return runBlocking { withContext(Runloop.runLoop.main) { bound() } }
    }

    override fun newBox(info: SessionInformation, name: String, frame: Rect, properties: Map<String, String>): Box =
        createAgentBox(info, name, frame, properties.mapValues { JsonPrimitive(it.value) })

    override fun delete(info: SessionInformation, box: Box) {
        actionTable["-delete-box"]!!.invoke(listOf(box))
        doResync()
    }

    override fun run(info: SessionInformation, box: Box, exec: Execute, reply: (Message) -> Unit) =
        executeAllRequest(box, exec, reply, null, info)

    override fun launch(info: SessionInformation, box: Box, exec: Execute, reply: (Message) -> Unit) {
        val previous = thisSessionInfo.get()
        thisSessionInfo.set(info)
        try { launchAllRequest(box, exec, reply) } finally {
            if (previous == null) thisSessionInfo.remove() else thisSessionInfo.set(previous)
        }
    }

    override fun stop(info: SessionInformation, box: Box) = stopBoxRequest(box, entrypoint = "ai-agent.StopBox")

    override fun captureOutput(info: SessionInformation, box: Box, settleMs: Int): BoxOutputShot =
        BoxOutputCapture.capture(info, box.id, settleMs)
}

// ---- the tools ---------------------------------------------------------------------------------

class FieldGraphTools(
    val info: SessionInformation,
    private val host: FieldGraphHost = LiveFieldGraphHost,
    /**
     * `(uid, property)` pairs whose text this turn's *reply* will replace — the editor chat's current
     * box and property, whose rewritten code is the reply itself. Writing those through a tool would
     * be silently overwritten when the reply lands, so the tools refuse and say where the edit goes.
     */
    val replyOwned: Set<Pair<String, String>> = emptySet(),
    /**
     * Whether this turn's instructions carry the eval-distilled lessons ([AgentLessons]). Defaults to
     * on unless `AFIELD_AI4_LESSONS=off`; an eval can pin it per task to measure it.
     */
    val lessons: Boolean = AgentLessons.enabledByDefault(),
    /**
     * Whether [ViewBoxOutputRequest] is offered. Only an eval's counterfactual turns it off (`blind`):
     * the same visual task with the model unable to look.
     */
    val seesOutput: Boolean = true,
    /**
     * The OperationTranscript origin this turn's edits are recorded under (`ai4:<turn>`), so a rewound turn's
     * edits can be undone without touching anyone else's. Blank: recorded like a person's.
     */
    val origin: String = "",
) {
    internal val geometryReviews by lazy {
        GeometryReviewTools(info, { action -> host.onOwner(info, action) }) { runs -> studyBuilds.runsFromEarlierInputs(runs) }
    }
    internal val studyBuilds by lazy { StudyBuildTools(info, bindings = {
        host.onOwner(info) { afield.study.build.studyBuildBindings(info.boxes.values + info.root) }
    }, findings = { runs -> host.onOwner(info) { afield.study.build.studyBuildFindings(info.boxes.values + info.root, runs) } }) }

    /** [InspectKitRequest]: which kit this document runs and what differs from it (KitTools.kt). */
    fun kit(q: InspectKitRequest): KitToolResult = inspectKit(q) { action -> host.onOwner(info, action) }

    companion object {
        /**
         * The one way every AI4 path builds its graph tools: the editor chat passes the box and
         * property its reply rewrites; the document chat, `chatGeneral` and `AgentTaskRunner` pass
         * none.
         */
        fun forTurn(info: SessionInformation, replyOwnedBox: Box? = null, replyOwnedProperty: String = "code", origin: String = ""): FieldGraphTools =
            FieldGraphTools(info, replyOwned = replyOwnedBox?.let { setOf(it.id to replyOwnedProperty) }.orEmpty(), origin = origin)
    }

    private fun refuseReplyOwned(box: Box, property: String): FieldGraphResult? =
        if ((box.id to property) !in replyOwned) null
        else FieldGraphResult(false,
            "'${box.name}' $property is the text your reply rewrites in this editor turn: the reply becomes the " +
                "proposal the user accepts, so an edit made here would be overwritten by it. Put the change in your " +
                "reply. To test a candidate first, call RunBoxRequest on this box with `code` = your candidate: it " +
                "runs that source without writing it.",
            uid = box.id, name = box.name, property = property)

    /** Uids of boxes this instance created, in order — the task runner reports them. */
    val created: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    private fun live(box: Box) = !box.getPropertyRaw("_tombstone").isTrue()

    /** A box by uid or unique name; ambiguity is refused, never guessed. */
    fun resolve(token: String): Box {
        val t = token.trim()
        require(t.isNotEmpty()) { "No box given." }
        if (t == info.root.id || t == "root") return info.root
        info.boxes[t]?.takeIf(::live)?.let { return it }
        return uniqueBoxOrRefuse(t, info.boxes.values.filter { live(it) && it.name == t })
            ?: throw IllegalArgumentException(
                "No box '$t' in ${info.documentName}. Known: " +
                    info.boxes.values.filter(::live).joinToString(", ") { "${it.name} (${it.id.take(8)})" }.take(600)
            )
    }

    private fun summary(box: Box): FieldBoxSummary {
        val attempt = ExecutionReports.latest(box, "code")
        return FieldBoxSummary(
            uid = box.id, name = box.name,
            parents = box.parents.filter { it !== info.root && live(it) }.map { it.name.ifBlank { it.id } },
            children = box.children.filter(::live).map { it.name.ifBlank { it.id } },
            running = isRunning(box),
            codeChars = (box.getPropertyRaw("code") as? String)?.length ?: 0,
            lastRunSuccess = attempt?.success,
            lastRunError = attempt?.error.orEmpty().take(400),
        )
    }

    fun isRunning(box: Box): Boolean = activeRunningStrands(box).isNotEmpty() || box.jobs.any { it.isActive && !it.isCancelled }

    fun listBoxes(): FieldGraphResult = host.onOwner(info) {
        val boxes = info.boxes.values.filter(::live).sortedBy { it.name.lowercase() }.map(::summary)
        FieldGraphResult(true, "${boxes.size} box(es) in ${info.documentName}.", boxes = boxes)
    }

    fun newBox(req: NewBoxRequest): FieldGraphResult = host.onOwner(info) {
        val parent = req.parent.takeIf { it.isNotBlank() }?.let(::resolve)
        val frame = nextFrame(parent)
        val properties = if (req.code.isNotEmpty()) mapOf("code" to req.code) else emptyMap()
        val box = host.newBox(info, req.name.ifBlank { "Untitled" }, frame, properties)
        created += box.id
        if (parent != null && parent !== info.root) {
            parent.connect(box)
            doResync()
        }
        FieldGraphResult(
            true,
            "Created '${box.name}'" + (parent?.let { " under '${it.name}'" } ?: "") + ". It has not been run.",
            uid = box.id, name = box.name
        )
    }

    /**
     * To the right of everything already on the canvas. Not below: output areas hang beneath their
     * boxes, so a box placed "under the lowest box" lands on top of that box's output (it did, in the
     * first demo capture). A child goes directly beneath its parent's column only when that column
     * is the rightmost; otherwise it too goes right, which keeps every new box clear of every output.
     */
    private fun nextFrame(parent: Box?): Rect {
        val others = info.boxes.values.filter(::live)
        val right = others.maxOfOrNull { it.frame.x + it.frame.w } ?: 20.0
        val top = parent?.takeIf { it !== info.root }?.frame?.y ?: (others.minOfOrNull { it.frame.y } ?: 80.0)
        return Rect(right + 120.0, top, 200.0, 60.0)
    }

    fun deleteBox(req: DeleteBoxRequest): FieldGraphResult = host.onOwner(info) {
        val box = resolve(req.box)
        require(box !== info.root) { "The document root cannot be deleted." }
        if (replyOwned.any { it.first == box.id }) return@onOwner FieldGraphResult(false,
            "'${box.name}' is the box this editor turn's reply rewrites; it cannot be deleted from inside that turn.",
            uid = box.id, name = box.name)
        host.delete(info, box)
        FieldGraphResult(true, "Deleted '${box.name}'.", uid = box.id, name = box.name)
    }

    fun setProperty(req: SetBoxPropertyRequest): FieldGraphResult = host.onOwner(info) {
        val box = resolve(req.box)
        refuseReplyOwned(box, req.property)?.let { return@onOwner it }
        require(req.value.length <= 2_000_000) { "Value is too large." }
        check(!info.readOnly) { "This document is read-only." }
        val value = if (req.property == "code") unwrapEditorScript(req.value) else req.value
        applyAgentPropertyRecorded(box, req.property, JsonPrimitive(value), origin)
        needsResync.add(box to req.property)
        doResync()
        FieldGraphResult(true, "Set ${req.property} on '${box.name}' (${req.value.length} chars). Not run.",
            uid = box.id, name = box.name, property = req.property)
    }

    fun patchText(req: PatchBoxTextRequest): FieldGraphResult = host.onOwner(info) {
        val box = resolve(req.box)
        refuseReplyOwned(box, req.property)?.let { return@onOwner it }
        check(!info.readOnly) { "This document is read-only." }
        val before = box.getPropertyRaw(req.property) as? String
            ?: return@onOwner FieldGraphResult(false, "'${box.name}' has no text property '${req.property}'.", uid = box.id, name = box.name, property = req.property)
        val after = when {
            req.find.isNotEmpty() -> {
                // One counting rule for both "how many" and "which one": non-overlapping, left to right.
                val starts = generateSequence(before.indexOf(req.find).takeIf { it >= 0 }) { prev ->
                    before.indexOf(req.find, prev + req.find.length).takeIf { it >= 0 }
                }.toList()
                val count = starts.size
                when {
                    req.occurrence < 0 -> return@onOwner FieldGraphResult(false,
                        "occurrence must be 0 (all) or a positive match number, not ${req.occurrence}.",
                        uid = box.id, name = box.name, property = req.property)
                    count == 0 -> return@onOwner FieldGraphResult(false,
                        "`find` does not occur in ${req.property} of '${box.name}'. Read it with GetBoxPropertyRequest and match it exactly.",
                        uid = box.id, name = box.name, property = req.property)
                    req.occurrence == 0 -> before.replace(req.find, req.replace)
                    req.occurrence == 1 && count > 1 && !req.allowMultiple -> return@onOwner FieldGraphResult(false,
                        "`find` occurs $count times in ${req.property} of '${box.name}'. Add surrounding text to make it unique, " +
                            "or pass occurrence=N (or 0 for all).", uid = box.id, name = box.name, property = req.property)
                    req.occurrence > count -> return@onOwner FieldGraphResult(false,
                        "occurrence ${req.occurrence} requested but `find` occurs $count times.", uid = box.id, name = box.name, property = req.property)
                    else -> {
                        val index = starts[req.occurrence - 1]
                        before.substring(0, index) + req.replace + before.substring(index + req.find.length)
                    }
                }
            }
            req.startLine > 0 -> {
                val lines = before.split("\n")
                val start = req.startLine
                val end = if (req.endLine == 0) start else req.endLine
                val replacementLines = if (req.replacement.isEmpty()) emptyList() else req.replacement.removeSuffix("\n").split("\n")
                if (req.insert) {
                    if (start > lines.size + 1) return@onOwner FieldGraphResult(false,
                        "Cannot insert before line $start: ${req.property} of '${box.name}' has ${lines.size} lines.",
                        uid = box.id, name = box.name, property = req.property)
                    (lines.subList(0, start - 1) + replacementLines + lines.subList(start - 1, lines.size)).joinToString("\n")
                } else {
                    if (end < start || end > lines.size) return@onOwner FieldGraphResult(false,
                        "Line range $start..$end is outside ${req.property} of '${box.name}', which has ${lines.size} lines.",
                        uid = box.id, name = box.name, property = req.property)
                    (lines.subList(0, start - 1) + replacementLines + lines.subList(end, lines.size)).joinToString("\n")
                }
            }
            else -> return@onOwner FieldGraphResult(false, "Give either `find` (with `replace`) or `startLine` (with `endLine` and `replacement`).",
                uid = box.id, name = box.name, property = req.property)
        }
        applyAgentPropertyRecorded(box, req.property, JsonPrimitive(after), origin)
        needsResync.add(box to req.property)
        doResync()
        FieldGraphResult(true, "Patched ${req.property} on '${box.name}' (${before.length} -> ${after.length} chars). Not run.",
            uid = box.id, name = box.name, property = req.property, value = after.takeIf { it.length <= 4000 })
    }

    fun getProperty(req: GetBoxPropertyRequest): FieldGraphResult = host.onOwner(info) {
        val box = resolve(req.box)
        val raw = if (req.property == "name") box.name else box.getPropertyRaw(req.property)
        when (raw) {
            is String -> FieldGraphResult(true, "${req.property} of '${box.name}' (${raw.length} chars).",
                uid = box.id, name = box.name, property = req.property, value = raw)
            null -> FieldGraphResult(false, "'${box.name}' has no property '${req.property}'.",
                uid = box.id, name = box.name, property = req.property)
            else -> FieldGraphResult(false, "'${req.property}' on '${box.name}' is a ${raw.javaClass.simpleName}, not text.",
                uid = box.id, name = box.name, property = req.property, value = raw.toString().take(400))
        }
    }

    /** [SymbolAtRequest]: the box is found where the graph lives; resolving runs off it, as the editor hover does. */
    fun symbolAt(req: SymbolAtRequest): SymbolAtResult {
        val box = host.onOwner(info) { resolve(req.box) }
        return SymbolAt.describe(box, req)
    }

    fun connect(req: ConnectBoxesRequest): FieldGraphResult = host.onOwner(info) {
        val parent = resolve(req.parent)
        val child = resolve(req.child)
        require(parent !== child) { "A box cannot be its own parent." }
        actionTable["-connect"]?.invoke(listOf(parent, child)) ?: parent.connect(child)
        doResync()
        FieldGraphResult(child in parent.children, "Connected '${parent.name}' -> '${child.name}'.", uid = child.id, name = child.name)
    }

    fun disconnect(req: DisconnectBoxesRequest): FieldGraphResult = host.onOwner(info) {
        val parent = resolve(req.parent)
        val child = resolve(req.child)
        actionTable["-disconnect"]?.invoke(listOf(parent, child)) ?: parent.disconnect(child)
        doResync()
        FieldGraphResult(child !in parent.children, "Disconnected '${parent.name}' from '${child.name}'.", uid = child.id, name = child.name)
    }

    fun run(req: RunBoxRequest): FieldGraphResult = execute(req.box, req.code, req.timeoutSeconds, launch = false, settleSeconds = 0.05)

    fun launch(req: LaunchBoxRequest): FieldGraphResult = execute(req.box, req.code, req.timeoutSeconds, launch = true, settleSeconds = req.settleSeconds)

    private fun execute(token: String, rawCode: String?, timeoutSeconds: Double, launch: Boolean, settleSeconds: Double): FieldGraphResult {
        // Blank means "not given". OpenAI's strict function schemas make every field required, so a
        // model that means "run what is there" sends `code: ""` — and before this, that *replaced*
        // the box's code with nothing and then failed with "no code" (seen in the first live demo).
        val code = rawCode?.takeIf { it.isNotBlank() }?.let(::unwrapEditorScript)
        val (box, source) = host.onOwner<Pair<FieldGraphResult?, Pair<Box, String>?>>(info) {
            val box = resolve(token)
            // The editor turn's own box: run the candidate without storing it — the reply is where the
            // text goes, and it becomes the proposal the user accepts.
            if (code != null && (box.id to "code") in replyOwned) {
                // A candidate *run* ends; a candidate *launch* would leave the unaccepted code running.
                if (launch) return@onOwner Pair(FieldGraphResult(false,
                    "LaunchBoxRequest with `code` is refused on '${box.name}', the box this editor turn's reply rewrites: " +
                        "it would leave unaccepted code running. Check the candidate with RunBoxRequest(code = …) instead.",
                    uid = box.id, name = box.name, property = "code"), null)
                return@onOwner Pair(null, box to code)
            }
            if (code != null) {
                applyAgentPropertyRecorded(box, "code", JsonPrimitive(code), origin)
                needsResync.add(box to "code")
                doResync()
            }
            Pair(null, box to ((box.getPropertyRaw("code") as? String).orEmpty()))
        }.let { (refusal, resolved) -> refusal?.let { return it }; resolved!! }
        val verb = if (launch) "launch" else "run"
        if (source.isBlank()) return FieldGraphResult(false, "'${box.name}' has no code to $verb.", uid = box.id, name = box.name)

        val requestId = "ai-agent-$verb-${UUID.randomUUID()}"
        val verdict = CompletableFuture<ExecutionReport>()
        val reply: (Message) -> Unit = { message ->
            if (message.name == "execution-verdict") {
                runCatching { Json.decodeFromJsonElement<ExecutionReport>(message.payload as JsonElement) }
                    .getOrNull()
                    ?.takeIf { it.requestId == requestId }
                    ?.let { verdict.complete(it) }
            }
        }
        val marker = BoxOutputLog.mark()
        val exec = Execute(uid = box.id, type = "code", line = 0, code = source, singleLine = false,
            property = "code", requestId = requestId)
        if (launch) host.launch(info, box, exec, reply) else host.run(info, box, exec, reply)

        var report = try {
            verdict.get((timeoutSeconds.coerceIn(0.5, 600.0) * 1000).toLong(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) { null }
        // No verdict: if the box's code is what holds the Runloop, throw out of it (BoxInterrupt) and
        // give the interrupted run a moment to report, so the agent gets a verdict and Field moves again.
        var interrupted = false
        // The timeout may fall while the box is still compiling, so watch a little longer for its code
        // to reach the Runloop before giving up.
        if (report == null && !launch) {
            val main = afield.runtime.lifecycle.Runloop.mainThread
            val deadline = System.currentTimeMillis() + 8000
            while (main != null && report == null && System.currentTimeMillis() < deadline) {
                if (!interrupted && afield.runtime.lifecycle.BoxInterrupt.culprit(main.stackTrace) != null) {
                    afield.runtime.lifecycle.BoxInterrupt.request("RunBox timeout after ${timeoutSeconds}s", main)
                    interrupted = true
                }
                report = try { verdict.get(50, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { null }
            }
        }
        if (report?.let { !it.success && "Box interrupted:" in it.error } == true) interrupted = true
        if (settleSeconds > 0) Thread.sleep((settleSeconds.coerceAtMost(10.0) * 1000).toLong())

        val page = BoxOutputLog.since(box, marker)
        val stdout = page.entries.filter { it.kind == "out" }.map { it.text }
        val errors = page.entries.filter { it.kind == "err" }.map { it.text }
        val displays = page.entries.filter { it.kind == "display" }.associate { it.channel to it.text.take(3000) }
        val running = if (launch) isRunning(box) else null
        val common = FieldGraphResult(
            success = false, message = "", uid = box.id, name = box.name, property = "code",
            running = running, stdout = stdout.takeLast(60), errors = errors.takeLast(20),
            displays = displays, outputMarker = page.marker
        )
        return when {
            interrupted -> common.copy(
                stage = "interrupted", timedOut = true, error = report?.error?.take(4000) ?: "",
                message = "The $verb of '${box.name}' did not return within ${timeoutSeconds}s, so Field interrupted it " +
                    (if (report != null) "and it stopped. " else "but has no verdict yet. ") +
                    "Look for a loop or recursion that never ends."
            )
            report == null -> common.copy(
                stage = "timeout", timedOut = true,
                message = "No verdict for the $verb of '${box.name}' within ${timeoutSeconds}s. " +
                    "It may still be compiling or be stuck; check ReadBoxOutput, or StopBox it."
            )
            !report.success -> common.copy(
                stage = report.stage, error = report.error.take(4000),
                message = "The $verb of '${box.name}' FAILED. Read `error` and `errors`, fix the code, and try again."
            )
            else -> common.copy(
                success = true, stage = report.stage,
                message = if (launch) "Launched '${box.name}'; setup completed" + (if (running == true) " and it is running." else " and it is not running.")
                else "Ran '${box.name}' successfully."
            )
        }
    }

    fun stop(req: StopBoxRequest): FieldGraphResult {
        val box = host.onOwner(info) { resolve(req.box) }
        host.stop(info, box)
        // Stopping cancels jobs; give the cancellation a moment to land before reporting.
        val deadline = System.currentTimeMillis() + 2000
        while (isRunning(box) && System.currentTimeMillis() < deadline) Thread.sleep(25)
        val running = isRunning(box)
        return FieldGraphResult(!running, if (running) "Asked '${box.name}' to stop, but it is still running." else "Stopped '${box.name}'.",
            uid = box.id, name = box.name, running = running)
    }

    /** A picture of [box]'s output area, through the host. Used by [viewOutput] and by picture checks. */
    fun captureOutput(box: Box, settleMs: Int = 300): BoxOutputShot = host.captureOutput(info, box, settleMs)

    fun viewOutput(req: ViewBoxOutputRequest): BoxOutputView {
        val box = host.onOwner(info) { resolve(req.box) }
        val shot = captureOutput(box, if (req.settleMs <= 0) 300 else req.settleMs.coerceIn(0, 5000))
        val image = shot.image
        if (!shot.success || image == null) return BoxOutputView(false, shot.message, uid = box.id, name = box.name, channels = shot.channels)
        val stats = PictureStats.of(image)
        val blank = stats.inkFraction < 0.002
        return BoxOutputView(
            success = true,
            message = "Captured the output of '${box.name}' (${shot.channels.joinToString()}), ${image.width}x${image.height}" +
                (if (blank) ". It looks BLANK: almost every pixel is the background colour ${stats.dominant.firstOrNull()?.first ?: ""}." else ".") +
                " The picture is attached. This is an independent browser; viewerStates records its geometry view, which may differ from the user's.",
            uid = box.id, name = box.name, channels = shot.channels, width = image.width, height = image.height,
            inkFraction = stats.inkFraction,
            dominantColours = stats.dominant.map { (hex, share) -> "$hex ${"%.1f".format(share * 100)}%" },
            path = shot.path, imageAttached = true, viewerStates = shot.viewerStates, dataUrl = jpegDataUrl(image), note = req.note,
        )
    }

    fun readOutput(req: ReadBoxOutputRequest): FieldGraphResult {
        val box = host.onOwner(info) { resolve(req.box) }
        val deadline = System.currentTimeMillis() + (req.waitSeconds.coerceIn(0.0, 120.0) * 1000).toLong()
        fun matches(page: BoxOutputLog.Page) = req.contains.isEmpty() || page.entries.any { it.text.contains(req.contains) }
        var page = BoxOutputLog.since(box, req.since)
        while (!matches(page) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            page = BoxOutputLog.since(box, req.since)
        }
        val found = if (req.contains.isEmpty()) null else matches(page)
        val limit = req.maxEntries.coerceIn(1, 400)
        val entries = page.entries.takeLast(limit).map { FieldOutputEntry(it.seq, it.kind, it.text.take(2000), it.channel, it.line) }
        return FieldGraphResult(
            success = found != false,
            message = buildString {
                append("${page.entries.size} entr${if (page.entries.size == 1) "y" else "ies"} from '${box.name}'")
                if (page.dropped) append(" (older entries were dropped)")
                if (found == false) append("; none contained '${req.contains}' within ${req.waitSeconds}s")
                append('.')
            },
            uid = box.id, name = box.name, entries = entries, outputMarker = page.marker,
            found = found, timedOut = found == false, running = isRunning(box)
        )
    }
}

/**
 * The editor chat shows the model Kotlin wrapped as `/*preamble*/ fun SCRIPT() { … /*postamble*/ }`
 * ([afield.runtime.languages.KOTLIN_SCRIPT]), and models pass that wrapper back as `code`. Run as-is it only
 * *declares* `SCRIPT` — which compiles, "succeeds", and prints nothing: the first live editor-turn
 * candidate run on 2026-09-29 did exactly that. Strip it, the way the editor strips its own reply.
 */
internal fun unwrapEditorScript(code: String): String =
    if (code.contains("/*preamble*/") && code.contains("/*postamble*/")) afield.runtime.languages.unwrapScriptLenient(code).trimEnd() + "\n"
    else code

/** Registers the graph tools on [tools], each gated on the context carrying a [FieldGraphTools]. */
internal fun registerFieldGraphTools(tools: ToolRegistry) {
    val on = { ctx: afield.ai.tools.ToolExecutionContext -> ctx.fieldGraph != null }
    tools.register(ListBoxesRequest::class.java, on) { ctx, _ -> ctx.fieldGraph!!.listBoxes() }
    tools.register(NewBoxRequest::class.java, on) { ctx, req -> ctx.fieldGraph!!.newBox(req) }
    tools.register(DeleteBoxRequest::class.java, on) { ctx, req -> ctx.fieldGraph!!.deleteBox(req) }
    tools.register(SetBoxPropertyRequest::class.java, on) { ctx, req -> ctx.fieldGraph!!.setProperty(req) }
    tools.register(GetBoxPropertyRequest::class.java, on) { ctx, req -> ctx.fieldGraph!!.getProperty(req) }
    tools.register(PatchBoxTextRequest::class.java, on) { ctx, req -> ctx.fieldGraph!!.patchText(req) }
    tools.register(ConnectBoxesRequest::class.java, on) { ctx, req -> ctx.fieldGraph!!.connect(req) }
    tools.register(DisconnectBoxesRequest::class.java, on) { ctx, req -> ctx.fieldGraph!!.disconnect(req) }
    tools.register(RunBoxRequest::class.java, on) { ctx, req -> ctx.fieldGraph!!.run(req) }
    tools.register(LaunchBoxRequest::class.java, on) { ctx, req -> ctx.fieldGraph!!.launch(req) }
    tools.register(StopBoxRequest::class.java, on) { ctx, req -> ctx.fieldGraph!!.stop(req) }
    tools.register(ReadBoxOutputRequest::class.java, on) { ctx, req -> ctx.fieldGraph!!.readOutput(req) }
    tools.register(SymbolAtRequest::class.java, on) { ctx, req -> ctx.fieldGraph!!.symbolAt(req) }
    tools.register(ViewBoxOutputRequest::class.java, { ctx -> ctx.fieldGraph?.seesOutput == true }) { ctx, req -> ctx.fieldGraph!!.viewOutput(req) }
}

/** The tool names [registerFieldGraphTools] adds, for instructions and tests. */
val fieldGraphToolNames: List<String> = listOf(
    ListBoxesRequest::class, NewBoxRequest::class, DeleteBoxRequest::class, SetBoxPropertyRequest::class,
    GetBoxPropertyRequest::class, PatchBoxTextRequest::class, ConnectBoxesRequest::class, DisconnectBoxesRequest::class, RunBoxRequest::class,
    LaunchBoxRequest::class, StopBoxRequest::class, ReadBoxOutputRequest::class, ViewBoxOutputRequest::class, SymbolAtRequest::class
).map { it.java.simpleName }
