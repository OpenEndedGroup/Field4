package afield.ai.tools

import afield.ai.conversation.AiSessionTree
import afield.ai.conversation.SuspendsTurn
import afield.ai.diagnostics.log
import afield.ai.models.AnthropicBridge
import afield.ai.models.OpenAiIdeCodexBridge
import afield.ai.models.primitiveKind
import afield.ai.models.setupChanges
import afield.ai.models.toolResultBlock

import afield.ai.conversation.AiTurnInbox
import afield.ai.conversation.aiToolFailureEndsTurn
import afield.ai.conversation.aiToolFailureMessage
import afield.ai.models.LlmProgressUpdate
import afield.runtime.languages.MalformedAI
import afield.app.diagnostics.CodeUseFailureRow
import afield.app.diagnostics.CodeUseEarlySourceSymbolRow
import afield.app.diagnostics.CodeUseHotSourceSymbolRow
import afield.app.diagnostics.CodeUseLaunchRow
import afield.app.diagnostics.CodeUseSnippetRow
import afield.app.diagnostics.CodeUseSnippetUseRow
import afield.app.diagnostics.CodeUseSummary
import afield.app.diagnostics.CodeUseRelatedSourceRow
import afield.app.diagnostics.CodeUseSemanticAnalyzeResult
import afield.app.diagnostics.CodeUseSourceReferenceRow
import afield.app.diagnostics.CodeUseSourceTarget
import afield.app.diagnostics.CodeUseSymbolRow
import afield.app.diagnostics.CodeUseUnresolvedRow
import afield.ai.retrieval.SemanticRetrievalHit
import afield.ai.retrieval.SemanticRetrievalModelInfo
import afield.ai.retrieval.SemanticRetrievalStatus
import afield.app.diagnostics.logging.lprintln
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openai.core.JsonValue
import com.openai.models.responses.FunctionTool
import com.fasterxml.jackson.databind.node.ObjectNode
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The request and result classes of the tools Field's AI agent can call, and the registry that
 * dispatches a call by name to its handler.
 *
 * Each tool is a Kotlin data class: its simple name is the tool name, `@JsonClassDescription` and
 * `@JsonPropertyDescription` are what the model reads, and [AgentToolMeta] records the tool's group
 * and effects. The file covers attachments and definitions, Kotlin compile and evaluate, shader
 * reload, render-window capture, macros, code-use queries, and the agent's scratchpad and checklist
 * ([AgentMemoryState]). A result that carries pictures implements [CarriesToolImages], so the images
 * travel as images rather than as base64 text inside the JSON output.
 *
 * `ToolSchema` emits the JSON Schema for a request class from Kotlin reflection alone, skipping
 * `@JsonIgnore` and placeholder `unused` fields; `openAiTool` builds the OpenAI function tool and
 * keeps nullable arguments optional. `AgentToolSchemaTest` checks that OpenAI accepts the argument
 * schema of every registered tool.
 */

/**
 * Commentary tool: model uses this for non-code notes/questions/alternatives.
 * You collect these separately and keep code output clean.
 */
@JsonClassDescription("Assistant commentary or design notes about the code.")
@AgentToolMeta("core", effects = [])
data class Commentary(val text: String)

/**
 * Represents a file/language/code triple that can be attached to a request.
 * Attachments are retrieved via tools rather than stuffed into the prompt body.
 */
data class ContextAttachment(
    /** The name the agent lists and reads the attachment by. */
    val fileName: String,
    /** Its language, such as `kotlin` or `glsl`. */
    val language: String,
    /** The attached text itself. */
    val code: String,
    /** An optional line saying what the attachment is, shown when the agent lists attachments. */
    val description: String = ""
)

/** Tool: list available attachment filenames (+ languages) for this request. */
@JsonClassDescription("List all attached context files available for this request.")
@AgentToolMeta("context", effects = [ToolEffect.READ_DOCUMENT])
data class ListAttachmentsRequest(val unused: Int = 0)

data class ListAttachmentsResult(val attachments: List<AttachmentInfo>)
data class AttachmentInfo(val file: String, val language: String, val description: String = "")

/** Tool: read a bounded excerpt of an attachment or resolvable source. */
@JsonClassDescription("Read an attachment or resolvable file/ref in bounded excerpts (default 6000 UTF-16 code units, maximum 65536). The response identifies the whole source hash and totalChars, marks isExcerpt and gives nextOffset when more remains. Continue with nextOffset and expectedSourceHash to refuse mixed versions. An excerpt is not the entire file: never replace a file with just its excerpt. Use find for a case-sensitive literal search starting at offset: return a bounded excerpt around the first match instead of reading unrelated source. searchMatched=false means no match, not an empty file. Related guides/examples are preserved.")
@AgentToolMeta("context", effects = [ToolEffect.READ_DOCUMENT, ToolEffect.FILESYSTEM_READ])
data class AttachmentRequest(
    /** The attachment name, or a resolvable file or `afield://` ref. */
    val file: String,
    /** Where the excerpt starts, in UTF-16 code units; pass the previous response's `nextOffset` to continue. */
    val offset: Int = 0,
    /** How much to return: 6000 by default, at most 65536. */
    val maxChars: Int = 6000,
    /** The `sourceHash` of an earlier excerpt; when set, a source that has changed since is refused rather than mixed. */
    val expectedSourceHash: String = "",
    /** A case-sensitive literal to search for from [offset]; the excerpt is taken around the first match. */
    val find: String = ""
)

data class AttachmentResponse(
    /** The file that was read. */
    val file: String,
    /** Its language. */
    val language: String,
    /** The excerpt: the whole file only when [isExcerpt] is false. */
    val content: String,
    /** Guides that discuss this source. */
    val relatedGuides: List<String> = emptyList(),
    /** Examples that use this source. */
    val relatedExamples: List<String> = emptyList(),
    /** Where [content] starts in the whole source. */
    val offset: Int = 0,
    /** The length of the whole source. */
    val totalChars: Int = content.length,
    /** A hash of the whole source, to pass back as `expectedSourceHash`. */
    val sourceHash: String = "",
    /** Where the next excerpt starts, or null when nothing remains. */
    val nextOffset: Int? = null,
    /** True when [content] is part of the source, not all of it. */
    val isExcerpt: Boolean = false,
    /** Whether `find` matched; null when no `find` was given. False means no match, not an empty file. */
    val searchMatched: Boolean? = null,
    /** Where `find` matched, in UTF-16 code units. */
    val matchOffset: Int? = null,
    /** 1-based line of [offset] and of [matchOffset]: offsets are not line numbers, so cite these. */
    val startLine: Int? = null,
    val matchLine: Int? = null,
    /** When `find` missed: up to eight `line: text` rows containing its longest identifier. */
    val nearMatches: List<String> = emptyList()
)

