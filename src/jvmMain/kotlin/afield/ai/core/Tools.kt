package afield.ai.core
import afield.CodeUseFailureRow
import afield.CodeUseEarlySourceSymbolRow
import afield.CodeUseHotSourceSymbolRow
import afield.CodeUseLaunchRow
import afield.CodeUseSnippetRow
import afield.CodeUseSnippetUseRow
import afield.CodeUseSummary
import afield.CodeUseRelatedSourceRow
import afield.CodeUseSemanticAnalyzeResult
import afield.CodeUseSourceReferenceRow
import afield.CodeUseSourceTarget
import afield.CodeUseSymbolRow
import afield.CodeUseUnresolvedRow
import afield.ai.SemanticRetrievalHit
import afield.ai.SemanticRetrievalModelInfo
import afield.ai.SemanticRetrievalStatus
import afield.logger.lprintln
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFunctionToolCall
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    val code: String,
    val description: String = ""
)

/** Tool: list available attachment filenames (+ languages) for this request. */
@JsonClassDescription("List all attached context files available for this request.")
data class ListAttachmentsRequest(val unused: Int = 0)

data class ListAttachmentsResult(val attachments: List<AttachmentInfo>)
data class AttachmentInfo(val file: String, val language: String, val description: String = "")

/** Tool: retrieve an attached file's contents by name. */
@JsonClassDescription("Retrieve the code content of an attached file by its name. Responses may also include relatedGuides and relatedExamples paths.")
data class AttachmentRequest(val file: String)

data class AttachmentResponse(
    val file: String,
    val language: String,
    val content: String,
    val relatedGuides: List<String> = emptyList(),
    val relatedExamples: List<String> = emptyList()
)

// Kotlin-only tools ---------------------------------------------------------

/** Tool: list all definitions visible from the current Kotlin file (incl shaders). */
@JsonClassDescription("List all definitions visible from the current Kotlin file, including associated shaders.")
data class ListDefinitionsRequest(val unused: Int = 0)

data class ListDefinitionsResult(val definitions: List<DefinitionInfo>)
data class DefinitionInfo(val name: String, val type: String, val fileRef: String, val language: String)

/** Tool: get full contents of text properties for the current box/file context. */
@JsonClassDescription("Return full contents for available text properties (code and shaders) in the current Field box and graph-relevant boxes, with file refs, languages, box names, relation labels, and preamble hints. Preamble hints distinguish inherited Box properties from shader-generated Mesh/Module binding helpers.")
data class ListContentsRequest(val unused: Int = 0)

data class ListContentsResult(
    val fileRef: String? = null,
    val content: String? = null,
    val language: String? = null,
    val contents: List<ListContentEntry> = emptyList(),
    val preambleHints: List<PreamblePropertyHint> = emptyList()
)

data class PreamblePropertyHint(
    val property: String,
    val receiver: String,
    val delegate: String,
    val type: String = "",
    val boxUid: String = "",
    val boxName: String = "",
    val relation: String = "current",
    val graphRelevant: Boolean = false
)

data class ListContentEntry(
    val fileRef: String,
    val property: String,
    val language: String,
    val content: String,
    val boxUid: String = "",
    val boxName: String = "",
    val relation: String = "current"
)

@JsonClassDescription("List Kotlin REPL context variables currently retained by a Field box's KotlinInterface/KotlinInterface2 without evaluating code. Target may be blank for the current box, a box uid/name, or an afield://... ref. This lists retained script variables, not Field box properties. In later EvaluateKotlinExpression calls, refer to these variables by their names; refer to the current/target box as thisBox, never as _ or _.foo.")
data class ListKotlinContextVariablesRequest(
    val target: String = "",
    val maxValueChars: Int = 300
)

data class KotlinContextVariableInfo(
    val name: String,
    val declaration: String,
    val declaredType: String,
    val runtimeType: String,
    val mutable: Boolean,
    val valuePreview: String,
    val primitiveValue: Any? = null,
    val primitiveKind: String = ""
)

data class ListKotlinContextVariablesResult(
    val success: Boolean,
    val target: String,
    val boxUid: String,
    val boxName: String,
    val generation: Int,
    val message: String,
    val variables: List<KotlinContextVariableInfo> = emptyList()
)

@JsonClassDescription("Evaluate a Kotlin expression in a Field box's existing KotlinInterface/KotlinInterface2 context. Target may be blank for the current box, a box uid/name, or an afield://... ref. Prefer read-only expressions; this runs against live runtime objects. The current/target Field box is thisBox. Do not use _, _.foo, or bare box as current-box syntax. Use thisBox.getPropertyRaw(\"name\") / thisBox.setPropertyRaw(\"name\", value) for raw box properties, and use box(\"uid\", root = thisBox.inside) when intentionally resolving or creating another box in the same document.")
data class EvaluateKotlinExpressionRequest(
    val target: String = "",
    val expression: String,
    val renderHtml: Boolean = true,
    val maxChars: Int = 12000
)

data class EvaluateKotlinExpressionResult(
    val success: Boolean,
    val target: String,
    val boxUid: String,
    val boxName: String,
    val expression: String,
    val valueKind: String,
    val type: String = "",
    val runtimeType: String = "",
    val primitiveValue: Any? = null,
    val primitiveKind: String = "",
    val stringValue: String = "",
    val html: String = "",
    val diagnostics: List<CompileDiagnostic> = emptyList(),
    val message: String = ""
)

