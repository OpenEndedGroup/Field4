@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package afield.ai

import afield.*
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.helpers.ResponseAccumulator
import com.openai.models.ChatModel
import com.openai.models.Reasoning
import com.openai.models.ReasoningEffort
import com.openai.models.ResponseFormatText
import com.openai.models.conversations.ConversationCreateParams
import com.openai.models.conversations.items.ItemCreateParams
import com.openai.models.responses.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/* =============================================================================
   Logging
   ============================================================================= */

fun interface LogSink {
    fun log(msg: String)
}

object StdoutLog : LogSink {
    override fun log(msg: String) {
        val prefix = "[OpenAiIde] "
        val full = prefix + msg
        val width = System.getenv("COLUMNS")?.toIntOrNull()?.coerceAtLeast(20) ?: 100
        val padded = if (full.length < width) " ".repeat(width - full.length) + full else full
        val ansiDarkGray = "\u001b[90m"
        val ansiReset = "\u001b[0m"
        println(ansiDarkGray + padded + ansiReset)
    }
}

private class TraceLogger private constructor(
    private val path: Path,
    private val maxChars: Int
) {
    private val lock = Any()

    fun log(section: String, payload: String) {
        val clipped = if (payload.length > maxChars) {
            payload.take(maxChars) + "\n... [truncated ${payload.length - maxChars} chars]"
        } else {
            payload
        }
        val entry = buildString {
            append("---- ")
            append(section)
            append(" ")
            append(Instant.now().toString())
            append("\n")
            append(clipped)
            append("\n\n")
        }
        synchronized(lock) {
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            ).use { it.write(entry) }
        }
    }

    companion object {
        fun fromEnv(): TraceLogger? {
            val raw = System.getenv("AFIELD_AI4_TRACE")
                ?: System.getenv("AFIELD_AI4_TRACE_PATH")
                ?: return null
            val normalized = raw.trim()
            if (normalized.isEmpty()) return null
            if (normalized.equals("0", ignoreCase = true) ||
                normalized.equals("false", ignoreCase = true) ||
                normalized.equals("off", ignoreCase = true)
            ) {
                return null
            }
            val defaultPath = Paths.get(System.getProperty("user.home"), ".afield", "logs", "ai4_trace.log")
            val path = if (
                normalized.equals("1", ignoreCase = true) ||
                normalized.equals("true", ignoreCase = true) ||
                normalized.equals("yes", ignoreCase = true) ||
                normalized.equals("on", ignoreCase = true)
            ) {
                defaultPath
            } else {
                val p = Paths.get(normalized)
                if (p.isAbsolute) p else Paths.get(System.getProperty("user.dir")).resolve(p)
            }
            val max = System.getenv("AFIELD_AI4_TRACE_MAX")?.toIntOrNull()?.coerceAtLeast(1000) ?: 20000
            return TraceLogger(path, max)
        }
    }
}

class NotifyLog(
    private val label: String = "AI",
    private val prefix: String = "AI4",
    private val maxLen: Int = 200,
    private val dedupeWindowMs: Long = 1000
) : LogSink {
    override fun log(msg: String) {
        StdoutLog.log(msg)
        val cleaned = msg.replace(Regex("\\s+"), " ").trim()
        val clipped = if (cleaned.length <= maxLen) cleaned else cleaned.substring(0, maxLen) + "..."
        val key = "$label|$prefix|$clipped"
        val now = System.currentTimeMillis()
        val prev = lastNotification.put(key, now)
        if (prev != null && now - prev < dedupeWindowMs) return
        notify("$prefix: $clipped", label)
    }

    companion object {
        private val lastNotification = ConcurrentHashMap<String, Long>()
    }
}

object SilentLog : LogSink {
    override fun log(msg: String) {
        // intentionally quiet
    }
}

/* =============================================================================
   Tool schemas
   ============================================================================= */

/**
 * Commentary tool: model uses this for non-code notes/questions/alternatives.
 * You collect these separately and keep code output clean.
 */
@JsonClassDescription("Assistant commentary or design notes about the code.")
data class Commentary(val text: String)

/**
 * Represents a file/language/code triple that can be attached to a request.
 * Attachments are retrieved via tools rather than stuffed into the prompt body.
 */
data class ContextAttachment(
    val fileName: String,
    val language: String,
    val code: String
)

/** Tool: list available attachment filenames (+ languages) for this request. */
@JsonClassDescription("List all attached context files available for this request.")
data class ListAttachmentsRequest(val unused: Int = 0)

data class ListAttachmentsResult(val attachments: List<AttachmentInfo>)
data class AttachmentInfo(val file: String, val language: String)

/** Tool: retrieve an attached file's contents by name. */
@JsonClassDescription("Retrieve the code content of an attached file by its name.")
data class AttachmentRequest(val file: String)

data class AttachmentResponse(val file: String, val language: String, val content: String)

/** Kotlin-only tools -------------------------------------------------------- */

/** Tool: list all definitions visible from the current Kotlin file (incl shaders). */
@JsonClassDescription("List all definitions visible from the current Kotlin file, including associated shaders.")
data class ListDefinitionsRequest(val unused: Int = 0)

data class ListDefinitionsResult(val definitions: List<DefinitionInfo>)
data class DefinitionInfo(val name: String, val type: String, val fileRef: String, val language: String)

/** Tool: get full contents of the current Kotlin file. */
@JsonClassDescription("Return the full contents of the current Kotlin file.")
data class ListContentsRequest(val unused: Int = 0)

data class ListContentsResult(val fileRef: String, val content: String)

/** Tool: edit another file by name (your integration decides how to apply this). */
@JsonClassDescription("Edit another file by specifying its name and new content.")
data class EditFileRequest(val file: String, val newContent: String)

data class EditFileResponse(val success: Boolean, val message: String? = null)

/** Example tool (optional) ------------------------------------------------- */
@JsonClassDescription("Looks up symbols in the IDE workspace index.")
data class WorkspaceSymbolSearch(val query: String, val limit: Int = 25)

data class WorkspaceSymbolSearchResult(val matches: List<WorkspaceSymbolMatch>)
data class WorkspaceSymbolMatch(
    val symbol: String,
    val kind: String,
    val locationHint: String,
    val filePath: String? = null,
    val fileId: String? = null,
    val line: Int? = null,
    val definitionLine: String? = null,
    val definitionBody: String? = null
)

/** CLI example tools -------------------------------------------------------- */

@JsonClassDescription("Return the current UTC time in the requested format.")
data class GetUtcTime(val format: String = "yyyy-MM-dd HH:mm:ss")

@JsonClassDescription("Store a short note under a key.")
data class Remember(val key: String, val value: String)

@JsonClassDescription("Recall a stored note by key.")
data class Recall(val key: String)

/* =============================================================================
   Language handling
   ============================================================================= */

data class LanguageSpec(
    val name: String,
    val wrap: (String) -> String,
    val unwrap: (String) -> String
)

class MalformedAI(message: String) : RuntimeException(message)

private fun normalizeGlslPreamble(code: String): String {
    val lines = code.trimEnd().split("\n")
    val extensions = mutableListOf<String>()
    val body = mutableListOf<String>()

    for (line in lines) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("#version") -> {
                // Normalize to the required Vulkan GLSL version.
                continue
            }
            trimmed.startsWith("#extension") -> {
                extensions += trimmed
                continue
            }
            else -> body += line
        }
    }

    return buildString {
        append("#version 450")
        if (extensions.isNotEmpty()) {
            append("\n")
            append(extensions.joinToString("\n"))
        }
        if (body.isNotEmpty()) {
            append("\n\n")
            append(body.joinToString("\n"))
        }
    }.trimEnd()
}

