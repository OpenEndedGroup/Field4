@file:OptIn(ExperimentalJsExport::class, ExperimentalUuidApi::class)

package afield

import CanvasGestures
import clipRay
import connectCenters
import getCenter
import doubleTap
import handleCustomizationRequest
import handleEvalRequest
import handleOutputAreaRequest
import handleOutputAreaControl
import clearOutputAreaContents
import teardownBoxOutputs
import kotlinext.js.asJsObject
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.css.*
import kotlinx.dom.hasClass
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import org.w3c.dom.*
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.svg.SVGElement
import org.w3c.dom.svg.SVGSVGElement
import org.w3c.dom.url.URLSearchParams
import registerDoubleTap
import tripleTap
import web.cssom.important
import web.timers.clearTimeout
import web.timers.setTimeout
import kotlin.js.Date
import kotlin.js.json
import kotlin.js.jsTypeOf
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

external fun interact(selector: String): dynamic
external fun interact(selector: HTMLElement): dynamic

external fun notify(text: String, category: String)

external fun setBoxRunning(uid: String, running: Boolean, watermark: Int, strand: String, mode: String)

external fun showMonaco(): dynamic
external fun hideMonaco(): dynamic
external fun showDocsIframe(): dynamic
external fun hideDocsIframe(): dynamic
external fun setDocsIframeContent(url: String?): dynamic
external fun switchToMonaco(): dynamic
external fun switchToDocsIframe(url: String?): dynamic
external fun switchToDefaultDocs(): dynamic
external fun initMenuSupport(): dynamic


external fun setDecorationsForEditor(uid: String, property: String, box: HTMLElement, decorSpec: dynamic)

external fun setActions(actions: dynamic): dynamic

external fun goMarkingMenu(x: Double, y: Double, menus: dynamic, event: dynamic, cancelCallback: dynamic): dynamic
external fun provideMissingMouseup(event: dynamic): dynamic

private const val AI4_AUTO_NAME_PROPERTY = "ai4_auto_name"

external object Provisional {
    fun clear()
    fun set(uid: String, property: String, contents: String)
}

/**
 * Tracks initial document loading progress. Backend load-status messages
 * keep the UI in a loading state while metadata and box initialization are
 * still being queued, and expected box counts keep the client from declaring
 * success before the first box frame has arrived.
 *
 * Tracking begins automatically on backend status, expected counts, or the
 * first [boxAdded] call. [start] only provides a watchdog for unusual cases.
 */
object LoadingTracker {
    private var expectedBoxes = -1   // -1 = server hasn't told us yet
    private val arrivedBoxIds = mutableSetOf<String>()
    private val previewedBoxIds = mutableSetOf<String>()
    private val createdBoxIds = mutableSetOf<String>()
    private val onlineBoxIds = mutableSetOf<String>()
    private var timeoutHandle: Int? = null
    private var finished = false
    private var backendLoading = true
    private var backendCompleted = -1
    private var sawBackendStatus = false
    private var statusText = "loading document"
    private var progressElement: HTMLElement? = null
    private val startedAt = window.performance.now()

    private fun diagnosticSnapshot(reason: String): dynamic = json(
        "reason" to reason,
        "elapsedMs" to (window.performance.now() - startedAt).roundToInt(),
        "expectedBoxes" to expectedBoxes,
        "previewedBoxes" to previewedBoxIds.size,
        "createdBoxes" to createdBoxIds.size,
        "arrivedBoxes" to arrivedBoxIds.size,
        "onlineBoxes" to onlineBoxIds.size,
        "backendCompleted" to backendCompleted,
        "backendLoading" to backendLoading,
        "sawBackendStatus" to sawBackendStatus,
        "finished" to finished,
        "statusText" to statusText,
        "previewedBoxSample" to previewedBoxIds.take(24).toTypedArray(),
        "createdBoxSample" to createdBoxIds.take(24).toTypedArray(),
        "onlineBoxSample" to onlineBoxIds.take(24).toTypedArray()
    )

    private fun publishDiagnostic(reason: String, details: dynamic = null) {
        val snapshot = diagnosticSnapshot(reason)
        snapshot.details = details
        window.asDynamic().fieldLoadingDiagnosticState = snapshot
        fieldDiagnostic("document-loading", snapshot)
    }

    /**
     * The server tells us how many non-tombstoned boxes to expect.
     * If count is 0, finish immediately (empty document).
     */
    fun setExpectedCount(count: Int) {
        if (finished) return
        expectedBoxes = count
        publishDiagnostic("expected-count", json("count" to count))
        if (isComplete()) {
            finish()
        } else {
            updateNotification()
            resetTimeout()
        }
    }

    fun boxFramesPreviewed(uids: List<String>) {
        if (finished) return
        previewedBoxIds.addAll(uids)
        arrivedBoxIds.addAll(uids)
        publishDiagnostic(
            "frames-previewed",
            json(
                "count" to uids.size,
                "uids" to uids.take(50).toTypedArray()
            )
        )
        if (isComplete()) {
            finish()
        } else {
            updateNotification()
            resetTimeout()
        }
    }

    /**
     * Kick off the watchdog. The watchdog refreshes the loading notice while
     * the backend is still active or while expected box frames are missing.
     */
    fun start() {
        if (finished) return
        publishDiagnostic("watchdog-started")
        resetTimeout()
    }

    fun documentLoadStatus(status: DocumentLoadStatus) {
        if (finished) return
        sawBackendStatus = true
        backendLoading = !status.terminal
        if (status.count >= 0) expectedBoxes = status.count
        if (status.completed >= 0) backendCompleted = status.completed
        statusText = when {
            status.message.isNotBlank() -> status.message
            status.phase.isNotBlank() -> status.phase.replace("-", " ")
            else -> "loading document"
        }
        publishDiagnostic(
            "backend-status",
            json(
                "phase" to status.phase,
                "message" to status.message,
                "count" to status.count,
                "completed" to status.completed,
                "terminal" to status.terminal
            )
        )
        if (isComplete()) {
            finish()
        } else {
            updateNotification()
            resetTimeout()
        }
    }

    /** A new box arrived from the server (set-rect). */
    fun boxAdded(uid: String) {
        if (finished) return
        createdBoxIds.add(uid)
        arrivedBoxIds.add(uid)
        publishDiagnostic("box-created", json("uid" to uid))
        updateNotification()
        resetTimeout()
    }

    /** A box transitioned from offline to online. */
    fun boxOnline(uid: String) {
        if (finished) return
        arrivedBoxIds.add(uid)
        onlineBoxIds.add(uid)
        publishDiagnostic("box-online", json("uid" to uid))
        if (isComplete()) {
            finish()
        } else {
            updateNotification()
            resetTimeout()
        }
    }

    private fun isComplete(): Boolean {
        if (expectedBoxes == 0) return true
        if (expectedBoxes > 0 && maxOf(onlineBoxIds.size, backendCompleted) >= expectedBoxes) return true
        if (expectedBoxes > 0 && arrivedBoxIds.size >= expectedBoxes && sawBackendStatus && !backendLoading) return true
        return false
    }

    private fun updateNotification() {
        val total = maxOf(if (expectedBoxes > 0) expectedBoxes else 0, arrivedBoxIds.size, onlineBoxIds.size)
        val received = arrivedBoxIds.size
        val online = maxOf(onlineBoxIds.size, backendCompleted)
        val detail = if (total > 0) "$statusText; $received/$total received, $online/$total online" else statusText
        updateStartupProgress(total, received, online, detail)
        if (detail == "loading document") {
            notify("loading document <<spinner>>", "setup")
        } else {
            notify("loading document \u2014 $detail <<spinner>>", "setup")
        }
    }

    private fun ensureStartupProgress(): HTMLElement {
        progressElement?.let { return it }
        document.body?.classList?.add("field-loading")
        val root = document.createElement("div") as HTMLElement
        root.classList.add("field-startup-progress")
        root.innerHTML = """
            <div class="field-startup-progress-title">Loading document</div>
            <div class="field-startup-progress-detail"></div>
            <div class="field-startup-progress-track"><div class="field-startup-progress-bar"></div></div>
        """.trimIndent()
        document.body?.appendChild(root)
        progressElement = root
        return root
    }

    private fun updateStartupProgress(total: Int, received: Int, online: Int, detail: String) {
        val root = ensureStartupProgress()
        val title = root.querySelector(".field-startup-progress-title") as? HTMLElement
        val detailNode = root.querySelector(".field-startup-progress-detail") as? HTMLElement
        val bar = root.querySelector(".field-startup-progress-bar") as? HTMLElement
        title?.textContent = statusText.replaceFirstChar { it.uppercase() }
        detailNode?.textContent = if (total > 0) "$received/$total received, $online/$total online" else detail
        val done = if (total > 0) maxOf(received, online).coerceAtMost(total).toDouble() / total.toDouble() else 0.0
        bar?.style?.width = "${(done * 100.0).roundToInt()}%"
    }

    private fun resetTimeout() {
        timeoutHandle?.let { kotlinx.browser.window.clearTimeout(it) }
        timeoutHandle = kotlinx.browser.window.setTimeout({ handleIdleTimeout() }, 4000)
    }

    private fun handleIdleTimeout() {
        if (finished) return
        if (isComplete()) {
            publishDiagnostic("watchdog-complete")
            finish()
            return
        }
        if (backendLoading) {
            publishDiagnostic("watchdog-backend-still-loading")
            updateNotification()
            resetTimeout()
            return
        }
        if (expectedBoxes > 0 && arrivedBoxIds.size < expectedBoxes) {
            publishDiagnostic("watchdog-missing-box-frames")
            updateNotification()
            resetTimeout()
            return
        }
        if (!sawBackendStatus && expectedBoxes < 0 && arrivedBoxIds.isEmpty()) {
            publishDiagnostic("watchdog-no-backend-messages")
            updateNotification()
            resetTimeout()
            return
        }
        publishDiagnostic("watchdog-fallback-finish")
        finish()
    }

    private fun finish() {
        if (finished) return
        finished = true
        publishDiagnostic("finished")
        timeoutHandle?.let { kotlinx.browser.window.clearTimeout(it) }
        timeoutHandle = null
        progressElement?.let { element ->
            element.classList.add("field-startup-progress-done")
            window.setTimeout({
                element.remove()
                if (progressElement == element) progressElement = null
                document.body?.classList?.remove("field-loading")
            }, 450)
        }
        notify("finished loading \u2014 press <b>?</b> for help", "setup")
        updateEmptyDocumentWelcome()
        send(Message("replay-output-areas"))
    }

    fun isFinished(): Boolean = finished
}

lateinit var mainViewport: HTMLElement
lateinit var mainCanvas: HTMLElement
lateinit var overlaySVGLayer: SVGSVGElement
lateinit var connectionSplineLayer: SVGSVGElement
external val monaco: dynamic

external val allActions: dynamic

external var originatingMouseDown: MouseEvent

lateinit var moveHandler: (dynamic) -> Set_rect?
lateinit var resizeHandler: (dynamic) -> Unit
lateinit var monacoResizeHandler: (dynamic) -> Unit
var mainTranslation = listOf(0.0, 0.0)

/**
 * The canvas zoom. The camera is `screen = world * mainZoom + mainTranslation`: translation stays
 * in screen pixels (so every pan delta, centring calculation and `set-viewport` is unchanged at
 * zoom 1) and the scale is applied about the canvas origin (`transform-origin: 0 0`).
 * Anything that turns a screen measurement into a world one has to divide by it.
 */
var mainZoom = 1.0
    private set

const val MIN_CANVAS_ZOOM = 0.1
const val MAX_CANVAS_ZOOM = 4.0

fun mainCanvasTransform(x: Double, y: Double): String = "translate(${x}px, ${y}px) scale($mainZoom)"

/** The viewBox for a window-sized SVG layer drawn in world coordinates. */
fun worldViewBox(width: Double, height: Double): String =
    "${-mainTranslation[0] / mainZoom} ${-mainTranslation[1] / mainZoom} ${width / mainZoom} ${height / mainZoom}"

private const val dotSpacingSmall = 25.0
private const val dotSpacingLarge = 100.0
private const val dotAlphaSmallIdle = 0.02
private const val dotAlphaLargeIdle = 0.04
private const val dotAlphaSmallActive = 0.16
private const val dotAlphaLargeActive = 0.24
private const val axisAlphaIdle = 0.0
private const val axisAlphaActive = 0.12
private const val monacoMinWidthPx = 420.0
private const val monacoViewportGutterPx = 10.0
private const val monacoMinHeightPx = 120.0
private const val editorLayoutStorageKey = "afield.editorOverlayLayouts.v1"
private const val editorLayoutStorageMaxEntries = 80

private var dotAlphaSmall = dotAlphaSmallIdle
private var dotAlphaLarge = dotAlphaLargeIdle
private var dotAlphaSmallTarget = dotAlphaSmallIdle
private var dotAlphaLargeTarget = dotAlphaLargeIdle
private var axisAlpha = axisAlphaIdle
private var axisAlphaTarget = axisAlphaIdle
private var backgroundFadeTimeoutHandle: Int? = null
private var backgroundLerpHandle: Int? = null

private var editorOpacity = 1.0
private var editorOpacityTarget = 1.0
private const val editorOpacityPanning = 0.5
private const val editorOpacityIdle = 1.0
private var navigationPanAnimationHandle: Int? = null
private var navigationPanAnimationGeneration = 0
private var emptyDocumentWelcome: HTMLElement? = null

private fun liveDocumentBoxCount(): Int {
    val boxes = document.body?.getElementsByClassName("box")?.asList().orEmpty()
    return boxes.count { element ->
        val box = element as? HTMLElement ?: return@count false
        val uid = (box.asDynamic().uid as? String)
            ?: box.getAttribute("uid")
            ?: box.getAttribute("data-uid")
        uid != "root" && !box.classList.contains("tombstoned") && box.asDynamic().tombstoned != true
    }
}

private fun ensureEmptyDocumentWelcome(): HTMLElement? {
    emptyDocumentWelcome?.let { return it }
    if (!::mainViewport.isInitialized) return null

    val root = document.createElement("div") as HTMLElement
    root.classList.add("field-empty-document-welcome")
    root.style.cssText = """
        position: fixed;
        left: 50%;
        top: 50%;
        transform: translate(-50%, -50%);
        z-index: 8;
        pointer-events: none;
        text-align: center;
        color: rgba(255, 255, 255, 0.88);
        padding: 24px 28px;
        border-radius: 8px;
        background: rgba(18, 20, 24, 0.42);
        backdrop-filter: blur(10px);
        opacity: 0;
        transition: opacity 180ms ease, transform 180ms ease;
        user-select: none;
        display: none;
    """.trimIndent()
    root.innerHTML = """
        <div style="font-size:28px;font-weight:650;line-height:1.15;margin-bottom:8px;">Welcome to Field</div>
        <div style="font-size:15px;line-height:1.45;color:rgba(255,255,255,0.72);">Press <b style="color:rgba(255,255,255,0.94);">n</b> to make a new box</div>
    """.trimIndent()
    mainViewport.appendChild(root)
    emptyDocumentWelcome = root
    return root
}

internal fun updateEmptyDocumentWelcome() {
    val welcome = ensureEmptyDocumentWelcome() ?: return
    val show = LoadingTracker.isFinished() && liveDocumentBoxCount() == 0
    if (show) {
        welcome.style.display = "block"
        window.requestAnimationFrame {
            welcome.style.opacity = "1"
            welcome.style.transform = "translate(-50%, -50%)"
        }
    } else {
        welcome.style.opacity = "0"
        welcome.style.transform = "translate(-50%, calc(-50% + 6px))"
        window.setTimeout({
            if (!LoadingTracker.isFinished() || liveDocumentBoxCount() != 0) {
                welcome.style.display = "none"
            }
        }, 200)
    }
}

@Serializable
private data class EditorOverlayLayoutRecord(
    val key: String,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double
)

@Serializable
private data class EditorOverlayLayoutStore(
    val entries: List<EditorOverlayLayoutRecord> = emptyList()
)

private var editorOverlayLayoutRecords: MutableList<EditorOverlayLayoutRecord>? = null

private fun editorOverlayViewportKey(): String =
    "${window.innerWidth.toDouble().roundToInt()}x${window.innerHeight.toDouble().roundToInt()}"

private fun EditorOverlayLayoutRecord.isUsable(): Boolean =
    x.isFinite() && y.isFinite() && w.isFinite() && h.isFinite() && w > 0.0 && h > 0.0

private fun readEditorOverlayLayoutRecords(): MutableList<EditorOverlayLayoutRecord> {
    editorOverlayLayoutRecords?.let { return it }
    val loaded = runCatching {
        val raw = window.localStorage.getItem(editorLayoutStorageKey)
        if (raw.isNullOrBlank()) {
            mutableListOf()
        } else {
            Json.decodeFromString<EditorOverlayLayoutStore>(raw)
                .entries
                .filter { it.isUsable() }
                .takeLast(editorLayoutStorageMaxEntries)
                .toMutableList()
        }
    }.getOrElse {
        mutableListOf()
    }
    editorOverlayLayoutRecords = loaded
    return loaded
}

private fun saveEditorOverlayLayoutRecords(records: List<EditorOverlayLayoutRecord>) {
    runCatching {
        window.localStorage.setItem(
            editorLayoutStorageKey,
            Json.encodeToString(EditorOverlayLayoutStore(records.takeLast(editorLayoutStorageMaxEntries)))
        )
    }
}

private fun findEditorOverlayLayoutRecord(key: String): EditorOverlayLayoutRecord? =
    readEditorOverlayLayoutRecords().lastOrNull { it.key == key && it.isUsable() }

private fun rememberEditorOverlayLayout(record: EditorOverlayLayoutRecord) {
    if (!record.isUsable()) return
    val records = readEditorOverlayLayoutRecords()
    val next = records.filterNot { it.key == record.key }.toMutableList()
    next.add(record)
    while (next.size > editorLayoutStorageMaxEntries) next.removeAt(0)
    records.clear()
    records.addAll(next)
    saveEditorOverlayLayoutRecords(records)
}

private fun editorOverlayLayoutFromElement(key: String, el: HTMLElement): EditorOverlayLayoutRecord {
    val rect = el.getBoundingClientRect()
    return EditorOverlayLayoutRecord(
        key = key,
        x = rect.left + window.scrollX,
        y = rect.top + window.scrollY,
        w = rect.width,
        h = rect.height
    )
}

private fun clampEditorOverlayLayout(layout: EditorOverlayLayoutRecord): Rect {
    val inset = monacoViewportGutterPx
    val availableWidth = max(1.0, window.innerWidth.toDouble() - inset * 2.0)
    val availableHeight = max(1.0, window.innerHeight.toDouble() - inset * 2.0)
    val minWidth = min(monacoMinWidthPx, availableWidth)
    val minHeight = min(monacoMinHeightPx, availableHeight)
    val width = min(max(layout.w, minWidth), availableWidth)
    val height = min(max(layout.h, minHeight), availableHeight)

    val minX = window.scrollX + inset
    val minY = window.scrollY + inset
    val maxX = max(minX, window.scrollX + window.innerWidth.toDouble() - inset - width)
    val maxY = max(minY, window.scrollY + window.innerHeight.toDouble() - inset - height)
    val x = min(max(layout.x, minX), maxX)
    val y = min(max(layout.y, minY), maxY)

    return Rect(x, y, width, height)
}

private fun applyEditorOverlayLayout(el: HTMLElement, layout: EditorOverlayLayoutRecord): Rect {
    val rect = clampEditorOverlayLayout(layout)
    val dyn = el.asDynamic()
    el.style.transform = "translate(0px, 0px)"
    el.style.left = "${rect.x}px"
    el.style.top = "${rect.y}px"
    el.style.right = "auto"
    el.style.bottom = "auto"
    el.style.position = "absolute"
    el.style.width = "${rect.w}px"
    el.style.height = "${rect.h}px"
    dyn.data_x = rect.x
    dyn.data_y = rect.y
    dyn.data_w = rect.w
    dyn.data_h = rect.h
    dyn.data_right_margin = max(0.0, window.scrollX + window.innerWidth.toDouble() - rect.right)
    dyn.data_initialized = true
    return rect
}

@JsExport
@JsName("launchNumber")
var launchNumber = 0

external fun launchAll(uid: String, code: String)
external fun continueAll(uid: String, code: String)

private fun breadthFirstExecutionOrder(root: HTMLElement): List<HTMLElement> {
    val ordered = mutableListOf<HTMLElement>()
    val seen = mutableSetOf<String>()
    val queue = mutableListOf(root)

    fun uidFor(box: HTMLElement): String? = box.asDynamic().uid as? String

    var index = 0
    while (index < queue.size) {
        val box = queue[index++]
        val uid = uidFor(box) ?: continue
        if (!seen.add(uid)) continue
        ordered.add(box)

        val children = box.asDynamic().box_children as? Array<String> ?: emptyArray()
        children.forEach { childUid ->
            val child = findBoxElementByUid(childUid)
            if (child != null) queue.add(child)
        }
    }

    return ordered
}

var canvasGestures = CanvasGestures(launcher = {
    if (it.size == 1) {
        launchAll(it[0].unsafeCast<dynamic>().uid as String, it[0].unsafeCast<dynamic>().code as String)
    } else if (it.isNotEmpty()) {
        val items = it.map { box ->
            val payload = js("{}")
            payload.uid = box.unsafeCast<dynamic>().uid as String
            payload.type = "launch"
            payload.line = 0
            payload.code = box.unsafeCast<dynamic>().code as String
            payload.singleLine = false
            payload.property = "code"
            payload.strand = "default"
            payload
        }.toTypedArray()
        monacoLaunchBatch(items)
    }
}, executionExpansionProvider = { box, breadthFirst ->
    if (breadthFirst) breadthFirstExecutionOrder(box) else listOf(box)
}).also {
    val w = window.asDynamic()
    w.canvasGestures = it
    w.rebuildConnectionGroup = { translationOnly: dynamic ->
        it.rebuildConnectionGroup((translationOnly as? Boolean) ?: false)
    }
    w.requestConnectionGroupRebuild = { translationOnly: dynamic ->
        it.requestConnectionGroupRebuild((translationOnly as? Boolean) ?: false)
    }
}

var selected = mutableSetOf<HTMLElement>()
var selectedList = eval("[]")
var selectedProperty = "code"

// Display mode from ?editorOnly / ?noeditor: "editor-only" | "no-editor" | "normal".
// Also mirrored onto window.fieldDisplayMode for the Monaco selection monitor to read.
internal var fieldDisplayMode: String = "normal"

// The server-mediated context shared by separate visionOS WKWebView projections. The URL/RPC
// name is retained for compatibility, but the context now carries ephemeral UI intents as well
// as selection.
internal var sharedUiContext: String? = null

// A boolean URL flag that may appear bare (?editorOnly) or valued (?editorOnly=1|true).
private fun flagParam(params: URLSearchParams, name: String): Boolean {
    val v = params.get(name) ?: return false
    return v.isEmpty() || v == "1" || v == "true"
}

// True while applying a selection received from a shared-selection peer, so the resulting
// selectBox/deselectBox churn doesn't echo the selection straight back out to the server.
private var applyingRemoteSelection = false
private const val editorPropertyStickinessStorageKey = "field4.editorPropertyStickiness"
private const val editorPropertyPerBoxStoragePrefix = "field4.editorPropertyPerBox."
private var explicitPropertySelectionInProgress = false

private val monacoRemoteTextProperties = monacoPropertyNames
    .filterNot { it == "code" || it == "contents" }
    .toSet()

var onSelectedChange = mutableMapOf<String, () -> Boolean>()

var events = HashMap<String, () -> Boolean>()

private enum class CardinalDirection(val label: String) {
    NORTH("↑"),
    SOUTH("↓"),
    EAST("→"),
    WEST("←")
}

private var spatialNavActive = false
private var spatialNavKey: String? = null
private var spatialNavAnchor: HTMLElement? = null
private var spatialNavTargets = mutableMapOf<CardinalDirection, HTMLElement>()
private var metaPressed = false
private var ctrlPressed = false
private var lastControllerSelectionSignature = ""

// Prevent duplicate Alt-click launches (e.g., nested boxes receiving duplicate events)
var lastAltDownUid: String? = null
var lastAltDownStamp = 0.0
var lastShiftDownUid: String? = null
var lastShiftDownStamp = 0.0
var pendingShiftToggleBox: HTMLElement? = null
var pendingShiftToggleDownX = 0.0
var pendingShiftToggleDownY = 0.0
var pendingShiftToggleHadDrag = false
private const val shiftToggleClickSlopPx = 4.0

// Layers system state
var visibleLayers = mutableSetOf("default")
var activeLayer: String? = null

private fun isLayerDebugEnabled(): Boolean {
    val raw = window.asDynamic().layerDebug
    return !(raw == false || raw == "false" || raw == "0")
}

fun layerDebugLog(event: String, payload: dynamic = null) {
    if (!isLayerDebugEnabled()) return
    if (payload == null || payload == js("undefined")) {
        console.info("[layers] $event")
    } else {
        console.info("[layers] $event", payload)
    }
}

fun selectionContextChanged() {
    js("window.dispatchEvent(new CustomEvent('field-selection-changed'))")
    syncControllerSelection()
    if (currentAnnotations.isNotEmpty()) {
        redrawSlideAnnotations()
        scheduleSlideAnnotationRedraw(10)
    }
    if (spatialNavActive) {
        refreshSpatialNavTargets()
    }
}

private fun syncControllerSelection() {
    if (applyingRemoteSelection) return
    val selectedIds = selected.mapNotNull { it.asDynamic().uid as? String }
    val signature = selectedIds.joinToString("\u0000") + "\u0001" + selectedProperty
    if (signature == lastControllerSelectionSignature) return
    lastControllerSelectionSignature = signature
    send(Message("controller-selection").payload(ControllerSelectionState(selectedIds, selectedProperty)))
}

// Apply a selection pushed from a shared-selection peer (?shaderSelectionContext). Drives the
// same selectBox/deselectBox path a local click would, so the editor + badges update normally,
// but suppresses the outbound echo and only touches boxes that exist in this document.
private fun applyRemoteSelection(state: ControllerSelectionState) {
    val targetUids = state.selectedUids.toSet()
    applyingRemoteSelection = true
    try {
        if (state.property.isNotBlank()) {
            selectedProperty = state.property
            window.unsafeCast<dynamic>().selectedProperty = state.property
        }
        // Drop boxes that are no longer part of the shared selection.
        selected.toList().forEach { el ->
            val uid = el.asDynamic().uid as? String
            if (uid == null || uid !in targetUids) deselectBox(el)
        }
        // Add newly-selected boxes that actually exist here.
        targetUids.forEach { uid ->
            val el = findBoxByUid(uid)
            if (el != null && !selected.contains(el)) selectBox(el)
        }
    } finally {
        applyingRemoteSelection = false
        // Record what we just applied so a genuine local change still diffs correctly and we
        // don't immediately re-broadcast this applied selection.
        val appliedIds = selected.mapNotNull { it.asDynamic().uid as? String }
        lastControllerSelectionSignature = appliedIds.joinToString("\u0000") + "\u0001" + selectedProperty
    }
}

internal fun setSelectedEditorProperty(property: String) {
    selectedProperty = property
    window.unsafeCast<dynamic>().selectedProperty = property
    syncControllerSelection()
}

private fun editorPropertyStickinessMode(): String {
    val raw = runCatching {
        window.localStorage.getItem(editorPropertyStickinessStorageKey)
    }.getOrNull()
        ?: (window.asDynamic().fieldEditorPropertyStickiness as? String)
        ?: "none"
    return when (raw.trim().lowercase()) {
        "per-box", "perbox" -> "per-box"
        "global" -> "global"
        else -> "none"
    }
}

private fun activeEditorProperty(): String {
    val w = window.asDynamic()
    val property = (w.selectedProperty as? String)
        ?: (w.currentSelectedProperty as? String)
        ?: selectedProperty
    return property.takeIf { monacoPropertyNames.contains(it) } ?: "code"
}

private fun boxHasPropertyContents(box: HTMLElement, property: String): Boolean {
    val value = box.asDynamic()[property]
    val exists = js("value !== undefined && value !== null") as Boolean
    if (!exists) return false
    return if (js("typeof value === 'string' || value instanceof String") as Boolean) {
        (value as String).trim().isNotEmpty()
    } else {
        js("Boolean(value)") as Boolean
    }
}

