package afield.examples.rectsl

import afield.rectsl.Color4
import afield.rectsl.Colormaps
import afield.rectsl.PaintSpec
import afield.rectsl.RectAnimationOracle
import afield.rectsl.SampledRect
import afield.rectsl.SceneProgram
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/*
FIELD_TEST
id: rectsl.dataviz.proof
runner: jvm-main
entrypoint: afield.examples.rectsl.RectSLDataVizProof
tags: [smoke, examples, rectsl, dataviz]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "dataviz proof rendered"
*/

/**
 * Pixel proof for the RectSL data-visualization family. Two kinds of PNG:
 *
 *  1. `colormaps.png` — every ramp drawn straight from [Colormaps], so the gradient
 *     you look at *is* the published control-point table (viridis dark-purple → teal →
 *     yellow, etc.).
 *  2. `<program>.png` — the exact worked programs from `rectsl_examples.md` compiled
 *     through the real `RectAnimationOracle` → `SceneProgram.sample(t)` pipeline and
 *     CPU-rasterized (fill colour + geometry are the real sampled output; only the GPU
 *     shader is skipped, which these words never touch).
 *
 * As a FIELD_TEST it renders to a temp dir and asserts colours/coverage (no committed
 * writes). Pass an output dir as `args[0]` (or `RECTSL_PROOF_DIR`) to regenerate the
 * committed proof PNGs. Runs headless and exits naturally.
 */
object RectSLDataVizProof {

    private val PROGRAMS: List<Pair<String, Double>> = listOf(
        "colormap-legend" to 2.0,
        "data-bars" to 3.0,
        "heatmap-field" to 2.0,
        "scatter-plot" to 2.0,
        "data-inspect" to 1.0,
    )

    private fun source(name: String): String = when (name) {
        "colormap-legend" -> """
            #rectsl
            wall 24 4 abstract
            duration 4
            fps 30
            "swatch" rect  0.7 3 wh
              24 '( fc 11 *  0 offset  "viridis" f colormap solid fill ) array
            ;
        """.trimIndent()
        "data-bars" -> """
            #rectsl
            wall 18 8 abstract
            duration 5
            fps 30
            '( 3 7 2 9 5 8 4 6 1 6 ) data  normalize  "magma" bars
              '( '( 0=0 0.6=1 ) ease-out alpha ) each
              0.07 stagger-time
        """.trimIndent()
        "heatmap-field" -> """
            #rectsl
            wall 12 8 abstract
            duration 4
            fps 30
            0 1 48 linspace  '( 6 * sin 1 + ) data-map  8 6 "turbo" heat
              '( '( 0=0 0.5=1 ) ease-in-out alpha ) each
              0.03 stagger-time
        """.trimIndent()
        "scatter-plot" -> """
            #rectsl
            wall 10 10 abstract
            duration 4
            fps 30
            0 1 40 linspace  dup  '( 7 * sin ) data-map  "plasma" scatter
        """.trimIndent()
        "data-inspect" -> """
            #rectsl
            wall 16 6 abstract
            duration 2
            fps 30
            '( 12 5 27 3 19 8 ) data
              dup extent  drop drop
              dup dmin drop
              dup dmax drop
              dup data-len drop
              dup 2 data-at drop
              "coolwarm" bars
        """.trimIndent()
        else -> error("unknown program $name")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val outDir = File(args.getOrNull(0) ?: System.getenv("RECTSL_PROOF_DIR")
            ?: File(System.getProperty("java.io.tmpdir"), "rectsl-dataviz-proof").path)
        outDir.mkdirs()

        // 1. Colormap strips — straight from the tables.
        val strip = renderColormaps()
        val stripFile = File(outDir, "colormaps.png")
        ImageIO.write(strip, "png", stripFile)
        println("wrote ${stripFile.absolutePath} (${strip.width}x${strip.height})")
        assertColormapSanity()

        // 2. The real worked programs, sampled + CPU-rasterized.
        PROGRAMS.forEach { (name, t) ->
            val result = RectAnimationOracle.compile(source(name))
            val program = result.programOrNull
            check(result.ok && program != null) {
                "$name failed to compile:\n" + result.diagnostics.joinToString("\n")
            }
            val img = renderProgram(program, t)
            val file = File(outDir, "$name.png")
            ImageIO.write(img, "png", file)
            val painted = paintedFraction(img, program.background)
            check(painted > 0.01) { "$name rasterized to a near-empty image (painted=$painted)" }
            println("wrote ${file.absolutePath} (${img.width}x${img.height}, painted=${"%.3f".format(painted)})")
        }

        println("dataviz proof rendered to ${outDir.absolutePath}")
    }

