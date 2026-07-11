@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import afield.Rect
import afield.graphics.FLine
import org.joml.Vector4f
import java.awt.Stroke
import kotlin.math.max
import kotlin.math.min

fun rect(x: Number, y: Number, w: Number, h: Number) =
    Rect(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble())

/**
 * Preferred builder for [FLine] geometry in sketches and examples.
 */
inline fun fline(block: FLine.() -> Unit): FLine = FLine().apply(block)

/**
 * A single straight segment written as a range: `vec(0, 0)..vec(1, 1)`.
 * Composes with `+=` for chaining, e.g. `f += vec(2, 0)..vec(3, 3)`.
 */
operator fun Vec3.rangeTo(other: Vec3): FLine = fline { moveTo(this@rangeTo); lineTo(other) }

/** 2D overload of the segment range operator. */
operator fun Vec2.rangeTo(other: Vec2): FLine = fline { moveTo(this@rangeTo); lineTo(other) }

/**
 * Add a [moveTo] and immediately style the resulting node: `moveTo(v) { color = RED }`.
 */
inline fun FLine.moveTo(v: Vec3, style: FLine.Node.() -> Unit): FLine = moveTo(v).also { here.style() }

/** Add a [lineTo] and immediately style the resulting node. */
inline fun FLine.lineTo(v: Vec3, style: FLine.Node.() -> Unit): FLine = lineTo(v).also { here.style() }

/** Add a cubic and immediately style the resulting node. */
inline fun FLine.cubicTo(c1: Vec3, c2: Vec3, to: Vec3, style: FLine.Node.() -> Unit): FLine =
    cubicTo(c1, c2, to).also { here.style() }

var FLine.color by Delegation<Vector4f> { vec(1.0, 1.0, 1.0, 1.0) }
var FLine.Node.color by Delegation<Vector4f> { vec(1.0, 1.0, 1.0, 1.0) }
var FLine.thickness by Delegation<Double?>()
var FLine.thicken by Delegation<Stroke?>()
var FLine.filled by Delegation<Boolean> { false }
var FLine.stroked by Delegation<Boolean> { true }
var FLine.pointed by Delegation<Boolean> { false }
var FLine.pointSize by Delegation<Double> { 1.0 }

var FLine.Node.pointSize by Delegation<Double?>(null)

/** Hit information passed to [FLine.onClick] from the interactive line renderers. */
data class FLineIntersection(
    val closestVertexIndex: Int,
    val position: Vec3
)

/** Callback invoked when this FLine is clicked in an interactive output. */
var FLine.onClick by Delegation<((FLineIntersection) -> Unit)?>()

/**
 * Color to show when the pointer hovers over this FLine in an interactive SVG output.
 * When null no hover color change is applied.
 */
var FLine.hoverColor by Delegation<Vec4?>()

fun seg(a: Vec2, b: Vec2, pointed: Boolean = false, color: Vec4 = vec(1, 1, 1, 1)) = fline {
    moveTo(a.x, a.y); lineTo(b.x, b.y)
    if (pointed) this.pointed = true
    this.color = Vec4(color)
}

/**
 * Append a closed rectangle path to this line.
 */
fun FLine.rect(x: Number, y: Number, w: Number, h: Number): FLine {
    val xf = x.toDouble()
    val yf = y.toDouble()
    val wf = w.toDouble()
    val hf = h.toDouble()
    moveTo(xf, yf)
    lineTo(xf + wf, yf)
    lineTo(xf + wf, yf + hf)
    lineTo(xf, yf + hf)
    close()
    return this
}

/**
 * Append a closed rectangle path for [rect] to this line.
 */
fun FLine.rect(rect: Rect): FLine = rect(rect.x, rect.y, rect.w, rect.h)

/**
 * Convert this rectangle into a styled [FLine].
 */
fun Rect.toFLine(
    color: Vec4 = vec(1.0, 1.0, 1.0, 1.0),
    filled: Boolean = false,
    stroked: Boolean = true,
    thickness: Double? = if (stroked) 1.0 else null
): FLine = fline {
    rect(this@toFLine)
    this.color = Vec4(color)
    this.filled = filled
    this.stroked = stroked
    thickness?.let { this.thickness = it }
}

/**
 * Scale and translate these lines so their 2D bounds fit inside [frame].
 *
 * When [preserveAspect] is true the same scale is used on both axes.
 */
