@file:afield.CompletionCategory("Synthesis")
package afield.pd

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
import afield.four.synth.FieldAudioEngine
import afield.four.synth.audio
import afield.inMain
import afield.graphics.lines
import afield.payload
import afield.plugins
import afield.runtime.MainLanguageRuntime
import afield.runtime.TopologyWatcher
import afield.runtime.applyRuntimeToggle
import afield.runtime.isMainLanguage
import afield.runtime.registerMainLanguageRuntime
import afield.send
import afield.stdlib.Delegation
import afield.stdlib.LineNotes
import afield.thisSession
import kotlinx.coroutines.yield
import java.io.Closeable

/**
 * Pure Data as a box language: set a box's Main Language to Pd and its children hold patches.
 *
 * A child's `code` is the text of a `.pd` file. **Run** (or launch) opens it in a Pd of the box's own
 * and plays it until the box stops; running it again replaces the patch. A **fragment** — a selected
 * line or two that are not patch statements — is a Pd message, written the way a Pd message box
 * with a leading `;` writes one: `freq 440` sends 440 to `[r freq]`, `go bang` bangs `[r go]`. So a
 * patch and the messages that play it sit in the same box, and running a line is sending it.
 *
 * What Pd says reaches the box the way Kotlin's output does: `[print]` lines are printed, an object
 * Pd could not create is marked on its line, and every `[s]` and `[r]` name in the patch is
 * listened on, so a line note on `[s out]` shows the last value that went through it.
 */
class PdExecution(val inside: Box) : BoxExecution {

    override val languageName: String get() = "pd"

    /** The Pd playing this box's patch, or null when it is stopped. */
    @Volatile
    var running: PdLaunchState? = null
        private set

