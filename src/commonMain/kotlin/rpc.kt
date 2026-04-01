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
    val endColumn: Int = -1
)

@Serializable
data class Execute(
    val uid: String,
    val type: String,
    val line: Int,
    val code: String,
    val singleLine: Boolean,
    val strand: String = "default"
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
data class Chat_input(val value: String, val phase: Int, val images: List<String> = emptyList())

@Serializable
data class DocsSearch_input(val value: String, val phase: Int)

@Serializable
data class LocalEval(val uid: String, val prompt: String, val kind: String)

@Serializable
data class Stop_box(val uid: String)

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
    val prefix: String = ""
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
    val position: Int
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
data class Hello(
    val filename: String,
    val username: String,
    val sessionKey: String? = null,
    val scratch: Boolean = false
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
    val compute: String? = null,
    val mesh: String? = null,
    val task: String? = null,
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
data class AgentCreateBoxResponse(
    val documentName: String,
    val scratch: Boolean,
    val box: AgentBoxSnapshot
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
    val uid: String? = null,
    val dx: Double = 0.0,
    val dy: Double = 0.0,
    val hold: Boolean = false,
    val steps: Int = 12,
    val filename: String? = null,
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
data class Set_string(val uid: String, val property: String, val value: String)

@Serializable
data class Set_output(val uid: String, val property: String, val channel: String, val value: String)

@Serializable
data class OutputControl(val uid: String, val action: String, val channel: String? = null)

@Serializable
data class ProvisionalEntry(val uid: String, val property: String, val contents: String)

@Serializable
data class ProvisionalSet(val entries: List<ProvisionalEntry>)

@Serializable
data class ProvisionalTarget(val uid: String, val property: String)

@Serializable
data class Notify(val text: String, val category: String)

@Serializable
data class Set_topology(val uid: String, val kind: String, val value: List<String>)

@Serializable
data class Action_Desc(val id: String, val description: String, val detail: String = "")

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
data class Perform_action(val uid_currentSelection: List<String>, val uid_action: String)

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
    val padding: Double = 12.0
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
    val padding: Double = 12.0
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
data class FLineClick(val callbackId: String)

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
    val message: String = "",
    val diffStats: HistoryDiffStats? = null
)

@Serializable
data class VersionTransition(
    val fromIndex: Int,
    val toIndex: Int,
    val timestamp: Long
)

@Serializable
data class HistoryDiffStats(
    @EncodeDefault
    val boxesAdded: Int = 0,
    @EncodeDefault
    val boxesDeleted: Int = 0,
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
    val totalLinesRemoved: Int = 0
)

@Serializable
data class HistoryVersionsList(
    val versions: List<HistoryVersion>,
    val transitions: List<VersionTransition> = emptyList()
)

@Serializable
data class NameHistoryVersion(val versionIndex: Int, val name: String)

@Serializable
data class RestoreVersion(val versionIndex: Int)
