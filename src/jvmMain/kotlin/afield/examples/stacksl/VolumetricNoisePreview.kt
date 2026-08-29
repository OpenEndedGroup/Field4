package afield.examples.stacksl

import afield.stacksl.NoiseVec3
import afield.stacksl.VolumetricNoiseConstants
import afield.stacksl.VolumetricNoiseReference
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt
import javax.imageio.ImageIO

/**
 * Draws the figure that settles the argument this branch is about, on the CPU, with no device.
 *
 * The claim under test is visual and cannot be settled by a number: that StackSL's 2D `fbm`, fed a
 * 3D position, produces *one pattern imprinted at every depth* rather than a volume. So the figure
 * is a filmstrip — the same field sampled at a march of z values — for the 2D word on the top row
 * and [VolumetricNoiseReference.fbm3] on the bottom. If the top row's frames are visibly the same
 * picture drifting sideways while the bottom row's frames are unrelated, the defect and the fix
 * are both established by looking, which is the only way this kind of claim gets established.
 *
 * A third row draws `curl3`'s vector field as line-integral-convolution-ish streaks, because
 * "divergence-free" also has a look: closed swirls, no sources, no sinks.
 *
 * Run it with the jvmMain classpath; it writes PNGs to the directory given as the first argument
 * (default: a temp dir) and prints the paths. It deliberately writes nowhere by default, so it can
 * never scribble on a committed asset by accident.
 */
object VolumetricNoisePreview {

    private const val TILE = 150
    private const val PAD = 6
    private val SLICES = listOf(0.0f, 0.35f, 0.7f, 1.05f, 1.4f, 1.75f)

    /** The shipped 2D lowering, transcribed: `_fbm(input.xy + input.zw * 0.07)`, 5 fixed octaves. */
    private fun fract(v: Float) = v - floor(v)

    private fun hash21(px: Float, py: Float): Float {
        var x = fract(px * 123.34f)
        var y = fract(py * 456.21f)
        val d = x * (x + 45.32f) + y * (y + 45.32f)
        x += d
        y += d
        return fract(x * y)
    }

    private fun noise2(px: Float, py: Float): Float {
        val ix = floor(px)
        val iy = floor(py)
        val ux = (px - ix).let { it * it * (3f - 2f * it) }
        val uy = (py - iy).let { it * it * (3f - 2f * it) }
        val a = hash21(ix, iy)
        val b = hash21(ix + 1f, iy)
        val c = hash21(ix, iy + 1f)
        val d = hash21(ix + 1f, iy + 1f)
        val lo = a + (b - a) * ux
        val hi = c + (d - c) * ux
        return lo + (hi - lo) * uy
    }

    /** What `input fbm` actually computes for `input = vec4(x, y, z, 0)`. */
    fun legacy2dFbmWord(x: Float, y: Float, z: Float): Float {
        var px = x + z * 0.07f
        var py = y
        var value = 0.0f
        var amplitude = 0.5f
        repeat(5) {
            value += amplitude * noise2(px, py)
            val nx = 1.6f * px + 1.2f * py + 17.17f
            val ny = -1.2f * px + 1.6f * py + 17.17f
            px = nx
            py = ny
            amplitude *= 0.5f
        }
        return value
    }

    private fun gray(v: Float): Int {
        val c = (v.coerceIn(0f, 1f) * 255).toInt()
        return (0xFF shl 24) or (c shl 16) or (c shl 8) or c
    }

