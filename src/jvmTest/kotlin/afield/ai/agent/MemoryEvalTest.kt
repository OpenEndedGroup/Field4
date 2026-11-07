package afield.ai.agent

import afield.ai.memory.ChatHistory
import afield.ai.memory.UserMemory
import afield.ai.models.ScriptedLlmBridge
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * The memory eval's harness, with a stand-in for the model: arms, fresh memory per scenario, sessions recorded as
 * chats a dream can read, dream passes only between sessions of the dream arm, and the scoring of last sessions.
 */
class MemoryEvalTest {

    /**
     * A pretend AI4 with perfect use of memory: a session with a follow-up saves what the person said; a last
     * session passes when its memory holds a note. Without memory it can only pass the control.
     */
    private fun fakeSession(task: AgentTaskRequest): AgentTaskResult {
        val memory = task.memory.takeIf { it.isNotBlank() && it != "off" }?.let { UserMemory.at(File(it)) }
        val calls = mutableListOf<AgentToolCallRecord>()
        val replies = mutableListOf("Done.")
        val counts = mutableListOf(0)
        for (f in task.followUps) {
            if (memory != null) {
                val name = "said-" + (memory.notes().size + 1)
                memory.write(name, f.take(60), f, "person", "verified", emptyList(), "")
                calls += AgentToolCallRecord(1, "MemoryWriteRequest", """{"name":"$name"}""", true, "{}")
                counts += 1
            } else counts += 0
            replies += "Fixed."
        }
        val control = task.goal.contains("Squares") || task.goal.contains("Hello")
        val last = task.followUps.isEmpty() && !task.goal.contains("went up")
        val passed = control || !last || (memory != null && memory.notes().isNotEmpty())
        return AgentTaskResult(
            taskId = task.taskId, goal = task.goal, mode = task.mode, provider = "fake", model = "fake",
            verdict = if (passed) "pass" else "fail",
            checks = task.checks.map { AgentCheckOutcome(it, passed, if (passed) "seen" else "not seen") },
            toolCalls = calls, finalText = replies.last(), rounds = calls.size, maxToolRounds = task.maxToolRounds,
            hitToolRoundCap = false, elapsedMs = 1, documentName = "scratch", replies = replies, turnToolCalls = counts,
        )
    }

    @Test fun armsAreScoredOnTheLastSessionWithFreshMemoryPerScenarioAndDreamsBetweenSessions() {
        val root = Files.createTempDirectory("memory-eval-test").toFile()
        try {
            val tasks = mutableListOf<AgentTaskRequest>()
            val dreamBridges = mutableListOf<ScriptedLlmBridge>()
            val report = MemoryEval.run(
                MemoryEvalRequest(mode = "scripted", dir = root.path),
                { task -> tasks += task; fakeSession(task) },
                { ScriptedLlmBridge(listOf(ScriptedLlmBridge.Step.Final("nothing to change"))).also { dreamBridges += it } },
            )
            assertEquals("", report.error)
            val scenarios = MemoryEval.scenarios.size
            assertEquals(mapOf("none" to "1/$scenarios", "memory" to "$scenarios/$scenarios", "dream" to "$scenarios/$scenarios"), report.arms)
            assertEquals(mapOf("none" to 0, "memory" to 1, "dream" to 1), report.perScenario["kelvin"])
            assertEquals(mapOf("none" to 1, "memory" to 1, "dream" to 1), report.perScenario["control"])

            // The baseline has no memory; the memory arms get a folder of the scenario's own.
            assertTrue(tasks.filter { it.taskId.startsWith("none-") }.all { it.memory == "off" })
            val kelvinDir = File(root, "memory/kelvin-r1/memory")
            assertTrue(tasks.filter { it.taskId.startsWith("memory-kelvin") }.all { it.memory == kelvinDir.path })
            assertEquals(listOf("said-1"), UserMemory.at(kelvinDir).notes().map { it.name }, "no note from another scenario")

            // A dream between every pair of sessions in the dream arm, and only there: one per scenario, two for price-change.
            val betweens = MemoryEval.scenarios.sumOf { it.sessions.size - 1 }
            assertEquals(betweens, report.dreams.size)
            assertEquals(betweens, dreamBridges.size)
            assertTrue(report.dreams.all { it.ok }, report.dreams.toString())
            assertContains(dreamBridges.first().received.single().prompt, "widget-price-s1@t-1.b0")

            // Each session is a chat the dream could read: every request, each tool call under the turn that made it.
            val history = ChatHistory.forWorkspace(File(root, "dream/price-change-r1/history"))
            val turns = history.turnsSince(0)
            assertEquals(listOf("Make a box named Quote that prints the cost of 2 gadgets.",
                "Gadgets cost 4.50 each in my shop. Fix Quote to use that, and remember the price.",
                "Gadgets went up to 5.00 each this week. Make a box named Invoice that prints the cost of 3 gadgets.",
                "Make a box named Restock that prints the cost of 10 gadgets."), turns.map { it.request })
            val page = history.read("price-change-s1", "", "", 20_000).text
            assertTrue(page.indexOf("MemoryWriteRequest") > page.indexOf("Gadgets cost 4.50"), page)

            val scored = report.sessions.filter { it.scored }
            assertEquals(scenarios * 3, scored.size)
            assertEquals(mapOf("MemoryWriteRequest" to 1), report.sessions.first { it.arm == "memory" && it.scenario == "kelvin" && it.session == 1 }.memoryCalls)
            assertTrue(File(root, "report.json").readText().contains("\"perScenario\""))
        } finally { root.deleteRecursively() }
    }

    @Test fun aLiveRunIsCappedAndUnknownArmsAreRefused() {
        assertFailsWith<IllegalArgumentException> {
            MemoryEval.run(MemoryEvalRequest(mode = "live", repeats = 5, dir = "/nonexistent-never-written"), { error("not run") }, { error("not run") })
        }
        assertFailsWith<IllegalArgumentException> {
            MemoryEval.run(MemoryEvalRequest(mode = "scripted", arms = listOf("sometimes")), { error("not run") }, { error("not run") })
        }
    }
}
