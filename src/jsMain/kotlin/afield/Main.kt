@file:OptIn(ExperimentalJsExport::class, ExperimentalUuidApi::class)

package afield

import CanvasGestures
import doubleTap
import handleCustomizationRequest
import handleEvalRequest
import handleOutputAreaRequest
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
import org.w3c.dom.HTMLElement
import org.w3c.dom.MutationObserver
import org.w3c.dom.MutationObserverInit
import org.w3c.dom.asList
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.svg.SVGElement
import org.w3c.dom.svg.SVGSVGElement
import org.w3c.dom.url.URLSearchParams
import registerDoubleTap
import web.cssom.important
import web.timers.setTimeout
import kotlin.js.json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

external fun interact(selector: String): dynamic
external fun interact(selector: HTMLElement): dynamic

external fun notify(text: String, category: String)

external fun setBoxRunning(uid: String, running: Boolean, watermark: Int, strand: String)

external fun showMonaco(): dynamic
external fun hideMonaco(): dynamic
external fun initMenuSupport(): dynamic


external fun setDecorationsForEditor(uid: String, property: String, box: HTMLElement, decorSpec : dynamic)

external fun setActions(actions: dynamic): dynamic

external fun goMarkingMenu(x: Double, y: Double, menus: dynamic, event: dynamic, cancelCallback: dynamic): dynamic
external fun provideMissingMouseup(event: dynamic): dynamic

external object Provisional {
    fun clear()
    fun set(uid: String, property: String, contents: String)
}

lateinit var mainCanvas: HTMLElement
lateinit var overlaySVGLayer: SVGSVGElement
external val monaco: dynamic

external val allActions: dynamic

external var originatingMouseDown: MouseEvent

lateinit var moveHandler: (dynamic) -> Unit
lateinit var resizeHandler: (dynamic) -> Unit
lateinit var monacoResizeHandler: (dynamic) -> Unit
var mainTranslation = listOf(0.0, 0.0)
lateinit var annotationSVGLayer: SVGSVGElement
private var editorVisibilityObserver: MutationObserver? = null
private var editorPropertyObserver: MutationObserver? = null

@JsExport
@JsName("launchNumber")
var launchNumber = 0

external fun launchAll(uid: String, code: String)

@JsExport
@JsName("canvasGestures")
var canvasGestures = CanvasGestures(launcher = {
    it.forEach { launchAll(it.unsafeCast<dynamic>().uid as String, it.unsafeCast<dynamic>().code as String) }
})

var selected = mutableSetOf<HTMLElement>()
var selectedList = eval("[]")
var selectedProperty = "code"

var onSelectedChange = mutableMapOf<String, () -> Boolean>()

var events = HashMap<String, () -> Boolean>()

// Layers system state
var visibleLayers = mutableSetOf("default")
var activeLayer: String? = null

private fun selectionContextChanged() {
    if (currentAnnotations.isNotEmpty()) {
        redrawSlideAnnotations()
        scheduleSlideAnnotationRedraw(10)
    }
}

fun serviceEvents() {
    events.entries.retainAll { (k, v) ->
        v()
    }
}

var slideNoteAnchorUid: String? = null

fun findBoxByUid(uid: String?): HTMLElement? {
    if (uid.isNullOrBlank()) return null
    val boxes = document.body!!.getElementsByClassName("box").asList()
    return boxes.firstOrNull { it.unsafeCast<dynamic>().uid == uid } as? HTMLElement
}