private fun rememberedEditorPropertyForBox(box: HTMLElement): String? {
    val uid = uidForBoxElement(box) ?: return null
    val property = runCatching {
        window.localStorage.getItem(editorPropertyPerBoxStoragePrefix + uid)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    return property.takeIf { monacoPropertyNames.contains(it) }
}

internal fun rememberEditedPropertyForCurrentSelection(property: String) {
    if (editorPropertyStickinessMode() != "per-box") return
    if (!monacoPropertyNames.contains(property)) return
    val w = window.asDynamic()
    val next = w.selectedList ?: selectedList
    val len = (next?.length as? Number)?.toInt() ?: 0
    if (len != 1) return
    val box = next[0] as? HTMLElement ?: return
    val uid = uidForBoxElement(box) ?: return
    runCatching {
        window.localStorage.setItem(editorPropertyPerBoxStoragePrefix + uid, property)
    }
}

fun serviceEvents() {
    events.entries.retainAll { (k, v) ->
        v()
    }
}

private fun findBoxElementByUid(uid: String): HTMLElement? {
    val boxes = document.body?.getElementsByClassName("box")?.asList() ?: return null
    return boxes.firstOrNull {
        val candidate = (it.asDynamic().uid as? String)
            ?: it.getAttribute("uid")
            ?: it.getAttribute("data-uid")
        candidate == uid
    } as? HTMLElement
}

private fun uidForBoxElement(box: HTMLElement): String? =
    (box.asDynamic().uid as? String)
        ?: box.getAttribute("uid")
        ?: box.getAttribute("data-uid")

private fun findAbsoluteFileProxyBox(path: String): HTMLElement? {
    val boxes = document.body?.getElementsByClassName("box")?.asList() ?: return null
    return boxes.firstOrNull {
        val box = it.asDynamic()
        (box.file_proxy_mode as? String) == "absolute" &&
                (box.file_proxy_path as? String) == path
    } as? HTMLElement
}

private var actorArcCounter = 0
private const val ACTOR_ARC_LAYER_ID = "actor-arc-layer"
private const val ACTOR_ARC_DURATION_MS = 150
private const val ACTOR_ARC_DOT_RADIUS = 3.25

// dedup: key → (SVG group, current timeout handle)
private val activeArcs = mutableMapOf<String, Pair<SVGElement, web.timers.Timeout>>()

private fun arcKey(arc: ActorArc): String =
    "${arc.senderUid}|${arc.handlerUid}|${arc.targetUid}|${arc.isReply}"

private fun ensureActorArcLayer(): SVGSVGElement {
    val existing = document.getElementById(ACTOR_ARC_LAYER_ID) as? SVGSVGElement
    if (existing != null) return existing

    val svgNs = "http://www.w3.org/2000/svg"
    val layer = document.createElementNS(svgNs, "svg") as SVGSVGElement
    layer.id = ACTOR_ARC_LAYER_ID
    layer.classList.add("actor-arc-layer")
    layer.setAttribute("height", "100%")
    layer.setAttribute("width", "100%")
    document.body?.appendChild(layer)
    console.log("[actor-arc] created SVG layer")
    return layer
}

/**
 * Get a box's world-space DOMRect (screen rect offset by mainTranslation).
 */
private fun boxWorldRect(uid: String): DOMRect? {
    val el = findBoxElementByUid(uid) ?: return null
    val r = el.getBoundingClientRect()
    return DOMRect(
        (r.x - mainTranslation[0]) / mainZoom,
        (r.y - mainTranslation[1]) / mainZoom,
        r.width / mainZoom,
        r.height / mainZoom
    )
}

private fun drawActorArc(arc: ActorArc) {
    console.log("[actor-arc] drawActorArc: sender=${arc.senderUid} target=${arc.targetUid} handler=${arc.handlerUid} reply=${arc.isReply}")

    val svgNs = "http://www.w3.org/2000/svg"
    val layer = ensureActorArcLayer()

    val vbw = window.innerWidth.toDouble()
    val vbh = window.innerHeight.toDouble()
    layer.setAttribute("viewBox", worldViewBox(vbw, vbh))

    val key = arcKey(arc)
    val existing = activeArcs[key]
    if (existing != null) {
        // same arc already on screen – cancel old timeout and schedule a fresh one
        clearTimeout(existing.second)
        val handle = setTimeout({ existing.first.remove(); activeArcs.remove(key) }, ACTOR_ARC_DURATION_MS)
        activeArcs[key] = existing.first to handle
        return
    }

    val group = document.createElementNS(svgNs, "g") as SVGElement
    group.id = "actor-arc-${actorArcCounter++}"
    val color = if (arc.isReply) "rgba(120, 220, 160, 0.85)" else "rgba(235, 150, 70, 0.85)"

    if (arc.isReply) {
        val senderUid = arc.senderUid
        if (senderUid == null) {
            console.log("[actor-arc] reply arc skipped: senderUid is null")
            return
        }
        val fromRect = boxWorldRect(arc.handlerUid)
        val toRect = boxWorldRect(senderUid)
        if (fromRect == null || toRect == null) return
        drawClippedLine(group, fromRect, toRect, color, svgNs)
    } else {
        val senderUid = arc.senderUid
        if (senderUid == null) {
            console.log("[actor-arc] send arc skipped: senderUid is null")
            return
        }
        val fromRect = boxWorldRect(senderUid)
        val handlerRect = boxWorldRect(arc.handlerUid)
        if (fromRect == null || handlerRect == null) return
        val (senderClip, handlerClip) = drawClippedLine(group, fromRect, handlerRect, color, svgNs)

        if (arc.handlerUid != arc.targetUid && senderClip != null && handlerClip != null) {
            val targetRect = boxWorldRect(arc.targetUid) ?: return
            // start 2/3 along the sender→handler line
            val branchPt = Point(
                senderClip.x + (handlerClip.x - senderClip.x) * 2.0 / 3.0,
                senderClip.y + (handlerClip.y - senderClip.y) * 2.0 / 3.0
            )
            val targetCenter = getCenter(targetRect)
            val dx = targetCenter.x - branchPt.x
            val dy = targetCenter.y - branchPt.y
            val toClip = clipRay(targetRect, -dx, -dy)
            drawLineBetweenPoints(group, branchPt, toClip.point, "rgba(180, 130, 255, 0.85)", svgNs)
        }
    }

    layer.appendChild(group)

    val handle = setTimeout({
        group.remove()
        activeArcs.remove(key)
    }, ACTOR_ARC_DURATION_MS)
    activeArcs[key] = group to handle
}

/**
 * Draw a straight line between two box rects, clipped to their edges
 * (matching parent→child connection placement), with a small dot at
 * the receiving (end) side. Returns the clipped start/end points.
 */
private fun drawClippedLine(
    parent: SVGElement,
    fromRect: DOMRect,
    toRect: DOMRect,
    color: String,
    svgNs: String
): Pair<Point?, Point?> {
    val (fromClip, toClip) = connectCenters(fromRect, toRect)
    val a = fromClip.point
    val b = toClip.point
    if (a.distance(b) < 1.0) return null to null

    drawLineBetweenPoints(parent, a, b, color, svgNs)
    return a to b
}

/**
 * Draw a straight line between two points with a small dot at the end.
 */
private fun drawLineBetweenPoints(parent: SVGElement, a: Point, b: Point, color: String, svgNs: String) {
    if (a.distance(b) < 1.0) return

    val line = document.createElementNS(svgNs, "line") as SVGElement
    line.setAttribute("x1", a.x.toString())
    line.setAttribute("y1", a.y.toString())
    line.setAttribute("x2", b.x.toString())
    line.setAttribute("y2", b.y.toString())
    line.setAttribute("stroke", color)
    line.setAttribute("stroke-width", "1")
    line.setAttribute("fill", "none")
    parent.appendChild(line)

    // dot at receiving end
    val dot = document.createElementNS(svgNs, "circle") as SVGElement
    dot.setAttribute("cx", b.x.toString())
    dot.setAttribute("cy", b.y.toString())
    dot.setAttribute("r", ACTOR_ARC_DOT_RADIUS.toString())
    dot.setAttribute("fill", color)
    parent.appendChild(dot)
}

private fun domRectToRect(r: dynamic): Rect {
    return Rect(
        (r.left as Number).toDouble(),
        (r.top as Number).toDouble(),
        (r.width as Number).toDouble(),
        (r.height as Number).toDouble()
    )
}

private fun Rect.expand(padding: Double) = Rect(x - padding, y - padding, w + padding * 2.0, h + padding * 2.0)
private fun Rect.translate(dx: Double, dy: Double) = Rect(x + dx, y + dy, w, h)

private fun unionRects(rects: List<Rect>): Rect? {
    if (rects.isEmpty()) return null
    return rects.drop(1).fold(rects.first()) { acc, r ->
        val nx = min(acc.x, r.x)
        val ny = min(acc.y, r.y)
        val nr = max(acc.right, r.right)
        val nb = max(acc.bottom, r.bottom)
        Rect(nx, ny, nr - nx, nb - ny)
    }
}

private fun collectScreenshotRects(box: HTMLElement): List<Rect> {
    val rects = mutableListOf(domRectToRect(box.getBoundingClientRect()))
    val outputArea = box.querySelector(".outputArea") as? HTMLElement
    outputArea?.let { oa ->
        if (oa.style.visibility != "hidden") {
            val disclosures = oa.querySelectorAll(".disclosure").asList()
            if (disclosures.isNotEmpty()) {
                disclosures.mapNotNull { it as? HTMLElement }
                    .forEach { rects.add(domRectToRect(it.getBoundingClientRect())) }
            } else {
                rects.add(domRectToRect(oa.getBoundingClientRect()))
            }
        }
    }
    return rects
}

private fun svgContentClientRect(svg: SVGElement): Rect? = runCatching {
    val bbox = svg.asDynamic().getBBox?.invoke() ?: return@runCatching null
    val ctm = svg.asDynamic().getScreenCTM?.invoke() ?: return@runCatching null
    val x = (bbox.x as? Number)?.toDouble() ?: return@runCatching null
    val y = (bbox.y as? Number)?.toDouble() ?: return@runCatching null
    val w = (bbox.width as? Number)?.toDouble() ?: return@runCatching null
    val h = (bbox.height as? Number)?.toDouble() ?: return@runCatching null
    if (w <= 0.0 || h <= 0.0) return@runCatching null

    fun point(px: Double, py: Double): Pair<Double, Double>? {
        val p = svg.asDynamic().createSVGPoint?.invoke() ?: return null
        p.x = px
        p.y = py
        val transformed = p.matrixTransform(ctm)
        val tx = (transformed.x as? Number)?.toDouble() ?: return null
        val ty = (transformed.y as? Number)?.toDouble() ?: return null
        return tx to ty
    }

    val corners = listOfNotNull(
        point(x, y),
        point(x + w, y),
        point(x, y + h),
        point(x + w, y + h)
    )
    if (corners.isEmpty()) return@runCatching null
    val left = corners.minOf { it.first }
    val top = corners.minOf { it.second }
    val right = corners.maxOf { it.first }
    val bottom = corners.maxOf { it.second }
    val width = right - left
    val height = bottom - top
    if (width <= 0.0 || height <= 0.0) return@runCatching null
    Rect(left, top, width, height).expand(8.0)
}.getOrNull()

private fun svgGraphicClientRect(element: SVGElement): Rect? = runCatching {
    val bbox = element.asDynamic().getBBox?.invoke() ?: return@runCatching null
    val ctm = element.asDynamic().getScreenCTM?.invoke() ?: return@runCatching null
    val pointFactory =
        (element.asDynamic().ownerSVGElement ?: element).asDynamic().createSVGPoint ?: return@runCatching null
    val x = (bbox.x as? Number)?.toDouble() ?: return@runCatching null
    val y = (bbox.y as? Number)?.toDouble() ?: return@runCatching null
    val w = (bbox.width as? Number)?.toDouble() ?: return@runCatching null
    val h = (bbox.height as? Number)?.toDouble() ?: return@runCatching null
    if (w <= 0.0 || h <= 0.0) return@runCatching null

    fun point(px: Double, py: Double): Pair<Double, Double>? {
        val p = pointFactory.invoke() ?: return null
        p.x = px
        p.y = py
        val transformed = p.matrixTransform(ctm)
        val tx = (transformed.x as? Number)?.toDouble() ?: return null
        val ty = (transformed.y as? Number)?.toDouble() ?: return null
        return tx to ty
    }

    val corners = listOfNotNull(
        point(x, y),
        point(x + w, y),
        point(x, y + h),
        point(x + w, y + h)
    )
    if (corners.isEmpty()) return@runCatching null
    val left = corners.minOf { it.first }
    val top = corners.minOf { it.second }
    val right = corners.maxOf { it.first }
    val bottom = corners.maxOf { it.second }
    val width = right - left
    val height = bottom - top
    if (width <= 0.0 || height <= 0.0) return@runCatching null
    Rect(left, top, width, height).expand(8.0)
}.getOrNull()

private fun svgIntrinsicWidth(svg: SVGElement): Double {
    val widthAttr = svg.getAttribute("width")
        ?.removeSuffix("px")
        ?.trim()
        ?.toDoubleOrNull()
        ?: 0.0
    val viewBoxWidth = svg.getAttribute("viewBox")
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.takeIf { it.size == 4 }
        ?.getOrNull(2)
        ?.toDoubleOrNull()
        ?: 0.0
    return max(widthAttr, viewBoxWidth)
}

private fun disclosureTitleClientRect(disclosure: HTMLElement): Rect? {
    val disclosureRect = domRectToRect(disclosure.getBoundingClientRect())
    if (disclosureRect.w <= 2.0 || disclosureRect.h <= 2.0) return null
    val header = disclosure.querySelector(".disclosure-header") as? HTMLElement ?: return disclosureRect
    if (!isOverlayVisible(header)) return disclosureRect
    val headerRect = domRectToRect(header.getBoundingClientRect())
    val titleRect = (header.querySelector(".title") as? HTMLElement)
        ?.takeIf { isOverlayVisible(it) }
        ?.let { domRectToRect(it.getBoundingClientRect()) }

    val targetWidth = min(
        disclosureRect.w,
        max(
            108.0,
            max(titleRect?.w ?: 0.0, headerRect.w.coerceAtMost(180.0)) + 20.0
        )
    )
    val targetHeight = max(headerRect.h, titleRect?.h ?: 0.0)
    return Rect(disclosureRect.x, disclosureRect.y, targetWidth, targetHeight)
}

private fun cssPixelsOrZero(raw: String): Double =
    raw.removeSuffix("px").trim().toDoubleOrNull() ?: 0.0

private fun dataAttributePixelsOrZero(element: HTMLElement, attribute: String): Double =
    element.getAttribute(attribute)
        ?.removeSuffix("px")
        ?.trim()
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?: 0.0

private fun hasStyledBox(element: HTMLElement): Boolean {
    val style = window.getComputedStyle(element)
    val background = style.backgroundColor.trim().lowercase()
    val hasBackground =
        background != "transparent" &&
                background != "rgba(0, 0, 0, 0)" &&
                background != "rgba(0,0,0,0)"
    val borderWidth = cssPixelsOrZero(style.borderTopWidth) +
            cssPixelsOrZero(style.borderRightWidth) +
            cssPixelsOrZero(style.borderBottomWidth) +
            cssPixelsOrZero(style.borderLeftWidth)
    return hasBackground || borderWidth > 0.0
}

private fun directText(element: HTMLElement): String {
    val pieces = mutableListOf<String>()
    val children = element.childNodes
    for (index in 0 until children.length) {
        val node = children.item(index) ?: continue
        if (node.nodeType == Node.TEXT_NODE) {
            pieces.add(node.textContent.orEmpty())
        }
    }
    return pieces.joinToString("").trim()
}

private fun disclosureBodyClientRect(disclosure: HTMLElement): Rect? {
    val content = disclosure.querySelector(".disclosure-content") as? HTMLElement ?: return null
    if (!isOverlayVisible(content)) return null

    val rects = content.querySelectorAll("*").asList()
        .mapNotNull { node ->
            val element = node as? Element ?: return@mapNotNull null
            if (!isOverlayVisible(element)) return@mapNotNull null
            val rect = domRectToRect(element.getBoundingClientRect())
            if (rect.w <= 2.0 || rect.h <= 2.0) return@mapNotNull null

            when (element) {
                is SVGElement,
                is HTMLCanvasElement,
                is HTMLImageElement,
                is HTMLTableElement,
                is HTMLVideoElement -> rect

                is HTMLIFrameElement -> {
                    val intrinsicWidth = dataAttributePixelsOrZero(element, "data-afield-intrinsic-width")
                    val intrinsicHeight = dataAttributePixelsOrZero(element, "data-afield-intrinsic-height")
                    Rect(
                        rect.x,
                        rect.y,
                        max(rect.w, intrinsicWidth),
                        max(rect.h, intrinsicHeight)
                    )
                }

                is HTMLElement -> {
                    val text = directText(element)
                    val include = element.children.length == 0 ||
                            hasStyledBox(element) ||
                            text.isNotEmpty()
                    if (include) rect else null
                }

                else -> null
            }
        }

    return unionRects(rects)?.expand(6.0)
}

private fun collectOutputScreenshotRects(box: HTMLElement): List<Rect> {
    val outputArea = box.querySelector(".outputArea") as? HTMLElement ?: return emptyList()
    if (outputArea.style.visibility == "hidden") return emptyList()

    val disclosureElements = outputArea.querySelectorAll(".disclosure").asList()
        .mapNotNull { disclosure ->
            val element = disclosure as? HTMLElement ?: return@mapNotNull null
            if (!isOverlayVisible(element)) return@mapNotNull null
            val rect = domRectToRect(element.getBoundingClientRect())
            if (rect.w <= 2.0 || rect.h <= 2.0) return@mapNotNull null
            element
        }

    fun disclosureHasControls(disclosure: HTMLElement): Boolean {
        val content = disclosure.querySelector(".disclosure-content") as? HTMLElement ?: return false
        return content.querySelector(".field-control, input, textarea, select, button") != null
    }

    fun disclosureScreenshotRect(disclosure: HTMLElement): Rect? {
        val disclosureRect = domRectToRect(disclosure.getBoundingClientRect())
        if (disclosureRect.w <= 2.0 || disclosureRect.h <= 2.0) return null
        val disclosureTitleRect = disclosureTitleClientRect(disclosure)
        if (disclosureHasControls(disclosure)) {
            val bodyRect = disclosureBodyClientRect(disclosure) ?: disclosureRect
            return if (disclosureTitleRect == null) bodyRect else unionRects(listOf(disclosureTitleRect, bodyRect))
                ?: bodyRect
        }
        val svgRects = disclosure.querySelectorAll("svg").asList()
            .mapNotNull { node ->
                val svg = node as? SVGElement ?: return@mapNotNull null
                if (!isOverlayVisible(svg as Element)) return@mapNotNull null
                svgContentClientRect(svg) ?: domRectToRect(svg.getBoundingClientRect())
            }
        if (svgRects.isNotEmpty()) {
            val maxArea = svgRects.maxOf { it.w * it.h }
            val meaningful = svgRects.filter { rect ->
                val area = rect.w * rect.h
                area >= max(2500.0, maxArea * 0.15)
            }
            val bodyRect = unionRects(
                meaningful.ifEmpty {
                    listOf(svgRects.maxByOrNull { it.w * it.h } ?: svgRects.last())
                }
            ) ?: disclosureRect
            return if (disclosureTitleRect == null) bodyRect else unionRects(listOf(disclosureTitleRect, bodyRect))
                ?: bodyRect
        }
        val bodyRect = disclosureBodyClientRect(disclosure) ?: disclosureRect
        return if (disclosureTitleRect == null) bodyRect else unionRects(listOf(disclosureTitleRect, bodyRect))
            ?: bodyRect
    }

    if (disclosureElements.size > 1) {
        return disclosureElements.mapNotNull(::disclosureScreenshotRect)
    }

    val disclosureTitleRect = disclosureElements.firstOrNull()?.let(::disclosureTitleClientRect)
    disclosureElements.firstOrNull()
        ?.takeIf(::disclosureHasControls)
        ?.let(::disclosureScreenshotRect)
        ?.let { return listOf(it) }

    val svgRects = outputArea.querySelectorAll("svg").asList()
        .mapNotNull { node ->
            val svg = node as? SVGElement ?: return@mapNotNull null
            if (!isOverlayVisible(svg as Element)) return@mapNotNull null
            svgContentClientRect(svg) ?: domRectToRect(svg.getBoundingClientRect())
        }
    if (svgRects.isNotEmpty()) {
        val maxArea = svgRects.maxOf { it.w * it.h }
        val meaningful = svgRects.filter { rect ->
            val area = rect.w * rect.h
            area >= max(2500.0, maxArea * 0.15)
        }
        val trimmed = meaningful.ifEmpty {
            listOf(svgRects.maxByOrNull { it.w * it.h } ?: svgRects.last())
        }
        if (disclosureTitleRect == null) return trimmed
        return trimmed.map { rect -> unionRects(listOf(disclosureTitleRect, rect)) ?: rect }
    }

    disclosureElements.firstOrNull()?.let(::disclosureScreenshotRect)?.let { return listOf(it) }
    return listOf(domRectToRect(outputArea.getBoundingClientRect()))
}

private fun visibleMonacoContainer(): HTMLElement? {
    val container = document.querySelector(".monaco-editor-container") as? HTMLElement ?: return null
    return if (isOverlayVisible(container)) container else null
}

private fun currentViewportRect() = Rect(
    window.scrollX,
    window.scrollY,
    window.innerWidth.toDouble(),
    window.innerHeight.toDouble()
)

private fun clipForPageRect(pageRect: Rect): Rect? {
    val viewport = currentViewportRect()
    val left = max(pageRect.x, viewport.x)
    val top = max(pageRect.y, viewport.y)
    val right = min(pageRect.right, viewport.right)
    val bottom = min(pageRect.bottom, viewport.bottom)
    val width = right - left
    val height = bottom - top
    if (width <= 0.0 || height <= 0.0) return null
    return Rect(left, top, width, height)
}

private fun clipForVisibleElement(element: HTMLElement, padding: Double): Rect? {
    val pageRect = domRectToRect(element.getBoundingClientRect())
        .expand(max(0.0, padding))
        .translate(window.scrollX, window.scrollY)
    return clipForPageRect(pageRect)
}

private fun visibleDocsIframeElement(): HTMLElement? {
    val iframe = document.getElementById("docs-iframe") as? HTMLElement
    if (iframe != null && isOverlayVisible(iframe)) return iframe
    val container = document.querySelector(".docs-iframe-container") as? HTMLElement ?: return null
    return if (isOverlayVisible(container)) container else null
}

private fun clipForVisibleDocsIframe(padding: Double): Rect? {
    val element = visibleDocsIframeElement() ?: return null
    val maxHeight = 1120.0
    val pageRect = domRectToRect(element.getBoundingClientRect())
        .expand(max(0.0, padding))
        .translate(window.scrollX, window.scrollY)
    val clippedHeight = min(pageRect.h, maxHeight)
    return clipForPageRect(Rect(pageRect.x, pageRect.y, pageRect.w, clippedHeight))
}

private fun pageRectForElement(element: HTMLElement): Rect =
    domRectToRect(element.getBoundingClientRect())
        .translate(window.scrollX, window.scrollY)

private fun visibleSlideNoteOverlay(): HTMLElement? {
    val overlay = document.getElementById("slide-note-overlay") as? HTMLElement ?: return null
    if (!isOverlayVisible(overlay)) return null
    if (overlay.style.display == "none") return null
    return overlay
}

private fun guideAnnotationScreenshotRect(): Rect? {
    if (currentAnnotations.isEmpty()) return null
    val layer = document.getElementById("slide-annotation-layer") as? SVGSVGElement ?: return null
    if (!isOverlayVisible(layer)) return null
    val group = layer.querySelector("[data-name=\"slideAnnotations\"]") as? SVGElement ?: return null
    return svgGraphicClientRect(group)
}

private fun snapGuideScreenshotRect(): Rect? {
    val group = document.querySelector("#snap-guides") as? SVGElement ?: return null
    if (!isOverlayVisible(group)) return null
    return svgGraphicClientRect(group)
}

private fun activeSnapReferenceScreenshotRects(): List<Rect> =
    boxSnappingSystem.activeSnapReferenceBoxes().map { domRectToRect(it.getBoundingClientRect()) }

private fun spatialNavScreenshotRects(): List<Rect> {
    if (!spatialNavActive) return emptyList()
    val rects = mutableListOf<Rect>()
    val selectors = listOf(
        ".spatial-nav-highlight",
        ".spatial-nav-badge",
        ".spatial-nav-bookmark-badge",
        ".spatial-nav-lines"
    )
    selectors.forEach { selector ->
        document.querySelectorAll(selector).asList().forEach { node ->
            val element = node as? HTMLElement ?: return@forEach
            if (!isOverlayVisible(element)) return@forEach
            val rect = domRectToRect(element.getBoundingClientRect())
            if (rect.w > 0.0 && rect.h > 0.0) rects.add(rect)
        }
    }
    document.querySelectorAll("svg.spatial-nav-lines").asList().forEach { node ->
        val svg = node as? SVGElement ?: return@forEach
        if (!isOverlayVisible(svg)) return@forEach
        svgGraphicClientRect(svg)?.let { rects.add(it) }
    }
    return rects
}

private fun collectGuideScreenshotRects(box: HTMLElement): List<Rect> {
    val rects = mutableListOf<Rect>()
    visibleSlideNoteOverlay()?.let { rects.add(domRectToRect(it.getBoundingClientRect())) }
    guideAnnotationScreenshotRect()?.let { rects.add(it) }
    snapGuideScreenshotRect()?.let { rects.add(it) }
    rects.addAll(activeSnapReferenceScreenshotRects())
    rects.addAll(spatialNavScreenshotRects())
    return rects
}

private fun clipForWindowEditorCapture(box: HTMLElement, padding: Double): Rect? {
    val rects = mutableListOf(pageRectForElement(box))
    visibleSlideNoteOverlay()?.let { rects.add(pageRectForElement(it)) }
    clipForRenderedEditorContent(0.0)?.let { rects.add(it) }
    val union = unionRects(rects) ?: return null
    return clipForPageRect(union.expand(max(0.0, padding)))
}

/**
 * An editor-only capture with a line note showing: shorten the pane so the note sits under the text.
 *
 * Screenshot prep sizes the pane for a window capture (840–1200 CSS px, [prepareMonacoForScreenshotLayout]),
 * and a line note is drawn in the left overlay at the pane's *foot*. [clipForRenderedEditorContent] keeps
 * the note in the clip, because it is part of what the editor shows, so a four-line box with a note was a
 * whole pane tall — code at the top, the note 2000 device pixels below it, nothing between (lua.md's
 * captures, 2026-09-30; every language whose runs leave a note is the same). This is what
 * `ui.editorHeight(px)` does by hand in scripted capture="ui" fences, done here for every editor-only
 * capture: pane height = the rendered text and its inline output zones + the note's own height + a
 * margin, never taller than prep made it, then Monaco is laid out again before the clip is measured.
 *
 * Only `editorOnly` captures call this, and only when a note is visible; a window capture with the editor
 * open keeps the pane prep gave it, and so does an editor capture without a note. The pane stays this size
 * until the next screenshot prep re-sizes it, which every capture does first.
 */
private fun fitEditorPaneToContentAndNote(container: HTMLElement) {
    val overlay = (container.querySelector(".hoverlay-left") as? HTMLElement)
        ?.takeIf { it.querySelector(".afield-line-note") != null && isOverlayVisible(it) } ?: return
    val editor = window.asDynamic().globalEditor ?: return
    val containerRect = container.getBoundingClientRect()
    val headerHeight = runCatching {
        (container.querySelector(".hoverlay") as? HTMLElement)?.getBoundingClientRect()?.height ?: 0.0
    }.getOrDefault(0.0)
    val contentHeight = runCatching { (editor.getContentHeight?.invoke() as? Number)?.toDouble() ?: 0.0 }.getOrDefault(0.0)
    val textBottom = container.querySelectorAll(
        ".view-lines > div, .view-zones > div, .margin-view-zones > div, .execution_output"
    ).asList().mapNotNull {
        val element = it as? HTMLElement ?: return@mapNotNull null
        if (!isOverlayVisible(element)) return@mapNotNull null
        element.getBoundingClientRect().bottom - containerRect.top
    }.maxOrNull() ?: 0.0
    val noteHeight = overlay.getBoundingClientRect().height
    val wanted = max(textBottom, headerHeight + contentHeight) + noteHeight + 36.0
    if (wanted >= containerRect.height) return
    container.style.height = "${wanted}px"
    layoutGlobalEditor()
}

private fun clipForRenderedEditorContent(padding: Double): Rect? {
    val container = visibleMonacoContainer() ?: return null
    val editor = window.asDynamic().globalEditor ?: return clipForVisibleElement(container, padding)
    val containerRect = container.getBoundingClientRect()
    val headerHeight = runCatching {
        val hover = container.querySelector(".hoverlay") as? HTMLElement ?: return@runCatching 0.0
        hover.getBoundingClientRect().height
    }.getOrDefault(0.0)
    val contentHeight = runCatching {
        (editor.getContentHeight?.invoke() as? Number)?.toDouble() ?: 0.0
    }.getOrDefault(0.0)
    val domBottom = container.querySelectorAll(
        ".view-lines > div, .view-zones > div, .margin-view-zones > div, .view-overlays > div, .margin-view-overlays > div, .execution_output"
    ).asList().mapNotNull {
        val element = it as? HTMLElement ?: return@mapNotNull null
        if (!isOverlayVisible(element)) return@mapNotNull null
        element.getBoundingClientRect().bottom - containerRect.top
    }.maxOrNull() ?: 0.0
    // A line note beside the cursor (fieldSuggest.js#lineNoteHtml, drawn into the left overlay at the
    // foot of the pane) is part of what the editor shows about its text, so an editor capture that
    // trimmed to the text alone would cut off exactly the note being captured. Only a note: the
    // overlay's other contents (the word under the cursor) keep the clip they always had.
    val noteBottom = (container.querySelector(".hoverlay-left") as? HTMLElement)
        ?.takeIf { it.querySelector(".afield-line-note") != null && isOverlayVisible(it) }
        ?.let { it.getBoundingClientRect().bottom - containerRect.top }
        ?: 0.0
    val layoutInfo = runCatching { editor.getLayoutInfo?.invoke() }.getOrNull()
    val contentLeft = (layoutInfo?.contentLeft as? Number)?.toDouble() ?: 0.0
    val scrollWidth = runCatching {
        (editor.getContentWidth?.invoke() as? Number)?.toDouble()
            ?: (editor.getScrollWidth?.invoke() as? Number)?.toDouble()
            ?: 0.0
    }.getOrDefault(0.0)
    val scrollbarWidth = runCatching {
        (layoutInfo?.verticalScrollbarWidth as? Number)?.toDouble()
            ?: (layoutInfo?.scrollbarWidth as? Number)?.toDouble()
            ?: 0.0
    }.getOrDefault(0.0)
    val domRight = container.querySelectorAll(
        ".margin, .margin-view-overlays, .line-numbers, .view-lines > div, .view-zones > div, .margin-view-zones > div, .view-overlays > div, .execution_output"
    ).asList().mapNotNull {
        val element = it as? Element ?: return@mapNotNull null
        if (!isOverlayVisible(element)) return@mapNotNull null
        val right = element.getBoundingClientRect().right - containerRect.left
        if (right > 0.0) right else null
    }.maxOrNull() ?: 0.0
    val trimmedHeight = max(
        160.0,
        max(max(domBottom, noteBottom), headerHeight + contentHeight) + 20.0
    ).coerceAtMost(containerRect.height)
    val trimmedWidth = max(
        360.0,
        max(domRight, contentLeft + scrollWidth + scrollbarWidth) + 24.0
    ).coerceAtMost(containerRect.width)
    val pageRect = Rect(
        containerRect.left,
        containerRect.top,
        trimmedWidth,
        trimmedHeight
    ).expand(max(0.0, padding)).translate(window.scrollX, window.scrollY)
    return clipForPageRect(pageRect)
}

private fun clearSelectionForScreenshot() {
    selected.toList().forEach { deselectBox(it) }
}

private fun hideNotificationsForScreenshot() {
    document.querySelectorAll(".bubble").asList()
        .mapNotNull { it as? HTMLElement }
        .forEach {
            it.style.visibility = "hidden"
            it.style.opacity = "0"
            it.style.setProperty("pointer-events", "none")
        }
}

private fun disclosureInterfaceFor(container: Element): dynamic {
    val factory = js("window.disclosureInterface")
    if (factory == null || factory == js("undefined")) return null
    return runCatching { factory(container) }.getOrNull()
}

private fun openAllOutputDisclosures(outputArea: HTMLElement) {
    outputArea.style.visibility = "visible"
    outputArea.style.height = "auto"
    val contain = outputArea.querySelector(".disclosure-group") ?: return
    val inter = disclosureInterfaceFor(contain) ?: return
    val ids = contain.querySelectorAll(".disclosure").asList().mapNotNull {
        it.asDynamic().__disclosureId as? String
    }
    ids.forEach { id ->
        runCatching {
            (inter.open as (String) -> Unit).invoke(id)
        }
    }
}

private fun fitOutputDisclosuresForScreenshot(outputArea: HTMLElement) {
    outputArea.style.maxHeight = "none"
    val maxDisclosureHeight = max(680.0, min(1400.0, window.innerHeight.toDouble() * 0.82))
    outputArea.querySelectorAll(".disclosure-content").asList()
        .mapNotNull { it as? HTMLElement }
        .forEach { content ->
            content.style.maxHeight = "none"
            content.style.setProperty("overflow", "visible")
            val scrollHeight = (content.scrollHeight as? Number)?.toDouble() ?: 0.0
            val childHeights = content.children.asList()
                .mapNotNull { child ->
                    val element = child as? HTMLElement ?: return@mapNotNull null
                    val rect = element.getBoundingClientRect()
                    val intrinsicHeight = dataAttributePixelsOrZero(element, "data-afield-intrinsic-height")
                    max(
                        max(rect.height, (element.scrollHeight as? Number)?.toDouble() ?: 0.0),
                        intrinsicHeight
                    )
                }
            val svgHeights = content.querySelectorAll("svg").asList()
                .mapNotNull { node ->
                    val svg = node as? HTMLElement ?: return@mapNotNull null
                    val rectHeight = svg.getBoundingClientRect().height
                    val scrollHeightSvg = (svg.scrollHeight as? Number)?.toDouble() ?: 0.0
                    max(rectHeight, scrollHeightSvg)
                }
            val naturalHeight = max(
                scrollHeight,
                max(
                    childHeights.maxOrNull() ?: 0.0,
                    svgHeights.maxOrNull() ?: 0.0
                )
            )
            val minimumHeight = when {
                content.querySelector("iframe") != null -> 220.0
                content.querySelector("input, textarea, select, button") != null -> 260.0
                content.querySelector("svg") != null -> 260.0
                else -> 120.0
            }
            val targetHeight = max(minimumHeight, naturalHeight + 36.0)
                .coerceAtMost(maxDisclosureHeight)
            content.style.height = "${targetHeight}px"
        }
}

// A `data-afield-autosize="off"` frame that declares no width of its own has no width to report and
// none to contribute here. `outputareas2.js` (`fillWidthForDisclosure`) deliberately gives such a
// frame the width of the box it is under, because measuring the frame only ever returns the panel
// width it already had. Measuring it *again* on this side and then widening the box to match closes
// that loop from the outside: the box chases the panel it just widened, and a `.h2` docs figure grew
// until it hit the window-width cap. A fill frame is as wide as it is told to be; only content with
// a width of its own gets to move the box.
private fun isFillWidthIframe(element: Element): Boolean {
    if (element.tagName.uppercase() != "IFRAME") return false
    if ((element.getAttribute("data-afield-autosize") ?: "").trim().lowercase() != "off") return false
    val html = element as? HTMLElement ?: return false
    if (dataAttributePixelsOrZero(html, "data-afield-intrinsic-width") > 0.0) return false
    val inline = html.style.width.trim().lowercase()
    return inline.isEmpty() || inline == "auto" || inline.endsWith("%")
}

private fun containsFillWidthIframe(element: Element): Boolean =
    isFillWidthIframe(element) ||
        element.querySelectorAll("iframe").asList().any { it is Element && isFillWidthIframe(it) }

private fun fitBoxWidthToOutputsForScreenshot(box: HTMLElement, outputArea: HTMLElement) {
    val currentWidth = box.getBoundingClientRect().width
    val maxContentWidth = outputArea.querySelectorAll(".disclosure-content").asList()
        .mapNotNull { it as? HTMLElement }
        .map { content ->
            val contentIsFillWidth = containsFillWidthIframe(content)
            val scrollW = if (contentIsFillWidth) 0.0 else content.scrollWidth.toDouble()
            val contentRect = content.getBoundingClientRect()
            val childW = content.children.asList()
                .mapNotNull { child ->
                    val element = child
                    if (!isOverlayVisible(element)) return@mapNotNull null
                    if (containsFillWidthIframe(element)) return@mapNotNull null
                    val edgeW = element.getBoundingClientRect().right - contentRect.left
                    val html = element as? HTMLElement
                    val intrinsicWidth =
                        html?.let { dataAttributePixelsOrZero(it, "data-afield-intrinsic-width") } ?: 0.0
                    max(
                        edgeW,
                        max(
                            max(
                                html?.getBoundingClientRect()?.width ?: 0.0,
                                (html?.scrollWidth as? Number)?.toDouble() ?: 0.0
                            ),
                            intrinsicWidth
                        )
                    )
                }
                .maxOrNull() ?: 0.0
            val svgW = content.querySelectorAll("svg").asList()
                .mapNotNull { node ->
                    val svg = node as? SVGElement ?: return@mapNotNull null
                    if (!isOverlayVisible(svg as Element)) return@mapNotNull null
                    val contentRight = svgContentClientRect(svg)?.right?.minus(contentRect.left) ?: 0.0
                    max(
                        max(contentRight, svg.getBoundingClientRect().right - contentRect.left),
                        svgIntrinsicWidth(svg)
                    )
                }
                .maxOrNull() ?: 0.0
            max(scrollW, max(childW, svgW))
        }
        .maxOrNull() ?: 0.0

    if (maxContentWidth <= 0.0) return

    val disclosureWidth = (maxContentWidth + 56.0)
        .coerceAtMost(max(1040.0, window.innerWidth.toDouble() * 0.94))

    // A panel holding a fill frame is already exactly as wide as it should be -- the auto-sizer gave
    // it the width of its box. Overwriting that with the width the *other* panels needed shrinks it,
    // and because the auto-size passes keep running after this and put the box width back, the panel
    // ends up wider than the clip that was measured here: the `.h2` table spilled past the right edge
    // of the figure with its `value` column outside the frame. Leave those panels alone.
    outputArea.querySelectorAll(".disclosure").asList()
        .mapNotNull { it as? HTMLElement }
        .filterNot(::containsFillWidthIframe)
        .forEach { disclosure ->
            disclosure.style.width = "${disclosureWidth}px"
            disclosure.style.maxWidth = "none"
        }
    outputArea.querySelectorAll(".disclosure-content").asList()
        .mapNotNull { it as? HTMLElement }
        .filterNot(::containsFillWidthIframe)
        .forEach { content ->
            content.style.width = "${disclosureWidth}px"
            content.style.maxWidth = "none"
        }

    val desiredWidth = (disclosureWidth + 32.0)
        .coerceAtLeast(currentWidth)
        .coerceAtMost(max(920.0, window.innerWidth.toDouble() * 0.95))

    if (desiredWidth > currentWidth + 2.0) {
        box.style.width = "${desiredWidth}px"
    }
}

private fun openNestedDisclosuresForScreenshot(targetBox: HTMLElement) {
    fun openFor(box: HTMLElement) {
        val nestedIn = box.unsafeCast<dynamic>().box_nestedIn as? String ?: return
        val parentBox = document.body
            ?.getElementsByClassName("box")
            ?.asList()
            ?.firstOrNull { it.unsafeCast<dynamic>().uid == nestedIn } as? HTMLElement ?: return
        openFor(parentBox)
        val outputArea = parentBox.getElementsByClassName("outputArea").asList().firstOrNull() as? HTMLElement ?: return
        outputArea.style.visibility = "visible"
        outputArea.style.height = "auto"
        val contain = outputArea.querySelector(".disclosure-group") ?: return
        runCatching {
            val inter = disclosureInterfaceFor(contain) ?: return@runCatching
            (inter.open as (String) -> Unit).invoke("nested")
        }
    }

    openFor(targetBox)
}

private fun revealBoxForScreenshot(
    box: HTMLElement,
    openOutputs: Boolean,
    panIntoView: Boolean,
    padding: Double
): Rect? {
    openNestedDisclosuresForScreenshot(box)
    if (openOutputs) {
        val outputArea = box.querySelector(".outputArea") as? HTMLElement
        if (outputArea != null) {
            openAllOutputDisclosures(outputArea)
            val hasIframeOutputs = outputArea.querySelector("iframe") != null
            if (!hasIframeOutputs) {
                fitOutputDisclosuresForScreenshot(outputArea)
                fitBoxWidthToOutputsForScreenshot(box, outputArea)
            }
        }
    }

    var targetRect = unionRects(collectScreenshotRects(box)) ?: return null
    if (panIntoView) {
        ensureRectVisibleForScreenshot(targetRect.expand(padding))
        targetRect = unionRects(collectScreenshotRects(box)) ?: targetRect
    }
    return targetRect
}

private fun iframeOutputRectForScreenshot(
    box: HTMLElement,
    openOutputs: Boolean,
    panIntoView: Boolean,
    padding: Double
): Rect? {
    if (!openOutputs) return null
    val outputArea = box.querySelector(".outputArea") as? HTMLElement ?: return null
    if (outputArea.querySelector("iframe") == null) return null

    openNestedDisclosuresForScreenshot(box)
    openAllOutputDisclosures(outputArea)
    fitOutputDisclosuresForScreenshot(outputArea)
    fitBoxWidthToOutputsForScreenshot(box, outputArea)

    fun iframeAwareDisclosureRect(disclosure: HTMLElement): Rect? {
        val disclosureRect = domRectToRect(disclosure.getBoundingClientRect())
        if (disclosureRect.w <= 2.0 || disclosureRect.h <= 2.0) return null
        val titleRect = disclosureTitleClientRect(disclosure)
        val bodyRect = disclosureBodyClientRect(disclosure)
        return unionRects(listOfNotNull(titleRect, bodyRect)) ?: bodyRect ?: titleRect ?: disclosureRect
    }

    fun visibleDisclosureRects(): List<Rect> =
        outputArea.querySelectorAll(".disclosure").asList()
            .mapNotNull { it as? HTMLElement }
            .filter { isOverlayVisible(it) }
            .mapNotNull(::iframeAwareDisclosureRect)
            .filter { it.w > 2.0 && it.h > 2.0 }

    var targetRect = unionRects(visibleDisclosureRects())
        ?: domRectToRect(outputArea.getBoundingClientRect())

    if (panIntoView) {
        ensureRectVisibleForScreenshot(targetRect.expand(padding))
        targetRect = unionRects(visibleDisclosureRects())
            ?: domRectToRect(outputArea.getBoundingClientRect())
    }

    return targetRect
}

private fun ensureRectVisibleForScreenshot(rect: Rect) {
    if (rect.w <= 0.0 || rect.h <= 0.0) return

    val safe = navigationSafeViewportRect(padding = 12.0, occluderGap = 10.0)
    val inset = 10.0
    val leftBound = safe.x + inset
    val rightBound = safe.right - inset
    val topBound = safe.y + inset
    val bottomBound = safe.bottom - inset

    var dx = 0.0
    var dy = 0.0

    if (rect.w >= rightBound - leftBound) {
        dx = leftBound - rect.x
    } else if (rect.x < leftBound) {
        dx = leftBound - rect.x
    } else if (rect.right > rightBound) {
        dx = rightBound - rect.right
    }

    if (rect.h >= bottomBound - topBound) {
        dy = topBound - rect.y
    } else if (rect.y < topBound) {
        dy = topBound - rect.y
    } else if (rect.bottom > bottomBound) {
        dy = bottomBound - rect.bottom
    }

    if (abs(dx) < 0.5 && abs(dy) < 0.5) return
    applyViewportTranslation(
        nextX = mainTranslation[0] + dx,
        nextY = mainTranslation[1] + dy,
        panning = false,
        refreshSpatial = false,
        syncServer = true
    )
}

private fun globalEditorText(): String? = runCatching {
    val editor = window.asDynamic().globalEditor ?: return@runCatching null
    val model = editor.getModel?.invoke() ?: return@runCatching null
    model.getValue?.invoke() as? String
}.getOrNull()

private fun boxPropertyText(box: HTMLElement, property: String): String? = runCatching {
    box.asDynamic()[property] as? String
}.getOrNull()

private fun normalizeEditorSyncText(text: String?): String? =
    text?.replace("\r\n", "\n")?.trimEnd('\n')

private fun layoutGlobalEditor() {
    runCatching {
        val editor = window.asDynamic().globalEditor ?: return@runCatching
        editor.layout?.invoke()
    }
}

/**
 * A still of the editor must not land in the dark half of the caret's blink (Monaco's default
 * `cursorBlinking: "blink"`), which it did about half the time: hold the caret solid while a capture is
 * prepared and taken, and put the editor's own setting back afterwards ([restoreCaptureCursorBlinking],
 * which the JVM calls once the screenshot is written). Only the capture page's editor is touched.
 *
 * Held solid, Field's caret — a red *block* (`cursorStyle: "block"` in BootstrapMonacoInit) — covered the
 * character under it in every capture, and after a run the caret sits at line 1, column 1, so a figure's
 * first word lost its first letter (`box` read `ox`, `spawn` `pawn`, lua.md 2026-09-30). So a capture
 * also draws the caret as a thin line, which marks the line a note is about and hides nothing.
 */
private fun holdCaretSolidForCapture() {
    val w = window.asDynamic()
    val editor = w.globalEditor ?: return
    if (w.__afieldCaptureCursorBlinking == undefined) {
        val current = runCatching { editor.getRawOptions().cursorBlinking as? String }.getOrNull()
        w.__afieldCaptureCursorBlinking = current ?: "blink"
        val style = runCatching { editor.getRawOptions().cursorStyle as? String }.getOrNull()
        w.__afieldCaptureCursorStyle = style ?: "block"
    }
    runCatching { editor.updateOptions(js("({ cursorBlinking: 'solid', cursorStyle: 'line-thin' })")) }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun restoreCaptureCursorBlinking() {
    val w = window.asDynamic()
    val previous = w.__afieldCaptureCursorBlinking as? String ?: return
    val editor = w.globalEditor
    if (editor != null) {
        val options = js("({})")
        options.cursorBlinking = previous
        options.cursorStyle = (w.__afieldCaptureCursorStyle as? String) ?: "block"
        runCatching { editor.updateOptions(options) }
    }
    w.__afieldCaptureCursorBlinking = undefined
    w.__afieldCaptureCursorStyle = undefined
}

@Suppress("unused")
private val installRestoreCaptureCursorBlinking = run {
    window.asDynamic().afieldRestoreCaptureCursorBlinking = ::restoreCaptureCursorBlinking
}

private fun prepareMonacoForScreenshotLayout() {
    holdCaretSolidForCapture()
    val container = document.querySelector(".monaco-editor-container") as? HTMLElement ?: return
    val targetWidth = min(860.0, max(760.0, window.innerWidth.toDouble() * 0.38))
    val targetHeight = min(1200.0, max(840.0, window.innerHeight.toDouble() * 0.72))
    container.style.top = "12px"
    container.style.right = "12px"
    container.style.width = "${targetWidth}px"
    container.style.height = "${targetHeight}px"
}

private fun syncEditorToBoxForScreenshot(box: HTMLElement, property: String): Boolean {
    val w = window.asDynamic()
    val editor = w.globalEditor ?: return false
    val monacoValue = w.globalMonaco
    val code = boxPropertyText(box, property) ?: ""

    val selectedArray = eval("[]")
    selectedArray.push(box)
    w.currentSelectedCode = selectedArray
    w.currentSelectedProperty = property
    w.selectedProperty = property

    val language = when (property) {
        "code" -> (box.asDynamic().code_language as? String) ?: monacoFindLanguageForPropertyName(property)
        "contents" -> (box.asDynamic().contents_language as? String) ?: monacoFindLanguageForPropertyName(property)
        else -> monacoFindLanguageForPropertyName(property)
    }

    runCatching { w.clear(editor) }
    runCatching { w.pauseLineTracking() }
    runCatching { editor.setValue(code.replace("\r\n", "\n")) }
    runCatching { editor.getModel()?.setEOL(0) }
    runCatching { monacoValue?.editor?.setModelLanguage(editor.getModel(), language.lowercase()) }
    runCatching {
        editor._domElement
            .querySelectorAll(".hoverlay")[0]
            .innerHTML = "$property <i>($language)</i>"
    }
    runCatching { editor.setScrollTop(0) }
    runCatching { editor.setScrollLeft(0) }
    runCatching { editor.setPosition(js("{ lineNumber: 1, column: 1 }")) }
    runCatching { editor.revealLineNearTop(1) }
    runCatching { w.unpauseLineTracking() }
    layoutGlobalEditor()
    return true
}

private fun switchSelectionToProperty(
    uid: String,
    property: String,
    syncEditor: Boolean = false,
    applyScreenshotLayout: Boolean = false
): Boolean {
    val box = findBoxElementByUid(uid) ?: return false
    selected.toList().forEach {
        if (it != box) {
            deselectBox(it)
        }
    }
    selectedProperty = property
    window.unsafeCast<dynamic>().selectedProperty = selectedProperty
    if (!selected.contains(box)) {
        explicitPropertySelectionInProgress = true
        try {
            selectBox(box)
        } finally {
            explicitPropertySelectionInProgress = false
        }
    } else {
        window.unsafeCast<dynamic>().selectedList = selectedList
        selectionContextChanged()
    }
    if (!syncEditor) return true
    runCatching { switchToMonaco() }
    if (applyScreenshotLayout) {
        prepareMonacoForScreenshotLayout()
    }
    layoutGlobalEditor()
    return window.asDynamic().globalEditor != null
}

private fun openNestedDisclosuresForNavigation(targetBox: HTMLElement) {
    fun openFor(box: HTMLElement) {
        val nestedIn = box.unsafeCast<dynamic>().box_nestedIn as? String ?: return
        val parentBox = findBoxElementByUid(nestedIn) ?: return
        openFor(parentBox)

        val outputArea = parentBox.getElementsByClassName("outputArea").asList().firstOrNull() as? HTMLElement ?: return
        outputArea.style.visibility = "visible"
        outputArea.style.height = "auto"

        val contain = outputArea.querySelector(".disclosure-group") ?: return
        val inter = disclosureInterfaceFor(contain) ?: return
        runCatching {
            (inter.open as (String) -> Unit).invoke("nested")
        }.onFailure {
            console.info("[nav] failed to open nested disclosure", it)
        }
    }

    openFor(targetBox)
}

private fun navigateToBoxProperty(
    uid: String,
    property: String = "code",
    scrollToLine: Int? = null,
    highlightLine: Int? = null,
    syncEditor: Boolean = true,
    center: Boolean = false,
    column: Int = -1,
    endColumn: Int = -1
): Boolean {
    val box = findBoxElementByUid(uid) ?: return false
    openNestedDisclosuresForNavigation(box)
    switchSelectionToProperty(uid, property, syncEditor = syncEditor)
    val targetLine = scrollToLine ?: highlightLine

    setTimeout({
        runCatching {
            if (center) centerBoxForMarkdownNavigation(box) else ensureBoxVisibleForNavigation(box)
        }.onFailure {
            console.info("[nav] failed to scroll to box", it)
        }
    }, 50)

    if (targetLine != null) {
        setTimeout({
            val w = window.unsafeCast<dynamic>()
            val editor = w.globalEditor
            val monaco = w.globalMonaco
            if (editor != null && editor != undefined && monaco != null && monaco != undefined) {
                runCatching {
                    editor.revealLineInCenter(targetLine)
                    val lineToHighlight = highlightLine ?: targetLine
                    // A column range selects that stretch of the line (a study card's number) and hands
                    // the keyboard to the editor, so typing replaces it.
                    val from = if (column > 0) column else 1
                    val to = if (column > 0 && endColumn > column) endColumn else from
                    val range = js("new monaco.Range(lineToHighlight, from, lineToHighlight, to)")
                    editor.setSelection(range)
                    editor.revealLineInCenter(lineToHighlight)
                    if (column > 0) editor.focus()
                }.onFailure {
                    console.info("[nav] failed to scroll to line", it)
                }
            }
        }, 100)
    }

    return true
}

private fun editorIsSyncedFor(box: HTMLElement, property: String): Boolean {
    val expected = boxPropertyText(box, property)?.takeIf { it.isNotBlank() } ?: return true
    val actual = globalEditorText() ?: return false
    return normalizeEditorSyncText(actual) == normalizeEditorSyncText(expected)
}

private fun editorHasRenderableContentFor(box: HTMLElement, property: String): Boolean {
    val expected = normalizeEditorSyncText(boxPropertyText(box, property)) ?: return true
    val actual = normalizeEditorSyncText(globalEditorText())
    if (actual == expected) return true
    if (actual != null && actual.isBlank()) return false

    val expectedFirst = expected.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
    val actualFirst = actual?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
    if (expectedFirst != null && expectedFirst == actualFirst) return true

    val editorDomText = runCatching {
        val container = visibleMonacoContainer() ?: return@runCatching null
        val lines = container.querySelector(".view-lines") ?: return@runCatching null
        lines.textContent?.replace('\u00A0', ' ')
    }.getOrNull()
    val editorFirst = editorDomText
        ?.lineSequence()
        ?.firstOrNull { it.isNotBlank() }
        ?.trim()
    return editorFirst != null && expectedFirst != null && editorFirst.startsWith(expectedFirst)
}

private fun editorSyncDebug(box: HTMLElement, property: String): String {
    val expected = normalizeEditorSyncText(boxPropertyText(box, property))
    val actual = normalizeEditorSyncText(globalEditorText())
    val visible = visibleMonacoContainer() != null
    val editorExists = window.asDynamic().globalEditor != null
    val domText = runCatching {
        val container = document.querySelector(".monaco-editor-container") as? HTMLElement ?: return@runCatching null
        val lines = container.querySelector(".view-lines") ?: return@runCatching null
        lines.textContent?.replace('\u00A0', ' ')
    }.getOrNull()
    val expectedFirst = expected?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
    val actualFirst = actual?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
    val domFirst = domText?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
    return buildString {
        append("editor=")
        append(editorExists)
        append(" visible=")
        append(visible)
        append(" expectedLen=")
        append(expected?.length ?: -1)
        append(" actualLen=")
        append(actual?.length ?: -1)
        append(" expectedFirst=")
        append(expectedFirst ?: "<null>")
        append(" actualFirst=")
        append(actualFirst ?: "<null>")
        append(" domFirst=")
        append(domFirst ?: "<null>")
    }
}

private fun applyViewportTranslation(
    nextX: Double,
    nextY: Double,
    panning: Boolean = false,
    refreshSpatial: Boolean = true,
    syncServer: Boolean = true,
    zoom: Double = mainZoom
) {
    val zoomChanged = zoom != mainZoom
    mainZoom = zoom
    val mc = mainCanvas.unsafeCast<dynamic>()
    (mc as HTMLElement).style.transform = mainCanvasTransform(nextX, nextY)
    mainTranslation = listOf(nextX, nextY)
    mc.data_x = nextX
    mc.data_y = nextY

    updateBackgroundGrid(nextX, nextY, panning = panning)

    if (syncServer) {
        send(
            Message("set-viewport").payload(
                Set_rect(
                    uid = "root",
                    property = "viewport",
                    value = Rect(nextX, nextY, window.innerWidth.toDouble(), window.innerHeight.toDouble())
                )
            )
        )
    }

    drawingLayerViewportChanged(nextX, nextY)
    canvasGestures.requestConnectionGroupRebuild(translationOnly = !zoomChanged)
    repositionSlideNoteOverlayIfVisible()
    if (refreshSpatial && spatialNavActive) {
        refreshSpatialNavTargets()
    }
}

/** The drawing layer was panned or zoomed from inside Excalidraw; move Field's canvas to match. */
fun panViewportForDrawingLayer(nextX: Double, nextY: Double, zoom: Double) {
    if (nextX == mainTranslation[0] && nextY == mainTranslation[1] && zoom == mainZoom) return
    cancelNavigationPanAnimation()
    val clamped = zoom.coerceIn(MIN_CANVAS_ZOOM, MAX_CANVAS_ZOOM)
    if (clamped == zoom) {
        applyViewportTranslation(nextX, nextY, zoom = zoom)
    } else {
        // Excalidraw's range is wider than Field's. Keep the world point at the window centre
        // fixed while clamping, or the translation (computed for the unclamped zoom) would jump.
        val cx = window.innerWidth / 2.0
        val cy = window.innerHeight / 2.0
        val worldX = (cx - nextX) / zoom
        val worldY = (cy - nextY) / zoom
        applyViewportTranslation(cx - worldX * clamped, cy - worldY * clamped, zoom = clamped)
    }
}

/**
 * Zooms the canvas to [zoom], keeping the world point under screen point ([anchorX], [anchorY])
 * where it is.
 */
fun setCanvasZoom(zoom: Double, anchorX: Double, anchorY: Double) {
    val next = zoom.coerceIn(MIN_CANVAS_ZOOM, MAX_CANVAS_ZOOM)
    if (next == mainZoom) return
    val worldX = (anchorX - mainTranslation[0]) / mainZoom
    val worldY = (anchorY - mainTranslation[1]) / mainZoom
    cancelNavigationPanAnimation()
    applyViewportTranslation(anchorX - worldX * next, anchorY - worldY * next, zoom = next)
    bumpBackgroundVisibility()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun resetCanvasZoom() = setCanvasZoom(1.0, window.innerWidth / 2.0, window.innerHeight / 2.0)

private fun registerCanvasZoomActions() {
    val reset: dynamic = js("({})")
    reset.id = "reset-canvas-zoom"
    reset.label = "Reset Canvas Zoom"
    reset.detail = "Return the canvas to 100%, keeping the centre of the window where it is"
    reset.run = { resetCanvasZoom() }
    val builtin = allActions.builtin ?: js("[]")
    builtin.push(reset)
    allActions.builtin = builtin
    updateActions()
    window.asDynamic().setCanvasZoom = ::setCanvasZoom
    window.asDynamic().resetCanvasZoom = ::resetCanvasZoom
    window.asDynamic().canvasZoom = { mainZoom }
}

private fun cancelNavigationPanAnimation() {
    navigationPanAnimationGeneration += 1
    navigationPanAnimationHandle?.let { window.cancelAnimationFrame(it) }
    navigationPanAnimationHandle = null
}

private fun animateViewportTranslation(
    nextX: Double,
    nextY: Double,
    durationMs: Double = 220.0,
    refreshSpatialDuringAnimation: Boolean = spatialNavActive
) {
    val startX = mainTranslation[0]
    val startY = mainTranslation[1]
    val dx = nextX - startX
    val dy = nextY - startY
    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
    if (distance < 1.0 || durationMs <= 0.0) {
        applyViewportTranslation(nextX, nextY, panning = false, refreshSpatial = refreshSpatialDuringAnimation)
        return
    }

    cancelNavigationPanAnimation()
    val generation = navigationPanAnimationGeneration
    val startAt = window.performance.now()

    fun easeInOutQuint(t: Double): Double {
        return if (t < 0.5) {
            16.0 * t * t * t * t * t
        } else {
            val x = -2.0 * t + 2.0
            1.0 - (x * x * x * x * x) / 2.0
        }
    }

    fun frame(now: Double) {
        if (generation != navigationPanAnimationGeneration) return

        val t = ((now - startAt) / durationMs).coerceIn(0.0, 1.0)
        val eased = easeInOutQuint(t)
        val x = startX + dx * eased
        val y = startY + dy * eased

        val done = t >= 0.999
        applyViewportTranslation(
            nextX = x,
            nextY = y,
            panning = false,
            refreshSpatial = refreshSpatialDuringAnimation,
            syncServer = done
        )

        if (done) {
            navigationPanAnimationHandle = null
        } else {
            navigationPanAnimationHandle = window.requestAnimationFrame { ts -> frame(ts) }
        }
    }

    navigationPanAnimationHandle = window.requestAnimationFrame { ts -> frame(ts) }
}

private fun centerViewportOn(rect: Rect) {
    val dx = window.innerWidth / 2.0 - (rect.x + rect.w / 2.0)
    val dy = window.innerHeight / 2.0 - (rect.y + rect.h / 2.0)
    val nextX = mainTranslation[0] + dx
    val nextY = mainTranslation[1] + dy

    applyViewportTranslation(nextX, nextY, panning = false)
}

private fun isOverlayVisible(element: Element?): Boolean {
    if (element == null) return false
    val style = window.getComputedStyle(element)
    if (style.display == "none" || style.visibility == "hidden") return false
    val opacity = style.opacity.toDoubleOrNull() ?: 1.0
    if (opacity <= 0.05) return false
    val rect = element.getBoundingClientRect()
    return rect.width > 1.0 && rect.height > 1.0
}

private fun navigationSafeViewportRect(padding: Double = 16.0, occluderGap: Double = 14.0): Rect {
    val viewportWidth = window.innerWidth.toDouble()
    val viewportHeight = window.innerHeight.toDouble()
    val base = Rect(
        padding,
        padding,
        (viewportWidth - padding * 2.0).coerceAtLeast(1.0),
        (viewportHeight - padding * 2.0).coerceAtLeast(1.0)
    )

    val occluderRects = listOf(".docs-iframe-container", ".monaco-editor-container")
        .mapNotNull { selector ->
            val element = document.querySelector(selector) as? HTMLElement ?: return@mapNotNull null
            if (!isOverlayVisible(element)) return@mapNotNull null
            val rect = element.getBoundingClientRect()
            Rect(rect.left, rect.top, rect.width, rect.height)
        }

    val occluder = occluderRects.maxByOrNull { it.w * it.h } ?: return base
    val occluderRight = occluder.right
    val occluderBottom = occluder.bottom

    val overlapsBase = occluder.x < base.right &&
            occluderRight > base.x &&
            occluder.y < base.bottom &&
            occluderBottom > base.y
    if (!overlapsBase) return base

    // Monaco/docs panel is generally side-docked; carve it out horizontally.
    val leftSafeWidth = (occluder.x - occluderGap) - base.x
    val rightSafeX = max(base.x, occluderRight + occluderGap)
    val rightSafeWidth = base.right - rightSafeX
    val carved = if (leftSafeWidth >= rightSafeWidth) {
        Rect(base.x, base.y, leftSafeWidth.coerceAtLeast(0.0), base.h)
    } else {
        Rect(rightSafeX, base.y, rightSafeWidth.coerceAtLeast(0.0), base.h)
    }

    val minSafeWidth = viewportWidth * 0.22
    return if (carved.w >= minSafeWidth) carved else base
}

internal fun ensureBoxVisibleForNavigation(target: HTMLElement) {
    val rect = target.getBoundingClientRect()
    if (rect.width <= 0.0 || rect.height <= 0.0) return

    val safe = navigationSafeViewportRect()
    val safeRight = safe.right
    val safeBottom = safe.bottom
    val marginX = min(48.0, safe.w * 0.2)
    val marginY = min(42.0, safe.h * 0.2)
    val leftBound = safe.x + marginX
    val rightBound = safeRight - marginX
    val topBound = safe.y + marginY
    val bottomBound = safeBottom - marginY

    val targetCenterX = rect.left + rect.width / 2.0
    val targetCenterY = rect.top + rect.height / 2.0
    val safeCenterX = safe.x + safe.w / 2.0
    val safeCenterY = safe.y + safe.h / 2.0

    var dx = 0.0
    var dy = 0.0

    if (rightBound <= leftBound || rect.width > rightBound - leftBound) {
        dx = safeCenterX - targetCenterX
    } else if (rect.left < leftBound) {
        dx = leftBound - rect.left
    } else if (rect.right > rightBound) {
        dx = rightBound - rect.right
    }

    if (bottomBound <= topBound || rect.height > bottomBound - topBound) {
        dy = safeCenterY - targetCenterY
    } else if (rect.top < topBound) {
        dy = topBound - rect.top
    } else if (rect.bottom > bottomBound) {
        dy = bottomBound - rect.bottom
    }

    if (abs(dx) < 0.5 && abs(dy) < 0.5) return
    animateViewportTranslation(
        nextX = mainTranslation[0] + dx,
        nextY = mainTranslation[1] + dy,
        durationMs = 420.0,
        refreshSpatialDuringAnimation = spatialNavActive
    )
}

/** Center a linked box in the exposed canvas, accounting for the guide and editor together. */
private fun centerBoxForMarkdownNavigation(target: HTMLElement) {
    val rect = target.getBoundingClientRect()
    if (rect.width <= 0.0 || rect.height <= 0.0) return
    var spaces = listOf(Rect(16.0, 16.0, window.innerWidth - 32.0, window.innerHeight - 32.0))
    for (selector in listOf(".docs-iframe-container", ".monaco-editor-container", "#document-guide")) {
        val overlay = document.querySelector(selector) ?: continue
        if (!isOverlayVisible(overlay)) continue
        val r = overlay.getBoundingClientRect()
        val obstacle = Rect(r.left - 14, r.top - 14, r.width + 28, r.height + 28)
        spaces = spaces.flatMap { area ->
            if (obstacle.x >= area.right || obstacle.right <= area.x || obstacle.y >= area.bottom || obstacle.bottom <= area.y) listOf(area)
            else listOf(
                Rect(area.x, area.y, (obstacle.x - area.x).coerceAtLeast(0.0), area.h),
                Rect(obstacle.right, area.y, (area.right - obstacle.right).coerceAtLeast(0.0), area.h),
                Rect(area.x, area.y, area.w, (obstacle.y - area.y).coerceAtLeast(0.0)),
                Rect(area.x, obstacle.bottom, area.w, (area.bottom - obstacle.bottom).coerceAtLeast(0.0))
            ).filter { it.w >= 96 && it.h >= 96 }
        }
    }
    val safe = spaces.maxByOrNull { it.w * it.h } ?: navigationSafeViewportRect()
    animateViewportTranslation(
        nextX = mainTranslation[0] + safe.x + safe.w / 2 - rect.left - rect.width / 2,
        nextY = mainTranslation[1] + safe.y + safe.h / 2 - rect.top - rect.height / 2,
        durationMs = 420.0,
        refreshSpatialDuringAnimation = spatialNavActive
    )
}

private fun prepareBoxScreenshot(req: BoxScreenshotRequest): BoxScreenshotResponse {
    if (window.asDynamic().fieldClientOpen != true || window.asDynamic().fieldBackdoorDisconnectedOverlayActive == true) {
        return BoxScreenshotResponse(uid = req.uid, success = false, message = "Field page is not connected")
    }
    val box = findBoxElementByUid(req.uid)
        ?: return BoxScreenshotResponse(uid = req.uid, success = false, message = "box ${req.uid} not found")

    if (!req.includeNotifications) hideNotificationsForScreenshot()
    if (req.hideMonaco) {
        if (!req.preserveSelection) clearSelectionForScreenshot()
        runCatching { hideMonaco() }
    }

    val iframeOutputRect = iframeOutputRectForScreenshot(
        box,
        openOutputs = req.openOutputs,
        panIntoView = req.panIntoView,
        padding = req.padding
    )

    val targetRect = if (iframeOutputRect != null) {
        iframeOutputRect
    } else {
        val revealedRect = revealBoxForScreenshot(
            box,
            openOutputs = req.openOutputs,
            panIntoView = req.panIntoView,
            padding = req.padding
        ) ?: return BoxScreenshotResponse(
            uid = req.uid,
            success = false,
            message = "no geometry for ${req.uid}"
        )

        // For docs captures that open outputs, crop to the visible disclosures instead
        // of the whole box body to avoid large empty header/code regions.
        if (req.openOutputs) {
            unionRects(collectOutputScreenshotRects(box)) ?: revealedRect
        } else {
            revealedRect
        }
    }

    val guideAwareRect = unionRects(listOf(targetRect) + collectGuideScreenshotRects(box)) ?: targetRect

    val padded = guideAwareRect.expand(req.padding)
    val clip = padded.translate(window.scrollX, window.scrollY)
    val viewport = Rect(
        window.scrollX,
        window.scrollY,
        window.innerWidth.toDouble(),
        window.innerHeight.toDouble()
    )

    if (clip.w <= 0.0 || clip.h <= 0.0) {
        return BoxScreenshotResponse(
            uid = req.uid,
            success = false,
            message = "invalid clip for ${req.uid}"
        )
    }

    return BoxScreenshotResponse(
        uid = req.uid,
        success = true,
        clip = clip,
        viewport = viewport
    )
}

private fun prepareWindowScreenshot(req: WindowScreenshotRequest): WindowScreenshotResponse {
    if (window.asDynamic().fieldClientOpen != true || window.asDynamic().fieldBackdoorDisconnectedOverlayActive == true) {
        return WindowScreenshotResponse(uid = req.uid, success = false, message = "Field page is not connected")
    }
    val box = req.uid?.let(::findBoxElementByUid)
    if (req.uid != null && box == null) {
        return WindowScreenshotResponse(uid = req.uid, success = false, message = "box ${req.uid} not found")
    }
    if (req.showEditor && box == null) {
        return WindowScreenshotResponse(uid = req.uid, success = false, message = "showEditor requires a target box")
    }
    if (req.editorOnly && (!req.showEditor || box == null)) {
        return WindowScreenshotResponse(
            uid = req.uid,
            success = false,
            message = "editorOnly requires a target box and showEditor"
        )
    }

    selectedProperty = req.property
    window.unsafeCast<dynamic>().selectedProperty = selectedProperty
    if (!req.includeNotifications) hideNotificationsForScreenshot()

    if (req.showEditor && box != null) {
        if (!req.preserveSelection && !switchSelectionToProperty(req.uid, req.property, syncEditor = true, applyScreenshotLayout = true)) {
            // Distinguish "this box's text has not arrived yet" -- which retrying fixes -- from
            // "this page has no Monaco at all", which it never does: a bootstrap failure leaves a
            // connected page with a live websocket and no editor, and the undifferentiated message
            // sent everyone hunting timing problems that were not there.
            val reason = if (window.asDynamic().globalEditor == null) {
                "no Monaco editor exists on this page (window.globalEditor is unset -- " +
                    "the editor bootstrap failed, check the browser console)"
            } else {
                "selection did not switch to ${req.property}"
            }
            return WindowScreenshotResponse(uid = req.uid, success = false, message = "editor not synced yet; $reason")
        }
        if (req.preserveSelection) {
            prepareMonacoForScreenshotLayout()
            layoutGlobalEditor()
        }
    } else {
        if (!req.preserveSelection) clearSelectionForScreenshot()
        runCatching { hideMonaco() }
    }

    if (box != null) {
        revealBoxForScreenshot(
            box,
            openOutputs = req.openOutputs,
            panIntoView = req.panIntoView,
            padding = req.padding
        )
    }

    if (req.showEditor && box != null && !editorHasRenderableContentFor(box, req.property)) {
        return WindowScreenshotResponse(
            uid = req.uid,
            success = false,
            message = "editor not synced yet; ${editorSyncDebug(box, req.property)}"
        )
    }

    val viewport = currentViewportRect()
    if (req.editorOnly) {
        val editor = visibleMonacoContainer()
            ?: return WindowScreenshotResponse(uid = req.uid, success = false, message = "editor not visible yet")
        fitEditorPaneToContentAndNote(editor)
        val clip = clipForRenderedEditorContent(req.padding)
            ?: return WindowScreenshotResponse(uid = req.uid, success = false, message = "invalid editor clip")
        return WindowScreenshotResponse(
            uid = req.uid,
            success = true,
            clip = clip,
            viewport = viewport
        )
    }

    if (req.showEditor && box != null) {
        val clip = clipForWindowEditorCapture(box, req.padding)
            ?: return WindowScreenshotResponse(uid = req.uid, success = false, message = "invalid window-editor clip")
        return WindowScreenshotResponse(
            uid = req.uid,
            success = true,
            clip = clip,
            viewport = viewport
        )
    }

    visibleDocsIframeElement()?.let {
        val clip = clipForVisibleDocsIframe(req.padding)
            ?: return WindowScreenshotResponse(uid = req.uid, success = false, message = "invalid docs iframe clip")
        return WindowScreenshotResponse(
            uid = req.uid,
            success = true,
            clip = clip,
            viewport = viewport
        )
    }

    return WindowScreenshotResponse(
        uid = req.uid,
        success = true,
        viewport = viewport
    )
}

private fun dynamicString(value: dynamic): String? = value as? String

private fun dynamicDouble(value: dynamic, default: Double): Double =
    (value as? Number)?.toDouble() ?: default

private fun dynamicBoolean(value: dynamic, default: Boolean): Boolean =
    (value as? Boolean) ?: default

private fun rawToBoxScreenshotRequest(raw: dynamic) = BoxScreenshotRequest(
    uid = dynamicString(raw?.uid) ?: "",
    padding = dynamicDouble(raw?.padding, 12.0),
    panIntoView = dynamicBoolean(raw?.panIntoView, true),
    hideMonaco = dynamicBoolean(raw?.hideMonaco, true),
    openOutputs = dynamicBoolean(raw?.openOutputs, false),
    preserveSelection = dynamicBoolean(raw?.preserveSelection, false),
    includeNotifications = dynamicBoolean(raw?.includeNotifications, false)
)

private fun rawToWindowScreenshotRequest(raw: dynamic) = WindowScreenshotRequest(
    uid = dynamicString(raw?.uid),
    property = dynamicString(raw?.property) ?: "code",
    panIntoView = dynamicBoolean(raw?.panIntoView, true),
    showEditor = dynamicBoolean(raw?.showEditor, false),
    openOutputs = dynamicBoolean(raw?.openOutputs, false),
    editorOnly = dynamicBoolean(raw?.editorOnly, false),
    padding = dynamicDouble(raw?.padding, 12.0),
    waitMs = dynamicDouble(raw?.waitMs, 0.0),
    preserveSelection = dynamicBoolean(raw?.preserveSelection, false),
    includeNotifications = dynamicBoolean(raw?.includeNotifications, false)
)

private fun prepareBoxScreenshotJson(raw: dynamic): String {
    val request = rawToBoxScreenshotRequest(raw)
    val response = runCatching { prepareBoxScreenshot(request) }.getOrElse { throwable ->
        BoxScreenshotResponse(
            uid = request.uid,
            success = false,
            message = throwable.message ?: "Unexpected box screenshot preparation failure"
        )
    }
    return Json.encodeToString(response)
}

private fun prepareWindowScreenshotJson(raw: dynamic): String {
    val request = rawToWindowScreenshotRequest(raw)
    val response = runCatching { prepareWindowScreenshot(request) }.getOrElse { throwable ->
        WindowScreenshotResponse(
            uid = request.uid,
            success = false,
            message = throwable.message ?: "Unexpected window screenshot preparation failure"
        )
    }
    return Json.encodeToString(response)
}

private fun attachPanToNestedArea(container: HTMLElement) {
    val content = container.querySelector(".nested-content") as? HTMLElement ?: run {
        val nc = document.createElement("div") as HTMLElement
        nc.classList.add("nested-content")
        container.appendChild(nc)
        nc
    }

    container.style.asDynamic().overflow = "hidden"
    container.style.asDynamic().touchAction = "none"
    container.style.asDynamic().width = "100%"
    container.style.asDynamic().height = "100%"

    fun applyTranslation(x: Double, y: Double) {
        content.style.transform = "translate(${x}px, ${y}px)"
        container.asDynamic().data_x = x
        container.asDynamic().data_y = y
        canvasGestures.rebuildConnectionGroup()
    }

    // Initialize stored offsets if missing
    val initX = (container.asDynamic().data_x as? Double) ?: 0.0
    val initY = (container.asDynamic().data_y as? Double) ?: 0.0
    applyTranslation(initX, initY)

    if (container.asDynamic().panHandlersAttached == true) return
    container.asDynamic().panHandlersAttached = true

    var panActive = false
    var startX = 0.0
    var startY = 0.0
    var baseX = 0.0
    var baseY = 0.0

    val wheelHandler: (dynamic) -> dynamic = { event: dynamic ->
        event.preventDefault()
        event.stopPropagation()

        val currentX = (container.asDynamic().data_x as? Double) ?: 0.0
        val currentY = (container.asDynamic().data_y as? Double) ?: 0.0
        val dx = -(event.deltaX as? Double ?: 0.0)
        val dy = -(event.deltaY as? Double ?: 0.0)
        applyTranslation(currentX + dx, currentY + dy)
        null
    }
    container.onwheel = wheelHandler
    (container.parentElement as? HTMLElement)?.onwheel = wheelHandler

    container.onmousedown = onmousedown@{ event ->
        val buttons = event.buttons.toInt()
        val isMiddleButton = buttons == 4 || event.button.toInt() == 1
        if (!isMiddleButton) {
            return@onmousedown null
        }
        panActive = true
        startX = event.clientX.toDouble()
        startY = event.clientY.toDouble()
        baseX = (container.asDynamic().data_x as? Double) ?: 0.0
        baseY = (container.asDynamic().data_y as? Double) ?: 0.0
        try {
            event.asDynamic().target?.setPointerCapture?.invoke(event.asDynamic().pointerId)
        } catch (_: dynamic) {
        }
        event.preventDefault()
        event.stopPropagation()
        null
    }

    container.onmousemove = onmousemove@{ event ->
        if (!panActive) return@onmousemove null
        val dx = event.clientX - startX
        val dy = event.clientY - startY
        applyTranslation(baseX + dx, baseY + dy)
        event.preventDefault()
        event.stopPropagation()
        null
    }

    val endPan: (dynamic) -> Unit = let@{ event: dynamic ->
        if (!panActive) return@let
        panActive = false
        event?.preventDefault()
        event?.stopPropagation()
    }

    container.onmouseup = endPan
    container.onmouseleave = endPan
    container.onpointerup = endPan
    container.onpointercancel = endPan
}

private fun normalizeOffset(value: Double, spacing: Double): Double {
    var mod = value % spacing
    if (mod < 0) mod += spacing
    return mod
}

private fun applyBackgroundAlphas() {
    if (!::mainViewport.isInitialized) return
    mainViewport.style.setProperty("--dot-alpha-25", dotAlphaSmall.toString())
    mainViewport.style.setProperty("--dot-alpha-100", dotAlphaLarge.toString())
    mainViewport.style.setProperty("--axis-alpha", axisAlpha.toString())
    renderWebGLBackground()
}

private fun applyEditorOpacity() {
    val container = document.querySelector(".monaco-editor-container") as? HTMLElement ?: return
    if (container.style.visibility == "hidden" || container.style.opacity == "0") return
    container.style.opacity = editorOpacity.toString()
}

private fun installWebGLBackground(host: HTMLElement) {
    val sharedBackground = window.asDynamic().FieldWebGLBackground
    if (sharedBackground != null && sharedBackground != js("undefined")) {
        val sharedInstaller = sharedBackground.install
        if (sharedInstaller != null && sharedInstaller != js("undefined")) {
            sharedInstaller(host)
            return
        }
    }

    val installer = js(
        """
        (function(host) {
            if (!host || host.__fieldWebglBackground) return;

            const canvas = document.createElement("canvas");
            canvas.className = "field-webgl-background";
            host.insertBefore(canvas, host.firstChild);

            const gl = canvas.getContext("webgl", {
                alpha: false,
                antialias: false,
                depth: false,
                stencil: false,
                preserveDrawingBuffer: true
            }) || canvas.getContext("experimental-webgl", {
                alpha: false,
                antialias: false,
                depth: false,
                stencil: false,
                preserveDrawingBuffer: true
            });

            if (!gl) {
                canvas.remove();
                host.__fieldWebglBackground = { ok: false };
                return;
            }

            const vertexSource = `
                attribute vec2 a_position;
                void main() {
                    gl_Position = vec4(a_position, 0.0, 1.0);
                }
            `;

            const fragmentSource = `
                precision highp float;

                uniform vec2 u_resolution;
                uniform vec2 u_translation;
                uniform float u_dotSmallAlpha;
                uniform float u_dotLargeAlpha;
                uniform float u_axisAlpha;
                uniform float u_dpr;
                uniform float u_zoom;

                float hash(vec2 p) {
                    p = fract(p * vec2(0.1031, 0.11369));
                    p += dot(p, p.yx + 19.19);
                    return fract((p.x + p.y) * p.x);
                }

                // world is in world units * dpr; the dot radii stay in screen pixels at any zoom.
                float dotMask(vec2 world, float spacing, float r0, float r1) {
                    vec2 q = mod(world - vec2(0.5 * u_dpr / u_zoom), spacing);
                    q = min(q, spacing - q);
                    float d = length(q) * u_zoom;
                    return 1.0 - smoothstep(r0, r1, d);
                }

                float roundedRectSdf(vec2 p, vec2 halfSize, float radius) {
                    vec2 q = abs(p) - halfSize + vec2(radius);
                    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
                }

                void main() {
                    vec2 frag = vec2(gl_FragCoord.x, u_resolution.y - gl_FragCoord.y);
                    vec2 halfResolution = u_resolution * 0.5;
                    float falloff = 450.0 * u_dpr;
                    vec2 edgeDistance = min(frag, u_resolution - frag);
                    vec2 edgeProximity = 1.0 - clamp(edgeDistance / falloff, 0.0, 1.0);
                    float shade = min(length(edgeProximity), 1.0) * 0.28;

                    vec3 centerColor = vec3(24.0 / 255.0);
                    vec3 edgeColor = vec3(2.0 / 255.0);
                    vec3 color = mix(centerColor, edgeColor, shade);

                    float dither = (hash(frag) - 0.5) * (2.4 / 255.0);
                    color += vec3(dither);

                    vec2 world = (frag - u_translation) / u_zoom;
                    // Zoomed out, the 25px dots would crowd into a haze; fade them first.
                    float smallFade = smoothstep(0.35, 0.7, u_zoom);
                    float largeFade = smoothstep(0.1, 0.2, u_zoom);
                    float smallDots = dotMask(world, 25.0 * u_dpr, 0.75 * u_dpr, 1.25 * u_dpr) * smallFade;
                    float largeDots = dotMask(world, 100.0 * u_dpr, 1.25 * u_dpr, 2.05 * u_dpr) * largeFade;
                    float dotAlpha = clamp(smallDots * u_dotSmallAlpha + largeDots * u_dotLargeAlpha, 0.0, 0.32);
                    color = mix(color, vec3(1.0), dotAlpha);

                    float axisX = 1.0 - smoothstep(0.45 * u_dpr, 1.05 * u_dpr, abs(frag.x - u_translation.x));
                    float axisY = 1.0 - smoothstep(0.45 * u_dpr, 1.05 * u_dpr, abs(frag.y - u_translation.y));
                    color = mix(color, vec3(1.0), max(axisX, axisY) * u_axisAlpha);

                    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
                }
            `;

            function compile(type, source) {
                const shader = gl.createShader(type);
                gl.shaderSource(shader, source);
                gl.compileShader(shader);
                if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
                    const log = gl.getShaderInfoLog(shader);
                    gl.deleteShader(shader);
                    throw new Error(log || "shader compile failed");
                }
                return shader;
            }

            try {
                const program = gl.createProgram();
                gl.attachShader(program, compile(gl.VERTEX_SHADER, vertexSource));
                gl.attachShader(program, compile(gl.FRAGMENT_SHADER, fragmentSource));
                gl.linkProgram(program);
                if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
                    throw new Error(gl.getProgramInfoLog(program) || "shader link failed");
                }

                const buffer = gl.createBuffer();
                gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
                gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([
                    -1, -1,
                     1, -1,
                    -1,  1,
                     1,  1
                ]), gl.STATIC_DRAW);

                const locations = {
                    position: gl.getAttribLocation(program, "a_position"),
                    resolution: gl.getUniformLocation(program, "u_resolution"),
                    translation: gl.getUniformLocation(program, "u_translation"),
                    dotSmallAlpha: gl.getUniformLocation(program, "u_dotSmallAlpha"),
                    dotLargeAlpha: gl.getUniformLocation(program, "u_dotLargeAlpha"),
                    axisAlpha: gl.getUniformLocation(program, "u_axisAlpha"),
                    dpr: gl.getUniformLocation(program, "u_dpr"),
                    zoom: gl.getUniformLocation(program, "u_zoom")
                };

                const state = {
                    ok: true,
                    hasFrame: false,
                    width: 0,
                    height: 0,
                    dpr: 1,
                    tx: 0,
                    ty: 0,
                    dotSmall: 0.02,
                    dotLarge: 0.04,
                    axis: 0.0,
                    zoom: 1.0,
                    renderCount: 0,
                    skippedCount: 0,
                    render(tx, ty, dotSmall, dotLarge, axis, zoom) {
                        const dpr = Math.max(1, window.devicePixelRatio || 1);
                        const width = Math.max(1, Math.round(host.clientWidth * dpr));
                        const height = Math.max(1, Math.round(host.clientHeight * dpr));
                        const nextTx = Number(tx) || 0;
                        const nextTy = Number(ty) || 0;
                        const nextDotSmall = Number(dotSmall) || 0;
                        const nextDotLarge = Number(dotLarge) || 0;
                        const nextAxis = Number(axis) || 0;
                        const nextZoom = Number(zoom) || state.zoom;

                        if (
                            state.hasFrame &&
                            state.width === width &&
                            state.height === height &&
                            state.dpr === dpr &&
                            state.tx === nextTx &&
                            state.ty === nextTy &&
                            state.dotSmall === nextDotSmall &&
                            state.dotLarge === nextDotLarge &&
                            state.axis === nextAxis &&
                            state.zoom === nextZoom
                        ) {
                            state.skippedCount += 1;
                            return false;
                        }

                        state.tx = nextTx;
                        state.ty = nextTy;
                        state.dotSmall = nextDotSmall;
                        state.dotLarge = nextDotLarge;
                        state.axis = nextAxis;
                        state.zoom = nextZoom;
                        state.width = width;
                        state.height = height;
                        state.dpr = dpr;
                        if (canvas.width !== width || canvas.height !== height) {
                            canvas.width = width;
                            canvas.height = height;
                        }

                        gl.viewport(0, 0, width, height);
                        gl.useProgram(program);
                        gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
                        gl.enableVertexAttribArray(locations.position);
                        gl.vertexAttribPointer(locations.position, 2, gl.FLOAT, false, 0, 0);
                        gl.uniform2f(locations.resolution, width, height);
                        gl.uniform2f(locations.translation, state.tx * dpr, state.ty * dpr);
                        gl.uniform1f(locations.dotSmallAlpha, state.dotSmall);
                        gl.uniform1f(locations.dotLargeAlpha, state.dotLarge);
                        gl.uniform1f(locations.axisAlpha, state.axis);
                        gl.uniform1f(locations.dpr, dpr);
                        gl.uniform1f(locations.zoom, state.zoom);
                        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
                        state.hasFrame = true;
                        state.renderCount += 1;
                        return true;
                    }
                };

                host.__fieldWebglBackground = state;
                window.__fieldRenderWebglBackground = function(tx, ty, dotSmall, dotLarge, axis, zoom) {
                    if (host.__fieldWebglBackground && host.__fieldWebglBackground.ok) {
                        return host.__fieldWebglBackground.render(tx, ty, dotSmall, dotLarge, axis, zoom);
                    }
                    return false;
                };

                if (window.ResizeObserver) {
                    new ResizeObserver(function() {
                        state.render(state.tx, state.ty, state.dotSmall, state.dotLarge, state.axis);
                    }).observe(host);
                }
                window.addEventListener("resize", function() {
                    state.render(state.tx, state.ty, state.dotSmall, state.dotLarge, state.axis);
                });
            } catch (error) {
                console.warn("[field-background] WebGL background disabled", error);
                canvas.remove();
                host.__fieldWebglBackground = { ok: false, error: String(error && error.message || error) };
            }
        })
        """
    )
    installer(host)
}

private fun renderWebGLBackground() {
    if (!::mainViewport.isInitialized) return
    val renderer = window.asDynamic().__fieldRenderWebglBackground
    if (renderer == null || renderer == js("undefined")) return
    renderer(mainTranslation[0], mainTranslation[1], dotAlphaSmall, dotAlphaLarge, axisAlpha, mainZoom)
}

private fun stepBackgroundLerp(): Boolean {
    val deltaSmall = dotAlphaSmallTarget - dotAlphaSmall
    val deltaLarge = dotAlphaLargeTarget - dotAlphaLarge
    val deltaAxis = axisAlphaTarget - axisAlpha
    val deltaEditor = editorOpacityTarget - editorOpacity
    val changed =
        abs(deltaSmall) > 0.0005 || abs(deltaLarge) > 0.0005 || abs(deltaAxis) > 0.0005 || abs(deltaEditor) > 0.005

    dotAlphaSmall += deltaSmall * 0.18
    dotAlphaLarge += deltaLarge * 0.18
    axisAlpha += deltaAxis * 0.18
    editorOpacity += deltaEditor * 0.15

    applyBackgroundAlphas()
    applyEditorOpacity()
    return changed
}

private fun scheduleBackgroundLerp() {
    if (backgroundLerpHandle != null) return
    backgroundLerpHandle = window.requestAnimationFrame {
        backgroundLerpHandle = null
        val needsMore = stepBackgroundLerp()
        if (needsMore) {
            scheduleBackgroundLerp()
        }
    }
}

private fun bumpBackgroundVisibility() {
    dotAlphaSmallTarget = dotAlphaSmallActive
    dotAlphaLargeTarget = dotAlphaLargeActive
    axisAlphaTarget = axisAlphaActive
    editorOpacityTarget = editorOpacityPanning
    scheduleBackgroundLerp()

    backgroundFadeTimeoutHandle?.let { window.clearTimeout(it) }
    backgroundFadeTimeoutHandle = window.setTimeout({
        dotAlphaSmallTarget = dotAlphaSmallIdle
        dotAlphaLargeTarget = dotAlphaLargeIdle
        axisAlphaTarget = axisAlphaIdle
        editorOpacityTarget = editorOpacityIdle
        scheduleBackgroundLerp()
    }, 420)
}

fun updateBackgroundGrid(translationX: Double, translationY: Double, panning: Boolean = false) {
    if (!::mainViewport.isInitialized) return

    // The dots are the visual unit for box frames, so their spacing is in world units and scales
    // with the canvas zoom. This CSS-gradient grid is the fallback for when WebGL is unavailable.
    val small = dotSpacingSmall * mainZoom
    val large = dotSpacingLarge * mainZoom
    val posSmall = "${normalizeOffset(translationX, small) - (small / 2) + 0.5}px ${
        normalizeOffset(
            translationY,
            small
        ) - (small / 2) + 0.5
    }px"
    val posLarge = "${normalizeOffset(translationX, large) - (large / 2) + 0.5}px ${
        normalizeOffset(
            translationY,
            large
        ) - (large / 2) + 0.5
    }px"
    val posAxisX = "${translationX}px 0px"
    val posAxisY = "0px ${translationY}px"

    mainViewport.style.backgroundSize = "1px 100%, 100% 1px, ${small}px ${small}px, ${large}px ${large}px, 100% 100%"
    mainViewport.style.backgroundPosition = "$posAxisX, $posAxisY, $posSmall, $posLarge, center center"
    renderWebGLBackground()

    if (panning) {
        bumpBackgroundVisibility()
    }
}

fun initializeBackgroundGrid(host: HTMLElement) {
    mainViewport = host
    host.style.backgroundColor = "rgb(20, 20, 20)"
    host.style.backgroundImage =
        "linear-gradient(90deg, rgba(255,255,255,var(--axis-alpha)) 1px, rgba(255,255,255,0) 1px), " +
                "linear-gradient(0deg, rgba(255,255,255,var(--axis-alpha)) 1px, rgba(255,255,255,0) 1px), " +
                "radial-gradient(circle at 50% 50%, rgba(255,255,255,var(--dot-alpha-25)) 0.8px, rgba(255,255,255,0) 1.2px), " +
                "radial-gradient(circle at 50% 50%, rgba(255,255,255,var(--dot-alpha-100)) 1.3px, rgba(255,255,255,0) 2.0px), " +
                "var(--field-vignette-background)"
    host.style.backgroundRepeat = "no-repeat, no-repeat, repeat, repeat, no-repeat"
    host.style.backgroundSize =
        "1px 100%, 100% 1px, ${dotSpacingSmall}px ${dotSpacingSmall}px, ${dotSpacingLarge}px ${dotSpacingLarge}px, 100% 100%"
    host.style.backgroundPosition = "0px 0px, 0px 0px, 0px 0px, 0px 0px, center center"
    installWebGLBackground(host)
    applyBackgroundAlphas()
}

private fun isEditorFocused(): Boolean {
    val active = document.activeElement ?: return false
    val className = active.asDynamic().className?.toString() ?: ""
    val inMonacoTextArea = active.classList.contains("monaco-mouse-cursor-text")
    val closestMonaco = try {
        active.asDynamic().closest?.call(active, ".monaco-editor") != null
    } catch (e: dynamic) {
        false
    }
    val textInput = active is HTMLTextAreaElement || active is HTMLInputElement
    return inMonacoTextArea || closestMonaco || textInput || className.contains("monaco", ignoreCase = true)
}

var slideNoteAnchorUid: String? = null

fun findBoxByUid(uid: String?): HTMLElement? {
    if (uid.isNullOrBlank()) return null
    val boxes = document.body!!.getElementsByClassName("box").asList()
    return boxes.firstOrNull { it.unsafeCast<dynamic>().uid == uid } as? HTMLElement
}

fun positionSlideNoteOverlay(overlay: HTMLElement, anchorUid: String?) {
    val anchorBox = findBoxByUid(anchorUid) ?: selected.firstOrNull()

    overlay.setAttribute("data-anchor-uid", anchorUid ?: "")
    if (anchorBox != null) {
        overlay.classList.remove("fallback")
        overlay.style.visibility = "hidden"
        overlay.style.display = "block"

        val rect = anchorBox.getBoundingClientRect()
        val margin = 12.0
        val overlayWidth = overlay.offsetWidth.toDouble().coerceAtLeast(1.0)
        val overlayHeight = overlay.offsetHeight.toDouble().coerceAtLeast(1.0)
        val scrollX = window.scrollX
        val scrollY = window.scrollY
        val spaceRight = window.innerWidth - rect.right
        val spaceLeft = rect.left

        var left = rect.right + margin + scrollX
        if (spaceRight < overlayWidth + margin && spaceLeft > spaceRight) {
            left = rect.left - overlayWidth - margin + scrollX
        }

        val minTop = scrollY + margin
        val maxTop = scrollY + window.innerHeight - overlayHeight - margin
        var top = rect.top + scrollY
        top = top.coerceIn(minTop, maxTop)

        overlay.style.left = "${left}px"
        overlay.style.top = "${top}px"
        overlay.style.right = "auto"
        overlay.style.bottom = "auto"
        overlay.style.visibility = "visible"
    } else {
        overlay.classList.add("fallback")
        overlay.style.left = "auto"
        overlay.style.top = "20px"
        overlay.style.right = "20px"
        overlay.style.bottom = "auto"
        overlay.style.visibility = "visible"
    }
}

// Chat UI support
data class ChatWidget(
    val widgetId: String,
    val slideshowId: String,
    val slideNumber: Int,
    val container: HTMLElement,
    val content: HTMLElement,
    val messagesArea: HTMLElement,
    val messageInput: HTMLTextAreaElement
)

private val chatWidgets = mutableMapOf<String, ChatWidget>()

private fun cleanupChatWidgets() {
    val iterator = chatWidgets.iterator()
    while (iterator.hasNext()) {
        val entry = iterator.next()
        if (!entry.value.container.isConnected) {
            iterator.remove()
        }
    }
}

private fun widgetsFor(slideshowId: String, slideNumber: Int): List<ChatWidget> =
    chatWidgets.values.filter { it.slideshowId == slideshowId && it.slideNumber == slideNumber }

fun loadChatMessages(slideshowId: String, slideNumber: Int) {
    send("load-chat-messages", js("{slideshowId: slideshowId, slideNumber: slideNumber}"))
}

fun postChatMessage(slideshowId: String, slideNumber: Int, message: String) {
    val username = URLSearchParams(document.location?.search).get("username") ?: "anonymous"
    send(
        "post-chat-message",
        js("{slideshowId: slideshowId, slideNumber: slideNumber, username: username, message: message}")
    )
}

private fun createChatWidget(
    slideshowId: String,
    slideNumber: Int,
    expanded: Boolean = false,
    showHeader: Boolean = true
): ChatWidget {
    cleanupChatWidgets()
    val widgetId = "chat-${Uuid.random()}"

    val chatContainer = document.createElement("div") as HTMLElement
    chatContainer.id = "chat-container-$widgetId"
    chatContainer.setAttribute("data-chat-widget-id", widgetId)
    chatContainer.setAttribute("data-chat-slideshow", slideshowId)
    chatContainer.setAttribute("data-chat-slide", slideNumber.toString())
    chatContainer.classList.add("slide-chat-widget")
    if (!showHeader) chatContainer.classList.add("slide-chat-widget--embedded")

    val chatContent = document.createElement("div") as HTMLElement
    chatContent.id = "chat-content-$widgetId"
    chatContent.style.display = if (showHeader) {
        if (expanded) "block" else "none"
    } else {
        "block"
    }

    val messagesArea = document.createElement("div") as HTMLElement
    messagesArea.id = "chat-messages-$widgetId"
    messagesArea.classList.add("slide-chat-messages")

    val inputArea = document.createElement("div") as HTMLElement
    inputArea.classList.add("slide-chat-input-area")

    val messageInput = document.createElement("textarea") as HTMLTextAreaElement
    messageInput.id = "chat-input-$widgetId"
    messageInput.setAttribute("placeholder", "Add a comment + return")
    messageInput.classList.add("slide-chat-input")

    fun sendCurrentMessage() {
        val message = messageInput.value.trim()
        if (message.isNotBlank()) {
            postChatMessage(slideshowId, slideNumber, message)
            messageInput.value = ""
        }
    }

    messageInput.onkeydown = { event ->
        val keyboardEvent = event
        if (keyboardEvent.key == "Enter" && !keyboardEvent.shiftKey) {
            keyboardEvent.preventDefault()
            sendCurrentMessage()
        }
    }

    if (showHeader) {
        val chatHeader = document.createElement("div") as HTMLElement
        chatHeader.classList.add("slide-chat-header")
        chatHeader.innerHTML = if (expanded) "▾ Discussion" else "▸ Discussion"

        var isExpanded = expanded
        chatHeader.onclick = {
            isExpanded = !isExpanded
            chatContent.style.display = if (isExpanded) "block" else "none"
            chatHeader.innerHTML = if (isExpanded) "▾ Discussion" else "▸ Discussion"
        }

        chatContainer.appendChild(chatHeader)
    }

    inputArea.appendChild(messageInput)
    chatContent.appendChild(messagesArea)
    chatContent.appendChild(inputArea)
    chatContainer.appendChild(chatContent)

    val widget = ChatWidget(
        widgetId = widgetId,
        slideshowId = slideshowId,
        slideNumber = slideNumber,
        container = chatContainer,
        content = chatContent,
        messagesArea = messagesArea,
        messageInput = messageInput
    )
    chatWidgets[widgetId] = widget
    loadChatMessages(slideshowId, slideNumber)
    return widget
}

fun createChatUI(
    slideshowId: String,
    slideNumber: Int,
    expanded: Boolean = false,
    showHeader: Boolean = true
): HTMLElement {
    return createChatWidget(slideshowId, slideNumber, expanded, showHeader).container
}

@JsExport
fun mountSharedChat(
    containerId: String,
    slideshowId: String,
    slideNumber: Int = 0,
    expanded: Boolean = false,
    showHeader: Boolean = true
) {
    cleanupChatWidgets()
    val host = document.getElementById(containerId) as? HTMLElement ?: return
    val widget = createChatWidget(slideshowId, slideNumber, expanded, showHeader)
    host.innerHTML = ""
    host.appendChild(widget.container)
}

@Suppress("unused")
private val exposeChatMount = run {
    window.asDynamic().mountSharedChat = ::mountSharedChat
    true
}

fun displayChatMessages(target: HTMLElement, messages: List<ChatMessage>) {
    val messagesArea = target
    messagesArea.innerHTML = ""

    if (messages.isEmpty()) {
        val emptyMsg = document.createElement("div") as HTMLElement
        emptyMsg.classList.add("slide-chat-empty")
        emptyMsg.innerHTML = "No messages yet"
        messagesArea.appendChild(emptyMsg)
    } else {
        fun formatTimestamp(raw: String): String {
            fun isValidDate(date: dynamic): Boolean = js("!isNaN(date.getTime())") as Boolean

            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""

            val candidates = mutableListOf<dynamic>()

            trimmed.toDoubleOrNull()?.let { numeric ->
                val millis = when {
                    numeric > 1e12 -> numeric
                    numeric > 1e9 -> numeric * 1000
                    else -> numeric
                }
                candidates.add(eval("new Date($millis)"))
            }

            val normalized = trimmed
                .replace(" ", "T")
                .replace(Regex("(\\.\\d{3})\\d+")) { it.groupValues[1] }

            candidates.add(eval("new Date('$normalized')"))

            if (!normalized.endsWith("Z") && normalized.contains("T")) {
                candidates.add(eval("new Date('$normalized' + 'Z')"))
            }

            val date = candidates.firstOrNull { isValidDate(it) } ?: return ""
            return date.toLocaleTimeString("en-US", js("{hour: '2-digit', minute: '2-digit'}"))
        }

        messages.forEach { msg ->
            val msgDiv = document.createElement("div") as HTMLElement
            msgDiv.classList.add("slide-chat-message")

            val header = document.createElement("div") as HTMLElement
            header.classList.add("slide-chat-message-header")

            val timestamp = formatTimestamp(msg.timestamp)

            header.innerHTML = if (timestamp.isNotEmpty()) {
                "<strong>${msg.username}</strong> <span class='slide-chat-message-time'>$timestamp</span>"
            } else {
                "<strong>${msg.username}</strong>"
            }

            val content = document.createElement("div") as HTMLElement
            content.classList.add("slide-chat-message-content")
            content.textContent = msg.message

            msgDiv.appendChild(header)
            msgDiv.appendChild(content)
            messagesArea.appendChild(msgDiv)
        }

        // Scroll to bottom
        messagesArea.scrollTop = messagesArea.scrollHeight.toDouble()
    }
}

fun repositionSlideNoteOverlayIfVisible() {
    val overlay = document.getElementById("slide-note-overlay") as? HTMLElement ?: return
    if (overlay.style.display != "none") {
        positionSlideNoteOverlay(overlay, slideNoteAnchorUid)
        // Redraw annotations when the overlay moves
        if (currentAnnotations.isNotEmpty()) {
            redrawSlideAnnotations()
            scheduleSlideAnnotationRedraw(80)
        }
    }
}

/**
 * Creates HTML with text truncated to a certain length (DEPRECATED - kept for reference)
 */
private fun createTruncatedHTML(html: String, targetText: String): String {
    return html
}


@JsExport
@JsName("select")
fun select(selector: String): List<HTMLElement> {
    val s = document.querySelectorAll(selector)
    return (0 until s.length).mapNotNull {
        s.item(it) as? HTMLElement
    }
}

/**
 * Replace the browser's visible-layer set, mirror it to the session server, and recompute box,
 * output, and connection visibility. An empty set hides every layered box. [duration] is
 * milliseconds and matters only when [animated] is true.
 */
fun setVisibleLayers(layers: Set<String>, animated: Boolean = false, duration: Double = 300.0) {
    val before = visibleLayers.toList()
    visibleLayers.clear()
    visibleLayers.addAll(layers)
    layerDebugLog(
        "setVisibleLayers",
        json(
            "before" to before,
            "after" to visibleLayers.toList(),
            "animated" to animated,
            "duration" to duration
        )
    )
    send(Message("set-visible-layers").payload(SetVisibleLayers(layers.toList(), animated, duration)))
    if (animated) {
        updateAllBoxVisibility(animated = true, duration = duration)
        setTimeout({ canvasGestures.rebuildConnectionGroup() }, duration.toInt() + 30)
    } else {
        updateAllBoxVisibility()
        canvasGestures.rebuildConnectionGroup()
    }
}

/**
 * Ask the server to leave the current active layer and enter [layer]. The server snapshots frames
 * for boxes belonging to the old layer, then applies stored frames for the new layer. Passing null
 * deactivates frame capture but does not restore a pre-layer baseline.
 */
fun setActiveLayer(layer: String?, animated: Boolean = false, duration: Double = 500.0) {
    val before = activeLayer
    activeLayer = layer
    layerDebugLog(
        "setActiveLayer",
        json(
            "before" to before,
            "after" to activeLayer,
            "animated" to animated,
            "duration" to duration
        )
    )
    send(Message("set-active-layer").payload(SetActiveLayer(layer, animated, duration)))
}

/** Read layer names currently represented in browser box properties, returning `default` if empty. */
fun getAllLayers(): Set<String> {
    val allLayers = mutableSetOf<String>()
    select(".box").forEach { box ->
        val layers = box.asDynamic().box_layers as? String
        if (layers != null) {
            layers.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                allLayers.add(it)
            }
        }
        // Extract layer names from layerFrames map
        val layerFrames = box.asDynamic().box_layerFrames
        if (layerFrames != null && layerFrames != undefined) {
            try {
                // Use for..in loop to iterate over object keys safely
                for (key in js("Object.keys(layerFrames)") as Array<String>) {
                    allLayers.add(key)
                }
            } catch (e: Exception) {
                console.warn("Failed to extract layer names from layerFrames for box " + box.asDynamic().uid, e)
            }
        }
    }
    if (allLayers.isEmpty()) {
        allLayers.add("default")
    }
    return allLayers
}

