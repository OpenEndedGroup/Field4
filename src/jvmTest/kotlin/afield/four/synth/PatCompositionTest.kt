package afield.four.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Exactness tests for the arrangement and pitch surface in `PatComposition.kt`.
 *
 * The voice-leading test measures the counterfactual live: it computes the total semitone
 * movement of the same progression played from its roots and played voice-led, and asserts the
 * relationship rather than a remembered number.
 */
class PatCompositionTest {

    private fun <A> PatPattern<A>.cycle(index: Long = 0L): List<PatEvent<A>> =
        events(PatArc(PatQ.of(index), PatQ.of(index + 1))).sortedWith(compareBy({ it.arc.start }, { it.arc.end }))

    private fun arcsOf(events: List<PatEvent<*>>): List<Pair<PatQ, PatQ>> = events.map { it.arc.start to it.arc.end }

    private fun quarters() = (0 until 4).map { PatQ.of(it, 4) to PatQ.of(it + 1, 4) }

    /** Arcs of a cycle, expressed relative to that cycle's start, so cycles compare to each other. */
    private fun localArcsOf(events: List<PatEvent<*>>, cycleIndex: Long): List<Pair<PatQ, PatQ>> {
        val base = PatQ.of(cycleIndex)
        return events.map { (it.arc.start - base) to (it.arc.end - base) }
    }

    // ── displacement and layering ─────────────────────────────────────────────────────────

    @Test
    fun `off shifts a copy by an exact rational amount`() {
        val events = pat("a b c d").off(PatQ.of(1, 8)) { it }.cycle()
        // Nine, not eight: the shifted copy really is displaced across the cycle boundary, so the
        // previous cycle's last event shows up clipped to 0 .. 1/8 and this cycle's last event is
        // clipped at 7/8 .. 1. That is the correct answer for a genuine time shift and the reason
        // `off` is built on `delay` rather than on a per-cycle rotation.
        assertEquals(9, events.size)
        assertEquals((0 until 8).map { PatQ.of(it, 8) }, events.map { it.arc.start }.distinct().sorted())
        assertEquals(
            listOf(PatQ.ZERO to PatQ.of(1, 8)),
            arcsOf(events.filter { it.arc.start == PatQ.ZERO && it.arc.end == PatQ.of(1, 8) }),
            "the wrapped tail of the previous cycle is missing"
        )
    }

    @Test
    fun `off can quieten its echo through a parameter`() {
        val events = pat("a b c d").off(1, 8) { it with gain(0.4) }.cycle()
        val loud = events.filter { !it.annotations.containsKey("gain") }
        val quiet = events.filter { it.num("gain") == 0.4 }
        assertEquals(4, loud.size)
        assertEquals(5, quiet.size, "four echoes plus the previous cycle's wrapped tail")
        assertEquals(quarters(), arcsOf(loud))
    }

    @Test
    fun `superimpose and layer add exactly one voice per transform`() {
        assertEquals(4 + 8, pat("a b c d").superimpose { it.fast(2) }.cycle().size)
        assertEquals(4 + 4 + 4, pat("a b c d").layer({ it }, { it.rev() }, { it.rot(1) }).cycle().size)
    }

    @Test
    fun `jux hard-pans the two copies and juxBy narrows the image`() {
        val wide = pat("a b").jux { it.rev() }.cycle()
        assertEquals(4, wide.size)
        assertEquals(setOf(0.0, 1.0), wide.map { it.num("pan") }.toSet())
        val narrow = pat("a b").juxBy(0.5) { it.rev() }.cycle()
        assertEquals(setOf(0.25, 0.75), narrow.map { it.num("pan") }.toSet())
    }

    // ── rotation and holding ──────────────────────────────────────────────────────────────