    // ── colormap strip ───────────────────────────────────────────────────────────
    private fun renderColormaps(): BufferedImage {
        val names = Colormaps.names
        val w = 640
        val labelW = 96
        val rowH = 40
        val gap = 10
        val pad = 12
        val h = pad * 2 + names.size * rowH + (names.size - 1) * gap
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(24, 24, 28)
        g.fillRect(0, 0, w, h)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        names.forEachIndexed { row, name ->
            val y = pad + row * (rowH + gap)
            g.color = Color.WHITE
            g.drawString(name, 8, y + rowH / 2 + 5)
            val barX = labelW
            val barW = w - labelW - pad
            for (px in 0 until barW) {
                val t = px / (barW - 1).toDouble()
                g.color = awt(Colormaps.sample(name, t))
                g.fillRect(barX + px, y, 1, rowH)
            }
        }
        g.dispose()
        return img
    }

    private fun assertColormapSanity() {
        // viridis: dark purple at 0 → yellow at 1.
        val v0 = Colormaps.sample("viridis", 0.0)
        val v1 = Colormaps.sample("viridis", 1.0)
        check(v0.r < 0.4 && v0.g < 0.2 && v0.b in 0.25..0.5) { "viridis(0) not dark-purple: $v0" }
        check(v1.r > 0.8 && v1.g > 0.7 && v1.b < 0.35) { "viridis(1) not yellow: $v1" }
        // magma/inferno start near-black.
        check(Colormaps.sample("magma", 0.0).let { it.r < 0.1 && it.g < 0.1 && it.b < 0.1 }) { "magma(0) not near-black" }
        // coolwarm diverging: blue at 0, ~grey at 0.5, red at 1.
        val c0 = Colormaps.sample("coolwarm", 0.0)
        val c1 = Colormaps.sample("coolwarm", 1.0)
        check(c0.b > c0.r && c0.b > 0.6) { "coolwarm(0) not blue: $c0" }
        check(c1.r > c1.b && c1.r > 0.6) { "coolwarm(1) not red: $c1" }
        // turbo starts dark blue-purple, ends dark red.
        check(Colormaps.sample("turbo", 0.0).b > Colormaps.sample("turbo", 0.0).g) { "turbo(0) not blue-ish" }
        println("colormap sanity: viridis/magma/coolwarm/turbo endpoints correct")
    }

    // ── CPU rasterizer for a sampled scene ────────────────────────────────────────
    private fun renderProgram(program: SceneProgram, time: Double): BufferedImage {
        val aspect = program.wall.width / program.wall.height
        val longSide = 512
        val (w, h) = if (aspect >= 1.0) longSide to (longSide / aspect).roundToInt()
        else (longSide * aspect).roundToInt() to longSide
        val img = BufferedImage(w.coerceAtLeast(2), h.coerceAtLeast(2), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = awt(program.background)
        g.fillRect(0, 0, img.width, img.height)
        g.stroke = BasicStroke(1f)

        val sx = img.width / program.wall.width
        val sy = img.height / program.wall.height
        program.sample(time).rects.forEach { r ->
            drawRect(g, r, img.width, img.height, sx, sy)
        }
        g.dispose()
        return img
    }

    private fun drawRect(g: java.awt.Graphics2D, r: SampledRect, w: Int, h: Int, sx: Double, sy: Double) {
        val pw = r.size.x * r.scale.x * sx
        val ph = r.size.y * r.scale.y * sy
        if (pw <= 0.0 || ph <= 0.0) return
        // wall origin at centre, +y up → flip vertical for image space.
        val cx = w / 2.0 + r.center.x * sx
        val cy = h / 2.0 - r.center.y * sy
        val old = g.transform
        val tf = AffineTransform()
        tf.translate(cx, cy)
        tf.rotate(-r.rotation) // negated: image y runs downward
        g.transform = tf
        g.paint = when (val fill = r.fill) {
            is PaintSpec.Solid -> awt(fill.color, r.alpha)
            is PaintSpec.LinearGradient -> awt(fill.c0, r.alpha) // approximate with the start colour
        }
        g.fill(Rectangle2D.Double(-pw / 2.0, -ph / 2.0, pw, ph))
        g.transform = old
    }

    private fun paintedFraction(img: BufferedImage, background: Color4): Double {
        val bg = awt(background).rgb and 0xFFFFFF
        var painted = 0
        val total = img.width * img.height
        var i = 0
        val step = if (total > 200_000) 4 else 1 // subsample large images
        var y = 0
        while (y < img.height) {
            var x = 0
            while (x < img.width) {
                if ((img.getRGB(x, y) and 0xFFFFFF) != bg) painted++
                x += step
                i++
            }
            y += step
        }
        return painted.toDouble() / i.coerceAtLeast(1)
    }

    private fun awt(c: Color4, alphaMul: Double = 1.0): Color {
        fun b(v: Double) = (v.coerceIn(0.0, 1.0) * 255.0).roundToInt().coerceIn(0, 255)
        return Color(b(c.r), b(c.g), b(c.b), b(c.a * alphaMul))
    }
}
