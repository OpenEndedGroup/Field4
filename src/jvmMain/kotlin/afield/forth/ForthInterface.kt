package afield.forth

import afield.*
import afield.logger.lprintln
import afield.runtime.TopologyWatcher
import afield.runtime.MainLanguageRuntime
import afield.runtime.applyRuntimeToggle
import afield.runtime.isMainLanguage
import afield.runtime.registerMainLanguageRuntime
import afield.stdlib.Delegation
import io.ktor.websocket.WebSocketSession

/**
 * Makes ForthField a language a box can be written in.
 *
 * `ForthInterpreter.kt` is the language itself. This file is the half that joins it to the
 * workspace: the `BoxExecution` a ForthField box runs under, and the registration that puts
 * "Set Main Language: ForthField" in front of a user.
 *
 * #### Running code
 *
 * [ForthExecution] answers the execution contract a box's editor talks to. Running a fragment,
 * or the whole box, evaluates the source once and reports each diagnostic on the line that produced
 * it, printing `ok` — with what the run left on the stack, the top value in bold — when a run
 * finished cleanly and produced no visible output of its own.
 * Completion offers the dictionary's words, each with the kind and the documentation the
 * interpreter holds for it. Executing a symbol runs one named word as a single-line fragment.
 *
 * Launching is the one that is more than an evaluation. The code runs once to define its words, and
 * if its last token names a colon definition, that word becomes a *strand*: a coroutine that
 * re-executes it until the box's run generation for that strand moves on, yielding between passes.
 * `frame` is available only there, and it ends a pass rather than the strand, so a word can be
 * written as the body of a per-frame loop. Anything else the code ends with defines words and stops.
 * Stopping the box cancels every strand job and bumps every run generation.
 *
 * #### Becoming a ForthField box
 *
 * A box does not choose this language for itself; its parent does. `installForth` registers a
 * `MainLanguageRuntime` under the id `forth`, and makes the `isForthRoot` flag one of the
 * properties a document persists. `installForthPlugin` starts a `TopologyWatcher`, and
 * `forthTopologyNeedsUpdating` is what that watcher calls on every graph edit: each child of a box
 * flagged `isForthRoot`, or set to the `forth` main language, is given a `ForthExecution`, and a
 * child left with no such parent goes back to Kotlin. Each child gets an interpreter of its own, so
 * a stack and a dictionary belong to one box and outlive the run that filled them.
 */

/**
 * The execution a ForthField box runs under: one [ForthInterpreter], and the editor protocol
 * around it.
 */
class ForthExecution(val inside: Box) : BoxExecution {

    /**
     * This box's interpreter, holding the stack, the dictionary and the variables.
     *
     * One per box and never replaced, so everything a run leaves behind is there for the next one.
     */
    val forth = ForthInterpreter()

    // Strand jobs: strandName -> Job (for run-loop integration)
    private val strandJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    private fun configureForthOutput(
        inside: Box,
        executeLine: Int,
        reply: (Message) -> Unit,
        originSession: WebSocketSession?,
        allowFrameYield: Boolean
    ): () -> Boolean {
        var hadVisibleOutput = false
        configureOutputProxies(inside, reply, originSession)
        inside.output.displayProxy = { channel, content ->
            hadVisibleOutput = true
            send(Message("set-output").payload(Set_output(inside.id, "outputarea", channel, content.toString())))
        }
        forth.onOutput = { text ->
            if (text.isNotBlank()) hadVisibleOutput = true
            inside.output.proxy(text, text.endsWith("\n"), executeLine)
        }
        forth.onDisplay = { channel, content ->
            hadVisibleOutput = true
            inside.output.display(channel, content)
        }
        forth.onFrame = if (allowFrameYield) {
            { throw ForthFrameYield() }
        } else {
            { throw ForthError("'frame' is only available in launch/strand execution") }
        }
        return { hadVisibleOutput }
    }

    private fun clearExecutionErrorStatus(reply: (Message) -> Unit, originSession: WebSocketSession?) {
        dispatchExecutionOutput(
            inside,
            reply,
            Output(
                uid = inside.id,
                line = -1,
                kind = "err",
                property = "code",
                html = "",
                frame = Runloop.runLoop.frame.toInt()
            ),
            originSession = originSession
        )
    }

