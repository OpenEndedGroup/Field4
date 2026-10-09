package afield.four.synth

import afield.petri.emits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The net on `chain_petri_sparks.md`, and the three run queries its particle box makes each frame:
 * `firingsOver` for the bursts, `markingAt` for where each ring's token is, and `countAt` for how
 * full `charge` is. The page's captions describe these firings, so they are pinned here.
 */
class PetriSparksDocsTest {

    private val net = PetriSparksDocs.net()

    @Test
    fun `the rings fire at their own periods and four kicks make a clap`() {
        val run = net.run()
        val firings = run.firingsOver(PatArc(PatQ.ZERO, PatQ.of(4)))
        fun at(name: String) = firings.filter { it.transition == name }.map { it.at }

        // Grid 1/8: a ring of n places brings its token back every n eighths.
        assertEquals((0 until 8).map { PatQ.of(it, 2) }, at("kick"))
        assertEquals(PatQ.of(3, 8), at("snare")[1] - at("snare")[0])
        assertEquals(PatQ.of(5, 8), at("hat")[1] - at("hat")[0])
        assertEquals(PatQ.of(2, 8), at("hat.2")[0] - at("hat")[0])

        // The fourth kick (at 3/2) fills charge; the clap empties it one step later.
        assertEquals(listOf(PatQ.of(13, 8), PatQ.of(29, 8)), at("clap"))
        assertEquals(4, run.countAt("charge", PatQ.of(13, 8)))
        assertEquals(0, run.countAt("charge", PatQ.of(14, 8)))

        // Exactly one place of each ring is marked at any instant.
        val marking = run.markingAt(PatQ.of(7, 8))
        listOf(listOf("k0", "k1", "k2", "k3"), listOf("s0", "s1", "s2"), listOf("h0", "h1", "h2", "h3", "h4"))
            .forEach { ring -> assertEquals(1, ring.sumOf { marking.count(it) }, "ring $ring") }
    }

    @Test
    fun `emits carries exactly the sounding transitions as Dirt folder names`() {
        val values = net.emits().events(PatArc(PatQ.ZERO, PatQ.of(4))).map { it.value }.toSet()
        assertEquals(setOf("bd", "sn", "hh", "cp"), values)
    }

    @Test
    fun `the clip renders from the pinned Dirt-Samples`() {
        val scene = PetriSparksDocs.SCENES.single()
        val audio = PetriSparksDocs.render(net, 2, scene.cps)
        assertTrue(audio.frames > 0)
        assertTrue(PatSoundDocs.peak(audio.mono) > 0.02, "silent render")
    }
}
