package afield.graphics.scene.reading

import afield.geometry.path.FLine
import afield.math.linalg.Vector3f
import kotlin.math.*

enum class DiagramTextFrameMode { BOUNDS, LINE_UNION }

/** A light frame and its sliding endpoints share the exact same cached boundary. */
class DiagramTextFrame internal constructor(
    val span: DiagramTextSpan,
    val path: DiagramPath,
    standoff: Float,
    var mode: DiagramTextFrameMode = DiagramTextFrameMode.BOUNDS,
) {
    /** Optional backdrop replacement; the outline remains ordinary additive ink. */
    val frost = ReadingFrostSettings()

    /** Extra length at EACH end is sideLength * fraction + distance, in text-local metres. */
    var overstrokeFraction = 0f
        set(value) { require(value.isFinite() && value >= 0f); field = value }
    var overstrokeDistance = 0f
        set(value) { require(value.isFinite() && value >= 0f); field = value }

    // Mechanical holders fit both ends to one aspect. Ordinary frames keep their measured bounds.
    internal var boundsAspect: ((DiagramFrame) -> Double)? = null

    private val endpoints = mutableListOf<DiagramAttachment>()
    var standoff: Float = standoff
        set(value) {
            require(value.isFinite() && value >= 0f)
            field = value
            endpoints.forEach { it.standoff = value }
        }
    /** Fill small side steps toward wider adjacent rows when the difference is at most 2 × standoff. */
    var mergeSmallSteps = true

    private val lines = run {
        var start = 0
        span.run.text.split('\n').mapIndexedNotNull { row, line ->
            val a = max(start, span.start)
            val b = min(start + line.length, span.endExclusive)
            start += line.length + 1
            if (a < b) row to span.run.characters(a, b) else null
        }
    }
    private data class GeometryKey(val mode: DiagramTextFrameMode, val gap: Float, val scale: Float,
        val merge: Boolean, val aspect: Double?, val metrics: List<DiagramTextMetrics>)
    private var cachedKey: GeometryKey? = null
    private var cachedGeometry: ReadingFrameGeometry? = null

    init { require(standoff.isFinite() && standoff >= 0f) }

    // Geometry is in text-local metres. Translation, head pose and plane orientation do not
    // invalidate the union; only typography, scale, mode, simplification or clearance do.
    internal fun geometry(frame: DiagramFrame, gap: Float = standoff): ReadingFrameGeometry {
        val measured = if (mode == DiagramTextFrameMode.BOUNDS) listOf(0 to frame.measure(span))
            else lines.map { (row, line) -> row to frame.measure(line) }.filter { it.second.hasInk }
                .ifEmpty { listOf(0 to frame.measure(span)) }
        val scale = frame.textScale(span.run)
        val aspect = boundsAspect?.invoke(frame)?.takeIf { mode == DiagramTextFrameMode.BOUNDS }
        val key = GeometryKey(mode, gap, scale, mergeSmallSteps, aspect, measured.map { it.second })
        if (key == cachedKey) return requireNotNull(cachedGeometry)
        val rectlets = measured.map { (row, metrics) ->
            val r = metrics.bounds(DiagramBoundsKind.FRAME)
            ReadingRectlet(min(r.left * scale, r.right * scale).toDouble(),
                min(r.bottom * scale, r.top * scale).toDouble(),
                max(r.left * scale, r.right * scale).toDouble(),
                max(r.bottom * scale, r.top * scale).toDouble(), row)
        }
        val fitted = if (aspect == null) rectlets else rectlets.map { r ->
            val width = r.right - r.left + gap * 2
            val height = r.top - r.bottom + gap * 2
            val extraX = max(0.0, height * aspect - width) * .5
            val extraY = max(0.0, width / aspect - height) * .5
            r.copy(left = r.left - extraX, right = r.right + extraX,
                bottom = r.bottom - extraY, top = r.top + extraY)
        }
        return readingRectletUnion(fitted, gap.toDouble(), mode == DiagramTextFrameMode.LINE_UNION && mergeSmallSteps)
            .also { cachedKey = key; cachedGeometry = it }
    }

    /** Natural padded aspect, without a mechanical holder's aspect constraint. */
    internal fun naturalAspect(frame: DiagramFrame): Double {
        val r = frame.measure(span).bounds(DiagramBoundsKind.FRAME)
        val scale = abs(frame.textScale(span.run))
        return max(1e-9, r.width * scale + standoff * 2.0) /
            max(1e-9, r.height * scale + standoff * 2.0)
    }

    private fun world(frame: DiagramFrame, p: ReadingFramePoint): Vector3f =
        frame.textPoint(span.run, 0f, 0f).add(frame.textDirection(span.run, p.x.toFloat(), p.y.toFloat(), 0f))

    /** BL, BR, TR, TL of the overall bounding rectangle, even for a stepped union. */
    internal fun corners(frame: DiagramFrame): List<Vector3f> = geometry(frame).let { r ->
        listOf(ReadingFramePoint(r.left, r.bottom), ReadingFramePoint(r.right, r.bottom),
            ReadingFramePoint(r.right, r.top), ReadingFramePoint(r.left, r.top)).map { world(frame, it) }
    }

    internal fun outline(frame: DiagramFrame): FLine {
        val r = geometry(frame)
        val pose = frame.pose(path)
        val fraction = pose.overstrokeFraction ?: overstrokeFraction
        val distance = pose.overstrokeDistance ?: overstrokeDistance
        val strokes = readingFrameOverstrokes(r, fraction.toDouble(), distance.toDouble())
        return FLine().apply {
            if (mode == DiagramTextFrameMode.BOUNDS) {
                val points = corners(frame)
                moveTo(points.first())
                points.drop(1).forEach { lineTo(it) }
                close()
            } else if (mode == DiagramTextFrameMode.LINE_UNION) {
                for (contour in r.contours) {
                    moveTo(world(frame, contour.first()))
                    contour.drop(1).forEach { lineTo(world(frame, it)) }
                    close()
                }
            }
            // Only the four OUTER bounds contribute. Never prolong a rectlet's internal seam.
            for ((a, b) in strokes) { moveTo(world(frame, a)); lineTo(world(frame, b)) }
        }
    }

    /** Concave, disconnected and holed outlines project only onto their exposed boundary. */
    fun attach(site: DiagramTextSite = DiagramTextSite.BOUNDARY): DiagramAttachment =
        DiagramAttachment(span.run.owner, { f -> world(f, geometry(f).center).add(f.pose(path).offset) }) { frame, toward, gap ->
            val pathOffset = frame.pose(path).offset
            val offset = Vector3f(toward).sub(frame.textPoint(span.run, 0f, 0f)).sub(pathOffset)
            val x = frame.textDirection(span.run, 1f, 0f, 0f)
            val y = frame.textDirection(span.run, 0f, 1f, 0f)
            val query = ReadingFramePoint(offset.dot(x).toDouble(), offset.dot(y).toDouble())
            world(frame, geometry(frame, gap).project(query, site)).add(pathOffset)
        }.also { it.standoff = standoff; endpoints.add(it) }
}

