package afield.ai.agent

import afield.ai.core.aiToolMapper
import kotlinx.serialization.Serializable

/**
 * A small eval suite for Field's own agent: seventeen tasks (five graded by pixels, three made of many similar calls
 * that a tool program does in one, two that are several turns of one conversation), each a goal, the setup it starts
 * from, checks that decide it, and a scripted solution.
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
        "val bars = values.joinToString(\"\") { v -> \"<div style='display:inline-block; vertical-align:bottom; width:20px; margin:3px; background:#1f77b4; height:\${v}%'></div>\" }\n" +
        "display(\"main\", \"<div style='background:white; padding:8px'>\$bars</div>\")"
    val BARS_FIXED = BARS_CODE.replace("height:\${v}%", "height:\${v}px")

    /**
     * Look → revise → look. Chart's container has class `bars`, and *another* box, Theme, displays a
     * stylesheet that makes `.bars` `flex-direction: row-reverse` — output panels share one page, so a
     * style in one panel reaches every other. The bars therefore appear in the reverse of the list's
     * order, and sorting the list ascending, the obvious first move, draws them tallest-first. Chart's
     * own code does not say so; the picture does. (The first version put row-reverse in Chart's own
     * code; the live model read it and got it right first time, twice, so it tested nothing.) The
     * scripted solution makes the mistake on purpose, sees it, and fixes it.
     */
    val THEME_CODE = "display(\"main\", \"<style>.bars { flex-direction: row-reverse; justify-content: flex-end; }</style>" +
        "<div style='font: 12px monospace'>theme loaded</div>\")"
    private fun chartCode(values: String, tallestRed: Boolean): String =
        "val values = $values\n" +
            "val top = values.maxOrNull()\n" +
            "val bars = values.joinToString(\"\") { v -> \"<div style='width:24px; margin:3px; height:\${v}px; background:\" + " +
            (if (tallestRed) "(if (v == top) \"#d62728\" else \"#1f77b4\")" else "\"#1f77b4\"") + " + \"'></div>\" }\n" +
            "display(\"main\", \"<div class='bars' style='display:flex; align-items:flex-end; " +
            "background:white; padding:8px; height:110px'>\$bars</div>\")"
    /**
     * Look → revise → look, with a trap no source states: the output panel's width. Five 28 px bars
     * fit; ten wrap onto a second row, which the picture shows and the code cannot (the panel is as
     * wide as the box, and a new box's frame is chosen by Field). `bars-sorted-red` turned out not to
     * force a revision: the live model read the stylesheet in the other box through ListContents and
     * pre-empted it in all four runs.
     */
    private fun rowCode(values: String, nowrap: Boolean): String =
        "val values = $values\n" +
            "val bars = values.joinToString(\"\") { v -> \"<div style='display:inline-block; vertical-align:bottom; width:28px; " +
            "margin:3px; background:#1f77b4; height:\${v}px'></div>\" }\n" +
            "display(\"main\", \"<div style='background:white; padding:8px" + (if (nowrap) "; white-space:nowrap" else "") + "'>\$bars</div>\")"
    val ROW_CODE = rowCode("listOf(20, 30, 40, 50, 60)", nowrap = false)
    val ROW_FIRST_TRY = rowCode("listOf(20, 30, 40, 50, 60, 70, 80, 90, 100, 110)", nowrap = false)
    val ROW_FIXED = rowCode("listOf(20, 30, 40, 50, 60, 70, 80, 90, 100, 110)", nowrap = true)

    // ---- many-call tasks: the work is the same call over many boxes or values -------------------------
    //
    // These exist to measure tool programs (RunToolProgramRequest): each is a dozen similar calls done
    // directly, or one program. Their scripted solutions are programs; `programs=off` is the other arm.

    val CELL_BAD = "val xs = listOf<Int>()\nprintln(\"cell \" + xs.first())"
    fun cellCode(n: Int) = "println(\"cell $n ok\")"
    val CELL_NAMES = (1..10).map { "Cell%02d".format(it) }
    val FAILING_CELLS = listOf("Cell03", "Cell07", "Cell09")
    val REPORT_CODE = "println(\"Cell03, Cell07, Cell09\")"

    /** A value nobody computes in their head: the hash of a string built from k, so the sweep has to run. */
    fun sqCode(k: Int) = "val k = $k\nprintln(\"\${k}x\${k * k}\".hashCode().mod(1000))"
    /** What [sqCode] prints for k = 1..8, as Kotlin computes it. */
    val SWEEP_OUTPUTS = listOf(858, 822, 788, 25, 846, 669, 494, 342)
    val RESULTS_CODE = "println(\"${SWEEP_OUTPUTS.joinToString(" ")}\")"

    val TODO_NOTES = setOf(3, 4, 8, 10, 12)
    fun noteCode(n: Int) = "// note $n" + (if (n in TODO_NOTES) "\n// TODO: tidy this up" else "") + "\nprintln($n)"
    fun noteName(n: Int) = "Note%02d".format(n)

    // ---- multi-turn tasks: one conversation, several requests ---------------------------------------
    //
    // These exist to measure where instructions go (the context pipeline): until 2026-10-02 every turn's
    // full instruction block stayed in the conversation, so a four-turn task resent three stale copies.

    val COUNTER_3 = "println((1..3).toList())"
    val COUNTER_5 = "println((1..5).toList())"
    val COUNTER_SQUARES = "println((1..5).map { it * it })"
    val TOTAL_CODE = "println((1..5).sumOf { it * it })"

    private fun powersTable(n: Int, cubes: Boolean) =
        "display(\"main\", \"<table>\" + (1..$n).joinToString(\"\") { i -> \"<tr><td>\$i</td><td>\${i * i}</td>" +
            (if (cubes) "<td>\${i * i * i}</td>" else "") + "</tr>\" } + \"</table>\")"
    val POWERS_3 = powersTable(3, cubes = false)
    val POWERS_5 = powersTable(5, cubes = false)
    val POWERS_5_CUBES = powersTable(5, cubes = true)

    private fun program(code: String, purpose: String) =
        call("RunToolProgramRequest", "code" to code.trimIndent(), "purpose" to purpose)

    val CHART_CODE = chartCode("listOf(55, 25, 90, 40, 70)", tallestRed = false)
    /** The first try: sorted ascending, tallest red — drawn tallest-first because of row-reverse. */
    val CHART_FIRST_TRY = chartCode("listOf(55, 25, 90, 40, 70).sorted()", tallestRed = true)
    val CHART_FIXED = chartCode("listOf(55, 25, 90, 40, 70).sortedDescending()", tallestRed = true)

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
        EvalTask(
            "bars-sorted-red",
            AgentTaskRequest(
                goal = "The box Chart shows five bars. Change it so that, as displayed, the bars are sorted by height — " +
                    "shortest on the left, tallest on the right — and the tallest bar is red (#d62728) while the others " +
                    "stay blue. Run it and check what it shows; keep going until it looks right.",
                setup = steps(call("NewBoxRequest", "name" to "Theme", "code" to THEME_CODE), call("RunBoxRequest", "box" to "Theme"),
                    call("NewBoxRequest", "name" to "Chart", "code" to CHART_CODE), call("RunBoxRequest", "box" to "Chart")),
                checks = listOf(check("last-run-ok:Chart"), check("output-colour:Chart:#d62728:0.01"),
                    check("output-rising:Chart:#1f77b4,#d62728:5")),
                taskId = "bars-sorted-red"
            ),
            steps(call("ViewBoxOutputRequest", "box" to "Chart", "note" to "the starting order"),
                call("SetBoxPropertyRequest", "box" to "Chart", "value" to CHART_FIRST_TRY),
                call("RunBoxRequest", "box" to "Chart"),
                call("ViewBoxOutputRequest", "box" to "Chart", "note" to "sorted shortest-to-tallest, tallest red?"),
                call("SetBoxPropertyRequest", "box" to "Chart", "value" to CHART_FIXED),
                call("RunBoxRequest", "box" to "Chart"),
                call("ViewBoxOutputRequest", "box" to "Chart", "note" to "rising left to right, the rightmost red"),
                "Something outside Chart reverses its bars (they drew tallest-first after sorting ascending); sorted descending they rise, the tallest red.")
        ),
        EvalTask(
            "bars-one-row",
            AgentTaskRequest(
                goal = "The box Row shows five blue bars. Add five more after them, 70, 80, 90, 100 and 110 pixels tall, " +
                    "keeping every bar 28 pixels wide, so that all ten stand side by side in a single row rising left to " +
                    "right. Run it and check what it shows; keep going until it looks right.",
                setup = steps(call("NewBoxRequest", "name" to "Row", "code" to ROW_CODE), call("RunBoxRequest", "box" to "Row")),
                checks = listOf(check("last-run-ok:Row"), check("output-rising:Row:#1f77b4:10")),
                taskId = "bars-one-row"
            ),
            steps(call("SetBoxPropertyRequest", "box" to "Row", "value" to ROW_FIRST_TRY),
                call("RunBoxRequest", "box" to "Row"),
                call("ViewBoxOutputRequest", "box" to "Row", "note" to "ten bars in one row?"),
                call("SetBoxPropertyRequest", "box" to "Row", "value" to ROW_FIXED),
                call("RunBoxRequest", "box" to "Row"),
                call("ViewBoxOutputRequest", "box" to "Row", "note" to "ten bars in one row, rising"),
                "Ten bars wrapped onto two rows in the panel's width; white-space:nowrap keeps them in one.")
        ),
        EvalTask(
            "audit-failing",
            AgentTaskRequest(
                goal = "Some of the boxes Cell01 to Cell10 fail when they run. Make a box named Report that prints the " +
                    "names of the failing ones, sorted and separated by a comma and a space (for example: Cell02, Cell05), " +
                    "and run Report.",
                setup = steps(*CELL_NAMES.mapIndexed { i, name ->
                    call("NewBoxRequest", "name" to name, "code" to if (name in FAILING_CELLS) CELL_BAD else cellCode(i + 1))
                }.toTypedArray()),
                checks = listOf(check("last-run-ok:Report"), check("output-contains:Report:Cell03, Cell07, Cell09")),
                taskId = "audit-failing"
            ),
            steps(program("""
                const cells = tools.ListBoxes({}).boxes.map(b => b.name).filter(n => n.startsWith("Cell")).sort();
                const failing = cells.filter(n => !tools.RunBox({box: n}).success);
                tools.NewBox({name: "Report", code: 'println("' + failing.join(", ") + '")'});
                const report = tools.RunBox({box: "Report"});
                return {failing, printed: report.stdout};
            """, "run every Cell, collect the failing ones, report them"),
                "Cell03, Cell07 and Cell09 fail (an empty list's first()); Report prints them.")
        ),
        EvalTask(
            "sweep-parameter",
            AgentTaskRequest(
                goal = "The box Sq starts with the line `val k = 1`. Run Sq once for each k from 1 to 8 by changing that " +
                    "line, then make a box named Results that prints the eight numbers Sq printed, in order of k, separated " +
                    "by spaces, and run Results. Leave Sq with k = 1 when you are done.",
                setup = steps(call("NewBoxRequest", "name" to "Sq", "code" to sqCode(1))),
                checks = listOf(check("last-run-ok:Results"),
                    check("output-ints-include:Results:${SWEEP_OUTPUTS.joinToString(",")}"),
                    check("property-contains:Sq:code:val k = 1\n"), check("property-lacks:Sq:code:val k = 8")),
                taskId = "sweep-parameter"
            ),
            steps(program("""
                const printed = [];
                for (let k = 1; k <= 8; k++) {
                  tools.PatchBoxText({box: "Sq", startLine: 1, replacement: "val k = " + k});
                  printed.push(tools.RunBox({box: "Sq"}).stdout.join(" ").trim());
                }
                tools.PatchBoxText({box: "Sq", startLine: 1, replacement: "val k = 1"});
                tools.NewBox({name: "Results", code: 'println("' + printed.join(" ") + '")'});
                return {printed, results: tools.RunBox({box: "Results"}).stdout};
            """, "sweep k = 1..8 through Sq and collect what it prints"),
                "Swept k = 1..8 through Sq; Results prints the eight values in order, and Sq is back at k = 1.")
        ),
        EvalTask(
            "tag-todos",
            AgentTaskRequest(
                goal = "Some of the boxes Note01 to Note12 have a TODO comment in their code. Insert the line " +
                    "`// reviewed` as the first line of the code of each of those boxes, and change nothing else. " +
                    "Do not run any box.",
                setup = steps(*(1..12).map { n -> call("NewBoxRequest", "name" to noteName(n), "code" to noteCode(n)) }.toTypedArray()),
                checks = TODO_NOTES.map { n -> check("property-contains:${noteName(n)}:code:// reviewed\n// note $n") } +
                    listOf(1, 2, 6, 11).map { n -> check("property-lacks:${noteName(n)}:code:reviewed") },
                taskId = "tag-todos"
            ),
            steps(program("""
                const notes = tools.ListBoxes({}).boxes.map(b => b.name).filter(n => n.startsWith("Note")).sort();
                const tagged = [];
                for (const n of notes) {
                  const code = tools.GetBoxProperty({box: n}).value || "";
                  if (!code.includes("TODO")) continue;
                  tools.PatchBoxText({box: n, startLine: 1, insert: true, replacement: "// reviewed"});
                  tagged.push(n);
                }
                return tagged;
            """, "tag every Note box whose code has a TODO"),
                "Five boxes had a TODO (Note03, Note04, Note08, Note10, Note12); each now starts with // reviewed.")
        ),
        EvalTask(
            "four-turns",
            AgentTaskRequest(
                goal = "Make a box named Counter that prints the numbers 1 to 3 as one list on one line, and run it.",
                followUps = listOf(
                    "Change Counter so that it prints 1 to 5 instead, still as one list, and run it.",
                    "Now make Counter print the squares of 1 to 5 instead, as one list, and run it.",
                    "Make a second box named Total that prints the sum of those five squares, and run it.",
                ),
                checks = listOf(check("last-run-ok:Counter"), check("output-ints-include:Counter:1,4,9,16,25"),
                    check("last-run-ok:Total"), check("output-contains:Total:55")),
                taskId = "four-turns"
            ),
            steps(call("NewBoxRequest", "name" to "Counter", "code" to COUNTER_3), call("RunBoxRequest", "box" to "Counter"),
                "Counter prints 1 to 3.",
                call("SetBoxPropertyRequest", "box" to "Counter", "value" to COUNTER_5), call("RunBoxRequest", "box" to "Counter"),
                "Counter prints 1 to 5.",
                call("SetBoxPropertyRequest", "box" to "Counter", "value" to COUNTER_SQUARES), call("RunBoxRequest", "box" to "Counter"),
                "Counter prints 1, 4, 9, 16, 25.",
                call("NewBoxRequest", "name" to "Total", "code" to TOTAL_CODE), call("RunBoxRequest", "box" to "Total"),
                "Total prints 55.")
        ),
        EvalTask(
            "revise-table",
            AgentTaskRequest(
                goal = "Launch a box named Powers that uses display to show an HTML <table> in the output channel \"main\", " +
                    "with one row for each n in 1..3 holding n and n*n.",
                followUps = listOf(
                    "Extend Powers' table to n = 1..5, and launch it again.",
                    "Add a third column to every row holding n*n*n, and launch Powers again.",
                ),
                checks = listOf(check("last-run-ok:Powers"), check("output-contains:Powers:<table"),
                    check("output-ints-include:Powers:5,25,125"), check("property-contains:Powers:code:display")),
                taskId = "revise-table"
            ),
            steps(call("NewBoxRequest", "name" to "Powers", "code" to POWERS_3), call("LaunchBoxRequest", "box" to "Powers"),
                "Powers shows n and n*n for 1..3.",
                call("SetBoxPropertyRequest", "box" to "Powers", "value" to POWERS_5), call("LaunchBoxRequest", "box" to "Powers"),
                "Powers now goes to 5.",
                call("SetBoxPropertyRequest", "box" to "Powers", "value" to POWERS_5_CUBES), call("LaunchBoxRequest", "box" to "Powers"),
                "Powers shows n, n*n and n*n*n for 1..5.")
        ),
    )

    /** Tasks made of many similar calls: what tool programs are for. */
    val manyCallTaskIds = setOf("audit-failing", "sweep-parameter", "tag-todos")

    /** Tasks that are several user turns of one conversation: what the context pipeline is for. */
    val multiTurnTaskIds = setOf("four-turns", "revise-table")

    /** Tasks whose checks read pixels; they need a Field that can capture box output. */
    val visualTaskIds = setOf("swatch-colour", "draw-sun", "bars-visible", "bars-sorted-red", "bars-one-row")

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
    /**
     * Path to a saved [AgentEvalReport]: run nothing, distil that report instead (implies [distil]).
     * How a change to [AgentLessons.distil] is applied to a run already paid for.
     */
    val distilFrom: String = "",
    /** Withhold ViewBoxOutputRequest from every task (the counterfactual for the visual tasks). */
    val blind: Boolean = false,
    /** `off` withholds RunToolProgramRequest from every task (the tool-program A/B arm); see [AgentTaskRequest.programs]. */
    val programs: String = "",
    /** `legacy` puts every task's instructions back inside the stored message; see [AgentTaskRequest.context]. */
    val context: String = "",
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
    /** Sums of [AgentTaskResult.cacheReadTokens] / [AgentTaskResult.cacheWriteTokens]; null when no task reported them. */
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
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
    /**
     * Leak evidence, filled by the server's entry points ([afield.runAgentEvalHeadless]): open documents
     * before and after the run, and AI4 capture pages still open after it. -1 = not measured.
     */
    val documentsBefore: Int = -1,
    val documentsAfter: Int = -1,
    val capturePagesOpenAfter: Int = -1,
    /** Whether tool programs were offered; sums of [AgentTaskResult.programCalls] and [AgentTaskResult.nestedCalls]. */
    val programs: Boolean = false,
    val programCalls: Int = 0,
    val nestedCalls: Int = 0,
    /** `legacy` when the run put instructions inside the stored message, else blank. */
    val context: String = "",
)

