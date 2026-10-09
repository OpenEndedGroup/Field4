package afield.ai.agent

import afield.AiAssist
import afield.Box
import afield.SessionInformation
import afield.ai.core.*
import afield.ai.core.program.NOT_CALLABLE_FROM_PROGRAMS
import afield.stdlib.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * A box chat's turn that ends with `AwaitStudyBuildsRequest` waits for its jobs without the model
 * ([StudyBuildAwaits]): the wait is saved with the chat, survives a restart, and resumes the conversation once,
 * with each job's outcome, when the jobs finish, are cancelled, or the time allowed has passed.
 */
class StudyBuildAwaitsTest {
    private val host = object : FieldGraphHost by LiveFieldGraphHost {
        override fun <T> onOwner(info: SessionInformation, action: () -> T): T = action()
    }
    private val registry = ToolRegistry().also(::registerBuiltInAgentTools)
    private val hour = 3_600_000L

    /** [release] lets the build finish; while [hold] is up, the worker does not look for a cancellation. */
    private class Fixture(val info: SessionInformation, val chat: Box, val source: Box, val dir: File,
        val builds: StudyBuilds, val release: CountDownLatch, val hold: CountDownLatch) : AutoCloseable {
        override fun close() { release.countDown(); hold.countDown(); builds.close() }
    }

    private fun session(): SessionInformation =
        SessionInformation(Files.createTempDirectory("await-builds-").toString(), "test", root = Box("root"), ephemeral = true)

    private fun stages(release: CountDownLatch, hold: CountDownLatch = CountDownLatch(0)) = listOf(StudyBuildStage("bake", "Bake the mesh", "v1",
        resultNotes = { r -> listOf(StudyBuildNotice("${r.facts["open"]} findings remain; inspect the report.")) }) { c ->
        c.progress(0.25, "Baking")
        while (!release.await(20, TimeUnit.MILLISECONDS)) { hold.await(); c.checkCancelled() }
        c.file("mesh.txt").writeText("baked")
        StudyBuildProduct(listOf("mesh.txt"), facts = mapOf("triangles" to 1200, "open" to 2))
    })

    private fun bind(info: SessionInformation, dir: File, release: CountDownLatch, hold: CountDownLatch = CountDownLatch(0)): Pair<Box, StudyBuilds> {
        val source = Box("source").also { info.boxes[it.id] = it }
        val builds = StudyBuilds(dir, stages(release, hold), { emptyMap() })
        source.properties["_studyBuilds:study"] = BoxStudyBuildBinding(source, "study", builds)
        return source to builds
    }

    private fun fixture(held: Boolean = false): Fixture {
        val info = session()
        val chat = Box("chat").also { it.name = "Chat"; info.boxes[it.id] = it }
        val dir = Files.createTempDirectory("await-builds-store").toFile()
        val release = CountDownLatch(1)
        val hold = CountDownLatch(if (held) 1 else 0)
        val (source, builds) = bind(info, dir, release, hold)
        return Fixture(info, chat, source, dir, builds, release, hold)
    }

    private fun Fixture.start(): String = builds.start("bake", builds.stages().single().fingerprint!!).id

    private fun waitFor(builds: StudyBuilds, id: String, status: StudyBuildStatus) {
        val until = System.currentTimeMillis() + 10_000
        while (builds.job(id).status != status) {
            check(System.currentTimeMillis() < until) { "job $id is ${builds.job(id).status}, not $status" }
            Thread.sleep(10)
        }
    }

    private fun ctx(info: SessionInformation, suspendable: Boolean = true) =
        ToolExecutionContext(fieldGraph = FieldGraphTools(info, host), suspendable = suspendable, promoted = mutableSetOf())

    private fun await(info: SessionInformation, job: String, maxHours: Double = 4.0) = registry.executeByName(ctx(info),
        "AwaitStudyBuildsRequest", """{"boxId":"source","name":"study","jobIds":["$job"],"maxHours":$maxHours}""") as StudyBuildAwaitResult

    private fun awaits(resume: (SessionInformation, Box, AiAssist) -> Boolean, clock: () -> Long = System::currentTimeMillis) =
        StudyBuildAwaits(graphFor = { FieldGraphTools(it, host) }, resume = resume, pollMs = hour, clock = clock, isOpen = { true })