fun List<FLine>.fitInto(
    frame: Rect,
    padding: Double = 0.0,
    preserveAspect: Boolean = true
): List<FLine> {
    val bounds = mapNotNull { runCatching { it.fast2DBounds() }.getOrNull() }
        .reduceOrNull(Rect::union)
        ?: return this
    if (bounds.w <= 0.0 || bounds.h <= 0.0) return this

    val safePadding = padding.coerceAtLeast(0.0)
    val innerW = (frame.w - safePadding * 2.0).coerceAtLeast(0.0)
    val innerH = (frame.h - safePadding * 2.0).coerceAtLeast(0.0)
    if (innerW <= 0.0 || innerH <= 0.0) return this

    val rawScaleX = innerW / bounds.w
    val rawScaleY = innerH / bounds.h
    val scaleX = if (preserveAspect) min(rawScaleX, rawScaleY) else rawScaleX
    val scaleY = if (preserveAspect) scaleX else rawScaleY
    if (!scaleX.isFinite() || !scaleY.isFinite() || scaleX <= 0.0 || scaleY <= 0.0) return this

    val offsetX = frame.x + safePadding - bounds.x * scaleX + (innerW - bounds.w * scaleX) * 0.5
    val offsetY = frame.y + safePadding - bounds.y * scaleY + (innerH - bounds.h * scaleY) * 0.5

    return map { line ->
        line.transform { p ->
            vec(
                p.x * scaleX + offsetX,
                p.y * scaleY + offsetY,
                p.z
            )
        }
    }
}

/**
 * Build an arrow with a real arrowhead instead of relying on point sprites.
 */
fun arrow(
    x0: Double,
    y0: Double,
    x1: Double,
    y1: Double,
    color: Vec4 = vec(1.0, 1.0, 1.0, 1.0),
    thickness: Double = 2.0,
    headLength: Double = Double.NaN,
    headWidth: Double = Double.NaN,
    startHead: Boolean = false,
    endHead: Boolean = true
): FLine = arrow(
    vec(x0, y0),
    vec(x1, y1),
    color = color,
    thickness = thickness,
    headLength = headLength,
    headWidth = headWidth,
    startHead = startHead,
    endHead = endHead
)

/**
 * Build an arrow with configurable arrowheads.
 */
fun arrow(
    from: Vec2,
    to: Vec2,
    color: Vec4 = vec(1.0, 1.0, 1.0, 1.0),
    thickness: Double = 2.0,
    headLength: Double = Double.NaN,
    headWidth: Double = Double.NaN,
    startHead: Boolean = false,
    endHead: Boolean = true
): FLine {
    val start = Vec2(from)
    val end = Vec2(to)
    val delta = Vec2(end).sub(start)
    val length = delta.length().toDouble()
    if (length <= 1e-6) {
        return fline {
            this.color = Vec4(color)
            this.stroked = true
            this.filled = false
            this.thickness = thickness
        }
    }

    val direction = delta.normalize(Vec2())
    val rawHeadLength = headLength.takeIf { it.isFinite() && it > 0.0 } ?: max(12.0, thickness * 6.0)
    val maxHeadFraction = if (startHead && endHead) 0.35 else 0.6
    val resolvedHeadLength = min(rawHeadLength, length * maxHeadFraction)
    val rawHeadWidth = headWidth.takeIf { it.isFinite() && it > 0.0 } ?: max(8.0, thickness * 4.0)
    val resolvedHeadWidth = min(rawHeadWidth, resolvedHeadLength * 1.6)

    fun appendHead(target: FLine, tip: Vec2, facing: Vec2) {
        if (resolvedHeadLength <= 0.0 || resolvedHeadWidth <= 0.0) return
        val dir = Vec2(facing)
        if (dir.lengthSquared() <= 1e-8f) return
        dir.normalize()
        val normal = Vec2(-dir.y, dir.x)
        val baseCenter = Vec2(tip).sub(Vec2(dir).mul(resolvedHeadLength.toFloat()))
        val halfWidth = (resolvedHeadWidth * 0.5).toFloat()
        val baseLeft = Vec2(baseCenter).add(Vec2(normal).mul(halfWidth))
        val baseRight = Vec2(baseCenter).sub(Vec2(normal).mul(halfWidth))
        target.moveTo(tip)
        target.lineTo(baseLeft)
        target.lineTo(baseRight)
        target.close()
    }

    val shaftStart = Vec2(start)
    val shaftEnd = Vec2(end)
    if (startHead) shaftStart.add(Vec2(direction).mul(resolvedHeadLength.toFloat()))
    if (endHead) shaftEnd.sub(Vec2(direction).mul(resolvedHeadLength.toFloat()))

    return fline {
        if (Vec2(shaftEnd).sub(shaftStart).lengthSquared() > 1e-8f) {
            moveTo(shaftStart)
            lineTo(shaftEnd)
        }
        if (endHead) appendHead(this, end, direction)
        if (startHead) appendHead(this, start, Vec2(direction).mul(-1.0f))
        this.color = Vec4(color)
        this.thickness = thickness
        this.stroked = true
        this.filled = startHead || endHead
    }
}
