package afield.ai.tools

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Background repository commands ([RepoCommandJobs]): start, wait, read by offset, stop, time out, and
 * the refusal to build under a Field that runs from the same checkout. The fixture's `gradlew` is a shell
 * script, so "Gradle" here is `sh`: `fast` prints and exits, `slow` prints a line a second, `fail` exits 3.
 */
class RepoCommandJobsTest {
    private class Fixture : AutoCloseable {
        val outer: Path = Files.createTempDirectory("field-repo-jobs")
        val root: Path = Files.createDirectories(outer.resolve("repo"))
        init {
            val script = """
                #!/bin/sh
                shift
                case "${'$'}1" in
                  fast) echo "fast task ran"; exit 0 ;;
                  fail) echo "about to fail"; exit 3 ;;
                  slow) i=0; while [ ${'$'}i -lt 60 ]; do echo "tick ${'$'}i"; i=${'$'}((i+1)); sleep 0.2; done; echo "BUILD SUCCESSFUL"; exit 0 ;;
                esac
                echo "unknown task ${'$'}1"; exit 1
            """.trimIndent() + "\n"
            root.resolve("gradlew").also { Files.writeString(it, script) }.toFile().setExecutable(true)
        }
        val files = RepoFileTools(RepoScope(root, listOf("**")))
        val tools = RepoCommandTools(files, RepoCommandScope(setOf("gradle", "git-status"), maxTimeoutSec = 30))
        fun start(task: String, timeoutSec: Int = 0) = tools.start(RepoStartCommandRequest("gradle", listOf(task), timeoutSec))
        override fun close() {
            RepoCommandJobs.all().filter { it.root == root.toFile().canonicalFile || it.root == root.toFile() }.forEach { RepoCommandJobs.stop(it) }
            outer.toFile().deleteRecursively()
        }
    }

    @Test fun aQuickCommandReportsItsExitInTheStartCall() = Fixture().use { f ->
        val started = f.start("fast")
        assertTrue(started.success, started.message)
        // Usually it has ended within the start call's short wait; on a loaded machine it may not have yet.
        if (started.state == "exited") assertTrue(started.message.contains("already ended"), started.message)
        else assertTrue(started.message.contains("RepoCommandStatusRequest(jobId=\"${started.jobId}\""), started.message)
        val r = f.tools.status(RepoCommandStatusRequest(started.jobId, waitSeconds = 30, fromByte = 0))
        assertEquals("exited", r.state, r.message)
        assertEquals(0, r.exitCode)
        assertTrue(r.output.contains("fast task ran"), r.output)
        assertTrue(File(r.logPath).readText().contains("fast task ran"))
    }

    @Test fun aFailingCommandIsNotASuccess() = Fixture().use { f ->
        val started = f.start("fail")
        val r = f.tools.status(RepoCommandStatusRequest(started.jobId, waitSeconds = 10))
        assertFalse(r.success)
        assertEquals(3, r.exitCode)
        assertTrue(r.message.startsWith("exit 3"), r.message)
    }

    @Test fun statusWaitsForTextThenReadsOnFromItsOffset() = Fixture().use { f ->
        val started = f.start("slow")
        assertTrue(started.success, started.message)
        assertEquals("running", started.state)
        val seen = f.tools.status(RepoCommandStatusRequest(started.jobId, waitSeconds = 20, untilText = "tick 5", fromByte = 0))
        assertEquals(true, seen.found, seen.message)
        assertEquals("running", seen.state)
        assertTrue(seen.output.contains("tick 5"), seen.output)
        val done = f.tools.status(RepoCommandStatusRequest(started.jobId, waitSeconds = 30, fromByte = seen.nextByte))
        assertEquals("exited", done.state, done.message)
        assertEquals(0, done.exitCode)
        assertTrue(done.output.contains("BUILD SUCCESSFUL"), done.output)
        assertFalse(done.output.contains("tick 0\n"), "a read from nextByte must not repeat what was read before")
        val listed = f.tools.status(RepoCommandStatusRequest())
        assertTrue(listed.jobs.any { it.jobId == started.jobId && it.state == "exited" }, listed.jobs.toString())
    }

