package afield.ai.agent

import afield.app.server.ReadOnlyDocuments
import afield.app.server.SessionInformation
import afield.ai.completion.*
import afield.ai.conversation.*
import afield.ai.diagnostics.*
import afield.ai.models.*
import afield.ai.tools.*
import afield.runtime.languages.*
import afield.graphics.three.ThreeReviewInbox
import afield.ui.visualization.GeometryFinding
import afield.ui.visualization.geometryReviewSurfacesOwnedBy
import afield.graphics.three.threeViewsOwnedBy
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonIgnore
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.Base64

/**
 * AI4 tools for the geometry review inbox: listing a document's Three.js and canvas review views, reading and answering their notes, reporting findings, styling parts and inspecting a view.
 *
 * Each request class here is one tool, described to the model by its `@JsonClassDescription`; every
 * call returns a [GeometryToolResult], which can attach saved pictures as images. The tools read and
 * write the same notes the viewer shows, scoped to the current document.
 */

/**
 * List the review views this document owns. This is where a `viewId` and the current `expectedRevision`
 * come from, and the other tools here need both. Views belonging to other documents are not listed.
 */
@JsonClassDescription("List live Three.js and custom canvas review views owned by this document, with source boxes, geometry revisions and unresolved note counts. Returns success plus one entry per view. Use it when you need a viewId or the current expectedRevision and do not have them; views in other documents are excluded. A document with no views returns an empty list, not an error. e.g. an entry has viewId = `bracket-view`.")
@AgentToolMeta("geometry", ToolExposure.DIRECT, effects = [ToolEffect.READ_DOCUMENT])
data class ListGeometryViewsRequest(val unused: Int = 0)

/**
 * Colour, highlight or ghost named parts of a solid viewer. The style map is stored on the source box, so
 * it survives a rebuild that keeps the same part IDs; the geometry, the camera, the selection, the notes
 * and the build's currency are all left alone.
 */
@JsonClassDescription("Style named solid parts in an existing document-owned Three.js viewer. Returns success and the stored style map. Use it when a task asks to colour, highlight or ghost parts; it needs part IDs and the revision from InspectGeometryViewRequest. An unknown viewer, a stale revision or more than 10000 parts fails with success=false. e.g. parts = [{partId = `lid`, color = [1, 0, 0]}]. RGB colours are 0..1; unlit preserves colour without lighting; xrayOpacity=1 keeps a part legible when the surrounding surfaces fade. Stores presentation on the source box, surviving rebuilds with the same part IDs. Does not alter geometry, checks, camera, selection, notes or build currency. replace=true replaces this view's whole style map; an empty replacement restores defaults.")
@AgentToolMeta("geometry", ToolExposure.DEFERRED, effects = [ToolEffect.MUTATE_DOCUMENT])
data class StyleGeometryPartsRequest(val viewId: String, val expectedRevision: String,
    /** The parts to restyle, at most 10000, each named once and each present in the view as it stands. */
    val parts: List<GeometryPartColor>,
    /** Draw them at their own colour, with neither shading nor tone mapping. */
    val unlit: Boolean = false,
    /** How solid these parts stay while the rest fades, from 0 to 1, or null to follow the viewer's slider. */
    val xrayOpacity: Double? = null,
    /** Replace the view's whole style map rather than adding to it; an empty replacement restores defaults. */
    val replace: Boolean = false)

/** One part's colour, as RGB from 0 to 1. */
data class GeometryPartColor(val partId: String, val color: List<Double>)


/**
 * A page of a view's notes, as summaries rather than whole notes: id, kind, status and the revision each
 * was made against. Reading leaves every status where it was. A note that came from a build attempt says
 * whether that attempt ran against earlier inputs, in which case it may describe a design that is gone.
 */