    @Test fun aTurnThatAwaitsItsBuildEndsAndResumesOnceWithNoModelCallInBetween() = fixture().use { f ->
        val job = f.start()
        waitFor(f.builds, job, StudyBuildStatus.RUNNING)
        val bridge = ScriptedLlmBridge(listOf(
            ScriptedLlmBridge.Step.Call("AwaitStudyBuildsRequest", """{"boxId":"source","name":"study","jobIds":["$job"],"maxHours":4}"""),
            ScriptedLlmBridge.Step.Final("The bake finished with 1200 triangles.")))
        val state = bridge.startThread("k", "")
        val resumed = mutableListOf<AiAssist>()
        var second: LlmSendResult? = null
        val awaits = awaits({ _, box, assist ->
            assertSame(f.chat, box)
            resumed += assist
            second = bridge.send(state, assist.extraPrompt, ctx = ctx(f.info))
            true
        })
        awaits.use {
            val first = bridge.send(state, "Bake the mesh and tell me how it went.", ctx = ctx(f.info))
            val suspension = assertNotNull(first.suspension, "the await ends the turn")
            val saved = awaits.suspend(f.info, f.chat, suspension, "code", 24)
            assertEquals(saved, StudyBuildAwaits.read(f.chat), "saved with the chat")
            assertContains(saved.row, "Waiting for 1 build · no model calls")
            assertTrue(afield.parseAiChatBlocks(saved.row).single().isBuildWait, "the panel shows it on its own")
            assertContains(saved.row, "attempt `$job`")
            val transcript = "<<you>>\nBake the mesh\n<<ai>>\nStarted it.\n" + saved.row
            assertEquals(transcript.replace("<<progress>>", "<<progress-open>>"), awaits.shown(f.info, f.chat, transcript),
                "a panel shows the waiting row open while the wait lasts")

            repeat(3) { awaits.tick() }
            assertEquals(1, bridge.received.size, "no model call while the build runs")
            assertTrue(resumed.isEmpty())
            assertEquals("RUNNING", awaits.suspensionOf(f.info, f.chat)!!.jobs.single().status)

            f.release.countDown()
            waitFor(f.builds, job, StudyBuildStatus.SUCCEEDED)
            awaits.tick()
            val continuation = resumed.single()
            assertEquals("general", continuation.kind)
            assertTrue(continuation.queueIfBusy, "a turn already running in the box is given it, like a typed message")
            assertTrue(continuation.continuationId.isNotBlank())
            val text = continuation.extraPrompt
            assertTrue(text.startsWith("**Field:** the build you awaited has ended"), text)
            assertContains(text, "No model call was made while it ran.")
            assertContains(text, "`study/bake` (Bake the mesh), attempt `$job`: **succeeded**; current result.")
            assertContains(text, "Facts: triangles=1200, open=2.")
            assertContains(text, "Warning: 2 findings remain; inspect the report.")

            assertEquals(2, bridge.received.size, "one model call, when the build had finished")
            assertNotNull(bridge.received[1].resumedRound, "the resumed turn opens with the await's own output")
            assertEquals("The bake finished with 1200 triangles.", second!!.text)
            assertNull(StudyBuildAwaits.read(f.chat), "the wait is over")
            assertNull(awaits.suspensionOf(f.info, f.chat))
            assertEquals(transcript, awaits.shown(f.info, f.chat, transcript), "and its row is shown closed")
            awaits.tick()
            assertEquals(1, resumed.size, "resumed once")
        }
    }

