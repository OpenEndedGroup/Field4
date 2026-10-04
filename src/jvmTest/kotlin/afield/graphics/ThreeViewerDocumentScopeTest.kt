package afield.graphics

import afield.Box
import afield.SessionInformation
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class ThreeViewerDocumentScopeTest {
    private fun box(path: String = Files.createTempDirectory("three-doc-").toString()): Box {
        val root = Box("root")
        val info = SessionInformation(path, documentName = "same-name", root = root, ephemeral = true)
        root.inside = info
        return Box("copied-box").also { it.inside = info; info.boxes[it.id] = it }
    }

    private fun record(view: ThreeGeometryViewState): JsonObject {
        val revision = view.reviews()["revision"]!!.jsonPrimitive.content
        val feature = buildJsonObject {
            put("artifactDigest", revision); put("partId", "target"); put("kind", "point")
            put("point", JsonArray(listOf(1, 2, 3).map(::JsonPrimitive)))
        }
        return buildJsonObject {
            put("viewId", view.uid); put("artifactDigest", revision); put("text", "Keep this decision")
            put("selection", feature)
            putJsonObject("view") { put("artifactDigest", revision); put("selection", feature) }
        }
    }

    @Test fun copiedBoxIdsCannotReplaceAnotherDocumentsViewerOrReviewOwner() {
        val a = box(); val b = box()
        val first = a.three(name = "targets") { }
        val second = b.three(name = "targets") { }
        assertNotEquals(first.uid, second.uid)
        assertNotSame(first, second)
        assertSame(a, first.reviewInbox.owner); assertSame(b, second.reviewInbox.owner)
        assertEquals(listOf(first), threeViewsOwnedBy(listOf(a)))
        assertEquals(listOf(second), threeViewsOwnedBy(listOf(b)))
        first.reviewInbox.receive(record(first), true)
        assertEquals(1, first.reviews()["openCount"]!!.jsonPrimitive.int)
        assertEquals(0, second.reviews()["openCount"]!!.jsonPrimitive.int)
        assertFailsWith<IllegalArgumentException> { second.reviewInbox.receive(record(first), true) }
    }

    @Test fun lineViewsAndMeshViewsUseTheSameStableDocumentIdentity() {
        val a = box(); val b = box()
        val first = a.threeLines(emptyList(), name = "targets")
        assertSame(first, a.three(name = "targets") { })
        assertNotEquals(first.uid, b.threeLines(emptyList(), name = "targets").uid)
        val reopened = box(a.inside.filename + "/.")
        assertEquals(first.uid, reopened.three(name = "targets") { }.uid)
        assertNotEquals(first.uid, a.three(name = "other") { }.uid)
    }

    @Test fun registeredViewCannotBeReboundAcrossDocuments() {
        val a = box(); val b = box()
        val first = a.three(name = "targets") { }
        assertFailsWith<IllegalArgumentException> { first.bindSource(b) }
        assertSame(a, first.reviewInbox.owner)
    }

    @Test fun cameraLinkScopeIsDocumentOwnedAndDoesNotChangeGeometryIdentity() {
        val a = box(); val b = box()
        val first = a.three(name = "one") { }
        val sibling = a.three(name = "two") { }
        val unrelated = b.three(name = "one") { }
        assertNotNull(first.cameraLinkScope())
        assertEquals(first.cameraLinkScope(), sibling.cameraLinkScope())
        assertNotEquals(first.cameraLinkScope(), unrelated.cameraLinkScope())
        val payload = ThreeGeometryPayload(uid = "fixture")
        assertEquals(threeReviewRevision(payload), threeReviewRevision(payload.copy(cameraLinkScope = "different")))
    }

    @Test fun legacyNotesStayReadableAndCopiesKeepIndependentHistory() {
        val a = box(); val first = a.three(name = "targets") { }
        val legacy = JsonObject(record(first) + mapOf("viewId" to JsonPrimitive("${a.id}_targets"), "id" to JsonPrimitive("old-note")))
        a.properties["geometryReviews"] = JsonArray(listOf(legacy)).toString()
        val old = first.reviews()["notes"]!!.jsonArray.single().jsonObject
        assertEquals(first.uid, old["viewId"]!!.jsonPrimitive.content)
        assertEquals("${a.id}_targets", old["recordedViewId"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(listOf(legacy)).toString(), a.properties["geometryReviews"])
        first.reviewInbox.receive(record(first), true)
        val retained = a.properties["geometryReviews"]
        val b = box().also { it.properties["geometryReviews"] = retained }
        val second = b.three(name = "targets") { }
        assertEquals(2, second.reviews()["notes"]!!.jsonArray.size)
        val revision = second.reviews()["revision"]!!.jsonPrimitive.content
        second.reviewInbox.updateNote(buildJsonObject {
            put("id", "old-note"); put("reply", "Only in the copy"); put("actor", "test")
            put("checkedRevision", revision); put("expectedVersion", 0)
        })
        assertEquals(retained, a.properties["geometryReviews"])
        assertEquals(1, second.reviews()["notes"]!!.jsonArray.first().jsonObject["replies"]!!.jsonArray.size)
        assertEquals(0, a.three(name = "unrelated") { }.reviews()["openCount"]!!.jsonPrimitive.int)
    }
}
