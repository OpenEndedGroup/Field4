@file:OptIn(ExperimentalSerializationApi::class)

package afield

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.js.JsName
import kotlin.math.max
import kotlin.math.min

var _uid = 0

fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is Map<*, *> -> toJsonElement()
    is Collection<*> -> toJsonElement()
    is ByteArray -> this.toList().toJsonElement()
    is CharArray -> this.toList().toJsonElement()
    is ShortArray -> this.toList().toJsonElement()
    is IntArray -> this.toList().toJsonElement()
    is LongArray -> this.toList().toJsonElement()
    is FloatArray -> this.toList().toJsonElement()
    is DoubleArray -> this.toList().toJsonElement()
    is BooleanArray -> this.toList().toJsonElement()
    is Array<*> -> toJsonElement()
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Enum<*> -> JsonPrimitive(this.toString())
    else -> {
        throw IllegalStateException("Can't serialize unknown type: $this")
    }
}

fun Map<*, *>.toJsonElement(): JsonElement {
    val map = mutableMapOf<String, JsonElement>()
    this.entries.forEach { e ->
        map.put(e.key as String, e.value.toJsonElement())
    }
    return JsonObject(map)
}

fun Collection<*>.toJsonElement(): JsonElement {
    return JsonArray(this.map { it.toJsonElement() })
}

fun Array<*>.toJsonElement(): JsonElement {
    return JsonArray(this.map { it.toJsonElement() })
}

@Serializable
data class Message(
    @JsName("name")
    val name: String,
    @JsName("uid")
    val uid: String = "message_${_uid++}",
    @JsName("replyTo")
    var replyTo: String? = null,
    @JsName("originalName")
    var originalName: String? = null,
    @JsName("payload")
    var payload: JsonElement? = null
) {

    fun toName(replyTo: String): Message {
        return Message(name = replyTo, uid = uid, replyTo = replyTo, originalName = this.name, payload = payload)
    }
}

@Serializable
data class MessageBatch(
    val messages: List<Message>
)


@Serializable
enum class OutputKind {
    normal, error
}

@Serializable
data class Output(
    val uid: String = "",
    val line: Int,
    val html: String,
    val kind: String,
    val property: String = "code",
    val annotation: String = "",
    val frame: Int,
    val startColumn: Int = -1,
    val endLine: Int = -1,
    val endColumn: Int = -1,
    val stack: List<OutputStackLocation> = emptyList(),
    val suggestions: List<OutputSuggestion> = emptyList(),
    /**
     * Set on an end-of-run `"ok"` when this run also reported an error, so the client keeps the
     * error markers instead of clearing them.
     *
     * A run can report an error and still finish successfully. `stack.auto` is the clearest case and
     * does it deliberately: a StackSL slot that fails to compile leaves the previous binding in place
     * and reports the diagnostic rather than throwing, so a half-typed program cannot take the frame
     * down. The Kotlin around it then completes, the run emits `"ok"`, and the client's `"ok"` branch
     * calls `clearErrors`, which removes *every* error output in the editor -- including the one
     * placed a few milliseconds earlier by this same run. The error is reported, rendered, and then
     * deleted by its own run's success, so the editor shows nothing at all.
     */
    val preserveErrors: Boolean = false
)

/**
 * A single actionable fix for a compile/runtime error, rendered in the error output area as a
 * row with two buttons: "Apply" (does the edit directly via Monaco executeEdits) and "Ask AI4"
 * (hands the fix to the assistant with a shaped prompt). See suggestionsFor() on the JVM side.
 *
 * [kind] discriminates how "Apply" behaves client-side:
 *   - "rename"   replace the offending token at [line]/[column] with [replacement]
 *   - "import"   insert `import <importFqn>`; if [replacement] is set, also rename the token
 *   - "wrap"     append a helper (e.g. `.d`/`.f`) to the offending value; best-effort
 *   - "generic"  no safe mechanical edit — "Apply" notifies; "Ask AI4" is the real path
 */
@Serializable
data class OutputSuggestion(
    val kind: String,
    val label: String,
    val detail: String = "",
    val token: String = "",
    val replacement: String = "",
    val importFqn: String = "",
    val line: Int = -1,
    val column: Int = -1,
    val aiInstruction: String = ""
)

@Serializable
data class OutputStackLocation(
    val uid: String,
    val property: String = "code",
    val line: Int,
    val method: String = "",
    val primary: Boolean = false
)

@Serializable
data class Execute(
    val uid: String,
    val type: String,
    val line: Int,
    val code: String,
    val singleLine: Boolean,
    val property: String = "code",
    val strand: String = "default",
    val requestId: String = ""
)

@Serializable
data class ExecuteBatch(
    val executions: List<Execute>
)

@Serializable
data class ExecuteSymbol(
    val uid: String,
    val symbol: String,
    val line: Int
)

@Serializable
data class AiAssist(
    val uid: String,
    val prompt: String,
    val extraPrompt: String = "",
    val startLine: Int = 0,
    val property: String = "code",
    val kind: String,
    val images: List<String> = emptyList()
)

@Serializable
data class AiChatScreenshotRequest(
    val uid: String,
    val target: String = ""
)

@Serializable
data class AiChatScreenshotResponse(
    val success: Boolean,
    val dataUrl: String = "",
    val message: String = "",
    val title: String = "",
    val width: Int = 0,
    val height: Int = 0
)

@Serializable
data class Chat_input(val value: String, val phase: Int, val images: List<String> = emptyList())

@Serializable
data class DocsSearch_input(val value: String, val phase: Int)

@Serializable
data class LocalEval(val uid: String, val prompt: String, val kind: String)

@Serializable
data class Stop_box(val uid: String)

@Serializable
data class InitialBoxFrame(val uid: String, val frame: Rect)

@Serializable
data class Expected_box_count(val count: Int, val frames: List<InitialBoxFrame> = emptyList())

@Serializable
data class DocumentLoadStatus(
    val phase: String,
    val message: String = "",
    val count: Int = -1,
    val completed: Int = -1,
    val terminal: Boolean = false
)

@Serializable
data class UserTheme(
    val css: String,
    val path: String,
    val lastModified: Long = 0L
)

@Serializable
data class IsRunning(
    val uid: String,
    val isRunning: Boolean,
    val watermark: Int,
    val strand: String = "default",
    val mode: String = "launch"
)

