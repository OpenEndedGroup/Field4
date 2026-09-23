
import afield.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlin.math.max
import kotlin.math.min
import org.w3c.dom.*

external fun disclosureInterface(container: Element): dynamic
external fun aiChatUpdate(container: Element, uid: String, markdown: String)

private const val PRESERVE_DISCLOSURE_CONTENT = "__AFIELD_PRESERVE_DISCLOSURE_CONTENT__"

fun insertHTMLWithScripts(container: Element, htmlString: String, isModule : Boolean = false) {
    // Create a document fragment from the HTML string
    val range = document.createRange()
    val fragment = range.createContextualFragment(htmlString)

    // Get all <script> elements in the fragment
    val scripts: NodeList = fragment.querySelectorAll("script")

    // Loop through each <script> and replace it with a new one to trigger execution
    for (i in 0 until scripts.length) {
        val oldScript = scripts.item(i) as? Element ?: continue
        val newScript = document.createElement("script")

        if (isModule) newScript.setAttribute("type", "module")
        // Copy over all attributes (e.g., src, type, etc.)
        val attributes = oldScript.attributes
        for (j in 0 until attributes.length) {
            val attr = attributes.item(j)
            if (attr != null) {
                newScript.setAttribute(attr.nodeName, attr.nodeValue ?: "")
            }
        }

        // Copy the script content
        newScript.textContent = oldScript.textContent

        // Replace the old script with the new one in the fragment
        oldScript.parentNode?.replaceChild(newScript, oldScript)
    }

    // Insert the fragment into the container at the desired location
    container.appendChild(fragment)
}

private data class LinesBounds(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
)

private const val MIN_FITTED_STROKE_OPACITY = 0.18

private fun parsePositiveDouble(raw: String?): Double? {
    val candidate = raw?.trim()?.removeSuffix("px")?.takeIf { it.isNotEmpty() } ?: return null
    return candidate.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
}

private fun parseFiniteDouble(raw: String?): Double? {
    val candidate = raw?.trim()?.removeSuffix("px")?.takeIf { it.isNotEmpty() } ?: return null
    return candidate.toDoubleOrNull()?.takeIf { it.isFinite() }
}

private fun parseSvgViewBox(raw: String?): LinesBounds? {
    val parts = raw
        ?.trim()
        ?.split(Regex("[\\s,]+"))
        ?.mapNotNull { it.toDoubleOrNull() }
        ?: return null
    if (parts.size < 4) return null
    val width = parts[2]
    val height = parts[3]
    if (!width.isFinite() || !height.isFinite() || width <= 0.0 || height <= 0.0) return null
    return LinesBounds(parts[0], parts[1], width, height)
}

private fun parseLinesWrapperBounds(wrapper: HTMLElement): LinesBounds? {
    val x = parseFiniteDouble(wrapper.getAttribute("data-afield-bounds-x")) ?: return null
    val y = parseFiniteDouble(wrapper.getAttribute("data-afield-bounds-y")) ?: return null
    val width = parseFiniteDouble(wrapper.getAttribute("data-afield-bounds-w")) ?: return null
    val height = parseFiniteDouble(wrapper.getAttribute("data-afield-bounds-h")) ?: return null
    if (width < 0.0 || height < 0.0) return null
    return LinesBounds(x, y, width, height)
}

/**
 * The title, caption and card styling a plane figure asks the viewer to draw around its picture.
 *
 * `afield.stdlib.planeFigureHtml` owns this look — the strings here are its own, carried over on the
 * wrapper rather than re-typed on this side, so the live figure and the still card cannot drift
 * apart. A plain `Box.lines` output carries none of these attributes and gets [chrome] `null`, which
 * is the bare viewport this file has always drawn.
 */
private data class LinesFigureChrome(
    val title: String?,
    val caption: String?,
    val cardStyle: String,
    val titleStyle: String,
    val captionStyle: String,
    val width: Double,
    val height: Double,
)

private fun parseLinesFigureChrome(wrapper: HTMLElement): LinesFigureChrome? {
    val width = parsePositiveDouble(wrapper.getAttribute("data-afield-figure-width")) ?: return null
    val height = parsePositiveDouble(wrapper.getAttribute("data-afield-figure-height")) ?: return null
    return LinesFigureChrome(
        title = wrapper.getAttribute("data-afield-figure-title")?.takeIf { it.isNotBlank() },
        caption = wrapper.getAttribute("data-afield-figure-caption")?.takeIf { it.isNotBlank() },
        cardStyle = wrapper.getAttribute("data-afield-figure-card-style") ?: "",
        titleStyle = wrapper.getAttribute("data-afield-figure-title-style") ?: "",
        captionStyle = wrapper.getAttribute("data-afield-figure-caption-style") ?: "",
        width = width,
        height = height,
    )
}

private fun usableLinesBounds(bounds: LinesBounds?): LinesBounds? {
    bounds ?: return null
    if (!bounds.x.isFinite() || !bounds.y.isFinite() || !bounds.width.isFinite() || !bounds.height.isFinite()) return null
    if (bounds.width < 0.0 || bounds.height < 0.0) return null
    if (bounds.width <= 0.0 && bounds.height <= 0.0) {
        return LinesBounds(bounds.x - 0.5, bounds.y - 0.5, 1.0, 1.0)
    }
    if (bounds.width <= 0.0) {
        return LinesBounds(bounds.x - 0.5, bounds.y, 1.0, bounds.height)
    }
    if (bounds.height <= 0.0) {
        return LinesBounds(bounds.x, bounds.y - 0.5, bounds.width, 1.0)
    }
    return bounds
}

