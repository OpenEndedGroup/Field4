@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import afield.Rect
import afield.graphics.FLine
import afield.stdlib.linalg.Vector4f
import java.awt.Stroke
import kotlin.math.max
import kotlin.math.min

/**
 * The small vocabulary of `FLine` construction and styling that sketches and examples reach for
 * first.
 *
 * [fline] is the builder; the [rangeTo] operator makes a one-segment line out of two vectors
 * (`a..b`, composable with `+=`), and the styling overloads of `moveTo` / `lineTo` / `cubicTo`
 * position a node and style it in the same call. Appearance is carried by delegated properties on
 * `FLine` and `FLine.Node` — `color`, `thickness`, `thicken`, `filled`, `stroked`, `pointed`,
 * `pointSize` — which the renderers read back; `onClick` and `hoverColor` only do anything in the
 * interactive outputs.
 *
 * The rest are shape helpers: `rect` and [Rect.toFLine], `arrow` (a real filled arrowhead rather
 * than a point sprite), [seg], and [fitInto], which scales and centres a list of lines into a
 * `Rect` using their 2D bounds only.
 */

/**
 * A plain [Rect] from `x, y, w, h` — the frame type [fitInto], [Rect.toFLine] and the `lines(...)`
 * outputs all speak.
 *
 * Origin-plus-size, not two corners, and any [Number] is coerced to `Double`. `y` grows *downwards*
 * in the 2D outputs, so `rect(0, 0, w, h)` is the top-left corner. Distinct from the [FLine.rect]
 * extension, which appends a rectangle path to a line you already have.
 */
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

/**
 * The line's colour as RGBA in `0..1` (sRGB, alpha included) — one colour for the stroke, the fill
 * and the node dots alike. Opaque white when never set.
 *
 * The default is *materialised on first read*: reading `color` from a line that has never had one
 * writes white into that line's property bag, so a line you merely inspected is no longer "unset".
 */
var FLine.color by Delegation<Vector4f> { vec(1.0, 1.0, 1.0, 1.0) }

/**
 * Per-node colour override in `0..1`, which is how one FLine carries a gradient: the GPU
 * point/triangle writers in `afield.graphics.StandardDrawing` use a node's own colour where it has
 * one and the whole line's [color] where it does not.
 *
 * Those writers read the node's raw property, so the default-on-read behaviour of [color] bites
 * harder here — merely *reading* `node.color` pins that node to white, and it stops following the
 * line's colour thereafter.
 */
var FLine.Node.color by Delegation<Vector4f> { vec(1.0, 1.0, 1.0, 1.0) }

/**
 * Stroke width, in the same units as the line's own points (pixels, for the 2D outputs). `null` —
 * the default — means "unset", which every renderer reads as 1.0 rather than as "invisible".
 */
var FLine.thickness by Delegation<Double?>()

/**
 * An AWT [Stroke] that replaces "stroke at one width" with "convert this line into the outline of
 * its own stroke" — dashes, tapers, calligraphic pens, anything `Stroke.createStrokedShape` can do.
 *
 * Only the Java2D `Shape` conversion (`FLinesAndJavaShapes.flineToJavaShape`) honours it, and there
 * it takes precedence over [thickness]; the Skiko, SVG and GPU line renderers ignore it entirely, so
 * a dashed line will look solid on screen. `null` by default.
 */
var FLine.thicken by Delegation<Stroke?>()

/**
 * Whether closed subpaths are flooded with [color]. `false` by default, and independent of
 * [stroked] — with both true the renderers fill and then draw the outline on top.
 */
var FLine.filled by Delegation<Boolean> { false }

/**
 * Whether the path is stroked at [thickness]. `true` by default; with this and [filled] both false
 * the geometry still exists and still hit-tests, but nothing is drawn except the node dots that
 * [pointed] asks for.
 */
var FLine.stroked by Delegation<Boolean> { true }