/**
 * Wraps top-level Kotlin script into a fun SCRIPT() block with preamble/postamble markers.
 * Keeps leading import/@file lines at top.
 */
fun wrapScript(script: String): String {
    val lines = script.split("\n")
    val importLines = lines.takeWhile { it.trim().startsWith("import ") || it.trim().startsWith("@file") }
    val bodyLines = lines.drop(importLines.size)
    return buildString {
        if (importLines.isNotEmpty()) {
            append(importLines.joinToString("\n"))
            append("\n\n")
        }
        append("/*preamble*/ fun SCRIPT() {\n")
        append(bodyLines.joinToString("\n"))
        if (bodyLines.isNotEmpty()) append("\n")
        append("/*postamble*/ }")
    }
}

/**
 * Lenient unwrap for Kotlin script output.
 */
fun unwrapScriptLenient(wrapped: String): String {
    val lines = wrapped.split("\n")

    // Case A: markers exist
    val preambleIdx = lines.indexOfFirst { it.contains("/*preamble*/") }
    val postambleIdx = lines.indexOfFirst { it.contains("/*postamble*/") }
    if (preambleIdx != -1 && postambleIdx != -1 && postambleIdx > preambleIdx) {
        val importLines = lines.subList(0, preambleIdx).dropLastWhile { it.isBlank() }
        val bodyLines = lines.subList(preambleIdx + 1, postambleIdx)
        return buildString {
            if (importLines.isNotEmpty()) {
                append(importLines.joinToString("\n"))
                if (bodyLines.isNotEmpty()) append("\n\n")
            }
            append(bodyLines.joinToString("\n"))
        }.trimEnd()
    }

    // Case B: salvage SCRIPT() without markers
    val scriptStart = lines.indexOfFirst { it.contains("fun SCRIPT()") && it.contains("{") }
    if (scriptStart != -1) {
        var depth = 0
        var started = false
        val body = mutableListOf<String>()
        for (i in scriptStart until lines.size) {
            val line = lines[i]
            val opens = line.count { it == '{' }
            val closes = line.count { it == '}' }
            if (!started) {
                depth += opens - closes
                started = true
                continue
            } else {
                depth += opens - closes
                if (depth <= 0) break
                body += line
            }
        }
        val imports = lines.take(scriptStart).dropLastWhile { it.isBlank() }
        return buildString {
            if (imports.isNotEmpty()) {
                append(imports.joinToString("\n"))
                if (body.isNotEmpty()) append("\n\n")
            }
            append(body.joinToString("\n"))
        }.trimEnd()
    }

    // Case C: already a top-level script
    return wrapped.trimEnd()
}

val KOTLIN_SCRIPT: LanguageSpec = LanguageSpec(
    name = "Kotlin",
    wrap = ::wrapScript,
    unwrap = ::unwrapScriptLenient
)

val GLSL_VERTEX: LanguageSpec = LanguageSpec(
    name = "GLSL (vertex)",
    wrap = ::normalizeGlslPreamble,
    unwrap = ::normalizeGlslPreamble
)

val GLSL_FRAGMENT: LanguageSpec = LanguageSpec(
    name = "GLSL (fragment)",
    wrap = ::normalizeGlslPreamble,
    unwrap = ::normalizeGlslPreamble
)

val GLSL_MESH: LanguageSpec = LanguageSpec(
    name = "GLSL (mesh)",
    wrap = ::normalizeGlslPreamble,
    unwrap = ::normalizeGlslPreamble
)

val GLSL_TASK: LanguageSpec = LanguageSpec(
    name = "GLSL (task)",
    wrap = ::normalizeGlslPreamble,
    unwrap = ::normalizeGlslPreamble
)

val GLSL_COMPUTE: LanguageSpec = LanguageSpec(
    name = "GLSL (compute)",
    wrap = ::normalizeGlslPreamble,
    unwrap = ::normalizeGlslPreamble
)

private val LANGUAGE_PROPERTIES = listOf("code", "vertex", "fragment", "compute", "mesh", "task")

/* =============================================================================
   Prompt templates
   ============================================================================= */

private fun baseAssistantContract(langName: String): String = """
You are a code-writing assistant for $langName embedded in an IDE.

Hard rules:
- Return ONLY the complete edited code for the current file (no markdown fences, no diffs, no prose).
- Preserve existing code as much as possible; make the smallest coherent change set.
- Do not leave <help>, <error>, <cursor>, <rewrite> markers in the final code.
- If the input contains the markers '/*preamble*/ fun SCRIPT() {' and '/*postamble*/ }', you MUST preserve them exactly in your output.
- If you decide no changes are needed, return the original code unchanged.
- If the input contains <cursor>, treat it as the insertion point and keep surrounding code intact.
- If the input contains <rewrite>...</rewrite>, only rewrite the code between those markers.
- If the input contains <error>...</error>, fix the error described or indicated there.

Non-code notes/questions/alternatives:
- Do NOT include them inline in the code.
- Instead, call the Commentary tool with those notes.
- If you choose not to modify the code, call Commentary with a brief reason.

Tools:
- When you call a tool, you must wait for its output before continuing.
""".trimIndent()

fun generalInstructions(lang: LanguageSpec): String =
    baseAssistantContract(lang.name)

fun generalChatInstructions(lang: LanguageSpec, prompt: String): String = """
You are an IDE-embedded assistant inside Field, a live-coding environment, working with $lang.

Guidelines:
- Respond in markdown (prose is OK).
- Prefer grounding answers in the project's actual code: look up symbols/definitions and use their source.
- Use tools (WorkspaceSymbolSearch, ListDefinitions, ListContents, EditFile, etc.) when identifiers or behaviors matter.
- Do not paste full files unless explicitly asked.
- The current box/file content is provided in the conversation for context.

User request: $prompt
""".trimIndent()

fun inlineInstructions(lang: LanguageSpec, prompt: String, line: Int): String = """
${baseAssistantContract(lang.name)}

The user wants help starting at line $line. The position is marked with <help>.
Focus edits at and after <help> when reasonable.
User request: $prompt
""".trimIndent()

fun errorInstructions(lang: LanguageSpec, errorMessage: String): String = """
${baseAssistantContract(lang.name)}

The code has an error: $errorMessage
The faulty region is marked with <error> and </error>. Fix it.
""".trimIndent()

/* =============================================================================
   Tool execution context + tool providers
   ============================================================================= */

