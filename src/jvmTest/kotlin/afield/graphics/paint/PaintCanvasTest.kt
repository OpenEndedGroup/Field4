package afield.graphics.paint

import afield.app.protocol.Rect
import afield.geometry.path.FLine
import afield.math.linalg.Vec4
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PaintCanvasTest {

    @Test
    fun closedPolylineReturnsToItsStartWithoutReadingCloseStorage() {
        val line = FLine().moveTo(2, 3).lineTo(4, 3).lineTo(4, 5).close()
        val sampled = polylines3d(line, 0.5).single()
        assertEquals(sampled.first(), sampled.last())
        assertEquals(2f, sampled.first().x)
        assertEquals(3f, sampled.first().y)
        assertTrue(sampled.size > 4)
    }



    private val white = Vec4(1f, 1f, 1f, 1f)
    private val red = Vec4(0.8f, 0.1f, 0.05f, 1f)

    private fun r(p: Int) = (p shr 16) and 0xff
    private fun g(p: Int) = (p shr 8) and 0xff

    /** A horizontal stroke across the middle of a 400 x 200 px (1000 x 500 unit) bare canvas. */
    private fun paintedStripe(view: Rect? = null, line: FLine = FLine().moveTo(100, 250).lineTo(900, 250)): IntArray =
        PaintCanvas(400, aspect = 2.0, ground = PaintGround.Bare, groundColor = white, view = view).use { c ->
            c.brush(BrushKind.HogFlat, width = 40.0).use { b ->
                b.load(red)
                c.stroke(line, b, shake = 0.0)
            }
            c.render()
        }

    @Test fun missingLibraryIsReportedNotThrownAtClassLoad() {
        assertTrue(PaintLibrary.fileName.contains("field_paint_jvm"))
        assertTrue(PaintLibrary.searchedPaths.last().endsWith(java.nio.file.Path.of("build", "field-paint", "release", PaintLibrary.fileName)))
        assertTrue(PaintLibrary.describe().isNotEmpty())
    }

    @Test fun aBareCanvasRendersItsGroundColour() {
        requirePaintLibrary()
        PaintCanvas(64, aspect = 2.0, ground = PaintGround.Bare, groundColor = Vec4(0.5f, 0.25f, 1f, 1f)).use { c ->
            assertEquals(64 to 32, c.pixelSize)
            val px = c.render()
            assertEquals(64 * 32, px.size)
            // sRGB 0.5 -> linear -> back to sRGB 8-bit: 128 (127.5 rounds up), 64, 255, opaque.
            for (p in px) assertEquals(0xff8040ff.toInt(), p, "pixel %08x".format(p))
        }
    }

    @Test fun aStrokeLaysPaintWhereTheLineIsAndNowhereElse() {
        requirePaintLibrary()
        val p = paintedStripe()
        fun at(x: Int, y: Int) = p[y * 400 + x]
        // On the line, mid-stroke (500, 250 units = 200, 100 px): red paint over white.
        val on = at(200, 100)
        assertTrue(r(on) > 150 && g(on) < 120, "on the stroke: %08x".format(on))
        // Far from it: untouched white.
        assertEquals(0xffffffff.toInt(), at(5, 5))
        assertEquals(0xffffffff.toInt(), at(395, 195))
        assertTrue(p.count { g(it) < 200 } > 1000, "the stroke covers a band, not a few pixels")
    }

    @Test fun viewMapsLineCoordinatesOntoTheWholeCanvas() {
        requirePaintLibrary()
        // The same stroke drawn at twice the scale (a 2000 x 1000 view) lands exactly where the
        // canvas-unit one does. A power-of-two scale keeps every mapped coordinate exact, so the
        // pictures are compared bit for bit rather than to a tolerance.
        val inUnits = paintedStripe()
        val inView = paintedStripe(Rect(0.0, 0.0, 2000.0, 1000.0), FLine().moveTo(200, 500).lineTo(1800, 500))
        assertContentEquals(inUnits, inView)
    }

    @Test fun theSameSeedPaintsTheSamePicture() {
        requirePaintLibrary()
        fun once() = PaintCanvas(200, ground = PaintGround.Oil, seed = 7).use { c ->
            c.brush(BrushKind.Round, 25.0).use { b ->
                b.load(red)
                c.stroke(FLine().moveTo(100, 100).cubicTo(400, 900, 600, 100, 900, 900), b)
            }
            c.render(relief = 0.6)
        }
        val a = once()
        assertContentEquals(a, once())
        assertTrue(a.distinct().size > 50, "an oil ground is textured, not flat")
    }

    @Test fun renderingLeavesTheCanvasWet() {
        requirePaintLibrary()
        PaintCanvas(200, ground = PaintGround.Bare, groundColor = white).use { c ->
            val b = c.brush(BrushKind.HogFlat, 60.0).load(red)
            c.stroke(FLine().moveTo(100, 500).lineTo(900, 500), b, shake = 0.0)
            val before = c.render()
            // A wet blue stroke across the wet red one mixes with it; had render dried the canvas,
            // the red would stay pure under it. Compare against a canvas that was dried first.
            val blue = Vec4(0.05f, 0.1f, 0.8f, 1f)
            fun crossed(canvas: PaintCanvas) = canvas.apply {
                stroke(FLine().moveTo(500, 100).lineTo(500, 900), brush(BrushKind.HogFlat, 60.0).load(blue), shake = 0.0)
            }.render()
            val wet = crossed(c)
            val driedFirst = PaintCanvas(200, ground = PaintGround.Bare, groundColor = white).use { d ->
                d.stroke(FLine().moveTo(100, 500).lineTo(900, 500), d.brush(BrushKind.HogFlat, 60.0).load(red), shake = 0.0)
                d.dry()
                crossed(d)
            }
            assertTrue(before.isNotEmpty())
            val diff = wet.indices.count { abs(r(wet[it]) - r(driedFirst[it])) > 8 }
            assertTrue(diff > 20, "wet-into-wet differs from wet-over-dry at the crossing ($diff px)")
        }
    }

    @Test fun theDefaultGroundIsBareWhite() {
        requirePaintLibrary()
        PaintCanvas(64).use { c -> assertTrue(c.render().all { it == 0xffffffff.toInt() }) }
    }

    @Test fun badArgumentsAreErrorsNotCrashes() {
        requirePaintLibrary()
        PaintCanvas(64).use { c ->
            val e = assertFailsWith<IllegalStateException> { c.brush(width = 0.0) }
            assertTrue(e.message!!.contains("brush width"), e.message)
        }
        assertFailsWith<IllegalStateException> { PaintCanvas(2) }
    }
}

