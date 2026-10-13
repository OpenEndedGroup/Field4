package afield.graphics.paint

import afield.geometry.path.FLine
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Watercolour and ink through Field: every volume and mass accounted for, the wet canvas saved and
 * restored exactly, brushes kept, gestures timed, and paint over watercolour sealing it.
 */
class WaterMediaTest {

    private fun available(): Boolean {
        if (!PaintLibrary.isAvailable) println("SKIPPED water media tests: ${PaintLibrary.describe()}")
        return PaintLibrary.isAvailable
    }

    private fun rect(x0: Double, y0: Double, x1: Double, y1: Double) =
        FLine().moveTo(x0, y0).lineTo(x1, y0).lineTo(x1, y1).lineTo(x0, y1).lineTo(x0, y0)

    /** A small sheet: 200 px over 60 mm, 0.3 mm pixels. */
    private fun sheet(paper: Paper = Paper.ColdPress, seed: Long = 3) = PaintCanvas.paper(200, aspect = 1.5, paper = paper, sizeMm = 60.0, seed = seed)

    private fun assertBalanced(c: PaintCanvas, what: String) {
        val l = assertNotNull(c.water, what)
        assertTrue(abs(l.imbalanceMm3) < 1e-4 * l.addedMm3.coerceAtLeast(1e-3), "$what: water out by ${l.imbalanceMm3} of ${l.addedMm3} mm³")
        for (p in l.pigments) assertTrue(abs(p.imbalance) < 1e-4 * p.added.coerceAtLeast(1e-3), "$what: ${p.name} out by ${p.imbalance} of ${p.added} µg")
    }

    @Test fun aCanvasHasNoWaterUntilWaterTouchesIt() {
        if (!available()) return
        PaintCanvas(100).use { c ->
            assertNull(c.water)
            assertNull(c.paper)
            assertNull(c.waterMap(WaterMap.SurfaceWater))
        }
        sheet().use { c ->
            assertNotNull(c.paper).let { assertTrue(it.roughnessMicrons > 10.0 && it.capacityMicrons > 50.0) }
            val relief = c.waterMap(WaterMap.Relief)!!
            assertTrue(relief.maxOrNull()!! - relief.minOrNull()!! > 40f, "cold press has a tooth")
            assertTrue(c.water!!.dry)
        }
    }

    @Test fun everyDropIsAccountedForAsItFlowsSinksInAndDries() {
        if (!available()) return
        for (paper in listOf(Paper.ColdPress, Paper.Xuan)) sheet(paper).use { c ->
            c.drop(500.0, 330.0, Watercolors.sumi * 2.0, volumeMm3 = 10.0)
            c.wet(rect(100.0, 100.0, 300.0, 300.0), microns = 80.0, paint = Watercolors.ultramarine * 2.0)
            c.waitSeconds(5.0)
            assertBalanced(c, "$paper after 5 s")
            val w = c.water!!
            // the drop's 10 µl and 80 µm over 200 units square (12 mm on a 60 mm sheet): 11.5 µl
            assertEquals(10.0 + 0.08 * 12.0 * 12.0, w.addedMm3, 0.05 * w.addedMm3)
            assertTrue(w.surfaceMm3 + w.absorbedMm3 > 0.0, "still wet")
            c.dry()
            assertBalanced(c, "$paper dry")
            val d = c.water!!
            assertTrue(d.dry, "$paper: dry")
            assertEquals(d.addedMm3, d.evaporatedMm3, 1e-3 * d.addedMm3, "$paper: all the water evaporated")
            val sumi = d.pigments.first { it.name == "sumi" }
            assertEquals(sumi.added, sumi.bound + sumi.glued + sumi.deposited, 1e-3 * sumi.added, "the ink is all in the paper")
        }
    }

