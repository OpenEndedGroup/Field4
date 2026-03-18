package afield.graphics

import afield.stdlib.*
import field.graphics.FLinesAndJavaShapes
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.MultiLineString
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.buffer.OffsetCurve
import org.locationtech.jts.operation.buffer.VariableBuffer
import org.locationtech.jts.operation.linemerge.LineMerger
import org.locationtech.jts.operation.polygonize.Polygonizer
import org.locationtech.jts.operation.union.UnaryUnionOp
import java.awt.BasicStroke
import java.awt.Shape
import java.awt.geom.FlatteningPathIterator
import java.awt.geom.GeneralPath
import java.awt.geom.PathIterator
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Join style for Java2D/JTS offset and buffer operations.
 */
enum class FLineStrokeJoin(internal val awtJoin: Int, internal val jtsJoin: Int) {
    MITER(BasicStroke.JOIN_MITER, BufferParameters.JOIN_MITRE),
    ROUND(BasicStroke.JOIN_ROUND, BufferParameters.JOIN_ROUND),
    BEVEL(BasicStroke.JOIN_BEVEL, BufferParameters.JOIN_BEVEL)
}

/**
 * Cap style for Java2D/JTS offset and buffer operations.
 */
enum class FLineStrokeCap(internal val awtCap: Int, internal val jtsCap: Int) {
    BUTT(BasicStroke.CAP_BUTT, BufferParameters.CAP_FLAT),
    ROUND(BasicStroke.CAP_ROUND, BufferParameters.CAP_ROUND),
    SQUARE(BasicStroke.CAP_SQUARE, BufferParameters.CAP_SQUARE)
}

/**
 * Ribbon result split into its left edge, right edge, and closed shell.
 */
data class FLineRibbon(
    val left: FLine,
    val right: FLine,
    val shell: FLine
) {
    override fun toString(): String = prettySummary(
        "FLineRibbon",
        "left" to left,
        "right" to right,
        "shell" to shell
    )
}

/**
 * JTS split result: largest outer loop, interior loops, and open line components.
 */
data class FLineSplit(
    val outerLoop: FLine?,
    val innerLoops: List<FLine>,
    val lines: List<FLine>
) {
    override fun toString(): String = prettySummary(
        "FLineSplit",
        "outerLoop" to outerLoop,
        "innerLoops" to innerLoops.size,
        "lines" to lines.size
    )
}

/**
 * Create left/right offset edges at equal distance from the centerline.
 */
fun FLine.byBidirectionalOffsetting(
    distance: Float,
    samples: Int = 200,
    sampler: FLineSampler? = null
): Pair<FLine, FLine> {
    val d = abs(distance)
    val ribbon = offsetRibbon(
        samples = samples,
        sampler = sampler,
        leftDistance = { _, _ -> d },
        rightDistance = { _, _ -> d }
    )
    return ribbon.left to ribbon.right
}

/**
 * Create left/right offset edges with independent distance functions.
 */
fun FLine.byVariableThicknessOffsetting(
    leftDistance: ParameterFunction,
    rightDistance: ParameterFunction = leftDistance,
    samples: Int = 200,
    sampler: FLineSampler? = null
): Pair<FLine, FLine> {
    val ribbon = offsetRibbon(
        samples = samples,
        sampler = sampler,
        leftDistance = { t, p -> leftDistance.evaluate(t, p).coerceAtLeast(0f) },
        rightDistance = { t, p -> rightDistance.evaluate(t, p).coerceAtLeast(0f) }
    )
    return ribbon.left to ribbon.right
}

/**
 * Build a constant-thickness ribbon polygon around this line.
 *
 * [thickness] is full ribbon width.
 */
fun FLine.byRibboning(
    thickness: Float,
    samples: Int = 200,
    sampler: FLineSampler? = null
): FLine {
    val half = abs(thickness) * 0.5f
    return offsetRibbon(
        samples = samples,
        sampler = sampler,
        leftDistance = { _, _ -> half },
        rightDistance = { _, _ -> half }
    ).shell
}

/**
 * Build a variable-thickness ribbon polygon around this line.
 *
 * [thicknessFunc] returns full ribbon width at each sample.
 */
fun FLine.byVariableRibboning(
    thicknessFunc: ParameterFunction,
    samples: Int = 200,
    sampler: FLineSampler? = null
): FLine {
    return offsetRibbon(
        samples = samples,
        sampler = sampler,
        leftDistance = { t, p -> (thicknessFunc.evaluate(t, p) * 0.5f).coerceAtLeast(0f) },
        rightDistance = { t, p -> (thicknessFunc.evaluate(t, p) * 0.5f).coerceAtLeast(0f) }
    ).shell
}

