package afield.stdlib

import afield.stdlib.StudyBuildTestSupport.arrives
import afield.stdlib.StudyBuildTestSupport.startCurrent
import afield.stdlib.StudyBuildTestSupport.temp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class StudyBuildExclusiveTest {
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val slow = StudyBuildStage("route", "Route services", "v1") { c ->
        entered.countDown(); release.await(5, TimeUnit.SECONDS)
        c.file("a").writeText("a"); StudyBuildProduct(listOf("a"))
    }
    private val other = StudyBuildStage("cut", "Cut panels", "v1") { c -> c.file("b").writeText("b"); StudyBuildProduct(listOf("b")) }

    @Test fun anExclusiveRunnerRefusesASecondBuildWhileOneRuns() {
        StudyBuilds(temp(), listOf(slow, other), { emptyMap() }, exclusive = true).use { builds ->
            val first = builds.startCurrent("route"); entered.arrives()
            val refused = assertFailsWith<IllegalStateException> { builds.startCurrent("cut") }
            assertContains(refused.message!!, "one stage at a time, and 'Route services' is still running")
            assertEquals(1, builds.history().size)
            val card = studyBuildCardHtml("card", "services", builds.stages(), emptyList(), exclusive = true) { stage, action, _, _ -> "[$stage-$action]" }
            assertContains(card, "[route-cancel]")
            assertFalse(card.contains("[cut-build]"), "the card offers a build the runner would refuse")
            assertContains(card, "One build at a time: this can start when Route services finishes.")
            release.countDown()
            assertEquals(StudyBuildStatus.SUCCEEDED, builds.await(first.id).status)
            assertEquals(StudyBuildStatus.SUCCEEDED, builds.await(builds.startCurrent("cut").id).status)
        }
    }

    @Test fun anOrdinaryRunnerBuildsDifferentStagesTogether() {
        StudyBuilds(temp(), listOf(slow, other), { emptyMap() }).use { builds ->
            val first = builds.startCurrent("route"); entered.arrives()
            assertEquals(StudyBuildStatus.SUCCEEDED, builds.await(builds.startCurrent("cut").id).status)
            assertContains(studyBuildCardHtml("card", "services", builds.stages(), emptyList()) { stage, action, _, _ -> "[$stage-$action]" }, "[cut-build]")
            release.countDown(); builds.await(first.id)
        }
    }

    @Test fun awaitReturnsWhenTheBuildEndsOrTheTimeoutPasses() {
        StudyBuilds(temp(), listOf(slow), { emptyMap() }).use { builds ->
            val job = builds.startCurrent("route"); entered.arrives()
            val started = System.nanoTime()
            assertEquals(StudyBuildStatus.RUNNING, builds.await(job.id, 0.2).status)
            assertTrue(System.nanoTime() - started >= 150_000_000)
            release.countDown()
            assertEquals(StudyBuildStatus.SUCCEEDED, builds.await(job.id).status)
            assertFailsWith<IllegalArgumentException> { builds.await(job.id, -1.0) }
        }
    }
}