class PaintMediaTest {


    private val white = Vec4(1f, 1f, 1f, 1f)
    private fun lum(p: Int) = ((p shr 16) and 0xff) + ((p shr 8) and 0xff) + (p and 0xff)
    private fun r(p: Int) = (p shr 16) and 0xff
    private fun g(p: Int) = (p shr 8) and 0xff
    private fun b(p: Int) = p and 0xff

    /**
     * A 400 x 400 px bare white canvas: 1000 x 1000 units, 2.5 units per pixel, on a 150 mm sheet
     * so a 0.3 mm pencil point is most of a pixel wide (at the default 700 mm it is a fifth of one).
     */
    private fun canvas() = PaintCanvas(400, ground = PaintGround.Bare, groundColor = white, sizeMm = 150.0)
    private fun IntArray.at(x: Int, y: Int) = this[y * 400 + x]
    private fun IntArray.meanLum(x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var s = 0.0
        for (y in y0 until y1) for (x in x0 until x1) s += lum(at(x, y))
        return s / ((x1 - x0) * (y1 - y0))
    }
    private fun square(x0: Double, y0: Double, x1: Double, y1: Double) =
        FLine().moveTo(x0, y0).lineTo(x1, y0).lineTo(x1, y1).lineTo(x0, y1).lineTo(x0, y0)

    @Test fun aPencilLineIsGreyWhereItIsDrawnAndThePencilWears() {
        requirePaintLibrary()
        canvas().use { c ->
            val p = c.pencil("4B")
            c.draw(FLine().moveTo(100, 500).lineTo(900, 500), p, pressure = 0.9, tremor = 0.0)
            val px = c.render()
            assertTrue(px.meanLum(100, 198, 300, 202) < 700, "the line is darker than white paper: ${px.meanLum(100, 198, 300, 202)}")
            assertEquals(765.0, px.meanLum(100, 20, 300, 60), "far from it the paper is untouched")
            assertTrue(p.worn in 100.0..140.0, "800 units of line at 0.15 mm per unit is ~120 mm of wear, got ${p.worn}")
            assertEquals(0.0, p.sharpen().worn)
        }
    }

