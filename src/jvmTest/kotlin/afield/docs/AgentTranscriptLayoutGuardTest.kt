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
            transcript = transcript("compact", 40.0, 40.0, 240.0, 50.0),
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
        assertTrue(problems.any { "exceeds the ordinary 520 x 120 ceiling" in it }, problems.joinToString("\n"))
    }

    @Test
    fun reviewedLargeFramePassesAndStaleExceptionFails() {
        val accepted = fixture(
            transcript = transcript("dashboard", 40.0, 40.0, 1040.0, 700.0),
            exceptions = "example.yaml#dashboard: Wide dashboard card holds a deliberately composed visual surface.",
        )
        assertEquals(emptyList(), AgentTranscriptLayoutGuard.audit(accepted).problems)

        val stale = fixture(
            transcript = transcript("dashboard", 40.0, 40.0, 240.0, 50.0),
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

    private fun fixture(transcript: String, exceptions: String = ""): File {
        val repo = Files.createTempDirectory("transcript-layout-guard").toFile()
        val corpus = File(repo, "dev_docs/agents/transcripts").also { it.mkdirs() }
        File(corpus, "example.yaml").writeText(transcript + "\n")
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
}