private fun parseLinesWrapper(htmlString: String): HTMLElement? {
    val range = document.createRange()
    val fragment = range.createContextualFragment(htmlString)
    return fragment.querySelector(".afield-lines-output") as? HTMLElement
}

private data class ThreeIframeRequest(
    val src: String,
    val height: String?
)

private fun parseThreeIframeRequest(htmlString: String): ThreeIframeRequest? {
    val range = document.createRange()
    val fragment = range.createContextualFragment(htmlString)
    val iframe = fragment.querySelector("iframe") as? HTMLIFrameElement ?: return null
    val rawSrc = iframe.getAttribute("src")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val anchor = document.createElement("a") as HTMLAnchorElement
    anchor.href = rawSrc
    if (anchor.pathname != "/threejs") return null
    val height = parsePositiveDouble(iframe.style.height)
        ?.let { "${it}px" }
        ?: parsePositiveDouble(iframe.getAttribute("height"))?.let { "${it}px" }
    return ThreeIframeRequest(rawSrc, height)
}

private fun isPersistentThreeOutput(htmlString: String): Boolean =
    parseThreeIframeRequest(htmlString) != null

private fun updatePersistentThreeOutput(contentRoot: HTMLElement, htmlString: String): Boolean {
    val request = parseThreeIframeRequest(htmlString) ?: return false
    val existing = contentRoot.asDynamic().__afieldThreeIframe as? HTMLIFrameElement
    val iframe = if (existing != null) {
        if (existing.parentNode == null) contentRoot.appendChild(existing)
        existing
    } else {
        val created = document.createElement("iframe") as HTMLIFrameElement
        created.style.apply {
            border = "none"
            padding = "0"
            margin = "0"
            width = "100%"
            display = "block"
            background = "#000000"
        }
        contentRoot.innerHTML = ""
        contentRoot.appendChild(created)
        contentRoot.asDynamic().__afieldThreeIframe = created
        created
    }

    if (iframe.getAttribute("src") != request.src) {
        iframe.setAttribute("src", request.src)
    }
    iframe.style.height = request.height ?: iframe.style.height.takeIf { it.isNotBlank() } ?: "420px"
    contentRoot.asDynamic().dataset.afieldAutoSizeMode = "fixed"
    contentRoot.style.padding = "0px"
    contentRoot.style.background = "#000000"
    return true
}