private const val FRAME_VISIBILITY_EPSILON = 1e-6
private const val OFFLINE_BOX_VISIBLE_OPACITY = 0.72
private const val OFFLINE_BOX_ARRIVAL_DURATION_MS = 180

fun hasRenderableFrame(box: HTMLElement): Boolean {
    val d = box.unsafeCast<dynamic>()
    val w = (d.data_w as? Number)?.toDouble() ?: 0.0
    val h = (d.data_h as? Number)?.toDouble() ?: 0.0
    return w > FRAME_VISIBILITY_EPSILON || h > FRAME_VISIBILITY_EPSILON
}

private fun boxVisibleOpacity(box: HTMLElement): String =
    if (box.classList.contains("offline") || box.asDynamic().offline == true) {
        OFFLINE_BOX_VISIBLE_OPACITY.toString()
    } else {
        "1"
    }

private fun animateOfflineBoxArrival(box: HTMLElement) {
    if (!box.classList.contains("offline") && box.asDynamic().offline != true) return
    if (!isBoxVisible(box) || !hasRenderableFrame(box)) return

    box.style.transition = "opacity ${OFFLINE_BOX_ARRIVAL_DURATION_MS}ms ease-out, filter 0.16s"
    box.style.opacity = "0.35"
    window.setTimeout({
        box.style.opacity = boxVisibleOpacity(box)
    }, 0)
    window.setTimeout({
        box.style.transition = ""
        if (isBoxVisible(box)) {
            box.style.opacity = boxVisibleOpacity(box)
        }
    }, OFFLINE_BOX_ARRIVAL_DURATION_MS)
}

