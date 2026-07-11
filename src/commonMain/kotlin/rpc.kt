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
    val suggestions: List<OutputSuggestion> = emptyList()
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
    val strand: String = "default"
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
    val receiverDocumentationSourceLine: Int = -1
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
    val sequence: Int = 0
)

@Serializable
data class Hello(
    val filename: String,
    val username: String,
    val sessionKey: String? = null,
    val scratch: Boolean = false
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
    val output: List<MacroOutputLine> = emptyList()
)

@Serializable
data class AgentOpenDocument(
    val documentName: String? = null,
    val scratch: Boolean = false,
    val username: String = "agent"
)

@Serializable
data class AgentDocumentSnapshot(
    val documentName: String,
    val scratch: Boolean,
    val sessionKey: String,
    val boxes: List<AgentBoxSnapshot>
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
    val code_language: String? = null,
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


@Serializable
data class BoxScreenshotRequest(
    val uid: String,
    val padding: Double = 12.0,
    val panIntoView: Boolean = true,
    val hideMonaco: Boolean = true,
    val openOutputs: Boolean = false
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
    val waitMs: Double = 0.0
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
    val waitMs: Double = 0.0
)

@Serializable
data class AgentBoxScreenshot(
    val uid: String,
    val path: String,
    val padding: Double = 12.0,
    val panIntoView: Boolean = true,
    val openOutputs: Boolean = true
)

@Serializable
data class AgentNativeWindowScreenshot(
    val path: String,
    val waitFrames: Int = 30
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
    val message: String = ""
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
    val fps: Int = 30
)

@Serializable
data class AgentBoxVideo(
    val uid: String,
    val path: String,
    val padding: Double = 12.0,
    val panIntoView: Boolean = true,
    val openOutputs: Boolean = true,
    val durationSec: Double = 5.0,
    val fps: Int = 30
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
data class SelectAndEditBox(val uid: String, val property: String = "code", val scrollToLine: Int? = null, val highlightLine: Int? = null)

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

// Slide annotation for drawing lines from guide text to boxes/editor positions
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
@Serializable
data class FLineClick(
    val callbackId: String,
    val closestVertexIndex: Int = -1,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0
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
    val decimalPlaces: Int = 2
)

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
