@file:afield.CompletionCategory("Lighting")

package afield.dmx

import afield.Box
import afield.displayOpen
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
 * With [outline] (the default up to 64 fixtures; a 200-pixel strip would double the SVG for rings
 * too small to see) each disc is ringed, and the ring colour flips at every universe boundary so
 * where a long strip changes universe is visible at a glance.
 *
 * @param radius disc radius in rig units; defaults to 40% of the median spacing between consecutive fixtures.
 */
fun Patch.toFLines(radius: Double? = null, background: Boolean = true, outline: Boolean = fixtures.size <= 64): List<FLine> =
    drawFixtures(radius, background, outline) { f ->
        val scale = f.dimmer.coerceIn(0.0, 1.0)
        vec(f.color.x * scale, f.color.y * scale, f.color.z * scale)
    }

/**
 * The patch drawn in the colours its fixtures show in the universes [universeData] returns (a
 * function from universe number to its 512 slots — `input::universe` for a [DmxInput]), decoded by
 * [Fixture.decode]. It is [toFLines] with the wire as the source of colour instead of
 * [Fixture.color]: the rig in the room, lit by what arrived, so a received frame can be set beside
 * the look that was sent. Each universe is fetched once.
 */
fun Patch.receivedToFLines(universeData: (Int) -> ByteArray, radius: Double? = null, background: Boolean = true,
                           outline: Boolean = fixtures.size <= 64): List<FLine> {
    val cache = HashMap<Int, ByteArray>()
    return drawFixtures(radius, background, outline) { f -> f.decode(cache.getOrPut(f.universe) { universeData(f.universe) }) }
}

private fun Patch.drawFixtures(radius: Double?, background: Boolean, outline: Boolean, colourOf: (Fixture) -> afield.stdlib.Vec3): List<FLine> {
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
        val c = colourOf(f)
        val px = f.position.x.toDouble()
        val py = f.position.y.toDouble()
        out += disc(px, py, r).apply {
            filled = true; stroked = false
            color = vec(c.x, c.y, c.z, 1.0)
        }
        if (outline) out += disc(px, py, r).apply {
            filled = false; stroked = true; thickness = r * 0.18; color = ringColours[ring]
        }
    }
    return out
}

/**
 * A received (or sent) universe as geometry, in a [cols]-wide grid of [cell]-sized squares.
 *
 * With no [profile], each of the 512 slots is one gray cell at its value. With a [profile], the
 * universe is read back as the fixtures that profile describes, packed from slot 1 — one cell per
 * fixture, coloured from its slots (white adds to all three primaries, amber adds 1 : 0.5 red and
 * green, a lone dimmer is gray, a master dimmer scales the rest; fine bytes are ignored). So an RGB
 * strip's universe reads as 170 coloured pixels and an RGBW matrix's as 128. [rgb] is shorthand for
 * `profile = FixtureProfile.RGB`.
 */
fun universeToFLines(
    data: ByteArray,
    cols: Int = 32,
    cell: Double = 12.0,
    rgb: Boolean = false,
    length: Int = 512,
    profile: FixtureProfile? = if (rgb) FixtureProfile.RGB else null,
): List<FLine> {
    val out = ArrayList<FLine>()
    val footprint = profile?.footprint ?: 1
    val cells = length / footprint
    val rows = (cells + cols - 1) / cols
    out += rectLine(-cell * 0.5, -cell * 0.5, cols * cell + cell, rows * cell + cell).apply {
        filled = true; stroked = false; color = vec(0.06, 0.06, 0.07, 1.0)
    }
    for (i in 0 until cells) {
        val cx = (i % cols) * cell
        val cy = (i / cols) * cell
        val col = if (profile == null) {
            val g = (data[i].toInt() and 0xff) / 255.0
            vec(g, g, g, 1.0)
        } else fixtureColour(data, i * footprint, profile)
        out += rectLine(cx + 1, cy + 1, cell - 2, cell - 2).apply { filled = true; stroked = false; color = col }
    }
    return out
}

/** The display colour of one [profile] fixture whose first slot is 0-based [at] in [data]. */
private fun fixtureColour(data: ByteArray, at: Int, profile: FixtureProfile): afield.stdlib.Vec4 {
    var r = 0.0; var g = 0.0; var b = 0.0; var dim = 1.0; var level = -1.0
    profile.slots.forEachIndexed { k, slot ->
        val v = (data[at + k].toInt() and 0xff) / 255.0
        when (slot) {
            Slot.RED -> r += v
            Slot.GREEN -> g += v
            Slot.BLUE -> b += v
            Slot.WHITE -> { r += v; g += v; b += v }
            Slot.AMBER -> { r += v; g += 0.5 * v }
            Slot.DIMMER -> if (profile.hasColour) dim = v else level = v
            else -> {}
        }
    }
    if (level >= 0) { r = level; g = level; b = level }
    return vec(minOf(1.0, r * dim), minOf(1.0, g * dim), minOf(1.0, b * dim), 1.0)
}

/** Show the patch in its current colours in this box's [channel] output. */
fun Box.showPatch(patch: Patch, channel: String = "lights", w: Double = 640.0, h: Double = 240.0) {
    lines(patch.toFLines(), channel = channel, w = w, h = h, fitToSize = true)
    displayOpen(channel)
}

/**
 * Show [patch] lit by what [input] has received — each fixture decoded from its own universe and
 * address ([receivedToFLines]) — in this box's [channel] output. Beside [showPatch] it is the sent
 * look and the received one in the same layout; [showUniverse] is the same data in wire order.
 */
fun Box.showReceived(patch: Patch, input: DmxInput, channel: String = "received", w: Double = 640.0, h: Double = 240.0) {
    lines(patch.receivedToFLines({ input.universe(it) }), channel = channel, w = w, h = h, fitToSize = true)
    displayOpen(channel)
}

/**
 * Show one universe of [input] in this box's [channel] output: as [profile] fixtures (RGB pixels by
 * default), or as 512 gray slots with `profile = null`. See [universeToFLines].
 */
fun Box.showUniverse(input: DmxInput, universe: Int, channel: String = "universe $universe",
                     profile: FixtureProfile? = FixtureProfile.RGB, cols: Int = 32, w: Double = 640.0, h: Double = 240.0) {
    lines(universeToFLines(input.universe(universe), cols = cols, profile = profile), channel = channel, w = w, h = h, fitToSize = true)
    displayOpen(channel)
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