    @Test fun aRestartedFieldRearmsTheWaitSavedWithTheChat() = fixture().use { f ->
        val job = f.start()
        waitFor(f.builds, job, StudyBuildStatus.RUNNING)
        val before = awaits({ _, _, _ -> fail("the Field that stopped never resumes the chat") })
        before.suspend(f.info, f.chat, assertNotNull(await(f.info, job).suspension), "code", 24)
        val saved = f.chat.getPropertyRaw(StudyBuildAwaits.PROPERTY) as String
        // Field stops. Its runner stops with it, and the document comes back as new boxes holding what was saved.
        before.close()
        f.builds.close()
        val info = session()
        val chat = Box("chat").also { info.boxes[it.id] = it }
        chat.setPropertyRaw(StudyBuildAwaits.PROPERTY, saved, doResync = false)
        val (_, reopened) = bind(info, f.dir, CountDownLatch(0))
        reopened.use {
            val resumed = mutableListOf<AiAssist>()
            awaits({ _, box, assist -> assertSame(chat, box); resumed += assist; true }).use { after ->
                after.rearm(info)
                assertEquals(job, after.suspensionOf(info, chat)!!.jobs.single().jobId)
                after.tick()
                assertContains(resumed.single().extraPrompt, "attempt `$job`: **interrupted**.")
                assertNull(StudyBuildAwaits.read(chat))
            }
        }
    }

    @Test fun aRunnerThatNeverComesBackIsReadFromItsDirectoryAfterAGracePeriod() = fixture().use { f ->
        val job = f.start()
        waitFor(f.builds, job, StudyBuildStatus.RUNNING)
        // What a runner leaves when Field dies under it: its manifest still says running.
        val left = Files.createTempDirectory("await-builds-crashed").toFile()
        File(f.dir, "builds.json").copyTo(File(left, "builds.json"))
        val info = session()
        val chat = Box("chat").also { info.boxes[it.id] = it }
        var now = System.currentTimeMillis()
        val resumed = mutableListOf<AiAssist>()
        awaits({ _, _, assist -> resumed += assist; true }, clock = { now }).use { awaits ->
            awaits.suspend(info, chat, TurnSuspension(listOf(AwaitedBuild("source", "study", job, "bake", "Bake the mesh", left.path)), now + hour), "code", 24)
            awaits.tick()
            assertTrue(resumed.isEmpty(), "a runner may be relaunching: its box re-running, the document reopening")
            now += awaits.runnerGraceMs
            awaits.tick()
            assertContains(resumed.single().extraPrompt, "attempt `$job`: **interrupted**: its runner is not running")
        }
    }

    @Test fun aCancelledJobResumesTheChatWithItsCancellation() = fixture(held = true).use { f ->
        val job = f.start()
        waitFor(f.builds, job, StudyBuildStatus.RUNNING)
        val resumed = mutableListOf<AiAssist>()
        awaits({ _, _, assist -> resumed += assist; true }).use { awaits ->
            awaits.suspend(f.info, f.chat, assertNotNull(await(f.info, job).suspension), "code", 24)
            val cancel = registry.executeByName(ctx(f.info), "CancelStudyBuildRequest",
                """{"boxId":"source","name":"study","jobId":"$job"}""") as StudyBuildToolResult
            assertTrue(cancel.success, cancel.message)
            awaits.tick()
            assertTrue(resumed.isEmpty(), "a cancellation request is not a stopped build")
            f.hold.countDown()
            waitFor(f.builds, job, StudyBuildStatus.CANCELLED)
            awaits.tick()
            assertContains(resumed.single().extraPrompt, "attempt `$job`: **cancelled**")
        }
    }

    @Test fun theTimeLimitResumesTheChatWithTheJobStillRunning() = fixture().use { f ->
        val job = f.start()
        waitFor(f.builds, job, StudyBuildStatus.RUNNING)
        var now = System.currentTimeMillis()
        val resumed = mutableListOf<AiAssist>()
        awaits({ _, _, assist -> resumed += assist; true }, clock = { now }).use { awaits ->
            val result = await(f.info, job, maxHours = 0.5)
            assertEquals(true, result.details["suspended"])
            awaits.suspend(f.info, f.chat, result.suspension!!, "code", 24)
            now += 29 * 60_000L
            awaits.tick()
            assertTrue(resumed.isEmpty())
            now += 2 * 60_000L
            awaits.tick()
            val text = resumed.single().extraPrompt
            assertTrue(text.startsWith("**Field:** the time you allowed for these builds has passed (31 min), and 1 build is still running"), text)
            assertContains(text, "attempt `$job`: **still running** (25%, Baking).")
            assertEquals(StudyBuildStatus.RUNNING, f.builds.job(job).status, "the build itself is not stopped")
        }
    }

