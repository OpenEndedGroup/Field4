package afield.examples.rectsl

import afield.rectsl.study.CpuRectSLRasterizer
import afield.rectsl.study.RectSLRasterizer
import afield.rectsl.study.RectSLStudy
import afield.rectsl.study.renderAll
import afield.stacksl.cpu.CpuRaster
import afield.stacksl.study.StackSLStudyShader
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/*
FIELD_TEST
id: rectsl.study.gpu-agreement
runner: backdoor-box
graphicsContext: true
tags: [rectsl, study, gpu, graphics]
timeoutSec: 180
execution: launch
waitSec: 40.0
expect:
  allowMissingOutput: true
  image:
    path: /tmp/rectsl-study-gpu-agreement.png
*/
private val GPU_AGREEMENT_SNIPPET = """
import afield.four.Renderer
import afield.four.modules.fromContext
import afield.examples.rectsl.RectSLStudyGpuSmoke
import afield.rectsl.study.RectSLGpuRasterizer
import java.util.concurrent.CompletableFuture

// The GPU study renderer waits on frames the main thread draws, so it runs off that thread.
val renderer: Renderer = fromContext()
val sheet = java.io.File("/tmp/rectsl-study-gpu-agreement.png").also { it.delete() }
val work = CompletableFuture.supplyAsync { RectSLStudyGpuSmoke.run(RectSLGpuRasterizer({ renderer }), sheet) }
loop { if (work.isDone) println(work.get()) }
""".trimIndent()

/**
 * Proof that a RectSL study's GPU frames are the scene's frames: the same scenes drawn by
 * [afield.rectsl.study.RectSLGpuRasterizer] on a live renderer and by the CPU reference agree, and a
 * few pixels are pinned outright — so a flip, a colour written without its sRGB decode, a target that
 * does not blend, or a stale time fails here rather than in a picture a model is shown.
 *
 * The two rasterize rectangle edges by different rules, so edges may differ by a pixel: agreement is
 * "almost every pixel within two levels", and the pinned pixels sit well inside their rectangles.
 * [run] writes the evidence sheet — GPU above, CPU below — only when every check passes.
 */
object RectSLStudyGpuSmoke {
    private const val HEADER = "->wall 4 2 meters\n->duration 4\n->fps 20\n->background 0x000000\n"

    /** Scenes, each with pixels (wall x, y, time) whose shown colour is pinned. */
    val SCENES: List<Triple<String, String, List<Pair<List<Double>, Int>>>> = listOf(
        Triple("up is up", HEADER + "\"sun\" rect  1 0.5 ->xy  0.6 ->square  0xff0000 solid ->fill ;",
            listOf(listOf(1.0, 0.5, 0.0) to 0xff0000, listOf(1.0, -0.5, 0.0) to 0x000000, listOf(-1.0, 0.5, 0.0) to 0x000000)),
        Triple("colours as typed", HEADER + "\"grey\" rect  0 0 ->xy  3 1.5 ->wh  0x808080 solid ->fill ;",
            listOf(listOf(0.0, 0.0, 0.0) to 0x808080)),
        // Blending is on the typed values, as in a design tool and the RectSL gallery: 3% of orange over a
        // dark sky is a faint wash (#0e182f), not the maroon box linear-light blending would make of it.
        Triple("faint is faint", "->wall 4 2 meters\n->duration 4\n->fps 20\n->background 0x07152f\n\"glow\" rect  0 0 ->xy  2 1 ->wh  0xff882d solid ->fill  0.03 ->alpha ;",
            listOf(listOf(0.0, 0.0, 0.0) to 0x0e182f, listOf(1.5, 0.8, 0.0) to 0x07152f)),
        Triple("blending", HEADER + "\"a\" rect  -0.4 0 ->xy  1.2 ->square  rgba 1 0 0 0.5 solid ->fill ;\n\"b\" rect  0.4 0 ->xy  1.2 ->square  rgba 0 0 1 0.5 solid ->fill ;",
            emptyList()),
        Triple("gradient and rotation", HEADER + "\"g\" rect  0 0 ->xy  3 1 ->wh  0xff2d55 0x2d7bff  -1.5 0  1.5 0  linear ->fill  0.3 ->rot ;",
            emptyList()),
        Triple("material", HEADER + "material ramp '(\n  uv x 0.5 * [1,1,1,0] * [0,0,0,1] +\n)\n\"t\" rect  0 0 ->xy  3.6 1.8 ->wh  0xffffff solid ->fill  ramp ->material ;",
            emptyList()),
        Triple("time", HEADER + "\"m\" rect  0 0 ->xy  0.5 ->square  0x33ff66 solid ->fill  '( 0=-1.5  4=1.5 ) linear ->x ;",
            listOf(listOf(-0.75, 0.0, 1.0) to 0x33ff66, listOf(0.75, 0.0, 3.0) to 0x33ff66, listOf(-0.75, 0.0, 3.0) to 0x000000))
    )

