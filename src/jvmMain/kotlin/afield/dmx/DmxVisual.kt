@file:afield.CompletionCategory("Lighting")

package afield.dmx

import afield.Box
import afield.graphics.FLine
import afield.graphics.lines
import afield.graphics.outputTo
import afield.stdlib.color
import afield.stdlib.filled
import afield.stdlib.stroked
import afield.stdlib.thickness
import afield.stdlib.vec
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private fun disc(cx: Double, cy: Double, r: Double, segments: Int = 20): FLine {
    val f = FLine()
    for (i in 0..segments) {
        val a = 2 * PI * i / segments
        if (i == 0) f.moveTo(cx + r * cos(a), cy + r * sin(a)) else f.lineTo(cx + r * cos(a), cy + r * sin(a))
    }
    return f
}

private fun rectLine(x: Double, y: Double, w: Double, h: Double): FLine =
    FLine().moveTo(x, y).lineTo(x + w, y).lineTo(x + w, y + h).lineTo(x, y + h).lineTo(x, y)

/**
 * The patch as drawable geometry: a dark ground, then one filled disc per fixture in its current
 * colour (dimmer applied), ringed in grey so an unlit fixture is still visible. Positions are the
 * fixtures' own; y grows downward in box output, as it does for every `lines(...)` figure.
 *
 * Fixtures whose universe differs from their predecessor's start a new ring colour, so universe
 * boundaries in a long strip are visible at a glance.
 *
 * @param radius disc radius in rig units; defaults to 40% of the median spacing between consecutive fixtures.
 */
fun Patch.toFLines(radius: Double? = null, background: Boolean = true): List<FLine> {
    val out = ArrayList<FLine>(fixtures.size * 2 + 1)
    if (fixtures.isEmpty()) return out
    val r = radius ?: run {
        val gaps = fixtures.zipWithNext { a, b -> Math.hypot((a.position.x - b.position.x).toDouble(), (a.position.y - b.position.y).toDouble()) }.filter { it > 1e-9 }.sorted()
        if (gaps.isEmpty()) 5.0 else gaps[gaps.size / 2] * 0.4
    }
    val (x, y, w, h) = bounds()
    if (background) {
        out += rectLine(x - 3 * r, y - 3 * r, w + 6 * r, h + 6 * r).apply {
            filled = true; stroked = false; color = vec(0.06, 0.06, 0.07, 1.0)
        }
    }
    val ringColours = listOf(vec(0.45, 0.45, 0.5, 1.0), vec(0.85, 0.65, 0.2, 1.0))
    var ring = 0
    var lastUniverse = fixtures.first().universe
    for (f in fixtures) {
        if (f.universe != lastUniverse) { ring = 1 - ring; lastUniverse = f.universe }
        val scale = f.dimmer.coerceIn(0.0, 1.0)
        val c = f.color
        val px = f.position.x.toDouble()
        val py = f.position.y.toDouble()
        out += disc(px, py, r).apply {
            filled = true; stroked = false
            color = vec(c.x * scale, c.y * scale, c.z * scale, 1.0)
        }
        out += disc(px, py, r).apply {
            filled = false; stroked = true; thickness = r * 0.18; color = ringColours[ring]
        }
    }
    return out
}

/**
 * A received (or sent) universe as geometry: 512 cells in a [cols]-wide grid, each cell gray at its
 * slot's value. With [rgb] true, consecutive slot triples are drawn as one coloured cell instead
 * (170 cells), which is how a pixel strip's universe reads best.
 */
fun universeToFLines(data: ByteArray, cols: Int = 32, cell: Double = 12.0, rgb: Boolean = false, length: Int = 512): List<FLine> {
    val out = ArrayList<FLine>()
    val cells = if (rgb) length / 3 else length
    val rows = (cells + cols - 1) / cols
    out += rectLine(-cell * 0.5, -cell * 0.5, cols * cell + cell, rows * cell + cell).apply {
        filled = true; stroked = false; color = vec(0.06, 0.06, 0.07, 1.0)
    }
    for (i in 0 until cells) {
        val cx = (i % cols) * cell
        val cy = (i / cols) * cell
        val col = if (rgb) {
            vec((data[3 * i].toInt() and 0xff) / 255.0, (data[3 * i + 1].toInt() and 0xff) / 255.0, (data[3 * i + 2].toInt() and 0xff) / 255.0, 1.0)
        } else {
            val g = (data[i].toInt() and 0xff) / 255.0
            vec(g, g, g, 1.0)
        }
        out += rectLine(cx + 1, cy + 1, cell - 2, cell - 2).apply { filled = true; stroked = false; color = col }
    }
    return out
}

/** Show the patch in its current colours in this box's [channel] output. */
fun Box.showPatch(patch: Patch, channel: String = "lights", w: Double = 640.0, h: Double = 240.0) {
    lines(patch.toFLines(), channel = channel, w = w, h = h, fitToSize = true)
}

/** Show one universe of [input] in this box's [channel] output (RGB cells if [rgb]). */
fun Box.showUniverse(input: DmxInput, universe: Int, channel: String = "universe $universe", rgb: Boolean = true,
                     w: Double = 640.0, h: Double = 240.0) {
    lines(universeToFLines(input.universe(universe), rgb = rgb), channel = channel, w = w, h = h, fitToSize = true)
}

/**
 * Rasterise [lines] into a [w] × [h] PNG at [file], fitted with a 10 px margin — for looking at a
 * visualiser without a browser (a headless Field, a test, a docs figure). Returns the file.
 */
fun renderLinesPng(lines: List<FLine>, file: File, w: Int = 640, h: Int = 240): File {
    val xs = lines.flatMap { l -> l.nodes.mapNotNull { it.toOrNull?.x?.toDouble() } }
    val ys = lines.flatMap { l -> l.nodes.mapNotNull { it.toOrNull?.y?.toDouble() } }
    val x0 = xs.minOrNull() ?: 0.0; val x1 = xs.maxOrNull() ?: 1.0
    val y0 = ys.minOrNull() ?: 0.0; val y1 = ys.maxOrNull() ?: 1.0
    val margin = 10.0
    val s = minOf((w - 2 * margin) / maxOf(1e-9, x1 - x0), (h - 2 * margin) / maxOf(1e-9, y1 - y0))
    val ox = (w - (x1 - x0) * s) / 2
    val oy = (h - (y1 - y0) * s) / 2
    val surface = Surface.makeRasterN32Premul(w, h)
    try {
        val canvas = surface.canvas
        canvas.clear(0xff202024.toInt())
        for (l in lines) {
            val placed = l.transform { p -> vec((p.x - x0) * s + ox, (p.y - y0) * s + oy, p.z) }
            l.thickness?.let { placed.thickness = it * s }
            outputTo(placed, canvas, antiAlias = true)
        }
        val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes
            ?: error("Skia declined to encode the PNG")
        file.parentFile?.mkdirs()
        file.writeBytes(png)
    } finally {
        runCatching { surface.close() }
    }
    return file
}
