@file:OptIn(ExperimentalJsExport::class, ExperimentalUuidApi::class)

package afield

import CanvasGestures
import clipRay
import doubleTap
import handleCustomizationRequest
import handleEvalRequest
import handleOutputAreaRequest
import handleOutputAreaControl
import kotlinext.js.asJsObject
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.css.*
import kotlinx.dom.hasClass
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.encodeToString
import org.w3c.dom.*
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.svg.SVGElement
import org.w3c.dom.svg.SVGSVGElement
import org.w3c.dom.url.URLSearchParams
import registerDoubleTap
import web.cssom.important
import web.timers.setTimeout
import kotlin.js.json
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

external fun interact(selector: String): dynamic
external fun interact(selector: HTMLElement): dynamic

external fun notify(text: String, category: String)

external fun setBoxRunning(uid: String, running: Boolean, watermark: Int, strand: String)

external fun showMonaco(): dynamic
external fun hideMonaco(): dynamic
external fun showDocsIframe(): dynamic
external fun hideDocsIframe(): dynamic
external fun setDocsIframeContent(url: String?): dynamic
external fun switchToMonaco(): dynamic
external fun switchToDocsIframe(url: String?): dynamic
external fun switchToDefaultDocs(): dynamic
external fun initMenuSupport(): dynamic


external fun setDecorationsForEditor(uid: String, property: String, box: HTMLElement, decorSpec : dynamic)

external fun setActions(actions: dynamic): dynamic

external fun goMarkingMenu(x: Double, y: Double, menus: dynamic, event: dynamic, cancelCallback: dynamic): dynamic
external fun provideMissingMouseup(event: dynamic): dynamic

external object Provisional {
    fun clear()
    fun set(uid: String, property: String, contents: String)
}

lateinit var mainViewport: HTMLElement
lateinit var mainCanvas: HTMLElement
lateinit var overlaySVGLayer: SVGSVGElement
external val monaco: dynamic

external val allActions: dynamic

external var originatingMouseDown: MouseEvent

lateinit var moveHandler: (dynamic) -> Set_rect?
lateinit var resizeHandler: (dynamic) -> Unit
lateinit var monacoResizeHandler: (dynamic) -> Unit
var mainTranslation = listOf(0.0, 0.0)

private const val dotSpacingSmall = 25.0
private const val dotSpacingLarge = 100.0
private const val dotAlphaSmallIdle = 0.02
private const val dotAlphaLargeIdle = 0.04
private const val dotAlphaSmallActive = 0.16
private const val dotAlphaLargeActive = 0.24
private const val axisAlphaIdle = 0.0
private const val axisAlphaActive = 0.12

private var dotAlphaSmall = dotAlphaSmallIdle
private var dotAlphaLarge = dotAlphaLargeIdle
private var dotAlphaSmallTarget = dotAlphaSmallIdle
private var dotAlphaLargeTarget = dotAlphaLargeIdle
private var axisAlpha = axisAlphaIdle
private var axisAlphaTarget = axisAlphaIdle
private var backgroundFadeTimeoutHandle: Int? = null
private var backgroundLerpHandle: Int? = null
private var navigationPanAnimationHandle: Int? = null
private var navigationPanAnimationGeneration = 0

@JsExport
@JsName("launchNumber")
var launchNumber = 0

external fun launchAll(uid: String, code: String)

@JsExport
@JsName("canvasGestures")
var canvasGestures = CanvasGestures(launcher = {
    it.forEach { launchAll(it.unsafeCast<dynamic>().uid as String, it.unsafeCast<dynamic>().code as String) }
}).also {
    val w = window.asDynamic()
    w.canvasGestures = it
    w.rebuildConnectionGroup = { translationOnly: dynamic ->
        it.rebuildConnectionGroup((translationOnly as? Boolean) ?: false)
    }
}

var selected = mutableSetOf<HTMLElement>()
var selectedList = eval("[]")
var selectedProperty = "code"

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
    if (currentAnnotations.isNotEmpty()) {
        redrawSlideAnnotations()
        scheduleSlideAnnotationRedraw(10)
    }
    if (spatialNavActive) {
        refreshSpatialNavTargets()
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

private fun domRectToRect(r: dynamic): Rect {
    return Rect(
        (r.left as Number).toDouble(),
        (r.top as Number).toDouble(),
        (r.width as Number).toDouble(),
        (r.height as Number).toDouble()
    )
}

private val Rect.right: Double get() = x + w
private val Rect.bottom: Double get() = y + h

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
    val disclosureRects = disclosureElements.map { domRectToRect(it.getBoundingClientRect()) }
    if (disclosureRects.size > 1) {
        return disclosureRects
    }
    val disclosureTitleRect = disclosureElements.firstOrNull()?.let(::disclosureTitleClientRect)

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

    if (disclosureRects.isNotEmpty()) {
        return disclosureRects
    }
    return listOf(domRectToRect(outputArea.getBoundingClientRect()))
}

private fun visibleMonacoContainer(): HTMLElement? {
    val container = document.querySelector(".monaco-editor-container") as? HTMLElement ?: return null
    return if (isOverlayVisible(container)) container else null
}

