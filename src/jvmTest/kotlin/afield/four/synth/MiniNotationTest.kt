package afield.four.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mini-notation tests: the three standalone step markers added on top of `MiniPat`, and — the part
 * that had no coverage at all before — a **source-span regression corpus**.
 *
 * `Marker.patHighlight` maps a scheduled event back into the pattern text through
 * `PatEvent.sourceSpan`, which is how the live editor lights up the atom that is currently sounding.
 * Nothing asserted that the spans were right, so any parser change could silently point the
 * highlighting at the wrong characters. The span corpus below closes that: for a corpus covering
 * every modifier, every event's span must extract exactly the text of the atom whose value the event
 * carries.
 */
class MiniNotationTest {

    private fun <A> PatPattern<A>.cycle(index: Long = 0L): List<PatEvent<A>> =
        events(PatArc(PatQ.of(index), PatQ.of(index + 1))).sortedWith(compareBy({ it.arc.start }, { it.arc.end }))

    private fun arcsOf(events: List<PatEvent<*>>): List<Pair<PatQ, PatQ>> = events.map { it.arc.start to it.arc.end }

    // ── `.` groups ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a dot groups what precedes it against what follows`() {
        // Two groups, so two halves — not five equal fifths.
        val events = pat("bd sd . hh hh hh").cycle()
        assertEquals(listOf("bd", "sd", "hh", "hh", "hh"), events.map { it.value })
        assertEquals(
            listOf(
                PatQ.ZERO to PatQ.of(1, 4),
                PatQ.of(1, 4) to PatQ.of(1, 2),
                PatQ.of(1, 2) to PatQ.of(2, 3),
                PatQ.of(2, 3) to PatQ.of(5, 6),
                PatQ.of(5, 6) to PatQ.ONE
            ),
            arcsOf(events)
        )
        // and it is exactly the bracketed spelling.
        assertEquals(arcsOf(pat("[bd sd] [hh hh hh]").cycle()), arcsOf(events))
    }

    @Test
    fun `three dot groups split the cycle in thirds`() {
        val events = pat("a . b b . c c c").cycle()
        assertEquals(listOf("a", "b", "b", "c", "c", "c"), events.map { it.value })
        assertEquals(PatQ.of(1, 3), events[0].arc.end)
        assertEquals(PatQ.of(1, 3) to PatQ.of(1, 2), events[1].arc.start to events[1].arc.end)
        assertEquals(PatQ.of(2, 3), events[3].arc.start)
    }

    @Test
    fun `a dot inside a token is still part of the token`() {
        // This is the compatibility guarantee: decimals, sample payloads and pat-function atoms
        // all contain dots and must be untouched.
        assertEquals(listOf(0.5, 1.25), pat("0.5 1.25").nums().cycle().map { it.value })
        assertEquals(listOf("g:(0,0.12)", "hh"), pat("g:(0,0.12) hh").cycle().map { it.value })
        assertEquals(1, pat("0.5").cycle().size)
    }

    // ── `_` elongates ─────────────────────────────────────────────────────────────────────

    @Test
    fun `an underscore extends the previous step by one slot`() {
        val events = pat("bd _ _ sd").cycle()
        assertEquals(listOf("bd", "sd"), events.map { it.value })
        assertEquals(listOf(PatQ.ZERO to PatQ.of(3, 4), PatQ.of(3, 4) to PatQ.ONE), arcsOf(events))
        // exactly the `@` spelling.
        assertEquals(arcsOf(pat("bd@3 sd").cycle()), arcsOf(events))
        // it stacks with an explicit `@`.
        assertEquals(arcsOf(pat("bd@3 sd").cycle()), arcsOf(pat("bd@2 _ sd").cycle()))
    }

    @Test
    fun `an underscore inside a token is still part of the token`() {
        assertEquals(listOf("my_atom", "b"), pat("my_atom b").cycle().map { it.value })
        assertEquals(2, pat("my_atom b").cycle().size)
    }

    // ── bare `!` repeats ──────────────────────────────────────────────────────────────────

    @Test
    fun `a bare bang repeats the previous step`() {
        val events = pat("bd ! ! sd").cycle()
        assertEquals(listOf("bd", "bd", "bd", "sd"), events.map { it.value })
        assertEquals((0 until 4).map { PatQ.of(it, 4) to PatQ.of(it + 1, 4) }, arcsOf(events))
        // exactly the postfix spelling, and it composes with it.
        assertEquals(pat("bd!3 sd").cycle().map { it.value }, events.map { it.value })
        assertEquals(pat("bd!3 sd").cycle().map { it.value }, pat("bd!2 ! sd").cycle().map { it.value })
    }

    @Test
    fun `a bang attached to a token is still the postfix count`() {
        assertEquals(listOf("bd", "bd", "cp"), pat("bd!2 cp").cycle().map { it.value })
    }

    // ── the markers together, and with everything else ────────────────────────────────────

    @Test
    fun `markers compose with groups, euclid, polymeter and alternation`() {
        // A group whose halves use different markers.
        assertEquals(
            listOf("bd", "bd", "hh", "hh", "hh"),
            pat("bd ! . hh hh hh").cycle().map { it.value }
        )
        // An elongation inside a bracket.
        assertEquals(arcsOf(pat("[a@3 b] c").cycle()), arcsOf(pat("[a _ _ b] c").cycle()))
        // Markers do not disturb a Euclidean suffix on the group's terms.
        assertEquals(3, pat("bd(3,8) . hh").cycle().count { it.value == "bd" })
        // Polymeter still parses, and `_` works inside a layer.
        val poly = pat("{bd _ sd, hh hh hh}").cycle()
        assertTrue(poly.isNotEmpty())
        // Per-cycle alternation is unaffected.
        assertEquals(listOf("a"), pat("<a b> . <a b>").cycle(0).map { it.value }.distinct())
        assertEquals(listOf("b"), pat("<a b> . <a b>").cycle(1).map { it.value }.distinct())
    }

    @Test
    fun `every pre-existing mini-notation form still parses to what it did`() {
        // A regression net for the parser change: these are the forms the file header documents.
        val corpus = mapOf(
            "bd [hh hh] sd" to 4,
            "bd*2 cp" to 3,
            "bd!2 cp" to 3,
            "superpiano@3 superpiano" to 2,
            "[bd cp](3,8) hh!2" to 8,
            "{bd hh 808:4}%8" to 8,
            "c, e, g" to 3,
            "bd:(1,2,3) hh" to 2,
            "~ sd ~ sd" to 2,
            "0.5 1.25" to 2,
            "g:(0,0.12) tent:(1,0.08,0.8)" to 2,
        )
        corpus.forEach { (source, expected) ->
            assertEquals(expected, pat(source).cycle().size, "pat(\"$source\") event count")
        }
        // `{bd sd stab, cp arpy cr arpy}` layers two lines of different length.
        assertTrue(pat("{bd sd stab, cp arpy cr arpy}").cycle().size >= 6)
        // `[bd*4|hh*12|cp*2]` picks one alternative per cycle, so a cycle has 4, 12 or 2 events.
        val choices = (0L until 24L).map { pat("[bd*4|hh*12|cp*2]").cycle(it).size }.toSet()
        assertTrue(choices.isNotEmpty() && choices.all { it in setOf(4, 12, 2) }, "random choice: $choices")
    }

    // ── `|` at the top level ──────────────────────────────────────────────────────────────

    /**
     * A whole string is a group like any other. Tidal's grammar says so structurally — `pPolyIn` is
     * `brackets pTidal`, the same production the top level uses — so `bd|sd|cp` has to parse, and
     * `[bd|sd|cp]` has to be the identical pattern rather than merely a similar one. Until
     * 2026-09-13 `parseTop` went straight to `parseStack` and the bare form threw
     * `Unexpected '|' at 2`.
     */
    @Test
    fun `a bare random choice parses, and the brackets add nothing`() {
        for (cycle in 0L until 16L) {
            assertEquals(
                pat("[bd|sd|cp]").cycle(cycle).map { it.value },
                pat("bd|sd|cp").cycle(cycle).map { it.value },
                "cycle $cycle: the brackets changed the pattern"
            )
        }
        // Not a constant dressed up as a choice: over sixteen cycles all three turn up.
        val chosen = (0L until 16L).flatMap { pat("bd|sd|cp").cycle(it).map { e -> e.value } }
        assertEquals(setOf("bd", "sd", "cp"), chosen.toSet(), "bare choice did not reach every alternative")
        assertEquals(16, chosen.size, "a bare choice should sound once a cycle")

        // The alternatives are sequences, not atoms: `bd*2 | sd cp` chooses between a two-event
        // bar and a two-event bar of different values, never mixes them.
        for (cycle in 0L until 16L) {
            val values = pat("bd*2 | sd cp").cycle(cycle).map { it.value }
            assertTrue(
                values == listOf("bd", "bd") || values == listOf("sd", "cp"),
                "cycle $cycle mixed the alternatives: $values"
            )
        }
    }

    /**
     * `|` binds looser than `,`, so a mixed string is a choice between stacks. Tidal declines to
     * decide this case — its `pTidal` takes a `,`-tail *or* a `|`-tail, so mixing them is a Tidal
     * parse error — which is why Field is free to pick, and why picking the looser one outermost is
     * the safe pick: every string Tidal accepts still means what Tidal means by it.
     */
    @Test
    fun `a mixed stack and choice reads as a choice between stacks`() {
        for (cycle in 0L until 16L) {
            val values = pat("bd, hh*2 | cp").cycle(cycle).map { it.value }.sorted()
            assertTrue(
                values == listOf("bd", "hh", "hh") || values == listOf("cp"),
                "cycle $cycle: expected the `bd, hh*2` layer or the `cp` layer, got $values"
            )
        }
        val everything = (0L until 16L).map { pat("bd, hh*2 | cp").cycle(it).size }.toSet()
        assertEquals(setOf(3, 1), everything, "both sides of the choice should turn up in sixteen cycles")
    }

    // ── polymeter, which used to fall silent after one bar ────────────────────────────────

    @Test
    fun `polymeter keeps playing past cycle zero`() {
        // The bug this pins: `MiniPat.compilePolyLayer` builds ABSOLUTE windows and handed them to
        // `within`, which reads its window as a local arc inside one cycle. Every window for cycle 1
        // and beyond therefore missed, and `pat("{bd hh 808:4}%8")` played eight events in the first
        // bar and nothing ever again — an advertised feature that stopped after one bar.
        for (source in listOf("{bd sd stab, cp arpy cr}", "{bd hh 808:4}%8", "{bd sd, hh hh hh}")) {
            val counts = (0L until 6L).map { pat(source).cycle(it).size }
            assertTrue(counts.all { it > 0 }, "pat(\"$source\") went silent: $counts")
            assertEquals(1, counts.distinct().size, "pat(\"$source\") changed density per cycle: $counts")
        }
    }

    @Test
    fun `polymeter rotates against the pulse grid, which is the whole point`() {
        // Three items against an eight-pulse grid: the sequence phases, so each cycle starts on a
        // different item. If this only ever started on `bd`, polymeter would just be a fast sequence.
        val pattern = pat("{bd hh 808:4}%8")
        // Three items over eight pulses: after a bar the sequence has advanced by 8 mod 3 = 2, so the
        // cycle starts step *two* further on each time — bd, then 808:4, then hh.
        assertEquals(listOf("bd", "808:4", "hh"), (0L until 3L).map { pattern.cycle(it).first().value })
        for (cycle in 0L until 6L) {
            val events = pattern.cycle(cycle)
            assertEquals(8, events.size, "cycle $cycle")
            val base = PatQ.of(cycle)
            assertEquals(
                (0 until 8).map { PatQ.of(it, 8) + base },
                events.map { it.arc.start },
                "cycle $cycle is not on the eighth grid"
            )
        }
        // Cycle 0 is unchanged by the fix — the previously-working bar must stay byte for byte.
        assertEquals(
            listOf("bd", "hh", "808:4", "bd", "hh", "808:4", "bd", "hh"),
            pattern.cycle(0).map { it.value }
        )
    }

    @Test
    fun `the first layer sets the pulse and the others phase against it`() {
        // `{bd sd, hh hh hh}` — the FIRST layer fixes the grid at two slots per cycle, so the
        // three-item layer plays two of its items per bar and phases. That is the definition of
        // polymeter rather than a bug: both layers keep going, at the same density, forever.
        val pattern = pat("{bd sd, hh hh hh}")
        for (cycle in 0L until 6L) {
            val events = pattern.cycle(cycle)
            assertEquals(2, events.count { it.value == "bd" || it.value == "sd" }, "cycle $cycle drums")
            assertEquals(2, events.count { it.value == "hh" }, "cycle $cycle hats")
            assertEquals(4, events.size, "cycle $cycle total")
        }
        // With three distinguishable items the phasing is visible: `{a b c}` against a two-pulse
        // grid starts on a, then c, then b — a three-cycle cycle.
        val phasing = pat("{bd sd, a b c}")
        assertEquals(
            listOf("a", "c", "b", "a"),
            (0L until 4L).map { c -> phasing.cycle(c).first { it.value in setOf("a", "b", "c") }.value }
        )
    }

    @Test
    fun `a per-cycle construct inside a polymeter layer advances`() {
        // `patFocus` passes the repetition index down as the child's own cycle, so `<a b>` inside a
        // layer alternates instead of being frozen on cycle 0 forever.
        val values = (0L until 4L).map { pat("{<a b> c}%4").cycle(it).map { e -> e.value } }
        assertTrue(values.all { it.size == 4 }, "density changed: ${values.map { it.size }}")
        assertTrue(
            values.flatten().contains("a") && values.flatten().contains("b"),
            "the alternation never advanced: $values"
        )
    }

    // ── the source-span regression corpus ─────────────────────────────────────────────────

    @Test
    fun `every atom's source span quotes the atom that produced it`() {
        // The live editor highlighting (Marker.patHighlight) maps an event back into the pattern
        // text through sourceSpan. Nothing asserted the spans were right, so this is the net: for
        // every form in the corpus, every event's span must extract exactly its own atom's text.
        val corpus = listOf(
            "bd sd hh cp",
            "bd [hh hh] sd",
            "bd sd . hh hh hh",
            "bd _ _ sd",
            "bd ! ! sd",
            "bd!2 cp",
            "superpiano@3 superpiano",
            "bd(3,8) hh",
            "[bd cp](3,8) hh!2",
            "{bd hh 808:4}%8",
            "{bd sd stab, cp arpy cr arpy}",
            "c, e, g",
            "bd*4 cp",
            "bd/2 cp",
            "bd:3 sd 808:12",
            "bd*8?0.4",
            "c'maj7 a'min7",
            "0.5 1.25",
        )
        var checked = 0
        for (source in corpus) {
            for (cycle in 0L until 4L) {
                for (event in pat(source).cycle(cycle)) {
                    val span = event.sourceSpan
                    if (span == null) {
                        assertTrue(false, "pat(\"$source\") cycle $cycle: event ${event.value} has no source span")
                        continue
                    }
                    assertEquals(
                        source, span.source,
                        "pat(\"$source\"): span points at a different source string"
                    )
                    assertEquals(
                        event.value, span.text,
                        "pat(\"$source\") cycle $cycle: event value '${event.value}' but span quotes '${span.text}' " +
                            "(offsets ${span.start}..${span.endExclusive})"
                    )
                    assertTrue(
                        span.start in 0..span.endExclusive && span.endExclusive <= source.length,
                        "pat(\"$source\"): span ${span.start}..${span.endExclusive} is out of range"
                    )
                    checked++
                }
            }
        }
        assertTrue(checked > 200, "the span corpus only checked $checked events")
    }

    @Test
    fun `alternation and per-cycle choice keep the span on the atom that actually played`() {
        // `<a bb>` plays a different atom each cycle, and the span must follow — otherwise the
        // editor highlights the wrong alternative for half the bars.
        val source = "<a bb> cc"
        for (cycle in 0L until 6L) {
            for (event in pat(source).cycle(cycle)) {
                assertEquals(event.value, event.sourceSpan?.text, "cycle $cycle, event ${event.value}")
            }
        }
        // A random choice does the same.
        for (cycle in 0L until 12L) {
            for (event in pat("[aa|bbb|c]").cycle(cycle)) {
                assertEquals(event.value, event.sourceSpan?.text, "random choice cycle $cycle")
            }
        }
    }

    @Test
    fun `a parameter pattern's span is recorded alongside the atom's own`() {
        // `with(...)` must not overwrite the atom's span; it appends the parameter's, so a highlight
        // can show both where the note is and where its value came from.
        val events = (pat("bd sd") with gain("0.4 0.9")).cycle()
        assertEquals(listOf("bd", "sd"), events.map { it.sourceSpan?.text })
        assertEquals(listOf("0.4", "0.9"), events.map { it.additionalSpans.lastOrNull()?.text })
    }
}