private fun ensureLinesOutputView(contentRoot: HTMLElement): dynamic {
    val existing = contentRoot.asDynamic().__afieldLinesView
    if (existing != null) {
        val root = existing.root as? HTMLElement
        if (root != null && root.parentNode == null) contentRoot.appendChild(root)
        return existing
    }

    val root = document.createElement("div") as HTMLElement
    root.className = "afield-lines-output-shell"
    root.style.apply {
        position = "relative"
        width = "100%"
        height = "100%"
        minWidth = "0"
        minHeight = "0"
    }

    // Chrome for a plane figure: created empty for every viewer and left hidden unless an output
    // asks for it, because one viewer is reused for every output on the channel — a box that draws
    // `lines(...)` and then `showPlane(...)` into the same channel gets the same shell both times,
    // and rebuilding it per output would mean re-binding the pointer handlers and starting a second
    // `watchViewportSize` loop on every frame of an animation.
    val header = document.createElement("div") as HTMLElement
    header.className = "afield-lines-output-header"
    header.style.display = "none"

    val figureTitle = document.createElement("div") as HTMLElement
    figureTitle.className = "afield-lines-output-title"
    figureTitle.style.display = "none"

    val figureCaption = document.createElement("div") as HTMLElement
    figureCaption.className = "afield-lines-output-caption"
    figureCaption.style.display = "none"

    header.appendChild(figureTitle)
    header.appendChild(figureCaption)
    root.appendChild(header)

    val viewport = document.createElement("div") as HTMLElement
    viewport.className = "afield-lines-output-viewport"
    viewport.style.apply {
        position = "relative"
        setProperty("overflow", "hidden")
        width = "100%"
        height = "100%"
        minWidth = "0"
        minHeight = "0"
        border = "1px solid rgba(255,255,255,0.07)"
        background = "rgba(0,0,0,0.16)"
        boxSizing = "border-box"
        setProperty("touch-action", "none")
        cursor = "grab"
    }

    val stage = document.createElementNS("http://www.w3.org/2000/svg", "svg")
    stage.setAttribute("width", "100%")
    stage.setAttribute("height", "100%")
    stage.setAttribute("preserveAspectRatio", "none")
    stage.setAttribute("shape-rendering", "geometricPrecision")
    stage.setAttribute("text-rendering", "geometricPrecision")
    stage.setAttribute("class", "nested-svg-stage")

    val stageGroup = document.createElementNS("http://www.w3.org/2000/svg", "g")
    stageGroup.setAttribute("vector-effect", "non-scaling-stroke")
    stage.appendChild(stageGroup)
    viewport.appendChild(stage)
    root.appendChild(viewport)
    contentRoot.appendChild(root)

    val view = js("({})")
    view.root = root
    view.viewport = viewport
    view.stage = stage
    view.stageGroup = stageGroup
    view.scale = 1.0
    view.tx = 0.0
    view.ty = 0.0
    view.manual = false
    view.contentBounds = null
    view.viewportWidth = 1.0
    view.viewportHeight = 1.0
    view.panPointerId = null
    view.panLastX = 0.0
    view.panLastY = 0.0
    view.sourceWidth = 1.0
    view.sourceHeight = 1.0
    view.lastMeasuredWidth = -1.0
    view.lastMeasuredHeight = -1.0
    view.lastStrokeFadeScale = null
    view.header = header
    view.figureTitle = figureTitle
    view.figureCaption = figureCaption
    view.figureChrome = null
    // Null means "derive the margin from the viewport", which is what a bare `lines` output wants.
    // A plane figure has already fitted its geometry into a padded frame and hands that frame over
    // as its bounds, so a second margin here would shrink the picture away from the still.
    view.fitPad = null

    fun updateTransform() {
        val scale = (view.scale as Double)
        val tx = (view.tx as Double)
        val ty = (view.ty as Double)
        stageGroup.setAttribute("transform", "matrix($scale 0 0 $scale $tx $ty)")
        val previousStrokeFadeScale = (view.lastStrokeFadeScale as? Number)?.toDouble()
        if (previousStrokeFadeScale != scale) {
            applySubpixelStrokeFade(stageGroup, scale)
            view.lastStrokeFadeScale = scale
        }
    }

    fun updateViewportMetrics() {
        val measuredWidth = viewport.clientWidth.toDouble()
        val measuredHeight = viewport.clientHeight.toDouble()
        val fallbackWidth = view.sourceWidth as Double
        val fallbackHeight = view.sourceHeight as Double
        view.viewportWidth = max(1.0, if (measuredWidth > 0.0) measuredWidth else fallbackWidth)
        view.viewportHeight = max(1.0, if (measuredHeight > 0.0) measuredHeight else fallbackHeight)
        stage.setAttribute("viewBox", "0 0 ${view.viewportWidth} ${view.viewportHeight}")
    }

    fun fitToContent() {
        val bounds = view.contentBounds as? LinesBounds ?: return
        updateViewportMetrics()
        val viewportWidth = max(1.0, view.viewportWidth as Double)
        val viewportHeight = max(1.0, view.viewportHeight as Double)
        val pad = (view.fitPad as? Number)?.toDouble()
            ?: max(12.0, min(viewportWidth, viewportHeight) * 0.06)
        val availableWidth = max(1.0, viewportWidth - pad * 2.0)
        val availableHeight = max(1.0, viewportHeight - pad * 2.0)
        val scale = min(
            availableWidth / max(bounds.width, 1e-6),
            availableHeight / max(bounds.height, 1e-6)
        ).coerceAtLeast(1e-6)
        view.scale = scale
        view.tx = (viewportWidth - bounds.width * scale) * 0.5 - bounds.x * scale
        view.ty = (viewportHeight - bounds.height * scale) * 0.5 - bounds.y * scale
        updateTransform()
    }

    fun endPan() {
        view.panPointerId = null
        viewport.style.cursor = "grab"
    }

    viewport.onwheel = { event ->
        event.preventDefault()
        val rect = viewport.getBoundingClientRect()
        val px = event.clientX - rect.left
        val py = event.clientY - rect.top
        val currentScale = view.scale as Double
        val factor = if (event.deltaY < 0) 1.12 else 1.0 / 1.12
        val nextScale = (currentScale * factor).coerceIn(1e-4, 1e4)
        if (nextScale != currentScale) {
            val tx = view.tx as Double
            val ty = view.ty as Double
            val worldX = (px - tx) / currentScale
            val worldY = (py - ty) / currentScale
            view.scale = nextScale
            view.tx = px - worldX * nextScale
            view.ty = py - worldY * nextScale
            view.manual = true
            updateTransform()
        }
        null
    }

    viewport.onpointerdown = { event ->
        if (event.button.toInt() != 0 && event.button.toInt() != 1) {
            null
        } else {
        view.panPointerId = event.pointerId
        view.panLastX = event.clientX
        view.panLastY = event.clientY
        view.manual = true
        viewport.style.cursor = "grabbing"
        viewport.setPointerCapture(event.pointerId)
        event.preventDefault()
        null
        }
    }

    viewport.onpointermove = { event ->
        if ((view.panPointerId as? Number)?.toInt() != event.pointerId) {
            null
        } else {
            val dx = event.clientX - (view.panLastX as Double)
            val dy = event.clientY - (view.panLastY as Double)
            view.panLastX = event.clientX
            view.panLastY = event.clientY
            view.tx = (view.tx as Double) + dx
            view.ty = (view.ty as Double) + dy
            updateTransform()
            null
        }
    }

    viewport.onpointerup = { event ->
        if ((view.panPointerId as? Number)?.toInt() == event.pointerId) endPan()
        null
    }

    viewport.onpointercancel = {
        endPan()
        null
    }

    viewport.ondblclick = {
        view.manual = false
        fitToContent()
        null
    }

    fun watchViewportSize() {
        if (root.parentNode == null) return
        val width = viewport.clientWidth.toDouble()
        val height = viewport.clientHeight.toDouble()
        if (width != (view.lastMeasuredWidth as Double) || height != (view.lastMeasuredHeight as Double)) {
            view.lastMeasuredWidth = width
            view.lastMeasuredHeight = height
            updateViewportMetrics()
            if (!(view.manual as Boolean)) {
                fitToContent()
            } else {
                updateTransform()
            }
        }
        window.requestAnimationFrame {
            watchViewportSize()
        }
    }
    watchViewportSize()

    view.updateViewportMetrics = { updateViewportMetrics() }
    view.updateTransform = { updateTransform() }
    view.fitToContent = { fitToContent() }
    contentRoot.asDynamic().__afieldLinesView = view
    return view
}

