package afield

import DubinsPose
import addArrowHead
import connectCenters
import dubinsMinRadius
import dubinsShortestPath
import kotlinx.browser.document
import kotlinx.browser.window
import midPose
import org.w3c.dom.DOMRect
import org.w3c.dom.HTMLElement
import org.w3c.dom.MutationObserver
import org.w3c.dom.MutationObserverInit
import org.w3c.dom.svg.SVGElement
import org.w3c.dom.svg.SVGSVGElement
import posesFromPolyline
import sampleDubinsPath
import screenToStandard
import standardToScreen
import web.timers.setTimeout
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

private var annotationSVGLayer: SVGSVGElement? = null
private var editorVisibilityObserver: MutationObserver? = null
private var editorPropertyObserver: MutationObserver? = null
internal var currentAnnotations: List<SlideAnnotation> = emptyList()
internal var currentNoteOverlay: HTMLElement? = null
private var annotationRedrawFramesRemaining = 0
private var annotationRedrawScheduled = false
private fun domRect(x: Double, y: Double, w: Double, h: Double): DOMRect =
    js("new DOMRect(x, y, w, h)").unsafeCast<DOMRect>()

private fun resamplePolyline(points: List<Point>, count: Int): List<Point> {
    if (points.isEmpty()) return points
    if (count <= 2) return listOf(points.first(), points.last())

    val lengths = DoubleArray(points.size)
    for (i in 1 until points.size) {
        lengths[i] = lengths[i - 1] + points[i - 1].distance(points[i])
    }
    val total = lengths.last()
    if (total < 1e-6) return List(count) { idx ->
        when (idx) {
            0 -> points.first()
            count - 1 -> points.last()
            else -> points.first()
        }
    }

    val step = total / (count - 1)
    val result = mutableListOf<Point>()
    var seg = 1
    for (i in 0 until count) {
        val targetDist = step * i
        while (seg < lengths.size && lengths[seg] < targetDist) seg++
        if (seg >= lengths.size) {
            result += points.last()
        } else {
            val prevDist = lengths[seg - 1]
            val nextDist = lengths[seg]
            val ratio = if (nextDist - prevDist < 1e-9) 0.0 else (targetDist - prevDist) / (nextDist - prevDist)
            val p0 = points[seg - 1]
            val p1 = points[seg]
            result += Point(
                p0.x + (p1.x - p0.x) * ratio,
                p0.y + (p1.y - p0.y) * ratio
            )
        }
    }
    return result
}

private val annotationPathState = mutableMapOf<String, List<Point>>()

internal fun clearAnnotationPathState() {
    annotationPathState.clear()
}

