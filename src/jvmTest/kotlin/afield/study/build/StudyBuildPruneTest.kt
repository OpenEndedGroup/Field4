package afield.study.build

import afield.data.collections.invoke
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.graphics.render.rangeTo
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.d
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.times
import afield.math.linalg.x
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.media.image.close

import afield.data.collections.get
import afield.document.graph.named
import afield.document.workspace.file
import afield.graphics.shader.mesh
import afield.math.linalg.set
import afield.simulation.automata.history
import afield.study.build.StudyBuildTestSupport.arrives
import afield.study.build.StudyBuildTestSupport.build
import afield.study.build.StudyBuildTestSupport.startCurrent
import afield.study.build.StudyBuildTestSupport.state
import afield.study.build.StudyBuildTestSupport.temp
import afield.study.build.StudyBuildTestSupport.writing
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class StudyBuildPruneTest {
    private val plan = AtomicReference("two rooms")
    private val failMesh = AtomicBoolean(false)
    private val mesh = StudyBuildStage("mesh", "Mesh", "mesh-v1", setOf("plan")) { c ->
        if (failMesh.get()) throw StudyBuildFailure(StudyBuildProblem("bad-plan", "The plan does not close."))
        c.file("walls.obj").writeText("o walls\n# ${c.inputs.getValue("plan")}\n" + "v 0 0 0\n".repeat(1000))
        StudyBuildProduct(listOf("walls.obj"))
    }
    private val report = StudyBuildStage("report", "Report", "report-v1", dependencies = listOf("mesh")) { c ->
        c.file("report.txt").writeText(c.dependencyFile("mesh", "walls.obj").readLines()[1]); StudyBuildProduct(listOf("report.txt"))
    }

    private fun attempts(builds: StudyBuilds) = File(builds.directory, "attempts").list().orEmpty().toSet()

    @Test fun pruningKeepsCurrentLatestAndSucceededResultsAndWhatTheyNameAndDeletesTheRest() {
        val root = temp()
        val kept: Set<String>
        val superseded: String
        StudyBuilds(root, listOf(mesh, report), { mapOf("plan" to plan.get()) }).use { builds ->
            superseded = builds.build("mesh").id
            plan.set("three rooms"); val reported = builds.build("mesh")
            val r1 = builds.build("report")
            plan.set("four rooms"); val current = builds.build("mesh")
            failMesh.set(true); plan.set("five rooms"); val failed = builds.build("mesh")
            assertEquals(StudyBuildStatus.FAILED, failed.status)

            val preview = builds.prune(dryRun = true)
            assertEquals(listOf(superseded), preview.deleted)
            assertTrue(preview.freedBytes > 8000, "freed ${preview.freedBytes} bytes")
            assertTrue(superseded in attempts(builds), "a dry run deleted an attempt")

            val pruned = builds.prune()
            kept = setOf(reported.id, r1.id, current.id, failed.id)
            assertEquals(kept, pruned.kept.toSet())
            assertEquals(listOf(superseded), pruned.deleted)
            assertEquals(kept, attempts(builds))
            assertFailsWith<IllegalStateException> { builds.job(superseded) }
            // The latest report still reads the mesh it was built from.
            assertEquals("# three rooms", File(builds.directory, "attempts/${r1.id}/report.txt").readText())
            assertTrue(builds.prune().deleted.isEmpty())
        }
        StudyBuilds(root, listOf(mesh, report), { mapOf("plan" to plan.get()) }).use { reopened ->
            assertEquals(kept, reopened.history().map { it.id }.toSet())
        }
    }

    @Test fun pinsAndTheAttemptsThatRequestsAndResumesNameAreKept() {
        val compare = StudyBuildStage("compare", "Compare", "v1", setOf("plan")) { c ->
            (c.request["resume"] as? String)?.let { c.resumeFrom(it, listOf("walls.obj"), sameDesign = false) }
            c.file("diff.txt").writeText("against ${c.request["against"]}"); StudyBuildProduct(listOf("diff.txt"))
        }
        StudyBuilds(temp(), listOf(mesh, compare), { mapOf("plan" to plan.get()) }).use { builds ->
            val pinned = builds.build("mesh").id
            plan.set("b"); val named = builds.build("mesh").id
            plan.set("c"); val resumed = builds.build("mesh").id
            plan.set("d"); val dropped = builds.build("mesh").id
            plan.set("e"); builds.build("mesh")
            builds.build("compare", mapOf("against" to named, "resume" to resumed))
            val pruned = builds.prune(pins = setOf(pinned, "00000000-0000-0000-0000-000000000000"))
            assertEquals(listOf(dropped), pruned.deleted)
            assertTrue(setOf(pinned, named, resumed).all { it in pruned.kept })
        }
    }

    @Test fun runningAttemptsStayAndDirectoriesWithNoRecordGo() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val slow = StudyBuildStage("slow", "Slow", "v1", setOf("plan")) { c ->
            entered.countDown(); release.await(5, TimeUnit.SECONDS)
            c.file("x").writeText("x"); StudyBuildProduct(listOf("x"))
        }
        StudyBuilds(temp(), listOf(slow), { mapOf("plan" to plan.get()) }).use { builds ->
            val running = builds.startCurrent("slow"); entered.arrives()
            val stray = File(builds.directory, "attempts/11111111-2222-3333-4444-555555555555").apply { mkdirs() }
            File(stray, "left.txt").writeText("from a crash")
            val pruned = builds.prune()
            assertEquals(listOf(running.id), pruned.kept)
            assertFalse(stray.exists())
            release.countDown()
            assertEquals(StudyBuildStatus.SUCCEEDED, builds.await(running.id).status)
        }
    }

    @Test fun runnersThatNameEachOthersAttemptsArePrunedTogether() {
        val runners = ConcurrentHashMap<String, StudyBuilds>()
        val place = StudyBuildStage("place", "Place", "v1", dependencies = listOf(dependsOn("level", "blockout"))) { c ->
            c.file("loot.txt").writeText(c.dependencyFile(dependsOn("level", "blockout"), "walls.txt").readText()); StudyBuildProduct(listOf("loot.txt"))
        }
        val make = { StudyBuilds(temp(), listOf(writing("blockout", "plan", "walls.txt")), { mapOf("plan" to plan.get()) }) }
        make().use { level ->
            runners["level"] = level
            StudyBuilds(temp(), listOf(place), { emptyMap() }, upstream = runners::get).use { loot ->
                val first = level.build("blockout")
                loot.build("place")
                plan.set("other"); level.build("blockout")
                plan.set("third"); level.build("blockout")
                // Pruned alone, the level would delete the walls the current placement was built from.
                assertTrue(first.id in level.prune(dryRun = true).deleted)
                val together = StudyBuilds.prune(listOf(level, loot))
                assertFalse(first.id in together[0].deleted)
                assertEquals(1, together[0].deleted.size)
                assertTrue(together[1].deleted.isEmpty())
            }
        }
    }
}
