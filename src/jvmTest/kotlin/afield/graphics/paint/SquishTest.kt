package afield.graphics.paint

import afield.geometry.path.FLine
import afield.math.linalg.Vec4
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Folding, pressing and rubbing wet paint ([PaintCanvas.fold], [PaintCanvas.press], [PaintCanvas.rub]):
 * paint is moved and never made or lost, an even fold leaves a mirror image, and a plate takes away
 * exactly what it reports. Renders land in `build/paint-squish/` for looking at.
 */
class SquishTest {
    private fun line(vararg p: Double) = FLine().apply { moveTo(p[0], p[1]); for (i in 2 until p.size step 2) lineTo(p[i], p[i + 1]) }

    /** Thick red, blue and yellow body paint, all on the left half of a square canvas 400 px wide. */
    private fun blobs(c: PaintCanvas) {
            for ((col, path) in listOf(
                Vec4(0.8f, 0.08f, 0.06f, 1f) to line(180.0, 260.0, 430.0, 330.0),
                Vec4(0.08f, 0.15f, 0.7f, 1f) to line(220.0, 520.0, 460.0, 600.0),
                Vec4(0.95f, 0.75f, 0.1f, 1f) to line(120.0, 760.0, 400.0, 700.0),
            )) {
                c.brush(BrushKind.HogFlat, 90.0).use { b -> b.load(col, amount = 4.0); c.stroke(path, b, pressure = 1.0..1.0) }
            }
    }

    private fun save(c: PaintCanvas, name: String) {
        val dir = File("build/paint-squish").apply { mkdirs() }
        val px = c.render(wet = true, light = PaintLight.Raking)
        val w = c.widthPx
        val img = BufferedImage(w, px.size / w, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, w, px.size / w, px, 0, w)
        ImageIO.write(img, "png", File(dir, "$name.png"))
    }

    @Test fun aFoldMovesPaintButNeverMakesOrLosesIt() {
        requirePaintLibrary()
        PaintCanvas(400, aspect = 1.0, seed = 5).use { c ->
            blobs(c)
            save(c, "fold_before")
            val before = c.wetMm3
            assertTrue(before > 0.0, "the brushes laid wet paint")
            val r = c.fold(500.0, 0.0, pressure = 0.9, peel = 0.8, seed = 2)
            save(c, "fold_after")
            assertTrue(r.movedMm3 > 0.0, "the fold moved paint: $r")
            assertEquals(0.0, r.liftedMm3)
            assertTrue(abs(c.wetMm3 - before) / before < 1e-4, "$before mm³ before the fold, ${c.wetMm3} after")
        }
    }

    @Test fun anEvenFoldPrintsTheMirrorImage() {
        requirePaintLibrary()
        PaintCanvas(400, aspect = 1.0, seed = 5).use { c ->
            blobs(c)
            c.fold(500.0, 0.0, pressure = 1.0, spread = 0.0, peel = 0.0, seed = 2)
            // the left half was carried onto the right: its paint now shows there, mirrored
            val px = c.render(wet = true)
            val w = c.widthPx
            var painted = 0
            var unlike = 0
            for (y in 0 until px.size / w) for (x in 0 until w / 2) {
                val a = px[y * w + x]; val b = px[y * w + (w - 1 - x)]
                if ((a and 0xffffff) < 0xe0e0e0 || (b and 0xffffff) < 0xe0e0e0) painted++ else continue
                val d = maxOf(abs((a shr 16 and 255) - (b shr 16 and 255)), abs((a shr 8 and 255) - (b shr 8 and 255)), abs((a and 255) - (b and 255)))
                if (d > 60) unlike++
            }
            val redRight = (0 until px.size / w).count { y -> ((px[y * w + w - 1 - 117] shr 16) and 255) > 150 && (px[y * w + w - 1 - 117] and 255) < 90 }
            assertTrue(redRight > 5, "the red reached the right half ($redRight rows)")
            // where the films only just touched the two sides differ; everywhere else they print alike
            assertTrue(painted > 1000 && unlike * 20 < painted, "$unlike of $painted painted pixels differ from their mirror")
        }
    }

    @Test fun aPlateTakesWhatItReportsAndARubOnlyMoves() {
        requirePaintLibrary()
        PaintCanvas(400, aspect = 1.0, seed = 5).use { c ->
            blobs(c)
            val before = c.wetMm3
            val p = c.press(300.0, 560.0, radius = 160.0, pressure = 0.9, dx = 1.0, dy = 0.3, take = 0.5, seed = 3)
            save(c, "press_after")
            assertTrue(p.liftedMm3 > 0.0)
            assertTrue(abs(before - c.wetMm3 - p.liftedMm3) / p.liftedMm3 < 1e-3, "lifted ${before - c.wetMm3}, reported ${p.liftedMm3}")
            val mid = c.wetMm3
            val r = c.rub(line(150.0, 300.0, 850.0, 330.0), radius = 40.0, pressure = 0.8, seed = 4)
            save(c, "rub_after")
            assertTrue(r.movedMm3 > 0.0)
            assertTrue(abs(c.wetMm3 + r.liftedMm3 - mid) / mid < 1e-4, "$mid mm³ before the rub, ${c.wetMm3} after and ${r.liftedMm3} on the pad")
        }
    }
}