@Serializable
data class Metadata(
    val icon: String,
    val tail: String,
    val text: String,
    val displayText: String,
    val documentation: String = "",
    val runtimeDocumentationHtml: String = "",
    val prefix: String = "",
    val category: String = "",
    val generateDocumentation: Boolean = false,
    val documentationSourceName: String = "",
    val documentationSourcePath: String = "",
    val documentationSourceLine: Int = -1,
    val receiverDisplayName: String = "",
    val receiverDocumentation: String = "",
    val receiverDocumentationSourceName: String = "",
    val receiverDocumentationSourcePath: String = "",
    val receiverDocumentationSourceLine: Int = -1,
    /**
     * Client-side ranking boost added on top of fuzzy/category/recency scoring. Used to float
     * context-appropriate candidates to the top of the completion list — e.g. after an infix
     * function, the values whose type matches the operator's expected operand. 0 = no boost.
     */
    val contextScore: Int = 0,
    /**
     * `own` | `supertype` | `universal` | `""`: how close this candidate's DECLARATION is to the type of
     * the receiver it is being offered on (`AaCompletionClient.DECLARATION_PROXIMITY`).
     *
     * A separate field from [contextScore] rather than a negative boost, because `contextScore > 0` is
     * also the type-fit *filter* signal (`execution.kt`'s `typeFitFilterActive`) — folding a penalty into
     * it would make a candidate that both fits the expected type and is declared on `Any` disappear
     * instead of merely ranking lower. `fieldSuggest.js` turns this into its own ranking term.
     */
    val declarationProximity: String = "",
    /**
     * 1-based position of this candidate in its receiver type's `@CommonMembers` list, or 0 for a
     * candidate the type does not call out. Supplied by the server from the annotation on the receiver's
     * class; `fieldSuggest.js` turns it into a ranking term.
     *
     * This is a **prior**, and it exists because the popup had none. At an empty prefix (`f.`) there is
     * no text to fuzzy-match, so ranking falls to category weight plus the server's order — and the one
     * term that could lift the members a person actually reaches for is `recency`, which lives in
     * `localStorage`. That is zero for a new user, zero in a fresh browser profile, and zero in every
     * test, so the ordering a test can measure is not the ordering a long-time user sees. An annotation
     * in the source is visible to all four.
     *
     * Ranked rather than boolean so a type can say `moveTo` before `cubicTo` without the client
     * inventing an order. See `CommonMemberPrior`.
     */
    val commonMemberRank: Int = 0
)


@Serializable
data class Completion(
    val boundsStart: Int,
    val boundsEnd: Int,
    val columnsStart: Int = -1,
    val lineStart: Int = -1,
    val useLineColumns: Boolean = false,
    val metadata: List<Metadata>,
)

@Serializable
data class CompletionRequest(
    val uid: String,
    val line: Int,
    val allCode: String,
    val column: Int,
    val position: Int,
    val property: String = "code",
)

/** Asks what [line] (1-based) of a box's [property], holding [allCode], shows — see `afield.stdlib.LineNotes`. */
@Serializable
data class LineNoteRequest(
    val uid: String,
    val property: String,
    val allCode: String,
    val line: Int,
)

/**
 * What a line shows: a sentence, and a picture as a `data:` URL; both empty when there is nothing to say.
 * [volatile] when the note can change while the text does not (a last run's value), so the editor asks
 * again next time rather than keeping it.
 */
@Serializable
data class LineNoteReply(
    val uid: String,
    val property: String,
    val line: Int,
    val caption: String = "",
    val image: String = "",
    val volatile: Boolean = false,
    /** Where the value came from ("last run, 12 s ago"); with [value], the note split for the eye. Empty: show [caption]. */
    val meta: String = "",
    /** What the line came to, in runs; a `strong` run is a result the editor sets in bold. */
    val value: List<LineNoteSegment> = emptyList(),
    /** Device pixels per CSS pixel of [image]. */
    val imageScale: Int = 1,
)

/** One run of a line note's value: see [LineNoteReply.value]. */
@Serializable
data class LineNoteSegment(val text: String, val strong: Boolean = false)

/**
 * One completion exactly as Field's popup presented it. This is intentionally a client report,
 * rather than a copy of the server response: `fieldSuggest.js` merges quick, language, compiler,
 * and AI candidates, then applies fuzzy/category/context/recency ranking and grouping before the
 * user sees anything. [key] is stable within an interaction and lets an accepted outcome point
 * back to the corresponding row in a particular presentation revision.
 */
@Serializable
data class CompletionInteractionCandidate(
    val key: String,
    val source: String,
    val displayText: String,
    val insertText: String,
    val detail: String = "",
    val documentation: String = "",
    val category: String = "",
    val icon: String = "",
    val score: Double = 0.0,
    val contextScore: Int = 0,
    val declarationProximity: String = "",
    val prefix: String = ""
)

/**
 * Append-only completion-observability event emitted by the popup.
 *
 * `requested` carries the complete unsaved source and caret. `presented` carries the exact row
 * order visible in the popup; there may be several revisions as compiler and AI answers arrive.
 * `outcome` is terminal and records either the accepted row or why no row was selected. The JVM
 * enriches every event with the box's other completion-relevant properties and effective inherited
 * preamble before writing it to a dated JSONL file under
 * `~/.afield/logs/completion_interactions/`.
 */
@Serializable
data class CompletionInteractionEvent(
    val schemaVersion: Int = 1,
    val interactionId: String,
    val event: String,
    val uid: String,
    val property: String,
    val languageId: String = "",
    val allCode: String = "",
    val line: Int = -1,
    val column: Int = -1,
    val position: Int = -1,
    val manual: Boolean = false,
    val query: String = "",
    val memberContext: Boolean = false,
    val presentationRevision: Int = -1,
    val candidates: List<CompletionInteractionCandidate> = emptyList(),
    val outcome: String = "",
    val reason: String = "",
    val selectedIndex: Int = -1,
    val selected: CompletionInteractionCandidate? = null,
    val sourceAfter: String = "",
    val lineAfter: Int = -1,
    val columnAfter: Int = -1,
    val positionAfter: Int = -1
)

@Serializable
data class CompletionResolveRequest(
    val uid: String,
    val property: String,
    val label: String,
    val insertText: String,
    val detail: String,
    val displayText: String,
    val existingDocumentation: String = "",
    val allCode: String,
    val line: Int,
    val column: Int,
    val position: Int,
    val documentationSourceName: String = "",
    val documentationSourcePath: String = "",
    val documentationSourceLine: Int = -1
)

@Serializable
data class CompletionResolveResult(val documentation: String)

/**
 * Request to render a "source card" for a symbol the user clicked inside the
 * documentation panel (a KDoc `[Symbol]` link or the source-location line).
 * Either [symbol] or [path]+[line] identify the target; the backend resolves
 * whichever it can.
 */
@Serializable
data class SourceCardRequest(
    val symbol: String = "",
    val path: String = "",
    val line: Int = -1
)

/**
 * A resolved documentation card, shaped like a completion item's documentation
 * so the same panel renderer can display it. [documentation] is markdown with
 * `[Symbol]` cross-references already linkified, so navigation is recursive.
 */
@Serializable
data class SourceCardResult(
    val found: Boolean = false,
    val title: String = "",
    val documentation: String = "",
    val category: String = "",
    val sourceName: String = "",
    val sourcePath: String = "",
    val sourceLine: Int = -1
)

/**
 * A window of raw source text for the "view source" affordance — served over HTTP
 * (`GET /api/source`) so both the completion doc panel and the welcome-screen
 * semantic search can show the actual code behind a symbol or a search hit. Covers
 * project files under `./src` and classpath `-sources.jar` entries.
 */
@Serializable
data class SourceFileResponse(
    val found: Boolean = false,
    val path: String = "",
    val language: String = "",
    val startLine: Int = 0,
    val endLine: Int = 0,
    val code: String = ""
)

@Serializable
data class SymbolContextRequest(
    val uid: String,
    val line: Int,
    val allCode: String,
    val column: Int,
    val position: Int,
    val property: String = "code",
    val forceRefresh: Boolean = false,
)

@Serializable
data class SymbolContextResponse(
    val symbol: String = "",
    val html: String = "",
    val isLiveValue: Boolean = false
)

@Serializable
data class DebugProbeRequest(
    val requestId: String,
    val uid: String,
    val line: Int,
    val position: Int,
    val symbol: String,
    val allCode: String,
    val property: String = "code",
    val threadName: String? = null,
    val maxHits: Int = 16,
    val timeoutMs: Long = 5_000L
)

