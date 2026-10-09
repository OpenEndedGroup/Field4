package afield.ai.agent

import afield.Box
import afield.SessionInformation
import afield.ai.core.*
import afield.stdlib.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

class StudyBuildToolsTest {
    private val host = object : FieldGraphHost by LiveFieldGraphHost {
        override fun <T> onOwner(info: SessionInformation, action: () -> T): T = action()
    }
    private val registry = ToolRegistry().also(::registerBuiltInAgentTools)

    @Test fun buildAndReviewToolsDeclareEffectsAndLoadByNamespace() {
        val info = session()
        val ctx = ToolExecutionContext(fieldGraph = FieldGraphTools(info, host, lessons = false), promoted = mutableSetOf())
        val all = registry.coreMetas()
        val reads = listOf("ListStudyBuildsRequest", "InspectStudyBuildsRequest", "WaitStudyBuildRequest",
            "ListGeometryViewsRequest", "ReadGeometryReviewsRequest", "ReadGeometryNoteRequest", "InspectGeometryViewRequest")
        reads.forEach { assertTrue(all.getValue(it).readOnly, it) }
        val writes = listOf("StartStudyBuildRequest", "CancelStudyBuildRequest", "PruneStudyBuildsRequest", "StyleGeometryPartsRequest",
            "ReplyGeometryNoteRequest", "ReviewGeometryNoteRequest", "ReportGeometryFindingRequest")
        writes.forEach { assertTrue(all.getValue(it).mutates, it) }
        assertTrue(ToolEffect.EXECUTE_CODE in all.getValue("StartStudyBuildRequest").effects)
        fun names() = registry.describe(ctx).map { it.toString() }.joinToString()
        assertFalse(names().contains("StartStudyBuildRequest"))
        for (namespace in listOf("builds", "geometry")) {
            val result = registry.executeByName(ctx, "DescribeToolsRequest", "{\"namespace\":\"$namespace\"}") as DescribeToolsResult
            assertTrue(result.success, result.message)
        }
        assertTrue(names().contains("StartStudyBuildRequest"))
        assertTrue(names().contains("InspectGeometryViewRequest"))
    }

    @Test fun missingInputHelpIsVisibleToTheAgentWithoutPermittingABuild() {
        val info = session()
        val box = Box("source").also { info.boxes[it.id] = it }
        val help = StudyBuildInputHelp("Idle", "Ask the agent to prepare and test a candidate.")
        StudyBuilds(Files.createTempDirectory("idle-build-tools").toFile(), listOf(
            StudyBuildStage("replay", "Test candidate", "v1", setOf("candidate"), inputHelp = help) {
                error("Missing-input build must not run")
            }), { emptyMap() }).use { builds ->
            box.properties["_studyBuilds:study"] = BoxStudyBuildBinding(box, "study", builds)
            val result = call(info, InspectStudyBuildsRequest(box.id, "study"))
            assertTrue(result.success)
            val encoded = aiToolMapper.writeValueAsString(result)
            assertContains(encoded, "Ask the agent to prepare and test a candidate.")
            assertContains(encoded, "Missing input: candidate")
            assertFalse(call(info, StartStudyBuildRequest(box.id, "study", "replay", "invented")).success)
            assertTrue(builds.history().isEmpty())
        }
    }
    @Test fun anAgentsRequestIsRecordedForOneAttemptAndAClosedGateExplainsTheWait() {
        val info = session()
        val box = Box("source").also { info.boxes[it.id] = it }
        val budget = java.util.concurrent.atomic.AtomicReference("10")
        val gate = StudyBuildGate("budget allowed", setOf("budget")) { e ->
            if (e.inputs.getValue("budget").toInt() >= 20) null else "The budget is ${e.inputs["budget"]} steps; the search needs at least 20."
        }
        StudyBuilds(Files.createTempDirectory("request-build-tools").toFile(), listOf(
            StudyBuildStage("search", "Search", "v1", setOf("budget allowed")) { c ->
                c.file("result").writeText("resumed from ${c.request["resume"] ?: "nothing"}"); StudyBuildProduct(listOf("result"))
            }), { mapOf("budget" to budget.get()) }, gates = listOf(gate)).use { builds ->
            box.properties["_studyBuilds:study"] = BoxStudyBuildBinding(box, "study", builds)
            val waiting = aiToolMapper.writeValueAsString(call(info, InspectStudyBuildsRequest(box.id, "study")))
            assertContains(waiting, "Waiting for budget allowed")
            assertContains(waiting, "the search needs at least 20.")
            assertFalse(call(info, StartStudyBuildRequest(box.id, "study", "search", "invented")).success)

            budget.set("30")
            val fingerprint = builds.stages().single().fingerprint!!
            val asked = call(info, StartStudyBuildRequest(box.id, "study", "search", fingerprint, waitSeconds = 5.0, request = mapOf("resume" to "job-1")))
            assertTrue(asked.success, asked.message)
            val job = asked.details["job"] as Map<*, *>
            assertEquals(mapOf("resume" to "job-1"), job["request"])
            assertEquals(true, (asked.details["currency"] as Map<*, *>)["isCurrent"])
            assertEquals(false, job["fromEarlierInputs"])
            val plain = call(info, StartStudyBuildRequest(box.id, "study", "search", fingerprint, waitSeconds = 5.0))
            assertEquals(emptyMap<String, Any?>(), (plain.details["job"] as Map<*, *>)["request"])
            assertEquals("resumed from nothing", builds.currentFile("search", "result").readText())
        }
    }

