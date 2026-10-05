package afield.stdlib

import afield.Box
import afield.SessionInformation
import afield.graphics.GeometryFinding
import afield.graphics.bindGeometryReviewSurface
import afield.graphics.threeViewFor
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class StudyBuildFindingsTest {
    private fun source(): Box {
        val root = Box("root")
        val info = SessionInformation(Files.createTempDirectory("build-findings").toString(), documentName = "review", root = root, ephemeral = true)
        root.inside = info
        return Box("source").also { it.inside = info; info.boxes[it.id] = it }
    }
    private fun record(view: String, run: String): JsonObject {
        val selection = buildJsonObject { put("artifactDigest", "r1"); put("kind", "point"); put("point", JsonArray(listOf(1, 2, 3).map(::JsonPrimitive))) }
        return buildJsonObject {
            put("viewId", view); put("artifactDigest", "r1"); put("text", "Inspect the open boundary")
            put("selection", selection); putJsonObject("view") { put("artifactDigest", "r1"); put("selection", selection) }
            put("finding", GeometryFinding("check/boundary", "error", "Mesh", run, "checker", "Export held", "Inspect this edge").json())
        }
    }

    @Test fun linksBelongToTheAttemptAndExactDocumentWithoutDuplicatingStoredNotes() {
        val a = source(); val b = source()
        val va = threeViewFor("test-${UUID.randomUUID()}").also { it.bindSource(a); it.reviewInbox.update("r1") }
        val vb = threeViewFor("test-${UUID.randomUUID()}").also { it.bindSource(b); it.reviewInbox.update("r1") }
        val id = va.reviewInbox.receive(record(va.uid, "job-a"), true)["id"]!!.jsonPrimitive.content
        vb.reviewInbox.receive(record(vb.uid, "job-a"), true)
        va.reviewInbox.receive(record(va.uid, "job-b"), true)
        val found = studyBuildFindings(listOf(a), setOf("job-a"))
        assertEquals(1, found.size)
        assertEquals(id, found.single().noteId); assertEquals(va.uid, found.single().viewId)
        assertEquals(false, found.single().geometryStale); assertTrue(found.single().live)
        assertEquals(emptyList(), studyBuildFindings(listOf(a), setOf("unknown")))
    }

    @Test fun closingDiscussionDoesNotHideEvidenceAndGeometryChangesAreExplicit() {
        val source = source()
        val view = threeViewFor("test-${UUID.randomUUID()}").also { it.bindSource(source); it.reviewInbox.update("r1") }
        val id = view.reviewInbox.receive(record(view.uid, "failed-job"), true)["id"]!!.jsonPrimitive.content
        view.reviewInbox.updateNote(buildJsonObject {
            put("id", id); put("status", "resolved"); put("summary", "Discussion finished; the checker still owns acceptance")
            put("actor", "test"); put("checkedRevision", "r1"); put("expectedVersion", 0)
        })
        assertEquals("resolved", studyBuildFindings(listOf(source), setOf("failed-job")).single().status)
        view.reviewInbox.update("r2")
        assertEquals(true, studyBuildFindings(listOf(source), setOf("failed-job")).single().geometryStale)
    }

    @Test fun historicalFindingSurvivesAnUnlaunchedReviewSourceWithoutAFalseLiveLink() {
        val source = source()
        val note = JsonObject(record("old-view", "old-job") + ("id" to JsonPrimitive("old-note")))
        source.properties["geometryReviews"] = JsonArray(listOf(note)).toString()
        val found = studyBuildFindings(listOf(source), setOf("old-job")).single()
        assertFalse(found.live); assertNull(found.geometryStale); assertEquals("old-note", found.noteId)
    }

    @Test fun customCanvasLinksToItsOutputBoxWhileKeepingNotesOnItsSource() {
        val source = source()
        val output = Box("canvas").also { it.inside = source.inside; source.inside.boxes[it.id] = it }
        bindGeometryReviewSurface(source, "plan", "r1", output) { it() }.use { review ->
            val id = review.receive(record(review.uid, "plan-job"))["id"]!!.jsonPrimitive.content
            val finding = studyBuildFindings(listOf(source, output), setOf("plan-job")).single()
            assertEquals(id, finding.noteId)
            assertEquals(source.id, finding.sourceBox)
            assertEquals(output.id, finding.outputBox)
            assertTrue(finding.live)
        }
        assertFalse(studyBuildFindings(listOf(source, output), setOf("plan-job")).single().live)
    }
}
