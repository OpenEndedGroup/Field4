package afield.stdlib

import afield.stdlib.StudyBuildTestSupport.arrives
import afield.stdlib.StudyBuildTestSupport.build
import afield.stdlib.StudyBuildTestSupport.startCurrent
import afield.stdlib.StudyBuildTestSupport.state
import afield.stdlib.StudyBuildTestSupport.temp
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class StudyBuildResumeTest {
    // Relaxes for 100 more steps, continuing from the checkpoint of the attempt named in the request.
    private val relax = StudyBuildStage("relax", "Relax", "relax-v1", setOf("path")) { c ->
        (c.request["resume"] as? String)?.let { c.resumeFrom(it, listOf("checkpoint.txt", "trials"), c.request["sameDesign"] != false) }
        val checkpoint = c.file("checkpoint.txt")
        val start = if (checkpoint.isFile) checkpoint.readText().toInt() else 0
        checkpoint.writeText("${start + 100}")
        c.file("trials/run-${start + 100}.txt").apply { parentFile.mkdirs() }.writeText("trial")
        c.file("trials/notes/latest.txt").apply { parentFile.mkdirs() }.writeText("after ${start + 100}")
        StudyBuildProduct(listOf("checkpoint.txt"), mapOf("steps" to start + 100))
    }

    @Test fun anAttemptContinuesFromAHashCheckedCopyOfAnEarlierOne() {
        StudyBuilds(temp(), listOf(relax), { mapOf("path" to "L1 to L7") }).use { builds ->
            val first = builds.build("relax")
            val resumed = builds.build("relax", mapOf("resume" to first.id))
            assertEquals(StudyBuildStatus.SUCCEEDED, resumed.status, resumed.problem?.message)
            assertEquals(200, resumed.facts["steps"])
            val copy = resumed.resumedFrom.single()
            assertEquals(first.id, copy.jobId)
            assertEquals(listOf("checkpoint.txt", "trials/notes/latest.txt", "trials/run-100.txt"), copy.files.map { it.path })
            assertEquals(first.artifacts.single { it.path == "checkpoint.txt" }.sha256, copy.files.first().sha256)
            assertEquals("trial", File(builds.directory, "attempts/${resumed.id}/trials/run-100.txt").readText())
            assertEquals(resumed.id, builds.state("relax").current?.id)
        }
    }

    @Test fun aCheckpointOfAnotherDesignIsRefusedUnlessAskedFor() {
        val path = AtomicReference("L1 to L7")
        StudyBuilds(temp(), listOf(relax), { mapOf("path" to path.get()) }).use { builds ->
            val first = builds.build("relax")
            path.set("L1 to L3")
            val refused = builds.build("relax", mapOf("resume" to first.id))
            assertEquals(StudyBuildStatus.FAILED, refused.status)
            assertContains(refused.problem!!.message, "built a different design")
            assertTrue(refused.resumedFrom.isEmpty())
            val warm = builds.build("relax", mapOf("resume" to first.id, "sameDesign" to false))
            assertEquals(200, warm.facts["steps"])
        }
    }

    @Test fun aChangedResultFileIsNotCopied() {
        StudyBuilds(temp(), listOf(relax), { mapOf("path" to "L1 to L7") }).use { builds ->
            val first = builds.build("relax")
            File(builds.directory, "attempts/${first.id}/checkpoint.txt").writeText("999")
            val refused = builds.build("relax", mapOf("resume" to first.id))
            assertEquals(StudyBuildStatus.FAILED, refused.status)
            assertContains(refused.problem!!.message, "changed or is missing")
        }
    }

    @Test fun aFailedAttemptsCheckpointCanBeResumedButARunningOneCannot() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val partial = StudyBuildStage("partial", "Partial", "v1") { c ->
            c.file("checkpoint.txt").writeText("40")
            entered.countDown(); release.await(5, TimeUnit.SECONDS)
            throw StudyBuildFailure(StudyBuildProblem("budget", "Stopped at its time budget with a checkpoint."))
        }
        val next = StudyBuildStage("next", "Next", "v1") { c ->
            val copied = c.resumeFrom(c.request.getValue("resume") as String, listOf("checkpoint.txt"), sameDesign = false)
            c.file("result.txt").writeText("continued from ${copied.single().readText()}")
            StudyBuildProduct(listOf("result.txt"))
        }
        StudyBuilds(temp(), listOf(partial, next), { emptyMap() }).use { builds ->
            val running = builds.startCurrent("partial"); entered.arrives()
            val early = builds.build("next", mapOf("resume" to running.id))
            assertEquals(StudyBuildStatus.FAILED, early.status)
            assertContains(early.problem!!.message, "still running")
            release.countDown()
            assertEquals(StudyBuildStatus.FAILED, builds.await(running.id).status)
            val continued = builds.build("next", mapOf("resume" to running.id))
            assertEquals(StudyBuildStatus.SUCCEEDED, continued.status, continued.problem?.message)
            assertEquals("continued from 40", builds.currentFile("next", "result.txt").readText())
            val unknown = builds.build("next", mapOf("resume" to "00000000-0000-0000-0000-000000000000"))
            assertContains(unknown.problem!!.message, "No attempt")
        }
    }
}