private fun positionSlideNoteOverlay(overlay: HTMLElement, anchorUid: String?) {
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

private fun repositionSlideNoteOverlayIfVisible() {
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
 * Animates slide note text letter by letter with pauses on punctuation
 */
private fun animateSlideNoteText(overlay: HTMLElement, html: String) {
    // For animation, we'll create a wrapper that progressively reveals content
    overlay.innerHTML = ""
    val contentDiv = document.createElement("div") as HTMLElement
    overlay.appendChild(contentDiv)

    // Build DOM structure and capture the original text nodes
    contentDiv.innerHTML = html
    val textNodes = collectTextNodes(contentDiv)
    val originalTexts = textNodes.map { it.textContent ?: "" }
    val fullText = originalTexts.joinToString("")

    // Nothing to animate if there's no text
    if (fullText.isEmpty()) return

    // Hide all text initially; we'll progressively restore characters
    textNodes.forEach { it.textContent = "" }

    var charIndex = 0
    val delay = 15.0 // milliseconds per character

    fun animateNextChar() {
        if (charIndex >= fullText.length) return

        // Reveal characters up to the current index across all nodes
        var remaining = charIndex
        textNodes.zip(originalTexts).forEach { (node, original) ->
            val len = original.length
            when {
                remaining >= len -> {
                    if (node.textContent != original) node.textContent = original
                    remaining -= len
                }
                remaining >= 0 -> {
                    node.textContent = original.substring(0, remaining + 1)
                    remaining = -1
                }
                else -> node.textContent = ""
            }
        }

        val char = fullText[charIndex]
        charIndex++

        // Extra pause for periods, commas, and other punctuation
        val pauseExtra = when (char) {
            '.', '!', '?' -> 300.0
            ',', ';', ':' -> 150.0
            else -> 0.0
        }

        // If we have active annotations, keep redrawing so the line start tracks the growing note width
        if (currentAnnotations.isNotEmpty()) {
            scheduleSlideAnnotationRedraw(10)
        }

        setTimeout({
            animateNextChar()
        }, (delay + pauseExtra).toInt())
    }

    animateNextChar()
}

/**
 * Collects all text nodes from a DOM tree for animation
 */
private fun collectTextNodes(element: org.w3c.dom.Node): List<org.w3c.dom.Node> {
    val result = mutableListOf<org.w3c.dom.Node>()

    fun traverse(node: org.w3c.dom.Node) {
        if (node.nodeType == 3.toShort()) { // TEXT_NODE
            result.add(node)
        } else {
            val children = node.childNodes
            for (i in 0 until children.length) {
                children.item(i)?.let { traverse(it) }
            }
        }
    }

    traverse(element)
    return result
}

private fun ensureAnnotationLayer(): SVGSVGElement {
    if (::annotationSVGLayer.isInitialized && annotationSVGLayer.parentNode != null) return annotationSVGLayer

    val existing = document.getElementById("slide-annotation-layer") as? SVGSVGElement
    if (existing != null) {
        annotationSVGLayer = existing
        return existing
    }

    val svg = document.createElementNS("http://www.w3.org/2000/svg", "svg") as SVGSVGElement
    svg.id = "slide-annotation-layer"
    svg.setAttribute("width", "100%")
    svg.setAttribute("height", "100%")
    svg.style.apply {
        position = "absolute"
        top = "0"
        left = "0"
        right = "0"
        bottom = "0"
        zIndex = "10002"
    }
    svg.style.asDynamic().pointerEvents = "none"
    document.body!!.appendChild(svg)
    annotationSVGLayer = svg
    return svg
}

private fun ensureEditorVisibilityObserver() {
    if (editorVisibilityObserver != null) return
    val container = document.querySelector(".monaco-editor-container") as? HTMLElement ?: return

    val observer = MutationObserver { _, _ ->
        if (currentAnnotations.isNotEmpty()) {
            scheduleSlideAnnotationRedraw(5)
        }
    }
    observer.observe(
        container,
        MutationObserverInit(
            attributes = true,
            attributeFilter = arrayOf("style", "class"),
            attributeOldValue = false,
            childList = false,
            subtree = false
        )
    )
    editorVisibilityObserver = observer
}

private fun ensureEditorPropertyObserver() {
    if (editorPropertyObserver != null) return
    val overlay = (window.asDynamic().globalEditor?.unsafeCast<dynamic>()?._domElement as? HTMLElement)
        ?.querySelector(".hoverlay") as? HTMLElement ?: return

    val observer = MutationObserver { _, _ ->
        selectionContextChanged()
    }
    observer.observe(
        overlay,
        MutationObserverInit(
            childList = true,
            characterData = true,
            subtree = true
        )
    )
    editorPropertyObserver = observer
}

private fun slideAnnotationGroup(): SVGElement {
    val svg = ensureAnnotationLayer()
    val name = "slideAnnotations"
    val existing = svg.querySelector("[data-name=\"$name\"]")
    if (existing != null) return existing as SVGElement
    val g = document.createElementNS("http://www.w3.org/2000/svg", "g") as SVGElement
    g.setAttribute("data-name", name)
    svg.appendChild(g)
    return g
}

private fun syncAnnotationViewBoxToTranslation() {
    val svg = ensureAnnotationLayer()
    val rect = svg.getBoundingClientRect()
    svg.setAttribute("viewBox", "${-mainTranslation[0]} ${-mainTranslation[1]} ${rect.width} ${rect.height}")
}

private var annotationRedrawFramesRemaining = 0
private var annotationRedrawScheduled = false

private fun scheduleSlideAnnotationRedraw(frames: Int = 0) {
    if (frames > annotationRedrawFramesRemaining) {
        annotationRedrawFramesRemaining = frames
    }
    if (annotationRedrawScheduled) return

    annotationRedrawScheduled = true
    fun tick() {
        redrawSlideAnnotations()
        if (annotationRedrawFramesRemaining > 0) {
            annotationRedrawFramesRemaining--
            window.requestAnimationFrame { tick() }
        } else {
            annotationRedrawScheduled = false
        }
    }
    window.requestAnimationFrame { tick() }
}

/**
 * Creates HTML with text truncated to a certain length (DEPRECATED - kept for reference)
 */
private fun createTruncatedHTML(html: String, targetText: String): String {
    return html
}

/**
 * Draws annotation lines from the slide note to targets
 */
private var currentAnnotations: List<SlideAnnotation> = emptyList()
private var currentNoteOverlay: HTMLElement? = null

private fun drawSlideAnnotations(annotations: List<SlideAnnotation>, noteOverlay: HTMLElement) {
    currentAnnotations = annotations
    currentNoteOverlay = noteOverlay
    ensureEditorVisibilityObserver()
    ensureEditorPropertyObserver()
    redrawSlideAnnotations()
    if (annotations.isNotEmpty()) {
        scheduleSlideAnnotationRedraw(100)
    }
}

private fun redrawSlideAnnotations() {
    val annotations = currentAnnotations
    val noteOverlay = currentNoteOverlay

    if (annotations.isEmpty() || noteOverlay == null) {
        clearSlideAnnotations()
        return
    }

    // Keep selectedProperty in sync with the global window state in case external UI changed it
    window.asDynamic().selectedProperty?.toString()?.let { prop ->
        if (prop != selectedProperty) {
            selectedProperty = prop
        }
    }

    syncAnnotationViewBoxToTranslation()

    val svg = slideAnnotationGroup()
    svg.innerHTML = ""

    val noteRect = noteOverlay.getBoundingClientRect()
    val ox = mainTranslation[0]
    val oy = mainTranslation[1]

    annotations.forEach { annotation ->
        val targetBox = findBoxByUid(annotation.targetUid)
        if (targetBox != null) {
            val targetRect = targetBox.getBoundingClientRect()

            val targetingEditorLine = annotation.targetLine != null && annotation.targetProperty != null
            if (targetingEditorLine) {
                val propertyMatches = selectedProperty == annotation.targetProperty ||
                        (window.asDynamic().selectedProperty?.toString() == annotation.targetProperty)
                val boxMatches = selected.contains(targetBox) || targetBox.asDynamic().selected == true
                if (!propertyMatches || !boxMatches) {
                    return@forEach
                }
            }

            // Start point: right side of note overlay, vertically centered
            val startX = noteRect.right - ox
            val startY = noteRect.top + noteRect.height / 2 - oy

            // End point: depends on whether we're targeting a specific line or the whole box
            val (endX, endY) = if (annotation.targetLine != null && annotation.targetProperty != null) {
                // Prefer anchoring to the Monaco editor line when available
                val w = window.asDynamic()
                val editor = w.globalEditor
                val monaco = w.globalMonaco
                val editorDom = try { editor?.getDomNode() } catch (e: dynamic) { null }

                if (editor != null && editorDom != null) {
                    val editorRect = (editorDom as HTMLElement).getBoundingClientRect()
                    val editorHost = editorDom as HTMLElement
                    val editorVisible = editorRect.width > 0 && editorRect.height > 0 && editorHost.offsetParent != null
                    if (!editorVisible) return@forEach
                    val topForLine = (try { editor.getTopForLineNumber(annotation.targetLine) } catch (e: dynamic) { null }) as? Number
                    val scrollTop = (try { editor.getScrollTop() } catch (e: dynamic) { null }) as? Number ?: 0.0
                    val lineHeight = if (monaco != null) {
                        val opt = try { monaco.editor.EditorOption?.lineHeight } catch (e: dynamic) { null }
                        if (opt != null) (try { editor.getOption(opt) } catch (e: dynamic) { null }) as? Number else null
                    } else null

                    val y = if (topForLine != null) {
                        editorRect.top + (topForLine.toDouble() - scrollTop.toDouble()) + (lineHeight?.toDouble() ?: 16.0) / 2.0 - oy
                    } else {
                        editorRect.top + editorRect.height * 0.3 - oy
                    }

                    Pair(
                        editorRect.left - ox,
                        y
                    )
                } else {
                    // Fallback: aim toward the box (legacy behavior)
                    Pair(
                        targetRect.left - ox,
                        targetRect.top + (targetRect.height * 0.3) - oy
                    )
                }
            } else {
                // Target the left-center of the box
                Pair(
                    targetRect.left - ox,
                    targetRect.top + targetRect.height / 2 - oy
                )
            }

            // Draw the line with a cubic Bezier curve for smooth appearance
            val dx = endX - startX
            val controlOffset = kotlin.math.abs(dx) * 0.3

            path {
                moveTo(startX, startY)
                cubicTo(
                    startX + controlOffset, startY,
                    endX - controlOffset, endY,
                    endX, endY
                )
                stroke = annotation.color
                strokeWidth = annotation.strokeWidth
                fill = "none"
                if (annotation.dashed) {
                    path.setAttribute("stroke-dasharray", "5,5")
                }
                appendTo(svg as org.w3c.dom.svg.SVGElement)
            }

            // Add a small circle at the end point
            circle {
                cx = endX
                cy = endY
                r = 4
                fill = annotation.color
                appendTo(svg as org.w3c.dom.svg.SVGElement)
            }

            // Add a small circle at the start point
            circle {
                cx = startX
                cy = startY
                r = 3
                fill = annotation.color
                stroke = annotation.color
                strokeWidth = 1
                appendTo(svg as org.w3c.dom.svg.SVGElement)
            }
        }
    }
}

/**
 * Clears all slide annotation lines
 */
private fun clearSlideAnnotations() {
    currentAnnotations = emptyList()
    currentNoteOverlay = null
    val svg = slideAnnotationGroup()
    svg.innerHTML = ""
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
fun setVisibleLayers(layers: Set<String>) {
    visibleLayers.clear()
    visibleLayers.addAll(layers)
    send(Message("set-visible-layers").payload(SetVisibleLayers(layers.toList())))
    updateAllBoxVisibility()
    canvasGestures.rebuildConnectionGroup()
}

fun setActiveLayer(layer: String?) {
    activeLayer = layer
    send(Message("set-active-layer").payload(SetActiveLayer(layer)))
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

fun isBoxVisible(box: HTMLElement): Boolean {
    val boxLayers = (box.asDynamic().box_layers as? String) ?: "default"
    val layers = boxLayers.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    return layers.any { layer -> visibleLayers.contains(layer) }
}

fun updateAllBoxVisibility() {
    select(".box").forEach { box ->
        val visible = isBoxVisible(box)
        box.style.visibility = if (visible) "visible" else "hidden"
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

private fun updateSelectionBadges() {
    val orderMap = if (selected.size >= 2) {
        selected.toList().withIndex().associate { (idx, element) -> element to idx + 1 }
    } else emptyMap()
    val boxes = document.body?.getElementsByClassName("box")?.asList().orEmpty()

    boxes.forEach { element ->
        val box = element as HTMLElement
        val badge = ensureSelectionBadge(box)
        val order = orderMap[box]
        if (order != null) {
            badge.textContent = order.toString()
            badge.classList.add("visible")
            box.setAttribute("data-selection-order", order.toString())
        } else {
            badge.textContent = ""
            badge.classList.remove("visible")
            box.removeAttribute("data-selection-order")
        }
    }
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
        borderColor = important(rgb(72, 201, 255, 0.78))
    }

    ".doubleClickToEdit" {
        userSelect = UserSelect.none
    }

}

@OptIn(DelicateCoroutinesApi::class)
suspend fun main() {
    launchNumber++
    println(" -- welcome to Main.kt --")
    window.unsafeCast<dynamic>().selected = selected
    window.unsafeCast<dynamic>().selectedList = selectedList
    window.unsafeCast<dynamic>().selectedProperty = selectedProperty
    window.unsafeCast<dynamic>().selectionContextChanged = { selectionContextChanged() }
    window.unsafeCast<dynamic>().selectBox = ::selectBox
    window.unsafeCast<dynamic>().deselectBox = ::deselectBox

    window.unsafeCast<dynamic>().showCheatsheet = ::showCheatsheet

    initializeClient()
    registerDoubleTap()

    doubleTap["Escape"] = {
        removeNewestOutput()
        null
    }

    doubleTap["Shift"] = {
        val w = window.unsafeCast<dynamic>()
        val fn = w.openAnywherePalette
        if (fn != undefined && fn != null) {
            try { fn() } catch (e: Exception) { console.log("openAnywherePalette failed", e) }
        } else {
            console.log("openAnywherePalette not available")
        }
        null
    }


    handlers.put("local-eval") { message, _ ->
        val le = Json.decodeFromJsonElement<LocalEval>(message.payload!!)
        val boxes = document.body!!.getElementsByClassName("box").asList()
        val found = boxes.firstOrNull { it.unsafeCast<dynamic>().uid == le.uid }

        console.log(" -- local eval ", found)
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

    handlers.put("set-rect") { message, _ ->

        val set = Json.decodeFromJsonElement<Set_rect>(message.payload!!)
        if (set.property !== "frame") return@put Handled.no

        console.log(" set rect ", set, set.value)

        val u = set.uid

        val boxes = document.body!!.getElementsByClassName("box").asList()
        val found = boxes.firstOrNull { it.unsafeCast<dynamic>().uid == u }

        if (found === null) {
            console.log(" created box on demand ")
            mainCanvas.append {
                createBox("", frame = set.value, uid = u, sendInitialSync = false)
            }
        } else {
            (found as HTMLElement).style.transform = "translate(${set.value.x}px, ${set.value.y}px)"

            found.style.width = "${set.value.w}px"
            found.style.height = "${set.value.h}px"
            found.style.visibility = if (set.value.w > 0.0 || set.value.h > 0.0) "visible" else "hidden"

            found.unsafeCast<dynamic>().data_x = set.value.x
            found.unsafeCast<dynamic>().data_y = set.value.y
            found.unsafeCast<dynamic>().data_w = set.value.w
            found.unsafeCast<dynamic>().data_h = set.value.h
        }
        canvasGestures.rebuildConnectionGroup()
        repositionSlideNoteOverlayIfVisible()

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
                    if (!set.number) found.unsafeCast<dynamic>().style.visibility = "hidden"
                    else found.unsafeCast<dynamic>().style.visibility = "visible"
                    canvasGestures.rebuildConnectionGroup()
                }
            }
            "offline" -> {
                console.log(" got offline message ", set)
                if (found !=null) {
                    if (set.number) {
                        console.log(" bringing ", found, " offline ")
                        found.unsafeCast<HTMLElement>().classList.add("offline")
                        found.asDynamic().offline = true
                        canvasGestures.rebuildConnectionGroup()
                    }
                    else {
                        console.log(" bringing ", found, " online ")
                        found.unsafeCast<HTMLElement>().classList.remove("offline")
                        found.asDynamic().offline = false
                        canvasGestures.rebuildConnectionGroup()
                    }
                }
            }
         }
        Handled.yes
    }

    handlers.put("is-running") { message, _ ->
        println(" got is-running message ")
        val isRunning = Json.decodeFromJsonElement<IsRunning>(message.payload!!)
        setBoxRunning(isRunning.uid, isRunning.isRunning, isRunning.watermark, isRunning.strand)

        Handled.yes
    }
    handlers.put("describe-action") { message, _ ->
        val set = Json.decodeFromJsonElement<Describe_actions>(message.payload!!)
        val u = set.uid

        val boxes = document.body!!.getElementsByClassName("box").asList()
        var found = boxes.firstOrNull { it.unsafeCast<dynamic>().uid == u }

        if (found != null) {
            found.unsafeCast<dynamic>().injectedActions = set.value
            console.log(" injected actions are ${set.value}")
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
            console.log("disclosureInterface not ready yet; skipping nested output area init")
            return outputArea
        }
        val inter = di(contain!!)
        val nestedRoot = (inter.upsertDisclosure as (String, String, String) -> HTMLElement).invoke(
            "nested",
            "nested",
            ""
        ) as HTMLElement
        outputArea.style.visibility = "visible"
        outputArea.style.height = "auto"
        return nestedRoot
    }

    fun handleNestedTopology(parent: HTMLElement, nestedIds: List<String>) {
        val previous = (parent.unsafeCast<dynamic>().box_nested as? Array<String>)?.toSet() ?: emptySet()
        val newSet = nestedIds.toSet()
        val toRemove = previous - newSet

        val nestedRoot = ensureNestedOutputArea(parent)

        toRemove.forEach { childId ->
            val child = document.body!!.getElementsByClassName("box").asList()
                .firstOrNull { it.unsafeCast<dynamic>().uid == childId } as? HTMLElement
            if (child != null && child != parent) {
                mainCanvas.appendChild(child)
            }
        }

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

    handlers.put("set-output") { message, _ ->
        val set = Json.decodeFromJsonElement<Set_output>(message.payload!!)
        val u = set.uid

        var found = defaultCreate(u)

        println(" got set-output request for ${u} with ${set.value}")
        when (set.property) {
            "outputarea" -> {
                handleOutputAreaRequest(found as HTMLElement, set)
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

    handlers.put("set-string") { message, _ ->
        val set = Json.decodeFromJsonElement<Set_string>(message.payload!!)
        val u = set.uid

        var found = defaultCreate(u)

        when (set.property) {
            "name" -> {
                console.log(" got set-string name request for ${u} with ${set.value}")
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
            }

            "code_editorState" -> {
                found.unsafeCast<dynamic>().code_editorState = set.value
                notifyPotentialEditedPropertyHasEditorStateRemoteUpdated(u, "code", set.value)
            }

            "code_decorationState" -> {
                found.unsafeCast<dynamic>().decorSpec = set.value
                console.log(set.value)
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
            }

            "task" -> {
                found.unsafeCast<dynamic>().task = set.value
            }

            "mesh" -> {
                found.unsafeCast<dynamic>().mesh = set.value
            }

            "metadata" -> {
                found.unsafeCast<dynamic>().metadata = set.value
            }

            "compute" -> {
                found.unsafeCast<dynamic>().compute = set.value
            }

            "preamble" -> {
                found.unsafeCast<dynamic>().preamble = set.value
            }

            "vertex" -> {
                found.unsafeCast<dynamic>().vertex = set.value
            }

            "customization" -> {
                handleCustomizationRequest(found as HTMLElement, set)
            }

            "layers" -> {
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
                                    console.log("Failed to open nested disclosure", e)
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
                    box.scrollIntoView(js("{ behavior: 'smooth', block: 'center' }"))
                } catch (e: Exception) {
                    console.log("Failed to scroll to box", e)
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
                    console.log("switchToProperty failed", e)
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
                            console.log("Failed to scroll to line", e)
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

        // Set content with optional animation
        if (note.animated) {
            animateSlideNoteText(overlay, note.html)
        } else {
            overlay.innerHTML = note.html
        }
        overlay.style.display = "block"

        overlay.querySelector(".close-btn")?.let { overlay.removeChild(it) }
        if (note.persistent) {
            val closeBtn = document.createElement("button") as HTMLElement
            closeBtn.classList.add("close-btn")
            closeBtn.innerHTML = "×"
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
                if (href != null && href.startsWith("#slideshow:")) {
                    val command = href.substring(11) // Remove "#slideshow:" prefix
                    // Send command to server to control slideshow
                    send("slideshow-command", js("{command: command}"))
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

    val filename = URLSearchParams(document.location?.search).get("file") ?: "default"
    val username = URLSearchParams(document.location?.search).get("user") ?: "everybody"
    val hello = Message("hello").payload(Hello(filename, username))
    onOpen.add(hello)

    val body = document.body ?: error("No body")
    console.log(" well, this is fun 1")
    body.append {
        style {
            unsafe {
                +styles.toString()
            }
        }
    }
    console.log(" well, this is fun 2")
    val mainViewport = body.append {
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

    console.log(" well, this is fun 3")
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
        console.log(" (suppressed right click) ")
        it.preventDefault()
    }

    console.log(" well, this is fun 4")
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
            console.log("-onmousedown d", event.target == mainViewport, event)
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

            send(Message("set-viewport").payload(Set_rect(uid="root", property="viewport", Rect(x.toDouble(),y.toDouble(), window.innerWidth.toDouble(), window.innerHeight.toDouble()))))

            canvasGestures.rebuildConnectionGroup()
            repositionSlideNoteOverlayIfVisible()
        }
        else
            canvasGestures.move(event)
    }


    console.log(" well, this is fun 4")
    mainViewport.onwheel = onwheel@{ event ->

        // todo, should read "has outputArea_content as parent"
        val target = event.target as? HTMLElement
        if (target != null && target.asDynamic().closest(".outputArea") != null) {
            return@onwheel null
        }

        if (event.target is SVGElement) {
        } else {

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

            send(Message("set-viewport").payload(Set_rect(uid="root", property="viewport", Rect(x.toDouble(),y.toDouble(), window.innerWidth.toDouble(), window.innerHeight.toDouble()))))

            canvasGestures.rebuildConnectionGroup()
            repositionSlideNoteOverlayIfVisible()
        }

        null
    }


    mainCanvas.unsafeCast<dynamic>().data_x = 0
    mainCanvas.unsafeCast<dynamic>().data_y = 0

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
                    console.log("Importing metadata.edn file: ${file.name}")
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

    monacoResizeHandler = { event: dynamic ->

        val target: dynamic = event.target
        val x: Number = (target.data_x ?: 0.0) + event.deltaRect.left
        val y: Number = (target.data_y ?: 0.0) + event.deltaRect.top

//        (target as HTMLElement).style.transform = "translate(${x}px, ${y}px)"

        target.style.width = event.rect.width + "px"
        target.style.height = event.rect.height + "px"

        target.data_x = x
        target.data_y = y
        target.data_w = event.rect.width
        target.data_h = event.rect.height
    }

    moncaoResizing.resizable(
        json("edges" to json("top" to true, "bottom" to true, "left" to true, "right" to true))
    ).on("resizemove") { event: dynamic ->
        monacoResizeHandler(event)
    }

    val interactable = interact(".box")

    moveHandler = { event: dynamic ->

        val target: dynamic = event.target
        val x: Number = (target.data_x ?: 0.0) + event.dx
        val y: Number = (target.data_y ?: 0.0) + event.dy

        console.log("two ways ", x, y, originatingMouseDown, event)

        val tdx = (event.clientX - originatingMouseDown.clientX) as Number
        val tdy = (event.clientY - originatingMouseDown.clientY) as Number
        val odx = (target.originalFramePosition as List<Number>)[0].toDouble() + tdx.toDouble()
        val ody = (target.originalFramePosition as List<Number>)[1].toDouble() + tdy.toDouble()
        console.log("two ways => ", odx, ody)

//        (target as HTMLElement).style.transform = "translate(${x}px, ${y}px)" // round trip to server
//        target.data_x = x
//        target.data_y = y

        var t = window.getComputedStyle(event.target).borderWidth.split("px")[0].toDouble() * 0.0

        val uid = target.uid
        send(
            Message("set-rect").payload(
                Set_rect(
                    uid = uid,
                    property = "frame",
                    Rect(odx.toDouble(), ody.toDouble(), event.rect.width - t * 2, event.rect.height - t * 2)
                )
            )
        )
    }

    interactable.on("up") { event ->
        console.log(" interact up ", event)
    }

    interactable.on("dragend") { event ->
        console.log(" interact dragend ", event)
        provideMissingMouseup(event)
    }

    interactable.on("dragstart") { event ->
        selected.copy().forEach {
            it.asDynamic().originalMouseLocation = listOf(event.clientX, event.clientY)
            it.asDynamic().originalFramePosition = listOf(it.asDynamic().data_x, it.asDynamic().data_y)
        }
    }

    interactable.on("down") { event ->
        val originalTarget = event.target as? HTMLElement
        val insideOutputArea = originalTarget?.closest(".outputArea") != null

        val target: dynamic = reorient(event.target)
        if (event.altKey) {
            if (insideOutputArea) {
                return@on
            }

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
                notify("<i>${target.name}</i> stopped", "launch")
                stopBox(target.uid)
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

            if (event.metaKey) {}
            else if (event.shiftKey) {
                if (selected.contains(event.target)) deselectBox(target)
                else selectBox(target)
            } else {
                selected.copy().forEach {
                    deselectBox(it)
                }
                selectBox(target)
            }
        }
    }

    interactable.draggable(json("onmove" to { event: dynamic ->
        console.log(" originating mousedown is ", originatingMouseDown.altKey)

        if (event.altKey) {
        } else if (originatingMouseDown.altKey) {
        } else {
            var targeted = false
            selected.forEach {
                event.target = reorient(it)
                if (event.target) {
                    moveHandler(event)
                    targeted = true
                }
            }
            if (targeted)
                canvasGestures.rebuildConnectionGroup()
        }
    }))



    resizeHandler = { event: dynamic ->

        val target: dynamic = event.target

        console.log(" inside resize handler ", event.deltaRect, event.delta, target)
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


        target.data_x = x
        target.data_y = y
        target.data_w = w
        target.data_h = h

//        target.style.width = w + "px"
//        target.style.height = h + "px"

        console.log(" -> ${Rect(target.data_x, target.data_y, target.data_w, target.data_h)}")
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

        console.log(" RESIZING, original target", event)

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
    }

    interact(".doubleClickToEdit").on("doubletap") { event ->
        console.log(" hello ? anybody? ", event)
        val t = event.target.unsafeCast<HTMLElement>()
        val box = t.parentElement!!.parentElement

        if (t.contentEditable != "true") {
            t.contentEditable = "true"

            fun fireChange() {
                box.unsafeCast<dynamic>().name = t.textContent
                val uid = box.unsafeCast<dynamic>().uid as String
                console.log(" sending set-string for name ${t.textContent} because the contenteditable has changed")
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

    console.log(" printing client ?? ")
    console.log(client)

    registerMouseBug()

    window.addEventListener("resize", {
        canvasGestures.rebuildConnectionGroup()
        repositionSlideNoteOverlayIfVisible()
    })
    window.addEventListener("scroll", {
        repositionSlideNoteOverlayIfVisible()
    })
}


fun stopBox(uid: String) {
    console.log(" sending stop-box message")
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

    oa = d1.getElementsByClassName("outputArea").asList()[0] as HTMLElement

    if (sendInitialSync) {
        console.log(" sending initial sync for box ${name}")
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