    private fun tile(z: Float, field: (Float, Float, Float) -> Float): BufferedImage {
        val img = BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB)
        for (py in 0 until TILE) {
            for (px in 0 until TILE) {
                val x = (px / TILE.toFloat()) * 4.0f - 2.0f
                val y = (py / TILE.toFloat()) * 4.0f - 2.0f
                img.setRGB(px, py, gray(field(x, y, z)))
            }
        }
        return img
    }

    /**
     * `curl3`'s field, drawn by advecting a dense grid of tracers a short way and accumulating
     * where they pass. A divergence-free field keeps the tracer density even; a field with sources
     * and sinks tears holes and piles up bright knots.
     */
    private fun curlTile(z: Float): BufferedImage {
        val acc = FloatArray(TILE * TILE)
        val steps = 24
        val dt = 0.012f
        for (sy in 0 until TILE) {
            for (sx in 0 until TILE) {
                var x = (sx / TILE.toFloat()) * 4.0f - 2.0f
                var y = (sy / TILE.toFloat()) * 4.0f - 2.0f
                repeat(steps) {
                    val v = VolumetricNoiseReference.curl3(NoiseVec3(x, y, z))
                    val m = sqrt(v.x * v.x + v.y * v.y) + 1e-6f
                    x += (v.x / m) * dt
                    y += (v.y / m) * dt
                    val ix = (((x + 2.0f) / 4.0f) * TILE).toInt()
                    val iy = (((y + 2.0f) / 4.0f) * TILE).toInt()
                    if (ix in 0 until TILE && iy in 0 until TILE) acc[iy * TILE + ix] += 1.0f
                }
            }
        }
        val peak = acc.max().coerceAtLeast(1.0f)
        val img = BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB)
        for (i in acc.indices) {
            val v = min(1.0f, acc[i] / (peak * 0.12f))
            img.setRGB(i % TILE, i / TILE, gray(v))
        }
        return img
    }

    private fun strip(rows: List<Pair<String, List<BufferedImage>>>): BufferedImage {
        val label = 26
        val gutter = 108
        val w = gutter + SLICES.size * TILE + (SLICES.size + 1) * PAD
        val h = label + rows.size * (TILE + PAD) + PAD
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
            java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON
        )
        g.color = java.awt.Color(18, 18, 20)
        g.fillRect(0, 0, w, h)

        g.color = java.awt.Color(150, 150, 155)
        g.font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 12)
        SLICES.forEachIndexed { c, z ->
            g.drawString("z = %.2f".format(z * 2.2f), gutter + PAD + c * (TILE + PAD), label - 9)
        }

        rows.forEachIndexed { r, (name, row) ->
            val top = label + r * (TILE + PAD)
            g.color = java.awt.Color(228, 228, 232)
            g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 13)
            g.drawString(name, PAD, top + TILE / 2)
            row.forEachIndexed { c, img ->
                g.drawImage(img, gutter + PAD + c * (TILE + PAD), top, null)
            }
        }
        g.dispose()
        return out
    }

    /** Mean absolute difference between two tiles, 0..1. The filmstrip's claim, as a number. */
    private fun meanAbsDiff(a: BufferedImage, b: BufferedImage): Double {
        var sum = 0.0
        for (y in 0 until TILE) {
            for (x in 0 until TILE) {
                val pa = (a.getRGB(x, y) and 0xFF) / 255.0
                val pb = (b.getRGB(x, y) and 0xFF) / 255.0
                sum += abs(pa - pb)
            }
        }
        return sum / (TILE * TILE)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(args.firstOrNull() ?: System.getProperty("java.io.tmpdir"))
        outDir.mkdirs()

        val scale = 2.2f
        val legacy = SLICES.map { z -> tile(z) { x, y, zz -> legacy2dFbmWord(x * scale, y * scale, zz * scale) } }
        val volumetric = SLICES.map { z ->
            tile(z) { x, y, zz -> VolumetricNoiseReference.fbm3(NoiseVec3(x * scale, y * scale, zz * scale)) }
        }
        val curl = SLICES.map { z -> curlTile(z * scale) }

        val figure = strip(
            listOf(
                "fbm  (2D)" to legacy,
                "fbm3" to volumetric,
                "curl3" to curl
            )
        )
        val file = File(outDir, "stacksl-volumetric-noise-slices.png")
        ImageIO.write(figure, "png", file)

        // The filmstrip's claim, stated numerically so the caption cannot drift from the picture.
        val legacyDrift = SLICES.indices.drop(1).map { meanAbsDiff(legacy[0], legacy[it]) }
        val volumetricDrift = SLICES.indices.drop(1).map { meanAbsDiff(volumetric[0], volumetric[it]) }
        println("wrote ${file.absolutePath} (${file.length()} bytes, ${figure.width}x${figure.height})")
        println("mean |slice - first slice|, walking z:")
        println("  legacy 2D fbm : " + legacyDrift.joinToString(", ") { "%.4f".format(it) })
        println("  fbm3          : " + volumetricDrift.joinToString(", ") { "%.4f".format(it) })
        println(
            "  final ratio   : %.2fx more change per unit z in fbm3".format(
                volumetricDrift.last() / legacyDrift.last()
            )
        )
        println("octave rotation determinant check: ${VolumetricNoiseConstants.OCTAVE_ROTATION_COLUMNS.size} columns")
    }
}
