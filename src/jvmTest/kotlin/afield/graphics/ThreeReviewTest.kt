package afield.graphics

import afield.Box
import kotlinx.serialization.json.*
import kotlin.test.*

class ThreeReviewTest {
    private fun note(revision: String, text: String = "Thin feature") = buildJsonObject {
        put("viewId", "view"); put("artifactDigest", revision); put("text", text)
        val feature = buildJsonObject {
            put("artifactDigest", revision); put("partId", "panel"); put("kind", "vertex")
            put("point", JsonArray(listOf(1,2,3).map(::JsonPrimitive))); put("vertex", 0)
        }
        put("selection", feature)
        putJsonObject("view") { put("artifactDigest", revision); put("selection", feature) }
    }
    @Test fun selectionAndNotesReachFieldAndStaleNotesCannotReplaceCurrentSelection() {
        val box = Box("source")
        val inbox = ThreeReviewInbox("view").also { it.owner=box; it.update("first") }
        inbox.receive(note("first"), false)
        assertEquals(note("first"), inbox.snapshot()["selection"])
        inbox.receive(note("first"), true)
        assertEquals(1, inbox.snapshot().getValue("notes").jsonArray.size)
        assertNotNull(box.getPropertyRaw("geometryReviews"))
        inbox.update("second")
        assertEquals(JsonNull, inbox.snapshot()["selection"])
        val before = inbox.snapshot()
        assertFailsWith<IllegalArgumentException> { inbox.receive(note("first"), true) }
        assertEquals(before, inbox.snapshot())
        val reopened = ThreeReviewInbox("view").also { it.owner=box; it.update("second") }
        assertEquals(1, reopened.snapshot().getValue("notes").jsonArray.size)
        assertEquals("source", reopened.snapshot().getValue("sourceBox").jsonPrimitive.content)
    }
    @Test fun ordinaryGeometryRevisionChangesWithVerticesButNotCameraOrCallbacks() {
        val p = ThreeGeometryPayload(uid="view", points=ThreeGeometryData(mapOf("position" to ThreeAttribute(3,floatArrayOf(1f,2f,3f)))))
        assertEquals(threeReviewRevision(p), threeReviewRevision(p.copy(autoFrame=false,frameHeight=800)))
        assertNotEquals(threeReviewRevision(p), threeReviewRevision(p.copy(points=ThreeGeometryData(mapOf("position" to ThreeAttribute(3,floatArrayOf(1f,2f,4f)))))))
    }
    @Test fun malformedReferenceDoesNotMutateInbox() {
        val inbox=ThreeReviewInbox("view").also { it.update("r") }
        val valid=note("r")
        val forged=JsonObject(valid + ("view" to buildJsonObject { put("artifactDigest","other") }))
        assertFailsWith<IllegalArgumentException> { inbox.receive(forged,true) }
        assertEquals(0,inbox.snapshot().getValue("notes").jsonArray.size)
    }
    @Test fun screenshotsAreSidecarsAndRemainReadableAfterReopening() {
        val directory=java.nio.file.Files.createTempDirectory("review-evidence-test")
        try {
            val output=java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(12,8,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",output)
            val valid=note("r")
            val evidence=buildJsonObject {
                put("schemaVersion",1);put("artifactDigest","r");put("view",valid.getValue("view"))
                put("viewport",JsonArray(listOf(120,80).map(::JsonPrimitive)))
                put("cropBounds",JsonArray(listOf(0.0,0.0,1.0,1.0).map(::JsonPrimitive)))
                for (kind in listOf("context","crop")) putJsonObject(kind) {
                    put("width",12);put("height",8)
                    put("dataUrl","data:image/png;base64,"+java.util.Base64.getEncoder().encodeToString(output.toByteArray()))
                }
            }
            val saved=ThreeReviewEvidence.store(evidence,valid,directory)
            assertFalse(saved.toString().contains("base64"))
            val hash=saved.getValue("crop").jsonObject.getValue("sha256").jsonPrimitive.content
            val notes=listOf(JsonObject(valid + ("evidence" to saved)))
            assertContentEquals(output.toByteArray(),ThreeReviewEvidence.read(hash,notes,directory))
            assertNull(ThreeReviewEvidence.read(hash,emptyList(),directory))
            assertNull(ThreeReviewEvidence.read("../secret",notes,directory))
            assertFailsWith<IllegalArgumentException> { ThreeReviewEvidence.store(JsonObject(evidence + ("artifactDigest" to JsonPrimitive("stale"))),valid,directory) }
            val wrong=JsonObject(evidence.getValue("crop").jsonObject + ("width" to JsonPrimitive(12000)))
            assertFailsWith<IllegalArgumentException> { ThreeReviewEvidence.store(JsonObject(evidence + ("crop" to wrong)),valid,directory) }
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun explicitResolutionSurvivesReopeningAndRejectsCompetingOrStaleUpdates() {
        val box = Box("source")
        val inbox = ThreeReviewInbox("view").also { it.owner=box; it.update("old") }
        val id = inbox.receive(note("old"), true).getValue("id").jsonPrimitive.content
        // Reading is not acknowledgement; a rebuild cannot silently resolve an old note.
        inbox.snapshot(); inbox.update("current")
        assertEquals(1, inbox.snapshot().getValue("openCount").jsonPrimitive.int)
        fun change(version: Int, status: String = "resolved", revision: String = "current", noteId: String = id) = buildJsonObject {
            put("id", noteId); put("status", status); put("expectedVersion", version)
            put("checkedRevision", revision); put("summary", "Transferred the thin tip; material and assembly checks pass")
            put("actor", "test-agent")
        }
        assertFailsWith<IllegalStateException> { inbox.updateNote(change(0, revision="old")) }
        assertFailsWith<IllegalArgumentException> { inbox.updateNote(change(0, noteId="missing")) }
        val original = inbox.snapshot().getValue("notes").jsonArray.single().jsonObject
        inbox.updateNote(change(0))
        assertEquals(0, inbox.snapshot().getValue("notes").jsonArray.size)
        assertEquals(1, inbox.snapshot().getValue("resolvedCount").jsonPrimitive.int)
        assertFailsWith<IllegalStateException> { inbox.updateNote(change(0, "open")) }
        val reopened = ThreeReviewInbox("view").also { it.owner=box; it.update("current") }
        val resolved = reopened.snapshot(true).getValue("notes").jsonArray.single().jsonObject
        assertEquals(original["text"], resolved["text"])
        assertEquals(original["selection"], resolved["selection"])
        assertEquals("old", resolved.getValue("artifactDigest").jsonPrimitive.content)
        assertEquals("current", resolved.getValue("resolution").jsonObject.getValue("checkedRevision").jsonPrimitive.content)
        reopened.updateNote(change(1, "open"))
        assertEquals(2, reopened.snapshot().getValue("notes").jsonArray.single().jsonObject.getValue("history").jsonArray.size)
        val other = ThreeReviewInbox("other-view").also { it.owner=box; it.update("current") }
        assertFailsWith<IllegalArgumentException> { other.updateNote(change(2)) }
    }

    @Test fun legacyNotesAreOpenAndBrowserCannotForgeTheirCompletion() {
        val box = Box("source")
        box.properties["geometryReviews"] = JsonArray(listOf(JsonObject(note("r") + ("id" to JsonPrimitive("legacy"))))).toString()
        val inbox = ThreeReviewInbox("view").also { it.owner=box; it.update("r") }
        val legacy = inbox.snapshot().getValue("notes").jsonArray.single().jsonObject
        assertEquals("open", legacy.getValue("status").jsonPrimitive.content)
        assertEquals(0, legacy.getValue("version").jsonPrimitive.int)
        inbox.receive(JsonObject(note("r") + mapOf("status" to JsonPrimitive("resolved"), "version" to JsonPrimitive(100))), true)
        assertEquals(2, inbox.snapshot().getValue("openCount").jsonPrimitive.int)
        assertEquals(0, inbox.snapshot().getValue("notes").jsonArray.last().jsonObject.getValue("version").jsonPrimitive.int)
    }

    @Test fun repliesAccumulateAcrossAgentsAndReloadWithoutReopeningResolvedNotes() {
        val box = Box("source")
        val inbox = ThreeReviewInbox("view").also { it.owner=box; it.update("r") }
        val id = inbox.receive(note("r"), true).getValue("id")
        fun reply(version: Int, actor: String) = buildJsonObject {
            put("id", id); put("reply", "Finding from $actor: keep the original boundary fixture.")
            put("actor", actor); put("checkedRevision", "r"); put("expectedVersion", version)
        }
        inbox.updateNote(reply(0,"first-agent"))
        assertFailsWith<IllegalStateException> { inbox.updateNote(reply(0,"second-agent")) }
        val reopened = ThreeReviewInbox("view").also { it.owner=box; it.update("r") }
        reopened.updateNote(reply(1,"second-agent"))
        reopened.updateNote(buildJsonObject {
            put("id",id);put("status","resolved");put("summary","Verified the repair.")
            put("actor","first-agent");put("checkedRevision","r");put("expectedVersion",2)
        })
        reopened.updateNote(reply(3,"archivist"))
        assertEquals(0,reopened.snapshot().getValue("openCount").jsonPrimitive.int)
        val saved = reopened.snapshot(true).getValue("notes").jsonArray.single().jsonObject
        assertEquals("Thin feature",saved.getValue("text").jsonPrimitive.content)
        assertEquals(3,saved.getValue("replies").jsonArray.size)
        assertEquals(4,saved.getValue("version").jsonPrimitive.int)
        assertEquals("first-agent",saved.getValue("replies").jsonArray.first().jsonObject.getValue("actor").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { reopened.updateNote(JsonObject(reply(4,"bad") + ("status" to JsonPrimitive("resolved")))) }
    }

}