    @Test
    fun `iter advances by one quarter per cycle and comes back round`() {
        val pattern = pat("a b c d").iter(4)
        assertEquals(listOf("a", "b", "c", "d"), pattern.cycle(0).map { it.value })
        assertEquals(listOf("b", "c", "d", "a"), pattern.cycle(1).map { it.value })
        assertEquals(listOf("c", "d", "a", "b"), pattern.cycle(2).map { it.value })
        assertEquals(listOf("d", "a", "b", "c"), pattern.cycle(3).map { it.value })
        assertEquals(listOf("a", "b", "c", "d"), pattern.cycle(4).map { it.value })
        assertEquals(quarters(), localArcsOf(pattern.cycle(1), 1), "iter must not disturb the grid")
    }

    @Test
    fun `iterBack goes the other way`() {
        assertEquals(listOf("d", "a", "b", "c"), pat("a b c d").iterBack(4).cycle(1).map { it.value })
    }

    @Test
    fun `linger repeats the head of the cycle on an exact grid`() {
        val quarter = pat("a b c d").linger(1, 4).cycle()
        assertEquals(listOf("a", "a", "a", "a"), quarter.map { it.value })
        assertEquals(quarters(), arcsOf(quarter))
        val half = pat("a b c d").linger(1, 2).cycle()
        assertEquals(listOf("a", "b", "a", "b"), half.map { it.value })
        assertEquals(quarters(), arcsOf(half))
        // A fraction that does not divide the cycle clips the last repeat rather than rounding.
        val third = pat("a b c").linger(1, 3).cycle()
        assertEquals(listOf("a", "a", "a"), third.map { it.value })
        assertEquals((0 until 3).map { PatQ.of(it, 3) to PatQ.of(it + 1, 3) }, arcsOf(third))
    }

    @Test
    fun `ply repeats each event inside its own arc`() {
        val events = pat("a b").ply(3).cycle()
        assertEquals(6, events.size)
        assertEquals(listOf("a", "a", "a", "b", "b", "b"), events.map { it.value })
        assertEquals((0 until 6).map { PatQ.of(it, 6) to PatQ.of(it + 1, 6) }, arcsOf(events))
    }

    @Test
    fun `rot moves values without moving the rhythm`() {
        val events = pat("a b c d").rot(1).cycle()
        assertEquals(quarters(), arcsOf(events))
        assertEquals(listOf("b", "c", "d", "a"), events.map { it.value })
        assertEquals(listOf("d", "a", "b", "c"), pat("a b c d").rot(-1).cycle().map { it.value })
    }

    @Test
    fun `shuffle is a real permutation of the cycle's parts`() {
        val pattern = pat("a b c d").shuffle(4)
        var sawAReordering = false
        for (cycle in 0L until 32L) {
            val values = pattern.cycle(cycle).map { it.value }
            assertEquals(4, values.size, "cycle $cycle lost or gained a part")
            assertEquals(listOf("a", "b", "c", "d"), values.sorted(), "cycle $cycle is not a permutation")
            assertEquals(quarters(), localArcsOf(pattern.cycle(cycle), cycle))
            if (values != listOf("a", "b", "c", "d")) sawAReordering = true
        }
        assertTrue(sawAReordering, "shuffle never actually reordered anything")
    }

    @Test
    fun `randcat and wrandcat pick per cycle, deterministically`() {
        val pattern = randcat(pat("a"), pat("b"), pat("c"))
        val first = (0L until 24L).map { pattern.cycle(it).single().value }
        val again = (0L until 24L).map { pattern.cycle(it).single().value }
        assertEquals(first, again, "randcat must be a pure function of the cycle index")
        assertEquals(setOf("a", "b", "c"), first.toSet(), "randcat never reached one of its options")

        val weighted = wrandcat(pat("a") to 9.0, pat("b") to 1.0)
        val picks = (0L until 200L).map { weighted.cycle(it).single().value }
        val aCount = picks.count { it == "a" }
        assertTrue(aCount in 150..200, "a weighted 9:1 should dominate; got $aCount of 200")
        assertTrue(picks.contains("b"), "the 1-weight option never came up in 200 cycles")
    }

