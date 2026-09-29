@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import afield.stacksl.cpu.CpuRaster
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What a study can say about a picture in numbers, and how it lays pictures out for a model to look
 * at. Every study that draws — StackSL, RectSL, a stack.auto slot — measures and lays out its frames
 * here, so a measurement means the same thing in all of them.
 *
 * A model shown only an image will describe the image. These measurements are the things it cannot
 * read reliably off a thumbnail, each one a question a conversation about a shader actually asks:
 * *is it blown out?* ([StudyFrameStats.clippedAbove]), *is anything NaN?* (`nonFinite`), *is it
 * flat or busy?* (`detail`, `featureSize`), *what colours is it?* (`palette`), *did it move?*
 * ([StudyMotion]), *did my edit change what I said it would, and only that?* ([StudyDifference]).
 * All of them are taken from the program's **raw** output before the frame's clamp ([displayed]), so
 * clipping is counted rather than hidden.
 */
data class StudyFrameStats(
    val seconds: Double,
    /** Mean R, G, B in `0..1` as the window shows them — sRGB-encoded, see [StudyEvidence.presented]. */
    val meanRgb: List<Double>,
    /** Mean luma (Rec. 709) of the presented image, and its standard deviation. */
    val luma: Double,
    val lumaSpread: Double,
    /** Fraction of R/G/B values the program left below 0 or above 1 — what `saturate` or the frame's clamp throws away. */
    val clippedBelow: Double,
    val clippedAbove: Double,
    /** Fraction of pixels whose colour was NaN or infinite; the frame draws them black. */
    val nonFinite: Double,
    /** Mean luma gradient, in luma per frame width: near 0 is flat, tens is busy. */
    val detail: Double,
    /** Typical feature width as a fraction of the frame, from how often luma crosses its mean along rows and columns; null when flat. */
    val featureSize: Double?,
    /** Hasler–Süsstrunk colourfulness on the 0..255 scale: under ~15 reads as grey, over ~80 as vivid. */
    val colourfulness: Double,
    /** The frame's main colours by k-means, most common first, with the fraction of pixels each covers. */
    val palette: List<StudySwatch>
)

data class StudySwatch(val hex: String, val fraction: Double)

/** How much consecutive frames differ: mean absolute change in 8-bit levels, and the fraction of pixels that moved by more than two levels. */
data class StudyMotion(val fromSeconds: Double, val toSeconds: Double, val meanLevels: Double, val changedFraction: Double)

/** A difference between two frames, restricted to a region or its complement. */
data class StudyDifference(
    val scope: String,
    val pixels: Int,
    /** Largest per-channel difference, in 8-bit levels (or raw units when comparing stages). */
    val max: Double,
    val mean: Double,
    /** Fraction of pixels differing by more than one level (or 1e-6 raw). */
    val changedFraction: Double,
    /** Where the largest difference is, in `uv`. */
    val atU: Double?,
    val atV: Double?
)

object StudyEvidence {

    /** What a study frame draws for a raw raster: non-finite colour becomes black, the rest is clamped, alpha is one. */
    fun displayed(raw: CpuRaster): CpuRaster {
        val out = FloatArray(raw.rgba.size)
        for (p in 0 until raw.width * raw.height) {
            val r = raw.rgba[p * 4]; val g = raw.rgba[p * 4 + 1]; val b = raw.rgba[p * 4 + 2]
            val finite = r.isFinite() && g.isFinite() && b.isFinite()
            out[p * 4] = if (finite) r.coerceIn(0f, 1f) else 0f
            out[p * 4 + 1] = if (finite) g.coerceIn(0f, 1f) else 0f
            out[p * 4 + 2] = if (finite) b.coerceIn(0f, 1f) else 0f
            out[p * 4 + 3] = 1f
        }
        return CpuRaster(raw.width, raw.height, out)
    }

