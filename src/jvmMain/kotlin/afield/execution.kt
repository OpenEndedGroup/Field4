package afield

import afield.ai.Ai4GlslAutocomplete
import afield.ai.glslSpecForProperty
import afield.logger.lprintln
import afield.macros.standardExecutionPractice
import afield.stdlib.Action
import afield.stdlib._currentlyExecutingFragment
import afield.stdlib.actions
import afield.stdlib.compute
import afield.stdlib.fragment
import afield.stdlib.i
import afield.stdlib.mesh
import afield.stdlib.task
import afield.stdlib.times
import afield.stdlib.vertex
import kotlinx.coroutines.*
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.annotations.Annotated
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import java.io.File
import kotlin.collections.forEach
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptDiagnostic

@RunsAt(newRoot)
fun installKEActions(root : Box) {
    root.actions["reset"] = Action("Reset Kotlin Environment for Box") {
        it.forEach {
            it.ki = makeKI(it)
        }
    }
}

// refactor this to make the contract about is-running much-much clearer
interface BoxExecution {

    fun executeFragment(inside: Box, execute: Execute, reply: ((Message) -> Unit))
    fun executeAll(inside: Box, execute: Execute, reply: ((Message) -> Unit))
    fun launchAll(
        inside: Box,
        execute: Execute,
        code: String = execute.code,
        strand: String = "default",
        reply: ((Message) -> Unit)
    )

    fun executeSymbol(inside: Box, executeSymbol: ExecuteSymbol, reply: ((Message) -> Unit))

    fun stop(inside: Box)

    fun complete(inside: Box, completionRequest: CompletionRequest, reply: ((Message) -> Unit))

    val languageName: String
}

fun configureOutputProxies(box: Box, reply: (Message) -> Unit) {
    box.output.proxy = { content, nl, line ->
        val n = content?.toString() ?: ""
        if (n.trim().length > 0) {
            reply(
                Message("output").payload(
                    Output(
                        line = line.i, kind = "normal", html = n.trim()+(if(nl) "\n" else ""), frame = Runloop.runLoop.frame.toInt()
                    )
                )
            )
        }
    }

    box.output.errorProxy = { content, property, nl ->
        val n = content?.toString() ?: ""
        if (n.trim().length > 0) {
            reply(
                Message("output").payload(
                    Output(
                        line = nl.i, kind = "err", property = property, html = n, frame = Runloop.runLoop.frame.toInt()
                    )
                )
            )
        }
    }
}


@Suppress("UnstableApiUsage")
class KotlinExecution : BoxExecution {
    override fun executeFragment(box: Box, exec: Execute, reply: ((Message) -> Unit)) {

        val ln = Math.max(0, exec.line)

        val code = ("\n" * ln) + exec.code.trimEnd()

        val dur = +exec.code.count { it == '\n' }

        val existingFunction = box.ki.context.functions.filter { it.key == code.trim() }.map { it.value }.firstOrNull()
        if (existingFunction != null) {
            println(" found existing function: $existingFunction")

            reply(
                Message("output").payload(
                    Output(
                        line = ln + dur,
                        kind = "normal",
                        html = existingFunction.function.toString() + "<br>",
                        annotation = "<i>${existingFunction.name}=</i>(function)",
                        frame = Runloop.runLoop.frame.toInt()
                    )
                )
            )
            return
        }

        lprintln(" global scope ts is ${thisSession.get()}")
        val wm = ++box.fragmentGeneration

        box._currentlyExecutingFragment = true
        send(Message("is-running").payload(IsRunning(exec.uid, true, wm, "__fragment__")))
        inMain {
            execute(box, code, reply, 0, finishedHandler = {
                box._currentlyExecutingFragment = false
                send(Message("is-running").payload(IsRunning(exec.uid, false, wm, "__fragment__")))
            }) {}
        }


    }

    override fun executeAll(box: Box, exec: Execute, reply: ((Message) -> Unit)) {

        val code = exec.code

        val wm = ++box.fragmentGeneration
        send(Message("is-running").payload(IsRunning(exec.uid, true, wm, "__fragment__")))
        inMain {
            execute(box, code, reply, code.split("\n").size, finishedHandler = {
                send(Message("is-running").payload(IsRunning(exec.uid, false, wm, "__fragment__")))
            }) {}
        }


    }