private val initialBoxPlaceholders = mutableMapOf<String, HTMLElement>()
private var initialBoxPlaceholderLayer: HTMLElement? = null

private fun publishInitialPlaceholderCount(lastFrameCount: Int? = null) {
    val w = window.asDynamic()
    if (lastFrameCount != null) {
        w.__fieldInitialFrameCount = lastFrameCount
    }
    w.__fieldInitialPlaceholderCount = initialBoxPlaceholders.size
}

private fun removeBoxPlaceholder(uid: String) {
    val fromMap = initialBoxPlaceholders.remove(uid)
    val fromHtml = if (fromMap == null) {
        initialBoxPlaceholderLayer
            ?.getElementsByClassName("box-placeholder")
            ?.asList()
            ?.firstOrNull {
                val element = it as? HTMLElement
                element?.asDynamic()?.uid == uid || element?.getAttribute("data-uid") == uid
            } as? HTMLElement
    } else null
    (fromMap ?: fromHtml)?.remove()
    publishInitialPlaceholderCount()
}

private fun initialPlaceholderLayer(): HTMLElement {
    val existing = initialBoxPlaceholderLayer
    if (existing != null) return existing

    val layer = (document.querySelector(".box-placeholder-layer") as? HTMLElement)
        ?: (document.createElement("div") as HTMLElement).also {
            it.classList.add("box-placeholder-layer")
            it.setAttribute("aria-hidden", "true")
            document.body?.appendChild(it)
        }
    initialBoxPlaceholderLayer = layer
    layer.getElementsByClassName("box-placeholder").asList().forEach {
        val element = it as? HTMLElement ?: return@forEach
        val uid = (element.asDynamic().uid as? String)
            ?: element.getAttribute("data-uid")
            ?: return@forEach
        element.asDynamic().uid = uid
        initialBoxPlaceholders[uid] = element
    }
    syncInitialPlaceholderLayerTransform()
    return layer
}

