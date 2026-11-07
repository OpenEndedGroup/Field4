package afield.graphics.scene.reading

import afield.geometry.path.FLine
import afield.graphics.text.*
import afield.math.linalg.Vector3f
import kotlin.math.*

/**
 * Font units, baseline-left origin, y upward. Unlike an advance box, ink may overhang either side.
 */
data class DiagramTextRect(val left: Float, val bottom: Float, val right: Float, val top: Float) {
    val width
        get() = right - left

    val height
        get() = top - bottom

    val centerX
        get() = (left + right) * .5f

    val centerY
        get() = (bottom + top) * .5f

    /** A local font-space rectangle, ready for DiagramFrame.textLine / textPath. */
    fun outline(): FLine =
        FLine()
            .moveTo(left, bottom)
            .lineTo(right, bottom)
            .lineTo(right, top)
            .lineTo(left, top)
            .close()
}

enum class DiagramBoundsKind {
    INK,
    NO_DESCENDERS,
    EM,
    CAPS,
    X_HEIGHT,
    ADVANCE,
}

/**
 * Prepared geometry, not a guess from character counts. Spaces occupy advances but contain no ink.
 */
data class DiagramTextMetrics(
    val ink: DiagramTextRect,
    val advance: DiagramTextRect,
    val ascent: Float,
    val descent: Float,
    val leading: Float,
    val capHeight: Float,
    val xHeight: Float,
    val baseline: Float,
    val hasInk: Boolean,
    val lastBaseline: Float = baseline,
    val body: DiagramTextRect =
        DiagramTextRect(ink.left, max(ink.bottom, baseline), ink.right, max(ink.top, baseline)),
) {
    val width
        get() = advance.width

    val noDescenders
        get() = body

    fun bounds(kind: DiagramBoundsKind): DiagramTextRect =
        when (kind) {
            DiagramBoundsKind.INK -> ink
            DiagramBoundsKind.NO_DESCENDERS -> noDescenders
            DiagramBoundsKind.EM ->
                DiagramTextRect(
                    advance.left,
                    lastBaseline - descent,
                    advance.right,
                    baseline + ascent,
                )
            DiagramBoundsKind.CAPS ->
                DiagramTextRect(advance.left, lastBaseline, advance.right, baseline + capHeight)
            DiagramBoundsKind.X_HEIGHT ->
                DiagramTextRect(advance.left, lastBaseline, advance.right, baseline + xHeight)
            DiagramBoundsKind.ADVANCE -> advance
        }
}

/** Stable anchors resolve against current physics and score transforms; padding is in metres. */
class DiagramTextBox internal constructor(val span: DiagramTextSpan, val kind: DiagramBoundsKind) {
    fun at(x: Number, y: Number): DiagramAnchor {
        val u = x.toFloat()
        val v = y.toFloat()
        require(u.isFinite() && v.isFinite())
        return DiagramAnchor(span.run.owner) { f ->
            val r = f.measure(span).bounds(kind)
            f.textPoint(span.run, r.left + r.width * u, r.bottom + r.height * v)
        }
    }

    val bottomLeft
        get() = at(0, 0)

    val bottomRight
        get() = at(1, 0)

    val topLeft
        get() = at(0, 1)

    val topRight
        get() = at(1, 1)

    val center
        get() = at(.5, .5)

    val left
        get() = at(0, .5)

    val right
        get() = at(1, .5)

    val top
        get() = at(.5, 1)

    val bottom
        get() = at(.5, 0)

    /** A rotated rectangle in diagram space, not the AABB of that rectangle after rotation. */
    fun outline(frame: DiagramFrame, padding: Float = 0f): FLine {
        require(padding.isFinite())
        val run = span.run
        val r = frame.measure(span).bounds(kind)
        val p =
            listOf(r.left to r.bottom, r.right to r.bottom, r.right to r.top, r.left to r.top)
                .mapIndexed { i, (x, y) ->
                    frame
                        .textPoint(run, x, y)
                        .add(
                            frame.textDirection(
                                run,
                                if (i == 0 || i == 3) -padding else padding,
                                if (i < 2) -padding else padding,
                                0f,
                            ),
                        )
                }
        return FLine().moveTo(p[0]).lineTo(p[1]).lineTo(p[2]).lineTo(p[3]).close()
    }
}

/** An offset in the text's rotated axes, measured in metres rather than font units. */
fun DiagramAnchor.alongText(
    run: DiagramText,
    x: Number = 0,
    y: Number = 0,
    z: Number = 0,
): DiagramAnchor {
    require(owner === run.owner)
    val dx = x.toFloat()
    val dy = y.toFloat()
    val dz = z.toFloat()
    require(listOf(dx, dy, dz).all { it.isFinite() })
    return DiagramAnchor(owner) { f -> f.position(this).add(f.textDirection(run, dx, dy, dz)) }
}

/** Any font-space FLine follows the same transform as the glyphs, with access to real metrics. */
fun ReadingDiagramBuilder.textPath(
    id: String,
    run: DiagramText,
    shape: DiagramTextMetrics.() -> FLine,
    block: DiagramPath.() -> Unit = {},
): DiagramPath = path(id, shape = { textLine(run, measure(run).shape()) }, block = block)

fun ReadingDiagramBuilder.rectangle(
    id: String,
    bounds: DiagramTextBox,
    padding: Float = 0f,
    block: DiagramPath.() -> Unit = {},
): DiagramPath = path(id, shape = { bounds.outline(this, padding) }, block = block)