/**
 * Dress the viewer as a plane-figure card, or strip it back to the bare `lines` viewport.
 *
 * Called on every output rather than once at construction, because the viewer is per channel and the
 * outputs on a channel need not agree: `showPlane` then `lines` on the same channel must not leave a
 * card frame around the second one. Only text and style attributes move — never the elements
 * themselves — so an animation calling `showPlane` every frame keeps its pointer handlers, its
 * pan/zoom state and its single resize watcher.
 *
 * The two sizing modes are the substance of it. A bare `lines` output fills the disclosure, which the
 * user resizes ("fixed", both axes 100%). A figure is a fixed-size picture like the still it mirrors,
 * so it asks for content auto-sizing and pins the viewport to the figure's own pixels — which also
 * makes the viewer's initial fit the identity transform, and the first frame the still exactly.
 */
private fun applyLinesFigureChrome(contentRoot: HTMLElement, view: dynamic, chrome: LinesFigureChrome?) {
    val root = view.root as HTMLElement
    val viewport = view.viewport as HTMLElement
    val header = view.header as HTMLElement
    val figureTitle = view.figureTitle as HTMLElement
    val figureCaption = view.figureCaption as HTMLElement

    if (chrome == null) {
        if (view.figureChrome != null) {
            root.removeAttribute("style")
            root.style.apply {
                position = "relative"
                width = "100%"
                height = "100%"
                minWidth = "0"
                minHeight = "0"
            }
            header.style.display = "none"
            viewport.style.apply {
                width = "100%"
                height = "100%"
                border = "1px solid rgba(255,255,255,0.07)"
                background = "rgba(0,0,0,0.16)"
            }
            view.fitPad = null
            view.figureChrome = null
        }
        contentRoot.asDynamic().dataset.afieldAutoSizeMode = "fixed"
        contentRoot.style.padding = "0px"
        return
    }

    // Typed rather than left dynamic, so the comparisons below are Kotlin's and not JavaScript's.
    val previous: LinesFigureChrome? = view.figureChrome as? LinesFigureChrome
    if (previous == null ||
        previous.cardStyle != chrome.cardStyle ||
        previous.width != chrome.width ||
        previous.height != chrome.height
    ) {
        root.setAttribute("style", chrome.cardStyle)
        root.style.apply {
            position = "relative"
            width = "fit-content"
            height = "fit-content"
        }
        viewport.style.apply {
            width = "${chrome.width}px"
            height = "${chrome.height}px"
            // The card supplies the frame and the backdrop; a second border here would double it.
            border = "none"
            background = "none"
        }
    }

    header.style.display = "block"
    if (previous?.titleStyle != chrome.titleStyle) figureTitle.setAttribute("style", chrome.titleStyle)
    if (previous?.captionStyle != chrome.captionStyle) figureCaption.setAttribute("style", chrome.captionStyle)
    if (previous?.title != chrome.title) {
        figureTitle.textContent = chrome.title ?: ""
        figureTitle.style.display = if (chrome.title == null) "none" else "block"
    }
    if (previous?.caption != chrome.caption) {
        figureCaption.textContent = chrome.caption ?: ""
        figureCaption.style.display = if (chrome.caption == null) "none" else "block"
    }

    view.fitPad = 0.0
    view.figureChrome = chrome
    contentRoot.asDynamic().dataset.afieldAutoSizeMode = "content"
    contentRoot.style.padding = "0px"
}

private fun applyNonScalingStroke(root: Element) {
    root.setAttribute("vector-effect", "non-scaling-stroke")
    forEachStrokeFadeElement(root) { element ->
        element.setAttribute("vector-effect", "non-scaling-stroke")
        element.setAttribute("data-afield-base-stroke-width", parsePositiveDouble(element.getAttribute("stroke-width"))?.toString() ?: "1")
        element.getAttribute("stroke-opacity")?.let {
            element.setAttribute("data-afield-base-stroke-opacity", it)
        }
    }
}

private fun forEachStrokeFadeElement(root: Element, block: (Element) -> Unit) {
    if (root.isStrokeFadeElement()) block(root)
    val strokedElements = root.querySelectorAll("path,circle,line,polyline,polygon,rect,ellipse")
    for (index in 0 until strokedElements.length) {
        (strokedElements.item(index) as? Element)?.let(block)
    }
}

private fun Element.isStrokeFadeElement(): Boolean {
    val tag = tagName.lowercase()
    return tag == "path" ||
        tag == "circle" ||
        tag == "line" ||
        tag == "polyline" ||
        tag == "polygon" ||
        tag == "rect" ||
        tag == "ellipse"
}