    @Test fun pruningPreviewsBeforeItDeletesAndRefusesReadOnlyDocuments() {
        val info = session()
        val box = Box("source").also { info.boxes[it.id] = it }
        val plan = java.util.concurrent.atomic.AtomicReference("a")
        StudyBuilds(Files.createTempDirectory("prune-build-tools").toFile(), listOf(StudyBuildStage("mesh", "Mesh", "v1", setOf("plan")) { c ->
            c.file("walls").writeText(c.inputs.getValue("plan").repeat(1000)); StudyBuildProduct(listOf("walls"))
        }), { mapOf("plan" to plan.get()) }).use { builds ->
            box.properties["_studyBuilds:study"] = BoxStudyBuildBinding(box, "study", builds)
            val old = builds.await(builds.start("mesh", builds.stages().single().fingerprint!!).id)
            plan.set("b"); builds.await(builds.start("mesh", builds.stages().single().fingerprint!!).id)

            val preview = call(info, PruneStudyBuildsRequest(pins = listOf("not-a-job")))
            assertTrue(preview.success, preview.message)
            assertContains(preview.message, "Would delete 1 attempts")
            assertEquals(listOf("not-a-job"), preview.details["unknownPins"])
            assertEquals(2, builds.history().size)
            val readOnly = session(readOnly = true).also { it.boxes[box.id] = box }
            assertContains(assertFailsWith<IllegalStateException> { call(readOnly, PruneStudyBuildsRequest(apply = true)) }.message.orEmpty(), "read-only")
            // A direct non-agent caller is also refused by the build adapter itself.
            assertFalse(StudyBuildTools(readOnly, { listOf(BoxStudyBuildBinding(box, "study", builds)) }).prune(PruneStudyBuildsRequest(apply = true)).success)
            assertEquals(2, builds.history().size)

            val applied = call(info, PruneStudyBuildsRequest(apply = true))
            assertContains(applied.message, "Deleted 1 attempts")
            val runner = (applied.details["runners"] as List<*>).single() as Map<*, *>
            assertEquals<Map<*, *>>(mapOf("boxId" to box.id, "name" to "study", "kept" to 1, "deleted" to 1), runner.filterKeys { it != "freedBytes" })
            assertTrue((runner["freedBytes"] as Long) >= 1000)
            assertFalse(builds.history().any { it.id == old.id })
        }
    }

    private fun session(readOnly: Boolean = false): SessionInformation {
        val root = Box("root")
        return SessionInformation(Files.createTempDirectory("build-tools-").toString(), "test", root = root, ephemeral = true, readOnly = readOnly)
    }
    private fun call(info: SessionInformation, q: Any) = registry.executeByName(ToolExecutionContext(fieldGraph = FieldGraphTools(info, host)), q.javaClass.simpleName, aiToolMapper.writeValueAsString(q)) as StudyBuildToolResult