    @Test
    fun `choose and wchoose are continuous so they need segmenting`() {
        val values = choose("x", "y", "z").segment(16).cycle().map { it.value }
        assertEquals(16, values.size)
        assertEquals(setOf("x", "y", "z"), values.toSet())
        val weighted = wchoose("x" to 8.0, "y" to 1.0).segment(64).cycle().map { it.value }
        assertTrue(weighted.count { it == "x" } > weighted.count { it == "y" })
    }

    @Test
    fun `chop cuts each event in place and records which slice it is`() {
        val events = pat("a b").chop(2).cycle()
        assertEquals(listOf("a", "a", "b", "b"), events.map { it.value })
        assertEquals((0 until 4).map { PatQ.of(it, 4) to PatQ.of(it + 1, 4) }, arcsOf(events))
        assertEquals(listOf("0", "0.5", "0", "0.5"), events.map { it.annotations["begin"] })
        assertEquals(listOf("0.5", "1", "0.5", "1"), events.map { it.annotations["end"] })
    }

    @Test
    fun `striateAcross interleaves where the existing striate slices in place`() {
        // Measured, not assumed: `striate` in TidalPatterns.kt slices each event in place despite
        // its Tidal name, so it agrees with `chop` on values and only `striateAcross` interleaves.
        assertEquals(listOf("a", "a", "b", "b"), pat("a b").chop(2).cycle().map { it.value })
        assertEquals(
            listOf("a", "a", "b", "b"),
            pat("a b").striate(2).cycle().sortedWith(compareBy({ it.arc.start }, { it.arc.end })).map { it.value },
            "if this ever changes, striate has been fixed and striateAcross may be redundant"
        )
        val across = pat("a b").striateAcross(2).cycle()
        assertEquals(listOf("a", "b", "a", "b"), across.map { it.value })
        assertEquals((0 until 4).map { PatQ.of(it, 4) to PatQ.of(it + 1, 4) }, arcsOf(across))
        assertEquals(listOf("0", "0", "0.5", "0.5"), across.map { it.annotations["begin"] })
    }

    // ── mini-notation additions ───────────────────────────────────────────────────────────

    @Test
    fun `mini-notation question mark degrades with a probability`() {
        assertEquals(8, pat("bd*8?0").cycle().size, "?0 must drop nothing")
        assertEquals(0, pat("bd*8?1").cycle().size, "?1 must drop everything")
        val half = (0L until 40L).sumOf { pat("bd*8?0.5").cycle(it).size }
        assertTrue(half in 100..220, "?0.5 over 320 slots dropped to $half — not roughly half")
        // deterministic: the same text gives the same events every time.
        assertEquals(pat("bd*8?0.4").cycle(7).map { it.arc }, pat("bd*8?0.4").cycle(7).map { it.arc })
        // bare `?` defaults to a half.
        assertTrue(pat("bd*8?").cycle().size < 8)
    }