/** Analytic cubic extrema of the actual Slug source outlines, after glyph placement. */
internal fun readingInkBounds(line: FLine): DiagramTextRect? {
    var x0 = Float.POSITIVE_INFINITY
    var y0 = Float.POSITIVE_INFINITY
    var x1 = Float.NEGATIVE_INFINITY
    var y1 = Float.NEGATIVE_INFINITY
    fun add(p: Vector3f) {
        x0 = min(x0, p.x)
        y0 = min(y0, p.y)
        x1 = max(x1, p.x)
        y1 = max(y1, p.y)
    }
    var previous: Vector3f? = null
    fun roots(a: Float, b: Float, c: Float, d: Float): List<Float> {
        val qa = -a + 3 * b - 3 * c + d
        val qb = 2 * (a - 2 * b + c)
        val qc = b - a
        if (abs(qa) < 1e-8f) return if (abs(qb) < 1e-8f) emptyList() else listOf(-qc / qb)
        val discriminant = qb * qb - 4 * qa * qc
        if (discriminant < 0) return emptyList()
        val s = sqrt(discriminant)
        return listOf((-qb + s) / (2 * qa), (-qb - s) / (2 * qa))
    }
    for (node in line.nodes) when (node) {
        is FLine.MoveTo -> {
            previous = node.to
            add(node.to)
        }
        is FLine.LineTo -> {
            previous = node.to
            add(node.to)
        }
        is FLine.CubicTo -> {
            val a = previous ?: continue
            val b = node.c1
            val c = node.c2
            val d = node.to
            add(d)
            for (t in roots(a.x, b.x, c.x, d.x) + roots(a.y, b.y, c.y, d.y)) if (t > 0f && t < 1f) {
                val u = 1f - t
                add(
                    Vector3f(a)
                        .mul(u * u * u)
                        .fma(3 * u * u * t, b)
                        .fma(3 * u * t * t, c)
                        .fma(t * t * t, d),
                )
            }
            previous = d
        }
        is FLine.Close -> previous = null
    }
    return if (x0.isFinite()) DiagramTextRect(x0, y0, x1, y1) else null
}

internal fun ReadingText.prepareMetrics(start: Int, end: Int): DiagramTextMetrics {
    require(start >= 0 && end > start && end <= text.length)
    val l = requireNotNull(layout) { "Text measurements require a prepared layout" }
    val boundaries =
        l.glyphs.map { it.charIndex }.toSet() +
            text.length +
            text.indices.filter { text[it] == '\n' }
    require(start in boundaries && end in boundaries) {
        "Span boundary splits a shaped glyph cluster in '$text'"
    }
    val glyphs = l.glyphs.filter { it.charIndex in start until end }
    require(glyphs.isNotEmpty())
    val lines = glyphs.groupBy { it.lineIndex }
    val left = lines.values.minOf { it.first().origin.x }
    val right = lines.maxOf { (index, _) ->
        l.glyphs.firstOrNull { it.charIndex == end && it.lineIndex == index }?.origin?.x
            ?: l.lines[index].advanceWidth.toFloat()
    }
    val baseline = -l.lines[glyphs.first().lineIndex].baselineY.toFloat()
    val lastBaseline = -l.lines[glyphs.last().lineIndex].baselineY.toFloat()
    val perLine = HashMap<Int, FLine>()
    val outline = FLine()
    for (g in glyphs) if (!g.glyph.isBlank) {
        val transform = g.transform()
        val part =
            g.glyph.outline.copy().transform { p ->
                val q = transform.transform(java.awt.geom.Point2D.Float(p.x, p.y), null)
                Vector3f(q.x.toFloat(), -q.y.toFloat(), 0f)
            }
        outline.nodes.addAll(part.nodes)
        perLine.getOrPut(g.lineIndex) { FLine() }.nodes.addAll(part.nodes)
    }
    val ink = readingInkBounds(outline)
    val selectedFaces = glyphs.map { glyphFaces[it.charIndex] ?: l.typeface }.distinct()
    val ascent = selectedFaces.maxOf { it.ascent }.toFloat()
    val descent = selectedFaces.maxOf { it.descent }.toFloat()
    fun height(sample: String): Float = selectedFaces.maxOf { face ->
        readingInkBounds(layoutText(sample, face).toFLine().transform { Vector3f(it.x, -it.y, 0f) })
            ?.top ?: 0f
    }
    val bodies = perLine.mapNotNull { (index, shape) ->
        readingInkBounds(shape)?.let {
            val y = -l.lines[index].baselineY.toFloat()
            DiagramTextRect(it.left, max(it.bottom, y), it.right, max(it.top, y))
        }
    }
    val body =
        if (bodies.isEmpty()) DiagramTextRect(left, baseline, left, baseline)
        else
            DiagramTextRect(
                bodies.minOf { it.left },
                bodies.minOf { it.bottom },
                bodies.maxOf { it.right },
                bodies.maxOf { it.top },
            )
    return DiagramTextMetrics(
        ink ?: DiagramTextRect(left, baseline, left, baseline),
        DiagramTextRect(left, lastBaseline, right, baseline),
        ascent,
        descent,
        selectedFaces.maxOf { it.leading }.toFloat(),
        height("H"),
        height("x"),
        baseline,
        ink != null,
        lastBaseline,
        body,
    )
}