    @Test fun softerPencilsDrawDarkerLines() {
        requirePaintLibrary()
        fun darkness(grade: String) = canvas().use { c ->
            c.draw(FLine().moveTo(100, 500).lineTo(900, 500), c.pencil(grade), pressure = 0.8, tremor = 0.0)
            765 - c.render().meanLum(100, 196, 300, 204)
        }
        assertTrue(darkness("6B") > darkness("2H") + 20, "6B ${darkness("6B")} vs 2H ${darkness("2H")}")
        assertFailsWith<IllegalArgumentException> { canvas().use { it.pencil("Q7") } }
    }

    @Test fun hatchingAndStipplingStayInsideTheirRegion() {
        requirePaintLibrary()
        canvas().use { c ->
            c.hatch(square(100.0, 100.0, 450.0, 450.0), c.chalk(), spacing = 8.0, length = 80.0, pressure = 0.9)
            c.stipple(square(550.0, 550.0, 900.0, 900.0), Vec4(0.1f, 0.2f, 0.6f, 1f), size = 6.0, coverage = 1.5, hiding = 0.9)
            val px = c.render()
            assertTrue(px.meanLum(60, 60, 160, 160) < 700, "hatched square is darkened")
            assertTrue(px.meanLum(240, 240, 340, 340).let { it < 740 }, "stippled square is coloured")
            val s = px.at(290, 290)
            assertTrue(b(s) >= r(s), "stipple is blue-ish: %08x".format(s))
            // the other two quadrants are untouched (a small margin for strokes that overrun)
            assertEquals(765.0, px.meanLum(250, 20, 380, 140), "top right is white")
            assertEquals(765.0, px.meanLum(20, 250, 140, 380), "bottom left is white")
        }
    }

    @Test fun anEraserLiftsLooseGraphiteButNotFixedGraphite() {
        requirePaintLibrary()
        fun after(fix: Boolean) = canvas().use { c ->
            c.hatch(square(100.0, 100.0, 900.0, 900.0), c.pencil("4B"), spacing = 5.0, pressure = 0.9)
            val before = c.render().meanLum(100, 100, 300, 300)
            if (fix) c.fixDrawing()
            c.erase(square(0.0, 0.0, 1000.0, 1000.0), strength = 1.0)
            c.render().meanLum(100, 100, 300, 300) - before
        }
        assertTrue(after(fix = false) > 20, "erasing lightens loose graphite by ${after(false)}")
        assertTrue(abs(after(fix = true)) < 1.0, "fixed graphite stays: changed by ${after(true)}")
    }

    @Test fun anOutlineComesBackAsStrokesAndIsPainted() {
        requirePaintLibrary()
        canvas().use { c ->
            val rough = FLine().moveTo(200, 200).lineTo(800, 220).lineTo(780, 800).lineTo(220, 780).lineTo(200, 200)
            val strokes = c.outline(rough, c.brush(BrushKind.Round, 8.0).load(Vec4(0f, 0f, 0f, 1f)), OutlineCharacter.Searching)
            assertTrue(strokes.pieces().size >= 3, "a searching hand lifts several times: ${strokes.pieces().size}")
            assertTrue(c.render().meanLum(70, 70, 330, 330) < 765, "the outline was painted")
            val unpainted = canvas().use { d -> d.outline(rough, null).also { assertEquals(765.0, d.render().meanLum(0, 0, 400, 400)) } }
            assertTrue(unpainted.pieces().isNotEmpty())
        }
    }

    @Test fun workCoversTheRegionWithStrokes() {
        requirePaintLibrary()
        canvas().use { c ->
            c.work(square(250.0, 250.0, 750.0, 750.0), Vec4(0.8f, 0.2f, 0.1f, 1f), Handling.Body, clip = true)
            val px = c.render()
            val mid = px.at(200, 200)
            assertTrue(r(mid) > 150 && g(mid) < 150, "red paint in the middle: %08x".format(mid))
            assertEquals(765.0, px.meanLum(0, 0, 60, 60), "the corner is untouched")
            assertTrue(c.stats.handMinutes > 0.0, "the strokes cost hand time")
        }
    }