    /**
     * What a person sees of a raw raster in the window: [displayed], then the sRGB transfer function.
     *
     * The window's swapchain is `B8G8R8A8_SRGB` (scRGB when HDR is on, which looks the same for
     * `0..1`), so the value the shader writes is treated as *linear* light and encoded for display on
     * the way to the screen: `0.5` shows as sRGB 188, a light grey, not 128. An image that showed the
     * written values directly would be markedly darker than the window, and a model reading it would
     * be judging a different picture from the person. Every study image, every export and the cards'
     * WebGL previews go through this; clipping and NaN are still counted on the raw values.
     */
    fun presented(raw: CpuRaster): CpuRaster {
        val shown = displayed(raw)
        val out = shown.rgba.copyOf()
        for (p in 0 until shown.width * shown.height) for (c in 0 until 3) out[p * 4 + c] = linearToSrgb(out[p * 4 + c])
        return CpuRaster(shown.width, shown.height, out)
    }

    /** The sRGB transfer function (IEC 61966-2-1), for a linear value in `0..1`. */
    fun linearToSrgb(v: Float): Float =
        if (v <= 0.0031308f) v * 12.92f else (1.055 * Math.pow(v.toDouble(), 1.0 / 2.4) - 0.055).toFloat()

    fun stats(raw: CpuRaster, seconds: Double): StudyFrameStats {
        val shown = presented(raw)
        val n = raw.width * raw.height
        var below = 0; var above = 0; var nonFinite = 0
        val sum = DoubleArray(3)
        val luma = DoubleArray(n)
        for (p in 0 until n) {
            var bad = false
            for (c in 0 until 3) {
                val v = raw.rgba[p * 4 + c]
                if (!v.isFinite()) bad = true
                else if (v < 0f) below++
                else if (v > 1f) above++
                sum[c] += shown.rgba[p * 4 + c]
            }
            if (bad) nonFinite++
            luma[p] = 0.2126 * shown.rgba[p * 4] + 0.7152 * shown.rgba[p * 4 + 1] + 0.0722 * shown.rgba[p * 4 + 2]
        }
        val lumaMean = luma.average()
        val lumaSpread = sqrt(luma.sumOf { (it - lumaMean) * (it - lumaMean) } / n)
        return StudyFrameStats(
            seconds = seconds,
            meanRgb = sum.map { round(it / n) },
            luma = round(lumaMean),
            lumaSpread = round(lumaSpread),
            clippedBelow = round(below / (3.0 * n)),
            clippedAbove = round(above / (3.0 * n)),
            nonFinite = round(nonFinite.toDouble() / n),
            detail = round(detail(luma, raw.width, raw.height)),
            featureSize = featureSize(luma, lumaMean, raw.width, raw.height)?.let(::round),
            colourfulness = round(colourfulness(shown)),
            palette = palette(shown)
        )
    }

    private fun round(v: Double): Double = (v * 10000.0).roundToInt() / 10000.0

    private fun detail(luma: DoubleArray, w: Int, h: Int): Double {
        var total = 0.0; var count = 0
        for (y in 0 until h - 1) for (x in 0 until w - 1) {
            val dx = luma[y * w + x + 1] - luma[y * w + x]
            val dy = luma[(y + 1) * w + x] - luma[y * w + x]
            total += sqrt(dx * dx + dy * dy); count++
        }
        return if (count == 0) 0.0 else total / count * w
    }

    private fun featureSize(luma: DoubleArray, mean: Double, w: Int, h: Int): Double? {
        var crossings = 0L; var lines = 0
        for (y in 0 until h) {
            var previous = luma[y * w] >= mean
            for (x in 1 until w) { val now = luma[y * w + x] >= mean; if (now != previous) crossings++; previous = now }
            lines++
        }
        for (x in 0 until w) {
            var previous = luma[x] >= mean
            for (y in 1 until h) { val now = luma[y * w + x] >= mean; if (now != previous) crossings++; previous = now }
            lines++
        }
        val perLine = crossings.toDouble() / lines
        return if (perLine < 0.5) null else 1.0 / perLine
    }