private fun syncInitialPlaceholderLayerTransform() {
    val layer = initialBoxPlaceholderLayer ?: return
    val canvas = if (::mainCanvas.isInitialized) mainCanvas.asDynamic() else null
    val x = (canvas?.data_x as? Number)?.toDouble() ?: 0.0
    val y = (canvas?.data_y as? Number)?.toDouble() ?: 0.0
    layer.style.transform = "translate(${x}px, ${y}px)"
}

private fun renderInitialBoxPlaceholders(frames: List<InitialBoxFrame>) {
    publishInitialPlaceholderCount(frames.size)
    if (frames.isNotEmpty()) {
        hideSplashScreenImmediately()
    }
    val layer = initialPlaceholderLayer()
    frames.forEach { initial ->
        val uid = initial.uid
        if (uid == "root") return@forEach
        val existingBox = document.body?.getElementsByClassName("box")?.asList()
            ?.firstOrNull { it.unsafeCast<dynamic>().uid == uid } as? HTMLElement
        if (existingBox != null && !existingBox.classList.contains("offline") && existingBox.asDynamic().offline != true) {
            removeBoxPlaceholder(uid)
            return@forEach
        }

        val frame = initial.frame
        var placeholder = initialBoxPlaceholders[uid]
            ?: layer.getElementsByClassName("box-placeholder").asList().firstOrNull {
                val element = it as? HTMLElement
                element?.asDynamic()?.uid == uid || element?.getAttribute("data-uid") == uid
            } as? HTMLElement
        if (placeholder == null) {
            placeholder = document.createElement("div") as HTMLElement
            placeholder.classList.add("box-placeholder")
            placeholder.setAttribute("aria-hidden", "true")
            placeholder.asDynamic().uid = uid
            placeholder.setAttribute("data-uid", uid)
            layer.appendChild(placeholder)
        }
        initialBoxPlaceholders[uid] = placeholder
        placeholder.style.transform = "translate(${frame.x}px, ${frame.y}px)"
        placeholder.style.width = "${frame.w}px"
        placeholder.style.height = "${frame.h}px"
        placeholder.style.display =
            if (frame.w > FRAME_VISIBILITY_EPSILON || frame.h > FRAME_VISIBILITY_EPSILON) "block" else "none"
    }
    publishInitialPlaceholderCount(frames.size)
}

private fun applyFrameToBox(box: HTMLElement, frame: Rect) {
    box.style.transform = "translate(${frame.x}px, ${frame.y}px)"
    box.style.width = "${frame.w}px"
    box.style.height = "${frame.h}px"

    val d = box.unsafeCast<dynamic>()
    d.data_x = frame.x
    d.data_y = frame.y
    d.data_w = frame.w
    d.data_h = frame.h
}

private fun applyBoxVisibilityImmediate(box: HTMLElement, includeOutputArea: Boolean = false) {
    // Immediate state changes can race against prior fade-out timers; cancel those first.
    clearPendingVisibilityHide(box)

    val visible = isBoxVisible(box)
    val uid = box.asDynamic().uid as? String ?: "(unknown)"
    val boxLayers = (box.asDynamic().box_layers as? String) ?: "default"
    val serverVisible = box.asDynamic().server_visible as? Boolean

    layerDebugLog(
        "applyBoxVisibilityImmediate",
        json(
            "uid" to uid,
            "visible" to visible,
            "includeOutputArea" to includeOutputArea,
            "layers" to boxLayers,
            "server_visible" to serverVisible,
            "displayBefore" to box.style.display,
            "visibilityBefore" to box.style.visibility
        )
    )

    box.style.visibility = if (visible) "visible" else "hidden"
    box.style.opacity = if (visible) boxVisibleOpacity(box) else "0"
    box.style.asDynamic().pointerEvents = if (visible) "auto" else "none"
    if (visible) box.style.display = "flex" else box.style.display = "none"

    if (!includeOutputArea) return

    val outputArea = box.getElementsByClassName("outputArea").asList().firstOrNull() as? HTMLElement
    if (outputArea != null) {
        clearPendingVisibilityHide(outputArea)
        outputArea.style.visibility = if (visible) "visible" else "hidden"
        outputArea.style.opacity = if (visible) "1" else "0"
        outputArea.style.asDynamic().pointerEvents = if (visible) "auto" else "none"
        if (visible) {
            outputArea.style.removeProperty("display")
        } else {
            outputArea.style.display = "none"
        }
    }
}

/**
 * True only for a renderable, server-visible, non-tombstoned box whose comma-separated `layers`
 * property intersects [visibleLayers]. A missing `layers` property means `default`.
 */
fun isBoxVisible(box: HTMLElement): Boolean {
    if (!hasRenderableFrame(box)) return false
    if (((box.asDynamic().server_visible as? Boolean) ?: true).not()) return false
    if (box.classList.contains("tombstoned")) return false
    val boxLayers = (box.asDynamic().box_layers as? String) ?: "default"
    val layers = boxLayers.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    return layers.any { layer -> visibleLayers.contains(layer) }
}

private fun clearPendingVisibilityHide(element: HTMLElement) {
    val pending = element.asDynamic().__visibilityHideTimeout
    if (pending != null && pending != js("undefined")) {
        val uid = element.asDynamic().uid as? String ?: element.className
        layerDebugLog("clearPendingVisibilityHide", json("id" to uid, "handle" to pending))
        window.clearTimeout((pending as Number).toInt())
        element.asDynamic().__visibilityHideTimeout = null
    }
}

private fun applyFadeVisibility(element: HTMLElement, show: Boolean, duration: Double) {
    clearPendingVisibilityHide(element)
    val uid = element.asDynamic().uid as? String ?: element.className
    layerDebugLog(
        "applyFadeVisibility",
        json(
            "id" to uid,
            "show" to show,
            "duration" to duration,
            "displayBefore" to element.style.display,
            "visibilityBefore" to element.style.visibility
        )
    )
    element.style.transition = "opacity ${duration}ms ease"
    if (show) {
        if (element.classList.contains("box")) {
            element.style.display = "flex"
        } else {
            element.style.removeProperty("display")
        }
        element.style.visibility = "visible"
        element.style.asDynamic().pointerEvents = "auto"
        if (element.style.opacity.isBlank()) element.style.opacity = "0"
        window.requestAnimationFrame {
            element.style.opacity = "1"
        }
    } else {
        element.style.opacity = "0"
        element.style.asDynamic().pointerEvents = "none"
        val hideHandle = setTimeout({
            layerDebugLog("applyFadeVisibility hide timer fired", json("id" to uid, "duration" to duration))
            element.style.visibility = "hidden"
            element.style.display = "none"
            element.asDynamic().__visibilityHideTimeout = null
        }, duration.toInt())
        element.asDynamic().__visibilityHideTimeout = hideHandle
        layerDebugLog(
            "applyFadeVisibility scheduled hide timer",
            json("id" to uid, "handle" to hideHandle, "duration" to duration)
        )
    }
}

fun updateAllBoxVisibility(animated: Boolean = false, duration: Double = 250.0) {
    layerDebugLog(
        "updateAllBoxVisibility",
        json(
            "animated" to animated,
            "duration" to duration,
            "visibleLayers" to visibleLayers.toList()
        )
    )
    select(".box").forEach { box ->
        val visible = isBoxVisible(box)
        val outputArea = box.getElementsByClassName("outputArea").asList().firstOrNull() as? HTMLElement

        if (animated) {
            applyFadeVisibility(box, visible, duration)
            if (outputArea != null) {
                applyFadeVisibility(outputArea, visible, duration)
            }
        } else {
            applyBoxVisibilityImmediate(box, includeOutputArea = true)
        }
    }
}


fun showCheatsheet() {
    // Redraw the key legends from the live bindings: cheap, and it means the card can never show a
    // shortcut the user has since rebound.
    val render = window.asDynamic().renderCheatsheetKeys
    if (render != null && render != undefined) render()

    (document.getElementById("overlaycanvas") as HTMLElement).style.unsafeCast<dynamic>().backdropFilter =
        "blur(20px) brightness(0.0)"
    select("#cheatsheet").forEach {
        it.style.opacity = "1"
        it.style.unsafeCast<dynamic>().pointerEvents = "auto"
        it.onclick = { event ->
            hideCheatsheet()
        }
    }
}

fun hideCheatsheet() {
    println(select("#cheatsheet"))
    (document.getElementById("overlaycanvas") as HTMLElement).style.unsafeCast<dynamic>().backdropFilter = ""
    select("#cheatsheet").forEach {
        it.style.opacity = "0"
        it.style.unsafeCast<dynamic>().pointerEvents = "none"
    }
}

private fun ensureSelectionBadge(box: HTMLElement): HTMLElement {
    val existing = box.getElementsByClassName("selection-order-badge").asList().firstOrNull() as? HTMLElement
    if (existing != null) return existing

    val badge = document.createElement("div") as HTMLElement
    badge.classList.add("selection-order-badge")
    box.appendChild(badge)
    return badge
}

private fun normalizeSelection(): List<HTMLElement> {
    // Keep only elements that are still connected and visually selected
    val alive = selected.filter { element ->
        val connected = element.isConnected
        connected && element.classList.contains("selected")
    }
    if (alive.size != selected.size) {
        selected.clear()
        alive.forEach { selected.add(it) }

        selectedList = eval("[]")
        selected.forEach { selectedList.push(it) }
        window.unsafeCast<dynamic>().selectedList = selectedList
        window.unsafeCast<dynamic>().selectedProperty = selectedProperty
    }
    return alive
}

private fun updateSelectionBadges() {
    val normalized = normalizeSelection()
    val orderMap = if (normalized.size >= 2) {
        normalized.withIndex().associate { (idx, element) -> element to idx + 1 }
    } else emptyMap()
    val boxes = document.body?.getElementsByClassName("box")?.asList().orEmpty()

    boxes.forEach { element ->
        val box = element as HTMLElement
        val order = orderMap[box]
        if (order != null) {
            val badge = ensureSelectionBadge(box)
            badge.textContent = order.toString()
            badge.classList.add("visible")
            box.setAttribute("data-selection-order", order.toString())
        } else {
            box.removeAttribute("data-selection-order")
            val badges = box.getElementsByClassName("selection-order-badge").asList()
            badges.forEach { b ->
                val badge = b as? HTMLElement
                if (badge?.parentElement == box) {
                    box.removeChild(badge)
                }
            }
        }
    }
}

private const val spatialNavPadding = 12.0
private const val spatialNavDoubleTapThresholdMs = 360.0

private fun isSpatialNavCandidate(box: HTMLElement?): Boolean {
    if (box == null) return false
    if (!box.isConnected) return false
    if (box.classList.contains("tombstoned")) return false
    if (!isBoxVisible(box)) return false
    val rect = box.getBoundingClientRect()
    return rect.width > 0.0 && rect.height > 0.0
}

private fun rectCenter(rect: DOMRect): Pair<Double, Double> =
    Pair(rect.left + rect.width / 2.0, rect.top + rect.height / 2.0)

private fun inflateRect(rect: DOMRect, pad: Double): DOMRect {
    val left = rect.left - pad
    val top = rect.top - pad
    val width = rect.width + pad * 2.0
    val height = rect.height + pad * 2.0
    return js("new DOMRect(left, top, width, height)") as DOMRect
}

private fun bookmarkSlotsByBox(): Map<String, List<Int>> {
    return try {
        loadBookmarks().bookmarks.mapNotNull { bookmark ->
            val uid = bookmark.selectedBox ?: return@mapNotNull null
            val num = bookmark.id.removePrefix("bookmark_").toIntOrNull() ?: return@mapNotNull null
            uid to num
        }.groupBy({ it.first }, { it.second })
    } catch (_: dynamic) {
        emptyMap()
    }
}

private fun findDirectionalSpatialTargets(anchor: HTMLElement): Map<CardinalDirection, HTMLElement> {
    val anchorRect = anchor.getBoundingClientRect()
    val (ax, ay) = rectCenter(anchorRect)
    val boxes = document.body?.getElementsByClassName("box")?.asList().orEmpty()
        .mapNotNull { it as? HTMLElement }
        .filter { it != anchor && isSpatialNavCandidate(it) }

    val best = mutableMapOf<CardinalDirection, Pair<HTMLElement, Double>>()

    boxes.forEach { candidate ->
        val rect = candidate.getBoundingClientRect()
        val (cx, cy) = rectCenter(rect)
        val dx = cx - ax
        val dy = cy - ay
        if (abs(dx) < 1e-3 && abs(dy) < 1e-3) return@forEach

        val direction = if (abs(dx) > abs(dy)) {
            if (dx > 0) CardinalDirection.EAST else CardinalDirection.WEST
        } else {
            if (dy > 0) CardinalDirection.SOUTH else CardinalDirection.NORTH
        }

        val primary =
            if (direction == CardinalDirection.EAST || direction == CardinalDirection.WEST) abs(dx) else abs(dy)
        val secondary =
            if (direction == CardinalDirection.EAST || direction == CardinalDirection.WEST) abs(dy) else abs(dx)
        val score = primary + secondary * 0.2

        val existing = best[direction]
        if (existing == null || score < existing.second) {
            best[direction] = candidate to score
        }
    }

    return best.mapValues { it.value.first }
}

private fun ensureSpatialNavOverlay(): HTMLElement {
    val existing = document.getElementById("spatial-nav-overlay") as? HTMLElement
    if (existing != null) return existing
    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "spatial-nav-overlay"
    overlay.classList.add("spatial-nav-overlay")
    document.body?.appendChild(overlay)
    return overlay
}

private fun clearSpatialNavOverlay() {
    val existing = document.getElementById("spatial-nav-overlay") as? HTMLElement
    existing?.parentElement?.removeChild(existing)
}

private fun renderSpatialNavOverlay(anchor: HTMLElement) {
    if (!spatialNavActive) {
        clearSpatialNavOverlay()
        return
    }

    val overlay = ensureSpatialNavOverlay()
    overlay.innerHTML = ""

    val svg = document.createElementNS("http://www.w3.org/2000/svg", "svg") as SVGSVGElement
    svg.setAttribute("class", "spatial-nav-lines")
    svg.setAttribute("width", "100%")
    svg.setAttribute("height", "100%")
    overlay.appendChild(svg)

    val anchorRect = anchor.getBoundingClientRect()
    val bookmarkMap = bookmarkSlotsByBox()

    fun appendHighlight(rect: DOMRect, isAnchor: Boolean) {
        val highlight = document.createElement("div") as HTMLElement
        highlight.classList.add("spatial-nav-highlight")
        if (isAnchor) highlight.classList.add("anchor")
        highlight.style.left = "${rect.left}px"
        highlight.style.top = "${rect.top}px"
        highlight.style.width = "${rect.width}px"
        highlight.style.height = "${rect.height}px"
        overlay.appendChild(highlight)
    }

    fun appendBadge(rect: DOMRect, direction: CardinalDirection) {
        val badge = document.createElement("div") as HTMLElement
        badge.classList.add("spatial-nav-badge")
        badge.textContent = direction.label
        val (left, top) = when (direction) {
            CardinalDirection.NORTH -> Pair(rect.left + rect.width / 2.0, rect.top - 16.0)
            CardinalDirection.SOUTH -> Pair(rect.left + rect.width / 2.0, rect.bottom + 16.0)
            CardinalDirection.EAST -> Pair(rect.right + 16.0, rect.top + rect.height / 2.0)
            CardinalDirection.WEST -> Pair(rect.left - 16.0, rect.top + rect.height / 2.0)
        }
        badge.style.left = "${left}px"
        badge.style.top = "${top}px"
        overlay.appendChild(badge)
    }

    fun appendBookmarkBadges(rect: DOMRect, labels: List<Int>, anchorLeft: Double? = null, anchorTop: Double? = null) {
        labels.forEachIndexed { index, num ->
            val badge = document.createElement("div") as HTMLElement
            badge.classList.add("spatial-nav-bookmark-badge")
            badge.textContent = "F$num"
            val defaultLeft: Double
            val defaultTop: Double
            if (anchorLeft != null && anchorTop != null) {
                defaultLeft = anchorLeft + index * 36.0
                defaultTop = anchorTop
            } else {
                // Spread along top edge, centered
                val spread = (index - (labels.size - 1) / 2.0) * 36.0
                defaultLeft = rect.left + rect.width / 2.0 + spread
                defaultTop = rect.top
            }
            badge.style.left = "${defaultLeft}px"
            badge.style.top = "${defaultTop}px"
            overlay.appendChild(badge)
        }
    }

    val anchorHighlight = inflateRect(anchorRect, spatialNavPadding)
    appendHighlight(anchorHighlight, true)
    val (ax, ay) = rectCenter(anchorHighlight)

    spatialNavTargets.forEach { (direction, target) ->
        if (!isSpatialNavCandidate(target)) return@forEach
        val rect = target.getBoundingClientRect()
        val targetHighlight = inflateRect(rect, spatialNavPadding)
        appendHighlight(targetHighlight, false)
        appendBadge(targetHighlight, direction)
        bookmarkMap[target.asDynamic().uid as? String ?: ""]?.let { slots ->
            val (arrowLeft, arrowTop) = when (direction) {
                CardinalDirection.NORTH -> Pair(targetHighlight.left + targetHighlight.width / 2.0, targetHighlight.top)
                CardinalDirection.SOUTH -> Pair(
                    targetHighlight.left + targetHighlight.width / 2.0,
                    targetHighlight.bottom
                )

                CardinalDirection.EAST -> Pair(
                    targetHighlight.right,
                    targetHighlight.top + targetHighlight.height / 2.0
                )

                CardinalDirection.WEST -> Pair(targetHighlight.left, targetHighlight.top + targetHighlight.height / 2.0)
            }
            appendBookmarkBadges(targetHighlight, slots, arrowLeft, arrowTop)
        }
        val (tx, ty) = rectCenter(targetHighlight)
        val dx = tx - ax
        val dy = ty - ay
        val start = clipRay(anchorHighlight, dx, dy).point
        val end = clipRay(targetHighlight, -dx, -dy).point
        val line = document.createElementNS(svg.namespaceURI, "line") as SVGElement
        line.setAttribute("x1", start.x.toString())
        line.setAttribute("y1", start.y.toString())
        line.setAttribute("x2", end.x.toString())
        line.setAttribute("y2", end.y.toString())
        line.setAttribute("class", "spatial-nav-line")
        svg.appendChild(line)
    }

    val targetedUids = spatialNavTargets.values.mapNotNull { it.asDynamic().uid as? String }.toSet()
    bookmarkMap.forEach { (uid, slots) ->
        if (targetedUids.contains(uid)) return@forEach
        val box = findBoxByUid(uid) ?: return@forEach
        if (!isSpatialNavCandidate(box)) return@forEach
        val rect = inflateRect(box.getBoundingClientRect(), spatialNavPadding)
        appendBookmarkBadges(rect, slots)
    }
}

private fun refreshSpatialNavTargets() {
    if (!spatialNavActive) return
    val anchor = selected.lastOrNull()?.takeIf { isSpatialNavCandidate(it) }
        ?: spatialNavAnchor?.takeIf { isSpatialNavCandidate(it) }
    if (anchor == null) {
        deactivateSpatialNavMode()
        return
    }
    spatialNavAnchor = anchor
    spatialNavTargets.clear()
    spatialNavTargets.putAll(findDirectionalSpatialTargets(anchor))
    renderSpatialNavOverlay(anchor)
}

private fun activateSpatialNav(triggerKey: String) {
    if (spatialNavActive && spatialNavKey == triggerKey) {
        refreshSpatialNavTargets()
        return
    }
    val anchor = selected.lastOrNull() ?: return
    spatialNavActive = true
    spatialNavKey = triggerKey
    spatialNavAnchor = anchor
    refreshSpatialNavTargets()
}

private fun deactivateSpatialNavMode() {
    spatialNavActive = false
    spatialNavKey = null
    spatialNavAnchor = null
    spatialNavTargets.clear()
    clearSpatialNavOverlay()
    try {
        switchToMonaco()
    } catch (_: dynamic) {
    }
    try {
        val editor = window.asDynamic().globalEditor
        if (editor != null && editor.focus != undefined) {
            editor.focus()
        }
    } catch (_: dynamic) {
    }
}

private fun installSpatialNavigationShortcut() {
    var lastMetaDown = 0.0
    var lastCtrlDown = 0.0
    var lastMetaDownSerial = 0
    var lastCtrlDownSerial = 0
    var lastMetaWasPure = false
    var lastCtrlWasPure = false
    var keydownSerial = 0

    fun trackTrigger(key: String, event: KeyboardEvent) {
        val now = window.performance.now()
        val alreadyPressed = if (key == "Meta") metaPressed else ctrlPressed
        val lastDown = if (key == "Meta") lastMetaDown else lastCtrlDown
        val lastSerial = if (key == "Meta") lastMetaDownSerial else lastCtrlDownSerial
        val lastPure = if (key == "Meta") lastMetaWasPure else lastCtrlWasPure
        val isPure = when (key) {
            "Meta" -> !(event.shiftKey || event.altKey || event.ctrlKey)
            "Control" -> !(event.shiftKey || event.altKey || event.metaKey)
            else -> true
        }
        val noInterveningKey = lastSerial != 0 && lastSerial == keydownSerial - 1
        val shouldTrigger = !alreadyPressed &&
                lastPure &&
                isPure &&
                noInterveningKey &&
                now - lastDown < spatialNavDoubleTapThresholdMs

        if (shouldTrigger) {
            event.preventDefault()
            activateSpatialNav(key)
        }

        if (key == "Meta") {
            metaPressed = true
            lastMetaDown = if (shouldTrigger) 0.0 else now
            lastMetaDownSerial = if (shouldTrigger) 0 else keydownSerial
            lastMetaWasPure = if (shouldTrigger) false else isPure
        } else {
            ctrlPressed = true
            lastCtrlDown = if (shouldTrigger) 0.0 else now
            lastCtrlDownSerial = if (shouldTrigger) 0 else keydownSerial
            lastCtrlWasPure = if (shouldTrigger) false else isPure
        }
    }

    window.addEventListener("keydown", { raw ->
        val event = raw as KeyboardEvent
        if (!event.repeat) {
            keydownSerial += 1
        }
        when (event.key) {
            "Meta" -> if (!event.repeat) trackTrigger("Meta", event)
            "Control" -> if (!event.repeat) trackTrigger("Control", event)
        }

        if (spatialNavActive && spatialNavKey != null) {
            val triggerHeld = when (spatialNavKey) {
                "Meta" -> metaPressed || event.metaKey
                "Control" -> ctrlPressed || event.ctrlKey
                else -> false
            }
            if (!triggerHeld) {
                deactivateSpatialNavMode()
                return@addEventListener
            }
            when (event.key) {
                "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight" -> {
                    event.preventDefault()
                    event.stopPropagation()
                    val dir = when (event.key) {
                        "ArrowUp" -> CardinalDirection.NORTH
                        "ArrowDown" -> CardinalDirection.SOUTH
                        "ArrowLeft" -> CardinalDirection.WEST
                        else -> CardinalDirection.EAST
                    }
                    val target = spatialNavTargets[dir]
                    if (target != null) {
                        selected.toList().forEach { deselectBox(it) }
                        selectBox(target)
                        ensureBoxVisibleForNavigation(target)
                        spatialNavAnchor = target
                        refreshSpatialNavTargets()
                    } else {
                        spatialNavAnchor?.let { renderSpatialNavOverlay(it) }
                    }
                }

                "Escape" -> {
                    event.preventDefault()
                    event.stopPropagation()
                    deactivateSpatialNavMode()
                }
            }
        }
    }, true)

    window.addEventListener("keyup", { raw ->
        val event = raw as KeyboardEvent
        when (event.key) {
            "Meta" -> {
                metaPressed = false
                if (spatialNavActive && spatialNavKey == "Meta") {
                    deactivateSpatialNavMode()
                }
            }

            "Control" -> {
                ctrlPressed = false
                if (spatialNavActive && spatialNavKey == "Control") {
                    deactivateSpatialNavMode()
                }
            }
        }
    }, true)

    window.addEventListener("blur", {
        metaPressed = false
        ctrlPressed = false
        if (spatialNavActive) {
            deactivateSpatialNavMode()
        }
    })
}


fun deselectBox(it: HTMLElement) {
    it.classList.remove("selected")
    it.unsafeCast<dynamic>().selected = false
    if (selected.contains(it)) {
        selected.remove(it)

        selectedList = eval("[]")
        selected.forEach { selectedList.push(it) }
        window.unsafeCast<dynamic>().selectedList = selectedList
        window.unsafeCast<dynamic>().selectedProperty = selectedProperty

        events["selectionChanged"] = {
            onSelectedChange.entries.retainAll { (k, v) -> v() }
            false
        }
    }
    updateSelectionBadges()
    selectionContextChanged()

}

private fun bringBoxToFront(it: HTMLElement) {
    val boxes = document.body!!.getElementsByClassName("box").asList()
    if (boxes.isEmpty()) return
    var maxZ = Int.MIN_VALUE
    var maxCount = 0
    var maxBox: HTMLElement? = null
    boxes.forEach { box ->
        val z = box.unsafeCast<HTMLElement>().style.zIndex.toIntOrNull() ?: 0
        if (z > maxZ) {
            maxZ = z
            maxCount = 1
            maxBox = box.unsafeCast<HTMLElement>()
        } else if (z == maxZ) {
            maxCount++
        }
    }
    val selectedZ = it.style.zIndex.toIntOrNull() ?: 0
    if (maxBox == null) return
    if (maxCount == 1 && maxBox != it) {
        maxBox.style.zIndex = selectedZ.toString()
        it.style.zIndex = maxZ.toString()
    } else {
        it.style.zIndex = (maxZ + 1).toString()
    }
}

fun selectBox(it: HTMLElement) {
    it.classList.add("selected")
    it.unsafeCast<dynamic>().selected = true
    if (!selected.contains(it)) {
        selected.add(it)
        if (selected.size == 1) {
            val defaultProperty = it.unsafeCast<dynamic>().default_property as? String
            val editorProperty = activeEditorProperty()
            if (explicitPropertySelectionInProgress) {
                // Keep explicit property navigation from becoming a default for later box selections.
            } else if (editorPropertyStickinessMode() == "per-box") {
                setSelectedEditorProperty(
                    rememberedEditorPropertyForBox(it)
                        ?: defaultProperty?.takeIf { property -> property.isNotBlank() }
                        ?: "code")
            } else if (editorPropertyStickinessMode() == "global" && boxHasPropertyContents(it, editorProperty)) {
                setSelectedEditorProperty(editorProperty)
            } else if (!defaultProperty.isNullOrBlank()) {
                setSelectedEditorProperty(defaultProperty)
            } else {
                setSelectedEditorProperty("code")
            }
        }

//        val p = it.parentNode!!
//        if (p.lastChild != it) {
//            p.appendChild(it)
//        }
        bringBoxToFront(it)

        selectedList = eval("[]")
        selected.forEach { selectedList.push(it) }
        window.unsafeCast<dynamic>().selectedList = selectedList
        window.unsafeCast<dynamic>().selectedProperty = selectedProperty

        events["selectionChanged"] = {
            onSelectedChange.entries.retainAll { (k, v) -> v() }
            false
        }
    }
    updateSelectionBadges()
    selectionContextChanged()
}

private const val FILE_PROXY_MENU_BOX_OFFSET_X = 120.0
private const val FILE_PROXY_MENU_BOX_OFFSET_Y = 45.0
private const val DESKTOP_DROP_PATH_HINT =
    "Run Field in the full Desktop client to use drag-and-drop with full file paths."

private data class DroppedFileProxy(
    val file: dynamic,
    val fileName: String,
    val path: String?,
    val mimeType: String?
)

// Be liberal in what we accept as a droppable transcript — match everything the
// server's loader (operation_transcript.kt loadMacroOperations) will actually run:
// a top-level `operations:` / `ops:` mapping, OR a bare YAML sequence whose first
// entry is a recognized operation verb. Indentation, inline values, and leading
// comment/blank lines are all tolerated.
private val OPERATION_TRANSCRIPT_MARKER = Regex("^\\s*(operations|ops)\\s*:", RegexOption.MULTILINE)

// A bare top-level sequence begins with e.g. "- create:" (see the verb vocabulary in
// operation_transcript.kt). Gating the bare-list form on a known verb keeps us from
// hijacking an arbitrary YAML list the user meant to drop as a file proxy.
private val BARE_OPERATION_ITEM = Regex("^-\\s+([\\w-]+)\\s*:")
private val OPERATION_VERBS = setOf(
    "create", "new-box", "box",
    "execute", "execute-fragment",
    "launch", "launch-all", "launch-fragment",
    "edit", "set", "set-string",
    "move", "frame", "set-rect",
    "connect", "delete", "tombstone",
    "wait", "marker"
)

internal fun looksLikeOperationTranscript(text: String): Boolean {
    if (OPERATION_TRANSCRIPT_MARKER.containsMatchIn(text)) return true
    val firstMeaningful = text.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        ?: return false
    val verb = BARE_OPERATION_ITEM.find(firstMeaningful)?.groupValues?.getOrNull(1)?.lowercase()
    return verb != null && verb in OPERATION_VERBS
}

// Which dropped files are worth reading and sniffing as a transcript. Beyond the
// obvious .yaml/.yml, accept .txt, JSON (valid YAML), text-ish MIME types, and
// extensionless files — while leaving clearly-binary drops (images, video, .png …)
// to go straight to the file-proxy menu without a wasteful full read.
private fun isTranscriptCandidateFile(lowerName: String, mimeType: String?): Boolean {
    if (lowerName.endsWith(".yaml") || lowerName.endsWith(".yml") ||
        lowerName.endsWith(".txt") || lowerName.endsWith(".json")
    ) return true
    val mime = mimeType?.lowercase().orEmpty()
    if (mime.startsWith("text/") || mime.contains("yaml") || mime == "application/json") return true
    val base = lowerName.substringAfterLast('/').substringAfterLast('\\')
    return !base.contains('.')
}

private fun decodeFileUri(raw: String?): String? {
    val line = raw
        ?.lines()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") && it.startsWith("file://") }
        ?: return null
    var path = line.removePrefix("file://")
    if (path.startsWith("localhost/")) path = path.removePrefix("localhost")
    if (!path.startsWith("/")) path = "/$path"
    return runCatching { js("decodeURIComponent")(path) as String }.getOrElse { path }
}

private fun extractDroppedFilePath(file: dynamic, dataTransfer: dynamic): String? {
    val direct = file.path as? String
    if (!direct.isNullOrBlank()) return direct

    val uri = runCatching { dataTransfer.getData("text/uri-list") as? String }.getOrNull()
    decodeFileUri(uri)?.let { return it }

    val plain = runCatching { dataTransfer.getData("text/plain") as? String }.getOrNull()
    decodeFileUri(plain)?.let { return it }
    if (!plain.isNullOrBlank() && plain.startsWith("/")) return plain

    val webkit = file.webkitRelativePath as? String
    if (!webkit.isNullOrBlank() && webkit.startsWith("/")) return webkit
    return null
}

private fun readDroppedFileBase64(file: dynamic, callback: (String?) -> Unit) {
    val reader = js("new FileReader()")
    reader.onload = { loadEvent: dynamic ->
        val result = loadEvent.target.result as? String
        callback(result?.substringAfter(",", missingDelimiterValue = result))
    }
    reader.onerror = {
        callback(null)
    }
    reader.readAsDataURL(file)
}

private fun sendCreateFileProxyBox(request: CreateFileProxyBox) {
    val callbackName = "create-file-proxy-box-${Uuid.random()}"
    sendWithReply(
        Message("create-file-proxy-box").payload(request),
        callbackName
    ) { message, _ ->
        val response = Json.decodeFromJsonElement<CreateFileProxyBoxResponse>(message.payload!!)
        if (response.ok) {
            notify(response.message.ifBlank { "Created file proxy" }, "file")
            response.uid?.let { uid ->
                switchSelectionToProperty(uid, response.defaultProperty.ifBlank { "contents" }, syncEditor = true)
            }
        } else {
            notify(response.message.ifBlank { "Could not create file proxy" }, "error")
        }
        Handled.yes_remove
    }
}

