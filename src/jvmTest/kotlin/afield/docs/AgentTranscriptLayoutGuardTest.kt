package afield.docs

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentTranscriptLayoutGuardTest {
    @Test
    fun compactPositiveFramePasses() {
        val repo = fixture(
            transcript = transcript("compact", 40.0, 40.0, 340.0, 100.0),
        )

        val result = AgentTranscriptLayoutGuard.audit(repo)

        assertEquals(emptyList(), result.problems)
        assertEquals(1, result.frames)
        assertEquals(0, result.largeFrames)
    }

    @Test
    fun negativeAndUnreviewedLargeFramesFailWithActionableMessages() {
        val repo = fixture(
            transcript = transcript("oversized", -20.0, 40.0, 1000.0, 1000.0),
        )

        val problems = AgentTranscriptLayoutGuard.audit(repo).problems

        assertTrue(problems.any { "negative position" in it }, problems.joinToString("\n"))
        assertTrue(problems.any { "exceeds the ordinary 620 x 170 ceiling" in it }, problems.joinToString("\n"))
    }

    @Test
    fun reviewedLargeFramePassesAndStaleExceptionFails() {
        val accepted = fixture(
            transcript = transcript("dashboard", 40.0, 40.0, 1040.0, 700.0),
            exceptions = "example.yaml#dashboard: Wide dashboard card holds a deliberately composed visual surface.",
        )
        assertEquals(emptyList(), AgentTranscriptLayoutGuard.audit(accepted).problems)

        val stale = fixture(
            transcript = transcript("dashboard", 40.0, 40.0, 340.0, 100.0),
            exceptions = "example.yaml#dashboard: Wide dashboard card holds a deliberately composed visual surface.",
        )
        val problems = AgentTranscriptLayoutGuard.audit(stale).problems
        assertTrue(problems.any { "no longer large" in it }, problems.joinToString("\n"))
    }

    @Test
    fun missingFrameIsRejectedBecauseItCreatesAnInvisibleBox() {
        val repo = fixture(
            transcript = """
                name: missing_frame
                operations:
                  - create:
                      box: invisible
                      name: Invisible
            """.trimIndent(),
        )

        val problems = AgentTranscriptLayoutGuard.audit(repo).problems

        assertTrue(problems.any { "invisible Rect(0,0,0,0)" in it }, problems.joinToString("\n"))
    }

    // ------------------------------------------------------- the demo half of the same corpus

    /**
     * The ceiling in the message is enforced over saved demo documents too, and this is the proof.
     *
     * Until 2026-09-20 every test in this file wrote a `transcripts/` fixture and nothing else, so
     * `parseDemoFrames` — half the population, and the half the 2026-09-18 `700 x 900` escape came
     * out of — had no coverage at all. The guard was widened on 2026-09-05 and then believed,
     * which is the thing this branch exists to stop doing.
     */
    @Test
    fun anOversizedBoxInASavedDemoDocumentIsHeldToTheSameCeiling() {
        val repo = fixture(
            transcript = transcript("compact", 40.0, 40.0, 340.0, 100.0),
            demo = demoDocument(demoBox("wide_panel", 1180.0, 900.0)),
        )

        val result = AgentTranscriptLayoutGuard.audit(repo)

        assertTrue(
            result.problems.any {
                "example_demo/metadata.edn box 'wide_panel'" in it &&
                    "exceeds the ordinary 620 x 170 ceiling" in it
            },
            report(result),
        )
        assertEquals(1, result.demos)
        assertEquals(2, result.frames, "one transcript create plus one demo box")
    }

    /** And the same registry grandfathers a demo box, keyed on `<document>/metadata.edn#<box>`. */
    @Test
    fun aReviewedDemoBoxPasses() {
        val repo = fixture(
            transcript = transcript("compact", 40.0, 40.0, 340.0, 100.0),
            exceptions = "example_demo/metadata.edn#wide_panel: Saved demo document; the box holds " +
                "the eight-term table it prints, which is 80 columns wide.",
            demo = demoDocument(demoBox("wide_panel", 1180.0, 900.0)),
        )

        assertEquals(emptyList(), AgentTranscriptLayoutGuard.audit(repo).problems)
    }

    /**
     * A demo box with no rectangle is reported, rather than eating the next box's.
     *
     * The spanning regex this replaced matched `"id" … #afield/rect` non-greedily across the whole
     * document, so `no_frame` below would have been recorded with `1180 x 900` and `wide_panel`
     * would not have been seen at all: one omission, two boxes wrong, silently. The YAML half has
     * rejected exactly this omission since the guard was written.
     */
    @Test
    fun aDemoBoxWithNoFrameIsReportedInsteadOfStealingTheNextBoxesRectangle() {
        val repo = fixture(
            transcript = transcript("compact", 40.0, 40.0, 340.0, 100.0),
            demo = demoDocument(
                "no_frame" to """"name" "No frame"""",
                demoBox("wide_panel", 1180.0, 900.0),
            ),
        )

        val result = AgentTranscriptLayoutGuard.audit(repo)

        assertTrue(result.problems.any { "box 'no_frame' has no" in it }, report(result))
        assertTrue(
            result.problems.any { "box 'wide_panel'" in it && "exceeds the ordinary" in it },
            "the box after the frameless one must still be audited. " + report(result),
        )
    }

    /**
     * A corpus that has moved is a failure, not a smaller run.
     *
     * `parseDemoFrames` used to return an empty list when `dev_docs/agents/demos` was absent, so
     * renaming or relocating the directory would have taken the demo half of the population to
     * zero and still printed `ok`.
     */
    @Test
    fun aMissingDemoCorpusFailsRatherThanShrinkingThePopulation() {
        val repo = fixture(transcript = transcript("compact", 40.0, 40.0, 340.0, 100.0))
        File(repo, "dev_docs/agents/demos").delete()

        val result = AgentTranscriptLayoutGuard.audit(repo)

        assertTrue(result.problems.any { "no demo corpus at" in it }, report(result))
    }

    /**
     * What the audit actually saw, for a message that would otherwise be the empty string.
     *
     * `assertTrue(problems.any { … }, problems.joinToString())` reports *nothing at all* when the
     * list is empty, which is precisely the case where a guard has stopped looking. An assertion
     * about a population has to print the population or it reproduces the bug it is testing for.
     */
    private fun report(result: AgentTranscriptLayoutGuard.AuditResult): String =
        "no problem matched. Population: ${result.transcripts} transcript(s), ${result.demos} demo " +
            "document(s), ${result.frames} frame(s), ${result.largeFrames} large. Problems:\n" +
            result.problems.joinToString("\n").ifBlank { "  (none)" }

    private fun fixture(transcript: String, exceptions: String = "", demo: String? = null): File {
        val repo = Files.createTempDirectory("transcript-layout-guard").toFile()
        val corpus = File(repo, "dev_docs/agents/transcripts").also { it.mkdirs() }
        File(corpus, "example.yaml").writeText(transcript + "\n")
        val demos = File(repo, "dev_docs/agents/demos").also { it.mkdirs() }
        if (demo != null) {
            File(demos, "example_demo").also { it.mkdirs() }
                .let { File(it, "metadata.edn").writeText(demo + "\n") }
        }
        File(repo, "dev_docs/agents/transcript_layout_exceptions.yaml").writeText(
            "version: 1\nlargeFrames:\n" +
                if (exceptions.isBlank()) "  {}\n" else "  $exceptions\n"
        )
        return repo
    }

    private fun transcript(box: String, x: Double, y: Double, width: Double, height: Double): String = """
        name: example
        operations:
          - create:
              box: $box
              name: Example
              frame: { x: $x, y: $y, w: $width, h: $height }
    """.trimIndent()

    /**
     * One saved document, in the shape [AgentTranscriptLayoutGuard.parseDemoFrames] actually reads.
     *
     * `root` last and frameless, because that is what every one of the twenty shipped
     * `metadata.edn` files looks like: the guard must treat it as the container it is and not as a
     * box that forgot its rectangle.
     */
    private fun demoDocument(vararg boxes: Pair<String, String>): String {
        val body = boxes.joinToString(" ") { (id, props) -> """{"id" "$id" "properties" {$props}}""" }
        return """{"version" 2 "boxes" [$body {"id" "root" "properties" {"name" "root"}}]}"""
    }

    private fun demoBox(id: String, width: Double, height: Double): Pair<String, String> =
        id to """"name" "$id" "frame" #afield/rect [40.0 40.0 $width $height]"""
}
