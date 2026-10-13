package afield.paint

import afield.Keyframe
import afield.KeyframeEvaluator
import afield.PenSample
import afield.Rect
import afield.stdlib.Vec4
import afield.takeToLanes
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A take recorded on a gesture pad, baked into keyframe lanes, edited, and replayed as a brush
 * movement on a paint canvas (`afield/paint/gestureReplay.kt`). The PNGs go to
 * `build/gesture-paint-replay/`; the ones in `dev_docs/paint/assets/gesture_paint_replay/` are copies.
 */
class GesturePaintReplayTest {

    private fun available(): Boolean {
        if (!PaintLibrary.isAvailable) println("SKIPPED gesture replay tests: ${PaintLibrary.describe()}")
        return PaintLibrary.isAvailable
    }

    private val umber = Vec4(0.25f, 0.15f, 0.1f, 1f)
    private val out = File("build/gesture-paint-replay").apply { mkdirs() }

    // A hand's 1.6 s left-to-right wave across the pad at an uneven 120 Hz with a little tremor, as a
    // pen records it: pressing harder through the middle, leaning right and toward the user. Seeded.
    private fun recordedWave(): List<PenSample> {
        val rnd = Random(11)
        val out = ArrayList<PenSample>()
        var t = 0.0
        while (t < 1.6) {
            val u = t / 1.6
            out += PenSample(
                t, -0.85 + 1.7 * u + rnd.nextDouble(-0.003, 0.003),
                0.35 * sin(u * 2 * PI) + rnd.nextDouble(-0.003, 0.003),
                pressure = 0.05 + 0.95 * sin(u * PI), tiltX = 35.0, tiltY = 20.0, twist = 0.0,
            )
            t += 1.0 / 120 * rnd.nextDouble(0.6, 1.4)
        }
        return out
    }

    private fun lane(keys: List<Keyframe>) = KeyframeEvaluator(Json.encodeToString(keys))

    private fun canvas() = PaintCanvas(400, aspect = 1.0, groundColor = Vec4(0.97f, 0.96f, 0.92f, 1f), sizeMm = 200.0, seed = 4)

    // How much darker than the ground row band [y0, y1) (pixels) is, summed.
    private fun ink(px: IntArray, w: Int, y0: Int, y1: Int): Double {
        var sum = 0.0
        for (y in y0 until y1) for (x in 0 until w) {
            val p = px[y * w + x]
            sum += (247 - ((p shr 16 and 255) + (p shr 8 and 255) + (p and 255)) / 3.0).coerceAtLeast(0.0)
        }
        return sum
    }

    @Test fun aPensTiltBecomesTheHandlesLean() {
        assertEquals(0.0 to 0.0, penPose(0.0, 0.0))
        penPose(30.0, 0.0).let { (tilt, az) -> assertEquals(30.0, tilt, 1e-9); assertEquals(0.0, az, 1e-9) }
        penPose(0.0, 30.0).let { (tilt, az) -> assertEquals(30.0, tilt, 1e-9); assertEquals(90.0, az, 1e-9) }
        // leaning both ways at once leans further than either, toward the diagonal
        penPose(30.0, 30.0).let { (tilt, az) -> assertTrue(tilt > 30.0 && tilt < 45.0, "$tilt"); assertEquals(45.0, az, 1e-9) }
    }

    @Test fun theTakesTimingPressureAndTiltReachTheGesture() {
        if (!available()) return
        canvas().use { c ->
            val take = recordedWave()
            val g = c.gesture(take, into = Rect(100.0, 100.0, 800.0, 800.0))
            assertEquals(take.size, g.samples.size)
            assertEquals(take.last().t, g.duration, 1e-12, "the recording's own timing")
            assertEquals(100.0 + 0.075 * 800, g.samples.first().x, 2.0)
            // pad y up, canvas y down: the wave's crest (pad +0.35) is above the middle on the canvas
            assertTrue(g.samples.minOf { it.y } < 500.0 - 0.3 * 400)
            assertEquals(1.2 * take.maxOf { it.pressure }, g.samples.maxOf { it.pressure }, 1e-9)
            val (tilt, az) = penPose(35.0, 20.0)
            assertTrue(g.samples.all { abs(it.tilt - tilt) < 1e-9 && abs(it.azimuth - az) < 1e-9 }, "the pen's lean, not the path's heading")
            // without the pen, a constant press and a lean toward the heading
            val mouse = c.gesture(take.map { PenSample(it.t, it.x, it.y) })
            assertTrue(mouse.samples.all { it.pressure == 0.6 && it.tilt == 30.0 })
            assertTrue(abs(mouse.samples.first().azimuth) < 60.0, "heading right: ${mouse.samples.first().azimuth}")
        }
    }

