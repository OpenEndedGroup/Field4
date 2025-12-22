@file:JsExport

import afield.*
import kotlinx.browser.document
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.DOMRect
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.svg.SVGElement
import org.w3c.dom.svg.SVGSVGElement
import web.animations.requestAnimationFrame
import kotlin.math.*


fun connect_center(a_box: HTMLElement, a_rect: DOMRect, b_box: HTMLElement, b_rect: DOMRect, svg: SVGElement, opacity: Double = 1.0) {
    val (a, b) = connectCenters(a_rect, b_rect)
    val sc = min(50.0, a.point.distance(b.point) / 3);
    path {
        d =
            "M${a.point.x} ${a.point.y} C${a.point.x + a.normal.x * sc} ${a.point.y + a.normal.y * sc} ${b.point.x + b.normal.x * sc} ${b.point.y + b.normal.y * sc} ${b.point.x} ${b.point.y}"
        stroke = "rgba(128,128,128,${opacity})"
        strokeWidth = 1
        fill = "none"
        appendTo(svg)
    }

    addArrowHeadToCubicSpline(
        svg,
        a.point.x,
        a.point.y,
        a.point.x + a.normal.x * sc,
        a.point.y + a.normal.y * sc,
        b.point.x + b.normal.x * sc,
        b.point.y + b.normal.y * sc,
        b.point.x,
        b.point.y, opacity
    )
}

var knownConnections = mutableMapOf("parent->child" to ::connect_center)


class CanvasGestures(val launcher: (List<HTMLElement>) -> Unit) {

    companion object {
        val svgNamespace = "http://www.w3.org/2000/svg"

        fun select(selector: String): List<HTMLElement> {
            val s = document.querySelectorAll(selector)
            return (0 until s.length).mapNotNull {
                s.item(it) as? HTMLElement
            }
        }

        fun find(u: String) = select(".box").find { it.unsafeCast<dynamic>().uid === u }

        fun boxes() = select(".box").filter { it.unsafeCast<dynamic>().uid != undefined }
    }


    private var overlayContext: CanvasRenderingContext2D
    private var overlayCanvas: HTMLCanvasElement