    override fun launchAll(box: Box, exec: Execute, code: String, strand: String, reply: ((Message) -> Unit)) {

        val generation = box.runGeneration.incrementAndGet("default")
        send(Message("is-running").payload(IsRunning(exec.uid, true, generation, strand)))

        val ln = Math.max(0, exec.line)

        val code = ("\n" * ln) + exec.code.trimEnd()

        inMain {

            // TODO! support pre-compilation. This executes the box (once) and grabs it's return value, then launch-all will simply do that. This means you end up with a lot in frames {}
            // this is just the field2 design

            execute(box, code, reply, exec.line) { returnValue ->

                configureOutputProxies(box, reply)

                launchReturnValue(box, returnValue, strand = strand, { isRunning, strand, watermark ->
                    send(Message("is-running").payload(IsRunning(exec.uid, isRunning, watermark, strand)))
                })
            }

            send(Message("is-running").payload(IsRunning(exec.uid, false, generation, strand)))
        }
    }


    suspend fun execute(
        box: Box,
        code: String,
        reply: ((Message) -> Unit)?,
        ln: Int,
        finishedHandler: () -> Unit = {},
        handleReturn: (Any?) -> Unit
    ) {

        try {
            var dur = code.split("\n").count()
            var hasError = false
            Box.context.set(box) // coroutine context?
            var output = mutableListOf<String>("")
            box.output.proxy = { content, nl, line ->
                output[output.size - 1] = output[output.size - 1] + content.toString()
                if (nl) output.add("")

                println("out "+content)
                reply?.invoke(
                    Message("output").payload(
                        Output(
                            line = line.i,
                            kind = "normal",
                            html = content.toString().trim()+(if(nl) "\n" else ""),
                            frame = Runloop.runLoop.frame.toInt()
                        )
                    )
                )

            }
            box.output.errorProxy = { content, property, line ->
                hasError = hasError || content != null
                reply?.invoke(
                    Message("output").payload(
                        Output(
                            line = (line + ln).i,
                            kind = "error",
                            property = property,
                            html = content?.toString() ?: "",
                            frame = Runloop.runLoop.frame.toInt()
                        )
                    )
                )
            }
            box.output.displayProxy = { channel, content ->
                send(Message("set-output").payload(Set_output(box.id, "outputarea", channel, content.toString())))
            }

            val returnValues: List<ResultValue?> = standardExecutionPractice(box.ki, code, { compileError ->
                hasError = true
                lprintln("compiler error")
                var first = true
                compileError.forEach {
                    lprintln("  ${it.code} ${it.message} ${it.location} ${it.severity}")
                    if (first && it.severity.ordinal > ScriptDiagnostic.Severity.WARNING.ordinal) {
                        first = false
                        if (it.location != null) {
                            reply?.invoke(
                                Message("output").payload(
                                    Output(
                                        line = it.location!!.start.line,
                                        kind = "error",
                                        html = it.message,
                                        frame = Runloop.runLoop.frame.toInt()
                                    )
                                )
                            )
                        } else {
                            reply?.invoke(
                                Message("output").payload(
                                    Output(
                                        line = ln + dur,
                                        kind = "error",
                                        html = it.message,
                                        frame = Runloop.runLoop.frame.toInt()
                                    )
                                )
                            )
                        }
                    }
                }
            }, { evalError ->
                hasError = true
                lprintln("eval error")
                evalError.forEach {
                    lprintln("  ${it.code} ${it.message} ${it.location}")
                }
            }, { evalException ->
                hasError = true
                lprintln("exception thrown in fragment ")
                evalException.printStackTrace()

                var fallbackFirstLine = -1
                var firstLineInThisBox = -1
                evalException.stackTrace.forEach { e ->
                    println("${e.fileName} ${e.className} ${e.lineNumber}")
                    if (fallbackFirstLine == -1) {
                        fallbackFirstLine = e.lineNumber
                    }
                    if(e?.fileName?.contains(box.id) ?: false && firstLineInThisBox == -1) {
                        firstLineInThisBox = e.lineNumber
                    }
                }

                var firstLine = if (firstLineInThisBox!=-1) firstLineInThisBox else fallbackFirstLine
                reply?.invoke(
                    Message("output").payload(
                        Output(
                            line = firstLine,
                            kind = "error",
                            html = evalException.message ?: evalException.javaClass.name,
                            frame = Runloop.runLoop.frame.toInt()
                        )
                    )
                )

            }, implicits = listOf(box))

            if (!hasError) {
                var size = 0
                output.forEach { size += it.trim().length }
//                    reply?.invoke(
//                        Message("output").payload(
//                            Output(
//                                line = ln + dur, kind = "normal", html = output.joinToString(separator = "<br>")
//                            )
//                        )
//                    )

                returnValues.forEach { returnValue ->
                    when (returnValue) {
                        is ResultValue.Unit -> {
                            if (size == 0) reply?.invoke(
                                Message("output").payload(
                                    Output(
                                        line = dur,
                                        kind = "ok",
                                        html = "ok",
                                        frame = Runloop.runLoop.frame.toInt()
                                    )
                                )
                            )
                            handleReturn(null)
                        }

                        is ResultValue.NotEvaluated -> {}

                        is ResultValue.Error -> {
                            reply?.invoke(
                                Message("output").payload(
                                    Output(
                                        line = ln + dur,
                                        kind = "error",
                                        html = returnValue.error.message ?: returnValue.error.javaClass.name,
                                        frame = Runloop.runLoop.frame.toInt()
                                    )
                                )
                            )
                            handleReturn(null)
                        }

                        is ResultValue.Value -> {
                            if (size == 0) {
                                val name = returnValue.name
                                val value = findDefinition(returnValue.value, search = box.root.children)
                                val type = returnValue.type

                                if (value.length > 0) {
                                    reply?.invoke(
                                        Message("output").payload(
                                            Output(
                                                line = ln + dur,
                                                kind = "normal",
                                                html = value,//.trim().replace("\n", "<br>") + "<br>",
                                                annotation = "<i>${name}=</i>${type}",
                                                frame = Runloop.runLoop.frame.toInt()
                                            )
                                        )
                                    )
                                } else reply?.invoke(

                                    Message("output").payload(
                                        Output(
                                            line = ln + dur,
                                            kind = "ok",
                                            html = "ok",
                                            frame = Runloop.runLoop.frame.toInt()
                                        )
                                    )
                                )
                            }

                            handleReturn(returnValue.value)
                        }

                        null -> {
                            if (size == 0) reply?.invoke(
                                Message("output").payload(
                                    Output(
                                        line = ln + dur,
                                        kind = "ok",
                                        html = "ok",
                                        frame = Runloop.runLoop.frame.toInt()
                                    )
                                )
                            )
                            handleReturn(null)
                        }

                    }

                }
            } else {
                lprintln("ERROR\n has error")
            }
        } finally {
            finishedHandler()
        }

    }