    private fun colourfulness(shown: CpuRaster): Double {
        val n = shown.width * shown.height
        val rg = DoubleArray(n); val yb = DoubleArray(n)
        for (p in 0 until n) {
            val r = shown.rgba[p * 4] * 255.0; val g = shown.rgba[p * 4 + 1] * 255.0; val b = shown.rgba[p * 4 + 2] * 255.0
            rg[p] = r - g; yb[p] = 0.5 * (r + g) - b
        }
        fun meanStd(a: DoubleArray): Pair<Double, Double> {
            val m = a.average(); return m to sqrt(a.sumOf { (it - m) * (it - m) } / a.size)
        }
        val (mrg, srg) = meanStd(rg); val (myb, syb) = meanStd(yb)
        return sqrt(srg * srg + syb * syb) + 0.3 * sqrt(mrg * mrg + myb * myb)
    }

    /** Deterministic k-means (k = 5) over at most 4096 evenly strided pixels, farthest-point seeded. */
    private fun palette(shown: CpuRaster): List<StudySwatch> {
        val n = shown.width * shown.height
        val stride = max(1, n / 4096)
        val samples = (0 until n step stride).map { p -> doubleArrayOf(shown.rgba[p * 4].toDouble(), shown.rgba[p * 4 + 1].toDouble(), shown.rgba[p * 4 + 2].toDouble()) }
        fun d2(a: DoubleArray, b: DoubleArray) = (a[0] - b[0]).let { it * it } + (a[1] - b[1]).let { it * it } + (a[2] - b[2]).let { it * it }
        val mean = DoubleArray(3) { c -> samples.sumOf { it[c] } / samples.size }
        val centres = mutableListOf(samples.maxBy { d2(it, mean) }.copyOf())
        while (centres.size < 5) {
            val next = samples.maxBy { s -> centres.minOf { d2(s, it) } }
            if (centres.minOf { d2(next, it) } < 1e-6) break
            centres += next.copyOf()
        }
        val assignment = IntArray(samples.size)
        repeat(12) {
            samples.forEachIndexed { i, s -> assignment[i] = centres.indices.minBy { d2(s, centres[it]) } }
            centres.indices.forEach { k ->
                val members = samples.indices.filter { assignment[it] == k }
                if (members.isNotEmpty()) for (c in 0 until 3) centres[k][c] = members.sumOf { samples[it][c] } / members.size
            }
        }
        return centres.indices.map { k -> centres[k] to samples.indices.count { assignment[it] == k }.toDouble() / samples.size }
            .filter { it.second >= 0.01 }
            .sortedByDescending { it.second }
            .map { (c, f) ->
                StudySwatch("#%02x%02x%02x".format(Locale.US, (c[0] * 255).roundToInt().coerceIn(0, 255),
                    (c[1] * 255).roundToInt().coerceIn(0, 255), (c[2] * 255).roundToInt().coerceIn(0, 255)), round(f))
            }
    }

    fun motion(a: CpuRaster, b: CpuRaster, from: Double, to: Double): StudyMotion {
        val pa = presented(a).toArgb(); val pb = presented(b).toArgb()
        var total = 0.0; var changed = 0
        for (i in pa.indices) {
            val d = levels(pa[i], pb[i])
            total += channelMean(pa[i], pb[i]); if (d > 2) changed++
        }
        return StudyMotion(from, to, round(total / pa.size), round(changed.toDouble() / pa.size))
    }

    private fun levels(p: Int, q: Int): Int = maxOf(
        abs(((p shr 16) and 0xff) - ((q shr 16) and 0xff)),
        abs(((p shr 8) and 0xff) - ((q shr 8) and 0xff)),
        abs((p and 0xff) - (q and 0xff)))

    private fun channelMean(p: Int, q: Int): Double = (
        abs(((p shr 16) and 0xff) - ((q shr 16) and 0xff)) +
            abs(((p shr 8) and 0xff) - ((q shr 8) and 0xff)) +
            abs((p and 0xff) - (q and 0xff))) / 3.0

    private fun lumaLevel(p: Int): Double = 0.2126 * ((p shr 16) and 0xff) + 0.7152 * ((p shr 8) and 0xff) + 0.0722 * (p and 0xff)

