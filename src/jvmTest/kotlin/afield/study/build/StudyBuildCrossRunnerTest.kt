package afield.study.build

import afield.data.collections.invoke
import afield.data.collections.s
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
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.times
import afield.math.linalg.x
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times

import afield.data.collections.get
import afield.document.workspace.file
import afield.document.workspace.path
import afield.math.linalg.set
import afield.media.image.close
import afield.document.graph.Box
import afield.app.server.SessionInformation
import afield.study.build.StudyBuildTestSupport.build
import afield.study.build.StudyBuildTestSupport.state
import afield.study.build.StudyBuildTestSupport.temp
import afield.study.build.StudyBuildTestSupport.writing
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class StudyBuildCrossRunnerTest {
    private val runners = ConcurrentHashMap<String, StudyBuilds>()
    private val place = StudyBuildStage("place", "Place loot", "place-v1", dependencies = listOf(dependsOn("level", "blockout"))) { c ->
        c.file("loot.txt").writeText("loot in " + c.dependencyFile(dependsOn("level", "blockout"), "walls.txt").readText())
        StudyBuildProduct(listOf("loot.txt"))
    }

    @Test fun aStageDependsOnTheCurrentResultOfAnotherRunner() {
        val plan = AtomicReference("four rooms")
        StudyBuilds(temp(), listOf(place), { emptyMap() }, upstream = runners::get).use { downstream ->
            assertEquals(listOf("level/blockout"), downstream.state("place").blockedBy)
            assertContains(downstream.state("place").reason!!, "No build runner 'level'")
            StudyBuilds(temp(), listOf(writing("blockout", "plan", "walls.txt")), { mapOf("plan" to plan.get()) }).use { level ->
                runners["level"] = level
                assertContains(downstream.state("place").reason!!, "level/blockout (Not built yet)")
                val walls = level.build("blockout")
                val placed = downstream.build("place")
                assertEquals(StudyBuildStatus.SUCCEEDED, placed.status)
                assertEquals(walls.id, placed.dependencies.getValue("level/blockout").jobId)
                assertEquals("loot in four rooms", downstream.currentFile("place", "loot.txt").readText())

                // An upstream edit makes the downstream result stale until the upstream is rebuilt.
                plan.set("five rooms")
                assertNull(downstream.state("place").current)
                assertEquals(listOf("level/blockout"), downstream.state("place").blockedBy)
                level.build("blockout")
                assertNotNull(downstream.state("place").fingerprint)
                assertNull(downstream.state("place").current)
                assertEquals("loot in five rooms", java.io.File(downstream.attemptDirectory(downstream.build("place").id), "loot.txt").readText())
            }
        }
    }

    @Test fun aGateCanJudgeAnotherRunnersResult() {
        val rooms = StudyBuildGate("enough rooms", dependencies = listOf(dependsOn("level", "blockout"))) { evidence ->
            val count = (evidence.facts(dependsOn("level", "blockout"))["rooms"] as Number).toInt()
            if (count >= 4) null else "The level has $count rooms; placement needs at least 4."
        }
        val gated = StudyBuildStage("place", "Place loot", "v1", setOf("enough rooms")) { c -> c.file("x").writeText("x"); StudyBuildProduct(listOf("x")) }
        val plan = AtomicReference("3")
        StudyBuilds(temp(), listOf(writing("blockout", "plan", "walls.txt") { mapOf("rooms" to it.toInt()) }), { mapOf("plan" to plan.get()) }).use { level ->
            runners["level"] = level
            StudyBuilds(temp(), listOf(gated), { emptyMap() }, gates = listOf(rooms), upstream = runners::get).use { downstream ->
                level.build("blockout")
                assertEquals("The level has 3 rooms; placement needs at least 4.", downstream.state("place").inputHelp?.message)
                plan.set("6"); level.build("blockout")
                assertEquals(StudyBuildStatus.SUCCEEDED, downstream.build("place").status)
            }
        }
    }

    @Test fun aCycleAcrossRunnersIsRefusedRatherThanRecursing() {
        val a = StudyBuildStage("a", "A", "v1", dependencies = listOf(dependsOn("second", "b"))) { error("never") }
        val b = StudyBuildStage("b", "B", "v1", dependencies = listOf(dependsOn("first", "a"))) { error("never") }
        StudyBuilds(temp(), listOf(a), { emptyMap() }, upstream = runners::get).use { first ->
            StudyBuilds(temp(), listOf(b), { emptyMap() }, upstream = runners::get).use { second ->
                runners["first"] = first; runners["second"] = second
                assertContains(assertFailsWith<IllegalStateException> { first.stages() }.message!!, "cycle")
            }
        }
    }

    @Test fun dependencyNamesAreRunnerAndStage() {
        assertEquals("level/blockout", dependsOn("level", "blockout"))
        assertFailsWith<IllegalArgumentException> { dependsOn("level/x", "blockout") }
        assertFailsWith<IllegalArgumentException> {
            StudyBuilds(temp(), listOf(StudyBuildStage("s", "S", "v1", dependencies = listOf("level/")) { error("never") }), { emptyMap() })
        }
    }

    @Test fun aDocumentFindsItsRunnersByNameAndRefusesTwoOfOneName() {
        val document = SessionInformation(temp().path, "cross-runner", root = Box("root"), ephemeral = true)
        val other = SessionInformation(temp().path, "elsewhere", root = Box("root"), ephemeral = true)
        StudyBuilds(temp(), listOf(writing("blockout", "plan")), { emptyMap() }).use { builds ->
            val binding = BoxStudyBuildBinding(Box("level-box"), "level", builds, document)
            try {
                assertSame(builds, studyBuildRunner(document, "level"))
                assertNull(studyBuildRunner(other, "level"))
                assertNull(studyBuildRunner(document, "missing"))
                StudyBuilds(temp(), listOf(writing("blockout", "plan")), { emptyMap() }).use { twin ->
                    val second = BoxStudyBuildBinding(Box("twin-box"), "level", twin, document)
                    assertContains(assertFailsWith<IllegalStateException> { studyBuildRunner(document, "level") }.message!!, "Two boxes")
                    second.close()
                }
                assertSame(builds, studyBuildRunner(document, "level"))
            } finally { binding.close() }
            assertNull(studyBuildRunner(document, "level"))
        }
    }
}
