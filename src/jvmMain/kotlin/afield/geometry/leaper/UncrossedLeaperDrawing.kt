/**
 * Drawing uncrossed leaper paths: [boardLines] and [pathLine] turn a [LeaperBoard] and an
 * [UncrossedPath] into FLines for `lines(...)`, cut where a move wraps round a cylinder or torus;
 * [uncrossedTile] combines them with start and end marks; [plotterSvg] writes any FLines as
 * stroke-only SVG for a pen plotter; [readDrawnPath] snaps a hand-drawn polyline onto a board's
 * cells so [LeaperBoard.check] can say whether it is a legal uncrossed path.
 */
package afield.geometry.leaper

import afield.geometry.path.FLine
import afield.graphics.render.circle
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.stroked
import afield.graphics.render.thickness
import afield.math.linalg.Vec4
import afield.math.linalg.vec
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The squares of a board, [cell] units across, with (0, 0)'s centre at ([x], [y]) + cell / 2. */
fun LeaperBoard.boardLines(
    cell: Double = 10.0,
    x: Double = 0.0,
    y: Double = 0.0,
    light: Vec4 = vec(1.0, 1.0, 1.0, 0.10),
    dark: Vec4 = vec(1.0, 1.0, 1.0, 0.03),
): List<FLine> {
    fun squares(parity: Int, c: Vec4) = FLine().apply {
        for (i in 0 until w) for (j in 0 until h) if ((i + j) % 2 == parity) {
            moveTo(x + i * cell, y + j * cell)
            lineTo(x + (i + 1) * cell, y + j * cell)
            lineTo(x + (i + 1) * cell, y + (j + 1) * cell)
            lineTo(x + i * cell, y + (j + 1) * cell)
            lineTo(x + i * cell, y + j * cell)
        }
        filled = true
        stroked = false
        color = c
    }
    val out = mutableListOf(squares(0, light), squares(1, dark))
    // A wrapping edge is drawn as a dashed seam, so a cylinder or torus reads differently from a rectangle.
    fun seam(x0: Double, y0: Double, x1: Double, y1: Double) = FLine().apply {
        val n = 2 * (hypot(x1 - x0, y1 - y0) / cell).roundToInt().coerceAtLeast(1)
        for (k in 0 until n step 2) {
            moveTo(x0 + (x1 - x0) * (k + 0.25) / n, y0 + (y1 - y0) * (k + 0.25) / n)
            lineTo(x0 + (x1 - x0) * (k + 0.75) / n, y0 + (y1 - y0) * (k + 0.75) / n)
        }
        color = vec(0.45, 0.8, 1.0, 0.8)
        thickness = 1.0
    }
    if (topology.wrapsX) {
        out += seam(x, y, x, y + h * cell)
        out += seam(x + w * cell, y, x + w * cell, y + h * cell)
    }
    if (topology.wrapsY) {
        out += seam(x, y, x + w * cell, y)
        out += seam(x, y + h * cell, x + w * cell, y + h * cell)
    }
    return out
}

/**
 * [path] as one FLine through cell centres, [cell] units per square, with (0, 0)'s corner at
 * ([x], [y]). A wrapping move becomes two or more pieces: it leaves through one edge and comes
 * back through the opposite one, exactly as it is drawn on the cylinder or torus.
 */
fun UncrossedPath.pathLine(cell: Double = 10.0, x: Double = 0.0, y: Double = 0.0): FLine {
    val f = FLine()
    var penX = Double.NaN
    var penY = Double.NaN
    for (pieces in board.drawnPieces(this)) for (p in pieces) {
        val ax = x + (p[0] + 0.5) * cell
        val ay = y + (p[1] + 0.5) * cell
        if (ax != penX || ay != penY) f.moveTo(ax, ay)
        penX = x + (p[2] + 0.5) * cell
        penY = y + (p[3] + 0.5) * cell
        f.lineTo(penX, penY)
    }
    f.color = vec(0.98, 0.85, 0.3, 1.0)
    f.thickness = 1.5
    return f
}