// Source-location probe: watch a symbol at a class/file + line anywhere in the
// loaded JVM (not tied to a box or a REPL snippet). At least one of className /
// sourceName must be set; `line` is the real 1-based source line.
@Serializable
data class DebugSourceProbeRequest(
    val requestId: String,
    val symbol: String,
    val line: Int,
    val className: String? = null,
    val sourceName: String? = null,
    val threadName: String? = null,
    val maxHits: Int = 16,
    val timeoutMs: Long = 5_000L
)

@Serializable
data class DebugProbeEvent(
    val requestId: String,
    val uid: String,
    val phase: String,
    val symbol: String,
    val line: Int,
    val html: String,
    val channel: String = "debug probe",
    val annotation: String = "",
    val inspectorUrl: String = "",
    val done: Boolean = false,
    val sequence: Int = 0,
    // Machine-readable mirror of the HTML card, for non-UI consumers (the MCP
    // bridge / agents). The editor renders `html`; agents read these instead.
    val error: String? = null,
    val samples: List<DebugProbeSampleData> = emptyList(),
    val scope: List<DebugProbeScopeData> = emptyList()
)

@Serializable
data class DebugProbeSampleData(
    val hitIndex: Int,
    val threadName: String,
    val declaringType: String,
    val method: String,
    val line: Int,
    val resolution: String,
    val valueKind: String,
    val valueType: String? = null,
    val valueText: String
)

@Serializable
data class DebugProbeScopeData(
    val name: String,
    val resolution: String,
    val type: String? = null,
    val preview: String = "",
    val method: String? = null,
    val line: Int = -1
)

@Serializable
data class Hello(
    val filename: String,
    val username: String,
    val sessionKey: String? = null,
    val scratch: Boolean = false,
    // Optional shared-UI channel: all sessions that pass the same non-blank context name receive
    // each other's selection changes and ephemeral UI intents (server-mediated). Lets an
    // editor-only dock and a canvas-only dock act as projections of one workspace. The field name
    // predates UI intents and is retained for compatibility.
    val shaderSelectionContext: String? = null
)

/**
 * Server → client, once per attach: whether anything this session does will be kept.
 *
 * The client used to have no way of knowing. `Hello.scratch` tells it about `ephemeral`, but
 * read-only is a different flag and `SessionInformation.readOnly`'s own KDoc says a read-only
 * session "looks normal to the client" — which is the bug, because Field has no explicit save and
 * an hour of work can be discarded with nothing ever having said so.
 *
 * Sent unconditionally rather than only when read-only, so the client can also *clear* the state:
 * a socket that reconnects onto a writable session must not keep a stale banner up.
 *
 * @param readOnly nothing this session writes will reach disk
 * @param reason short, user-facing, already in plain language — the client displays it as-is
 */
@Serializable
data class SessionWriteStatus(
    val readOnly: Boolean,
    val documentName: String = "",
    val reason: String = ""
)

@Serializable
data class MacroTranscriptCommandRequest(
    val command: String,
    val name: String = "",
    val label: String = "",
    val selection: List<String> = emptyList(),
    val scope: String = "document",
    val projectName: String = "",
    val content: String = "",
    val offset: Int = 0,
    val limit: Int = 80,
    val indices: List<Int> = emptyList(),
    val mode: String = ""
)

@Serializable
data class TranscriptEntry(
    val index: Int,
    val verb: String,
    val summary: String,
    val yaml: String,
    val box: String = "",
    val property: String = "",
    val from: String = "",
    val to: String = "",
    val frame: String = "",
    val at: String = "",
    val selectable: Boolean = true
)

@Serializable
data class MacroOutputLine(
    val uid: String = "",
    val kind: String = "normal",
    val line: Int = -1,
    val html: String = ""
)

@Serializable
data class MacroTranscriptCommandResponse(
    val success: Boolean,
    val message: String,
    val name: String = "",
    val path: String = "",
    val operationCount: Int = 0,
    val macros: List<String> = emptyList(),
    val content: String = "",
    val recording: Boolean = false,
    val draft: String = "",
    val selection: List<String> = emptyList(),
    val documentMacros: List<String> = emptyList(),
    val projectMacros: List<String> = emptyList(),
    val workspaceMacros: List<String> = emptyList(),
    val projectName: String = "",
    val scope: String = "document",
    val direction: String = "",
    val applied: Int = 0,
    val skipped: Int = 0,
    val errors: List<String> = emptyList(),
    val entries: List<TranscriptEntry> = emptyList(),
    val offset: Int = 0,
    val limit: Int = 0,
    val total: Int = 0,
    val hasMore: Boolean = false,
    val prompt: String = "",
    val output: List<MacroOutputLine> = emptyList(),
    val setupVerified: Boolean = true
)

@Serializable
data class AgentOpenDocument(
    val documentName: String? = null,
    val scratch: Boolean = false,
    val username: String = "agent",
    /**
     * Open the named document but never write back to it.
     *
     * Not the same as [scratch], which discards the name and synthesises a throwaway document
     * under `~/.agent-scratch/`. This opens the real thing, read-only, which is what inspecting a
     * demo committed to a repository needs.
     */
    val readOnly: Boolean = false,
    /**
     * Keep the named document running — its windows and running boxes — after this client
     * disconnects, instead of the cleanup a document gets when its last client leaves.
     *
     * For automation that spans several invocations (a recorded conversation: one invocation per
     * turn) against a document whose window must stay up between them. Ignored for scratch documents.
     */
    val keepRunning: Boolean = false
)

@Serializable
data class AgentDocumentSnapshot(
    val documentName: String,
    val scratch: Boolean,
    val sessionKey: String,
    val boxes: List<AgentBoxSnapshot>,
    /** Reported so a caller can check that `open --read-only` was honoured, rather than assume it. */
    val readOnly: Boolean = false,
    /**
     * Whether this session's document was already there when it was opened.
     *
     * Opening a name that does not exist is not an error -- it is how a document gets created --
     * but from the caller's side it is indistinguishable from a load that worked: both answer
     * `ok`, and the symptom, `boxes: []`, has three other causes. False for a scratch session,
     * which by construction never had anything to find.
     *
     * The server answers this because only the server can: it resolves the name against its own
     * process-global workspace, which the client's `--workspace` does not decide. The predicate
     * is `SavingContext.documentExistsOnDisk`, evaluated before the load -- the load creates the
     * directory, so afterwards nobody can tell.
     *
     * `@EncodeDefault` is load-bearing, not tidiness. `Message.payload` serialises with the
     * default `Json`, whose `encodeDefaults` is false, so a plain `= false` would be *omitted*
     * from the wire -- and the client reads an absent key as "this Field predates the field,
     * you have verified nothing". Without this annotation the alarming case and the
     * unanswerable case are the same bytes. Verified on a live Field, which is how the first
     * attempt was caught.
     */
    @EncodeDefault
    val documentExisted: Boolean = false,
    /**
     * Whether this open joined a document this Field already held in memory rather than reading
     * one from disk. Both mean the document existed; they differ in whose edits you are about to
     * share, which is what matters when attaching to a Field somebody else is using.
     */
    @EncodeDefault
    val attachedToLiveDocument: Boolean = false,
    val executionVerdicts: Boolean = false
)

