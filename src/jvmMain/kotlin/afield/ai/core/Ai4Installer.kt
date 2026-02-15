package afield.ai.core

import afield.*
import afield.ai.KotlinDefinitionRecord
import afield.ai.KotlinSourceIndex
import afield.logger.lprintln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val ai4Scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

fun installAi4() {
    var bridge: OpenAiIdeCodexBridge? = null
    var helpers: IdeChatHelpers? = null
    var chatBridge: OpenAiIdeCodexBridge? = null
    var chatHelpers: IdeChatHelpers? = null
    val threadByKey = ConcurrentHashMap<String, OpenAiIdeCodexBridge.ThreadState>()
    val chatThreadByKey = ConcurrentHashMap<String, OpenAiIdeCodexBridge.ThreadState>()
    val chatHistoryByKey = ConcurrentHashMap<String, String>()

    fun langSpecForName(name: String): LanguageSpec = when {
        name.equals("Kotlin", ignoreCase = true) -> KOTLIN_SCRIPT
        name.equals(GLSL_VERTEX.name, ignoreCase = true) -> GLSL_VERTEX
        name.equals(GLSL_FRAGMENT.name, ignoreCase = true) -> GLSL_FRAGMENT
        name.equals(GLSL_MESH.name, ignoreCase = true) -> GLSL_MESH
        name.equals(GLSL_TASK.name, ignoreCase = true) -> GLSL_TASK
        name.equals(GLSL_COMPUTE.name, ignoreCase = true) -> GLSL_COMPUTE
        else -> LanguageSpec(
            name = name,
            wrap = { it.trimEnd() },
            unwrap = { it.trimEnd() }
        )
    }

    fun threadFor(fileKey: String, fileText: String): OpenAiIdeCodexBridge.ThreadState =
        threadByKey[fileKey] ?: bridge!!.startThread(fileKey, fileText).also {
            threadByKey[fileKey] = it
        }

    fun chatThreadFor(fileKey: String, fileText: String): OpenAiIdeCodexBridge.ThreadState =
        chatThreadByKey[fileKey] ?: chatBridge!!.startThread(
            fileKey,
            fileText,
            instructions = OpenAiIdeCodexBridge.defaultChatThreadInstructions
        ).also {
            chatThreadByKey[fileKey] = it
        }

    data class FileRef(val document: String, val uid: String, val property: String)

    fun documentName(): String = File(thisSessionInfo.get().filename).name

    fun fileRefFor(docName: String, uid: String, property: String): String =
        "afield://$docName/$uid/$property"

    fun parseFileRef(raw: String): FileRef? {
        if (!raw.startsWith("afield://")) return null
        val parts = raw.removePrefix("afield://").split("/", limit = 3)
        if (parts.size != 3) return null
        return FileRef(parts[0], parts[1], parts[2])
    }

    handlers["ai-assist"] = mutableSetOf({ message, _ ->
        val exec = Json.decodeFromJsonElement<AiAssist>(message.payload!!)
        val box = box(exec.uid)

        if (bridge == null) {
            val uiLog = NotifyLog()
            bridge = OpenAiIdeCodexBridge(log = uiLog)
            helpers = IdeChatHelpers(bridge!!, log = uiLog)
            notify("Initializing AI support v4", "AI4")
        }

        if (chatBridge == null) {
            val uiLog = NotifyLog(label = "")
            chatBridge = OpenAiIdeCodexBridge(model = "gpt-5.2", log = uiLog)
            chatHelpers = IdeChatHelpers(chatBridge!!, log = uiLog)
        }

        val sessionInfo = thisSessionInfo.get()
        if (sessionInfo == null) {
            notify("AI4 unavailable: no active session context.", "AI4")
            return@mutableSetOf Handled.yes
        }

        val langName = languageNameForProperty(box, exec.property)
        val lang = langSpecForName(langName)
        val docName = File(sessionInfo.filename).name
        val docPath = sessionInfo.filename
        val escapedDocPath = Regex.escape(docPath)
        val fileKey = fileRefFor(docName, exec.uid, exec.property)
        val textProperties = linkedSetOf("code", "vertex", "fragment", "mesh", "task", "compute").apply {
            if (exec.property.isNotBlank()) add(exec.property)
        }

        fun languageNameForBoxProperty(target: Box, property: String): String = when (property) {
            "code" -> target.ke.languageName
            "vertex" -> "GLSL (vertex)"
            "fragment" -> "GLSL (fragment)"
            "compute" -> "GLSL (compute)"
            "mesh" -> "GLSL (mesh)"
            "task" -> "GLSL (task)"
            "customization", "remote_js" -> "JavaScript"
            "metadata" -> "YAML"
            else -> languageNameForProperty(target, property)
        }

        fun boxByIdOrNull(uid: String): Box? =
            if (uid == sessionInfo.root.id) sessionInfo.root else sessionInfo.boxes[uid]

        fun textPropertyContents(target: Box, property: String): String? =
            (target.getPropertyRaw(property) as? String)?.takeIf { it.isNotBlank() }

        fun languageForFilePath(path: Path): String {
            val lower = path.fileName?.toString()?.lowercase().orEmpty()
            return when {
                lower.endsWith(".kt") || lower.endsWith(".kts") -> "Kotlin"
                lower.endsWith(".glsl") || lower.endsWith(".vert") || lower.endsWith(".frag")
                    || lower.endsWith(".comp") || lower.endsWith(".mesh") || lower.endsWith(".task") -> "GLSL"
                lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs") -> "JavaScript"
                lower.endsWith(".ts") || lower.endsWith(".tsx") -> "TypeScript"
                lower.endsWith(".java") -> "Java"
                lower.endsWith(".py") -> "Python"
                lower.endsWith(".md") -> "Markdown"
                lower.endsWith(".yaml") || lower.endsWith(".yml") -> "YAML"
                lower.endsWith(".json") -> "JSON"
                lower.endsWith(".html") -> "HTML"
                lower.endsWith(".css") -> "CSS"
                lower.endsWith(".xml") -> "XML"
                else -> "text"
            }
        }

        val listDefinitions = {
            fun definitionsForBox(target: Box): List<DefinitionInfo> {
                val langForBox = languageNameForProperty(target, "code")
                val fileRef = fileRefFor(docName, target.id, "code")
                val vars = target.ki.context.vars.values.map { v ->
                    DefinitionInfo(
                        name = v.name,
                        type = "variable",
                        fileRef = fileRef,
                        language = langForBox
                    )
                }
                val funcs = target.ki.context.functions.values.map { f ->
                    DefinitionInfo(
                        name = f.toString(true),
                        type = "function",
                        fileRef = fileRef,
                        language = langForBox
                    )
                }
                return vars + funcs
            }

            val currentDefs = definitionsForBox(box)
            val otherDefs = sessionInfo.boxes.values
                .filter { it.id != box.id }
                .sortedBy { it.name.ifBlank { it.id } }
                .flatMap { definitionsForBox(it) }

            ListDefinitionsResult(definitions = currentDefs + otherDefs)
        }

        val listFileContents = {
            val entries = textProperties.mapNotNull { property ->
                val content = textPropertyContents(box, property) ?: return@mapNotNull null
                ListContentEntry(
                    fileRef = fileRefFor(docName, box.id, property),
                    property = property,
                    language = languageNameForBoxProperty(box, property),
                    content = content
                )
            }
            if (entries.isEmpty()) {
                throw IllegalStateException("No text content available for box '${box.id}'.")
            }
            val primary = entries.firstOrNull { it.property == exec.property } ?: entries.first()
            notify("Listing ${entries.size} content property/properties for ${box.id}", "AI4")
            ListContentsResult(
                fileRef = primary.fileRef,
                content = primary.content,
                language = primary.language,
                contents = entries
            )
        }

        val resolveAttachment: (String) -> AttachmentResponse? = attachmentResolver@{ raw ->
            val requested = raw.trim()
            if (requested.isEmpty()) return@attachmentResolver null

            parseFileRef(requested)?.let { ref ->
                if (ref.document != docName) return@let
                val target = boxByIdOrNull(ref.uid) ?: return@let
                val content = textPropertyContents(target, ref.property) ?: return@let
                return@attachmentResolver AttachmentResponse(
                    file = fileRefFor(docName, target.id, ref.property),
                    language = languageNameForBoxProperty(target, ref.property),
                    content = content
                )
            }

            val requestedProperty = requested.removePrefix("/")
            if (requestedProperty in textProperties) {
                val content = textPropertyContents(box, requestedProperty) ?: return@attachmentResolver null
                return@attachmentResolver AttachmentResponse(
                    file = fileRefFor(docName, box.id, requestedProperty),
                    language = languageNameForBoxProperty(box, requestedProperty),
                    content = content
                )
            }

            val candidatePaths = linkedSetOf<Path>()
            val requestedPath = runCatching { Paths.get(requested) }.getOrNull()
            if (requestedPath != null) {
                if (requestedPath.isAbsolute) {
                    candidatePaths.add(requestedPath.normalize())
                } else {
                    candidatePaths.add(Paths.get(System.getProperty("user.dir")).resolve(requestedPath).normalize())
                    val docDir = Paths.get(docPath).parent
                    if (docDir != null) {
                        candidatePaths.add(docDir.resolve(requestedPath).normalize())
                    }
                }
            }

            for (candidate in candidatePaths) {
                if (!Files.exists(candidate) || !Files.isRegularFile(candidate)) continue
                val content = runCatching { Files.readString(candidate, StandardCharsets.UTF_8) }.getOrNull() ?: continue
                return@attachmentResolver AttachmentResponse(
                    file = candidate.toString(),
                    language = languageForFilePath(candidate),
                    content = content
                )
            }

            null
        }

        val editOtherFile: (EditFileRequest) -> EditFileResponse = editOtherFile@{ req ->
            notify("Editing ${req.file}", "AI4")
            val ref = parseFileRef(req.file)
                ?: return@editOtherFile EditFileResponse(false, "Unsupported file ref '${req.file}'")
            if (ref.document != docName) {
                return@editOtherFile EditFileResponse(false, "Unknown document '${ref.document}'")
            }
            val target = boxByIdOrNull(ref.uid)
                ?: return@editOtherFile EditFileResponse(false, "Unknown box '${ref.uid}'")
            setProvisional(docPath, target.id, ref.property, req.newContent)
            EditFileResponse(true, "Updated ${req.file}")
        }

        val workspaceSymbolSearch: (WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult = workspaceSymbolSearch@{ req ->
            val query = req.query.trim()
            if (query.isEmpty()) return@workspaceSymbolSearch WorkspaceSymbolSearchResult(emptyList())

            val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex()
            val definitions = index?.definitions.orEmpty()
            val limit = req.limit.coerceAtLeast(1)
            val exact = ArrayList<WorkspaceSymbolMatch>()
            val prefix = ArrayList<WorkspaceSymbolMatch>()
            val contains = ArrayList<WorkspaceSymbolMatch>()

            val fileCache = HashMap<String, List<String>>()
            fun fileLines(path: String): List<String>? {
                return fileCache.getOrPut(path) {
                    val direct = Paths.get(path)
                    val resolved = if (Files.exists(direct)) direct else Paths.get(System.getProperty("user.dir")).resolve(path)
                    if (!Files.exists(resolved)) return@getOrPut emptyList()
                    runCatching { Files.readAllLines(resolved, StandardCharsets.UTF_8) }.getOrDefault(emptyList())
                }.takeIf { it.isNotEmpty() }
            }

            fun definitionLineText(def: KotlinDefinitionRecord): String? {
                val lines = fileLines(def.filePath) ?: return null
                val idx = def.line - 1
                return lines.getOrNull(idx)?.trimEnd()
            }

            fun functionBody(def: KotlinDefinitionRecord, maxChars: Int = 6000): String? {
                if (def.kind != "fun") return null
                val lines = fileLines(def.filePath) ?: return null
                val startIdx = (def.line - 1).coerceIn(0, lines.lastIndex)
                var depth = 0
                var found = false
                val sb = StringBuilder()
                var chars = 0
                for (i in startIdx..lines.lastIndex) {
                    val line = lines[i]
                    var j = 0
                    while (j < line.length) {
                        val ch = line[j]
                        if (!found) {
                            if (ch == '{') {
                                found = true
                                depth = 1
                                sb.append(ch)
                            }
                        } else {
                            sb.append(ch)
                            if (ch == '{') depth++
                            if (ch == '}') {
                                depth--
                                if (depth == 0) return sb.toString().trimEnd()
                            }
                        }
                        if (found) {
                            chars++
                            if (chars >= maxChars) return sb.toString().trimEnd()
                        }
                        j++
                    }
                    if (found) {
                        sb.append('\n')
                        chars++
                        if (chars >= maxChars) return sb.toString().trimEnd()
                    }
                }
                return if (found) sb.toString().trimEnd() else null
            }

            fun toMatch(def: KotlinDefinitionRecord): WorkspaceSymbolMatch {
                val location = if (!def.fileId.isNullOrBlank()) {
                    "${def.filePath}:${def.line} (fileId=${def.fileId})"
                } else {
                    "${def.filePath}:${def.line}"
                }
                val lineText = definitionLineText(def)
                val body = functionBody(def)
                return WorkspaceSymbolMatch(
                    symbol = def.name,
                    kind = def.kind,
                    locationHint = location,
                    filePath = def.filePath,
                    fileId = def.fileId,
                    line = def.line,
                    definitionLine = lineText,
                    definitionBody = body
                )
            }

            for (def in definitions) {
                val name = def.name
                if (name.equals(query, ignoreCase = true)) {
                    exact.add(toMatch(def))
                    if (exact.size >= limit) break
                    continue
                }
                if (name.startsWith(query, ignoreCase = true)) {
                    prefix.add(toMatch(def))
                    continue
                }
                if (name.contains(query, ignoreCase = true)) {
                    contains.add(toMatch(def))
                }
            }

            val matches = ArrayList<WorkspaceSymbolMatch>(limit)
            for (bucket in listOf(exact, prefix, contains)) {
                for (item in bucket) {
                    matches.add(item)
                    if (matches.size >= limit) break
                }
                if (matches.size >= limit) break
            }

            WorkspaceSymbolSearchResult(matches)
        }

        val checkCode: (CheckCodeRequest) -> CheckCodeResult = checkCode@{ req ->
            try {
                when (req.language.lowercase()) {
                    "kotlin" -> {
                        CodeChecker.checkKotlin(req.code, box.ki)
                    }
                    "python" -> {
                        CodeChecker.checkPython(req.code)
                    }
                    "clojure" -> {
                        // Get or create ClojureHost for this box
                        val clojureHost = box.ce?.clj ?: afield.clojure.ClojureHost()
                        CodeChecker.checkClojure(req.code, clojureHost)
                    }
                    "glsl" -> {
                        CodeChecker.checkGLSL(req.code, req.property)
                    }
                    else -> {
                        CheckCodeResult(
                            success = false,
                            errors = listOf(
                                CodeError(
                                    message = "Unsupported language: ${req.language}. Supported: Kotlin, Python, Clojure, GLSL",
                                    severity = "error"
                                )
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                lprintln("error,code_checker", "Code check failed: ${e.message}")
                CheckCodeResult(
                    success = false,
                    errors = listOf(
                        CodeError(
                            message = "Code check failed: ${e.message}",
                            severity = "error"
                        )
                    )
                )
            }
        }

        val vectorStoreIds = KotlinSourceIndex.currentVectorStoreId()?.let { listOf(it) } ?: emptyList()

        if (exec.kind == "chat" || exec.kind == "cursor" || exec.kind == "rewrite" || exec.kind == "error") {
            clearProvisional(docPath)
        }

        val state = if (exec.kind == "general") {
            chatThreadFor(fileKey, exec.prompt)
        } else {
            threadFor(fileKey, exec.prompt)
        }

        when (exec.kind) {
            "chat" -> notify("Initiating AI Chat", "AI4")
            "general" -> notify("Initiating AI Conversation", "AI4")
            "cursor" -> notify("Initiating Completion", "AI4")
            "rewrite" -> notify("Initiating AI Rewrite", "AI4")
            "error" -> notify("Initiating AI Error Fix", "AI4")
            else -> {
                notify("Unknown AI request kind '${exec.kind}'", "AI4")
                return@mutableSetOf Handled.yes
            }
        }

        ai4Scope.launch {
            val chatResult: OpenAiIdeCodexBridge.SendResult?
            val editResult: IdeChatHelpers.HighLevelResult?
            val streamActive = AtomicBoolean(false)
            var streamPrefix: String? = null
            try {
                when (exec.kind) {
                    "chat" -> {
                        chatResult = null
                        editResult = helpers!!.chatInline(
                            state = state,
                            code = exec.prompt,
                            lang = lang,
                            lineNumber = exec.startLine,
                            userPrompt = exec.extraPrompt,
                            resolveAttachment = resolveAttachment,
                            listDefinitions = listDefinitions,
                            listFileContents = listFileContents,
                            editOtherFile = editOtherFile,
                            workspaceSymbolSearch = workspaceSymbolSearch,
                            checkCode = checkCode,
                            vectorStoreIds = vectorStoreIds
                        )
                    }

                    "general" -> {
                        val trimmedPrompt = exec.extraPrompt.trim()
                        val attachmentMarkdown = exec.images.joinToString("\n") { url ->
                            "![attachment]($url)"
                        }.trim()
                        val userBlock = buildString {
                            if (trimmedPrompt.isNotEmpty()) append(trimmedPrompt)
                            if (attachmentMarkdown.isNotEmpty()) {
                                if (trimmedPrompt.isNotEmpty()) append("\n\n")
                                append(attachmentMarkdown)
                            }
                        }.trim()
                        val previous = chatHistoryByKey[fileKey].orEmpty()
                        val prefix = buildString {
                            if (previous.isNotBlank()) {
                                append(previous)
                                append("\n")
                            }
                            if (userBlock.isNotEmpty()) {
                                append("<<you>>\n")
                                append(userBlock)
                                append("\n")
                            }
                            append("<<ai>>\n")
                        }
                        val streamLock = Any()
                        val streamBuffer = StringBuilder()
                        var lastPush = 0L
                        streamPrefix = prefix
                        streamActive.set(true)
                        chatResult = chatHelpers!!.chatGeneral(
                            state = state,
                            code = exec.prompt,
                            lang = lang,
                            userPrompt = exec.extraPrompt,
                            imageUrls = exec.images,
                            resolveAttachment = resolveAttachment,
                            listDefinitions = listDefinitions,
                            listFileContents = listFileContents,
                            editOtherFile = editOtherFile,
                            workspaceSymbolSearch = workspaceSymbolSearch,
                            checkCode = checkCode,
                            vectorStoreIds = vectorStoreIds,
                            onTextDelta = { chunk ->
                                if (!streamActive.get() || chunk.isEmpty()) return@chatGeneral
                                synchronized(streamLock) {
                                    streamBuffer.append(chunk)
                                }
                                val now = System.currentTimeMillis()
                                if (now - lastPush >= 80L) {
                                    lastPush = now
                                    if (!streamActive.get()) return@chatGeneral
                                    val next = prefix + synchronized(streamLock) { streamBuffer.toString() }
                                    val payload = Set_output(box.id, "outputarea", "ai-chat", next)
                                    _send(Message("set-output").payload(payload), document = escapedDocPath)
                                }
                            }
                        )
                        streamActive.set(false)
                        editResult = null
                    }

                    "cursor" -> {
                        chatResult = null
                        editResult = helpers!!.chatFile(
                            state = state,
                            code = exec.prompt,
                            lang = lang,
                            userPrompt = "Complete the code at <cursor>.",
                            resolveAttachment = resolveAttachment,
                            listDefinitions = listDefinitions,
                            listFileContents = listFileContents,
                            editOtherFile = editOtherFile,
                            workspaceSymbolSearch = workspaceSymbolSearch,
                            checkCode = checkCode
                        )
                    }

                    "rewrite" -> {
                        chatResult = null
                        editResult = helpers!!.chatFile(
                            state = state,
                            code = exec.prompt,
                            lang = lang,
                            userPrompt = "Rewrite only the code between <rewrite> and </rewrite>.",
                            resolveAttachment = resolveAttachment,
                            listDefinitions = listDefinitions,
                            listFileContents = listFileContents,
                            editOtherFile = editOtherFile,
                            workspaceSymbolSearch = workspaceSymbolSearch,
                            checkCode = checkCode
                        )
                    }

                    "error" -> {
                        chatResult = null
                        editResult = helpers!!.chatFile(
                            state = state,
                            code = exec.prompt,
                            lang = lang,
                            userPrompt = "Fix the error described between <error> and </error>.",
                            resolveAttachment = resolveAttachment,
                            listDefinitions = listDefinitions,
                            listFileContents = listFileContents,
                            editOtherFile = editOtherFile,
                            workspaceSymbolSearch = workspaceSymbolSearch,
                            checkCode = checkCode,
                            vectorStoreIds = vectorStoreIds
                        )
                    }

                    else -> {
                        chatResult = null
                        editResult = null
                    }
                }
            } catch (e: Exception) {
                streamActive.set(false)
                inMain {
                    notify("AI ${exec.kind} failed: ${e.message ?: e::class.java.simpleName}", "AI4")
                }
                return@launch
            }

            inMain {
                if (chatResult != null) {
                    chatThreadByKey[fileKey] = chatResult.threadState
                    val trimmedPrompt = exec.extraPrompt.trim()
                    val attachmentMarkdown = exec.images.joinToString("\n") { url ->
                        "![attachment]($url)"
                    }.trim()
                    val userBlock = buildString {
                        if (trimmedPrompt.isNotEmpty()) append(trimmedPrompt)
                        if (attachmentMarkdown.isNotEmpty()) {
                            if (trimmedPrompt.isNotEmpty()) append("\n\n")
                            append(attachmentMarkdown)
                        }
                    }.trim()
                    val trimmedResponse = chatResult.text.trim()
                    val prefix = streamPrefix ?: buildString {
                        val previous = chatHistoryByKey[fileKey].orEmpty()
                        if (previous.isNotBlank()) {
                            append(previous)
                            append("\n")
                        }
                        if (userBlock.isNotEmpty()) {
                            append("<<you>>\n")
                            append(userBlock)
                            append("\n")
                        }
                        append("<<ai>>\n")
                    }
                    val next = prefix + trimmedResponse.ifEmpty { "_(no response)_" }
                    chatHistoryByKey[fileKey] = next
                    val payload = Set_output(box.id, "outputarea", "ai-chat", next)
                    _send(Message("set-output").payload(payload), document = escapedDocPath)
                    notify("Finished AI conversation", "AI4")
                } else if (editResult != null) {
                    threadByKey[fileKey] = editResult.threadState
                    markProvisional(box, exec.property, editResult.editedCode)
                    notify("Finished AI ${exec.kind}", "AI4")
                }
            }
        }
        Handled.yes
    })
    
    // Chat interface handlers
    handlers["hello_chat"] = mutableSetOf({ message, reply ->
        val hello = Json.decodeFromJsonElement<Hello>(message.payload!!)
        lprintln("info,chat", "Chat connection from ${hello.username} for file ${hello.filename}")
        // Initialize chat state if needed
        Handled.yes
    })
    
    handlers["chat_input"] = mutableSetOf({ message, reply ->
        val input = Json.decodeFromJsonElement<Chat_input>(message.payload!!)
        
        if (chatBridge == null) {
            val uiLog = NotifyLog(label = "")
            chatBridge = OpenAiIdeCodexBridge(model = "gpt-5.2", log = uiLog)
            chatHelpers = IdeChatHelpers(chatBridge!!, log = uiLog)
        }
        
        val fileKey = "chat:${documentName()}"
        val state = chatThreadFor(fileKey, "")
        
        ai4Scope.launch {
            try {
                // Format the user message with images if present
                val userMessage = buildString {
                    append(input.value)
                    if (input.images.isNotEmpty()) {
                        append("\n\n[User attached ${input.images.size} image(s)]")
                    }
                }
                
                // Send intermediate response
                reply(Message("chat_input").payload(Chat_input(
                    value = "<div style='opacity: 0.5;'>Thinking...</div>",
                    phase = 1
                )))
                
                val lang = LanguageSpec(
                    name = "text",
                    wrap = { it.trimEnd() },
                    unwrap = { it.trimEnd() }
                )
                val result = chatHelpers!!.chatGeneral(
                    state = state,
                    code = "",
                    lang = lang,
                    userPrompt = userMessage,
                    imageUrls = input.images
                )
                
                chatThreadByKey[fileKey] = result.threadState
                
                // Format response with markdown
                val formattedResponse = formatMarkdown(result.text)
                
                // Send final response
                inMain {
                    reply(Message("chat_input").payload(Chat_input(
                        value = formattedResponse,
                        phase = 20
                    )))
                }
            } catch (e: Exception) {
                inMain {
                    reply(Message("chat_input").payload(Chat_input(
                        value = "<div style='color: red;'>Error: ${e.message}</div>",
                        phase = 20
                    )))
                }
            }
        }
        
        Handled.yes
    })
}

private fun formatMarkdown(text: String): String {
    // Simple markdown formatting for chat output
    var result = text
        .replace(Regex("```([\\s\\S]*?)```"), "<pre><code>$1</code></pre>")
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "<strong>$1</strong>")
        .replace(Regex("\\*([^*]+)\\*"), "<em>$1</em>")
        .replace("\n", "<br/>")
    return result
}