// Kotlin-only tools ---------------------------------------------------------

/** Tool: list all definitions visible from the current Kotlin file (incl shaders). */
@JsonClassDescription("List all definitions visible from the current Kotlin file, including associated shaders.")
@AgentToolMeta("kotlin", ToolExposure.DEFERRED, effects = [ToolEffect.READ_DOCUMENT])
data class ListDefinitionsRequest(val unused: Int = 0)

data class ListDefinitionsResult(val definitions: List<DefinitionInfo>)
data class DefinitionInfo(val name: String, val type: String, val fileRef: String, val language: String)

/** Tool: get full contents of text properties for the current box/file context. */
@JsonClassDescription("Return full contents for available text properties (code and shaders) in the current Field box and graph-relevant boxes, with file refs, languages, box names, relation labels, and preamble hints. Preamble hints distinguish inherited Box properties from shader-generated Mesh/Module binding helpers. Use it when you need the code of several related boxes at once, or the file refs EditFileRequest takes; for one property of a named box, GetBoxPropertyRequest is smaller. Unavailable (an error) outside a box's chat. e.g. a result entry's fileRef = `afield://doc/box-uid/code`.")
@AgentToolMeta("kotlin", effects = [ToolEffect.READ_DOCUMENT])
data class ListContentsRequest(val unused: Int = 0)

data class ListContentsResult(
    /** The ref of the current box's main property, when there is one. */
    val fileRef: String? = null,
    /** That property's text. */
    val content: String? = null,
    /** That property's language. */
    val language: String? = null,
    /** Every text property of the current box and its graph-relevant boxes. */
    val contents: List<ListContentEntry> = emptyList(),
    /** The names the box's preamble puts in scope, and where each comes from. */
    val preambleHints: List<PreamblePropertyHint> = emptyList(),
    /** Set when there is something to say about the listing itself, such as an empty box. */
    val message: String = ""
)

data class PreamblePropertyHint(
    /** The name as the box's code writes it. */
    val property: String,
    /** What the name is read from. */
    val receiver: String,
    /** How it is supplied: an inherited Box property or a shader-generated Mesh/Module binding helper. */
    val delegate: String,
    /** Its type, when known. */
    val type: String = "",
    /** The box that supplies it. */
    val boxUid: String = "",
    /** That box's name. */
    val boxName: String = "",
    /** How the supplying box relates to the current one: `current`, a parent, and so on. */
    val relation: String = "current",
    /** True when the supplying box is reached through the box graph. */
    val graphRelevant: Boolean = false
)

data class ListContentEntry(
    /** The ref to pass to `EditFileRequest`, such as `afield://doc/box-uid/code`. */
    val fileRef: String,
    /** The property name: `code`, `fragment`, and so on. */
    val property: String,
    /** Its language. */
    val language: String,
    /** Its full text. */
    val content: String,
    /** The box it belongs to. */
    val boxUid: String = "",
    /** That box's name. */
    val boxName: String = "",
    /** How that box relates to the current one. */
    val relation: String = "current"
)

@JsonClassDescription("List Kotlin REPL context variables currently retained by a Field box's KotlinInterface/KotlinInterface2 without evaluating code. Target may be blank for the current box, a box uid/name, or an afield://... ref. This lists retained script variables, not Field box properties. String values in both valuePreview and primitiveValue are bounded by maxValueChars (64 to 100000 characters) and carry an explicit truncation marker when shortened. In later EvaluateKotlinExpression calls, refer to these variables by their names; refer to the current/target box as thisBox, never as _ or _.foo.")
@AgentToolMeta("kotlin", effects = [ToolEffect.READ_DOCUMENT])
data class ListKotlinContextVariablesRequest(
    /** Blank for the current box, or a box uid, name or `afield://` ref. */
    val target: String = "",
    /** The bound on each string value, 64 to 100000 characters; a shortened value says so. */
    val maxValueChars: Int = 300
)

data class KotlinContextVariableInfo(
    /** The variable's name, as later evaluations refer to it. */
    val name: String,
    /** Its declaration as written. */
    val declaration: String,
    /** The type it was declared with. */
    val declaredType: String,
    /** The class of its current value. */
    val runtimeType: String,
    /** True for a `var`. */
    val mutable: Boolean,
    /** A bounded rendering of its value. */
    val valuePreview: String,
    /** The value itself when it is a primitive or string, else null. */
    val primitiveValue: Any? = null,
    /** Which primitive [primitiveValue] is, or empty. */
    val primitiveKind: String = ""
)

data class ListKotlinContextVariablesResult(
    /** Whether the box was found and its context read. */
    val success: Boolean,
    /** The target as requested. */
    val target: String,
    /** The box it resolved to. */
    val boxUid: String,
    /** That box's name. */
    val boxName: String,
    /** Which run of the box the variables come from. */
    val generation: Int,
    /** What happened, in words. */
    val message: String,
    /** The retained script variables, not the box's properties. */
    val variables: List<KotlinContextVariableInfo> = emptyList()
)

@JsonClassDescription("Evaluate a Kotlin expression in a Field box's existing KotlinInterface/KotlinInterface2 context. Target may be blank for the current box, a box uid/name, or an afield://... ref. Prefer read-only expressions; this runs against live runtime objects. String results in both primitiveValue and stringValue, and optional HTML, are bounded by maxChars (64 to 100000 characters) and carry an explicit truncation marker when shortened. Request a substring or summary of large values to inspect more selectively. The current/target Field box is thisBox. Do not use _, _.foo, or bare box as current-box syntax. Use thisBox.getPropertyRaw(\"name\") / thisBox.setPropertyRaw(\"name\", value) for raw box properties, and use box(\"uid\", root = thisBox.inside) when intentionally resolving or creating another box in the same document.")
@AgentToolMeta("kotlin", effects = [ToolEffect.EXECUTE_CODE])
data class EvaluateKotlinExpressionRequest(
    /** Blank for the current box, or a box uid, name or `afield://` ref. */
    val target: String = "",
    /** The Kotlin to evaluate in that box's live context, where the box is `thisBox`. */
    val expression: String,
    /** Also return an HTML rendering of the value. */
    val renderHtml: Boolean = true,
    /** The bound on string results and HTML, 64 to 100000 characters; a shortened value says so. */
    val maxChars: Int = 12000
)

