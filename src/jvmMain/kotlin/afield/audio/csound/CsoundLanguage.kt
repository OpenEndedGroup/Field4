@file:afield.runtime.completion.CompletionCategory("Synthesis")
package afield.audio.csound
/**
 * Csound as a box Main Language: Run, Launch and Alt-Enter on `.csd` text, with opcode completion
 * and error lines mapped back into the box.
 */


import afield.document.graph.Box
import afield.runtime.execution.BoxExecution
import afield.app.protocol.Completion
import afield.app.protocol.CompletionRequest
import afield.app.protocol.Execute
import afield.app.protocol.ExecuteSymbol
import afield.runtime.execution.ExecutionTrace
import afield.app.protocol.IsRunning
import afield.app.protocol.Message
import afield.app.protocol.Metadata
import afield.app.protocol.Output
import afield.runtime.lifecycle.RunsAt
import afield.runtime.lifecycle.Runloop
import afield.runtime.execution.configureOutputProxies
import afield.runtime.execution.dispatchExecutionOutput
import afield.runtime.lifecycle.early
import afield.audio.synth.FieldAudioEngine
import afield.audio.synth.audio
import afield.runtime.lifecycle.inMain
import afield.app.protocol.payload
import afield.runtime.lifecycle.plugins
import afield.runtime.MainLanguageRuntime
import afield.runtime.TopologyWatcher
import afield.runtime.applyRuntimeToggle
import afield.runtime.isMainLanguage
import afield.runtime.registerMainLanguageRuntime
import afield.app.protocol.send
import afield.document.properties.Delegation
import afield.ui.output.LineNotes
import afield.app.server.thisSession
import kotlinx.coroutines.yield
import java.io.Closeable

/**
 * Csound as a box language: set a box's Main Language to Csound and its children hold `.csd` text —
 * `<CsOptions>`, `<CsInstruments>`, `<CsScore>` — or a bare orchestra.
 *
 * - **Run** or **Launch** (Alt-0 runs the whole box) compiles the orchestra and score into a Csound
 *   of the box's own and plays it through Field's audio engine until the box stops or the score
 *   ends. `f 0 z` in the score keeps it running with nothing scheduled. Running again replaces it.
 * - **Alt-Enter** on a line or a selection, while the box is playing, depends on where it is:
 *   - in the orchestra, inside an `instr … endin`: that whole instrument is recompiled into the
 *     running Csound (`csoundCompileOrc`). Notes already sounding finish with the old definition;
 *     the next event uses the new one. A selection that is itself whole instruments is compiled as
 *     selected. Outside any instrument (a `giTable ftgen …` or `chn_k` line), the line or selection
 *     is compiled as global code, which runs once.
 *   - in the score: the statement is read into the running Csound now (`csoundReadScore`), so its
 *     start time counts from this moment: `i "Pluck" 0 1 440` sounds at once.
 *   - in `<CsOptions>`: nothing is sent; options apply at the next launch, and the box says so.
 * - Errors are marked on the box line they belong to, whichever of these produced them.
 * - Stop (or a new run) ends the performance and frees the Csound.
 *
 * Kotlin boxes reach a playing Csound box through [afield.audio.synth.csoundOf]: its channels, tables, events, and its
 * audio node for feeding Field signals in and taking Csound's channels out as signals.
 */
class CsoundExecution(val inside: Box) : BoxExecution {

    override val languageName: String get() = "csound"

    /** What a launch holds: the instance, its audio node and registration, and the document it came from. */
    class Running(val csound: CsoundInstance, val node: CsoundNode, val document: CsdDocument, private val closeables: List<Closeable>) : Closeable {
        override fun close() {
            closeables.forEach { runCatching { it.close() } }
            csound.close()
        }
    }

