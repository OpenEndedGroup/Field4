package afield.ml

import afield.Box
import afield.BoxExecution
import afield.CachedLaunchReturn
import afield.CompletionRequest
import afield.Execute
import afield.ExecuteSymbol
import afield.HasOutput
import afield.IsRunning
import afield.Message
import afield.Output
import afield.ReturnValueRegistry
import afield.RunningState
import afield.Runloop
import afield.RunsAt
import afield.SymbolContextRequest
import afield.SymbolContextResponse
import afield.configureOutputProxies
import afield.dispatchExecutionOutput
import afield.early
import afield.inMain
import afield.ml.core.Evaluator
import afield.ml.core.fieldMLValueToHost
import afield.ml.core.renderFieldMLValue
import afield.payload
import afield.plugins
import afield.runtime.TopologyWatcher
import afield.runtime.MainLanguageRuntime
import afield.runtime.applyRuntimeToggle
import afield.runtime.isMainLanguage
import afield.runtime.registerMainLanguageRuntime
import afield.send
import afield.captureLaunchEpoch
import afield.isLaunchEpochCurrent
import afield.stopTrackedJobs
import afield.stdlib.Delegation
import afield.thisSession
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CancellationException

/**
 * FieldML as a box language: set a box's Main Language to FieldML and its children hold FieldML
 * programs instead of Kotlin.
 *
 * [FieldMLExecution] is the `BoxExecution` behind that, one per box. It hands the text to
 * [FieldMLCompiler], evaluates the core tree that came back against [FieldMLRuntimePrelude], and
 * writes the value and its inferred type into the box as one inline `value : type` line. A program
 * that printed something of its own gets no inline line, which is what the file's `TrackingOutput`
 * is watching for. `display` does not count as printing, so a box that draws still says what it
 * evaluated to.
 *
 * Nothing survives between runs. Each run compiles its text from scratch and evaluates it against
 * a freshly built prelude, so a FieldML box is the script model rather than the accumulating one a
 * Datalog box uses. State that has to outlive a run goes in a box property, which is what the
 * prelude's `prop` and `once` are for.
 *
 * Executing and launching differ in one thing, and it is the thing that makes animation work. Both
 * compile and evaluate. Launch also hands the result to `ReturnValueRegistry`, the same machinery a
 * Kotlin box's return value goes through, so the `Sequence` that `animate` returns is advanced once
 * per run loop frame for as long as the box is running, while a plain number is simply the answer.
 * The run generation counted around that is how a second launch supersedes the first rather than
 * running beside it.
 *
 * Which boxes are FieldML is decided by `fieldMLTopologyNeedsUpdating`, and the answer is about
 * parents: a box runs FieldML because a *parent* carries `isFieldMLRoot` or names `fieldml` as its
 * main language, not because it carries the mark itself.
 */

private const val FIELDML_PENDING_LAUNCH_MODE = "launching"
private const val FIELDML_ACTIVE_LAUNCH_MODE = "launch"

/**
 * One FieldML box's runtime: compile, evaluate, and report back to the editor, holding nothing
 * between runs and hopping to the main thread before each one.
 */
