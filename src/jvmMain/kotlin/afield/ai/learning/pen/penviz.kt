/**
 * Pictures of what a learned pen is thinking: its next-step distribution as ellipses, a stroke
 * coloured by one memory cell or drawn thick where the pen is sure to stay down, and the density
 * of hundreds of possible futures as an image.
 *
 * Everything here reads a [PenLine]: its [PenLine.history] for what it has drawn and
 * [PenLine.distribution] for what it expects next. Tests: `PenVizTest`.
 */

package afield.ai.learning.pen

import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.thickness
import afield.math.linalg.Vec4
import afield.media.image.util.PngEncoder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One ellipse per mixture component, at [sigmas] standard deviations, with opacity following the
 * component's weight; components lighter than [minWeight] are left out. Read it as "where the next
 * point will land": a tight cluster is a pen that knows, a spread of faint rings one that is
 * choosing.
 */
fun PenDistribution.ellipses(
    sigmas: Double = 2.0,
    minWeight: Double = 0.01,
    color: Vec4 = Vec4(0.55f, 0.85f, 1f, 1f),
): List<FLine> {
    val top = weights.max()
    return (0 until size).filter { weights[it] >= minWeight }.map { i ->
        // principal axes of [[sx², ρ sx sy], [ρ sx sy, sy²]]
        val a = sigmaX[i] * sigmaX[i]
        val d = sigmaY[i] * sigmaY[i]
        val b = correlation[i] * sigmaX[i] * sigmaY[i]
        val mid = (a + d) / 2
        val spread = sqrt(((a - d) / 2) * ((a - d) / 2) + b * b)
        val l1 = sqrt(max(mid + spread, 0.0)) * sigmas
        val l2 = sqrt(max(mid - spread, 0.0)) * sigmas
        val angle = 0.5 * kotlin.math.atan2(2 * b, a - d)
        val e = FLine()
        for (k in 0..40) {
            val t = k * 2 * PI / 40
            val x = meanX[i] + cos(angle) * cos(t) * l1 - sin(angle) * sin(t) * l2
            val y = meanY[i] + sin(angle) * cos(t) * l1 + cos(angle) * sin(t) * l2
            if (k == 0) e.moveTo(x, y) else e.lineTo(x, y)
        }
        e.color = Vec4(color.x, color.y, color.z, (color.w * (0.15 + 0.85 * weights[i] / top)).toFloat())
        e.thickness = 1.0
        e
    }
}

/**
 * The pen's marks, split into [bands] FLines by [value], each coloured between [low] and [high].
 * [range] is the span of values mapped onto the colours; by default, the values' own.
 */
fun PenLine.coloredBy(
    bands: Int = 16,
    low: Vec4 = Vec4(0.2f, 0.45f, 1f, 1f),
    high: Vec4 = Vec4(1f, 0.55f, 0.2f, 1f),
    range: ClosedFloatingPointRange<Double>? = null,
    thickness: Double = 1.5,
    value: (PenMoment) -> Double,
): List<FLine> {
    val marks = history.filter { it.mark }
    if (marks.isEmpty()) return emptyList()
    val values = marks.map(value)
    val lo = range?.start ?: values.min()
    val hi = range?.endInclusive ?: values.max()
    val out = List(bands) { b ->
        val t = b / max(1.0, bands - 1.0)
        FLine().also {
            it.color = mix(low, high, t)
            it.thickness = thickness
        }
    }
    val joiner = BandJoiner(out)
    marks.forEachIndexed { i, m ->
        val t = if (hi > lo) ((values[i] - lo) / (hi - lo)).coerceIn(0.0, 1.0) else 0.5
        joiner.add((t * (bands - 1)).toInt(), m)
    }
    return out.filter { it.nodes.isNotEmpty() }
}

/**
 * The pen's marks coloured by memory cell [cell]'s output (the LSTM's `h`): blue where it is low,
 * orange where it is high. [range] is the span of outputs the colours cover, by default all of it,
 * -1 to 1; `null` spreads them over the values this cell actually took, for a cell that never
 * moves far. Needs a PenLine made with `recordMemory = true`.
 */