private fun copyDroppedPathToClipboard(dropped: DroppedFileProxy) {
    val path = dropped.path?.takeIf { it.isNotBlank() } ?: dropped.fileName
    val clipboard = window.navigator.asDynamic().clipboard
    if (clipboard != null) {
        clipboard.writeText(path).then({
            val message = if (dropped.path.isNullOrBlank()) {
                "Copied file name only; this browser did not expose a full path. $DESKTOP_DROP_PATH_HINT"
            } else {
                "Copied path to clipboard"
            }
            notify(message, "file")
        }).catch({ _: dynamic ->
            val fallback = document.createElement("textarea") as HTMLTextAreaElement
            fallback.value = path
            fallback.setAttribute("readonly", "readonly")
            fallback.style.position = "fixed"
            fallback.style.left = "-10000px"
            document.body?.appendChild(fallback)
            fallback.select()
            val copied =
                runCatching { document.asDynamic().execCommand("copy") as? Boolean ?: false }.getOrDefault(false)
            fallback.parentElement?.removeChild(fallback)
            notify(
                if (copied) "Copied to clipboard" else "Could not write to clipboard. $DESKTOP_DROP_PATH_HINT",
                if (copied) "file" else "error"
            )
        })
    } else {
        val fallback = document.createElement("textarea") as HTMLTextAreaElement
        fallback.value = path
        fallback.setAttribute("readonly", "readonly")
        fallback.style.position = "fixed"
        fallback.style.left = "-10000px"
        document.body?.appendChild(fallback)
        fallback.select()
        val copied = runCatching { document.asDynamic().execCommand("copy") as? Boolean ?: false }.getOrDefault(false)
        fallback.parentElement?.removeChild(fallback)
        notify(
            if (copied) "Copied to clipboard" else "Clipboard API is unavailable. $DESKTOP_DROP_PATH_HINT",
            if (copied) "file" else "error"
        )
    }
}

private fun createAbsoluteProxyForDrop(dropped: DroppedFileProxy, x: Double, y: Double) {
    val defaultPath = dropped.path ?: ""
    val path = if (defaultPath.isNotBlank()) {
        defaultPath
    } else {
        (window.asDynamic().prompt("Absolute path to proxy", dropped.fileName) as? String)
            ?.trim()
            .orEmpty()
    }
    if (path.isBlank()) {
        notify("Absolute proxy needs a filesystem path. $DESKTOP_DROP_PATH_HINT", "error")
        return
    }
    sendCreateFileProxyBox(
        CreateFileProxyBox(
            mode = "absolute",
            path = path,
            fileName = dropped.fileName,
            mimeType = dropped.mimeType,
            x = x,
            y = y
        )
    )
}

/**
 * Reveals one of the live-reloaded files in `~/.afield` as an editable file-proxy box, reusing the
 * existing box for that path if there already is one.
 */
private fun openUserConfigProxyBox(path: String, fileName: String, mimeType: String, what: String) {
    if (path.isBlank()) {
        notify("$what path is not available yet", "error")
        return
    }

    findAbsoluteFileProxyBox(path)?.let { box ->
        val uid = uidForBoxElement(box)
        if (uid != null && switchSelectionToProperty(uid, "contents", syncEditor = true)) {
            notify("Opened $what", "file")
            return
        }
    }

    val canvasDyn = mainCanvas.unsafeCast<dynamic>()
    val x = window.innerWidth.toDouble() / 2.0 -
            ((canvasDyn.data_x as? Number)?.toDouble() ?: 0.0) -
            FILE_PROXY_MENU_BOX_OFFSET_X
    val y = window.innerHeight.toDouble() / 2.0 -
            ((canvasDyn.data_y as? Number)?.toDouble() ?: 0.0) -
            FILE_PROXY_MENU_BOX_OFFSET_Y

    sendCreateFileProxyBox(
        CreateFileProxyBox(
            mode = "absolute",
            path = path,
            fileName = fileName,
            mimeType = mimeType,
            x = x,
            y = y
        )
    )
}

fun openUserThemeProxyBox() = openUserConfigProxyBox(
    path = (window.asDynamic().fieldUserThemePath as? String)?.trim().orEmpty(),
    fileName = "theme.css",
    mimeType = "text/css",
    what = "user theme"
)

fun openUserKeybindingsProxyBox() = openUserConfigProxyBox(
    path = (window.asDynamic().fieldUserKeybindingsPath as? String)?.trim().orEmpty(),
    fileName = "keybindings.yaml",
    mimeType = "text/yaml",
    what = "keybindings"
)

private fun createDocumentCopyProxyForDrop(dropped: DroppedFileProxy, x: Double, y: Double) {
    readDroppedFileBase64(dropped.file) { contentsBase64 ->
        if (contentsBase64 == null) {
            notify("Could not read dropped file", "error")
            return@readDroppedFileBase64
        }
        sendCreateFileProxyBox(
            CreateFileProxyBox(
                mode = "document-copy",
                path = dropped.path,
                fileName = dropped.fileName,
                contentsBase64 = contentsBase64,
                mimeType = dropped.mimeType,
                x = x,
                y = y
            )
        )
    }
}

// --- Dropped-file marking menu ----------------------------------------------
//
// Every dropped file is classified into a DropKind (classifyDroppedFile) and then
// offered a small radial marking menu of actions for that kind. showDropMenu is the
// single menu presenter; per-kind option builders below supply the entries. The
// builders receive the drop point in canvas (document) space.

/** One entry in a dropped-file marking menu. [angle] is auto-distributed when null. */
private class DropMenuOption(
    val text: String,
    val angle: Double? = null,
    val action: () -> Unit,
)

/**
 * Present a radial marking menu for a dropped file at the event location. [build]
 * receives the drop point in canvas/document space and returns the menu entries; the
 * first entry selected fires exactly once (marking menus can fire on release + move).
 */
private fun showDropMenu(event: dynamic, build: (x: Double, y: Double) -> List<DropMenuOption>) {
    var fired = false
    fun once(action: () -> Unit) {
        if (fired) return
        fired = true
        action()
    }

    val canvasDyn = mainCanvas.unsafeCast<dynamic>()
    val x = ((event.pageX as? Number)?.toDouble() ?: 0.0) -
            ((canvasDyn.data_x as? Number)?.toDouble() ?: 0.0) -
            FILE_PROXY_MENU_BOX_OFFSET_X
    val y = ((event.pageY as? Number)?.toDouble() ?: 0.0) -
            ((canvasDyn.data_y as? Number)?.toDouble() ?: 0.0) -
            FILE_PROXY_MENU_BOX_OFFSET_Y

    val options = build(x, y)
    val count = options.size.coerceAtLeast(1)
    val menus = options.mapIndexed { index, option ->
        // Evenly distribute around the circle (top-first, clockwise) unless the
        // option pins its own angle — the PLAIN proxy menu keeps its historical layout.
        val angle = option.angle ?: (90.0 - index * (360.0 / count))
        json(
            "text" to option.text,
            "callback" to { _: dynamic -> once { option.action() } },
            "radius" to 1.0,
            "angle" to angle
        )
    }.toTypedArray()
    goMarkingMenu(
        (event.clientX as? Number)?.toDouble() ?: 0.0,
        (event.clientY as? Number)?.toDouble() ?: 0.0,
        menus.asJsObject(),
        event,
        {}
    )
}

/** Plain-file options: the historical file-proxy menu (unchanged labels + angles). */
private fun plainDropOptions(dropped: DroppedFileProxy, x: Double, y: Double): List<DropMenuOption> = listOf(
    DropMenuOption("Copy path to clipboard", angle = 270.0) { copyDroppedPathToClipboard(dropped) },
    DropMenuOption("Create absolute proxy box", angle = 90.0) { createAbsoluteProxyForDrop(dropped, x, y) },
    DropMenuOption("Create proxy for copy in document folder", angle = 0.0) { createDocumentCopyProxyForDrop(dropped, x, y) },
)

private fun showDroppedFileProxyMenu(event: dynamic, dropped: DroppedFileProxy) =
    showDropMenu(event) { x, y -> plainDropOptions(dropped, x, y) }

/** metadata.edn options: flat import (current), open as a new document, or nested import. */
private fun metadataDropOptions(
    dropped: DroppedFileProxy,
    ednText: String,
    x: Double,
    y: Double,
): List<DropMenuOption> = listOf(
    DropMenuOption("Import into this document") {
        send(Message("import-metadata-edn").payload(ImportMetadata(ednText)))
        notify("Importing ${dropped.fileName}...", "import")
    },
    DropMenuOption("Open as new document in a new tab") {
        openImportInNewTab("edn", dropped.fileName, ednText)
    },
    DropMenuOption("Import nested in a new box") {
        send(Message("import-metadata-edn-nested").payload(ImportMetadataNested(ednText, x, y)))
        notify("Importing ${dropped.fileName} into a new nest box...", "import")
    },
)

/**
 * .ipynb options: python-cell import (the sensible default) plus file-proxy fallbacks.
 * A notebook has no meaning as a standalone Field document, so there is deliberately no
 * "open as a new document" action here (unlike metadata.edn, which *is* a document).
 */
private fun notebookDropOptions(
    dropped: DroppedFileProxy,
    notebookJson: String,
    x: Double,
    y: Double,
): List<DropMenuOption> = listOf(
    DropMenuOption("Import as Python cells") {
        send(Message("import-ipynb").payload(ImportNotebook(dropped.fileName, notebookJson, x, y)))
        notify("Importing notebook ${dropped.fileName}...", "import")
    },
    DropMenuOption("Copy path to clipboard") { copyDroppedPathToClipboard(dropped) },
    DropMenuOption("Create absolute proxy box") { createAbsoluteProxyForDrop(dropped, x, y) },
    DropMenuOption("Create proxy for copy in document folder") { createDocumentCopyProxyForDrop(dropped, x, y) },
)

/**
 * Macro/transcript options: run on selection (current), or save into a macro folder
 * without running. The project entry is only present when [projectName] is non-blank
 * (the document belongs to a project — see queryMacroScopesForDrop).
 */
private fun transcriptDropOptions(
    dropped: DroppedFileProxy,
    content: String,
    projectName: String,
): List<DropMenuOption> = buildList {
    add(DropMenuOption("Run on selection") { runDroppedTranscript(dropped.fileName, content) })
    add(DropMenuOption("Add to document macros") {
        addDroppedTranscriptToScope(dropped.fileName, content, "document")
    })
    if (projectName.isNotBlank()) {
        add(DropMenuOption("Add to project macros") {
            addDroppedTranscriptToScope(dropped.fileName, content, "project", projectName)
        })
    }
    add(DropMenuOption("Add to workspace macros") {
        addDroppedTranscriptToScope(dropped.fileName, content, "workspace")
    })
}

/**
 * Open a new document in a new browser tab and replay [payloadText] there through the
 * existing import handler. A same-origin localStorage handoff carries the payload: the
 * new tab reads it via the ?pendingImport token on load (see replayPendingImport).
 * [type] is "edn" or "ipynb".
 */
private fun openImportInNewTab(type: String, fileName: String, payloadText: String) {
    val stamp = Date.now().toLong().toString()
    val baseName = fileName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
    val safeBase = baseName.replace(Regex("[^A-Za-z0-9_-]"), "-").trim('-').ifBlank { "imported" }
    val docName = "$safeBase-$stamp"
    val token = "field-pending-import-$stamp"
    val record = json(
        "type" to type,
        "fileName" to fileName,
        "payload" to payloadText
    )
    window.localStorage.setItem(token, JSON.stringify(record))
    val encode = { value: String -> js("encodeURIComponent")(value) as String }
    val url = "/index.html?file=${encode(docName)}&pendingImport=${encode(token)}"
    val opened = window.open(url, "_blank")
    if (opened == null) {
        window.localStorage.removeItem(token)
        notify("Could not open a new tab (popup blocked?)", "error")
    } else {
        notify("Opening $docName in a new tab...", "import")
    }
}

/**
 * On load, replay a pending import handed off from another tab (see openImportInNewTab).
 * The message is registered on the websocket open queue so it is sent right after the
 * `hello` handshake, once the fresh document's session exists server-side.
 */
private fun replayPendingImport(token: String) {
    val raw = window.localStorage.getItem(token) ?: return
    window.localStorage.removeItem(token)
    val record = runCatching { JSON.parse<dynamic>(raw) }.getOrNull() ?: return
    val type = record.type as? String ?: return
    val fileName = (record.fileName as? String) ?: "imported"
    val payload = (record.payload as? String) ?: return
    when (type) {
        "edn" -> registerOnOpen(Message("import-metadata-edn").payload(ImportMetadata(payload)))
        "ipynb" -> registerOnOpen(Message("import-ipynb").payload(ImportNotebook(fileName, payload, 40.0, 40.0)))
        else -> return
    }
    notify("Importing $fileName into this new document...", "import")
}

/** How a dropped file is handled. Owns identification order (ipynb before transcript, etc.). */
private enum class DropKind { METADATA_EDN, NOTEBOOK, TRANSCRIPT_CANDIDATE, PLAIN }

private fun classifyDroppedFile(fileName: String, lowerName: String, mimeType: String?): DropKind = when {
    fileName == "metadata.edn" -> DropKind.METADATA_EDN
    // .ipynb is JSON and would otherwise be sniffed as a transcript — classify it first.
    lowerName.endsWith(".ipynb") -> DropKind.NOTEBOOK
    isTranscriptCandidateFile(lowerName, mimeType) -> DropKind.TRANSCRIPT_CANDIDATE
    else -> DropKind.PLAIN
}

private fun applyFileProxyErrorStatus(box: HTMLElement, message: String) {
    val status = box.querySelector(".statusArea") as? HTMLElement ?: return
    val tooltip = box.querySelector(".tooltiptext") as? HTMLElement ?: return
    val clean = message.trim()
    box.unsafeCast<dynamic>().file_proxy_error = clean
    if (clean.isNotEmpty()) {
        status.setAttribute("data-file-proxy-error", "true")
        status.classList.remove("status-good")
        status.classList.add("status-error")
        status.classList.remove("flash-inverted")
        window.requestAnimationFrame {
            status.classList.add("flash-inverted")
        }
        tooltip.textContent = clean
    } else if (status.getAttribute("data-file-proxy-error") == "true") {
        status.removeAttribute("data-file-proxy-error")
        status.classList.remove("status-error")
        status.classList.remove("flash-inverted")
        tooltip.textContent = ""
    }
}

private fun fileProxyDisplayPath(box: HTMLElement): String {
    val mode = (box.unsafeCast<dynamic>().file_proxy_mode as? String).orEmpty()
    val path = (box.unsafeCast<dynamic>().file_proxy_path as? String).orEmpty()
    if (path.isBlank()) return ""
    return when (mode) {
        "absolute" -> path
        "workspace" -> "workspace:$path"
        "document" -> "document:$path"
        else -> path
    }
}

private fun applyFileProxyPresentation(box: HTMLElement) {
    val pathText = fileProxyDisplayPath(box)
    if (pathText.isBlank()) return
    box.classList.add("file-proxy-box")
    box.style.backgroundColor = "var(--field-bg)"

    val titleStack = box.querySelector(".box-title-stack") as? HTMLElement
        ?: (box.querySelector(".doubleClickToEdit")?.parentElement as? HTMLElement)
        ?: return
    val path = titleStack.querySelector(".file-proxy-path") as? HTMLElement ?: run {
        val created = document.createElement("div") as HTMLElement
        created.classList.add("file-proxy-path")
        titleStack.appendChild(created)
        created
    }
    path.textContent = pathText
    path.title = pathText
}

private fun applyAi4AutoNameStyle(box: HTMLElement, generated: Boolean) {
    box.asDynamic().ai4_auto_name = generated
    val title = box.getElementsByClassName("doubleClickToEdit").asList().firstOrNull() as? HTMLElement ?: return
    title.style.fontStyle = if (generated) "italic" else ""
}

var styles = CssBuilder().apply {
    body {
        maxWidth = 100.vw
        maxHeight = 100.vh
//        backgroundColor = Color.red
    }

    ".selected" {
        borderColor = important(Color("rgba(var(--field-cyan-rgb), 0.78)"))
    }

    ".doubleClickToEdit" {
        userSelect = UserSelect.none
    }

}

private fun normalizeUserThemeCss(css: String): String {
    val trimmed = css.trim()
    if (trimmed.isBlank()) return ""
    return if (trimmed.contains("{")) {
        trimmed
    } else {
        ".field-user-theme {\n$trimmed\n}\n"
    }
}

private fun applyUserTheme(theme: UserTheme) {
    val styleId = "field-user-theme"
    val existing = document.getElementById(styleId) as? HTMLStyleElement
    val style = existing ?: (document.createElement("style") as HTMLStyleElement).also {
        it.id = styleId
        document.head!!.appendChild(it)
    }
    document.documentElement?.classList?.add("field-user-theme")
    style.textContent = normalizeUserThemeCss(theme.css)
    style.setAttribute("data-path", theme.path)
    style.setAttribute("data-last-modified", theme.lastModified.toString())
    window.asDynamic().fieldUserThemePath = theme.path
}

// ── Canvas history capture ───────────────────────────────────────────────────
// Canvas (electron-window) history screenshots used to be captured only inside history.html, which
// runs in an on-demand iframe — so they silently stopped whenever the History panel was closed
// (the case since the 2026-06 in-editor-history refactor). We now capture from the always-loaded
// editor, driven by the "history-changed" message on the editor's own WebSocket, so it fires on
// every save regardless of the panel. Electron-only: needs window.fieldElectron.captureHistoryScreenshot
// to grab the window pixels (a plain browser can't).
//
// The capture is costly in the *wrong process*: capturePage() -> encode -> base64 all run on
// Electron's single browser/main thread, which is also the only thread that routes input to the
// renderers. While it is busy every keystroke and cursor move in Monaco queues behind it -- and the
// renderer itself stays idle, so CSS/compositor animations keep running and it doesn't read as a
// freeze. It never reproduces in a plain browser: there is no fieldElectron there, so this whole
// path is skipped. Full-res PNG cost 533-543ms per shot (a 2.3MB data URL), and since this moved
// out of the on-demand History panel into the always-loaded editor it fires forever -- at the old
// 10s floor, roughly once per typed line.
//
// screenshotPayload() in electron/main.js now downscales to logical pixels and encodes JPEG, which
// brings a shot to ~40ms / ~23kB. The 2-minute floor is deliberate headroom on top of that, and is
// plenty for a history filmstrip.
private const val CANVAS_CAPTURE_IDLE_MS = 1200
private const val CANVAS_CAPTURE_MIN_INTERVAL_MS = 120_000
private var canvasCaptureInFlight = false
private var canvasCapturePending: Int? = null
private var canvasCaptureTimer: Int? = null
private var canvasCaptureLastAt = 0.0

private fun fieldElectronCaptureApi(): dynamic {
    val fe = window.asDynamic().fieldElectron
    return if (fe != null && jsTypeOf(fe.captureHistoryScreenshot) == "function") fe else null
}

private fun scheduleCanvasHistoryCapture(versionIndex: Int) {
    canvasCapturePending = versionIndex
    if (canvasCaptureInFlight || canvasCaptureTimer != null) return
    val elapsed = Date().getTime() - canvasCaptureLastAt
    val wait = maxOf(CANVAS_CAPTURE_IDLE_MS.toDouble(), CANVAS_CAPTURE_MIN_INTERVAL_MS - elapsed)
    canvasCaptureTimer = window.setTimeout({
        canvasCaptureTimer = null
        val v = canvasCapturePending ?: -1
        canvasCapturePending = null
        captureCanvasHistoryNow(v)
    }, wait.toInt())
}

// A base64 data URL carries 4 characters per 3 bytes, after the "data:image/...;base64," prefix.
private fun dataUrlByteEstimate(dataUrl: String): Int {
    val payload = dataUrl.length - (dataUrl.indexOf(',') + 1)
    return if (payload <= 0) 0 else payload / 4 * 3
}

private fun formatByteEstimate(bytes: Int): String = when {
    bytes >= 1024 * 1024 -> "${((bytes * 10.0 / (1024 * 1024)).roundToInt()) / 10.0}MB"
    bytes >= 1024 -> "${(bytes + 512) / 1024}kB"
    else -> "${bytes}B"
}

private fun captureCanvasHistoryNow(versionIndex: Int) {
    val api = fieldElectronCaptureApi() ?: return
    if (canvasCaptureInFlight) {
        scheduleCanvasHistoryCapture(versionIndex)
        return
    }
    canvasCaptureInFlight = true
    canvasCaptureLastAt = Date().getTime()
    val startedAt = canvasCaptureLastAt
    // Announce the capture before awaiting it. It stalls Electron's browser/main thread (and so all
    // input) for around half a second, and an unexplained periodic hiccup is a genuinely nasty thing
    // to debug -- this turns it into a visible, attributable event. "*history" reuses one bubble.
    notify("canvas history snapshot <<spinner>>", "*history")
    val finish = {
        canvasCaptureInFlight = false
        val v = canvasCapturePending
        canvasCapturePending = null
        if (v != null) scheduleCanvasHistoryCapture(v)
    }
    api.captureHistoryScreenshot().then({ shot: dynamic ->
        val dataUrl = shot?.dataUrl as? String
        val elapsed = (Date().getTime() - startedAt).roundToInt()
        if (!dataUrl.isNullOrEmpty()) {
            val width = (shot.width as? Number)?.toInt() ?: 0
            val height = (shot.height as? Number)?.toInt() ?: 0
            send(
                Message("store-history-screenshot").payload(
                    HistoryScreenshotStoreRequest(
                        channel = "electron-window",
                        dataUrl = dataUrl,
                        versionIndex = versionIndex,
                        width = width,
                        height = height
                    )
                )
            )
            notify(
                "canvas history snapshot — ${width}×$height, " +
                    "${formatByteEstimate(dataUrlByteEstimate(dataUrl))} in ${elapsed}ms",
                "*history"
            )
        } else {
            // Electron hands back an empty image when the window has no pixels to read (hidden,
            // minimised, fully occluded). Nothing to store, and it costs nothing.
            notify("canvas history snapshot skipped — window not visible", "*history")
        }
        finish()
        Unit
    }, { err: dynamic ->
        console.warn("canvas history capture failed", err)
        notify("canvas history snapshot failed", "*history")
        finish()
        Unit
    })
}