private fun strokeOpacityValue(raw: String?): Double {
    return raw?.toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 1.0
}

private fun applySubpixelStrokeFade(root: Element, scale: Double) {
    if (!scale.isFinite()) return
    forEachStrokeFadeElement(root) { element ->
        val stroke = element.getAttribute("stroke")?.trim()
        if (stroke == null || stroke == "none") return@forEachStrokeFadeElement

        val baseWidth = parsePositiveDouble(element.getAttribute("data-afield-base-stroke-width")) ?: 1.0
        val baseOpacityRaw = element.getAttribute("data-afield-base-stroke-opacity")
        val baseOpacity = strokeOpacityValue(baseOpacityRaw)
        val effectiveWidth = baseWidth * scale
        if (effectiveWidth < 1.0) {
            element.setAttribute(
                "stroke-opacity",
                (baseOpacity * effectiveWidth.coerceIn(MIN_FITTED_STROKE_OPACITY, 1.0)).toString()
            )
        } else if (baseOpacityRaw != null) {
            element.setAttribute("stroke-opacity", baseOpacityRaw)
        } else {
            element.removeAttribute("stroke-opacity")
        }
    }
}

private fun updatePersistentLinesOutput(contentRoot: HTMLElement, htmlString: String): Boolean {
    val wrapper = parseLinesWrapper(htmlString) ?: return false
    val sourceSvg = wrapper.querySelector("svg") ?: return false
    val view = ensureLinesOutputView(contentRoot)
    applyLinesFigureChrome(contentRoot, view, parseLinesFigureChrome(wrapper))
    val stageGroup = view.stageGroup as Element

    while (stageGroup.firstChild != null) {
        stageGroup.removeChild(stageGroup.firstChild!!)
    }
    val sourceChildren = sourceSvg.childNodes
    for (index in 0 until sourceChildren.length) {
        val child = sourceChildren.item(index) ?: continue
        val cloned = child.cloneNode(true)
        (cloned as? Element)?.let { applyNonScalingStroke(it) }
        stageGroup.appendChild(cloned)
    }

    val wrapperBounds = usableLinesBounds(parseLinesWrapperBounds(wrapper))
    val sourceViewBox = usableLinesBounds(parseSvgViewBox(sourceSvg.getAttribute("viewBox")))
    stageGroup.removeAttribute("transform")
    val groupBox = runCatching {
        val box = stageGroup.asDynamic().getBBox()
        if (box != null) LinesBounds(
            x = (box.x as Number).toDouble(),
            y = (box.y as Number).toDouble(),
            width = (box.width as Number).toDouble(),
            height = (box.height as Number).toDouble()
        ) else null
    }.getOrNull().let { usableLinesBounds(it) }
    val fallbackWidth = parsePositiveDouble(sourceSvg.getAttribute("width")) ?: sourceViewBox?.width ?: groupBox?.width ?: 1.0
    val fallbackHeight = parsePositiveDouble(sourceSvg.getAttribute("height")) ?: sourceViewBox?.height ?: groupBox?.height ?: 1.0
    val contentBounds = wrapperBounds
        ?: groupBox
        ?: sourceViewBox
        ?: LinesBounds(0.0, 0.0, fallbackWidth, fallbackHeight)

    val sourceWidth = max(1.0, parsePositiveDouble(sourceSvg.getAttribute("width")) ?: contentBounds.width)
    val sourceHeight = max(1.0, parsePositiveDouble(sourceSvg.getAttribute("height")) ?: contentBounds.height)

    view.sourceWidth = sourceWidth
    view.sourceHeight = sourceHeight
    view.contentBounds = contentBounds
    view.updateViewportMetrics()

    if (!(view.manual as Boolean)) {
        view.fitToContent()
    } else {
        view.updateTransform()
    }

    return true
}