@JsonClassDescription("Read a page of geometry annotation summaries, without opening a browser or acknowledging any note. Returns success, the page of note summaries (id, kind, status, revision) and the total. Use it when you need to know which notes a view has, or a noteId. Open notes are the default. An unknown view, or limit outside 1..30, fails with success=false. e.g. viewId = `bracket-view`, limit = 10. ReadGeometryNoteRequest returns a complete note and its saved pictures. Original annotation revisions can differ from the current geometry revision. A finding from a build attempt says fromEarlierInputs; true means that attempt ran against an earlier design, so the finding may no longer apply.")
@AgentToolMeta("geometry", ToolExposure.DEFERRED, effects = [ToolEffect.READ_DOCUMENT, ToolEffect.FILESYSTEM_READ])
data class ReadGeometryReviewsRequest(val viewId: String, val includeResolved: Boolean = false, val offset: Int = 0, val limit: Int = 10)

/**
 * One note whole: its text, the feature and view it points at, its replies and its resolution history,
 * with the pictures saved when it was made attached as images. Those pictures are of the revision that was
 * annotated, not of the geometry as it stands now; a picture whose bytes do not match its recorded hash
 * is reported as a warning rather than attached.
 */
@JsonClassDescription("Read one geometry annotation including its original feature/view reference, replies and resolution history. Use it when you need one note's full text or pictures, e.g. noteId = `n7` from ReadGeometryReviewsRequest; an unknown note fails with success=false and points at that list. Returns the note and attaches the saved context and crop as actual images when available. These are historical pictures of the annotated revision, not freshly rendered current geometry. Reading does not resolve the note or change the user's selection. A finding from a build attempt says fromEarlierInputs, as in ReadGeometryReviewsRequest.")
@AgentToolMeta("geometry", ToolExposure.DEFERRED, effects = [ToolEffect.READ_DOCUMENT, ToolEffect.FILESYSTEM_READ])
data class ReadGeometryNoteRequest(val viewId: String, val noteId: String, val includeImages: Boolean = true)

/**
 * Add a reply to a note without closing it: what was checked, and against which revision. The note version
 * from the latest read has to still be current, so two writers cannot answer the same note blind.
 * [ReviewGeometryNoteRequest] is the one that changes a note's status.
 */
@JsonClassDescription("Append a durable finding, decision or verification reply to a geometry note without changing its open/resolved status. Returns success and the note's new version. Use it when you have checked something about a note and want to record it; to close the note use ReviewGeometryNoteRequest instead. Supply the current geometry revision actually checked and the note version from the latest read; stale writes fail with success=false. e.g. text = `the lid gap is 0.4 mm after the fix`, expectedVersion = 3.")
@AgentToolMeta("geometry", ToolExposure.DEFERRED, effects = [ToolEffect.MUTATE_DOCUMENT, ToolEffect.FILESYSTEM_WRITE])
data class ReplyGeometryNoteRequest(val viewId: String, val noteId: String, val text: String, val actor: String, val checkedRevision: String, val expectedVersion: Int)

/**
 * Resolve a note, or reopen one, with the revision the decision was checked against. The original note,
 * its pictures, its replies and its history are kept either way. A note having been read, or the geometry
 * having been rebuilt, is not a reason to resolve it.
 */
@JsonClassDescription("Explicitly resolve a verified geometry note, or reopen it (status: `resolved` or `open`). Returns success and the note's new version and status. Use it only when you have verified the current geometry against the note; a stale revision or note version fails with success=false. Supply an explanation, current geometry revision actually checked, and latest note version. Never resolve merely because a note was read or geometry rebuilt. Preserves the original note, pictures, replies and history.")
@AgentToolMeta("geometry", ToolExposure.DEFERRED, effects = [ToolEffect.MUTATE_DOCUMENT, ToolEffect.FILESYSTEM_WRITE])
data class ReviewGeometryNoteRequest(val viewId: String, val noteId: String, val status: String, val summary: String, val actor: String, val checkedRevision: String, val expectedVersion: Int)

