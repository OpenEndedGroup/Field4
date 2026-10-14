package afield.graphics.paint

import afield.geometry.path.FLine
import afield.math.linalg.Vec4
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Upstream's later engines and tools through Field: engine choice and compatibility, spatter, the
 * knife, the rag, configurable brushes, the raking light, and exact snapshots of tools.
 */
class PaintToolsTest {


    private val white = Vec4(1f, 1f, 1f, 1f)
    private val red = Vec4(1f, 0f, 0f, 1f)
    private fun r(p: Int) = (p shr 16) and 0xff
    private fun b(p: Int) = p and 0xff
    private fun line(vararg p: Double) = FLine().apply { moveTo(p[0], p[1]); for (i in 2 until p.size step 2) lineTo(p[i], p[i + 1]) }
    private fun sha(px: IntArray): String = MessageDigest.getInstance("SHA-256").digest(
        java.nio.ByteBuffer.allocate(px.size * 4).also { b -> px.forEach { b.putInt(it) } }.array()).joinToString("") { "%02x".format(it) }

    /** One stroke of a red hog flat across a canvas 160 px wide, aspect 2. */
    private fun stripe(ground: PaintGround, seed: Long, engine: Int?) =
        PaintCanvas(160, aspect = 2.0, ground = ground, groundColor = white, seed = seed, engine = engine).use { c ->
            c.brush(BrushKind.HogFlat, 40.0).use { b -> b.load(red); c.stroke(line(100.0, 250.0, 900.0, 250.0), b, shake = 0.0) }
            c.render()
        }

    // The hashes are of the same calls painted by the wrapper over claude-paint 8e78df0 (engine 2), the
    // version Field shipped before engine 5: pinning engine 2 has to reproduce those pictures exactly.
    @Test fun engineTwoPaintsWhatFieldPaintedBefore() {
        requirePaintLibrary()
        assertEquals("5bf9227c061f9a5d6f8d223c0d95e84f3bbde911d9765ca983937601d8b798bd", sha(stripe(PaintGround.Bare, 1, 2)))
        assertEquals("0f190a28b1d861b518be8b7a620c4297bd1d899fbbbe227971361aba6492a289", sha(stripe(PaintGround.Oil, 7, 2)))
        PaintCanvas(64, engine = 2).use { assertEquals(2, it.paintEngine) }
        PaintCanvas(64).use { assertEquals(PaintCanvas.engine, it.paintEngine) }
        assertTrue(PaintCanvas.engine >= 5)
        assertFailsWith<IllegalStateException> { PaintCanvas(64, engine = 99) }
    }

    // A state saved by that same wrapper (format PAINTCK8, engine 2) still opens, as engine 2, to the picture it held.
    @Test fun aCanvasSavedBeforeEngineFiveStillOpens() {
        requirePaintLibrary()
        val f = kotlin.io.path.createTempFile("engine2", ".state").toFile()
        try {
            f.writeBytes(javaClass.getResourceAsStream("/afield/paint/engine2_oil_64.state")!!.readBytes())
            PaintCanvas.load(f.path).use { c ->
                assertEquals(2, c.paintEngine)
                assertEquals("e0bf47f0006ccee4eb97927411218e70a800e73a110fb50068805614b2682e8e", sha(c.render()))
            }
        } finally { f.delete() }
    }

    @Test fun spatterThrowsTheLoosePaintOfEachHair() {
        requirePaintLibrary()
        PaintCanvas(300, aspect = 1.5, groundColor = white).use { c ->
            val blue = Vec4(0f, 0f, 1f, 1f)
            // double-loaded: one side of the brush dipped in each colour (a dip of the whole brush would mix them)
            val bh = c.brush(BrushKind.Round, 30.0)
            bh.load(blue, stiff = 0.4, turps = 0.4, part = LoadPart(side = -1.0, share = 0.5))
            bh.load(red, stiff = 0.4, turps = 0.4, wipe = 0.0, part = LoadPart(side = 1.0, share = 0.5))
            val before = c.carried(bh).paintMm3
            val n = c.spatter(bh, 100.0, 300.0, toward = 500.0 to 0.0, spread = 0.3, force = 0.8)
            assertTrue(n > 50, "a hard flick of fluid paint throws many drops: $n")
            assertTrue(c.carried(bh).paintMm3 < before, "the drops left the brush")
            val px = c.render()
            assertTrue(px.any { r(it) > b(it) + 60 } && px.any { b(it) > r(it) + 60 }, "both colours of the double-loaded brush landed")
            assertTrue((0 until 200).all { y -> (0 until 20).all { x -> px[y * 300 + x] == 0xffffffff.toInt() } }, "nothing lands behind the flick")
            // stiff tube paint barely leaves the brush
            val stiff = c.brush(BrushKind.Round, 30.0).load(red, stiff = 1.0)
            assertTrue(c.spatter(stiff, 100.0, 300.0, toward = 500.0 to 0.0, force = 0.8) < n)
        }
    }