    init {
        println("finding ${document.querySelector("#overlaycanvas")}")
        overlayCanvas = document.querySelector("#overlaycanvas")!! as HTMLCanvasElement
        overlayContext = overlayCanvas.getContext("2d") as CanvasRenderingContext2D
    }

    fun getNamedSVGGroup(name: String) =
        (document.getElementById("overlaysvglayer") as SVGSVGElement).getGroupOrMake(name)

    fun invoke(name: String, builder: SVGElement.() -> Unit) {
        val circleLayer = getNamedSVGGroup(name)
        circleLayer.builder()
    }


    fun rebuildConnectionGroup() {
        val parent = document.getElementById("overlaysvglayer") as SVGSVGElement

        val (vbw, vbh) = parent.getBoundingClientRect().let { it.width to it.height }

        parent.setAttribute("viewBox", "${-mainTranslation[0]} ${-mainTranslation[1]} $vbw $vbh")

        val svg = getNamedSVGGroup("connections")
        svg.innerHTML = ""

        // example of new box accessable drawing
//        val circleLayer = getNamedSVGGroup("circleLayer")
//
//        run {
//            circle(circleLayer, "circley") {
//                isNew {
//                    cx = 50.0
//                    cy = 50.0
//                    r = 10.0
//                    fill = "white"
//                }
//                onMouseDown {
//                    fill = "red"
//                    animate("cx", 100.0 * Random.nextDouble(), 3.0)
//                    r = 20.0
//                }
//            }
//        }

        // because we're getting client rects we actually need to subtract this off
        var ox = mainTranslation[0] * 1.0
        var oy = mainTranslation[1] * 1.0

        fun traverse(connectionName: String, find: (HTMLElement) -> List<String>?) {
            boxes().forEach { from ->
                val c = find(from)
                val r1 = from.getBoundingClientRect()

                if (from.style.visibility === "hidden") return@forEach

                var o0 = 1.0
                if (from.asDynamic().offline ) o0 = 0.25

                r1.x -= ox
                r1.y -= oy

                from.unsafeCast<dynamic>()
                c?.let {
                    it.forEach { id ->
                        find(id)?.let { to ->
                            val r2 = to.getBoundingClientRect()
                            if (to.style.visibility === "hidden") return@forEach
                            var o1 = 1.0
                            if (to.asDynamic().offline ) o1 = 0.25

                            r2.x -= ox
                            r2.y -= oy
                            knownConnections[connectionName]?.invoke(from, r1, to, r2, svg, o0*o1)
                        }
                    }
                }
            }
        }

        traverse("parent->child") { from ->
            (from.unsafeCast<dynamic>().box_children as Array<String>?)?.toList()
        }
    }


    fun drawDot(x: Double, y: Double, r: Double) {
        overlayContext.beginPath()
        overlayContext.arc(x, y, r, 0.0, 2 * PI)
        overlayContext.fill()
    }

    fun intersects(x: Double, y: Double): dynamic {
        val element = document.elementFromPoint(x, y).unsafeCast<dynamic>()
        println("intersects $x $y = $element")
        return element
    }

    var isDown = false
    var isExecutionLine = false
    var isMarquee = false
    var start: Pair<Double, Double>? = null
    var rects: List<Pair<HTMLElement, DOMRect>>? = null


    fun down(e: org.w3c.dom.events.MouseEvent) {
        isDown = true
        if (e.altKey) {

            var boxList = document.querySelectorAll(".box")
            rects = (0 until boxList.length).map { boxList.item(it) as HTMLElement }
                .map { it to it.getBoundingClientRect() }

            isExecutionLine = true
            start = e.x to e.y
        } else {

            var boxList = document.querySelectorAll(".box")
            rects = (0 until boxList.length).map { boxList.item(it) as HTMLElement }
                .map { it to it.getBoundingClientRect() }

            isMarquee = true
            start = e.x to e.y
        }
    }

    fun up(e: org.w3c.dom.events.MouseEvent) {
        isDown = false
        if (isExecutionLine) {
            finalizeExecutionLine(start!!, e)
        } else if (isMarquee) {
            finalizeSelectionLine(start!!, e)
        }
        isExecutionLine = false
        isMarquee = false
        rebuildConnectionGroup()
    }

    fun updateExecutionLine(start: Pair<Double, Double>, e: MouseEvent) {
        overlayContext.fillStyle = "rgba(0,0,0,0)"
        overlayContext.clearRect(0.0, 0.0, overlayCanvas.width.toDouble(), overlayCanvas.height.toDouble())
        overlayContext.strokeStyle = "rgba(128,255,164,0.5)"
        overlayContext.lineWidth = 1.75
        overlayContext.beginPath()
        overlayContext.moveTo(e.x, e.y)
        overlayContext.lineTo(start.first, start.second)
        overlayContext.stroke()
        overlayContext.fillStyle = "rgba(128,255,164,0.5)"
        drawDot(e.x, e.y, 2.0)
        drawDot(start.first, start.second, 2.0)
        rects!!.filter { (element, rect) ->
            lineIntersectsDOMRect(e.x, e.y, start.first, start.second, rect)
        }.forEach { (element, rect) ->
            overlayContext.strokeRect(rect.x, rect.y, rect.width, rect.height)
        }
    }

    fun updateMarquee(start: Pair<Double, Double>, e: MouseEvent) {
        overlayContext.fillStyle = "rgba(0,0,0,0)"
        overlayContext.clearRect(0.0, 0.0, overlayCanvas.width.toDouble(), overlayCanvas.height.toDouble())
        overlayContext.strokeStyle = "rgba(128,255,164,0.5)"
        overlayContext.lineWidth = 1.75
        overlayContext.beginPath()
        overlayContext.moveTo(e.x, e.y)
        overlayContext.lineTo(start.first, e.y)
        overlayContext.lineTo(start.first, start.second)
        overlayContext.lineTo(e.x, start.second)
        overlayContext.lineTo(e.x, e.y)
        overlayContext.stroke()
        overlayContext.fillStyle = "rgba(128,255,164,0.5)"
        drawDot(e.x, e.y, 2.0)
        drawDot(start.first, start.second, 2.0)
        rects!!.filter { (element, rect) ->
            rect.intersectsRect(e.x, e.y, start.first, start.second)
        }.forEach { (element, rect) ->
            overlayContext.strokeRect(rect.x, rect.y, rect.width, rect.height)
        }
    }

    fun finalizeExecutionLine(start: Pair<Double, Double>, e: MouseEvent) {
        overlayContext.fillStyle = "rgba(0,0,0,0)"
        overlayContext.clearRect(0.0, 0.0, overlayCanvas.width.toDouble(), overlayCanvas.height.toDouble())

        rects!!.filter { (element, rect) ->
            lineIntersectsDOMRect(e.x, e.y, start.first, start.second, rect)
        }.let {
            launcher(it.map { it.first })
        }
    }

    fun finalizeSelectionLine(start: Pair<Double, Double>, e: MouseEvent) {
        overlayContext.fillStyle = "rgba(0,0,0,0)"
        overlayContext.clearRect(0.0, 0.0, overlayCanvas.width.toDouble(), overlayCanvas.height.toDouble())

        if (!e.shiftKey) selected.copy().forEach {
            deselectBox(it)
        }

        rects!!.filter { (element, rect) ->
            rect.intersectsRect(e.x, e.y, start.first, start.second)
        }.let {
            it.forEach { selectBox(it.first) }
        }
    }


    fun move(e: org.w3c.dom.events.MouseEvent) {
        if (isDown) {
            intersects(e.x, e.y)
        }

        if (isExecutionLine) {
            updateExecutionLine(start!!, e)
        } else if (isMarquee) {
            updateMarquee(start!!, e)
        }
    }

    fun doLineSegmentsIntersect(
        x0: Double, y0: Double, x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double
    ): Boolean {
        val s1x = x1 - x0
        val s1y = y1 - y0
        val s2x = x3 - x2
        val s2y = y3 - y2
        val denom = (-s2x * s1y + s1x * s2y)
        if (abs(denom) < 1e-10) return false
        val s = (-s1y * (x0 - x2) + s1x * (y0 - y2)) / denom
        val t = (s2x * (y0 - y2) - s2y * (x0 - x2)) / denom
        return (s in 0.0..1.0) && (t in 0.0..1.0)
    }

    fun lineIntersectsDOMRect(x0: Double, y0: Double, x1: Double, y1: Double, rect: DOMRect): Boolean {
        if (x0 >= rect.x && x0 <= rect.x + rect.width && y0 >= rect.y && y0 <= rect.y + rect.height) return true
        if (x1 >= rect.x && x1 <= rect.x + rect.width && y1 >= rect.y && y1 <= rect.y + rect.height) return true

        val left = rect.x
        val right = rect.x + rect.width
        val top = rect.y
        val bottom = rect.y + rect.height

        if (doLineSegmentsIntersect(x0, y0, x1, y1, left, top, right, top)) return true
        if (doLineSegmentsIntersect(x0, y0, x1, y1, left, bottom, right, bottom)) return true
        if (doLineSegmentsIntersect(x0, y0, x1, y1, left, top, left, bottom)) return true
        if (doLineSegmentsIntersect(x0, y0, x1, y1, right, top, right, bottom)) return true

        return false
    }

    fun DOMRect.intersectsRect(x0: Double, y0: Double, x1: Double, y1: Double): Boolean {
        return !(x + width < min(x0, x1) || x > max(x0, x1) || y + height < min(y0, y1) || y > max(y0, y1))
    }
}


// Define the constant threshold for corner adjustment.
const val EDGE_CHEAT = 20.0


// A data class to hold the clipped point and the normal of the edge.
data class ClippedResult(val point: Point, val normal: Point)

/**
 * Returns the center point of a DOMRect.
 */
fun getCenter(rect: DOMRect): Point = Point((rect.left + rect.right) / 2.0, (rect.top + rect.bottom) / 2.0)

/**
 * Given a rectangle and a ray direction (dx, dy) from its center,
 * returns a ClippedResult containing:
 *  - the intersection point on the rectangle's boundary (possibly adjusted so that
 *    it is at least EDGE_CHEAT pixels away from the corner along that edge), and
 *  - the normal to the edge that was hit.
 *
 * The rectangle is assumed to be axis-aligned.
 */
fun clipRay(rect: DOMRect, dx: Double, dy: Double): ClippedResult {
    val center = getCenter(rect)

    // Compute the scale factors (t) needed to reach each vertical or horizontal edge.
    val tX = when {
        dx > 0 -> (rect.right - center.x) / dx
        dx < 0 -> (rect.left - center.x) / dx
        else -> Double.POSITIVE_INFINITY
    }
    val tY = when {
        dy > 0 -> (rect.bottom - center.y) / dy
        dy < 0 -> (rect.top - center.y) / dy
        else -> Double.POSITIVE_INFINITY
    }

    val eps = 1e-9
    val t: Double
    val normal: Point

    if (tX < tY - eps) {
        // Hits a vertical edge.
        t = tX
        normal = if (dx > 0) Point(1.0, 0.0) else Point(-1.0, 0.0)
    } else if (tY < tX - eps) {
        // Hits a horizontal edge.
        t = tY
        normal = if (dy > 0) Point(0.0, 1.0) else Point(0.0, -1.0)
    } else {
        // Corner hit (tX and tY nearly equal).
        t = tX  // (or tY—they're effectively equal)
        val nX = when {
            dx > 0 -> 1.0
            dx < 0 -> -1.0
            else -> 0.0
        }
        val nY = when {
            dy > 0 -> 1.0
            dy < 0 -> -1.0
            else -> 0.0
        }
        val len = sqrt(nX * nX + nY * nY)
        normal = if (len > 0) Point(nX / len, nY / len) else Point(0.0, 0.0)
    }

    // Compute the raw intersection point.
    val intersection = Point(center.x + t * dx, center.y + t * dy)

    // --- Corner Adjustment ---
    // If the intersection is within EDGE_CHEAT pixels of a corner along the edge,
    // slide it along the edge so that it's at least EDGE_CHEAT pixels from the corner.
    var adjusted = intersection

    // When the normal is strictly horizontal, the edge is vertical.
    if (abs(normal.x) > eps && abs(normal.y) < eps) {
        // p.x is fixed (either rect.left or rect.right).
        if (adjusted.y - rect.top < EDGE_CHEAT) {
            adjusted = Point(adjusted.x, rect.top + EDGE_CHEAT)
        } else if (rect.bottom - adjusted.y < EDGE_CHEAT) {
            adjusted = Point(adjusted.x, rect.bottom - EDGE_CHEAT)
        }
    }
    // When the normal is strictly vertical, the edge is horizontal.
    else if (abs(normal.y) > eps && abs(normal.x) < eps) {
        // p.y is fixed (either rect.top or rect.bottom).
        if (adjusted.x - rect.left < EDGE_CHEAT) {
            adjusted = Point(rect.left + EDGE_CHEAT, adjusted.y)
        } else if (rect.right - adjusted.x < EDGE_CHEAT) {
            adjusted = Point(rect.right - EDGE_CHEAT, adjusted.y)
        }
    }
    // For a corner hit (or blended normal), decide which edge to treat as active.
    else {
        if (abs(dx) >= abs(dy)) {
            // Treat as if on a vertical edge.
            if (adjusted.y - rect.top < EDGE_CHEAT) {
                adjusted = Point(adjusted.x, rect.top + EDGE_CHEAT)
            } else if (rect.bottom - adjusted.y < EDGE_CHEAT) {
                adjusted = Point(adjusted.x, rect.bottom - EDGE_CHEAT)
            }
        } else {
            // Treat as if on a horizontal edge.
            if (adjusted.x - rect.left < EDGE_CHEAT) {
                adjusted = Point(rect.left + EDGE_CHEAT, adjusted.y)
            } else if (rect.right - adjusted.x < EDGE_CHEAT) {
                adjusted = Point(rect.right - EDGE_CHEAT, adjusted.y)
            }
        }
    }
    // --- End Corner Adjustment ---

    return ClippedResult(adjusted, normal)
}

/**
 * Returns a pair of ClippedResults that represent the endpoints of the line segment connecting
 * the two rectangles. The segment is drawn between the centers of the rectangles, but is "clipped"
 * so that it starts and ends at the rectangle boundaries.
 *
 * For rect1, the ray is cast from its center toward rect2's center.
 * For rect2, the ray is cast from its center toward rect1's center.
 */
fun connectCenters(rect1: DOMRect, rect2: DOMRect): Pair<ClippedResult, ClippedResult> {
    var center1 = getCenter(rect1)
    var center2 = getCenter(rect2)

    // Direction vector from rect1's center to rect2's center.
    val dx = center2.x - center1.x
    val dy = center2.y - center1.y

    // For rect1, clip along (dx, dy).
    val clipped1 = clipRay(rect1, dx, dy)
    // For rect2, clip along (-dx, -dy).
    val clipped2 = clipRay(rect2, -dx, -dy)

    return clipped1 to clipped2
}


fun addArrowHeadToCubicSpline(
    svg: SVGElement, x0: Double, y0: Double, cx0: Double, cy0: Double, cx1: Double, cy1: Double, x1: Double, y1: Double, opacity: Double = 1.0
) {
    // Parameter at which to place the arrow head.
//    val t = 40/(sqrt((x0-x1)*(x0-x1)+(y0-y1)*(y0-y1)) + sqrt((cx0-cx1)*(cx0-cx1)+(cy0-cy1)*(cy0-cy1)))
    val t = 0.5
    // Get the point on the curve.
    val (px, py) = cubicBezierPoint(t, x0, y0, cx0, cy0, cx1, cy1, x1, y1)

    // Get the tangent vector at t.
    val (dx, dy) = cubicBezierTangent(t, x0, y0, cx0, cy0, cx1, cy1, x1, y1)
    val len = sqrt(dx * dx + dy * dy)
    val ux = dx / len
    val uy = dy / len

    // Arrow head parameters.
    val ARROW_HEAD_LENGTH = 12.0   // length of each arrow head line in pixels
    val ARROW_HEAD_ANGLE = PI / 6  // 30° in radians

    // Compute the angle of the tangent.
    val tangentAngle = atan2(uy, ux)
    // The arrow head lines will point backward, so subtract PI.
    // Then offset by ±ARROW_HEAD_ANGLE.
    val angle1 = tangentAngle + PI - ARROW_HEAD_ANGLE
    val angle2 = tangentAngle + PI + ARROW_HEAD_ANGLE

    // Compute the endpoints of the arrow head lines.
    val ax1 = px + ARROW_HEAD_LENGTH * cos(angle1)
    val ay1 = py + ARROW_HEAD_LENGTH * sin(angle1)
    val ax2 = px + ARROW_HEAD_LENGTH * cos(angle2)
    val ay2 = py + ARROW_HEAD_LENGTH * sin(angle2)

    path {
//        d = "M $ax1,$ay1 L$px,$py L$ax2,$ay2 Z"
        moveTo(ax1, ay1)
        lineTo(px, py)
        lineTo(ax2, ay2)
        close()
        stroke = "black"
        strokeWidth = 2.0+(1-opacity)*2.0
        fill = "rgba(255,255,255,$opacity)"
        appendTo(svg)
    }
}
