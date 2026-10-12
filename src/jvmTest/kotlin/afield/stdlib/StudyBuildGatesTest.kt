package afield.stdlib

import afield.stdlib.StudyBuildTestSupport.arrives
import afield.stdlib.StudyBuildTestSupport.build
import afield.stdlib.StudyBuildTestSupport.done
import afield.stdlib.StudyBuildTestSupport.startCurrent
import afield.stdlib.StudyBuildTestSupport.state
import afield.stdlib.StudyBuildTestSupport.temp
import afield.stdlib.StudyBuildTestSupport.writing
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class StudyBuildGatesTest {
    // A level as a list of loot points and how far each is; a point beyond 30 steps is out of reach.
    private val traversal = writing("traversal", "level", "paths.txt") { level ->
        val far = level.split(',').filter { it.substringAfter('=').toInt() > 30 }.map { it.substringBefore('=') }
        mapOf("outOfReach" to far)
    }
    private val reachable = StudyBuildGate("reachable loot", dependencies = listOf("traversal")) { evidence ->
        val far = evidence.facts("traversal")["outOfReach"] as List<*>
        if (far.isEmpty()) null else "${far.size} loot points are out of reach: ${far.joinToString()}."
    }
    private val placement = StudyBuildStage("placement", "Place loot", "placement-v1", setOf("reachable loot"),
        listOf("traversal")) { c ->
        c.file("loot.txt").writeText(c.dependencyFile("traversal", "paths.txt").readText())
        StudyBuildProduct(listOf("loot.txt"))
    }

    @Test fun aGatedStageWaitsUntilTheMeasurementPassesAndSaysWhatItWaitsFor() {
        val level = AtomicReference("L1=12,L3=41,L7=55")
        StudyBuilds(temp(), listOf(traversal, placement), { mapOf("level" to level.get()) }, gates = listOf(reachable)).use { builds ->
            // Before the measurement exists, the stage waits for the stage the gate reads.
            assertEquals(listOf("traversal"), builds.state("placement").blockedBy)
            assertNull(builds.state("placement").fingerprint)

            builds.build("traversal")
            val waiting = builds.state("placement")
            assertNull(waiting.fingerprint)
            assertTrue(waiting.blockedBy.isEmpty())
            assertEquals(StudyBuildInputHelp("Waiting for reachable loot", "2 loot points are out of reach: L3, L7."), waiting.inputHelp)
            assertEquals("Waiting for reachable loot: 2 loot points are out of reach: L3, L7.", waiting.reason)
            assertFailsWith<IllegalStateException> { builds.start("placement", "anything") }
            assertContains(studyBuildStageHtml(waiting, builds.stages(), emptyList()), "Waiting for reachable loot")

            level.set("L1=12,L3=28,L7=30")
            val measured = builds.build("traversal")
            val open = builds.state("placement")
            assertNull(open.inputHelp)
            val placed = builds.build("placement")
            assertEquals(StudyBuildStatus.SUCCEEDED, placed.status)
            assertEquals(placed.id, builds.state("placement").current?.id)
            // The attempt records the gate's value and the result it judged.
            assertEquals(64, placed.inputs.getValue("reachable loot").length)
            assertEquals(measured.id, placed.gates.getValue("reachable loot").getValue("traversal").jobId)

            // A new measurement that still passes is new evidence: the earlier placement is stale.
            level.set("L1=10,L3=28,L7=30")
            builds.build("traversal")
            assertNotNull(builds.state("placement").fingerprint)
            assertNull(builds.state("placement").current)
            assertEquals("Inputs or recipe changed; rebuild this stage.", builds.state("placement").reason)
        }
    }

    @Test fun aQueuedBuildChecksItsGateAgainWhenItStarts() {
        val budget = AtomicReference("30")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val ran = AtomicBoolean(false)
        val slow = StudyBuildStage("slow", "Slow", "v1", setOf("budget")) { c ->
            entered.countDown(); release.await(5, TimeUnit.SECONDS)
            c.file("a").writeText("a"); StudyBuildProduct(listOf("a"))
        }
        val withinBudget = StudyBuildGate("within budget", setOf("budget")) { evidence ->
            if (evidence.inputs.getValue("budget").toInt() >= 20) null else "The budget is below 20 steps."
        }
        val gated = StudyBuildStage("gated", "Gated", "v1", setOf("within budget")) { c ->
            ran.set(true); c.file("b").writeText("b"); StudyBuildProduct(listOf("b"))
        }
        StudyBuilds(temp(), listOf(slow, gated), { mapOf("budget" to budget.get()) }, workers = 1, gates = listOf(withinBudget)).use { builds ->
            val first = builds.startCurrent("slow"); entered.arrives()
            val queued = builds.startCurrent("gated")
            assertEquals(StudyBuildStatus.QUEUED, builds.job(queued.id).status)
            budget.set("10")
            release.countDown(); builds.done(first.id)
            val refused = builds.done(queued.id)
            assertEquals(StudyBuildStatus.FAILED, refused.status)
            assertEquals("gate-closed", refused.problem?.code)
            assertContains(refused.problem!!.message, "The budget is below 20 steps.")
            assertFalse(ran.get(), "the recipe ran although its gate had closed")
            // The card sets the failure aside: the stage now waits for its gate again.
            assertEquals("Waiting for within budget", builds.state("gated").inputHelp?.label)
        }
    }

    @Test fun aGateWhoseCheckThrowsStaysClosedAndSaysSo() {
        val broken = StudyBuildGate("checked", dependencies = listOf("traversal")) { error("report is unreadable") }
        val gated = StudyBuildStage("gated", "Gated", "v1", setOf("checked")) { error("must not run") }
        StudyBuilds(temp(), listOf(traversal, gated), { mapOf("level" to "L1=1") }, gates = listOf(broken)).use { builds ->
            builds.build("traversal")
            assertNull(builds.state("gated").fingerprint)
            assertContains(builds.state("gated").inputHelp!!.message, "report is unreadable")
        }
    }

    @Test fun aGateReadsVerifiedResultFiles() {
        val clear = StudyBuildGate("clear paths", dependencies = listOf("traversal")) { evidence ->
            if ("blocked" in evidence.file("traversal", "paths.txt").readText()) "A path is blocked." else null
        }
        val gated = StudyBuildStage("gated", "Gated", "v1", setOf("clear paths")) { c -> c.file("x").writeText("x"); StudyBuildProduct(listOf("x")) }
        StudyBuilds(temp(), listOf(traversal, gated), { mapOf("level" to "L1=1") }, gates = listOf(clear)).use { builds ->
            val measured = builds.build("traversal")
            assertNotNull(builds.state("gated").fingerprint)
            // A result file changed after its build makes the upstream stale, so the gate cannot open on it.
            java.io.File(builds.directory, "attempts/${measured.id}/paths.txt").writeText("blocked")
            assertNull(builds.state("gated").fingerprint)
            assertEquals(listOf("traversal"), builds.state("gated").blockedBy)
        }
    }

    @Test fun gateDefinitionsRejectGatesOfGatesUnknownDependenciesAndCycles() {
        val open = StudyBuildGate("open") { null }
        assertFailsWith<IllegalArgumentException> {
            StudyBuilds(temp(), listOf(placement, traversal), { emptyMap() }, gates = listOf(reachable, reachable))
        }
        assertFailsWith<IllegalArgumentException> {
            StudyBuilds(temp(), listOf(traversal), { emptyMap() }, gates = listOf(open, StudyBuildGate("onGate", setOf("open")) { null }))
        }
        assertFailsWith<IllegalArgumentException> {
            StudyBuilds(temp(), listOf(traversal), { emptyMap() }, gates = listOf(StudyBuildGate("g", dependencies = listOf("absent")) { null }))
        }
        val loop = StudyBuildStage("loop", "Loop", "v1", setOf("of itself")) { error("never") }
        assertFailsWith<IllegalArgumentException> {
            StudyBuilds(temp(), listOf(loop), { emptyMap() }, gates = listOf(StudyBuildGate("of itself", dependencies = listOf("loop")) { null }))
        }
    }
}
