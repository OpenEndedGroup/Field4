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
    private val project: (DiagramFrame, Vector3f, Float) -> Vector3f,
) {
    /** Fixed clearance in text-local metres, unaffected by font scale. */
    var standoff: Float = 0f
        set(value) {
            require(value.isFinite() && value >= 0f)
            field = value
        }

    internal fun center(frame: DiagramFrame) = center.invoke(frame)
    internal fun resolve(frame: DiagramFrame, toward: Vector3f) = project(frame, toward, standoff)
}

/** Any existing text, particle, caret or path anchor can serve as a fixed endpoint. */
fun DiagramAnchor.attachment(standoff: Float = 0f) =
    DiagramAttachment(owner, { it.position(this) }) { f, toward, gap ->
        val p = f.position(this)
        val direction = Vector3f(toward).sub(p)
        if (direction.lengthSquared() > 1e-12f) p.fma(gap, direction.normalize()) else p
    }.also { it.standoff = standoff }

fun DiagramText.attach(
    site: DiagramTextSite = DiagramTextSite.CLOSEST,
    bounds: DiagramBoundsKind = DiagramBoundsKind.INK,
    underlineGap: Float = .014f,
    standoff: Float = 0f,
) = all.attach(site, bounds, underlineGap, standoff)

/**
 * Works on a word, a literal span or an entire multiline run. Sliding boundaries use per-line boxes,
 * not a large rectangle spanning the empty space to the right of shorter lines. Gap is in metres.
 * CLOSEST projects onto the filled boxes; BOUNDARY constrains to their perimeter instead.
 */
fun DiagramTextSpan.attach(
    site: DiagramTextSite = DiagramTextSite.CLOSEST,
    bounds: DiagramBoundsKind = DiagramBoundsKind.INK,
    underlineGap: Float = .014f,
    standoff: Float = 0f,
): DiagramAttachment = textAttachment(site, bounds, underlineGap, standoff, perLine = true)

internal fun DiagramTextSpan.textAttachment(
    site: DiagramTextSite,
    bounds: DiagramBoundsKind,
    underlineGap: Float,
    standoff: Float,
    perLine: Boolean,
): DiagramAttachment {
    require(underlineGap.isFinite() && underlineGap >= 0f)
    val box = DiagramTextBox(this, bounds)
    if (site == DiagramTextSite.CENTER) return box.center.attachment(standoff)
    if (site == DiagramTextSite.LEFT || site == DiagramTextSite.RIGHT) {
        val side = if (site == DiagramTextSite.LEFT) -1f else 1f
        val anchor = if (side < 0f) box.left else box.right
        return DiagramAttachment(run.owner, { it.position(anchor) }) { f, _, gap ->
            f.position(anchor).add(f.textDirection(run, side * gap, 0f, 0f))
        }.also { it.standoff = standoff }
    }
    // Text is immutable. Cache span boundaries here; measuring them reuses ReadingText's CPU cache.
    var start = 0
    val lines = if (!perLine) listOf(this) else run.text.split('\n').mapNotNull { line ->
        val a = max(start, this.start)
        val b = min(start + line.length, endExclusive)
        start += line.length + 1
        if (a < b) run.characters(a, b) else null
    }
    return DiagramAttachment(run.owner, { it.position(box.center) }) { frame, toward, gap ->
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
            val bl = point(r.left, r.bottom).add(frame.textDirection(run, -gap, -gap, 0f))
            val br = point(r.right, r.bottom).add(frame.textDirection(run, gap, -gap, 0f))
            val tl = point(r.left, r.top).add(frame.textDirection(run, -gap, gap, 0f))
            val tr = point(r.right, r.top).add(frame.textDirection(run, gap, gap, 0f))
            when (site) {
                DiagramTextSite.UNDERLINE -> {
                    val offset = frame.textDirection(run, 0f, -(underlineGap + gap), 0f)
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
    }.also { it.standoff = standoff }
}