data class EvaluateKotlinExpressionResult(
    /** Whether the expression compiled and ran. */
    val success: Boolean,
    /** The target as requested. */
    val target: String,
    /** The box it ran in. */
    val boxUid: String,
    /** That box's name. */
    val boxName: String,
    /** The expression that ran. */
    val expression: String,
    /** What kind of value came back. */
    val valueKind: String,
    /** The expression's static type. */
    val type: String = "",
    /** The class of the value. */
    val runtimeType: String = "",
    /** The value itself when it is a primitive or string, else null. */
    val primitiveValue: Any? = null,
    /** Which primitive [primitiveValue] is, or empty. */
    val primitiveKind: String = "",
    /** The value as a bounded string. */
    val stringValue: String = "",
    /** The value rendered as HTML, when asked for. */
    val html: String = "",
    /** Compiler diagnostics, when it did not compile. */
    val diagnostics: List<CompileDiagnostic> = emptyList(),
    /** What happened, in words. */
    val message: String = ""
)

/** Tool: edit another file by name (your integration decides how to apply this). */
@JsonClassDescription("Replace the whole text of another box property, named by its file ref (from ListContentsRequest), with newContent. Returns success and a message. Use it when the change belongs in a box other than the one this chat is on; for the box itself use SetBoxPropertyRequest or RunBoxRequest instead. It does not run anything. An unsupported file ref, or an unknown document or box, fails with success=false. e.g. file = `afield://doc/box-uid/code`.")
@AgentToolMeta("kotlin", effects = [ToolEffect.MUTATE_DOCUMENT])
data class EditFileRequest(val file: String, val newContent: String)

data class EditFileResponse(val success: Boolean, val message: String? = null)

/** Agentic verification tools --------------------------------------------- */

@JsonClassDescription("Compile Kotlin for a Field box without running it. Returns success and diagnostics (severity, message, line, column). Use it only when the code must not run yet; RunBoxRequest/LaunchBoxRequest compile by themselves and report the same errors, so do not call this before them. Fails when `file` names no box; e.g. file = `Primes`, or empty for the box this chat belongs to.")
@AgentToolMeta("kotlin", effects = [ToolEffect.READ_DOCUMENT])
data class CompileKotlinRequest(
    @JsonPropertyDescription("Which box: its name or uid, or its file ref afield://<document>/<box uid>/code exactly as ListContents reports it. Empty = the box this chat belongs to, where there is one.")
    val file: String = "",
    @param:JsonPropertyDescription("Optional replacement source to compile instead of the box's code. Omit it, or leave it empty, to compile the box's saved code.")
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
@AgentToolMeta("render", effects = [ToolEffect.CONTROL_RUNTIME, ToolEffect.GPU, ToolEffect.CAPTURE_IMAGE])
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
    "Post-shader-reload screenshot of the Field 4 render window, captured after a rendered frame. " +
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
    "Capture a live Field 4 (Vulkan) render window and attach it as an image, so you can see " +
        "what a renderer box is actually drawing. Returns success, the window title and size, and the " +
        "picture. Use it ONLY for boxes under a Renderer that opened a native window (meshes, shaders, " +
        "frames {}); for anything a box shows with display(...), lines(...), HTML, SVG or a chart, use " +
        "ViewBoxOutputRequest instead. A box with no render window fails with success=false: do not " +
        "retry it, switch to ViewBoxOutputRequest. Look " +
        "once before editing and again after; unlike ReloadShaders' visualCheck it needs no successful " +
        "reload. e.g. uid empty (this chat's box), settleMs = 500, note = `is the cube lit from the left?`"
)
@AgentToolMeta("render", effects = [ToolEffect.CAPTURE_IMAGE, ToolEffect.GPU])
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
                    "Field 4 render window${if (title.isNotBlank()) " '$title'" else ""}, " +
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
@AgentToolMeta("macros", ToolExposure.DEFERRED, effects = [ToolEffect.READ_DOCUMENT])
data class MacroListRequest(val unused: Int = 0)

data class MacroListResult(
    val success: Boolean,
    val message: String,
    val macros: List<String> = emptyList()
)

@JsonClassDescription("Read a saved operation-transcript macro YAML file by name. Read before editing a macro, and usually read before running when target mapping or expected effects matter.")
@AgentToolMeta("macros", ToolExposure.DEFERRED, effects = [ToolEffect.READ_DOCUMENT])
data class MacroReadRequest(val name: String)

data class MacroReadResult(
    val success: Boolean,
    val message: String,
    val name: String,
    val content: String = "",
    val operationCount: Int = 0
)

@JsonClassDescription("Create or replace a saved operation-transcript macro YAML file in the current Field document. Content should be YAML with an operations list. Use this to save a user-requested new/edited macro; do not rewrite an existing macro just to run it.")
@AgentToolMeta("macros", ToolExposure.DEFERRED, effects = [ToolEffect.MUTATE_DOCUMENT])
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

@JsonClassDescription("Run or undo a saved operation-transcript macro. Use direction=do to replay it and direction=undo to run it backwards. targetBoxes may contain box uids or names; when set, the macro's first recorded source box is remapped to each target box, which is the normal way to do a recorded macro to the current/selected box. Empty targetBoxes replays the recorded uids literally. Inspect applied/skipped/errors; if applied=0 or errors are returned, do not repeat the identical request. A failed Kotlin setup stops dependent operations and preserves changes already applied. Other language engines are verified from their execution verdicts (up to 10 s); setupVerified=false means a verdict did not arrive in time. Inspect errors before retrying; undo is a separate explicit action.")
@AgentToolMeta("macros", ToolExposure.DEFERRED, effects = [ToolEffect.MUTATE_DOCUMENT, ToolEffect.EXECUTE_CODE])
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
    val errors: List<String> = emptyList(),
    val output: List<afield.app.protocol.MacroOutputLine> = emptyList(),
    val setupVerified: Boolean = true
)

/** Code-use telemetry tools ------------------------------------------------- */

@JsonClassDescription("Query Field's local code-use telemetry database. kind may be summary, failures, snippets, launches, symbols, unresolved, line-callers, references-for-code, related-source-for-code, semantic-analyze, hot-symbols, or early-document-symbols. Prefer kind=failures when looking for compiler errors/runtime exceptions that reveal API gaps, missing docs, or growth points. Optional document and box filters are case-insensitive substrings for row types that carry that metadata.")
@AgentToolMeta("codeuse", effects = [ToolEffect.FILESYSTEM_READ])
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
@AgentToolMeta("codeuse", ToolExposure.DEFERRED, effects = [ToolEffect.FILESYSTEM_READ])
data class CodeUseCallersForSourceLineRequest(
    val sourcePath: String,
    val line: Int,
    val limit: Int = 20
)

