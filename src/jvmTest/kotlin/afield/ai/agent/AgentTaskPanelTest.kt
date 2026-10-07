package afield.ai.agent

import afield.Box
import afield.SessionInformation
import afield.ai.core.AiBoxConversation
import afield.ai.core.ToolExecutionContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    @Test
    fun livePanelCarriesTheRequestedBudgetAndReportsExhaustion() {
        val (info, tools) = document()
        val asker = tools.newBox(NewBoxRequest("Budget"))
        val box = info.boxes[asker.uid]!!
        val old = afield.handlers["ai-assist"]
        afield.handlers["ai-assist"] = mutableSetOf({ message, _ ->
            assertEquals("64", message.payload!!.jsonObject.getValue("maxToolRounds").jsonPrimitive.content)
            AiBoxConversation.running(box, true)
            assertEquals(-1, AiBoxConversation.turnRounds(box))
            assertEquals(false, AiBoxConversation.turnCapped(box))
            AiBoxConversation.write(box, "<<you>>\nFinish the retained build\n<<ai>>\nWork is still pending.\n")
            AiBoxConversation.completed(box, 64, true)
            AiBoxConversation.running(box, false)
            afield.Handled.yes
        })
        try {
            val result = run(info, AgentTaskRequest(goal = "Finish the retained build", mode = "live", chatBox = "Budget",
                maxToolRounds = 100, checks = listOf(AgentCheck("box-exists", box = "Budget"))))
            assertEquals(64, result.maxToolRounds)
            assertEquals(64, result.rounds)
            assertTrue(result.hitToolRoundCap)
            assertEquals("budget-exhausted", result.verdict, "An easy check must not mask an interrupted workflow")
        } finally {
            if (old == null) afield.handlers.remove("ai-assist") else afield.handlers["ai-assist"] = old
        }
    }

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
        assertTrue("✓ box-exists:Primes  ✓ last-run-ok:Primes  ✓ output-ints-include:Primes — PASS (scripted replay, no model was called)" in history,
            "the verdict's first line summarises every check")
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
    fun anInterruptedTurnUsesTheNormalChatRecoveryBeforeStarting() {
        val (info, tools) = document()
        val asker = tools.newBox(NewBoxRequest("Interrupted"))
        val box = info.boxes[asker.uid]!!
        AiBoxConversation.write(box, "<<you>>\nearlier\n<<progress-open>>\nPartial work\n")
        AiBoxConversation.running(box, true)
        val old = afield.handlers["ai-chat-history"]
        var historyReads = 0
        afield.handlers["ai-chat-history"] = mutableSetOf({ message, _ ->
            assertEquals(info, afield.thisSessionInfo.get())
            assertEquals(box.id, message.payload!!.jsonObject.getValue("uid").jsonPrimitive.content)
            historyReads++
            AiBoxConversation.recoverInterrupted(box)
            afield.Handled.yes
        })
        try {
            val result = run(info, AgentTaskRequest(goal = "continue", mode = "scripted",
                script = InFieldAgentEvals.steps("Recovered"), chatBox = "Interrupted"))
            assertEquals(1, historyReads)
            assertEquals("unchecked", result.verdict, result.error)
            val history = AiBoxConversation.read(box)
            assertTrue("Partial work" in history && "Previous AI turn was interrupted" in history)
            assertTrue("<<you>>\ncontinue" in history && "Recovered" in history)
        } finally {
            if (old == null) afield.handlers.remove("ai-chat-history") else afield.handlers["ai-chat-history"] = old
        }
    }

    @Test
    fun anObservationTimeoutLeavesTheLiveTurnAndTranscriptIntact() {
        val (info, tools) = document()
        val asker = tools.newBox(NewBoxRequest("Slow"))
        val box = info.boxes[asker.uid]!!
        val history = "<<you>>\nEarlier\n<<ai>>\nEarlier answer\n"
        AiBoxConversation.write(box, history)
        val old = afield.handlers["ai-assist"]
        val oldLimit = AgentTaskRunner.panelTurnLimitMs
        afield.handlers["ai-assist"] = mutableSetOf({ _, _ ->
            AiBoxConversation.write(box, history + "<<you>>\nLong analysis\n<<progress-open>>\nWorking\n")
            AiBoxConversation.running(box, true)
            afield.Handled.yes
        })
        AgentTaskRunner.panelTurnLimitMs = 1
        try {
            val result = run(info, AgentTaskRequest(goal = "Long analysis", mode = "live", chatBox = "Slow",
                checks = listOf(AgentCheck("box-exists", box = "Does not exist yet"))))
            assertEquals("running", result.verdict)
            assertEquals(asker.uid, result.chatBox)
            assertEquals("", result.error)
            assertEquals("", result.finalText, "a pending turn has no final answer")
            assertTrue(result.checks.isEmpty(), "completion checks must wait for completion")
            assertEquals(true, box.getPropertyRaw(AiBoxConversation.RUNNING_PROPERTY))
            assertEquals(history + "<<you>>\nLong analysis\n<<progress-open>>\nWorking\n", AiBoxConversation.read(box))
        } finally {
            AgentTaskRunner.panelTurnLimitMs = oldLimit
            AiBoxConversation.running(box, false)
            if (old == null) afield.handlers.remove("ai-assist") else afield.handlers["ai-assist"] = old
        }
    }

    @Test
    fun aProviderFailureIsAnErrorWithoutRepeatingTheUserTurnOrCheckingPartialWork() {
        val (info, tools) = document()
        val asker = tools.newBox(NewBoxRequest("Failed"))
        val box = info.boxes[asker.uid]!!
        val old = afield.handlers["ai-assist"]
        val message = "AI general failed: provider token rate limit"
        afield.handlers["ai-assist"] = mutableSetOf({ _, _ ->
            // Even a turn that finishes during dispatch started, unlike a refused request.
            AiBoxConversation.running(box, true)
            AiBoxConversation.write(box, "<<you>>\nInspect retained job\n<<ai>>\nPartial diagnosis\n")
            AiBoxConversation.failed(box, message)
            AiBoxConversation.running(box, false)
            afield.Handled.yes
        })
        try {
            val result = run(info, AgentTaskRequest(goal = "Inspect retained job", mode = "live", chatBox = "Failed",
                checks = listOf(AgentCheck("box-exists", box = "Failed"))))
            assertEquals("error", result.verdict)
            assertEquals(message, result.error)
            assertEquals("Partial diagnosis", result.finalText)
            assertTrue(result.checks.isEmpty(), "A real provider failure is not passed by already-existing output")
            val history = AiBoxConversation.read(box)
            assertEquals(1, Regex("<<you>>").findAll(history).count())
            assertTrue("No checks — ERROR" in history)
            assertTrue("Agent task not started" !in history)
        } finally {
            if (old == null) afield.handlers.remove("ai-assist") else afield.handlers["ai-assist"] = old
        }
    }

    @Test
    fun aQuickSuccessfulTurnClearsOldFailureAndDoesNotParseFailureWordsFromProse() {
        val (info, tools) = document()
        val asker = tools.newBox(NewBoxRequest("Recovered"))
        val box = info.boxes[asker.uid]!!
        AiBoxConversation.running(box, true)
        AiBoxConversation.failed(box, "Earlier failure")
        AiBoxConversation.running(box, false)
        val old = afield.handlers["ai-assist"]
        afield.handlers["ai-assist"] = mutableSetOf({ _, _ ->
            AiBoxConversation.running(box, true)
            AiBoxConversation.write(box, "<<you>>\nRead the job\n<<ai>>\nThe old log says AI general failed; the saved job completed.\n")
            AiBoxConversation.running(box, false)
            afield.Handled.yes
        })
        try {
            val result = run(info, AgentTaskRequest(goal = "Read the job", mode = "live", chatBox = "Recovered",
                checks = listOf(AgentCheck("box-exists", box = "Recovered"))))
            assertEquals("pass", result.verdict, result.error)
            assertEquals("", result.error)
            assertEquals(1, result.checks.size)
            assertTrue("old log says AI general failed" in result.finalText)
        } finally {
            if (old == null) afield.handlers.remove("ai-assist") else afield.handlers["ai-assist"] = old
        }
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
