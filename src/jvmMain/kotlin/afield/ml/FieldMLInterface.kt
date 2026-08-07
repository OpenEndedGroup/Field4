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
import afield.clearRunningStates
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
import afield.stdlib.Delegation
import afield.thisSession
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CancellationException

private const val FIELDML_PENDING_LAUNCH_MODE = "launching"
private const val FIELDML_ACTIVE_LAUNCH_MODE = "launch"

class FieldMLExecution(private val owner: Box) : BoxExecution {
    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        executeOnce(inside, execute, reply, fragment = true)
    }

    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        executeOnce(inside, execute, reply, fragment = false)
    }

    override fun launchAll(inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit) {
        val originSession = thisSession.get()
        inMain {
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

                    val consumed = ReturnValueRegistry.launchWithGeneration(inside, hostValue, strand, generation) { isRunning, notifiedStrand, watermark ->
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

    override fun stop(inside: Box) {
        inside.runGeneration.keys.toList().forEach { strand ->
            inside.runGeneration[strand] = (inside.runGeneration[strand] ?: 0) + 1
        }
        val jobsToCancel = inside.jobs.toList()
        inside.jobs.clear()
        inside.clearRunningStates()
        jobsToCancel.forEach { it.cancel() }
    }

    override fun complete(inside: Box, completionRequest: CompletionRequest, reply: (Message) -> Unit) {
        FieldMLCompletionEngine.complete(completionRequest)?.let {
            reply(Message("complete").payload(it))
        }
    }

    override fun describeSymbolContext(inside: Box, request: SymbolContextRequest): SymbolContextResponse =
        FieldMLCompletionEngine.describeSymbolContext(request)

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

var Box.isFieldMLRoot by Delegation<Boolean?>()

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

@RunsAt(plugins)
fun installFieldMLPlugin() {
    TopologyWatcher("fieldml") { fieldMLTopologyNeedsUpdating(it) }.start()
}

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