/**
 * Put a finding in front of a person, as a note in the same inbox their own annotations go to. It captures
 * its own pictures in a separate viewer, so it never moves the camera the person is looking through, and it
 * checks the revision again on the document thread: a capture that finished late cannot become a finding
 * against newer geometry. Every field below is required, and two reports agreeing on `key`, `stage`,
 * `runId` and `producer` are the same occurrence and reuse one thread.
 */
@JsonClassDescription("Ask for review of a geometry finding in the existing annotation inbox. Returns success, the note id and the captured pictures. Use it when a check finds something a person should see; a missing selection, an empty text or a stale revision fails with success=false. e.g. kind = `warning`, key = `wall-thickness/lid`. Supply a complete stateJson from InspectGeometryViewRequest with an actual point/region selection. Captures fresh context/crop images in an independent viewer; never moves the user's camera or changes build acceptance. kind is error (a failed check), warning (a suspected problem), or question (a decision needed). key identifies the check and subject; runId identifies its build/diagnostic attempt. Repeated identical reports reuse the thread. State what happens if left unchanged in consequence, and what feedback is needed in prompt. A repair that can proceed autonomously should say so rather than unnecessarily requiring user approval.")
@AgentToolMeta("geometry", ToolExposure.DEFERRED, effects = [ToolEffect.READ_DOCUMENT, ToolEffect.MUTATE_DOCUMENT, ToolEffect.CAPTURE_IMAGE, ToolEffect.FILESYSTEM_WRITE])
data class ReportGeometryFindingRequest(
    /** The view whose inbox the note goes to, from [ListGeometryViewsRequest]. */
    val viewId: String,
    /** The revision this finding is about, which has to still be the view's when the capture lands. */
    val expectedRevision: String,
    /** A complete state from [InspectGeometryViewRequest], and one with a point or region selected. */
    val stateJson: String,
    /** What was checked and on what, as something stable across runs: `wall-thickness/lid`. */
    val key: String,
    /** `error` for a failed check, `warning` for a suspected problem, `question` for a decision needed. */
    val kind: String,
    /** The build stage the check belongs to. */
    val stage: String,
    /** The build or diagnostic attempt it came from. */
    val runId: String,
    /** What made the finding. */
    val producer: String,
    /** The finding itself, 1 to 16000 characters. */
    val text: String,
    /** What happens if it is left as it is, to 4000 characters. */
    val consequence: String,
    /** What is wanted back, to 4000 characters; a repair that needs no permission should say so. */
    val prompt: String,
)

/**
 * Look at the geometry as it stands: a fresh picture from a capture browser of its own, a compact part
 * hierarchy, and a state complete enough to frame the next inspection the same way. The person's camera and
 * selection are untouched. Only a solid viewer can be inspected this way; a custom canvas is refused, with
 * the output box to use `ViewBoxOutputRequest` on instead.
 */