    @Test fun aScrapingKnifeKeepsWhatItTakes() {
        requirePaintLibrary()
        PaintCanvas(300, aspect = 1.5, ground = PaintGround.Oil, seed = 5).use { c ->
            val k = c.knife(70.0).load(Vec4(0.8f, 0.7f, 0.3f, 1f), amount = 0.9)
            c.knife(line(100.0, 300.0, 600.0, 320.0), k, pressure = 0.5..0.6)
            val laid = c.stats.wetPaintMm3
            assertTrue(laid > 1.0, "the knife laid a slab: $laid mm³")
            k.wipe()
            assertNull(c.carried(k).color)
            c.scrape(line(300.0, 150.0, 310.0, 500.0), k)
            val taken = laid - c.stats.wetPaintMm3
            assertTrue(taken > 0.5, "the scrape took paint off: $taken mm³")
            assertEquals(taken, c.carried(k).paintMm3, taken * 0.02, "and the blade holds what the canvas lost")
        }
    }

    @Test fun aRagLiftsExactlyWhatItReports() {
        requirePaintLibrary()
        PaintCanvas(300, aspect = 1.5, ground = PaintGround.Oil, seed = 6).use { c ->
            c.work(line(100.0, 100.0, 900.0, 100.0, 900.0, 500.0, 100.0, 500.0, 100.0, 100.0), Vec4(0.3f, 0.2f, 0.1f, 1f),
                handling = Handling.Broad, hiding = 0.6, stiff = 0.3)
            val r = c.rag(60.0)
            val w0 = c.stats.wetPaintMm3
            val lifted = c.wipe(line(150.0, 300.0, 850.0, 310.0), r)
            assertTrue(lifted > 1.0, "a wipe lifts open paint: $lifted mm³")
            assertEquals(w0 - c.stats.wetPaintMm3, lifted, lifted * 1e-3)
            assertTrue(c.ragState(r).load > 0.0)
            c.refold(r).dampen(r, 0.8)
            assertTrue(c.ragState(r).damp > 0.5)
            assertTrue(c.blot(r, 500.0, 200.0, 0.8) > 0.0)
        }
    }

    @Test fun aBrushIsMadeAsConfigured() {
        requirePaintLibrary()
        PaintCanvas(200).use { c ->
            val preset = c.brush(BrushKind.HogFlat, 40.0).config
            val made = c.brush(BrushKind.HogFlat, 40.0, config = BrushConfig(bristles = 12, stiffness = 0.1, lay = 0.5)).config
            assertEquals(12, made.bristles)
            assertEquals(0.1, made.stiffness!!, 1e-6)
            assertEquals(0.5, made.lay!!, 1e-6)
            assertEquals(preset.length, made.length, "what was not asked for is the preset's")
            assertFailsWith<IllegalStateException> { c.brush(BrushKind.Round, 20.0, config = BrushConfig(bristles = 0)) }
        }
    }

    @Test fun toolsRestoredFromTheirStateCarryOnExactly() {
        requirePaintLibrary()
        PaintCanvas(240, aspect = 1.5, ground = PaintGround.Oil, seed = 3).use { c ->
            val brush = c.brush(BrushKind.Filbert, 30.0).load(Vec4(0.2f, 0.4f, 0.7f, 1f))
            c.stroke(line(100.0, 200.0, 900.0, 260.0), brush)
            val knife = c.knife(50.0).load(Vec4(0.9f, 0.9f, 0.8f, 1f), amount = 0.5)
            c.knife(line(200.0, 400.0, 500.0, 420.0), knife)
            val rag = c.rag(40.0)
            c.wipe(line(150.0, 230.0, 800.0, 240.0), rag)

            val brush2 = PaintBrush.restore(brush.state(), brush.kind)
            val knife2 = PaintKnife.restore(knife.state())
            val rag2 = PaintRag.restore(rag.state())
            assertContentEquals(brush.state(), brush2.state())
            assertEquals(BrushKind.Filbert, PaintBrush.restore(brush.state()).kind)

            fun go(canvas: PaintCanvas, b: PaintBrush, k: PaintKnife, g: PaintRag): IntArray {
                canvas.stroke(line(100.0, 500.0, 900.0, 200.0), b)
                canvas.knife(line(600.0, 100.0, 900.0, 140.0), k)
                canvas.wipe(line(100.0, 450.0, 900.0, 450.0), g)
                return canvas.render()
            }
            val a = c.copy().use { go(it, brush, knife, rag) }
            val b = c.copy().use { go(it, brush2, knife2, rag2) }
            assertContentEquals(a, b, "the restored tools paint exactly what the originals do")
            assertFailsWith<IllegalStateException> { PaintKnife.restore(rag.state()) }
        }
    }

