@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import afield.Box
import afield.graphics.geometryReviewSurfacesOwnedBy
import afield.graphics.threeViewsOwnedBy
import kotlinx.serialization.json.*

/** A link to the existing discussion, never a second copy of its content or build verdict. */
internal data class StudyBuildFinding(
    val runId: String, val noteId: String, val viewId: String, val sourceBox: String,
    val outputBox: String, val text: String, val kind: String, val status: String,
    val replies: Int, val geometryStale: Boolean?, val live: Boolean,
)

/** Read on the document owner thread. Stopped sources retain discoverable historical findings. */
internal fun studyBuildFindings(boxes: Collection<Box>, runIds: Set<String>): List<StudyBuildFinding> {
    if (runIds.isEmpty()) return emptyList()
    val result = linkedMapOf<Pair<String, String>, StudyBuildFinding>()
    fun collect(source: Box, output: Box, snapshot: JsonObject, live: Boolean) {
        for (entry in (snapshot["notes"] as? JsonArray).orEmpty()) {
            val note = entry as? JsonObject ?: continue
            val finding = note["finding"] as? JsonObject ?: continue
            fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
            val run = finding.text("runId"); if (run !in runIds) continue
            val id = note.text("id"); if (id.isEmpty()) continue
            val kind = finding.text("kind"); if (kind !in setOf("error", "warning", "question")) continue
            result.putIfAbsent(source.id to id, StudyBuildFinding(run, id, note.text("viewId"), source.id,
                output.id, note.text("text").take(240), kind, note.text("status").ifEmpty { "open" },
                (note["replies"] as? JsonArray)?.size ?: 0,
                if (live) note["stale"]?.jsonPrimitive?.booleanOrNull else null, live))
        }
    }
    threeViewsOwnedBy(boxes).forEach { view -> view.reviewInbox.owner?.let { collect(it, it, view.reviews(true), true) } }
    geometryReviewSurfacesOwnedBy(boxes).forEach { collect(it.owner, it.outputBox, it.reviews(true), true) }
    boxes.distinct().forEach { source ->
        val notes = runCatching { Json.parseToJsonElement(source.getPropertyRaw("geometryReviews").toString()).jsonArray }.getOrNull()
        if (notes != null) collect(source, source, buildJsonObject { put("notes", notes) }, false)
    }
    return result.values.toList()
}
