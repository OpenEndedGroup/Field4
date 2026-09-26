package afield.stacksl.cpu

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

// ---------------------------------------------------------------------------------------------
// The contact sheet: CPU | GPU | amplified difference, for every gallery program, in one PNG a
// person can look at.
//
// WHY THIS IS A TOOL AND NOT A TEST. Tests here render to a temp directory and inspect; they must
// not write committed assets. But "it compiles" is not verification for a visual change, and a
// number in a test log is not either — somebody has to *see* the images. So the statistics are
// asserted by `StackSLCpuGalleryTest` (temp dir, no committed writes) and the picture is produced
// by this `main`, which writes into `dev_docs/assets/stacksl_cpu_backend/` and is run by hand.
//
// This is also the only file in the CPU backend that touches AWT. The evaluator itself
// (`GlslValue`, `GlslParser`, `GlslInterpreter`, `StackSLCpu`) is AWT-free on purpose, so a live
// Field box can render a StackSL program straight into a box output with no windowing toolkit —
// `PngEncoder` -> base64 -> `display()`. Text labels are the one thing that genuinely needs a
// font, and a report generator is allowed to.
// ---------------------------------------------------------------------------------------------

/**
 * Renders every gallery program on the CPU, compares it to the committed GPU still, and writes
 * a contact sheet plus a markdown table.
 *
 * Usage: `main(repoRoot, outputDir)`, both optional — the repo root defaults to the working
 * directory and the output to `dev_docs/assets/stacksl_cpu_backend`.
 */
object StackSLCpuContactSheet {

    /** Side of one tile on the sheet. 512 decimated by 4; see [decimateAverage]. */
    const val TILE = 128

    private const val GAP = 4
    private const val LABEL_H = 17
    private const val COLUMNS = 3
    private const val CELL_W = TILE * 3 + GAP * 2
    private const val CELL_H = TILE + LABEL_H
    private const val MARGIN = 10
    private const val HEADER_H = 34

    /**
     * Build the sheet. Returns the per-program statistics in the same order as the sheet's cells,
     * so the caller can print them beside the picture rather than recomputing.
     *
     * [only] restricts it to named entries. That exists for the test, which needs to prove the
     * generator works end to end but must not spend a second full-gallery render (200 seconds) to
     * do it; the committed sheet is always the whole gallery.
     */
    fun build(repoRoot: File, outputDir: File, only: Set<String>? = null): List<GalleryAgreement> {
        val entries = StackSLCpuGallery.load(repoRoot).filter { only == null || it.name in only }
        require(entries.isNotEmpty()) { "no gallery entries matched $only" }
        outputDir.mkdirs()

        val rows = (entries.size + COLUMNS - 1) / COLUMNS
        val image = BufferedImage(
            MARGIN * 2 + COLUMNS * CELL_W + (COLUMNS - 1) * MARGIN,
            HEADER_H + MARGIN + rows * (CELL_H + MARGIN),
            BufferedImage.TYPE_INT_RGB
        )
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(18, 18, 20)
        g.fillRect(0, 0, image.width, image.height)
        g.color = Color(210, 210, 215)
        g.font = Font(Font.MONOSPACED, Font.BOLD, 15)
        g.drawString(
            "StackSL CPU backend vs committed GPU stills — per cell: CPU | GPU | |difference| x16",
            MARGIN, 22
        )

        val stats = ArrayList<GalleryAgreement>()
        entries.forEachIndexed { index, entry ->
            val col = index % COLUMNS
            val row = index / COLUMNS
            val x0 = MARGIN + col * (CELL_W + MARGIN)
            val y0 = HEADER_H + MARGIN + row * (CELL_H + MARGIN)

            val tier = StackSLCpuGallery.tierOf(entry)
            val cpu = StackSLCpuGallery.render(entry)
            val cpuArgb = StackSLCpuGallery.galleryArgb(cpu)
            val gpu = entry.gpuPng?.let { readArgb(it) }

            drawTile(g, x0, y0 + LABEL_H, cpuArgb, cpu.width)
            if (gpu != null) drawTile(g, x0 + TILE + GAP, y0 + LABEL_H, gpu.first, gpu.second)
            else drawMissing(g, x0 + TILE + GAP, y0 + LABEL_H, "no GPU still")

            val agreement = if (gpu != null && gpu.second == cpu.width) {
                val a = StackSLCpuGallery.compare(entry.name, tier, cpu, gpu.first, gpu.second)
                drawDiffTile(g, x0 + 2 * (TILE + GAP), y0 + LABEL_H, cpuArgb, gpu.first, cpu.width)
                stats += a
                a
            } else {
                drawMissing(g, x0 + 2 * (TILE + GAP), y0 + LABEL_H, "—")
                null
            }

            g.color = tierColour(tier)
            g.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
            val label = if (agreement != null) {
                "%s  [%s]  max %d  mean %.3f".format(entry.name, tier.label, agreement.maxLevels, agreement.meanLevels)
            } else {
                "%s  [%s]".format(entry.name, tier.label)
            }
            g.drawString(label, x0, y0 + 12)
        }
        g.dispose()

        val sheet = File(outputDir, "gallery_contact_sheet.png")
        ImageIO.write(image, "png", sheet)
        File(outputDir, "gallery_agreement.md").writeText(markdownTable(stats))
        return stats
    }