    @Test fun aToolboxKeepsEveryToolExactly() {
        requirePaintLibrary()
        PaintCanvas(200, ground = PaintGround.Oil, seed = 4).use { c ->
            val box = PaintToolbox()
            box["sky"] = c.brush(BrushKind.HogFlat, 40.0, config = BrushConfig(bristles = 30)).load(Vec4(0.5f, 0.6f, 0.8f, 1f))
            box["stip"] = c.brush(BrushKind.Stippler, 20.0)
            box["knife"] = c.knife(50.0).load(red, amount = 0.3)
            box["rag"] = c.rag(40.0)
            box["pencil"] = c.pencil("4B")
            c.stroke(line(100.0, 300.0, 900.0, 300.0), box.brush("sky"))
            c.draw(line(100.0, 600.0, 900.0, 650.0), box.pencil("pencil"))
            val f = kotlin.io.path.createTempFile("tools", ".bin").toFile()
            try {
                box.save(f)
                PaintToolbox.load(f).use { back ->
                    assertEquals(box.names, back.names)
                    assertContentEquals(box.brush("sky").state(), back.brush("sky").state())
                    assertEquals(BrushKind.Stippler, back.brush("stip").kind)
                    assertContentEquals(box.knife("knife").state(), back.knife("knife").state())
                    assertContentEquals(box.rag("rag").state(), back.rag("rag").state())
                    assertEquals(box.pencil("pencil").worn, back.pencil("pencil").worn)
                    assertEquals(box.describe(c), back.describe(c))
                    val e = assertFailsWith<IllegalArgumentException> { back.knife("sky") }
                    assertTrue(e.message!!.contains("brush"), e.message)
                }
            } finally { f.delete() }
            assertNotNull(c.carried(box.brush("sky")).color, "the kept brush is dirty")
            box.close()
            assertTrue(box.names.isEmpty())
        }
    }

    @Test fun rawClothAndAbsorbentGrounds() {
        requirePaintLibrary()
        PaintCanvas(200, ground = PaintGround.RawCotton).use { c ->
            val px = c.render()
            assertTrue(px.distinct().size > 10, "the bare cloth shows its weave")
        }
        assertFailsWith<IllegalStateException> { PaintCanvas(200, ground = PaintGround.RawLinen, engine = 2) }
        // paint laid on an absorbent chalk ground loses oil into it: it dries matte
        fun glossy(absorbent: Double) = PaintCanvas(200, aspect = 1.0, ground = PaintGround.Linen, seed = 2).use { c ->
            c.prime(Vec4(0.95f, 0.94f, 0.9f, 1f), microns = 80.0, absorbent = absorbent)
            c.stroke(line(100.0, 500.0, 900.0, 500.0), c.brush(BrushKind.HogFlat, 120.0).load(Vec4(0.1f, 0.1f, 0.3f, 1f), stiff = 0.4))
            c.dry()
            c.render(light = PaintLight(90.0, 60.0))
        }
        val oil = glossy(0.0)
        val chalk = glossy(1.0)
        val differ = oil.indices.count { abs(r(oil[it]) - r(chalk[it])) > 3 }
        assertTrue(differ > 500, "an absorbent ground changes how the stroke dries ($differ px differ)")
    }

    @Test fun aRakingLightShadowsTheRelief() {
        requirePaintLibrary()
        // a 120 mm sheet: a slab's 0.3 mm edge has to be steep over a pixel to shade
        PaintCanvas(240, aspect = 1.5, groundColor = white, sizeMm = 120.0).use { c ->
            c.knife(line(200.0, 300.0, 800.0, 320.0), c.knife(80.0).load(white, amount = 1.0))
            val flat = c.render(wet = true)
            val raking = c.render(wet = true, light = PaintLight.Raking)
            val gallery = c.render(wet = true, light = PaintLight.Gallery)
            // white on white: the slab shows only where its edges slope, and only to a low lamp
            assertTrue(flat.indices.count { flat[it] != raking[it] } > 100, "the raking light finds the slab's edges")
            val shaded = { px: IntArray -> px.count { r(it) < 240 } }
            assertTrue(shaded(raking) > 100 && shaded(gallery) < shaded(raking) / 4,
                "a low lamp shades the relief that a high one barely does: ${shaded(raking)} vs ${shaded(gallery)}")
        }
    }
}
