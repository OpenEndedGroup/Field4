package afield.ai.agent

import afield.ai.core.aiToolMapper
import kotlinx.serialization.Serializable

/**
 * A small eval suite for Field's own agent: seven tasks, each a goal, the setup it starts from,
 * checks that decide it, and a scripted solution.
 *
 * The scripted solutions are real Kotlin for real Field. Replayed through [ScriptedLlmBridge] against
 * a live Field (`agent-eval --mode scripted`) they test the tools, the execution path and the checkers
 * end to end with no model — and a scripted pass is the floor any model run should reach. Replayed in
 * jvmTest against a fake host, they test the harness logic. A `live` run hands the same goals and
 * checks to the configured provider and ignores the solutions.
 *
 * Box names are fixed, so run each task in a fresh document ([AgentEvalRunner] does).
 */
object InFieldAgentEvals {

    data class EvalTask(val id: String, val request: AgentTaskRequest, val solution: String)

    /** A step for [steps]: a tool call. */
    fun call(tool: String, vararg args: Pair<String, Any?>) = tool to args.toMap()

    /** Build a script / setup JSON from calls and (optionally) a final answer. */
    fun steps(vararg items: Any): String = aiToolMapper.writeValueAsString(items.map { item ->
        when (item) {
            is Pair<*, *> -> mapOf("tool" to item.first, "arguments" to item.second)
            is String -> mapOf("final" to item)
            else -> throw IllegalArgumentException("step must be call(...) or a final string")
        }
    })

    private fun check(spec: String) = AgentCheck.parse(spec)

    val PRIMES_CODE = "val primes = listOf(2, 3, 5, 7, 11, 13, 17, 19, 23, 29)\nprintln(primes)"
    val BROKEN_CODE = "val count: Int = \"three\"\nprintln(\"count is \$count\")"
    val FIXED_CODE = "val count: Int = 3\nprintln(\"count is \$count\")"
    val SOURCE_CODE = "prop<Int>(secret) = 4217"
    val READER_CODE = "println(secret)"
    val TABLE_CODE = "display(\"main\", \"<table><tr><td>1</td><td>1</td></tr><tr><td>2</td><td>4</td></tr><tr><td>3</td><td>9</td></tr></table>\")"
    val TICKER_CODE = "var n = 0\nloop { n++ }"
    val DIVIDER_CODE = "val xs = listOf<Int>()\nprintln(\"first is \" + xs.first())"
    val DIVIDER_FIXED = "val xs = listOf<Int>()\nprintln(xs.firstOrNull()?.let { \"first is \$it\" } ?: \"empty\")"

