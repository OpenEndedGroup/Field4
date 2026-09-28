@file:afield.CompletionCategory("Lighting")

package afield.dmx

import afield.rectsl.RectSLImager
import afield.stdlib.Img
import afield.stdlib.resize
import afield.stdlib.vec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A RectSL scene on a light rig, measured: the worked example behind `public/docs/chain_scene_to_light.md`
 * and `SceneOnARigTest`, kept in one place so the page, the test and the demo document run the same
 * code.
 *
 * The route is four parts of Field that were written without each other: a RectSL scene is drawn on
 * the CPU into an [Img] ([RectSLImager]), [Patch.mapImage] colours a 16 × 8 grid of RGB pixels plus
 * four RGBWA washes from it, a [DmxOutput] sends the universes as sACN to 127.0.0.1, and a
 * [DmxInput] receives them; [Fixture.decode] turns what arrived back into colours in the rig's
 * layout, to be compared with the pixels the rig was given.
 *
 * Three things are measured, each by a function here: [roundTrip] (what the wire does to each
 * fixture's colour), [aliasing] (what sampling a 256-pixel frame at 16 points does to a thin moving
 * line), and [timing] (what a frame costs, and how often the receiver sees one while the scene
 * animates on the sender's 44 Hz clock).
 */
object SceneOnARig {

    /** The scene: an orange square that crosses the wall and turns, a thin white line, a blue band that fades in and out. */
    val SCENE: String = """
        #rectsl
        ->wall 16 8 abstract
        ->background 0x0c1024
        ->duration 4
        ->fps 30

        "sun" rect
          4 4 ->wh
          0xff7a1a solid ->fill
          '( 0=-5  2=5  4=-5 ) ->x
          '( 0=0  4=3.14159 ) ->rot
        ;

        "band" rect
          16 1.5 ->wh
          0 -3.25 ->xy
          0x2a9df4 solid ->fill
          '( 0=0.15  2=1  4=0.15 ) ->alpha
        ;

        "sweep" rect
          0.25 8 ->wh
          0xffffff solid ->fill
          '( 0=-8  4=8 ) linear ->x
        ;
    """.trimIndent()

    /** The line alone on black: a quarter of a fixture's width, crossing the wall in four seconds. */
    val LINE: String = """
        #rectsl
        ->wall 16 8 abstract
        ->background 0x000000
        ->duration 4
        ->fps 30

        "sweep" rect
          0.25 8 ->wh
          0xffffff solid ->fill
          '( 0=-8  4=8 ) linear ->x
        ;
    """.trimIndent()

    /** Grid columns and rows: one fixture per wall unit. */
    const val COLS = 16
    const val ROWS = 8

    /** Frame size the scene is drawn at before it meets the rig: 16 pixels per fixture each way. */
    const val FRAME_W = 256
    const val FRAME_H = 128

    /**
     * The rig: a [COLS] × [ROWS] grid of RGB pixels 10 units apart in universe 1 (384 of its 512
     * slots), and four RGBWA washes along the bottom in universe 2. Positions are in rig units,
     * y down, as every `lines(...)` drawing is.
     */
    class Rig(startUniverse: Int = 1) {
        val patch = Patch(startUniverse)
        val grid: List<Fixture> = patch.grid(COLS, ROWS, spacing = 10.0)
        val washes: List<Fixture>

        init {
            patch.moveCursor(startUniverse + 1)
            washes = patch.strip(4, vec(15.0, 85.0), vec(135.0, 85.0), profile = FixtureProfile.RGBWA, name = "wash")
        }

        /** The grid's own bounds, `x, y, w, h`: what the washes are mapped over too, so all fixtures read one image. */
        val gridArea: DoubleArray = patch.bounds(grid).toDoubleArray()

        /**
         * Colour every fixture from [frame]. With [area] (the default) the frame is first shrunk to
         * one pixel per grid fixture by area averaging, so each fixture shows the mean of its cell;
         * without, [Patch.mapImage] samples the full frame bilinearly at each fixture's position.
         */
        fun show(frame: Img, area: Boolean = true) {
            val img = if (area) frame.resize(COLS, ROWS) else frame
            patch.mapImage(img, only = grid)
            patch.mapImage(img, only = washes, area = gridArea)
        }
    }

    // ---- round trip ------------------------------------------------------------------------------

    /**
     * What the network did to the rig, over [times]. [levelErrors] counts slot bytes that differ
     * between what was rendered and what [DmxInput] merged (it should be zero: sACN is lossless);
     * [worstGrid] and [worstWash] are the largest per-channel difference, in 0..1, between the colour
     * a fixture was asked for and the colour [Fixture.decode] recovers from the received slots.
     */
    class RoundTrip(
        val frames: Int,
        val fixtures: Int,
        val slotsCompared: Int,
        val levelErrors: Int,
        val worstGrid: Double,
        val worstWash: Double,
        val packetsSent: Long,
        val packetsReceived: Long,
        val medianLatencyMicros: Double,
        /** The last frame's sent colours and received colours, row-major, for drawing. */
        val lastSent: Img,
        val lastReceived: Img,
    ) {
        /** [worstGrid] in 8-bit levels. */
        val worstGridLevels: Double get() = worstGrid * 255
        /** [worstWash] in 8-bit levels. */
        val worstWashLevels: Double get() = worstWash * 255

        override fun toString() =
            "RoundTrip($frames frames x $fixtures fixtures, $levelErrors of $slotsCompared slots differ, " +
                "worst grid ${"%.3f".format(worstGridLevels)} levels, worst wash ${"%.3f".format(worstWashLevels)} levels)"
    }

    /**
     * Send the scene at each of [times] as sACN to 127.0.0.1:[port] and receive it back on the same
     * port with Field's [DmxInput]; compare. Blocks until each frame has arrived (or 2 s). With
     * `port = 0` (the default) the receiver binds a free port and the sender is pointed at it.
     */
    fun roundTrip(port: Int = 0, times: List<Double> = (0..8).map { it * 0.5 }, scene: RectSLImager = RectSLImager(SCENE)): RoundTrip {
        val rig = Rig()
        val input = DmxInput(DmxProtocol.SACN, port = port, bindAddress = "127.0.0.1")
        val out = DmxOutput(DmxProtocol.SACN, host = "127.0.0.1", port = input.localPort, sourceName = "scene on a rig", autoStart = false, discovery = false)
        var levelErrors = 0; var slots = 0
        var worstGrid = 0.0; var worstWash = 0.0
        val latencies = ArrayList<Double>()
        var lastSent: Img? = null; var lastReceived: Img? = null
        try {
            for (t in times) {
                rig.show(scene.frame(t, FRAME_W, FRAME_H))
                val expected = HashMap<Int, ByteArray>()
                rig.patch.renderInto { u -> expected.getOrPut(u) { ByteArray(512) } }
                rig.patch.render(out)
                val before = rig.patch.universes.associateWith { input.version(it) }
                val sentAt = System.nanoTime()
                out.flush()
                val deadline = sentAt + 2_000_000_000L
                while (rig.patch.universes.any { input.version(it) == before[it] } && System.nanoTime() < deadline) Thread.onSpinWait()
                latencies += (System.nanoTime() - sentAt) / 1000.0
                val received = rig.patch.universes.associateWith { input.universe(it) }
                for ((u, want) in expected) {
                    val got = received.getValue(u)
                    for (i in 0 until 512) { slots++; if (want[i] != got[i]) levelErrors++ }
                }
                for (f in rig.patch.fixtures) {
                    val back = f.decode(received.getValue(f.universe))
                    val d = f.dimmer
                    val e = max(abs(back.x - f.color.x * d), max(abs(back.y - f.color.y * d), abs(back.z - f.color.z * d)))
                    if (f in rig.washes) worstWash = max(worstWash, e) else worstGrid = max(worstGrid, e)
                }
                lastSent = colours(rig) { it.color }
                lastReceived = colours(rig) { it.decode(received.getValue(it.universe)) }
            }
            return RoundTrip(
                times.size, rig.patch.fixtures.size, slots, levelErrors, worstGrid, worstWash,
                out.packetsSent, input.packetsReceived, latencies.sorted()[latencies.size / 2],
                lastSent!!, lastReceived!!,
            )
        } finally {
            out.close(); input.close()
        }
    }

    /** The grid's colours as a [COLS] × [ROWS] image, row-major as patched. */
    private fun colours(rig: Rig, of: (Fixture) -> afield.stdlib.Vec3): Img {
        val data = FloatArray(COLS * ROWS * 3)
        rig.grid.forEachIndexed { i, f ->
            val c = of(f)
            data[i * 3] = c.x; data[i * 3 + 1] = c.y; data[i * 3 + 2] = c.z
        }
        return Img(COLS, ROWS, 3, data)
    }

    /**
     * The worst difference, in 8-bit levels, between the colour a fixture of each preset profile was
     * asked for and what [Fixture.decode] reads back from the slots it wrote — over [count] random
     * colours (and random master dimmers for [FixtureProfile.DRGB]), seeded, with no network in
     * between. It is the floor under any round trip: what quantising to the profile's slots costs.
     */
    fun decodeErrors(count: Int = 2000, seed: Long = 7): Map<FixtureProfile, Double> {
        val rnd = java.util.Random(seed)
        val profiles = listOf(FixtureProfile.RGB, FixtureProfile.GRB, FixtureProfile.DRGB, FixtureProfile.RGBW, FixtureProfile.RGBWA, FixtureProfile.RGB16)
        return profiles.associateWith { profile ->
            var worst = 0.0
            val data = ByteArray(512)
            repeat(count) {
                val f = Fixture("f", profile, 1, 1, vec(0.0, 0.0))
                f.rgb(rnd.nextDouble(), rnd.nextDouble(), rnd.nextDouble())
                f.dimmer = if (profile == FixtureProfile.DRGB) rnd.nextDouble() else 1.0
                f.writeInto(data)
                val back = f.decode(data)
                val d = f.dimmer
                worst = max(worst, max(abs(back.x - f.color.x * d), max(abs(back.y - f.color.y * d), abs(back.z - f.color.z * d))))
            }
            worst * 255
        }
    }

    // ---- aliasing --------------------------------------------------------------------------------

    /**
     * One sampling route through the [LINE] sweep: per frame, the total light on the grid (the sum
     * over fixtures of their mean channel, so a fully lit fixture is 1) and the brightest fixture.
     */
    class SamplingTrace(val name: String, val total: DoubleArray, val peak: DoubleArray) {
        val min: Double get() = total.min()
        val max: Double get() = total.max()
        val mean: Double get() = total.average()
        /** Frames in which no grid fixture is above 1% — the line has gone from the rig. */
        val darkFrames: Int get() = peak.count { it < 0.01 }
        /** `(max − min) / mean` of [total] over the frames the line is wholly on the wall. */
        val flicker: Double get() = if (mean == 0.0) 0.0 else (max - min) / mean
    }

    /**
     * The aliasing measurement. The [LINE] scene, drawn in linear light at [FRAME_W] × [FRAME_H],
     * is put on the grid two ways over [frames] frames of the middle 3 seconds (the line wholly on
     * the wall): bilinear point samples of the full frame ([Patch.mapImage] as it is), and the frame
     * area-averaged to one pixel per fixture first. The line covers a quarter of one column of
     * fixtures, so the light it should put on the rig is `0.25 × ROWS` = 2 fixtures' worth,
     * wherever it is.
     */
    fun aliasing(frames: Int = 121, line: RectSLImager = RectSLImager(LINE)): Pair<SamplingTrace, SamplingTrace> {
        val rig = Rig()
        val point = SamplingTrace("point", DoubleArray(frames), DoubleArray(frames))
        val area = SamplingTrace("area", DoubleArray(frames), DoubleArray(frames))
        for (k in 0 until frames) {
            val t = 0.5 + 3.0 * k / (frames - 1.0)
            val img = line.frame(t, FRAME_W, FRAME_H, linear = true)
            for ((trace, useArea) in listOf(point to false, area to true)) {
                rig.show(img, area = useArea)
                var sum = 0.0; var peak = 0.0
                for (f in rig.grid) {
                    val m = (f.color.x + f.color.y + f.color.z) / 3.0
                    sum += m; peak = max(peak, m)
                }
                trace.total[k] = sum; trace.peak[k] = peak
            }
        }
        return point to area
    }

    /** The light the line should put on the grid, in fully-lit fixtures. */
    const val LINE_TRUTH: Double = 0.25 * ROWS

    // ---- timing ----------------------------------------------------------------------------------

    /**
     * Frame cost and delivery while the scene animates for [seconds] on a [DmxOutput]'s own 44 Hz
     * clock ([Patch.animate]), sent to and received from 127.0.0.1:[port]. [frameMillis] is the
     * sender-side cost of one look (draw, shrink, map); [arrivalMillis] the gaps between successive
     * universe-1 frames at the receiver. `port = 0` (the default) binds a free port, as [roundTrip] does.
     */
    class Timing(val seconds: Double, val ticks: Int, val frameMillis: DoubleArray, val arrivalMillis: DoubleArray, val lastError: Throwable?) {
        private fun pct(a: DoubleArray, p: Double) = if (a.isEmpty()) Double.NaN else a.sorted()[((a.size - 1) * p).roundToInt()]
        val frameMedian get() = pct(frameMillis, 0.5)
        val frameP95 get() = pct(frameMillis, 0.95)
        val arrivalMedian get() = pct(arrivalMillis, 0.5)
        val arrivalP95 get() = pct(arrivalMillis, 0.95)
        /** Received frames per second of universe 1. */
        val receivedHz get() = arrivalMillis.size / seconds
    }

    fun timing(port: Int = 0, seconds: Double = 3.0, scene: RectSLImager = RectSLImager(SCENE)): Timing {
        val rig = Rig()
        val input = DmxInput(DmxProtocol.SACN, port = port, bindAddress = "127.0.0.1")
        val arrivals = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        input.onFrame { u, _ -> if (u == 1) arrivals += System.nanoTime() }
        val costs = java.util.concurrent.ConcurrentLinkedQueue<Double>()
        val out = DmxOutput(DmxProtocol.SACN, host = "127.0.0.1", port = input.localPort, sourceName = "scene on a rig", discovery = false)
        try {
            rig.patch.animate(out) { t ->
                val t0 = System.nanoTime()
                rig.show(scene.frame(t % scene.duration, FRAME_W, FRAME_H))
                costs += (System.nanoTime() - t0) / 1e6
            }
            Thread.sleep((seconds * 1000).toLong())
        } finally {
            out.close()
        }
        Thread.sleep(50)
        input.close()
        val a = arrivals.toList()
        val gaps = a.zipWithNext { x, y -> (y - x) / 1e6 }.toDoubleArray()
        return Timing(seconds, costs.size, costs.toDoubleArray(), gaps, out.lastError)
    }
}
