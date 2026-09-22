package afield.datalog

import afield.Box
import afield.BoxExecution
import afield.Completion
import afield.CompletionRequest
import afield.Execute
import afield.ExecuteSymbol
import afield.ExecutionTrace
import afield.IsRunning
import afield.Message
import afield.Metadata
import afield.Output
import afield.RunsAt
import afield.Runloop
import afield.configureOutputProxies
import afield.dispatchExecutionOutput
import afield.early
import afield.inMain
import afield.payload
import afield.plugins
import afield.runtime.MainLanguageRuntime
import afield.runtime.TopologyWatcher
import afield.runtime.applyRuntimeToggle
import afield.runtime.isMainLanguage
import afield.runtime.registerMainLanguageRuntime
import afield.send
import afield.stdlib.Delegation
import afield.thisSession

/**
 * Datalog as a box language: the [DatalogExecution] that runs fragments inside a box, and the
 * registration that puts Datalog in the Main Language menu.
 *
 * [DatalogExecution] is the `BoxExecution`. It merges a fragment into the box's accumulated
 * program, prints whatever that fragment asked for, and completes on the relations the box has
 * actually defined as well as on the builtin functions, the aggregate operators and the box-graph
 * names. `installDatalog` registers the runtime and the persistent `isDatalogRoot` flag, and
 * `datalogTopologyNeedsUpdating` attaches and detaches executions as boxes are marked and unmarked.
 *
 * The `#directives` are the Field-specific part, and the reason this is more than a thin adapter:
 * `#boxes` loads the live document into the base relations of [dlBoxRelationSchema], `#why` and
 * `#whynot` reach the two provenance layers, and `#program` prints back what the box has
 * accumulated. `directiveHelp` is the one list that `#help` prints and completion offers, so a
 * directive added to it appears in both.
 */

/**
 * Datalog as a box language — set a box's Main Language to Datalog and its children hold Datalog
 * programs instead of Kotlin.
 *
 * The execution model is the REPL one rather than the script one: a box *accumulates*. Running a
 * fragment merges its facts and rules into the box's program and prints only what that fragment
 * asked for, so you can write a rule, run it, write a query below it, run just that line, and keep
 * going. That is why [Datalog.add] de-duplicates rules — re-running the whole box must not double
 * the program.
 *
 * Beyond the language itself there are `#directives`, which is where the Field-specific part lives:
 * `#boxes` loads the live document into the base relations described by [dlBoxRelationSchema], so a
 * box can ask questions about the document that contains it.
 */
class DatalogExecution(val inside: Box) : BoxExecution {

    val datalog = Datalog()

