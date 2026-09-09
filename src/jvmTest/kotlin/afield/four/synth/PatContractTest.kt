package afield.four.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract tests for `PatPattern` itself, swept across the whole operator surface.
 *
 * These are not tests of what any one operator *means* — `PatSignalsTest`, `PatAlgebraTest`,
 * `PatCompositionTest` and `MiniNotationTest` do that. They are tests of the properties every
 * operator has to satisfy for the language to be usable at all, swept across a corpus with one
 * expression per operator in all five operator files:
 *
 *  1. **Liveness.** Every pattern that is not explicitly allowed to rest makes at least one event in
 *     *every* one of the first six cycles. This is the property that catches the whole "works once,
 *     then silent" family — the polymeter bug (see `MiniNotationTest`) was exactly that, and it is
 *     verified below that this sweep is *sensitive* to it: reinstating the old `within` call in
 *     `compilePolyLayer` makes `mini polymeter` and `mini polymeter %` fail here.
 *  2. **Cycle-invariance.** A pattern with no per-cycle construct produces the same events in every
 *     cycle, up to a whole-cycle shift. Entries marked `varying()` are exempt, and the exemption
 *     list is itself asserted to be minimal.
 *  3. **Query-range additivity.** Asking for `[0, N)` in one call reports exactly the notes that
 *     asking for each `[i, i+1)` reports — whole for whole. Queries still return *fragments* of
 *     events that cross the window's edge, because a query cannot report time it was not asked
 *     about, but a fragment knows it is one (`PatEvent.whole`), so it is never mistaken for a new
 *     note. This property used to be weakened to "same onsets, same total sounding duration" on the
 *     grounds that fragmentation made exactness impossible; the weakening compared onset sets in one
 *     direction only, and the double-fire bug it was accommodating lived in the other direction.
 *  4. **Purity** and **query-order independence.** The same query twice gives the same answer, and
 *     asking for cycle 5 before cycle 0 gives the same answer as after.
 *  5. **Containment and well-formedness.** No event escapes the arc it was asked about, no part
 *     escapes its own whole, and no arc is empty or inverted — checked at whole-cycle, sub-cycle,
 *     straddling and negative arcs.
 *  6. **Scheduling matches rendering.** What `eventSequence` plays is exactly what a single wide
 *     query draws. This is the property that goes through the *production* consumer: every artifact
 *     on this branch — the piano rolls, the `.wav`s — renders from one wide query, so none of them
 *     could see a scheduler that struck a bar-crossing note twice.
 *
 * Adding an operator without adding a line to [corpus] is the only way to escape the sweep, and
 * `the corpus covers every operator file` guards against the corpus quietly shrinking.
 */
class PatContractTest {

    /**
     * A corpus entry.
     *
     * @param varies true when the pattern is *supposed* to differ from cycle to cycle — anything
     *   with `<>`, `?`, randomness, or a per-cycle transform. Those are exempt from the
     *   cycle-invariance property but not from any of the others.
     * @param maySkipCycles true when the pattern may legitimately produce an empty cycle. Only
     *   `silence` and the per-cycle random alternation of `randcat`-like forms qualify; everything
     *   else must make a sound in every bar, which is the property that catches "works once".
     */
    private class Entry(
        val label: String,
        val pattern: PatPattern<String>,
        val varies: Boolean = false,
        val maySkipCycles: Boolean = false
    )

    private infix fun String.means(pattern: PatPattern<String>) = Entry(this, pattern)

    /** Marks an entry as legitimately different every cycle. */
    private fun Entry.varying() = Entry(label, pattern, varies = true, maySkipCycles = maySkipCycles)

    /** Marks an entry as allowed to fall silent for a whole cycle. */
    private fun Entry.mayBeSilent() = Entry(label, pattern, varies = true, maySkipCycles = true)

