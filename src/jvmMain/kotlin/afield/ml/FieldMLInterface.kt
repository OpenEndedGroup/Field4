package afield.ml

import afield.Box
import afield.BoxExecution
import afield.CachedLaunchReturn
import afield.Completion
import afield.CompletionRequest
import afield.Execute
import afield.ExecuteSymbol
import afield.HasOutput
import afield.IsRunning
import afield.Message
import afield.Metadata
import afield.Output
import afield.ReturnValueRegistry
import afield.RunningState
import afield.Runloop
import afield.RunsAt
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
                    inside.output.error(t.message ?: t.javaClass.simpleName, execute.property, execute.line)
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
        val code = completionRequest.allCode
        val cursor = completionRequest.position.coerceIn(0, code.length)
        var start = cursor
        while (start > 0 && (code[start - 1].isLetterOrDigit() || code[start - 1] == '_')) start--
        val prefix = code.substring(start, cursor)
        val candidates = (fieldMLKeywords + fieldMLBuiltins + constructorNames(code))
            .distinct()
            .filter { it.startsWith(prefix, ignoreCase = false) }
            .sorted()
        if (candidates.isEmpty()) return
        reply(
            Message("complete").payload(
                Completion(
                    boundsStart = start,
                    boundsEnd = cursor,
                    metadata = candidates.map {
                        Metadata(
                            icon = if (it.firstOrNull()?.isUpperCase() == true) "class" else "keyword",
                            tail = "fieldml",
                            documentation = fieldMLKeywordDocs[it] ?: "FieldML symbol",
                            text = it,
                            displayText = it,
                        )
                    },
                ),
            ),
        )
    }

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

    private fun constructorNames(code: String): List<String> =
        Regex("""\btype\s+\w+(?:\s+\w+)*\s*=\s*([^\n]+)""")
            .findAll(code)
            .flatMap { match ->
                match.groupValues[1]
                    .split("|")
                    .asSequence()
                    .mapNotNull { it.trim().split(Regex("""\s+""")).firstOrNull() }
                    .filter { it.firstOrNull()?.isUpperCase() == true }
            }
            .toList()

    companion object {
        private val fieldMLKeywords = listOf(
            "let",
            "type",
            "match",
            "with",
            "if",
            "then",
            "else",
            "fun",
            "true",
            "false",
            "in",
        )

        private val fieldMLBuiltins = listOf(
            "Unit",
            "thisBox",
            "print",
            "println",
            "display",
            "show",
            "tap",
            "vec2",
            "vec3",
            "vec4",
            "fline",
            "moveTo",
            "moveTo3",
            "lineTo",
            "lineTo3",
            "moveToV",
            "lineToV",
            "cubicTo",
            "close",
            "thickness",
            "color",
            "filled",
            "stroked",
            "pointed",
            "pointSize",
            "hostInput",
            "hostClass",
            "hostNew0",
            "hostCall0",
            "hostCall1",
            "hostCall2",
            "hostStaticCall",
            "hostStaticTap",
            "hostTap",
            "hostGet",
            "hostSet",
        )

        private val fieldMLKeywordDocs = mapOf(
            "let" to "Introduce an immutable binding.",
            "type" to "Declare an algebraic data type.",
            "match" to "Pattern match over a value.",
            "with" to "Start match cases.",
            "fun" to "Anonymous function.",
            "if" to "Conditional expression.",
            "display" to "display \"channel\" value sends a value to a Field output panel and returns it.",
            "print" to "Print a value and return it, useful as a pipe tap.",
            "println" to "Print a value plus newline and return it, useful as a pipe tap.",
            "tap" to "Apply a function for its effect and return the original value.",
            "fline" to "Create an FLine host value.",
            "moveTo" to "moveTo x y line adds a move instruction and returns the FLine.",
            "lineTo" to "lineTo x y line adds a line instruction and returns the FLine.",
            "hostCall1" to "Generic opaque host bridge: hostCall1 \"method\" arg target.",
            "thisBox" to "Current Field Box as a host value.",
            "hostInput" to "Placeholder for the piped value in hostStaticTap argument templates.",
            "hostStaticCall" to "Generic opaque host bridge: hostStaticCall \"method\" args class.",
            "hostStaticTap" to "Build a pipe-friendly static JVM call that substitutes hostInput and returns the piped value.",
            "hostTap" to "Build a pipe-friendly JVM call from indexed Kotlin source metadata and named argument overrides.",
        )
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
