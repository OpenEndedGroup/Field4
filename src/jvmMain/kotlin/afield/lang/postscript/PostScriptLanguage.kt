package afield.lang.postscript

import afield.app.protocol.Completion
import afield.app.protocol.CompletionRequest
import afield.app.protocol.Execute
import afield.app.protocol.ExecuteSymbol
import afield.app.protocol.Message
import afield.app.protocol.Metadata
import afield.app.protocol.Output
import afield.app.protocol.payload
import afield.app.server.thisSession
import afield.document.graph.Box
import afield.document.properties.Delegation
import afield.runtime.MainLanguageRuntime
import afield.runtime.TopologyWatcher
import afield.runtime.applyRuntimeToggle
import afield.runtime.execution.BoxExecution
import afield.runtime.execution.ExecutionTrace
import afield.runtime.execution.configureOutputProxies
import afield.runtime.execution.dispatchExecutionOutput
import afield.runtime.isMainLanguage
import afield.runtime.lifecycle.RunsAt
import afield.runtime.lifecycle.Runloop
import afield.runtime.lifecycle.early
import afield.runtime.lifecycle.inMain
import afield.runtime.lifecycle.plugins
import afield.runtime.registerMainLanguageRuntime
import afield.ui.visualization.lines

/**
 * PostScript as a Field box language: set a box's Main Language to PostScript and its children hold
 * PostScript, run by [PostScriptInterpreter].
 *
 * - **Run** (the whole box) starts from an empty stack and an empty page, runs the text, and draws
 *   whatever it stroked and filled in the box's `postscript` lines panel. Definitions made with `def`
 *   stay in the box's interpreter, as in a REPL.
 * - **A selection** runs in the same interpreter without clearing anything, so `3 double` after a run
 *   that defined `double` shows `6`. A run that paints adds to the drawing.
 * - Every clean run ends with `ok` and the stack it left, top value in bold; an error is marked on the
 *   line of the token that raised it.
 * - **Launch** is the same as Run: PostScript describes a page, not a frame loop.
 */
class PostScriptExecution(val inside: Box) : BoxExecution {

    /** This box's interpreter; its dictionaries outlive each run. */
    val ps = PostScriptInterpreter()

    override val languageName: String get() = "postscript"

    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) = run(inside, execute, execute.code, "run", reply, fresh = true)

    override fun launchAll(inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit) =
        run(inside, execute, code, "launch", reply, fresh = true)

    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) =
        run(inside, execute, execute.code, "fragment", reply, fresh = false)

    override fun executeSymbol(inside: Box, executeSymbol: ExecuteSymbol, reply: (Message) -> Unit) {
        executeFragment(inside, Execute(uid = executeSymbol.uid, type = "fragment", line = executeSymbol.line,
            code = executeSymbol.symbol, singleLine = true), reply)
    }

    private fun run(inside: Box, execute: Execute, code: String, mode: String, reply: (Message) -> Unit, fresh: Boolean) {
        val originSession = thisSession.get()
        val trace = ExecutionTrace.forEngine(inside, execute, mode, reply)
        inMain {
            configureOutputProxies(inside, trace.reply, originSession)
            dispatchExecutionOutput(inside, trace.reply,
                Output(uid = inside.id, line = -1, kind = "err", property = "code", html = "", frame = Runloop.runLoop.frame.toInt()),
                originSession = originSession)
            if (fresh) ps.reset()
            val drawnBefore = ps.lines.size
            ps.onOutput = { text -> inside.output.proxy(text, text.endsWith("\n"), execute.line) }
            val error = try { ps.eval(code); null } catch (e: PostScriptInterpreter.PsError) { e }
            if (ps.lines.size != drawnBefore || (fresh && ps.lines.isNotEmpty())) {
                inside.lines(ps.lines.toList(), channel = "postscript")
            }
            if (error != null) {
                inside.displayError(error.message ?: "error", "code", execute.line + maxOf(error.line, 1))
                trace.finish(false, error.message ?: "error")
                return@inMain
            }
            dispatchExecutionOutput(inside, trace.reply,
                Output(uid = inside.id, line = execute.line + code.split("\n").size, kind = "ok",
                    html = okHtml(ps.stackStrings()), frame = Runloop.runLoop.frame.toInt()),
                originSession = originSession)
            trace.finish(true)
        }
    }

    override fun stop(inside: Box) {}

    override fun complete(inside: Box, completionRequest: CompletionRequest, reply: (Message) -> Unit) {
        val code = completionRequest.allCode
        val cursor = completionRequest.position.coerceIn(0, code.length)
        var start = cursor
        while (start > 0 && !code[start - 1].isWhitespace() && code[start - 1] !in "[]{}()/") start--
        val prefix = code.substring(start, cursor)
        val names = ps.dicts.reversed().flatMap { d -> d.map.entries.map { it.key to it.value } }
            .filter { it.first.startsWith(prefix) && it.first != prefix }.distinctBy { it.first }.sortedBy { it.first }
        if (names.isEmpty()) return
        reply(Message("complete").payload(Completion(boundsStart = start, boundsEnd = cursor,
            metadata = names.map { (name, v) ->
                val kind = if (v is PostScriptInterpreter.Obj.Op) "operator" else "defined"
                Metadata(icon = kind, tail = kind, documentation = if (v is PostScriptInterpreter.Obj.Op) "PostScript operator" else ps.show(v).take(200),
                    text = name, displayText = name)
            })))
    }
}

/** The `ok` marker: the stack a clean run left, bottom first, the top in bold. HTML. */
internal fun okHtml(stack: List<String>): String {
    if (stack.isEmpty()) return "ok"
    fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val shown = stack.takeLast(8).map { esc(if (it.length > 60) it.take(57) + "…" else it) }
    val body = shown.mapIndexed { i, v -> if (i == shown.lastIndex) "<b>$v</b>" else v }.joinToString(" ")
    return "<span class=\"afield-postscript-ok\">ok · &lt;${stack.size}&gt; " + (if (stack.size > shown.size) "… " else "") + body + "</span>"
}

/** Marks a box as a PostScript root; persisted, and set alongside `mainLanguage` by Set Main Language. */
var Box.isPostScriptRoot by Delegation<Boolean?>()

/** Register PostScript as a main language. */
@RunsAt(early)
fun installPostScriptLanguage() {
    Box.ioPersistentProperties.add("isPostScriptRoot")
    registerMainLanguageRuntime(
        MainLanguageRuntime(
            id = "postscript",
            label = "PostScript",
            runtimeClass = PostScriptExecution::class,
            setLegacyRoot = { box, enabled -> box.isPostScriptRoot = enabled },
            applyTopology = ::postScriptTopologyNeedsUpdating,
        ),
    )
}

/** Start the watcher that keeps PostScript roots' children running PostScript. */
@RunsAt(plugins)
fun installPostScriptLanguagePlugin() {
    TopologyWatcher("postscript") { postScriptTopologyNeedsUpdating(it) }.start()
}

/** Give every child of a PostScript root a [PostScriptExecution], and take it back when the root goes. */
fun postScriptTopologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { it.isPostScriptRoot == true || it.isMainLanguage("postscript") },
        runtimeClass = PostScriptExecution::class,
        newExecution = { child -> PostScriptExecution(child) },
        language = "PostScript",
        customization = "",
        notifyCategory = "postscript",
    )
}
