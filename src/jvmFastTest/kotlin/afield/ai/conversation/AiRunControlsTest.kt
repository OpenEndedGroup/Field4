package afield.ai.conversation

import afield.ai.tools.ToolExecutionContext
import afield.ai.tools.ToolRegistry
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*
import kotlin.test.*

class AiRunControlsTest {
    private fun pending(text: String, next: Boolean = false) = AiTurnInbox.Pending(text, text, followUp = next)

    @Test fun followUpsNeverSteerAndCanBeRecalled() {
        val inbox = AiTurnInbox()
        val next = pending("explain afterwards", true)
        val steer = pending("use blue")
        assertTrue(inbox.offer(next)); assertTrue(inbox.offer(steer))
        assertEquals(listOf(steer), inbox.drainForModel())
        assertEquals(listOf(next), inbox.recall())
        assertTrue(inbox.close().isEmpty())
    }

    @Test fun stopReturnsDraftsAndRefusesFurtherToolsAndInput() {
        val inbox = AiTurnInbox()
        val message = pending("later", true)
        inbox.offer(message)
        var stopped = false
        inbox.onStop = { stopped = true }
        assertEquals(listOf(message), inbox.stop())
        assertTrue(stopped)
        assertFailsWith<CancellationException> { inbox.checkStopped() }
        assertFalse(inbox.offer(pending("too late")))
        assertTrue(inbox.close().isEmpty())
    }

    @Test fun stopStillCancelsAndReturnsDraftsWhenItsJournalCannotBeWritten() {
        val directory = Files.createTempDirectory("ai-stop-disk-failure").toFile()
        try {
            val file = directory.resolve("events.jsonl")
            val inbox = AiTurnInbox(AiRunJournal(file))
            val message = pending("keep this draft")
            inbox.offer(message)
            assertTrue(file.delete()); assertTrue(file.mkdir())
            var cancelled = false
            inbox.onStop = { cancelled = true }
            assertEquals(listOf(message), inbox.stop())
            assertTrue(cancelled)
            assertFailsWith<CancellationException> { inbox.checkStopped() }
        } finally { directory.deleteRecursively() }
    }

    @Test fun withdrawingOneMessageLeavesTheOthers() {
        val inbox = AiTurnInbox()
        val a = pending("a"); val b = pending("b")
        inbox.offer(a); inbox.offer(b)
        assertEquals(a, inbox.withdraw(a.id))
        assertNull(inbox.withdraw(a.id))
        assertEquals(listOf(b), inbox.drainForModel())
    }

    data class Verdict(val pass: Boolean = true)

    @Test fun registryRecordsNegativeVerdictsAndRefusalsAndStopPreventsSideEffects() {
        val directory = Files.createTempDirectory("ai-tool-journal").toFile()
        try {
            val file = AiRunJournal.file(directory.path, "box")
            val inbox = AiTurnInbox(AiRunJournal(file))
            val registry = ToolRegistry()
            var calls = 0
            registry.register(Verdict::class.java) { _, request -> calls++; mapOf("success" to request.pass) }
            val ctx = ToolExecutionContext(inbox = inbox)
            registry.executeByName(ctx, "Verdict", "{\"pass\":false}")
            assertContains(file.readText(), "\"success\":\"false\"")
            assertFails { registry.executeByName(ctx.copy(withheld = setOf("Verdict")), "Verdict", "{}") }
            assertContains(file.readText(), "tool.failed")
            inbox.stop()
            assertFailsWith<CancellationException> { registry.executeByName(ctx, "Verdict", "{}") }
            assertEquals(1, calls)
            assertFailsWith<IllegalArgumentException> { AiRunJournal.readSnapshot(directory.path, "../../private") }
        } finally { directory.deleteRecursively() }
    }

    @Test fun journalIsDurableNestedAndRetainsContextWithoutReplayingIt() {
        val directory = Files.createTempDirectory("ai-run-events").toFile()
        try {
            val file = AiRunJournal.file(directory.path, "../../box")
            val journal = AiRunJournal(file, "run")
            journal.tool("outer", "secret arguments") {
                journal.tool("inner", "{}") { 3 }
            }
            assertFailsWith<IllegalStateException> {
                journal.tool("failure", "{}") { error("private failure text") }
            }
            val events = file.readLines().map { Json.parseToJsonElement(it).jsonObject }
            val starts = events.filter { it["type"]?.jsonPrimitive?.content == "tool.started" }
            assertEquals(starts[0]["call"], starts[1]["parent"])
            assertEquals("tool.failed", events.last()["type"]?.jsonPrimitive?.content)
            assertFalse(file.readText().contains("secret arguments"))
            val hash = journal.snapshot("unabridged context")
            assertEquals(hash, journal.snapshot("unabridged context"))
            val restored = GZIPInputStream(file.parentFile.resolve("blobs/$hash.gz").inputStream()).bufferedReader().use { it.readText() }
            assertEquals("unabridged context", restored)
            assertContains(AiRunJournal.inspect(directory.path, "../../box"), "tool.completed")
        } finally { directory.deleteRecursively() }
    }
}
