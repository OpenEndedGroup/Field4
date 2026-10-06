package afield.graphics

import afield.Box
import afield.METAROOT_ID
import afield.SavingContext
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Geometry identity for ordinary meshes, lines and points. Presentation and random callback IDs
 * are excluded, so orbiting, lighting and relaunching identical geometry do not invalidate a note.
 * Canonical solids retain their double-boundary identity; geometry displayed alongside them also
 * contributes. A moving ordinary point must invalidate a note even if every solid stays unchanged.
 */
internal fun threeReviewRevision(payload: ThreeGeometryPayload): String {
    val canonicalDigest = payload.inspector?.artifactDigest
    val geometryPayload = if (canonicalDigest == null) payload else {
        val canonicalParts = payload.inspector.parts.keys
        payload.copy(drawables = payload.drawables.filterNot { it.id in canonicalParts })
    }
    // Do not re-hash the canonical parts' display floats: their exact geometry is already covered,
    // and re-framing or duplicating vertices for shading must not retire existing solid-only notes.
    if (canonicalDigest != null && geometryPayload.lines == null && geometryPayload.fills == null &&
        geometryPayload.points == null && geometryPayload.drawables.isEmpty() &&
        geometryPayload.interactive.isEmpty() && geometryPayload.scene == null) return canonicalDigest
    val json = Json { encodeDefaults = true }
    val geometry = buildJsonObject {
        if (canonicalDigest != null) put("canonicalDigest", canonicalDigest)
        for ((key, value) in json.encodeToJsonElement(geometryPayload).jsonObject) {
            if (key in setOf("lines", "fills", "points", "drawables", "interactive", "scene")) put(key, value)
        }
    }
    fun stable(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.filterKeys { it !in setOf("callbackId", "sessionId", "hoverColor", "style", "label") }.mapValues { stable(it.value) })
        is JsonArray -> JsonArray(value.map(::stable))
        else -> value
    }
    return MessageDigest.getInstance("SHA-256").digest(stable(geometry).toString().toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Browser-to-Field review inbox. Notes are stored on the source box and survive document saves.
 * A new revision invalidates the live selection, while retaining old notes with their original revision.
 */
internal class ThreeReviewInbox(private val uid: String) {
    var owner: Box? = null
    // Local identity lives with the source box, so duplicate/moved documents retain notes
    // without sharing a live viewer. Old unscoped note IDs remain readable without rewriting.
    var localViewId: String? = null
    private fun belongs(note: JsonObject): Boolean = note["viewId"]?.jsonPrimitive?.contentOrNull == uid ||
        localViewId?.let { local ->
            note["localViewId"]?.jsonPrimitive?.contentOrNull == local ||
                note["viewId"]?.jsonPrimitive?.contentOrNull == local
        } == true

    private fun forCurrentView(note: JsonObject): JsonObject =
        if (note["viewId"]?.jsonPrimitive?.contentOrNull == uid) note else JsonObject(note + mapOf(
            "recordedViewId" to (note["recordedViewId"] ?: note.getValue("viewId")), "viewId" to JsonPrimitive(uid)))
    private var revision: String? = null
    private var selection: JsonObject? = null
    private var notes = listOf<JsonObject>()
    private val transientImages by lazy {
        // Rebinding a canvas in an ephemeral document must retain the images its notes reference.
        val box = owner
        if (box == null) java.nio.file.Files.createTempDirectory("field-three-review-")
        else synchronized(box) {
            box.properties.getOrPut("_geometryReviewImages") {
                java.nio.file.Files.createTempDirectory("field-three-review-")
            } as java.nio.file.Path
        }
    }
    private fun imageDirectory(): java.nio.file.Path = owner?.takeIf { it.isInsideInitialized() && !it.inside.ephemeral }
        ?.let { java.nio.file.Path.of(it.inside.filename).resolve(".geometry-review") } ?: transientImages

    @Synchronized fun image(hash: String): ByteArray? = ThreeReviewEvidence.read(hash, storedNotes().filter(::belongs), imageDirectory())

    @Synchronized fun update(revision: String) {
        if (this.revision != revision) selection = null
        this.revision = revision
    }

    @Synchronized fun snapshot(includeResolved: Boolean = false): JsonObject {
        val all = storedNotes().filter(::belongs).map { note ->
            JsonObject(mapOf("status" to JsonPrimitive("open"), "version" to JsonPrimitive(0)) + forCurrentView(note) +
                ("stale" to JsonPrimitive(note["artifactDigest"]?.jsonPrimitive?.contentOrNull != revision)))
        }
        return buildJsonObject {
            put("viewId", uid); put("revision", revision); put("sourceBox", owner?.id)
            put("selection", selection ?: JsonNull)
            put("notes", JsonArray(all.filter { includeResolved || it["status"]?.jsonPrimitive?.contentOrNull != "resolved" }))
            put("openCount", all.count { it["status"]?.jsonPrimitive?.contentOrNull != "resolved" })
            put("resolvedCount", all.count { it["status"]?.jsonPrimitive?.contentOrNull == "resolved" })
            put("includesResolved", includeResolved)
        }
    }

    /** Explicit acknowledgement, never a side effect of reading. Preserve the original note and evidence. */
    @Synchronized
    fun updateNote(request: JsonObject): JsonObject {
        require(request.toString().length <= 32_000) { "Review update is too large" }
        require(owner?.takeIf { it.isInsideInitialized() }?.inside?.readOnly != true) { "Document is read-only" }
        val id = request["id"]?.jsonPrimitive?.contentOrNull ?: error("Note id is required")
        val status = request["status"]?.jsonPrimitive?.contentOrNull
        val reply = request["reply"]?.jsonPrimitive?.contentOrNull?.trim()
        require((reply != null && status == null) || (reply == null && status in setOf("open", "resolved"))) { "Supply a reply or an open/resolved status, not both" }
        require(reply == null || (reply.isNotEmpty() && reply.length <= 16_000)) { "Reply must contain between 1 and 16000 characters" }
        val summary = request["summary"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val actor = request["actor"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        require(reply != null || (summary.isNotEmpty() && summary.length <= 8_000)) { "A short resolution or reopening explanation is required" }
        require(actor.isNotEmpty() && actor.length <= 200) { "Identify the person or agent making this update" }
        check(revision != null && request["checkedRevision"]?.jsonPrimitive?.contentOrNull == revision) { "Geometry changed; inspect the current revision before updating the note" }
        val previous = storedNotes()
        val index = previous.indexOfFirst { it["id"]?.jsonPrimitive?.contentOrNull == id && belongs(it) }
        require(index >= 0) { "Unknown note in this view" }
        val old = previous[index]
        val version = old["version"]?.jsonPrimitive?.intOrNull ?: 0
        check(request["expectedVersion"]?.jsonPrimitive?.intOrNull == version) { "Note changed; read the inbox again before updating it" }
        val changed = if (reply != null) {
            val replies = (old["replies"] as? JsonArray)?.toList().orEmpty()
            require(replies.size < 200) { "Reply history is full" }
            val entry = buildJsonObject {
                put("id", UUID.randomUUID().toString()); put("text", reply); put("actor", actor)
                put("checkedRevision", revision); put("at", Instant.now().toString()); put("version", version + 1)
            }
            JsonObject(old + mapOf("version" to JsonPrimitive(version + 1), "replies" to JsonArray(replies + entry)))
        } else {
            val history = (old["history"] as? JsonArray)?.toList().orEmpty()
            require(history.size < 200) { "Note history is full" }
            val event = buildJsonObject {
                put("status", status); put("summary", summary); put("actor", actor)
                put("checkedRevision", revision); put("at", Instant.now().toString()); put("version", version + 1)
            }
            JsonObject(old + mapOf("status" to JsonPrimitive(status), "version" to JsonPrimitive(version + 1),
                "resolution" to event, "history" to JsonArray(history + event)))
        }
        val updated = previous.toMutableList().also { it[index] = changed }
        require(JsonArray(updated).toString().length <= 2_000_000) { "Review inbox is full" }
        persist(updated)
        return buildJsonObject { put("ok", true); put("note", forCurrentView(changed)) }
    }

    private fun persist(updated: List<JsonObject>) {
        notes = updated
        owner?.properties?.set("geometryReviews", JsonArray(notes).toString())
        val box = owner
        if (box != null && box.isInsideInitialized() && !box.inside.ephemeral) {
            val info = box.inside
            SavingContext().save(info.filename, (info.boxes.values.filter { it.id != METAROOT_ID } + info.root).toSet(), "Geometry review")
        }
    }

    private fun storedNotes(): List<JsonObject> = owner?.getPropertyRaw("geometryReviews")?.let { raw ->
        runCatching { Json.parseToJsonElement(raw.toString()).jsonArray.map { it.jsonObject } }.getOrNull()
    } ?: notes

    /** Called on Field's main thread. Returns only after validation; stale reviews cannot mutate it. */
    @Synchronized
    fun receive(incoming: JsonObject, note: Boolean): JsonObject {
        require(incoming.toString().length <= 5_500_000) { "Review with images is too large" }
        val evidence = incoming["evidence"] as? JsonObject
        val record = JsonObject(incoming.filterKeys { it !in setOf("evidence", "status", "version", "resolution", "history", "replies", "localViewId", "recordedViewId") })
        require(record.toString().length <= 128_000) { "Review is too large" }
        require(record["viewId"]?.jsonPrimitive?.contentOrNull == uid) { "Review view differs" }
        require(revision != null && record["artifactDigest"]?.jsonPrimitive?.contentOrNull == revision) { "Geometry changed; select the feature again" }
        val view = record["view"]?.jsonObject ?: error("Review has no saved view")
        require(view["artifactDigest"]?.jsonPrimitive?.contentOrNull == revision) { "Saved view has another revision" }
        require(record["selection"] == view["selection"]) { "Selection and saved view disagree" }
        val feature = record["selection"]?.takeUnless { it == JsonNull }?.jsonObject
        if (feature != null) {
            require(feature["artifactDigest"]?.jsonPrimitive?.contentOrNull == revision) { "Feature has another revision" }
            require(feature["kind"]?.jsonPrimitive?.contentOrNull in setOf("point", "vertex", "edge", "face", "region")) { "Unknown feature kind" }
            val point = feature["point"]?.jsonArray ?: error("Feature has no point")
            require(point.size == 3 && point.all { it.jsonPrimitive.doubleOrNull?.isFinite() == true }) { "Invalid feature point" }
        }
        if (!note) { selection = record.takeIf { feature != null }; return buildJsonObject { put("ok", true) } }
        require(feature != null) { "Pick a feature before saving a note" }
        val text = record["text"]?.jsonPrimitive?.contentOrNull ?: error("Note has no text")
        require(text.length <= 16_000) { "Note is too long" }
        require(owner?.takeIf { it.isInsideInitialized() }?.inside?.readOnly != true) { "Document is read-only" }
        // Validate all note constraints before writing attachments.
        val previous = storedNotes()
        val finding = record["finding"]?.let {
            require(it is JsonObject) { "Finding metadata must be an object" }
            validateGeometryFinding(it)
            it
        }
        if (finding != null) {
            val prior = previous.firstOrNull { old ->
                belongs(old) && old["artifactDigest"] == record["artifactDigest"] &&
                    (old["finding"] as? JsonObject)?.let { sameFindingOccurrence(it, finding) } == true
            }
            if (prior != null) {
                require(prior["finding"] == finding && prior["text"] == record["text"] && prior["selection"] == feature) {
                    "This finding was already recorded with different evidence. Reply to its thread or use the new run identity."
                }
                return buildJsonObject { put("ok", true); put("id", prior.getValue("id")); put("existing", true); put("message", "Finding already recorded; its discussion and evidence are unchanged.") }
            }
        }
        require(previous.sumOf { it.toString().length } + record.toString().length + 2000 <= 2_000_000) { "Review inbox is full; export notes before adding more" }
        val attachments = evidence?.let { ThreeReviewEvidence.store(it, record, imageDirectory()) }
        val saved = JsonObject(record + (localViewId?.let { mapOf("localViewId" to JsonPrimitive(it)) } ?: emptyMap()) +
            (attachments?.let { mapOf("evidence" to it) } ?: emptyMap()) + mapOf("id" to JsonPrimitive(UUID.randomUUID().toString()),
            "sourceBox" to (owner?.id?.let(::JsonPrimitive) ?: JsonNull), "createdAt" to JsonPrimitive(Instant.now().toString()),
            "status" to JsonPrimitive("open"), "version" to JsonPrimitive(0)))
        persist(previous + saved)
        val box = owner
        return buildJsonObject { put("ok", true); put("id", saved.getValue("id")); put("message", if (box?.isInsideInitialized() == true && !box.inside.ephemeral) "Note added to Field." else "Note added to Field's live review inbox.") }
    }
}