    @Test fun touchesGlazesAndPriming() {
        requirePaintLibrary()
        canvas().use { c ->
            c.touch(c.brush(BrushKind.Round, 30.0).load(Vec4(0f, 0f, 0f, 1f)), 500.0, 500.0, pressure = 0.9)
            assertTrue(c.render().meanLum(196, 196, 204, 204) < 400, "a dab at the centre")
            c.dry().glaze(Vec4(0.9f, 0.6f, 0.1f, 1f), square(0.0, 0.0, 400.0, 400.0), coats = 3.0)
            val gl = c.render().at(60, 60)
            assertTrue(b(gl) < 230 && r(gl) > b(gl), "a warm glaze over the top left: %08x".format(gl))
            assertEquals(0xffffffff.toInt(), c.render().at(390, 390), "but not elsewhere")
            c.prime(Vec4(0.3f, 0.3f, 0.35f, 1f), microns = 80.0)
            assertTrue(c.render().meanLum(300, 300, 400, 400) < 500, "the ground covers everything")
        }
    }

    @Test fun tubesMixLikePaint() {
        requirePaintLibrary()
        assertTrue(Tubes.names.size > 10, "a real catalogue: ${Tubes.names}")
        val white = Tubes.names.first { it.contains("white") }
        val blue = Tubes.names.first { it.contains("blue") || it.contains("ultramarine") || it.contains("smalt") }
        val tint = Tubes.mix(mapOf(white to 3.0, blue to 1.0))
        assertTrue(tint.x + tint.y + tint.z > Tubes[blue].color.let { it.x + it.y + it.z }, "white lightens the blue")
        val green = Tubes.mixSpectral(Vec4(0.1f, 0.2f, 0.9f, 1f) to 1.0, Vec4(1f, 0.9f, 0.05f, 1f) to 1.0)
        assertTrue(green.y > green.x && green.y > green.z, "blue and yellow pigments make green: $green")
        assertFailsWith<IllegalStateException> { Tubes.mix(mapOf("unobtainium" to 1.0)) }
        canvas().use { c ->
            val b = c.brush(BrushKind.HogFlat, 50.0).load(mapOf(white to 1.0, blue to 1.0), medium = 0.2)
            c.stroke(FLine().moveTo(100, 500).lineTo(900, 500), b, shake = 0.0)
            val p = c.render().at(200, 200)
            assertTrue(b(p) > r(p), "a blue tint was painted: %08x".format(p))
        }
    }

    @Test fun copiesStateAndTheWetView() {
        requirePaintLibrary()
        canvas().use { c ->
            c.stroke(FLine().moveTo(100, 500).lineTo(900, 500), c.brush(BrushKind.HogFlat, 60.0).load(Vec4(0.1f, 0.3f, 0.8f, 1f)), shake = 0.0)
            assertTrue(c.stats.open > 0.0, "fresh paint is open")
            val copy = c.copy()
            copy.stroke(FLine().moveTo(500, 100).lineTo(500, 900), copy.brush(BrushKind.HogFlat, 60.0).load(Vec4(0f, 0f, 0f, 1f)))
            assertTrue(copy.render().meanLum(196, 40, 204, 60) < 400, "the copy took the new stroke")
            assertEquals(765.0, c.render().meanLum(196, 40, 204, 60), "the original did not")
            copy.close()

            val wet = c.render(wet = true)
            assertTrue(wet.at(200, 200) != 0xffffffff.toInt(), "wet paint shows in the wet view")

            val f = kotlin.io.path.createTempFile("paint", ".state").toFile()
            try {
                c.saveState(f.path)
                PaintCanvas.load(f.path).use { back ->
                    assertEquals(c.pixelSize, back.pixelSize)
                    assertContentEquals(c.render(), back.render())
                    assertTrue(back.stats.open > 0.0, "the paint is still wet after a round trip")
                }
            } finally { f.delete() }
            c.dry()
            assertEquals(1.0, c.stats.dry, 1e-9)
        }
    }

    @Test fun craquelureMarksAnOilCanvas() {
        requirePaintLibrary()
        // a 120 mm panel, so cracks a few dozen µm wide land on whole pixels
        PaintCanvas(400, ground = PaintGround.Oil, seed = 2, sizeMm = 120.0).use { c ->
            val before = c.render()
            c.crack(CrackKind.Aged)
            val after = c.render()
            val changed = before.indices.count { abs(lum(before[it]) - lum(after[it])) > 15 }
            assertTrue(changed > 300, "cracks darken a network of pixels: $changed")
        }
    }
}