@Serializable
data class AgentBoxSnapshot(
    val uid: String,
    val name: String,
    val frame: Rect,
    val runningStrands: Map<String, Int> = emptyMap(),
    val code: String? = null,
    val preamble: String? = null,
    val fragment: String? = null,
    val vertex: String? = null,
    val vertex_fragment: String? = null,
    val fragment_s: String? = null,
    val vertex_s: String? = null,
    val compute: String? = null,
    val compute_s: String? = null,
    val mesh: String? = null,
    val mesh_s: String? = null,
    val task: String? = null,
    val task_s: String? = null,
    val raygen: String? = null,
    val raygen_s: String? = null,
    val miss: String? = null,
    val miss_s: String? = null,
    val closesthit: String? = null,
    val closesthit_s: String? = null,
    val anyhit: String? = null,
    val anyhit_s: String? = null,
    val intersection: String? = null,
    val intersection_s: String? = null,
    val callable: String? = null,
    val callable_s: String? = null,
    val customization: String? = null,
    val metadata: String? = null,
    val notes: String? = null,
    val code_language: String? = null,
    /** The property the box edits and runs by default (`stackSL` for a StackSL program); `null` means `code`. */
    val default_property: String? = null,
    val autorun: Boolean = false,
    val layers: String? = null,
    val visible: Boolean = true,
    val offline: Boolean = false,
    val _tombstone: Boolean = false,
    val children: List<String> = emptyList(),
    val nested: List<String> = emptyList(),
    val nestedIn: String? = null
)