    @Test fun stopEndsTheJobAndATimeLimitDoesToo() = Fixture().use { f ->
        val running = f.start("slow")
        val stopped = f.tools.stop(RepoStopCommandRequest(running.jobId))
        assertTrue(stopped.success, stopped.message)
        assertEquals("stopped", stopped.state)
        val limited = f.start("slow", timeoutSec = 1)
        val r = f.tools.status(RepoCommandStatusRequest(limited.jobId, waitSeconds = 20))
        assertEquals("timed-out", r.state, r.message)
        assertFalse(r.success)
        assertTrue(r.message.contains("1s limit"), r.message)
    }

    @Test fun unknownJobsAndRefusedCommandsSaySo() = Fixture().use { f ->
        assertFalse(f.tools.status(RepoCommandStatusRequest("job-nope")).success)
        assertFalse(f.tools.stop(RepoStopCommandRequest("job-nope")).success)
        val refused = f.tools.start(RepoStartCommandRequest("docs-build", listOf("never")))
        assertFalse(refused.success)
        assertTrue(refused.message.contains("not a command this job may run"), refused.message)
        val badTask = f.tools.start(RepoStartCommandRequest("gradle", listOf("clean")))
        assertFalse(badTask.success, badTask.message)
    }

    @Test fun atMostThreeJobsRunAtOnceInOneCheckout() = Fixture().use { f ->
        repeat(RepoCommandJobs.MAX_RUNNING_PER_ROOT) { assertTrue(f.start("slow").success) }
        val fourth = f.start("slow")
        assertFalse(fourth.success)
        assertTrue(fourth.message.contains("already running"), fourth.message)
    }

    @Test fun aBuildUnderAFieldRunningFromThisCheckoutIsRefusedOnlyWhenSourcesChanged() = Fixture().use { f ->
        val classes = Files.createDirectories(f.root.resolve("build/classes/kotlin/jvm/main")).toString()
        val src = f.root.resolve("src/jvmMain/kotlin/afield/A.kt").also { Files.createDirectories(it.parent); Files.writeString(it, "class A\n") }
        val now = System.currentTimeMillis()
        src.toFile().setLastModified(now - 60_000)
        // A Field started after the last source change: Gradle would find the compile up to date.
        assertNull(f.tools.liveBuildHazard(classPath = classes, startedMs = now - 30_000))
        // Source saved after Field started: Gradle would recompile under it.
        src.toFile().setLastModified(now)
        val hazard = f.tools.liveBuildHazard(classPath = classes, startedMs = now - 30_000)
        assertNotNull(hazard)
        assertTrue(hazard.contains("src/jvmMain/kotlin/afield/A.kt"), hazard)
        // A Field running from somewhere else is no concern of this checkout's.
        assertNull(f.tools.liveBuildHazard(classPath = "/elsewhere/build/classes/kotlin/jvm/main", startedMs = now - 30_000))
    }

