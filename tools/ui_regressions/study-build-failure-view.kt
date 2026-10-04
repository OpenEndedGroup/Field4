// A real StudyBuild failure that names where it fails: this box, whose "plan" panel draws the failing
// wall in red. The card's "Show where it fails" must open that panel, not this box's code.
display("plan", "<div class=\"failure-view-plan\" style=\"padding:12px;color:#ff6969;font:14px/1.45 monospace\">W3 crosses the stair opening</div>")
displayClose("plan")
val builds = studyBuilds("failure-view", listOf(
    StudyBuildStage("layout", "House layout", "v1", setOf("plan")) {
        throw StudyBuildFailure(StudyBuildProblem("stair-clash", "Wall W3 crosses the stair opening",
            listOf("Move the stair or shorten W3"),
            details = mapOf("geometryView" to mapOf("box" to thisBox.id, "caption" to "W3 and the stair opening"))))
    }
)) { mapOf("plan" to "stair at 3") }
showStudyBuilds("failure-view")
builds.start("layout", builds.stages().single().fingerprint!!)
println("STUDY_BUILD_FAILURE_VIEW_READY")
