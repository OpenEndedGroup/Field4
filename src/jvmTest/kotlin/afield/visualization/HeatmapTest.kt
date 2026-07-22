package afield

import java.io.File
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Correctness + visual proof for the [heatmapImage] / [Box.showHeatmap] helper.
 *
 * Asserts the pure raster core directly on its ARGB output: aspect-preserving sizing, value→colormap
 * mapping at the endpoints, [flipY] / [transpose] orientation, [normalize] vs fixed-range, [logScale]
 * spreading, and a valid PNG header. Then renders three real captures (a viridis Gaussian bump, a
 * coolwarm diverging field, a synthetic rising-ridge "spectrogram") into the gitignored `build/`
 * so a human can eyeball real, non-black, correctly-coloured output.
 */
class HeatmapTest {

    private fun a(argb: Int) = (argb ushr 24) and 0xFF
    private fun r(argb: Int) = (argb ushr 16) and 0xFF
    private fun g(argb: Int) = (argb ushr 8) and 0xFF
    private fun b(argb: Int) = argb and 0xFF
    private fun lum(argb: Int) = 0.2126 * r(argb) + 0.7152 * g(argb) + 0.0722 * b(argb)

    @Test
    fun defaultHeightPreservesGridAspect() {
        val m = Array(2) { DoubleArray(4) { c -> c.toDouble() } } // 2 rows x 4 cols
        val img = heatmapImage(m, width = 400)
        assertEquals(400, img.width)
        assertEquals(200, img.height, "height = width * rows/cols = 400 * 2/4")
        assertEquals(400 * 200, img.pixels.size)
        // Explicit height overrides.
        assertEquals(123, heatmapImage(m, width = 400, height = 123).height)
        // Fully opaque.
        assertTrue(img.pixels.all { a(it) == 0xFF }, "every pixel opaque")
    }

    @Test
    fun horizontalRampMapsColumnsToColormapEndpoints() {
        val cols = 16
        val m = Array(4) { DoubleArray(cols) { c -> c.toDouble() / (cols - 1) } } // 0 at left, 1 at right
        val img = heatmapImage(m, colormap = "viridis", width = 320, height = 80, smooth = false)
        val left = img.pixels[0 + 40 * img.width]
        val right = img.pixels[(img.width - 1) + 40 * img.width]
        // viridis(0) = 0x440154 (dark purple: blue dominant, green ~0); viridis(1) = 0xFDE725 (yellow).
        assertTrue(b(left) > r(left) && g(left) < 40, "left edge is dark-purple viridis(0): argb=${left.toString(16)}")
        assertTrue(r(right) > 200 && g(right) > 180 && b(right) < 90, "right edge is yellow viridis(1): argb=${right.toString(16)}")
        assertTrue(lum(right) > lum(left) + 80, "ramp gets brighter left→right")
    }

    @Test
    fun flipYControlsWhichRowLandsAtBottom() {
        val rows = 8
        val m = Array(rows) { row -> DoubleArray(4) { row.toDouble() / (rows - 1) } } // row0=0 (dark), last=1 (bright)
        val up = heatmapImage(m, colormap = "viridis", width = 40, height = 160, smooth = false, flipY = true)
        // flipY=true → row 0 (dark) at the BOTTOM, brightest row at the TOP.
        val topU = up.pixels[20 + 0 * up.width]
        val botU = up.pixels[20 + (up.height - 1) * up.width]
        assertTrue(lum(topU) > lum(botU) + 80, "flipY: top brighter than bottom")
        val dn = heatmapImage(m, colormap = "viridis", width = 40, height = 160, smooth = false, flipY = false)
        val topD = dn.pixels[20 + 0 * dn.width]
        val botD = dn.pixels[20 + (dn.height - 1) * dn.width]
        assertTrue(lum(botD) > lum(topD) + 80, "no-flip: bottom brighter than top")
    }

