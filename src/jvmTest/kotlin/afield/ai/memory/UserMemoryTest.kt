package afield.ai.memory

import afield.ai.conversation.InstructionMode
import afield.ai.conversation.Stability
import afield.ai.conversation.buildInstructionFragments
import afield.ai.tools.HistoryReadRequest
import afield.ai.tools.HistorySearchRequest
import afield.ai.tools.HistoryToolResult
import afield.ai.tools.MemoryReadRequest
import afield.ai.tools.MemorySearchRequest
import afield.ai.tools.MemoryToolResult
import afield.ai.tools.MemoryWriteRequest
import afield.ai.tools.ToolExecutionContext
import afield.ai.tools.ToolRegistry
import afield.ai.tools.aiToolMapper
import afield.ai.tools.registerBuiltInAgentTools
import afield.runtime.languages.KOTLIN_SCRIPT
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * AI4's cross-session memory: notes on disk with revision-checked writes, a generated index that the
 * instructions carry, a git history of every change, and the chat history notes cite.
 */
class UserMemoryTest {

    private fun <T> withDir(block: (File) -> T): T {
        val dir = Files.createTempDirectory("ai4-memory-test").toFile()
        try { return block(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun aNoteIsCreatedReadAndReplacedOnlyWithItsRevision() = withDir { dir ->
        val m = UserMemory(dir)
        val created = m.write("thisbox-not-underscore", "Current box is `thisBox`", "Marc corrected `_` twice.", "idiom", "verified",
            listOf("doc/sketch@t-a1.b2"), "")
        assertTrue(created.ok, created.message)
        val read = m.read("thisbox-not-underscore")!!
        assertEquals("idiom", read.kind)
        assertEquals(listOf("doc/sketch@t-a1.b2"), read.sources)
        assertEquals("Marc corrected `_` twice.", read.body)

        // Replacing without the revision, or with a stale one, changes nothing.
        val blind = m.write("thisbox-not-underscore", "x", "y", "idiom", "verified", emptyList(), "")
        assertFalse(blind.ok); assertTrue(blind.conflict)
        File(dir, "thisbox-not-underscore.md").appendText("\nA person's hand edit.\n")
        val stale = m.write("thisbox-not-underscore", "x", "y", "idiom", "verified", emptyList(), read.revision)
        assertFalse(stale.ok); assertTrue(stale.conflict)
        assertContains(m.read("thisbox-not-underscore")!!.body, "hand edit")

        val fresh = m.read("thisbox-not-underscore")!!
        val replaced = m.write("thisbox-not-underscore", "Current box is `thisBox`, never `_`", "Merged.", "idiom", "verified", emptyList(), fresh.revision)
        assertTrue(replaced.ok, replaced.message)
        assertEquals("Merged.", m.read("thisbox-not-underscore")!!.body)
        // A revision for a note that does not exist is refused, so a deleted note is not silently recreated.
        assertFalse(m.write("never-was", "d", "b", "other", "unconfirmed", emptyList(), "abc").ok)
    }

    @Test fun badNamesKindsAndSizesAreRefusedAndDeleteNeedsAReason() = withDir { dir ->
        val m = UserMemory(dir)
        assertFalse(m.write("Bad Name", "d", "b", "other", "unconfirmed", emptyList(), "").ok)
        assertFalse(m.write("../escape", "d", "b", "other", "unconfirmed", emptyList(), "").ok)
        assertFalse(m.write("ok", "d", "b", "mood", "unconfirmed", emptyList(), "").ok)
        assertFalse(m.write("ok", "", "b", "other", "unconfirmed", emptyList(), "").ok)
        assertFalse(m.write("ok", "d", "x".repeat(UserMemory.MAX_BODY_CHARS + 1), "other", "unconfirmed", emptyList(), "").ok)
        assertTrue(m.write("ok", "d", "b", "", "", emptyList(), "").ok)
        assertEquals("other", m.read("ok")!!.kind)
        assertEquals("unconfirmed", m.read("ok")!!.status)
        assertFalse(m.delete("ok", " ").ok)
        assertFalse(m.delete("missing", "why").ok)
        assertTrue(m.delete("ok", "merged elsewhere").ok)
        assertNull(m.read("ok"))
    }

    @Test fun theIndexListsKindsInOrderMarksUnconfirmedNotesAndIsCapped() = withDir { dir ->
        val m = UserMemory(dir)
        m.write("an-idiom", "an idiom", "b", "idiom", "verified", emptyList(), "")
        m.write("a-person", "who they are", "b", "person", "verified", emptyList(), "")
        m.write("a-trap", "a trap", "b", "trap", "unconfirmed", emptyList(), "")
        val lines = m.index().lines()
        assertEquals(listOf("- a-person (person): who they are", "- a-trap (trap, unconfirmed): a trap", "- an-idiom (idiom): an idiom"), lines)
        assertContains(File(dir, UserMemory.INDEX_FILE).readText(), "- a-trap (trap, unconfirmed): a trap")

        repeat(40) { i -> m.write("filler-$i", "f".repeat(180), "b", "other", "verified", emptyList(), "") }
        val index = m.index()
        assertTrue(index.length <= UserMemory.MAX_INDEX_CHARS + 80, "index is ${index.length} chars")
        assertContains(index, "more notes; MemorySearchRequest lists them all")
    }

    @Test fun everyChangeIsACommitInTheMemorysOwnRepository() = withDir { dir ->
        val hasGit = runCatching { ProcessBuilder("git", "--version").start().waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!hasGit) return@withDir
        val m = UserMemory(dir)
        m.write("one", "first", "b", "other", "unconfirmed", emptyList(), "", reason = "testing")
        m.delete("one", "wrong")
        val p = ProcessBuilder("git", "log", "--format=%s %an").directory(dir).redirectErrorStream(true)
            .also { it.environment().keys.removeIf { k -> k.startsWith("GIT_") } }.start()
        val log = p.inputStream.bufferedReader().readText().trim().lines()
        assertEquals(listOf("delete one: wrong Field AI4", "create one: testing Field AI4"), log)
    }

    @Test fun theToolsAreOfferedOnlyWithMemoryAndTheInstructionsCarryContractAndIndex() = withDir { dir ->
        val m = UserMemory(dir)
        m.write("a-person", "prefers short replies", "b", "person", "verified", emptyList(), "")
        val tools = ToolRegistry().also(::registerBuiltInAgentTools)
        val ctx = ToolExecutionContext(userMemory = m)
        assertTrue(tools.isAvailable(ctx, "MemoryWriteRequest"))
        assertFalse(tools.isAvailable(ToolExecutionContext(), "MemoryWriteRequest"))
        assertFalse(tools.isAvailable(ctx, "HistorySearchRequest"))

        val search = tools.executeByName(ctx, "MemorySearchRequest", aiToolMapper.writeValueAsString(MemorySearchRequest("short"))) as MemoryToolResult
        assertEquals(listOf("a-person"), search.entries.map { it.name })
        val read = tools.executeByName(ctx, "MemoryReadRequest", aiToolMapper.writeValueAsString(MemoryReadRequest("a-person"))) as MemoryToolResult
        val write = tools.executeByName(ctx, "MemoryWriteRequest", aiToolMapper.writeValueAsString(
            MemoryWriteRequest("a-person", "prefers short replies, no tables", "b2", "person", "verified", revision = read.revision))) as MemoryToolResult
        assertTrue(write.success, write.message)

        val fragments = buildInstructionFragments(InstructionMode.Chat, KOTLIN_SCRIPT, "hi", null, null, ctx)
        val contract = fragments.single { it.source == "memory.contract" }
        assertEquals(Stability.STABLE, contract.stability)
        val index = fragments.single { it.source == "memory.index" }
        assertEquals(Stability.VOLATILE, index.stability)
        assertContains(index.text, "- a-person (person): prefers short replies, no tables")
        assertTrue(buildInstructionFragments(InstructionMode.Chat, KOTLIN_SCRIPT, "hi", null, null, ToolExecutionContext())
            .none { it.source.startsWith("memory") })
    }

    @Test fun historySearchesTheWorkspacesChatsAndReadsFromACitation() = withDir { workspace ->
        val doc = File(workspace, "sketch").apply { mkdirs() }
        val box = "b1"
        File(doc, "metadata.edn").writeText(
            """{:version 2 :boxes [{"id" "root" "properties" {"name" "root"}} {"id" "$box" "properties" {"name" "Chat" "ai4_chat_history" #afield/file "${box}_ai4_chat_history_.md" "ai4_session" #afield/file "${box}_ai4_session_.jsonl"}}]}"""
        )
        val t1 = "<<you>>\nuse thisBox please\n<<ai>>\nNoted: thisBox, not underscore.\n"
        val t2 = "<<you>>\nnow add a slider\n<<ai>>\nAdded.\n"
        fun q(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()
        File(doc, "${box}_ai4_session_.jsonl").writeText(listOf(
            """{"t":"header","v":1}""",
            """{"t":"turn","id":"t-1","parent":null,"ts":1000,"markdown":${q(t1)},"user":"use thisBox please"}""",
            """{"t":"turn","id":"t-2","parent":"t-1","ts":2000,"markdown":${q(t2)},"user":"now add a slider"}""",
            """{"t":"head","id":"t-2","renderedSha":"x"}""",
        ).joinToString("\n"))
        File(doc, "${box}_ai4_chat_history_.md").writeText(t1 + t2)

        val history = ChatHistory.forDocument(doc)
        val tools = ToolRegistry().also(::registerBuiltInAgentTools)
        val ctx = ToolExecutionContext(chatHistory = history)
        val found = tools.executeByName(ctx, "HistorySearchRequest", aiToolMapper.writeValueAsString(HistorySearchRequest("underscore"))) as HistoryToolResult
        assertTrue(found.success, found.message)
        val citation = found.hits.single().substringBefore(" · ")
        assertEquals("sketch@t-1.b1", citation)

        val page = tools.executeByName(ctx, "HistoryReadRequest", aiToolMapper.writeValueAsString(HistoryReadRequest(from = citation))) as HistoryToolResult
        assertTrue(page.success, page.message)
        assertContains(page.text, "Noted: thisBox")
        assertContains(page.text, "now add a slider")
        assertFalse(page.text.contains("use thisBox please"), "reading from a citation starts at that block")

        val missing = tools.executeByName(ctx, "HistoryReadRequest", aiToolMapper.writeValueAsString(HistoryReadRequest(chat = "nope"))) as HistoryToolResult
        assertFalse(missing.success)
    }
}
