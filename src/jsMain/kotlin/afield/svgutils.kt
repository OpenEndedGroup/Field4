package afield

import CanvasGestures.Companion.svgNamespace
import kotlinx.browser.document
import org.w3c.dom.svg.SVGCircleElement
import org.w3c.dom.svg.SVGElement
import org.w3c.dom.svg.SVGPathElement
import org.w3c.dom.svg.SVGSVGElement
import web.dom.Element
import kotlin.math.sqrt
import kotlin.reflect.KProperty

@JsName("anime.animate")
external fun animate(element: Element, animation: dynamic)


operator fun Boolean.invoke(function: () -> Unit) {
    if (this) function()
}

data class Point(val x: Double, val y: Double) {
    fun distance(point: Point): Double {
        return sqrt((x - point.x) * (x - point.x) + (y - point.y) * (y - point.y))
    }

    operator fun plus(other: Point) = Point(x + other.x, y + other.y)
    operator fun minus(other: Point) = Point(x - other.x, y - other.y)
    infix fun dot(other: Point) = x * other.x + y * other.y
}


fun SVGSVGElement.getGroupOrMake(name: String): SVGElement {
    val svg = this
    var child = svg.querySelector("#$name")
    if (child == null) {
        child = document.createElementNS(svgNamespace, "g") as SVGElement
        child.id = name
        svg.appendChild(child)
    }
    return child as SVGElement
}

// Compute a point on a cubic Bézier at parameter t.
fun cubicBezierPoint(
    t: Double,
    x0: Double, y0: Double,
    cx0: Double, cy0: Double,
    cx1: Double, cy1: Double,
    x1: Double, y1: Double
): Pair<Double, Double> {
    val oneMinusT = 1.0 - t
    val x = oneMinusT * oneMinusT * oneMinusT * x0 +
            3.0 * oneMinusT * oneMinusT * t * cx0 +
            3.0 * oneMinusT * t * t * cx1 +
            t * t * t * x1
    val y = oneMinusT * oneMinusT * oneMinusT * y0 +
            3.0 * oneMinusT * oneMinusT * t * cy0 +
            3.0 * oneMinusT * t * t * cy1 +
            t * t * t * y1
    return x to y
}

// Compute the derivative (tangent vector) at parameter t.
fun cubicBezierTangent(
    t: Double,
    x0: Double, y0: Double,
    cx0: Double, cy0: Double,
    cx1: Double, cy1: Double,
    x1: Double, y1: Double
): Pair<Double, Double> {
    val oneMinusT = 1.0 - t
    val dx = 3.0 * oneMinusT * oneMinusT * (cx0 - x0) +
            6.0 * oneMinusT * t * (cx1 - cx0) +
            3.0 * t * t * (x1 - cx1)
    val dy = 3.0 * oneMinusT * oneMinusT * (cy0 - y0) +
            6.0 * oneMinusT * t * (cy1 - cy0) +
            3.0 * t * t * (y1 - cy1)
    return dx to dy
}

interface HasPath {
    val path: SVGElement
}

class Attribution<T>(val defaultValue: () -> T) {
    var value: T? = null
    operator fun getValue(f: HasPath, property: KProperty<*>): T {
        var current = value
        if (current == null) {
            current = defaultValue()
            value = current
            f.path.setAttribute(property.name, current.toString())
        }
        @Suppress("UNCHECKED_CAST")
        return current as T
    }

    operator fun setValue(f: HasPath, property: KProperty<*>, v: T) {
        this.value = v
        f.path.setAttribute(property.name, value.toString())
    }
}

open class BaseBuilder(override val path: SVGElement) : HasPath {
    var stroke: String by Attribution { "" }
    var fill: String by Attribution { "" }
    var strokeWidth: Number by Attribution { 1.0 }

    fun appendTo(s: SVGElement) {
        s.appendChild(path)
    }

    fun onMouseDown(s: (org.w3c.dom.events.MouseEvent) -> Unit) {
        path.addEventListener("mousedown", {
            s(it as org.w3c.dom.events.MouseEvent)
        })
        path.style.unsafeCast<dynamic>().pointerEvents = "all"
    }

    @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
    inline fun <T : Any> obj(builder: dynamic.() -> Unit): T {
        val o = js("{}")
        builder(o)
        return o as T
    }

    //todo: unpack easing etc.
    fun animate(property: String, to: Any, over: Double) {
        animate(path.unsafeCast<Element>(), obj {
            this[property] = to
            this[duration] = over
        })
    }
}

class CircleBuilder(override val path: SVGCircleElement, val isNew: Boolean = false) : BaseBuilder(path) {
    var cx: Number by Attribution { 0.0 }
    var cy: Number by Attribution { 0.0 }
    var r: Number by Attribution { 0.0 }
}

class PathBuilder(override val path: SVGPathElement, val isNew: Boolean = false) : BaseBuilder(path) {

    fun moveTo(p: Point) = moveTo(p.x, p.y)
    fun lineTo(p: Point) = lineTo(p.x, p.y)
    fun cubicTo(c0: Point, c1: Point, p: Point) = cubicTo(c0.x, c0.y, c1.x, c1.y, p.x, p.y)

    fun moveTo(x: Double, y: Double) {
        d += "M$x,$y "
    }

    fun lineTo(x: Double, y: Double) {
        d += "L$x,$y "
    }

    fun cubicTo(cx0: Double, cy0: Double, cx1: Double, cy1: Double, x: Double, y: Double) {
        d += "C$cx0,$cy0,$cx1,$cy1,$x,$y "
    }

    fun close() {
        d += "Z"
    }

    var d: String by Attribution { "" }


}

@Suppress("UNCHECKED_CAST")
fun <T> svgElement(kind: String, parent: SVGElement? = null, named: String? = null): Pair<T, Boolean> {
    return if (named != null && parent != null) {
        val e = parent.querySelector("#$named")
        if (e != null) {
            e as T to false
        } else {
            document.createElementNS(svgNamespace, kind) as T to true
        }
    } else {
        document.createElementNS(svgNamespace, kind) as T to true
    }
}

fun path(parent: SVGElement? = null, named: String? = null, builder: PathBuilder.() -> Unit): SVGPathElement {
    val (path, isNew) = svgElement<SVGPathElement>("path", parent, named)
    PathBuilder(path, isNew).apply {
        if (parent != null) appendTo(parent)
        if (named != null) path.id = named
        builder()
    }
    return path
}

fun circle(parent: SVGElement? = null, named: String? = null, builder: CircleBuilder.() -> Unit): SVGCircleElement {
    val (path, isNew) = svgElement<SVGCircleElement>("circle", parent, named)
    CircleBuilder(path, isNew).apply {
        if (parent != null) appendTo(parent)
        if (named != null) path.id = named
        builder()
    }
    return path
}