@JsonClassDescription("Given Kotlin code, rank the most interesting files/properties/methods inside src/ that the code appears to refer to. This is useful for grounding a Field box or inline chat request in Field source.")
@AgentToolMeta("codeuse", ToolExposure.DEFERRED, effects = [ToolEffect.FILESYSTEM_READ])
data class CodeUseReferencesForCodeRequest(
    val code: String,
    val limit: Int = 5
)

@JsonClassDescription("List hot Field source symbols by observed Field box usage. window may be all, week, day, or month. Report each row's confidence literally: k2-resolved is semantic; source-index-* values are static source-index evidence; textual values are fallback evidence. Do not call source-index or textual rows resolved.")
@AgentToolMeta("codeuse", ToolExposure.DEFERRED, effects = [ToolEffect.FILESYSTEM_READ])
data class CodeUseHotSymbolsRequest(
    val window: String = "all",
    val limit: Int = 20
)

@JsonClassDescription("Find source symbols used early in a document's observed code-use timeline and then rarely afterward. Use this to spot initialization/open-time helpers. document is an optional case-insensitive substring.")
@AgentToolMeta("codeuse", ToolExposure.DEFERRED, effects = [ToolEffect.FILESYSTEM_READ])
data class CodeUseEarlyDocumentSymbolsRequest(
    val document: String = "",
    val earlyWindowMs: Long = 600000,
    val limit: Int = 20
)

@JsonClassDescription("Given Field box code, return the most relevant src/ routines with bounded source excerpts, observed call-site frequencies, return/lifecycle summaries, and other Field snippets that called the same routines. Prefer this when interrogating how box code maps onto Field source.")
@AgentToolMeta("codeuse", ToolExposure.DEFERRED, effects = [ToolEffect.FILESYSTEM_READ])
data class CodeUseRelatedSourceForCodeRequest(
    val code: String,
    val limit: Int = 5,
    val callersPerSymbol: Int = 5,
    val sourceRadiusLines: Int = 24
)

@JsonClassDescription("Given Field box or inline Kotlin code, ask Field's optional semantic K2 sidecar to resolve the code against src/ and enrich the result with local code-use graph evidence: source excerpts, call-site frequencies, timestamps, and peer snippets. Falls back to the local source-index graph if the sidecar is not configured or unavailable.")
@AgentToolMeta("codeuse", ToolExposure.DEFERRED, effects = [ToolEffect.FILESYSTEM_READ])
data class CodeUseSemanticAnalyzeCodeRequest(
    val code: String,
    val sourceName: String = "AgentSnippet.kt",
    val limit: Int = 5,
    val callersPerSymbol: Int = 5,
    val sourceRadiusLines: Int = 24
)

/** Semantic retrieval tools ------------------------------------------------ */

@JsonClassDescription("Search Field's native Lucene semantic retrieval index across Field internals, current workspace code, and documentation. This is natural-language to code/docs retrieval, not compiler resolution. Set corpus for one corpus, or corpora for combinations. Available corpora are field-internals, workspace, and documentation. Leave both blank to search all corpora. Use documentName with corpus/workspace to search a specific current workspace document. Results include BM25/symbol/vector ranks when available; vector search is active only when the local ONNX embedder is configured.")
@AgentToolMeta("retrieval", effects = [ToolEffect.FILESYSTEM_READ])
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
@AgentToolMeta("retrieval", ToolExposure.DEFERRED, effects = [ToolEffect.FILESYSTEM_READ])
data class SemanticRetrievalStatusRequest(val unused: Int = 0)

@JsonClassDescription("Rebuild Field's native Lucene semantic retrieval index from current source-index declarations and documentation sections. Use sparingly; normal searches rebuild automatically if the index is missing.")
@AgentToolMeta("retrieval", ToolExposure.DEFERRED, effects = [ToolEffect.FILESYSTEM_READ, ToolEffect.FILESYSTEM_WRITE])
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

@JsonClassDescription("Read this chat's in-memory scratchpad across turns. It does not survive a server restart; use document guide tools for durable handoff notes.")
@AgentToolMeta("memory", effects = [ToolEffect.AGENT_STATE])
data class ScratchpadReadRequest(val unused: Int = 0)

@JsonClassDescription("Update this chat's in-memory scratchpad. Use append, replace or clear. Use document guide tools for notes that must survive a restart.")
@AgentToolMeta("memory", effects = [ToolEffect.AGENT_STATE])
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

@JsonClassDescription("Read the in-memory checklist for this chat. Use it to track long-running tasks across turns.")
@AgentToolMeta("memory", effects = [ToolEffect.AGENT_STATE])
data class ChecklistReadRequest(val unused: Int = 0)