    /** One run's Pd, audio registration and listeners; closed as a unit. */
    class PdLaunchState(val pd: PdInstance, val patch: PdPatch, val graph: PdPatchGraph, private val closeables: List<Closeable>) : Closeable {
        override fun close() {
            closeables.forEach { runCatching { it.close() } }
            pd.close()
        }
    }

    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) =
        launchAll(inside, execute, execute.code, execute.strand, reply)

    override fun launchAll(inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit) {
        val originSession = thisSession.get()
        val trace = ExecutionTrace.forEngine(inside, execute, "launch", reply)
        inMain {
            configureOutputProxies(inside, trace.reply, originSession)
            if (!PdLibrary.isAvailable) {
                inside.error(PdLibrary.describe(), line = execute.line, property = "code")
                trace.finish(false, "libpd is not built")
                return@inMain
            }
            // A new generation ends the previous run's loop, which closes that run's Pd.
            val generation = inside.runGeneration.getOrPut(strand) { -1 } + 1
            inside.runGeneration[strand] = generation

            val state = try {
                open(inside, code, execute.line)
            } catch (t: Throwable) {
                inside.error(t.message ?: t.javaClass.simpleName, line = execute.line, property = "code")
                trace.finish(false, "the patch did not open")
                send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand)))
                return@inMain
            }
            if (state.patch.problems.none { it.line >= 0 }) clearErrorStatus(inside, trace.reply, originSession)
            running = state
            send(Message("is-running").payload(IsRunning(execute.uid, true, generation, strand)))
            trace.finish(true)

            inMain {
                try {
                    while (inside.runGeneration[strand] == generation) {
                        state.pd.poll()
                        yield()
                    }
                } finally {
                    state.pd.poll()
                    if (running === state) running = null
                    state.close()
                    send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand)))
                }
            }
        }
    }

    private fun open(box: Box, code: String, firstLine: Int): PdLaunchState {
        val engine = FieldAudioEngine.shared()
        val pd = PdInstance(sampleRate = engine.sampleRate)
        val closeables = ArrayList<Closeable>()
        try {
            closeables += pd.onPrint { line -> runCatching { box.println(line) } }
            val patch = pd.openPatchText(PdPatchGraph.statementsOnly(code), "box_${box.id.filter { it.isLetterOrDigit() }.take(40)}", source = code)
            for (p in patch.problems) {
                if (p.line >= 0) runCatching { box.error(p.message, "code", firstLine + p.line) }
                runCatching { box.println(p.message) }
            }
            val graph = PdPatchGraph.parse(code)
            // Listen on every name the patch uses, so a line note can show what went through it.
            for (name in graph.sendNames + graph.receiveNames) closeables += pd.receive(name) {}
            val handle = FieldAudioEngine.shared().register(audio(pd.node, gain = 1f))
            closeables += Closeable { handle.close() }
            runCatching { box.lines(graph.toFLines(), channel = "patch") }
            return PdLaunchState(pd, patch, graph, closeables)
        } catch (t: Throwable) {
            closeables.forEach { runCatching { it.close() } }
            pd.close()
            throw t
        }
    }

    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        val lines = execute.code.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        if (lines.isEmpty()) return
        // Patch statements: reload the whole box, because a patch cannot be edited a line at a time.
        if (lines.any { it.startsWith("#") }) {
            val whole = inside.getPropertyRaw("code") as? String ?: execute.code
            launchAll(inside, execute.copy(code = whole, line = 0), whole, execute.strand, reply)
            return
        }
        val originSession = thisSession.get()
        val trace = ExecutionTrace.forEngine(inside, execute, "fragment", reply)
        inMain {
            configureOutputProxies(inside, trace.reply, originSession)
            val state = running
            if (state == null) {
                inside.error("this patch is not running; run the box first, then send it messages", line = execute.line, property = "code")
                trace.finish(false, "not running")
                return@inMain
            }
            val sent = ArrayList<String>()
            for (line in lines) {
                val m = parsePdMessageLine(line) ?: continue
                sendMessage(state.pd, m.first, m.second, m.third)
                sent += line.removeSuffix(";")
            }
            dispatchExecutionOutput(
                inside, trace.reply,
                Output(uid = inside.id, line = execute.line + lines.size, kind = "ok",
                    html = "sent " + sent.joinToString("; ").replace("<", "&lt;"), frame = Runloop.runLoop.frame.toInt()),
                originSession = originSession,
            )
            trace.finish(true)
        }
    }

    override fun executeSymbol(inside: Box, executeSymbol: ExecuteSymbol, reply: (Message) -> Unit) {
        executeFragment(inside, Execute(uid = executeSymbol.uid, type = "fragment", line = executeSymbol.line,
            code = executeSymbol.symbol, singleLine = true), reply)
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
        while (start > 0 && !code[start - 1].isWhitespace() && code[start - 1] != ';') start--
        val prefix = code.substring(start, cursor)
        val lineStart = code.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val before = code.substring(lineStart, start).trim().split(Regex("\\s+"))
        // After `#X obj x y ` the next word is a class name; anywhere else, offer the statements.
        val candidates: List<Pair<String, String>> = if (before.size == 4 && before[0] == "#X" && before[1] == "obj") {
            PD_VANILLA_OBJECTS.map { it to "object" }
        } else if (prefix.startsWith("#")) {
            PD_STATEMENTS.map { it to "statement" }
        } else {
            val names = running?.graph?.let { it.receiveNames } ?: PdPatchGraph.parse(code).receiveNames
            names.map { it to "receive" }
        }
        val matches = candidates.filter { it.first.startsWith(prefix) && it.first != prefix }
        if (matches.isEmpty()) return
        reply(Message("complete").payload(Completion(boundsStart = start, boundsEnd = cursor,
            metadata = matches.map { (text, kind) -> Metadata(icon = kind, tail = kind, documentation = "Pd $kind", text = text, displayText = text) })))
    }

    private fun clearErrorStatus(inside: Box, reply: (Message) -> Unit, originSession: io.ktor.websocket.WebSocketSession?) {
        dispatchExecutionOutput(inside, reply,
            Output(uid = inside.id, line = -1, kind = "err", property = "code", html = "", frame = Runloop.runLoop.frame.toInt()),
            originSession = originSession)
    }

    companion object {
        /** The statement openers a patch is made of. */
        val PD_STATEMENTS = listOf("#N canvas 0 0 450 300 12;", "#X obj ", "#X msg ", "#X floatatom ", "#X text ", "#X connect ")

        /** Pd vanilla's commonest classes, for completion after `#X obj x y`. Not every class Pd has. */
        val PD_VANILLA_OBJECTS = listOf(
            "osc~", "phasor~", "noise~", "sig~", "line~", "vline~", "dac~", "adc~", "*~", "+~", "-~", "/~",
            "lop~", "hip~", "bp~", "vcf~", "clip~", "wrap~", "cos~", "tabread4~", "tabread~", "tabwrite~",
            "tabplay~", "tabosc4~", "delwrite~", "delread~", "delread4~", "env~", "snapshot~", "throw~", "catch~",
            "s~", "r~", "mtof", "ftom", "dbtorms", "rmstodb", "metro", "delay", "timer", "random", "counter",
            "float", "int", "+", "-", "*", "/", "mod", "div", "moses", "select", "route", "pack", "unpack",
            "trigger", "t", "spigot", "change", "line", "print", "send", "receive", "s", "r", "loadbang",
            "bang", "symbol", "list", "table", "array", "tabread", "tabwrite", "makenote", "stripnote",
            "notein", "noteout", "ctlin", "ctlout", "expr", "expr~", "sigmund~", "bonk~", "fiddle~",
        )
    }
}

