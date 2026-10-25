package afield.ai.agent

import afield.document.graph.Box
import afield.app.server.SessionInformation
import afield.ai.completion.*
import afield.ai.conversation.*
import afield.ai.diagnostics.*
import afield.ai.models.*
import afield.ai.tools.*
import afield.runtime.languages.*
import afield.ui.visualization.GeometryFinding
import afield.graphics.three.ThreeGeometryViewState
import afield.graphics.three.threeViewFor
import afield.study.build.BoxStudyBuildBinding
import afield.study.build.StudyBuildFailure
import afield.study.build.StudyBuildProblem
import afield.study.build.StudyBuildStage
import afield.study.build.StudyBuilds
import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.*

class GeometryReviewToolsTest {
    private val host = object : FieldGraphHost by LiveFieldGraphHost {
        override fun <T> onOwner(info: SessionInformation, action: () -> T): T = action()
    }

    private data class Fixture(val info: SessionInformation, val view: ThreeGeometryViewState, val ctx: ToolExecutionContext)
    private fun fixture(readOnly: Boolean = false): Fixture {
        val root = Box("root-${UUID.randomUUID()}")
        val info = SessionInformation(Files.createTempDirectory("geometry-tools-").toString(), documentName = "review-test", root = root, ephemeral = true, readOnly = readOnly)
        root.inside = info
        val box = Box("source").also { it.name = "Shapes"; it.inside = info }
        info.boxes[box.id] = box
        val view = threeViewFor("view-${UUID.randomUUID()}").also { it.bindSource(box); it.reviewInbox.update("r1") }
        return Fixture(info, view, ToolExecutionContext(fieldGraph = FieldGraphTools(info, host)))
    }

    private val registry = ToolRegistry().also(::registerBuiltInAgentTools)
    private fun call(f: Fixture, request: Any, registry: ToolRegistry = this.registry): GeometryToolResult =
        registry.executeByName(f.ctx, request.javaClass.simpleName, aiToolMapper.writeValueAsString(request)) as GeometryToolResult