fun PenLine.coloredByCell(cell: Int, bands: Int = 16, thickness: Double = 1.5, range: ClosedFloatingPointRange<Double>? = -1.0..1.0): List<FLine> =
    coloredBy(bands, range = range, thickness = thickness) {
        (it.hidden ?: error("coloredByCell needs a PenLine made with recordMemory = true"))[cell].toDouble()
    }

/**
 * The pen's marks drawn thick where it was sure the pen would stay on the paper and thin where it
 * expected to lift: the lift probability read as pressure. It was never trained on pressure, so
 * this is the model's confidence, not a hand's. Widths follow the logarithm of the probability,
 * averaged over [smoothing] points either side and spread over this line's own range of it.
 */
fun PenLine.widthByLift(
    bands: Int = 12,
    thinnest: Double = 0.3,
    thickest: Double = 4.0,
    color: Vec4 = Vec4(0.9f, 0.92f, 1f, 1f),
    smoothing: Int = 4,
): List<FLine> {
    val marks = history.filter { it.mark }
    if (marks.isEmpty()) return emptyList()
    val raw = marks.map { ln(max(it.liftProbability, 1e-9)) }
    // averaged over [smoothing] points either side: the probability jumps from point to point, and
    // a width that jumps with it reads as a row of beads rather than a stroke
    val logs = raw.indices.map { i -> (max(0, i - smoothing)..min(raw.size - 1, i + smoothing)).map { raw[it] }.average() }
    val sorted = logs.sorted()
    val lo = sorted[sorted.size / 20]
    val hi = sorted[sorted.size - 1 - sorted.size / 20]
    val out = List(bands) { b ->
        FLine().also {
            it.thickness = thinnest + (thickest - thinnest) * b / max(1.0, bands - 1.0)
            it.color = color
        }
    }
    val joiner = BandJoiner(out)
    marks.forEachIndexed { i, m ->
        val stay = if (hi > lo) (1 - (logs[i] - lo) / (hi - lo)).coerceIn(0.0, 1.0) else 1.0
        joiner.add((stay * (bands - 1)).toInt(), m)
    }
    return out.filter { it.nodes.isNotEmpty() }
}

/**
 * How unsure a distribution is, in squared drawing units: the spread of where the next point could
 * land, counting both each component's own spread and how far apart the components sit. Use it to
 * find the moments worth a close-up.
 */
val PenDistribution.uncertainty: Double get() {
    var mx = 0.0; var my = 0.0
    for (i in 0 until size) { mx += weights[i] * meanX[i]; my += weights[i] * meanY[i] }
    var v = 0.0
    for (i in 0 until size) {
        val dx = meanX[i] - mx
        val dy = meanY[i] - my
        v += weights[i] * (sigmaX[i] * sigmaX[i] + sigmaY[i] * sigmaY[i] + dx * dx + dy * dy)
    }
    return v
}

/**
 * The [count] memory cells whose output changed most while drawing, most variable first: the
 * place to start looking for a cell that means something. Needs `recordMemory = true`.
 */
fun PenLine.liveliestCells(count: Int = 12): List<Int> {
    val h = history.mapNotNull { it.hidden }
    if (h.isEmpty()) return emptyList()
    val n = h[0].size
    val variance = DoubleArray(n) { c ->
        val mean = h.sumOf { it[c].toDouble() } / h.size
        h.sumOf { (it[c] - mean) * (it[c] - mean) } / h.size
    }
    return (0 until n).sortedByDescending { variance[it] }.take(count)
}

/**
 * Where the pen might go: [count] futures of [steps] points from here, accumulated into an image of
 * [width] by [height] pixels over [area] (drawing coordinates, `x, y, w, h`), on a log scale so a
 * single stray future still shows. [line] and [extra] are drawn on top in [ink]. Returns PNG bytes
 * for `display(...)`.
 */
fun PenLine.futuresDensity(
    area: DoubleArray? = null,
    count: Int = 400,
    steps: Int = 60,
    width: Int = 1040,
    height: Int = 520,
    seed: Long = 1,
    ink: Vec4 = Vec4(1f, 0.85f, 0.3f, 1f),
): ByteArray {
    val futures = futures(count, steps, seed)
    val image = DensityImage(width, height, area ?: framing(futures + line, width.toDouble() / height))
    futures.forEach { image.add(it) }
    val out = image.toneMapped()
    image.ink(line, ink, out)
    return PngEncoder.encode(out, width, height)
}