/** Tool: edit another file by name (your integration decides how to apply this). */
@JsonClassDescription("Edit another file by specifying its name and new content.")
data class EditFileRequest(val file: String, val newContent: String)

data class EditFileResponse(val success: Boolean, val message: String? = null)

/** Agentic verification tools --------------------------------------------- */

@JsonClassDescription("Compile Kotlin for the current Field box or an afield://.../code file ref. Call this after editing Kotlin and before finishing the turn; fix errors and rerun until success.")
data class CompileKotlinRequest(
    val file: String = "",
    val source: String? = null,
    val runSetupBlocks: Boolean = true
)

data class CompileDiagnostic(
    val severity: String,
    val message: String,
    val line: Int = -1,
    val column: Int = -1,
    val endLine: Int = -1,
    val endColumn: Int = -1,
    val code: String? = null
)

data class SetupPropertyChange(
    val property: String,
    val changed: Boolean,
    val cacheChanged: Boolean,
    val beforeChars: Int,
    val afterChars: Int
)

data class CompileKotlinResult(
    val success: Boolean,
    val file: String,
    val message: String,
    val diagnostics: List<CompileDiagnostic> = emptyList(),
    val setupChanges: List<SetupPropertyChange> = emptyList()
)

@JsonClassDescription("Reload shader modules for the current Field box or target box/file ref. Call this after editing GLSL/Slang shader properties and before finishing the turn; fix errors and rerun until success.")
data class ReloadShadersRequest(
    val target: String = "",
    val mode: String = "auto",
    val visualCheck: Boolean = true
)

data class ShaderReloadError(
    val property: String,
    val line: Int = -1,
    val message: String
)

/**
 * Caption for the post-reload screenshot.
 *
 * Phrased as what to look *for* rather than what it is, because on the [CarriesToolImages] path the
 * caption is the only text travelling with the picture, and a bare picture answers no question.
 */
internal const val POST_RELOAD_SCREENSHOT_CAPTION: String =
    "Post-shader-reload screenshot of the afield.four render window, captured after the settle delay. " +
        "Inspect it against what the user asked for, together with any earlier screenshot. If the " +
        "requested visual result is not achieved, edit and reload again before finalizing."

data class ReloadShadersResult(
    val success: Boolean,
    val target: String,
    val mode: String,
    val message: String,
    val reloaded: List<String> = emptyList(),
    val errors: List<ShaderReloadError> = emptyList(),
    val visualCheckAttached: Boolean = false,
    val visualCheckMessage: String = "",
    @get:JsonIgnore
    val visualDataUrl: String = ""
) : CarriesToolImages {

    /**
     * Derived from [visualDataUrl] rather than passed separately, so "was a picture attached" has one
     * source of truth.
     *
     * Implementing the interface is what makes this screenshot reach an Anthropic model at all. Before
     * it, [AnthropicBridge] handled this type by appending a line of prose saying a screenshot had been
     * captured and then dropping the screenshot: `toolResultBlock` only ever reads [CarriesToolImages],
     * and [visualDataUrl] is `@JsonIgnore`d out of the result JSON on purpose. So the model was told a
     * picture existed and shown nothing — the same failure the interface was introduced to end, left in
     * place here because this type predated it and both bridges special-cased it by name.
     */
    @get:JsonIgnore
    override val toolImages: List<ToolImage>
        get() =
            if (visualDataUrl.isBlank()) emptyList()
            else listOf(ToolImage(visualDataUrl, POST_RELOAD_SCREENSHOT_CAPTION))
}

/** Looking at the render window ---------------------------------------------- */

@JsonClassDescription(
    "Capture the live afield.four render window and attach it as an image, so you can see what the " +
        "running code is actually drawing. Use it for any visual request: once before editing to see " +
        "the starting point, and again afterwards to check the result. Unlike ReloadShaders' " +
        "visualCheck this needs no shader edit and no successful reload, so it also works when a " +
        "reload just failed — which is when looking is most useful."
)
data class CaptureRenderWindowRequest(
    @JsonPropertyDescription(
        "Which window, when more than one is visible: a 1-based index, or a substring of the window " +
            "title. Empty picks the first capturable window visible from the target box."
    )
    val target: String = "",
    @JsonPropertyDescription(
        "Box uid or afield:// ref to look for windows from. Empty uses the box this chat belongs to."
    )
    val uid: String = "",
    @JsonPropertyDescription(
        "Milliseconds to wait before capturing, to let an animation or a recompile settle. " +
            "Clamped to 0..5000."
    )
    val settleMs: Int = 0,
    @JsonPropertyDescription(
        "What you are looking for in this capture. Travels with the image as its caption."
    )
    val note: String = ""
)

/** Upper bound on [CaptureRenderWindowRequest.settleMs], so a bad number cannot stall a turn. */
internal const val CAPTURE_RENDER_WINDOW_MAX_SETTLE_MS: Int = 5_000