    @Test fun coarsePigmentGranulatesAndFineStains() {
        if (!available()) return
        sheet().use { c ->
            c.wet(rect(100.0, 100.0, 900.0, 560.0), microns = 120.0, paint = Watercolors.ultramarine * 2.0 + Watercolors.phthaloBlue * 0.7)
            c.dry()
            val p = c.water!!.pigments.associateBy { it.name }
            val u = p.getValue("ultramarine"); val ph = p.getValue("phthalo blue")
            assertTrue(u.glued > 0.9 * u.added, "ultramarine stays on the surface, loose: $u")
            assertTrue(ph.bound > 0.5 * ph.added, "phthalo goes into the fibres and stains: $ph")
        }
    }

    @Test fun aTiltedSheetShedsItsWaterDownhill() {
        if (!available()) return
        sheet(Paper.HotPress).use { c ->
            c.tilt(40.0, 90.0)
            c.wet(rect(200.0, 300.0, 800.0, 500.0), microns = 250.0, paint = Watercolors.quinacridoneRose * 1.0)
            c.waitSeconds(20.0)
            val l = c.water!!
            assertTrue(l.runoffMm3 > 0.05 * l.addedMm3, "water ran off the bottom: ${l.runoffMm3} of ${l.addedMm3}")
            assertTrue(l.pigments.single().runoff > 0.0)
            assertBalanced(c, "tilted")
            val top = c.waterMap(WaterMap.SurfaceWater)!!.let { m -> (0 until 200).sumOf { x -> m[30 * 200 + x].toDouble() } }
            assertEquals(0.0, top, 1e-6, "nothing flowed uphill")
        }
    }

    @Test fun aBrushLaysLessTheFasterItGoesAndDryBrushSkipsTheTooth() {
        if (!available()) return
        fun laid(speed: Double) = sheet().use { c ->
            val b = c.waterBrush(60.0).load(Watercolors.burntSienna * 3.0)
            c.wash(FLine().moveTo(100, 300).lineTo(900, 300), b, speed = speed).waterLaid
        }
        assertTrue(laid(20.0) > 1.3 * laid(300.0), "slow ${laid(20.0)} vs fast ${laid(300.0)}")
        fun covered(paper: Paper, fill: Double) = sheet(paper).use { c ->
            val b = c.waterBrush(120.0).load(Watercolors.burntSienna * 6.0, fill = fill)
            c.wash(FLine().moveTo(80, 330).lineTo(920, 330), b, pressure = listOf(0.25), speed = 250.0)
            val m = c.waterMap(WaterMap.Pigment)!!
            val row = (330.0 / 1000 * 200).toInt()
            (40 until 160).count { x -> (row - 2..row + 2).all { y -> m[y * 200 + x] > 0f } } / 120.0
        }
        assertTrue(covered(Paper.Rough, 0.1) < 0.8 * covered(Paper.Rough, 1.0), "a nearly dry brush leaves gaps on rough paper")
        assertTrue(covered(Paper.HotPress, 0.1) > covered(Paper.Rough, 0.1), "fewer on hot press")
    }

    @Test fun aWetCanvasSavesAndLoadsExactlyAndCarriesOnTheSame() {
        if (!available()) return
        sheet(Paper.Xuan).use { c ->
            c.drop(400.0, 300.0, Watercolors.sumi * 2.0, volumeMm3 = 8.0)
            c.waitSeconds(1.0)
            val f = kotlin.io.path.createTempFile("wet", ".state").toFile()
            try {
                c.saveState(f.path)
                PaintCanvas.load(f.path).use { back ->
                    assertEquals(c.water, back.water)
                    assertContentEquals(c.render(wet = true), back.render(wet = true))
                    c.waitSeconds(3.0); back.waitSeconds(3.0)
                    assertContentEquals(c.waterMap(WaterMap.Carried), back.waterMap(WaterMap.Carried), "it carries on exactly as it would have")
                }
            } finally { f.delete() }
            val copy = c.copy()
            copy.drop(700.0, 300.0, WaterPaint.Clear, volumeMm3 = 5.0)
            assertTrue(copy.water!!.addedMm3 > c.water!!.addedMm3, "a copy is independent")
            copy.close()
        }
    }

