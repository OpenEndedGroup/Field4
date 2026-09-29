package afield.ai.agent

import afield.Box
import afield.SessionInformation
import afield.ai.core.AiBoxConversation
import afield.ai.core.ToolExecutionContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A task run *as an AI4 chat turn* (`AgentTaskRequest.chatBox`, which `askFieldAgent` always sets):
 * its exchange and verdict land in the target box's `ai4_chat_history`, and it fails fast instead of
 * waiting when no turn can start.
 *
 * The scripted path is exercised end to end here (fake execution host). The live path's dispatch
 * through the real `ai-assist` handler needs a running Field; what is tested here is that it gives up
 * at once, with the reason in the transcript, when the handler does not start a turn — which in a
 * jvmTest, with no handler installed, is always.
 */
class AgentTaskPanelTest {
    private val table = mapOf(InFieldAgentEvals.PRIMES_CODE to InFieldAgentHarnessTest.Fake(out = listOf("[2, 3, 5, 7, 11, 13, 17, 19, 23, 29]")))

    private fun document(): Pair<SessionInformation, FieldGraphTools> {
        val dir = Files.createTempDirectory("agent-panel-").toFile().apply { deleteOnExit() }
        val root = Box("root")
        root.name = "root"
        val info = SessionInformation(filename = dir.resolve("doc").absolutePath, documentName = "doc", root = root, ephemeral = true)
        root.inside = info
        return info to FieldGraphTools(info, InFieldAgentHarnessTest.FakeHost(table))
    }

    private fun run(info: SessionInformation, request: AgentTaskRequest) =
        AgentTaskRunner.run(request, info, InFieldAgentHarnessTest.FakeHost(table),
            contextFor = { _, graph, _ -> ToolExecutionContext(fieldGraph = graph) })

    @Test
    fun aScriptedTaskWritesItsTurnToolCallsAndVerdictIntoTheChatHistory() {
        val (info, tools) = document()
        val asker = tools.newBox(NewBoxRequest("Asker"))
        val task = InFieldAgentEvals.byId("primes")
        val result = run(info, task.request.copy(mode = "scripted", script = task.solution, chatBox = "Asker"))
        assertEquals("pass", result.verdict, result.error)
        assertEquals(asker.uid, result.chatBox)
        assertEquals(2, result.toolCalls.size, "the structured result still carries the tool log")

        val history = AiBoxConversation.read(info.boxes[asker.uid]!!)
        assertTrue(history.startsWith("<<you>>\n${task.request.goal}"), history.take(200))
        assertTrue("Scripted replay — no model was called" in history)
        assertTrue("<<progress>>\nNewBoxRequest finished" in history)
        assertTrue("<<progress>>\nRunBoxRequest finished" in history)
        assertTrue("\"provider\":\"scripted\"" in history, "the reply is labelled with the scripted identity")
        assertTrue("Checks — PASS (scripted replay, no model was called)" in history)
        assertTrue("✓ box-exists:Primes" in history && "✓ output-ints-include:Primes:2,3,5,7,11,13,17,19,23,29" in history)

        // A second task appends; it does not replace the conversation.
        run(info, AgentTaskRequest(goal = "second", mode = "scripted", script = InFieldAgentEvals.steps("ok"), chatBox = "Asker"))
        val after = AiBoxConversation.read(info.boxes[asker.uid]!!)
        assertTrue(after.startsWith(history) && "<<you>>\nsecond" in after)
    }

    @Test
    fun aBusyBoxFailsFastWithTheReasonInTheTranscript() {
        val (info, tools) = document()
        val busy = tools.newBox(NewBoxRequest("Busy"))
        AiBoxConversation.running(info.boxes[busy.uid]!!, true)
        val t0 = System.nanoTime()
        val result = run(info, AgentTaskRequest(goal = "anything", mode = "live", chatBox = "Busy"))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000, "no waiting")
        assertEquals("error", result.verdict)
        assertTrue(result.error.contains("already has an AI turn running"))
        val history = AiBoxConversation.read(info.boxes[busy.uid]!!)
        assertTrue("Agent task not started" in history && "already has an AI turn running" in history)
    }

    @Test
    fun aTurnThatNeverStartsFailsFastAndNeverReturnsAnotherTurnsReply() {
        val (info, tools) = document()
        val quiet = tools.newBox(NewBoxRequest("Quiet"))
        // An earlier conversation, which must not be mistaken for this task's reply.
        AiBoxConversation.write(info.boxes[quiet.uid]!!, "<<you>>\nearlier\n<<ai>>\nan earlier reply\n")
        val t0 = System.nanoTime()
        val result = run(info, AgentTaskRequest(goal = "make a box", mode = "live", chatBox = "Quiet"))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000, "no waiting for a turn that did not start")
        assertEquals("error", result.verdict)
        assertTrue(result.error.contains("did not start a turn"), result.error)
        assertEquals("", result.finalText)
        assertTrue("did not start a turn" in AiBoxConversation.read(info.boxes[quiet.uid]!!))
    }
}