data class CaptureRenderWindowResult(
    val success: Boolean,
    val message: String,
    val title: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val settledMs: Int = 0,
    @get:JsonIgnore
    val dataUrl: String = ""
) : CarriesToolImages, SummarisesForToolLog {

    @get:JsonIgnore
    override val toolImages: List<ToolImage>
        get() =
            if (dataUrl.isBlank()) emptyList()
            else listOf(
                ToolImage(
                    dataUrl,
                    "afield.four render window${if (title.isNotBlank()) " '$title'" else ""}, " +
                        "captured live. Describe what you actually see, then say whether it matches " +
                        "what was asked for."
                )
            )

    /**
     * One line for the tool I/O log, and specifically including `images=`.
     *
     * The bug this tool exists to fix was invisible in the logs for exactly this reason: the old path
     * logged `visual=false` and nothing else, so "refused to capture" and "captured and dropped it"
     * read identically. Whether a picture was produced belongs in the log line.
     */
    override fun toolLogSummary(): String =
        "success=$success images=${toolImages.size} ${width}x$height settledMs=$settledMs " +
            "title=${title.take(60)} message=${message.take(160)}"
}

/** Macro transcript tools --------------------------------------------------- */

@JsonClassDescription("List saved operation-transcript macros available to the current Field document. Use this before running a macro when the user names one imprecisely or asks what macros exist.")
data class MacroListRequest(val unused: Int = 0)

data class MacroListResult(
    val success: Boolean,
    val message: String,
    val macros: List<String> = emptyList()
)

@JsonClassDescription("Read a saved operation-transcript macro YAML file by name. Read before editing a macro, and usually read before running when target mapping or expected effects matter.")
data class MacroReadRequest(val name: String)

data class MacroReadResult(
    val success: Boolean,
    val message: String,
    val name: String,
    val content: String = "",
    val operationCount: Int = 0
)

@JsonClassDescription("Create or replace a saved operation-transcript macro YAML file in the current Field document. Content should be YAML with an operations list. Use this to save a user-requested new/edited macro; do not rewrite an existing macro just to run it.")
data class MacroWriteRequest(
    val name: String,
    val content: String
)

data class MacroWriteResult(
    val success: Boolean,
    val message: String,
    val name: String,
    val operationCount: Int = 0
)

@JsonClassDescription("Run or undo a saved operation-transcript macro. Use direction=do to replay it and direction=undo to run it backwards. targetBoxes may contain box uids or names; when set, the macro's first recorded source box is remapped to each target box, which is the normal way to do a recorded macro to the current/selected box. Empty targetBoxes replays the recorded uids literally. Inspect applied/skipped/errors; if applied=0 or errors are returned, do not repeat the identical request. If a normal run reports operation errors in chat, Field will attempt an automatic undo and return the undo result; inspect that result and ask before retrying.")
data class MacroRunRequest(
    val name: String,
    val direction: String = "do",
    val targetBoxes: List<String> = emptyList(),
    val strict: Boolean = false
)

data class MacroRunResult(
    val success: Boolean,
    val message: String,
    val name: String,
    val direction: String,
    val targetBoxes: List<String> = emptyList(),
    val applied: Int = 0,
    val skipped: Int = 0,
    val errors: List<String> = emptyList()
)

/** Code-use telemetry tools ------------------------------------------------- */

@JsonClassDescription("Query Field's local code-use telemetry database. kind may be summary, failures, snippets, launches, symbols, unresolved, line-callers, references-for-code, related-source-for-code, semantic-analyze, hot-symbols, or early-document-symbols. Prefer kind=failures when looking for compiler errors/runtime exceptions that reveal API gaps, missing docs, or growth points. Optional document and box filters are case-insensitive substrings for row types that carry that metadata.")
data class CodeUseQueryRequest(
    val kind: String = "failures",
    val limit: Int = 20,
    val document: String = "",
    val box: String = "",
    val sourcePath: String = "",
    val line: Int = 0,
    val code: String = "",
    val sourceName: String = "AgentSnippet.kt",
    val window: String = "all",
    val earlyWindowMs: Long = 600000,
    val callersPerSymbol: Int = 5,
    val sourceRadiusLines: Int = 24
)

data class CodeUseQueryResult(
    val success: Boolean,
    val kind: String,
    val message: String,
    val summary: CodeUseSummary? = null,
    val target: CodeUseSourceTarget? = null,
    val failures: List<CodeUseFailureRow> = emptyList(),
    val snippets: List<CodeUseSnippetRow> = emptyList(),
    val launches: List<CodeUseLaunchRow> = emptyList(),
    val symbols: List<CodeUseSymbolRow> = emptyList(),
    val unresolved: List<CodeUseUnresolvedRow> = emptyList(),
    val callers: List<CodeUseSnippetUseRow> = emptyList(),
    val references: List<CodeUseSourceReferenceRow> = emptyList(),
    val hotSymbols: List<CodeUseHotSourceSymbolRow> = emptyList(),
    val earlySymbols: List<CodeUseEarlySourceSymbolRow> = emptyList(),
    val relatedSources: List<CodeUseRelatedSourceRow> = emptyList(),
    val semanticAnalysis: CodeUseSemanticAnalyzeResult? = null
)

