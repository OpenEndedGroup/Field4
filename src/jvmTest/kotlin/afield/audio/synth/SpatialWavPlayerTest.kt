package afield.audio.synth

import java.io.File
import kotlin.math.*
import kotlin.test.*

/** Actual Steam Audio rendering, without opening an audio device. */
class SpatialWavPlayerTest {
    @Test
    fun transportAndHeadRelativeDirectionReachRealBinauralSamples() {
        org.junit.Assume.assumeTrue(
            "requires Steam Audio native library",
            afield.audio.spatial.steamaudio.SteamAudioNatives.isAvailable,
        )
        // Broadband probe makes ear-level assertions meaningful; the demo WAV is bass-heavy.
        val wav = File.createTempFile("spatial-wav-probe", ".wav")
        val random = java.util.Random(1234)
        afield.audio.engine.WavFloatArrayWriter.writeMono16(
            wav,
            FloatArray(44_100) { (random.nextFloat() - .5f) * .4f },
            44_100,
        )
        wav.deleteOnExit()
        var valid = true
        var yaw = 0f
        val head = HeadPoseSource { p, q ->
            p.set(0f, 0f, 0f)
            q.identity().rotateY(yaw)
            valid
        }
        SpatialWavPlayer(wav, head).use { player ->
            val ctx = SynthContext(48_000f, 256)
            val l = FloatArray(256)
            val r = FloatArray(256)
            fun block() {
                player.render(l, r, 256, ctx)
                assertTrue((l + r).all { it.isFinite() })
            }
            block()
            assertEquals(0.0, player.positionSeconds)
            player.place(-1, 0, 0)
            player.gain = .5f
            player.distanceRolloff = false
            player.play()
            var le = 0.0
            var re = 0.0
            repeat(80) {
                block()
                le += l.sumOf { (it * it).toDouble() }
                re += r.sumOf { (it * it).toDouble() }
            }
            assertTrue(
                le > re * 1.1,
                "left world source must reach the left ear more strongly: $le / $re",
            )
            player.pause()
            val paused = player.positionSeconds
            repeat(20) { block() }
            assertEquals(paused, player.positionSeconds)
            assertTrue((l + r).maxOf { abs(it) } < .001f, "pause must fade to silence")
            player.seek(.2)
            block()
            assertEquals(.2, player.positionSeconds, 1e-6)
            // A head turn swaps which ear faces the same world-space source.
            yaw = PI.toFloat()
            player.seek(0.0)
            player.play()
            le = 0.0
            re = 0.0
            repeat(80) {
                block()
                le += l.sumOf { (it * it).toDouble() }
                re += r.sumOf { (it * it).toDouble() }
            }
            assertTrue(re > le * 1.1, "head rotation must swap the dominant ear: $le / $re")
            valid = false
            repeat(5) { block() } // dropouts must retain orientation, not repeatedly invert it
            player.looping = false
            player.seek(player.durationSeconds - .001)
            block()
            assertFalse(player.playing)
        }
    }
    @Test
    fun separateOutputsStayPacedWhileAnotherOutputCloses() {
        val device = System.getenv("FIELD_TEST_OPENAL_OUTPUT")
        org.junit.Assume.assumeTrue("opt-in OpenAL device test", device != null)
        val a = java.util.concurrent.atomic.AtomicLong()
        val b = java.util.concurrent.atomic.AtomicLong()
        fun signal(counter: java.util.concurrent.atomic.AtomicLong) = object : StereoRenderable {
            override fun render(l: FloatArray, r: FloatArray, frames: Int, context: SynthContext) {
                l.fill(0f); r.fill(0f); counter.incrementAndGet()
            }
        }
        FieldAudioEngine.openBinauralOutput(device!!).use { first ->
            FieldAudioEngine.openBinauralOutput(device).use { second ->
                first.register(AudioPatchSpec.from(signal(a)))
                second.register(AudioPatchSpec.from(signal(b)))
                Thread.sleep(100)
                val beforeA = a.get(); val beforeB = b.get()
                Thread.sleep(250)
                assertTrue(a.get()-beforeA in 15L..100L, "first stream lost device pacing")
                assertTrue(b.get()-beforeB in 15L..100L, "second stream lost device pacing")
                first.close()
                val before = b.get()
                Thread.sleep(250)
                assertTrue(b.get()-before in 15L..100L, "closing another context broke the remaining stream")
            }
        }
    }
}
