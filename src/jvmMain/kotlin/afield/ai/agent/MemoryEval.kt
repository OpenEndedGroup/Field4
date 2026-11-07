package afield.ai.agent

import afield.ai.memory.ChatHistory
import afield.ai.memory.Dreaming
import afield.ai.memory.UserMemory
import afield.ai.models.LlmBridge
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * # Does AI4's memory help? Scenarios of fresh sessions, with and without it
 *
 * Each scenario is two or three sessions with AI4, every one in a fresh document and a fresh conversation, as
 * if on different days. In an early session the person says something AI4 cannot know otherwise: a price, a
 * house style, a unit, and in one scenario a change to what they said before. The last session asks for work
 * that depends on it, without repeating it. Only the last session is scored. This follows the setup of
 * Anthropic's memory measurements (anthropic.com/research/components-of-agent-memory): a fresh session per
 * question, the memory folder the only thing carried over.
 *
 * Every scenario runs under each of three arms:
 *
 * - `none`: no memory. The baseline, which should fail every scenario except the control.
 * - `memory`: the scenario's sessions share one memory folder ([UserMemory]), fresh for the scenario.
 * - `dream`: as `memory`, with a dream pass ([Dreaming]) between sessions over the sessions so far.
 *
 * A dream pass reads saved chats, and a headless task saves none, so after each session the runner records it
 * as a chat in the arm's own history workspace ([recordSession]): each request the person made, the tool
 * calls that turn made (name, outcome, arguments), and AI4's reply. That record is written by this harness
 * from the task's result, not by the chat panel, and holds less than a panel transcript: no images, and
 * arguments cut at 300 characters.
 *
 * `control` is the scenario memory should not change: the early session teaches nothing the last one needs. A
 * drop there would mean memory got in the way.
 *
 * Run it with the backdoor's `memory-eval`; results and every arm's memory folders stay under [MemoryEvalRequest.dir].
 */
object MemoryEval {

    /** One session: what the person asks, what they add afterwards in the same conversation, and the checks. */
    data class Session(val goal: String, val followUps: List<String> = emptyList(), val checks: List<String> = emptyList())

    /** A scenario: [sessions] in order, the last one scored. [teaches] says what the earlier sessions carry. */
    data class Scenario(val id: String, val teaches: String, val sessions: List<Session>)

    val ARMS = listOf("none", "memory", "dream")
    const val MAX_LIVE_SESSIONS = 40

    val scenarios: List<Scenario> = listOf(
        Scenario("widget-price", "a fact, with an explicit request to remember it", listOf(
            Session("Make a box named Quote that prints the cost of 3 widgets.",
                listOf("Widgets cost 7.25 each in my shop. Fix Quote to use that, and remember the price for future sessions."),
                listOf("output-contains:Quote:21.75")),
            Session("Make a box named Order that prints the cost of 12 widgets.", checks = listOf("output-contains:Order:87")),
        )),
        Scenario("kelvin", "a correction about units, with no request to remember it", listOf(
            Session("Make a box named Boil that prints the boiling point of water.",
                listOf("No, in my documents temperatures are always in kelvin. Please fix it."),
                listOf("output-contains:Boil:373")),
            // Kelvin, and only kelvin: the first live run's baseline printed "273.15 K (0 °C, 32 °F)", hedging into a pass.
            Session("Make a box named Freeze that prints the freezing point of water.",
                checks = listOf("output-contains:Freeze:273", "property-lacks:Freeze:code:°C", "property-lacks:Freeze:code:°F")),
        )),
        Scenario("result-prefix", "a house style, stated as a correction", listOf(
            Session("Make a box named Total that prints the sum of 3, 4 and 5.",
                listOf("In my work every printed result line starts with 'RESULT: ', like 'RESULT: 12'. Please change Total to do that."),
                listOf("output-contains:Total:RESULT: 12")),
            Session("Make a box named Product that prints the product of 6 and 7.", checks = listOf("output-contains:Product:RESULT: 42")),
        )),
        Scenario("price-change", "a fact that changes in the second session; the third needs the new value", listOf(
            Session("Make a box named Quote that prints the cost of 2 gadgets.",
                listOf("Gadgets cost 4.50 each in my shop. Fix Quote to use that, and remember the price."),
                listOf("output-contains:Quote:9")),
            Session("Gadgets went up to 5.00 each this week. Make a box named Invoice that prints the cost of 3 gadgets.",
                checks = listOf("output-contains:Invoice:15")),
            Session("Make a box named Restock that prints the cost of 10 gadgets.", checks = listOf("output-contains:Restock:50")),
        )),
        Scenario("control", "nothing the last session needs", listOf(
            Session("Make a box named Hello that prints hello.", listOf("Thanks. For what it's worth, I like short replies."),
                listOf("output-contains:Hello:hello")),
            // One printed line: the first live run's five-line answer reached the check as its last three lines, in every arm.
            Session("Make a box named Squares that prints the sum of the squares of 1 to 5.", checks = listOf("output-contains:Squares:55")),
        )),
    )

