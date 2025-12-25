package afield.ai.core.install

import afield.*
import afield.ai.KotlinDefinitionRecord
import afield.ai.KotlinSourceIndex
import afield.ai.core.bridge.OpenAiIdeCodexBridge
import afield.ai.core.chat.IdeChatHelpers
import afield.ai.core.lang.*
import afield.ai.core.logging.NotifyLog
import afield.ai.core.tools.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
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

        val langName = languageNameForProperty(box, exec.property)
        val lang = langSpecForName(langName)
        val docName = documentName()
        val docPath = thisSessionInfo.get().filename
        val escapedDocPath = Regex.escape(docPath)
        val fileKey = fileRefFor(docName, exec.uid, exec.property)

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
            val otherDefs = afield.thisSessionInfo.get().boxes.values
                .filter { it.id != box.id }
                .sortedBy { it.name.ifBlank { it.id } }
                .flatMap { definitionsForBox(it) }

            ListDefinitionsResult(definitions = currentDefs + otherDefs)
        }

        val listFileContents = {
            val fileRef = fileRefFor(docName, exec.uid, exec.property)
            notify("Listing contents for $fileRef", "AI4")
            val contents = box.getPropertyRaw(exec.property)
            if (contents !is String) {
                throw IllegalStateException("No text content for $fileRef")
            }
            ListContentsResult(fileRef, contents)
        }

        val editOtherFile: (EditFileRequest) -> EditFileResponse = editOtherFile@{ req ->
            notify("Editing ${req.file}", "AI4")
            val ref = parseFileRef(req.file)
                ?: return@editOtherFile EditFileResponse(false, "Unsupported file ref '${req.file}'")
            if (ref.document != docName) {
                return@editOtherFile EditFileResponse(false, "Unknown document '${ref.document}'")
            }
            val target = afield.thisSessionInfo.get().boxes[ref.uid]
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
                            listDefinitions = listDefinitions,
                            listFileContents = listFileContents,
                            editOtherFile = editOtherFile,
                            workspaceSymbolSearch = workspaceSymbolSearch,
                            vectorStoreIds = vectorStoreIds
                        )
                    }

                    "general" -> {
                        val trimmedPrompt = exec.extraPrompt.trim()
                        val previous = chatHistoryByKey[fileKey].orEmpty()
                        val prefix = buildString {
                            if (previous.isNotBlank()) {
                                append(previous)
                                append("\n")
                            }
                            if (trimmedPrompt.isNotEmpty()) {
                                append("<<you>>\n")
                                append(trimmedPrompt)
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
                            listDefinitions = listDefinitions,
                            listFileContents = listFileContents,
                            editOtherFile = editOtherFile,
                            workspaceSymbolSearch = workspaceSymbolSearch,
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
                            listDefinitions = listDefinitions,
                            listFileContents = listFileContents,
                            editOtherFile = editOtherFile,
                            workspaceSymbolSearch = workspaceSymbolSearch
                        )
                    }

                    "rewrite" -> {
                        chatResult = null
                        editResult = helpers!!.chatFile(
                            state = state,
                            code = exec.prompt,
                            lang = lang,
                            userPrompt = "Rewrite only the code between <rewrite> and </rewrite>.",
                            listDefinitions = listDefinitions,
                            listFileContents = listFileContents,
                            editOtherFile = editOtherFile,
                            workspaceSymbolSearch = workspaceSymbolSearch
                        )
                    }

                    "error" -> {
                        chatResult = null
                        editResult = helpers!!.chatFile(
                            state = state,
                            code = exec.prompt,
                            lang = lang,
                            userPrompt = "Fix the error described between <error> and </error>.",
                            listDefinitions = listDefinitions,
                            listFileContents = listFileContents,
                            editOtherFile = editOtherFile,
                            workspaceSymbolSearch = workspaceSymbolSearch,
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
                    val trimmedResponse = chatResult.text.trim()
                    val prefix = streamPrefix ?: buildString {
                        val previous = chatHistoryByKey[fileKey].orEmpty()
                        if (previous.isNotBlank()) {
                            append(previous)
                            append("\n")
                        }
                        if (trimmedPrompt.isNotEmpty()) {
                            append("<<you>>\n")
                            append(trimmedPrompt)
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
}