internal fun animateSlideNoteText(overlay: HTMLElement, html: String) {
    overlay.innerHTML = ""
    val contentDiv = document.createElement("div") as HTMLElement
    overlay.appendChild(contentDiv)

    contentDiv.innerHTML = html
    val textNodes = collectTextNodes(contentDiv)
    val originalTexts = textNodes.map { it.textContent ?: "" }
    val fullText = originalTexts.joinToString("")
    if (fullText.isEmpty()) return

    textNodes.forEach { it.textContent = "" }

    var charIndex = 0
    val delay = 15.0

    fun animateNextChar() {
        if (charIndex >= fullText.length) return

        var remaining = charIndex
        textNodes.zip(originalTexts).forEach { (node, original) ->
            val len : Int = original.length
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
        val pauseExtra = when (char) {
            '.', '!', '?' -> 300.0
            ',', ';', ':' -> 150.0
            else -> 0.0
        }

        if (currentAnnotations.isNotEmpty()) {
            scheduleSlideAnnotationRedraw(10)
        }

        setTimeout({ animateNextChar() }, (delay + pauseExtra).toInt())
    }

    animateNextChar()
}

private fun collectTextNodes(element: org.w3c.dom.Node): List<org.w3c.dom.Node> {
    val result = mutableListOf<org.w3c.dom.Node>()
    fun traverse(node: org.w3c.dom.Node) {
        if (node.nodeType == 3.toShort()) {
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

internal fun buildNoteRect(noteRect: DOMRect, ox: Double, oy: Double): DOMRect = domRect(
    noteRect.left - ox,
    noteRect.top - oy,
    noteRect.width,
    noteRect.height
)

internal fun buildTargetRectForBox(targetRect: DOMRect, ox: Double, oy: Double): DOMRect = domRect(
    targetRect.left - ox,
    targetRect.top - oy,
    targetRect.width,
    targetRect.height
)

internal fun buildTargetRectForLine(
    targetRect: DOMRect,
    editorRect: DOMRect?,
    ox: Double,
    oy: Double,
    lineCenter: Double?,
    lineHeight: Double?
): DOMRect = if (editorRect != null) {
    domRect(
        editorRect.left - ox - 6,
        (lineCenter ?: (editorRect.top + editorRect.height * 0.3 - oy)) - (lineHeight ?: 12.0) / 2.0,
        max(12.0, editorRect.width * 0.15),
        lineHeight ?: 12.0
    )
} else {
    domRect(
        targetRect.left - ox,
        targetRect.top + (targetRect.height * 0.3) - oy,
        targetRect.width,
        max(8.0, targetRect.height * 0.1)
    )
}

internal fun drawDubinsAnnotation(
    stateKey: String,
    startRect: DOMRect,
    endRect: DOMRect,
    svg: SVGElement,
    color: String,
    strokeWidth: Double,
    dashed: Boolean
) {
    val (startClip, endClip) = connectCenters(startRect, endRect)
    val startHeading = atan2(startClip.normal.y, startClip.normal.x)
    val endHeading = atan2(-endClip.normal.y, -endClip.normal.x)
    val startPose = DubinsPose(startClip.point.x, startClip.point.y, startHeading)
    val endPose = DubinsPose(endClip.point.x, endClip.point.y, endHeading)

    val straight = startClip.point.distance(endClip.point)
    val radius = min(dubinsMinRadius, max(1.0, straight / 3.1))
    val step = max(3.0, radius / 3.0) / 2.0
    val dubins = dubinsShortestPath(screenToStandard(startPose), screenToStandard(endPose), radius)

    val drawPoints: List<Point> = if (dubins != null) {
        val sampledStd = sampleDubinsPath(dubins, screenToStandard(startPose), step)
        sampledStd.map { standardToScreen(it) }.map { Point(it.x, it.y) }
    } else {
        listOf(startClip.point, endClip.point)
    }

    if (drawPoints.size < 2) return

    val target = resamplePolyline(drawPoints, min(128, max(2, drawPoints.size)))

    val blended = annotationPathState[stateKey]?.let { prev ->
        val prevResampled = resamplePolyline(prev, target.size)
        val alpha = 0.35
        prevResampled.zip(target).map { (a, b) ->
            Point(a.x * (1 - alpha) + b.x * alpha, a.y * (1 - alpha) + b.y * alpha)
        }
    } ?: target

    annotationPathState[stateKey] = blended

    path {
        moveTo(blended.first().x, blended.first().y)
        blended.drop(1).forEach { p -> lineTo(p.x, p.y) }
        stroke = color
        this.strokeWidth = strokeWidth
        fill = "none"
        if (dashed) {
            path.setAttribute("stroke-dasharray", "8,6")
        }
        appendTo(svg)
    }

    val arrowPose = midPose(posesFromPolyline(blended))
    addArrowHead(svg, Point(arrowPose.x, arrowPose.y), arrowPose.heading, 1.0, color)

    circle {
        cx = startClip.point.x
        cy = startClip.point.y
        r = strokeWidth * 1.2
        fill = color
        appendTo(svg)
    }
    circle {
        cx = endClip.point.x
        cy = endClip.point.y
        r = strokeWidth * 1.4
        fill = color
        appendTo(svg)
    }
}

internal fun ensureAnnotationLayer(): SVGSVGElement {
    annotationSVGLayer?.let { if (it.parentNode != null) return it }
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

internal fun ensureEditorVisibilityObserver() {
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

internal fun ensureEditorPropertyObserver(selectionContextChanged: () -> Unit) {
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

internal fun scheduleSlideAnnotationRedraw(frames: Int = 0) {
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

internal fun drawSlideAnnotations(annotations: List<SlideAnnotation>, noteOverlay: HTMLElement) {
    currentAnnotations = annotations
    currentNoteOverlay = noteOverlay
    ensureEditorVisibilityObserver()
    ensureEditorPropertyObserver(::selectionContextChanged)
    redrawSlideAnnotations()
    if (annotations.isNotEmpty()) {
        scheduleSlideAnnotationRedraw(100)
    }
}

internal fun redrawSlideAnnotations() {
    val annotations = currentAnnotations
    val noteOverlay = currentNoteOverlay

    if (annotations.isEmpty() || noteOverlay == null) {
        clearSlideAnnotations()
        return
    }

    window.asDynamic().selectedProperty?.toString()?.let { prop ->
        if (prop != selectedProperty) {
            selectedProperty = prop
        }
    }

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

            val startRect = buildNoteRect(noteRect, ox, oy)

            val endRect = if (annotation.targetLine != null && annotation.targetProperty != null) {
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

                    val yCenter = if (topForLine != null) {
                        editorRect.top + (topForLine.toDouble() - scrollTop.toDouble()) + (lineHeight?.toDouble() ?: 16.0) / 2.0 - oy
                    } else {
                        editorRect.top + editorRect.height * 0.3 - oy
                    }

                    buildTargetRectForLine(
                        targetRect,
                        editorRect,
                        ox,
                        oy,
                        yCenter,
                        lineHeight?.toDouble()
                    )
                } else {
                    buildTargetRectForLine(
                        targetRect,
                        null,
                        ox,
                        oy,
                        null,
                        null
                    )
                }
            } else {
                buildTargetRectForBox(targetRect, ox, oy)
            }

            drawDubinsAnnotation(
                stateKey = annotation.id,
                startRect = startRect,
                endRect = endRect,
                svg = svg,
                color = annotation.color,
                strokeWidth = annotation.strokeWidth,
                dashed = annotation.dashed
            )
        }
    }
}

internal fun clearSlideAnnotations() {
    currentAnnotations = emptyList()
    currentNoteOverlay = null
    clearAnnotationPathState()
    val svg = slideAnnotationGroup()
    svg.innerHTML = ""
}



fun ensureEditorPropertyObserver() {
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
