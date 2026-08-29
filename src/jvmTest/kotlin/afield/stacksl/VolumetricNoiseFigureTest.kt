package afield.stacksl

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Draws the defect and the fix, and then MEASURES the picture.
 *
 * "It compiles" is not verification for anything visual, and neither is a person nodding at a
 * thumbnail. This renders the fields on the CPU — the same reference the GLSL mirrors — into a
 * temp directory for a human to look at, and then asserts the property the eye is supposed to
 * find, so the claim survives without the human.
 *
 * The trick that makes the defect visible is the axis mapping: the image's vertical axis is **z**,
 * not y. StackSL's 2D `fbm` reads `coord.xy + coord.zw * 0.07`, so moving down the image moves the
 * sample point sideways by a fourteenth as much — every row is the same field, shifted. That is
 * literally "a fixed stripy pattern imprinted at every depth", and it renders as near-vertical
 * streaks. `fbm3` reads `coord.xyz` and renders as blobs.
 *
 * Nothing here is committed: the PNGs go to a temp dir, per the repo's no-committed-writes rule
 * for tests. The point of the file is the assertions.
 */
class VolumetricNoiseFigureTest {

    private val ref = VolumetricNoiseReference
    private val dim = 300

    // The legacy 2D helpers, transliterated from StackSL.kt's helperSource, as the "before" panel.
    private fun lHash(px: Float, py: Float): Float {
        var x = px * 123.34f
        var y = py * 456.21f
        x -= floor(x); y -= floor(y)
        val d = x * (x + 45.32f) + y * (y + 45.32f)
        x += d; y += d
        val r = x * y
        return r - floor(r)
    }

    private fun lNoise(px: Float, py: Float): Float {
        val ix = floor(px); val iy = floor(py)
        val fx = px - ix; val fy = py - iy
        val ux = fx * fx * (3f - 2f * fx); val uy = fy * fy * (3f - 2f * fy)
        val a = lHash(ix, iy); val b = lHash(ix + 1f, iy)
        val c = lHash(ix, iy + 1f); val d = lHash(ix + 1f, iy + 1f)
        val top = a + (b - a) * ux
        return top + ((c + (d - c) * ux) - top) * uy
    }

    private fun lFbm(px: Float, py: Float): Float {
        var x = px; var y = py; var amp = 0.5f; var v = 0f
        repeat(5) {
            v += amp * lNoise(x, y)
            val nx = 1.6f * x - 1.2f * y + 17.17f
            val ny = 1.2f * x + 1.6f * y + 17.17f
            x = nx; y = ny; amp *= 0.5f
        }
        return v
    }

    /** The 2D word applied to a 3D coordinate, exactly as the lowering does it. */
    private fun legacyOn3D(x: Float, y: Float, z: Float, t: Float) = lFbm(x + z * 0.07f, y + t * 0.07f)

    private fun gray(v: Float): Int {
        val c = (v.coerceIn(0f, 1f) * 255).toInt()
        return (0xFF shl 24) or (c shl 16) or (c shl 8) or c
    }

