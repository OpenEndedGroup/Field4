package afield.ml

import afield.Box
import afield.BoxExecution
import afield.Completion
import afield.CompletionRequest
import afield.Execute
import afield.ExecuteSymbol
import afield.IsRunning
import afield.Message
import afield.Metadata
import afield.Output
import afield.Runloop
import afield.RunsAt
import afield.configureOutputProxies
import afield.dispatchExecutionOutput
import afield.displayOpen
import afield.early
import afield.inMain
import afield.ml.core.Evaluator
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

class FieldMLExecution(private val owner: Box) : BoxExecution {
    private val launchJobs = mutableMapOf<String, Job>()

    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        executeOnce(inside, execute, reply, fragment = true)
    }

    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        executeOnce(inside, execute, reply, fragment = false)
    }

    override fun launchAll(inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit) {
        val originSession = thisSession.get()
        inMain {
            launchJobs[strand]?.cancel()
            configureOutputProxies(inside, reply, originSession)
            val compileResult = FieldMLCompiler.compile(code)
            if (!reportDiagnostics(inside, execute, compileResult, originSession, reply)) {
                val generation = inside.runGeneration.getOrPut(strand) { -1 } + 1
                inside.runGeneration[strand] = generation
                send(Message("is-running").payload(IsRunning(execute.uid, true, generation, strand)))
                inside.displayOpen("fieldml")
                inside.displayOpen("kotlin")

                val core = compileResult.core!!
                val typeText = afield.ml.type.TypePrinter().print(compileResult.typed!!.type)
                val kotlin = compileResult.kotlin.orEmpty()

                val job = inMain {
                    try {
                        while (inside.runGeneration[strand] == generation) {
                            val value = Evaluator(FieldMLRuntimePrelude.values(inside.output)).evaluate(core)
                            inside.output.display(
                                "fieldml",
                                renderRunReport(
                                    value = renderFieldMLValue(value),
                                    type = typeText,
                                    mode = "launch:$strand",
                                    diagnostics = emptyList(),
                                ),
                            )
                            inside.output.display("kotlin", "<pre><code>${escapeHtml(kotlin)}</code></pre>")
                            delay(33)
                            yield()
                        }
                    } catch (t: Throwable) {
                        inside.output.error(t.message ?: t.javaClass.simpleName, execute.property, execute.line)
                    } finally {
                        send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand)))
                    }
                }
                launchJobs[strand] = job
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
        launchJobs.forEach { (_, job) -> job.cancel() }
        launchJobs.clear()
        inside.runGeneration.keys.toList().forEach { strand ->
            inside.runGeneration[strand] = (inside.runGeneration[strand] ?: 0) + 1
        }
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
                    val value = Evaluator(FieldMLRuntimePrelude.values(inside.output)).evaluate(result.core!!)
                    val typeText = afield.ml.type.TypePrinter().print(result.typed!!.type)
                    val valueText = renderFieldMLValue(value)
                    dispatchExecutionOutput(
                        inside,
                        reply,
                        Output(
                            uid = inside.id,
                            line = execute.line + execute.code.lines().lastIndex,
                            kind = "normal",
                            property = execute.property,
                            html = "${escapeHtml(valueText)} <span style='color:#888'>: ${escapeHtml(typeText)}</span>",
                            frame = Runloop.runLoop.frame.toInt(),
                        ),
                        originSession = originSession,
                    )
                    inside.displayOpen("fieldml")
                    inside.displayOpen("kotlin")
                    inside.output.display(
                        "fieldml",
                        renderRunReport(
                            value = valueText,
                            type = typeText,
                            mode = if (fragment) "fragment" else "all",
                            diagnostics = result.diagnostics.map { it.message },
                        ),
                    )
                    inside.output.display("kotlin", "<pre><code>${escapeHtml(result.kotlin.orEmpty())}</code></pre>")
                }
            } catch (t: Throwable) {
                inside.output.error(t.message ?: t.javaClass.simpleName, execute.property, execute.line)
            } finally {
                send(Message("is-running").payload(IsRunning(execute.uid, false, watermark, "__fragment__")))
            }
        }
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
        inside.displayOpen("fieldml")
        inside.output.display(
            "fieldml",
            renderRunReport(
                value = null,
                type = null,
                mode = "diagnostics",
                diagnostics = errors.map { it.message },
            ),
        )
        return true
    }

    private fun renderRunReport(value: String?, type: String?, mode: String, diagnostics: List<String>): String =
        buildString {
            append("<div style='font: 13px/1.45 system-ui, sans-serif; color:#ddd;'>")
            append("<div style='color:#8ac6ff; font-weight:600;'>FieldML ")
            append(escapeHtml(mode))
            append("</div>")
            if (value != null) {
                append("<div><b>value</b> <code>")
                append(escapeHtml(value))
                append("</code></div>")
            }
            if (type != null) {
                append("<div><b>type</b> <code>")
                append(escapeHtml(type))
                append("</code></div>")
            }
            if (diagnostics.isNotEmpty()) {
                append("<ul>")
                diagnostics.forEach {
                    append("<li>")
                    append(escapeHtml(it))
                    append("</li>")
                }
                append("</ul>")
            }
            append("</div>")
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
            "print",
            "println",
            "display",
            "show",
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
            "hostClass",
            "hostNew0",
            "hostCall0",
            "hostCall1",
            "hostCall2",
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
            "fline" to "Create an FLine host value.",
            "moveTo" to "moveTo x y line adds a move instruction and returns the FLine.",
            "lineTo" to "lineTo x y line adds a line instruction and returns the FLine.",
            "hostCall1" to "Generic opaque host bridge: hostCall1 \"method\" arg target.",
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

private fun escapeHtml(text: String): String =
    buildString(text.length) {
        text.forEach { c ->
            append(
                when (c) {
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '&' -> "&amp;"
                    '"' -> "&quot;"
                    else -> c
                },
            )
        }
    }