    fun byId(id: String): Scenario = scenarios.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("No memory-eval scenario '$id'. Known: ${scenarios.joinToString { it.id }}")

    /**
     * Record [result], the [index]th session of a scenario, as a saved chat in [workspace]: a document folder named
     * [documentName] holding one box whose session tree has a node per user turn, as `ChatArchive` reads it.
     */
    fun recordSession(workspace: File, documentName: String, request: AgentTaskRequest, result: AgentTaskResult, savedAt: Long) {
        fun q(s: String) = JsonPrimitive(s).toString()
        val doc = File(workspace, documentName).apply { mkdirs() }
        val box = "session"
        File(doc, "metadata.edn").writeText(
            """{:version 2 :boxes [{"id" "root" "properties" {"name" "root"}} {"id" "$box" "properties" {"name" "Memory eval session (recorded by the harness)" "ai4_chat_history" #afield/file "${box}_ai4_chat_history_.md" "ai4_session" #afield/file "${box}_ai4_session_.jsonl"}}]}"""
        )
        val asked = listOf(request.goal) + request.followUps
        val counts = result.turnToolCalls.takeIf { it.size == asked.size } ?: (listOf(result.toolCalls.size) + List(asked.size - 1) { 0 })
        val replies = result.replies.takeIf { it.size == asked.size } ?: (List(asked.size - 1) { "" } + result.finalText)
        var next = 0
        val lines = mutableListOf("""{"t":"header","v":1}""")
        val transcript = StringBuilder()
        var parent = "null"
        asked.forEachIndexed { i, text ->
            val md = buildString {
                append("<<you>>\n").append(text.trim()).append("\n")
                result.toolCalls.drop(next).take(counts[i]).forEach { c ->
                    append("<<progress>>\n").append(c.tool).append(if (c.ok) " finished" else " failed").append("\n")
                    append(c.arguments.take(300)).append("\n")
                }
                append("<<ai>>\n").append(replies[i].trim().ifEmpty { "(no reply)" }).append("\n")
            }
            next += counts[i]
            transcript.append(md)
            lines += """{"t":"turn","id":"t-$i","parent":$parent,"ts":${savedAt + i},"markdown":${q(md)},"user":${q(text)}}"""
            parent = "\"t-$i\""
        }
        lines += """{"t":"head","id":"t-${asked.size - 1}","renderedSha":"harness"}"""
        File(doc, "${box}_ai4_session_.jsonl").writeText(lines.joinToString("\n"))
        File(doc, "${box}_ai4_chat_history_.md").writeText(transcript.toString())
    }