/**
 * A rectangle (`x, y, w, h`) around [lines]'s points, padded by a tenth, widened or heightened to
 * [aspect] (width over height) so nothing is stretched. Ignores the farthest 1% of points, so
 * one wild future does not shrink everything else.
 */
fun framing(lines: List<FLine>, aspect: Double): DoubleArray {
    val xs = lines.flatMap { l -> l.nodes.map { it.to.x.toDouble() } }.sorted()
    val ys = lines.flatMap { l -> l.nodes.map { it.to.y.toDouble() } }.sorted()
    if (xs.isEmpty()) return doubleArrayOf(0.0, 0.0, aspect, 1.0)
    val cut = xs.size / 200
    var x0 = xs[cut]; var x1 = xs[xs.size - 1 - cut]
    var y0 = ys[cut]; var y1 = ys[ys.size - 1 - cut]
    val pad = 0.1 * max(x1 - x0, y1 - y0) + 1
    x0 -= pad; x1 += pad; y0 -= pad; y1 += pad
    var w = x1 - x0
    var h = y1 - y0
    if (w / h < aspect) { val nw = h * aspect; x0 -= (nw - w) / 2; w = nw } else { val nh = w / aspect; y0 -= (nh - h) / 2; h = nh }
    return doubleArrayOf(x0, y0, w, h)
}

/**
 * Accumulates FLines into a grid of counts over a region of drawing coordinates and tone-maps
 * them into an image: [futuresDensity]'s canvas, usable on its own for any pile of lines.
 */
class DensityImage(val width: Int, val height: Int, val area: DoubleArray) {
    private val counts = FloatArray(width * height)
    private val sx = width / area[2]
    private val sy = height / area[3]

    /** Add [line]'s marks, one unit of ink per pixel of length, anti-aliased. */
    fun add(line: FLine, weight: Float = 1f) = segments(line) { x, y -> splat(x, y, weight) }

    private fun segments(line: FLine, at: (Double, Double) -> Unit) {
        line.pieces().forEach { piece ->
            // curves are flattened at about a pixel, so a cubic is not drawn as its chord
            val p = if (piece.nodes.any { it is FLine.CubicTo }) piece.sampleByDistance((1.0 / sx).toFloat()) else piece.nodes.map { it.to }
            for (i in 1 until p.size) {
                val ax = (p[i - 1].x - area[0]) * sx
                val ay = (p[i - 1].y - area[1]) * sy
                val bx = (p[i].x - area[0]) * sx
                val by = (p[i].y - area[1]) * sy
                val n = max(1, (max(abs(bx - ax), abs(by - ay)) * 2).toInt())
                for (k in 0 until n) {
                    val t = k / n.toDouble()
                    at(ax + (bx - ax) * t, ay + (by - ay) * t)
                }
            }
        }
    }

    private fun splat(x: Double, y: Double, w: Float) {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = (x - x0).toFloat()
        val fy = (y - y0).toFloat()
        put(x0, y0, w * (1 - fx) * (1 - fy)); put(x0 + 1, y0, w * fx * (1 - fy))
        put(x0, y0 + 1, w * (1 - fx) * fy); put(x0 + 1, y0 + 1, w * fx * fy)
    }

    private fun put(x: Int, y: Int, w: Float) {
        if (x in 0 until width && y in 0 until height) counts[y * width + x] += w
    }

    /** Counts as ARGB pixels: black through blue to white, logarithmically. */
    fun toneMapped(): IntArray {
        val top = ln(1.0 + (counts.maxOrNull() ?: 0f))
        return IntArray(counts.size) { i ->
            val t = if (top > 0) (ln(1.0 + counts[i]) / top).toFloat() else 0f
            val r = min(1f, max(0f, t * 2 - 1))
            val g = min(1f, max(0f, t * 1.6f - 0.4f))
            val b = min(1f, t * 1.8f)
            argb(r, g, b)
        }
    }

