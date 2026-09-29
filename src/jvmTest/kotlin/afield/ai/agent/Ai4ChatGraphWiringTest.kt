package afield.ai.agent

import afield.Box
import afield.BoxOutputLog
import afield.SessionInformation
import afield.ai.core.IdeChatHelpers
import afield.ai.core.InstructionMode
import afield.ai.core.KOTLIN_SCRIPT
import afield.ai.core.ScriptedLlmBridge
import afield.ai.core.ToolExecutionContext
import afield.ai.core.buildDynamicInstructions
import afield.server.agentRouteRefusal
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The graph tools are part of AI4 itself, not a harness beside it: the chat helpers every panel and
 * the document chat call carry them, the editor turn's own box is protected from them, the shared
 * instruction builder describes them, and the patch tool edits text in place.
 *
 * The `ai-assist` / `chat_input` handlers themselves pass `FieldGraphTools.forTurn(...)` into these
 * helpers; that wiring is verified by live turns through the handlers (IN_FIELD_AGENT_HARNESS.md),
 * because the handlers need a running Field.
 */
class Ai4ChatGraphWiringTest {
    private val host = InFieldAgentHarnessTest.FakeHost(emptyMap())

    private fun document(): SessionInformation {
        val dir = Files.createTempDirectory("ai4-chat-graph-").toFile().apply { deleteOnExit() }
        val root = Box("root")
        root.name = "root"
        val info = SessionInformation(filename = dir.resolve("doc").absolutePath, documentName = "doc", root = root, ephemeral = true)
        root.inside = info
        return info
    }

    @Test
    fun everyChatHelperOffersTheGraphAndPatchToolsWhenWired() {
        val info = document()
        val graph = FieldGraphTools(info, host)
        fun offered(run: (IdeChatHelpers, ScriptedLlmBridge) -> Unit): List<String> {
            val bridge = ScriptedLlmBridge(listOf(ScriptedLlmBridge.Step.Final("println(1)")))
            run(IdeChatHelpers(bridge), bridge)
            return bridge.received.single().availableTools
        }
        val general = offered { h, b -> h.chatGeneral(b.startThread("k", ""), "", KOTLIN_SCRIPT, "hi", fieldGraph = graph) }
        val file = offered { h, b -> h.chatFile(b.startThread("k", ""), "println(0)", KOTLIN_SCRIPT, "edit", fieldGraph = graph) }
        val inline = offered { h, b -> h.chatInline(b.startThread("k", ""), "println(0)", KOTLIN_SCRIPT, 0, "edit", fieldGraph = graph) }
        val error = offered { h, b -> h.chatError(b.startThread("k", ""), "println(0)", KOTLIN_SCRIPT, "boom", fieldGraph = graph) }
        for (tools in listOf(general, file, inline, error)) {
            assertTrue(tools.containsAll(fieldGraphToolNames), "missing: ${fieldGraphToolNames - tools.toSet()}")
            assertTrue("PatchBoxTextRequest" in tools)
        }
        val unwired = offered { h, b -> h.chatGeneral(b.startThread("k", ""), "", KOTLIN_SCRIPT, "hi") }
        assertTrue(unwired.none { it in fieldGraphToolNames }, "a chat that does not wire fieldGraph gets none")
    }

    @Test
    fun theSharedInstructionsDescribeTheToolsAndTheEditorTurnRule() {
        val info = document()
        val box = FieldGraphTools(info, host).newBox(NewBoxRequest("Me", code = "println(0)"))
        val chat = buildDynamicInstructions(InstructionMode.Chat, KOTLIN_SCRIPT, "p", null, null,
            ToolExecutionContext(fieldGraph = FieldGraphTools.forTurn(info)))
        assertTrue("PatchBoxTextRequest" in chat && "RunBoxRequest" in chat)
        assertFalse("THIS turn's reply rewrites" in chat)
        val editor = buildDynamicInstructions(InstructionMode.General, KOTLIN_SCRIPT, "p", null, null,
            ToolExecutionContext(fieldGraph = FieldGraphTools.forTurn(info, info.boxes[box.uid])))
        assertTrue("THIS turn's reply rewrites" in editor)
        val none = buildDynamicInstructions(InstructionMode.Chat, KOTLIN_SCRIPT, "p", null, null, ToolExecutionContext())
        assertFalse("RunBoxRequest" in none)
    }