class FieldMLExecution(private val owner: Box) : BoxExecution {
    /**
     * Run the selected text and print what it evaluated to.
     *
     * The FieldML runtime draws no distinction between a fragment and the whole box: both compile
     * and evaluate the text they were handed, and only the caller decides how much of the box that
     * text is.
     */
    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        executeOnce(inside, execute, reply, fragment = true)
    }

    /** Run the whole box and print what it evaluated to, without offering the result to the launch machinery. */
    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        executeOnce(inside, execute, reply, fragment = false)
    }

    /**
     * Run the whole box and keep whatever the result turns out to be alive.
     *
     * Beyond what [executeAll] does, the value is converted to its host form and offered to
     * `ReturnValueRegistry`, so a `Sequence` from `animate` runs a frame at a time until the box is
     * stopped or launched again. The `is-running` messages sent around that are what the editor
     * draws as the box's running indicator, and they carry a generation per strand so a relaunch
     * cancels its predecessor instead of doubling it.
     *
     * A result no handler claims is not an error; it just means there is nothing left to run, and
     * the cached return value is dropped so a downstream box does not read a stale one.
     */
    override fun launchAll(inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit) {
        val originSession = thisSession.get()
        val launchEpoch = inside.captureLaunchEpoch()
        inMain {
            if (!inside.isLaunchEpochCurrent(launchEpoch)) return@inMain
            configureOutputProxies(inside, reply, originSession)
            val compileResult = FieldMLCompiler.compile(code)
            if (!reportDiagnostics(inside, execute, compileResult, originSession, reply)) {
                val generation = inside.runGeneration.getOrPut(strand) { -1 } + 1
                inside.runGeneration[strand] = generation
                inside.currentRunningStates[strand] = RunningState(generation, FIELDML_PENDING_LAUNCH_MODE)
                send(Message("is-running").payload(IsRunning(execute.uid, true, generation, strand, FIELDML_PENDING_LAUNCH_MODE)))

                val core = compileResult.core!!
                try {
                    val output = TrackingOutput(inside.output)
                    val value = Evaluator(FieldMLRuntimePrelude.values(output, inside)).evaluate(core)
                    val typeText = afield.ml.type.TypePrinter().print(compileResult.typed!!.type)
                    val valueText = renderFieldMLValue(value)
                    if (!output.printed) {
                        dispatchExecutionOutput(
                            inside,
                            reply,
                            Output(
                                uid = inside.id,
                                line = fieldMLResultLine(execute.line, compileResult),
                                kind = "normal",
                                property = execute.property,
                                html = renderInlineResult(valueText, typeText),
                                frame = Runloop.runLoop.frame.toInt(),
                            ),
                            originSession = originSession,
                        )
                    }
                    if (inside.currentRunningStates[strand]?.watermark == generation) {
                        inside.currentRunningStates.remove(strand)
                    }
                    send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand, FIELDML_PENDING_LAUNCH_MODE)))

                    val hostValue = fieldMLValueToHost(value)
                    if (hostValue == null) {
                        inside.cachedLaunchReturns.remove(strand)
                    } else {
                        inside.cachedLaunchReturns[strand] = CachedLaunchReturn(code, hostValue)
                    }

                    val consumed = ReturnValueRegistry.launchWithGeneration(
                        inside,
                        hostValue,
                        strand,
                        generation,
                        launchEpoch,
                    ) { isRunning, notifiedStrand, watermark ->
                        if (isRunning) {
                            inside.currentRunningStates[notifiedStrand] = RunningState(watermark, FIELDML_ACTIVE_LAUNCH_MODE)
                        } else if (inside.currentRunningStates[notifiedStrand]?.watermark == watermark) {
                            inside.currentRunningStates.remove(notifiedStrand)
                        }
                        send(Message("is-running").payload(IsRunning(execute.uid, isRunning, watermark, notifiedStrand, FIELDML_ACTIVE_LAUNCH_MODE)))
                    }
                    if (!consumed) {
                        inside.cachedLaunchReturns.remove(strand)
                    }
                } catch (_: CancellationException) {
                    if (inside.currentRunningStates[strand]?.watermark == generation) {
                        inside.currentRunningStates.remove(strand)
                    }
                    send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand, FIELDML_PENDING_LAUNCH_MODE)))
                } catch (t: Throwable) {
                    if (inside.currentRunningStates[strand]?.watermark == generation) {
                        inside.currentRunningStates.remove(strand)
                    }
                    send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand, FIELDML_PENDING_LAUNCH_MODE)))
                    inside.output.error(fieldMLErrorMessage(t), execute.property, execute.line)
                }
            }
        }
    }

    /** Run one symbol the editor asked about, as a single-line fragment, and print what it evaluated to. */
    override fun executeSymbol(inside: Box, executeSymbol: ExecuteSymbol, reply: (Message) -> Unit) {
        executeFragment(
            inside,
            Execute(
                uid = executeSymbol.uid,
                type = "fragment",
                line = executeSymbol.line,
                code = executeSymbol.symbol,
                singleLine = true,
                property = "code",
            ),
            reply,
        )
    }

    /**
     * Stop whatever this box launched.
     *
     * There is no FieldML-specific state to tear down, so this is the standard box stop: every
     * strand's generation is bumped past what is running, which is the condition the launch
     * handlers loop on, and the box's registered jobs and stop hooks are drained.
     */
    override fun stop(inside: Box) {
        inside.stopTrackedJobs()
    }

    /** Answer the editor's completion request from [FieldMLCompletionEngine], which reads the whole box rather than this execution's state. */
    override fun complete(inside: Box, completionRequest: CompletionRequest, reply: (Message) -> Unit) {
        reply(Message("complete").payload(FieldMLCompletionEngine.complete(completionRequest)))
    }

    /** The hover card for the symbol under the caret, from [FieldMLCompletionEngine]. */
    override fun describeSymbolContext(inside: Box, request: SymbolContextRequest): SymbolContextResponse =
        FieldMLCompletionEngine.describeSymbolContext(request)

    /** `fieldml`: what the AI layer and the operation transcript are told a box's `code` property is written in. */
    override val languageName: String get() = "fieldml"

    private fun executeOnce(inside: Box, execute: Execute, reply: (Message) -> Unit, fragment: Boolean) {
        val originSession = thisSession.get()
        inMain {
            val watermark = ++inside.fragmentGeneration
            configureOutputProxies(inside, reply, originSession)
            send(Message("is-running").payload(IsRunning(execute.uid, true, watermark, "__fragment__")))
            try {
                val result = FieldMLCompiler.compile(execute.code)
                if (!reportDiagnostics(inside, execute, result, originSession, reply)) {
                    val output = TrackingOutput(inside.output)
                    val value = Evaluator(FieldMLRuntimePrelude.values(output, inside)).evaluate(result.core!!)
                    val typeText = afield.ml.type.TypePrinter().print(result.typed!!.type)
                    val valueText = renderFieldMLValue(value)
                    if (!output.printed) {
                        dispatchExecutionOutput(
                            inside,
                            reply,
                            Output(
                                uid = inside.id,
                                line = fieldMLResultLine(execute.line, result),
                                kind = "normal",
                                property = execute.property,
                                html = renderInlineResult(valueText, typeText),
                                frame = Runloop.runLoop.frame.toInt(),
                            ),
                            originSession = originSession,
                        )
                    }
                }
            } catch (t: Throwable) {
                inside.output.error(t.message ?: t.javaClass.simpleName, execute.property, execute.line)
            } finally {
                send(Message("is-running").payload(IsRunning(execute.uid, false, watermark, "__fragment__")))
            }
        }
    }

    private fun fieldMLResultLine(executeLine: Int, result: CompilationResult): Int {
        val sourceLine = result.typed?.range?.end?.line ?: result.surface?.range?.end?.line
        return sourceLine?.let { executeLine + it } ?: executeLine
    }

    private fun reportDiagnostics(
        inside: Box,
        execute: Execute,
        result: CompilationResult,
        originSession: WebSocketSession?,
        reply: (Message) -> Unit,
    ): Boolean {
        val errors = result.diagnostics.filter { it.severity == afield.ml.diag.Severity.Error }
        if (errors.isEmpty()) {
            dispatchExecutionOutput(
                inside,
                reply,
                Output(
                    uid = inside.id,
                    line = -1,
                    kind = "err",
                    property = execute.property,
                    html = "",
                    frame = Runloop.runLoop.frame.toInt(),
                ),
                originSession = originSession,
            )
            return false
        }

        errors.forEach { diagnostic ->
            val line = diagnostic.range?.start?.line?.let { execute.line + it - 1 } ?: execute.line
            inside.output.error(diagnostic.message, execute.property, line)
        }
        return true
    }

}