    @Test
    fun transposeSwapsAxes() {
        // matrix[row][col]: 2 rows, 3 cols. transpose => 3 rows, 2 cols.
        val m = arrayOf(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 1.0, 1.0))
        val normal = heatmapImage(m, width = 300) // rows=2 cols=3 → h = 300*2/3 = 200
        val trans = heatmapImage(m, width = 300, transpose = true) // rows=3 cols=2 → h = 300*3/2 = 450
        assertEquals(200, normal.height)
        assertEquals(450, trans.height)
    }

    @Test
    fun fixedRangeDiffersFromDataNormalization() {
        // Values only reach 0.5. normalize=true stretches 0.5→colormap(1); normalize=false keeps 0.5→mid.
        val m = Array(4) { DoubleArray(8) { c -> 0.5 * c / 7.0 } }
        val normd = heatmapImage(m, colormap = "viridis", width = 160, height = 40, smooth = false)
        val fixed = heatmapImage(m, colormap = "viridis", width = 160, height = 40, smooth = false, normalize = false)
        val normMax = normd.pixels[(normd.width - 1) + 20 * normd.width]
        val fixedMax = fixed.pixels[(fixed.width - 1) + 20 * fixed.width]
        // Under data-normalization the max value hits the bright end; under fixed [0,1] it stays mid/dim.
        assertTrue(lum(normMax) > lum(fixedMax) + 40,
            "normalize=true reaches brighter than fixed range (norm=${lum(normMax)} fixed=${lum(fixedMax)})")
    }

    @Test
    fun logScaleSpreadsOrdersOfMagnitude() {
        // Four columns spanning decades. Linear clusters the small ones; log spreads them evenly.
        val vals = doubleArrayOf(1.0, 10.0, 100.0, 1000.0)
        val m = arrayOf(vals.copyOf(), vals.copyOf())
        val lin = heatmapImage(m, colormap = "viridis", width = 4, height = 2, smooth = false, logScale = false)
        val log = heatmapImage(m, colormap = "viridis", width = 4, height = 2, smooth = false, logScale = true)
        fun col(img: RasterImage, c: Int) = img.pixels[c] // row 0
        // Log: luminance strictly increases across the four decades and the gaps are comparable.
        val ll = (0..3).map { lum(col(log, it)) }
        assertTrue(ll[1] > ll[0] && ll[2] > ll[1] && ll[3] > ll[2], "log luminance monotonic: $ll")
        // Linear: the 10 vs 1 gap is tiny compared with log (both ~bottom of the map).
        val linLow = lum(col(lin, 1)) - lum(col(lin, 0))
        val logLow = lum(col(log, 1)) - lum(col(log, 0))
        assertTrue(logLow > linLow + 20, "log separates decade-low values better than linear (log=$logLow lin=$linLow)")
    }

    @Test
    fun pngHasValidHeaderAndIsNonTrivial() {
        val m = Array(20) { row -> DoubleArray(20) { col -> sin(row * 0.4) * sin(col * 0.4) } }
        val png = heatmapImage(m, colormap = "turbo", width = 200).png()
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue(png.size > 1000, "PNG non-trivial (${png.size} bytes)")
        for (i in sig.indices) assertEquals(sig[i], png[i], "PNG signature byte $i")
    }

    @Test
    fun outputHasRealColourVariety() {
        val m = Array(24) { row ->
            DoubleArray(24) { col ->
                val dx = (col - 12) / 8.0; val dy = (row - 12) / 8.0
                exp(-(dx * dx + dy * dy))
            }
        }
        val img = heatmapImage(m, colormap = "magma", width = 240)
        val distinct = img.pixels.toHashSet()
        assertTrue(distinct.size > 200, "a Gaussian bump must have real colour variety (got ${distinct.size})")
    }

    /** Endpoint sanity for every named colormap: 0 and 1 must differ, and channels stay in range. */
    @Test
    fun everyColormapEndpointDiffers() {
        for (name in Colormaps.names) {
            val m = arrayOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0))
            val img = heatmapImage(m, colormap = name, width = 20, height = 20, smooth = false)
            val lo = img.pixels[0]
            val hi = img.pixels[img.width - 1]
            assertTrue(lo != hi, "$name endpoints must differ")
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Visual proof — render real captures into gitignored build/ so a human can eyeball them.
    // (Not committed; the shipped demo transcript drives the live display() path separately.)
    // ----------------------------------------------------------------------------------------------

    @Test
    fun rendersEyeballableCaptures() {
        val outDir = File(worktreeRoot(), "build/heatmap-proof").apply { mkdirs() }

        // 1. Viridis Gaussian bump.
        val bump = Array(48) { row ->
            DoubleArray(64) { col ->
                val dx = (col - 32) / 16.0; val dy = (row - 24) / 12.0
                exp(-(dx * dx + dy * dy))
            }
        }
        File(outDir, "gaussian_viridis.png").writeBytes(heatmapImage(bump, colormap = "viridis", width = 640).png())

        // 2. Coolwarm diverging field (values in [-1,1]).
        val diverging = Array(48) { row ->
            DoubleArray(64) { col -> sin(col * 0.18) * sin(row * 0.22) }
        }
        File(outDir, "diverging_coolwarm.png")
            .writeBytes(heatmapImage(diverging, colormap = "coolwarm", width = 640, vmin = -1.0, vmax = 1.0, normalize = false).png())

        // 3. Synthetic [frame][bin] "spectrogram": a rising ridge — transpose + flipY + logScale.
        val frames = 120; val bins = 80
        val spec = Array(frames) { f ->
            DoubleArray(bins) { bin ->
                val ridge = (bins * (0.15 + 0.7 * f / frames)).toInt()
                val d = (bin - ridge).toDouble()
                (0.001 + exp(-d * d / 12.0)) * (1.0 + 0.3 * sin(f * 0.3))
            }
        }
        File(outDir, "spectrogram_synthetic.png")
            .writeBytes(heatmapImage(spec, colormap = "magma", width = 900, transpose = true, flipY = true, logScale = true).png())

        for (n in listOf("gaussian_viridis.png", "diverging_coolwarm.png", "spectrogram_synthetic.png")) {
            val f = File(outDir, n)
            assertTrue(f.length() > 2000, "$n must be a non-trivial PNG (${f.length()} bytes)")
        }
        println("HEATMAP_PROOF_DIR=${outDir.absolutePath}")
    }

    private fun worktreeRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        return File(System.getProperty("user.dir"))
    }
}
