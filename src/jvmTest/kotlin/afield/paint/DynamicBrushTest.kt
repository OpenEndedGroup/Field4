package afield.paint

import afield.graphics.FLine
import afield.stdlib.Vec4
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Brushes whose hairs move: timed gestures with the handle's pose, where the hairs touched, the bend
 * they keep, watercolour from their belly, and exact snapshots kept with a studio.
 */
class DynamicBrushTest {

    private fun available(): Boolean {
        if (!PaintLibrary.isAvailable) println("SKIPPED dynamic brush tests: ${PaintLibrary.describe()}")
        return PaintLibrary.isAvailable
    }

    private val umber = Vec4(0.25f, 0.15f, 0.1f, 1f)
    private fun line(x0: Double, y0: Double, x1: Double, y1: Double) = FLine().moveTo(x0, y0).lineTo(x1, y1)

    /** 200 px over 60 mm, 0.3 mm pixels. */
    private fun canvas(seed: Long = 2) = PaintCanvas(200, aspect = 1.5, groundColor = Vec4(0.97f, 0.96f, 0.92f, 1f), sizeMm = 60.0, seed = seed)

    @Test fun theHairsTouchWhereTheGestureWentAndNowhereElse() {
        if (!available()) return
        canvas().use { c ->
            assertNull(c.contactMap())
            val b = c.dynamicBrush(BrushKind.Round, 60.0, hairs = 24).load(umber)
            assertEquals(24, b.hairCount)
            assertEquals(24, c.hairs(b).size)
            val r = c.stroke(line(150.0, 300.0, 850.0, 300.0), b, speed = 60.0, tilt = 30.0)
            // 700 units of a 60 mm sheet is 42 mm: 0.7 s at 60 mm/s, in 1 ms substeps
            assertEquals(0.7, r.seconds, 0.02)
            assertTrue(r.substeps >= 690, "${r.substeps} substeps")
            assertTrue(r.contacts > 0 && r.maxContact > 0.0 && r.maxContact <= 1.0, "$r")
            assertTrue(b.fill < 1.0, "it laid paint: fill ${b.fill}")
            val m = assertNotNull(c.contactMap())
            val (w, h) = c.pixelSize
            assertEquals(w * h, m.size)
            val row = (300.0 / 1000 * w).toInt()
            assertTrue(m[row * w + w / 2] > 0f, "touched along the stroke")
            assertEquals(0.0, (0 until w).sumOf { x -> m[(row + 40) * w + x].toDouble() }, 0.0, "not 12 mm away from it")
            // contact is seconds of hair: no pixel can have been under hairs longer than the stroke took
            assertTrue(m.max() <= r.seconds * b.hairCount)
        }
    }

    @Test fun aTiltedBrushLaysMoreOfItsHairsOnThePaper() {
        if (!available()) return
        // lightly pressed: upright only the tips touch, laid over the hairs' bellies lie along the paper
        fun lying(tilt: Double) = canvas().use { c ->
            c.stroke(c.gesture(line(150.0, 300.0, 850.0, 300.0), speed = 60.0, pressure = listOf(0.15), tilt = tilt), c.dynamicBrush(BrushKind.Filbert, 60.0).load(umber)).maxContact
        }
        val (upright, over) = lying(0.0) to lying(60.0)
        assertTrue(over > 1.5 * upright, "laid over $over of a hair on the paper vs upright $upright")
    }

    @Test fun hardScrubbingLeavesTheHairsSetInABend() {
        if (!available()) return
        canvas().use { c ->
            val b = c.dynamicBrush(BrushKind.HogFlat, 60.0, hairs = 24).load(umber, stiff = 0.6)
            assertEquals(0.0, b.retainedBend, 1e-9, "a new brush is straight")
            val scrub = c.gesture(FLine().moveTo(100, 500).lineTo(900, 500).lineTo(100, 510).lineTo(900, 520), speed = 120.0, pressure = listOf(1.6), tilt = 10.0, roll = 0.0)
            c.stroke(scrub, b)
            val set = b.retainedBend
            assertTrue(set > 0.02, "scrubbed hard it keeps a bend: $set rad")
            PaintToolbox().also { it["hog"] = b }.describe(c).let { assertTrue(it.contains("set in a bend"), it) }
            // the set only comes slowly: a light stroke after doesn't add much, and a stiff yield keeps it
            c.stroke(line(100.0, 200.0, 900.0, 200.0), b, pressure = listOf(0.3))
            assertEquals(set, b.retainedBend, 0.3 * set)
            val stiff = c.dynamicBrush(BrushKind.HogFlat, 60.0, hairs = 24).load(umber, stiff = 0.6).set(yieldDegrees = 170.0)
            c.stroke(scrub, stiff)
            assertTrue(stiff.retainedBend < 0.1 * set, "past nothing it can't yield: ${stiff.retainedBend}")
        }
    }