    /**
     * Evaluate [execute]'s code once, with output and errors routed back to the box.
     *
     * A clean run that printed nothing of its own gets an `ok` marker on the line after the
     * fragment, so that a run is always visibly a run. A run with diagnostics gets each of them on
     * the line that produced it, counted from the fragment's own first line. `frame` is refused
     * here: it belongs to `launchAll`.
     */
    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        evalOnce(inside, execute, "fragment", reply)
    }

    /** One `forth.eval` of the box, reported under [mode]. See DatalogExecution.runOnce. */
    private fun evalOnce(inside: Box, execute: Execute, mode: String, reply: (Message) -> Unit) {
        val originSession = thisSession.get()
        val trace = ExecutionTrace.forEngine(inside, execute, mode, reply)
        inMain {
            val wm = ++inside.fragmentGeneration
            send(Message("is-running").payload(IsRunning(execute.uid, true, wm, "__fragment__")))
            val hadVisibleOutput = configureForthOutput(
                inside = inside,
                executeLine = execute.line,
                reply = trace.reply,
                originSession = originSession,
                allowFrameYield = false
            )
            var ok = false
            try {
                val diagnostics = forth.evalRecordingLineNotes(inside, execute.code, execute.line)
                if (diagnostics.isEmpty()) {
                    clearExecutionErrorStatus(trace.reply, originSession)
                }
                if (diagnostics.isEmpty() && !hadVisibleOutput()) {
                    dispatchExecutionOutput(
                        inside,
                        trace.reply,
                        Output(
                            uid = inside.id,
                            line = execute.line + execute.code.split("\n").size,
                            kind = "ok",
                            html = forthOkHtml(forth.stackTop(FORTH_NOTE_VALUES), forth.stack.size),
                            frame = Runloop.runLoop.frame.toInt()
                        ),
                        originSession = originSession
                    )
                } else if (diagnostics.isNotEmpty()) {
                    diagnostics.forEach { diag ->
                        val errLine = if (diag.line >= 0) execute.line + diag.line else execute.line
                        inside.error(diag.message, line = errLine, property = "code")
                    }
                }
                ok = diagnostics.isEmpty()
            } finally {
                trace.finish(ok)
                send(Message("is-running").payload(IsRunning(execute.uid, false, wm, "__fragment__")))
            }
        }
    }

    /**
     * Run the whole box, which for ForthField is `executeFragment` over all of it.
     *
     * The interpreter keeps its stack and dictionary between calls either way, so there is nothing
     * for a whole-box run to reset that a fragment run would not.
     */
    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        evalOnce(inside, execute, "run", reply)
    }

    /**
     * Run [code] once, and then keep running its last word every pass if that word is a colon
     * definition.
     *
     * The single run defines the words and reports any diagnostic from doing so, and stops there if
     * it found one. Otherwise the last token decides: a name the dictionary holds as a colon
     * definition becomes the strand's body, and anything else leaves the definitions in place
     * without starting anything. A second launch of the same [strand] cancels the first.
     *
     * The strand is a coroutine that calls the word until this box's run generation for [strand]
     * changes, yielding between passes. `frame` inside the word ends a pass early rather than the
     * strand, which is how a word is written as a per-frame body. A ForthField error stops the
     * strand and is reported on its line.
     */
    override fun launchAll(
        inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit
    ) {
        val originSession = thisSession.get()
        val trace = ExecutionTrace.forEngine(inside, execute, "launch", reply)
        inMain {
            val wm = ++inside.fragmentGeneration
            configureForthOutput(
                inside = inside,
                executeLine = execute.line,
                reply = trace.reply,
                originSession = originSession,
                allowFrameYield = true
            )

            // First execute the code to define any words
            val setupDiags = forth.evalRecordingLineNotes(inside, code, execute.line)
            if (setupDiags.isNotEmpty()) {
                setupDiags.forEach { diag ->
                    val errLine = if (diag.line >= 0) execute.line + diag.line else execute.line
                    inside.error(diag.message, line = errLine, property = "code")
                }
                trace.finish(false, "the box did not define its words without a diagnostic")
                send(Message("is-running").payload(IsRunning(execute.uid, false, wm, strand)))
                return@inMain
            }

            clearExecutionErrorStatus(trace.reply, originSession)

            // If code ends with a word call (last non-whitespace token), register it as a strand
            val tokens = forth.tokenize(code.trim())
            val lastToken = tokens.lastOrNull()
            val lastWordName = lastToken?.text?.lowercase()

            val strandWord = when {
                lastWordName != null && forth.dictionary.containsKey(lastWordName) &&
                    forth.dictionary[lastWordName] is ForthWord.Compiled -> lastWordName
                else -> null
            }

            if (strandWord != null) {
                // Remove previous strand job for this strand name
                strandJobs[strand]?.cancel()

                val generation = inside.runGeneration.getOrPut(strand) { -1 } + 1
                inside.runGeneration[strand] = generation

                send(Message("is-running").payload(IsRunning(execute.uid, true, generation, strand)))

                val job = inMain {
                    forth.addStrand(strand, strandWord)
                    try {
                        while (inside.runGeneration[strand] == generation) {
                            try {
                                val fw = forth.dictionary[strandWord]
                                if (fw != null) forth.execWord(fw)
                            } catch (_: ForthFrameYield) {
                                // Cooperative per-tick yield requested by `frame`.
                            } catch (e: ForthError) {
                                val diag = forth.diagnosticFor(e)
                                inside.error(diag.message, line = diag.line, property = "code")
                                break
                            } catch (e: Throwable) {
                                lprintln("error,forth", e.stackTraceToString())
                                inside.error(
                                    e.message ?: e.javaClass.simpleName,
                                    line = if (forth.currentLine >= 0) forth.currentLine else -1,
                                    property = "code"
                                )
                                break
                            }
                            kotlinx.coroutines.yield()
                        }
                    } finally {
                        forth.removeStrand(strand)
                        send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand)))
                    }
                }
                strandJobs[strand] = job
            } else {
                send(Message("is-running").payload(IsRunning(execute.uid, false, wm, strand)))
            }
            // The words are defined and the strand, if the last token named one, is running. A
            // box whose last token is not a colon definition is a successful launch that simply
            // started nothing, which is what this engine's KDoc above says it should be.
            trace.finish(true)
        }
    }

    /**
     * Stop every strand this box is running.
     *
     * The jobs are cancelled, the interpreter's strand table is emptied, and every run generation
     * is bumped, so a pass already in flight finds its generation stale and does not start another.
     * The dictionary and the stack are left alone.
     */
    override fun stop(inside: Box) {
        strandJobs.forEach { (_, job) -> job.cancel() }
        strandJobs.clear()
        forth.strands.clear()
        inside.runGeneration.keys.toList().forEach { strand ->
            inside.runGeneration[strand] = (inside.runGeneration[strand] ?: 0) + 1
        }
    }

    /**
     * Complete the word being typed against this box's dictionary.
     *
     * The prefix is whatever non-whitespace run ends at the cursor, which is all a Forth needs:
     * every word is whitespace-delimited. Each candidate carries its kind (builtin, word, variable,
     * constant, lambda) as both icon and tail, and a colon definition shows its own body as
     * documentation. Nothing is sent when nothing matches.
     */
    override fun complete(inside: Box, completionRequest: CompletionRequest, reply: (Message) -> Unit) {
        val code = completionRequest.allCode
        val cursor = completionRequest.position.coerceIn(0, code.length)

        val completions = forth.complete(code, cursor)
        if (completions.isNotEmpty()) {
            // Extract the prefix start for bounds
            var start = cursor
            while (start > 0 && !code[start - 1].isWhitespace()) start--

            reply(
                Message("complete").payload(
                    Completion(
                        boundsStart = start,
                        boundsEnd = cursor,
                        metadata = completions.map { c ->
                            Metadata(
                                icon = c.kind,
                                tail = c.kind,
                                documentation = c.documentation,
                                text = c.word,
                                displayText = c.word
                            )
                        }
                    )
                )
            )
        }
    }

    /**
     * Run one named word, as though its name had been typed and evaluated on its own line.
     *
     * The editor sends this when a word in the source is run by itself. The symbol text is
     * evaluated rather than looked up, so it takes the same path as any other fragment and reports
     * the same way.
     */
    override fun executeSymbol(inside: Box, executeSymbol: ExecuteSymbol, reply: (Message) -> Unit) {
        val exec = Execute(
            uid = executeSymbol.uid,
            type = "fragment",
            line = executeSymbol.line,
            code = executeSymbol.symbol,
            singleLine = true
        )
        executeFragment(inside, exec, reply)
    }

    /** `forth`, the name this execution reports itself under. */
    override val languageName: String get() = "forth"
}