    @Test fun aKeptWatercolourBrushKeepsItsWaterAndPigment() {
        if (!available()) return
        sheet().use { c ->
            val box = PaintToolbox()
            box["wash"] = c.waterBrush(50.0).load(Watercolors.viridian * 2.5, fill = 0.8)
            c.wash(FLine().moveTo(100, 200).lineTo(900, 220), box.waterBrush("wash"))
            val before = box.waterBrush("wash")
            assertTrue(before.fill < 0.8 && before.pigmentUg > 0.0)
            val f = kotlin.io.path.createTempFile("tools", ".bin").toFile()
            try {
                box.save(f)
                PaintToolbox.load(f).use { back ->
                    val b = back.waterBrush("wash")
                    assertContentEquals(before.state(), b.state())
                    assertTrue(back.describe(c).contains("watercolour brush"))
                }
            } finally { f.delete() }
            box.close()
        }
    }

    @Test fun timedGestures() {
        if (!available()) return
        sheet().use { c ->
            val g = c.gesture(FLine().moveTo(0, 0).lineTo(500, 0).moveTo(0, 100).lineTo(500, 100), speed = 50.0, pressure = listOf(0.2, 1.0), gap = 0.5)
            // 500 units on a 60 mm sheet is 30 mm: 0.6 s at 50 mm/s, twice, half a second apart
            assertEquals(1.7, g.duration, 0.02)
            assertEquals(0.2, g.samples.first().pressure, 1e-9)
            assertEquals(1.0, g.samples.last().pressure, 1e-9)
            assertEquals(g.duration / 2, g.faster(2.0).duration, 1e-9)
            assertEquals(g.duration + 1.0, g.pause(1.0).duration, 1e-9)
            assertEquals(g.duration, g.reversed().duration, 1e-9)
            assertFailsWith<IllegalArgumentException> { TimedGesture(listOf(TimedGesture.Sample(0.0, 0.0, 1.0, 1.0), TimedGesture.Sample(1.0, 0.0, 0.5, 1.0))) }
            val r = c.wash(g, c.waterBrush(40.0).load(Watercolors.cadmiumYellow * 2.0))
            assertEquals(1.7, r.seconds, 0.05)
        }
    }

    @Test fun paintOverWatercolourSealsItIntoThePicture() {
        if (!available()) return
        sheet().use { c ->
            c.wet(rect(100.0, 100.0, 900.0, 560.0), microns = 100.0, paint = Watercolors.ultramarine * 2.0)
            c.dry()
            val brush = c.brush(BrushKind.HogFlat, 80.0).load(afield.math.linalg.Vec4(0.9f, 0.8f, 0.6f, 1f), hiding = 0.6)
            c.stroke(FLine().moveTo(500, 50).lineTo(500, 600), brush)
            val l = c.water!!
            val u = l.pigments.single()
            assertTrue(u.sealed > 0.0 && u.sealed < 0.5 * u.added, "the stroke sealed the watercolour under it, and only that: $u")
            assertBalanced(c, "sealed")
            val sealed = c.waterMap(WaterMap.Sealed)!!
            assertTrue(sealed[60 * 200 + 100] > 0.5f && sealed[60 * 200 + 20] == 0f, "sealed under the stroke at x = 500, not at x = 100")
        }
    }

    @Test fun pigmentsAndPaints() {
        if (!available()) return
        assertTrue(Watercolors.names.size >= 7)
        assertTrue(Watercolors.ultramarine.diameterUm > Watercolors.phthaloBlue.diameterUm)
        val custom = WaterPigment.of("indigo", afield.math.linalg.Vec4(0.15f, 0.18f, 0.3f, 1f), strength = 2.0, diameterUm = 0.2, stain = 0.1)
        assertEquals("indigo", custom.name)
        val mix = Watercolors.ultramarine * 1.0 + Watercolors.ultramarine * 2.0 + custom * 0.5
        assertEquals(3.0, mix.pigments.getValue(Watercolors.ultramarine), 1e-12)
        assertFailsWith<NoSuchElementException> { Watercolors["unobtainium"] }
        assertFailsWith<IllegalStateException> { sheet().use { it.waitSeconds(-1.0) } }
    }
}