data class ToolExecutionContext(
    val attachments: List<ContextAttachment> = emptyList(),

    // Kotlin-only providers (optional)
    val listDefinitions: (() -> ListDefinitionsResult)? = null,
    val listFileContents: (() -> ListContentsResult)? = null,
    val editOtherFile: ((EditFileRequest) -> EditFileResponse)? = null,

    // Optional extra tools
    val workspaceSymbolSearch: ((WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult)? = null
)

fun virtualFilesToAttachments(files: List<VirtualTextFile>): List<ContextAttachment> =
    files.map { ContextAttachment(it.ref.fileName, it.ref.language, it.content) }

/* =============================================================================
   Bridge
   ============================================================================= */

class OpenAiIdeCodexBridge(
    private val client: OpenAIClient = OpenAIOkHttpClient.fromEnv(),
    model: String = ChatModel.GPT_5_1_CODEX.toString(),
    private val log: LogSink = StdoutLog,
    private val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM
) {
    private val chatModel: ChatModel = ChatModel.of(model)
    private val mapper: ObjectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    private val tools = ToolRegistry()
    private val trace: TraceLogger? = TraceLogger.fromEnv()
    private val textConfig: ResponseTextConfig =
        ResponseTextConfig.builder().format(ResponseFormatText.builder().build()).build()
    private val reasoningConfig: Reasoning =
        Reasoning.builder()
            .effort(reasoningEffort)
            .summary(Reasoning.Summary.DETAILED)
            .build()

    init {
        registerBuiltIns()
        log.log("Initialized OpenAiIdeCodexBridge(model=$chatModel)")
    }

    fun <T : Any> registerTool(toolClass: Class<T>, handler: (ToolExecutionContext, T) -> Any) {
        tools.register(toolClass, handler)
    }

    fun startThread(
        fileKey: String,
        fileText: String,
        instructions: String = defaultThreadInstructions
    ): ThreadState {
        log.log("startThread(): fileKey=$fileKey")
        val t0 = System.nanoTime()

        val localThreadId = UUID.randomUUID().toString()
        val metadata = ConversationCreateParams.Metadata.builder()
            .putAdditionalProperty("ide_file_key", JsonValue.from(fileKey))
            .putAdditionalProperty("local_thread_id", JsonValue.from(localThreadId))
            .build()

        val convParams = ConversationCreateParams.builder().metadata(metadata).build()
        val conversationId = client.conversations().create(convParams).id()

        appendDeveloperMessage(conversationId, instructions)
        appendUserMessage(
            conversationId,
            buildString {
                appendLine("IDE_FILE_CONTEXT:")
                appendLine("key: $fileKey")
                appendLine("content:")
                appendLine(fileText)
            }
        )

        val ms = (System.nanoTime() - t0) / 1_000_000
        log.log("startThread(): conversationId=$conversationId localThreadId=$localThreadId (${ms}ms)")
        return ThreadState(localThreadId, conversationId, fileKey)
    }

    /**
     * Send a prompt, chaining with previous_response_id when available.
     *
     * IMPORTANT: With the Responses API, when the model returns function calls,
     * send function call outputs back as **FunctionCallOutput input items**
     * linked by call_id and set previous_response_id to the tool-call response.
     *
     * Putting tool results in ordinary messages is NOT sufficient; the API will error:
     * "No tool output found for function call <call_id>"
     */
    fun send(
        state: ThreadState,
        userPrompt: String,
        fileText: String? = null,
        ctx: ToolExecutionContext = ToolExecutionContext(),
        vectorStoreIds: List<String> = emptyList(),
        maxOutputTokens: Long = 20480,
        maxToolRounds: Int = 8,
        onTextDelta: ((String) -> Unit)? = null
    ): SendResult {
        val t0 = System.nanoTime()

        val inputText = buildString {
            if (fileText != null) {
                appendLine("IDE_FILE_UPDATE:")
                appendLine("key: ${state.fileKey}")
                appendLine("content:")
                appendLine(fileText)
                appendLine()
            }
            appendLine(userPrompt)
        }

        trace?.log(
            "PROMPT",
            buildString {
                appendLine("conversationId=${state.conversationId}")
                appendLine("previousResponseId=${state.previousResponseId ?: "<none>"}")
                appendLine("fileKey=${state.fileKey}")
                appendLine("inputChars=${inputText.length}")
                if (vectorStoreIds.isNotEmpty()) {
                    appendLine("vectorStoreIds=${vectorStoreIds.joinToString(",")}")
                }
                appendLine()
                append(inputText)
            }
        )

        log.log(
            "convId=${state.conversationId} fileKey=${state.fileKey} inputChars=${inputText.length} " +
                    "maxOutputTokens=$maxOutputTokens attachments=${ctx.attachments.size}"
        )

        val paramsBuilder = ResponseCreateParams.builder()
            .model(chatModel)
            .input(inputText)
            .maxOutputTokens(maxOutputTokens)
        if (state.previousResponseId == null) {
            paramsBuilder.conversation(state.conversationId)
        } else {
            paramsBuilder.previousResponseId(state.previousResponseId)
        }
        paramsBuilder.text(textConfig)
        paramsBuilder.reasoning(reasoningConfig)
        tools.addAllTo(paramsBuilder)
        if (vectorStoreIds.isNotEmpty()) {
            paramsBuilder.addFileSearchTool(vectorStoreIds)
        }

        log.log("running prompt <<spinner>>")
        var response: Response = createResponse(paramsBuilder.build(), onTextDelta)
        if (onTextDelta != null) {
            println()
        }
        state.previousResponseId = response.id()
        log.log("initial response id=${response.id()} outputItems=${response.output().size} <<spinner>>")

        val commentaryMessages = mutableListOf<String>()

        fun logOutputShape(resp: Response) {
            val out = resp.output()
            val msgCount = out.count { it.isMessage() }
            val fnCount = out.count { it.isFunctionCall() }
            val otherCount = out.size - msgCount - fnCount
            log.log("output shape: messages=$msgCount functionCalls=$fnCount other=$otherCount")
            if (resp.error().isPresent) {
                val e = resp.error().get()
                log.log("response.error code=${e.code()} message=${e.message()}")
            }
            val msgStatuses = out.mapNotNull { it.message().orElse(null)?.status() }
            if (msgStatuses.isNotEmpty()) {
                val labels = msgStatuses.joinToString(",") { it.value().toString().lowercase() }
                log.log("message status(es)=$labels")
                if (msgStatuses.any { it.value() == ResponseOutputMessage.Status.Value.INCOMPLETE }) {
                    log.log("WARNING: message status incomplete; output may be truncated.")
                }
            }
            if (msgCount == 0) logOutputDetails(resp)
        }

        logOutputShape(response)

        var rounds = 0
        while (rounds < maxToolRounds) {
            val calls = response.output()
                .filter { it.isFunctionCall() }
                .mapNotNull { it.functionCall().orElse(null) }

            if (calls.isEmpty()) {
                log.log("no tool calls; finishing")
                break
            }

            log.log("toolCalls=${calls.size} (round ${rounds + 1}/$maxToolRounds)")
            val toolOutputs = mutableListOf<ResponseInputItem>()
            for (call in calls) {
                log.log("tool: name=${call.name()} callId=${call.callId()} ${call.arguments()}")
                trace?.log(
                    "TOOL_CALL",
                    "name=${call.name()} callId=${call.callId()}\nargs=${call.arguments()}"
                )

                val toolResult: Any = try {
                    tools.execute(ctx, call)
                } catch (e: Exception) {
                    log.log("tool: ERROR name=${call.name()} msg=${e.message}")
                    mapOf(
                        "error" to true,
                        "tool" to call.name(),
                        "message" to (e.message ?: e::class.java.name)
                    )
                }

                trace?.log(
                    "TOOL_OUTPUT",
                    buildString {
                        appendLine("name=${call.name()} callId=${call.callId()}")
                        val json = runCatching { mapper.writeValueAsString(toolResult) }
                            .getOrElse { toolResult.toString() }
                        append(json)
                    }
                )

                if (call.name() == Commentary::class.java.simpleName) {
                    // Extract commentary regardless of handler output
                    try {
                        val c = call.arguments(Commentary::class.java)
                        commentaryMessages += c.text
                        log.log("tool: Commentary captured (${c.text.length} chars)")
                    } catch (e: Exception) {
                        log.log("tool: Commentary parse failed: ${e.message}")
                    }
                }

                // Attach tool result as FunctionCallOutput input items for the follow-up request.
                val outputItem = ResponseInputItem.FunctionCallOutput.builder()
                    .callId(call.callId())
                    .outputAsJson(toolResult)
                    .build()
                toolOutputs += ResponseInputItem.ofFunctionCallOutput(outputItem)
            }

            log.log("creating response (follow-up) <<spinner>>")
            val followupBuilder = ResponseCreateParams.builder()
                .model(chatModel)
                .previousResponseId(response.id())
                .input(ResponseCreateParams.Input.ofResponse(toolOutputs))
                .maxOutputTokens(maxOutputTokens)
            followupBuilder.text(textConfig)
            followupBuilder.reasoning(reasoningConfig)
            tools.addAllTo(followupBuilder)
            if (vectorStoreIds.isNotEmpty()) {
                followupBuilder.addFileSearchTool(vectorStoreIds)
            }

            response = createResponse(followupBuilder.build(), onTextDelta)
            if (onTextDelta != null) {
                println()
            }
            state.previousResponseId = response.id()
            log.log("follow-up response id=${response.id()} outputItems=${response.output().size}")
            logOutputShape(response)

            rounds++
        }

        val text = extractOutputTextWithNewlines(response)
        val reasoningSummaries = extractReasoningSummaries(response)
        trace?.log(
            "RESPONSE",
            buildString {
                appendLine("responseId=${response.id()}")
                appendLine("textChars=${text.length}")
                appendLine("commentaryCount=${commentaryMessages.size}")
                appendLine()
                append(text)
            }
        )
        if (text.isBlank() && reasoningSummaries.isNotEmpty()) {
            commentaryMessages += reasoningSummaries
            val combined = reasoningSummaries.joinToString(" ").trim()
            log.log("reasoning summary (no output_text): ${truncate(combined, 400)}")
        }

        if (text.isBlank()) {
            // This is a very common integration gotcha; log a big hint.
            log.log("WARNING: model returned no output_text. " +
                    "If output shape shows messages=0, your model may be returning non-text items. " +
                    "Try increasing maxOutputTokens or simplifying prompt.")
        }

        val ms = (System.nanoTime() - t0) / 1_000_000
        log.log(
            "done responseId=${response.id()} textChars=${text.length} " +
                    "commentaryCount=${commentaryMessages.size} (${ms}ms)"
        )

        return SendResult(
            threadState = state,
            text = text,
            rawResponseId = response.id(),
            commentary = commentaryMessages.toList()
        )
    }

    fun threadStateToJson(state: ThreadState): String = mapper.writeValueAsString(state)
    fun threadStateFromJson(json: String): ThreadState = mapper.readValue(json)

    data class SendResult(
        val threadState: ThreadState,
        val text: String,
        val rawResponseId: String,
        val commentary: List<String> = emptyList()
    )

    data class ThreadState(
        val localThreadId: String,
        val conversationId: String,
        val fileKey: String,
        var previousResponseId: String? = null
    )

    /* ------------------------- Conversations helpers ------------------------ */

    private fun appendUserMessage(conversationId: String, text: String) =
        appendMessage(conversationId, ResponseInputItem.Message.Role.USER, text)

    private fun appendDeveloperMessage(conversationId: String, text: String) =
        appendMessage(conversationId, ResponseInputItem.Message.Role.DEVELOPER, text)

    private fun appendMessage(conversationId: String, role: ResponseInputItem.Message.Role, text: String) {
        val message = ResponseInputItem.Message.builder()
            .role(role)
            .addInputTextContent(text)
            .build()

        val params = ItemCreateParams.builder()
            .conversationId(conversationId)
            .addItem(message)
            .build()

        client.conversations().items().create(conversationId, params)
    }

    private fun createResponse(params: ResponseCreateParams, onTextDelta: ((String) -> Unit)?): Response {
        if (onTextDelta == null) return client.responses().create(params)

        val acc = ResponseAccumulator.create()
        client.async().responses().createStreaming(params).subscribe({ event ->
            acc.accumulate(event)
            event.outputTextDelta().ifPresent { delta ->
                val chunk = delta.delta()
                if (chunk.isNotEmpty()) onTextDelta(chunk)
            }
        }).onCompleteFuture().join()

        return acc.response()
    }

    private fun extractOutputTextWithNewlines(response: Response): String {
        val parts = mutableListOf<String>()
        response.output().forEach { item ->
            if (item.isMessage()) {
                val msg = item.message().orElse(null)
                msg?.content()?.forEach { content ->
                    val outText = content.outputText().orElse(null)
                    if (outText != null) parts += outText.text()
                }
            }
        }
        return parts.joinToString(separator = "\n").trimEnd()
    }

    private fun logOutputDetails(response: Response) {
        response.output().forEachIndexed { idx, item ->
            val desc = when {
                item.isMessage() -> {
                    val msg = item.message().orElse(null)
                    if (msg == null) {
                        "message"
                    } else {
                        val pieces = msg.content().map { content ->
                            when {
                                content.outputText().isPresent -> {
                                    val len = content.outputText().get().text().length
                                    "output_text(len=$len)"
                                }
                                content.refusal().isPresent -> "refusal"
                                else -> "content_unknown"
                            }
                        }
                        "message status=${msg.status()} content=${pieces.joinToString(",")}"
                    }
                }
                item.isFunctionCall() -> {
                    val call = item.functionCall().orElse(null)
                    if (call == null) "function_call" else "function_call name=${call.name()}"
                }
                item.isReasoning() -> {
                    val reasoning = item.reasoning().orElse(null)
                    if (reasoning == null) {
                        "reasoning"
                    } else {
                        val summary = try {
                            reasoning.summary().joinToString(" ") { it.text() }
                        } catch (_: Exception) {
                            ""
                        }
                        val summaryPart = if (summary.isBlank()) "" else " summary=${truncate(summary, 200)}"
                        "reasoning status=${reasoning.status().orElse(null)}$summaryPart"
                    }
                }
                item.isCompaction() -> "compaction"
                item.isFileSearchCall() -> "file_search_call"
                item.isWebSearchCall() -> "web_search_call"
                item.isComputerCall() -> "computer_call"
                item.isImageGenerationCall() -> "image_generation_call"
                item.isCodeInterpreterCall() -> "code_interpreter_call"
                item.isLocalShellCall() -> "local_shell_call"
                item.isShellCall() -> "shell_call"
                item.isShellCallOutput() -> "shell_call_output"
                item.isApplyPatchCall() -> "apply_patch_call"
                item.isApplyPatchCallOutput() -> "apply_patch_call_output"
                item.isMcpCall() -> "mcp_call"
                item.isMcpListTools() -> "mcp_list_tools"
                item.isMcpApprovalRequest() -> "mcp_approval_request"
                item.isCustomToolCall() -> "custom_tool_call"
                else -> "unknown"
            }
            log.log("output[$idx] $desc")
            if (desc == "unknown") {
                val raw = item._json().orElse(null)?.toString()
                if (raw != null) log.log("output[$idx] raw=${truncate(raw, 400)}")
            }
        }
    }

    private fun extractReasoningSummaries(response: Response): List<String> =
        response.output().mapNotNull { item ->
            val reasoning = item.reasoning().orElse(null) ?: return@mapNotNull null
            val summary = try {
                reasoning.summary().joinToString(" ") { it.text() }.trim()
            } catch (_: Exception) {
                ""
            }
            summary.ifBlank { null }
        }

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.substring(0, max) + "..."

    /* ------------------------------ Tools ---------------------------------- */

    private fun registerBuiltIns() {
        // Commentary: handler can be a no-op acknowledgement; we extract text from call args anyway.
        tools.register(Commentary::class.java) { _, _ -> mapOf("ok" to true) }

        // Attachments
        tools.register(ListAttachmentsRequest::class.java) { ctx, _ ->
            ListAttachmentsResult(
                attachments = ctx.attachments.map { AttachmentInfo(it.fileName, it.language) }
            )
        }
        tools.register(AttachmentRequest::class.java) { ctx, req ->
            val hit = ctx.attachments.firstOrNull { it.fileName == req.file }
                ?: throw IllegalArgumentException("No attachment named '${req.file}'. Call ListAttachments first.")
            AttachmentResponse(hit.fileName, hit.language, hit.code)
        }

        // Kotlin-only tools (only work if provider is supplied in ctx)
        tools.register(ListDefinitionsRequest::class.java) { ctx, _ ->
            ctx.listDefinitions?.invoke()
                ?: throw IllegalStateException("ListDefinitions not available for this request.")
        }
        tools.register(ListContentsRequest::class.java) { ctx, _ ->
            ctx.listFileContents?.invoke()
                ?: throw IllegalStateException("ListContents not available for this request.")
        }
        tools.register(EditFileRequest::class.java) { ctx, req ->
            ctx.editOtherFile?.invoke(req)
                ?: throw IllegalStateException("EditFile not available for this request.")
        }

        // Optional extra tool
        tools.register(WorkspaceSymbolSearch::class.java) { ctx, req ->
            ctx.workspaceSymbolSearch?.invoke(req)
                ?: throw IllegalStateException("WorkspaceSymbolSearch not available for this request.")
        }
    }

    private class ToolRegistry {
        private data class Handler<T : Any>(
            val cls: Class<T>,
            val fn: (ToolExecutionContext, T) -> Any
        )

        private val handlers = ConcurrentHashMap<String, Handler<*>>()

        fun <T : Any> register(toolClass: Class<T>, handler: (ToolExecutionContext, T) -> Any) {
            handlers[toolClass.simpleName] = Handler(toolClass, handler)
        }

        fun addAllTo(builder: ResponseCreateParams.Builder) {
            handlers.values.forEach { h ->
                @Suppress("UNCHECKED_CAST")
                builder.addTool(h.cls as Class<Any>)
            }
        }

        fun execute(ctx: ToolExecutionContext, call: ResponseFunctionToolCall): Any {
            val h = handlers[call.name()]
                ?: throw IllegalArgumentException("No tool registered for: ${call.name()}")
            @Suppress("UNCHECKED_CAST")
            val handler = h as Handler<Any>
            val args: Any = call.arguments(handler.cls)
            return handler.fn(ctx, args)
        }
    }

    companion object {
        val defaultThreadInstructions: String = """
You are an IDE-embedded code assistant working in a novel live-coding environment.
Return only code for the current file in responses (no markdown fences, no diffs, no prose).
If you have non-code thoughts, call the Commentary tool. Commentary, reasoning and questions there are welcome if you use the tool.
        """.trimIndent()

        val defaultChatThreadInstructions: String = """
You are an IDE-embedded assistant working in a novel live-coding environment.
Respond in markdown. You may discuss, explain, and propose options.
If you need to change code, use the available tools rather than dumping entire files.
        """.trimIndent()
    }
}

/* =============================================================================
   High-level helpers
   ============================================================================= */

class IdeChatHelpers(
    private val bridge: OpenAiIdeCodexBridge,
    private val log: LogSink = StdoutLog
) {

    data class HighLevelResult(
        val editedCode: String,
        val commentary: List<String>,
        val rawResponseId: String,
        val threadState: OpenAiIdeCodexBridge.ThreadState
    )

    fun chatFile(
        state: OpenAiIdeCodexBridge.ThreadState,
        code: String,
        lang: LanguageSpec,
        userPrompt: String,
        attachments: List<ContextAttachment> = emptyList(),
        listDefinitions: (() -> ListDefinitionsResult)? = null,
        listFileContents: (() -> ListContentsResult)? = null,
        editOtherFile: ((EditFileRequest) -> EditFileResponse)? = null,
        workspaceSymbolSearch: ((WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult)? = null,
        vectorStoreIds: List<String> = emptyList(),
        onTextDelta: ((String) -> Unit)? = null
    ): HighLevelResult {
        log.log("chatFile(): lang=${lang.name} codeChars=${code.length} attachments=${attachments.size}")

        val ctx = ToolExecutionContext(
            attachments = attachments,
            listDefinitions = listDefinitions,
            listFileContents = listFileContents,
            editOtherFile = editOtherFile,
            workspaceSymbolSearch = workspaceSymbolSearch
        )

        val instructions = buildDynamicInstructions(
            mode = Mode.General,
            lang = lang,
            userPrompt = userPrompt,
            errorMessage = null,
            lineNumber = null,
            ctx = ctx
        )

        val wrapped = lang.wrap(code)
        val result = bridge.send(
            state,
            "$instructions\n\n$userPrompt\n\n$wrapped",
            ctx = ctx,
            vectorStoreIds = vectorStoreIds,
            onTextDelta = onTextDelta
        )
        val unwrapped = lang.unwrap(result.text)

        validateNoMarkers(unwrapped)

        log.log("chatFile(): done editedChars=${unwrapped.length} commentary=${result.commentary.size}")
        return HighLevelResult(
            editedCode = unwrapped,
            commentary = result.commentary,
            rawResponseId = result.rawResponseId,
            threadState = result.threadState
        )
    }

    fun chatGeneral(
        state: OpenAiIdeCodexBridge.ThreadState,
        code: String,
        lang: LanguageSpec,
        userPrompt: String,
        attachments: List<ContextAttachment> = emptyList(),
        listDefinitions: (() -> ListDefinitionsResult)? = null,
        listFileContents: (() -> ListContentsResult)? = null,
        editOtherFile: ((EditFileRequest) -> EditFileResponse)? = null,
        workspaceSymbolSearch: ((WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult)? = null,
        vectorStoreIds: List<String> = emptyList(),
        onTextDelta: ((String) -> Unit)? = null
    ): OpenAiIdeCodexBridge.SendResult {
        log.log("chatGeneral(): lang=${lang.name} codeChars=${code.length} attachments=${attachments.size}")

        val ctx = ToolExecutionContext(
            attachments = attachments,
            listDefinitions = listDefinitions,
            listFileContents = listFileContents,
            editOtherFile = editOtherFile,
            workspaceSymbolSearch = workspaceSymbolSearch
        )

        val instructions = buildDynamicInstructions(
            mode = Mode.Chat,
            lang = lang,
            userPrompt = userPrompt,
            errorMessage = null,
            lineNumber = null,
            ctx = ctx
        )

        return bridge.send(
            state,
            "$instructions\n\n$userPrompt",
            fileText = code,
            ctx = ctx,
            vectorStoreIds = vectorStoreIds,
            onTextDelta = onTextDelta
        )
    }

    fun chatInline(
        state: OpenAiIdeCodexBridge.ThreadState,
        code: String,
        lang: LanguageSpec,
        lineNumber: Int,
        userPrompt: String,
        attachments: List<ContextAttachment> = emptyList(),
        listDefinitions: (() -> ListDefinitionsResult)? = null,
        listFileContents: (() -> ListContentsResult)? = null,
        editOtherFile: ((EditFileRequest) -> EditFileResponse)? = null,
        workspaceSymbolSearch: ((WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult)? = null,
        vectorStoreIds: List<String> = emptyList(),
        onTextDelta: ((String) -> Unit)? = null
    ): HighLevelResult {
        log.log("chatInline(): lang=${lang.name} line=$lineNumber codeChars=${code.length} attachments=${attachments.size}")

        val ctx = ToolExecutionContext(
            attachments = attachments,
            listDefinitions = listDefinitions,
            listFileContents = listFileContents,
            editOtherFile = editOtherFile,
            workspaceSymbolSearch = workspaceSymbolSearch
        )

        val instructions = buildDynamicInstructions(
            mode = Mode.Inline,
            lang = lang,
            userPrompt = userPrompt,
            errorMessage = null,
            lineNumber = lineNumber,
            ctx = ctx
        )

        val wrappedLines = lang.wrap(code).split("\n").toMutableList()
        val insertAt = (lineNumber + 1).coerceIn(0, wrappedLines.size)
        wrappedLines.add(insertAt, "<help>")
        val withHelp = wrappedLines.joinToString("\n")

        val result = bridge.send(
            state,
            "$instructions\n\n$withHelp",
            ctx = ctx,
            vectorStoreIds = vectorStoreIds,
            onTextDelta = onTextDelta
        )
        val unwrapped = lang.unwrap(result.text)

        validateNoMarkers(unwrapped)

        log.log("chatInline(): done editedChars=${unwrapped.length} commentary=${result.commentary.size}")
        return HighLevelResult(
            editedCode = unwrapped,
            commentary = result.commentary,
            rawResponseId = result.rawResponseId,
            threadState = result.threadState
        )
    }

    fun chatError(
        state: OpenAiIdeCodexBridge.ThreadState,
        code: String,
        lang: LanguageSpec,
        errorMessage: String,
        attachments: List<ContextAttachment> = emptyList(),
        listDefinitions: (() -> ListDefinitionsResult)? = null,
        listFileContents: (() -> ListContentsResult)? = null,
        editOtherFile: ((EditFileRequest) -> EditFileResponse)? = null,
        workspaceSymbolSearch: ((WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult)? = null,
        vectorStoreIds: List<String> = emptyList(),
        onTextDelta: ((String) -> Unit)? = null
    ): HighLevelResult {
        log.log("chatError(): lang=${lang.name} error='${errorMessage.take(120)}' codeChars=${code.length}")

        val ctx = ToolExecutionContext(
            attachments = attachments,
            listDefinitions = listDefinitions,
            listFileContents = listFileContents,
            editOtherFile = editOtherFile,
            workspaceSymbolSearch = workspaceSymbolSearch
        )

        val instructions = buildDynamicInstructions(
            mode = Mode.Error,
            lang = lang,
            userPrompt = null,
            errorMessage = errorMessage,
            lineNumber = null,
            ctx = ctx
        )

        val wrapped = lang.wrap(code)
        val result = bridge.send(
            state,
            "$instructions\n\n$wrapped",
            ctx = ctx,
            vectorStoreIds = vectorStoreIds,
            onTextDelta = onTextDelta
        )
        val unwrapped = lang.unwrap(result.text)

        validateNoMarkers(unwrapped)

        log.log("chatError(): done editedChars=${unwrapped.length} commentary=${result.commentary.size}")
        return HighLevelResult(
            editedCode = unwrapped,
            commentary = result.commentary,
            rawResponseId = result.rawResponseId,
            threadState = result.threadState
        )
    }

    private enum class Mode { General, Inline, Error, Chat }

    private fun buildDynamicInstructions(
        mode: Mode,
        lang: LanguageSpec,
        userPrompt: String?,
        errorMessage: String?,
        lineNumber: Int?,
        ctx: ToolExecutionContext
    ): String {
        val core = when (mode) {
            Mode.General -> generalInstructions(lang)
            Mode.Inline -> inlineInstructions(lang, userPrompt ?: "", lineNumber ?: 0)
            Mode.Error -> errorInstructions(lang, errorMessage ?: "")
            Mode.Chat -> generalChatInstructions(lang, userPrompt ?: "")
        }

        val toolAds = mutableListOf<String>()

        toolAds += "- Commentary: use this for non-code notes/questions/alternatives."

        if (ctx.attachments.isNotEmpty()) {
            toolAds += "- ListAttachments: lists available attached context files."
            toolAds += "- AttachmentRequest: retrieve an attached file's contents by filename (call ListAttachments first)."
        }

        val isKotlin = lang.name.equals("Kotlin", ignoreCase = true)
        if (isKotlin && ctx.listDefinitions != null) toolAds += "- ListDefinitions: list visible definitions (including shaders associated with Kotlin)."
        if (isKotlin && ctx.listFileContents != null) toolAds += "- ListContents: get full contents of the current Kotlin file."
        if (ctx.editOtherFile != null) toolAds += "- EditFile: update another file by name (e.g., paired shaders)."
        if (ctx.workspaceSymbolSearch != null) {
            toolAds += "- WorkspaceSymbolSearch: search workspace symbols by query."
            toolAds += "  Results include filePath, fileId, line, definitionLine, and definitionBody (for fun) when available."
        }

        val guidance = if (mode == Mode.Chat) {
            """
- Prefer calling tools over guessing (definitions, other file contents, attachments).
- If a symbol or behavior is mentioned, use WorkspaceSymbolSearch to locate definitions and ground the answer.
- If an exact match fails, try softer queries (short name, receiver+name, suffix, drop generics).
- If you call a tool, wait for its output before continuing.
            """.trimIndent()
        } else {
            """
- Prefer calling tools over guessing (definitions, other file contents, attachments).
- Keep code output clean; put explanations in Commentary tool calls.
- If you call a tool, wait for its output before continuing.
            """.trimIndent()
        }

        val toolsSection = """

Tools available:
${toolAds.joinToString("\n")}

Guidance:
$guidance
        """.trimIndent()

        return "$core\n$toolsSection"
    }

    private fun validateNoMarkers(code: String) {
        val bad = listOf("<help>", "<error>", "</error>", "<cursor>", "<rewrite>", "</rewrite>")
        val hit = bad.firstOrNull { code.contains(it) }
        if (hit != null) throw MalformedAI("Model output still contained marker '$hit'. Refusing to apply.")
    }
}

/* =============================================================================
   GLSL autocomplete
   ============================================================================= */

private val GLSL_PROPERTY_SPECS: Map<String, LanguageSpec> = mapOf(
    "vertex" to GLSL_VERTEX,
    "fragment" to GLSL_FRAGMENT,
    "mesh" to GLSL_MESH,
    "task" to GLSL_TASK,
    "compute" to GLSL_COMPUTE
)

fun glslSpecForProperty(property: String): LanguageSpec? =
    GLSL_PROPERTY_SPECS[property.lowercase()]

private fun glslCompletionThreadInstructions(langName: String): String = """
You are an IDE autocomplete engine for $langName.

Rules:
- Output 3 candidate completions separated by a line containing only: ---
- Each candidate is ONLY the text to insert at <cursor>.
- After the candidates, output a notes block starting with the line: <<<notes>>>
- In the notes block, provide brief notes for candidates 1, 2, and 3 (one line each).
- Do not include markdown or backticks.
- Do not repeat existing code around the cursor.
- Keep each candidate short (a few lines max).
- Be willing to speculate; prefer a plausible completion to an empty output.
- Do not call tools; output plain text only.
""".trimIndent()

private fun glslCompletionPrompt(
    lang: LanguageSpec,
    stage: String,
    line: Int,
    column: Int,
    codeWithCursor: String,
    contextBlock: String
): String = """
Return 3 completion candidates separated by a line containing only: ---
Then include a notes block that starts with: <<<notes>>>
Format the notes as:
1) ...
2) ...
3) ...
Stage: $stage
Cursor: line $line, column $column

Code:
$codeWithCursor

${contextBlock.trim()}
""".trimIndent()

private fun glslContextBlock(context: Map<String, String>): String {
    if (context.isEmpty()) return ""
    val entries = context.filterValues { it.isNotBlank() }
    if (entries.isEmpty()) return ""
    return buildString {
        appendLine("Related context:")
        entries.forEach { (name, value) ->
            appendLine("<<<$name>>>")
            appendLine(value.trimEnd())
            appendLine("<<<END $name>>>")
            appendLine()
        }
    }.trimEnd()
}

private fun cleanCompletionText(raw: String): String {
    var text = raw.trim()
    if (text.startsWith("```")) {
        val lines = text.lines()
        val fenceEnd = lines.indexOfLast { it.trim().startsWith("```") }
        if (fenceEnd > 0) {
            text = lines.subList(1, fenceEnd).joinToString("\n").trim()
        }
    }
    text = text.replace("<cursor>", "")
        .replace("<rewrite>", "")
        .replace("</rewrite>", "")
        .replace("<help>", "")
        .replace("<error>", "")
        .replace("</error>", "")
    return text.trimEnd()
}

private fun splitCompletionCandidates(text: String): List<String> {
    val results = mutableListOf<String>()
    val current = StringBuilder()
    val lines = text.split("\n")
    for (line in lines) {
        if (line.trim() == "---") {
            val candidate = current.toString().trim()
            if (candidate.isNotEmpty()) results += candidate
            current.clear()
            continue
        }
        if (current.isNotEmpty()) current.append("\n")
        current.append(line)
    }
    val candidate = current.toString().trim()
    if (candidate.isNotEmpty()) results += candidate
    return results
}

private fun splitNotesBlock(text: String): Pair<String, String> {
    val marker = Regex("(?i)<<<notes>>>")
    val match = marker.find(text) ?: return text.trimEnd() to ""
    val candidatesText = text.substring(0, match.range.first).trimEnd()
    val notesText = text.substring(match.range.last + 1).trim()
    return candidatesText to notesText
}

data class CompletionCandidate(
    val text: String,
    val documentation: String
)

private fun parseCandidateDocs(commentary: String, count: Int): List<String> {
    if (count <= 0) return emptyList()
    val docs = MutableList(count) { "" }
    if (commentary.isBlank()) return docs
    val regex = Regex("^\\s*(\\d+)[).:-]?\\s*(.*)$")
    var current = -1
    commentary.lines().forEach { line ->
        val match = regex.matchEntire(line)
        if (match != null) {
            val idx = match.groupValues[1].toIntOrNull()?.minus(1) ?: -1
            if (idx in 0 until count) {
                current = idx
                docs[idx] = match.groupValues[2].trim()
                return@forEach
            }
        }
        if (current >= 0) {
            val existing = docs[current]
            docs[current] = if (existing.isBlank()) line.trim() else (existing + "\n" + line.trim()).trim()
        }
    }
    if (docs.any { it.isNotBlank() }) return docs
    val fallback = commentary.trim()
    return List(count) { fallback }
}

object Ai4GlslAutocomplete {
    private val bridge: OpenAiIdeCodexBridge by lazy {
        val fastModel = System.getenv("AFIELD_GLSL_AUTOCOMPLETE_MODEL")
            ?: "gpt-5.2"
        OpenAiIdeCodexBridge(
            model = fastModel,
            log = SilentLog,
            reasoningEffort = ReasoningEffort.LOW
        )
    }
    private val threadByKey = ConcurrentHashMap<String, OpenAiIdeCodexBridge.ThreadState>()

    fun complete(
        fileKey: String,
        property: String,
        code: String,
        context: Map<String, String>,
        line: Int,
        column: Int,
        position: Int,
        maxOutputTokens: Long = 400
    ): List<CompletionCandidate> {
        val lang = glslSpecForProperty(property) ?: return emptyList()
        val stage = property.lowercase()
        val safePos = position.coerceIn(0, code.length)
        val codeWithCursor = code.substring(0, safePos) + "<cursor>" + code.substring(safePos)
        val wrapped = lang.wrap(codeWithCursor)
        val contextBlock = glslContextBlock(context)

        val state = threadByKey[fileKey] ?: bridge.startThread(
            fileKey,
            lang.wrap(code),
            instructions = glslCompletionThreadInstructions(lang.name)
        ).also { threadByKey[fileKey] = it }

        val prompt = glslCompletionPrompt(lang, stage, line, column, wrapped, contextBlock)
        val result = bridge.send(state, prompt, maxOutputTokens = maxOutputTokens)
        val cleaned = cleanCompletionText(result.text)
        val (candidatesText, notesText) = splitNotesBlock(cleaned)
        val candidates = splitCompletionCandidates(candidatesText)
        val fallbackNotes = result.commentary.joinToString("\n").trim()
        val docs = parseCandidateDocs(if (notesText.isNotBlank()) notesText else fallbackNotes, candidates.size)
        return candidates.mapIndexed { idx, text ->
            CompletionCandidate(text, docs.getOrElse(idx) { "" })
        }
    }
}

object Ai4CompletionDoc {
    private val bridge: OpenAiIdeCodexBridge by lazy {
        val model = System.getenv("AFIELD_COMPLETION_DOC_MODEL") ?: "gpt-5.2"
        OpenAiIdeCodexBridge(
            model = model,
            log = SilentLog,
            reasoningEffort = ReasoningEffort.LOW
        )
    }
    private val threadByKey = ConcurrentHashMap<String, OpenAiIdeCodexBridge.ThreadState>()

    fun resolveDoc(
        fileKey: String,
        label: String,
        displayText: String,
        insertText: String,
        detail: String,
        existingDoc: String,
        allCode: String,
        line: Int,
        column: Int
    ): String {
        val symbol = extractSymbolName(displayText, insertText, label)
        val definition = findDefinition(symbol)
        val definitionSnippet = definition?.let { loadDefinitionSnippet(it) }
        val cursorContext = snippetAroundCursor(allCode, line, column)

        val instructions = """
You write concise usage notes for code completion candidates.
Respond in markdown only. Keep it short and directly useful.
If you include code, keep it minimal and relevant.
        """.trimIndent()

        val state = threadByKey[fileKey] ?: bridge.startThread(
            fileKey = fileKey,
            fileText = "",
            instructions = instructions
        ).also { threadByKey[fileKey] = it }

        val prompt = buildString {
            appendLine("Completion candidate:")
            appendLine("- symbol: ${symbol.ifBlank { label }}")
            appendLine("- label: $label")
            if (displayText.isNotBlank()) appendLine("- display: $displayText")
            if (detail.isNotBlank()) appendLine("- detail: $detail")
            if (insertText.isNotBlank()) {
                appendLine()
                appendLine("Insert text:")
                appendLine("```kotlin")
                appendLine(insertText.trimEnd())
                appendLine("```")
            }
            if (cursorContext.isNotBlank()) {
                appendLine()
                appendLine("Current context:")
                appendLine("```kotlin")
                appendLine(cursorContext.trimEnd())
                appendLine("```")
            }
            if (definitionSnippet != null) {
                appendLine()
                appendLine("Definition source (${definitionSnippet.path}:${definitionSnippet.line}):")
                if (!definitionSnippet.fileId.isNullOrBlank()) {
                    appendLine("fileId: ${definitionSnippet.fileId}")
                }
                appendLine("```kotlin")
                appendLine(definitionSnippet.snippet.trimEnd())
                appendLine("```")
            }
            if (existingDoc.isNotBlank()) {
                appendLine()
                appendLine("Existing documentation (if any):")
                appendLine(existingDoc.trimEnd())
            }
            appendLine()
            appendLine("Write a short usage note with a one-line summary and 2-4 bullets.")
        }

        val result = bridge.send(
            state = state,
            userPrompt = prompt,
            maxOutputTokens = 400
        )
        return result.text.trimEnd()
    }

    private data class DefinitionSnippet(
        val path: String,
        val line: Int,
        val fileId: String?,
        val snippet: String
    )

    private fun extractSymbolName(displayText: String, insertText: String, label: String): String {
        val candidates = listOf(displayText, insertText, label)
        for (raw in candidates) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            parseSymbolFromDeclaration(text)?.let { return it }
            parseQualifiedName(text)?.let { return it }
        }
        return label.trim()
    }

    private fun unwrapIdentifier(raw: String): String =
        if (raw.startsWith("`") && raw.endsWith("`") && raw.length > 1) {
            raw.removePrefix("`").removeSuffix("`")
        } else {
            raw
        }

    private fun parseSymbolFromDeclaration(text: String): String? {
        val match = Regex("^(fun|class|interface|object|val|var|typealias)\\s+(.+)$")
            .find(text)
            ?: return null
        val kind = match.groupValues[1]
        val remainder = match.groupValues[2].trimStart()
        return when (kind) {
            "fun" -> {
                val head = remainder.substringBefore("(").trim()
                parseQualifiedName(head)
            }
            "val", "var" -> {
                val head = remainder.substringBefore(":").substringBefore("=").trim()
                parseQualifiedName(head)
            }
            "class", "interface", "object", "typealias" -> {
                Regex("^(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)")
                    .find(remainder)?.groupValues?.get(1)?.let { unwrapIdentifier(it) }
            }
            else -> null
        }
    }

    private fun parseQualifiedName(raw: String): String? {
        if (raw.isBlank()) return null
        val noGenerics = stripGenericArgs(raw)
        val compact = removeWhitespaceOutsideBackticks(noGenerics).replace("?", "")
        if (compact.isBlank()) return null
        val regex = Regex("^(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)(\\.`[^`]+`|\\.[A-Za-z_][A-Za-z0-9_]*)*$")
        val match = regex.find(compact) ?: return null
        return match.value.split(".").joinToString(".") { unwrapIdentifier(it) }
    }

    private fun stripGenericArgs(raw: String): String {
        val sb = StringBuilder(raw.length)
        var depth = 0
        for (ch in raw) {
            when (ch) {
                '<' -> depth++
                '>' -> if (depth > 0) depth--
                else -> if (depth == 0) sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun removeWhitespaceOutsideBackticks(raw: String): String {
        val sb = StringBuilder(raw.length)
        var inBackticks = false
        for (ch in raw) {
            if (ch == '`') {
                inBackticks = !inBackticks
                sb.append(ch)
                continue
            }
            if (!inBackticks && ch.isWhitespace()) continue
            sb.append(ch)
        }
        return sb.toString()
    }

    private fun findDefinition(symbol: String): KotlinDefinitionRecord? {
        if (symbol.isBlank()) return null
        val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex()
        val definitions = index?.definitions.orEmpty()
        definitions.firstOrNull { it.name == symbol }?.let { return it }
        definitions.firstOrNull { it.name.equals(symbol, ignoreCase = true) }?.let { return it }
        val dotIdx = symbol.lastIndexOf('.')
        if (dotIdx > 0 && dotIdx < symbol.length - 1) {
            val short = symbol.substring(dotIdx + 1)
            definitions.firstOrNull { it.name == short }?.let { return it }
            return definitions.firstOrNull { it.name.equals(short, ignoreCase = true) }
        }
        return null
    }

    private fun loadDefinitionSnippet(def: KotlinDefinitionRecord): DefinitionSnippet? {
        val path = Paths.get(def.filePath)
        if (!Files.exists(path)) return null
        val text = runCatching { Files.readString(path, StandardCharsets.UTF_8) }.getOrNull() ?: return null
        val lines = text.split("\n")
        val center = (def.line - 1).coerceIn(0, lines.lastIndex)
        val start = (center - 20).coerceAtLeast(0)
        val end = (center + 20).coerceAtMost(lines.lastIndex)
        val snippet = lines.subList(start, end + 1).joinToString("\n")
        return DefinitionSnippet(def.filePath, def.line, def.fileId, snippet)
    }

    private fun snippetAroundCursor(text: String, line: Int, column: Int): String {
        if (text.isBlank()) return ""
        val lines = text.split("\n")
        val center = (line - 1).coerceIn(0, lines.lastIndex)
        val start = (center - 12).coerceAtLeast(0)
        val end = (center + 12).coerceAtMost(lines.lastIndex)
        val snippet = lines.subList(start, end + 1).toMutableList()
        val lineIdx = center - start
        if (lineIdx in snippet.indices) {
            val raw = snippet[lineIdx]
            val col = (column - 1).coerceIn(0, raw.length)
            snippet[lineIdx] = raw.substring(0, col) + "<cursor>" + raw.substring(col)
        }
        return snippet.joinToString("\n")
    }
}

@RunsAt(installHandlers)
fun ai4_install() {
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

    handlers["ai-assist"] = mutableSetOf({ message, reply: (afield.Message) -> Unit ->
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
                val lang = languageNameForProperty(target, "code")
                val fileRef = fileRefFor(docName, target.id, "code")
                val vars = target.ki.context.vars.values.map { v ->
                    DefinitionInfo(
                        name = v.name,
                        type = "variable",
                        fileRef = fileRef,
                        language = lang
                    )
                }
                val funcs = target.ki.context.functions.values.map { f ->
                    DefinitionInfo(
                        name = f.toString(true),
                        type = "function",
                        fileRef = fileRef,
                        language = lang
                    )
                }
                return vars + funcs
            }

            val currentDefs = definitionsForBox(box)
            val otherDefs = thisSessionInfo.get().boxes.values
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
            val target = thisSessionInfo.get().boxes[ref.uid]
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

        GlobalScope.launch(Dispatchers.IO) {
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
                    val next = prefix + trimmedResponse.ifEmpty { "_(no response)_"}
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