/**
 * [ForthInterpreter.eval] of [code], recording for [box]'s line notes ([afield.stdlib.LastRunNotes])
 * the data stack after each line — after the last top-level step that ended on it — the words each
 * line defined, the line a failure stopped on and the lines that never ran. [lineOffset] is where
 * [code] starts in the box, as `Execute.line` counts.
 *
 * A note is read back from this record rather than by evaluating the program up to the line, because
 * a Forth prefix is not side-effect free: the stack and the variables outlive the run, and words
 * print, display and reach into the JVM.
 */
fun ForthInterpreter.evalRecordingLineNotes(box: Box, code: String, lineOffset: Int): List<ForthDiagnostic> {
    // Only the top of the stack is kept, and only once per line: copying the whole stack after every
    // step made a deep stack quadratic (200 lines, 200 deep: 180 ms), and a line's note is about the
    // stack as the line leaves it.
    class Span(val first: Int, val last: Int, val top: List<String>, val depth: Int, val defined: List<String>)
    val spans = ArrayList<Span>()
    val pendingNames = ArrayList<String>()
    var pendingFirst = -1
    onTopLevelStep = { first, last, defined, endsLine ->
        // A step that starts on a line an earlier step ended on joins it: a line's note is about all of it.
        if (pendingFirst < 0) pendingFirst = first
        while (spans.isNotEmpty() && spans.last().last >= first) {
            val earlier = spans.removeAt(spans.size - 1)
            pendingFirst = minOf(pendingFirst, earlier.first)
            pendingNames.addAll(0, earlier.defined)
        }
        defined?.let { pendingNames += it }
        if (endsLine) {
            spans += Span(pendingFirst, last, stackTop(afield.stdlib.LineNotePictures.SHOWN), stack.size, pendingNames.toList())
            pendingNames.clear()
            pendingFirst = -1
        }
    }
    val diagnostics = try { eval(code) } finally { onTopLevelStep = null }
    val stopped = diagnostics.firstOrNull()?.line?.takeIf { it >= 1 }
    runCatching {
        afield.stdlib.LastRunNotes.record(
            box, "code", code, lineOffset,
            produced = spans.filter { stopped == null || it.last < stopped }.map { span -> forthLineNote(span.first, span.last, span.top, span.depth, span.defined) },
            stoppedAt = stopped,
            stopReason = diagnostics.firstOrNull()?.message?.lineSequence()?.firstOrNull(),
            notReached = if (stopped == null) emptyList() else tokenize(code).map { it.line }.filter { it > stopped }.distinct(),
        )
    }.onFailure { lprintln("warning,forth", "could not record line notes: $it") }
    return diagnostics
}