    /**
     * Displayed-colour difference between two same-size rasters, split into outside and inside an
     * optional `uv` [region] (`u0, v0, u1, v1`). With [lumaOnly] only brightness is compared. A pixel
     * counts as changed when it moved by more than one 8-bit level. With [shown], [a] and [b] are
     * already what the window shows (a capture, see [fromPng]) and are compared as they are.
     */
    fun difference(a: CpuRaster, b: CpuRaster, region: List<Double>?, lumaOnly: Boolean, shown: Boolean = false): Pair<StudyDifference, StudyDifference?> {
        require(a.width == b.width && a.height == b.height)
        val pa = (if (shown) a else presented(a)).toArgb(); val pb = (if (shown) b else presented(b)).toArgb()
        return split(a.width, a.height, region, 1.0) { i ->
            if (lumaOnly) abs(lumaLevel(pa[i]) - lumaLevel(pb[i])) else levels(pa[i], pb[i]).toDouble()
        }
    }

    /** Raw-value difference (largest component) — for comparing what two programs leave on the stack at a stage. */
    fun rawDifference(a: CpuRaster, b: CpuRaster, region: List<Double>?): Pair<StudyDifference, StudyDifference?> {
        require(a.width == b.width && a.height == b.height)
        return split(a.width, a.height, region, 1e-6) { i ->
            (0 until 4).maxOf { c ->
                val x = a.rgba[i * 4 + c]; val y = b.rgba[i * 4 + c]
                when {
                    x.isNaN() && y.isNaN() -> 0.0
                    x == y -> 0.0
                    else -> abs(x - y).toDouble().takeIf { it.isFinite() } ?: Double.POSITIVE_INFINITY
                }
            }
        }
    }

    private class Accumulator(val scope: String, val threshold: Double) {
        var pixels = 0; var max = 0.0; var total = 0.0; var at = -1; var changed = 0
        fun add(i: Int, d: Double) {
            pixels++; total += d
            if (d > threshold) changed++
            if (at < 0 || d > max) { max = d; at = i }
        }
    }