    private fun tierColour(tier: AgreementTier): Color = when (tier) {
        AgreementTier.EXACT_OPS -> Color(120, 225, 140)
        AgreementTier.SMOOTH_TRANSCENDENTAL -> Color(140, 200, 255)
        AgreementTier.HASH21_AMPLIFIED -> Color(250, 210, 120)
        AgreementTier.HASH11_UNBOUNDED -> Color(255, 160, 140)
        AgreementTier.FEEDBACK -> Color(180, 160, 220)
    }

    /**
     * Box-average 4x4 blocks, which is what a viewer's own downscale would do — so the CPU and
     * GPU tiles on the sheet read as the images they are and not as aliasing artefacts.
     */
    private fun drawTile(g: java.awt.Graphics2D, x: Int, y: Int, argb: IntArray, width: Int) {
        val step = width / TILE
        for (ty in 0 until TILE) for (tx in 0 until TILE) {
            g.color = Color(decimateAverage(argb, width, tx * step, ty * step, step))
            g.fillRect(x + tx, y + ty, 1, 1)
        }
    }

    /**
     * The difference tile decimates by **maximum**, not by average, and amplifies 16x.
     *
     * Averaging here would be dishonest in the one direction that matters: the interesting failure
     * of this whole exercise is a handful of pixels differing by 200 levels inside an image whose
     * mean difference is a hundredth of a level, and a 4x4 box average divides such a pixel by 16
     * before the 16x amplification puts it back — hiding exactly the thing worth seeing. Green is
     * a small difference, yellow-white a large one.
     */
    private fun drawDiffTile(g: java.awt.Graphics2D, x: Int, y: Int, a: IntArray, b: IntArray, width: Int) {
        val step = width / TILE
        for (ty in 0 until TILE) for (tx in 0 until TILE) {
            var worst = 0
            for (sy in 0 until step) for (sx in 0 until step) {
                val i = (ty * step + sy) * width + (tx * step + sx)
                val p = a[i]
                val q = b[i]
                worst = maxOf(
                    worst,
                    Math.abs(((p shr 16) and 0xff) - ((q shr 16) and 0xff)),
                    Math.abs(((p shr 8) and 0xff) - ((q shr 8) and 0xff)),
                    Math.abs((p and 0xff) - (q and 0xff))
                )
            }
            g.color = Color(minOf(255, worst * 2), minOf(255, worst * 16), 0)
            g.fillRect(x + tx, y + ty, 1, 1)
        }
    }

    private fun drawMissing(g: java.awt.Graphics2D, x: Int, y: Int, text: String) {
        g.color = Color(40, 40, 44)
        g.fillRect(x, y, TILE, TILE)
        g.color = Color(140, 140, 145)
        g.font = Font(Font.MONOSPACED, Font.PLAIN, 10)
        g.drawString(text, x + 6, y + TILE / 2)
    }

    /** Average a `step` x `step` block into one packed RGB. */
    private fun decimateAverage(argb: IntArray, width: Int, x: Int, y: Int, step: Int): Int {
        var r = 0
        var gg = 0
        var b = 0
        for (sy in 0 until step) for (sx in 0 until step) {
            val p = argb[(y + sy) * width + (x + sx)]
            r += (p shr 16) and 0xff
            gg += (p shr 8) and 0xff
            b += p and 0xff
        }
        val n = step * step
        return ((r / n) shl 16) or ((gg / n) shl 8) or (b / n)
    }

    private fun readArgb(file: File): Pair<IntArray, Int> {
        val image = ImageIO.read(file) ?: error("could not read ${file.absolutePath}")
        val out = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, out, 0, image.width)
        return out to image.width
    }

    private fun markdownTable(stats: List<GalleryAgreement>): String = buildString {
        appendLine("<!-- generated by afield.stacksl.cpu.StackSLCpuContactSheet; do not edit by hand -->")
        appendLine()
        appendLine("| program | tier | mean levels | max levels | within 1 level | exact | worst pixel |")
        appendLine("| --- | --- | --- | --- | --- | --- | --- |")
        stats.sortedWith(compareBy({ it.tier.ordinal }, { -it.maxLevels })).forEach {
            appendLine(
                "| `%s` | %s | %.4f | %d | %.4f%% | %.4f%% | %d,%d |".format(
                    it.name, it.tier.label, it.meanLevels, it.maxLevels,
                    it.withinOneFraction * 100, it.exactFraction * 100,
                    it.worstPixel.first, it.worstPixel.second
                )
            )
        }
    }
}

/** Command line: `[repoRoot] [outputDir]`. */
fun main(args: Array<String>) {
    val repoRoot = File(args.getOrElse(0) { System.getProperty("user.dir") })
    val outputDir = File(args.getOrElse(1) { File(repoRoot, "dev_docs/assets/stacksl_cpu_backend").path })
    val started = System.nanoTime()
    val stats = StackSLCpuContactSheet.build(repoRoot, outputDir)
    val seconds = (System.nanoTime() - started) / 1e9
    stats.sortedWith(compareBy({ it.tier.ordinal }, { -it.maxLevels })).forEach { println(it) }
    println()
    AgreementTier.entries.forEach { tier ->
        val inTier = stats.filter { it.tier == tier }
        if (inTier.isEmpty()) return@forEach
        println(
            "%-13s n=%2d  worst max=%3d  worst mean=%.4f  worst within1=%.4f%%".format(
                tier.label, inTier.size, inTier.maxOf { it.maxLevels },
                inTier.maxOf { it.meanLevels }, inTier.minOf { it.withinOneFraction } * 100
            )
        )
    }
    println()
    println("%d program(s) compared in %.1fs -> %s".format(stats.size, seconds, outputDir.absolutePath))
}
