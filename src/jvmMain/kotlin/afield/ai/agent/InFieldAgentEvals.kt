package afield.ai.agent

import afield.ai.core.aiToolMapper
import kotlinx.serialization.Serializable

/**
 * A small eval suite for Field's own agent: ten tasks (three of them graded by pixels), each a goal, the setup it starts from,
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

    // ---- visual tasks: the defect, or the answer, is in the pixels ----------------------------------

    /**
     * A swatch whose colour is decided by the cascade: a stylesheet `!important` red, a more specific
     * green, an inline purple — and then a script that sets an inline `!important` blue, which beats
     * them all. Reading the source invites "red"; the picture is blue.
     */
    val SWATCH_CODE = "display(\"main\", \"\"\"<style>#sw { background: #d62728 !important; } .x .y #sw { background: #2ca02c; }</style>" +
        "<div class=\"x\"><div class=\"y\"><div id=\"sw\" style=\"background: #9467bd; width: 240px; height: 120px\"></div></div></div>" +
        "<script>document.getElementById('sw').style.setProperty('background', '#1f77b4', 'important')</script>\"\"\")"
    val ANSWER_CODE = "println(\"blue\")"

    val SUN_CODE = "display(\"main\", \"\"\"<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"300\" height=\"200\">" +
        "<rect width=\"300\" height=\"200\" fill=\"#0b1a4a\"/><circle cx=\"150\" cy=\"100\" r=\"50\" fill=\"#ffd700\"/></svg>\"\"\")"

    /** Percent heights inside a parent of auto height compute to auto: five empty bars, zero pixels tall. */
    val BARS_CODE = "val values = listOf(20, 40, 60, 80, 100)\n" +
        "val bars = values.joinToString(\"\") { v -> \"<div style='display:inline-block; vertical-align:bottom; width:30px; margin:4px; background:#1f77b4; height:\${v}%'></div>\" }\n" +
        "display(\"main\", \"<div style='background:white; padding:8px'>\$bars</div>\")"
    val BARS_FIXED = BARS_CODE.replace("height:\${v}%", "height:\${v}px")

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
        EvalTask(
            "swatch-colour",
            AgentTaskRequest(
                goal = "The box Swatch displays a coloured rectangle. Make a box named Answer that prints just the name " +
                    "of the colour the rectangle is shown in, as a person looking at it would say it (one of: red, green, " +
                    "blue, purple, orange), and run Answer.",
                setup = steps(call("NewBoxRequest", "name" to "Swatch", "code" to SWATCH_CODE), call("RunBoxRequest", "box" to "Swatch")),
                checks = listOf(check("last-run-ok:Answer"), check("output-contains:Answer:blue"),
                    check("property-lacks:Answer:code:red")),
                taskId = "swatch-colour"
            ),
            steps(call("ViewBoxOutputRequest", "box" to "Swatch", "note" to "which colour the rectangle is"),
                call("NewBoxRequest", "name" to "Answer", "code" to ANSWER_CODE),
                call("RunBoxRequest", "box" to "Answer"),
                "The rectangle is blue: the script's inline !important wins. Answer prints blue.")
        ),
        EvalTask(
            "draw-sun",
            AgentTaskRequest(
                goal = "Make a box named Sun that uses display to show, in the output channel \"main\", a 300 by 200 SVG " +
                    "picture: a dark blue night sky filling the whole picture, with a filled yellow circle (the sun) in it. " +
                    "Run it and check that the picture looks right.",
                checks = listOf(check("box-exists:Sun"), check("last-run-ok:Sun"), check("output-picture:Sun"),
                    check("output-colour:Sun:#0a1850:0.15"), check("output-colour:Sun:#ffe000:0.01")),
                taskId = "draw-sun"
            ),
            steps(call("NewBoxRequest", "name" to "Sun", "code" to SUN_CODE),
                call("RunBoxRequest", "box" to "Sun"),
                call("ViewBoxOutputRequest", "box" to "Sun", "note" to "a yellow sun on a dark blue sky"),
                "Sun displays a yellow circle on a dark blue sky; the capture shows both.")
        ),
        EvalTask(
            "bars-visible",
            AgentTaskRequest(
                goal = "The box Bars is meant to show five blue bars, 20, 40, 60, 80 and 100 pixels tall, but nobody can see " +
                    "them. Fix Bars so that the five bars are visible at those heights, run it, and check what it shows.",
                setup = steps(call("NewBoxRequest", "name" to "Bars", "code" to BARS_CODE), call("RunBoxRequest", "box" to "Bars")),
                checks = listOf(check("last-run-ok:Bars"), check("output-colour:Bars:#1f77b4:0.02")),
                taskId = "bars-visible"
            ),
            steps(call("ViewBoxOutputRequest", "box" to "Bars", "note" to "are the bars visible?"),
                call("SetBoxPropertyRequest", "box" to "Bars", "value" to BARS_FIXED),
                call("RunBoxRequest", "box" to "Bars"),
                call("ViewBoxOutputRequest", "box" to "Bars", "note" to "five blue bars of increasing height"),
                "Percent heights had no definite parent height to resolve against, so every bar was 0 px tall; they are px now.")
        ),
    )

    /** Tasks whose checks read pixels; they need a Field that can capture box output. */
    val visualTaskIds = setOf("swatch-colour", "draw-sun", "bars-visible")

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
    /** `on` / `off` pins the distilled lessons in every task's instructions; blank = default ([AgentLessons]). */
    val lessons: String = "",
    /** After the run, distil its tool misuse into the lessons file ([AgentLessons.distilAndWrite]). */
    val distil: Boolean = false,
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
    /** Sum of [AgentTaskResult.misuseCalls]. */
    val misuseCalls: Int = 0,
    /** Sum of [AgentTaskResult.imagesSeen]. */
    val imagesSeen: Int = 0,
    val rounds: Int = 0,
    /** Whether the lessons were on, and which version of the file was read. */
    val lessons: Boolean = false,
    val lessonsVersion: Int = 0,
    /** Set when the request asked to distil: what was written. */
    val distilled: List<String> = emptyList(),
    val distilMessage: String = "",
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
        val lessonsOn = AgentLessons.resolve(request.lessons)
        val lessonsPin = if (lessonsOn) "on" else "off"
        val lessonsVersion = AgentLessons.rawText()?.let { AgentLessons.parse(it).version } ?: 0
        val results = chosen.map { task ->
            runTask(task.request.copy(
                mode = request.mode,
                model = request.model,
                maxToolRounds = rounds,
                script = if (live) "" else task.solution,
                lessons = lessonsPin,
            ))
        }
        fun sum(f: (AgentTaskResult) -> Long?): Long? = results.mapNotNull(f).takeIf { it.isNotEmpty() }?.sum()
        val report = AgentEvalReport(
            mode = request.mode,
            provider = results.firstOrNull { it.provider.isNotBlank() }?.provider.orEmpty(),
            model = results.firstOrNull { it.model.isNotBlank() }?.model.orEmpty(),
            population = results.size,
            passed = results.count { it.verdict == "pass" },
            results = results,
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
            inputTokens = sum { it.inputTokens },
            outputTokens = sum { it.outputTokens },
            misuseCalls = results.sumOf { it.misuseCalls },
            imagesSeen = results.sumOf { it.imagesSeen },
            rounds = results.sumOf { it.rounds.coerceAtLeast(0) },
            lessons = lessonsOn,
            lessonsVersion = lessonsVersion,
        )
        if (!request.distil) return report
        val outcome = AgentLessons.distilAndWrite(report)
        return report.copy(distilled = outcome.lessons.map { it.bullet() }, distilMessage = outcome.message)
    }
}