@JsonClassDescription("Given a source path and line inside src/, show recent Field box code snippets that appear to call the enclosing source symbol, with timestamps, compile/eval status, return types, and launch lifecycles. Confidence values are literal: k2-resolved is semantic; source-index-* values are static source-index evidence; textual values are fallback evidence and must not be described as resolved.")
data class CodeUseCallersForSourceLineRequest(
    val sourcePath: String,
    val line: Int,
    val limit: Int = 20
)

@JsonClassDescription("Given Kotlin code, rank the most interesting files/properties/methods inside src/ that the code appears to refer to. This is useful for grounding a Field box or inline chat request in Field source.")
data class CodeUseReferencesForCodeRequest(
    val code: String,
    val limit: Int = 5
)

@JsonClassDescription("List hot Field source symbols by observed Field box usage. window may be all, week, day, or month. Report each row's confidence literally: k2-resolved is semantic; source-index-* values are static source-index evidence; textual values are fallback evidence. Do not call source-index or textual rows resolved.")
data class CodeUseHotSymbolsRequest(
    val window: String = "all",
    val limit: Int = 20
)

@JsonClassDescription("Find source symbols used early in a document's observed code-use timeline and then rarely afterward. Use this to spot initialization/open-time helpers. document is an optional case-insensitive substring.")
data class CodeUseEarlyDocumentSymbolsRequest(
    val document: String = "",
    val earlyWindowMs: Long = 600000,
    val limit: Int = 20
)

@JsonClassDescription("Given Field box code, return the most relevant src/ routines with bounded source excerpts, observed call-site frequencies, return/lifecycle summaries, and other Field snippets that called the same routines. Prefer this when interrogating how box code maps onto Field source.")
data class CodeUseRelatedSourceForCodeRequest(
    val code: String,
    val limit: Int = 5,
    val callersPerSymbol: Int = 5,
    val sourceRadiusLines: Int = 24
)

@JsonClassDescription("Given Field box or inline Kotlin code, ask Field's optional semantic K2 sidecar to resolve the code against src/ and enrich the result with local code-use graph evidence: source excerpts, call-site frequencies, timestamps, and peer snippets. Falls back to the local source-index graph if the sidecar is not configured or unavailable.")
data class CodeUseSemanticAnalyzeCodeRequest(
    val code: String,
    val sourceName: String = "AgentSnippet.kt",
    val limit: Int = 5,
    val callersPerSymbol: Int = 5,
    val sourceRadiusLines: Int = 24
)

/** Semantic retrieval tools ------------------------------------------------ */

@JsonClassDescription("Search Field's native Lucene semantic retrieval index across Field internals, current workspace code, and documentation. This is natural-language to code/docs retrieval, not compiler resolution. Set corpus for one corpus, or corpora for combinations. Available corpora are field-internals, workspace, and documentation. Leave both blank to search all corpora. Use documentName with corpus/workspace to search a specific current workspace document. Results include BM25/symbol/vector ranks when available; vector search is active only when the local ONNX embedder is configured.")
data class SemanticRetrievalSearchRequest(
    val query: String,
    val limit: Int = 10,
    val corpus: String = "",
    val corpora: List<String> = emptyList(),
    val pathPrefix: String = "",
    val kinds: List<String> = emptyList(),
    val documentName: String = "",
    val boxId: String = "",
    val property: String = "",
    val includeCapsule: Boolean = false,
    val includeSource: Boolean = false
)

@JsonClassDescription("Return status for Field's native Lucene semantic retrieval index, including capsule backend, ONNX/vector availability, and available corpora for SemanticRetrievalSearchRequest.")
data class SemanticRetrievalStatusRequest(val unused: Int = 0)

@JsonClassDescription("Rebuild Field's native Lucene semantic retrieval index from current source-index declarations and documentation sections. Use sparingly; normal searches rebuild automatically if the index is missing.")
data class SemanticRetrievalRebuildRequest(val unused: Int = 0)

data class SemanticRetrievalToolResult(
    val success: Boolean,
    val message: String,
    val status: SemanticRetrievalStatus,
    val model: SemanticRetrievalModelInfo? = null,
    val availableCorpora: List<String> = emptyList(),
    val results: List<SemanticRetrievalHit> = emptyList()
)

/** Long-running task tools -------------------------------------------------- */

@JsonClassDescription("Read the persistent scratchpad for this chat. Use it to recover durable notes, decisions, constraints, and handoff context from earlier turns.")
data class ScratchpadReadRequest(val unused: Int = 0)

@JsonClassDescription("Update the persistent scratchpad for this chat. Use append to add durable notes, replace to rewrite the whole scratchpad, or clear to erase it.")
data class ScratchpadUpdateRequest(
    val operation: String = "append",
    val text: String = ""
)

data class ScratchpadResult(
    val success: Boolean,
    val message: String,
    val text: String = "",
    val charCount: Int = 0
)

@JsonClassDescription("Read the persistent checklist for this chat. Use it to track long-running tasks across turns.")
data class ChecklistReadRequest(val unused: Int = 0)

@JsonClassDescription("Update the persistent checklist for this chat. Use operation add, update, complete, reopen, remove, replace, or clear. For update/complete/reopen/remove, itemId identifies the item.")
data class ChecklistUpdateRequest(
    val operation: String,
    val itemId: String = "",
    val text: String = "",
    val status: String = "pending",
    val items: List<ChecklistItemInput> = emptyList()
)