    private fun note(view: ThreeGeometryViewState, images: Boolean = false, text: String = "Please inspect this corner"): JsonObject {
        val feature = buildJsonObject {
            put("artifactDigest", "r1"); put("partId", "shape"); put("kind", "vertex")
            put("point", JsonArray(listOf(1, 2, 3).map(::JsonPrimitive))); put("vertex", 0)
        }
        val state = buildJsonObject { put("artifactDigest", "r1"); put("selection", feature) }
        return buildJsonObject {
            put("viewId", view.uid); put("artifactDigest", "r1"); put("text", text); put("selection", feature); put("view", state)
            if (images) putJsonObject("evidence") {
                val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
                put("schemaVersion", 1); put("artifactDigest", "r1"); put("view", state)
                put("viewport", JsonArray(listOf(120, 80).map(::JsonPrimitive)))
                put("cropBounds", JsonArray(listOf(0.0, 0.0, 1.0, 1.0).map(::JsonPrimitive)))
                for (kind in listOf("context", "crop")) putJsonObject(kind) {
                    put("width", 12); put("height", 8)
                    put("dataUrl", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes))
                }
            }
        }
    }

    private fun save(f: Fixture, images: Boolean = false, text: String = "Please inspect this corner"): String =
        f.view.reviewInbox.receive(note(f.view, images, text), true).getValue("id").jsonPrimitive.content

    @Test fun customCanvasesShareNotesImagesConcurrencyAndLifetimeWithoutPretendingToBeThreeViews() {
        val f = fixture()
        val owner = f.view.reviewInbox.owner!!
        fun bind() = afield.ui.visualization.bindGeometryReviewSurface(owner, "plan", "r1") { it() }
        val surface = bind()
        val record = JsonObject(note(f.view, images = true) + ("viewId" to JsonPrimitive(surface.uid)))
        val id = surface.receive(record).getValue("id").jsonPrimitive.content
        assertEquals(2, call(f, ReadGeometryNoteRequest(surface.uid, id)).imageCount)
        val listed = aiToolMapper.writeValueAsString(call(f, ListGeometryViewsRequest()))
        assertTrue(listed.contains("canvas")); assertTrue(listed.contains("ViewBoxOutputRequest"))
        val capture = call(f, InspectGeometryViewRequest(surface.uid, "r1"))
        assertFalse(capture.success); assertTrue(capture.message.contains("custom canvas"))
        val other = fixture()
        assertFalse(call(other, ReadGeometryNoteRequest(surface.uid, id)).success)
        assertTrue(call(f, ReplyGeometryNoteRequest(surface.uid, id, "Retain this decision", "test", "r1", 0)).success)
        assertFalse(call(f, ReviewGeometryNoteRequest(surface.uid, id, "resolved", "checked", "test", "r1", 0)).success)
        surface.updateRevision("r2")
        assertFailsWith<IllegalArgumentException> { surface.receive(record) }
        assertEquals(2, call(f, ReadGeometryNoteRequest(surface.uid, id)).imageCount)
        surface.close()
        assertFalse(call(f, ReadGeometryReviewsRequest(surface.uid)).success)
        assertFailsWith<IllegalStateException> { surface.reviews() }
        val restored = bind()
        assertEquals(surface.uid, restored.uid)
        assertEquals(1, restored.reviews()["openCount"]!!.jsonPrimitive.int)
        assertEquals(2, call(f, ReadGeometryNoteRequest(restored.uid, id)).imageCount)
        f.info.boxes.remove(owner.id)
        assertFalse(call(f, ReadGeometryReviewsRequest(restored.uid)).success)
        assertFailsWith<IllegalStateException> { restored.receive(record) }
        restored.close()
    }

    @Test fun presentationToolsRespectDocumentScopeAndReadOnlyState() {
        val f = fixture()
        f.view.update(afield.graphics.three.ThreeGeometryPayload(uid = f.view.uid))
        val revision = f.view.reviews()["revision"]!!.jsonPrimitive.content
        assertTrue(call(f, StyleGeometryPartsRequest(f.view.uid, revision, emptyList(), replace = true)).success)
        assertFalse(call(fixture(), StyleGeometryPartsRequest(f.view.uid, revision, emptyList())).success)
        val readOnly = fixture(readOnly = true)
        val denied = StyleGeometryPartsRequest(readOnly.view.uid, "r1", emptyList())
        assertContains(assertFailsWith<IllegalStateException> { call(readOnly, denied) }.message.orEmpty(), "read-only")
        assertFalse(readOnly.ctx.fieldGraph!!.geometryReviews.style(denied).success)
        assertFalse(call(f, StyleGeometryPartsRequest(f.view.uid, "old", emptyList())).success)
    }

    @Test fun appearanceToolHasAnSdkAcceptedSchemaAndExecutesNonemptyArguments() {
        // Exercise the same OpenAI SDK schema construction as a real conversation.
        // A Map argument passed direct tool calls but prevented every chat request.
        @Suppress("UNCHECKED_CAST")
        val schema = openAiTool(StyleGeometryPartsRequest::class.java as Class<Any>)
        assertEquals("StyleGeometryPartsRequest", schema.name())
        val f = fixture()
        val drawable = afield.graphics.three.ThreeDrawable(id = "a",
            geometry = afield.graphics.three.ThreeGeometryData(attributes = mapOf(
                "position" to afield.graphics.three.ThreeAttribute(3, floatArrayOf(0f,0f,0f,1f,0f,0f,0f,1f,0f)))),
            style = afield.graphics.three.ThreeDrawStyle(kind = "surface"))
        f.view.update(afield.graphics.three.ThreeGeometryPayload(uid = f.view.uid, drawables = listOf(drawable)))
        val revision = f.view.reviews()["revision"]!!.jsonPrimitive.content
        val request = StyleGeometryPartsRequest(f.view.uid, revision,
            listOf(GeometryPartColor("a", listOf(1.0,0.7,0.2))), unlit = true, xrayOpacity = 1.0)
        assertTrue(call(f, request).success)
        assertEquals(revision, f.view.reviews()["revision"]!!.jsonPrimitive.content)
        assertTrue(f.view.reviewInbox.owner!!.getPropertyRaw("geometryAppearance").toString().contains("xrayOpacity"))
        assertFalse(call(f, request.copy(parts = request.parts + request.parts)).success)
    }

    @Test fun sharedRegistriesExposeTheToolsWithRequiredConcurrencyArguments() {
        val f = fixture()
        val names = setOf("ListGeometryViewsRequest", "ReadGeometryReviewsRequest", "ReadGeometryNoteRequest", "ReplyGeometryNoteRequest", "ReviewGeometryNoteRequest", "InspectGeometryViewRequest", "ReportGeometryFindingRequest", "StyleGeometryPartsRequest")
        val context = f.ctx.copy(promoted = mutableSetOf())
        fun schemas(r: ToolRegistry) = r.describe(context).filter { it.jsonObject["name"]!!.jsonPrimitive.content in names }
        assertEquals(listOf("ListGeometryViewsRequest"), schemas(registry).map { it.jsonObject["name"]!!.jsonPrimitive.content })
        registry.executeByName(context, "DescribeToolsRequest", """{"namespace":"geometry"}""")
        assertEquals(names.size, schemas(registry).size)
        assertEquals(schemas(registry), schemas(Ai4BackdoorTools.registry))
        assertTrue(names.none { registry.isAvailable(ToolExecutionContext(), it) })
        for (name in listOf("ReplyGeometryNoteRequest", "ReviewGeometryNoteRequest")) {
            val schema = schemas(registry).first { it.jsonObject["name"]!!.jsonPrimitive.content == name }.jsonObject
            val required = schema["inputSchema"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue(required.containsAll(listOf("viewId", "noteId", "actor", "checkedRevision", "expectedVersion")))
        }
    }

    @Test fun guessedViewIdsCannotReadOrWriteAnotherDocumentsNotesEvenWithSameBoxId() {
        val a = fixture(); val b = fixture()
        val id = save(b, images = true)
        val listed = call(a, ListGeometryViewsRequest())
        assertTrue(listed.success)
        assertTrue(aiToolMapper.writeValueAsString(listed).contains(a.view.uid))
        assertFalse(aiToolMapper.writeValueAsString(listed).contains(b.view.uid))
        assertFalse(call(a, ReadGeometryReviewsRequest(b.view.uid)).success)
        assertFalse(call(a, ReadGeometryNoteRequest(b.view.uid, id)).success)
        assertFalse(call(a, ReplyGeometryNoteRequest(b.view.uid, id, "wrong document", "test", "r1", 0)).success)
        assertFalse(call(a, ReviewGeometryNoteRequest(b.view.uid, id, "resolved", "wrong", "test", "r1", 0)).success)
        assertEquals(1, b.view.reviews()["openCount"]!!.jsonPrimitive.int)
    }

    @Test fun findingToolRefusesMissingSelectionForeignViewsAndReadOnlyDocumentsBeforeCapture() {
        val a=fixture(); val b=fixture()
        fun request(viewId: String = a.view.uid, state: String = "") = ReportGeometryFindingRequest(
            viewId,"r1",state,"check/shape","warning","Cut","attempt","test",
            "Inspect this feature","A suspected defect needs examination","Is this intended?")
        assertFalse(call(a,request()).success)
        val state=buildJsonObject {
            put("artifactDigest","r1");put("partIds",JsonArray(listOf(JsonPrimitive("shape"))))
            put("camera",JsonArray(listOf(1,2,3).map(::JsonPrimitive)));put("target",JsonArray(listOf(0,0,0).map(::JsonPrimitive)))
            put("selection",note(a.view)["selection"]!!)
        }.toString()
        assertFalse(call(a,request(b.view.uid,state)).success)
        val readOnly=fixture(readOnly=true)
        val denied = request(readOnly.view.uid,state)
        assertContains(assertFailsWith<IllegalStateException> { call(readOnly, denied) }.message.orEmpty(), "read-only")
        assertFalse(readOnly.ctx.fieldGraph!!.geometryReviews.report(denied).success)
        assertEquals(0,readOnly.view.reviews()["openCount"]!!.jsonPrimitive.int)
        assertEquals(0,a.view.reviews()["openCount"]!!.jsonPrimitive.int)
        assertEquals(0,b.view.reviews()["openCount"]!!.jsonPrimitive.int)
    }

    @Test fun notesCarryActualHistoricalImagesWithoutAcknowledgingOrReplacingManualSelection() {
        val f = fixture()
        val id = save(f, images = true)
        f.view.reviewInbox.receive(note(f.view), false)
        val before = f.view.reviews()
        val result = call(f, ReadGeometryNoteRequest(f.view.uid, id))
        assertTrue(result.success)
        assertEquals(2, result.imageCount)
        assertTrue(result.toolImages.all { it.dataUrl.startsWith("data:image/png;base64,") })
        assertFalse(aiToolMapper.writeValueAsString(result).contains("base64"))
        assertEquals(before, f.view.reviews())
        f.view.reviewInbox.update("r2")
        val historical = call(f, ReadGeometryNoteRequest(f.view.uid, id))
        assertEquals("r2", historical.details["currentRevision"])
        assertTrue(historical.toolImages.all { "annotated geometry r1" in it.caption && "current geometry is r2" in it.caption })
        assertEquals(1, f.view.reviews()["openCount"]!!.jsonPrimitive.int)
    }

    @Test fun repliesAndResolutionPreserveHistoryAndRefuseStaleRevisionsAndCompetingWriters() {
        val f = fixture(); val id = save(f)
        val stale = call(f, ReplyGeometryNoteRequest(f.view.uid, id, "old", "a", "older", 0))
        assertFalse(stale.success)
        assertTrue(stale.message.contains("Geometry changed"))
        assertTrue(call(f, ReplyGeometryNoteRequest(f.view.uid, id, "Checked the local feature", "a", "r1", 0)).success)
        assertFalse(call(f, ReplyGeometryNoteRequest(f.view.uid, id, "lost update", "b", "r1", 0)).success)
        assertEquals(1, f.view.reviews()["openCount"]!!.jsonPrimitive.int)
        assertTrue(call(f, ReviewGeometryNoteRequest(f.view.uid, id, "resolved", "Verified the change", "a", "r1", 1)).success)
        assertEquals(0, f.view.reviews()["openCount"]!!.jsonPrimitive.int)
        assertEquals(1, f.view.reviews()["resolvedCount"]!!.jsonPrimitive.int)
        assertTrue(call(f, ReplyGeometryNoteRequest(f.view.uid, id, "A durable follow-up", "b", "r1", 2)).success)
        assertEquals(0, f.view.reviews()["openCount"]!!.jsonPrimitive.int)
        val read = call(f, ReadGeometryNoteRequest(f.view.uid, id, includeImages = false))
        val note = read.details["note"] as Map<*, *>
        assertEquals(2, (note["replies"] as List<*>).size)
        assertEquals("Please inspect this corner", note["text"])
        assertTrue(call(f, ReviewGeometryNoteRequest(f.view.uid, id, "open", "Needs a second check", "a", "r1", 3)).success)
        assertEquals(1, f.view.reviews()["openCount"]!!.jsonPrimitive.int)
    }

    @Test fun readOnlyDocumentsAndDeletedSourceBoxesCannotBeMutated() {
        val f = fixture(readOnly = true); val id = "existing-note"
        f.view.reviewInbox.owner!!.properties["geometryReviews"] = JsonArray(listOf(
            JsonObject(note(f.view) + mapOf("id" to JsonPrimitive(id), "status" to JsonPrimitive("open"), "version" to JsonPrimitive(0)))
        )).toString()
        assertTrue(call(f, ReadGeometryNoteRequest(f.view.uid, id)).success)
        val denied = ReplyGeometryNoteRequest(f.view.uid, id, "no", "a", "r1", 0)
        assertContains(assertFailsWith<IllegalStateException> { call(f, denied) }.message.orEmpty(), "read-only")
        assertFalse(f.ctx.fieldGraph!!.geometryReviews.reply(denied).success)
        f.info.boxes.remove("source")
        assertFalse(call(f, ReadGeometryNoteRequest(f.view.uid, id)).success)
        val resolve = ReviewGeometryNoteRequest(f.view.uid, id, "resolved", "gone", "a", "r1", 0)
        assertContains(assertFailsWith<IllegalStateException> { call(f, resolve) }.message.orEmpty(), "read-only")
        assertFalse(f.ctx.fieldGraph.geometryReviews.review(resolve).success)
    }

    @Test fun inboxIsPagedAndLongSummariesDoNotFillTheContext() {
        val f = fixture()
        repeat(3) { save(f, text = "x".repeat(1600)) }
        val page = call(f, ReadGeometryReviewsRequest(f.view.uid, limit = 2))
        assertEquals(3, page.details["total"])
        assertEquals(2, page.details["nextOffset"])
        val notes = page.details["notes"] as List<*>
        assertEquals(2, notes.size)
        assertEquals(1200, ((notes[0] as Map<*, *>)["text"] as String).length)
        assertEquals(true, (notes[0] as Map<*, *>)["textTruncated"])
        val last = call(f, ReadGeometryReviewsRequest(f.view.uid, offset = 2, limit = 2))
        assertNull(last.details["nextOffset"])
        assertFalse(call(f, ReadGeometryReviewsRequest(f.view.uid, limit = 0)).success)
        assertFalse(call(f, ReadGeometryReviewsRequest(f.view.uid, offset = -1)).success)
    }

    @Test fun invalidAndHistoricalCaptureRequestsAreRefusedBeforeOpeningAnyBrowser() {
        val f = fixture(); val id = save(f)
        assertFalse(call(f, InspectGeometryViewRequest("unknown", "r1")).success)
        assertFalse(call(f, InspectGeometryViewRequest(f.view.uid, "stale")).success)
        assertFalse(call(f, InspectGeometryViewRequest(f.view.uid, "r1", explode = -1.0)).success)
        assertFalse(call(f, InspectGeometryViewRequest(f.view.uid, "r1", pick = GeometryPick(2.0, 0.5))).success)
        assertFalse(call(f, InspectGeometryViewRequest(f.view.uid, "r1", sectionNormal = listOf(0.0, 0.0, 0.0))).success)
        assertFalse(call(f, InspectGeometryViewRequest(f.view.uid, "r1", camera = listOf(Double.NaN, 1.0, 2.0))).success)
        val partial = call(f, InspectGeometryViewRequest(f.view.uid, "r1", stateJson = """{"artifactDigest":"r1","camera":[1,2,3]}"""))
        assertFalse(partial.success)
        assertTrue(partial.message.contains("complete state"))
        f.view.reviewInbox.update("r2")
        val old = call(f, InspectGeometryViewRequest(f.view.uid, "r2", noteId = id))
        assertFalse(old.success)
        assertTrue(old.message.contains("older geometry"))
    }

    @Test fun compactInspectionKeepsRestoreStateAndIdentitiesWithoutDuplicatingProvenance() {
        val state = mapOf("artifactDigest" to "r1", "partIds" to listOf("panel"), "camera" to listOf(1, 2, 3))
        val leaf = mapOf("key" to "panel", "label" to "Panel", "ids" to listOf("panel"), "children" to emptyList<Any>(), "leaves" to listOf("redundant"))
        val captured = mapOf("state" to state, "parts" to mapOf("key" to "all", "ids" to listOf("panel"), "children" to listOf(leaf), "leaves" to List(100) { leaf }),
            "sources" to listOf(mapOf("id" to "panel", "label" to "Panel", "precision" to "canonical-double", "source" to "x".repeat(10000))))
        val compact = compactInspectionDetails(captured)
        assertEquals(state, compact["state"])
        val wire = aiToolMapper.writeValueAsString(compact)
        assertTrue(wire.length < 1000)
        assertTrue(wire.contains("canonical-double"))
        assertTrue(wire.contains("panel"))
        assertFalse(wire.contains("leaves"))
        assertFalse(wire.contains("xxxx"))
    }

    @Test fun blindContextsCannotObtainReviewPicturesOrOpenAViewer() {
        val f = fixture(); val id = save(f, images = true)
        val blind = f.copy(ctx = ToolExecutionContext(fieldGraph = FieldGraphTools(f.info, host, seesOutput = false)))
        assertFalse(registry.isAvailable(blind.ctx, "InspectGeometryViewRequest"))
        assertEquals(0, call(blind, ReadGeometryNoteRequest(f.view.uid, id)).imageCount)
    }

    @Test fun findingsOfABuildAttemptWithEarlierInputsAreMarkedWhenRead() {
        val f = fixture()
        val source = f.view.reviewInbox.owner!!
        var plan = "stair at 3"
        StudyBuilds(Files.createTempDirectory("review-builds").toFile(), listOf(StudyBuildStage("layout", "House layout", "v1", setOf("plan")) {
            throw StudyBuildFailure(StudyBuildProblem("overlap", "Two walls overlap"))
        }), { mapOf("plan" to plan) }).use { builds ->
            source.properties["_studyBuilds:study"] = BoxStudyBuildBinding(source, "study", builds)
            val job = builds.start("layout", builds.stages().single().fingerprint!!)
            assertTrue(f.ctx.fieldGraph!!.studyBuilds.wait(WaitStudyBuildRequest(source.id, "study", job.id, 5.0)).details["terminal"] == true)
            fun saveFinding(runId: String?, text: String): String {
                val finding = runId?.let { GeometryFinding("wall-stair", "error", "layout", it, "test", "The stair is blocked", "Move it?").json() }
                val record = JsonObject(note(f.view, text = text) + listOfNotNull(finding?.let { "finding" to it }))
                return f.view.reviewInbox.receive(record, true).getValue("id").jsonPrimitive.content
            }
            val fromBuild = saveFinding(job.id, "W3 crosses the stair")
            val elsewhere = saveFinding("diagnostic-7", "From a check that is not a build attempt")
            val plain = saveFinding(null, "Please inspect this corner")
            fun read() = call(f, ReadGeometryReviewsRequest(f.view.uid))
            fun marks() = (read().details["notes"] as List<*>).associate { (it as Map<*, *>)["id"] to it["fromEarlierInputs"] }
            fun noteMark(id: String) = call(f, ReadGeometryNoteRequest(f.view.uid, id, includeImages = false)).details["fromEarlierInputs"]

            assertEquals<Map<*, *>>(mapOf(fromBuild to false, elsewhere to null, plain to null), marks())
            assertFalse(read().message.contains("earlier inputs"))
            assertEquals(false, noteMark(fromBuild))

            plan = "stair at 4"
            // The build card's predicate: the agent and the person get the same verdict.
            assertTrue(afield.study.build.studyBuildLatestUsedEarlierInputs(builds.stages().single()))
            assertEquals<Map<*, *>>(mapOf(fromBuild to true, elsewhere to null, plain to null), marks())
            assertContains(read().message, "1 from a build attempt with earlier inputs")
            // The discussion is still open; only its bearing on the current design changed.
            assertEquals("open", (read().details["notes"] as List<*>).map { it as Map<*, *> }.single { it["id"] == fromBuild }["status"])
            assertEquals(true, noteMark(fromBuild))
            assertNull(noteMark(plain))
        }
    }
}