@JsonClassDescription("Update the in-memory checklist for this chat. Use operation add, update, complete, reopen, remove, replace, or clear. For update/complete/reopen/remove, itemId identifies the item.")
@AgentToolMeta("memory", effects = [ToolEffect.AGENT_STATE])
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

    // Turn counter and the turn the checklist last changed in: a list set in round 1 and never touched again
    // was re-shown at every turn's end as if current, and read as "nothing done" (2026-10-28).
    private var turn = 0
    private var checklistChangedInTurn = -1

    /** Called when an agent turn starts. */
    fun beginTurn() = lock.write { turn++ }

    /** `"2 of 6 done, 1 in progress"`, plus `"; not updated this turn"` when it is stale; null when empty. */
    fun checklistSummary(): String? = lock.read {
        if (checklist.isEmpty()) return@read null
        val done = checklist.count { it.status == "done" }
        val active = checklist.count { it.status == "in_progress" }
        "$done of ${checklist.size} done" + (if (active > 0) ", $active in progress" else "") +
            if (checklistChangedInTurn < turn) "; not updated this turn" else ""
    }

    /**
     * The scratchpad and checklist as they are, for the session tree: memory is branch state, so a rewound
     * conversation gets back the memory of the turn it was rewound to (AiSessionTree).
     */
    data class Snapshot(val scratchpad: String, val checklistJson: String)

    fun snapshot(): Snapshot = lock.read { Snapshot(scratchpad, aiToolMapper.writeValueAsString(checklist)) }

    fun restore(snapshot: Snapshot) = lock.write {
        scratchpad = snapshot.scratchpad
        checklist.clear()
        runCatching {
            checklist += aiToolMapper.readValue(snapshot.checklistJson.ifBlank { "[]" },
                aiToolMapper.typeFactory.constructCollectionType(List::class.java, ChecklistItem::class.java)) as List<ChecklistItem>
        }
        nextChecklistId = (checklist.mapNotNull { it.id.toIntOrNull() }.maxOrNull() ?: 0) + 1
    }

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
        checklistChangedInTurn = turn
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
            append("**Checklist**")
            if (checklistChangedInTurn < turn) append(" _(not updated this turn)_")
            appendLine()
            checklist.forEach { item ->
                append(when (item.status) { "done" -> "- [x] "; "in_progress" -> "- [~] "; else -> "- [ ] " })
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
 * The MCP backdoor carries images separately in `AgentAiToolCallResponse.images` and translates
 * them to MCP image blocks. The ordinary result JSON still excludes image data. Include measurements
 * and a caption alongside each image so all callers can understand what it proves.
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
@JsonClassDescription("Look up symbols, including extension properties such as String.file. Exact names return the exact declaration first, with KDoc and getter/source. Related guides/examples are optional; request them only when needed.")
@AgentToolMeta("symbols", effects = [ToolEffect.FILESYSTEM_READ])
data class WorkspaceSymbolSearch(val query: String, val limit: Int = 25, val includeRelatedContext: Boolean = false)

data class WorkspaceSymbolSearchResult(
    val matches: List<WorkspaceSymbolMatch>,
    /** Source files saved since the index was built whose text contains the query; their symbols may be missing above. */
    val unindexedMatches: List<String> = emptyList(),
    val message: String = ""
)
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
    val relatedExamples: List<String> = emptyList(),
    val kdoc: String? = null
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
    val documentGuides: DocumentGuideTools? = null,

    // Optional extra tools
    val workspaceSymbolSearch: ((WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult)? = null,
    val workspaceSearch: ((WorkspaceSearchRequest) -> WorkspaceSearchResult)? = null,
    /** The `repo` tools over one checkout ([RepoFileTools]); only a headless job with a repo scope wires it. */
    val repoFiles: RepoFileTools? = null,
    /** The `repo` command tool ([RepoCommandTools]) over [repoFiles]' root; only a job that allows commands wires it. */
    val repoCommands: RepoCommandTools? = null,

    /** What this conversation is about, when it is about one box: see [ToolFocus]. */
    val focus: ToolFocus? = null,

    /**
     * Document-authored tools contributed by boxes via the [aiTool] DSL. These are
     * resolved per-request (the anchoring document's [DocumentToolRegistry] snapshot)
     * and layered on top of the static built-ins by [ToolRegistry]: they surface on
     * both the OpenAI chat path ([ToolRegistry.addAllTo]/[ToolRegistry.execute]) and the
     * provider-neutral MCP face ([ToolRegistry.describe]/[ToolRegistry.executeByName]).
     * A dynamic tool whose name collides with a built-in is ignored (built-ins win).
     */
    val dynamicTools: List<BoxTool> = emptyList(),

    /**
     * Native box graph + execution tools (NewBox, RunBox, ReadBoxOutput, ...) for one document.
     * `null` hides them: only contexts that mean to let the agent edit and run the document wire it.
     */
    val fieldGraph: afield.ai.agent.FieldGraphTools? = null,

    /**
     * Tool names this conversation must not see or call, whatever their gates say. The registry
     * enforces it everywhere (schemas, MCP descriptors, dispatch), so an A/B arm that withholds a tool
     * cannot leak it through a second path. An eval's `programs=off` arm withholds `RunToolProgramRequest`.
     */
    val withheld: Set<String> = emptySet(),

    /**
     * Progress lines from inside a long tool — a tool program reports each nested call through it,
     * so the panel shows `program: RunBox Cell03 (4/12)` rather than one silent spinner. The bridges
     * wire it to their `onProgress`; null where nobody is watching.
     */
    val progress: ((String) -> Unit)? = null,

    /**
     * Deferred tools this conversation has loaded with `DescribeToolsRequest` — the bridges pass their
     * thread's own set, so a load lasts for the conversation (and, with branching, for its branch). Null
     * where there is no conversation to remember it in.
     */
    val promoted: MutableSet<String>? = null,
    /** Structured progress from native tools, scoped to this conversation. */
    val reportProgress: ((LlmProgressUpdate) -> Unit)? = null,
    /**
     * Messages the person sends while this turn runs. The bridges drain it after each tool round and
     * send what it held as a user message alongside the tool results; null where nobody can write in.
     */
    val inbox: AiTurnInbox? = null,
    /**
     * Whether a tool may end this turn to wait ([SuspendsTurn]). Only a conversation that something will
     * resume says so: the box chat's general turn, which saves the suspension with the chat. Elsewhere
     * (the editor kinds, the document chat, a headless task, MCP) the waiting tools are not offered.
     */
    val suspendable: Boolean = false,
) {
    /** Whether the document this conversation acts on refuses edits: the session's flag, or Field's global one. */
    fun documentReadOnly(): Boolean = afield.app.server.ReadOnlyDocuments.global || fieldGraph?.info?.readOnly == true
}

/**
 * The document, box and property a conversation is focused on — the box whose AI4 chat is open.
 *
 * Extension tools read it to scope themselves: a study tool is offered when the focused box is a
 * study it knows how to work on, and it works on *that* box, so two open documents (or two programs
 * in one document) never share a tool's state. `null` for conversations that are not about a box,
 * such as the document-wide chat.
 */
data class ToolFocus(
    /** The document's path, as `SessionInformation.filename`. */
    val document: String,
    /** The focused box. */
    val box: afield.document.graph.Box,
    /** The property the chat was opened on, such as `code` or `stackSL`. */
    val property: String,
)

/** Turns a document's virtual text files into attachments the agent can list and read, keeping each file's name and language. */
fun virtualFilesToAttachments(files: List<afield.document.workspace.VirtualTextFile>): List<ContextAttachment> =
    files.map { ContextAttachment(it.ref.fileName, it.ref.language, it.content) }

internal class ToolRegistry {
    private data class Handler<T : Any>(
        val cls: Class<T>,
        val available: (ToolExecutionContext) -> Boolean,
        val fn: (ToolExecutionContext, T) -> Any,
        val meta: ToolMeta = ToolMeta.of(cls),
    )

    private val handlers = ConcurrentHashMap<String, Handler<*>>()

    /** The names of the registered (non-dynamic) tools. */
    fun handlerNames(): Set<String> = handlers.keys.toSet()

    /** The request class of every registered (non-dynamic) tool, by name, whatever its gate: what AgentToolSchemaTest checks. */
    internal fun handlerClasses(): Map<String, Class<*>> = handlers.mapValues { it.value.cls }.toSortedMap()

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
        meta: ToolMeta = ToolMeta.of(toolClass),
        handler: (ToolExecutionContext, T) -> Any
    ) {
        synchronized(writeLock) {
            handlers[toolClass.simpleName] = Handler(toolClass, available, handler, meta)
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
        meta: ToolMeta = ToolMeta.of(toolClass),
        handler: (ToolExecutionContext, T) -> Any,
    ): Boolean = synchronized(writeLock) {
        val name = toolClass.simpleName
        if (handlers.containsKey(name) && !extensionOwned.contains(name)) return@synchronized false
        handlers[name] = Handler(toolClass, available, handler, meta)
        extensionOwned.add(name)
        true
    }

    /**
     * Every core (non-extension) request class, regardless of its availability gate. Read by the
     * description audit (`ToolDescriptionRubricTest`), which must see tools whose providers a test
     * context does not wire.
     */
    internal fun coreToolClasses(): List<Class<*>> =
        handlers.entries.filter { it.key !in extensionOwned }.map { it.value.cls }.sortedBy { it.simpleName }

    /** Every extension-owned request class (what [AgentToolExtensions] applied), for the same audit. */
    internal fun extensionToolClasses(): List<Class<*>> =
        handlers.entries.filter { it.key in extensionOwned }.map { it.value.cls }.sortedBy { it.simpleName }

    /** True if [registerExtension] would accept [name] — i.e. core does not already hold it. */
    internal fun acceptsExtensionNamed(name: String): Boolean =
        !handlers.containsKey(name) || extensionOwned.contains(name)

    /** Retire an extension's tool. A core tool of the same name is left alone. */
    internal fun unregisterExtension(name: String) {
        synchronized(writeLock) {
            if (extensionOwned.remove(name)) handlers.remove(name)
        }
    }

    /** A handler's gate, with the conversation's [ToolExecutionContext.withheld] applied on top. */
    private fun Handler<*>.offeredUnder(ctx: ToolExecutionContext): Boolean =
        cls.simpleName !in ctx.withheld && available(ctx)

    // ---- exposure: which face sees which tool ------------------------------------------------------

    private fun programsOffered(ctx: ToolExecutionContext): Boolean =
        handlers[afield.ai.tools.program.RUN_TOOL_PROGRAM]?.offeredUnder(ctx) == true

    /**
     * Whether [face] lists a tool with [meta] under [ctx]. A model sees DIRECT tools, DEFERRED ones it has
     * loaded with DescribeToolsRequest, and PROGRAM ones only when it has no tool programs to call them from.
     * MCP clients and programs see everything but HIDDEN.
     */
    private fun shown(name: String, meta: ToolMeta, face: ToolFace, ctx: ToolExecutionContext): Boolean = when (meta.exposure) {
        ToolExposure.HIDDEN -> false
        ToolExposure.DIRECT -> true
        ToolExposure.DEFERRED -> face != ToolFace.MODEL || ctx.promoted?.contains(name) == true
        ToolExposure.PROGRAM -> face != ToolFace.MODEL || !programsOffered(ctx)
    }

    /** Core's own tools (not extensions) and their metadata, whatever their gates: what ToolMetaPinTest pins. */
    internal fun coreMetas(): Map<String, ToolMeta> =
        handlers.filterKeys { it !in extensionOwned }.mapValues { it.value.meta }.toSortedMap()

    /** The metadata of [name] under [ctx] (built-in, extension or box-authored), or null if unknown. */
    fun metaOf(ctx: ToolExecutionContext, name: String): ToolMeta? =
        handlers[name]?.meta ?: dynamicTool(dynamicToolsIn(ctx), name)?.meta

    fun addAllTo(builder: ResponseCreateParams.Builder, ctx: ToolExecutionContext, deferLoading: Boolean = false, loadedTools: Set<String> = emptySet()) {
        val immediate = setOf("Commentary", "AttachmentRequest", "DocumentGuideListRequest", "DocumentGuideReadRequest", "ChecklistReadRequest", "ChecklistUpdateRequest", "ScratchpadReadRequest", "ScratchpadUpdateRequest", "DescribeToolsRequest", "RunToolProgramRequest")
        var hasDeferredTool = false
        handlers.values.forEach { h ->
            if (h.offeredUnder(ctx) && shown(h.cls.simpleName, h.meta, ToolFace.MODEL, ctx)) {
                @Suppress("UNCHECKED_CAST")
                val tool = openAiTool(h.cls as Class<Any>)
                val defer = deferLoading && tool.name() !in immediate && tool.name() !in loadedTools
                hasDeferredTool = hasDeferredTool || defer
                builder.addTool(if (defer) tool.toBuilder().deferLoading(true).build() else tool)
            }
        }
        // Document-authored tools (box aiTool DSL). Built-ins win on name collision.
        dynamicToolsIn(ctx).filter { shown(it.wireName, it.meta, ToolFace.MODEL, ctx) }.forEach { tool ->
            val definition = tool.toFunctionTool()
            val defer = deferLoading && definition.name() !in loadedTools
            hasDeferredTool = hasDeferredTool || defer
            builder.addTool(if (defer) definition.toBuilder().deferLoading(true).build() else definition)
        }
        if (hasDeferredTool) builder.addTool(com.openai.models.responses.ToolSearchTool.builder()
            .execution(com.openai.models.responses.ToolSearchTool.Execution.SERVER).build())
    }

    fun execute(ctx: ToolExecutionContext, call: ResponseFunctionToolCall): Any =
        dispatch(ctx, call.name(), CallOrigin.MODEL, { cls -> call.arguments(cls) }, { call.arguments() })

    /**
     * The one place a call is checked, for both execution paths: the conversation's withheld set, the
     * tool's gate (the OpenAI path used to skip it), HIDDEN tools (Field's own callers only), and a
     * document mutation on a read-only document — refused here, centrally, because until 2026-10-02 only
     * two of the graph tools checked for themselves and NewBox, Connect and Delete did not.
     */
    private fun dispatch(
        ctx: ToolExecutionContext,
        name: String,
        origin: CallOrigin,
        typedArgs: (Class<Any>) -> Any,
        rawArgs: () -> String,
    ): Any {
        check(name !in ctx.withheld) { "Tool '$name' is not available in this conversation." }
        val h = handlers[name]
        val meta = h?.meta ?: dynamicTool(dynamicToolsIn(ctx), name)?.meta
            ?: throw IllegalArgumentException("No tool registered for: $name")
        if (meta.exposure == ToolExposure.HIDDEN && origin != CallOrigin.BACKDOOR) {
            throw IllegalStateException("Tool '$name' is not offered to agents.")
        }
        if (meta.mutates && ctx.documentReadOnly()) {
            throw IllegalStateException("This document is read-only, and '$name' changes it.")
        }
        if (h != null && !h.offeredUnder(ctx)) {
            throw IllegalStateException("Tool '$name' is not available in this context (its provider is not wired).")
        }
        // The document's agent extensions (agentExtension { beforeTool / afterTool }) see every call, a tool
        // program's nested ones included; a refusal becomes the result the model reads.
        val documentKey = ctx.extensionDocumentKey()
        val hooked = AgentExtensionRegistry.hooksFor(documentKey).isNotEmpty()
        val view = if (hooked) ToolCallView(name, runCatching { rawArgs() }.getOrDefault("{}"), origin.name, meta.effects.map { it.name }.toSet()) else null
        if (view != null) AgentExtensionRegistry.before(documentKey, view)?.let { return it }
        val started = System.nanoTime()
        val result: Any = try {
            if (h != null) {
                @Suppress("UNCHECKED_CAST")
                val handler = h as Handler<Any>
                handler.fn(ctx, typedArgs(handler.cls))
            } else {
                // Fall through to a document-authored tool with this name.
                dynamicTool(dynamicToolsIn(ctx), name)!!.runBlockingFromToolLoop(ctx, rawArgs())
            }
        } catch (e: Throwable) {
            if (view != null && !aiToolFailureEndsTurn(e)) {
                AgentExtensionRegistry.after(documentKey, view, ToolResultView(false, aiToolFailureMessage(e), (System.nanoTime() - started) / 1_000_000))
            }
            throw e
        }
        if (view != null) {
            val summary = (result as? SummarisesForToolLog)?.toolLogSummary()
                ?: runCatching { aiToolMapper.writeValueAsString(result) }.getOrDefault(result.toString()).take(200)
            val json = runCatching { aiToolMapper.readTree(aiToolMapper.writeValueAsString(result)) }.getOrNull()
            val ok = json == null || !(json.get("success")?.asBoolean(true) == false || json.get("error")?.asBoolean(false) == true)
            AgentExtensionRegistry.after(documentKey, view, ToolResultView(ok, summary, (System.nanoTime() - started) / 1_000_000))
        }
        return result
    }

    /** The box tool called [name], by its own name or the [BoxTool.wireName] a model calls it by. */
    private fun dynamicTool(tools: List<BoxTool>, name: String): BoxTool? =
        tools.firstOrNull { it.name == name } ?: tools.firstOrNull { it.wireName == name }

    /** Dynamic tools visible under [ctx], excluding any whose name or wire name a built-in has. */
    private fun dynamicToolsIn(ctx: ToolExecutionContext): List<BoxTool> {
        val tools = (ctx.focus?.document?.takeIf { it.isNotBlank() }?.let(DocumentToolRegistry::toolsFor) ?: ctx.dynamicTools)
            .filter { it.name !in handlers.keys && it.wireName !in handlers.keys && it.name !in ctx.withheld && it.wireName !in ctx.withheld }
        val collisions = tools.groupBy { it.wireName }.values.filter { it.size > 1 }
        require(collisions.isEmpty()) { "Conflicting tool identities: ${collisions.flatten().joinToString { it.name }}" }
        return tools
    }

    internal fun openAiDynamicToolsIn(ctx: ToolExecutionContext): Map<String, BoxTool> =
        dynamicToolsIn(ctx).associateBy { it.wireName }

    // --- Provider-neutral face (used by the MCP backdoor bridge) -----------
    // The OpenAI path above binds tools to ResponseCreateParams / ResponseFunctionToolCall.
    // These two methods expose the exact same registry + gates + handlers to any
    // transport that speaks plain JSON — currently the agent backdoor's
    // agent-ai-tools-list / agent-ai-tool-call handlers, surfaced over MCP.

    /**
     * Tools available under [ctx], as MCP-style descriptors {name, description, inputSchema}, for one [face]:
     * what a model is sent (the default), what an MCP client lists, or what a program may call. The MCP face
     * adds `annotations` (`readOnlyHint`, `destructiveHint`) and `x-field` {namespace, exposure, effects}.
     */
    fun describe(ctx: ToolExecutionContext, face: ToolFace = ToolFace.MODEL): JsonArray = buildJsonArray {
        fun descriptor(name: String, description: String?, schema: JsonObject, meta: ToolMeta) = buildJsonObject {
            put("name", name)
            if (!description.isNullOrBlank()) put("description", description)
            put("inputSchema", schema)
            if (face == ToolFace.MCP) {
                put("annotations", buildJsonObject {
                    put("readOnlyHint", meta.readOnly)
                    put("destructiveHint", meta.mutates)
                })
                put("x-field", buildJsonObject {
                    put("namespace", meta.namespace)
                    put("exposure", meta.exposure.name)
                    put("effects", buildJsonArray { meta.effects.sortedBy { it.name }.forEach { add(JsonPrimitive(it.name)) } })
                })
            }
        }
        handlers.values
            .filter { it.offeredUnder(ctx) && shown(it.cls.simpleName, it.meta, face, ctx) }
            .sortedBy { it.cls.simpleName }
            .forEach { h ->
                add(descriptor(h.cls.simpleName, h.cls.kotlin.findAnnotation<JsonClassDescription>()?.value,
                    ToolSchema.forClass(h.cls), h.meta))
            }
        dynamicToolsIn(ctx)
            .filter { shown(it.wireName, it.meta, face, ctx) }
            .sortedBy { it.wireName }
            .forEach { tool -> add(descriptor(if (face == ToolFace.MCP) tool.name else tool.wireName, tool.description, tool.parametersSchema, tool.meta)) }
    }

    /** True if [name] is registered (static or dynamic) and its gate passes under [ctx]. Exposure aside: a deferred tool is available. */
    fun isAvailable(ctx: ToolExecutionContext, name: String): Boolean =
        handlers[name]?.offeredUnder(ctx) == true || dynamicTool(dynamicToolsIn(ctx), name) != null

    /** The descriptor [describe] would give for [name] under [ctx] on [face], or null if it is not offered there. */
    fun describeOne(ctx: ToolExecutionContext, name: String, face: ToolFace = ToolFace.PROGRAM): JsonObject? =
        describe(ctx, face).firstOrNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull == name } as? JsonObject

    /** Offered tools a model does not get schemas for until it asks: DEFERRED ones not yet loaded, by namespace. */
    fun deferredUnder(ctx: ToolExecutionContext): Map<String, List<String>> {
        // Exposure first: DescribeToolsRequest's own gate calls this, so asking every gate here would recurse.
        val names = handlers.values.filter { it.meta.exposure == ToolExposure.DEFERRED && ctx.promoted?.contains(it.cls.simpleName) != true && it.offeredUnder(ctx) }
            .map { it.meta.namespace to it.cls.simpleName } +
            dynamicToolsIn(ctx).filter { it.meta.exposure == ToolExposure.DEFERRED && ctx.promoted?.contains(it.wireName) != true }.map { it.meta.namespace to it.wireName }
        return names.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.sorted() }.toSortedMap()
    }

    /**
     * The tool catalogue for the instructions: one line per namespace that a model cannot learn from its
     * schemas alone — namespaces with deferred or program-only tools, and every tool that came from outside
     * core (extensions, box-authored tools), which the hand-written tool adverts never mention. Empty when
     * there is nothing to say.
     */
    fun catalogue(ctx: ToolExecutionContext): String {
        data class Entry(val namespace: String, val label: String)
        val entries = mutableListOf<Entry>()
        fun label(name: String, meta: ToolMeta): String {
            val marks = buildString {
                if (meta.exposure == ToolExposure.DEFERRED && ctx.promoted?.contains(name) != true) append('*')
                if (meta.exposure == ToolExposure.PROGRAM && programsOffered(ctx)) append('°')
            }
            return marks + name + if (meta.mutates) "!" else ""
        }
        // A deferred tool is only worth naming where it can be loaded: a context with no conversation to
        // remember a load in (promoted == null) never offers DescribeToolsRequest.
        fun listable(meta: ToolMeta) = meta.exposure != ToolExposure.HIDDEN && (meta.exposure != ToolExposure.DEFERRED || ctx.promoted != null)
        handlers.values.filter { it.offeredUnder(ctx) && listable(it.meta) }.forEach { h ->
            val name = h.cls.simpleName
            val notVisible = h.meta.exposure == ToolExposure.DEFERRED || (h.meta.exposure == ToolExposure.PROGRAM && programsOffered(ctx))
            if (notVisible || name in extensionOwned) entries += Entry(h.meta.namespace, label(name, h.meta))
        }
        dynamicToolsIn(ctx).filter { listable(it.meta) }.forEach { entries += Entry(it.meta.namespace, label(it.wireName, it.meta)) }
        if (entries.isEmpty()) return ""
        return buildString {
            append("- Tool catalogue beyond the schemas above (* = schema not loaded: call DescribeToolsRequest with the namespace or names first; ")
            append("° = call it from RunToolProgramRequest; ! = changes the document):")
            entries.groupBy { it.namespace }.toSortedMap().forEach { (namespace, list) ->
                append("\n  - ").append(namespace).append(": ").append(list.map { it.label }.sorted().joinToString(", "))
            }
        }
    }

    /**
     * Deserialize [argsJson] into the tool's request class (Jackson, matching the
     * OpenAI path's `call.arguments(cls)`) and invoke the handler. The result is a
     * plain Jackson data class; callers serialize it with [aiToolMapper].
     */
    fun executeByName(ctx: ToolExecutionContext, name: String, argsJson: String, origin: CallOrigin = CallOrigin.MODEL): Any =
        dispatch(ctx, name, origin, { cls -> aiToolMapper.readValue(argsJson.ifBlank { "{}" }, cls) }, { argsJson })
}