    /** Draw [line] solidly over [pixels] in [color]. */
    fun ink(line: FLine, color: Vec4, pixels: IntArray) = segments(line) { x, y ->
        for (dy in -1..1) for (dx in -1..1) {
            val px = x.toInt() + dx
            val py = y.toInt() + dy
            if (px in 0 until width && py in 0 until height) pixels[py * width + px] = argb(color.x, color.y, color.z)
        }
    }

    private fun argb(r: Float, g: Float, b: Float) =
        (0xff shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
}

/**
 * Appends marks to per-band FLines, continuing the previous mark's polyline when the next one starts
 * where it ended in the same band, so a run of segments is drawn as one path rather than a row of
 * separately capped dashes.
 */
private class BandJoiner(val out: List<FLine>) {
    private var band = -1
    private var end: afield.math.linalg.Vec3? = null

    fun add(b: Int, m: PenMoment) {
        val line = out[b]
        if (b != band || end != m.from) line.moveTo(m.from.x, m.from.y)
        line.lineTo(m.to.x, m.to.y)
        band = b
        end = m.to
    }
}

private fun mix(a: Vec4, b: Vec4, t: Double): Vec4 {
    val u = t.toFloat()
    return Vec4(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u, a.z + (b.z - a.z) * u, a.w + (b.w - a.w) * u)
}

/** The last [points] points the pen drew, as an FLine ending at [PenLine.tip]: the context for a close-up. */
fun PenLine.tail(points: Int = 20): FLine {
    val out = FLine()
    history.takeLast(points).forEachIndexed { i, m ->
        if (i == 0 || !m.mark) out.moveTo(m.from.x, m.from.y)
        out.lineTo(m.to.x, m.to.y)
    }
    return out
}

/** One moment of a [PenLine]: its last few points, whether its pen was up, and what it expected next. */
class PenCloseUp(val tail: FLine, val penUp: Boolean, val distribution: PenDistribution) {
    /** The tail in [color] and the distribution's [ellipses]. */
    fun lines(color: Vec4 = Vec4(0.85f, 0.9f, 1f, 1f)): List<FLine> =
        listOf(tail.also { it.color = color; it.thickness = 1.5 }) + distribution.ellipses()
}

/** This moment, as a [PenCloseUp] over the last [points] points. */
fun PenLine.closeUp(points: Int = 8): PenCloseUp = PenCloseUp(tail(points), penUp, distribution()!!)

/**
 * [groups] laid out left to right, each moved so it starts [gap] after the previous one ends and
 * sits with its top at [y]: small multiples that never overlap, whatever their sizes.
 */
fun sideBySide(groups: List<List<FLine>>, gap: Double = 12.0, y: Double = 0.0): List<FLine> {
    var x = 0.0
    return groups.flatMap { g ->
        val boxes = g.filter { it.nodes.isNotEmpty() }.map { it.fast2DBounds() }
        if (boxes.isEmpty()) return@flatMap emptyList()
        val x0 = boxes.minOf { it.x }
        val y0 = boxes.minOf { it.y }
        val x1 = boxes.maxOf { it.x + it.w }
        val dx = x - x0
        val dy = y - y0
        x += x1 - x0 + gap
        // `+` keeps each line's properties: colour, thickness, onClick
        g.map { it + afield.math.linalg.vec(dx, dy, 0.0) }
    }
}

/** [groups] stacked top to bottom, each starting [gap] below the previous one's lowest point. */
fun stacked(groups: List<List<FLine>>, gap: Double = 12.0, x: Double = 0.0): List<FLine> {
    var y = 0.0
    return groups.flatMap { g ->
        val boxes = g.filter { it.nodes.isNotEmpty() }.map { it.fast2DBounds() }
        if (boxes.isEmpty()) return@flatMap emptyList()
        val x0 = boxes.minOf { it.x }
        val y0 = boxes.minOf { it.y }
        val y1 = boxes.maxOf { it.y + it.h }
        val dx = x - x0
        val dy = y - y0
        y += y1 - y0 + gap
        // `+` keeps each line's properties: colour, thickness, onClick
        g.map { it + afield.math.linalg.vec(dx, dy, 0.0) }
    }
}