    @Test
    fun theEditorTurnsOwnTextIsRefusedButOtherBoxesAndPlainRunsAreNot() {
        val info = document()
        val setup = FieldGraphTools(info, InFieldAgentHarnessTest.FakeHost(mapOf("println(0)" to InFieldAgentHarnessTest.Fake(out = listOf("0")))))
        val me = setup.newBox(NewBoxRequest("Me", code = "println(0)"))
        setup.newBox(NewBoxRequest("Other", code = "a"))
        val tools = FieldGraphTools(info, InFieldAgentHarnessTest.FakeHost(mapOf("println(0)" to InFieldAgentHarnessTest.Fake(out = listOf("0")))),
            replyOwned = setOf(me.uid to "code"))

        val set = tools.setProperty(SetBoxPropertyRequest("Me", value = "x"))
        assertFalse(set.success)
        assertTrue(set.message.contains("reply"))
        assertFalse(tools.patchText(PatchBoxTextRequest("Me", find = "0", replace = "1")).success)
        assertFalse(tools.run(RunBoxRequest("Me", code = "println(2)")).success)
        assertEquals("println(0)", info.boxes[me.uid]!!.getPropertyRaw("code"), "nothing wrote the reply-owned text")

        assertTrue(tools.run(RunBoxRequest("Me", timeoutSeconds = 10.0)).success, "running its current code is allowed")
        assertTrue(tools.setProperty(SetBoxPropertyRequest("Me", property = "notes", value = "n")).success, "other properties are fine")
        assertTrue(tools.setProperty(SetBoxPropertyRequest("Other", value = "b")).success, "other boxes are fine")
    }

    @Test
    fun patchEditsInPlaceAndRefusesWhatItCannotDoExactly() {
        val info = document()
        val tools = FieldGraphTools(info, host)
        val b = tools.newBox(NewBoxRequest("P", code = "val a = 1\nval b = 1\nprintln(a + b)"))
        fun code() = info.boxes[b.uid]!!.getPropertyRaw("code")

        assertFalse(tools.patchText(PatchBoxTextRequest("P", find = "= 1", replace = "= 2")).success, "ambiguous find is refused")
        assertFalse(tools.patchText(PatchBoxTextRequest("P", find = "nope", replace = "x")).success)
        assertTrue(tools.patchText(PatchBoxTextRequest("P", find = "val b = 1", replace = "val b = 5")).success)
        assertEquals("val a = 1\nval b = 5\nprintln(a + b)", code())
        assertTrue(tools.patchText(PatchBoxTextRequest("P", find = "val", replace = "var", occurrence = 0)).success)
        assertEquals("var a = 1\nvar b = 5\nprintln(a + b)", code())
        assertTrue(tools.patchText(PatchBoxTextRequest("P", startLine = 2, endLine = 2, replacement = "var b = 7")).success)
        assertEquals("var a = 1\nvar b = 7\nprintln(a + b)", code())
        assertTrue(tools.patchText(PatchBoxTextRequest("P", startLine = 1, endLine = 0, replacement = "// head")).success, "insert before line 1")
        assertEquals("// head\nvar a = 1\nvar b = 7\nprintln(a + b)", code())
        assertFalse(tools.patchText(PatchBoxTextRequest("P", startLine = 9, endLine = 9, replacement = "x")).success)
    }

    @Test
    fun agentRoutesAreLoopbackLocalhostAndSameOriginOnly() {
        assertNull(agentRouteRefusal("127.0.0.1", "localhost:19611", null, null), "curl/backdoor")
        assertNull(agentRouteRefusal("::1", "[::1]:9611", "same-origin", "https://[::1]:9611"))
        assertNotNull(agentRouteRefusal("192.168.1.5", "localhost:9611", null, null), "off-machine peer")
        assertNotNull(agentRouteRefusal("127.0.0.1", "evil.example:9611", null, null), "DNS rebinding keeps its own name in Host")
        assertNotNull(agentRouteRefusal("127.0.0.1", "192.168.1.5:9611", null, null), "a LAN literal is not localhost")
        assertNotNull(agentRouteRefusal("127.0.0.1", "localhost:9611", "cross-site", "https://evil.example"))
        assertNotNull(agentRouteRefusal("127.0.0.1", "localhost:9611", "same-site", "http://localhost:9493"))
    }

    @Test
    fun displayReplacesItsChannelAndDeletingABoxDropsItsHistory() {
        val uid = "log-" + System.nanoTime()
        val start = BoxOutputLog.mark()
        repeat(50) { BoxOutputLog.record(uid, "display", "<b>$it</b>", channel = "main") }
        BoxOutputLog.record(uid, "display", "other", channel = "side")
        val page = BoxOutputLog.since(uid, start)
        assertEquals(listOf("<b>49</b>", "other"), page.entries.map { it.text }, "one entry per channel: the latest")
        val held = BoxOutputLog.heldChars()
        BoxOutputLog.clear(uid)
        assertTrue(BoxOutputLog.since(uid, 0).entries.isEmpty())
        assertEquals(held - "<b>49</b>".length - "other".length, BoxOutputLog.heldChars())

        val info = document()
        val tools = FieldGraphTools(info, host)
        val b = tools.newBox(NewBoxRequest("Gone"))
        BoxOutputLog.record(b.uid, "out", "hello")
        tools.deleteBox(DeleteBoxRequest("Gone"))
        assertTrue(BoxOutputLog.since(b.uid, 0).entries.isEmpty(), "-delete-box clears the log")
    }
}