    fun launchReturnValue(
        inside: Box,
        returnValue: Any?,
        strand: String = "default",
        notify: (running: Boolean, strand: String, watermark: Int) -> Unit
    ) {
        val generation = inside.runGeneration.incrementAndGet(strand)
        when (returnValue) {


            is Sequence<*> -> {
                val a = returnValue.iterator()
                inMain {
                    notify(true, strand, generation)
                    while (inside.runGeneration[strand] == generation && a.hasNext()) {
                        val n = a.next()
                        yield()
                    }
                    notify(false, strand, generation)
                }
            }

            is Frames -> {
                returnValue.shouldend = {
                    inside.runGeneration[strand] != generation
                }
                val original = returnValue.atEnd
                returnValue.atEnd = {
                    original()
                    notify(false, strand, generation)
                }
                inMain {
                    notify(true, strand, generation)
                    returnValue.job = kotlin.coroutines.coroutineContext[Job]!!
                    inside.jobs += returnValue.job!!
                    try {
                        returnValue.body()
                        // throwing here (a cancellation exception) does not
                        // run atEnd (should it?)
                    } catch (e: CancellationException) {
                    } catch (t: Throwable) {
                        t.printStackTrace()
                        var firstLine = -1
                        t.stackTrace.forEach { e ->
                            println("${e.fileName} ${e.className} ${e.lineNumber}")
                            if (firstLine == -1 && e.fileName.contains(inside.id)) {
                                firstLine = e.lineNumber
                            }
                        }
                        inside.error(t.message ?: "", line = firstLine)
                    } finally {
                        notify(false, strand, generation)
                    }
                }
            }

            else -> {
                notify(false, strand, generation)

            }

        }
    }

    override fun stop(box: Box) {
        box.runGeneration.incrementAndGet("default")
        box.jobs.removeAll {
            it.cancel()
            true
        }
    }