data class ChecklistItemInput(
    val text: String,
    val status: String = "pending",
    val id: String = ""
)

data class ChecklistItem(
    val id: String,
    val text: String,
    val status: String = "pending"
)

data class ChecklistResult(
    val success: Boolean,
    val message: String,
    val items: List<ChecklistItem> = emptyList()
)

class AgentMemoryState {
    private val lock = ReentrantReadWriteLock()
    private var scratchpad: String = ""
    private val checklist = mutableListOf<ChecklistItem>()
    private var nextChecklistId = 1

    fun readScratchpad(): ScratchpadResult = lock.read {
        ScratchpadResult(
            success = true,
            message = if (scratchpad.isBlank()) "Scratchpad is empty." else "Scratchpad loaded.",
            text = scratchpad,
            charCount = scratchpad.length
        )
    }

    fun updateScratchpad(request: ScratchpadUpdateRequest): ScratchpadResult = lock.write {
        when (request.operation.trim().lowercase()) {
            "append" -> {
                val addition = request.text.trim()
                if (addition.isBlank()) {
                    return@write ScratchpadResult(false, "Append requires non-empty text.", scratchpad, scratchpad.length)
                }
                scratchpad = buildString {
                    append(scratchpad.trimEnd())
                    if (isNotEmpty()) append("\n\n")
                    append(addition)
                }
                ScratchpadResult(true, "Scratchpad appended.", scratchpad, scratchpad.length)
            }
            "replace", "set" -> {
                scratchpad = request.text.trim()
                ScratchpadResult(true, "Scratchpad replaced.", scratchpad, scratchpad.length)
            }
            "clear" -> {
                scratchpad = ""
                ScratchpadResult(true, "Scratchpad cleared.", scratchpad, 0)
            }
            else -> ScratchpadResult(
                false,
                "Unknown scratchpad operation '${request.operation}'. Use append, replace, or clear.",
                scratchpad,
                scratchpad.length
            )
        }
    }

    fun readChecklist(): ChecklistResult = lock.read {
        ChecklistResult(
            success = true,
            message = if (checklist.isEmpty()) "Checklist is empty." else "Checklist loaded.",
            items = checklist.toList()
        )
    }

    fun updateChecklist(request: ChecklistUpdateRequest): ChecklistResult = lock.write {
        fun normalizedStatus(raw: String): String = when (raw.trim().lowercase()) {
            "done", "complete", "completed", "checked" -> "done"
            "in_progress", "in-progress", "active", "doing" -> "in_progress"
            else -> "pending"
        }

        fun findIndex(id: String): Int {
            val trimmed = id.trim()
            if (trimmed.isBlank()) return -1
            checklist.indexOfFirst { it.id == trimmed }.takeIf { it >= 0 }?.let { return it }
            val numeric = trimmed.toIntOrNull()
            if (numeric != null && numeric in 1..checklist.size) return numeric - 1
            return -1
        }

        fun nextId(): String = (nextChecklistId++).toString()

        val op = request.operation.trim().lowercase()
        val result = when (op) {
            "add" -> {
                val text = request.text.trim()
                if (text.isBlank()) {
                    ChecklistResult(false, "Add requires non-empty text.", checklist.toList())
                } else {
                    checklist += ChecklistItem(nextId(), text, normalizedStatus(request.status))
                    ChecklistResult(true, "Checklist item added.", checklist.toList())
                }
            }
            "update", "set" -> {
                val idx = findIndex(request.itemId)
                val text = request.text.trim()
                if (idx < 0) {
                    ChecklistResult(false, "Unknown checklist item '${request.itemId}'.", checklist.toList())
                } else if (text.isBlank()) {
                    ChecklistResult(false, "Update requires non-empty text.", checklist.toList())
                } else {
                    val old = checklist[idx]
                    val status = if (request.status.isBlank()) old.status else normalizedStatus(request.status)
                    checklist[idx] = old.copy(text = text, status = status)
                    ChecklistResult(true, "Checklist item updated.", checklist.toList())
                }
            }
            "complete", "done" -> {
                val idx = findIndex(request.itemId)
                if (idx < 0) {
                    ChecklistResult(false, "Unknown checklist item '${request.itemId}'.", checklist.toList())
                } else {
                    checklist[idx] = checklist[idx].copy(status = "done")
                    ChecklistResult(true, "Checklist item completed.", checklist.toList())
                }
            }
            "reopen", "pending" -> {
                val idx = findIndex(request.itemId)
                if (idx < 0) {
                    ChecklistResult(false, "Unknown checklist item '${request.itemId}'.", checklist.toList())
                } else {
                    checklist[idx] = checklist[idx].copy(status = "pending")
                    ChecklistResult(true, "Checklist item reopened.", checklist.toList())
                }
            }
            "remove", "delete" -> {
                val idx = findIndex(request.itemId)
                if (idx < 0) {
                    ChecklistResult(false, "Unknown checklist item '${request.itemId}'.", checklist.toList())
                } else {
                    checklist.removeAt(idx)
                    ChecklistResult(true, "Checklist item removed.", checklist.toList())
                }
            }
            "replace" -> {
                checklist.clear()
                request.items.forEach { item ->
                    val text = item.text.trim()
                    if (text.isNotBlank()) {
                        val id = item.id.trim().ifBlank { nextId() }
                        checklist += ChecklistItem(id, text, normalizedStatus(item.status))
                    }
                }
                ChecklistResult(true, "Checklist replaced.", checklist.toList())
            }
            "clear" -> {
                checklist.clear()
                ChecklistResult(true, "Checklist cleared.", emptyList())
            }
            else -> ChecklistResult(
                false,
                "Unknown checklist operation '${request.operation}'. Use add, update, complete, reopen, remove, replace, or clear.",
                checklist.toList()
            )
        }
        result
    }