    @Test fun newTemplatesBuildArgvFromCheckedSlots() {
        val root = Files.createTempDirectory("field-repo-templates")
        try {
            Files.createDirectories(root.resolve("public/docs")).resolve("page.md").also { Files.writeString(it, "# p\n") }
            val files = RepoFileTools(RepoScope(root, listOf("**")))
            fun build(name: String, vararg args: String) = RepoCommandTemplates.ALL.getValue(name).build(args.toList(), files)
            assertEquals(listOf("env", "FIELD_DOCS_RUNNER_MODE=never"), build("docs-build", "never").take(2))
            assertEquals(listOf("--page", "page.md"), build("docs-build", "incremental", "--page", "page.md").takeLast(2))
            assertEquals(listOf("--id", "examples.demo-transcript.exec"), build("field-test", "--id", "examples.demo-transcript.exec").takeLast(2))
            assertEquals("run-jvm-main afield.examples.DemoTranscriptExecSmokeTest --arg knight_tour_workshop",
                build("jvm-main", "afield.examples.DemoTranscriptExecSmokeTest", "knight_tour_workshop").last())
            assertEquals("run-jvm-main afield.examples.DocumentToTranscript --arg '/a b/doc' --arg 'it'\"'\"'s'",
                build("jvm-main", "afield.examples.DocumentToTranscript", "/a b/doc", "it's").last())
            assertEquals(listOf("sync", "--mode", "incremental"), build("docs-publications", "sync", "--mode", "incremental").takeLast(3))
            for (bad in listOf(listOf("docs-build", "sometimes"), listOf("docs-build", "never", "--page", "../x.md"), listOf("docs-build", "never", "--port", "1"),
                    listOf("field-test"), listOf("field-test", "--id", "a b"), listOf("jvm-main", "java.lang.Runtime"), listOf("jvm-main", "afield.X", "two\nlines"),
                    listOf("docs-publications", "prune"), listOf("docs-publications", "sync", "--force"))) {
                kotlin.test.assertFailsWith<RepoPathRefused>("$bad should be refused") { build(bad[0], *bad.drop(1).toTypedArray()) }
            }
            val docsNever = build("docs-build", "never")
            assertFalse(RepoCommandTemplates.ALL.getValue("docs-build").touchesBuild(docsNever))
            assertTrue(RepoCommandTemplates.ALL.getValue("docs-build").touchesBuild(build("docs-build", "incremental")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun aResumedTurnReadsHowEachAwaitedJobEnded() = Fixture().use { f ->
        val started = f.start("fail")
        f.tools.status(RepoCommandStatusRequest(started.jobId, waitSeconds = 20))
        val saved = afield.ai.agent.StudyBuildAwaits.SavedJob(boxId = "", name = "gradle", jobId = started.jobId, label = "gradle fail",
            kind = afield.ai.conversation.AwaitedBuild.REPO_JOB)
        val lost = saved.copy(jobId = "job-gone", label = "docs-build never")
        val text = afield.ai.agent.StudyBuildAwaits.repoContinuationText(listOf(saved, lost), waitedMs = 90_000, timedOut = false)
        assertTrue(text.contains("`gradle fail`, job `${started.jobId}`: **exited**, exit 3"), text)
        assertTrue(text.contains("about to fail"), text)
        assertTrue(text.contains("`docs-build never`, job `job-gone`: **lost**"), text)
        assertTrue(text.contains("after 1 min"), text)
    }

    @Test fun theCommandAfterEnvAssignmentsIsFoundToo() = Fixture().use { f ->
        val argv = f.tools.resolveExecutable(listOf("env", "FIELD_DOCS_RUNNER_MODE=never", "sh", "-c", "true"))
        assertTrue(File(argv[0]).isAbsolute && argv[0].endsWith("/env"), argv.toString())
        assertEquals("FIELD_DOCS_RUNNER_MODE=never", argv[1])
        assertTrue(File(argv[2]).isAbsolute && argv[2].endsWith("/sh"), argv.toString())
        assertEquals(listOf("-c", "true"), argv.drop(3))
    }

    @Test fun awaitingRunningJobsSuspendsTheTurnAndFinishedOnesDoNot() = Fixture().use { f ->
        val slow = f.start("slow")
        val r = awaitRepoCommands(AwaitRepoCommandsRequest(listOf(slow.jobId), maxMinutes = 5))
        assertTrue(r.success && r.suspended, r.message)
        val job = r.suspension!!.jobs.single()
        assertEquals(afield.ai.conversation.AwaitedBuild.REPO_JOB, job.kind)
        assertEquals(slow.jobId, job.jobId)
        val fast = f.start("fast")
        val done = awaitRepoCommands(AwaitRepoCommandsRequest(listOf(fast.jobId)))
        assertTrue(done.success && !done.suspended && done.suspension == null, done.message)
        assertFalse(awaitRepoCommands(AwaitRepoCommandsRequest(listOf("job-nope"))).success)
        assertFalse(awaitRepoCommands(AwaitRepoCommandsRequest(listOf(fast.jobId), maxMinutes = 0)).success)
    }
}