    @Test fun stoppingTheWaitLeavesTheBuildRunning() = fixture().use { f ->
        val job = f.start()
        waitFor(f.builds, job, StudyBuildStatus.RUNNING)
        awaits({ _, _, _ -> fail("a stopped wait never resumes the chat") }).use { awaits ->
            awaits.suspend(f.info, f.chat, assertNotNull(await(f.info, job).suspension), "code", 24)
            assertEquals(listOf(job), awaits.cancel(f.info, f.chat)!!.jobs.map { it.jobId })
            assertNull(StudyBuildAwaits.read(f.chat))
            f.release.countDown()
            waitFor(f.builds, job, StudyBuildStatus.SUCCEEDED)
            awaits.tick()
            assertNull(awaits.cancel(f.info, f.chat), "nothing left to stop")
        }
    }

    @Test fun aContinuationTheChatRefusesIsOfferedAgainLater() = fixture().use { f ->
        val job = f.start()
        f.release.countDown()
        var now = System.currentTimeMillis()
        val offered = mutableListOf<AiAssist>()
        awaits({ _, _, assist -> offered += assist; offered.size > 1 }, clock = { now }).use { awaits ->
            waitFor(f.builds, job, StudyBuildStatus.SUCCEEDED)
            awaits.suspend(f.info, f.chat, TurnSuspension(listOf(AwaitedBuild("source", "study", job, "bake")), now + hour), "code", 24)
            awaits.tick()
            assertEquals(1, offered.size)
            assertNotNull(StudyBuildAwaits.read(f.chat), "still waiting for the chat to take it")
            awaits.tick()
            assertEquals(1, offered.size, "not offered again at once")
            now += 30_000
            awaits.tick()
            assertEquals(2, offered.size)
            assertEquals(offered[0], offered[1], "the same continuation, not a second one")
            assertNull(StudyBuildAwaits.read(f.chat))
        }
    }

    @Test fun aClosedDocumentKeepsItsWaitForWhenItOpensAgain() = fixture().use { f ->
        val job = f.start()
        f.release.countDown()
        waitFor(f.builds, job, StudyBuildStatus.SUCCEEDED)
        var open = true
        val resumed = mutableListOf<AiAssist>()
        StudyBuildAwaits(graphFor = { FieldGraphTools(it, host) }, resume = { _, _, a -> resumed += a; true }, pollMs = hour,
            isOpen = { open }).use { awaits ->
            awaits.suspend(f.info, f.chat, TurnSuspension(listOf(AwaitedBuild("source", "study", job, "bake")), System.currentTimeMillis() + hour), "code", 24)
            open = false
            awaits.tick()
            assertTrue(resumed.isEmpty(), "a document nobody has open is not resumed")
            assertNotNull(StudyBuildAwaits.read(f.chat), "its wait stays saved with it")
            open = true
            awaits.rearm(f.info)
            awaits.tick()
            assertContains(resumed.single().extraPrompt, "attempt `$job`: **succeeded**")
        }
    }

    @Test fun jobsThatHaveAlreadyFinishedAreAnsweredWithoutEndingTheTurn() = fixture().use { f ->
        val job = f.start()
        f.release.countDown()
        waitFor(f.builds, job, StudyBuildStatus.SUCCEEDED)
        val result = await(f.info, job)
        assertTrue(result.success, result.message)
        assertNull(result.suspension)
        assertEquals(false, result.details["suspended"])
        assertFalse(await(f.info, "no-such-job").success)
        assertFalse(await(f.info, job, maxHours = 49.0).success)
    }

    @Test fun awaitIsOfferedOnlyWhereSomethingWillResumeTheTurn() = fixture().use { f ->
        fun offered(suspendable: Boolean): Boolean {
            val ctx = ctx(f.info, suspendable)
            registry.executeByName(ctx, "DescribeToolsRequest", """{"namespace":"builds"}""")
            return registry.describe(ctx).any { "AwaitStudyBuildsRequest" in it.toString() }
        }
        assertFalse(offered(false), "the editor kinds, the document chat, a headless task, MCP")
        assertTrue(offered(true), "the box chat's general turn")
        assertTrue("AwaitStudyBuildsRequest" in NOT_CALLABLE_FROM_PROGRAMS, "only a call the model makes itself can end the turn")
    }
}