@JsonClassDescription("Inspect current Three.js geometry in a separate capture browser. Returns a fresh picture, a compact part hierarchy and the complete restorable state. Use it when you need to see or measure current geometry; for a custom canvas use ViewBoxOutputRequest instead, and this tool fails saying so. A stale expectedRevision, or invalid camera, pick or section values, fails with success=false and the reason. e.g. camera = [2, 1.5, 3], target = [0, 0, 0]. Never moves the user's camera or publishes a manual selection. Get expectedRevision from ListGeometryViewsRequest. stateJson must be the COMPLETE state returned by a previous inspection, never a fabricated partial state. Use camera and target to change framing in viewer coordinates (Y up). Optionally adjust explode/visibility/x-ray/section or pick the nearest visible feature. includeMetadata adds full source provenance and redundant part-tree details; normally leave false. Historical notes remain readable with ReadGeometryNoteRequest.")
@AgentToolMeta("geometry", ToolExposure.DEFERRED, effects = [ToolEffect.READ_DOCUMENT, ToolEffect.CAPTURE_IMAGE])
data class InspectGeometryViewRequest(
    /** The view to capture, which has to be a solid viewer rather than a custom canvas. */
    val viewId: String,
    /** The revision from [ListGeometryViewsRequest]. Geometry that changed under it fails the call. */
    val expectedRevision: String,
    /** A note to frame the view the way it was annotated, instead of `stateJson`, and never both. */
    val noteId: String = "",
    /**
     * The complete state a previous inspection returned, to 128000 characters, carrying at least its part
     * IDs, camera and target. A partial or invented state is refused; to change only the framing, pass
     * `camera` and `target` instead.
     */
    val stateJson: String = "",
    /** How far to pull the parts apart, as a finite distance of zero or more. */
    val explode: Double? = null,
    /** Parts to leave out of the picture, at most 10000 of them. */
    val hidden: List<String>? = null,
    /** Fade the surfaces that are not the subject. */
    val xray: Boolean? = null,
    /** How solid the faded surfaces stay, from 0.03 to 1. */
    val xrayOpacity: Double? = null,
    /** The normal of a cutting plane, as a finite 3-vector that is not all zeroes. */
    val sectionNormal: List<Double>? = null,
    /** Where along that normal to cut, from 0 to 1 across the geometry. */
    val sectionFraction: Double = 0.5,
    /** A point to pick the nearest visible feature at, instead of a region. */
    val pick: GeometryPick? = null,
    /** A rectangle to select, instead of a point. */
    val region: GeometryRegion? = null,
    /** Where to put the camera, as a finite 3-vector in viewer coordinates, Y up. */
    val camera: List<Double>? = null,
    /** What to point it at, in the same coordinates; it has to differ from [camera]. */
    val target: List<Double>? = null,
    /** Add the source provenance and the full part tree, which most inspections do not need. */
    val includeMetadata: Boolean = false,
)

/** A point to pick at, in normalized view coordinates, and what kind of feature to pick there. */
data class GeometryPick(val x: Double, val y: Double, val kind: String = "face")

/** A rectangle to select, centred at `cx`, `cy` in normalized view coordinates and rotated by `angle`. */
data class GeometryRegion(val cx: Double, val cy: Double, val width: Double, val height: Double, val angle: Double = 0.0)

/** What every geometry review tool answers with, pictures included. */
data class GeometryToolResult(
    /** False when the request was refused or the view was unavailable, with the reason in [message]. */
    val success: Boolean,
    /** One sentence about what happened. */
    val message: String,
    /** Whatever the tool reports: the views, a page of notes, a captured observation, a note's new version. */
    val details: Map<String, Any?> = emptyMap(),
    /** Anything the reader should know about the answer, such as a picture that could not be verified. */
    val warnings: List<String> = emptyList(),
    /** The pictures themselves, kept out of the JSON the model reads as text. */
    @get:JsonIgnore
    override val toolImages: List<ToolImage> = emptyList(),
) : CarriesToolImages, SummarisesForToolLog {
    /** How many pictures came back. */
    val imageCount: Int get() = toolImages.size

    /** The verdict, the picture count and the message, which is what the tool log keeps. */
    override fun toolLogSummary(): String = "success=$success images=$imageCount $message"
}

/**
 * Document-scoped adapters over the same inbox used by the viewer and external inspection tools.
 * [buildRuns] says, for the build attempts behind findings' run ids, whether each ran against earlier
 * inputs; it is called off the owner thread, because a build's currency reads its inputs.
 */