    override val languageName: String get() = "datalog"

    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        runOnce(inside, execute, "fragment", reply)
    }

    /**
     * One run of the program, reported under [mode].
     *
     * Every entry point lands here because Datalog has only the one behaviour, but they must not
     * all *claim* to be the same thing: `ExecutionReports` records a setup only from a mode that is
     * not `"fragment"`, so running or launching a box has to say so.
     */
    private fun runOnce(inside: Box, execute: Execute, mode: String, reply: (Message) -> Unit) {
        val originSession = thisSession.get()
        val trace = ExecutionTrace.forEngine(inside, execute, mode, reply)
        inMain {
            val watermark = ++inside.fragmentGeneration
            send(Message("is-running").payload(IsRunning(execute.uid, true, watermark, "__fragment__")))
            configureOutputProxies(inside, trace.reply, originSession)
            var ok = false
            try {
                val text = runProgram(execute.code)
                clearErrorStatus(inside, trace.reply, originSession)
                dispatchExecutionOutput(
                    inside,
                    trace.reply,
                    Output(
                        uid = inside.id,
                        line = execute.line + execute.code.split("\n").size,
                        kind = "ok",
                        html = escape(text.ifBlank { "ok" }),
                        frame = Runloop.runLoop.frame.toInt(),
                    ),
                    originSession = originSession,
                )
                ok = true
            } catch (e: DlException) {
                val line = if (e.line >= 0) execute.line + e.line - 1 else execute.line
                inside.error(e.message ?: "datalog error", line = line, property = "code")
            } catch (e: Throwable) {
                inside.error(e.message ?: e.javaClass.simpleName, line = execute.line, property = "code")
            } finally {
                trace.finish(ok)
                send(Message("is-running").payload(IsRunning(execute.uid, false, watermark, "__fragment__")))
            }
        }
    }

    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        runOnce(inside, execute, "run", reply)
    }

    /**
     * There is no per-frame Datalog. Launch runs the program once, like execute — a box that is
     * "running" forever while holding a finished model would only lie in the UI.
     */
    override fun launchAll(inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit) {
        runOnce(inside, execute.copy(code = code), "launch", reply)
    }

    override fun executeSymbol(inside: Box, executeSymbol: ExecuteSymbol, reply: (Message) -> Unit) {
        executeFragment(
            inside,
            Execute(
                uid = executeSymbol.uid,
                type = "fragment",
                line = executeSymbol.line,
                code = executeSymbol.symbol,
                singleLine = true,
            ),
            reply,
        )
    }

    override fun stop(inside: Box) {
        inside.runGeneration.keys.toList().forEach { strand ->
            inside.runGeneration[strand] = (inside.runGeneration[strand] ?: 0) + 1
        }
    }

    override fun complete(inside: Box, completionRequest: CompletionRequest, reply: (Message) -> Unit) {
        val code = completionRequest.allCode
        val cursor = completionRequest.position.coerceIn(0, code.length)
        var start = cursor
        while (start > 0 && (code[start - 1].isLetterOrDigit() || code[start - 1] == '_' || code[start - 1] == '#')) start--
        val prefix = code.substring(start, cursor)

        val candidates = ArrayList<Triple<String, String, String>>() // text, kind, documentation
        if (prefix.startsWith("#")) {
            directiveHelp.forEach { (name, help) -> candidates.add(Triple("#$name", "directive", help)) }
        } else {
            val known = LinkedHashMap<String, String>()
            datalog.facts.values.forEach { known[it.name] = "${it.name}/${it.arity} — ${it.size} facts" }
            datalog.rules.forEach { rule ->
                known.getOrPut(rule.head.pred) { "${rule.head.pred}/${rule.head.arity} — derived" }
            }
            known.forEach { (name, doc) -> candidates.add(Triple(name, "relation", doc)) }
            DatalogParser.functions.forEach { candidates.add(Triple(it, "function", "builtin function")) }
            DatalogParser.aggregateOps.forEach { candidates.add(Triple(it, "aggregate", "aggregate operator")) }
            dlBoxRelationSchema.forEach { (signature, doc) ->
                candidates.add(Triple(signature.substringBefore('('), "box graph", "$signature — $doc (needs #boxes)"))
            }
        }

        val matches = candidates.filter { it.first.startsWith(prefix, ignoreCase = true) }.distinctBy { it.first }
        if (matches.isEmpty()) return
        reply(
            Message("complete").payload(
                Completion(
                    boundsStart = start,
                    boundsEnd = cursor,
                    metadata = matches.map { (text, kind, doc) ->
                        Metadata(icon = kind, tail = kind, documentation = doc, text = text, displayText = text)
                    },
                )
            )
        )
    }

    private fun clearErrorStatus(inside: Box, reply: (Message) -> Unit, originSession: io.ktor.websocket.WebSocketSession?) {
        dispatchExecutionOutput(
            inside,
            reply,
            Output(
                uid = inside.id,
                line = -1,
                kind = "err",
                property = "code",
                html = "",
                frame = Runloop.runLoop.frame.toInt(),
            ),
            originSession = originSession,
        )
    }

    /**
     * A Datalog answer is a table drawn in spaces, so it has to be monospaced. Which font is
     * `field.css`'s business, not this file's: it rules `pre` to the house Iosevka.
     */
    private fun escape(text: String): String =
        "<pre style=\"margin:0; white-space:pre-wrap\">" +
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") +
            "</pre>"

    // ── the REPL ────────────────────────────────────────────────────────────────────────────────

    /**
     * Merge a fragment and report on it. Visible for the smoke test and the backdoor; returns what
     * would be printed rather than printing, so it is testable without a UI.
     */
    fun runProgram(source: String): String {
        val unit = DatalogParser.parse(source)
        val out = StringBuilder()

        // `#reset` clears before the fragment is merged; every other command reports on the state
        // *after* it. Running them all first meant a fragment that stated a rule and asked `#why`
        // about it in the same breath answered "not in the model" — which the docs capture caught.
        val (before, after) = unit.directives.partition { it.name == "reset" }
        before.forEach { directive -> out.appendLine(runDirective(directive)) }

        // A bare non-ground atom on its own — `path(a, Z)` — reads as a question, not a broken fact.
        val (asQueries, asRules) = unit.rules.partition { rule ->
            rule.isFact && rule.head.terms.any { it is DlVar }
        }
        val factsBefore = datalog.facts.values.sumOf { it.size }
        val rulesBefore = datalog.rules.size
        datalog.addParsed(DlUnit(rules = asRules))
        val addedFacts = datalog.facts.values.sumOf { it.size } - factsBefore
        val addedRules = datalog.rules.size - rulesBefore

        after.forEach { directive -> out.appendLine(runDirective(directive)) }

        val goals = unit.queries.map { it.body } + asQueries.map { listOf(it.head as DlLiteral) }
        goals.forEach { body ->
            val text = body.joinToString(", ") { dlLiteralText(it) }
            val rows = datalog.query(text)
            out.appendLine("?- $text")
            out.appendLine(rows.table())
            rows.warnings.forEach { out.appendLine("  warning: $it") }
        }

        if (goals.isEmpty() && unit.directives.isEmpty()) {
            val solution = datalog.solve()
            out.append(
                "added $addedFacts fact(s), $addedRules rule(s) — " +
                    "${solution.factCount} facts across ${solution.relations.size} relations"
            )
            solution.warnings.forEach { out.append("\n  warning: $it") }
        }
        return out.toString().trimEnd()
    }

    private fun runDirective(directive: DlDirective): String = when (directive.name) {
        "reset" -> {
            datalog.reset()
            "reset"
        }
        "boxes" -> {
            listOf("box", "name", "child", "nested", "frame", "prop", "running").forEach { datalog.clearFacts(it) }
            datalog.addBoxGraph(inside.root)
            "loaded the box graph: " + datalog.facts
                .filterKeys { it in setOf("box", "name", "child", "nested", "frame", "prop", "running") }
                .values.joinToString(", ") { "${it.name}/${it.arity} ${it.size}" }
        }
        "relations" -> datalog.solve().summary().joinToString("\n").ifBlank { "(nothing yet)" }
        "program" -> datalog.program().ifBlank { "(empty)" }
        "why" -> {
            if (directive.argument.isBlank()) "usage: #why <goal>"
            // A fact that is absent is the commonest reason to ask why, so point at the verb that
            // answers *that* question rather than stopping at "no".
            else datalog.why(directive.argument)?.render()
                ?: "${directive.argument} is not in the model — try #whynot ${directive.argument}"
        }
        "whynot" -> {
            if (directive.argument.isBlank()) "usage: #whynot <goal>"
            else datalog.whyNot(directive.argument).render()
        }
        "facts" -> {
            val pred = directive.argument.trim()
            if (pred.isBlank()) "usage: #facts <relation>"
            else {
                val relation = datalog.solve()[pred]
                if (relation == null) "no relation '$pred'"
                else datalog.rowsOf(pred).joinToString("\n") { row ->
                    if (row.isEmpty()) pred else pred + row.joinToString(", ", "(", ")") { dlSourceFormat(it) }
                }.ifBlank { "$pred is empty" }
            }
        }
        "help" -> helpText()
        else -> "unknown directive '#${directive.name}' — try #help"
    }

    private fun helpText(): String = buildString {
        appendLine("directives:")
        directiveHelp.forEach { (name, help) -> appendLine("  #$name".padEnd(16) + help) }
        appendLine()
        appendLine("box-graph relations (after #boxes):")
        dlBoxRelationSchema.forEach { (signature, doc) -> appendLine("  " + signature.padEnd(24) + doc) }
        appendLine()
        appendLine("aggregates: " + DatalogParser.aggregateOps.sorted().joinToString(" "))
        append("functions:  " + DatalogParser.functions.sorted().joinToString(" "))
    }

    companion object {
        val directiveHelp: List<Pair<String, String>> = listOf(
            "help" to "this text",
            "boxes" to "load the live box graph into the base relations",
            "relations" to "every relation in the model, with row counts",
            "facts <p>" to "print the rows of one relation",
            "program" to "print the accumulated program as source",
            "why <goal>" to "explain how a fact was derived",
            "whynot <goal>" to "explain why a goal has no answers",
            "reset" to "forget every fact and rule in this box",
        )
    }
}

var Box.isDatalogRoot by Delegation<Boolean?>()

@RunsAt(early)
fun installDatalog() {
    Box.ioPersistentProperties.add("isDatalogRoot")
    registerMainLanguageRuntime(
        MainLanguageRuntime(
            id = "datalog",
            label = "Datalog",
            runtimeClass = DatalogExecution::class,
            setLegacyRoot = { box, enabled -> box.isDatalogRoot = enabled },
            applyTopology = ::datalogTopologyNeedsUpdating,
        ),
    )
}

@RunsAt(plugins)
fun installDatalogPlugin() {
    TopologyWatcher("datalog") { datalogTopologyNeedsUpdating(it) }.start()
}

fun datalogTopologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { it.isDatalogRoot == true || it.isMainLanguage("datalog") },
        runtimeClass = DatalogExecution::class,
        newExecution = { child -> DatalogExecution(child) },
        language = "Datalog",
        customization = "",
        notifyCategory = "datalog",
    )
}