/**
 * Read one message line — `receiver selector-or-value args…` — as [PdExecution] sends it. A trailing
 * `;` and a leading one are both allowed, as in a Pd message box. Numbers become floats, the rest
 * symbols. `freq 440` is a float; `go bang` a bang; `voice set 3 two` the message `set 3 two`.
 *
 * @return receiver, selector and arguments, or null for a line with no receiver
 */
fun parsePdMessageLine(line: String): Triple<String, String, List<Any>>? {
    val words = line.trim().removePrefix(";").removeSuffix(";").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return null
    val receiver = words[0]
    val rest = words.drop(1).map { w -> w.toFloatOrNull() ?: w }
    return when {
        rest.isEmpty() -> Triple(receiver, "bang", emptyList())
        rest[0] is Float -> Triple(receiver, if (rest.size == 1) "float" else "list", rest)
        else -> Triple(receiver, rest[0] as String, rest.drop(1))
    }
}

/** Send a message [parsePdMessageLine] read. */
internal fun sendMessage(pd: PdInstance, receiver: String, selector: String, args: List<Any>) {
    when (selector) {
        "bang" -> pd.bang(receiver)
        "float" -> pd.send(receiver, args[0] as Float)
        "list" -> pd.list(receiver, *args.toTypedArray())
        "symbol" -> pd.symbol(receiver, args.firstOrNull()?.toString() ?: "")
        else -> pd.message(receiver, selector, *args.toTypedArray())
    }
}

var Box.isPdRoot by Delegation<Boolean?>()

@RunsAt(early)
fun installPdLanguage() {
    Box.ioPersistentProperties.add("isPdRoot")
    registerMainLanguageRuntime(
        MainLanguageRuntime(
            id = "pd",
            label = "Pure Data",
            runtimeClass = PdExecution::class,
            setLegacyRoot = { box, enabled -> box.isPdRoot = enabled },
            applyTopology = ::pdTopologyNeedsUpdating,
        ),
    )
}

@RunsAt(plugins)
fun installPdLanguagePlugin() {
    TopologyWatcher("pd") { pdTopologyNeedsUpdating(it) }.start()
}

/**
 * Line notes for a Pd box: what an object is wired to, and the last value through an `[s]` or `[r]`.
 * Registered late so it is the newest provider for `code` among those that take a Pd box.
 */
@RunsAt(plugins, priority = 100)
fun installPdLineNotes() {
    LineNotes.register("code", { it.ke is PdExecution }) { box, source, line ->
        val execution = box.ke as? PdExecution
        val graph = PdPatchGraph.parse(source)
        graph.describeLine(line - 1) { name -> execution?.running?.pd?.lastValue(name) }?.let { LineNotes.Note(it) }
    }
}

fun pdTopologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { it.isPdRoot == true || it.isMainLanguage("pd") },
        runtimeClass = PdExecution::class,
        newExecution = { child -> PdExecution(child) },
        language = "Pd",
        customization = "",
        notifyCategory = "pd",
    )
}
