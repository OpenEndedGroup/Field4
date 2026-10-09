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

    @Test fun aStageWaitingOnAnotherRunnerOrAGateSaysWhatItWaitsFor() {
        val acrossRunners = StudyStageState("place", "Place loot", null, null, null,
            "Build current dependencies first: level/blockout (Not built yet)", listOf("level/blockout"))
        val html = studyBuildStageHtml(acrossRunners, listOf(acrossRunners), emptyList())
        assertContains(html, "Waiting for an earlier stage")
        assertContains(html, "Complete level/blockout first.")
        val gated = StudyStageState("place", "Place loot", null, null, null, "Waiting for reachable loot: L3 is out of reach.",
            inputHelp = StudyBuildInputHelp("Waiting for reachable loot", "L3 is out of reach."))
        val waiting = studyBuildStageHtml(gated, listOf(gated), emptyList()) { _, _, _, _ -> "<button>" }
        assertContains(waiting, "Waiting for reachable loot</span>")
        assertContains(waiting, "L3 is out of reach.")
        assertFalse(waiting.contains("<button>"), "a waiting stage offers no Build button")
    }

    @Test fun theCardShowsWhatTheLatestAttemptWasAskedFor() {
        val asked = StudyBuildRecord("attempt", "search", "v1", "fp", emptyMap(), emptyMap(), status = StudyBuildStatus.SUCCEEDED,
            request = mapOf("resume" to "<job>", "budgetSeconds" to 60))
        val html = studyBuildStageHtml(StudyStageState("search", "Search", "fp", null, asked, null), emptyList(), emptyList())
        assertContains(html, "Request: resume = &lt;job&gt;, budgetSeconds = 60")
        assertFalse(studyBuildStageHtml(StudyStageState("search", "Search", "fp", null, asked.copy(request = emptyMap()), null),
            emptyList(), emptyList()).contains("Request:"))
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

    private fun finding(run: String, kind: String, text: String, status: String = "open", live: Boolean = true) =
        StudyBuildFinding(run, "note-$kind", "view-1", "source-box", "output-box", text, kind, status, 0, false, live)

    private fun failedState(fingerprint: String?, attemptFingerprint: String = "fp-now", details: Map<String, Any?> = emptyMap()) =
        StudyStageState("layout", "House layout", fingerprint, null,
            StudyBuildRecord("attempt-1", "layout", "v1", attemptFingerprint, emptyMap(), emptyMap(), status = StudyBuildStatus.FAILED,
                problem = StudyBuildProblem("overlap", "Two walls overlap", listOf("Move the stair"), details)),
            if (fingerprint == attemptFingerprint) "Two walls overlap" else "Inputs or recipe changed; rebuild this stage.")

    @Test fun errorFindingsAreRedLinksWhileDecisionsStayCyan() {
        val state = failedState("fp-now")
        val findings = listOf(finding("attempt-1", "error", "Wall <W3> crosses the stair"),
            finding("attempt-1", "warning", "Check the landing"), finding("attempt-1", "question", "Keep the skylight?"),
            finding("attempt-1", "error", "Old overlap", status = "resolved"), finding("other-attempt", "error", "Not this attempt"))
        val html = studyBuildCardHtml("card", "house", listOf(state), findings)
        assertContains(html, "Findings for this attempt · 3 open · 1 closed")
        val error = Regex("<a class=\"study-build-finding-error\"[^>]*>([^<]*)</a>").findAll(html).toList()
        assertEquals(listOf("Error · Wall &lt;W3&gt; crosses the stair"), error.map { it.groupValues[1] })
        assertContains(error.single().value, "href=\"#box:output-box\" data-box-uid=\"output-box\" data-review-view=\"view-1\" data-review-note=\"note-error\"")
        // Specificity (0,3,0) beats the cyan `.study-build-findings a` rule (0,2,1).
        assertContains(html, ".card .study-build-findings .study-build-finding-error{color:#ff6969}")
        assertContains(html, "<a class=\"study-build-finding-warning\"")
        assertContains(html, "<a class=\"study-build-finding-question\"")
        assertContains(html, "<a class=\"study-build-finding-resolved\"")
        assertContains(html, "Error · Old overlap · resolved")
        assertFalse(html.contains("Not this attempt"))
        assertContains(studyBuildFindingsHtml(state, listOf(finding("attempt-1", "error", "Unlaunched", live = false))),
            "<p class=\"study-build-state study-build-finding-error\">Error · Unlaunched · run the review source to open</p>")
    }

    @Test fun findingsOfAnAttemptWithEarlierInputsAreNotOpenErrors() {
        val dir = java.nio.file.Files.createTempDirectory("study-build-panel-").toFile()
        val shape = java.util.concurrent.atomic.AtomicReference("stair at 3")
        val stage = StudyBuildStage("layout", "House layout", "v1", setOf("shape")) {
            throw StudyBuildFailure(StudyBuildProblem("overlap", "Two walls overlap", listOf("Move the stair"),
                mapOf("geometryView" to mapOf("box" to "plan-box", "caption" to "Stair opening"))))
        }
        try {
            StudyBuilds(dir, listOf(stage), { mapOf("shape" to shape.get()) }).use { builds ->
                val job = builds.start("layout", builds.stages().single().fingerprint!!)
                val deadline = System.nanoTime() + 5_000_000_000L
                while (builds.job(job.id).status in setOf(StudyBuildStatus.QUEUED, StudyBuildStatus.RUNNING)) {
                    check(System.nanoTime() < deadline) { "Build never finished" }; Thread.sleep(5)
                }
                val findings = listOf(finding(job.id, "error", "Wall W3 crosses the stair"), finding(job.id, "warning", "Check the landing"))

                val failed = builds.stages().single()
                assertFalse(studyBuildLatestUsedEarlierInputs(failed))
                val current = studyBuildStageHtml(failed, listOf(failed), findings)
                assertContains(current, "Build failed")
                assertContains(current, "2 open")
                assertContains(current, "study-build-finding-error")
                assertContains(current, "Show where it fails")

                shape.set("stair at 4")
                val changed = builds.stages().single()
                assertEquals(job.id, changed.latest?.id)
                assertTrue(studyBuildLatestUsedEarlierInputs(changed))
                val html = studyBuildStageHtml(changed, listOf(changed), findings)
                assertContains(html, "Needs rebuilding")
                assertContains(html, "<p class=\"study-build-muted\">2 findings from an attempt with earlier inputs; rebuild to recheck.</p>")
                assertFalse(html.contains("open ·"), html)
                assertFalse(html.contains("Wall W3"), html)
                assertFalse(html.contains("study-build-error"), html)
                assertFalse(html.contains("study-build-finding-"), html)
                assertFalse(html.contains("Show where it fails…</a>"), html)
                assertContains(html, "study-build-diagnostic-earlier")
                assertContains(html, "ran against earlier inputs")
                assertContains(studyBuildFindingsHtml(changed, findings.take(1)), "1 finding from an attempt with earlier inputs")
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun failedAttemptCanNameTheBoxThatShowsWhereItFails() {
        val view = mapOf("box" to "plan\"box", "caption" to "Walls <W3> & W4 overlap", "viewId" to "plan-view")
        val state = failedState("fp-now", details = mapOf("geometryView" to view))
        val job = state.latest!!
        val problem = job.problem!!
        assertEquals(StudyBuildGeometryView("plan\"box", "Walls <W3> & W4 overlap", "plan-view"), studyBuildGeometryView(problem))
        val link = studyBuildGeometryViewHtml(problem)
        assertEquals("<p class=\"study-build-geometry-view\"><a href=\"#box:plan&quot;box\" data-box-uid=\"plan&quot;box\" data-box-reveal=\"output\" data-review-view=\"plan-view\">Show where it fails…</a> · Walls &lt;W3&gt; &amp; W4 overlap</p>", link)
        assertFalse(link.contains("data-review-note"))

        val diagnostic = studyBuildDiagnosticHtml(job, false)
        assertFalse(diagnostic.contains("no geometry location"), diagnostic)
        assertContains(diagnostic, "The failing geometry is shown in red in the output of the box")

        val section = studyBuildStageHtml(state, listOf(state), emptyList())
        assertContains(section, "<p class=\"study-build-error\">Move the stair</p>$link")
        assertFalse(section.contains("no geometry location"), section)
        assertContains(studyBuildCardHtml("card", "house", listOf(state), emptyList()), ".card .study-build-geometry-view a{color:#ff6969;")

        val minimal = StudyBuildProblem("overlap", "Two walls overlap", details = mapOf("geometryView" to mapOf("box" to "plan-box")))
        assertEquals("<p class=\"study-build-geometry-view\"><a href=\"#box:plan-box\" data-box-uid=\"plan-box\" data-box-reveal=\"output\">Show where it fails…</a></p>", studyBuildGeometryViewHtml(minimal))
        for (bad in listOf(mapOf("caption" to "no box"), mapOf("box" to " "), mapOf("box" to 3), "plan-box"))
            assertEquals("", studyBuildGeometryViewHtml(minimal.copy(details = mapOf("geometryView" to bad))), "$bad")
    }
}
