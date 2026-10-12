package afield.stdlib

import afield.stdlib.StudyBuildTestSupport.build
import afield.stdlib.StudyBuildTestSupport.startCurrent
import afield.stdlib.StudyBuildTestSupport.temp
import org.junit.Assume
import java.io.File
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class StudyBuildProcessTest {
    private val sh = File("/bin/sh")

    /** A stage that runs [script] with `sh` and keeps its log; [facts] records what runProcess returned. */
    private fun shell(script: String, progressFile: String? = null, stall: Duration? = null, deadline: Duration? = null, check: Boolean = true) =
        StudyBuildStage("run", "Run", "run-v1") { c ->
            c.file("script.sh").writeText(script)
            val result = c.runProcess(listOf(sh.path, "script.sh"), progressFile, stall, deadline, progressSpan = 0.0..0.8, check = check)
            StudyBuildProduct(listOf("process.log"), mapOf("exitCode" to result.exitCode, "lastMessage" to result.lastMessage))
        }

    private fun alive(pidFile: File): Boolean {
        val pid = pidFile.readText().trim().toLong()
        val deadline = System.nanoTime() + 5_000_000_000
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) == false) return false
            Thread.sleep(20)
        }
        return true
    }

    private fun attemptFile(builds: StudyBuilds, record: StudyBuildRecord, path: String) = File(builds.directory, "attempts/${record.id}/$path")

    @BeforeTest fun needsAPosixShell() = Assume.assumeTrue("needs /bin/sh", sh.canExecute())

    @Test fun progressComesFromTheProgramsFileAndTheLogIsKept() {
        val script = """
            i=0
            for f in 0.25 0.5 0.75 1.0; do
              i=${'$'}((i + 1))
              sleep 0.3
              printf '{"fraction": %s, "message": "pass %s of 4"}' "${'$'}f" "${'$'}i" > progress.json
              echo "finished pass ${'$'}i"
            done
        """.trimIndent()
        StudyBuilds(temp(), listOf(shell(script, "progress.json")), { emptyMap() }).use { builds ->
            val job = builds.startCurrent("run")
            val seen = linkedSetOf<String>()
            val progress = mutableListOf<Double>()
            while (true) {
                val now = builds.job(job.id)
                if (now.status !in setOf(StudyBuildStatus.QUEUED, StudyBuildStatus.RUNNING)) break
                seen += now.message; progress += now.progress
                Thread.sleep(10)
            }
            val done = builds.await(job.id)
            assertEquals(StudyBuildStatus.SUCCEEDED, done.status, done.problem?.message)
            assertTrue("pass 2 of 4" in seen, "progress messages seen: $seen")
            assertEquals(progress.sorted(), progress, "progress went backwards")
            assertTrue(progress.all { it <= 0.8 }, "progress left its span: $progress")
            assertEquals(0, done.facts["exitCode"])
            assertEquals("pass 4 of 4", done.facts["lastMessage"])
            assertContains(attemptFile(builds, done, "process.log").readText(), "finished pass 4")
        }
    }

    @Test fun aStalledProgramIsStoppedWithEveryProcessItStarted() {
        val script = """
            printf '{"fraction": 0.1, "message": "loading"}' > progress.json
            sleep 60 &
            echo ${'$'}! > child.pid
            wait
        """.trimIndent()
        StudyBuilds(temp(), listOf(shell(script, "progress.json", stall = 1.seconds)), { emptyMap() }).use { builds ->
            val started = System.nanoTime()
            val stalled = builds.build("run")
            assertEquals(StudyBuildStatus.FAILED, stalled.status)
            assertEquals("process-stalled", stalled.problem?.code)
            assertContains(stalled.problem!!.message, "made no progress for 1s (last: loading)")
            assertEquals("process.log", stalled.problem.details["log"])
            assertTrue(System.nanoTime() - started < 8_000_000_000, "the stall watchdog took too long")
            assertFalse(alive(attemptFile(builds, stalled, "child.pid")), "the program's child is still running")
        }
    }

    @Test fun withoutAProgressFileSilenceIsAStall() {
        val script = """
            for i in 1 2 3; do echo "line ${'$'}i"; sleep 0.2; done
            sleep 60
        """.trimIndent()
        StudyBuilds(temp(), listOf(shell(script, stall = 1.seconds)), { emptyMap() }).use { builds ->
            val stalled = builds.build("run")
            assertEquals("process-stalled", stalled.problem?.code)
            assertContains(attemptFile(builds, stalled, "process.log").readText(), "line 3")
        }
    }

    @Test fun aDeadlineStopsAProgramThatKeepsReporting() {
        val script = """
            i=0
            while true; do i=${'$'}((i + 1)); printf '{"fraction": 0.5, "message": "step %s"}' "${'$'}i" > progress.json; sleep 0.1; done
        """.trimIndent()
        StudyBuilds(temp(), listOf(shell(script, "progress.json", stall = 30.seconds, deadline = 1.seconds)), { emptyMap() }).use { builds ->
            val late = builds.build("run")
            assertEquals("process-deadline", late.problem?.code)
            assertContains(late.problem!!.message, "did not finish within 1s")
        }
    }

    @Test fun cancellingTheBuildStopsTheProgram() {
        val script = "echo ${'$'}${'$'} > self.pid\nsleep 60"
        StudyBuilds(temp(), listOf(shell(script)), { emptyMap() }).use { builds ->
            val job = builds.startCurrent("run")
            val pid = File(builds.directory, "attempts/${job.id}/self.pid")
            val deadline = System.nanoTime() + 5_000_000_000
            while (!(pid.isFile && pid.length() > 0) && System.nanoTime() < deadline) Thread.sleep(10)
            builds.cancel(job.id)
            assertEquals(StudyBuildStatus.CANCELLED, builds.await(job.id, 10.0).status)
            assertFalse(alive(pid), "the program outlived its cancelled build")
        }
    }

    @Test fun aNonZeroExitFailsTheBuildUnlessTheRecipeChecksItItself() {
        StudyBuilds(temp(), listOf(shell("echo broken; exit 3")), { emptyMap() }).use { builds ->
            val failed = builds.build("run")
            assertEquals("process-failed", failed.problem?.code)
            assertEquals(3, failed.problem!!.details["exitCode"])
            assertContains(failed.problem.suggestions.single(), "process.log")
        }
        StudyBuilds(temp(), listOf(shell("exit 3", check = false)), { emptyMap() }).use { builds ->
            assertEquals(3, builds.build("run").facts["exitCode"])
        }
    }
}
