package afield.ai.memory

import afield.ai.models.LlmBridge
import afield.ai.models.LlmSendResult
import afield.ai.models.LlmThreadState
import afield.ai.models.ScriptedLlmBridge
import afield.ai.models.ScriptedLlmBridge.Step
import afield.ai.tools.ToolExecutionContext
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * A dream pass: which turns it reads, what it is told, that its changes land as one commit with a log, that the
 * watermark moves only after a pass that finished, and when an idle pass is due.
 */
class DreamingTest {
    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    private fun q(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()

    /** A workspace with one document whose chat has [turns] (request, reply, saved-at) on one branch. */
    private fun workspace(root: File, turns: List<Triple<String, String, Long>>): File {
        val ws = File(root, "ws")
        val doc = File(ws, "sketch").apply { mkdirs() }
        val box = "b1"
        File(doc, "metadata.edn").writeText(
            """{:version 2 :boxes [{"id" "root" "properties" {"name" "root"}} {"id" "$box" "properties" {"name" "Chat" "ai4_chat_history" #afield/file "${box}_ai4_chat_history_.md" "ai4_session" #afield/file "${box}_ai4_session_.jsonl"}}]}"""
        )
        val lines = mutableListOf("""{"t":"header","v":1}""")
        var parent = "null"
        val transcript = StringBuilder()
        turns.forEachIndexed { i, (asked, answered, ts) ->
            val md = "<<you>>\n$asked\n<<ai>>\n$answered\n"
            transcript.append(md)
            lines += """{"t":"turn","id":"t-$i","parent":$parent,"ts":$ts,"markdown":${q(md)},"user":${q(asked)}}"""
            parent = "\"t-$i\""
        }
        lines += """{"t":"head","id":"t-${turns.size - 1}","renderedSha":"x"}"""
        File(doc, "${box}_ai4_session_.jsonl").writeText(lines.joinToString("\n"))
        File(doc, "${box}_ai4_chat_history_.md").writeText(transcript.toString())
        return ws
    }

    private fun <T> temp(block: (File) -> T): T {
        val dir = Files.createTempDirectory("ai4-dream-test").toFile()
        try { return block(dir) } finally { dir.deleteRecursively() }
    }

    private fun gitLog(dir: File): List<String> {
        val p = ProcessBuilder("git", "log", "--format=%s").directory(dir).redirectErrorStream(true)
            .also { it.environment().keys.removeIf { k -> k.startsWith("GIT_") } }.start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(5, TimeUnit.SECONDS)
        return if (p.exitValue() == 0) out.lines() else emptyList()
    }

    private val hasGit = runCatching { ProcessBuilder("git", "--version").start().waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)

    @Test fun aPassReadsTheNewTurnsAndLandsAsOneCommitWithItsLog() = temp { root ->
        val ws = workspace(root, listOf(
            Triple("label sliders with units please", "Will do.", now - 10 * 24 * hour), // before the first look-back
            Triple("no: brackets, like speed [Hz]", "Understood, brackets.", now - 3 * hour),
            Triple("draw the wave", "Done.", now - 2 * hour),
        ))
        val memory = UserMemory(File(root, "memory"))
        memory.write("slider-units", "Label sliders with units", "Use parentheses.", "person", "unconfirmed", emptyList(), "")
        val history = ChatHistory.forWorkspace(ws)

        val bridge = ScriptedLlmBridge(listOf(
            Step.Call("HistorySearchRequest", """{"query":"brackets"}"""),
            Step.Call("MemoryReadRequest", """{"name":"slider-units"}"""),
            Step.Call("MemoryWriteRequest", """{"name":"slider-units","description":"Label sliders with units in square brackets","body":"Brackets, e.g. speed [Hz].","kind":"person","status":"verified","sources":["sketch@t-1.b0"],"revision":"${'$'}{MemoryReadRequest.revision}"}"""),
            Step.Call("MemoryWriteRequest", """{"name":"wave-drawn","description":"A wave was drawn","body":"b","kind":"project"}"""),
            Step.Call("MemoryDeleteRequest", """{"name":"wave-drawn","reason":"document progress, not memory"}"""),
            Step.Final("Corrected slider-units from parentheses to brackets, citing the correction."),
        ))
        val report = Dreaming.run(memory, history, bridge, "test", now = now)
        assertTrue(report.ok, report.message)
        assertEquals(2, report.turnsRead, "the first look-back skips the ten-day-old turn")
        assertEquals(listOf("slider-units"), report.updated)
        assertEquals(emptyList(), report.created)

        val note = memory.read("slider-units")!!
        assertEquals("verified", note.status)
        assertEquals(listOf("sketch@t-1.b0"), note.sources)

        // What the model was given: the dreaming instructions, the turns as citations, and only memory and history tools.
        val got = bridge.received.single()
        assertEquals(Dreaming.INSTRUCTIONS, got.instructions)
        assertContains(got.prompt, "sketch@t-1.b0")
        assertContains(got.prompt, "no: brackets, like speed [Hz]")
        assertFalse(got.prompt.contains("label sliders with units please"))
        assertTrue(got.availableTools.containsAll(listOf("MemoryWriteRequest", "HistoryReadRequest")))
        assertTrue(got.availableTools.none { it.startsWith("NewBox") || it.startsWith("RunBox") || it.startsWith("DocumentGuide") },
            "a dream touches memory only: ${got.availableTools}")

        val log = File(report.log).readText()
        assertContains(log, "- updated: slider-units")
        assertContains(log, "Corrected slider-units")
        assertTrue(File(report.log).parentFile.name == Dreaming.LOG_DIR)
        assertFalse(memory.notes().any { it.name.startsWith("20") }, "a dream log is not a note")
        if (hasGit) {
            val commits = gitLog(memory.dir)
            assertEquals(2, commits.size, "the note's creation, then the whole pass as one commit: $commits")
            assertContains(commits.first(), "dream ")
            assertContains(commits.first(), "0 created, 1 updated, 0 deleted from 2 turn(s)")
        }

        // The watermark moved: the same turns are not read again.
        val again = Dreaming.run(memory, history, ScriptedLlmBridge(listOf(Step.Final("nothing"))), "test", now = now + hour)
        assertTrue(again.ok)
        assertContains(again.message, "Nothing to consolidate")
        assertEquals(".dream-state.json\n", File(memory.dir, ".gitignore").readText())
    }

    @Test fun aFailedPassLeavesTheWatermarkSoTheTurnsAreReadAgain() = temp { root ->
        val ws = workspace(root, listOf(Triple("remember tabs not spaces", "OK.", now - hour)))
        val memory = UserMemory(File(root, "memory"))
        val history = ChatHistory.forWorkspace(ws)
        val broken = object : LlmBridge {
            override fun startThread(fileKey: String, fileText: String, instructions: String): LlmThreadState =
                ScriptedLlmBridge(emptyList()).startThread(fileKey, fileText, instructions)
            override fun send(state: LlmThreadState, userPrompt: String, fileText: String?, ctx: ToolExecutionContext,
                              vectorStoreIds: List<String>, maxOutputTokens: Long, maxToolRounds: Int, imageDataUrls: List<String>,
                              onTextDelta: ((String) -> Unit)?, onProgress: ((afield.ai.models.LlmProgressUpdate) -> Unit)?,
                              turnContext: afield.ai.conversation.TurnContext?): LlmSendResult = throw IllegalStateException("no API key")
        }
        val failed = Dreaming.run(memory, history, broken, "test", now = now)
        assertFalse(failed.ok)
        assertContains(failed.message, "no API key")
        assertEquals(1, Dreaming.pending(memory, history, now).second.size)
    }

    @Test fun anIdlePassWaitsForQuietEnoughTurnsAndTheInterval() = temp { root ->
        val ws = workspace(root, listOf(
            Triple("a", "x", now - 3 * hour), Triple("b", "x", now - 2 * hour), Triple("c", "x", now - 10 * 60_000L),
        ))
        val memory = UserMemory(File(root, "memory"))
        val history = ChatHistory.forWorkspace(ws)
        assertFalse(Dreaming.idleDue(memory, history, now), "the newest turn is ten minutes old: the person may still be working")
        assertTrue(Dreaming.idleDue(memory, history, now + Dreaming.IDLE_QUIET_MS))
        Dreaming.run(memory, history, ScriptedLlmBridge(listOf(Step.Final("ok"))), "test", now = now + Dreaming.IDLE_QUIET_MS)
        assertFalse(Dreaming.idleDue(memory, history, now + Dreaming.IDLE_QUIET_MS + hour), "read already, and too soon after the last pass")
    }
}