    /** The Csound playing this box, or null when it is stopped. */
    @Volatile
    var running: Running? = null
        private set

    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) =
        launchAll(inside, execute, execute.code, execute.strand, reply)

    override fun launchAll(inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit) {
        val originSession = thisSession.get()
        val trace = ExecutionTrace.forEngine(inside, execute, "launch", reply)
        inMain {
            configureOutputProxies(inside, trace.reply, originSession)
            if (!CsoundLibrary.isAvailable) {
                inside.displayError(CsoundLibrary.describe(), line = execute.line + 1, property = "code")
                trace.finish(false, "libcsound is not built")
                return@inMain
            }
            val generation = inside.runGeneration.getOrPut(strand) { -1 } + 1
            inside.runGeneration[strand] = generation
            val doc = CsdDocument.parse(code)
            val state = try {
                open(inside, doc)
            } catch (e: CsoundCompileError) {
                // One error, one message: a diagnostic placed on a line is not printed again below.
                if (e.problems.none { it.line > 0 }) for (line in e.printed) runCatching { inside.println(line) }
                for (p in e.problems) {
                    runCatching { inside.displayError(p.message, "code", if (p.line > 0) doc.orchestra.firstLine + p.line else execute.line + 1) }
                }
                trace.finish(false, "the orchestra did not compile")
                send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand)))
                return@inMain
            } catch (t: Throwable) {
                inside.displayError(t.message ?: t.javaClass.simpleName, line = execute.line + 1, property = "code")
                trace.finish(false, "Csound did not start")
                send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand)))
                return@inMain
            }
            clearErrorStatus(inside, trace.reply, originSession)
            running = state
            send(Message("is-running").payload(IsRunning(execute.uid, true, generation, strand)))
            trace.finish(true)
            inMain {
                try {
                    while (inside.runGeneration[strand] == generation && !state.csound.finished) {
                        state.csound.poll()
                        yield()
                    }
                } finally {
                    state.csound.poll()
                    if (running === state) running = null
                    state.close()
                    send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand)))
                }
            }
        }
    }

    private fun open(box: Box, doc: CsdDocument): Running {
        val engine = FieldAudioEngine.shared()
        // Audio goes to Field's engine, so the flags that pick a device or a file are left out.
        val flags = doc.optionFlags.filterNot { f -> DEVICE_FLAGS.any { f.startsWith(it) } }
        val score = doc.score?.body?.takeIf { it.isNotBlank() } ?: "f 0 z"
        val cs = CsoundInstance(doc.orchestra.body, score, sampleRate = engine.sampleRate, options = flags)
        val closeables = ArrayList<Closeable>()
        try {
            closeables += cs.onMessage { line -> runCatching { box.println(line) } }
            cs.poll()
            val node = cs.node()
            val handle = engine.register(audio(node, gain = 1f))
            closeables += Closeable { handle.close() }
            return Running(cs, node, doc, closeables)
        } catch (t: Throwable) {
            closeables.forEach { runCatching { it.close() } }
            cs.close()
            throw t
        }
    }

    /** What Alt-Enter did, for the box's output and for tests. */
    sealed class Dispatch {
        data class Instrument(val names: List<String>, val firstLine: Int) : Dispatch()
        data class Global(val firstLine: Int) : Dispatch()
        data class Score(val text: String, val firstLine: Int) : Dispatch()
        data class Options(val firstLine: Int) : Dispatch()
    }

    /**
     * Decide what Alt-Enter on [fragment], starting at 0-based box line [line] of [whole], should do,
     * and the text to send. Pure: no Csound needed, which is what makes it testable on its own.
     */
    fun dispatchFor(whole: String, fragment: String, line: Int): Pair<Dispatch, String> {
        val doc = CsdDocument.parse(whole)
        return when (doc.partAt(line)) {
            CsdDocument.Part.OPTIONS -> Dispatch.Options(line) to ""
            CsdDocument.Part.SCORE -> Dispatch.Score(fragment.trim(), line) to fragment
            else -> {
                val fragmentLines = fragment.trimEnd('\n').split("\n").size
                val selectedInstruments = doc.instruments.filter { it.startLine >= line && it.endLine <= line + fragmentLines - 1 }
                val enclosing = doc.instrumentAt(line)
                when {
                    selectedInstruments.isNotEmpty() -> Dispatch.Instrument(selectedInstruments.flatMap { it.names }, line) to fragment
                    enclosing != null -> Dispatch.Instrument(enclosing.names, enclosing.startLine) to doc.instrumentText(enclosing)
                    else -> Dispatch.Global(line) to fragment
                }
            }
        }
    }

    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        val originSession = thisSession.get()
        val trace = ExecutionTrace.forEngine(inside, execute, "fragment", reply)
        inMain {
            configureOutputProxies(inside, trace.reply, originSession)
            val whole = inside.getPropertyRaw("code") as? String ?: execute.code
            val (what, text) = dispatchFor(whole, execute.code, execute.line)
            val state = running
            if (what is Dispatch.Options) {
                ok(inside, trace.reply, originSession, execute.line + 1, "options apply when the box is launched")
                trace.finish(true)
                return@inMain
            }
            if (state == null) {
                inside.displayError("this Csound box is not playing: launch it (Alt-0), then Alt-Enter sends to it", line = execute.line + 1, property = "code")
                trace.finish(false, "not running")
                return@inMain
            }
            val (result, firstLine, done) = when (what) {
                is Dispatch.Instrument -> Triple(state.csound.compileNow(text), what.firstLine,
                    "recompiled instr ${what.names.joinToString(", ")}")
                is Dispatch.Global -> Triple(state.csound.compileNow(text), what.firstLine, "compiled as global code")
                is Dispatch.Score -> Triple(state.csound.scoreNow(text), what.firstLine,
                    "sent " + text.trim().lines().filter { it.isNotBlank() }.joinToString("; ") { it.trim() })
                is Dispatch.Options -> error("unreachable")
            }
            state.csound.poll()
            if (!result.ok || result.problems.isNotEmpty()) {
                for (p in result.problems) {
                    runCatching { inside.displayError(p.message, "code", if (p.line > 0) firstLine + p.line else execute.line + 1) }
                }
                if (!result.ok) {
                    if (result.problems.isEmpty()) inside.displayError("Csound refused it: " + result.printed.lastOrNull().orEmpty(), line = execute.line + 1, property = "code")
                    trace.finish(false, "Csound refused the fragment")
                    return@inMain
                }
            }
            ok(inside, trace.reply, originSession, execute.line + execute.code.trimEnd('\n').split("\n").size, done)
            trace.finish(true)
        }
    }

    private fun ok(inside: Box, reply: (Message) -> Unit, originSession: io.ktor.websocket.WebSocketSession?, line: Int, text: String) {
        dispatchExecutionOutput(inside, reply,
            Output(uid = inside.id, line = line, kind = "ok", html = text.replace("&", "&amp;").replace("<", "&lt;"),
                frame = Runloop.runLoop.frame.toInt()),
            originSession = originSession)
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
        val (start, rows) = completions(completionRequest.allCode, completionRequest.position) ?: return
        if (rows.isEmpty()) return
        reply(Message("complete").payload(Completion(boundsStart = start, boundsEnd = completionRequest.position.coerceIn(0, completionRequest.allCode.length),
            metadata = rows.map { r -> Metadata(icon = r.kind, tail = r.tail, documentation = r.documentation, text = r.text, displayText = r.text, category = r.category) })))
    }

    /** One completion row. */
    data class Row(val text: String, val kind: String, val tail: String, val documentation: String, val category: String = "")

    /** The rows for the cursor at [position] in [code], and where the replaced word starts. */
    fun completions(code: String, position: Int): Pair<Int, List<Row>>? {
        val cursor = position.coerceIn(0, code.length)
        val doc = CsdDocument.parse(code)
        val line = code.substring(0, cursor).count { it == '\n' }
        val lineStart = code.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let { if (it < 0 || cursor == 0) 0 else it + 1 }
        val before = code.substring(lineStart, cursor)
        // Inside a string: channel names after a bus opcode, instrument names after `i "` in the score.
        val quotes = before.count { it == '"' }
        if (quotes % 2 == 1) {
            val start = lineStart + before.lastIndexOf('"') + 1
            val prefix = code.substring(start, cursor)
            val inScore = doc.partAt(line) == CsdDocument.Part.SCORE
            val rows = if (inScore || before.trimStart().startsWith("i") && doc.partAt(line) == CsdDocument.Part.SCORE) {
                doc.instruments.flatMap { it.names }.filter { !it[0].isDigit() }.map { Row(it, "instrument", "instr", "instrument $it") }
            } else {
                doc.channelNames.map { Row(it, "channel", "channel", if (it in doc.declaredChannels) "declared with chn_ on line ${doc.declaredChannels[it]!! + 1}" else "a channel this orchestra uses") }
            }
            return start to rows.filter { it.text.startsWith(prefix) && it.text != prefix }.distinctBy { it.text }
        }
        var start = cursor
        while (start > 0 && (code[start - 1].isLetterOrDigit() || code[start - 1] == '_')) start--
        val prefix = code.substring(start, cursor)
        if (doc.partAt(line) == CsdDocument.Part.SCORE) {
            // At the start of a score statement after `i`: the instrument numbers.
            val trimmed = before.trimStart()
            if (trimmed.startsWith("i") && trimmed.length <= 1 + prefix.length + 1) {
                val rows = doc.instruments.flatMap { it.names }.map { n ->
                    val text = if (n[0].isDigit()) n else "\"$n\""
                    Row(text, "instrument", "instr", "instrument $n")
                }
                return start to rows.filter { it.text.startsWith(prefix) }
            }
            val statements = SCORE_STATEMENTS.map { (k, d) -> Row(k, "statement", "score", d) }
            return start to statements.filter { prefix.isNotEmpty() && it.text.startsWith(prefix) && it.text != prefix }
        }
        if (prefix.isEmpty()) return null
        val rows = ArrayList<Row>()
        ORCHESTRA_KEYWORDS.filter { it.startsWith(prefix) && it != prefix }.forEach { rows += Row(it, "keyword", "keyword", "Csound keyword") }
        for (op in CsoundVocabulary.committed.opcodes) {
            if (!op.name.startsWith(prefix) || op.name == prefix) continue
            rows += Row(op.name, "opcode", opcodeTail(op), opcodeDocumentation(op), OPCODE_CATEGORY)
        }
        return start to rows.sortedWith(compareBy({ it.text.length }, { it.text })).take(200)
    }

    private fun clearErrorStatus(inside: Box, reply: (Message) -> Unit, originSession: io.ktor.websocket.WebSocketSession?) {
        dispatchExecutionOutput(inside, reply,
            Output(uid = inside.id, line = -1, kind = "err", property = "code", html = "", frame = Runloop.runLoop.frame.toInt()),
            originSession = originSession)
    }

    companion object {
        /** The completion group Csound opcodes are listed under. Not in fieldSuggest's CATEGORY_WEIGHT, so it ranks as "other" did. */
        const val OPCODE_CATEGORY = "csound-opcodes"

        /**
         * The row's detail: the manual's shortest syntax line, which names its arguments the way the
         * manual's argument notes do (`ares poscil aamp, acps [, ifn, iphs]`); libcsound's spelled-out
         * signature when the manual has no entry.
         */
        fun opcodeTail(op: CsoundVocabulary.Opcode): String =
            op.syntax.minByOrNull { it.length } ?: CsoundVocabulary.describe(op.name, op.signatures.first())

        /**
         * The detail panel, as markdown (fieldSuggest renders it with aiChatMarkdownToHtml, as it does
         * every language's documentation): the description, the syntax lines in a code block, the
         * arguments one per line, the manual's section and the link to the page.
         */
        fun opcodeDocumentation(op: CsoundVocabulary.Opcode): String = buildString {
            op.description?.let { append(it).append("\n\n") }
            val syntax = op.syntax.ifEmpty { op.signatures.map { CsoundVocabulary.describe(op.name, it) } }
            append("```csound\n").append(syntax.joinToString("\n")).append("\n```\n\n")
            if (op.arguments.isNotEmpty()) {
                for (a in op.arguments.take(6)) append("- `").append(a.name).append("` — ").append(a.text).append("\n")
                append("\n")
            }
            if (op.page != null) {
                op.category?.let { append(it).append(" · ") }
                append("[").append(CsoundVocabulary.manualUrl(op.page).removePrefix("https://")).append("](").append(CsoundVocabulary.manualUrl(op.page)).append(")")
            } else {
                append("Not in the Csound manual ").append(CsoundVocabulary.MANUAL_TAG).append("; signatures from libcsound.")
            }
        }.trimEnd()

        /** Flags that pick an audio or MIDI device or a file; Field's engine is the device. */
        val DEVICE_FLAGS = listOf("-o", "-i", "-+rtaudio", "-+rtmidi", "-M", "-Q", "-b", "-B", "--output", "--input", "-W", "-A", "-n", "-d")

        /** The orchestra's own words: everything that is syntax rather than an opcode. */
        val ORCHESTRA_KEYWORDS = listOf(
            "instr", "endin", "opcode", "endop", "if", "then", "ithen", "kthen", "elseif", "else", "endif",
            "while", "until", "do", "od", "goto", "igoto", "kgoto", "sr", "kr", "ksmps", "nchnls", "nchnls_i", "0dbfs",
            "A4", "struct", "void",
        )

        /** Score statements, with what each does. */
        val SCORE_STATEMENTS = listOf(
            "i" to "i p1 p2 p3 …: play instrument p1 at time p2 for p3 beats",
            "f" to "f n time size gen args…: make function table n",
            "t" to "t 0 bpm …: tempo",
            "e" to "e: end of score",
            "s" to "s: end of section",
            "a" to "a 0 start duration: advance (skip) score time",
            "b" to "b time: time offset for following events",
            "v" to "v factor: warp the next section's times",
            "q" to "q p1 time 0|1: mute or unmute an instrument",
            "r" to "r count macro: repeat a section",
            "m" to "m name: mark a section",
            "n" to "n name: repeat a marked section",
            "x" to "x: skip the rest of a section",
            "y" to "y seed: seed the score's random numbers",
            "d" to "d p1 time: turn off a held note",
        )
    }
}

