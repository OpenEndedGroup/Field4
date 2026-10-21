package afield.audio.synth

import java.nio.file.Paths
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The pieces behind `Box.sound` and `Box.thisRMS`: the voice layout [DoughEngine.voiceFrames] reads out
 * of wasm memory, the per-group meter built on it, and [DoughLive] running with nothing draining it.
 */
class DoughLiveTest {
    private val wasm = Paths.get("./src/jvmMain/resources/dough.wasm")
    private val l = FloatArray(DoughEngine.BLOCK_SIZE)
    private val r = FloatArray(DoughEngine.BLOCK_SIZE)

    @Test
    fun voiceFramesFindsEachVoicesGroupAtTheMeasuredOffsets() {
        DoughEngine(wasm, 48_000).use { d ->
            d.evaluatePath("/dough/play/s/sine/note/60/gain/0.5/group/777")
            d.renderBlock(l, r)
            d.evaluatePath("/dough/play/s/saw/note/67/gain/0.3/group/778")
            d.renderBlock(l, r)
            val out = FloatArray(DoughEngine.MAX_VOICES * 3)
            val n = d.voiceFrames(out)
            assertEquals(2, n)
            assertEquals(setOf(777, 778), setOf(out[0].toInt(), out[3].toInt()))
        }
    }

    @Test
    fun aLoneDryVoiceIsTwiceTheModulesOutput() {
        // With one voice and no delay or reverb, the module's output is that voice's ch[] halved, which
        // is what pins VOICE_CHANNELS_OFFSET: a wrong offset reads some other float.
        DoughEngine(wasm, 48_000).use { d ->
            d.evaluatePath("/dough/play/s/sine/note/60/gain/0.5/pan/0.2")
            repeat(3) { d.renderBlock(l, r) }
            val out = FloatArray(3)
            assertEquals(1, d.voiceFrames(out))
            assertEquals(-1, out[0].toInt(), "an event with no group reads -1")
            assertTrue(abs(l[127]) > 1e-3, "premise: the voice is sounding")
            assertEquals(l[127] * 2f, out[1], 1e-6f)
            assertEquals(r[127] * 2f, out[2], 1e-6f)
        }
    }

    private fun meterLevel(path: String, blocks: Int, group: Int = 5): Pair<DoughVoiceMeter, Double> {
        val meter = DoughVoiceMeter(48_000, publishEvery = 1)
        DoughEngine(wasm, 48_000).use { d ->
            d.evaluatePath(path, mapOf("group" to group))
            repeat(blocks) {
                d.renderBlock(l, r)
                meter.afterBlock(d)
            }
        }
        return meter to sqrt(meter.published[group] ?: 0.0)
    }

    @Test
    fun aSineAtGainOneReadsTheReference() {
        val (_, rms) = meterLevel("/dough/play/s/sine/note/69/gain/1/duration/2", blocks = 300)
        println("sine gain 1 measured rms $rms")
        assertEquals(DoughLive.SINE_GAIN_ONE_RMS, rms, DoughLive.SINE_GAIN_ONE_RMS * 0.15)
    }

    @Test
    fun levelFollowsGainAndKeepsGroupsApart() {
        val (meter, quiet) = meterLevel("/dough/play/s/sine/note/69/gain/0.2/duration/2", blocks = 300, group = 9)
        assertEquals(0.2, quiet / DoughLive.SINE_GAIN_ONE_RMS, 0.05)
        assertEquals(setOf(9), meter.published.keys, "only the group that played has a level")
    }

    @Test
    fun levelFallsAfterTheRelease() {
        val (meter, rms) = meterLevel("/dough/play/s/sine/note/69/gain/1/duration/0.05/release/0.05", blocks = 400)
        assertTrue(rms < 1e-4, "silent 1 s after a 0.1 s note, got $rms")
        assertTrue(meter.published.isEmpty(), "a decayed group is forgotten")
    }

    @Test
    fun doughPathEventReadsPairsAndRefusesAnOddPath() {
        assertEquals(
            mapOf("dough" to "play", "note" to "60", "s" to "sine"),
            doughPathEvent("/dough/play/note/60 // the pitch\n/s/sine"),
        )
        assertFailsWith<IllegalArgumentException> { doughPathEvent("/dough/play/note") }
    }

    @Test
    fun withNothingDrainingItTheLiveEngineStillMeasures() {
        DoughLive(wasm, 48_000).use { live ->
            live.start()
            val deadline = System.nanoTime() + 20_000_000_000L
            while (!live.ready && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(live.ready, "booted: ${live.lastError}")
            live.play("/dough/play/s/sine/note/69/gain/0.5/duration/3", group = 3)
            var peak = 0.0
            val until = System.nanoTime() + 1_500_000_000L
            while (System.nanoTime() < until) {
                peak = maxOf(peak, live.level(3))
                Thread.sleep(20)
            }
            assertTrue(live.droppedBlocks > 0, "the ring filled and the engine kept going")
            assertEquals(0.5, peak, 0.12)
            assertEquals(0.0, live.level(4))
        }
    }
}