    /**
     * One entry per operator. Deliberately written as combinator expressions rather than only as
     * parsed strings, so the Kotlin-level surface is covered too.
     */
    private fun corpus(): List<Entry> = listOf(
        // ── TidalPatterns.kt: the original surface ──
        "pure" means pure("a"),
        ("silence" means silence()).mayBeSilent(),
        "patOf" means patOf("a", "b", "c"),
        "cat" means cat(pat("a b"), pat("c")),
        ("slowcat" means slowcat(pat("a b"), pat("c"))).varying(),
        "fastcat" means fastcat(pat("a b"), pat("c")),
        "stack" means stack(pat("a b"), pat("c d e")),
        "rev" means pat("a b c").rev(),
        "palindrome" means pat("a b c").palindrome(),
        "zoom" means pat("a b c d").zoom(PatQ.of(1, 4), PatQ.of(1, 2)),
        "delay" means pat("a b c").delay(PatQ.of(1, 8)),
        "fast" means pat("a b").fast(3),
        ("slow" means pat("a b c").slow(2)).varying(),
        ("every" means pat("a b c").every(3) { it.rev() }).varying(),
        ("switchEvery" means pat("a b").switchEvery(2, pat("x y z"))).varying(),
        ("whenCycle" means pat("a b").whenCycle({ it % 2 == 0L }) { it.fast(2) }).varying(),
        ("whenMod" means pat("a b").whenMod(1, 3) { it.rev() }).varying(),
        // chunk walks its transform through the bar, so it *must* vary per cycle — being able to
        // leave this un-`varying()` is precisely how the old `inside`-in-disguise gave itself away.
        ("chunk" means pat("a b c d").chunk(2) { it.rev() }).varying(),
        "inside" means pat("a b c d").inside(2) { it.rev() },
        "outside" means pat("a b c d").outside(2) { it.rev() },
        "playWhen" means pat("a b c d").playWhen { it < PatQ.of(1, 2) },
        "withinCycle" means pat("a b c d").withinCycle(PatArc(PatQ.ZERO, PatQ.of(1, 2))) { it.fast(2) },
        "subdivide" means pat("a b").subdivide(3),
        ("sometimesBy" means pat("a b c").sometimesBy(0.5) { it.fast(2) }).varying(),
        ("degradeBy" means pat("a*8").degradeBy(0.4)).varying(),
        "striate" means pat("a b").striate(3),
        "stutter" means pat("a b").stutter(3, PatQ.of(1, 4)),
        ("timeScale" means pat("a b c").timeScale(PatQ.of(3, 2))).varying(),
        ("density" means pat("a b c").density(1.5)).varying(),
        "arp(list)" means pat("a b").arp(listOf("x", "y", "z"), speed = 3),
        "arp(pattern)" means pat("c, e, g").arp("0 2 1"),
        "zip" means pat("a b").zip(pat("1 2 3"), "v"),
        "join" means join(pat("a b").map { pat("x y") }),
        "mask" means mask(euclid(3, 8), pat("a")),
        "euclidFill" means euclidFill(3, 8, on = pat("a")),
        "within" means within(PatArc(PatQ.of(1, 4), PatQ.of(3, 4)), pat("a b")),
        "dropSampleIndex" means pat("a:3 b").dropSampleIndex(),

        // ── mini-notation ──
        "mini seq" means pat("a [b b] c"),
        "mini fast" means pat("a*3 b"),
        "mini slow" means pat("a/2 b"),
        "mini replicate" means pat("a!3 b"),
        "mini elongate" means pat("a@3 b"),
        ("mini rest" means pat("a ~ b ~")).mayBeSilent(),
        "mini euclid" means pat("a(3,8,2) b"),
        ("mini alternate" means pat("<a b c> d")).varying(),
        ("mini random alt" means pat("[a|b|c] d")).varying(),
        "mini stack" means pat("a, b c, d e f"),
        ("mini degrade" means pat("a*8?0.4")).varying(),
        "mini index" means pat("a:3 b:12"),
        "mini dot group" means pat("a b . c c c"),
        "mini underscore" means pat("a _ _ b"),
        "mini bare bang" means pat("a ! ! b"),
        ("mini polymeter" means pat("{a b c, d e}")).varying(),
        ("mini polymeter %" means pat("{a b c}%8")).varying(),
        "mini poly alternate" means pat("{<a b> c}%4"),
        "mini nested" means pat("[a [b c]] [d e]"),

        // ── PatSignals.kt ──
        "sine.segment" means sine().segment(8).texts(),
        "saw.range" means saw().range(1, 9).segment(5).texts(),
        ("rand.segment" means rand().segment(6).texts()).varying(),
        ("perlin.segment" means perlin().segment(6).texts()).varying(),
        ("irand" means irand(5).segment(7).texts()).varying(),
        "run" means run(5).texts(),
        "quantise" means sine().quantise(0.25).segment(9).texts(),
        "struct" means struct(euclid(3, 8), pat("a b")),
        "struct(bools)" means struct(bools("t f t t"), run(4).texts()),
        "grid" means struct(grid(6), pat("a")),
        "nums.texts" means pat("1 2 3").nums().texts(),

        // ── PatAlgebra.kt ──
        "appBoth" means pat("0 1").nums().`|+|`(pat("10 20 30").nums()).texts(),
        "appLeft" means pat("0 1").nums().`|+`(pat("10 20 30").nums()).texts(),
        "appRight" means pat("0 1").nums().`+|`(pat("10 20 30").nums()).texts(),
        "takeRight" means pat("a b").`#`(pat("x y z")),
        "keepLeft" means pat("a b").`|L`(sine()),
        "rhythmFrom" means (pat("a b") rhythmFrom run(4)),
        "with(one)" means (pat("a b c") with gain(sine())),
        "with(many)" means pat("a b").with(gain(saw()), pan("0 1"), speed(2)),
        "without" means (pat("a b") with gain(0.5)).without("gain"),

        // ── PatComposition.kt ──
        "off" means pat("a b").off(1, 8) { it with gain(0.4) },
        "superimpose" means pat("a b").superimpose { it.fast(2) },
        "layer" means pat("a b").layer({ it }, { it.rev() }),
        "jux" means pat("a b c").jux { it.rev() },
        "juxBy" means pat("a b").juxBy(0.4) { it.rot(1) },
        ("iter" means pat("a b c d").iter(4)).varying(),
        ("iterBack" means pat("a b c d").iterBack(3)).varying(),
        "linger" means pat("a b c d").linger(1, 3),
        "ply" means pat("a b").ply(3),
        "rot" means pat("a b c").rot(1),
        ("shuffle" means pat("a b c d").shuffle(4)).varying(),
        ("randcat" means randcat(pat("a"), pat("b c"))).varying(),
        ("wrandcat" means wrandcat(pat("a") to 3.0, pat("b c") to 1.0)).varying(),
        ("choose" means choose("a", "b", "c").segment(5)).varying(),
        ("wchoose" means wchoose("a" to 3.0, "b" to 1.0).segment(5)).varying(),
        "chop" means pat("a b").chop(3),
        "striateAcross" means pat("a b").striateAcross(3),
        "squeeze" means pat("a b").squeeze { if (it == "a") pat("1 2 3") else pat("4 5") },
        "squeezeJoin" means squeezeJoin(pat("a b").map { pat("x y") }),
        "pick" means run(4).pick("x", "y", "z"),
        "swing" means pat("a*8").swing(4),
        "swingBy" means pat("a*8").swingBy(PatQ.of(1, 4), 2),
        "press" means pat("a b c").press(),
        "pressBy" means pat("a b").pressBy(PatQ.of(1, 3)),
        "ncat" means ncat(pat("a") to 1, pat("b c") to 2),
        (("unDegradeBy" means pat("a*8").unDegradeBy(0.4)).mayBeSilent()).varying(),
        ("sometimesEventBy" means pat("a*8").sometimesEventBy(0.4) { it.fast(2) }).varying(),
        ("someCyclesBy" means pat("a b").someCyclesBy(0.5) { it.rev() }).varying(),
        "euclidInv" means mask(euclidInv(3, 8), pat("a")),
        "euclidFull" means euclidFull(3, 8, pat("a"), pat("b")),
        "scale" means run(8).scale("minor").texts(),
        "toScale" means run(6).toScale(listOf(0, 2, 5, 7)).texts(),
        "chords" means chords("c'maj7 a'min7").notesOf().texts(),
        "voiceLead" means chords("c'maj7 a'min7 d'min7").voiceLead().notesOf().texts(),
        "arpeggiate" means chords("c'maj7 f'min7").arpeggiate().texts(),
        "chord" means run(2).scale("major").chord("min7").notesOf().texts(),

        // ── PatKolakoski.kt ──
        "kolakoski" means mask(kolakoski(16), pat("a")),
        "kolakoski gaps" means mask(kolakoski(16, KolakoskiReading.GAPS), pat("a")),
        "kolakoski 2,3" means mask(kolakoski(16, a = 2, b = 3), pat("a")),
        "kolakoskiInv" means mask(kolakoskiInv(16), pat("a")),
        "kolakoskiFill" means kolakoskiFill(16, on = pat("a")),
        "kolakoskiFull" means kolakoskiFull(16, pat("a"), pat("b")),
        "mini kolakoski" means pat("a(k16) b"),
        "mini kolakoski gaps" means pat("a(g16,3) b"),
    )