/** Keep the SDK schema, including nested definitions, but retain Kotlin nullable arguments.
 * Java reflection alone loses that distinction and strict tools then force invented values for
 * mutually exclusive options such as a point pick and a rectangular pick.
 */
internal fun openAiTool(cls: Class<Any>): FunctionTool {
    val sdk = ResponseCreateParams.builder().model("schema").input("").addTool(cls).build()
        .tools().orElseThrow().single().function().orElseThrow()
    val nullable = cls.kotlin.primaryConstructor?.parameters.orEmpty()
        .filter { it.type.isMarkedNullable }.mapNotNull { it.name }
    if (nullable.isEmpty()) return sdk
    val schema = aiToolMapper.createObjectNode()
    sdk._parameters().asObject().orElseThrow().forEach { (key, value) ->
        schema.set<com.fasterxml.jackson.databind.JsonNode>(key, value.convert(com.fasterxml.jackson.databind.JsonNode::class.java))
    }
    val properties = schema.path("properties") as? ObjectNode ?: return sdk
    nullable.forEach { name ->
        properties.get(name)?.let { original ->
            val union = aiToolMapper.createObjectNode()
            union.putArray("anyOf").add(original).addObject().put("type", "null")
            properties.set<ObjectNode>(name, union)
        }
    }
    val params = FunctionTool.Parameters.builder()
    schema.properties().forEach { (key, value) -> params.putAdditionalProperty(key, JsonValue.fromJsonNode(value)) }
    return sdk.toBuilder().parameters(params.build()).build()
}