    @Test fun aBrushKeptInAToolboxComesBackExactly() {
        if (!available()) return
        canvas().use { c ->
            val box = PaintToolbox()
            box["rigger"] = c.dynamicBrush(BrushKind.Round, 40.0, hairs = 16).load(umber)
            c.stroke(c.gesture(line(100.0, 300.0, 900.0, 320.0), speed = 200.0, pressure = listOf(1.4), tilt = 20.0), box.dynamicBrush("rigger"))
            val before = box.dynamicBrush("rigger")
            val f = kotlin.io.path.createTempFile("tools", ".bin").toFile()
            try {
                box.save(f)
                PaintToolbox.load(f).use { back ->
                    val b = back.dynamicBrush("rigger")
                    assertContentEquals(before.state(), b.state())
                    assertEquals(BrushKind.Round, b.kind)
                    assertTrue(back.describe(c).contains("moving hairs"))
                    // and paints exactly as the original would have, from the same canvas
                    val a = c.copy(); val z = c.copy()
                    val g = c.gesture(line(100.0, 500.0, 900.0, 480.0), speed = 80.0, tilt = 30.0)
                    a.stroke(g, before); z.stroke(g, b)
                    assertContentEquals(a.render(), z.render())
                    assertContentEquals(before.state(), b.state())
                    a.close(); z.close()
                }
            } finally { f.delete() }
            box.close()
        }
    }

    @Test fun movingHairsPaintWatercolourAndTheBooksBalance() {
        if (!available()) return
        // the share of the stroke's middle left as gaps: under a quarter of the pigment the stroke lays at its 90th
        // percentile (the paper shows through it)
        fun stroke(paper: Paper, fill: Double): Pair<Double, PaintCanvas> {
            val c = PaintCanvas.paper(200, aspect = 1.5, paper = paper, sizeMm = 60.0, seed = 5)
            val b = c.dynamicBrush(BrushKind.Round, 100.0, hairs = 24).loadWater(c, Watercolors.burntSienna * 20.0, fill = fill)
            assertTrue(b.watercolour)
            val r = c.stroke(line(80.0, 330.0, 920.0, 330.0), b, pressure = listOf(0.3), speed = 200.0, tilt = 50.0)
            assertTrue(r.waterLaid > 0.0 && r.pigmentLaid > 0.0, "$r")
            val m = c.waterMap(WaterMap.Pigment)!!
            val row = (330.0 / 1000 * 200).toInt()
            val v = (40 until 160).flatMap { x -> (row - 2..row + 2).map { y -> m[y * 200 + x] } }.sorted()
            val hi = v[v.size * 9 / 10]
            return v.count { it < 0.25f * hi } / v.size.toDouble() to c
        }
        val (full, c) = stroke(Paper.Rough, 1.0)
        c.use {
            val l = it.water!!
            assertTrue(abs(l.imbalanceMm3) < 1e-4 * l.addedMm3, "water out by ${l.imbalanceMm3} of ${l.addedMm3}")
            val p = l.pigments.single()
            assertTrue(abs(p.imbalance) < 1e-4 * p.added, "pigment out by ${p.imbalance} of ${p.added}")
        }
        assertEquals(0.0, full, 0.01, "a full brush leaves no gaps")
        val (dry, d) = stroke(Paper.Rough, 0.08); d.close()
        assertTrue(dry > 0.1, "a nearly dry brush skips the tooth: $dry of the stroke left as gaps")
        val (smooth, e) = stroke(Paper.HotPress, 0.08); e.close()
        assertTrue(smooth < 0.5 * dry, "fewer on hot press: $smooth vs $dry")
    }

    @Test fun gesturesKeepTheHandlesHeadingContinuous() {
        if (!available()) return
        canvas().use { c ->
            // right to left: the heading is 180°, and must not jump to -180° partway
            val g = c.gesture(FLine().moveTo(900, 300).lineTo(500, 301).lineTo(100, 299), tilt = 30.0)
            for ((a, b) in g.samples.zipWithNext()) assertTrue(abs(b.azimuth - a.azimuth) < 10.0, "${a.azimuth} -> ${b.azimuth}")
            assertEquals(180.0, abs(g.samples[g.samples.size / 2].azimuth), 1.0)
            assertEquals(30.0, g.samples.first().tilt, 0.0)
            assertEquals(g.samples.first().azimuth + 90.0, g.samples.first().roll, 1e-9, "a flat is held across its path")
            // scrubbing back and forth the hand keeps its lean and pushes the brush back, not swinging the handle round
            val scrub = c.gesture(FLine().moveTo(100, 300).lineTo(900, 300).lineTo(100, 310).lineTo(900, 320), tilt = 10.0)
            assertTrue(scrub.samples.all { abs(it.azimuth - scrub.samples.first().azimuth) < 5.0 }, scrub.samples.map { it.azimuth }.distinct().toString())
            // but a smooth U-turn turns it
            val u = c.gesture(FLine().moveTo(100, 300).lineTo(700, 300).cubicTo(900, 300, 900, 500, 700, 500).lineTo(100, 500), tilt = 10.0)
            assertEquals(180.0, u.samples.last().azimuth - u.samples.first().azimuth, 5.0)
        }
    }

    @Test fun nonsenseIsRefused() {
        if (!available()) return
        canvas().use { c ->
            assertFailsWith<IllegalStateException> { c.dynamicBrush(hairs = 0) }
            assertFailsWith<IllegalStateException> { c.dynamicBrush(segments = 1) }
            val b = c.dynamicBrush()
            assertFailsWith<IllegalStateException> { b.set(friction = -1.0 to 0.2) }
            assertFailsWith<IllegalStateException> { b.set(cohesion = 1.5) }
            // past the usual is allowed: friction of 3, a joint that never yields
            b.set(friction = 3.0 to 2.5, yieldDegrees = 360.0)
            assertEquals(3.0, b.friction.first, 1e-6)
            val back = TimedGesture.Sample(0.0, 0.0, 1.0, 0.5)
            assertFailsWith<IllegalArgumentException> { c.stroke(TimedGesture(listOf(back, back.copy(t = 0.5))), b) }
        }
    }
}