@OptIn(DelicateCoroutinesApi::class)
suspend fun main() {
    launchNumber++
    println(" -- welcome to Main.kt --")
    // Before anything registers an action or a global key listener, so they all resolve their
    // shortcuts through ~/.afield/keybindings.yaml from the very first registration.
    Keybindings.install()
    // Before the socket opens, so the server's per-attach `set-session-write-status` has a handler
    // waiting for it. A read-only session must be announced by the time the document appears, not
    // at some point after the user has started working in it.
    ReadOnlyMode.install()
    val searchParams = URLSearchParams(document.location?.search)
    val filename = searchParams.get("file") ?: "default"
    val username = searchParams.get("user") ?: "everybody"
    val sessionKey = searchParams.get("sessionKey")
    val scratch = searchParams.get("scratch") == "1" || searchParams.get("scratch") == "true"
    // Shared-selection channel + editor/canvas display modes (see dev docs / vision_frontend).
    val shaderSelectionContext = searchParams.get("shaderSelectionContext")?.takeIf { it.isNotBlank() }
    sharedUiContext = shaderSelectionContext
    window.asDynamic().fieldSharedUiContext = sharedUiContext
    fieldDisplayMode = when {
        flagParam(searchParams, "editorOnly") -> "editor-only"
        flagParam(searchParams, "noeditor") -> "no-editor"
        else -> "normal"
    }
    window.asDynamic().fieldDisplayMode = fieldDisplayMode
    document.body?.classList?.add(
        when (fieldDisplayMode) {
            "editor-only" -> "mode-editor-only"
            "no-editor" -> "mode-no-editor"
            else -> "mode-normal"
        }
    )
    // ?transparent — glass field backdrop, opaque boxes/outputs. Orthogonal to the display mode
    // (combine e.g. ?noeditor&transparent). Needs WebDock.isOpaque=false natively to see through.
    if (flagParam(searchParams, "transparent")) {
        document.body?.classList?.add("mode-transparent")
    }
    window.asDynamic().fieldBackdoorUiReady = false
    window.asDynamic().fieldBackdoorDisconnectedOverlayActive = false
    window.asDynamic().currentDocumentFilename = filename
    window.asDynamic().currentDocumentSessionKey = sessionKey
    window.asDynamic().currentDocumentScratch = scratch
    window.unsafeCast<dynamic>().selected = selected
    window.unsafeCast<dynamic>().selectedList = selectedList
    window.unsafeCast<dynamic>().selectedProperty = selectedProperty
    window.unsafeCast<dynamic>().selectionContextChanged = { selectionContextChanged() }
    window.unsafeCast<dynamic>().switchToProperty = { uid: String, property: String ->
        switchSelectionToProperty(uid, property, syncEditor = true)
    }
    window.unsafeCast<dynamic>().navigateToBoxProperty =
        { uid: String, property: String, scrollToLine: Int?, highlightLine: Int? ->
            navigateToBoxProperty(uid, property, scrollToLine, highlightLine)
        }
    window.unsafeCast<dynamic>().navigateToBoxFromLink =
        { uid: String, property: String, scrollToLine: Int?, highlightLine: Int? ->
            navigateToBoxProperty(uid, property, scrollToLine, highlightLine, center = true)
        }
    window.unsafeCast<dynamic>().selectBox = ::selectBox
    window.unsafeCast<dynamic>().deselectBox = ::deselectBox
    window.asDynamic().fieldBackdoorPrepareBoxScreenshot = { raw: dynamic -> prepareBoxScreenshotJson(raw) }
    window.asDynamic().fieldBackdoorPrepareWindowScreenshot = { raw: dynamic -> prepareWindowScreenshotJson(raw) }
    window.asDynamic().clearEditorOutputsForBox = { uid: String ->
        clearEditorOutputsForBox(uid)
        Unit
    }
    window.asDynamic().clearOutputAreasForBox = { uid: String ->
        findBoxElementByUid(uid)?.let { box ->
            handleOutputAreaControl(box, OutputControl(uid = uid, action = "clear-all"))
        }
        Unit
    }
    window.asDynamic().clearOutputAreaContentsForBox = { uid: String ->
        findBoxElementByUid(uid)?.let(::clearOutputAreaContents)
        Unit
    }

    window.unsafeCast<dynamic>().showCheatsheet = ::showCheatsheet

    // Intercept clicks on box-reference links anywhere in the document (Monaco view zones, output areas, etc.)
    document.addEventListener("click", { event ->
        val target = (event.target as? Element) ?: return@addEventListener
        val link = target.closest("a[data-box-uid], a[href^='#box:']") ?: return@addEventListener
        val uid = link.getAttribute("data-box-uid")?.trim()?.takeIf { it.isNotBlank() }
            ?: link.getAttribute("href")?.removePrefix("#box:")?.trim()?.takeIf { it.isNotBlank() }
            ?: return@addEventListener
        val property = link.getAttribute("data-box-property")?.trim()?.takeIf { it.isNotBlank() }
            ?: link.getAttribute("data-property")?.trim()?.takeIf { it.isNotBlank() }
        val scrollToLine = link.getAttribute("data-box-scroll-to-line")?.trim()?.toIntOrNull()
        val highlightLine = link.getAttribute("data-box-highlight-line")?.trim()?.toIntOrNull()
        event.preventDefault()
        event.stopPropagation()
        navigateToBoxProperty(uid, property ?: "code", scrollToLine, highlightLine)
    })

    // Initialize the box snapping system
    boxSnappingSystem.initialize()

    document.title = filename.substringAfterLast("/")

    registerDoubleTap()
    installSpatialNavigationShortcut()

    doubleTap["Escape"] = {
        removeNewestOutput()
    }
    tripleTap["Escape"] = {
        clearFocusedEditorOutputs()
    }

    fun requestConnectionGroupRebuild() {
        canvasGestures.requestConnectionGroupRebuild()
    }

    doubleTap["Shift"] = {
        val w = window.unsafeCast<dynamic>()
        val fn = w.openAnywherePalette
        if (fn != undefined && fn != null) {
            try {
                fn()
            } catch (e: Exception) {
                console.info("[palette] openAnywherePalette failed", e)
            }
        } else {
            console.info("[palette] openAnywherePalette not available")
        }
    }


    val batchedMessageDrain = object {
        private val deferred = mutableListOf<Message>()
        private var scheduled = false
        private val chunkSize = 20
        private val chunkMillis = 5.0

        private fun property(message: Message): String? =
            try {
                message.payload?.jsonObject?.get("property")?.jsonPrimitive?.contentOrNull
            } catch (_: Throwable) {
                null
            }

        fun shouldDefer(message: Message): Boolean {
            if (message.name == "message-batch") return true
            if (message.name == "describe-action") return true
            if (message.name == "set-string") {
                return property(message) !in setOf("name", "layers", "layerFrames", AI4_AUTO_NAME_PROPERTY)
            }
            return false
        }

        fun enqueue(messages: List<Message>) {
            if (messages.isEmpty()) return
            deferred.addAll(messages.filter { it.name != "message-batch" })
            schedule()
        }

        private fun schedule() {
            if (scheduled) return
            scheduled = true
            window.setTimeout({ drain() }, 0)
        }

        private fun drain() {
            scheduled = false
            val started = window.performance.now()
            var processed = 0
            while (deferred.isNotEmpty() && processed < chunkSize && window.performance.now() - started < chunkMillis) {
                val child = deferred.removeAt(0)
                handle(child)
                processed++
            }
            if (deferred.isNotEmpty()) {
                schedule()
            }
        }
    }

    handlers.put("message-batch") { message, reply ->
        val batch = Json.decodeFromJsonElement<MessageBatch>(message.payload!!)
        if (!LoadingTracker.isFinished()) {
            fieldDiagnostic(
                "ws-message-batch",
                json(
                    "count" to batch.messages.size,
                    "names" to batch.messages.take(100).map { it.name }.toTypedArray()
                )
            )
        }
        val deferred = mutableListOf<Message>()
        batch.messages.forEach { child ->
            if (batchedMessageDrain.shouldDefer(child)) {
                deferred.add(child)
            } else {
                handle(child, reply)
            }
        }
        batchedMessageDrain.enqueue(deferred)
        Handled.yes
    }

    handlers.put("local-eval") { message, _ ->
        val le = Json.decodeFromJsonElement<LocalEval>(message.payload!!)
        val boxes = document.body!!.getElementsByClassName("box").asList()
        val found = boxes.firstOrNull { it.unsafeCast<dynamic>().uid == le.uid }

        found?.let {
            try {
                handleEvalRequest(found as HTMLElement, le.prompt)
            } catch (e: Exception) {
                println("exception thrown in local eval")
                e.printStackTrace()
            }
        }

        Handled.yes
    }

    fun applyIncomingRect(set: Set_rect): Boolean {
        if (set.property != "frame") return false

        val u = set.uid
        val boxes = document.body!!.getElementsByClassName("box").asList()
        val found = boxes.firstOrNull { it.unsafeCast<dynamic>().uid == u }

        if (found === null) {
            mainCanvas.append {
                createBox("", frame = set.value, uid = u, sendInitialSync = false)
            }
            LoadingTracker.boxAdded(u)
        } else {
            val box = found as HTMLElement
            val wasRenderable = hasRenderableFrame(box)
            applyFrameToBox(box, set.value)
            applyBoxVisibilityImmediate(box)
            if (!wasRenderable && hasRenderableFrame(box)) {
                animateOfflineBoxArrival(box)
            }
        }
        hideSplashScreenAfterFontLoad()
        updateEmptyDocumentWelcome()
        return true
    }

    handlers.put("expected-box-count") { message, _ ->
        val ebc = Json.decodeFromJsonElement<Expected_box_count>(message.payload!!)
        LoadingTracker.setExpectedCount(ebc.count)
        LoadingTracker.boxFramesPreviewed(ebc.frames.map { it.uid })
        renderInitialBoxPlaceholders(ebc.frames)
        Handled.yes
    }

    handlers.put("document-load-status") { message, _ ->
        val status = Json.decodeFromJsonElement<DocumentLoadStatus>(message.payload!!)
        LoadingTracker.documentLoadStatus(status)
        if (status.terminal && status.count == 0) {
            hideSplashScreenAfterFontLoad()
        }
        Handled.yes
    }

    handlers.put("set-user-theme") { message, _ ->
        val theme = Json.decodeFromJsonElement<UserTheme>(message.payload!!)
        applyUserTheme(theme)
        Handled.yes
    }

    handlers.put("set-rect") { message, _ ->
        val set = Json.decodeFromJsonElement<Set_rect>(message.payload!!)
        if (!applyIncomingRect(set)) return@put Handled.no
        requestConnectionGroupRebuild()
        repositionSlideNoteOverlayIfVisible()

        Handled.yes
    }

    handlers.put("set-rects") { message, _ ->
        val set = Json.decodeFromJsonElement<Set_rects>(message.payload!!)
        var changed = false
        set.values.forEach { entry ->
            if (applyIncomingRect(entry)) changed = true
        }
        if (changed) {
            requestConnectionGroupRebuild()
            repositionSlideNoteOverlayIfVisible()
            return@put Handled.yes
        }
        Handled.no
    }

    handlers.put("set-animated-rect") { message, _ ->
        val set = Json.decodeFromJsonElement<Set_animated_rect>(message.payload!!)
        if (set.property != "frame") return@put Handled.no

        val u = set.uid
        val boxes = document.body!!.getElementsByClassName("box").asList()
        val found = boxes.firstOrNull { it.unsafeCast<dynamic>().uid == u } as? HTMLElement

        if (found != null) {
            // Add transition CSS
            found.style.transition =
                "transform ${set.duration}ms ease-in-out, width ${set.duration}ms ease-in-out, height ${set.duration}ms ease-in-out"

            // Apply new position and size
            applyFrameToBox(found, set.value)
            applyBoxVisibilityImmediate(found)

            // Remove transition after animation completes
            window.setTimeout({
                found.style.transition = ""
                requestConnectionGroupRebuild()
            }, set.duration.toInt())
        }

        Handled.yes
    }

    handlers.put("set-visibility-animated") { message, _ ->
        val payload = message.payload?.let { Json.decodeFromJsonElement<Map<String, Any>>(it) } ?: return@put Handled.no
        val uid = payload["uid"] as? String ?: return@put Handled.no
        val visible = payload["visible"] as? Boolean ?: return@put Handled.no
        val duration = (payload["duration"] as? Double) ?: 300.0
        layerDebugLog(
            "recv set-visibility-animated",
            json(
                "uid" to uid,
                "visible" to visible,
                "duration" to duration
            )
        )

        val boxes = document.body!!.getElementsByClassName("box").asList()
        val found = boxes.firstOrNull { it.unsafeCast<dynamic>().uid == uid } as? HTMLElement

        if (found != null) {
            found.asDynamic().server_visible = visible

            if (!hasRenderableFrame(found)) {
                applyBoxVisibilityImmediate(found)
                requestConnectionGroupRebuild()
                return@put Handled.yes
            }

            if (visible) {
                clearPendingVisibilityHide(found)
                // Fade in: set visibility first with opacity 0, then animate to 1
                found.style.display = "flex"
                found.style.visibility = "visible"
                found.style.opacity = "0"
                found.style.transition = "opacity ${duration}ms ease-in-out"

                window.setTimeout({
                    found.style.opacity = "1"
                }, 10)

                window.setTimeout({
                    found.style.transition = ""
                }, duration.toInt() + 10)
            } else {
                // Fade out: animate opacity to 0, then set visibility hidden
                clearPendingVisibilityHide(found)
                found.style.transition = "opacity ${duration}ms ease-in-out"
                found.style.opacity = "0"

                val hideHandle = window.setTimeout({
                    found.style.visibility = "hidden"
                    found.style.display = "none"
                    found.style.transition = ""
                    found.style.opacity = "1" // Reset for next show
                    found.asDynamic().__visibilityHideTimeout = null
                }, duration.toInt())
                found.asDynamic().__visibilityHideTimeout = hideHandle
            }

            // Update connections after animation
            window.setTimeout({
                requestConnectionGroupRebuild()
            }, duration.toInt())
        }

        Handled.yes
    }

    handlers.put("set-boolean") { message, _ ->
        val set = Json.decodeFromJsonElement<Set_boolean>(message.payload!!)
        val u = set.uid

        val boxes = document.body!!.getElementsByClassName("box").asList()
        var found = boxes.firstOrNull { it.unsafeCast<dynamic>().uid == u }

        fun defaultCreate() {
            mainCanvas.append {
                found = createBox("untitled", frame = Rect(0.0, 0.0, 0.0, 0.0), uid = u, sendInitialSync = false)
            }
        }
        if (found == null) {
            defaultCreate()
        }


        when (set.property) {
            "visible" -> {
                if (found != null) {
                    val box = found as HTMLElement
                    layerDebugLog(
                        "recv set-boolean visible",
                        json(
                            "uid" to u,
                            "value" to set.number,
                            "box_layers" to ((box.asDynamic().box_layers as? String) ?: "default"),
                            "visibleLayers" to visibleLayers.toList()
                        )
                    )
                    box.asDynamic().server_visible = set.number
                    applyBoxVisibilityImmediate(box)
                    requestConnectionGroupRebuild()
                }
            }

            "offline" -> {
                if (found != null) {
                    if (set.number) {
                        found.unsafeCast<HTMLElement>().classList.add("offline")
                        found.asDynamic().offline = true
                        applyBoxVisibilityImmediate(found.unsafeCast<HTMLElement>())
                        requestConnectionGroupRebuild()
                    } else {
                        found.unsafeCast<HTMLElement>().classList.remove("offline")
                        found.asDynamic().offline = false
                        LoadingTracker.boxOnline(u)
                        applyBoxVisibilityImmediate(found.unsafeCast<HTMLElement>())
                        removeBoxPlaceholder(u)
                        requestConnectionGroupRebuild()
                    }
                }
            }

            AI4_AUTO_NAME_PROPERTY -> {
                found?.let {
                    applyAi4AutoNameStyle(it.unsafeCast<HTMLElement>(), set.number)
                }
            }

            "_tombstone" -> {
                var target = found
                if (target == null) {
                    mainCanvas.append {
                        target =
                            createBox("untitled", frame = Rect(0.0, 0.0, 0.0, 0.0), uid = u, sendInitialSync = false)
                    }
                }
                target?.let { boxEl ->
                    val box = boxEl.unsafeCast<HTMLElement>()
                    if (set.number) {
                        box.style.display = "none"
                        box.classList.add("tombstoned")
                        box.asDynamic().tombstoned = true
                        // Release the box's output areas (observers, iframes, render
                        // loops, editors). A hidden tombstoned box otherwise keeps them
                        // alive and running, so a long-lived page that deletes many boxes
                        // (docs capture runner) accumulates them and pins the browser.
                        teardownBoxOutputs(box)
                        if (selected.contains(box)) {
                            deselectBox(box)
                        }
                    } else {
                        box.classList.remove("tombstoned")
                        box.asDynamic().tombstoned = false
                        applyBoxVisibilityImmediate(box)
                    }
                    updateEmptyDocumentWelcome()
                    requestConnectionGroupRebuild()
                }
            }
        }
        Handled.yes
    }

    handlers.put("is-running") { message, _ ->
        println(" got is-running message ${message}")
        val isRunning = Json.decodeFromJsonElement<IsRunning>(message.payload!!)
        setBoxRunning(isRunning.uid, isRunning.isRunning, isRunning.watermark, isRunning.strand, isRunning.mode)

        Handled.yes
    }
    handlers.put("output") { message, _ ->
        val output = Json.decodeFromJsonElement<Output>(message.payload!!)
        routeMonacoOutput(output)
        acknowledgeOutputRendered("output-handler")
        Handled.yes
    }
    handlers.put("peer-output") { message, _ ->
        if (!window.location.search.contains("user=playwright")) {
            acknowledgeOutputRendered("peer-output-ignored")
            return@put Handled.no
        }
        val output = Json.decodeFromJsonElement<Output>(message.payload!!)
        routeMonacoOutput(output)
        acknowledgeOutputRendered("peer-output-handler")
        Handled.yes
    }
    handlers.put("describe-action") { message, _ ->
        val set = Json.decodeFromJsonElement<Describe_actions>(message.payload!!)
        val u = set.uid

        val boxes = document.body!!.getElementsByClassName("box").asList()
        var found = boxes.firstOrNull { it.unsafeCast<dynamic>().uid == u }

        if (found != null) {
            found.unsafeCast<dynamic>().injectedActions = set.value
            refreshCommandsFromSelection(
                selected.toList(),
                selected.mapNotNull { it.unsafeCast<dynamic>().injectedActions as List<Action_Desc>? }
                    .toList())
        }

        Handled.yes
    }

    fun defaultCreate(u: String): HTMLElement {
        val boxes = document.body!!.getElementsByClassName("box").asList()
        var found = boxes.firstOrNull { it.unsafeCast<dynamic>().uid == u } as HTMLElement?
        if (found != null) {
            if (u == "root") {
                found.style.display = "none"
                found.style.visibility = "hidden"
            }
            return found
        }

        mainCanvas.append {
            found = createBox("untitled", frame = Rect(0.0, 0.0, 0.0, 0.0), uid = u, sendInitialSync = false)
        }

        if (u == "root") {
            // keep the root transport box invisible to users but present for state sync
            found!!.style.display = "none"
            found!!.style.visibility = "hidden"
        }
        return found!!
    }

    fun ensureNestedOutputArea(box: HTMLElement): HTMLElement {
        val outputArea = box.getElementsByClassName("outputArea").asList().firstOrNull() as? HTMLElement
            ?: return box
        val contain = outputArea.querySelector(".disclosure-group")
        val di = js("window.disclosureInterface")
        if (di == null) {
            console.info("[nested] disclosureInterface not ready yet; skipping nested output area init")
            return outputArea
        }
        val inter = di(contain!!)
        val disclosureContent = (contain.querySelectorAll(".disclosure").asList().firstOrNull {
            (it.asDynamic().__disclosureId as? String) == "nested"
        } as? HTMLElement)?.querySelector(".disclosure-content") as? HTMLElement ?: (
                inter.upsertDisclosure as (String, String, String) -> HTMLElement
                ).invoke(
                "nested",
                "nested",
                ""
            )
        val nestedRoot = disclosureContent.querySelector(".nested-root") as? HTMLElement ?: run {
            val host = document.createElement("div") as HTMLElement
            host.classList.add("nested-root")
            disclosureContent.appendChild(host)
            host
        }
        nestedRoot.style.asDynamic().overflow = "hidden"
        nestedRoot.style.position = "relative"
        nestedRoot.style.width = "100%"
        nestedRoot.style.height = "100%"

        // Insert or reuse a dedicated nested canvas inside the disclosure content
        val canvas = nestedRoot.querySelector(".nested-canvas") as? HTMLElement ?: run {
            val nc = document.createElement("div") as HTMLElement
            nc.classList.add("nested-canvas")
            nestedRoot.appendChild(nc)
            nc
        }
        val content = canvas.querySelector(".nested-content") as? HTMLElement ?: run {
            val nc = document.createElement("div") as HTMLElement
            nc.classList.add("nested-content")
            canvas.appendChild(nc)
            nc
        }
        val directBoxes = mutableListOf<HTMLElement>()
        for (i in 0 until canvas.children.length) {
            val child = canvas.children.item(i) as? HTMLElement ?: continue
            if (child.classList.contains("box")) {
                directBoxes += child
            }
        }
        directBoxes.forEach { child ->
            content.appendChild(child)
        }
        attachPanToNestedArea(canvas)
        outputArea.style.visibility = "visible"
        outputArea.style.height = "auto"
        return content
    }

    fun handleNestedTopology(parent: HTMLElement, nestedIds: List<String>) {
        val previous = (parent.unsafeCast<dynamic>().box_nested as? Array<String>)?.toSet() ?: emptySet()
        val newSet = nestedIds.toSet()
        val toRemove = previous - newSet

        fun removeChildrenFromNested(childIds: Set<String>) {
            childIds.forEach { childId ->
                val child = document.body!!.getElementsByClassName("box").asList()
                    .firstOrNull { it.unsafeCast<dynamic>().uid == childId } as? HTMLElement
                if (child != null && child != parent) {
                    mainCanvas.appendChild(child)
                }
            }
        }

        // Only create nested output area if there are actually nested boxes
        if (newSet.isEmpty()) {
            // If there were previously nested boxes, remove them back to main canvas
            removeChildrenFromNested(toRemove)
            parent.unsafeCast<dynamic>().box_nested = emptyArray<String>()
            return
        }

        val nestedRoot = ensureNestedOutputArea(parent)

        removeChildrenFromNested(toRemove)

        nestedIds.forEach { childId ->
            val child = defaultCreate(childId)
            if (child != parent) {
                nestedRoot.appendChild(child)
            }
        }

        parent.unsafeCast<dynamic>().box_nested = nestedIds.toTypedArray()
    }

    handlers.put("set-topology") { message, _ ->
        val set = Json.decodeFromJsonElement<Set_topology>(message.payload!!)
        val u = set.uid

        var found = defaultCreate(u)

        when (set.kind) {
            "box_children" -> {
                found.unsafeCast<dynamic>().box_children = set.value.toTypedArray()
            }

            "box_nested" -> {
                handleNestedTopology(found, set.value)
            }

            "box_nestedIn" -> {
                found.unsafeCast<dynamic>().box_nestedIn = set.value.firstOrNull()
            }
        }

        requestConnectionGroupRebuild()
        Handled.yes
    }

    handlers.put("notify") { message, _ ->
        val set = Json.decodeFromJsonElement<Notify>(message.payload!!)
        notify(set.text, set.category)
        Handled.yes
    }

    handlers.put("osc-editor") { message, _ ->
        val command = Json.decodeFromJsonElement<OscEditorCommand>(message.payload!!)
        monacoRunOscEditorCommand(command)
        Handled.yes
    }

    handlers.put("box-screenshot") { message, reply ->
        val req = Json.decodeFromJsonElement<BoxScreenshotRequest>(message.payload!!)
        val responseName = message.replyTo ?: "box-screenshot"
        window.setTimeout({
            val result = runCatching { prepareBoxScreenshot(req) }.getOrElse { throwable ->
                console.error("[screenshot] box prep failed", throwable)
                BoxScreenshotResponse(
                    uid = req.uid,
                    success = false,
                    message = throwable.message ?: "Unexpected box screenshot preparation failure"
                )
            }
            reply(Message(responseName).payload(result))
        }, 220)
        Handled.yes
    }

    handlers.put("window-screenshot") { message, reply ->
        val req = Json.decodeFromJsonElement<WindowScreenshotRequest>(message.payload!!)
        val responseName = message.replyTo ?: "window-screenshot"
        window.setTimeout({
            val result = runCatching { prepareWindowScreenshot(req) }.getOrElse { throwable ->
                console.error("[screenshot] window prep failed", throwable)
                WindowScreenshotResponse(
                    uid = req.uid,
                    success = false,
                    message = throwable.message ?: "Unexpected window screenshot preparation failure"
                )
            }
            reply(Message(responseName).payload(result))
        }, 220)
        Handled.yes
    }

    window.asDynamic().fieldBackdoorUiReady = true

    // Canvas history capture: on every save the server broadcasts "history-changed" to this
    // editor's WebSocket; grab the Electron window and store it under channel "electron-window".
    // Only in Electron (fieldElectron present). The flag tells history.html to skip its own
    // now-redundant trigger so the panel being open doesn't double-capture.
    if (fieldElectronCaptureApi() != null) {
        window.asDynamic().__canvasHistoryCaptureActive = true
        handlers.put("history-changed") { message, _ ->
            val versionIndex = message.payload
                ?.let { Json.decodeFromJsonElement<HistoryChanged>(it) }
                ?.versionIndex ?: -1
            scheduleCanvasHistoryCapture(versionIndex)
            Handled.yes
        }
    }

    handlers.put("set-output") { message, _ ->
        val set = Json.decodeFromJsonElement<Set_output>(message.payload!!)
        val u = set.uid

        var found = defaultCreate(u)

        when (set.property) {
            "outputarea" -> {
                handleOutputAreaRequest(found, set)
            }
        }
        // Release back pressure only after disclosure content has crossed a browser paint boundary.
        acknowledgeOutputRendered("output-area")
        Handled.yes
    }

    handlers.put("output-control") { message, _ ->
        val control = Json.decodeFromJsonElement<OutputControl>(message.payload!!)
        val u = control.uid
        val found = defaultCreate(u)
        handleOutputAreaControl(found, control)
        acknowledgeOutputRendered("output-control")
        Handled.yes
    }

    handlers.put("clear-editor-outputs") { message, _ ->
        val clear = Json.decodeFromJsonElement<ClearEditorOutputs>(message.payload!!)
        clearEditorOutputsForBoxFromServer(clear.uid)
        Handled.yes
    }

    handlers.put("control-value-update") { message, _ ->
        val payload = message.payload ?: return@put Handled.no
        val fn = window.asDynamic().applyFieldControlUpdate
        if (jsTypeOf(fn) == "function") {
            fn(JSON.parse<dynamic>(payload.toString()))
            Handled.yes
        } else {
            Handled.no
        }
    }

    // A selection pushed from a peer sharing our ?shaderSelectionContext. The server only sends
    // this to context peers (never the originator), so applying it can't loop back on the sender.
    handlers.put("controller-selection") { message, _ ->
        val payload = message.payload ?: return@put Handled.no
        val state = runCatching { Json.decodeFromJsonElement<ControllerSelectionState>(payload) }.getOrNull()
            ?: return@put Handled.no
        applyRemoteSelection(state)
        Handled.yes
    }

    // Canvas-owned panels can be requested from a paired editor-only WKWebView. Only the canvas
    // projection consumes these intents; other editor projections deliberately ignore them.
    handlers.put("shared-ui-intent") { message, _ ->
        val payload = message.payload ?: return@put Handled.no
        val intent = runCatching { Json.decodeFromJsonElement<SharedUiIntent>(payload) }.getOrNull()
            ?: return@put Handled.no
        if (fieldDisplayMode == "no-editor") {
            when (intent.action) {
                "show-document-history" -> showDocumentHistoryLocally(intent.filename)
                else -> return@put Handled.no
            }
        }
        Handled.yes
    }

    handlers.put("set-provisional") { message, _ ->
        val set = Json.decodeFromJsonElement<ProvisionalSet>(message.payload!!)
        Provisional.clear()
        set.entries.forEach { entry ->
            Provisional.set(entry.uid, entry.property, entry.contents)
        }
        Handled.yes
    }

    handlers.put("history-restore-begin") { _, _ ->
        js("window.__historyRestoreAuthoritative = true")
        Handled.yes
    }

    handlers.put("history-restore-end") { _, _ ->
        window.setTimeout({
            js("window.__historyRestoreAuthoritative = false")
        }, 0)
        Handled.yes
    }

    handlers.put("actor-arc") { message, _ ->
        val arc = Json.decodeFromJsonElement<ActorArc>(message.payload!!)
        drawActorArc(arc)
        Handled.yes
    }

    handlers.put("set-null") { message, _ ->
        val set = Json.decodeFromJsonElement<Set_null>(message.payload!!)
        val u = set.uid
        val found = defaultCreate(u)

        found.unsafeCast<dynamic>()[set.property] = null
        if (set.property == "name") {
            found.getElementsByClassName("doubleClickToEdit").asList().firstOrNull()?.innerHTML = ""
            applyAi4AutoNameStyle(found.unsafeCast<HTMLElement>(), found.asDynamic().ai4_auto_name == true)
        }
        if (set.property == "_needsRerun") {
            val element = found.unsafeCast<HTMLElement>()
            element.classList.remove("needs-rerun")
            element.title = ""
        }
        if (set.property in monacoRemoteTextProperties) {
            notifyEmbeddedEditorOfRemoteUpdate(u, set.property, "")
            notifyPotentialEditedPropertyHasBeenRemoteUpdated(u, set.property, "")
        }

        Handled.yes
    }

    handlers.put("set-string") { message, _ ->
        val set = Json.decodeFromJsonElement<Set_string>(message.payload!!)
        if (!acceptSetStringSync(set)) return@put Handled.yes
        val u = set.uid

        var found = defaultCreate(u)

        when {
            set.property == "name" -> {
                found.getElementsByClassName("doubleClickToEdit").asList()[0].innerHTML = set.value
                found.unsafeCast<dynamic>().name = set.value
                applyAi4AutoNameStyle(found.unsafeCast<HTMLElement>(), found.asDynamic().ai4_auto_name == true)
            }

            // reloadPersonal dropped this box's Kotlin environment. It is deliberately not re-run for
            // you, so the box has to say so itself -- the value is the reason, which becomes the
            // tooltip. Cleared via set-null when the box is next executed.
            set.property == "_needsRerun" -> {
                val element = found.unsafeCast<HTMLElement>()
                element.classList.add("needs-rerun")
                element.title = set.value
            }

            set.property == "code" -> {
                found.unsafeCast<dynamic>().code = set.value
                notifyPotentialEditedPropertyHasBeenRemoteUpdated(u, "code", set.value)
                notifyEmbeddedEditorOfRemoteUpdate(u, "code", set.value)
            }

            set.property.endsWith("_editorState") -> {
                found.unsafeCast<dynamic>()[set.property] = set.value
                notifyPotentialEditedPropertyHasEditorStateRemoteUpdated(
                    u,
                    set.property.removeSuffix("_editorState"),
                    set.value
                )
            }

            set.property == "code_decorationState" -> {
                found.unsafeCast<dynamic>().decorSpec = set.value
                val sv = JSON.parse<dynamic>(set.value)
                setDecorationsForEditor(u, "code", found.unsafeCast<HTMLElement>(), sv)
            }

            set.property == "code_language" -> {
                if (set.value != found.unsafeCast<dynamic>().code_language) {
                    found.unsafeCast<dynamic>().code_language = set.value
                    notifyPotentialEditedPropertyHasHadLanguageChanged(u, "code", set.value)
                }
            }

            set.property == "contents" -> {
                found.unsafeCast<dynamic>().contents = set.value
                notifyPotentialEditedPropertyHasBeenRemoteUpdated(u, "contents", set.value)
                notifyEmbeddedEditorOfRemoteUpdate(u, "contents", set.value)
            }

            set.property == "contents_language" -> {
                if (set.value != found.unsafeCast<dynamic>().contents_language) {
                    found.unsafeCast<dynamic>().contents_language = set.value
                    notifyPotentialEditedPropertyHasHadLanguageChanged(u, "contents", set.value)
                }
            }

            set.property == "file_proxy_error" -> {
                applyFileProxyErrorStatus(found, set.value)
            }

            set.property == "file_proxy_mode" -> {
                found.unsafeCast<dynamic>().file_proxy_mode = set.value
                applyFileProxyPresentation(found)
            }

            set.property == "file_proxy_path" -> {
                found.unsafeCast<dynamic>().file_proxy_path = set.value
                applyFileProxyPresentation(found)
            }

            else -> when (set.property) {
                "fragment" -> {
                    found.unsafeCast<dynamic>().fragment = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "fragment", set.value)
                }

                "stackSL" -> {
                    found.unsafeCast<dynamic>().stackSL = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "stackSL", set.value)
                }

                "rectSL" -> {
                    found.unsafeCast<dynamic>().rectSL = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "rectSL", set.value)
                }

                "vertex_fragment" -> {
                    found.unsafeCast<dynamic>().vertex_fragment = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "vertex_fragment", set.value)
                }

                "fragment_s" -> {
                    found.unsafeCast<dynamic>().fragment_s = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "fragment_s", set.value)
                }

                "task" -> {
                    found.unsafeCast<dynamic>().task = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "task", set.value)
                }

                "task_s" -> {
                    found.unsafeCast<dynamic>().task_s = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "task_s", set.value)
                }

                "mesh" -> {
                    found.unsafeCast<dynamic>().mesh = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "mesh", set.value)
                }

                "mesh_s" -> {
                    found.unsafeCast<dynamic>().mesh_s = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "mesh_s", set.value)
                }

                "raygen" -> {
                    found.unsafeCast<dynamic>().raygen = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "raygen", set.value)
                }

                "raygen_s" -> {
                    found.unsafeCast<dynamic>().raygen_s = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "raygen_s", set.value)
                }

                "miss" -> {
                    found.unsafeCast<dynamic>().miss = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "miss", set.value)
                }

                "miss_s" -> {
                    found.unsafeCast<dynamic>().miss_s = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "miss_s", set.value)
                }

                "closesthit" -> {
                    found.unsafeCast<dynamic>().closesthit = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "closesthit", set.value)
                }

                "closesthit_s" -> {
                    found.unsafeCast<dynamic>().closesthit_s = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "closesthit_s", set.value)
                }

                "anyhit" -> {
                    found.unsafeCast<dynamic>().anyhit = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "anyhit", set.value)
                }

                "anyhit_s" -> {
                    found.unsafeCast<dynamic>().anyhit_s = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "anyhit_s", set.value)
                }

                "intersection" -> {
                    found.unsafeCast<dynamic>().intersection = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "intersection", set.value)
                }

                "intersection_s" -> {
                    found.unsafeCast<dynamic>().intersection_s = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "intersection_s", set.value)
                }

                "callable" -> {
                    found.unsafeCast<dynamic>().callable = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "callable", set.value)
                }

                "callable_s" -> {
                    found.unsafeCast<dynamic>().callable_s = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "callable_s", set.value)
                }

                "metadata" -> {
                    found.unsafeCast<dynamic>().metadata = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "metadata", set.value)
                }

                "compute" -> {
                    found.unsafeCast<dynamic>().compute = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "compute", set.value)
                }

                "compute_s" -> {
                    found.unsafeCast<dynamic>().compute_s = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "compute_s", set.value)
                }

                "preamble" -> {
                    found.unsafeCast<dynamic>().preamble = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "preamble", set.value)
                }

                "introduction" -> {
                    found.unsafeCast<dynamic>().introduction = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "introduction", set.value)
                }

                "attic" -> {
                    found.unsafeCast<dynamic>().attic = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "attic", set.value)
                }

                "notes" -> {
                    found.unsafeCast<dynamic>().notes = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "notes", set.value)
                }

                "vertex" -> {
                    found.unsafeCast<dynamic>().vertex = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "vertex", set.value)
                }

                "vertex_s" -> {
                    found.unsafeCast<dynamic>().vertex_s = set.value
                    notifyEmbeddedEditorOfRemoteUpdate(u, "vertex_s", set.value)
                }

                "customization" -> {
                    handleCustomizationRequest(found, set)
                }

                "layers" -> {
                    layerDebugLog(
                        "recv set-string layers",
                        json(
                            "uid" to set.uid,
                            "value" to set.value,
                            "visibleLayers" to visibleLayers.toList()
                        )
                    )
                    found.unsafeCast<dynamic>().box_layers = set.value
                    updateAllBoxVisibility()
                    requestConnectionGroupRebuild()
                }

                "layerFrames" -> {
                    // layerFrames comes as a JSON string representation of Map<String, Rect>
                    try {
                        val parsed = JSON.parse<dynamic>(set.value)
                        found.unsafeCast<dynamic>().box_layerFrames = parsed
                    } catch (e: Exception) {
                        console.error(
                            "Failed to parse layerFrames for box " + set.uid + ". Value: '" + set.value.take(
                                100
                            ) + "...'", e
                        )
                    }
                }

                "drawing" -> {
                    found.unsafeCast<dynamic>().drawing = set.value
                    if (u == "root") onRemoteDrawingScene(set.value)
                }

                else -> {
                    found.unsafeCast<dynamic>()[set.property] = set.value
                }
            }
        }

        if (set.property in monacoRemoteTextProperties) {
            notifyPotentialEditedPropertyHasBeenRemoteUpdated(u, set.property, set.value)
        }

        Handled.yes
    }

    // Slideshow handlers
    handlers.put("select-and-edit-box") { message, _ ->
        val sel = Json.decodeFromJsonElement<SelectAndEditBox>(message.payload!!)
        navigateToBoxProperty(sel.uid, sel.property, sel.scrollToLine, sel.highlightLine, center = sel.center,
            column = sel.column, endColumn = sel.endColumn)

        Handled.yes
    }

    handlers.put("show-slide-note") { message, _ ->
        val note = Json.decodeFromJsonElement<ShowSlideNote>(message.payload!!)
        slideNoteAnchorUid = note.uid.ifBlank { null }

        // Get or create the slide note overlay
        var overlay = document.getElementById("slide-note-overlay") as? HTMLElement
        if (overlay == null) {
            overlay = document.createElement("div") as HTMLElement
            overlay.id = "slide-note-overlay"
            overlay.classList.add("fallback")
            overlay.style.apply {
                position = "absolute"
                maxWidth = "400px"
                backgroundColor = "rgba(32, 32, 36, 0.9)"
                color = "#e6e6e6"
                padding = "5px 20px 5px 20px"
                borderRadius = "8px"
                boxShadow = "0 4px 12px rgba(0, 0, 0, 0.4)"
                zIndex = "10000"
                fontFamily =
                    "\"Iosevka\", \"Iosevka Term\", \"Iosevka Nerd Font\", \"Fira Code\", Menlo, Consolas, monospace"
                fontSize = "13px"
                lineHeight = "1.6"
                unsafeCast<dynamic>().backdropFilter = "blur(12px) brightness(1.04)"
            }
            document.body!!.appendChild(overlay)
        }

        // Clear previous content
        overlay.innerHTML = ""
        cleanupChatWidgets()

        // Add main content (animated typewriter reveal when requested)
        val contentDiv = document.createElement("div") as HTMLElement
        overlay.appendChild(contentDiv)
        if (note.animated) {
            animateSlideNoteText(contentDiv, note.html)
        } else {
            contentDiv.innerHTML = note.html
        }

        // Add chat UI if slideshow ID and slide number are present
        if (note.slideshowId.isNotBlank() && note.slideNumber >= 0) {
            val chatContainer = createChatUI(note.slideshowId, note.slideNumber)
            overlay.appendChild(chatContainer)
        }

        overlay.style.display = "block"

        if (note.persistent) {
            val closeBtn = document.createElement("button") as HTMLElement
            closeBtn.classList.add("close-btn")
            closeBtn.innerHTML = "×"
            closeBtn.style.apply {
                position = "absolute"
                top = "8px"
                right = "8px"
                background = "rgba(255, 255, 255, 0.2)"
                border = "none"
                borderRadius = "4px"
                color = "#fff"
                fontSize = "20px"
                width = "24px"
                height = "24px"
                cursor = "pointer"
                lineHeight = "20px"
                textAlign = "center"
            }
            closeBtn.onclick = {
                overlay.style.display = "none"
                clearSlideAnnotations()
            }
            overlay.appendChild(closeBtn)
        }

        positionSlideNoteOverlay(overlay, slideNoteAnchorUid)

        // Setup link handlers for navigation
        val links = overlay.getElementsByTagName("a").asList()
        links.forEach { link ->
            val a = link as HTMLElement
            a.onclick = { event ->
                event.preventDefault()
                val href = a.getAttribute("href")
                if (href != null) {
                    when {
                        href.startsWith("#slideshow:") -> {
                            val command = href.substring(11) // Remove "#slideshow:" prefix
                            // Send command to server to control slideshow
                            send("slideshow-command", js("{command: command}"))
                        }

                        href.startsWith("#custom:") -> {
                            val callbackName = href.substring(8) // Remove "#custom:" prefix
                            // Send callback command to server
                            send("slideshow-command", js("{command: 'callback:' + callbackName}"))
                        }
                    }
                }
                null
            }
        }

        // Draw annotation lines
        if (note.annotations.isNotEmpty()) {
            drawSlideAnnotations(note.annotations, overlay)
        } else {
            clearSlideAnnotations()
        }

        Handled.yes
    }

    handlers.put("hide-slide-note") { message, _ ->
        val overlay = document.getElementById("slide-note-overlay") as? HTMLElement
        if (overlay != null) {
            overlay.style.display = "none"
        }
        slideNoteAnchorUid = null
        clearSlideAnnotations()
        Handled.yes
    }

    handlers.put("chat-messages-loaded") { message, _ ->
        val loaded = Json.decodeFromJsonElement<ChatMessagesLoaded>(message.payload!!)
        cleanupChatWidgets()
        widgetsFor(loaded.slideshowId, loaded.slideNumber).forEach { widget ->
            displayChatMessages(widget.messagesArea, loaded.messages)
        }
        Handled.yes
    }

    handlers.put("chat-message-posted") { message, _ ->
        val posted = Json.decodeFromJsonElement<ChatMessagePosted>(message.payload!!)
        cleanupChatWidgets()
        if (widgetsFor(posted.slideshowId, posted.slideNumber).isNotEmpty()) {
            loadChatMessages(posted.slideshowId, posted.slideNumber)
        }
        Handled.yes
    }

    // Documentation iframe handlers
    handlers.put("show-docs-iframe") { message, _ ->
        val show = Json.decodeFromJsonElement<ShowDocsIframe>(message.payload!!)
        setDocsIframeContent(show.url)
        showDocsIframe()
        Handled.yes
    }

    handlers.put("hide-docs-iframe") { message, _ ->
        hideDocsIframe()
        Handled.yes
    }

    handlers.put("switch-to-docs-iframe") { message, _ ->
        val switch = Json.decodeFromJsonElement<SwitchToDocsIframe>(message.payload!!)
        switchToDocsIframe(switch.url)
        Handled.yes
    }

    handlers.put("switch-to-monaco-editor") { message, _ ->
        switchToMonaco()
        Handled.yes
    }

    val body = document.body ?: error("No body")
    body.append {
        style {
            unsafe {
                +styles.toString()
            }
        }
    }
    mainViewport = body.append {
        div(classes = "field-main-viewport") {
            style = CssBuilder().apply {
                width = 100.vw
                height = 100.vh
                backgroundColor = rgb(20, 20, 20, 1.0)
                padding = Padding(0.px)
                margin = Margin(0.px)
            }.toString()
        }
    }.first()

    initializeBackgroundGrid(mainViewport)

    // Connection spline layer: lives *inside* the viewport, painted above the WebGL
    // background but below the box canvas, so each box's backdrop-filter: blur(3px)
    // picks up the splines passing behind it. The endpoint dots stay on the front
    // layer (see ensureFrontConnectionLayer) so they still sit above the box borders.
    connectionSplineLayer = (document.createElementNS("http://www.w3.org/2000/svg", "svg") as SVGSVGElement).also {
        it.id = "connectionsplinelayer"
        it.classList.add("connection-spline-layer")
        it.setAttribute("height", "100%")
        it.setAttribute("width", "100%")
        mainViewport.appendChild(it)
    }

    mainCanvas = mainViewport.append {
        div(classes = "field-main-canvas") {
            style = CssBuilder().apply {
                width = 0.px
                height = 0.px
                overflow = Overflow.visible
                backgroundColor = Color.green
                padding = Padding(0.px)
                margin = Margin(0.px)
            }.toString()
        }

    }.first()

    registerOnOpen(
        Message("hello").payload(
            Hello(
                filename,
                username,
                sessionKey = sessionKey,
                scratch = scratch,
                shaderSelectionContext = shaderSelectionContext
            )
        )
    )
    initializeClient()

    // Fresh tab opened by a drag-drop "open as new document" action? Replay the
    // handed-off import once the socket is up (queued after the hello handshake).
    searchParams.get("pendingImport")?.takeIf { it.isNotBlank() }?.let { replayPendingImport(it) }

    overlaySVGLayer = document.getElementById("overlaysvglayer") as SVGSVGElement
    ensureAnnotationLayer()

    mainViewport.oncontextmenu = {
        it.preventDefault()
    }

    mainViewport.onclick = { event ->
        // now handled in gestures.kt
//        if (event.target == mainViewport) {
//            selected.copy().forEach {
//                deselectBox(it)
//            }
//        }
    }

    mainViewport.onmousedown = { event ->
        if (event.buttons.toInt() == 4) {
        } else {
            if (event.target == mainViewport) {
                canvasGestures.down(event)
            } else {

            }
        }
    }
    mainViewport.onmouseup = { event ->
        if (event.buttons.toInt() == 4) {
        } else {
            canvasGestures.up(event)
        }
    }
    mainViewport.onmousemove = { event ->
        if (event.buttons.toInt() === 4) {
            // this is actually a drag gesture
            cancelNavigationPanAnimation()
            event.preventDefault()
            event.stopPropagation()

            var mc = mainCanvas.unsafeCast<dynamic>()
            val x: Number = (mc.data_x ?: 0.0) + event.unsafeCast<dynamic>().movementX
            val y: Number = (mc.data_y ?: 0.0) + event.unsafeCast<dynamic>().movementY

            (mc as HTMLElement).style.transform = mainCanvasTransform(x.toDouble(), y.toDouble())
            mainTranslation = listOf(x.toDouble(), y.toDouble())
            drawingLayerViewportChanged(x.toDouble(), y.toDouble())

            println(" main translation now ${x} ${y} ${window.innerWidth} ${window.innerHeight}")

            mc.data_x = x
            mc.data_y = y
            syncInitialPlaceholderLayerTransform()

            updateBackgroundGrid(x.toDouble(), y.toDouble(), panning = true)

            send(
                Message("set-viewport").payload(
                    Set_rect(
                        uid = "root",
                        property = "viewport",
                        Rect(x.toDouble(), y.toDouble(), window.innerWidth.toDouble(), window.innerHeight.toDouble())
                    )
                )
            )

            canvasGestures.requestConnectionGroupRebuild(translationOnly = true)
            repositionSlideNoteOverlayIfVisible()
            if (spatialNavActive) {
                refreshSpatialNavTargets()
            }
        } else
            canvasGestures.move(event)
    }


    mainViewport.onwheel = onwheel@{ event ->

        // todo, should read "has outputArea_content as parent"
        val target = event.target as? HTMLElement
        if (target != null && target.asDynamic().closest(".outputArea") != null) {
            return@onwheel null
        }

        if (event.ctrlKey || event.metaKey) {
            // A trackpad pinch arrives as a ctrl+wheel; so does ctrl+scroll on a mouse.
            event.preventDefault()
            event.stopPropagation()
            setCanvasZoom(mainZoom * exp(-event.deltaY * 0.01), event.clientX.toDouble(), event.clientY.toDouble())
        } else if (event.target is SVGElement) {
        } else {

            cancelNavigationPanAnimation()
            event.preventDefault()
            event.stopPropagation()

            var mc = mainCanvas.unsafeCast<dynamic>()
            val x: Number = (mc.data_x ?: 0.0) - event.deltaX
            val y: Number = (mc.data_y ?: 0.0) - event.deltaY

            (mc as HTMLElement).style.transform = mainCanvasTransform(x.toDouble(), y.toDouble())
            mainTranslation = listOf(x.toDouble(), y.toDouble())
            drawingLayerViewportChanged(x.toDouble(), y.toDouble())

            println(" main translation now ${x} ${y} ${window.innerWidth} ${window.innerHeight}")

            mc.data_x = x
            mc.data_y = y
            syncInitialPlaceholderLayerTransform()

            updateBackgroundGrid(x.toDouble(), y.toDouble(), panning = true)

            send(
                Message("set-viewport").payload(
                    Set_rect(
                        uid = "root",
                        property = "viewport",
                        Rect(x.toDouble(), y.toDouble(), window.innerWidth.toDouble(), window.innerHeight.toDouble())
                    )
                )
            )

            canvasGestures.requestConnectionGroupRebuild(translationOnly = true)
            repositionSlideNoteOverlayIfVisible()
            if (spatialNavActive) {
                refreshSpatialNavTargets()
            }
        }

        null
    }


    mainCanvas.unsafeCast<dynamic>().data_x = 0
    mainCanvas.unsafeCast<dynamic>().data_y = 0
    syncInitialPlaceholderLayerTransform()
    updateBackgroundGrid(0.0, 0.0, panning = false)

    // File drop handler: metadata.edn imports boxes; transcript/macro YAML runs itself;
    // other files open a file-proxy marking menu.
    body.ondragover = { event ->
        event.preventDefault()
        null
    }

    body.ondrop = { event ->
        event.preventDefault()
        val dropEvent = event.asDynamic()
        val files = dropEvent.dataTransfer.files
        if (files.length > 0) {
            val file = files[0]
            val fileName = (file.name as? String)?.ifBlank { "dropped-file" } ?: "dropped-file"
            val lowerName = fileName.lowercase()
            val mimeType = file.type as? String
            val dropped = DroppedFileProxy(
                file = file,
                fileName = fileName,
                path = extractDroppedFilePath(file, dropEvent.dataTransfer),
                mimeType = mimeType
            )
            val plainMenu = { showDroppedFileProxyMenu(dropEvent, dropped) }
            val readText = { onText: (String) -> Unit ->
                val reader = js("new FileReader()")
                reader.onload = { loadEvent: dynamic -> onText(loadEvent.target.result as String) }
                reader.readAsText(file)
            }

            when (classifyDroppedFile(fileName, lowerName, mimeType)) {
                DropKind.METADATA_EDN -> readText { ednText ->
                    console.info("[import] metadata.edn file: $fileName")
                    showDropMenu(dropEvent) { x, y -> metadataDropOptions(dropped, ednText, x, y) }
                }
                DropKind.NOTEBOOK -> readText { notebookJson ->
                    console.info("[import] notebook file: $fileName")
                    showDropMenu(dropEvent) { x, y -> notebookDropOptions(dropped, notebookJson, x, y) }
                }
                DropKind.TRANSCRIPT_CANDIDATE -> readText { text ->
                    if (looksLikeOperationTranscript(text)) {
                        console.info("[transcript] dropped transcript: $fileName")
                        // Resolve project availability before building the menu so the
                        // "Add to project macros" entry only shows when it applies.
                        queryMacroScopesForDrop { projectName ->
                            showDropMenu(dropEvent) { _, _ -> transcriptDropOptions(dropped, text, projectName) }
                        }
                    } else {
                        // Text file, but not an operation transcript — treat it as a plain file.
                        plainMenu()
                    }
                }
                DropKind.PLAIN -> plainMenu()
            }
        }
        null
    }

    // TODO!
