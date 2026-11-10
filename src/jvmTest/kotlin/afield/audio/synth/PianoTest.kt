package afield.audio.synth

import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PianoTest {
    private val out = File("build/piano-test").also { it.mkdirs() }

    /** Reference values printed by libc++'s std::mt19937_64{42} and its distributions. */
    @Test
    fun rngReproducesLibcxx() {
        val r = PianoRng(42)
        assertEquals("13930160852258120406", r.nextLong().toULong().toString())
        assertEquals(0.27806278770939485, r.uniform(-1.0, 1.0))
        val g = r.Normal()
        assertEquals(0.39797739618378886, g.next(), 1e-15)
        assertEquals(-0.57409480672026159, g.next(), 1e-15)
        assertEquals(1.1185550524574792, g.next(), 1e-15)
    }

    @Test
    fun bridgeForceMatchesCoopianoPreparation() {
        for (midi in intArrayOf(69, 33, 84)) {
            val key = PianoKey(midi.toDouble(), PianoVoicing())
            val d = PianoStrike.prepare(key, PianoString.forKey(key), 0.5, 1L, 44_100.0)
            println("PIANO prep $midi contact ${"%.3f".format(d.contactMs)} ms peak ${"%.2f".format(d.peakForce)} N vh ${"%.3f".format(d.hammerVelocity)} phasors ${d.count}")
            val (f, _) = pianoBridgeForce(midi, 0.5, 8.0)
            java.io.DataOutputStream(File(out, "bridge_$midi.f64").outputStream().buffered()).use { o ->
                val bb = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (x in f) { bb.clear(); bb.putDouble(x); o.write(bb.array()) }
            }
        }
    }

    @Test
    fun a4RendersAndWritesWav() {
        val t0 = System.nanoTime()
        val r = pianoRender(8.0, Piano(sampleRate = 44_100, room = Param(0f))) { noteOn(69, 0.5, 0); noteOff(69, 8L * 44_100) }
        val ms = (System.nanoTime() - t0) / 1e6
        println("PIANO A4 render ${"%.0f".format(ms)} ms for 8 s, peak ${r.peak}")
        r.writeWav(File(out, "A4_field.wav"))
        pianoRender(8.0, Piano(sampleRate = 44_100, room = Param(0f), coupling = Param(0f))) { noteOn(69, 0.5, 0) }.writeWav(File(out, "A4_nores.wav"))
        assertTrue(r.peak > 0.01f && r.peak < 1f, "peak ${r.peak}")
    }
}