    private const val W = 128
    private const val H = 64
    private val TIMES = listOf(1.0, 3.0)

    private fun shown(raster: CpuRaster, x: Double, y: Double): Int {
        val col = ((x + 2) / 4 * raster.width).toInt().coerceIn(0, raster.width - 1)
        val row = ((1 - y) / 2 * raster.height).toInt().coerceIn(0, raster.height - 1)
        return StackSLStudyShader.presented(raster).toArgb()[row * raster.width + col] and 0xffffff
    }

    private fun levels(a: Int, b: Int) = maxOf(abs((a shr 16 and 0xff) - (b shr 16 and 0xff)), abs((a shr 8 and 0xff) - (b shr 8 and 0xff)), abs((a and 0xff) - (b and 0xff)))

    /** Compare, pin, time and — if everything holds — write [sheet]. Returns a one-line summary; throws on a failure. */
    fun run(rasterizer: RectSLRasterizer, sheet: File): String {
        val rows = mutableListOf<Pair<IntArray, IntArray>>()
        val lines = SCENES.map { (name, source, pins) ->
            val drawable = RectSLStudy.drawable(source)
            val times = (TIMES + pins.map { it.first[2] }).distinct()
            val gpu = rasterizer.renderAll(drawable, W, H, times)
            check(rasterizer.description.startsWith("GPU")) { "expected a GPU render, got: ${rasterizer.description}" }
            val cpu = CpuRectSLRasterizer.renderAll(drawable, W, H, times)
            pins.forEach { (at, expected) ->
                val i = times.indexOf(at[2])
                val g = shown(gpu[i], at[0], at[1]); val c = shown(cpu[i], at[0], at[1])
                check(levels(g, expected) <= 1) { "$name: the GPU shows #%06x at (${at[0]}, ${at[1]}) t=${at[2]}, expected #%06x".format(g, expected) }
                check(levels(c, expected) <= 1) { "$name: the CPU reference shows #%06x at (${at[0]}, ${at[1]}) t=${at[2]}, expected #%06x".format(c, expected) }
            }
            val worst = times.indices.map { i ->
                val g = StackSLStudyShader.presented(gpu[i]).toArgb(); val c = StackSLStudyShader.presented(cpu[i]).toArgb()
                if (times[i] in TIMES) rows += g to c
                val within = g.indices.count { levels(g[it], c[it]) <= 2 }.toDouble() / g.size
                check(within > 0.97) { "$name at t=${times[i]}: only ${"%.1f".format(within * 100)}% of pixels agree to two levels" }
                within
            }.min()
            "$name ${"%.1f".format(worst * 100)}%"
        }
        val heavy = RectSLStudy.drawable(HEADER + "\"p\" rect 0.1 ->square  '( 0=-1.9  4=1.9 ) ->x  60 '( fc 0.9 * ->y  i 0.05 * ->delay-all-time  \"turbo\" f colormap solid ->fill ) array ;")
        val start = System.nanoTime()
        rasterizer.renderAll(heavy, 640, 320, List(24) { it / 6.0 })
        val seconds = (System.nanoTime() - start) / 1e9
        check(seconds < 10.0) { "24 frames of 640x320 took ${"%.1f".format(seconds)}s on the GPU" }
        writeSheet(rows, sheet)
        return "rectsl study GPU agreement: ${SCENES.size} scenes agree with the CPU reference and hold their pinned pixels (${lines.joinToString("; ")}); " +
            "24 frames of a 60-rectangle scene at 640x320 in ${"%.2f".format(seconds)}s on ${rasterizer.description}"
    }

    private fun writeSheet(rows: List<Pair<IntArray, IntArray>>, sheet: File) {
        val image = BufferedImage(rows.size * (W + 4), 2 * H + 4, BufferedImage.TYPE_INT_RGB)
        rows.forEachIndexed { i, (gpu, cpu) ->
            image.setRGB(i * (W + 4), 0, W, H, gpu, 0, W)
            image.setRGB(i * (W + 4), H + 4, W, H, cpu, 0, W)
        }
        sheet.absoluteFile.parentFile.mkdirs()
        ImageIO.write(image, "png", sheet)
    }
}