fun renderLayersPalette(contentRoot: HTMLElement) {
    contentRoot.innerHTML = ""
    contentRoot.classList.add("layers-palette")
    val animateDuration = 450.0
    contentRoot.style.apply {
        display = "flex"
        flexDirection = "column"
        width = "50%"
        setProperty("gap", "8px")
        padding = "8px"
        backgroundColor = "#1c1c1c"
        borderRadius = "6px"
        color = "#f0f0f0"
        fontSize = "13px"
        fontWeight = "100"
    }

    val controls = document.createElement("div") as HTMLElement
    controls.style.apply {
        display = "flex"
        justifyContent = "flex-end"
        setProperty("gap", "4px")
        alignItems = "center"
        setProperty("padding-right", "4px")
    }

    val list = document.createElement("div") as HTMLElement
    list.classList.add("layers-list")
    list.style.apply {
        display = "flex"
        flexDirection = "column"
        setProperty("gap", "4px")
    }

    fun button(label: String, handler: () -> Unit): HTMLButtonElement {
        val btn = document.createElement("button") as HTMLButtonElement
        btn.textContent = label
        btn.style.apply {
            padding = "4px 8px"
            backgroundColor = "#2c2c2c"
            color = "#f0f0f0"
            border = "1px solid rgba(255,255,255,0.08)"
            borderRadius = "4px"
            cursor = "pointer"
        }
        btn.onclick = {
            handler()
            null
        }
        return btn
    }

    fun applyLayerState(nextVisible: Set<String>, desiredActive: String?) {
        if (nextVisible.isEmpty()) return
        val normalizedVisible = nextVisible.filter { it.isNotBlank() }.toMutableSet()
        if (normalizedVisible.isEmpty()) normalizedVisible.add("default")

        val activeCandidate = when {
            desiredActive != null && normalizedVisible.contains(desiredActive) -> desiredActive
            desiredActive != null -> normalizedVisible.firstOrNull()
            activeLayer == null -> null
            normalizedVisible.contains(activeLayer!!) -> activeLayer
            else -> normalizedVisible.firstOrNull()
        }
        layerDebugLog(
            "palette applyLayerState nextVisible=${normalizedVisible.joinToString(",")} " +
                "desiredActive=${desiredActive ?: "null"} activeCandidate=${activeCandidate ?: "null"} " +
                "prevVisible=${visibleLayers.joinToString(",")} prevActive=${activeLayer ?: "null"}"
        )

        setVisibleLayers(normalizedVisible.toSet(), animated = true, duration = animateDuration)
        if (activeCandidate != activeLayer) {
            setActiveLayer(activeCandidate, animated = true, duration = animateDuration)
        }
    }

    fun renderList() {
        val allLayers = getAllLayers().toList().sorted()
        val available = allLayers.toSet()
        val visibleNow = visibleLayers.filter { available.contains(it) }.toMutableSet().ifEmpty {
            if (allLayers.isNotEmpty()) mutableSetOf(allLayers.first()) else mutableSetOf("default")
        }
        val activeNow = activeLayer?.takeIf { visibleNow.contains(it) }

        list.innerHTML = ""
        if (allLayers.isEmpty()) {
            val empty = document.createElement("div") as HTMLElement
            empty.textContent = "No layers found"
            empty.style.opacity = "0.7"
            list.appendChild(empty)
            return
        }

        allLayers.forEach { layerName ->
            val row = document.createElement("div") as HTMLElement
            row.style.apply {
                display = "grid"
                setProperty("grid-template-columns", "1fr auto auto")
                setProperty("gap", "6px")
                alignItems = "center"
                padding = "4px 6px"
                setProperty("border", "none")
                setProperty("border-bottom", "1px solid rgba(255,255,255,0.03)")
                backgroundColor = if (layerName == activeNow) "#22272d" else "transparent"
            }

            val name = document.createElement("div") as HTMLElement
            name.textContent = layerName
            name.style.apply {
                setProperty("overflow", "hidden")
                setProperty("text-overflow", "ellipsis")
                setProperty("white-space", "nowrap")
                fontWeight = if (layerName == activeNow) "400" else "100"
            }

            val eye = document.createElement("button") as HTMLButtonElement
            eye.innerHTML = """
                <svg width="18" height="12" viewBox="0 0 18 12" xmlns="http://www.w3.org/2000/svg">
                    <path d="M1 6c1.8-3 4.2-4.5 8-4.5s6.2 1.5 8 4.5c-1.8 3-4.2 4.5-8 4.5S2.8 9 1 6Z" fill="none" stroke="currentColor" stroke-width="1.3"/>
                    <circle cx="9" cy="6" r="2.3" fill="currentColor"/>
                </svg>
            """.trimIndent()
            fun updateEye(isVisible: Boolean) {
                eye.style.opacity = if (isVisible) "1" else "0.25"
            }
            eye.title = "Toggle visibility (shift+click to solo)"
            eye.style.apply {
                background = "none"
                border = "none"
                color = "#f0f0f0"
                padding = "2px"
                cursor = "pointer"
                lineHeight = "1"
            }
            updateEye(visibleNow.contains(layerName))
            eye.onclick = { event ->
                val shiftSolo = (event.asDynamic().shiftKey as? Boolean) == true
                val nextVisible = if (shiftSolo) {
                    mutableSetOf(layerName)
                } else {
                    val next = visibleLayers.toMutableSet()
                    if (visibleNow.contains(layerName)) next.remove(layerName) else next.add(layerName)
                    next
                }
                if (nextVisible.isEmpty()) {
                    updateEye(true)
                } else {
                    val nextActive = when {
                        activeLayer == null -> null
                        nextVisible.contains(activeLayer!!) -> activeLayer
                        else -> nextVisible.firstOrNull()
                    }
                    applyLayerState(nextVisible, nextActive)
                    renderList()
                }
                null
            }

            val activeToggle = document.createElement("input") as HTMLInputElement
            activeToggle.type = "radio"
            activeToggle.name = "active-layer"
            activeToggle.title = "Set active layer"
            activeToggle.checked = layerName == activeNow
            activeToggle.style.apply {
                setProperty("transform", "translateY(-3px)")
            }
            activeToggle.onchange = {
                if (activeToggle.checked) {
                    val nextVisible = visibleLayers.toMutableSet()
                    nextVisible.add(layerName)
                    applyLayerState(nextVisible, layerName)
                    renderList()
                }
                null
            }

            row.append(name, eye, activeToggle)
            list.appendChild(row)
        }
    }

    val refreshButton = document.createElement("button") as HTMLButtonElement
    refreshButton.innerHTML = """
        <svg width="16" height="16" viewBox="0 0 16 16" xmlns="http://www.w3.org/2000/svg">
            <path d="M3.2 6.4a4.6 4.6 0 0 1 7.4-1.6h-1v1.5l3.2-2.2-3.2-2.2v1.4A6.2 6.2 0 0 0 2 6.5c0 3.4 2.7 6.1 6.1 6.1 1.6 0 3-.6 4.1-1.7l-1.1-1.1A4.6 4.6 0 0 1 2 6.5l1.2-.1Z" fill="currentColor"/>
        </svg>
    """.trimIndent()
    refreshButton.title = "Refresh layers"
    refreshButton.style.apply {
        background = "none"
        border = "none"
        color = "#f0f0f0"
        padding = "4px"
        cursor = "pointer"
        lineHeight = "1"
    }
    refreshButton.onclick = {
        renderList()
        null
    }

    controls.append(refreshButton)
    contentRoot.append(controls, list)
    renderList()
}