    /**
     * Run the eval. [runTask] runs one session in a fresh document (the server passes the headless runner);
     * [dreamBridge] makes the model for each dream pass.
     */
    fun run(request: MemoryEvalRequest, runTask: (AgentTaskRequest) -> AgentTaskResult, dreamBridge: () -> LlmBridge,
            clock: () -> Long = System::currentTimeMillis): MemoryEvalReport {
        val started = clock()
        val chosen = if (request.ids.isEmpty()) scenarios else request.ids.map(::byId)
        val arms = request.arms.ifEmpty { ARMS }
        arms.firstOrNull { it !in ARMS }?.let { throw IllegalArgumentException("Unknown arm '$it'. Arms: ${ARMS.joinToString()}") }
        val repeats = request.repeats.coerceIn(1, 5)
        val sessionCount = chosen.sumOf { it.sessions.size } * arms.size * repeats
        require(request.mode != "live" || sessionCount <= MAX_LIVE_SESSIONS) {
            "A live memory eval is capped at $MAX_LIVE_SESSIONS sessions; this one is $sessionCount (fewer ids, arms or repeats)."
        }
        val root = File(request.dir.ifBlank {
            "build/ai4-memory-eval/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        }).absoluteFile.also { it.mkdirs() }

        val sessions = mutableListOf<MemoryEvalSession>()
        val dreams = mutableListOf<MemoryEvalDream>()
        for (arm in arms) for (scenario in chosen) for (r in 1..repeats) {
            val runDir = File(root, "$arm/${scenario.id}-r$r")
            val memoryDir = File(runDir, "memory")
            val history = File(runDir, "history")
            scenario.sessions.forEachIndexed { k, s ->
                val task = AgentTaskRequest(
                    goal = s.goal, followUps = s.followUps, checks = s.checks.map(AgentCheck::parse),
                    mode = request.mode, model = request.model, maxToolRounds = request.maxToolRounds,
                    memory = if (arm == "none") "off" else memoryDir.path,
                    taskId = "${arm}-${scenario.id}-r$r-s${k + 1}",
                    allowRemote = request.allowRemote,
                )
                val result = runTask(task)
                recordSession(history, "${scenario.id}-s${k + 1}", task, result, clock())
                val memoryCalls = result.toolCalls.filter { it.tool.startsWith("Memory") || it.tool.startsWith("History") }
                    .groupingBy { it.tool }.eachCount()
                sessions += MemoryEvalSession(
                    arm = arm, scenario = scenario.id, repeat = r, session = k + 1, scored = k == scenario.sessions.lastIndex,
                    verdict = result.verdict,
                    checks = result.checks.map { (if (it.passed) "PASS " else "FAIL ") + it.detail.take(200) },
                    memoryCalls = memoryCalls,
                    notesAfter = if (arm == "none") emptyList() else UserMemory.at(memoryDir).notes().map { it.name },
                    reply = result.finalText.take(600), rounds = result.rounds, error = result.error,
                    inputTokens = result.inputTokens, outputTokens = result.outputTokens,
                )
                if (arm == "dream" && k < scenario.sessions.lastIndex) {
                    val d = Dreaming.run(UserMemory.at(memoryDir), ChatHistory.forWorkspace(history), dreamBridge(), "eval", now = clock())
                    dreams += MemoryEvalDream(scenario.id, r, k + 1, d.ok, d.message, d.created, d.updated, d.deleted)
                }
            }
        }
        val scored = sessions.filter { it.scored }
        val byArm = arms.associateWith { arm -> scored.filter { it.arm == arm }.let { s -> "${s.count { it.verdict == "pass" }}/${s.size}" } }
        val report = MemoryEvalReport(
            mode = request.mode, model = request.model, dir = root.path, arms = byArm,
            perScenario = chosen.associate { sc -> sc.id to arms.associateWith { arm ->
                scored.filter { it.arm == arm && it.scenario == sc.id }.count { it.verdict == "pass" } } },
            sessions = sessions, dreams = dreams, elapsedMs = clock() - started,
        )
        File(root, "report.json").writeText(Json { prettyPrint = true }.encodeToString(report))
        return report
    }
}

@Serializable
data class MemoryEvalRequest(
    /** Scenario ids; empty = all. */
    val ids: List<String> = emptyList(),
    /** Any of `none`, `memory`, `dream`; empty = all three. */
    val arms: List<String> = emptyList(),
    val mode: String = "live",
    val model: String = "",
    val maxToolRounds: Int = 16,
    /** Each scenario runs this many times per arm (1 to 5), each with a fresh memory folder. */
    val repeats: Int = 1,
    /** Where memory folders, recorded sessions and `report.json` go; blank = `build/ai4-memory-eval/<time>`. */
    val dir: String = "",
    /** A live run may reach a non-local provider only when this says so, as for an agent task. */
    val allowRemote: Boolean = false,
)

/** One session of one scenario under one arm. [scored] marks the last session, the one the scenario is graded on. */
@Serializable
data class MemoryEvalSession(
    val arm: String, val scenario: String, val repeat: Int, val session: Int, val scored: Boolean,
    val verdict: String, val checks: List<String>,
    /** Memory and history tool calls this session made, by tool. */
    val memoryCalls: Map<String, Int>,
    /** The notes in the arm's memory folder after this session (and before any dream). */
    val notesAfter: List<String>,
    val reply: String, val rounds: Int, val error: String = "",
    val inputTokens: Long? = null, val outputTokens: Long? = null,
)

/** One dream pass between two sessions of the `dream` arm. */
@Serializable
data class MemoryEvalDream(
    val scenario: String, val repeat: Int, val afterSession: Int, val ok: Boolean, val message: String,
    val created: List<String>, val updated: List<String>, val deleted: List<String>,
)

@Serializable
data class MemoryEvalReport(
    val mode: String,
    val model: String,
    /** Where everything was written, `report.json` included. */
    val dir: String,
    /** Scored sessions passed per arm, as `passed/scored`. */
    val arms: Map<String, String>,
    /** Passes of each scenario's last session, per arm. */
    val perScenario: Map<String, Map<String, Int>>,
    val sessions: List<MemoryEvalSession>,
    val dreams: List<MemoryEvalDream>,
    val elapsedMs: Long,
    val error: String = "",
)