    private fun rgb(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or
            ((g.coerceIn(0f, 1f) * 255).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255).toInt()

    /** Row-to-row correlation. Near 1 means every horizontal line is a copy of the last. */
    private fun verticalSelfSimilarity(field: (Float, Float) -> Float, scale: Float): Double {
        // Compare row 0 against row k, allowing for the horizontal shift the shear introduces:
        // take the best correlation over all shifts, which is generous to the 2D word.
        val n = 256
        val row0 = FloatArray(n) { field(it * scale, 0f) }
        var best = -1.0
        val rowK = FloatArray(n) { field(it * scale, 40f * scale) }
        for (shift in -60..60) {
            var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0; var m = 0
            for (i in 0 until n) {
                val j = i + shift
                if (j < 0 || j >= n) continue
                val a = row0[i].toDouble(); val b = rowK[j].toDouble()
                sa += a; sb += b; saa += a * a; sbb += b * b; sab += a * b; m++
            }
            if (m < 100) continue
            val cov = sab / m - (sa / m) * (sb / m)
            val va = saa / m - (sa / m) * (sa / m)
            val vb = sbb / m - (sb / m) * (sb / m)
            if (va > 1e-9 && vb > 1e-9) best = maxOf(best, cov / kotlin.math.sqrt(va * vb))
        }
        return best
    }

    @Test
    fun `the defect and the fix, drawn and measured`() {
        val out = File(System.getProperty("java.io.tmpdir"), "stacksl-volumetric-figure")
        out.mkdirs()

        val scale = 6f / dim  // about six noise cells across the panel
        val panels = linkedMapOf<String, (Int, Int) -> Int>(
            // Vertical axis is z in all of these.
            "1-legacy-fbm-2d" to { px, py -> gray(legacyOn3D(px * scale, 0f, py * scale, 1.75f) * 1.0322581f) },
            "2-volumetric-fbm3" to { px, py -> gray(ref.fbm3(NoiseVec3(px * scale, 0f, py * scale))) },
            "3-curl3" to { px, py ->
                val c = ref.curl3(NoiseVec3(px * scale * 0.55f, 0f, py * scale * 0.55f))
                rgb(c.x * 0.35f + 0.5f, c.y * 0.35f + 0.5f, c.z * 0.35f + 0.5f)
            },
            "4-worley3-cracks" to { px, py ->
                val w = ref.worley3(NoiseVec3(px * scale * 1.6f, 0f, py * scale * 1.6f))
                val crack = (1f - w.z).let { it * it * it * it * it * it * it * it }
                rgb(crack, crack * 0.75f, crack * 0.35f)
            },
            "5-warp3" to { px, py -> gray(ref.warp3(NoiseVec3(px * scale, 0f, py * scale))) }
        )

        // One strip, so the before/after sit side by side.
        val strip = BufferedImage(dim * panels.size, dim, BufferedImage.TYPE_INT_ARGB)
        panels.entries.forEachIndexed { index, (name, f) ->
            val img = BufferedImage(dim, dim, BufferedImage.TYPE_INT_ARGB)
            for (py in 0 until dim) for (px in 0 until dim) {
                val c = f(px, py)
                img.setRGB(px, py, c)
                strip.setRGB(index * dim + px, py, c)
            }
            ImageIO.write(img, "png", File(out, "$name.png"))
        }
        ImageIO.write(strip, "png", File(out, "comparison-strip.png"))

        // Depth slices: the same xy plane at six increasing z. For the 2D word these are one
        // image sliding sideways; for fbm3 they are six different images.
        val sliceCount = 6
        val slices = BufferedImage(dim * sliceCount, dim * 2, BufferedImage.TYPE_INT_ARGB)
        for (s in 0 until sliceCount) {
            val z = s * 1.2f
            for (py in 0 until dim) for (px in 0 until dim) {
                slices.setRGB(
                    s * dim + px, py,
                    gray(legacyOn3D(px * scale, py * scale, z, 1.75f) * 1.0322581f)
                )
                slices.setRGB(
                    s * dim + px, dim + py,
                    gray(ref.fbm3(NoiseVec3(px * scale, py * scale, z)))
                )
            }
        }
        ImageIO.write(slices, "png", File(out, "depth-slices.png"))

        // ---- and now measure what the eye is supposed to see -------------------------------
        val legacySim = verticalSelfSimilarity({ x, z -> legacyOn3D(x, 0f, z, 1.75f) }, scale)
        val volSim = verticalSelfSimilarity({ x, z -> ref.fbm3(NoiseVec3(x, 0f, z)) }, scale)
        println("FIGURES WRITTEN TO ${out.absolutePath}")
        println(
            "row-to-row self-similarity across 40 rows of z (1.0 = the identical pattern repeated): " +
                "legacy 2D fbm = ${"%.4f".format(legacySim)}, volumetric fbm3 = ${"%.4f".format(volSim)}"
        )

        assertTrue(
            legacySim > 0.9,
            "the 2D word was expected to reproduce essentially the same row at every depth, but its " +
                "self-similarity was only $legacySim -- this test's premise is stale"
        )
        assertTrue(
            volSim < 0.5,
            "fbm3 is repeating itself down the z axis (self-similarity $volSim); it is not a real 3D field"
        )

        // A panel that is uniformly one colour is a failed render, not a subtle one.
        panels.forEach { (name, f) ->
            val values = (0 until 400).map { f((it * 7) % dim, (it * 13) % dim) and 0xFF }
            assertTrue(
                values.toSet().size > 20,
                "panel $name is nearly flat (${values.toSet().size} distinct levels); the render failed"
            )
        }
    }
}