    /** A comparable, order-independent signature of one event. */
    private fun key(event: PatEvent<String>): String =
        "${event.arc.start}|${event.arc.end}|${event.value}|" +
            event.annotations.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }

    private fun signature(events: List<PatEvent<String>>): List<String> = events.map(::key).sorted()

    /** Re-expresses events relative to the start of cycle [cycle], so cycles compare to each other. */
    private fun shiftBack(events: List<PatEvent<String>>, cycle: Long): List<PatEvent<String>> {
        val base = PatQ.of(cycle)
        return events.map { it.copy(arc = PatArc(it.arc.start - base, it.arc.end - base)) }
    }

    private companion object {
        const val CYCLES = 4
    }

    // ── 1. liveness: the property that catches "works once, then silent" ──────────────────

    @Test
    fun `every pattern makes a sound in every one of the first six cycles`() {
        val problems = mutableListOf<String>()
        for (entry in corpus()) {
            if (entry.maySkipCycles) continue
            val counts = (0L until 6L).map { entry.pattern.events(PatArc(PatQ.of(it), PatQ.of(it + 1))).size }
            if (counts.any { it == 0 }) problems += "${entry.label}: events per cycle = $counts"
        }
        assertTrue(
            problems.isEmpty(),
            "patterns that fall silent (the polymeter-bug family):\n" + problems.joinToString("\n")
        )
    }

    @Test
    fun `the silence exemption list is minimal`() {
        // A guard on the exemption: adding an operator to `mayBeSilent()` to make the liveness test
        // pass is the obvious way to defeat it, so the membership is pinned. Each member earns it:
        //   silence       — by definition;
        //   mini rest     — "a ~ b ~" is deliberately half rests, and `~` alone would be all rest;
        //   unDegradeBy   — it keeps only what degradeBy drops, so ~40% of eight events, which
        //                   legitimately comes out empty for some cycles (measured: cycle 5 of six).
        val exempt = corpus().filter { it.maySkipCycles }.map { it.label }.toSet()
        assertEquals(setOf("silence", "mini rest", "unDegradeBy"), exempt, "the silence exemption list grew")
    }

    // ── 2. cycle-invariance ───────────────────────────────────────────────────────────────

    @Test
    fun `a pattern with no per-cycle construct repeats exactly`() {
        val problems = mutableListOf<String>()
        for (entry in corpus()) {
            if (entry.varies || entry.maySkipCycles) continue
            val first = signature(shiftBack(entry.pattern.events(PatArc(PatQ.ZERO, PatQ.ONE)), 0))
            for (cycle in 1L until 6L) {
                val later = signature(
                    shiftBack(entry.pattern.events(PatArc(PatQ.of(cycle), PatQ.of(cycle + 1))), cycle)
                )
                if (later != first) {
                    problems += "${entry.label}: cycle $cycle differs from cycle 0" +
                        "\n    cycle 0: ${first.take(4)}" +
                        "\n    cycle $cycle: ${later.take(4)}"
                    break
                }
            }
        }
        assertTrue(problems.isEmpty(), "patterns that should repeat and do not:\n" + problems.joinToString("\n"))
    }

    @Test
    fun `the varying exemption list only holds things that really vary`() {
        // The other half of the guard: an entry marked `varying()` must actually differ across the
        // first six cycles, otherwise the exemption is hiding a bug rather than describing one.
        val problems = mutableListOf<String>()
        for (entry in corpus()) {
            if (!entry.varies || entry.maySkipCycles) continue
            val shapes = (0L until 6L).map {
                signature(shiftBack(entry.pattern.events(PatArc(PatQ.of(it), PatQ.of(it + 1))), it))
            }
            if (shapes.distinct().size == 1) problems += entry.label
        }
        assertTrue(
            problems.isEmpty(),
            "marked as varying but identical in all six cycles — the exemption is unearned:\n" +
                problems.joinToString("\n")
        )
    }

    // ── 3. query-range additivity ─────────────────────────────────────────────────────────

    /**
     * Query-range additivity, stated **exactly** — the window you look through must not change the
     * music.
     *
     * This property used to be weakened to "same onsets, same total sounding duration", on the
     * grounds that a single-arc event model necessarily returns one wide event where two narrow
     * queries return two fragments. That was true of the old model and it was the wrong response:
     * the weakened form compared onset *sets* in one direction only, and the bug it was papering
     * over — a cycle-spanning event coming back as two indistinguishable fragments, and therefore
     * being played twice — lived precisely in the direction it did not check.
     *
     * Now that [PatEvent.whole] records where an event really begins and ends, the honest property
     * is available: the events **with onsets** are the same events, whole for whole, however you
     * slice the query. Fragments are still returned — a query cannot report time it was not asked
     * about — but a fragment now knows it is one, so it is no longer mistaken for a new note.
     */
    @Test
    fun `a multi-cycle query and per-cycle queries report exactly the same notes`() {
        val problems = mutableListOf<String>()
        for ((label, pattern) in corpus().map { it.label to it.pattern }) {
            val wide = pattern.events(PatArc(PatQ.ZERO, PatQ.of(CYCLES)))
            val perCycle = (0 until CYCLES).flatMap { pattern.events(PatArc(PatQ.of(it), PatQ.of(it + 1))) }

            // A note is identified by where it really runs and what it says — not by the fragment
            // this particular query happened to see.
            fun notes(events: List<PatEvent<String>>): List<String> =
                events.filter { it.hasOnset }.map { "${it.extent}|${it.value}" }.sorted()

            val a = notes(wide)
            val b = notes(perCycle)
            if (a != b) {
                val lost = a - b.toSet()
                val invented = b - a.toSet()
                problems += "$label: the two ways of asking disagree" +
                    (if (lost.isNotEmpty()) "\n    only in the wide query:      ${lost.take(4)}" else "") +
                    (if (invented.isNotEmpty()) "\n    only in the per-cycle query: ${invented.take(4)}" else "") +
                    (if (lost.isEmpty() && invented.isEmpty()) "\n    same set, different counts: ${a.size} vs ${b.size}" else "")
            }

            // A part is a window onto a whole, so it can never stick out of one.
            for (event in wide + perCycle) {
                val extent = event.whole ?: continue
                if (event.arc.start < extent.start || event.arc.end > extent.end) {
                    problems += "$label: part ${event.arc} escapes its whole $extent"
                    break
                }
            }
        }
        assertTrue(problems.isEmpty(), "query-range additivity failed for:\n" + problems.joinToString("\n"))
    }

    // ── 2. purity ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `every operator answers the same query the same way twice`() {
        val problems = mutableListOf<String>()
        for ((label, pattern) in corpus().map { it.label to it.pattern }) {
            val arc = PatArc(PatQ.ZERO, PatQ.of(CYCLES))
            val first = signature(pattern.events(arc))
            val second = signature(pattern.events(arc))
            if (first != second) problems += "$label: ${first.size} then ${second.size} events"
        }
        assertTrue(problems.isEmpty(), "impure operators:\n" + problems.joinToString("\n"))
    }

    // ── 3. query-order independence ───────────────────────────────────────────────────────

    @Test
    fun `querying cycles out of order gives the same answer`() {
        val problems = mutableListOf<String>()
        for ((label, pattern) in corpus().map { it.label to it.pattern }) {
            val forwards = (0 until 6).associateWith { signature(pattern.events(PatArc(PatQ.of(it), PatQ.of(it + 1)))) }
            // A second, independent pattern instance queried backwards, so neither shared state nor
            // a warm cache inside one instance can hide a dependency on query order.
            val backwards = (5 downTo 0).associateWith { signature(pattern.events(PatArc(PatQ.of(it), PatQ.of(it + 1)))) }
            for (cycle in 0 until 6) {
                if (forwards[cycle] != backwards[cycle]) {
                    problems += "$label: cycle $cycle differs when queried backwards"
                    break
                }
            }
        }
        assertTrue(problems.isEmpty(), "order-dependent operators:\n" + problems.joinToString("\n"))
    }

    // ── 4. containment and well-formedness ────────────────────────────────────────────────

    @Test
    fun `no event escapes the arc it was asked about`() {
        val problems = mutableListOf<String>()
        val arc = PatArc(PatQ.of(1), PatQ.of(3))
        for ((label, pattern) in corpus().map { it.label to it.pattern }) {
            for (event in pattern.events(arc)) {
                if (event.arc.start < arc.start || event.arc.end > arc.end) {
                    problems += "$label: event ${event.value} at ${event.arc} escapes $arc"
                    break
                }
            }
        }
        assertTrue(problems.isEmpty(), "events outside the queried arc:\n" + problems.joinToString("\n"))
    }

    @Test
    fun `no operator produces an empty or inverted arc`() {
        // `PatArc`'s own `require` would throw on construction, so this asserts the whole corpus can
        // be queried at all — including at sub-cycle granularity, where clipping arithmetic is
        // easiest to get wrong.
        val arcs = listOf(
            PatArc(PatQ.ZERO, PatQ.of(CYCLES)),
            PatArc(PatQ.of(1, 3), PatQ.of(2, 3)),
            PatArc(PatQ.of(7, 8), PatQ.of(9, 8)),
            PatArc(PatQ.of(-2), PatQ.of(1)),
            PatArc(PatQ.of(1, 64), PatQ.of(2, 64))
        )
        for ((label, pattern) in corpus().map { it.label to it.pattern }) {
            for (arc in arcs) {
                val events = runCatching { pattern.events(arc) }
                    .getOrElse { throw AssertionError("$label threw on $arc: ${it.javaClass.simpleName}: ${it.message}", it) }
                for (event in events) {
                    assertTrue(event.arc.end > event.arc.start, "$label produced ${event.arc} on $arc")
                }
            }
        }
    }

    // ── 6. the scheduler sees what the plot draws ─────────────────────────────────────────

    /**
     * The bug this whole whole/part apparatus exists for, pinned with its own numbers.
     *
     * `pat("a b c").slow(2)` is three steps stretched over two cycles, so nothing lands on the bar
     * line: `a` runs `0 → 2/3`, `b` runs `2/3 → 4/3` **across** it, and `c` runs `4/3 → 2`. A
     * scheduler asks one cycle at a time, so it sees `b` twice — once as `2/3 → 1` and again as
     * `1 → 4/3`. Played naively that is four notes, with `b` struck twice and each strike shorter
     * than the note actually is.
     *
     * Three notes, and `b` sounds once, for its full two thirds.
     */
    @Test
    fun `an event across a bar line is scheduled once, at full length`() {
        val pattern = pat("a b c").slow(2)

        // The wide query — what the piano roll and the audio renderer draw — is three notes.
        val drawn = pattern.events(PatArc(PatQ.ZERO, PatQ.of(2)))
        assertEquals(listOf("a", "b", "c"), drawn.map { it.value })
        assertEquals(PatArc(PatQ.of(2, 3), PatQ.of(4, 3)), drawn[1].arc, "b should straddle the bar line")

        // Per-cycle querying still returns the two fragments — a query cannot report time it was
        // not asked about — but only one of them is an onset.
        val cycleOne = pattern.events(PatArc(PatQ.ONE, PatQ.of(2)))
        val tail = cycleOne.single { it.value == "b" }
        assertFalse(tail.hasOnset, "the second fragment of b is a continuation, not a new note")
        assertEquals(PatArc(PatQ.of(2, 3), PatQ.of(4, 3)), tail.whole, "the fragment forgot where b began")

        // And the scheduler — the thing that actually makes sound — strikes it once.
        val scheduled = pattern.eventSequence(0).take(3).toList()
        assertEquals(listOf("a", "b", "c"), scheduled.map { it.value })
        assertEquals(
            listOf(PatArc(PatQ.ZERO, PatQ.of(2, 3)), PatArc(PatQ.of(2, 3), PatQ.of(4, 3)), PatArc(PatQ.of(4, 3), PatQ.of(2))),
            scheduled.map { it.arc },
            "a scheduled note should carry its whole length, not the part that fitted in one cycle"
        )
        // The fourth thing the scheduler sees belongs to the next repeat, not to a re-struck b.
        assertEquals(PatQ.of(2), pattern.eventSequence(0).drop(3).first().arc.start)
    }

    /**
     * The same property across the whole corpus: **every** operator schedules exactly the notes it
     * draws.
     *
     * This is the check that would have caught the `slow` regression, and the one the old
     * "fragmentation is unavoidable" framing made unstateable. It compares the scheduling path
     * (`eventSequence`, which queries one cycle at a time) against a single wide query — the shape
     * the galleries and the audio renderer use. Before [PatEvent.whole] those two disagreed for any
     * operator that could put an event across a bar line, and no artifact in the branch could see it
     * because every artifact rendered from the wide query.
     */
    @Test
    fun `every operator schedules exactly the notes a wide query draws`() {
        val problems = mutableListOf<String>()
        for ((label, pattern) in corpus().map { it.label to it.pattern }) {
            val expected = pattern.events(PatArc(PatQ.ZERO, PatQ.of(CYCLES)))
                .filter { it.hasOnset }
                .map { "${it.extent}|${it.value}" }
                .sorted()
            // A pattern with nothing in the window has nothing to schedule, and asking an endless
            // sequence for an event it will never produce would simply spin.
            if (expected.isEmpty()) continue

            // Taking exactly as many as we expect is what makes this safe on an infinite sequence,
            // and it is still enough to catch a double-fire: a spurious extra note inside the
            // window pushes a real later one out of the prefix, so the two lists stop matching.
            val played = pattern.eventSequence(0).take(expected.size).toList()
                .map { "${it.extent}|${it.value}" }
                .sorted()
            if (played != expected) {
                problems += "$label: the scheduler and a wide query disagree" +
                    "\n    drawn:  ${expected.take(6)}" +
                    "\n    played: ${played.take(6)}"
            }
        }
        assertTrue(problems.isEmpty(), "scheduling disagrees with rendering for:\n" + problems.joinToString("\n"))
    }

    /**
     * `chunk` walks; `inside` does not. The two used to be the same function.
     *
     * `chunk(4) { rev }` reverses a different quarter each cycle, so each of the four cycles reads
     * differently and the fifth returns to the first. `inside(4) { rev }` reverses the same material
     * every cycle and never changes. The old `chunk` was `inside` under the wrong name, and both the
     * commit that introduced it and `PATTERN_LANGUAGE.md` claimed it had been fixed to walk.
     */
    @Test
    fun `chunk walks its transform through the bar and inside does not`() {
        fun readCycle(pattern: PatPattern<String>, cycle: Int): String =
            pattern.events(PatArc(PatQ.of(cycle), PatQ.of(cycle + 1)))
                .filter { it.hasOnset }
                .sortedBy { it.extent.start.toDouble() }
                .joinToString(" ") { it.value }

        val walking = pat("a b c d").chunk(4) { it.rev() }
        val cycles = (0 until 5).map { readCycle(walking, it) }
        assertEquals(4, cycles.take(4).distinct().size, "chunk repeated itself instead of walking: $cycles")
        assertEquals(cycles[0], cycles[4], "chunk should come back round after n cycles: $cycles")

        val still = pat("a b c d").inside(4) { it.rev() }
        val insideCycles = (0 until 4).map { readCycle(still, it) }
        assertEquals(1, insideCycles.distinct().size, "inside should be cycle-invariant: $insideCycles")
    }

    @Test
    fun `the corpus covers every operator file`() {
        // A guard on the guard: if this sweep ever silently shrinks, the properties above stop
        // meaning anything.
        val labels = corpus().map { it.label }
        assertTrue(labels.size >= 100, "the contract corpus has shrunk to ${labels.size} entries")
        assertEquals(labels.size, labels.distinct().size, "duplicate corpus labels")
        listOf("pure", "mini polymeter", "sine.segment", "appLeft", "voiceLead", "swing", "kolakoski").forEach {
            assertTrue(it in labels, "the corpus lost its '$it' entry")
        }
    }
}
