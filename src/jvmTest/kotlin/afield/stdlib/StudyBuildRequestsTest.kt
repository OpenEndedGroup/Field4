package afield.stdlib

import afield.stdlib.StudyBuildTestSupport.build
import afield.stdlib.StudyBuildTestSupport.state
import afield.stdlib.StudyBuildTestSupport.temp
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class StudyBuildRequestsTest {
    // A stage that searches for a while; a request may give it a budget, which is not part of the design.
    private val search = StudyBuildStage("search", "Search", "search-v1", setOf("shape")) { c ->
        val budget = (c.request["budgetSeconds"] as? Number)?.toInt() ?: 5
        c.file("result.txt").writeText("${c.inputs.getValue("shape")} searched for $budget s")
        StudyBuildProduct(listOf("result.txt"), mapOf("budgetSeconds" to budget))
    }
    private val report = StudyBuildStage("report", "Report", "report-v1", dependencies = listOf("search")) { c ->
        c.file("report.txt").writeText(c.dependencyFile("search", "result.txt").readText())
        StudyBuildProduct(listOf("report.txt"))
    }

    @Test fun aRequestBelongsToOneAttemptAndIsPartOfItsFingerprint() {
        val root = temp()
        val asked: StudyBuildRecord
        StudyBuilds(root, listOf(search, report), { mapOf("shape" to "ring") }).use { builds ->
            val stageFingerprint = builds.state("search").fingerprint!!
            asked = builds.build("search", mapOf("budgetSeconds" to 60))
            assertEquals(mapOf<String, Any?>("budgetSeconds" to 60), asked.request)
            assertEquals(60, asked.facts["budgetSeconds"])
            assertNotEquals(stageFingerprint, asked.fingerprint)
            // Asked for more than the design, the result is still the current one for that design.
            assertEquals(stageFingerprint, builds.state("search").fingerprint)
            assertEquals(asked.id, builds.state("search").current?.id)
            assertFalse(studyBuildUsedEarlierInputs(builds.state("search"), asked))
            // Downstream stages fingerprint the requested attempt, request included.
            val reported = builds.build("report")
            assertEquals(asked.fingerprint, reported.dependencies.getValue("search").fingerprint)

            // The next start does not inherit the request.
            val plain = builds.build("search")
            assertTrue(plain.request.isEmpty())
            assertEquals(stageFingerprint, plain.fingerprint)
            assertEquals(5, plain.facts["budgetSeconds"])
            assertNull(builds.state("report").current)
        }
        StudyBuilds(root, listOf(search, report), { mapOf("shape" to "ring") }).use { reopened ->
            assertEquals(asked, reopened.job(asked.id))
            assertEquals("ring searched for 60 s", java.io.File(reopened.directory, "attempts/${asked.id}/result.txt").readText())
        }
    }

    @Test fun aRequestedResultGoesStaleWithItsDesign() {
        val shape = AtomicReference("ring")
        StudyBuilds(temp(), listOf(search), { mapOf("shape" to shape.get()) }).use { builds ->
            val asked = builds.build("search", mapOf("budgetSeconds" to 60))
            shape.set("star")
            assertNull(builds.state("search").current)
            assertTrue(studyBuildUsedEarlierInputs(builds.state("search"), asked))
            assertEquals("Inputs or recipe changed; rebuild this stage.", builds.state("search").reason)
            shape.set("ring")
            assertEquals(asked.id, builds.state("search").current?.id)
        }
    }

    @Test fun requestsAreFrozenAndBounded() {
        StudyBuilds(temp(), listOf(search), { mapOf("shape" to "ring") }).use { builds ->
            val fingerprint = builds.state("search").fingerprint!!
            val same = listOf(mapOf("a" to 1, "b" to listOf(1, 2)), mapOf("b" to listOf(1, 2), "a" to 1))
                .map { studyBuildFingerprint(fingerprint, it) }
            assertEquals(same[0], same[1], "key order must not change an attempt's fingerprint")
            assertFailsWith<IllegalArgumentException> { builds.start("search", fingerprint, mapOf("huge" to "x".repeat(70_000))) }
            assertTrue(builds.history().isEmpty())
            assertFailsWith<IllegalStateException> { builds.start("search", "stale", mapOf("budgetSeconds" to 1)) }
        }
    }
}