//    val outputAreaResizing = interact(".outputArea")
//
//    val outputResizeHandler = { event: dynamic ->
//        val target: dynamic = event.target
//
//        val parent_w = target.parentElement.parentElement.data_w
//        target.style.width = event.rect.width + "px"
//        target.style.height = event.rect.height + "px"
//
//        target.data_w = event.rect.width
//        target.data_h = event.rect.height
//    }
//
//    outputAreaResizing.resizable(
//        json("edges" to json("top" to false, "bottom" to true, "left" to false, "right" to true))
//    ).on("resizemove") { event: dynamic ->
//        outputResizeHandler(event)
//    }

    val moncaoResizing = interact(".monaco-editor-container")
    var currentEditorOverlayViewportKey = editorOverlayViewportKey()

    fun ensureAbsolutePosition(el: HTMLElement) {
        val dyn = el.asDynamic()
        if (dyn.data_initialized == true) return
        val isEditorOverlay = el.classList.contains("monaco-editor-container") ||
                el.classList.contains("docs-iframe-container")
        if (isEditorOverlay) {
            applyEditorOverlayLayout(el, editorOverlayLayoutFromElement(currentEditorOverlayViewportKey, el))
            return
        }

        val rect = el.getBoundingClientRect()
        val left = rect.left + window.scrollX
        val top = rect.top + window.scrollY
        el.style.left = "${left}px"
        el.style.top = "${top}px"
        el.style.right = "auto"
        el.style.bottom = "auto"
        el.style.position = "absolute"
        el.style.transform = "translate(0px, 0px)"
        dyn.data_x = left
        dyn.data_y = top
        dyn.data_w = rect.width
        dyn.data_h = rect.height
        dyn.data_right_margin = max(0.0, window.innerWidth.toDouble() - rect.right)
        dyn.data_initialized = true
    }

    fun updateRightMarginData(el: HTMLElement) {
        val rect = el.getBoundingClientRect()
        el.asDynamic().data_right_margin = max(0.0, window.innerWidth.toDouble() - rect.right)
    }

    fun mirrorOverlayGeometry(source: HTMLElement, target: HTMLElement) {
        ensureAbsolutePosition(target)
        val sourceDyn = source.asDynamic()
        val targetDyn = target.asDynamic()
        target.style.transform = "translate(0px, 0px)"
        target.style.left = source.style.left
        target.style.top = source.style.top
        target.style.width = source.style.width
        target.style.height = source.style.height
        target.style.right = "auto"
        target.style.bottom = "auto"
        targetDyn.data_x = sourceDyn.data_x
        targetDyn.data_y = sourceDyn.data_y
        targetDyn.data_w = sourceDyn.data_w
        targetDyn.data_h = sourceDyn.data_h
        targetDyn.data_right_margin = sourceDyn.data_right_margin
        targetDyn.data_initialized = true
    }

    fun rightAnchoredEditorOverlayLayout(viewportKey: String, el: HTMLElement): EditorOverlayLayoutRecord {
        val dyn = el.asDynamic()
        val rect = el.getBoundingClientRect()
        val inset = monacoViewportGutterPx
        val availableWidth = max(1.0, window.innerWidth.toDouble() - inset * 2.0)
        val availableHeight = max(1.0, window.innerHeight.toDouble() - inset * 2.0)
        val minWidth = min(monacoMinWidthPx, availableWidth)
        val minHeight = min(monacoMinHeightPx, availableHeight)
        val maxRightMargin = max(inset, window.innerWidth.toDouble() - inset - minWidth)
        val storedRightMargin = (dyn.data_right_margin as? Number)?.toDouble()
            ?: max(0.0, window.innerWidth.toDouble() - rect.right)
        val rightMargin = min(max(storedRightMargin, inset), maxRightMargin)
        val maxWidth = max(minWidth, window.innerWidth.toDouble() - rightMargin - inset)
        val currentWidth = (dyn.data_w as? Number)?.toDouble() ?: rect.width
        val currentHeight = (dyn.data_h as? Number)?.toDouble() ?: rect.height
        val width = min(max(currentWidth, minWidth), maxWidth)
        val height = min(max(currentHeight, minHeight), availableHeight)
        val x = window.scrollX + window.innerWidth.toDouble() - rightMargin - width
        val y = run {
            val currentY = (dyn.data_y as? Number)?.toDouble() ?: (rect.top + window.scrollY)
            val minY = window.scrollY + inset
            val maxY = max(minY, window.scrollY + window.innerHeight.toDouble() - inset - height)
            min(max(currentY, minY), maxY)
        }

        return EditorOverlayLayoutRecord(viewportKey, x, y, width, height)
    }

    fun anchorEditorOverlayToRightEdge(el: HTMLElement, viewportKey: String) {
        ensureAbsolutePosition(el)
        applyEditorOverlayLayout(el, rightAnchoredEditorOverlayLayout(viewportKey, el))
    }

    fun rememberEditorOverlayForCurrentViewport() {
        val primary = (document.querySelector(".monaco-editor-container") as? HTMLElement)
            ?: (document.querySelector(".docs-iframe-container") as? HTMLElement)
            ?: return
        ensureAbsolutePosition(primary)
        rememberEditorOverlayLayout(editorOverlayLayoutFromElement(currentEditorOverlayViewportKey, primary))
    }

    fun restoreOrClampEditorOverlayForViewport(viewportKey: String) {
        val monacoContainer = document.querySelector(".monaco-editor-container") as? HTMLElement
        val docsContainer = document.querySelector(".docs-iframe-container") as? HTMLElement
        val primary = monacoContainer ?: docsContainer

        if (primary != null) {
            ensureAbsolutePosition(primary)
            val stored = findEditorOverlayLayoutRecord(viewportKey)
            if (stored != null) {
                applyEditorOverlayLayout(primary, stored)
            } else {
                anchorEditorOverlayToRightEdge(primary, viewportKey)
            }
        }

        if (monacoContainer != null && docsContainer != null) {
            mirrorOverlayGeometry(monacoContainer, docsContainer)
        }

        layoutGlobalEditor()
    }

    fun handleEditorOverlayViewportResize() {
        val nextViewportKey = editorOverlayViewportKey()
        rememberEditorOverlayForCurrentViewport()
        currentEditorOverlayViewportKey = nextViewportKey
        restoreOrClampEditorOverlayForViewport(nextViewportKey)
    }

    restoreOrClampEditorOverlayForViewport(currentEditorOverlayViewportKey)

    monacoResizeHandler = { event: dynamic ->

        val target: dynamic = event.target
        val targetElement = target as HTMLElement
        ensureAbsolutePosition(targetElement)
        val x = ((target.data_x ?: 0.0) as Number).toDouble() + (event.deltaRect.left as Number).toDouble()
        val y = ((target.data_y ?: 0.0) as Number).toDouble() + (event.deltaRect.top as Number).toDouble()
        val w = (event.rect.width as Number).toDouble()
        val h = (event.rect.height as Number).toDouble()

        applyEditorOverlayLayout(
            targetElement,
            EditorOverlayLayoutRecord(currentEditorOverlayViewportKey, x, y, w, h)
        )

        // Keep docs container in sync when Monaco is resized
        val docsContainer = document.querySelector(".docs-iframe-container") as? HTMLElement
        if (docsContainer != null) {
            mirrorOverlayGeometry(targetElement, docsContainer)
        }
        layoutGlobalEditor()
    }

    moncaoResizing.resizable(
        json("edges" to json("top" to true, "bottom" to true, "left" to true, "right" to true))
    ).on("resizemove") { event: dynamic ->
        monacoResizeHandler(event)
    }.on("resizeend") { _: dynamic ->
        rememberEditorOverlayForCurrentViewport()
    }

    // Capture from pointer-down, before a fast first move can cross into the iframe and steal the gesture.
    val docsResizeContainer = document.querySelector(".docs-iframe-container") as? HTMLElement
    var docsResizePointerId: Int? = null
    val clearDocsResizeShield = {
        docsResizeContainer?.classList?.remove("is-resizing")
        docsResizePointerId?.let { pointerId ->
            try {
                docsResizeContainer?.asDynamic()?.releasePointerCapture?.invoke(pointerId)
            } catch (_: dynamic) {
            }
        }
        docsResizePointerId = null
    }
    docsResizeContainer?.addEventListener("pointerdown", { rawEvent ->
        val event = rawEvent.asDynamic()
        if (event.target === docsResizeContainer) {
            docsResizePointerId = (event.pointerId as Number).toInt()
            docsResizeContainer.classList.add("is-resizing")
            try {
                docsResizeContainer.asDynamic().setPointerCapture(event.pointerId)
            } catch (_: dynamic) {
            }
        }
    }, true)
    window.addEventListener("pointerup", { clearDocsResizeShield() }, true)
    window.addEventListener("pointercancel", { clearDocsResizeShield() }, true)
    window.addEventListener("blur", { clearDocsResizeShield() })

    // Allow docs iframe container to be resized like Monaco, and propagate new size back to Monaco/editor
    val docsResizing = interact(".docs-iframe-container")
    docsResizing.resizable(
        json("edges" to json("top" to true, "bottom" to true, "left" to true, "right" to true))
    ).on("resizestart") { event: dynamic ->
        (event.target as? HTMLElement)?.classList?.add("is-resizing")
    }.on("resizemove") { event: dynamic ->
        val target: dynamic = event.target
        val targetElement = target as HTMLElement
        ensureAbsolutePosition(targetElement)
        val x = ((target.data_x ?: 0.0) as Number).toDouble() + (event.deltaRect.left as Number).toDouble()
        val y = ((target.data_y ?: 0.0) as Number).toDouble() + (event.deltaRect.top as Number).toDouble()
        val w = (event.rect.width as Number).toDouble()
        val h = (event.rect.height as Number).toDouble()

        val appliedRect = applyEditorOverlayLayout(
            targetElement,
            EditorOverlayLayoutRecord(currentEditorOverlayViewportKey, x, y, w, h)
        )

        // Mirror size back to Monaco container
        val monacoContainer = document.querySelector(".monaco-editor-container") as? HTMLElement
        if (monacoContainer != null) {
            mirrorOverlayGeometry(targetElement, monacoContainer)
        }

        // Notify Monaco editor about size change
        val editor = window.asDynamic().globalEditor
        if (editor != null && editor != undefined && editor.layout != undefined) {
            val dims = js("{}")
            dims.width = appliedRect.w
            dims.height = appliedRect.h
            editor.layout(dims)
        }
    }.on("resizeend") { _: dynamic ->
        clearDocsResizeShield()
        rememberEditorOverlayForCurrentViewport()
    }

    val historyResizing = interact(".history-iframe-container")
    historyResizing.resizable(
        json("edges" to json("top" to true, "bottom" to true, "left" to true, "right" to true))
    ).on("resizemove") { event: dynamic ->
        val target: dynamic = event.target
        val targetElement = target as HTMLElement
        ensureAbsolutePosition(targetElement)

        val minWidth = 760.0
        val minHeight = 220.0
        val viewportGutter = 10.0
        val maxWidth = max(minWidth, window.innerWidth.toDouble() - viewportGutter * 2)
        val maxHeight = max(minHeight, window.innerHeight.toDouble() - viewportGutter * 2)
        val width = min(max((event.rect.width as Number).toDouble(), minWidth), maxWidth)
        val height = min(max((event.rect.height as Number).toDouble(), minHeight), maxHeight)
        val x = min(
            max(
                ((target.data_x ?: 0.0) as Number).toDouble() + (event.deltaRect.left as Number).toDouble(),
                viewportGutter
            ),
            max(viewportGutter, window.innerWidth.toDouble() - width - viewportGutter)
        )
        val y = min(
            max(
                ((target.data_y ?: 0.0) as Number).toDouble() + (event.deltaRect.top as Number).toDouble(),
                viewportGutter
            ),
            max(viewportGutter, window.innerHeight.toDouble() - height - viewportGutter)
        )

        targetElement.style.transform = "translate(0px, 0px)"
        targetElement.style.left = "${x}px"
        targetElement.style.top = "${y}px"
        targetElement.style.right = "auto"
        targetElement.style.bottom = "auto"
        targetElement.style.width = "${width}px"
        targetElement.style.height = "${height}px"

        target.data_x = x
        target.data_y = y
        target.data_w = width
        target.data_h = height
        updateRightMarginData(targetElement)
    }

    val interactable = interact(".box")

    moveHandler = move@{ event: dynamic ->
        val target: dynamic = event.target
        val uid = target.uid as? String ?: return@move null

        // Client deltas are screen pixels; frames are world units.
        val tdx = ((event.clientX - originatingMouseDown.clientX) as Number).toDouble() / mainZoom
        val tdy = ((event.clientY - originatingMouseDown.clientY) as Number).toDouble() / mainZoom
        val odx = (target.originalFramePosition as List<Number>)[0].toDouble() + tdx.toDouble()
        val ody = (target.originalFramePosition as List<Number>)[1].toDouble() + tdy.toDouble()

        var t = window.getComputedStyle(event.target).borderWidth.split("px")[0].toDouble() * 0.0

        // Apply snapping if Caps Lock is enabled
        val targetElement = target as HTMLElement
        val width =
            ((target.data_w as? Number)?.toDouble() ?: (event.rect?.width as? Number)?.toDouble() ?: 0.0) - t * 2
        val height =
            ((target.data_h as? Number)?.toDouble() ?: (event.rect?.height as? Number)?.toDouble() ?: 0.0) - t * 2

        val (snappedX, snappedY) = boxSnappingSystem.applySnapping(
            targetElement,
            odx,
            ody,
            width,
            height
        )

        Set_rect(
            uid = uid,
            property = "frame",
            value = Rect(snappedX, snappedY, width, height)
        )
    }

    interactable.on("up") { event ->
        val pendingBox = pendingShiftToggleBox
        if (pendingBox != null) {
            val upX = (event.clientX as? Number)?.toDouble() ?: pendingShiftToggleDownX
            val upY = (event.clientY as? Number)?.toDouble() ?: pendingShiftToggleDownY
            val moved = abs(upX - pendingShiftToggleDownX) > shiftToggleClickSlopPx ||
                    abs(upY - pendingShiftToggleDownY) > shiftToggleClickSlopPx
            if (!pendingShiftToggleHadDrag && !moved && selected.contains(pendingBox)) {
                deselectBox(pendingBox)
            }
        }
        pendingShiftToggleBox = null
        pendingShiftToggleHadDrag = false
    }

    interactable.on("dragend") { event ->
        provideMissingMouseup(event)
        // Clear snap guides when drag ends
        boxSnappingSystem.clearSnapGuides()
    }

    interactable.on("dragstart") { event ->
        if (pendingShiftToggleBox != null) {
            pendingShiftToggleHadDrag = true
        }
        selected.copy().forEach {
            it.asDynamic().originalMouseLocation = listOf(event.clientX, event.clientY)
            it.asDynamic().originalFramePosition = listOf(it.asDynamic().data_x, it.asDynamic().data_y)
        }
    }

    interactable.on("down") { event ->
        pendingShiftToggleBox = null
        pendingShiftToggleHadDrag = false


        val originalTarget = event.target as? HTMLElement
        val insideOutputArea = originalTarget?.closest(".outputArea") != null

        // If the click originates from inside an embedded Monaco editor,
        // don't re-select the box — that would disrupt the main editor.
        val insideEmbeddedEditor = originalTarget?.closest(".monaco-editor") != null && insideOutputArea
        if (insideEmbeddedEditor) return@on

        // If the click originates from a disclosure header (output area UI),
        // don't intercept — let the disclosure handle its own alt-click removal.
        val insideDisclosureHeader = originalTarget?.closest(".disclosure-header") != null
        if (insideOutputArea && insideDisclosureHeader) return@on

        val target: dynamic = reorient(event.target)
        if (event.altKey) {
            val uid = target.uid as? String ?: return@on
            val stamp = (event.timeStamp as? Double) ?: js("Date.now()") as Double
            val continueRequested = (event.shiftKey as? Boolean) == true

            // Shift+Alt+Click: custom marking menu
            if (continueRequested) {
                if (uid == lastAltDownUid && kotlin.math.abs(stamp - lastAltDownStamp) < 30.0) {
                    return@on
                }
                lastAltDownUid = uid
                lastAltDownStamp = stamp
                if (!target.classList.contains("box")) return@on
                requestCustomMarkingMenu(uid, event.x as Double, event.y as Double, event)
                return@on
            }

            if (uid == lastAltDownUid && kotlin.math.abs(stamp - lastAltDownStamp) < 30.0) {
                return@on
            }
            lastAltDownUid = uid
            lastAltDownStamp = stamp

            // Allow nested boxes to be launched even when they are inside an output area
            if (insideOutputArea && target?.classList?.contains("box") != true) {
                return@on
            }
            // Ignore clicks on nested container backgrounds (non-box elements inside nested output)
            if (!target.classList.contains("box")) return@on

            var already = false
            if (target.classList.contains("running")) {
                notify(" <i>${target.name}</i> is already running", "launch")
                already = true
            } else {
                if (continueRequested) {
                    notify("continuing <i>${target.name}</i>", "launch")
                    continueAll(target.uid, target["code"])
                } else {
                    notify("launched <i>${target.name}</i>", "launch")
                    launchAll(target.uid, target["code"])
                }
                already = false
            }

            val menus = mutableListOf(
                json("text" to "stop", "callback" to {
                    notify("<i>${target.name}</i> stopped", "launch")
                    stopBox(target.uid)
                }, "radius" to 1.0, "angle" to 0.0)
            )

            if (!already) {
                menus.add(json("text" to "continue", "callback" to {
                    notify("continuing <i>${target.name}</i>", "launch")
                    continueAll(target.uid, target["code"])
                }, "radius" to 1.0, "angle" to 180.0))
            }

            // needs to be a default for stopBox to also be called when the menu is cancelled

            goMarkingMenu(event.x, event.y, menus.toTypedArray().asJsObject(), event, {
                // Cancelled marking menu: do not stop by default; explicit stop is via menu item.
            });

            originatingMouseDown = event

//            event.preventDefault();
//            event.stopPropagation();

            selected.copy().forEach {
                it.asDynamic().originalMouseLocation = listOf(event.clientX, event.clientY)
                it.asDynamic().originalFramePosition = listOf(it.asDynamic().data_x, it.asDynamic().data_y)
            }
        } else {

            originatingMouseDown = event

            selected.copy().forEach {
                it.asDynamic().originalMouseLocation = listOf(event.clientX, event.clientY)
                it.asDynamic().originalFramePosition = listOf(it.asDynamic().data_x, it.asDynamic().data_y)
            }

            if (event.metaKey) {
                // no-op
            } else if (event.shiftKey) {
                val boxEl = target as? HTMLElement
                if (boxEl != null) {
                    val uid = boxEl.unsafeCast<dynamic>().uid as? String
                    val stamp = (event.timeStamp as? Double) ?: js("Date.now()") as Double
                    if (uid != null && uid == lastShiftDownUid && kotlin.math.abs(stamp - lastShiftDownStamp) < 50.0) {
                        return@on
                    }
                    lastShiftDownUid = uid
                    lastShiftDownStamp = stamp

                    if (selected.contains(boxEl)) {
                        pendingShiftToggleBox = boxEl
                        pendingShiftToggleDownX = (event.clientX as? Number)?.toDouble() ?: 0.0
                        pendingShiftToggleDownY = (event.clientY as? Number)?.toDouble() ?: 0.0
                    } else {
                        selectBox(boxEl)
                    }
                }
            } else {
                selected.copy().forEach {
                    deselectBox(it)
                }
                selectBox(target)
            }
        }
    }

    interactable.draggable(json("onmove" to { event: dynamic ->
        if (event.altKey) {
        } else if (originatingMouseDown.altKey) {
        } else {
            bumpBackgroundVisibility()
            val pending = mutableListOf<Set_rect>()
            selected.forEach {
                event.target = reorient(it)
                if (event.target) {
                    moveHandler(event)?.let { next ->
                        applyFrameToBox(event.target as HTMLElement, next.value)
                        pending.add(next)
                    }
                }
            }
            if (pending.isNotEmpty()) {
                send(Message("set-rects").payload(Set_rects(pending)))
                canvasGestures.requestConnectionGroupRebuild()
            }
        }
    }))



    resizeHandler = { event: dynamic ->

        val target: dynamic = event.target

        // interact.js measures the scaled element in screen pixels; frames are world units.
        var x: Double = (target.data_x ?: 0.0) + (event.deltaRect.left as Double) / mainZoom
        var y: Double = (target.data_y ?: 0.0) + (event.deltaRect.top as Double) / mainZoom

        var w = (event.rect.width as Double) / mainZoom
        var h = (event.rect.height as Double) / mainZoom

        if (target.lock_w) w = target.data_w ?: 100.0
        if (target.lock_h) h = target.data_h ?: 100.0

        if (target.lock_x) x = target.data_x ?: 0.0
        if (target.lock_y) y = target.data_y ?: 0.0

        if (target.lock_w && !target.lock_x) {
            x += (event.deltaRect.right as Double) / mainZoom
        }
        if (target.lock_h && !target.lock_y) {
            y += (event.deltaRect.bottom as Double) / mainZoom
            // why was this here?
//            if (!target.lock_x)
//                x += event.delta.x as Double
        }

//        (target as HTMLElement).style.transform = "translate(${x}px, ${y}px)" // round-trip to server?
        // renable this for serverless

        // Apply snapping if Caps Lock is enabled
        val targetElement = target as HTMLElement
        val snappedRect = boxSnappingSystem.applySnappingForResize(
            targetElement,
            x,
            y,
            w,
            h
        )

        target.data_x = snappedRect.x
        target.data_y = snappedRect.y
        target.data_w = snappedRect.w
        target.data_h = snappedRect.h

//        target.style.width = w + "px"
//        target.style.height = h + "px"

        val uid = target.uid
        send(
            Message("set-rect").payload(
                Set_rect(
                    uid = uid, property = "frame", Rect(target.data_x, target.data_y, target.data_w, target.data_h)
                )
            )
        )

    }

    interactable.resizable(
        json(
            "edges" to json("top" to true, "bottom" to true, "left" to true, "right" to true),
            "invert" to "reposition"
        )
    ).on("resizemove") { event: dynamic ->

        if (resizeStartBoxTarget(event._interaction.downEvent.target) == null) {
            return@on
        }

        if (originatingMouseDown.altKey) {
        } else if (event.altKey) {
        } else {
            selected.forEach {
                event.target = reorient(it)
                if (event.target) resizeHandler(event)
            }
        }
    }.on("resizeend") { event: dynamic ->
        // Clear snap guides when resize ends
        boxSnappingSystem.clearSnapGuides()
    }

    interact(".doubleClickToEdit").on("doubletap") { event ->
        val t = event.target.unsafeCast<HTMLElement>()
        val box = t.parentElement!!.parentElement.unsafeCast<HTMLElement>()

        if (t.contentEditable != "true") {
            t.contentEditable = "true"

            fun fireChange() {
                box.unsafeCast<dynamic>().name = t.textContent
                val uid = box.unsafeCast<dynamic>().uid as String
                val wasAi4AutoNamed = box.asDynamic().ai4_auto_name == true
                send(
                    Message("set-string").payload(
                        Set_string(
                            uid = uid, property = "name", value = t.textContent ?: ""
                        )
                    )
                )
                if (wasAi4AutoNamed) {
                    applyAi4AutoNameStyle(box, false)
                }
            }

            t.onblur = {
                t.contentEditable = "false"
                fireChange()
                null
            }

            setTimeout({
                t.contentEditable = "true"
                val range = document.createRange();
                val selection = (window.unsafeCast<dynamic>()).getSelection()
                range.selectNodeContents(t);
                selection?.removeAllRanges();
                selection?.addRange(range);
                t.focus();
            }, 0);

            setTimeout({
                t.contentEditable = "true"
                val range = document.createRange();
                val selection = (window.unsafeCast<dynamic>()).getSelection()
                range.selectNodeContents(t);
                selection?.removeAllRanges();
                selection?.addRange(range);
                t.focus();
            }, 300);

            t.onkeydown = { event ->
                if (event.keyCode == 13) {
                    event.preventDefault()
                    t.blur()
                    fireChange()
                }
            }
        }
    }

    later({
        initMenuSupport()
        registerBuiltinCommands()
        initializeBookmarkSystem()
        initializeDrawingLayer()
        registerCanvasZoomActions()
        initializeDocumentSearch()
        hideMonaco()

        later({
            LoadingTracker.start()
            (document.getElementById("overlaycanvas") as HTMLElement).style.unsafeCast<dynamic>().backdropFilter = ""
        }, 100)

    }, 0)
    forever(::serviceEvents, 30)

    onSelectedChange.put("server") {
        val ids = selected.map { it.id }
        true
    }

    send(
        Message("set-viewport").payload(
            Set_rect(
                uid = "root",
                property = "viewport",
                Rect(0.0, 0.0, window.innerWidth.toDouble(), window.innerHeight.toDouble())
            )
        )
    )

    registerMouseBug()

    window.addEventListener("resize", {
        handleEditorOverlayViewportResize()
        canvasGestures.rebuildConnectionGroup()
        repositionSlideNoteOverlayIfVisible()
        if (spatialNavActive) {
            refreshSpatialNavTargets()
        }
    })
    window.addEventListener("scroll", {
        repositionSlideNoteOverlayIfVisible()
        if (spatialNavActive) {
            refreshSpatialNavTargets()
        }
    })
}


// Exported to allow documentation iframe API to stop boxes programmatically
// Uses ExperimentalJsExport to make this Kotlin function directly callable from JavaScript
@OptIn(ExperimentalJsExport::class)
@JsExport
fun stopBox(uid: String) {
    send(Message("stop-box").payload(Stop_box(uid = uid)))

}

var retired = mutableSetOf<String>()

@OptIn(ExperimentalUuidApi::class)
fun TagConsumer<HTMLElement>.createBox(
    name: String, frame: Rect, uid: String = Uuid.random().toString(), sendInitialSync: Boolean = true
): HTMLElement {
    lateinit var oa: HTMLElement
    val d1 = div(classes = "box offline") {
        style = CssBuilder().apply {
            position = Position.absolute
            color = rgb(255, 255, 255, 0.8)
            borderRadius = 5.px
            backdropFilter = "blur(3px) saturate(2.5) contrast(1.1) brightness(0.9) hue-rotate(-38deg)"
            border = Border(1.px, BorderStyle.solid, rgb(255, 255, 255, 0.0))
            backgroundColor = rgb(50, 50, 50, 0.5)
            display = Display.flex
            flexDirection = FlexDirection.row
            flexWrap = FlexWrap.wrap
            justifyContent = JustifyContent.center
            alignItems = Align.center
            width = frame.w.px
            height = frame.h.px
            visible = frame.w > 0 || frame.h > 0
        }.toString()
        div(classes = "box-title-stack") {
            span(classes = "doubleClickToEdit") {
                +name
            }
        }
        div(classes = "statusArea") {
            div(classes = "tooltiptext") {
                +""
            }
        }
        div(classes = "outputArea") {
            style = CssBuilder().apply {
//                padding = Padding(5.px)
                position = Position.absolute
                left = 0.px;
                top = LinearDimension("calc( 100% + 5px )");
//                right = 0.px;
//                borderRadius = 5.px;
//                background = "rgba(0,0,0,0.5)";
                userSelect = UserSelect.none;
                overflow = Overflow.visible;
                visibility = Visibility.hidden;
                height = 0.px;
                width = 0.px;
            }.toString()
            div(classes = "disclosure-group") {
            }
        }
    }
    d1.style.transform = "translate(${frame.x}px, ${frame.y}px)"
    d1.asDynamic().offline = true

    val d1d = d1.unsafeCast<dynamic>()
    d1d.data_x = frame.x
    d1d.data_y = frame.y
    d1d.data_w = frame.w
    d1d.data_h = frame.h
    d1d.uid = uid
    d1d.name = name
    d1d.ai4_auto_name = false
    d1d.server_visible = true

    oa = d1.getElementsByClassName("outputArea").asList()[0] as HTMLElement
    applyBoxVisibilityImmediate(d1)
    if (!sendInitialSync) {
        animateOfflineBoxArrival(d1)
    }

    if (sendInitialSync) {
        send(
            Message("set-string").payload(
                Set_string(
                    uid = uid, property = "name", value = name
                )
            )
        )

        send(
            Message("set-rect").payload(
                Set_rect(
                    uid = uid, property = "frame", value = frame
                )
            )
        )
    }
    updateEmptyDocumentWelcome()
    return d1
}

fun later(f: () -> Unit, delay_ms: Int = 100) {
    setTimeout({
        f()
    }, delay_ms)
}

fun forever(f: () -> Unit, delay_ms: Int) {
    setTimeout({
        forever(f, delay_ms)
        f()
    }, delay_ms)
}

fun reorient(it: dynamic): dynamic {
    if (it == null) return null
    if (it.unsafeCast<HTMLElement>().hasClass("box")) return it
    return it.unsafeCast<HTMLElement>().parentElement?.let { parent ->
        reorient(parent)
    }
}

private fun resizeStartBoxTarget(it: dynamic): HTMLElement? {
    val target = it as? HTMLElement ?: return null
    val box = target.closest(".box") as? HTMLElement ?: return null
    val outputArea = target.closest(".outputArea") as? HTMLElement
    if (outputArea != null && !outputArea.contains(box)) return null
    return box
}


fun <E> MutableSet<E>.copy() = HashSet<E>(this)