internal class GeometryReviewTools(
    private val info: SessionInformation,
    private val onOwner: (() -> GeometryToolResult) -> GeometryToolResult,
    private val buildRuns: (Set<String>) -> Map<String, Boolean> = { emptyMap() },
) {
    private data class ReviewView(val uid: String, val reviewInbox: ThreeReviewInbox, val kind: String, val captureBox: String?) {
        fun reviews(includeResolved: Boolean = false) = reviewInbox.snapshot(includeResolved)
    }
    private fun views(): List<ReviewView> {
        val boxes = info.boxes.values + info.root
        return threeViewsOwnedBy(boxes).map { ReviewView(it.uid, it.reviewInbox, "threejs", it.reviewInbox.owner?.id) } +
            geometryReviewSurfacesOwnedBy(boxes).map { ReviewView(it.uid, it.reviewInbox, "canvas", it.outputBox.id) }
    }

    private fun view(id: String): ReviewView = views().firstOrNull { it.uid == id }
        ?: error("No live geometry view '$id' in this document. Call ListGeometryViewsRequest; run its source box if needed.")

    private fun guarded(action: () -> GeometryToolResult): GeometryToolResult = try { onOwner(action) }
    catch (e: IllegalArgumentException) { GeometryToolResult(false, e.message ?: "Invalid geometry review request") }
    catch (e: IllegalStateException) { GeometryToolResult(false, e.message ?: "Geometry review is unavailable") }

    fun list(): GeometryToolResult = guarded {
        val entries = views().map { view ->
            val snapshot = view.reviews()
            mapOf("viewId" to view.uid, "viewKind" to view.kind, "captureBox" to view.captureBox, "captureTool" to (if (view.kind == "threejs") "InspectGeometryViewRequest" else "ViewBoxOutputRequest"), "sourceBox" to view.reviewInbox.owner?.id,
                "sourceName" to view.reviewInbox.owner?.name, "revision" to snapshot["revision"]?.plain(),
                "openCount" to snapshot["openCount"]?.plain(), "resolvedCount" to snapshot["resolvedCount"]?.plain())
        }
        GeometryToolResult(true, "${entries.size} geometry views in ${info.documentName}", mapOf("views" to entries))
    }

    private fun runId(note: Any?): String? {
        val finding = (note as? Map<*, *>)?.get("finding") as? Map<*, *>
        return (finding?.get("runId") as? String)?.takeIf { it.isNotBlank() }
    }

    private fun earlierRuns(runIds: Set<String>): Map<String, Boolean> =
        if (runIds.isEmpty()) emptyMap() else try { buildRuns(runIds) } catch (e: Exception) { emptyMap() }

    // A finding from a build attempt against earlier inputs keeps its discussion status but, as on the build
    // card, no longer describes the current design.
    fun read(request: ReadGeometryReviewsRequest): GeometryToolResult {
        val read = readPage(request)
        val page = read.details["notes"] as? List<*> ?: return read
        val earlier = earlierRuns(page.mapNotNull(::runId).toSet())
        if (earlier.isEmpty()) return read
        val marked = page.map { note -> runId(note)?.let(earlier::get)?.let { (note as Map<*, *>) + ("fromEarlierInputs" to it) } ?: note }
        val count = marked.count { (it as Map<*, *>)["fromEarlierInputs"] == true }
        return read.copy(details = read.details + ("notes" to marked), message = read.message +
            if (count == 0) "" else "; $count from a build attempt with earlier inputs, which may no longer apply (fromEarlierInputs)")
    }

    private fun readPage(request: ReadGeometryReviewsRequest): GeometryToolResult = guarded {
        require(request.offset >= 0 && request.limit in 1..30) { "Use offset >= 0 and limit between 1 and 30" }
        val snapshot = view(request.viewId).reviews(request.includeResolved)
        val notes = snapshot.getValue("notes").jsonArray
        val page = notes.drop(request.offset).take(request.limit).map { entry ->
            val note = entry.jsonObject
            val text = note["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val summary = note.filterKeys { it in setOf("id", "status", "version", "artifactDigest", "createdAt", "sourceBox", "finding", "stale") }.mapValues { it.value.plain() }
            summary + mapOf("text" to text.take(1200), "textTruncated" to (text.length > 1200),
                "hasImages" to (note["evidence"] is JsonObject),
                "replyCount" to ((note["replies"] as? JsonArray)?.size ?: 0),
                "partId" to ((note["selection"] as? JsonObject)?.get("partId")?.plain()))
        }
        GeometryToolResult(true, "${page.size} of ${notes.size} notes; reading leaves their status unchanged",
            snapshot.filterKeys { it !in setOf("notes", "selection") }.mapValues { it.value.plain() } +
                mapOf("notes" to page, "offset" to request.offset, "total" to notes.size,
                    "nextOffset" to (request.offset + page.size).takeIf { it < notes.size }))
    }

    fun note(request: ReadGeometryNoteRequest): GeometryToolResult {
        val read = readNote(request)
        val run = runId(read.details["note"]) ?: return read
        val earlier = earlierRuns(setOf(run))[run] ?: return read
        return read.copy(details = read.details + ("fromEarlierInputs" to earlier))
    }

    private fun readNote(request: ReadGeometryNoteRequest): GeometryToolResult = guarded {
        val view = view(request.viewId)
        val snapshot = view.reviews(true)
        val note = findNote(snapshot, request.noteId)
        val images = mutableListOf<ToolImage>()
        val warnings = mutableListOf<String>()
        if (request.includeImages) {
            val evidence = note["evidence"] as? JsonObject
            if (evidence == null) warnings += "This note has no saved pictures; none have been reconstructed."
            else for (kind in listOf("context", "crop")) {
                val hash = (evidence[kind] as? JsonObject)?.get("sha256")?.jsonPrimitive?.contentOrNull
                val bytes = hash?.let { view.reviewInbox.image(it) }
                if (bytes == null || sha256(bytes) != hash) warnings += "The saved $kind image is missing or differs from its recorded hash."
                else images += ToolImage("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes),
                    "Saved $kind image for note ${request.noteId}, view ${request.viewId}, annotated geometry ${note["artifactDigest"]?.jsonPrimitive?.contentOrNull}. Historical evidence; current geometry is ${snapshot["revision"]?.jsonPrimitive?.contentOrNull}.")
            }
        }
        GeometryToolResult(true, "Read annotation ${request.noteId}", mapOf("viewId" to request.viewId,
            "currentRevision" to snapshot["revision"]?.plain(), "note" to note.plain()), warnings, images)
    }

    fun reply(request: ReplyGeometryNoteRequest): GeometryToolResult = update(request.viewId, buildJsonObject {
        put("id", request.noteId); put("reply", request.text); put("actor", request.actor)
        put("checkedRevision", request.checkedRevision); put("expectedVersion", request.expectedVersion)
    })

    fun review(request: ReviewGeometryNoteRequest): GeometryToolResult = update(request.viewId, buildJsonObject {
        put("id", request.noteId); put("status", request.status); put("summary", request.summary); put("actor", request.actor)
        put("checkedRevision", request.checkedRevision); put("expectedVersion", request.expectedVersion)
    })

    private fun update(viewId: String, request: JsonObject): GeometryToolResult = guarded {
        check(!info.readOnly && !ReadOnlyDocuments.global) { "This document is read-only" }
        val updated = view(viewId).reviewInbox.updateNote(request)
        GeometryToolResult(true, "Annotation updated", updated.mapValues { it.value.plain() })
    }

    fun report(request: ReportGeometryFindingRequest): GeometryToolResult {
        val prepared = guarded {
            GeometryFinding(request.key, request.kind, request.stage, request.runId, request.producer, request.consequence, request.prompt).json()
            require(request.text.isNotBlank() && request.text.length <= 16_000) { "Explain the finding in 1..16000 characters" }
            require(request.stateJson.isNotBlank()) { "Inspect and select the feature before reporting it" }
            check(!info.readOnly && !ReadOnlyDocuments.global) { "Document is read-only" }
            GeometryToolResult(true, "Finding prepared")
        }
        if (!prepared.success) return prepared
        val capture = inspect(InspectGeometryViewRequest(request.viewId, request.expectedRevision, stateJson = request.stateJson), request.text)
        if (!capture.success) return capture
        return guarded {
            check(!info.readOnly && !ReadOnlyDocuments.global) { "Document is read-only" }
            // receive checks the revision again on the document thread: a late capture
            // cannot become a finding against a newer design.
            val record = Json.parseToJsonElement(aiToolMapper.writeValueAsString(capture.details.getValue("observation"))).jsonObject
            val finding = GeometryFinding(request.key, request.kind, request.stage, request.runId, request.producer, request.consequence, request.prompt).json()
            val result = view(request.viewId).reviewInbox.receive(JsonObject(record + ("finding" to finding)), true)
            GeometryToolResult(true, result["message"]?.jsonPrimitive?.contentOrNull ?: "Finding recorded",
                mapOf("viewId" to request.viewId, "noteId" to result["id"]?.plain(), "existing" to (result["existing"]?.plain() ?: false), "buildAcceptanceChanged" to false))
        }
    }

    fun inspect(request: InspectGeometryViewRequest, observationText: String? = null): GeometryToolResult {
        val prepared = guarded {
            require(request.expectedRevision.isNotBlank()) { "Supply the geometry revision from ListGeometryViewsRequest" }
            require(request.noteId.isEmpty() || request.stateJson.isEmpty()) { "Restore a note or a view state, not both" }
            require(request.stateJson.length <= 128_000) { "Inspection state is too large" }
            listOf("camera" to request.camera, "target" to request.target).forEach { (name, vector) ->
                require(vector == null || vector.size == 3 && vector.all(Double::isFinite)) { "$name must be a finite 3-vector in viewer coordinates (Y up)" }
            }
            require(request.camera == null || request.target == null || request.camera != request.target) { "Camera and target must be different" }
            require(request.explode == null || request.explode.isFinite() && request.explode >= 0) { "Explode must be a finite non-negative distance" }
            require(request.hidden == null || request.hidden.size <= 10_000) { "Too many hidden parts" }
            require(request.xrayOpacity == null || request.xrayOpacity.isFinite() && request.xrayOpacity in 0.03..1.0) { "X-ray opacity must be between 0.03 and 1" }
            require(request.sectionFraction.isFinite() && request.sectionFraction in 0.0..1.0) { "Section fraction must be between 0 and 1" }
            request.sectionNormal?.let { require(it.size == 3 && it.all(Double::isFinite) && it.any { v -> v != 0.0 }) { "Section normal must be a finite, nonzero 3-vector" } }
            request.pick?.let { require(it.x in 0.0..1.0 && it.y in 0.0..1.0 && it.kind in setOf("point", "face", "edge", "vertex")) { "Pick coordinates must be normalized and kind point, face, edge or vertex" } }
            request.region?.let { require(it.cx in 0.0..1.0 && it.cy in 0.0..1.0 && it.width > 0 && it.width <= 1 && it.height > 0 && it.height <= 1 && it.angle.isFinite()) { "Invalid normalized inspection rectangle" } }
            require(request.pick == null || request.region == null) { "Pick a point or a region, not both" }
            val target = view(request.viewId)
            require(target.kind == "threejs") { "This is a custom canvas. Use ViewBoxOutputRequest for output box ${target.captureBox}; ReadGeometryNoteRequest returns saved annotation pictures." }
            val snapshot = target.reviews(true)
            check(snapshot["revision"]?.jsonPrimitive?.contentOrNull == request.expectedRevision) { "Geometry changed; inspect the current revision before capturing" }
            val note = request.noteId.takeIf { it.isNotEmpty() }?.let { findNote(snapshot, it) }
            require(note == null || note["artifactDigest"]?.jsonPrimitive?.contentOrNull == request.expectedRevision) { "That note belongs to older geometry. ReadGeometryNoteRequest returns its saved pictures; inspect the current geometry separately." }
            val state = request.stateJson.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).jsonObject }
            require(state == null || state["artifactDigest"]?.jsonPrimitive?.contentOrNull == request.expectedRevision) { "Inspection state belongs to different geometry" }
            require(state == null || state["partIds"] is JsonArray && state["camera"] is JsonArray && state["target"] is JsonArray) {
                "stateJson requires the complete state from a previous inspection, including partIds, camera and target. Use camera/target arguments for framing without restoring state."
            }
            if (observationText != null) require(state?.get("selection") is JsonObject) { "The inspected state has no selected feature" }
            GeometryToolResult(true, "Inspection prepared", mapOf("note" to note?.plain(), "state" to state?.plain(), "observationText" to observationText))
        }
        if (!prepared.success) return prepared
        val result = GeometryViewCapture.capture(request, prepared.details)
        if (!result.success) return result
        val current = guarded {
            check(view(request.viewId).reviews()["revision"]?.jsonPrimitive?.contentOrNull == request.expectedRevision) { "Geometry changed during capture; inspect the new revision before continuing" }
            GeometryToolResult(true, "Revision unchanged")
        }
        return if (current.success) result else result.copy(success = false, message = current.message)
    }

    fun style(request: StyleGeometryPartsRequest): GeometryToolResult = guarded {
        check(!info.readOnly && !ReadOnlyDocuments.global) { "Document is read-only" }
        val target = threeViewsOwnedBy(info.boxes.values + info.root).singleOrNull { it.uid == request.viewId }
            ?: error("No solid viewer with that ID in this document")
        require(request.parts.size <= 10000 && request.parts.map { it.partId }.toSet().size == request.parts.size) { "Supply at most 10000 distinct part IDs" }
        val styles = request.parts.associate { it.partId to afield.graphics.three.ThreePartAppearance(it.color, request.unlit, request.xrayOpacity) }
        target.styleParts(request.expectedRevision, styles, request.replace)
        GeometryToolResult(true, "Updated part appearance; geometry is unchanged", mapOf("viewId" to target.uid, "revision" to request.expectedRevision, "styledParts" to styles.size))
    }

    private fun findNote(snapshot: JsonObject, id: String): JsonObject = snapshot.getValue("notes").jsonArray
        .map { it.jsonObject }.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == id }
        ?: error("No note '$id' in this view. ReadGeometryReviewsRequest lists available notes.")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Keep kotlinx JSON out of Jackson's reflection path and preserve JSON null/number semantics. */