    @Test fun nativeToolsAreSharedAndOnlySeeTheirOwnDocument() {
        val a = session(); val b = session()
        val box = Box("same-box-id").also { a.boxes[it.id] = it }
        b.boxes[box.id] = Box(box.id)
        StudyBuilds(Files.createTempDirectory("build-tools-store").toFile(), listOf(StudyBuildStage("first", "First", "v1") { c ->
            c.file("result").writeText("checked"); StudyBuildProduct(listOf("result"))
        }), { emptyMap() }).use { builds ->
            box.properties["_studyBuilds:study"] = BoxStudyBuildBinding(box, "study", builds)
            assertContains(aiToolMapper.writeValueAsString(call(a, ListStudyBuildsRequest())), "same-box-id")
            assertFalse(aiToolMapper.writeValueAsString(call(b, ListStudyBuildsRequest())).contains("same-box-id"))
            assertFalse(call(b, InspectStudyBuildsRequest(box.id, "study")).success)
            assertFalse(call(b, StartStudyBuildRequest(box.id, "study", "first", builds.stages().single().fingerprint!!)).success)
            val q = StartStudyBuildRequest(box.id, "study", "first", builds.stages().single().fingerprint!!)
            assertTrue(call(a, q).success)
            a.boxes.remove(box.id)
            assertFalse(call(a, InspectStudyBuildsRequest(box.id, "study")).success)
        }
        val context = ToolExecutionContext(fieldGraph = FieldGraphTools(a, host))
        for (name in listOf("ListStudyBuildsRequest", "InspectStudyBuildsRequest", "WaitStudyBuildRequest", "StartStudyBuildRequest", "CancelStudyBuildRequest", "PruneStudyBuildsRequest")) {
            assertTrue(registry.isAvailable(context, name))
            assertTrue(Ai4BackdoorTools.registry.isAvailable(context, name))
            assertFalse(registry.isAvailable(ToolExecutionContext(), name))
        }
    }

    @Test fun waitObservesProgressTimeoutAndCompletionWithoutRestarting() {
        val info = session(readOnly = true)
        val box = Box("source").also { info.boxes[it.id] = it }
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        StudyBuilds(Files.createTempDirectory("wait-builds").toFile(), listOf(StudyBuildStage("first", "First", "v1", setOf("large")) { c ->
            c.progress(.25, "Testing"); entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            c.file("result").writeText("checked"); StudyBuildProduct(listOf("result"))
        }), { mapOf("large" to "do not return frozen input".repeat(1000)) }).use { builds ->
            try {
                box.properties["_studyBuilds:study"] = BoxStudyBuildBinding(box, "study", builds)
                val job = builds.start("first", builds.stages().single().fingerprint!!)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val q = WaitStudyBuildRequest(box.id, "study", job.id, waitSeconds = .01)
                val timed = call(info, q)
                assertTrue(timed.success)
                assertEquals(true, timed.details["timedOut"])
                assertEquals(false, timed.details["terminal"])
                assertFalse(aiToolMapper.writeValueAsString(timed).contains("do not return frozen input"))
                val changed = call(info, q.copy(afterUpdated = job.updated))
                assertEquals(false, changed.details["timedOut"])
                assertEquals(false, changed.details["terminal"])
                val other = session().also { it.boxes[box.id] = Box(box.id) }
                assertFalse(call(other, q).success)
                assertFalse(call(info, q.copy(jobId = "missing")).success)
                assertFalse(call(info, q.copy(waitSeconds = 901.0)).success)
                assertFalse(call(info, q.copy(waitSeconds = -1.0)).success)
                val finish = thread { Thread.sleep(50); release.countDown() }
                val done = call(info, q.copy(waitSeconds = 900.0))
                finish.join()
                assertTrue(done.success)
                assertEquals(true, done.details["terminal"])
                assertEquals(false, done.details["timedOut"])
                assertEquals(StudyBuildStatus.SUCCEEDED, (done.details["job"] as Map<*, *>)["status"])
                assertEquals(job.id, builds.history().single().id)
                assertFalse(builds.job(job.id).cancelRequested)
            } finally { release.countDown() }
        }
    }