/**
 * Draw a dot at every node of the line — every `moveTo`/`lineTo`/`cubicTo` endpoint, not the
 * interior control points of a cubic and not `close`. Off by default. The fast way to see where a
 * path's points actually landed, and how a particle set gets drawn at all.
 */
var FLine.pointed by Delegation<Boolean> { false }

/**
 * Size of the dots [pointed] draws, 1.0 by default, overridable node by node with
 * [FLine.Node.pointSize].
 *
 * It is not a radius: the Skiko, SVG and three.js outputs all draw a dot of radius `pointSize * 3`,
 * so the default is a 6-unit-wide dot.
 */
var FLine.pointSize by Delegation<Double> { 1.0 }

/**
 * Per-node override of the line's [FLine.pointSize], under the same `* 3` radius convention. `null`
 * — the default, and unlike the line-level property this one is not written back on read — means
 * "follow the line".
 */
var FLine.Node.pointSize by Delegation<Double?>(null)

/**
 * What kind of thing on a figure a click landed on — the same three-way distinction the editable
 * FLine widget already draws its hit targets with (`data-fline-hit-kind` in `sliders.js`), now
 * reported to [FLine.onClick] as well.
 *
 * [UNKNOWN] is not a failure: it is what an output that only knows "this line was clicked" says, and
 * what a *stale cached* `sliders.js` posting the old two-field payload decodes to.
 */
enum class FLineHitKind {
    /** Within picking distance of a node — a `moveTo`/`lineTo`/`cubicTo` endpoint. */
    POINT,
    /** On (or within picking distance of) one segment, and [FLineIntersection.u] says where along it. */
    EDGE,
    /** Inside a closed subpath, with [FLineIntersection.u] and `v` local to that face. */
    FACE,
    /** The line was hit but the output could not say which part of it. */
    UNKNOWN
}

/**
 * Which convention [FLineIntersection.u] and `v` follow for a [FLineHitKind.FACE] hit — because there
 * is no one right answer for a face nobody chose, and a plausible wrong number is worse than a
 * labelled one.
 *
 * The output does not pick the convention; the *face* does.
 */
enum class FLineFaceCoordinates {
    /** Not a face hit, or a face this output refused to parameterise. `u` and `v` are `NaN`. */
    NONE,
    /**
     * True bilinear coordinates on a four-corner face: `u` runs corner 0 -> 1, `v` corner 0 -> 3, so
     * `(u, v)` is a position *on a panel* however the quad is skewed or projected.
     *
     * Only reported when the solve is **checked** and passes: the forward map has to reproduce the
     * click, and the answer has to land inside the patch. On a non-convex quad it can do neither — the
     * Newton iteration converges onto the fold — and then the hit says [BOUNDING_BOX] instead of a
     * number that looks fine and is not.
     */
    BILINEAR,
    /**
     * The click's position within the face's own bounding box (in the face's plane, for the three.js
     * output): `0..1` per axis, monotone, and **not** a parameterisation of the surface. What a face
     * with three, or five, or twenty-eight corners gets.
     */
    BOUNDING_BOX
}

/**
 * Hit information passed to [FLine.onClick] from the interactive line renderers — what a click on a
 * figure *returns*.
 *
 * Everything past [position] is optional in the wire sense: an output that cannot work a field out
 * leaves it at its default ([FLineHitKind.UNKNOWN], `-1`, `null`, `NaN`), so a callback written
 * against the two original fields still compiles and still behaves, and an old browser cache posting
 * the old payload still arrives. What fills them in today is the interactive SVG output
 * (`buildInteractiveSVG` → `data-fline-elements` → `__flineIntersectionFromSvg` in `sliders.js`);
 * see `dev_docs/fline_hit_records.md` for the whole chain and for what each output can promise.
 */