/**
 * Refuse a request class whose schema openai-java would refuse. That check runs when a turn adds its tools,
 * and one refused class fails the whole turn, so an extension finds out here, where its author can see it.
 */
internal fun requireOpenAiSchema(cls: Class<*>) {
    try {
        @Suppress("UNCHECKED_CAST")
        openAiTool(cls as Class<Any>)
    } catch (e: Exception) {
        throw IllegalArgumentException(
            "Cannot register an AI tool named '${cls.simpleName}': OpenAI refuses its argument schema, so every " +
                "OpenAI or OpenRouter turn that offered it would fail before sending. A Map field is the usual cause; " +
                "take a JSON object in a String, or a List of a small data class, instead. ${e.message}", e
        )
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

    internal fun propertySchema(type: KType, description: String?): JsonObject {
        val base = schemaForType(type)
        if (description.isNullOrBlank()) return base
        return buildJsonObject {
            base.forEach { (k, v) -> put(k, v) }
            put("description", description)
        }
    }

    internal fun schemaForType(type: KType): JsonObject {
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

/**
 * Refuses model output that still contains one of the prompt's own markers (`<cursor>`, `<error>`,
 * `<rewrite>`, ...), by throwing `MalformedAI`, so a half-followed instruction is never written into a box.
 */
fun validateNoMarkers(code: String) {
    val bad = listOf("<help>", "<error>", "</error>", "<cursor>", "<rewrite>", "</rewrite>")
    val hit = bad.firstOrNull { code.contains(it) }
    if (hit != null) throw MalformedAI("Model output still contained marker '$hit'. Refusing to apply.")
}