private fun currentViewportRect() = Rect(
    window.scrollX.toDouble(),
    window.scrollY.toDouble(),
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
        .translate(window.scrollX.toDouble(), window.scrollY.toDouble())
    return clipForPageRect(pageRect)
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
        max(domBottom, headerHeight + contentHeight) + 20.0
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
    ).expand(max(0.0, padding)).translate(window.scrollX.toDouble(), window.scrollY.toDouble())
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
                    max(
                        rect.height,
                        (element.scrollHeight as? Number)?.toDouble() ?: 0.0
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

private fun fitBoxWidthToOutputsForScreenshot(box: HTMLElement, outputArea: HTMLElement) {
    val currentWidth = box.getBoundingClientRect().width
    val maxContentWidth = outputArea.querySelectorAll(".disclosure-content").asList()
        .mapNotNull { it as? HTMLElement }
        .map { content ->
            val scrollW = (content.scrollWidth as? Number)?.toDouble() ?: 0.0
            val contentRect = content.getBoundingClientRect()
            val childW = content.children.asList()
                .mapNotNull { child ->
                    val element = child as? Element ?: return@mapNotNull null
                    if (!isOverlayVisible(element)) return@mapNotNull null
                    val edgeW = element.getBoundingClientRect().right - contentRect.left
                    val html = element as? HTMLElement
                    max(
                        edgeW,
                        max(
                            html?.getBoundingClientRect()?.width ?: 0.0,
                            (html?.scrollWidth as? Number)?.toDouble() ?: 0.0
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

    outputArea.querySelectorAll(".disclosure").asList()
        .mapNotNull { it as? HTMLElement }
        .forEach { disclosure ->
            disclosure.style.width = "${disclosureWidth}px"
            disclosure.style.maxWidth = "none"
        }
    outputArea.querySelectorAll(".disclosure-content").asList()
        .mapNotNull { it as? HTMLElement }
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

    fun visibleDisclosureRects(): List<Rect> =
        outputArea.querySelectorAll(".disclosure").asList()
            .mapNotNull { it as? HTMLElement }
            .filter { isOverlayVisible(it) }
            .map { domRectToRect(it.getBoundingClientRect()) }
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

private fun prepareMonacoForScreenshotLayout() {
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

    val language = if (property == "code") {
        (box.asDynamic().code_language as? String) ?: monacoFindLanguageForPropertyName(property)
    } else {
        monacoFindLanguageForPropertyName(property)
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

private fun switchSelectionToProperty(uid: String, property: String, syncEditor: Boolean = false): Boolean {
    val box = findBoxElementByUid(uid) ?: return false
    selected.toList().forEach {
        if (it != box) {
            deselectBox(it)
        }
    }
    selectedProperty = property
    window.unsafeCast<dynamic>().selectedProperty = selectedProperty
    if (!selected.contains(box)) {
        selectBox(box)
    } else {
        window.unsafeCast<dynamic>().selectedList = selectedList
        selectionContextChanged()
    }
    if (!syncEditor) return true
    runCatching { switchToMonaco() }
    prepareMonacoForScreenshotLayout()
    layoutGlobalEditor()
    return window.asDynamic().globalEditor != null
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
        (lines.textContent as? String)?.replace('\u00A0', ' ')
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
        (lines.textContent as? String)?.replace('\u00A0', ' ')
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
    syncServer: Boolean = true
) {
    val mc = mainCanvas.unsafeCast<dynamic>()
    (mc as HTMLElement).style.transform = "translate(${nextX}px, ${nextY}px)"
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

    canvasGestures.rebuildConnectionGroup(translationOnly = true)
    repositionSlideNoteOverlayIfVisible()
    if (refreshSpatial && spatialNavActive) {
        refreshSpatialNavTargets()
    }
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

private fun ensureBoxVisibleForNavigation(target: HTMLElement) {
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

private fun prepareBoxScreenshot(req: BoxScreenshotRequest): BoxScreenshotResponse {
    if (window.asDynamic().fieldClientOpen != true || window.asDynamic().fieldBackdoorDisconnectedOverlayActive == true) {
        return BoxScreenshotResponse(uid = req.uid, success = false, message = "Field page is not connected")
    }
    val box = findBoxElementByUid(req.uid)
        ?: return BoxScreenshotResponse(uid = req.uid, success = false, message = "box ${req.uid} not found")

    hideNotificationsForScreenshot()
    if (req.hideMonaco) {
        clearSelectionForScreenshot()
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

    val padded = targetRect.expand(req.padding)
    val clip = padded.translate(window.scrollX.toDouble(), window.scrollY.toDouble())
    val viewport = Rect(
        window.scrollX.toDouble(),
        window.scrollY.toDouble(),
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
        return WindowScreenshotResponse(uid = req.uid, success = false, message = "editorOnly requires a target box and showEditor")
    }

    selectedProperty = req.property
    window.unsafeCast<dynamic>().selectedProperty = selectedProperty
    hideNotificationsForScreenshot()

    if (req.showEditor && box != null) {
        if (!switchSelectionToProperty(req.uid!!, req.property, syncEditor = true)) {
            return WindowScreenshotResponse(uid = req.uid, success = false, message = "editor not synced yet")
        }
    } else {
        clearSelectionForScreenshot()
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
        val clip = clipForRenderedEditorContent(req.padding)
            ?: return WindowScreenshotResponse(uid = req.uid, success = false, message = "invalid editor clip")
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
    openOutputs = dynamicBoolean(raw?.openOutputs, false)
)

private fun rawToWindowScreenshotRequest(raw: dynamic) = WindowScreenshotRequest(
    uid = dynamicString(raw?.uid),
    property = dynamicString(raw?.property) ?: "code",
    panIntoView = dynamicBoolean(raw?.panIntoView, true),
    showEditor = dynamicBoolean(raw?.showEditor, false),
    openOutputs = dynamicBoolean(raw?.openOutputs, false),
    editorOnly = dynamicBoolean(raw?.editorOnly, false),
    padding = dynamicDouble(raw?.padding, 12.0)
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
        null
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
}

private fun stepBackgroundLerp(): Boolean {
    val deltaSmall = dotAlphaSmallTarget - dotAlphaSmall
    val deltaLarge = dotAlphaLargeTarget - dotAlphaLarge
    val deltaAxis = axisAlphaTarget - axisAlpha
    val changed = abs(deltaSmall) > 0.0005 || abs(deltaLarge) > 0.0005 || abs(deltaAxis) > 0.0005

    dotAlphaSmall += deltaSmall * 0.18
    dotAlphaLarge += deltaLarge * 0.18
    axisAlpha += deltaAxis * 0.18

    applyBackgroundAlphas()
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
    scheduleBackgroundLerp()

    backgroundFadeTimeoutHandle?.let { window.clearTimeout(it) }
    backgroundFadeTimeoutHandle = window.setTimeout({
        dotAlphaSmallTarget = dotAlphaSmallIdle
        dotAlphaLargeTarget = dotAlphaLargeIdle
        axisAlphaTarget = axisAlphaIdle
        scheduleBackgroundLerp()
    }, 420)
}

fun updateBackgroundGrid(translationX: Double, translationY: Double, panning: Boolean = false) {
    if (!::mainViewport.isInitialized) return

    val posSmall = "${normalizeOffset(translationX, dotSpacingSmall) - (dotSpacingSmall / 2) + 0.5}px ${normalizeOffset(translationY, dotSpacingSmall) - (dotSpacingSmall / 2) + 0.5}px"
    val posLarge = "${normalizeOffset(translationX, dotSpacingLarge) - (dotSpacingLarge / 2) + 0.5}px ${normalizeOffset(translationY, dotSpacingLarge) - (dotSpacingLarge / 2) + 0.5}px"
    val posAxisX = "${translationX}px 0px"
    val posAxisY = "0px ${translationY}px"

    mainViewport.style.backgroundPosition = "$posAxisX, $posAxisY, $posSmall, $posLarge"

    if (panning) {
        bumpBackgroundVisibility()
    }
}

fun initializeBackgroundGrid(host: HTMLElement) {
    mainViewport = host
    host.style.backgroundColor = "rgb(20, 20, 20)"
                        host.style.backgroundImage = "linear-gradient(90deg, rgba(255,255,255,var(--axis-alpha)) 1px, rgba(255,255,255,0) 1px), " +
                            "linear-gradient(0deg, rgba(255,255,255,var(--axis-alpha)) 1px, rgba(255,255,255,0) 1px), " +
                        "radial-gradient(circle at 50% 50%, rgba(255,255,255,var(--dot-alpha-25)) 0.8px, rgba(255,255,255,0) 1.2px), " +
                        "radial-gradient(circle at 50% 50%, rgba(255,255,255,var(--dot-alpha-100)) 1.3px, rgba(255,255,255,0) 2.0px)"
    host.style.backgroundRepeat = "no-repeat, no-repeat, repeat, repeat"
    host.style.backgroundSize = "1px 100%, 100% 1px, ${dotSpacingSmall}px ${dotSpacingSmall}px, ${dotSpacingLarge}px ${dotSpacingLarge}px"
    host.style.backgroundPosition = "0px 0px, 0px 0px, 0px 0px, 0px 0px"
    applyBackgroundAlphas()
}

private fun isEditorFocused(): Boolean {
    val active = document.activeElement ?: return false
    val className = active.asDynamic().className?.toString() ?: ""
    val inMonacoTextArea = active.classList?.contains("monaco-mouse-cursor-text") == true
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
    send("post-chat-message", js("{slideshowId: slideshowId, slideNumber: slideNumber, username: username, message: message}"))
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
    chatContainer.style.apply {
        marginTop = if (showHeader) "15px" else "8px"
        borderTop = if (showHeader) "1px solid rgba(255, 255, 255, 0.1)" else "none"
        paddingTop = if (showHeader) "10px" else "0px"
    }

    val chatContent = document.createElement("div") as HTMLElement
    chatContent.id = "chat-content-$widgetId"
    chatContent.style.display = if (showHeader) {
        if (expanded) "block" else "none"
    } else {
        "block"
    }

    val messagesArea = document.createElement("div") as HTMLElement
    messagesArea.id = "chat-messages-$widgetId"
    messagesArea.style.apply {
        maxHeight = "200px"
        overflowY = "auto"
        marginBottom = "10px"
        padding = "8px"
        backgroundColor = "rgba(0, 0, 0, 0.3)"
        borderRadius = "4px"
        fontSize = "12px"
    }

    val inputArea = document.createElement("div") as HTMLElement
    inputArea.style.apply {
        display = "flex"
        marginTop = "8px"
        backgroundColor = "rgba(0, 0, 0, 0.3)"
        border = "1px solid rgba(255, 255, 255, 0.08)"
        borderRadius = "4px"
        padding = "6px"
    }

    val messageInput = document.createElement("textarea") as HTMLTextAreaElement
    messageInput.id = "chat-input-$widgetId"
    messageInput.setAttribute("placeholder", "Add a comment + return")
    messageInput.style.apply {
        flex = "1"
        minHeight = "60px"
        padding = "6px"
        backgroundColor = "rgba(0, 0, 0, 0.3)"
        color = "#e6e6e6"
        border = "1px solid rgba(255, 255, 255, 0.08)"
        borderRadius = "4px"
        fontSize = "12px"
        fontFamily = "inherit"
        resize = "vertical"
        outline = "1px solid #6fe6ff"
    }

    fun sendCurrentMessage() {
        val message = messageInput.value.trim()
        if (message.isNotBlank()) {
            postChatMessage(slideshowId, slideNumber, message)
            messageInput.value = ""
        }
    }

    messageInput.onkeydown = { event ->
        val keyboardEvent = event as KeyboardEvent
        if (keyboardEvent.key == "Enter" && !keyboardEvent.shiftKey) {
            keyboardEvent.preventDefault()
            sendCurrentMessage()
        }
    }

    if (showHeader) {
        val chatHeader = document.createElement("div") as HTMLElement
        chatHeader.style.apply {
            cursor = "pointer"
            fontWeight = "bold"
            fontSize = "12px"
            marginBottom = "8px"
            color = "#a0a0a0"
        }
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

fun createChatUI(slideshowId: String, slideNumber: Int, expanded: Boolean = false, showHeader: Boolean = true): HTMLElement {
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
        emptyMsg.style.apply {
            color = "#888"
            fontStyle = "italic"
            fontSize = "11px"
        }
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
            msgDiv.style.apply {
                marginBottom = "8px"
                paddingBottom = "8px"
                borderBottom = "1px solid rgba(255, 255, 255, 0.05)"
            }

            val header = document.createElement("div") as HTMLElement
            header.style.apply {
                fontSize = "11px"
                color = "#999"
                marginBottom = "4px"
                cssFloat = "right"
            }

            val timestamp = formatTimestamp(msg.timestamp)

            header.innerHTML = if (timestamp.isNotEmpty()) {
                "<strong>${msg.username}</strong> <span style='opacity: 0.6'>$timestamp</span>"
            } else {
                "<strong>${msg.username}</strong>"
            }

            val content = document.createElement("div") as HTMLElement
            content.style.apply {
                fontSize = "12px"
                color = "#e6e6e6"
                whiteSpace = "pre-wrap"
                wordWrap = "break-word"
            }
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

// Layers system functions
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

fun hasRenderableFrame(box: HTMLElement): Boolean {
    val d = box.unsafeCast<dynamic>()
    val w = (d.data_w as? Number)?.toDouble() ?: 0.0
    val h = (d.data_h as? Number)?.toDouble() ?: 0.0
    return w > FRAME_VISIBILITY_EPSILON || h > FRAME_VISIBILITY_EPSILON
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
    box.style.opacity = if (visible) "1" else "0"
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
        layerDebugLog("applyFadeVisibility scheduled hide timer", json("id" to uid, "handle" to hideHandle, "duration" to duration))
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

        val primary = if (direction == CardinalDirection.EAST || direction == CardinalDirection.WEST) abs(dx) else abs(dy)
        val secondary = if (direction == CardinalDirection.EAST || direction == CardinalDirection.WEST) abs(dy) else abs(dx)
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
                CardinalDirection.SOUTH -> Pair(targetHighlight.left + targetHighlight.width / 2.0, targetHighlight.bottom)
                CardinalDirection.EAST -> Pair(targetHighlight.right, targetHighlight.top + targetHighlight.height / 2.0)
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
            maxBox = box as HTMLElement
        } else if (z == maxZ) {
            maxCount++
        }
    }
    val selectedZ = it.style.zIndex.toIntOrNull() ?: 0
    if (maxBox == null) return
    if (maxCount == 1 && maxBox != it) {
        maxBox!!.style.zIndex = selectedZ.toString()
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

@OptIn(DelicateCoroutinesApi::class)
suspend fun main() {
    launchNumber++
    println(" -- welcome to Main.kt --")
    val searchParams = URLSearchParams(document.location?.search)
    val filename = searchParams.get("file") ?: "default"
    val username = searchParams.get("user") ?: "everybody"
    val sessionKey = searchParams.get("sessionKey")
    val scratch = searchParams.get("scratch") == "1" || searchParams.get("scratch") == "true"
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
    window.unsafeCast<dynamic>().selectBox = ::selectBox
    window.unsafeCast<dynamic>().deselectBox = ::deselectBox
    window.asDynamic().fieldBackdoorPrepareBoxScreenshot = { raw: dynamic -> prepareBoxScreenshotJson(raw) }
    window.asDynamic().fieldBackdoorPrepareWindowScreenshot = { raw: dynamic -> prepareWindowScreenshotJson(raw) }

    window.unsafeCast<dynamic>().showCheatsheet = ::showCheatsheet

    // Initialize the box snapping system
    boxSnappingSystem.initialize()

    document.title = filename.substringAfterLast("/")
    registerOnOpen(Message("hello").payload(Hello(filename, username, sessionKey = sessionKey, scratch = scratch)))

    initializeClient()
    registerDoubleTap()
    installSpatialNavigationShortcut()

    doubleTap["Escape"] = {
        removeNewestOutput()
        null
    }

    doubleTap["Shift"] = {
        val w = window.unsafeCast<dynamic>()
        val fn = w.openAnywherePalette
        if (fn != undefined && fn != null) {
            try { fn() } catch (e: Exception) { console.info("[palette] openAnywherePalette failed", e) }
        } else {
            console.info("[palette] openAnywherePalette not available")
        }
        null
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
        if (set.property !== "frame") return false

        val u = set.uid
        val boxes = document.body!!.getElementsByClassName("box").asList()
        val found = boxes.firstOrNull { it.unsafeCast<dynamic>().uid == u }

        if (found === null) {
            mainCanvas.append {
                createBox("", frame = set.value, uid = u, sendInitialSync = false)
            }
        } else {
            val box = found as HTMLElement
            applyFrameToBox(box, set.value)
            applyBoxVisibilityImmediate(box)
        }
        return true
    }

    handlers.put("set-rect") { message, _ ->
        val set = Json.decodeFromJsonElement<Set_rect>(message.payload!!)
        if (!applyIncomingRect(set)) return@put Handled.no
        canvasGestures.rebuildConnectionGroup()
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
            canvasGestures.rebuildConnectionGroup()
            repositionSlideNoteOverlayIfVisible()
            return@put Handled.yes
        }
        Handled.no
    }

    handlers.put("set-animated-rect") { message, _ ->
        val set = Json.decodeFromJsonElement<Set_animated_rect>(message.payload!!)
        if (set.property !== "frame") return@put Handled.no

        val u = set.uid
        val boxes = document.body!!.getElementsByClassName("box").asList()
        val found = boxes.firstOrNull { it.unsafeCast<dynamic>().uid == u } as? HTMLElement

        if (found != null) {
            // Add transition CSS
            found.style.transition = "transform ${set.duration}ms ease-in-out, width ${set.duration}ms ease-in-out, height ${set.duration}ms ease-in-out"

            // Apply new position and size
            applyFrameToBox(found, set.value)
            applyBoxVisibilityImmediate(found)

            // Remove transition after animation completes
            window.setTimeout({
                found.style.transition = ""
                canvasGestures.rebuildConnectionGroup()
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
                canvasGestures.rebuildConnectionGroup()
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
                canvasGestures.rebuildConnectionGroup()
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
                    canvasGestures.rebuildConnectionGroup()
                }
            }
            "offline" -> {
                if (found !=null) {
                    if (set.number) {
                        found.unsafeCast<HTMLElement>().classList.add("offline")
                        found.asDynamic().offline = true
                        canvasGestures.rebuildConnectionGroup()
                    }
                    else {
                        found.unsafeCast<HTMLElement>().classList.remove("offline")
                        found.asDynamic().offline = false
                        canvasGestures.rebuildConnectionGroup()
                    }
                }
            }
            "_tombstone" -> {
                var target = found
                if (target == null) {
                    mainCanvas.append {
                        target = createBox("untitled", frame = Rect(0.0, 0.0, 0.0, 0.0), uid = u, sendInitialSync = false)
                    }
                }
                target?.let { boxEl ->
                    if (set.number) {
                        (boxEl as HTMLElement).style.display = "none"
                        (boxEl as HTMLElement).classList.add("tombstoned")
                        boxEl.asDynamic().tombstoned = true
                        if (selected.contains(boxEl)) {
                            deselectBox(boxEl)
                        }
                    } else {
                        boxEl.classList.remove("tombstoned")
                        boxEl.asDynamic().tombstoned = false
                        applyBoxVisibilityImmediate(boxEl as HTMLElement)
                    }
                    canvasGestures.rebuildConnectionGroup()
                }
            }
         }
        Handled.yes
    }

    handlers.put("is-running") { message, _ ->
        println(" got is-running message ${message}")
        val isRunning = Json.decodeFromJsonElement<IsRunning>(message.payload!!)
        setBoxRunning(isRunning.uid, isRunning.isRunning, isRunning.watermark, isRunning.strand)

        Handled.yes
    }
    handlers.put("peer-output") { message, _ ->
        if (!window.location.search.contains("user=playwright")) {
            return@put Handled.no
        }
        val output = Json.decodeFromJsonElement<Output>(message.payload!!)
        routeMonacoOutput(output)
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
                found!!.style.display = "none"
                found!!.style.visibility = "hidden"
            }
            return found!!
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
        ) as HTMLElement
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

        canvasGestures.rebuildConnectionGroup()
        Handled.yes
    }

    handlers.put("notify") { message, _ ->
        val set = Json.decodeFromJsonElement<Notify>(message.payload!!)
        notify(set.text, set.category)
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

    handlers.put("set-output") { message, _ ->
        val set = Json.decodeFromJsonElement<Set_output>(message.payload!!)
        val u = set.uid

        var found = defaultCreate(u)

        when (set.property) {
            "outputarea" -> {
                handleOutputAreaRequest(found as HTMLElement, set)
            }
        }
        Handled.yes
    }

    handlers.put("output-control") { message, _ ->
        val control = Json.decodeFromJsonElement<OutputControl>(message.payload!!)
        val u = control.uid
        val found = defaultCreate(u)
        handleOutputAreaControl(found as HTMLElement, control)
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

    handlers.put("set-string") { message, _ ->
        val set = Json.decodeFromJsonElement<Set_string>(message.payload!!)
        val u = set.uid

        var found = defaultCreate(u)

        when (set.property) {
            "name" -> {
                if (found === null) {
                    mainCanvas.append {
                        createBox(set.value, frame = Rect(0.0, 0.0, 0.0, 0.0), uid = u, sendInitialSync = false)
                    }
                } else {
                    found!!.getElementsByClassName("doubleClickToEdit").asList()[0].innerHTML = set.value
                }
                found.unsafeCast<dynamic>().name = set.value
            }

            "code" -> {
                found.unsafeCast<dynamic>().code = set.value
                notifyPotentialEditedPropertyHasBeenRemoteUpdated(u, "code", set.value)
                notifyEmbeddedEditorOfRemoteUpdate(u, "code", set.value)
            }

            "code_editorState" -> {
                found.unsafeCast<dynamic>().code_editorState = set.value
                notifyPotentialEditedPropertyHasEditorStateRemoteUpdated(u, "code", set.value)
            }

            "code_decorationState" -> {
                found.unsafeCast<dynamic>().decorSpec = set.value
                val sv = JSON.parse<dynamic>(set.value)
                setDecorationsForEditor(u, "code",found.unsafeCast<HTMLElement>(), sv)
            }

            "code_language" -> {
                if (set.value != found.unsafeCast<dynamic>().code_language) {
                    found.unsafeCast<dynamic>().code_language = set.value
                    notifyPotentialEditedPropertyHasHadLanguageChanged(u, "code", set.value)
                }
            }

            "fragment" -> {
                found.unsafeCast<dynamic>().fragment = set.value
                notifyEmbeddedEditorOfRemoteUpdate(u, "fragment", set.value)
            }

            "task" -> {
                found.unsafeCast<dynamic>().task = set.value
                notifyEmbeddedEditorOfRemoteUpdate(u, "task", set.value)
            }

            "mesh" -> {
                found.unsafeCast<dynamic>().mesh = set.value
                notifyEmbeddedEditorOfRemoteUpdate(u, "mesh", set.value)
            }

            "metadata" -> {
                found.unsafeCast<dynamic>().metadata = set.value
                notifyEmbeddedEditorOfRemoteUpdate(u, "metadata", set.value)
            }

            "compute" -> {
                found.unsafeCast<dynamic>().compute = set.value
                notifyEmbeddedEditorOfRemoteUpdate(u, "compute", set.value)
            }

            "preamble" -> {
                found.unsafeCast<dynamic>().preamble = set.value
                notifyEmbeddedEditorOfRemoteUpdate(u, "preamble", set.value)
            }

            "introduction" -> {
                found.unsafeCast<dynamic>().introduction = set.value
                notifyEmbeddedEditorOfRemoteUpdate(u, "introduction", set.value)
            }

            "vertex" -> {
                found.unsafeCast<dynamic>().vertex = set.value
                notifyEmbeddedEditorOfRemoteUpdate(u, "vertex", set.value)
            }

            "customization" -> {
                handleCustomizationRequest(found as HTMLElement, set)
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
                canvasGestures.rebuildConnectionGroup()
            }

            "layerFrames" -> {
                // layerFrames comes as a JSON string representation of Map<String, Rect>
                try {
                    val parsed = JSON.parse<dynamic>(set.value)
                    found.unsafeCast<dynamic>().box_layerFrames = parsed
                } catch (e: Exception) {
                    console.error("Failed to parse layerFrames for box " + set.uid + ". Value: '" + set.value.take(100) + "...'", e)
                }
            }
        }

        Handled.yes
    }

    // Slideshow handlers
    handlers.put("select-and-edit-box") { message, _ ->
        val sel = Json.decodeFromJsonElement<SelectAndEditBox>(message.payload!!)
        val boxes = document.body!!.getElementsByClassName("box").asList()
        val found = boxes.firstOrNull { it.unsafeCast<dynamic>().uid == sel.uid }

        if (found != null) {
            val box = found as HTMLElement

            // Open nested disclosure areas if the box is nested
            fun openNestedDisclosures(targetBox: HTMLElement) {
                // Check if box is nested inside another box
                val nestedIn = targetBox.unsafeCast<dynamic>().box_nestedIn as? String
                if (nestedIn != null) {
                    // Find the parent box
                    val parentBoxes = document.body!!.getElementsByClassName("box").asList()
                    val parentBox = parentBoxes.firstOrNull {
                        it.unsafeCast<dynamic>().uid == nestedIn
                    } as? HTMLElement

                    if (parentBox != null) {
                        // Recursively open parent disclosures first
                        openNestedDisclosures(parentBox)

                        // Open the nested disclosure for this parent
                        val outputArea = parentBox.getElementsByClassName("outputArea").asList().firstOrNull() as? HTMLElement
                        if (outputArea != null) {
                            val contain = outputArea.querySelector(".disclosure-group")
                            val di = js("window.disclosureInterface")
                            if (di != null && di != undefined) {
                                try {
                                    val inter = di(contain)
                                    if (inter.open != null && inter.open != undefined) {
                                        (inter.open as (String) -> Unit).invoke("nested")
                                    }
                                } catch (e: Exception) {
                                    console.info("[nav] failed to open nested disclosure", e)
                                }
                            }

                            // Make sure the output area is visible
                            outputArea.style.visibility = "visible"
                            outputArea.style.height = "auto"
                        }
                    }
                }
            }

            // Open all parent disclosures
            openNestedDisclosures(box)

            // Scroll the nested area so the target box is visible
            setTimeout( {
                try {
                    ensureBoxVisibleForNavigation(box)
                } catch (e: Exception) {
                    console.info("[nav] failed to scroll to box", e)
                }
            }, 50)

            // Deselect all boxes first
            selected.toList().forEach { deselectBox(it) }

            // Select the target box
            selectBox(box)

            // Set the selected property
            selectedProperty = sel.property
            window.unsafeCast<dynamic>().selectedProperty = sel.property
            selectionContextChanged()

            // Trigger editor to show this property
            val w = window.unsafeCast<dynamic>()
            if (w.switchToProperty != null && w.switchToProperty != undefined) {
                try {
                    w.switchToProperty(sel.uid, sel.property)
                } catch (e: Exception) {
                    console.info("[nav] switchToProperty failed", e)
                }
            }

            // Scroll to line if specified
            if (sel.scrollToLine != null) {
                setTimeout( {
                    val editor = w.globalEditor
                    if (editor != null && editor != undefined) {
                        try {
                            val monaco = w.globalMonaco
                            if (monaco != null && monaco != undefined) {
                                // Reveal line at center
                                editor.revealLineInCenter(sel.scrollToLine)

                                // Highlight line if specified
                                if (sel.highlightLine != null) {
                                    val line = sel.highlightLine!!
                                    val range = js("new monaco.Range(line, 1, line, 1)")
                                    editor.setSelection(range)
                                    editor.revealLineInCenter(sel.highlightLine)
                                }
                            }
                        } catch (e: Exception) {
                            console.info("[nav] failed to scroll to line", e)
                        }
                    }
                }, 100)
            }
        }

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
                fontFamily = "\"Iosevka\", \"Iosevka Term\", \"Iosevka Nerd Font\", \"Fira Code\", Menlo, Consolas, monospace"
                fontSize = "13px"
                lineHeight = "1.6"
                unsafeCast<dynamic>().backdropFilter = "blur(12px) brightness(1.04)"
            }
            document.body!!.appendChild(overlay)
        }

        // Clear previous content
        overlay.innerHTML = ""
        cleanupChatWidgets()

        // Add main content
        val contentDiv = document.createElement("div") as HTMLElement
        contentDiv.innerHTML = note.html
        overlay.appendChild(contentDiv)

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
        div {
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

    mainCanvas = mainViewport.append {
        div {
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
        if (event.buttons.toInt()==4) {}
        else {
            if (event.target == mainViewport) {
                canvasGestures.down(event)
            } else {

            }
        }
    }
    mainViewport.onmouseup = { event ->
        if (event.buttons.toInt()==4) {} else {
            canvasGestures.up(event)
        }
    }
    mainViewport.onmousemove = { event ->
        if (event.buttons.toInt() ===4) {
            // this is actually a drag gesture
            cancelNavigationPanAnimation()
            event.preventDefault()
            event.stopPropagation()

            var mc = mainCanvas.unsafeCast<dynamic>()
            val x: Number = (mc.data_x ?: 0.0) + event.unsafeCast<dynamic>().movementX
            val y: Number = (mc.data_y ?: 0.0) + event.unsafeCast<dynamic>().movementY

            (mc as HTMLElement).style.transform = "translate(${x}px, ${y}px)"
            mainTranslation = listOf(x.toDouble(), y.toDouble())

            println(" main translation now ${x} ${y} ${window.innerWidth} ${window.innerHeight}")

            mc.data_x = x
            mc.data_y = y

            updateBackgroundGrid(x.toDouble(), y.toDouble(), panning = true)

            send(Message("set-viewport").payload(Set_rect(uid="root", property="viewport", Rect(x.toDouble(),y.toDouble(), window.innerWidth.toDouble(), window.innerHeight.toDouble()))))

            canvasGestures.rebuildConnectionGroup(translationOnly = true)
            repositionSlideNoteOverlayIfVisible()
            if (spatialNavActive) {
                refreshSpatialNavTargets()
            }
        }
        else
            canvasGestures.move(event)
    }


    mainViewport.onwheel = onwheel@{ event ->

        // todo, should read "has outputArea_content as parent"
        val target = event.target as? HTMLElement
        if (target != null && target.asDynamic().closest(".outputArea") != null) {
            return@onwheel null
        }

        if (event.target is SVGElement) {
        } else {

            cancelNavigationPanAnimation()
            event.preventDefault()
            event.stopPropagation()

            var mc = mainCanvas.unsafeCast<dynamic>()
            val x: Number = (mc.data_x ?: 0.0) - event.deltaX
            val y: Number = (mc.data_y ?: 0.0) - event.deltaY

            (mc as HTMLElement).style.transform = "translate(${x}px, ${y}px)"
            mainTranslation = listOf(x.toDouble(), y.toDouble())

            println(" main translation now ${x} ${y} ${window.innerWidth} ${window.innerHeight}")

            mc.data_x = x
            mc.data_y = y

            updateBackgroundGrid(x.toDouble(), y.toDouble(), panning = true)

            send(Message("set-viewport").payload(Set_rect(uid="root", property="viewport", Rect(x.toDouble(),y.toDouble(), window.innerWidth.toDouble(), window.innerHeight.toDouble()))))

            canvasGestures.rebuildConnectionGroup(translationOnly = true)
            repositionSlideNoteOverlayIfVisible()
            if (spatialNavActive) {
                refreshSpatialNavTargets()
            }
        }

        null
    }


    mainCanvas.unsafeCast<dynamic>().data_x = 0
    mainCanvas.unsafeCast<dynamic>().data_y = 0
    updateBackgroundGrid(0.0, 0.0, panning = false)

    // File drop handler for importing metadata.edn files into the current document
    // This allows users to drag and drop .edn files onto the page to import boxes
    // All imported boxes get new UUIDs to avoid ID conflicts (similar to copy/paste)
    body.ondragover = { event ->
        event.preventDefault()
        null
    }

    body.ondrop = { event ->
        event.preventDefault()
        val files = event.asDynamic().dataTransfer.files
        if (files.length > 0) {
            val file = files[0]
            if (file.name == "metadata.edn" || file.name.endsWith(".edn")) {
                val reader = js("new FileReader()")
                reader.onload = { loadEvent: dynamic ->
                    val ednText = loadEvent.target.result as String
                    console.info("[import] metadata.edn file: ${file.name}")
                    send(Message("import-metadata-edn").payload(ImportMetadata(ednText)))
                    notify("Importing ${file.name}...", "import")
                }
                reader.readAsText(file)
            } else {
                notify("Please drop a metadata.edn file", "error")
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

    fun ensureAbsolutePosition(el: HTMLElement) {
        val dyn = el.asDynamic()
        if (dyn.data_initialized == true) return
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
        dyn.data_initialized = true
    }

    monacoResizeHandler = { event: dynamic ->

        val target: dynamic = event.target
        ensureAbsolutePosition(target as HTMLElement)
        val x: Number = (target.data_x ?: 0.0) + event.deltaRect.left
        val y: Number = (target.data_y ?: 0.0) + event.deltaRect.top

        (target as HTMLElement).style.transform = "translate(0px, 0px)"
        target.style.left = "${x}px"
        target.style.top = "${y}px"
        target.style.width = event.rect.width + "px"
        target.style.height = event.rect.height + "px"

        target.data_x = x
        target.data_y = y
        target.data_w = event.rect.width
        target.data_h = event.rect.height

        // Keep docs container in sync when Monaco is resized
        val docsContainer = document.querySelector(".docs-iframe-container") as? HTMLElement
        if (docsContainer != null) {
            ensureAbsolutePosition(docsContainer)
            docsContainer.style.transform = "translate(0px, 0px)"
            docsContainer.style.left = target.style.left
            docsContainer.style.top = target.style.top
            docsContainer.style.width = target.style.width
            docsContainer.style.height = target.style.height
            docsContainer.asDynamic().data_x = target.data_x
            docsContainer.asDynamic().data_y = target.data_y
            docsContainer.asDynamic().data_w = target.data_w
            docsContainer.asDynamic().data_h = target.data_h
        }
    }

    moncaoResizing.resizable(
        json("edges" to json("top" to true, "bottom" to true, "left" to true, "right" to true))
    ).on("resizemove") { event: dynamic ->
        monacoResizeHandler(event)
    }

    // Allow docs iframe container to be resized like Monaco, and propagate new size back to Monaco/editor
    val docsResizing = interact(".docs-iframe-container")
    docsResizing.resizable(
        json("edges" to json("top" to true, "bottom" to true, "left" to true, "right" to true))
    ).on("resizemove") { event: dynamic ->
        val target: dynamic = event.target
        ensureAbsolutePosition(target as HTMLElement)
        val x: Number = (target.data_x ?: 0.0) + event.deltaRect.left
        val y: Number = (target.data_y ?: 0.0) + event.deltaRect.top

        target.style.transform = "translate(0px, 0px)"
        target.style.left = "${x}px"
        target.style.top = "${y}px"
        target.style.width = event.rect.width + "px"
        target.style.height = event.rect.height + "px"

        target.data_x = x
        target.data_y = y
        target.data_w = event.rect.width
        target.data_h = event.rect.height

        // Mirror size back to Monaco container
        val monacoContainer = document.querySelector(".monaco-editor-container") as? HTMLElement
        if (monacoContainer != null) {
            ensureAbsolutePosition(monacoContainer)
            monacoContainer.style.transform = "translate(0px, 0px)"
            monacoContainer.style.left = target.style.left
            monacoContainer.style.top = target.style.top
            monacoContainer.style.width = target.style.width
            monacoContainer.style.height = target.style.height
            monacoContainer.asDynamic().data_x = target.data_x
            monacoContainer.asDynamic().data_y = target.data_y
            monacoContainer.asDynamic().data_w = target.data_w
            monacoContainer.asDynamic().data_h = target.data_h
        }

        // Notify Monaco editor about size change
        val editor = window.asDynamic().globalEditor
        if (editor != null && editor != undefined && editor.layout != undefined) {
            val dims = js("{}")
            dims.width = event.rect.width
            dims.height = event.rect.height
            editor.layout(dims)
        }
    }

    val interactable = interact(".box")

    moveHandler = move@{ event: dynamic ->
        val target: dynamic = event.target
        val uid = target.uid as? String ?: return@move null

        val tdx = (event.clientX - originatingMouseDown.clientX) as Number
        val tdy = (event.clientY - originatingMouseDown.clientY) as Number
        val odx = (target.originalFramePosition as List<Number>)[0].toDouble() + tdx.toDouble()
        val ody = (target.originalFramePosition as List<Number>)[1].toDouble() + tdy.toDouble()

        var t = window.getComputedStyle(event.target).borderWidth.split("px")[0].toDouble() * 0.0

        // Apply snapping if Caps Lock is enabled
        val targetElement = target as HTMLElement
        val width = ((target.data_w as? Number)?.toDouble() ?: (event.rect?.width as? Number)?.toDouble() ?: 0.0) - t * 2
        val height = ((target.data_h as? Number)?.toDouble() ?: (event.rect?.height as? Number)?.toDouble() ?: 0.0) - t * 2

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
                notify("launched <i>${target.name}</i>", "launch")
                launchAll(target.uid, target["code"])
                already = false
            }

            val menus = mutableListOf(
                json("text" to "stop", "callback" to {
                    notify("<i>${target.name}</i> stopped", "launch")
                    stopBox(target.uid)
                }, "radius" to 1.0, "angle" to 0.0)
            )

            if (!already) {
                menus.add(json("text" to "launch", "callback" to {
                    notify("<i>${target.name}</i> will continue", "launch")
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
            val pending = mutableListOf<Set_rect>()
            selected.forEach {
                event.target = reorient(it)
                if (event.target) {
                    moveHandler(event)?.let { next ->
                        pending.add(next)
                    }
                }
            }
            if (pending.isNotEmpty()) {
                send(Message("set-rects").payload(Set_rects(pending)))
                canvasGestures.rebuildConnectionGroup()
            }
        }
    }))



    resizeHandler = { event: dynamic ->

        val target: dynamic = event.target

        var x: Double = (target.data_x ?: 0.0) + event.deltaRect.left
        var y: Double = (target.data_y ?: 0.0) + event.deltaRect.top

        var w = event.rect.width
        var h = event.rect.height

        if (target.lock_w) w = target.data_w ?: 100.0
        if (target.lock_h) h = target.data_h ?: 100.0

        if (target.lock_x) x = target.data_x ?: 0.0
        if (target.lock_y) y = target.data_y ?: 0.0

        if (target.lock_w && !target.lock_x) {
            x += event.deltaRect.right as Double
        }
        if (target.lock_h && !target.lock_y) {
            y += event.deltaRect.bottom as Double
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

        val originalTarget = event._interaction.downEvent.target
        val isBox = originalTarget?.unsafeCast<HTMLElement>()?.classList?.contains("box") ?: false
        if (!isBox) {
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
        val box = t.parentElement!!.parentElement

        if (t.contentEditable != "true") {
            t.contentEditable = "true"

            fun fireChange() {
                box.unsafeCast<dynamic>().name = t.textContent
                val uid = box.unsafeCast<dynamic>().uid as String
                send(
                    Message("set-string").payload(
                        Set_string(
                            uid = uid, property = "name", value = t.textContent ?: ""
                        )
                    )
                )
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
        initializeDocumentSearch()
        hideMonaco()

        later({
            notify("finished loading — press <b>?</b> for help", "setup")
            (document.getElementById("overlaycanvas") as HTMLElement).style.unsafeCast<dynamic>().backdropFilter = ""
        }, 100)

    }, 0)
    forever(::serviceEvents, 30)

    onSelectedChange.put("server") {
        val ids = selected.map { it.id as String }
        true
    }

    send(Message("set-viewport").payload(Set_rect(uid="root", property="viewport", Rect(0.0,0.0, window.innerWidth.toDouble(), window.innerHeight.toDouble()))))

    registerMouseBug()

    window.addEventListener("resize", {
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
            backdropFilter = "blur(4px)"
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
        div {
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
    d1d.server_visible = true

    oa = d1.getElementsByClassName("outputArea").asList()[0] as HTMLElement
    applyBoxVisibilityImmediate(d1)

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
    } ?: null
}


fun <E> MutableSet<E>.copy() = HashSet<E>(this)