    // ── pitch ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `scale degrees wrap into octaves in both directions`() {
        assertEquals(
            listOf(60.0, 62.0, 63.0, 65.0, 67.0, 68.0, 70.0, 72.0),
            run(8).scale("minor", root = 60).cycle().map { it.value }
        )
        assertEquals(
            listOf(48.0, 50.0, 52.0, 53.0),
            run(4).scale("major", root = 48).cycle().map { it.value }
        )
        // degree -1 of major is the leading tone below the root.
        assertEquals(59, 60 + patScaleDegree(patScaleSteps("major"), -1))
        assertEquals(72, 60 + patScaleDegree(patScaleSteps("major"), 7))
        // Tidal's argument order is an alias, not a different function.
        assertEquals(
            run(8).scale("minor").cycle().map { it.value },
            scale("minor", run(8)).cycle().map { it.value }
        )
    }

    @Test
    fun `note names parse into MIDI numbers with octave 5 as the default`() {
        assertEquals(60, patNoteNumber("c"))
        assertEquals(61, patNoteNumber("cs"))
        assertEquals(59, patNoteNumber("cf"))
        assertEquals(48, patNoteNumber("c4"))
        assertEquals(69, patNoteNumber("a"))
        assertEquals(57, patNoteNumber("a4"))
        assertEquals(63, patNoteNumber("ef"))
        assertEquals(60, patNoteNumber("60"))
        assertEquals(null, patNoteNumber("bd"))
    }

    @Test
    fun `chords parse from mini-notation and flatten into simultaneous notes`() {
        val progression = chords("c'maj7 a'min7")
        val events = progression.cycle()
        assertEquals(2, events.size)
        assertEquals(listOf(60, 64, 67, 71), events[0].value)
        assertEquals(listOf(69, 72, 76, 79), events[1].value)
        // a bare root is a major triad.
        assertEquals(listOf(60, 64, 67), chords("c").cycle().single().value)
        // notesOf stacks them: same arcs, one event per note.
        val block = progression.notesOf().cycle()
        assertEquals(8, block.size)
        assertEquals(4, block.count { it.arc.start == PatQ.ZERO })
        // arpeggiate spreads them across the arc instead.
        val arped = progression.arpeggiate().cycle()
        assertEquals(8, arped.size)
        assertEquals((0 until 8).map { PatQ.of(it, 8) to PatQ.of(it + 1, 8) }, arcsOf(arped))
        assertEquals(listOf(60.0, 64.0, 67.0, 71.0, 69.0, 72.0, 76.0, 79.0), arped.map { it.value })
        // and mini-notation still applies to a chord pattern.
        assertEquals(listOf(60, 64, 67, 71), chords("<c'maj7 a'min7>").cycle(0).single().value)
        assertEquals(listOf(69, 72, 76, 79), chords("<c'maj7 a'min7>").cycle(1).single().value)
    }

    @Test
    fun `chord builds a chord pattern over a pattern of roots`() {
        val events = run(2).scale("major", root = 60).chord("min7").cycle()
        assertEquals(listOf(listOf(60, 63, 67, 70), listOf(62, 65, 69, 72)), events.map { it.value })
    }

    @Test
    fun `voice leading measurably reduces how far the voices have to move`() {
        val source = "c'maj7 a'min7 d'min7 g'dom7"
        val asWritten = chords(source).cycle().map { it.value }
        val voiceLed = chords(source).voiceLead(center = 60).cycle().map { it.value }

        val movedAsWritten = patTotalVoiceMovement(asWritten)
        val movedVoiceLed = patTotalVoiceMovement(voiceLed)
        println(
            "voiceLead counterfactual: '$source' as written moves $movedAsWritten semitones " +
                "($asWritten); voice-led it moves $movedVoiceLed ($voiceLed)"
        )

        assertTrue(movedAsWritten > 0.0, "the raw progression should move at all")
        assertTrue(
            movedVoiceLed < movedAsWritten,
            "voiceLead did not reduce movement: $movedVoiceLed vs $movedAsWritten"
        )
        assertNotEquals(asWritten, voiceLed, "voiceLead returned the input unchanged")

        // Correctness, not just smallness: every voiced chord must be the same set of pitch
        // classes as the chord it replaced, and the same number of notes.
        asWritten.zip(voiceLed).forEach { (original, led) ->
            assertEquals(original.size, led.size, "voiceLead changed the chord's size")
            assertEquals(
                original.map { Math.floorMod(it, 12) }.toSortedSet(),
                led.map { Math.floorMod(it, 12) }.toSortedSet(),
                "voiceLead changed the harmony: $original became $led"
            )
        }
        // and it stays in the register it was asked for.
        assertTrue(voiceLed.flatten().all { it in 48..72 }, "voiceLead escaped its spread: $voiceLed")
    }

    @Test
    fun `the voicing distance metric is a metric on the cases that matter`() {
        assertEquals(0.0, patVoicingDistance(listOf(60, 64, 67), listOf(60, 64, 67)))
        assertEquals(3.0, patVoicingDistance(listOf(60, 64, 67), listOf(61, 65, 68)))
        // an extra voice is charged against the nearest of the other chord, not ignored.
        assertEquals(1.0, patVoicingDistance(listOf(60, 64, 67, 68), listOf(60, 64, 67)))
        assertEquals(patVoicingDistance(listOf(60, 64), listOf(62, 65)), patVoicingDistance(listOf(62, 65), listOf(60, 64)))
    }

    // ── the second operator batch ─────────────────────────────────────────────────────────

    @Test
    fun `squeezeJoin compresses each inner pattern into its outer event's arc`() {
        val events = pat("a b").squeeze { atom ->
            if (atom == "a") pat("1 2 3") else pat("4 5")
        }.cycle()
        assertEquals(listOf("1", "2", "3", "4", "5"), events.map { it.value })
        assertEquals(
            listOf(
                PatQ.ZERO to PatQ.of(1, 6),
                PatQ.of(1, 6) to PatQ.of(1, 3),
                PatQ.of(1, 3) to PatQ.of(1, 2),
                PatQ.of(1, 2) to PatQ.of(3, 4),
                PatQ.of(3, 4) to PatQ.ONE
            ),
            arcsOf(events),
            "the inner cycles must land exactly inside their outer halves"
        )
        // The distinction from the existing `join`, which does not rescale: the same outer pattern
        // gives a different answer. `join` asks the inner pattern what it is doing *at* the outer
        // event's position, so the inner triplet keeps its own 1/3 grid and is merely *cut* at the
        // outer boundary — four events, with a split at 1/2 that squeezeJoin does not have.
        val joined = join(pat("a b").map { pat("1 2 3") }).cycle()
        assertEquals(
            listOf(
                PatQ.ZERO to PatQ.of(1, 3),
                PatQ.of(1, 3) to PatQ.of(1, 2),
                PatQ.of(1, 2) to PatQ.of(2, 3),
                PatQ.of(2, 3) to PatQ.ONE
            ),
            arcsOf(joined)
        )
        assertEquals(listOf("1", "2", "2", "3"), joined.map { it.value })
    }

    @Test
    fun `pick indexes a list and wraps`() {
        assertEquals(listOf("x", "y", "z", "x"), run(4).pick("x", "y", "z").cycle().map { it.value })
        assertEquals(listOf("y"), pat("-2").nums().pick("x", "y", "z").cycle().map { it.value })
    }

    @Test
    fun `swingBy delays every second subdivision by an exact fraction`() {
        // Four pairs = an eighth grid; a third of an eighth is 1/24.
        val events = pat("a*8").swingBy(PatQ.of(1, 3), 4).cycle()
        val starts = events.map { it.arc.start }
        assertEquals(
            listOf(
                PatQ.of(0, 8), PatQ.of(1, 8) + PatQ.of(1, 24),
                PatQ.of(2, 8), PatQ.of(3, 8) + PatQ.of(1, 24),
                PatQ.of(4, 8), PatQ.of(5, 8) + PatQ.of(1, 24),
                PatQ.of(6, 8), PatQ.of(7, 8) + PatQ.of(1, 24)
            ),
            starts,
            "the off-beats must be exactly a third of an eighth late"
        )
        // On-beats untouched, and `swing(n)` is the same thing.
        assertEquals(starts, pat("a*8").swing(4).cycle().map { it.arc.start })
        // A zero amount is the identity.
        assertEquals(
            arcsOf(pat("a*8").cycle()),
            arcsOf(pat("a*8").swingBy(PatQ.ZERO, 4).cycle())
        )
    }

    @Test
    fun `press moves every event onto the later part of its own slot`() {
        val events = pat("a b").press().cycle()
        assertEquals(
            listOf(PatQ.of(1, 4) to PatQ.of(1, 2), PatQ.of(3, 4) to PatQ.ONE),
            arcsOf(events)
        )
        assertEquals(listOf("a", "b"), events.map { it.value }, "press must not change what plays")
        // pressBy(1/4) is a quarter of the slot late.
        assertEquals(
            listOf(PatQ.of(1, 8) to PatQ.of(1, 2), PatQ.of(5, 8) to PatQ.ONE),
            arcsOf(pat("a b").pressBy(PatQ.of(1, 4)).cycle())
        )
    }

    @Test
    fun `ncat splits the cycle by weight, exactly`() {
        val events = ncat(pat("a") to 1, pat("b") to 2).cycle()
        assertEquals(listOf("a", "b"), events.map { it.value })
        assertEquals(listOf(PatQ.ZERO to PatQ.of(1, 3), PatQ.of(1, 3) to PatQ.ONE), arcsOf(events))
        // Equal weights degrade to `cat`.
        assertEquals(
            arcsOf(cat(pat("a"), pat("b"), pat("c")).cycle()),
            arcsOf(ncat(pat("a") to 1, pat("b") to 1, pat("c") to 1).cycle())
        )
    }

    @Test
    fun `degradeBy and unDegradeBy partition the events exactly`() {
        // The property `sometimesEventBy` depends on: no event is in both halves, none is lost.
        for (probability in listOf(0.2, 0.5, 0.8)) {
            for (cycle in 0L until 8L) {
                val all = pat("a*16").cycle(cycle).map { it.arc }
                val kept = pat("a*16").degradeBy(probability).cycle(cycle).map { it.arc }
                val dropped = pat("a*16").unDegradeBy(probability).cycle(cycle).map { it.arc }
                assertEquals(
                    all.size, kept.size + dropped.size,
                    "p=$probability cycle=$cycle: ${kept.size} + ${dropped.size} != ${all.size}"
                )
                assertTrue((kept.toSet() intersect dropped.toSet()).isEmpty(), "the halves overlap")
                assertEquals(all.toSet(), kept.toSet() + dropped.toSet(), "an event went missing")
            }
        }
    }

    @Test
    fun `sometimesEventBy transforms some notes where sometimesBy transforms whole cycles`() {
        // The per-event version leaves the note count alone at p=0 and p=1 and, in between, produces
        // a cycle that is neither wholly transformed nor wholly untouched.
        assertEquals(8, pat("a*8").sometimesEventBy(0.0) { it.fast(2) }.cycle().size)
        assertEquals(16, pat("a*8").sometimesEventBy(1.0) { it.fast(2) }.cycle().size)

        val mixedCounts = (0L until 16L).map { pat("a*8").sometimesEventBy(0.5) { it.fast(2) }.cycle(it).size }
        assertTrue(mixedCounts.any { it in 9..15 }, "no cycle was partially transformed: $mixedCounts")

        // The pre-existing `sometimesBy` is all-or-nothing per cycle — which is why the per-event
        // version needed a different name. Measured here rather than asserted from memory.
        val cycleCounts = (0L until 16L).map { pat("a*8").sometimesBy(0.5) { it.fast(2) }.cycle(it).size }
        assertEquals(setOf(8, 16), cycleCounts.toSet(), "sometimesBy is not per-cycle: $cycleCounts")
        assertEquals(cycleCounts, (0L until 16L).map { pat("a*8").someCyclesBy(0.5) { it.fast(2) }.cycle(it).size })
    }

    @Test
    fun `euclidInv is the exact complement of euclid`() {
        val on = euclid(3, 8).cycle().map { it.arc.start }
        val off = euclidInv(3, 8).cycle().map { it.arc.start }
        assertEquals(3, on.size)
        assertEquals(5, off.size)
        assertTrue((on.toSet() intersect off.toSet()).isEmpty(), "euclidInv overlaps euclid")
        assertEquals((0 until 8).map { PatQ.of(it, 8) }.toSet(), (on + off).toSet())

        val full = euclidFull(3, 8, pat("bd"), pat("hh")).cycle()
        assertEquals(8, full.size)
        assertEquals(3, full.count { it.value == "bd" })
        assertEquals(5, full.count { it.value == "hh" })
    }

    @Test
    fun `toScale spells an arbitrary scale and wraps into octaves`() {
        assertEquals(
            listOf(60.0, 62.0, 63.0, 67.0, 69.0, 72.0),
            run(6).toScale(listOf(0, 2, 3, 7, 9), root = 60).cycle().map { it.value }
        )
        // and it agrees with the named path for a named scale.
        assertEquals(
            run(8).scale("minor", root = 60).cycle().map { it.value },
            run(8).toScale(patScaleSteps("minor"), root = 60).cycle().map { it.value }
        )
    }

    // ── time: the operator family that used to drop material ──────────────────────────────

    @Test
    fun `slow really spans cycles instead of dropping the rest of the pattern`() {
        val source = pat("a b c d")
        // Cycle 0 is the first half, cycle 1 the second, and then it comes round.
        assertEquals(listOf("a", "b"), source.slow(2).cycle(0).map { it.value })
        assertEquals(listOf("c", "d"), source.slow(2).cycle(1).map { it.value })
        assertEquals(listOf("a", "b"), source.slow(2).cycle(2).map { it.value })
        assertEquals(
            listOf(PatQ.ZERO to PatQ.of(1, 2), PatQ.of(1, 2) to PatQ.ONE),
            arcsOf(source.slow(2).cycle(0))
        )
        // Nothing is lost: over two cycles all four atoms sound, exactly once each.
        val twoCycles = source.slow(2).events(PatArc(PatQ.ZERO, PatQ.of(2))).map { it.value }
        assertEquals(listOf("a", "b", "c", "d"), twoCycles.sorted())
        // slow(4) spreads one atom per cycle rather than repeating the first forever.
        assertEquals(
            listOf("a", "b", "c", "d"),
            (0L until 4L).map { source.slow(4).cycle(it).single().value }
        )
    }

    @Test
    fun `fast spans cycles too, so an alternation advances inside one bar`() {
        // The tell for the old cat-of-copies implementation: it replayed cycle 0 n times, so
        // `<a b>.fast(2)` was `a a`. Cycle-spanning, it is `a b`.
        assertEquals(listOf("a", "b"), pat("<a b>").fast(2).cycle(0).map { it.value })
        assertEquals(listOf("c", "a"), pat("<a b c>").fast(2).cycle(1).map { it.value })
        // For a cycle-invariant pattern it is exactly what it always was.
        assertEquals(
            arcsOf(cat(pat("a b"), pat("a b")).cycle(0)),
            arcsOf(pat("a b").fast(2).cycle(0))
        )
        assertEquals(listOf("a", "b", "a", "b"), pat("a b").fast(2).cycle(0).map { it.value })
    }

    @Test
    fun `stretch is exact and fast and slow are inverse`() {
        val source = pat("a b c")
        // Two-thirds speed: exact rational arcs, no rounding.
        val stretched = source.stretch(PatQ.of(3, 2)).cycle(0)
        assertEquals(PatQ.of(1, 2), stretched.first().arc.end)
        // Round trip: fast then slow by the same factor is the identity, on any factor.
        for (factor in listOf(PatQ.of(2), PatQ.of(3), PatQ.of(3, 2), PatQ.of(5, 4))) {
            val roundTrip = source.fastBy(factor).slowBy(factor).cycle(0)
            assertEquals(arcsOf(source.cycle(0)), arcsOf(roundTrip), "factor $factor")
            assertEquals(source.cycle(0).map { it.value }, roundTrip.map { it.value }, "factor $factor")
        }
        // timeScale is fastBy, and density is timeScale on a Double.
        assertEquals(
            arcsOf(source.fastBy(PatQ.of(3, 2)).cycle(0)),
            arcsOf(source.timeScale(PatQ.of(3, 2)).cycle(0))
        )
    }

    @Test
    fun `mini-notation slash spans cycles like slow`() {
        assertEquals(listOf("a", "b"), pat("[a b c d]/2").cycle(0).map { it.value })
        assertEquals(listOf("c", "d"), pat("[a b c d]/2").cycle(1).map { it.value })
        // and `*` is fast.
        assertEquals(listOf("a", "b", "a", "b"), pat("[a b]*2").cycle(0).map { it.value })
    }
}