data class FLineIntersection(
    /**
     * Index into the line's `nodes` of the node nearest the click, or `-1` when the client did not
     * work one out.
     */
    val closestVertexIndex: Int,
    /**
     * Where the click landed, in the line's own coordinate space. The three.js output fills in all
     * three components; the 2D SVG output leaves `z` at 0 — [onElement] is the one to read for a
     * depth, because it interpolates `z` along the element that was hit.
     */
    val position: Vec3,
    /** Which kind of element was hit. [FLineHitKind.UNKNOWN] when the output does not distinguish. */
    val kind: FLineHitKind = FLineHitKind.UNKNOWN,
    /**
     * Index of the clicked line within the lines passed to that one `lines(...)` call, or `-1` when
     * unknown. The point of it: one shared `onClick` closure can serve a whole figure, instead of one
     * closure per part.
     */
    val lineIndex: Int = -1,
    /** The clicked line's [FLine.label], if it had one. */
    val label: String? = null,
    /**
     * Index into the line's `nodes` of the node that *is* this element: the node itself for a
     * [FLineHitKind.POINT], the node that draws the segment for an [FLineHitKind.EDGE], the `Close`
     * for a [FLineHitKind.FACE]. `-1` when unknown. Unlike [closestVertexIndex] this is the element,
     * not the nearest dot.
     */
    val nodeIndex: Int = -1,
    /** Which subpath (`moveTo`-delimited piece) of the line the element belongs to, or `-1`. */
    val subPathIndex: Int = -1,
    /** Ordinal of the element among its own kind within the line — edge 0, 1, 2…; face 0, 1… Or `-1`. */
    val elementIndex: Int = -1,
    /**
     * Where on the element the click landed, `0..1`, or `NaN` when it does not apply.
     *
     * For an [FLineHitKind.EDGE], along the segment: 0 at the segment's start node, 1 at its end. On a
     * cubic this is the **Bézier parameter**, not arc length, and the two are not interchangeable — on
     * an unevenly-controlled curve `u = 0.5` can be two thirds of the way along by length. It is the
     * parameter on purpose, because that is the number that indexes back into the curve the author
     * wrote; `FLineHitRecordProbeTest` pins the size of the disagreement on a deliberately lopsided
     * cubic so that nobody has to take this paragraph's word for it.
     *
     * For an [FLineHitKind.FACE], the first face coordinate — and [faceCoordinates] says what that
     * means.
     */
    val u: Double = Double.NaN,
    /** The second face coordinate for an [FLineHitKind.FACE], `NaN` otherwise. See [faceCoordinates]. */
    val v: Double = Double.NaN,
    /**
     * Which convention [u] and [v] follow for a face hit — bilinear on a four-corner face, its bounding
     * box otherwise, or a refusal. Always [FLineFaceCoordinates.NONE] for a non-face hit.
     */
    val faceCoordinates: FLineFaceCoordinates = FLineFaceCoordinates.NONE,
    /**
     * The point *on* the element nearest the click, in the line's own coordinate space, or `null`.
     *
     * For an [FLineHitKind.EDGE] this is the segment evaluated at [u], which means `z` is interpolated
     * along the segment: a 3D line drawn into the 2D SVG output recovers a real depth here even though
     * [position] cannot have one.
     */
    val onElement: Vec3? = null
)

/** Callback invoked when this FLine is clicked in an interactive output. */
var FLine.onClick by Delegation<((FLineIntersection) -> Unit)?>()

/**
 * A human name for this line, reported back as [FLineIntersection.label] when it is clicked and used
 * as the heading by [afield.graphics.inspect].
 *
 * Unset by default. Unlike [onClick] this is a plain string, so it survives serialisation and travels
 * with the line.
 */
var FLine.label by Delegation<String?>()

/**
 * Color to show when the pointer hovers over this FLine in an interactive SVG output.
 * When null no hover color change is applied.
 */
var FLine.hoverColor by Delegation<Vec4?>()

/**
 * One straight 2D segment from [a] to [b], styled in the same call — the named form of
 * `fline { moveTo(a); lineTo(b) }`, and what the graph and network examples draw their edges with.
 *
 * [color] is RGBA in `0..1` and is *copied* into the line, so mutating the vector afterwards leaves the
 * segment alone. Thickness is left unset, which the renderers read as 1.0. Pass `pointed = true` to also
 * dot both endpoints.
 */
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