var Box.isCsoundRoot by Delegation<Boolean?>()

@RunsAt(early)
fun installCsoundLanguage() {
    Box.ioPersistentProperties.add("isCsoundRoot")
    registerMainLanguageRuntime(
        MainLanguageRuntime(
            id = "csound",
            label = "Csound",
            runtimeClass = CsoundExecution::class,
            setLegacyRoot = { box, enabled -> box.isCsoundRoot = enabled },
            applyTopology = ::csoundTopologyNeedsUpdating,
        ),
    )
}

@RunsAt(plugins)
fun installCsoundLanguagePlugin() {
    TopologyWatcher("csound") { csoundTopologyNeedsUpdating(it) }.start()
}

/** Line notes for a Csound box. Registered late, so it is the newest provider for a Csound box's `code`. */
@RunsAt(plugins, priority = 100)
fun installCsoundLineNotes() {
    LineNotes.register("code", { it.ke is CsoundExecution }) { box, source, line ->
        csoundLineNote(CsdDocument.parse(source), line - 1, (box.ke as? CsoundExecution)?.running?.csound)
    }
}

/**
 * The note for 0-based box line [line], or null. Everything here is read from the text, except a
 * channel's value, which is read from the Csound that is [playing] (and marked volatile):
 *
 * - an `instr` line: how many `i` statements in this box's score play it, and on which lines;
 * - a score `i` line: which instrument it plays and where that is defined, or that there is none;
 * - a `chn_k`, `chnget`, `chnset`, `invalue` or `outvalue` line: the channel's value now.
 */
