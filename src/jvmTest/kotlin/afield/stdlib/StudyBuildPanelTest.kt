package afield.stdlib

import kotlin.test.*

class StudyBuildPanelTest {
    @Test fun longPhaseShowsElapsedTimeWithoutInventingAnEstimate() {
        val job = StudyBuildRecord("attempt", "mesh", "v1", "hash", emptyMap(), emptyMap(),
            created = "2026-10-03T09:00:00Z", status = StudyBuildStatus.RUNNING, progress = .1)
        assertEquals("Elapsed 2m 5s · progress reports completed work, not time remaining",
            studyBuildElapsedText(job, java.time.Instant.parse("2026-10-03T09:02:05Z")))
        assertContains(studyBuildElapsedText(job, java.time.Instant.parse("2026-10-03T08:59:00Z")), "0m 0s")
    }
    @Test fun currentResultCanNeedReviewWithoutClaimingTheBuildFailed() {
        val review = listOf(StudyBuildNotice("2 contacts remain. Inspect <report> & repair."))
        assertEquals("Current · Needs review", studyBuildCurrentLabel(review))
        assertEquals("Current", studyBuildCurrentLabel(listOf(StudyBuildNotice("Inspection pending", StudyBuildNoticeKind.INFO))))
        val correction = listOf(StudyBuildNotice("Cannot release", StudyBuildNoticeKind.ERROR))
        assertEquals("Current · Needs correction", studyBuildCurrentLabel(correction))
        val html = studyBuildResultNotesHtml(review, previous = true)
        assertContains(html, "Previous current result")
        assertContains(html, "&lt;report&gt; &amp; repair.")
        assertFalse(html.contains("<report>"))
        assertContains(studyBuildResultNotesHtml(correction), "study-build-error")
        assertEquals("", studyBuildResultNotesHtml(emptyList()))
    }

    @Test fun failedAttemptWithoutGeometryStillHasSelectableDiagnosticEvidence() {
        val job = StudyBuildRecord("attempt-1", "layout", "v1", "hash", emptyMap(), emptyMap(),
            status = StudyBuildStatus.FAILED,
            problem = StudyBuildProblem("unsupported-check", "Recipe returned the wrong result", listOf("Check recipe capabilities"),
                mapOf("expected" to "layout", "actual" to "panels", "unsafe" to "<script>")))
        val html = studyBuildDiagnosticHtml(job, false)
        assertContains(html, "Inspect failure details")
        assertContains(html, "no geometry location")
        assertContains(html, "attempt-1")
        assertContains(html, "Check recipe capabilities")
        assertContains(html, "&lt;script&gt;")
        assertFalse(html.contains("<script>"))
        assertContains(studyBuildDiagnosticHtml(job, true), "finding links above")
        assertEquals("", studyBuildDiagnosticHtml(job.copy(problem = null), false))
    }
}
