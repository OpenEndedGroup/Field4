    package afield.ai

    import afield.AiAssist
    import afield.Box
    import afield.Handled
    import afield.SavingContext
    import afield.Server.Companion.ai_assistant
    import afield.Server.Companion.ai_thread
    import afield.Set_string
    import afield.box
    import afield.handlers
    import afield.languageNameForProperty
    import afield.logger.lprintln
    import afield.notify
    import afield.payload
    import afield.stdlib.compute
    import afield.stdlib.fragment
    import afield.stdlib.vertex
    import afield.thisSessionInfo
    import com.aallam.openai.api.BetaOpenAI
    import com.aallam.openai.api.assistant.*
    import com.aallam.openai.api.core.Role
    import com.aallam.openai.api.file.FileId
    import com.aallam.openai.api.file.FileSource
    import com.aallam.openai.api.file.FileUpload
    import com.aallam.openai.api.file.Purpose
    import com.aallam.openai.api.http.Timeout
    import com.aallam.openai.api.message.Attachment
    import com.aallam.openai.api.message.Message
    import com.aallam.openai.api.message.MessageContent
    import com.aallam.openai.api.message.MessageRequest
    import com.aallam.openai.api.model.ModelId
    import com.aallam.openai.api.run.AssistantStreamEventType
    import com.aallam.openai.api.run.RunRequest
    import com.aallam.openai.api.thread.Thread
    import com.aallam.openai.api.thread.ThreadId
    import com.aallam.openai.api.thread.ThreadRequest
    import com.aallam.openai.client.OpenAI
    import com.aallam.openai.client.OpenAIConfig
    import com.aallam.openai.client.extension.getData
    import kotlinx.coroutines.flow.catch
    import kotlinx.coroutines.flow.collect
    import kotlinx.coroutines.flow.onEach
    import kotlinx.coroutines.runBlocking
    import kotlinx.io.files.Path
    import kotlinx.io.files.SystemFileSystem
    import kotlinx.serialization.json.Json
    import kotlinx.serialization.json.decodeFromJsonElement
    import java.io.File
    import java.nio.file.Files
    import java.nio.file.Paths
    import java.util.stream.Stream
    import kotlin.io.path.absolutePathString
    import kotlin.io.path.isRegularFile
    import kotlin.time.Duration.Companion.seconds

    // this is placeholder. AI2 will be MCP now OpenAI supports it

    class AI1 {

        @OptIn(BetaOpenAI::class)
        var assistant: Assistant? = null

        @OptIn(BetaOpenAI::class)
        var thread: Thread? = null

        var files: MutableList<com.aallam.openai.api.file.File>? = null

        val config = OpenAIConfig(
            token = "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",
            timeout = Timeout(60.seconds)
        )

        lateinit var openAI: OpenAI

        // these need to be persisted
        @OptIn(BetaOpenAI::class)
        fun start(assidantId: String?, threadId: String?) = runBlocking {
            openAI = OpenAI(config)

            if (assidantId != null) {
                assistant = openAI.assistant(AssistantId(assidantId))
            }
            if (assistant == null) {
                assistant = openAI.assistant(
                    request = AssistantRequest(
                        instructions = """
                                            You are a code writing assistant writing code in Kotlin and Vulkan GLSL, and you only answer with complete Kotlin, or GLSL function bodies or fragments. Please help the user write some code to complete the request. There is already code in this function body before <cursor> and code after <cursor>, keep this code as much as you can. Return only complete edited code, do not use backticks or any other kind of markup. The returned code must be a complete Kotlin function body, or a GLSL shader vertex, fragment or compute shader ready to run and nothing else. For Kotlin functions tThis editor has a special notation for separating out imports and the scripting function bodies. 
                                            
                                            For example, if asked to complete:
                                            
                                            import foo
                                            /*preamble*/ fun SCRIPT() {
                                            print("hello <cursor>
                                            /*postamble*/}
                                            
                                            you might return
                                            
                                            import foo
                                            /*preamble*/ fun SCRIPT() {
                                            print("hello world")
                                            /*postamble*/}
                                            
                                            if given a range demarcated by <rewrite> and </rewrite> you can rewrite that code given the comment above it. For example, given:
                                            
                                            import foo
                                            /*preamble*/ fun SCRIPT() {
                                            // make this more glum
                                            <rewrite>print("hello world")</rewrite>
                                            /*postamble*/}
                                            
                                            you might return
                                            
                                            import foo
                                            /*preamble*/ fun SCRIPT() {
                                            print("goodbye world")
                                            /*postamble*/}
                                            
                                            Comments inside code are welcome, but only if they significantly add to the understanding of the code or illuminate the choices you've made; don't repeat yourself. Match braces, and remember: return only working code.
                                            """.trimIndent(),
                        tools = listOf(AssistantTool.FileSearch, AssistantTool.CodeInterpreter),
                        model = ModelId("gpt-5.1-codex"),
                        toolResources = ToolResources(),
                    )
                )
            }

            if (threadId != null) {
                thread = openAI.thread(ThreadId(threadId))
            }

            if (thread == null) {
                thread = openAI.thread(request = ThreadRequest())
            }

            files = openAI.files().toMutableList()
        }

        fun glob(patten: String, path: String): Stream<java.nio.file.Path> {
            val pattern = Regex(patten)
            Files.walk(Paths.get(path)).use { stream ->
                return stream
                    .filter { it.isRegularFile() && it.fileName.toString().matches(pattern) }
            }
        }

        @OptIn(BetaOpenAI::class)
        fun syncAllAndAddToThread(dir: String) {

            glob(".*\\.kt$", dir).forEach { path ->
                path.absolutePathString() to runBlocking {
                    ensureSyncedFile(path.absolutePathString()) {
                        runBlocking {
                            openAI.message(
                                thread!!.id, request = MessageRequest(
                                    role = Role.User,
                                    content = "This is a file that might be useful for understanding the code that we are writing. It is called `${path.fileName.toString()}`.",
                                    attachments = listOf(
                                        Attachment(
                                            fileId = it.id, tools = listOf(
                                                AssistantTool.FileSearch
                                            )
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }

        suspend fun syncStandardProperties(documentPath: String, box: Box) {
            if (box.code.trim().length > 0) {
                syncBoxToThread(documentPath, box, "code", "kt"/*todo*/, box.ke.languageName, "language")
            }
            if ((box.vertex?.trim()?.length ?: 0) > 0) {
                syncBoxToThread(
                    documentPath,
                    box,
                    "vertex",
                    ".glsl",
                    "GLSL",
                    "This file contains the Vulkan vertex shader GLSL code for a shader associated with this box."
                )
            }
            if ((box.fragment?.trim()?.length ?: 0) > 0) {
                syncBoxToThread(
                    documentPath,
                    box,
                    "fragment",
                    ".glsl",
                    "GLSL",
                    "This file contains the Vulkan fragment shader GLSL code for a shader associated with this box."
                )
            }
            if ((box.compute?.trim()?.length ?: 0) > 0) {
                syncBoxToThread(
                    documentPath,
                    box,
                    "compute",
                    ".glsl",
                    "GLSL",
                    "This file contains the Vulkan compute shader GLSL code for a shader associated with this box."
                )
            }
        }

        @OptIn(BetaOpenAI::class)
        suspend fun syncBoxToThread(
            documentPath: String,
            box: Box,
            property: String,
            extension: String,
            language: String,
            extraPrompt: String
        ) {
            val fn = SavingContext.filenameFor(box.id, property, extension)
            val fnAll = documentPath + "/" + fn
            ensureSyncedFile(fnAll) {
                runBlocking {
                    openAI.message(
                        thread!!.id, request = MessageRequest(
                            role = Role.User,
                            content = "This is a file that might be useful for understanding the code that we are writing. Internally it has the name `$fn`, when talking about this file you can refer to it as the code in a box called `${box.name}.${property}`. It is written in the $language language. ${extraPrompt}",
                            attachments = listOf(
                                Attachment(
                                    fileId = it.id, tools = listOf(
                                        AssistantTool.FileSearch
                                    )
                                )
                            )
                        )
                    )
                }
            }
        }


        var markerStart = "/*preamble*/fun SCRIPT() {\n"
        var shortMarkerStart = "fun SCRIPT() {\n"
        var markerEnd = "/*postamble*/}\n"
        var shortMarkerEnd = "}"
        var cursor = "<cursor>\n"

        var rewriteBegin = "<rewrite>\n"
        var rewriteEnd = "</rewrite>\n"



        fun rewrite(contents: String, start: Int, end: Int): String {
            val lines = contents.split("\n")

            val preamble = lines.indexOfLast {
                it.lowercase().trim().startsWith("import ") || it.lowercase().trim().startsWith("@file")
            }

            val pre = lines.subList(0, preamble + 1)
            val before = lines.subList(preamble + 1, Math.min(lines.size, start))
            val middle = lines.subList(Math.min(lines.size, start), Math.min(lines.size, end))
            val end = lines.subList(Math.min(lines.size, end), lines.size)

            return pre.joinToString(separator = "\n") + markerStart + before.joinToString(separator = "\n") + rewriteBegin + "\n" + middle.joinToString(
                separator = "\n"
            ) + rewriteEnd + "\n" + end.joinToString(separator = "\n") + "\n" + markerEnd
        }

        fun cursorify(contents: String, endOfLine: Int): String {
            val lines = contents.split("\n")

            val preamble = lines.indexOfLast {
                it.lowercase().trim().startsWith("import ") || it.lowercase().trim().startsWith("@file")
            }

            val pre = lines.subList(0, preamble + 1)
            val before = lines.subList(preamble + 1, Math.min(lines.size, endOfLine + 1))
            val after = lines.subList(Math.min(lines.size, endOfLine + 1), lines.size)

            return pre.joinToString(separator = "\n") + markerStart + before.joinToString(separator = "\n") + cursor + "\n" + after.joinToString(
                separator = "\n"
            ) + markerEnd
        }
        fun uncursorify(contents: String): String {

            if (contents.indexOf(cursor) != -1) throw MalformedAIError("output shouldn't still have a <cursor> tag in it")
            // assumption -- this doesn't have <cursor> in it

            val a = contents.indexOf(shortMarkerStart)
            val b = contents.lastIndexOf(shortMarkerEnd)

            if (a == -1) throw MalformedAIError(" couldn't find marker for $shortMarkerStart in $contents")
            if (b == -1) throw MalformedAIError(" couldn't find marker for $shortMarkerEnd and $contents")

            val preamble = contents.substring(0, a).replace("/*preamble*/", "")
            val meat = contents.substring(a + shortMarkerStart.length, b).replace("/*postamble*/", "")

            return (preamble + "\n" + meat).trimIndent().trim()
        }

        companion object {
            val defaultInstructions =
                "Please help the user write some Kotlin code to complete the request. Make sure you balance your braces. There is already code in this file before <cursor> and code after <cursor>. Return only complete edited code, do not use backticks or any other kind of markup. The returned code must be a complete Kotlin file ready to run and nothing else."
            val rewriteInstructions =
                "Please help the user write some Kotlin code to complete the request. Make sure you balance your braces. There is already code in this file, you should rewrite the section between <rewrite> and </rewrite>. Return only complete edited code, do not use backticks or any other kind of markup. The returned code must be a complete Kotlin file ready to run and nothing else."
            val errorInstructions =
                "Please help the user fix some Kotlin code to complete the request. Make sure you balance your braces. There is already code in this file but there is an error at the point in the file indicated by <error> and </error>. Return only complete fixed code, do not use backticks or any other kind of markup. The returned code must be a complete Kotlin file ready to run and nothing else."
            val chatInstructions =
                "Please help the user fix and extend some <<LANG>> code to complete the request. Make sure you balance your braces. Return only complete, edited code, do not use backticks or any other kind of markup, do not supply a diff. The returned code must be a complete <<LANG>> file ready to run and nothing else. The user has asked for specific help starting at a particular line. That help is the message `<<HELP>>`, and this position is marked inside the code with `<help>`. Take care to focus your edits to the text to that point and after, as much as that makes sense given the language."
        }


        @OptIn(BetaOpenAI::class)
        fun query(
            contents: String,
            instructions: String = defaultInstructions,
            languageName: String = "Kotlin",
            result: (String) -> Unit
        ) = runBlocking {
            notify("AI starting...", "AI")
            openAI.message(
                threadId = thread!!.id,
                request = MessageRequest(
                    role = Role.User,
                    content = contents
                )
            )

            val inst = instructions.replace("Kotlin", languageName)
            print(" running AI prompt is\n${inst}")
            openAI.createStreamingRun(
                thread!!.id,
                request = RunRequest(
                    assistantId = assistant!!.id,
                    instructions = inst,
                )
            ).onEach {
                println("\n\n -- from ai\n")
                notify("AI ${it.type.name.lowercase()}", "AI")
                when (it.type) {

                    AssistantStreamEventType.THREAD_MESSAGE_COMPLETED -> {
                        print(" handling ${it}")
                        val completion =
                            (it.getData<Message>().content.firstNotNullOfOrNull { it as? MessageContent.Text }?.text)
                        if (completion == null) {
                            lprintln(" something's wrong with the THREAD_MESSAGE_COMPLETED ${it}")
                        } else
                            result(unwrap(completion?.value ?: ""))
                    }

                    else -> {
                        print(" unhandled event type ${it.type}")
                    }
                }
            }.catch {
                notify("AI failed ${it.message}", "AI")
                println("problem running ai")
                println(it)
                it.printStackTrace()
            }.collect()
        }

        @OptIn(BetaOpenAI::class)
        fun queryWithPrompt(
            extraPrompt: String,
            atLine: Int,
            contents: String,
            instructions: String = chatInstructions.replace("<<HELP>>", extraPrompt),
            languageName: String = "Kotlin",
            result: (String) -> Unit
        ) = runBlocking {
            notify("AI starting...", "AI")
            val a = contents.split("\n").toMutableList()
            a.add(atLine, "<help>")
            val contents2 = a.joinToString("\n")

            openAI.message(
                threadId = thread!!.id,
                request = MessageRequest(
                    role = Role.User,
                    content = contents2
                )
            )

            val inst = instructions.replace("<<LANG>>", languageName)
            print(" running AI prompt is\n${inst}")
            openAI.createStreamingRun(
                thread!!.id,
                request = RunRequest(
                    assistantId = assistant!!.id,
                    instructions = inst,
                )
            ).onEach {
                println("\n\n -- from ai\n")
                notify("AI(chat): ${it.type.name.lowercase()}", "AI")
                when (it.type) {

                    AssistantStreamEventType.THREAD_MESSAGE_COMPLETED -> {
                        print(" handling ${it}")
                        val completion =
                            (it.getData<Message>().content.firstNotNullOfOrNull { it as? MessageContent.Text }?.text)
                        if (completion == null) {
                            lprintln(" something's wrong with the THREAD_MESSAGE_COMPLETED ${it}")
                        } else
                            result(unwrap(completion?.value ?: ""))
                    }

                    else -> {
                        print(" unhandled event type ${it.type}")
                    }
                }
            }.catch {
                notify("AI(chat) failed ${it.message}", "AI")
                println("problem running ai")
                println(it)
                it.printStackTrace()
            }.collect()
        }


        suspend fun ensureSyncedFile(path: String, onRefresh: (com.aallam.openai.api.file.File) -> Unit): String {
            var needsUpload = false
            var previousFileId: String? = null

            if (!File(path + ".fileid").exists()) needsUpload = true
            else {
                needsUpload = true
                val id = File(path + ".fileid").readText()

                val info = id.split("`")
                val at = info[1].toLong()
                if (File(path).lastModified() > at) {
                    previousFileId = info[0]
                    needsUpload = true
                } else {
                    files?.find { it.id.id == info[0] }?.let {
                        needsUpload = false
                        return info[0]
                    }
                }
            }

            previousFileId?.let {
                // we should also delete any associated messages
                openAI.delete(FileId(previousFileId))
            }


            notify("syncing file to OpenAI...", "AI")
            val newFile = openAI.file(
                FileUpload(
                    // that .txt is a shame
                    FileSource(name = path + ".txt", SystemFileSystem.source(Path(path))),
                    purpose = Purpose("assistants")
                )
            )
            files?.add(newFile)
            File(path + ".fileid").writeText(newFile.id.id + "`" + File(path).lastModified().toString())
            onRefresh(newFile)
            notify("..syncing file complete", "AI")
            return newFile.id.id
        }

        fun wrap(prompt: String): String {
            return markerStart + prompt + markerEnd
        }

        fun unwrap(contents: String): String {
            return uncursorify(contents)
        }
    }

    class MalformedAIError(message: String) : Throwable(message)


    @OptIn(BetaOpenAI::class)
//    @RunsAt(installHandlers)
    fun ai1_install() {
        var ai: AI1? = null
        handlers["ai-assist"] = mutableSetOf({ message, reply: (afield.Message) -> Unit ->
            val exec = Json.decodeFromJsonElement<AiAssist>(message.payload!!)
            val box = box(exec.uid)

            if (ai == null) {
                ai = AI1()
                notify("Initializing AI", "AI")
                ai!!.start(ai_assistant, ai_thread)
                ai_thread = ai!!.thread!!.id.id
                println(" AI thread id is ${ai_thread}")
                ai_assistant = ai!!.assistant!!.id.id
                println("AI1 has started with ${ai_thread} ${ai_assistant}")
            }
            val filename = thisSessionInfo.get().filename

            when (exec.kind) {
                "cursor" -> {
                    val p = ai!!.wrap(exec.prompt)
                    notify("Initiating Completion", "AI")
                    ai!!.query(p, languageName = languageNameForProperty(box, exec.property)) {
                        notify("Finished AI Completion", "AI")
                        reply(
                            afield.Message("ai_replace").payload(
                                Set_string(
                                    uid = exec.uid, property = exec.property, value = it
                                )
                            )
                        )
                    }
                }

                "chat" -> {
                    val p = ai!!.wrap(exec.prompt)
                    notify("Initiating AI Chat", "AI")
                    ai!!.queryWithPrompt(exec.extraPrompt, exec.startLine, p, languageName = languageNameForProperty(box, exec.property)) {
                        notify("Finished AI chat", "AI")
                        reply(
                            afield.Message("ai_replace").payload(
                                Set_string(
                                    uid = exec.uid, property = exec.property, value = it
                                )
                            )
                        )
                    }
                }

                "rewrite" -> {
                    val p = ai!!.wrap(exec.prompt)
                    ai!!.query(
                        p,
                        instructions = AI1.rewriteInstructions,
                        languageName = languageNameForProperty(box, exec.property)
                    ) {
                        notify("Finished AI Completion", "AI")
                        reply(
                            afield.Message("ai_replace").payload(
                                Set_string(
                                    uid = exec.uid, property = exec.property, value = it
                                )
                            )
                        )
                    }
                }

                "error" -> {
                    val p = ai!!.wrap(exec.prompt)
                    ai!!.query(
                        p,
                        instructions = AI1.errorInstructions,
                        languageName = languageNameForProperty(box, exec.property)
                    ) {
                        notify("Finished AI Completion", "AI")
                        reply(
                            afield.Message("ai_replace").payload(
                                Set_string(
                                    uid = exec.uid, property = exec.property, value = it
                                )
                            )
                        )
                    }
                }

            }

            Handled.yes
        })
    }
