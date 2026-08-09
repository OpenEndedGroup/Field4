package afield

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionDocPolishRunStoreTest {
    @Test
    fun `persists complete trace and restores bounded latest tail`() {
        val root = Files.createTempDirectory("completion-doc-polish-runs-")
        val store = CompletionDocPolishRunStore(root, maxLogChars = 32)
        val started = 1_754_670_000_123L
        val runId = "12345678-abcd-4321-abcd-1234567890ab"
        val logPath = store.newLogPath(started, runId)
        val trace = "header\n" + "αβγδ".repeat(20) + "\nfinished\n"

        store.append(logPath.toString(), trace)
        store.save(
            PersistedCompletionDocPolishRun(
                running = false,
                status = "succeeded",
                runId = runId,
                taskName = "polishCompletionDocs",
                startedAtEpochMs = started,
                finishedAtEpochMs = started + 1000,
                exitCode = 0,
                message = "polishCompletionDocs finished.",
                logPath = logPath.toString()
            )
        )

        val restored = CompletionDocPolishRunStore(root, maxLogChars = 32).restore()
            ?: error("persisted run did not restore")
        assertEquals("succeeded", restored.state.status)
        assertEquals(logPath.toString(), restored.state.logPath)
        assertEquals(trace.takeLast(32), restored.logTail)
        assertEquals(trace, logPath.readText(), "the durable trace must not be truncated with the UI tail")
        assertTrue(Files.isRegularFile(root.resolve("latest.json")))
        assertFalse(Files.list(root).use { paths -> paths.anyMatch { it.fileName.toString().endsWith(".tmp") } })
    }

    @Test
    fun `will not append through a persisted path outside the run directory`() {
        val root = Files.createTempDirectory("completion-doc-polish-safe-path-")
        val outside = Files.createTempFile("completion-doc-polish-outside-", ".log")
        val store = CompletionDocPolishRunStore(root, maxLogChars = 100)

        assertFailsWith<IllegalStateException> {
            store.append(outside.toString(), "must not escape")
        }
        assertEquals("", outside.readText())
    }
}
