package afield.graphics.scene.reading

import afield.math.linalg.Vector3f
import kotlin.math.*

/** Fixed landmarks, or closest-point constraints on the selected text's prepared line boxes. */
enum class DiagramTextSite {
    CENTER, LEFT, RIGHT, CLOSEST, UNDERLINE, LEFT_MARGIN, RIGHT_MARGIN, BOUNDARY,
}

/** A connection endpoint. Sliding sites project toward the adjacent interior node in world space. */
class DiagramAttachment internal constructor(
    internal val owner: Any,
    private val center: (DiagramFrame) -> Vector3f,
    private val project: (DiagramFrame, Vector3f) -> Vector3f,
) {
    internal fun center(frame: DiagramFrame) = center.invoke(frame)
    internal fun resolve(frame: DiagramFrame, toward: Vector3f) = project(frame, toward)
}

/** Any existing text, particle, caret or path anchor can serve as a fixed endpoint. */
fun DiagramAnchor.attachment() = DiagramAttachment(owner, { it.position(this) }) { f, _ -> f.position(this) }

fun DiagramText.attach(
    site: DiagramTextSite = DiagramTextSite.CLOSEST,
    bounds: DiagramBoundsKind = DiagramBoundsKind.INK,
    underlineGap: Float = .014f,
) = all.attach(site, bounds, underlineGap)

/**
 * Works on a word, a literal span or an entire multiline run. Sliding boundaries use per-line boxes,
 * not a large rectangle spanning the empty space to the right of shorter lines. Gap is in metres.
 * CLOSEST projects onto the filled boxes; BOUNDARY constrains to their perimeter instead.
 */
fun DiagramTextSpan.attach(
    site: DiagramTextSite = DiagramTextSite.CLOSEST,
    bounds: DiagramBoundsKind = DiagramBoundsKind.INK,
    underlineGap: Float = .014f,
): DiagramAttachment {
    require(underlineGap.isFinite() && underlineGap >= 0f)
    val box = DiagramTextBox(this, bounds)
    when (site) {
        DiagramTextSite.CENTER -> return box.center.attachment()
        DiagramTextSite.LEFT -> return box.left.attachment()
        DiagramTextSite.RIGHT -> return box.right.attachment()
        else -> Unit
    }
    // Text is immutable. Cache span boundaries here; measuring them reuses ReadingText's CPU cache.
    var start = 0
    val lines = run.text.split('\n').mapNotNull { line ->
        val a = max(start, this.start)
        val b = min(start + line.length, endExclusive)
        start += line.length + 1
        if (a < b) run.characters(a, b) else null
    }
    return DiagramAttachment(run.owner, { it.position(box.center) }) { frame, toward ->
        var closest: Vector3f? = null
        var distance = Float.POSITIVE_INFINITY
        fun candidate(a: Vector3f, b: Vector3f) {
            val ab = Vector3f(b).sub(a)
            val length2 = ab.lengthSquared()
            val t = if (length2 > 1e-16f) Vector3f(toward).sub(a).dot(ab) / length2 else 0f
            val q = Vector3f(a).fma(t.coerceIn(0f, 1f), ab)
            val d = q.distanceSquared(toward)
            if (d < distance) { distance = d; closest = q }
        }
        for (line in lines) {
            val metrics = frame.measure(line)
            val r = metrics.bounds(bounds)
            fun point(x: Float, y: Float) = frame.textPoint(run, x, y)
            val bl = point(r.left, r.bottom)
            val br = point(r.right, r.bottom)
            val tl = point(r.left, r.top)
            val tr = point(r.right, r.top)
            when (site) {
                DiagramTextSite.UNDERLINE -> {
                    val offset = frame.textDirection(run, 0f, -underlineGap, 0f)
                    candidate(point(metrics.advance.left, metrics.baseline).add(offset),
                        point(metrics.advance.right, metrics.baseline).add(offset))
                }
                DiagramTextSite.LEFT_MARGIN -> candidate(bl, tl)
                DiagramTextSite.RIGHT_MARGIN -> candidate(br, tr)
                DiagramTextSite.CLOSEST -> {
                    // Project onto a possibly rotated/scaled rectangle, with safe degenerate axes.
                    val x = Vector3f(br).sub(bl)
                    val y = Vector3f(tl).sub(bl)
                    val v = Vector3f(toward).sub(bl)
                    val u = if (x.lengthSquared() > 1e-16f) (v.dot(x) / x.lengthSquared()).coerceIn(0f, 1f) else 0f
                    val w = if (y.lengthSquared() > 1e-16f) (v.dot(y) / y.lengthSquared()).coerceIn(0f, 1f) else 0f
                    val q = Vector3f(bl).fma(u, x).fma(w, y)
                    candidate(q, q)
                }
                else -> { candidate(bl, br); candidate(br, tr); candidate(tr, tl); candidate(tl, bl) }
            }
        }
        closest ?: frame.position(box.center)
    }
}