    @Test fun liveWaitReportsRealProgressAndFailureBeforeReturning() {
        val info = session()
        val box = Box("source").also { info.boxes[it.id] = it }
        val observed = CountDownLatch(1)
        val events = java.util.concurrent.CopyOnWriteArrayList<LlmProgressUpdate>()
        StudyBuilds(Files.createTempDirectory("live-builds").toFile(), listOf(StudyBuildStage("route", "Route services", "v1") { c ->
            c.progress(.3, "Checking bend at inlet")
            check(observed.await(5, TimeUnit.SECONDS))
            throw StudyBuildFailure(StudyBuildProblem("tight-bend", "Inlet bend is too tight", listOf("Move the inlet")))
        }), { emptyMap() }).use { builds ->
            box.properties["_studyBuilds:study"] = BoxStudyBuildBinding(box, "study", builds)
            val job = builds.start("route", builds.stages().single().fingerprint!!)
            val ctx = ToolExecutionContext(fieldGraph = FieldGraphTools(info, host), reportProgress = {
                events += it
                if (it.body.contains("Checking bend")) observed.countDown()
            })
            val q = WaitStudyBuildRequest(box.id, "study", job.id, waitSeconds = 600.0)
            val result = registry.executeByName(ctx, q.javaClass.simpleName, aiToolMapper.writeValueAsString(q)) as StudyBuildToolResult
            assertEquals(true, result.details["terminal"])
            assertTrue(events.any { it.phase == "build" && it.body.contains("Checking bend") })
            assertEquals("build_result", events.last().phase)
            assertContains(events.last().body, "Inlet bend is too tight")
            assertContains(events.last().body, "Move the inlet")
            assertContains(events.last().body, job.id)
            assertEquals(1, builds.history().size)
            // A presentation failure cannot change the recorded result or retry the operation.
            val broken = ctx.copy(reportProgress = { error("UI disconnected") })
            assertTrue((registry.executeByName(broken, q.javaClass.simpleName, aiToolMapper.writeValueAsString(q)) as StudyBuildToolResult).success)
        }
    }

    @Test fun interruptingObservationDoesNotCancelTheJob() {
        val info = session()
        val box = Box("source").also { info.boxes[it.id] = it }
        val release = CountDownLatch(1)
        StudyBuilds(Files.createTempDirectory("interrupt-wait").toFile(), listOf(StudyBuildStage("first", "First", "v1") { c ->
            check(release.await(5, TimeUnit.SECONDS)); c.file("result").writeText("ok"); StudyBuildProduct(listOf("result"))
        }), { emptyMap() }).use { builds ->
            try {
                box.properties["_studyBuilds:study"] = BoxStudyBuildBinding(box, "study", builds)
                val job = builds.start("first", builds.stages().single().fingerprint!!)
                var result: StudyBuildToolResult? = null
                var interrupted = false
                val observer = thread {
                    Thread.currentThread().interrupt()
                    result = call(info, WaitStudyBuildRequest(box.id, "study", job.id, waitSeconds = 900.0))
                    interrupted = Thread.currentThread().isInterrupted
                }
                observer.join(3000)
                assertFalse(observer.isAlive)
                assertEquals(false, result?.success)
                assertTrue(interrupted)
                assertFalse(builds.job(job.id).cancelRequested)
                assertEquals(job.id, builds.history().single().id)
            } finally { release.countDown() }
        }
    }