/**
 * Java2D stroke-based thickening using BasicStroke; good quality for 2D cases.
 *
 * [strokeWidth] is full stroke width.
 */
fun FLine.byJavaOffsetting(
    strokeWidth: Float,
    cap: FLineStrokeCap = FLineStrokeCap.ROUND,
    join: FLineStrokeJoin = FLineStrokeJoin.ROUND,
    miterLimit: Float = 10f
): FLine {
    val raw = FLinesAndJavaShapes.flineToJavaShape_notThickened(this)
    val stroked = BasicStroke(
        strokeWidth.coerceAtLeast(0f),
        cap.awtCap,
        join.awtJoin,
        miterLimit.coerceAtLeast(1f)
    ).createStrokedShape(raw)
    return FLinesAndJavaShapes.javaShapeToFLine(stroked)
}

/**
 * JTS buffer for this line (2D), returning filled geometry as an FLine.
 */
fun FLine.byJtsBuffering(
    distance: Double,
    flatness: Double = 0.5,
    quadrantSegments: Int = 8,
    join: FLineStrokeJoin = FLineStrokeJoin.ROUND,
    cap: FLineStrokeCap = FLineStrokeCap.ROUND,
    miterLimit: Double = 5.0
): FLine {
    val splitter = JtsCurveSplitter(flatness = flatness)
    val multi = splitter.toMultiLineString(this) ?: return FLine()
    val params = BufferParameters().apply {
        setQuadrantSegments(quadrantSegments.coerceAtLeast(1))
        setJoinStyle(join.jtsJoin)
        setEndCapStyle(cap.jtsCap)
        setMitreLimit(miterLimit.coerceAtLeast(1.0))
    }
    val buffered = BufferOp.bufferOp(multi, distance, params)
    return splitter.geometryToFLine(buffered)
}

/**
 * JTS offset-curve extraction (2D).
 */
fun FLine.byJtsOffsetting(
    distance: Double,
    flatness: Double = 0.5
): FLine {
    val splitter = JtsCurveSplitter(flatness = flatness)
    val multi = splitter.toMultiLineString(this) ?: return FLine()
    val offset = OffsetCurve.getCurve(multi, distance)
    return splitter.geometryToFLine(offset)
}

/**
 * JTS variable buffer sampled from this curve.
 */
fun FLine.byJtsVariableBuffering(
    numSamples: Int = 128,
    distance: (Double) -> Double,
    sampler: FLineSampler? = null,
    flatness: Double = 0.5
): FLine {
    val pieces = this.pieces().filter { it.nodes.size > 1 }.ifEmpty {
        if (this.nodes.size > 1) listOf(this) else emptyList()
    }
    if (pieces.isEmpty()) return FLine()

    val geoms = mutableListOf<Geometry>()
    for (piece in pieces) {
        val sampled = dedupeSeries2D(piece.sampleSeries(numSamples.coerceAtLeast(2), sampler = sampler))
        if (sampled.size < 2) continue
        val coords = sampled.map { Coordinate(it.point.x.toDouble(), it.point.y.toDouble()) }.toTypedArray()
        val distances = DoubleArray(sampled.size) { i -> distance(sampled[i].t.toDouble()) }
        val g = VariableBuffer(JtsCurveSplitter.GF.createLineString(coords), distances).result
        if (!g.isEmpty) geoms += g
    }

    if (geoms.isEmpty()) return FLine()
    val merged = UnaryUnionOp.union(geoms)
    return JtsCurveSplitter(flatness = flatness).geometryToFLine(merged)
}

/**
 * Split potentially self-crossing geometry into loops and open lines using JTS noding/polygonization.
 */
fun FLine.byJtsSplitting(flatness: Double = 0.5): FLineSplit {
    return JtsCurveSplitter(flatness = flatness).split(this)
}

private fun FLine.offsetRibbon(
    samples: Int,
    sampler: FLineSampler?,
    leftDistance: (Float, Vec3) -> Float,
    rightDistance: (Float, Vec3) -> Float
): FLineRibbon {
    val series = this.sampleSeries(samples.coerceAtLeast(2), sampler = sampler)
    if (series.size < 2) return FLineRibbon(FLine(), FLine(), FLine())

    val normals = planarNormals(series)
    val left = ArrayList<Vec3>(series.size)
    val right = ArrayList<Vec3>(series.size)

    for (i in series.indices) {
        val s = series[i]
        val n = normals[i]
        val dl = leftDistance(s.t, s.point).coerceAtLeast(0f)
        val dr = rightDistance(s.t, s.point).coerceAtLeast(0f)
        left += Vec3(s.point).add(Vec3(n).mul(dl))
        right += Vec3(s.point).sub(Vec3(n).mul(dr))
    }

    return FLineRibbon(
        left = toPolyline(left),
        right = toPolyline(right),
        shell = toRibbonShell(left, right)
    )
}