    private fun split(w: Int, h: Int, region: List<Double>?, threshold: Double, delta: (Int) -> Double): Pair<StudyDifference, StudyDifference?> {
        region?.let { require(it.size == 4 && it[0] < it[2] && it[1] < it[3]) { "A region is [u0, v0, u1, v1] with u0 < u1 and v0 < v1" } }
        val outside = Accumulator(if (region == null) "whole frame" else "outside the allowed region", threshold)
        val inside = Accumulator("inside the allowed region", threshold)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val u = (x + 0.5) / w; val v = 1.0 - (y + 0.5) / h
            val inRegion = region != null && u >= region[0] && u <= region[2] && v >= region[1] && v <= region[3]
            (if (inRegion) inside else outside).add(i, delta(i))
        }
        fun result(a: Accumulator) = StudyDifference(
            a.scope, a.pixels, round(a.max), round(if (a.pixels == 0) 0.0 else a.total / a.pixels),
            round(if (a.pixels == 0) 0.0 else a.changed.toDouble() / a.pixels),
            a.at.takeIf { it >= 0 }?.let { round((it % w + 0.5) / w) },
            a.at.takeIf { it >= 0 }?.let { round(1.0 - (it / w + 0.5) / h) })
        return result(outside) to (if (region != null) result(inside) else null)
    }

    // ---- Pictures -----------------------------------------------------------------------------

    /** One picture on a sheet: pixels (or an error in their place), and up to two lines of caption. */
    class Tile(val argb: IntArray?, val width: Int, val height: Int, val label: String, val caption: String = "", val error: String? = null)

    private val font: String by lazy {
        val names = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        listOf("Iosevka", "Iosevka Term", "Menlo", Font.MONOSPACED).first { it in names || it == Font.MONOSPACED }
    }

    fun tile(raster: CpuRaster?, label: String, caption: String = "", error: String? = null): Tile =
        if (raster == null) Tile(null, 1, 1, label, caption, error ?: "no image")
        else Tile(raster.toArgb(), raster.width, raster.height, label, caption, error)

    /** Show a raw raster as colour when it already is one, else stretched per channel into view. */
    fun normalizedTile(raw: CpuRaster, label: String, caption: String): Pair<Tile, Boolean> {
        val n = raw.width * raw.height
        var lo = Float.POSITIVE_INFINITY; var hi = Float.NEGATIVE_INFINITY
        val los = FloatArray(3) { Float.POSITIVE_INFINITY }; val his = FloatArray(3) { Float.NEGATIVE_INFINITY }
        for (p in 0 until n) for (c in 0 until 3) {
            val v = raw.rgba[p * 4 + c]
            if (v.isFinite()) { lo = min(lo, v); hi = max(hi, v); los[c] = min(los[c], v); his[c] = max(his[c], v) }
        }
        if (lo >= -1e-4f && hi <= 1.0001f) return tile(presented(raw), label, caption) to false
        val out = FloatArray(raw.rgba.size)
        for (p in 0 until n) {
            for (c in 0 until 3) {
                val v = raw.rgba[p * 4 + c]
                val span = his[c] - los[c]
                out[p * 4 + c] = if (!v.isFinite()) 0f else if (span < 1e-9f) 0.5f else (v - los[c]) / span
            }
            out[p * 4 + 3] = 1f
        }
        return tile(CpuRaster(raw.width, raw.height, out), label, caption) to true
    }

    /**
     * Lay [rows] of tiles out as one PNG: a title, a line of context, then each row of tiles
     * [tileSize] wide and [tileHeight] tall (square unless a wide scene asks otherwise), with captions
     * under each tile. Small renders are scaled up with nearest-neighbour so a model sees pixels
     * rather than a blur.
     */
    fun sheet(title: String, subtitle: String, rows: List<List<Tile>>, tileSize: Int, rowLabels: List<String> = emptyList(), footer: String = "",
              tileHeight: Int = tileSize): ByteArray {
        val columns = max(1, rows.maxOfOrNull { it.size } ?: 1)
        val labelWidth = if (rowLabels.isEmpty()) 0 else 150
        val captionHeight = 44
        val gap = 14
        val width = 28 * 2 + labelWidth + columns * tileSize + (columns - 1) * gap
        val headerHeight = if (subtitle.isBlank()) 50 else 74
        val footerHeight = if (footer.isBlank()) 12 else 36
        val height = headerHeight + rows.size * (tileHeight + captionHeight + gap) + footerHeight
        val image = BufferedImage(max(width, 520), height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(10, 10, 12); g.fillRect(0, 0, image.width, image.height)
        g.color = Color(236, 236, 236); g.font = Font(font, Font.PLAIN, 17)
        g.drawString(title, 28, 32)
        if (subtitle.isNotBlank()) { g.color = Color(165, 165, 170); g.font = Font(font, Font.PLAIN, 13); g.drawString(clip(g, subtitle, image.width - 56), 28, 56) }
        rows.forEachIndexed { r, row ->
            val y = headerHeight + r * (tileHeight + captionHeight + gap)
            rowLabels.getOrNull(r)?.let { label ->
                g.color = Color(200, 200, 205); g.font = Font(font, Font.PLAIN, 13)
                label.split('\n').forEachIndexed { i, part -> g.drawString(clip(g, part, labelWidth - 12), 28, y + 18 + i * 17) }
            }
            row.forEachIndexed { c, tile ->
                val x = 28 + labelWidth + c * (tileSize + gap)
                if (tile.argb != null) {
                    val source = BufferedImage(tile.width, tile.height, BufferedImage.TYPE_INT_RGB)
                    source.setRGB(0, 0, tile.width, tile.height, tile.argb, 0, tile.width)
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        if (tile.width < tileSize) RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR else RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    g.drawImage(source, x, y, tileSize, tileHeight, null)
                } else {
                    g.color = Color(40, 20, 20); g.fillRect(x, y, tileSize, tileHeight)
                    g.color = Color(240, 140, 120); g.font = Font(font, Font.PLAIN, 12)
                    wrap(g, tile.error ?: "no image", tileSize - 12).take(max(1, (tileHeight - 8) / 15)).forEachIndexed { i, part -> g.drawString(part, x + 6, y + 18 + i * 15) }
                }
                g.color = Color(60, 60, 64); g.stroke = BasicStroke(1f); g.drawRect(x - 1, y - 1, tileSize + 1, tileHeight + 1)
                g.color = Color(225, 225, 228); g.font = Font(font, Font.PLAIN, 13)
                g.drawString(clip(g, tile.label, tileSize), x, y + tileHeight + 17)
                if (tile.caption.isNotBlank()) { g.color = Color(150, 150, 155); g.font = Font(font, Font.PLAIN, 11); g.drawString(clip(g, tile.caption, tileSize), x, y + tileHeight + 34) }
            }
        }
        if (footer.isNotBlank()) { g.color = Color(130, 130, 135); g.font = Font(font, Font.PLAIN, 12); g.drawString(clip(g, footer, image.width - 56), 28, height - 14) }
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** A difference heat map: black where equal, through red to yellow-white at [full] levels. */
    fun differenceTile(a: CpuRaster, b: CpuRaster, label: String, caption: String, full: Double = 32.0, shown: Boolean = false): Tile {
        val pa = (if (shown) a else presented(a)).toArgb(); val pb = (if (shown) b else presented(b)).toArgb()
        val out = IntArray(pa.size) { i ->
            val d = (levels(pa[i], pb[i]) / full).coerceIn(0.0, 1.0)
            val r = (255 * min(1.0, d * 2)).roundToInt(); val g = (255 * max(0.0, d * 2 - 1)).roundToInt()
            (0xff shl 24) or (r shl 16) or (g shl 8) or (g / 2)
        }
        return Tile(out, a.width, a.height, label, caption)
    }

    /** [differenceTile] for raw stack values: white where the largest component differs by [full] or more. */
    fun rawDifferenceTile(a: CpuRaster, b: CpuRaster, label: String, caption: String, full: Double = 0.1): Tile {
        val out = IntArray(a.width * a.height) { i ->
            val d0 = (0 until 4).maxOf { c ->
                val x = a.rgba[i * 4 + c]; val y = b.rgba[i * 4 + c]
                if (x == y || (x.isNaN() && y.isNaN())) 0.0 else abs(x - y).toDouble().takeIf { it.isFinite() } ?: full
            }
            val d = (d0 / full).coerceIn(0.0, 1.0)
            val r = (255 * min(1.0, d * 2)).roundToInt(); val g = (255 * max(0.0, d * 2 - 1)).roundToInt()
            (0xff shl 24) or (r shl 16) or (g shl 8) or (g / 2)
        }
        return Tile(out, a.width, a.height, label, caption)
    }

    private fun clip(g: java.awt.Graphics2D, text: String, width: Int): String {
        val metrics = g.fontMetrics
        if (metrics.stringWidth(text) <= width) return text
        var cut = text
        while (cut.isNotEmpty() && metrics.stringWidth("$cut…") > width) cut = cut.dropLast(1)
        return "$cut…"
    }

    private fun wrap(g: java.awt.Graphics2D, text: String, width: Int): List<String> {
        val metrics = g.fontMetrics
        val lines = mutableListOf<String>()
        var line = ""
        text.split(Regex("\\s+")).forEach { word ->
            val next = if (line.isEmpty()) word else "$line $word"
            if (metrics.stringWidth(next) > width && line.isNotEmpty()) { lines += line; line = word } else line = next
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    fun format(v: Double): String = String.format(Locale.US, "%.3g", v)

    /**
     * A PNG — a capture of what a window or target shows — as a raster of the values it shows, `0..1`
     * per channel, alpha one. Already presented: compare such rasters with `shown = true`.
     */
    fun fromPng(png: ByteArray): CpuRaster {
        val image = ImageIO.read(png.inputStream()) ?: throw IllegalArgumentException("not an image")
        val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val out = FloatArray(argb.size * 4)
        argb.forEachIndexed { i, p ->
            out[i * 4] = ((p shr 16) and 0xff) / 255f; out[i * 4 + 1] = ((p shr 8) and 0xff) / 255f; out[i * 4 + 2] = (p and 0xff) / 255f; out[i * 4 + 3] = 1f
        }
        return CpuRaster(image.width, image.height, out)
    }
}
