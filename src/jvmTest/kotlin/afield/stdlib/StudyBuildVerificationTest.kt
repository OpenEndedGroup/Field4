package afield.stdlib

import afield.stdlib.StudyBuildTestSupport.build
import afield.stdlib.StudyBuildTestSupport.state
import afield.stdlib.StudyBuildTestSupport.temp
import afield.stdlib.StudyBuildTestSupport.writing
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Result files are hashed once while unchanged, and refused as soon as their metadata says they changed. */
class StudyBuildVerificationTest {
    private fun StudyBuilds.resultFile(record: StudyBuildRecord, path: String = "result.txt") = File(attemptDirectory(record.id), path)

    /** Hashes computed by [read], as a count. */
    private fun StudyBuilds.hashesDuring(read: () -> Unit): Long = fileHashes.get().also { read() }.let { fileHashes.get() - it }

    @Test fun anUnchangedResultIsHashedOnceAcrossRepeatedReads() {
        val root = temp()
        val stages = listOf(writing("mesh", "shape"), writing("bake", "shape", "baked.txt", listOf("mesh")))
        StudyBuilds(root, stages, { mapOf("shape" to "torus") }).use { builds -> builds.build("mesh"); builds.build("bake") }
        // Reopened, so nothing is remembered from the builds themselves.
        StudyBuilds(root, stages, { mapOf("shape" to "torus") }).use { builds ->
            builds.hashSettleNanos = 0
            assertEquals(2, builds.hashesDuring { builds.stages() }, "the first read hashes each current result file once")
            assertEquals(0, builds.hashesDuring { repeat(20) { builds.stages() }; builds.currentFile("bake", "baked.txt") })
            assertNotNull(builds.state("bake").current)
        }
    }

    @Test fun aDependantRunnerReadsAnUpstreamResultWithoutHashingItAgain() {
        val runners = ConcurrentHashMap<String, StudyBuilds>()
        val place = StudyBuildStage("place", "Place", "v1", dependencies = listOf(dependsOn("level", "blockout"))) { c ->
            c.file("loot.txt").writeText(c.dependencyFile(dependsOn("level", "blockout"), "walls.txt").readText())
            StudyBuildProduct(listOf("loot.txt"))
        }
        StudyBuilds(temp(), listOf(writing("blockout", "plan", "walls.txt")), { mapOf("plan" to "four rooms") }).use { level ->
            runners["level"] = level
            StudyBuilds(temp(), listOf(place), { emptyMap() }, upstream = runners::get).use { downstream ->
                level.hashSettleNanos = 0; downstream.hashSettleNanos = 0
                level.build("blockout"); downstream.build("place")
                downstream.stages()
                assertEquals(0, level.hashesDuring { repeat(20) { downstream.stages() } })
                assertEquals(0, downstream.hashesDuring { repeat(20) { downstream.stages() } })
            }
        }
    }

    @Test fun aSameLengthEditWithANewModificationTimeIsRefused() {
        StudyBuilds(temp(), listOf(writing("mesh", "shape")), { mapOf("shape" to "1234") }).use { builds ->
            builds.hashSettleNanos = 0
            val job = builds.build("mesh")
            assertNotNull(builds.state("mesh").current)
            val file = builds.resultFile(job)
            val written = Files.getLastModifiedTime(file.toPath())
            file.writeText("4321")
            // A coarse file-system clock can give the rewrite the old time; this test is about a new one.
            Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(written.toMillis() + 1000))
            assertNull(builds.state("mesh").current)
            assertContains(builds.state("mesh").reason!!, "changed or is missing")
            assertFailsWith<IllegalStateException> { builds.currentFile("mesh", "result.txt") }
            file.writeText("1234")
            assertNotNull(builds.state("mesh").current, "restoring the recorded contents makes the result current again")
        }
    }

    @Test fun anEditThatRestoresTheModificationTimeIsRefusedByItsStatusChange() {
        if ("unix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
        StudyBuilds(temp(), listOf(writing("mesh", "shape")), { mapOf("shape" to "1234") }).use { builds ->
            builds.hashSettleNanos = 0
            val job = builds.build("mesh")
            val file = builds.resultFile(job)
            val written = Files.getLastModifiedTime(file.toPath())
            assertNotNull(builds.state("mesh").current)
            Thread.sleep(20)
            file.writeText("4321")
            Files.setLastModifiedTime(file.toPath(), written)
            assertNull(builds.state("mesh").current, "copying tools that keep modification times still change the status-change time")
        }
    }

    @Test fun aFileChangedWithinTheSettleTimeIsHashedOnEveryRead() {
        StudyBuilds(temp(), listOf(writing("mesh", "shape")), { mapOf("shape" to "torus") }).use { builds ->
            builds.hashSettleNanos = 60_000_000_000L
            builds.build("mesh")
            assertEquals(3, builds.hashesDuring { repeat(3) { builds.stages() } })
        }
    }

    @Test fun theRevisionMovesWithRecordsAndUpstreamRunnersButNotWithReads() {
        val runners = ConcurrentHashMap<String, StudyBuilds>()
        val plan = AtomicReference("four rooms")
        val place = StudyBuildStage("place", "Place", "v1", dependencies = listOf(dependsOn("level", "blockout"))) { c ->
            c.file("x").writeText("x"); StudyBuildProduct(listOf("x"))
        }
        StudyBuilds(temp(), listOf(place), { emptyMap() }, upstream = runners::get).use { downstream ->
            val alone = downstream.revision()
            StudyBuilds(temp(), listOf(writing("blockout", "plan", "walls.txt")), { mapOf("plan" to plan.get()) }).use { level ->
                runners["level"] = level
                val joined = downstream.revision()
                assertNotEquals(alone, joined, "a runner that appears upstream changes the revision")
                downstream.stages(); plan.set("five rooms"); downstream.stages()
                assertEquals(joined, downstream.revision(), "reads and design edits record nothing")
                level.build("blockout")
                assertNotEquals(joined, downstream.revision(), "an upstream build changes it")
            }
        }
    }

    @Test fun anIdleCardReadsOnlyWhenItsRunnerMovesOrTheIdleIntervalPasses() {
        val idle = STUDY_BUILD_IDLE_REFRESH_MS * 1_000_000
        assertFalse(studyBuildPanelDue(false, listOf(3L), listOf(3L), idle - 1))
        assertTrue(studyBuildPanelDue(false, listOf(3L), listOf(3L), idle))
        assertTrue(studyBuildPanelDue(false, listOf(4L), listOf(3L), 0))
        assertTrue(studyBuildPanelDue(true, listOf(3L), listOf(3L), 0))
        assertTrue(studyBuildPanelDue(false, null, listOf(3L), 0), "a runner whose revision cannot be read is shown as it is")
    }
}
