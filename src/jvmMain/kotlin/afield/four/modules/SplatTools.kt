@file:Suppress("unused")

package afield.four.modules

import afield.four.fabricated.GaussianSplatRenderer
import afield.four.fabricated.SplatSequence
import java.io.File

/**
 * A command line over the CPU splat toolchain, for looking at a capture without opening Field.
 *
 * ```
 * run-jvm-main afield.four.modules.SplatToolsKt sheet ~/Downloads/circle-05-a120p0_00001_ out/
 * run-jvm-main afield.four.modules.SplatToolsKt frame ~/Downloads/shot/frame_000060.compressed.ply out/
 * ```
 *
 * This exists because the first question anyone has about a fresh 126-frame capture — is it any
 * good, and which frames are worth looking at — should not require a GPU, a window and a scrub. It
 * is also the second consumer of [SplatRaster] after [SplatContactSheet], which is the point: a
 * rasterizer with exactly one caller is a rasterizer whose interface has never been tested.
 */
object SplatTools {

    /** What [sheet] and [frame] wrote, so a caller can report it without re-reading the files. */
    data class Written(val file: File, val bytes: Int, val note: String)

    /**
     * A contact sheet of [directory]'s frames into [outputDir].
     *
     * @param tiles how many frames to show, spread across the whole sequence.
     */
    fun sheet(
        directory: File,
        outputDir: File,
        tiles: Int = 24,
        layout: SplatContactSheet.Layout = SplatContactSheet.Layout(),
        config: SplatRaster.RasterConfig = SplatRaster.RasterConfig(sizeScale = 2.5f, alphaScale = 1.6f)
    ): Written {
        val sequence = SplatSequence.fromDirectory(directory)
        val result = SplatContactSheet.of(
            sequence,
            frames = SplatContactSheet.spread(sequence.frameCount, tiles),
            layout = layout,
            config = config
        )
        outputDir.mkdirs()
        val file = File(outputDir, "${directory.name}_sheet.png")
        val png = result.png()
        file.writeBytes(png)
        return Written(
            file, png.size,
            "$result; camera eye ${result.camera.eye}, target ${result.camera.target}; " +
                "image ${result.image.stats()}"
        )
    }

    /**
     * Print what is in a cloud, without drawing it: [SplatCloud.CloudStats] over one file.
     *
     * The verb that answers "is this render dark because the capture is dark, or because I have the
     * gamma wrong". Rendering can only ever pose that question; only the data can settle it.
     */
    fun describe(path: File): String {
        val cloud = SplatCloud.read(path.absolutePath)
        return "${path.name}\n${cloud.stats()}"
    }

    /** One frame of a sequence, or one standalone `.compressed.ply`, at [width] x [height]. */
    fun frame(
        path: File,
        outputDir: File,
        width: Int = 800,
        height: Int = 450,
        config: SplatRaster.RasterConfig = SplatRaster.RasterConfig(sizeScale = 2.5f, alphaScale = 1.6f)
    ): Written {
        val raw = CompressedSplatPly.readRaw(path.absolutePath)
        val packed = SplatRaster.allocatePacked(raw.count)
        val bounds = GaussianSplatRenderer.packInto(raw, packed)
        val camera = SplatContactSheet.SheetCamera.autoFrame(
            bounds.boundsMin, bounds.boundsMax, aspect = width.toFloat() / height.toFloat()
        )
        val result = SplatRaster.rasterize(packed, raw.count, camera.viewAt(width, height), config)
        outputDir.mkdirs()
        val file = File(outputDir, "${path.name.removeSuffix(".compressed.ply")}.png")
        val png = result.image.toPng()
        file.writeBytes(png)
        return Written(
            file, png.size,
            "${raw.count} splats, ${result.stats}; bounds ${bounds.boundsMin}..${bounds.boundsMax}; " +
                "${result.image.stats()}"
        )
    }
}

fun main(args: Array<String>) {
    val usage = "usage: sheet  <sequence-directory> <output-directory> [tiles]\n" +
        "       frame  <file.compressed.ply> <output-directory> [width] [height]\n" +
        "       stats  <file.compressed.ply>"
    if (args.size < 2) {
        println(usage)
        System.exit(2)
        return
    }
    if (args[0] == "stats") {
        val target = File(args[1])
        if (!target.exists()) {
            println("no such path: ${target.absolutePath}")
            System.exit(2)
            return
        }
        println(SplatTools.describe(target))
        return
    }
    if (args.size < 3) {
        println(usage)
        System.exit(2)
        return
    }
    val input = File(args[1])
    if (!input.exists()) {
        println("no such path: ${input.absolutePath}")
        System.exit(2)
        return
    }
    val out = File(args[2])
    val written = when (args[0]) {
        "sheet" -> SplatTools.sheet(input, out, tiles = args.getOrNull(3)?.toInt() ?: 24)
        "frame" -> SplatTools.frame(
            input, out,
            width = args.getOrNull(3)?.toInt() ?: 800,
            height = args.getOrNull(4)?.toInt() ?: 450
        )
        else -> {
            println("unknown verb '${args[0]}'; expected 'sheet' or 'frame'")
            System.exit(2)
            return
        }
    }
    println("wrote ${written.file.absolutePath} (${written.bytes} bytes)")
    println("  ${written.note}")
}