internal fun JsonElement.plain(): Any? = when (this) {
    JsonNull -> null
    is JsonObject -> mapValues { it.value.plain() }
    is JsonArray -> map { it.plain() }
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
}

internal fun registerGeometryReviewTools(tools: ToolRegistry) {
    tools.register(StyleGeometryPartsRequest::class.java, { it.fieldGraph != null }) { ctx, req -> ctx.fieldGraph!!.geometryReviews.style(req) }
    tools.register(ListGeometryViewsRequest::class.java, { it.fieldGraph != null }) { ctx, _ -> ctx.fieldGraph!!.geometryReviews.list() }
    tools.register(ReadGeometryReviewsRequest::class.java, { it.fieldGraph != null }) { ctx, req -> ctx.fieldGraph!!.geometryReviews.read(req) }
    tools.register(ReadGeometryNoteRequest::class.java, { it.fieldGraph != null }) { ctx, req ->
        ctx.fieldGraph!!.geometryReviews.note(req.copy(includeImages = req.includeImages && ctx.fieldGraph.seesOutput))
    }
    tools.register(ReplyGeometryNoteRequest::class.java, { it.fieldGraph != null }) { ctx, req -> ctx.fieldGraph!!.geometryReviews.reply(req) }
    tools.register(ReviewGeometryNoteRequest::class.java, { it.fieldGraph != null }) { ctx, req -> ctx.fieldGraph!!.geometryReviews.review(req) }
    tools.register(InspectGeometryViewRequest::class.java, { it.fieldGraph?.seesOutput == true }) { ctx, req -> ctx.fieldGraph!!.geometryReviews.inspect(req) }
    tools.register(ReportGeometryFindingRequest::class.java, { it.fieldGraph?.seesOutput == true }) { ctx, req -> ctx.fieldGraph!!.geometryReviews.report(req) }
}