/** How many of the top stack values a note's sentence names. */
private const val FORTH_NOTE_VALUES = 6

/** The note for lines [first]..[last]: the stack they left, [top] of it [depth] deep, and the words they [defined]. */
internal fun forthLineNote(first: Int, last: Int, top: List<String>, depth: Int, defined: List<String>): afield.stdlib.LastRunNotes.Produced {
    val named = top.takeLast(FORTH_NOTE_VALUES)
    val more = depth > named.size
    val value = ArrayList<afield.stdlib.LineNotes.Segment>()
    if (defined.isNotEmpty()) {
        value += afield.stdlib.LineNotes.plain("defines ")
        defined.forEachIndexed { i, name -> if (i > 0) value += afield.stdlib.LineNotes.plain(", "); value += afield.stdlib.LineNotes.strong(name) }
        value += afield.stdlib.LineNotes.plain(" · ")
    }
    if (depth == 0) value += afield.stdlib.LineNotes.plain("the stack is empty")
    else {
        value += afield.stdlib.LineNotes.plain("the stack after it: <$depth> " + if (more) "… " else "")
        named.forEachIndexed { i, v -> if (i > 0) value += afield.stdlib.LineNotes.plain(" "); value += afield.stdlib.LineNotes.strong(afield.stdlib.LastRunNotes.oneLine(v, 40)) }
    }
    val caption = value.joinToString("") { it.text }
    return afield.stdlib.LastRunNotes.Produced(first, last, caption, value = value,
        picture = if (defined.isEmpty() || depth > 0) ({ afield.stdlib.LineNotePictures.stack(top, depth) }) else null,
        imageScale = afield.stdlib.LineNotePictures.SCALE)
}