private fun toPolyline(points: List<Vec3>): FLine {
    val f = FLine()
    if (points.isEmpty()) return f
    f.moveTo(points.first())
    for (i in 1 until points.size) f.lineTo(points[i])
    return f
}

private fun toRibbonShell(left: List<Vec3>, right: List<Vec3>): FLine {
    val f = FLine()
    if (left.isEmpty() || right.isEmpty()) return f

    f.moveTo(left.first())
    for (i in 1 until left.size) f.lineTo(left[i])
    for (i in right.lastIndex downTo 0) f.lineTo(right[i])
    f.close()
    return f
}

private fun planarNormals(series: List<FLineSample>): List<Vec3> {
    if (series.isEmpty()) return emptyList()
    val normals = MutableList(series.size) { vec(0f, 1f, 0f) }
    var lastNormal = vec(0f, 1f, 0f)
    val n = series.lastIndex
    for (i in series.indices) {
        val pPrev = series[(i - 1).coerceAtLeast(0)].point
        val pNext = series[(i + 1).coerceAtMost(n)].point
        val tx = pNext.x - pPrev.x
        val ty = pNext.y - pPrev.y
        val len = sqrt(tx * tx + ty * ty)
        val candidate = if (len > 1e-6f) {
            vec(-ty / len, tx / len, 0f)
        } else {
            Vec3(lastNormal)
        }
        if (i > 0 && candidate.dot(normals[i - 1]) < 0f) candidate.mul(-1f)
        normals[i] = candidate
        lastNormal = Vec3(candidate)
    }
    return normals
}

private fun dedupeSeries2D(series: List<FLineSample>, eps: Float = 1e-4f): List<FLineSample> {
    if (series.isEmpty()) return emptyList()
    val out = ArrayList<FLineSample>(series.size)
    var last: FLineSample? = null
    for (s in series) {
        val l = last
        if (l == null || (abs(s.point.x - l.point.x) > eps || abs(s.point.y - l.point.y) > eps)) {
            out += s
            last = s
        }
    }
    return out
}

/**
 * JTS helper for splitting/flattening/shape conversion.
 */
class JtsCurveSplitter(private val flatness: Double = 0.5) {
    companion object {
        internal val GF = GeometryFactory()
        private const val EPS = 1e-6
    }

    fun split(line: FLine): FLineSplit {
        val multi = toMultiLineString(line) ?: return FLineSplit(null, emptyList(), emptyList())
        val noded = UnaryUnionOp.union(multi)
        val merged = mergeLineStrings(noded)

        val closedLoops = merged.filter { it.coordinates.size > 1 && it.coordinates.first().equals2D(it.coordinates.last()) }
        val openCurves = merged.filterNot { it.coordinates.size > 1 && it.coordinates.first().equals2D(it.coordinates.last()) }

        val polygonizer = Polygonizer(true)
        merged.forEach { polygonizer.add(it) }
        @Suppress("UNCHECKED_CAST")
        val polygons = polygonizer.polygons as Collection<org.locationtech.jts.geom.Polygon>

        var outerLoop: FLine? = null
        val innerLoops = mutableListOf<FLine>()
        if (polygons.isNotEmpty()) {
            val sorted = polygons.sortedByDescending { it.area }
            val outer = sorted.first()
            outerLoop = ringToFLine(outer.exteriorRing as LineString, close = true)

            // Holes in the largest polygon are explicit inner loops.
            for (i in 0 until outer.numInteriorRing) {
                innerLoops += ringToFLine(outer.getInteriorRingN(i) as LineString, close = true)
            }
            // Remaining polygons become additional inner loops.
            for (i in 1 until sorted.size) {
                innerLoops += ringToFLine(sorted[i].exteriorRing as LineString, close = true)
                for (h in 0 until sorted[i].numInteriorRing) {
                    innerLoops += ringToFLine(sorted[i].getInteriorRingN(h) as LineString, close = true)
                }
            }
        } else if (closedLoops.isNotEmpty()) {
            // If polygonization fails, still return the largest closed loop.
            val biggest = closedLoops.maxByOrNull { it.length }
            outerLoop = biggest?.let { ringToFLine(it, close = true) }
            for (loop in closedLoops) {
                if (loop !== biggest) innerLoops += ringToFLine(loop, close = true)
            }
        }

        val lines = openCurves
            .filter { it.length > EPS }
            .map { lineToFLine(it) }

        return FLineSplit(outerLoop = outerLoop, innerLoops = innerLoops, lines = lines)
    }

