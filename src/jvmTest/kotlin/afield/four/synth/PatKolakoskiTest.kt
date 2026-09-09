package afield.four.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exactness tests for `PatKolakoski.kt`.
 *
 * The central property is self-description — the run lengths of the word are the word — and it is
 * checked by re-encoding the generated sequence rather than by comparing against a remembered
 * table. The step masks *are* compared against literal strings, because those are what the file
 * overview and the docs print, and a rhythm nobody can read off the page is not documented.
 */
class PatKolakoskiTest {

    private fun <A> PatPattern<A>.cycle(index: Long = 0L): List<PatEvent<A>> =
        events(PatArc(PatQ.of(index), PatQ.of(index + 1))).sortedWith(compareBy({ it.arc.start }, { it.arc.end }))

    /** A mask as the `x`/`.` string the docs use. */
    private fun render(mask: List<Boolean>): String = mask.joinToString("") { if (it) "x" else "." }

    /** Run-length encode a word: the lengths of its maximal constant runs, in order. */
    private fun runLengths(word: List<Int>): List<Int> {
        val out = mutableListOf<Int>()
        var index = 0
        while (index < word.size) {
            var run = 1
            while (index + run < word.size && word[index + run] == word[index]) run++
            out.add(run)
            index += run
        }
        return out
    }