/** Actual ink encloses used descenders; text without descenders stops at its baseline plus margin. */
fun ReadingDiagramBuilder.textFrame(
    id: String,
    span: DiagramTextSpan,
    standoff: Float = .02f,
    mode: DiagramTextFrameMode = DiagramTextFrameMode.BOUNDS,
    overstrokeFraction: Float = 0f,
    overstrokeDistance: Float = 0f,
    block: DiagramPath.() -> Unit = {},
): DiagramTextFrame {
    lateinit var frame: DiagramTextFrame
    val border = path(id, shape = { frame.outline(this) }) {
        width = .0008f
        block()
    }
    frame = DiagramTextFrame(span, border, standoff, mode).apply {
        this.overstrokeFraction = overstrokeFraction
        this.overstrokeDistance = overstrokeDistance
    }
    textFrames.add(frame)
    return frame
}

fun ReadingDiagramBuilder.textFrame(
    id: String,
    run: DiagramText,
    standoff: Float = .02f,
    mode: DiagramTextFrameMode = DiagramTextFrameMode.BOUNDS,
    overstrokeFraction: Float = 0f,
    overstrokeDistance: Float = 0f,
    block: DiagramPath.() -> Unit = {},
) = textFrame(id, run.all, standoff, mode, overstrokeFraction, overstrokeDistance, block)

/** Eight outward extensions of the overall rectangle, in text-local metres. */
internal fun readingFrameOverstrokes(
    r: ReadingFrameGeometry,
    fraction: Double,
    distance: Double,
): List<Pair<ReadingFramePoint, ReadingFramePoint>> {
    require(fraction.isFinite() && fraction >= 0 && distance.isFinite() && distance >= 0)
    if (fraction == 0.0 && distance == 0.0) return emptyList()
    val corners = listOf(ReadingFramePoint(r.left, r.bottom), ReadingFramePoint(r.right, r.bottom),
        ReadingFramePoint(r.right, r.top), ReadingFramePoint(r.left, r.top))
    return corners.indices.flatMap { i ->
        val a = corners[i]
        val b = corners[(i + 1) % 4]
        val length = hypot(b.x - a.x, b.y - a.y)
        if (length <= 1e-12) emptyList() else {
            val extension = length * fraction + distance
            val x = (b.x - a.x) / length * extension
            val y = (b.y - a.y) / length * extension
            val start = ReadingFramePoint(a.x - x, a.y - y)
            val end = ReadingFramePoint(b.x + x, b.y + y)
            listOf(start to a, b to end)
        }
    }
}