    val tasks: List<EvalTask> = listOf(
        EvalTask(
            "primes",
            AgentTaskRequest(
                goal = "Make a box named Primes that prints the first 10 prime numbers, and run it.",
                checks = listOf(check("box-exists:Primes"), check("last-run-ok:Primes"),
                    check("output-ints-include:Primes:2,3,5,7,11,13,17,19,23,29")),
                taskId = "primes"
            ),
            steps(call("NewBoxRequest", "name" to "Primes", "code" to PRIMES_CODE),
                call("RunBoxRequest", "box" to "Primes"),
                "Made Primes and ran it; it printed the first ten primes.")
        ),
        EvalTask(
            "fix-compile-error",
            AgentTaskRequest(
                goal = "The box named Broken does not compile. Fix its code so that it runs and prints exactly: count is 3",
                setup = steps(call("NewBoxRequest", "name" to "Broken", "code" to BROKEN_CODE), call("RunBoxRequest", "box" to "Broken")),
                checks = listOf(check("last-run-ok:Broken"), check("output-contains:Broken:count is 3")),
                taskId = "fix-compile-error"
            ),
            steps(call("GetBoxPropertyRequest", "box" to "Broken"),
                call("SetBoxPropertyRequest", "box" to "Broken", "value" to FIXED_CODE),
                call("RunBoxRequest", "box" to "Broken"),
                "Changed the String to an Int; Broken now prints count is 3.")
        ),
        EvalTask(
            "parent-variable",
            AgentTaskRequest(
                goal = "Connect the box Source as the parent of the box Reader. Then make Reader print the value of " +
                    "the value `secret` that Source shares — refer to it by name, do not copy the number — and run Reader.",
                setup = steps(call("NewBoxRequest", "name" to "Source", "code" to SOURCE_CODE), call("RunBoxRequest", "box" to "Source"),
                    call("NewBoxRequest", "name" to "Reader")),
                checks = listOf(check("connected:Source:Reader"), check("last-run-ok:Reader"),
                    check("output-contains:Reader:4217"), check("property-lacks:Reader:code:4217")),
                taskId = "parent-variable"
            ),
            steps(call("ConnectBoxesRequest", "parent" to "Source", "child" to "Reader"),
                call("SetBoxPropertyRequest", "box" to "Reader", "value" to READER_CODE),
                call("RunBoxRequest", "box" to "Reader"),
                "Reader is under Source and prints secret.")
        ),
        EvalTask(
            "html-table",
            AgentTaskRequest(
                goal = "Launch a box named Table that uses display to show an HTML <table> in the output channel \"main\", " +
                    "with one row for each n in 1..3 holding n and n*n.",
                checks = listOf(check("box-exists:Table"), check("last-run-ok:Table"), check("output-contains:Table:<table"),
                    check("output-ints-include:Table:1,1,2,4,3,9")),
                taskId = "html-table"
            ),
            steps(call("NewBoxRequest", "name" to "Table", "code" to TABLE_CODE),
                call("LaunchBoxRequest", "box" to "Table"),
                "Table is launched and displays the squares.")
        ),
        EvalTask(
            "stop-ticker",
            AgentTaskRequest(
                goal = "The box Ticker is running forever. Stop it, and confirm it is no longer running.",
                setup = steps(call("NewBoxRequest", "name" to "Ticker", "code" to TICKER_CODE), call("LaunchBoxRequest", "box" to "Ticker")),
                checks = listOf(check("box-exists:Ticker"), check("not-running:Ticker")),
                taskId = "stop-ticker"
            ),
            steps(call("StopBoxRequest", "box" to "Ticker"), call("ListBoxesRequest"), "Ticker is stopped.")
        ),
        EvalTask(
            "wire-two",
            AgentTaskRequest(
                goal = "Create two boxes named Alpha and Beta, and connect them so that Beta is a child of Alpha.",
                checks = listOf(check("box-exists:Alpha"), check("box-exists:Beta"), check("connected:Alpha:Beta")),
                taskId = "wire-two"
            ),
            steps(call("NewBoxRequest", "name" to "Alpha"), call("NewBoxRequest", "name" to "Beta", "parent" to "Alpha"),
                "Alpha -> Beta.")
        ),
        EvalTask(
            "fix-runtime-error",
            AgentTaskRequest(
                goal = "Box Divider throws an exception when it runs. Keep its list empty, but fix it so that it prints " +
                    "the word empty when the list is empty. Run it to show that it works.",
                setup = steps(call("NewBoxRequest", "name" to "Divider", "code" to DIVIDER_CODE), call("RunBoxRequest", "box" to "Divider")),
                checks = listOf(check("last-run-ok:Divider"), check("output-contains:Divider:empty")),
                taskId = "fix-runtime-error"
            ),
            steps(call("RunBoxRequest", "box" to "Divider"),
                call("SetBoxPropertyRequest", "box" to "Divider", "value" to DIVIDER_FIXED),
                call("RunBoxRequest", "box" to "Divider"),
                "Divider now uses firstOrNull and prints empty.")
        ),
    )

    fun byId(id: String): EvalTask = tasks.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("No eval task '$id'. Known: ${tasks.joinToString { it.id }}")
}

@Serializable
data class AgentEvalRequest(
    /** Task ids; empty = all. */
    val ids: List<String> = emptyList(),
    val mode: String = "scripted",
    val model: String = "",
    val maxToolRounds: Int = 24,
)

@Serializable
data class AgentEvalReport(
    val mode: String,
    val provider: String,
    val model: String,
    val population: Int,
    val passed: Int,
    val results: List<AgentTaskResult>,
    val elapsedMs: Long,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val error: String = "",
)

object AgentEvalRunner {
    /** Hard caps for a live eval: one run of at most this many tasks, at most this many rounds each. */
    const val MAX_LIVE_TASKS = 12
    const val MAX_LIVE_ROUNDS = 32

    /**
     * Run the suite, one task at a time. [runTask] runs one request in a fresh document and returns
     * its result; the server passes one that opens a headless scratch document per task.
     */
    fun run(request: AgentEvalRequest, runTask: (AgentTaskRequest) -> AgentTaskResult): AgentEvalReport {
        val started = System.nanoTime()
        val chosen = if (request.ids.isEmpty()) InFieldAgentEvals.tasks else request.ids.map(InFieldAgentEvals::byId)
        val live = request.mode == "live"
        require(!live || chosen.size <= MAX_LIVE_TASKS) { "A live eval is capped at $MAX_LIVE_TASKS tasks." }
        val rounds = if (live) request.maxToolRounds.coerceAtMost(MAX_LIVE_ROUNDS) else request.maxToolRounds
        val results = chosen.map { task ->
            runTask(task.request.copy(
                mode = request.mode,
                model = request.model,
                maxToolRounds = rounds,
                script = if (live) "" else task.solution
            ))
        }
        fun sum(f: (AgentTaskResult) -> Long?): Long? = results.mapNotNull(f).takeIf { it.isNotEmpty() }?.sum()
        return AgentEvalReport(
            mode = request.mode,
            provider = results.firstOrNull { it.provider.isNotBlank() }?.provider.orEmpty(),
            model = results.firstOrNull { it.model.isNotBlank() }?.model.orEmpty(),
            population = results.size,
            passed = results.count { it.verdict == "pass" },
            results = results,
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
            inputTokens = sum { it.inputTokens },
            outputTokens = sum { it.outputTokens },
        )
    }
}