    // ── the word ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the word over 1 and 2 is the classic Kolakoski sequence`() {
        assertEquals(
            listOf(1, 2, 2, 1, 1, 2, 1, 2, 2, 1, 2, 2, 1, 1, 2, 1, 1, 2, 2, 1),
            kolakoskiWord(20)
        )
    }

    @Test
    fun `the word is its own run-length encoding`() {
        // Re-encoding drops the tail of a word cut mid-run, so compare only the prefix the encoding
        // can vouch for: n terms of runs describe at most n terms of word.
        for (alphabet in listOf(1 to 2, 2 to 3, 1 to 3, 3 to 1)) {
            val word = kolakoskiWord(4000, alphabet.first, alphabet.second)
            val encoded = runLengths(word).dropLast(1)
            assertTrue(encoded.size > 1000, "too little to compare for $alphabet: ${encoded.size}")
            assertEquals(
                word.take(encoded.size),
                encoded,
                "the word over $alphabet does not describe its own runs"
            )
        }
    }

    @Test
    fun `the word only uses its two letters, and starts with the first`() {
        val word = kolakoskiWord(500, 2, 3)
        assertEquals(setOf(2, 3), word.toSet())
        assertEquals(2, word.first())
        // 2, 2, 3, 3, 2, 2, 2, 3, 3, 3, 2, 2 — no run shorter than two, so no lone step anywhere.
        assertEquals(listOf(2, 2, 3, 3, 2, 2, 2, 3, 3, 3, 2, 2), word.take(12))
    }

    @Test
    fun `generating more terms never changes the ones already generated`() {
        val long = kolakoskiWord(300)
        for (length in 0..300) assertEquals(long.take(length), kolakoskiWord(length))
    }

    // ── the two readings ──────────────────────────────────────────────────────────────────

    @Test
    fun `the runs reading is the figure the docs print`() {
        assertEquals("x..xx.x..x..xx.x", render(kolakoskiMask(16)))
    }

    @Test
    fun `the runs reading has the word for its own run lengths`() {
        val mask = kolakoskiMask(64)
        val runs = mutableListOf<Int>()
        for ((index, on) in mask.withIndex()) {
            if (index == 0 || mask[index - 1] != on) runs.add(1) else runs[runs.lastIndex]++
        }
        // The mask starts on an on-run by construction, so its run lengths are the word itself —
        // bar the last, which the step count cut short.
        assertEquals(kolakoskiWord(runs.size - 1), runs.dropLast(1))
    }

    @Test
    fun `the gaps reading puts an onset every word-term apart`() {
        assertEquals("xx.x.xxx.xx.x.xx", render(kolakoskiMask(16, KolakoskiReading.GAPS)))

        val mask = kolakoskiMask(64, KolakoskiReading.GAPS)
        val onsets = mask.withIndex().filter { it.value }.map { it.index }
        assertEquals(0, onsets.first(), "the gaps reading must start on the downbeat")
        val gaps = onsets.zipWithNext { a, b -> b - a }
        assertEquals(kolakoskiWord(gaps.size), gaps)
        // Every gap is a 1 or a 2, so the figure is denser than the run-length reading of the same
        // word — measured, not assumed. The sixteen-step counts are the ones the docs quote.
        assertTrue(
            mask.count { it } > kolakoskiMask(64).count { it },
            "gaps ${mask.count { it }} is not denser than runs ${kolakoskiMask(64).count { it }}"
        )
        assertEquals(8, kolakoskiMask(16).count { it })
        assertEquals(11, kolakoskiMask(16, KolakoskiReading.GAPS).count { it })
        // And no gap leaves more than one rest, because no term of the word over {1, 2} exceeds 2.
        assertTrue(gaps.all { it <= 2 }, "a gap left more than one rest: $gaps")
    }

    @Test
    fun `both readings are prefix-stable, and euclid is not`() {
        for (reading in KolakoskiReading.entries) {
            val long = kolakoskiMask(64, reading)
            for (steps in 1..64) {
                assertEquals(
                    long.take(steps),
                    kolakoskiMask(steps, reading),
                    "$reading is not prefix-stable at $steps steps"
                )
            }
        }
        // The contrast the file overview and `pattern_language.md` draw, with the two strings they
        // print: a Euclidean rhythm answers a spacing question that the step count is part of, so
        // its shorter figure is not a prefix of its longer one.
        assertEquals("x..x.x..", render(bjorklund(3, 8)))
        assertEquals("x.....x.", render(bjorklund(3, 16)).take(8))
    }

    @Test
    fun `rotation moves every onset later and keeps the count`() {
        val plain = kolakoskiMask(16)
        for (turn in 0..17) {
            val turned = kolakoskiMask(16, rotation = turn)
            assertEquals(plain.count { it }, turned.count { it }, "rotation $turn changed the density")
            assertEquals(
                plain.indices.map { plain[(it - turn % 16 + 32) % 16] },
                turned,
                "rotation $turn is not a rotation to the right"
            )
        }
    }

    // ── the patterns ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the pattern puts one whole step on each onset of the mask`() {
        val mask = kolakoskiMask(16)
        val events = kolakoski(16).cycle()
        assertEquals(mask.count { it }, events.size)
        assertEquals(
            mask.indices.filter { mask[it] }.map { PatQ.of(it, 16) },
            events.map { it.arc.start }
        )
        assertTrue(events.all { it.value }, "an off-step leaked into the pattern as a false event")
        // Each event lasts exactly one step, and knows it — the whole is what a player schedules.
        assertTrue(events.all { it.whole?.dur == PatQ.of(1, 16) }, "a step is not one sixteenth long")
    }

    @Test
    fun `kolakoskiInv is the exact complement of kolakoski`() {
        val on = kolakoski(16).cycle().map { it.arc.start }
        val off = kolakoskiInv(16).cycle().map { it.arc.start }
        assertTrue((on.toSet() intersect off.toSet()).isEmpty(), "kolakoskiInv overlaps kolakoski")
        assertEquals((0 until 16).map { PatQ.of(it, 16) }.toSet(), (on + off).toSet())

        val full = kolakoskiFull(16, pat("bd"), pat("hh")).cycle()
        assertEquals(16, full.size)
        assertEquals(on.size, full.count { it.value == "bd" })
        assertEquals(off.size, full.count { it.value == "hh" })
    }

    @Test
    fun `kolakoskiFill puts a copy of its argument on each onset, as euclidFill does`() {
        val onsets = kolakoskiMask(16).count { it }

        // A one-event argument lands one hit per onset, which is the case the mini-notation suffix
        // takes: `bd(k16)`.
        val single = kolakoskiFill(16, on = pat("bd")).cycle()
        assertEquals(onsets, single.size)
        assertEquals(
            kolakoskiMask(16).indices.filter { kolakoskiMask(16)[it] }.map { PatQ.of(it, 16) },
            single.map { it.arc.start }
        )

        // A longer argument does *not* get clipped to the step. Field's `mask` squeezes a whole
        // cycle of `on` into each true step (see `within`), so two steps of argument give two
        // events per onset. That is a property of `mask`, not of this family: `euclidFill` does the
        // same, and the two are asserted together so they cannot drift apart.
        assertEquals(2 * onsets, kolakoskiFill(16, on = pat("bd cp")).cycle().size)
        assertEquals(2 * 3, euclidFill(3, 8, on = pat("bd cp")).cycle().size)
    }

    // ── mini-notation ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the k and g suffixes reach the two readings`() {
        for ((source, reading) in listOf("bd(k16)" to KolakoskiReading.RUNS, "bd(g16)" to KolakoskiReading.GAPS)) {
            val notated = pat(source).cycle()
            val combinator = kolakoskiFill(16, reading, on = pat("bd")).cycle()
            assertEquals(combinator.map { it.arc.start to it.arc.end }, notated.map { it.arc.start to it.arc.end }, source)
            assertTrue(notated.all { it.value == "bd" }, source)
        }
        // The marker is a letter, so accept it in either case rather than failing with a message
        // about a missing pulse count.
        assertEquals(
            pat("bd(k16)").cycle().map { it.arc.start },
            pat("bd(K16)").cycle().map { it.arc.start }
        )
    }

    @Test
    fun `the suffix offset rotates the figure the same way a Euclidean offset does`() {
        // `bd(3,8,1)` rotates earlier by a step; so does `bd(k16,1)`.
        assertEquals(
            kolakoskiFill(16, rotation = -1, on = pat("bd")).cycle().map { it.arc.start },
            pat("bd(k16,1)").cycle().map { it.arc.start }
        )
        assertEquals(
            euclidFill(3, 8, rotation = -1, on = pat("bd")).cycle().map { it.arc.start },
            pat("bd(3,8,1)").cycle().map { it.arc.start }
        )
    }

    @Test
    fun `the suffix applies to a subgroup, as the Euclidean one does`() {
        val events = pat("[bd cp](k16)").cycle()
        // Two per onset, for the squeeze reason in `kolakoskiFill puts a copy …` above — and the
        // Euclidean suffix does the same, which is the point of the comparison.
        assertEquals(2 * kolakoskiMask(16).count { it }, events.size)
        assertEquals(2 * 3, pat("[bd cp](3,8)").cycle().size)
        assertEquals(setOf("bd", "cp"), events.map { it.value }.toSet())
    }

    @Test
    fun `a Euclidean suffix still parses unchanged`() {
        assertEquals(3, pat("bd(3,8)").cycle().size)
        assertEquals(5, pat("bd(5,16,2)").cycle().size)
    }
}