    fun checklistMarkdown(): String = lock.read {
        if (checklist.isEmpty()) return@read ""
        buildString {
            appendLine("**Checklist**")
            checklist.forEach { item ->
                append(if (item.status == "done") "- [x] " else "- [ ] ")
                append(item.text)
                append(" ")
                append("`#${item.id}`")
                appendLine()
            }
        }.trimEnd()
    }
}

// ---- Tool results that carry pictures -------------------------------------

/**
 * One image for the model to look at, as a `data:image/…;base64,…` URL.
 *
 * [caption] is what to look *for*: a bare picture in a conversation is much less useful than one that
 * says what question it answers.
 */
data class ToolImage(
    val dataUrl: String,
    val caption: String = "",
)

/**
 * A tool result that has something for the model to see, not just to read.
 *
 * # Why this exists
 *
 * Field's agent loop has been multimodal in one direction for a while — a person can attach an image,
 * and `ReloadShaders` can attach a GPU window screenshot — but a *tool* had no general way to answer in
 * pictures. That mattered as soon as tools started producing geometry: the only honest way for a model
 * to check that a house it just edited looks right is to look at it.
 *
 * Implement this on a result class and the bridge appends the images to the conversation as a user-role
 * message after the function-call output, with [ToolImage.caption] as the text.
 *
 * # Which surfaces honour this
 *
 * Both agent loops, by different routes that the two APIs force:
 *
 *  - [OpenAiIdeCodexBridge] appends a following user-role message, because the Responses API has no
 *    way to nest an image inside a function output.
 *  - [AnthropicBridge] puts them *inside* the `tool_result`, which the Messages API does allow and
 *    which is the better shape — the picture is bound to the call it came from, so a turn with three
 *    tool calls cannot leave the model guessing which image answers which. This was missing until
 *    2026-08-11: that bridge sent every result as a bare string, so a tool's pictures vanished with
 *    no error on the provider this project is built around. See `toolResultBlock` and
 *    `AnthropicToolImageTest`.
 *
 * **Not** the MCP backdoor. `agent-ai-tool-call` replies with `aiToolMapper` JSON, and the
 * `@get:JsonIgnore` below quite correctly keeps the images out of it, so an agent on the backdoor
 * gets the numbers and not the picture. That is right for a JSON transport, but it does mean a tool
 * whose answer is *only* an image has nothing to say there. Design around it: put anything the model
 * must not miss in the result as well as in the picture.
 *
 * # The one thing to get right
 *
 * [toolImages] is annotated `@get:JsonIgnore`, and any implementation must keep it that way — which is
 * why this is an interface with the annotation on it rather than a convention. A tool result is
 * serialised to JSON and sent to the model as the function-call output; a base64 PNG that reaches *that*
 * path is spent as text tokens, invisibly, and buys nothing, because the model cannot see an image
 * encoded in a JSON string. The image has to travel as an image.
 */
interface CarriesToolImages {

    /** The pictures, excluded from this result's JSON on purpose — see the interface docs. */
    @get:JsonIgnore
    val toolImages: List<ToolImage>
}

/** Example tool (optional) ------------------------------------------------- */
@JsonClassDescription("Looks up symbols in the IDE workspace index. Results may include relatedGuides and relatedExamples paths.")
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
    val definitionBody: String? = null,
    val relatedGuides: List<String> = emptyList(),
    val relatedExamples: List<String> = emptyList()
)

/** CLI example tools -------------------------------------------------------- */

@JsonClassDescription("Return the current UTC time in the requested format.")
data class GetUtcTime(val format: String = "yyyy-MM-dd HH:mm:ss")

@JsonClassDescription("Store a short note under a key.")
data class Remember(val key: String, val value: String)

@JsonClassDescription("Recall a stored note by key.")
data class Recall(val key: String)