@Serializable
data class AgentCreateBox(
    val name: String = "Untitled",
    val frame: Rect = Rect(0.0, 0.0, 100.0, 100.0),
    val properties: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class CreateFileProxyBox(
    val mode: String,
    val path: String? = null,
    val fileName: String,
    val contentsBase64: String? = null,
    val mimeType: String? = null,
    val x: Double = 0.0,
    val y: Double = 0.0
)

@Serializable
data class CreateFileProxyBoxResponse(
    val ok: Boolean,
    val uid: String? = null,
    val message: String = "",
    val mode: String = "",
    val path: String = "",
    val defaultProperty: String = "contents"
)

@Serializable
data class AgentCreateBoxResponse(
    val documentName: String,
    val scratch: Boolean,
    val box: AgentBoxSnapshot
)

@Serializable
data class AgentWaitFramesRequest(
    val frames: Long = 0,
    val timeoutSec: Double = 20.0
)

/**
 * Provider-neutral ai4 tool surface over the backdoor. `agent-ai-tools-list`
 * returns the tools available for the attached document (name/description/inputSchema);
 * `agent-ai-tool-call` invokes one by name. Surfaced over MCP by tools/field_mcp.py.
 */
@Serializable
data class AgentAiToolsListResponse(
    val tools: JsonElement
)

@Serializable
data class AgentAiToolCallRequest(
    val name: String,
    val arguments: JsonElement? = null,
    val focusBox: String? = null
)

@Serializable
data class AgentAiToolCallResponse(
    val name: String,
    val ok: Boolean,
    val result: JsonElement? = null,
    val error: String? = null,
    val images: List<AgentToolImage> = emptyList()
)

@Serializable
data class AgentToolImage(val dataUrl: String, val caption: String = "")

/** Set a single string property on a box (e.g. `notes`). Direct, reliable setter. */
@Serializable
data class AgentSetPropertyRequest(
    val uid: String,
    val property: String,
    val value: String
)

@Serializable
data class AgentSetPropertyResponse(
    val ok: Boolean,
    val uid: String,
    val property: String,
    val message: String = ""
)

/** Read a string property that is not part of the compact document snapshot. */
@Serializable
data class AgentGetPropertyRequest(
    val uid: String,
    val property: String
)

@Serializable
data class AgentGetPropertyResponse(
    val ok: Boolean,
    val uid: String,
    val property: String,
    val value: String? = null,
    val message: String = ""
)

@Serializable
data class AgentWaitFramesResponse(
    val ok: Boolean,
    val requestedFrames: Long,
    val startFrame: Long,
    val endFrame: Long,
    val elapsedFrames: Long,
    val timedOut: Boolean = false,
    val message: String = ""
)

@Serializable
data class AgentGraphicsContextResponse(
    val ok: Boolean,
    val error: String? = null,
    val documentName: String,
    val scratch: Boolean,
    val box: AgentBoxSnapshot,
    val reused: Boolean = false
)

@Serializable
data class AgentOpenPlaywrightResponse(
    val ok: Boolean,
    val error: String? = null,
    val documentName: String,
    val scratch: Boolean,
    val sessionKey: String,
    val url: String,
    val createdBrowser: Boolean = false,
    val createdContext: Boolean = false,
    val createdPage: Boolean = false,
    val navigated: Boolean = false,
    val ready: Boolean = false
)

@Serializable
data class AgentOpenEditorRequest(
    val uid: String,
    val property: String = "code",
    val panIntoView: Boolean = true,
    val openOutputs: Boolean = false,
    val padding: Double = 12.0
)

@Serializable
data class AgentOpenEditorResponse(
    val ok: Boolean,
    val uid: String,
    val property: String,
    val documentName: String,
    val scratch: Boolean,
    val sessionKey: String,
    val message: String = ""
)

@Serializable
data class AgentPlaywrightInputRequest(
    val action: String,
    val key: String? = null,
    val selector: String? = null,
    val uid: String? = null,
    val dx: Double = 0.0,
    val dy: Double = 0.0,
    val hold: Boolean = false,
    val steps: Int = 12,
    val filename: String? = null,
    val script: String? = null,
    val scriptMode: String = "expression",
    val waitMs: Double = 0.0
)

@Serializable
data class AgentPlaywrightInputResponse(
    val ok: Boolean,
    val action: String,
    val key: String? = null,
    val uid: String? = null,
    val documentName: String,
    val scratch: Boolean,
    val sessionKey: String,
    val result: kotlinx.serialization.json.JsonElement? = null,
    val message: String = ""
)

@Serializable
data class AgentRunJvmMainRequest(
    val entrypoint: String,
    val args: List<String> = emptyList(),
    val timeoutSec: Double = 20.0
)

@Serializable
data class AgentRunJvmMainResponse(
    val ok: Boolean,
    val entrypoint: String,
    val args: List<String> = emptyList(),
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val durationMs: Long = 0,
    val timedOut: Boolean = false,
    val message: String? = null,
    val command: List<String> = emptyList()
)

@Serializable
data class Set_string(
    val uid: String,
    val property: String,
    val value: String,
    val syncSequence: Long? = null,
    val clientId: String? = null,
    val clientSequence: Long? = null
)

@Serializable
data class Set_output(val uid: String, val property: String, val channel: String, val value: String)

@Serializable
data class OutputControl(val uid: String, val action: String, val channel: String? = null)

@Serializable
data class ClearEditorOutputs(val uid: String)

@Serializable
data class ProvisionalEntry(val uid: String, val property: String, val contents: String)

@Serializable
data class ProvisionalSet(val entries: List<ProvisionalEntry>)

@Serializable
data class ProvisionalTarget(val uid: String, val property: String)

@Serializable
data class ProvisionalAcceptedValue(val uid: String, val property: String, val value: String)

@Serializable
data class Notify(val text: String, val category: String)

@Serializable
data class OscEditorCommand(
    val command: String,
    val address: String,
    val value: Double? = null,
    val text: String = "",
    val debounceMs: Int = 200,
    val maxExecutionMs: Int = 750,
    val delta: Boolean = false,
    val vector2: Boolean = false,
    val x: Double? = null,
    val y: Double? = null,
    val dx: Double? = null,
    val dy: Double? = null
)

@Serializable
data class Set_topology(val uid: String, val kind: String, val value: List<String>)

@Serializable
data class Action_Desc(
    val id: String,
    val description: String,
    val detail: String = "",
    val docHtml: String = "",
    val docJs: String = ""
)

@Serializable
data class Describe_actions(val uid: String, val value: List<Action_Desc>)

@Serializable
class Directory(
    val command: String,
    val value: List<String> = emptyList<String>(),
    val metadata: JsonElement? = null,
    val mouseX: Double? = null,
    val mouseY: Double? = null
)

@Serializable
data class TemplateMetadata(
    val name: String,
    val lastModified: Long,
    val boxDocumentation: String,
    val source: String = "template"
)

@Serializable
data class SaveAsTemplate(val uids: List<String>, val templateName: String)


@Serializable
class RequestHashtags(val boxes: List<String>, val getOrSet: Boolean, val value: String = "")


@Serializable
data class Perform_action(
    val uid_currentSelection: List<String>,
    val uid_action: String,
    val uid_property: String = "code"
)

@Serializable
data class PerformActionResult(
    val actionId: String,
    val found: Boolean,
    val success: Boolean,
    val selectedUids: List<String>,
    val availableActionIds: List<String> = emptyList(),
    val error: String = ""
)

@Serializable
data class CopyBoxes(val uids: List<String>)

@Serializable
data class CopyBoxesResult(val edn: String, val filename: String = "")

@Serializable
data class ExportDocumentResult(val edn: String, val filename: String = "")

@Serializable
data class PasteBoxes(val edn: String)

@Serializable
data class PasteBoxesResult(val newUids: List<String>)

@Serializable
data class ImportMetadata(val ednText: String)

/**
 * Payload for importing a dropped `metadata.edn` as a nested subtree: the backend
 * loads the EDN with fresh UUIDs (like [ImportMetadata]), then wraps the top-level
 * imported boxes inside a new nest-parent box anchored at document-space ([x], [y]).
 */
@Serializable
data class ImportMetadataNested(
    val ednText: String,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val nestName: String = "",
)

/**
 * Payload for importing a dropped Jupyter notebook (`.ipynb`). The backend parses
 * [notebookJson], creates one child box per cell under a fresh python-root parent,
 * and lays them out as a vertical column anchored at document-space ([x], [y]).
 */
@Serializable
data class ImportNotebook(
    val fileName: String,
    val notebookJson: String,
    val x: Double = 0.0,
    val y: Double = 0.0,
)

/**
 * Request to export a python-root box column back out to a Jupyter notebook
 * (`.ipynb`). [uid] identifies the python-root box (see [ImportNotebook] for the
 * inverse). The backend replies with an [ExportNotebookResult].
 */
@Serializable
data class ExportNotebook(val uid: String)

/**
 * Reply to an [ExportNotebook] request: [notebookJson] is a valid nbformat-4
 * notebook, [fileName] a suggested download name (no directory, may lack the
 * `.ipynb` suffix — the client appends it).
 */
@Serializable
data class ExportNotebookResult(val notebookJson: String, val fileName: String = "")

@Serializable
data class Set_rect(val uid: String, val property: String, val value: Rect)

@Serializable
data class Set_rects(val values: List<Set_rect>)

@Serializable
data class Set_null(val uid: String, val property: String)

@Serializable
data class Set_number(val uid: String, val property: String, val number: Double)

@Serializable
data class Set_boolean(val uid: String, val property: String, val number: Boolean)


/**
 * What a capture expects a box's output to look like, so readiness can be checked against a
 * stated expectation rather than guessed from a default nobody chose.
 *
 * The distinction exists because "open the outputs" and "there are outputs to open" are not the
 * same claim, and conflating them made every editor capture uncheckable: a box whose output is
 * inline chips in the gutter has no output *panel*, so a readiness rule that waits for one can
 * never be satisfied and can never be wrong either.
 */
object ExpectedOutputs {
    /** An output panel that must be revealed and must hold visible, settled content. */
    const val PANEL = "panel"

    /** Output rendered beside the code in the editor. There is no panel, and waiting for one hangs. */
    const val INLINE = "inline"

    /** Deliberately no output: a handle-only or empty-state capture, asserted rather than tolerated. */
    const val NONE = "none"

    val ALL = setOf(PANEL, INLINE, NONE)
}

@Serializable
data class BoxScreenshotRequest(
    val uid: String,
    val padding: Double = 12.0,
    val panIntoView: Boolean = true,
    val hideMonaco: Boolean = true,
    val openOutputs: Boolean = false,
    val preserveSelection: Boolean = false,
    val includeNotifications: Boolean = false,
    /** One of [ExpectedOutputs]; what this capture asserts the box's output is. */
    val expectOutputs: String = ExpectedOutputs.PANEL
)

@Serializable
data class BoxScreenshotResponse(
    val uid: String,
    val success: Boolean,
    val clip: Rect? = null,
    val viewport: Rect? = null,
    val message: String = ""
)

@Serializable
data class WindowScreenshotRequest(
    val uid: String? = null,
    val property: String = "code",
    val panIntoView: Boolean = true,
    val showEditor: Boolean = false,
    val openOutputs: Boolean = false,
    val editorOnly: Boolean = false,
    val padding: Double = 12.0,
    val waitMs: Double = 0.0,
    val preserveSelection: Boolean = false,
    val includeNotifications: Boolean = false,
    /** One of [ExpectedOutputs]; what this capture asserts the box's output is. */
    val expectOutputs: String = ExpectedOutputs.PANEL
)

@Serializable
data class WindowScreenshotResponse(
    val uid: String? = null,
    val success: Boolean,
    val clip: Rect? = null,
    val viewport: Rect? = null,
    val message: String = ""
)

@Serializable
data class AgentWindowScreenshot(
    val path: String,
    val uid: String? = null,
    val property: String = "code",
    val panIntoView: Boolean = true,
    val showEditor: Boolean = false,
    val openOutputs: Boolean = false,
    val editorOnly: Boolean = false,
    val padding: Double = 12.0,
    val waitMs: Double = 0.0,
    val preserveSelection: Boolean = false,
    val includeNotifications: Boolean = false,
    /** One of [ExpectedOutputs]; what this capture asserts the box's output is. */
    val expectOutputs: String = ExpectedOutputs.PANEL
)

@Serializable
data class AgentBoxScreenshot(
    val uid: String,
    val path: String,
    val padding: Double = 12.0,
    val panIntoView: Boolean = true,
    val openOutputs: Boolean = true,
    val preserveSelection: Boolean = false,
    val includeNotifications: Boolean = false,
    /** One of [ExpectedOutputs]; what this capture asserts the box's output is. */
    val expectOutputs: String = ExpectedOutputs.PANEL
)

@Serializable
data class AgentNativeWindowScreenshot(
    val path: String,
    val waitFrames: Int = 30,
    val captureTimeoutMs: Long = 15_000L,
    val uid: String? = null
)

@Serializable
data class AgentScreenshotResponse(
    val ok: Boolean,
    val kind: String,
    val path: String,
    val uid: String? = null,
    val documentName: String,
    val scratch: Boolean,
    val sessionKey: String,
    val clip: Rect? = null,
    val viewport: Rect? = null,
    val message: String = "",
    val terminal: Boolean = false
)

@Serializable
data class AgentWindowVideo(
    val path: String,
    val uid: String? = null,
    val property: String = "code",
    val panIntoView: Boolean = true,
    val showEditor: Boolean = false,
    val openOutputs: Boolean = false,
    val editorOnly: Boolean = false,
    val padding: Double = 12.0,
    val durationSec: Double = 5.0,
    val fps: Int = 30,
    val frameSynchronous: Boolean = false,
    val restartTarget: Boolean = false,
    /** One of [ExpectedOutputs]; what this capture asserts the box's output is. */
    val expectOutputs: String = ExpectedOutputs.PANEL
)

@Serializable
data class AgentBoxVideo(
    val uid: String,
    val path: String,
    val property: String = "code",
    val padding: Double = 12.0,
    val panIntoView: Boolean = true,
    val openOutputs: Boolean = true,
    val durationSec: Double = 5.0,
    val fps: Int = 30,
    val frameSynchronous: Boolean = false,
    val restartTarget: Boolean = false,
    /** One of [ExpectedOutputs]; what this capture asserts the box's output is. */
    val expectOutputs: String = ExpectedOutputs.PANEL
)

@Serializable
data class AgentVideoResponse(
    val ok: Boolean,
    val kind: String,
    val path: String,
    val uid: String? = null,
    val documentName: String,
    val scratch: Boolean,
    val sessionKey: String,
    val durationSec: Double = 0.0,
    val frameCount: Int = 0,
    val clip: Rect? = null,
    val viewport: Rect? = null,
    val message: String = ""
)


@Serializable
data class Rect(val x: Double, val y: Double, val w: Double, val h: Double) {
    fun intersects(r: Rect): Boolean {
        return ((r.x < this.x + this.w && r.w + r.x > x && r.y < y + this.h) && r.y + r.h > this.y)
    }

    fun intersects(x : Double) = this.x<=x && this.x+this.w>x

    fun intersects(x0 : Double, x1 : Double) = this.x<=x1 && this.x+this.w>x0

    fun union(r: Rect) = Rect(
        min(x, r.x),
        min(y, r.y),
        max(x + w, r.x + r.w) - min(x, r.x),
        max(y + h, r.y + r.h) - min(y, r.y)
    )

    val left: Double get() = x
    val top: Double get() = y
    val right: Double get() = x + w
    val bottom: Double get() = y + h
    val cx: Double get() = x + w / 2.0
    val cy: Double get() = y + h / 2.0
}

// Slideshow messages
@Serializable
data class SelectAndEditBox(
    val uid: String,
    val property: String = "code",
    val scrollToLine: Int? = null,
    val highlightLine: Int? = null,
    val center: Boolean = false,
    /** When positive, select columns [column] up to [endColumn] (1-based, end exclusive) of the highlighted line, and focus the editor. */
    val column: Int = -1,
    val endColumn: Int = -1,
)

@Serializable
data class ShowSlideNote(
    val html: String,
    val uid: String = "",
    val persistent: Boolean = false,
    val animated: Boolean = false,
    val annotations: List<SlideAnnotation> = emptyList(),
    val slideshowId: String = "",
    val slideNumber: Int = -1
)

@Serializable
data class HideSlideNote(val dummy: Boolean = true)

@Serializable
data class SlideShowCommand(val command: String)

/**
 * Draw one line from the note overlay as a whole to a box or editor line. [id] is only the stable
 * interpolation-state key; it is not a DOM element id inside the note.
 */
@Serializable
data class SlideAnnotation(
    val id: String,
    val targetUid: String,
    val targetProperty: String? = null,
    val targetLine: Int? = null,
    val color: String = "rgba(128, 255, 164, 0.7)",
    val strokeWidth: Double = 2.0,
    val dashed: Boolean = false
)
/**
 * One click on an interactive FLine, as the browser posts it.
 *
 * Every field past [callbackId] is defaulted on purpose. The server decodes this **strictly** (an
 * unknown key throws), so the JavaScript and this class have to name the same things — but the
 * defaults mean a browser running a *stale cached* `sliders.js`, which posts only the first five
 * fields, still arrives and still fires the callback with `kind = UNKNOWN`.
 *
 * `kind` is the lower-case spelling of an `FLineHitKind` (`point`/`edge`/`face`, or empty). The
 * client-side reconstruction is `__flineIntersectionFromSvg` in `src/commonMain/resources/sliders.js`;
 * `FLineClick.toIntersection()` is the one place this becomes an `FLineIntersection`.
 */
@Serializable
data class FLineClick(
    val callbackId: String,
    val closestVertexIndex: Int = -1,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val kind: String = "",
    val lineIndex: Int = -1,
    val label: String? = null,
    val nodeIndex: Int = -1,
    val subPathIndex: Int = -1,
    val elementIndex: Int = -1,
    /** `bilinear`, `bbox`, `none` or empty — which convention `u`/`v` follow for a face hit. */
    val faceCoordinates: String = "",
    /** `null` rather than `NaN`: JSON has no NaN, and a browser that sends one cannot be parsed. */
    val u: Double? = null,
    val v: Double? = null,
    val ex: Double? = null,
    val ey: Double? = null,
    val ez: Double? = null
)

@Serializable
enum class FLineHandleKind {
    TO, C1, C2
}

@Serializable
enum class FLineEditSpace {
    MODEL, VIEW
}

@Serializable
data class FLinePointRef(
    val lineIndex: Int,
    val nodeIndex: Int,
    val handle: FLineHandleKind
)

@Serializable
data class FLineTranslatePoints(
    val sessionId: String,
    val points: List<FLinePointRef>,
    val dx: Double,
    val dy: Double,
    val dz: Double = 0.0,
    val space: FLineEditSpace = FLineEditSpace.VIEW
)

@Serializable
data class FLinePoint3(
    val x: Double,
    val y: Double,
    val z: Double = 0.0
)

@Serializable
data class FLineDrawStyle(
    val color: FloatArray = floatArrayOf(0.92f, 0.96f, 1.0f, 0.95f),
    val thickness: Double = 2.0,
    val stroked: Boolean = true,
    val filled: Boolean = false,
    val pointed: Boolean = false,
    val pointSize: Double = 1.0
)

@Serializable
data class FLineAddLine(
    val sessionId: String,
    val points: List<FLinePoint3>,
    val closed: Boolean = false,
    val style: FLineDrawStyle? = null,
    val space: FLineEditSpace = FLineEditSpace.VIEW
)

@Serializable
data class FLineBeginLine(
    val sessionId: String,
    val lineId: String,
    val point: FLinePoint3,
    val style: FLineDrawStyle? = null,
    val space: FLineEditSpace = FLineEditSpace.VIEW
)

@Serializable
data class FLineAppendLine(
    val sessionId: String,
    val lineId: String,
    val points: List<FLinePoint3>,
    val space: FLineEditSpace = FLineEditSpace.VIEW
)

@Serializable
data class FLineEndLine(
    val sessionId: String,
    val lineId: String
)

@Serializable
data class FLineClearSession(
    val sessionId: String
)

// Control messages
@Serializable
data class SliderUpdate(val sliderId: String, val value: Double)

@Serializable
data class Vec2Update(val controlId: String, val x: Double, val y: Double)

@Serializable
data class CheckboxUpdate(val controlId: String, val value: Boolean)

@Serializable
data class StringUpdate(val controlId: String, val value: String)

@Serializable
data class ColorUpdate(val controlId: String, val r: Double, val g: Double, val b: Double, val a: Double)

@Serializable
data class FileUploadUpdate(val controlId: String, val files: List<FileUploadEntry>)

@Serializable
data class FileUploadEntry(val name: String, val size: Long, val type: String, val dataBase64: String)

@Serializable
data class ControllerSelectionState(
    val selectedUids: List<String> = emptyList(),
    val property: String = "code"
)

/**
 * A short-lived UI request shared between projections of the same document. Unlike document
 * state, these requests are deliberately not persisted: an editor-only dock can ask its paired
 * canvas dock to open a canvas-owned panel without either WKWebView knowing about the other.
 */
@Serializable
data class SharedUiIntent(
    val action: String,
    val filename: String = ""
)

@Serializable
data class ControllerControl(
    val id: String,
    val type: String,
    val label: String,
    val channel: String = "controls",
    val min: Double = 0.0,
    val max: Double = 1.0,
    val step: Double = 0.01,
    val value: Double? = null,
    val xMin: Double = -1.0,
    val xMax: Double = 1.0,
    val yMin: Double = -1.0,
    val yMax: Double = 1.0,
    val x: Double? = null,
    val y: Double? = null,
    val checked: Boolean? = null,
    val text: String? = null,
    val r: Double? = null,
    val g: Double? = null,
    val b: Double? = null,
    val a: Double? = null,
    val decimalPlaces: Int = 2,
    /**
     * How travel maps to value — `"linear"`, `"log"`, `"exp"` or `"bipolar"`, matching
     * `ControlLaw.wireName`.
     *
     * This is the field that makes a law *inherited* rather than bolted onto one surface: the desktop
     * output area reads it out of `data-law` in the emitted HTML, and the tablet reads it straight off
     * this record when it polls `/controllers/controls`. A browser running a stale cached
     * `controlLaws.js` sees an unknown spelling and falls back to linear — wrong, but not broken.
     */
    val law: String = "linear",
    /** Taper strength for the `"exp"` and `"bipolar"` laws. */
    val lawExponent: Double = 2.0,
    /** The value at mid-travel for the `"bipolar"` law; ignored by the others. */
    val lawCenter: Double = 0.0,
    /**
     * Digits of precision to show, or `0` to use [decimalPlaces] as before.
     *
     * A nonlinear control cannot have one right decimal count (see `controlLawDecimals`), so nonlinear
     * laws set this and linear ones leave it at `0` — which is what keeps every existing slider's
     * formatting identical to the digit.
     */
    val significantDigits: Int = 0
)

/** A momentary button going down or coming back up. See `ControlGroupBuilder.button`. */
@Serializable
data class ButtonUpdate(val controlId: String, val pressed: Boolean)

@Serializable
data class ControllerControlsResponse(
    val document: String = "",
    val selectedUid: String? = null,
    val selectedName: String = "",
    val selectedProperty: String = "code",
    val controls: List<ControllerControl> = emptyList()
)

// Layers system messages
@Serializable
data class SetVisibleLayers(val layers: List<String>, val animated: Boolean = false, val duration: Double = 300.0)

@Serializable
data class SetActiveLayer(val layer: String?, val animated: Boolean = false, val duration: Double = 500.0)

@Serializable
data class GetAllLayers(val dummy: Boolean = true)

@Serializable
data class AllLayersResult(val layers: List<String>)

// Animated rect change
@Serializable
data class Set_animated_rect(val uid: String, val property: String, val value: Rect, val duration: Double = 500.0)
// Chat messages for guide/slideshow system
@Serializable
data class PostChatMessage(
    val slideshowId: String,
    val slideNumber: Int,
    val username: String,
    val message: String
)

@Serializable
data class LoadChatMessages(
    val slideshowId: String,
    val slideNumber: Int
)

@Serializable
data class ChatMessage(
    val username: String,
    val message: String,
    val timestamp: String
)

@Serializable
data class ChatMessagesLoaded(
    val slideshowId: String,
    val slideNumber: Int,
    val messages: List<ChatMessage>
)

@Serializable
data class ChatMessagePosted(
    val slideshowId: String,
    val slideNumber: Int,
    val message: ChatMessage
)

// Documentation iframe control messages
@Serializable
data class ShowDocsIframe(val url: String? = null)

@Serializable
data class HideDocsIframe(val dummy: String = "")

@Serializable
data class SwitchToDocsIframe(val url: String? = null)

@Serializable
data class SwitchToMonacoEditor(val dummy: String = "")

// Document history messages
@Serializable
data class HistoryVersion(
    val index: Int,
    val filename: String,
    val timestamp: Long,
    val boxCount: Int,
    val byteSize: Long = 0,
    val boxIds: List<String> = emptyList(),
    val message: String = "",
    val diffStats: HistoryDiffStats? = null,
    val activityStats: HistoryActivityStats? = null
)

@Serializable
data class HistoryBoxActivity(
    val uid: String,
    val name: String = "",
    val executions: Int = 0,
    val launches: Int = 0,
    val executionDelta: Int = 0,
    val launchDelta: Int = 0
)

@Serializable
data class HistoryScreenshotRef(
    val channel: String,
    val path: String,
    val timestamp: Long = 0L,
    val width: Int = 0,
    val height: Int = 0
)

@Serializable
data class HistoryScreenshotAdded(
    val versionIndex: Int,
    val ref: HistoryScreenshotRef
)

@Serializable
data class HistoryActivityStats(
    val fieldLaunchId: String = "",
    val fieldLaunchStartedAt: Long = 0L,
    val executions: Int = 0,
    val launches: Int = 0,
    val executionDelta: Int = 0,
    val launchDelta: Int = 0,
    val editedBoxes: Int = 0,
    val movedBoxes: Int = 0,
    val perBox: List<HistoryBoxActivity> = emptyList(),
    val screenshots: List<HistoryScreenshotRef> = emptyList()
)

@Serializable
data class VersionTransition(
    val fromIndex: Int,
    val toIndex: Int,
    val timestamp: Long
)

@Serializable
data class HistoryBoxLineStats(
    val uid: String,
    @EncodeDefault
    val added: Int = 0,
    @EncodeDefault
    val removed: Int = 0,
    @EncodeDefault
    val moved: Int = 0,
    @EncodeDefault
    val changed: Int = 0
)

@Serializable
data class HistoryDiffStats(
    @EncodeDefault
    val boxesAdded: Int = 0,
    @EncodeDefault
    val boxesDeleted: Int = 0,
    @EncodeDefault
    val boxesEdited: Int = 0,
    @EncodeDefault
    val boxesMoved: Int = 0,
    @EncodeDefault
    val linesAddedFromNew: Int = 0,
    @EncodeDefault
    val linesRemovedFromDeleted: Int = 0,
    @EncodeDefault
    val linesAddedFromChanges: Int = 0,
    @EncodeDefault
    val linesRemovedFromChanges: Int = 0,
    @EncodeDefault
    val totalLinesAdded: Int = 0,
    @EncodeDefault
    val totalLinesRemoved: Int = 0,
    @EncodeDefault
    val totalLinesChanged: Int = 0,
    @EncodeDefault
    val changedCodeBoxes: List<String> = emptyList(),
    @EncodeDefault
    val changedBoxIds: List<String> = emptyList(),
    @EncodeDefault
    val boxLineStats: List<HistoryBoxLineStats> = emptyList()
)

@Serializable
data class HistoryVersionsList(
    val versions: List<HistoryVersion>,
    val transitions: List<VersionTransition> = emptyList()
)

@Serializable
data class HistoryChanged(
    val filename: String,
    val timestamp: Long,
    val versionIndex: Int = -1,
    val boxCount: Int = 0,
    val byteSize: Long = 0,
    val boxIds: List<String> = emptyList()
)

@Serializable
data class HistorySearchRequest(
    val query: String,
    val basis: String = "previous"
)

@Serializable
data class HistorySearchResult(
    val query: String,
    val basis: String = "previous",
    val versionIndexes: List<Int> = emptyList()
)

@Serializable
data class HistoryLineBlameRequest(
    val uid: String,
    val property: String,
    val lineNumber: Int,
    val lineText: String = "",
    val propertyText: String = ""
)

@Serializable
data class HistoryLineBlameResult(
    val uid: String,
    val property: String,
    val lineNumber: Int,
    val lineText: String = "",
    val versionIndexes: List<Int> = emptyList()
)

@Serializable
data class HistorySelectionVersionsRequest(
    val uid: String,
    val property: String,
    val startLine: Int,
    val endLine: Int,
    val startColumn: Int = 1,
    val endColumn: Int = 1,
    val emptySelection: Boolean = false,
    val selectedText: String = "",
    val propertyText: String = ""
)

@Serializable
data class HistorySelectionVersionsResult(
    val uid: String,
    val property: String,
    val startLine: Int,
    val endLine: Int,
    val versions: List<HistoryVersion> = emptyList()
)

@Serializable
data class HistoryPropertyVersionRequest(
    val uid: String,
    val property: String,
    val versionIndex: Int
)

@Serializable
data class HistoryPropertyVersionResult(
    val uid: String,
    val property: String,
    val versionIndex: Int,
    val version: HistoryVersion? = null,
    val value: String = ""
)

@Serializable
data class HistoryLineChurnRequest(
    val uid: String,
    val property: String,
    val propertyText: String = "",
    val maxVersions: Int = 160
)

@Serializable
data class HistoryLineChurn(
    val line: Int,
    val score: Double,
    val count: Int = 0
)

@Serializable
data class HistoryLineChurnResult(
    val uid: String,
    val property: String,
    val versionCount: Int = 0,
    val lines: List<HistoryLineChurn> = emptyList()
)

@Serializable
data class TranscriptLineActivityRequest(
    val uid: String,
    val property: String,
    val propertyText: String = "",
    val maxEntries: Int = 4000
)

@Serializable
data class TranscriptLineActivity(
    val line: Int,
    val score: Double,
    val count: Int = 0
)

@Serializable
data class TranscriptLineActivityResult(
    val uid: String,
    val property: String,
    val entryCount: Int = 0,
    val lines: List<TranscriptLineActivity> = emptyList()
)

@Serializable
data class HistoryVersionStatsProgress(
    val versionIndex: Int,
    val completed: Int,
    val total: Int,
    val diffStats: HistoryDiffStats? = null,
    val boxCount: Int? = null,
    val boxIds: List<String> = emptyList(),
    val done: Boolean = false
)

@Serializable
data class HistoryRailLineStatsRequest(
    val basis: String = "current",
    val boxIds: List<String> = emptyList(),
    val properties: List<String> = emptyList(),
    val versionIndexes: List<Int> = emptyList()
)

@Serializable
data class HistoryRailLineStatsProgress(
    val basis: String = "current",
    val versionIndex: Int,
    val completed: Int,
    val total: Int,
    val boxFilter: List<String> = emptyList(),
    val properties: List<String> = emptyList(),
    val lineStats: HistoryLineStats = HistoryLineStats(),
    val done: Boolean = false
)

@Serializable
data class HistoryScreenshotStoreRequest(
    val channel: String,
    val dataUrl: String,
    val versionIndex: Int = -1,
    val width: Int = 0,
    val height: Int = 0
)

@Serializable
data class NameHistoryVersion(val versionIndex: Int, val name: String)

@Serializable
data class RestoreVersion(val versionIndex: Int)

@Serializable
data class HistoryDiffRequest(
    val versionIndex: Int,
    val boxIds: List<String> = emptyList(),
    val properties: List<String> = emptyList(),
    val basis: String = "current"
)

@Serializable
data class HistoryLineStats(
    @EncodeDefault
    val added: Int = 0,
    @EncodeDefault
    val removed: Int = 0,
    @EncodeDefault
    val moved: Int = 0,
    @EncodeDefault
    val changed: Int = 0
)

@Serializable
data class HistoryPropertyChange(
    val property: String,
    val status: String,
    val kind: String,
    val currentPreview: String = "",
    val targetPreview: String = "",
    val currentText: String = "",
    val targetText: String = "",
    val lineStats: HistoryLineStats = HistoryLineStats()
)

@Serializable
data class HistoryBoxChange(
    val uid: String,
    val name: String,
    val status: String,
    val properties: List<HistoryPropertyChange> = emptyList(),
    val currentFrame: Rect? = null,
    val targetFrame: Rect? = null,
    val currentChildren: List<String> = emptyList(),
    val targetChildren: List<String> = emptyList(),
    val currentParents: List<String> = emptyList(),
    val targetParents: List<String> = emptyList(),
    val lineStats: HistoryLineStats = HistoryLineStats()
)

@Serializable
data class HistoryVersionDiff(
    val versionIndex: Int,
    val currentIndex: Int,
    val generatedAt: Long,
    val basis: String = "current",
    val baselineIndex: Int = -1,
    val boxFilter: List<String> = emptyList(),
    val properties: List<String> = emptyList(),
    val boxes: List<HistoryBoxChange> = emptyList(),
    val changedBoxCount: Int = 0,
    val propertyChangeCount: Int = 0,
    val lineStats: HistoryLineStats = HistoryLineStats()
)

@Serializable
data class HistoryCherryPickSelection(
    val uid: String,
    val properties: List<String> = emptyList()
)

@Serializable
data class CherryPickHistoryRequest(
    val versionIndex: Int,
    val selections: List<HistoryCherryPickSelection>,
    val includeTopology: Boolean = true
)

@Serializable
data class CherryPickHistoryResult(
    val versionIndex: Int,
    val boxesTouched: Int,
    val propertiesTouched: Int,
    val tombstonedBoxes: Int
)

@Serializable
data class ActorArc(
    val senderUid: String?,
    val targetUid: String,
    val handlerUid: String,
    val isReply: Boolean = false
)

// Custom marking menu messages
@Serializable
data class CustomMarkingRequest(val uid: String)

@Serializable
data class CustomMarkingEntry(val direction: String, val label: String, val callbackId: String, val radius: Double, val angle: Double)

@Serializable
data class CustomMarkingResponse(val uid: String, val entries: List<CustomMarkingEntry>)

@Serializable
data class CustomMarkingFire(val callbackId: String)

/** An awaited execution result, explicitly separate from running and GPU presentation. */
@Serializable
data class ExecutionReport(
    val uid: String, val property: String, val revision: String,
    val requestId: String = "", val mode: String = "launch",
    val stage: String = "setup-complete", val success: Boolean,
    val error: String = ""
)