    @Test fun completedProposalDoesNotClaimItsQualificationChecksPassed() {
        val info = session()
        val box = Box("source").also { info.boxes[it.id] = it }
        val events = java.util.concurrent.CopyOnWriteArrayList<LlmProgressUpdate>()
        StudyBuilds(Files.createTempDirectory("proposal-wait").toFile(), listOf(
            StudyBuildStage("proposal", "Coordinate proposal", "v1", resultNotes = { job ->
                listOf(StudyBuildNotice("${job.facts["contacts"]} contacts remain. Inspect the retained proposal."))
            }) { c ->
                c.file("report").writeText("Unresolved contacts: 2")
                StudyBuildProduct(listOf("report"), facts = mapOf("contacts" to 2))
            }), { emptyMap() }).use { builds ->
            box.properties["_studyBuilds:study"] = BoxStudyBuildBinding(box, "study", builds)
            val job = builds.start("proposal", builds.stages().single().fingerprint!!)
            val ctx = ToolExecutionContext(fieldGraph = FieldGraphTools(info, host), reportProgress = { events += it })
            val q = WaitStudyBuildRequest(box.id, "study", job.id, waitSeconds = 600.0)
            val result = registry.executeByName(ctx, q.javaClass.simpleName, aiToolMapper.writeValueAsString(q)) as StudyBuildToolResult
            assertEquals(true, result.details["terminal"])
            assertEquals("Coordinate proposal · Build complete; needs review", events.last().title)
            assertContains(events.last().body, "2 contacts remain")
            assertContains(aiToolMapper.writeValueAsString(result.details["resultNotes"]), "2 contacts remain")
            assertContains(aiToolMapper.writeValueAsString(call(info, ListStudyBuildsRequest())), "2 contacts remain")
            assertContains(aiToolMapper.writeValueAsString(call(info, InspectStudyBuildsRequest(box.id, "study", job.id))), "2 contacts remain")
            assertFalse(events.any { it.title.contains("Checks passed", ignoreCase = true) })
            assertEquals(2, builds.job(job.id).facts["contacts"])
        }
    }

    @Test fun startAndWaitReturnsCompactEvidenceAndLiveCurrencyInOneCall() {
        val info = session()
        val box = Box("source").also { info.boxes[it.id] = it }
        var source = "SENSITIVE_FROZEN_SOURCE".repeat(5000)
        val events = mutableListOf<LlmProgressUpdate>()
        StudyBuilds(Files.createTempDirectory("compact-build").toFile(), listOf(
            StudyBuildStage("first", "First", "v1", setOf("source")) { c ->
                c.file("report").writeText("checks retained")
                StudyBuildProduct(listOf("report"), facts = mapOf("contacts" to 2))
            }), { mapOf("source" to source) }).use { builds ->
            box.properties["_studyBuilds:study"] = BoxStudyBuildBinding(box, "study", builds)
            val q = StartStudyBuildRequest(box.id, "study", "first", builds.stages().single().fingerprint!!, waitSeconds = 5.0)
            assertFalse(call(info, q.copy(waitSeconds = -1.0)).success)
            assertTrue(builds.history().isEmpty())
            val ctx = ToolExecutionContext(fieldGraph = FieldGraphTools(info, host), reportProgress = { events += it })
            val result = registry.executeByName(ctx, q.javaClass.simpleName, aiToolMapper.writeValueAsString(q)) as StudyBuildToolResult
            assertTrue(result.success)
            assertEquals(true, result.details["terminal"])
            assertEquals(true, (result.details["currency"] as Map<*, *>)["isCurrent"])
            assertEquals(2, (result.details["facts"] as Map<*, *>)["contacts"])
            assertContains(aiToolMapper.writeValueAsString(result.details["artifacts"]), "sha256")
            assertTrue(events.any { it.phase == "build_result" })
            assertEquals(1, builds.history().size)
            val job = builds.history().single()
            assertEquals(source, builds.job(job.id).inputs["source"])
            for (r in listOf(result, call(info, InspectStudyBuildsRequest(box.id, "study", job.id)),
                call(info, CancelStudyBuildRequest(box.id, "study", job.id)))) {
                val json = aiToolMapper.writeValueAsString(r)
                assertFalse(json.contains("SENSITIVE_FROZEN_SOURCE"))
                assertTrue(json.length < 6000, "Compact outcome was ${json.length} chars")
            }
            source = "new human edit"
            val stale = call(info, WaitStudyBuildRequest(box.id, "study", job.id, waitSeconds = 0.0))
            assertEquals(false, (stale.details["currency"] as Map<*, *>)["isCurrent"])
            assertEquals(1, builds.history().size)
        }
    }