    override fun complete(box: Box, exec: CompletionRequest, reply: (Message) -> Unit) {

        val position = exec.position
        val allCode = exec.allCode
        val property = exec.property

        if (glslSpecForProperty(property) != null) {
            val docName = File(thisSessionInfo.get().filename).name
            val fileKey = "afield://$docName/${box.id}/$property"
            GlobalScope.launch(Dispatchers.IO) {
                fun trimContext(value: String, maxChars: Int = 4000, maxLines: Int = 200): String {
                    if (value.isBlank()) return ""
                    val lines = value.split("\n")
                    val limitedLines = if (lines.size > maxLines) lines.take(maxLines) else lines
                    val joined = limitedLines.joinToString("\n").trimEnd()
                    return if (joined.length > maxChars) joined.take(maxChars).trimEnd() else joined
                }

                val context = linkedMapOf<String, String>()
                val codeContext = trimContext(box.getPropertyRaw("code") as? String ?: "")
                if (codeContext.isNotBlank()) {
                    context["code"] = codeContext
                }
                val shaderProps = listOf(
                    "vertex" to box.vertex,
                    "fragment" to box.fragment,
                    "mesh" to box.mesh,
                    "task" to box.task,
                    "compute" to box.compute
                )
                shaderProps.forEach { (name, value) ->
                    if (name != property && !value.isNullOrBlank()) {
                        context[name] = trimContext(value)
                    }
                }
                val candidates = try {
                    Ai4GlslAutocomplete.complete(
                        fileKey = fileKey,
                        property = property,
                        code = allCode,
                        context = context,
                        line = exec.line,
                        column = exec.column,
                        position = position
                    )
                } catch (_: Exception) {
                    emptyList()
                }

                val metadata = candidates.take(3).mapIndexed { idx, candidate ->
                    val display = candidate.text.lineSequence().firstOrNull()?.trim().orEmpty()
                    val doc = buildString {
                        val notes = candidate.documentation.ifBlank { "AI4 GLSL completion" }
                        append("**Notes**\n")
                        notes.lineSequence().forEach { line ->
                            val trimmed = line.trim()
                            if (trimmed.isNotEmpty()) append("- ").append(trimmed).append("\n")
                        }
                        if (candidate.text.isNotBlank()) {
                            append("\n**Insertion**\n")
                            append("```glsl\n")
                            append(candidate.text.trimEnd())
                            append("\n```\n")
                        }
                    }.trimEnd() + "\n"
                    Metadata(
                        icon = "keyword",
                        tail = "AI4 ${idx + 1}",
                        text = candidate.text,
                        displayText = if (display.isNotEmpty()) display else "AI4 completion ${idx + 1}",
                        documentation = doc
                    )
                }

                reply(
                    Message("complete").payload(
                        Completion(
                            boundsStart = position,
                            boundsEnd = position,
                            metadata = metadata
                        )
                    )
                )
            }
            return
        }

        fun completionFor(c: KotlinInterface.CompletionResult.Success): List<Metadata> {
            return c.metadata.map {
//                println(it.extraInfo)
                var extraDoc = ""
                val descriptor = it.extraInfo.getOrDefault("original", null)
                var mainText = it.displayText
                descriptor?.let {
                    if (descriptor is DeclarationDescriptor) {
//                                                mainText = DescriptorRenderer.HTML.render(descriptor as DeclarationDescriptor)
                        mainText = DescriptorRenderer.COMPACT_WITH_SHORT_TYPES.withOptions {
                            withDefinedIn = false
                        }.render(descriptor as DeclarationDescriptor) + "\n"
                    }
                    (it as? Annotated)?.let {
                        it.annotations.forEach {
                            print(it.fqName)
                            if (it.fqName.toString() == "afield.Doc") {
                                extraDoc += it.allValueArguments.values.joinToString(separator = "\n")
                                    .trim('\"')
                            }
                        }
                    }
                }
                Metadata(
                    icon = it.icon,
                    tail = it.tail,
                    text = it.text,
                    displayText = mainText.trim(),
                    documentation = extraDoc.trim()
                )
            }
        }

        fun importHelpFor(c: KotlinInterface.CompletionResult.Success): List<Metadata> {
            val s = allCode.substring(c.bounds.start, c.bounds.end)
            if (s.length>0)
            return classesWithPrefix(s).map {
                Metadata(
                    icon = "method",
                    tail = it.first,
                    text = s + it.first,
                    displayText = "(import) ${it.second}",
                    documentation = "import ${it.second}",
                    prefix = "import ${it.second}"
                )
            }
            else return emptyList()
        }

        runBlocking {
            launch(Runloop.runLoop.main) {
                val c = box.ki.complete(allCode, position)
                when (c) {
                    is KotlinInterface.CompletionResult.Success -> {
                        val completions = Completion(
                            boundsStart = c.bounds.start,
                            boundsEnd = c.bounds.end,
                            metadata = completionFor(c) + importHelpFor(c)
                        )

                        reply(
                            Message("complete").payload(
                                completions
                            )
                        )
                    }
                }
            }
        }
    }

    override fun executeSymbol(inside: Box, executeSymbol: ExecuteSymbol, reply: (Message) -> Unit) {
        // For Kotlin, construct a function call: symbolName()
        val functionCall = "${executeSymbol.symbol}()"
        
        // Create an Execute object for executeFragment
        val exec = Execute(
            uid = executeSymbol.uid,
            type = "fragment",
            line = executeSymbol.line,
            code = functionCall,
            singleLine = true
        )
        
        // Execute as a fragment
        executeFragment(inside, exec, reply)
    }

    override val languageName: String = "Kotlin"
}

private fun MutableMap<String, Int>.incrementAndGet(name: String): Int {
    this[name] = computeIfAbsent(name) { 0 } + 1
    return this[name]!!
}
