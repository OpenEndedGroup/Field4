package afield.forth

import afield.*
import afield.logger.lprintln
import afield.runtime.TopologyWatcher
import afield.runtime.applyRuntimeToggle
import afield.stdlib.Delegation
import io.ktor.websocket.WebSocketSession

/**
 * BoxExecution implementation for the FORTH microlanguage.
 *
 * Integrates the FORTH interpreter with Field4's execution model:
 * - executeFragment / executeAll: run FORTH code once, report errors/output
 * - launchAll: register a FORTH word as a run-loop strand (executes every frame)
 * - stop: remove all strands
 * - complete: word completion from the FORTH dictionary
 * - executeSymbol: run a named word
 */
class ForthExecution(val inside: Box) : BoxExecution {

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

    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        val originSession = thisSession.get()
        inMain {
            val wm = ++inside.fragmentGeneration
            send(Message("is-running").payload(IsRunning(execute.uid, true, wm, "__fragment__")))
            val hadVisibleOutput = configureForthOutput(
                inside = inside,
                executeLine = execute.line,
                reply = reply,
                originSession = originSession,
                allowFrameYield = false
            )
            try {
                val diagnostics = forth.eval(execute.code)
                if (diagnostics.isEmpty()) {
                    clearExecutionErrorStatus(reply, originSession)
                }
                if (diagnostics.isEmpty() && !hadVisibleOutput()) {
                    dispatchExecutionOutput(
                        inside,
                        reply,
                        Output(
                            uid = inside.id,
                            line = execute.line + execute.code.split("\n").size,
                            kind = "ok",
                            html = "ok",
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
            } finally {
                send(Message("is-running").payload(IsRunning(execute.uid, false, wm, "__fragment__")))
            }
        }
    }

    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        executeFragment(inside, execute, reply)
    }

    override fun launchAll(
        inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit
    ) {
        val originSession = thisSession.get()
        inMain {
            val wm = ++inside.fragmentGeneration
            configureForthOutput(
                inside = inside,
                executeLine = execute.line,
                reply = reply,
                originSession = originSession,
                allowFrameYield = true
            )

            // First execute the code to define any words
            val setupDiags = forth.eval(code)
            if (setupDiags.isNotEmpty()) {
                setupDiags.forEach { diag ->
                    val errLine = if (diag.line >= 0) execute.line + diag.line else execute.line
                    inside.error(diag.message, line = errLine, property = "code")
                }
                send(Message("is-running").payload(IsRunning(execute.uid, false, wm, strand)))
                return@inMain
            }

            clearExecutionErrorStatus(reply, originSession)

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
        }
    }

    override fun stop(inside: Box) {
        strandJobs.forEach { (_, job) -> job.cancel() }
        strandJobs.clear()
        forth.strands.clear()
        inside.runGeneration.keys.toList().forEach { strand ->
            inside.runGeneration[strand] = (inside.runGeneration[strand] ?: 0) + 1
        }
    }

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

    override val languageName: String get() = "forth"
}

var Box.isForthRoot by Delegation<Boolean?>()

@RunsAt(early)
fun installForth() {
    Box.ioPersistentProperties.add("isForthRoot")
}

@RunsAt(plugins)
fun installForthPlugin() {
    TopologyWatcher("forth") { forthTopologyNeedsUpdating(it) }.start()
}

fun forthTopologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { it.isForthRoot },
        runtimeClass = ForthExecution::class,
        newExecution = { child -> ForthExecution(child) },
        language = "Forth",
        customization = """this.style.border = "1px dashed #006400"; this.style.margin = "-5px" """,
        notifyCategory = "forth"
    )
}