    @Test fun readOnlyContextsCanInspectButCannotStartOrCancel() {
        val info = session(readOnly = true)
        val box = Box("source").also { info.boxes[it.id] = it }
        StudyBuilds(Files.createTempDirectory("read-builds").toFile(), listOf(StudyBuildStage("first", "First", "v1") { error("must not run") }), { emptyMap() }).use { builds ->
            box.properties["_studyBuilds:study"] = BoxStudyBuildBinding(box, "study", builds)
            assertTrue(call(info, InspectStudyBuildsRequest(box.id, "study")).success)
            val start = StartStudyBuildRequest(box.id, "study", "first", builds.stages().single().fingerprint!!)
            val cancel = CancelStudyBuildRequest(box.id, "study", "any")
            assertContains(assertFailsWith<IllegalStateException> { call(info, start) }.message.orEmpty(), "read-only")
            assertContains(assertFailsWith<IllegalStateException> { call(info, cancel) }.message.orEmpty(), "read-only")
            // A direct non-agent caller is also protected by the build adapter itself.
            val direct = StudyBuildTools(info, { listOf(BoxStudyBuildBinding(box, "study", builds)) })
            assertFalse(direct.start(start).success)
            assertFalse(direct.cancel(cancel).success)
            assertTrue(builds.history().isEmpty())
        }
    }

    @Test fun findingsOfAnAttemptWithEarlierInputsAreMarkedAndNotOpen() {
        val info = session()
        val box = Box("source").also { info.boxes[it.id] = it }
        var plan: String? = "stair at 3"
        StudyBuilds(Files.createTempDirectory("earlier-findings").toFile(), listOf(StudyBuildStage("layout", "House layout", "v1", setOf("plan")) {
            throw StudyBuildFailure(StudyBuildProblem("overlap", "Two walls overlap"))
        }), { plan?.let { mapOf("plan" to it) }.orEmpty() }).use { builds ->
            val job = builds.start("layout", builds.stages().single().fingerprint!!)
            fun finding(note: String, kind: String, status: String) =
                StudyBuildFinding(job.id, note, "view-1", box.id, box.id, "Wall W3 crosses the stair", kind, status, 0, false, true)
            val tools = StudyBuildTools(info, { listOf(BoxStudyBuildBinding(box, "study", builds)) }, { runs ->
                listOf(finding("note-error", "error", "open"), finding("note-old", "warning", "resolved")).filter { it.runId in runs } })
            assertEquals(StudyBuildStatus.FAILED, (tools.wait(WaitStudyBuildRequest(box.id, "study", job.id, 5.0)).details["job"] as Map<*, *>)["status"])
            fun inspect() = tools.inspect(InspectStudyBuildsRequest(box.id, "study")).details
            fun latest() = ((inspect()["stages"] as List<*>).single() as Map<*, *>)["latest"] as Map<*, *>
            fun reviews(job: Map<*, *>) = (job["reviewFindings"] as List<*>).map { it as Map<*, *> }

            val now = latest()
            assertEquals(false, now["fromEarlierInputs"])
            assertEquals(1, now["openFindings"])
            assertEquals(listOf("note-error" to false, "note-old" to false), reviews(now).map { it["noteId"] to it["fromEarlierInputs"] })

            plan = "stair at 4"
            // The card's own predicate: the agent and the person get the same verdict.
            assertTrue(studyBuildLatestUsedEarlierInputs(builds.stages().single()))
            val history = (inspect()["history"] as List<*>).single() as Map<*, *>
            val byId = tools.inspect(InspectStudyBuildsRequest(box.id, "study", job.id)).details["job"] as Map<*, *>
            for (earlier in listOf(latest(), history, byId)) {
                assertEquals(true, earlier["fromEarlierInputs"])
                assertEquals(0, earlier["openFindings"])
                // The discussion keeps its own status; what changed is whether it describes the current design.
                assertEquals(listOf("open" to true, "resolved" to true), reviews(earlier).map { it["status"] to it["fromEarlierInputs"] })
            }
            plan = null
            assertEquals(true, latest()["fromEarlierInputs"])
            plan = "stair at 3"
            assertEquals(false, latest()["fromEarlierInputs"])
            assertEquals(1, latest()["openFindings"])
        }
    }