fun handleOutputAreaRequest(box: HTMLElement, set: Set_output) {

    val outputArea = box.getElementsByClassName("outputArea").asList()[0] as HTMLElement
    outputArea.style.visibility = "visible"

    val contain = outputArea.querySelector(".disclosure-group")
    val di = js("window.disclosureInterface")
    if (di == null) {
        console.info("[output] disclosureInterface not ready yet; skipping channel ${set.channel}")
        return
    }
    val inter = di(contain!!)

    val channelId = set.channel
    val title = if (set.channel == "ai-chat") "AI Chat" else set.channel
    val preserveExistingContent = set.channel == "ai-chat" || isPersistentThreeOutput(set.value)
    val initialContent = if (preserveExistingContent) PRESERVE_DISCLOSURE_CONTENT else ""
    val contentRoot = (inter.upsertDisclosure as (String, String, String) -> HTMLElement).invoke(
        channelId,
        title,
        initialContent
    )
    val isLayersChannel = set.channel.equals("layers", ignoreCase = true)

    if (set.channel == "ai-chat") {
        contentRoot.classList.add("ai-chat-content")
        aiChatUpdate(contentRoot, set.uid, set.value)
        (inter.open as (String) -> Unit).invoke(channelId)
    } else if (isLayersChannel) {
        renderLayersPalette(contentRoot)
        (inter.open as (String) -> Unit).invoke(channelId)
    } else if (updatePersistentThreeOutput(contentRoot, set.value)) {
        (inter.open as (String) -> Unit).invoke(channelId)
    } else if (updatePersistentLinesOutput(contentRoot, set.value)) {
        (inter.open as (String) -> Unit).invoke(channelId)
    } else {
        insertHTMLWithScripts(contentRoot, set.value)
    }

}

private fun disclosureExists(container: Element, channelId: String): Boolean {
    val disclosures = container.querySelectorAll(".disclosure").asList()
    return disclosures.any { (it.asDynamic().__disclosureId as? String) == channelId }
}

fun handleOutputAreaControl(box: HTMLElement, control: OutputControl) {
    val outputArea = box.getElementsByClassName("outputArea").asList().firstOrNull() as? HTMLElement ?: return
    val contain = outputArea.querySelector(".disclosure-group") ?: return
    val di = js("window.disclosureInterface")
    if (di == null) {
        console.info("[output] disclosureInterface not ready yet; skipping control ${control.action}")
        return
    }
    val inter = di(contain)
    val channelId = control.channel?.trim()?.takeIf { it.isNotEmpty() }
    val action = control.action.trim().lowercase()

    when (action) {
        "open" -> {
            if (channelId == null) return
            outputArea.style.visibility = "visible"
            outputArea.style.height = "auto"
            if (!disclosureExists(contain, channelId)) {
                val title = if (channelId == "ai-chat") "AI Chat" else channelId
                (inter.upsertDisclosure as (String, String, String) -> Element).invoke(channelId, title, "")
            }
            (inter.open as (String) -> Unit).invoke(channelId)
        }
        "close" -> {
            if (channelId == null) return
            (inter.close as (String) -> Unit).invoke(channelId)
        }
        "clear" -> {
            if (channelId == null || channelId == "nested") return
            (inter.remove as (String) -> Unit).invoke(channelId)
        }
        "clear-all" -> {
            val ids = contain.querySelectorAll(".disclosure").asList().mapNotNull {
                it.asDynamic().__disclosureId as? String
            }
            ids.forEach { id ->
                if (id != "nested") {
                    (inter.remove as (String) -> Unit).invoke(id)
                }
            }
        }
        else -> console.info("[output] unknown output-control action ${control.action}")
    }
}

/**
 * Empty each ordinary output channel without removing its disclosure shell.
 *
 * Frame-synchronous docs capture measures an already-open output disclosure, then
 * relaunches the box from a clean presentation boundary. Removing the disclosure
 * here would make the next generic `set-output` recreate it closed, leaving the
 * previously measured movie clip full of background. Keeping the shell preserves
 * its open state and measured geometry while ensuring that no stale frame content
 * leaks into the relaunched capture.
 */
fun clearOutputAreaContents(box: HTMLElement) {
    val outputArea = box.getElementsByClassName("outputArea").asList().firstOrNull() as? HTMLElement ?: return
    val contain = outputArea.querySelector(".disclosure-group") ?: return
    val di = js("window.disclosureInterface")
    if (di == null || di == undefined) return
    val inter = di(contain)
    val ids = contain.querySelectorAll(".disclosure").asList().mapNotNull {
        it.asDynamic().__disclosureId as? String
    }
    ids.forEach { id ->
        if (id != "nested") {
            (inter.setContent as (String, String) -> Unit).invoke(id, "")
        }
    }
}