    /** The whole chain: record, bake, edit a lane, replay, and the paint is where the edit put it. */
    @Test
    fun anEditedTakePaintsWhereTheEditMovedIt() {
        if (!available()) return
        val take = recordedWave()
        val flat = take.flatMap { listOf(it.t, it.x, it.y) }.toDoubleArray()
        val lanes = takeToLanes(flat, 0.01)
        println("baked ${take.size} samples to ${lanes.x.size} keys")
        assertTrue(lanes.x.size * 4 < take.size, "baking reduced the take")
        // the edit: the whole y lane dragged down by 0.6 pad units, and the pressure keyed flat
        val editedY = lanes.y.map { it.copy(value = it.value - 0.6) }
        val pressureKeys = listOf(Keyframe(0.0, 0.7, "linear"), Keyframe(take.last().t, 0.7, "linear"))
        val into = Rect(100.0, 100.0, 800.0, 800.0)

        fun paint(y: List<Keyframe>, name: String) = canvas().use { c ->
            val g = c.gesture(lane(lanes.x), lane(y), into = into, pressureLane = lane(pressureKeys))
            assertTrue(g.samples.all { abs(it.pressure - 0.84) < 1e-6 }, "the pressure lane presses")
            val r = c.stroke(g, c.dynamicBrush(BrushKind.Round, 40.0, hairs = 32).load(umber, stiff = 0.5), seed = 4)
            println("$name: $r")
            File(out, "$name.png").writeBytes(c.png(relief = 0.6))
            c.render() to c.pixelSize.first
        }
        val (asRecorded, w) = paint(lanes.y, "as_recorded")
        val (edited, _) = paint(editedY, "edited")
        // the pad's middle row lands at canvas y 500 = pixel 200; 0.6 pad units down is 240 canvas
        // units, 96 pixels. Compare a band around each place the stroke's middle crosses.
        val recordedBand = ink(asRecorded, w, 170, 230)
        val movedBand = ink(edited, w, 266, 326)
        println("ink at the recorded place: recorded $recordedBand, edited ${ink(edited, w, 170, 230)}; at the moved place: recorded ${ink(asRecorded, w, 266, 326)}, edited $movedBand")
        assertTrue(recordedBand > 1000.0, "the recorded take painted: $recordedBand")
        assertTrue(movedBand > 1000.0, "the edited take painted where it was moved: $movedBand")
        assertTrue(ink(edited, w, 170, 230) < 0.25 * recordedBand, "and not where it was")
        assertTrue(ink(asRecorded, w, 266, 326) < 0.25 * movedBand, "the recording never went there")
    }

    /**
     * A pen take's pressure, kept through the replay, presses the hairs down where the pen pressed:
     * more hair on the paper (seconds of contact) through the middle of the stroke, where the pen
     * pressed hardest, than a take at one light pressure gives, and less at the start, where it
     * barely touched.
     */
    @Test
    fun aPensPressurePressesTheHairsWhereThePenPressed() {
        if (!available()) return
        val take = recordedWave()
        fun contact(samples: List<PenSample>, name: String): List<Double> = canvas().use { c ->
            c.stroke(c.gesture(samples, into = Rect(100.0, 100.0, 800.0, 800.0)), c.dynamicBrush(BrushKind.Round, 40.0, hairs = 32).load(umber, stiff = 0.5), seed = 4)
            File(out, "$name.png").writeBytes(c.png(relief = 0.6))
            val m = c.contactMap()!!; val (w, h) = c.pixelSize
            // hair-seconds in three column bands: the start, the middle and the end of the stroke
            listOf(60 to 100, 180 to 220, 300 to 340).map { (x0, x1) -> (0 until h).sumOf { y -> (x0 until x1).sumOf { x -> m[y * w + x].toDouble() } } }
        }
        val pressed = contact(take, "pen_pressure")
        val light = contact(take.map { it.copy(pressure = 0.35) }, "pen_light")
        println("hair-seconds start/middle/end: pen pressure $pressed, one light pressure $light")
        assertTrue(pressed[1] > 1.2 * light[1], "pressed harder in the middle: $pressed vs $light")
        assertTrue(pressed[0] < light[0], "barely touching at the start: $pressed vs $light")
    }
}