data class ToolExecutionContext(
    val attachments: List<ContextAttachment> = emptyList(),
    val resolveAttachment: ((String) -> AttachmentResponse?)? = null,

    // Kotlin-only providers (optional)
    val listDefinitions: (() -> ListDefinitionsResult)? = null,
    val listFileContents: (() -> ListContentsResult)? = null,
    val listKotlinContextVariables: ((ListKotlinContextVariablesRequest) -> ListKotlinContextVariablesResult)? = null,
    val evaluateKotlinExpression: ((EvaluateKotlinExpressionRequest) -> EvaluateKotlinExpressionResult)? = null,
    val editOtherFile: ((EditFileRequest) -> EditFileResponse)? = null,
    val compileKotlin: ((CompileKotlinRequest) -> CompileKotlinResult)? = null,
    val reloadShaders: ((ReloadShadersRequest) -> ReloadShadersResult)? = null,
    val captureRenderWindow: ((CaptureRenderWindowRequest) -> CaptureRenderWindowResult)? = null,
    val listMacros: ((MacroListRequest) -> MacroListResult)? = null,
    val readMacro: ((MacroReadRequest) -> MacroReadResult)? = null,
    val writeMacro: ((MacroWriteRequest) -> MacroWriteResult)? = null,
    val runMacro: ((MacroRunRequest) -> MacroRunResult)? = null,
    val codeUseQuery: ((CodeUseQueryRequest) -> CodeUseQueryResult)? = null,
    val semanticRetrievalSearch: ((SemanticRetrievalSearchRequest) -> SemanticRetrievalToolResult)? = null,
    val semanticRetrievalStatus: (() -> SemanticRetrievalToolResult)? = null,
    val semanticRetrievalRebuild: (() -> SemanticRetrievalToolResult)? = null,

    val agentMemory: AgentMemoryState? = null,

    // Optional extra tools
    val workspaceSymbolSearch: ((WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult)? = null
)

fun virtualFilesToAttachments(files: List<afield.ai.VirtualTextFile>): List<ContextAttachment> =
    files.map { ContextAttachment(it.ref.fileName, it.ref.language, it.content) }

internal class ToolRegistry {
    private data class Handler<T : Any>(
        val cls: Class<T>,
        val available: (ToolExecutionContext) -> Boolean,
        val fn: (ToolExecutionContext, T) -> Any
    )

    private val handlers = ConcurrentHashMap<String, Handler<*>>()

    /**
     * The subset of [handlers] that arrived through [AgentToolExtensions] rather than from core.
     *
     * Two things depend on telling the difference. An extension may replace or retire *its own* tool at
     * any time — that is what makes a live-coded tool set possible — and it may never replace one of
     * core's, because the agent's instructions are written around core's tools behaving as documented.
     */
    private val extensionOwned = ConcurrentHashMap.newKeySet<String>()

    /** Serialises the mutators; reads stay lock-free on the concurrent collections. */
    private val writeLock = Any()

    fun <T : Any> register(
        toolClass: Class<T>,
        available: (ToolExecutionContext) -> Boolean = { true },
        handler: (ToolExecutionContext, T) -> Any
    ) {
        synchronized(writeLock) {
            handlers[toolClass.simpleName] = Handler(toolClass, available, handler)
            // Core is registering this name directly, so core owns it from here on — even if an
            // extension got there first, which it does: applyTo runs at the top of
            // registerBuiltInAgentTools, before any of core's own register calls.
            //
            // And that ordering is exactly why the displacement has to be said out loud. This is the one
            // path by which the failure [registerExtension] is written to prevent still happens: an
            // extension registered before any chat existed is applied to a fresh registry — which
            // accepts it, because the registry is still empty — and is then overwritten here, a few
            // calls later, by the built-in of the same name. The author ends up with a tool registered
            // on their side that no model will ever see. AgentToolExtensions.register cannot catch this
            // one either: it looks for the clash in the registries bound so far, and at startup there
            // are none. Nothing anywhere reported it until this line.
            if (extensionOwned.remove(toolClass.simpleName)) {
                lprintln(
                    "warning,ai4",
                    "core tool '${toolClass.simpleName}' has displaced an extension tool of the same " +
                        "name, so no model will see the extension's. Rename the extension's request " +
                        "class — its simple name is the tool name the model sees, and core's names " +
                        "are reserved."
                )
            }
        }
    }

    /**
     * Register a tool on behalf of [AgentToolExtensions], refusing a name core already holds.
     *
     * @return `false` if a non-extension handler is already registered under this simple name, in which
     *   case nothing changed. The caller turns that into an error the extension author can read;
     *   swallowing it would leave them with a tool that is registered on their side and invisible to
     *   every model — the exact failure this seam was hardened against on 2026-08-11.
     */
    internal fun <T : Any> registerExtension(
        toolClass: Class<T>,
        available: (ToolExecutionContext) -> Boolean,
        handler: (ToolExecutionContext, T) -> Any
    ): Boolean = synchronized(writeLock) {
        val name = toolClass.simpleName
        if (handlers.containsKey(name) && !extensionOwned.contains(name)) return@synchronized false
        handlers[name] = Handler(toolClass, available, handler)
        extensionOwned.add(name)
        true
    }

    /** True if [registerExtension] would accept [name] — i.e. core does not already hold it. */
    internal fun acceptsExtensionNamed(name: String): Boolean =
        !handlers.containsKey(name) || extensionOwned.contains(name)

    /** Retire an extension's tool. A core tool of the same name is left alone. */
    internal fun unregisterExtension(name: String) {
        synchronized(writeLock) {
            if (extensionOwned.remove(name)) handlers.remove(name)
        }
    }

    fun addAllTo(builder: ResponseCreateParams.Builder, ctx: ToolExecutionContext) {
        handlers.values.forEach { h ->
            if (h.available(ctx)) {
                @Suppress("UNCHECKED_CAST")
                builder.addTool(h.cls as Class<Any>)
            }
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

    // --- Provider-neutral face (used by the MCP backdoor bridge) -----------
    // The OpenAI path above binds tools to ResponseCreateParams / ResponseFunctionToolCall.
    // These two methods expose the exact same registry + gates + handlers to any
    // transport that speaks plain JSON — currently the agent backdoor's
    // agent-ai-tools-list / agent-ai-tool-call handlers, surfaced over MCP.

    /** Tools available under [ctx], as MCP-style descriptors {name, description, inputSchema}. */
    fun describe(ctx: ToolExecutionContext): JsonArray = buildJsonArray {
        handlers.values
            .filter { it.available(ctx) }
            .sortedBy { it.cls.simpleName }
            .forEach { h ->
                add(buildJsonObject {
                    put("name", h.cls.simpleName)
                    val desc = h.cls.kotlin.findAnnotation<JsonClassDescription>()?.value
                    if (!desc.isNullOrBlank()) put("description", desc)
                    put("inputSchema", ToolSchema.forClass(h.cls))
                })
            }
    }

    /** True if [name] is registered and its gate passes under [ctx]. */
    fun isAvailable(ctx: ToolExecutionContext, name: String): Boolean =
        handlers[name]?.available(ctx) == true

    /**
     * Deserialize [argsJson] into the tool's request class (Jackson, matching the
     * OpenAI path's `call.arguments(cls)`) and invoke the handler. The result is a
     * plain Jackson data class; callers serialize it with [aiToolMapper].
     */
    fun executeByName(ctx: ToolExecutionContext, name: String, argsJson: String): Any {
        val h = handlers[name]
            ?: throw IllegalArgumentException("No tool registered for: $name")
        if (!h.available(ctx)) {
            throw IllegalStateException("Tool '$name' is not available in this context (its provider is not wired).")
        }
        @Suppress("UNCHECKED_CAST")
        val handler = h as Handler<Any>
        val args: Any = aiToolMapper.readValue(argsJson.ifBlank { "{}" }, handler.cls)
        return handler.fn(ctx, args)
    }
}

/** Shared Jackson mapper for the provider-neutral tool face (Kotlin data classes). */
internal val aiToolMapper: ObjectMapper = ObjectMapper().registerKotlinModule()

/**
 * Minimal Kotlin-reflection JSON-Schema emitter for the tool request classes.
 * Self-contained (no OpenAI-SDK-internal coupling) so the MCP schema cannot drift
 * on an SDK upgrade. Reads @JsonClassDescription/@JsonPropertyDescription and skips
 * @JsonIgnore and placeholder `unused` fields.
 */
internal object ToolSchema {
    fun forClass(cls: Class<*>): JsonObject {
        val ctor = cls.kotlin.primaryConstructor
        val required = mutableListOf<String>()
        val properties = buildJsonObject {
            ctor?.parameters?.forEach { param ->
                val pname = param.name ?: return@forEach
                if (pname == "unused") return@forEach
                if (param.findAnnotation<JsonIgnore>() != null) return@forEach
                put(pname, propertySchema(param.type, param.findAnnotation<JsonPropertyDescription>()?.value))
                if (!param.isOptional && !param.type.isMarkedNullable) required += pname
            }
        }
        return buildJsonObject {
            put("type", "object")
            put("properties", properties)
            if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
        }
    }

    private fun propertySchema(type: KType, description: String?): JsonObject {
        val base = schemaForType(type)
        if (description.isNullOrBlank()) return base
        return buildJsonObject {
            base.forEach { (k, v) -> put(k, v) }
            put("description", description)
        }
    }

    private fun schemaForType(type: KType): JsonObject {
        val cls = type.jvmErasure
        return when {
            cls == String::class || cls == Char::class -> typeObject("string")
            cls == Boolean::class -> typeObject("boolean")
            cls == Int::class || cls == Long::class || cls == Short::class || cls == Byte::class ->
                typeObject("integer")
            cls == Double::class || cls == Float::class -> typeObject("number")
            List::class.java.isAssignableFrom(cls.java) || Set::class.java.isAssignableFrom(cls.java) -> {
                val item = type.arguments.firstOrNull()?.type
                buildJsonObject {
                    put("type", "array")
                    put("items", item?.let { schemaForType(it) } ?: typeObject("string"))
                }
            }
            Map::class.java.isAssignableFrom(cls.java) -> {
                val value = type.arguments.getOrNull(1)?.type
                buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", value?.let { schemaForType(it) } ?: typeObject("string"))
                }
            }
            cls.java.isEnum -> buildJsonObject {
                put("type", "string")
                put("enum", JsonArray(cls.java.enumConstants.map { JsonPrimitive(it.toString()) }))
            }
            else -> typeObject("object")
        }
    }

    private fun typeObject(t: String): JsonObject = buildJsonObject { put("type", t) }
}

fun validateNoMarkers(code: String) {
    val bad = listOf("<help>", "<error>", "</error>", "<cursor>", "<rewrite>", "</rewrite>")
    val hit = bad.firstOrNull { code.contains(it) }
    if (hit != null) throw MalformedAI("Model output still contained marker '$hit'. Refusing to apply.")
}