fun csoundLineNote(doc: CsdDocument, line: Int, playing: CsoundInstance?): LineNotes.Note? {
    val text = doc.text.split("\n").getOrNull(line) ?: return null
    val code = CsdDocument.stripComment(text).trim()
    if (code.isEmpty()) return null
    when (doc.partAt(line)) {
        CsdDocument.Part.ORCHESTRA -> {
            doc.instruments.firstOrNull { it.startLine == line }?.let { ins ->
                val events = doc.events.filter { it.instrument in ins.names }
                val name = ins.names.joinToString(", ")
                if (doc.score == null) return LineNotes.Note("instr $name", meta = "no score in this box",
                    value = listOf(LineNotes.plain("instr "), LineNotes.strong(name)))
                val where = events.map { it.line + 1 }.distinct()
                val caption = "instr $name: ${events.size} event${if (events.size == 1) "" else "s"} in the score"
                val value = listOf(LineNotes.plain("instr $name: "), LineNotes.strong("${events.size}"),
                    LineNotes.plain(" event${if (events.size == 1) "" else "s"} in the score"))
                return LineNotes.Note(caption, meta = if (where.isEmpty()) "none: Alt-Enter an i statement to play it"
                    else "line${if (where.size == 1) "" else "s"} ${where.take(8).joinToString(", ")}${if (where.size > 8) ", …" else ""}", value = value)
            }
            val channel = Regex(""""([^"]+)"""").findAll(code).map { it.groupValues[1] }.firstOrNull { it in doc.channelNames }
            if (channel != null && Regex("""\b(chn_[kaS]|chnget|chnset|invalue|outvalue)\b""").containsMatchIn(code)) {
                if (playing == null || playing.isClosed) return LineNotes.Note("channel \"$channel\"", volatile = true,
                    meta = "not playing: launch the box to see its value", value = listOf(LineNotes.plain("channel "), LineNotes.strong("\"$channel\"")))
                val isString = code.contains("chn_S") || Regex("""\bS\w*\s+chnget""").containsMatchIn(code)
                val audio = code.contains("chn_a") || Regex("""\ba\w*\s+chnget|chnset\s+a""").containsMatchIn(code)
                if (audio) return LineNotes.Note("channel \"$channel\" (audio)", volatile = true, meta = "an a-rate channel",
                    value = listOf(LineNotes.plain("\"$channel\" "), LineNotes.strong("audio")))
                val shown = if (isString) "\"" + playing.stringChannel(channel) + "\"" else "%.4g".format(playing.channel(channel))
                val out = playing.outvalue(channel)
                return LineNotes.Note("\"$channel\" = $shown", volatile = true, meta = "the channel's value, live" + (out?.let { " · last outvalue $it" } ?: ""),
                    value = listOf(LineNotes.plain("\"$channel\" = "), LineNotes.strong(shown)))
            }
            return null
        }
        CsdDocument.Part.SCORE -> {
            val ev = doc.events.firstOrNull { it.line == line } ?: return null
            val ins = doc.instruments.firstOrNull { ev.instrument in it.names }
                ?: return LineNotes.Note("no instrument ${ev.instrument} in this orchestra", meta = "this event will be refused",
                    value = listOf(LineNotes.plain("no instrument "), LineNotes.strong(ev.instrument)))
            return LineNotes.Note("plays instr ${ev.instrument}, defined on line ${ins.startLine + 1}", meta = "Alt-Enter sends it now",
                value = listOf(LineNotes.plain("plays "), LineNotes.strong("instr ${ev.instrument}"), LineNotes.plain(" · line ${ins.startLine + 1}")))
        }
        else -> return null
    }
}

fun csoundTopologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { it.isCsoundRoot == true || it.isMainLanguage("csound") },
        runtimeClass = CsoundExecution::class,
        newExecution = { child -> CsoundExecution(child) },
        language = "Csound",
        customization = "",
        notifyCategory = "csound",
    )
}