/**
 * The mark that makes a box's *children* FieldML boxes.
 *
 * `MainLanguageRuntime` calls this the legacy root, and it is the second of the two marks
 * `fieldMLTopologyNeedsUpdating` accepts: either this or a `mainLanguage` of `fieldml` will do. It
 * is persisted with the document, which is what `installFieldML` adds it to
 * `Box.ioPersistentProperties` for.
 */
var Box.isFieldMLRoot by Delegation<Boolean?>()

/**
 * Register FieldML with the Main Language menu, and make `isFieldMLRoot` survive a save.
 *
 * Registration is what puts FieldML in the menu at all; without it the language exists but no box
 * can be told to use it.
 */
@RunsAt(early)
fun installFieldML() {
    Box.ioPersistentProperties.add("isFieldMLRoot")
    registerMainLanguageRuntime(
        MainLanguageRuntime(
            id = "fieldml",
            label = "FieldML",
            runtimeClass = FieldMLExecution::class,
            setLegacyRoot = { box, enabled -> box.isFieldMLRoot = enabled },
            applyTopology = ::fieldMLTopologyNeedsUpdating,
        ),
    )
}

/**
 * Start watching the box graph, so marking a box propagates to its children without a reload.
 *
 * The watcher fires `fieldMLTopologyNeedsUpdating` whenever a session's topology generation
 * changes, which is how a box dragged under a FieldML root becomes a FieldML box as you drop it.
 */
@RunsAt(plugins)
fun installFieldMLPlugin() {
    TopologyWatcher("fieldml") { fieldMLTopologyNeedsUpdating(it) }.start()
}

/**
 * Attach and detach [FieldMLExecution] over a set of boxes, from their parents' marks.
 *
 * A child of a marked box gets a [FieldMLExecution]; a child that has one and no marked parent
 * left goes back to Kotlin. Having *any* marked parent is enough, so a box under both a FieldML
 * root and something else stays FieldML and the order the boxes are visited in does not matter.
 */
fun fieldMLTopologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { it.isFieldMLRoot == true || it.isMainLanguage("fieldml") },
        runtimeClass = FieldMLExecution::class,
        newExecution = { child -> FieldMLExecution(child) },
        language = "fieldml",
        customization = "",
        notifyCategory = "fieldml",
    )
}

private class TrackingOutput(private val delegate: HasOutput) : HasOutput {
    var printed: Boolean = false
        private set

    override fun print(message: Any?) {
        printed = true
        delegate.print(message)
    }

    override fun println(message: Any?) {
        printed = true
        delegate.println(message)
    }

    override fun display(message: Any?) {
        delegate.display(message)
    }

    override fun display(channel: String, message: Any?) {
        delegate.display(channel, message)
    }

    override fun error(message: Any?, property: String, line: Int) {
        delegate.error(message, property, line)
    }
}

internal fun renderInlineResult(value: String, type: String): String =
    if (type.isBlank()) value else "$value : $type"

/**
 * FieldML evaluates by walking the tree, so a runaway or merely deep recursion surfaces as a
 * StackOverflowError with no message at all. Name it, and point at the iteration that does not grow
 * the stack.
 */
internal fun fieldMLErrorMessage(t: Throwable): String =
    when (t) {
        is StackOverflowError ->
            "Recursion went too deep. FieldML does not optimise tail calls — for long loops prefer range/tabulate with map or fold."
        else -> t.message ?: t.javaClass.simpleName
    }