/**
 * Tear down every output-area disclosure owned by [box]: disconnects its
 * ResizeObserver/MutationObserver, disposes any embedded Monaco editor, removes
 * the wrap (including any output iframe and the render loop running inside it),
 * and drops it from the disclosure maps. Mirrors the "clear-all" output-control.
 *
 * Called when a box is deleted/tombstoned. Without this a long-lived page (the
 * docs-capture runner creates/runs/deletes hundreds of boxes on one warm page)
 * accumulates live-but-hidden output areas whose observers/loops keep firing --
 * pinning chromium at hundreds of % CPU and multi-GB RSS. Output areas are
 * regenerated from server output if the box is ever restored (undo) and re-run.
 */
fun teardownBoxOutputs(box: HTMLElement) {
    val outputArea = box.getElementsByClassName("outputArea").asList().firstOrNull() as? HTMLElement ?: return
    val contain = outputArea.querySelector(".disclosure-group") ?: return
    val di = js("window.disclosureInterface")
    if (di == null || di == undefined) return
    val inter = di(contain)
    val ids = contain.querySelectorAll(".disclosure").asList().mapNotNull {
        it.asDynamic().__disclosureId as? String
    }
    ids.forEach { id ->
        // Keep "nested" (it holds child boxes, not output content).
        if (id != "nested") {
            (inter.remove as (String) -> Unit).invoke(id)
        }
    }
}

fun handleCustomizationRequest(box: HTMLElement, code: Set_string) {
    console.info("[customize] executing customization code for ${code.property}")
    val f = eval(
        """
        new Function("customize", `${code.value}`)
    """.trimIndent()
    ).unsafeCast<dynamic>()
    f.call(box)
}

@OptIn(DelicateCoroutinesApi::class)
fun handleEvalRequest(box: HTMLElement, code: String) {
    console.info("[customize] evaluating module code")
    insertHTMLWithScripts(box, "<script type='module'>$code</script>", isModule = true)
}

// ── Embedded editor in output area ─────────────────────────────────────

/**
 * Open an embedded Monaco editor for a given property inside a box's output area.
 * Creates a disclosure panel with the editor inside.
 */
fun openEditorInOutput(uid: String, property: String) {
    val box = findBoxByUid(uid) ?: run {
        console.warn("[embedded-editor] box not found: $uid")
        return
    }

    val emb = js("window.embeddedEditors") ?: run {
        console.warn("[embedded-editor] embeddedEditors module not loaded")
        return
    }

    // Check if an editor already exists for this (uid, property)
    val existing = emb.getFor(uid, property)
    if (existing != null) {
        js("if (typeof window.notify === 'function') window.notify('Editor already open for ' + property, 'command')")
        return
    }

    val outputArea = box.getElementsByClassName("outputArea").asList().firstOrNull() as? HTMLElement ?: return
    outputArea.style.visibility = "visible"
    val contain = outputArea.querySelector(".disclosure-group") ?: return
    val di = js("window.disclosureInterface") ?: return
    val inter = di(contain)

    val channelId = "editor:$property"
    val language = (emb.languageFor as Function<*>).asDynamic().call(emb, property) as? String ?: "plaintext"
    val title = "\u270E $property ($language)"

    // Create the disclosure panel
    val contentRoot = (inter.upsertDisclosure as (String, String, String) -> Element).invoke(channelId, title, "")
    (contentRoot as HTMLElement).asDynamic().dataset.afieldAutoSizeMode = "fixed"

    // Read current value from the box DOM element
    val currentValue = box.asDynamic()[property] as? String ?: ""

    // Create a container div for Monaco
    val editorContainer = document.createElement("div") as HTMLElement
    editorContainer.style.width = "100%"
    editorContainer.style.height = "100%"
    editorContainer.style.minHeight = "120px"
    editorContainer.asDynamic().style.overflow = "hidden"
    contentRoot.innerHTML = ""
    contentRoot.appendChild(editorContainer)

    // Override disclosure-content font-size — it defaults to 10px in CSS,
    // which Monaco inherits for gutter measurements. Set it to match the
    // editor's configured fontSize so line numbers and controls are sized
    // correctly.
    contentRoot.style.fontSize = "13px"
    contentRoot.style.padding = "0px"
    contentRoot.style.background = "#000000"

    // Create the embedded editor
    val editorId = (emb.create as Function<*>).asDynamic().call(emb, editorContainer, uid, property, language, currentValue) as? String

    if (editorId != null) {
        // The embeddedEditors.js module tags the container with data-embeddedEditorId
        // which the disclosure remove() hook reads for cleanup.
        // Also tag the disclosure content wrapper (contentRoot) so the remove hook can find it
        contentRoot.asDynamic().dataset.embeddedEditorId = editorId
    }

    // Open the disclosure
    (inter.open as (String) -> Unit).invoke(channelId)

    // Force Monaco to re-layout after the disclosure has opened and the DOM has settled
    if (editorId != null) {
        val entry = emb.getFor(uid, property)
        if (entry != null) {
            kotlinx.browser.window.requestAnimationFrame {
                entry.editor.layout()
            }
        }
    }
}