object AgentEvalRunner {
    /** Hard caps for a live eval: one run of at most this many tasks, at most this many rounds each. */
    const val MAX_LIVE_TASKS = 17
    const val MAX_LIVE_ROUNDS = 32

    /**
     * Run the suite, one task at a time. [runTask] runs one request in a fresh document and returns
     * its result; the server passes one that opens a headless scratch document per task.
     */
    fun run(request: AgentEvalRequest, runTask: (AgentTaskRequest) -> AgentTaskResult): AgentEvalReport {
        if (request.distilFrom.isNotBlank()) {
            val saved = AgentLessons.readReport(request.distilFrom)
            val outcome = AgentLessons.distilAndWrite(saved)
            return saved.copy(distilled = outcome.lessons.map { it.bullet() }, distilMessage = outcome.message)
        }
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
                blind = request.blind,
                programs = request.programs,
                context = request.context,
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
            cacheReadTokens = sum { it.cacheReadTokens },
            cacheWriteTokens = sum { it.cacheWriteTokens },
            misuseCalls = results.sumOf { it.misuseCalls },
            imagesSeen = results.sumOf { it.imagesSeen },
            rounds = results.sumOf { it.rounds.coerceAtLeast(0) },
            lessons = lessonsOn,
            lessonsVersion = lessonsVersion,
            programs = results.any { it.programs },
            context = request.context,
            programCalls = results.sumOf { it.programCalls },
            nestedCalls = results.sumOf { it.nestedCalls },
        )
        if (!request.distil) return report
        val outcome = AgentLessons.distilAndWrite(report)
        return report.copy(distilled = outcome.lessons.map { it.bullet() }, distilMessage = outcome.message)
    }
}