    internal fun toMultiLineString(line: FLine): MultiLineString? {
        val segments = flattenShape(FLinesAndJavaShapes.flineToJavaShape_notThickened(line))
        if (segments.isEmpty()) return null
        return GF.createMultiLineString(segments.toTypedArray())
    }

    internal fun geometryToFLine(geom: Geometry): FLine {
        return FLinesAndJavaShapes.javaShapeToFLine(geometryToGeneralPath(geom))
    }

    internal fun geometryToGeneralPath(geom: Geometry): GeneralPath {
        val gp = GeneralPath()

        fun appendLineString(ls: LineString, close: Boolean) {
            val coords = ls.coordinates
            if (coords.isEmpty()) return
            gp.moveTo(coords[0].x.toFloat(), coords[0].y.toFloat())
            for (i in 1 until coords.size) gp.lineTo(coords[i].x.toFloat(), coords[i].y.toFloat())
            if (close) gp.closePath()
        }

        fun walk(g: Geometry) {
            when (g) {
                is org.locationtech.jts.geom.Polygon -> {
                    appendLineString(g.exteriorRing as LineString, close = true)
                    for (i in 0 until g.numInteriorRing) {
                        appendLineString(g.getInteriorRingN(i) as LineString, close = true)
                    }
                }

                is LineString -> appendLineString(g, close = false)
                is GeometryCollection -> for (i in 0 until g.numGeometries) walk(g.getGeometryN(i))
            }
        }

        walk(geom)
        return gp
    }

    private fun mergeLineStrings(geometry: Geometry): List<LineString> {
        val merger = LineMerger()

        fun addLines(g: Geometry) {
            when (g) {
                is LineString -> merger.add(g)
                is GeometryCollection -> for (i in 0 until g.numGeometries) addLines(g.getGeometryN(i))
            }
        }

        addLines(geometry)
        @Suppress("UNCHECKED_CAST")
        return (merger.mergedLineStrings as Collection<LineString>).toList()
    }

    private fun flattenShape(shape: Shape): List<LineString> {
        val pit = FlatteningPathIterator(shape.getPathIterator(null), flatness)
        val coords = DoubleArray(6)
        val out = mutableListOf<LineString>()
        val current = mutableListOf<Coordinate>()
        var moveStart: Coordinate? = null

        fun appendIfNotDuplicate(c: Coordinate) {
            val last = current.lastOrNull()
            if (last == null || !last.equals2D(c)) current += c
        }

        fun flush() {
            if (current.size > 1) {
                out += GF.createLineString(current.toTypedArray())
            }
            current.clear()
            moveStart = null
        }

        while (!pit.isDone) {
            when (pit.currentSegment(coords)) {
                PathIterator.SEG_MOVETO -> {
                    flush()
                    val c = Coordinate(coords[0], coords[1])
                    current += c
                    moveStart = c
                }

                PathIterator.SEG_LINETO -> appendIfNotDuplicate(Coordinate(coords[0], coords[1]))
                PathIterator.SEG_CLOSE -> {
                    val start = moveStart
                    if (start != null) appendIfNotDuplicate(Coordinate(start.x, start.y))
                    flush()
                }
            }
            pit.next()
        }
        flush()
        return out
    }

    private fun lineToFLine(line: LineString): FLine {
        val f = FLine()
        val coords = line.coordinates
        if (coords.isEmpty()) return f
        f.moveTo(coords[0].x.toFloat(), coords[0].y.toFloat())
        for (i in 1 until coords.size) {
            f.lineTo(coords[i].x.toFloat(), coords[i].y.toFloat())
        }
        return f
    }

    private fun ringToFLine(ring: LineString, close: Boolean): FLine {
        val f = FLine()
        val coords = ring.coordinates
        if (coords.isEmpty()) return f
        f.moveTo(coords[0].x.toFloat(), coords[0].y.toFloat())
        val last = if (close && coords.size > 1 && coords.first().equals2D(coords.last())) coords.lastIndex - 1 else coords.lastIndex
        for (i in 1..last) {
            f.lineTo(coords[i].x.toFloat(), coords[i].y.toFloat())
        }
        if (close) f.close()
        return f
    }
}
