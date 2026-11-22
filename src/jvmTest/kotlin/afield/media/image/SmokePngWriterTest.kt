package afield.media.image

import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [saveHdrPng] and [saveRgbPng] to the private writers the smoke tests carried before they
 * shared them: each reference below is the old smoke body, copied verbatim, and the shared writer
 * must reproduce its pixels exactly over HDR values, negatives and zero/tiny alpha.
 */
class SmokePngWriterTest {
    private val w = 37
    private val h = 23
    private val pixels = Random(7).let { r ->
        FloatArray(w * h * 4) { i ->
            when (i % 9) {
                0 -> -r.nextFloat()
                1 -> r.nextFloat() * 40f
                2 -> 0f
                else -> r.nextFloat() * 2f
            }
        }.also { it[3] = 0f; it[7] = 1e-30f }
    }
    private val dir = Files.createTempDirectory("smoke-png").toFile().apply { deleteOnExit() }

    private fun read(f: File): IntArray = ImageIO.read(f).let { it.getRGB(0, 0, w, h, null, 0, w) }
    private fun write(image: BufferedImage): File = File.createTempFile("ref", ".png", dir).also { ImageIO.write(image, "png", it) }

    /** PathTrace{Emitter,Medium}GpuSmoke.writePng before the move. */
    private fun pathTraceReference(exposure: Float): BufferedImage {
        fun srgb(v: Double) = (if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055).coerceIn(0.0, 1.0)
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 4
            val alpha = maxOf(pixels[i + 3], 1e-20f)
            var rgb = 0
            for (c in 0..2) {
                val hdr = (pixels[i + c] / alpha * exposure).coerceAtLeast(0f).toDouble()
                rgb = (rgb shl 8) or (srgb(hdr / (1.0 + hdr)) * 255.0 + 0.5).toInt().coerceIn(0, 255)
            }
            image.setRGB(x, y, rgb)
        }
        return image
    }

    /** DirectLightingQualitySmoke / RtxdiLightingSmoke / DirectLightingDemo.save before the move. */
    private fun lightingReference(): BufferedImage {
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            fun channel(c: Int): Int {
                val value = pixels[(y * w + x) * 4 + c].toDouble().coerceAtLeast(0.0)
                return (255 * (value / (1 + value)).pow(1 / 2.2)).roundToInt().coerceIn(0, 255)
            }
            image.setRGB(x, y, (channel(0) shl 16) or (channel(1) shl 8) or channel(2))
        }
        return image
    }

    @Test
    fun hdrSrgbMatchesThePathTraceWriter() {
        for (exposure in listOf(0.22f, 0.45f, 1.6f)) {
            val shared = saveHdrPng(File(dir, "pt.png"), pixels, w, h, exposure, divideByAlpha = true)
            assertEquals(read(write(pathTraceReference(exposure))).toList(), read(shared).toList(), "exposure $exposure")
        }
    }

    @Test
    fun hdrGammaMatchesTheLightingWriter() {
        val shared = saveHdrPng(File(dir, "light.png"), pixels, w, h, encoding = HdrPngEncoding.GAMMA_22)
        assertEquals(read(write(lightingReference())).toList(), read(shared).toList())
    }

    @Test
    fun rgbWriterIsTopRowFirstAndOpaque() {
        val shared = saveRgbPng(File(dir, "nested/rgb.png"), w, h) { x, y -> (x * 7 shl 16) or (y * 11 shl 8) or (x + y) }
        val px = read(shared)
        assertEquals(0xFF000000.toInt() or (3 * 7 shl 16) or (2 * 11 shl 8) or 5, px[2 * w + 3])
    }
}