/**
 * The marker a clean ForthField run that printed nothing leaves after its last line: `ok`, and what the
 * run left on the stack — the depth and the top values, the topmost in bold — so a run shows its result
 * rather than suggesting nothing is left. A bare `ok` when the stack is empty. HTML, escaped.
 */
internal fun forthOkHtml(top: List<String>, depth: Int): String {
    if (depth == 0) return "ok"
    fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    val named = top.takeLast(FORTH_NOTE_VALUES).map { esc(afield.stdlib.LastRunNotes.oneLine(it, 40)) }
    val values = named.mapIndexed { i, v -> if (i == named.lastIndex) "<b>$v</b>" else v }.joinToString(" ")
    // Starting with a tag is what makes the editor render it as HTML (an inline marker is plain text).
    return "<span class=\"afield-forth-ok\">ok · &lt;$depth&gt; " + (if (depth > named.size) "… " else "") + values + "</span>"
}

/**
 * Marks a box as a ForthField root, which makes its children ForthField boxes.
 *
 * The language registry knows this as the legacy root flag and sets it alongside `mainLanguage`, so
 * a box switched with the Set Main Language action carries both. Both are persisted with the
 * document, and `forthTopologyNeedsUpdating` accepts either.
 */
var Box.isForthRoot by Delegation<Boolean?>()

/**
 * Register ForthField as a main language, and make `isForthRoot` a property documents keep.
 *
 * Registering is what puts "Set Main Language: ForthField" among a root box's actions, and what
 * lets `refreshMainLanguageRuntimes` apply the toggle synchronously instead of leaving it to the
 * watcher below.
 */
@RunsAt(early)
fun installForth() {
    Box.ioPersistentProperties.add("isForthRoot")
    registerMainLanguageRuntime(
        MainLanguageRuntime(
            id = "forth",
            label = "ForthField",
            runtimeClass = ForthExecution::class,
            setLegacyRoot = { box, enabled -> box.isForthRoot = enabled },
            applyTopology = ::forthTopologyNeedsUpdating,
        ),
    )
}

/** Start the watcher that re-applies `forthTopologyNeedsUpdating` whenever the graph changes. */
@RunsAt(plugins)
fun installForthPlugin() {
    TopologyWatcher("forth") { forthTopologyNeedsUpdating(it) }.start()
}

/**
 * Give every child of a ForthField root a `ForthExecution`, and take it back from a child whose
 * roots have all been switched off.
 *
 * A box is a root if `isForthRoot` is set or its main language is `forth`. A child that already
 * runs ForthField is left with the interpreter it has, so editing elsewhere in the graph does not
 * cost a box its stack. A child that is losing the language reverts to Kotlin, unless another root
 * still claims it, which is what lets a box be the child of two roots at once.
 */
fun forthTopologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { it.isForthRoot == true || it.isMainLanguage("forth") },
        runtimeClass = ForthExecution::class,
        newExecution = { child -> ForthExecution(child) },
        language = "Forth",
        customization = "",
        notifyCategory = "forth"
    )
}
