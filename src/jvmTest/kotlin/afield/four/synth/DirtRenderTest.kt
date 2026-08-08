package afield.four.synth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The offline Dirt renderer, with the emphasis on the path CI actually takes.
 *
 * A build machine has no 400MB sample library, so the fallback is not an edge case here — it is
 * the normal case, and it is the thing that would silently ruin every audio figure in the docs if
 * it regressed. So the no-library path is pinned hardest: it must produce *exactly* what
 * [patRenderStereo] produces, sample for sample, and must say that it did.
 *
 * The with-library assertions only run where the samples exist (see [DirtRender.dirtSampleRoot]),
 * because a test that fails on a colleague's laptop for want of a third-party download is worse
 * than no test.
 */
class DirtRenderTest {

    private val beat = pat("bd sd hh sd")

    @Test
    fun `without a library it falls back to the synth voice, exactly`() {
        val fallback = patRenderStereoDirt(beat, cycles = 2, cps = 2.0, library = null)
        val reference = patRenderStereo(beat, cycles = 2, cps = 2.0)

        assertFalse(fallback.usedSamples, "with no library nothing can have come from a sample")
        assertContentEquals(reference.left, fallback.audio.left, "the fallback must be the synth render, not an approximation of it")
        assertContentEquals(reference.right, fallback.audio.right)
        assertTrue(fallback.matched.isEmpty())
        assertTrue(fallback.unmatched.isEmpty())
    }

    @Test
    fun `the no-library description names the override rather than just failing quietly`() {
        val described = patRenderStereoDirt(beat, cycles = 1, cps = 2.0, library = null).describe()
        assertTrue(DirtRender.ENV_VAR in described, "a silent fallback should still tell you how to fix it: $described")
        assertTrue("synth" in described, described)
    }

    @Test
    fun `an absent sample root discovers nothing rather than throwing`() {
        // The probe must tolerate every candidate being missing — that is a normal machine, not a
        // broken one.
        val root = DirtRender.dirtSampleRoot()
        if (root != null) assertTrue(root.toFile().isDirectory, "a discovered root must be a real directory: $root")
    }

    @Test
    fun `rendering is deterministic`() {
        val a = patRenderStereoDirt(beat, cycles = 2, cps = 2.0, library = null)
        val b = patRenderStereoDirt(beat, cycles = 2, cps = 2.0, library = null)
        assertContentEquals(a.audio.left, b.audio.left)
    }

    @Test
    fun `with a library the standard drum tokens resolve to real samples`() {
        val library = DirtRender.libraryOrNull() ?: return // no samples on this machine; nothing to assert
        val rendered = patRenderStereoDirt(beat, cycles = 2, cps = 2.0, library = library)

        assertTrue(rendered.usedSamples)
        assertEquals(setOf("bd", "sd", "hh"), rendered.matched, "the three canonical drums are in every Dirt install")
        assertTrue(rendered.unmatched.isEmpty(), "nothing should have needed the synth voice: ${rendered.unmatched}")

        // And it is audibly *not* the synth render — the whole point of the exercise.
        val synth = patRenderStereo(beat, cycles = 2, cps = 2.0)
        assertFalse(
            rendered.audio.left.contentEquals(synth.left),
            "a sample render that equals the synth render means the samples were not used",
        )
    }

    @Test
    fun `a token the library has never heard of falls back per-event instead of going silent`() {
        val library = DirtRender.libraryOrNull() ?: return
        val mixed = patRenderStereoDirt(pat("bd zzzznotadrum"), cycles = 1, cps = 2.0, library = library)

        assertTrue(mixed.usedSamples)
        assertEquals(setOf("bd"), mixed.matched)
        assertEquals(setOf("zzzznotadrum"), mixed.unmatched)
        assertTrue(mixed.audio.left.any { it != 0f }, "the unknown token should still have been voiced")
        assertTrue(DirtRender.ENV_VAR !in mixed.describe(), "a partial match is not the no-library case")
    }
}