    @Test fun aFailureAgainstCurrentInputsNamesTheBoxThatDrawsIt() {
        val info = session()
        val box = Box("source").also { info.boxes[it.id] = it }
        val plan = Box("plan-box").also { it.name = "floor plan"; info.boxes[it.id] = it }
        var stair = "stair at 3"
        var view: Map<String, Any?> = mapOf("box" to plan.id, "caption" to "Stair opening", "viewId" to "view-1")
        var gate: CountDownLatch? = null
        StudyBuilds(Files.createTempDirectory("failure-view").toFile(), listOf(StudyBuildStage("layout", "House layout", "v1", setOf("stair")) {
            gate?.let { check(it.await(5, TimeUnit.SECONDS)) }
            throw StudyBuildFailure(StudyBuildProblem("overlap", "Two walls overlap", details = mapOf("geometryView" to view)))
        }), { mapOf("stair" to stair) }).use { builds ->
            box.properties["_studyBuilds:study"] = BoxStudyBuildBinding(box, "study", builds)
            val events = java.util.concurrent.CopyOnWriteArrayList<LlmProgressUpdate>()
            val ctx = ToolExecutionContext(fieldGraph = FieldGraphTools(info, host), reportProgress = { events += it })
            fun execute(q: Any) = registry.executeByName(ctx, q.javaClass.simpleName, aiToolMapper.writeValueAsString(q)) as StudyBuildToolResult
            fun build(): Map<*, *> {
                events.clear()
                return execute(StartStudyBuildRequest(box.id, "study", "layout", builds.stages().single().fingerprint!!,
                    waitSeconds = 5.0)).details["job"] as Map<*, *>
            }
            // The chat's progress card for the finished attempt.
            fun chatCard() = events.last { it.phase == "build_result" }.body

            val failed = build()
            assertEquals(StudyBuildStatus.FAILED, failed["status"])
            assertEquals(mapOf("box" to plan.id, "boxName" to "floor plan", "caption" to "Stair opening", "viewId" to "view-1"), failed["geometryView"])
            assertContains(aiToolMapper.writeValueAsString(call(info, InspectStudyBuildsRequest(box.id, "study"))), "\"boxName\":\"floor plan\"")
            // A field://box link, as AI4 itself writes them: it selects and centres the box, as the card's link does.
            assertContains(chatCard(), "The failing geometry is shown in red in the output of [floor plan](field://box/plan-box): Stair opening.")

            stair = "stair at 4"
            val earlier = call(info, InspectStudyBuildsRequest(box.id, "study", failed["id"] as String)).details["job"] as Map<*, *>
            assertEquals(true, earlier["fromEarlierInputs"])
            // The card shows no "Show where it fails" for an earlier design; the saved diagnostic still names the view.
            assertNull(earlier["geometryView"])
            assertContains(aiToolMapper.writeValueAsString(earlier["problem"]), "plan-box")

            view = mapOf("box" to "not-in-this-document")
            assertEquals(mapOf("box" to "not-in-this-document", "boxName" to null, "caption" to null, "viewId" to null), build()["geometryView"])
            // No link to a box this document does not have.
            assertContains(chatCard(), "in the output of “not-in-this-document”.")
            assertFalse(chatCard().contains("field://"))
            view = mapOf("caption" to "names no box")
            assertNull(build()["geometryView"])
            assertFalse(chatCard().contains("shown in red"))

            // An edit while the attempt runs: it still fails, but against inputs the design has left.
            view = mapOf("box" to plan.id)
            val release = CountDownLatch(1).also { gate = it }
            val job = builds.start("layout", builds.stages().single().fingerprint!!)
            stair = "stair at 5"
            release.countDown()
            events.clear()
            val late = execute(WaitStudyBuildRequest(box.id, "study", job.id, 5.0)).details["job"] as Map<*, *>
            assertEquals(StudyBuildStatus.FAILED, late["status"])
            assertEquals(true, late["fromEarlierInputs"])
            assertContains(chatCard(), "Two walls overlap")
            assertFalse(chatCard().contains("shown in red"))
        }
    }
}