/** The cell centres of [path] in order, in the same coordinates as [pathLine]. */
fun UncrossedPath.points(cell: Double = 10.0, x: Double = 0.0, y: Double = 0.0): List<Pair<Double, Double>> =
    cells.map { c -> board.xy(c).let { (i, j) -> (x + (i + 0.5) * cell) to (y + (j + 0.5) * cell) } }

/**
 * A board with [path] drawn on it: squares, seams, the path in gold, a white dot at the start and a
 * cyan one at the end (cycles get one white dot). Offsets as for [pathLine].
 */
fun UncrossedPath.uncrossedTile(cell: Double = 10.0, x: Double = 0.0, y: Double = 0.0): List<FLine> {
    val out = board.boardLines(cell, x, y).toMutableList()
    out += pathLine(cell, x, y)
    val pts = points(cell, x, y)
    fun dot(p: Pair<Double, Double>, c: Vec4) = FLine().apply {
        circle(p.first, p.second, cell * 0.16)
        filled = true
        stroked = false
        color = c
    }
    if (pts.isNotEmpty()) out += dot(pts.first(), vec(1.0, 1.0, 1.0, 1.0))
    if (pts.size > 1 && !closed) out += dot(pts.last(), vec(0.45, 0.8, 1.0, 1.0))
    return out
}

/**
 * [lines] as a stroke-only SVG for a pen plotter: every FLine becomes one `<path>` with no fill,
 * its colour and thickness kept, cubic segments kept as cubics. Filled FLines (board squares) are
 * left out unless [includeFilled]. The drawing is fitted, with [marginMm] on each side, into a
 * page [widthMm] × [heightMm].
 */
fun plotterSvg(lines: List<FLine>, widthMm: Double = 210.0, heightMm: Double = 297.0, marginMm: Double = 15.0, includeFilled: Boolean = false): String {
    val kept = lines.filter { includeFilled || !it.filled }
    var minX = Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    for (f in kept) for (n in f.nodes) for (p in n.storage) {
        minX = minOf(minX, p.x.toDouble())
        minY = minOf(minY, p.y.toDouble())
        maxX = maxOf(maxX, p.x.toDouble())
        maxY = maxOf(maxY, p.y.toDouble())
    }
    if (minX > maxX) return """<svg xmlns="http://www.w3.org/2000/svg" width="${widthMm}mm" height="${heightMm}mm" viewBox="0 0 $widthMm $heightMm"/>"""
    val scale = minOf((widthMm - 2 * marginMm) / maxOf(maxX - minX, 1e-9), (heightMm - 2 * marginMm) / maxOf(maxY - minY, 1e-9))
    val ox = (widthMm - (maxX - minX) * scale) / 2
    val oy = (heightMm - (maxY - minY) * scale) / 2
    fun fx(v: Float) = "%.3f".format(ox + (v - minX) * scale)
    fun fy(v: Float) = "%.3f".format(oy + (v - minY) * scale)
    val sb = StringBuilder()
    sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="${widthMm}mm" height="${heightMm}mm" viewBox="0 0 $widthMm $heightMm">""").append('\n')
    for (f in kept) {
        val d = StringBuilder()
        for (n in f.nodes) {
            val p = n.storage[0]
            when (n) {
                is FLine.MoveTo -> d.append("M${fx(p.x)} ${fy(p.y)}")
                is FLine.LineTo -> d.append("L${fx(p.x)} ${fy(p.y)}")
                is FLine.CubicTo -> d.append("C${fx(n.c1.x)} ${fy(n.c1.y)} ${fx(n.c2.x)} ${fy(n.c2.y)} ${fx(p.x)} ${fy(p.y)}")
            }
        }
        if (d.isEmpty()) continue
        val c = f.color
        val rgb = "#%02x%02x%02x".format(
            (c.x * 255).roundToInt().coerceIn(0, 255),
            (c.y * 255).roundToInt().coerceIn(0, 255),
            (c.z * 255).roundToInt().coerceIn(0, 255),
        )
        val width = "%.3f".format((f.thickness ?: 1.0) * 0.25)
        sb.append("""<path d="$d" fill="none" stroke="$rgb" stroke-width="$width" stroke-linecap="round" stroke-linejoin="round"/>""").append('\n')
    }
    sb.append("</svg>\n")
    return sb.toString()
}
