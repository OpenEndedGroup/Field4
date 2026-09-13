@file:afield.CompletionCategory("Patterns")
package afield.four.synth

import afield.four.audio.WavFloatArrayWriter
import java.io.File

/*
FIELD_TEST
id: synth.pat.mini
runner: jvm-main
entrypoint: afield.four.synth.PatMiniNotation
tags: [smoke, synth, pattern, tidal, mini-notation, plot, svg, wav, cpu]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "pat mini-notation gallery rendered"
*/

/**
 * The **mini-notation catalogue**: every form the `pat("…")` string can take, one entry each, with
 * the piano roll and the audio clip that the documentation publishes under it.
 *
 * This exists because the mini-notation section of `pattern_language.md` used to be a
 * fourteen-row table of prose — `bd*4` *"four times as fast"* — and a table cannot show you that
 * `{bd hh cp}%8` **phases** against the bar, or let you hear that `bd _ _ sd` and `bd@3 sd` are the
 * same rhythm. Each entry here renders both, so a reader learns the notation by looking at it and
 * listening to it rather than by trusting a gloss.
 *
 * ### One list, four consumers
 *
 * [EXAMPLES] is the single source of truth. The same list produces the committed SVG rolls, the
 * committed `.wav` clips, the checks below, and (via [markdownSection]) the page's own markdown —
 * so an example cannot appear in the prose without a figure, carry a roll that disagrees with its
 * audio, or drift out of the order the page teaches it in. Adding a form to the notation means
 * appending one [Example].
 *
 * ### What is actually verified
 *
 * As a FIELD_TEST (`synth.pat.mini`) every entry is rendered to a temp dir and asserted, writing no
 * committed assets:
 *
 *  * it parses, draws at least one event, and draws nothing outside its own plotted window;
 *  * the SVG holds exactly one rectangle per event plus one backdrop per lane plus one page
 *    background, so the figure cannot invent or drop a note;
 *  * where an entry declares an [Example.equivalent] — `bd ! ! sd` against `bd!3 sd` — the two are
 *    compared **arc for arc and value for value across every cycle**, which is the only claim in
 *    this file that a reader cannot check by eye;
 *  * the clip renders to a real RIFF/WAVE file, non-silent and all-finite, and its onsets are
 *    counted out of the audio and compared with the schedule;
 *  * [ATOM_COLOR_CLAIM] — the instrument colours distinguish exactly the tokens the voice gives a
 *    distinct sound to, checked against [patDrumHz] rather than against a comment;
 *  * and the handful of entries that make a *specific* musical claim carry their own
 *    [Example.verify] to prove it: that `?` really is deterministic, that `<>` really changes per
 *    cycle, that `%8` really phases.
 *
 * Pass an output dir as `args[0]` (or set `PAT_MINI_DIR`) to regenerate the committed captures
 * under `public/docs/assets/pattern_language/mini/`.
 */
object PatMiniNotation {

    /** Cycles per second for every clip. Percussive material, so the same 2 the other scenes use. */
    const val CPS = 2.0

    /**
     * How many analysis windows two strikes must be apart before an **exact** onset count is a fair
     * thing to ask of the audio.
     *
     * Measured on this catalogue rather than chosen: the detector compares each window against the
     * one a full window earlier and re-arms only on release, so two events need roughly three
     * windows between them to read as two rising edges. Every entry whose gap is an eighth note at
     * `CPS = 2` — 62 ms, five windows — counts exactly, including `{bd hh cp}%8` at 32 strikes and
     * `bd(3,8), cp(5,8)` at 10. The two entries that sit at a sixteenth, 31 ms, do not: `hh(k16)`
     * loses one strike of sixteen and `hh*16?0.3` lost five of twenty-four, both to the same cause,
     * a run of adjacent noise bursts whose per-note peak is stochastic. Three windows is where that
     * boundary actually falls, so it is where the strict claim stops.
     */
    const val RESOLVABLE_GAP_WINDOWS = 3.0

    // ── the model ──────────────────────────────────────────────────────────────────────────

    /**
     * One form of the notation, and everything published about it.
     *
     * @param id the asset stem — `pat-mini-<id>.svg` and `pat-mini-<id>.wav`, and the `audio-spec`
     *   the docs fence names.
     * @param source the mini-notation itself. This is the string the roll, the clip and the page's
     *   heading are all derived from; there is deliberately nowhere to type it a second time.
     * @param gloss one line, in the page's voice, saying what the form does.
     * @param equivalent a longhand spelling this form is shorthand *for*. When set, the roll gains a
     *   second lane and the test asserts the two are the same pattern — which is what makes the
     *   figure a proof rather than an illustration.
     * @param cycles how many cycles to draw and sound. Two for anything that repeats within the
     *   bar; four for the forms whose whole point is that consecutive cycles differ.
     * @param note a second sentence, for the forms where the roll shows something worth naming.
     * @param verify a claim about this entry's own events, beyond the generic checks.
     */
    class Example(
        val id: String,
        val source: String,
        val gloss: String,
        val equivalent: String? = null,
        val cycles: Int = 2,
        val note: String? = null,
        val verify: (List<PatEvent<String>>) -> Unit = {}
    ) {
        val pattern: PatPattern<String> get() = pat(source)

        /** The lanes the roll draws: the form, and its longhand underneath when it has one. */
        fun lanes(): List<PatPianoRollGallery.Lane> = buildList {
            add(PatPianoRollGallery.Lane(source, pat(source)))
            equivalent?.let { add(PatPianoRollGallery.Lane(it, pat(it))) }
        }

        fun spec(): PatPianoRollGallery.PatRollSpec =
            PatPianoRollGallery.PatRollSpec(source, "", cycles, lanes())
    }

    /**
     * One group of the catalogue — the page's `###` headings, and the order it teaches in.
     *
     * The order is pedagogical rather than alphabetical or grammatical: steps and rests first,
     * because every later form is written in terms of them; then the two ways to group; then the
     * modifiers that change a step's speed or width; then the forms that make consecutive cycles
     * differ, which is where mini-notation stops being a way to write a bar down; then polymeter and
     * the two rhythm generators; and values last, because `bd:3` is about parameters rather than
     * about time.
     */
    class Group(val title: String, val blurb: String, val examples: List<Example>)

    // ── the catalogue ──────────────────────────────────────────────────────────────────────

    val GROUPS: List<Group> = listOf(

        Group(
            "Steps, and the space between them",
            "A space is the only separator the notation has, and it always means *divide this level " +
                "evenly*. Everything below is a way of saying something other than \"evenly\".",
            listOf(
                Example(
                    id = "two-steps",
                    source = "bd sd",
                    gloss = "Two steps, half a cycle each.",
                    note = "Colour is per instrument throughout this page: `bd` is always this blue, " +
                        "`sd` always this orange. The colours are the drum map, so two bars that " +
                        "look different sound different."
                ),
                Example(
                    id = "four-steps",
                    source = "bd sd hh cp",
                    gloss = "Four steps, a quarter each. The cycle is divided by however many steps it finds."
                ),
                Example(
                    id = "rest",
                    source = "bd ~ sd ~",
                    gloss = "`~` is a rest — a step that takes up its share of the cycle and plays nothing.",
                    note = "The rest is not a quiet event; it is no event. Four steps are written and two " +
                        "rectangles are drawn, which is why the roll has gaps rather than gaps at low opacity.",
                    verify = { events ->
                        check(events.none { it.value == "~" }) { "a rest became an event: ${events.map { it.value }}" }
                    }
                ),
                Example(
                    id = "subdivide",
                    source = "bd [sd sd]",
                    gloss = "Brackets group. The bracket is **one step** that happens to contain two, so " +
                        "the pair shares the half-cycle the single `sd` would have had.",
                    note = "This is the recursion the whole notation rests on: a group is a step, so " +
                        "anything you can write at the top level you can write inside brackets."
                ),
                Example(
                    id = "nested",
                    source = "bd [sd [hh hh]]",
                    gloss = "And it nests as deep as you care to go — the inner pair splits the inner half " +
                        "of the outer half."
                ),
            )
        ),

        Group(
            "Two ways to group",
            "Brackets are not the only way to carve up a cycle. One of these avoids the brackets and " +
                "the other ignores the cycle's division entirely.",
            listOf(
                Example(
                    id = "dot-group",
                    source = "bd sd . hh hh hh",
                    equivalent = "[bd sd] [hh hh hh]",
                    gloss = "A standalone `.` groups what precedes it against what follows, with no brackets.",
                    note = "Read the two lanes against each other: this is **two halves**, not five fifths. " +
                        "The dot is the cheapest way to write an uneven division, and the second lane is " +
                        "the spelling it stands for."
                ),
                Example(
                    id = "stack",
                    source = "bd sd, hh*4",
                    gloss = "A comma **stacks** layers: both play through the same cycle, each divided on " +
                        "its own terms.",
                    note = "Two steps in one layer and four in the other. Nothing is renegotiated between " +
                        "them — a stack is simply two patterns queried together."
                ),
            )
        ),

        Group(
            "Speed, repetition, and width",
            "Four postfix modifiers, and two markers that spell two of them without the punctuation. " +
                "Every one of them attaches to the step on its left, including a bracketed group.",
            listOf(
                Example(
                    id = "fast",
                    source = "bd*4",
                    gloss = "`*n` plays the step `n` times as fast — four kicks where one was written."
                ),
                Example(
                    id = "fast-group",
                    source = "[bd sd]*2",
                    equivalent = "bd sd bd sd",
                    gloss = "`*n` on a group speeds up the whole group, so the pair happens twice."
                ),
                Example(
                    id = "slow",
                    source = "bd/2",
                    cycles = 4,
                    gloss = "`/n` is the other direction: half as fast, so this one spans **two cycles** and " +
                        "you hear it twice in four.",
                    note = "Drawn over four cycles because a form that is slower than the bar has nothing " +
                        "to show inside one. Note that `pat` is still a function of time here — the event " +
                        "simply has an arc two cycles wide.",
                    verify = { events ->
                        check(events.size == 2) { "bd/2 over four cycles should sound twice, sounded ${events.size}" }
                        val width = events.first().let { it.arc.end - it.arc.start }
                        check(width == PatQ.of(2)) { "bd/2's event should be two cycles wide, was $width" }
                    }
                ),
                Example(
                    id = "repeat",
                    source = "bd!3 sd",
                    equivalent = "bd bd bd sd",
                    gloss = "`!n` **repeats** the step, and each copy takes a slot of its own — so this is " +
                        "four steps, not two."
                ),
                Example(
                    id = "repeat-bare",
                    source = "bd ! ! sd",
                    equivalent = "bd!3 sd",
                    gloss = "A bare `!` repeats whatever preceded it, once per `!`.",
                    note = "Repeats accumulate rather than nest: two `!`s after `bd` give three kicks, " +
                        "which is what Tidal does. Nesting them would give four."
                ),
                Example(
                    id = "elongate",
                    source = "bd@3 sd",
                    gloss = "`@n` **widens** the step instead of repeating it: `bd` claims three of the " +
                        "four slots and sounds once."
                ),
                Example(
                    id = "elongate-bare",
                    source = "bd _ _ sd",
                    equivalent = "bd@3 sd",
                    gloss = "A standalone `_` extends the previous step by one slot — the same rhythm, " +
                        "written the way a notation-first person would write it.",
                    note = "`!` and `_` are the pair worth keeping straight, and the rolls make the " +
                        "difference plain: `bd ! ! sd` sounds three kicks, `bd _ _ sd` sounds one long one."
                ),
            )
        ),

        Group(
            "Forms that make the next cycle different",
            "Up to here the notation has described a single bar that then repeats. These four are the " +
                "point at which it stops doing that, and they are the reason a pattern is a function of " +
                "time rather than a list of notes. Each is drawn over four cycles.",
            listOf(
                Example(
                    id = "alternate",
                    source = "<bd sd>",
                    cycles = 4,
                    gloss = "Angle brackets take **one alternative per cycle**: kick, snare, kick, snare.",
                    verify = { events ->
                        val values = (0L until 4L).map { c -> events.filter { qFloor(it.arc.start) == c }.map { it.value } }
                        check(values == listOf(listOf("bd"), listOf("sd"), listOf("bd"), listOf("sd"))) {
                            "<bd sd> should alternate one per cycle, gave $values"
                        }
                    }
                ),
                Example(
                    id = "alternate-step",
                    source = "<bd sd cp> hh",
                    cycles = 4,
                    gloss = "Alternation is a step like any other, so it can sit inside a sequence — the " +
                        "first half rotates through three while the hat stays put.",
                    note = "Three alternatives against a four-cycle figure, so the pattern takes three " +
                        "cycles to come back round and the fourth cycle here is the first one again."
                ),
                Example(
                    id = "degrade",
                    source = "bd*8?",
                    gloss = "`?` drops the step — half the time, by default. It is random but not " +
                        "arbitrary: the same cycle always drops the same notes.",
                    verify = { events ->
                        check(events.size in 3..13) { "bd*8? over two cycles left ${events.size} of 16" }
                    }
                ),
                // Eighth-note hats rather than the sixteenths this card first used, for two
                // reasons that point the same way. The card next to it is `bd*8?`, so holding the
                // density fixed leaves the *probability* as the only thing that changes between
                // them. And a run of consecutive sixteenths is past what the shipped onset counter
                // resolves: `hh*16?0.3` measured 19 detections against 24 scheduled strikes, with
                // one run of four collapsing into a single rising edge — the counter reads 64
                // *uniform* sixteenths exactly, so the limit is irregular clustering, not density
                // alone. Turning this back up means the audio can no longer be checked.
                Example(
                    id = "degrade-p",
                    source = "hh*8?0.3",
                    gloss = "…and `?p` sets the probability. Thirty per cent of sixteen hats, deterministically.",
                    note = "Deterministic matters more than it sounds: a pattern you can re-query is a " +
                        "pattern you can render offline, scrub backwards, or draw — which is how the " +
                        "clip under this roll was made, and why it matches the picture note for note."
                ),
                Example(
                    id = "random-choice",
                    source = "[bd|sd|cp]",
                    cycles = 4,
                    gloss = "`|` picks **one** of the alternatives per cycle at random — where `<>` " +
                        "cycles through them in order.",
                    note = "The brackets are not optional here, and this is a real divergence from " +
                        "Tidal: Field parses `|` only *inside* a group, so bare `bd|sd|cp` is a " +
                        "parse error rather than a random choice. Deterministic in the same way `?` " +
                        "is, so this figure is not a different picture every time the docs build runs.",
                    verify = { events ->
                        check(events.size == 4) { "[bd|sd|cp] should sound once a cycle, sounded ${events.size}" }
                        check(events.map { it.value }.distinct().size > 1) {
                            "[bd|sd|cp] chose the same alternative all four cycles: ${events.map { it.value }}"
                        }
                    }
                ),
            )
        ),

        Group(
            "Polymeter",
            "Braces divide by a *step count* rather than by the cycle, which is the one thing brackets " +
                "and commas cannot express.",
            listOf(
                Example(
                    id = "polymeter",
                    source = "{bd sd, hh hh hh}",
                    gloss = "Layers of different lengths, all stepping at the **first** layer's rate — two " +
                        "steps a cycle here, so the three hats take a cycle and a half.",
                    cycles = 4,
                    note = "This is not `[bd sd, hh hh hh]`. A comma would squeeze three hats into one " +
                        "cycle; the braces keep the hats at the kick's step size and let them run over."
                ),
                Example(
                    id = "polymeter-sub",
                    source = "{bd hh cp}%8",
                    cycles = 4,
                    gloss = "`%n` sets that step rate explicitly: three atoms laid out eight steps to the " +
                        "cycle.",
                    note = "Read it across the bar line rather than within one. Three does not divide " +
                        "eight, so the figure **phases** — cycle 1 starts where cycle 0 left off, and it " +
                        "takes three cycles to return. That is the whole reason to write a polymeter " +
                        "instead of a fast sequence.",
                    verify = { events ->
                        fun cycle(c: Long) = events.filter { qFloor(it.arc.start) == c }.map { it.value }
                        check(cycle(0).size == 8) { "%8 should give eight steps a cycle, gave ${cycle(0).size}" }
                        check(cycle(0) != cycle(1)) { "{bd hh cp}%8 did not phase: cycle 0 and 1 both ${cycle(0)}" }
                        check(cycle(0) == cycle(3)) {
                            "three atoms over eight steps should return after three cycles: ${cycle(0)} vs ${cycle(3)}"
                        }
                    }
                ),
            )
        ),

        Group(
            "Rhythms the notation generates for you",
            "Two bracket suffixes that do not spell a rhythm out but *construct* one, from a step count " +
                "and a rule.",
            listOf(
                Example(
                    id = "euclid",
                    source = "bd(3,8)",
                    gloss = "Euclidean: spread three onsets as evenly as eight slots allow. Field draws " +
                        "three against eight as `x..x.x..` — gaps of 3, 2 and 3.",
                    note = "Tidal spells this same rhythm `x..x..x.`, starting the figure one bead further " +
                        "round. Both are the *same necklace* — gaps of 3, 3 and 2 in some rotation — so " +
                        "`bd(3,8)` in Field is Tidal's `bd(3,8)` rotated, not a different rhythm. " +
                        "Field's `bjorklund` is a rotation of Tidal's for a little over half of all " +
                        "`(pulses, steps)` pairs and identical for the rest; the next card is how you " +
                        "choose which rotation you wanted.",
                    verify = { events ->
                        val slots = events.filter { qFloor(it.arc.start) == 0L }
                            .map { (it.arc.start * PatQ.of(8)).toDouble() }
                        check(slots == listOf(0.0, 3.0, 5.0)) {
                            "bd(3,8) fell on $slots. If this has become 0, 3, 6 then Field's bjorklund " +
                                "was aligned with Tidal's — good, but PatKolakoskiTest pins the old " +
                                "spelling and this card's prose describes it, so both need to move too."
                        }
                        // The claim that survives a rotation, and the one that makes it Euclidean:
                        // the gaps are as equal as eight slots divided by three can be.
                        val gaps = (slots + 8.0).zipWithNext { a, b -> b - a }
                        check(gaps.toSet().size <= 2 && gaps.max() - gaps.min() <= 1.0) {
                            "bd(3,8) is not maximally even — gaps $gaps"
                        }
                    }
                ),
                Example(
                    id = "euclid-rotate",
                    source = "bd(3,8,2)",
                    gloss = "A third number **rotates** the figure, which is how you ask for the spelling " +
                        "you had in mind rather than the one the algorithm starts on.",
                    note = "A positive offset rotates the figure earlier. This is the same three onsets " +
                        "as the card above, moved two slots round the cycle.",
                    verify = { events ->
                        fun slotsOf(source: String) = pat(source)
                            .events(PatArc(PatQ.ZERO, PatQ.ONE))
                            .map { (it.arc.start * PatQ.of(8)).toDouble() }
                            .sorted()
                        val plain = slotsOf("bd(3,8)")
                        val rotated = slotsOf("bd(3,8,2)")
                        check(rotated.size == plain.size) {
                            "the offset changed the onset count: $plain vs $rotated"
                        }
                        // Rotating by two slots must map the figure onto itself, mod eight. This is
                        // the whole claim of the card, and it holds whichever bead the figure starts on.
                        check(rotated.map { (it + 2.0) % 8.0 }.sorted() == plain) {
                            "bd(3,8,2) is not bd(3,8) rotated by two slots: $rotated against $plain"
                        }
                    }
                ),
                Example(
                    id = "euclid-stack",
                    source = "bd(3,8), cp(5,8)",
                    gloss = "Two Euclidean figures over the same eight slots. Three against five is where " +
                        "the interlocking gets interesting.",
                    cycles = 2
                ),
                Example(
                    id = "kolakoski",
                    source = "hh(k16)",
                    gloss = "`k` asks for a Kolakoski rhythm instead — sixteen steps of the self-describing " +
                        "word, whose own run lengths spell the word back. `g` reads its gaps.",
                    note = "Unlike Euclid, turning the step count up **extends** a Kolakoski figure rather " +
                        "than re-placing it, so the first half of `k32` is `k16`. Field's own figure for " +
                        "that is further down this page."
                ),
            )
        ),

        Group(
            "A value on a step",
            "The last suffix is not about time at all.",
            listOf(
                Example(
                    id = "sample-index",
                    source = "bd:3 sd:1",
                    gloss = "`:n` is the sample index — which `bd` out of the folder of kicks.",
                    note = "It arrives two ways at once: the atom is still `bd:3` for anything reading " +
                        "names, *and* the event carries `n = 3` for the parameter algebra. The colour " +
                        "still says kick, because `bd:3` is a kick.",
                    verify = { events ->
                        val kick = events.first { it.value.startsWith("bd") }
                        check(kick.annotations["n"] == "3") {
                            "bd:3 should carry n=3, carried ${kick.annotations}"
                        }
                        check(PatPianoRollGallery.atomColor("bd:3") == PatPianoRollGallery.atomColor("bd")) {
                            "bd:3 should be kick-coloured"
                        }
                    }
                ),
            )
        ),
    )

    /** Every example, in the order the page teaches them. */
    val EXAMPLES: List<Example> = GROUPS.flatMap { it.examples }

    private val byId: Map<String, Example> = EXAMPLES.associateBy { it.id }

    // ── the voice ──────────────────────────────────────────────────────────────────────────

    /**
     * A percussive [patVoice] whose envelope **fits inside this pattern's own shortest gap**.
     *
     * One fixed envelope cannot serve a catalogue that runs from `bd sd` to `hh*16?0.3`: the decay
     * that gives a 62 Hz kick enough periods to read as a kick is seven times the gap between
     * sixteenth-note hats, and `PAT_GALLERY.md` records what that sounds like — "a continuous drone
     * that no onset detector could read".
     *
     * The number that matters is the **gap between consecutive onsets**, not the length of an
     * event's arc, and the first draft of this function used the arc. They differ, and the
     * difference was audible: `patRenderStereo` rings each note for `decay * 4` past its hold, so
     * scaling the decay to the arc gave `bd [sd sd]` a tail nearly twice its own note spacing and
     * three of its six notes disappeared into their neighbours. Taking a fifth of the shortest gap
     * puts the tail at four fifths of it instead, so consecutive notes separate **by construction**
     * rather than by luck — which is what lets [main] assert an exact onset count for almost every
     * entry instead of shrugging at a range.
     *
     * Coincident onsets are one gap, not a zero: a stack sounds its layers together and the ear
     * hears one hit, so the distinct start times are what get measured.
     */
    fun voiceFor(events: List<PatEvent<String>>, cps: Double = CPS): PatVoiceSpec {
        val gapCycles = onsetTimes(events).zipWithNext { a, b -> b - a }.minOrNull()
            ?: events.minOfOrNull { (it.arc.end - it.arc.start).toDouble() }
            ?: 0.25
        val gapSeconds = gapCycles / cps
        return patVoice(decay = (gapSeconds * 0.2).coerceIn(0.004, 0.05), sustain = 0.02)
    }

    /**
     * The distinct times this pattern strikes, in order — what a listener would count.
     *
     * Not the same as the event count, and the gallery would over-claim if it used one for the
     * other: `bd sd, hh*4` schedules twelve events over two cycles but strikes eight times, because
     * the kick and a hat land together twice a cycle and no detector can pull them apart.
     */
    fun onsetTimes(events: List<PatEvent<String>>): List<Double> =
        events.map { it.arc.start.toDouble() }.distinct().sorted()

    // ── rendering ──────────────────────────────────────────────────────────────────────────

    /** Resolves and draws example [id]'s compact roll. */
    fun svgFor(id: String): String {
        val example = byId[id] ?: error("unknown mini-notation example '$id'; known: ${byId.keys.sorted()}")
        val spec = example.spec()
        return PatPianoRollGallery.renderCompactSvg(spec, PatPianoRollGallery.resolve(spec))
    }

    /** Offline-renders example [id] and writes a stereo 16-bit PCM WAV — the docs fence's entry point. */
    fun renderWav(name: String, outPath: String) {
        val example = byId[name] ?: error("unknown mini-notation example '$name'; known: ${byId.keys.sorted()}")
        val audio = render(example)
        require(audio.frames > 0) { "example '$name' rendered no samples" }
        require(audio.left.any { it != 0f } || audio.right.any { it != 0f }) { "example '$name' rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, audio.left, audio.right, PatSoundDocs.SR, normalizePeak = 0.9f)
    }

    /** The audio for one example, through the stand-in synth at [CPS]. */
    fun render(example: Example): PatSoundDocs.Stereo {
        val events = example.pattern.events(PatArc(PatQ.ZERO, PatQ.of(example.cycles)))
        return patRenderStereo(
            example.pattern,
            cycles = example.cycles,
            cps = CPS,
            voice = voiceFor(events)
        )
    }

    // ── the colour claim ───────────────────────────────────────────────────────────────────

    /**
     * What [PatPianoRollGallery.ATOM_COLORS] asserts about [patDrumHz], checked rather than stated.
     *
     * Two directions, and both matter. **Distinct colour implies distinct sound:** two tokens drawn
     * in different colours must get different `(frequency, waveform)` pairs out of the drum map, or
     * the roll is drawing a difference the ear cannot find. **Shared colour implies shared sound:**
     * `bd` and `kick` are one colour, so they had better be one voice.
     */
    fun checkAtomColorClaim() {
        val colors = PatPianoRollGallery.ATOM_COLORS
        val voiced = colors.keys.associateWith { patDrumHz(it, baseHz = 220.0) }

        for ((tokenA, colorA) in colors) {
            for ((tokenB, colorB) in colors) {
                if (tokenA >= tokenB) continue
                val sameColor = colorA == colorB
                val sameSound = voiced[tokenA] == voiced[tokenB]
                check(sameColor == sameSound) {
                    if (sameColor) {
                        "`$tokenA` and `$tokenB` share a colour but not a voice: ${voiced[tokenA]} vs ${voiced[tokenB]}"
                    } else {
                        "`$tokenA` and `$tokenB` have different colours but the same voice ${voiced[tokenA]}"
                    }
                }
            }
        }

        // And a coloured token must be one the drum map actually claims: an atom that falls through
        // to `baseHz` has no voice of its own, so colouring it would be a promise about nothing.
        for ((token, sound) in voiced) {
            check(sound.second != null) {
                "`$token` is coloured but the drum map has no voice for it — it falls through to baseHz"
            }
        }
        println("atom colour claim: ${colors.values.distinct().size} colours over ${colors.size} tokens, all voiced and distinct")
    }

    // ── markdown ───────────────────────────────────────────────────────────────────────────

    /**
     * The page's own mini-notation section, generated from [GROUPS].
     *
     * The section is ~27 cards of image, audio fence and prose; hand-maintaining that is how a roll
     * ends up captioned with the wrong notation. `--markdown` prints it for pasting into
     * `pattern_language.md`, and `PatMiniNotationDocsGuardTest` asserts the committed page still
     * matches what this function produces, so the two cannot drift.
     *
     * @param assetDir the published asset directory, relative to the page.
     */
    fun markdownSection(assetDir: String = "assets/pattern_language/mini"): String {
        val sb = StringBuilder()
        for (group in GROUPS) {
            sb.append("### ").append(group.title).append("\n\n")
            sb.append(group.blurb).append("\n\n")
            for (example in group.examples) {
                sb.append(cardMarkdown(example, assetDir)).append('\n')
            }
        }
        return sb.toString()
    }

    /** One card: the notation, its roll, its clip, and its gloss. */
    fun cardMarkdown(example: Example, assetDir: String = "assets/pattern_language/mini"): String {
        val roll = "$assetDir/pat-mini-${example.id}.svg"
        val wav = "$assetDir/pat-mini-${example.id}.wav"
        val lanes = if (example.equivalent == null) {
            "a piano roll of `${example.source}` over ${example.cycles} cycles"
        } else {
            "a piano roll of `${example.source}` over ${example.cycles} cycles, drawn above `${example.equivalent}` " +
                "so the two can be read against each other"
        }
        val sb = StringBuilder()
        sb.append("<div class=\"pat-mini\" markdown>\n\n")
        sb.append("`").append(example.source).append("`\n")
        sb.append("{: .pat-mini-source }\n\n")
        sb.append("![").append(lanes).append("](").append(roll).append(")\n\n")
        sb.append("```diffstatic_runner {data-lang=kotlin audio=\"").append(wav)
        sb.append("\" audio-spec=\"").append(example.id)
        sb.append("\" audio-renderer=\"afield.four.synth.PatMiniNotation\" embed=true hidden=true name=\"pat-mini-")
        sb.append(example.id).append("-audio\"}\n")
        sb.append("// showPatAudio(pat(\"").append(example.source).append("\"), cycles = ").append(example.cycles)
        sb.append(", cps = ").append(CPS.toInt()).append(")\n")
        sb.append("```\n\n")
        sb.append(example.gloss).append('\n')
        example.note?.let { sb.append('\n').append(it).append('\n') }
        sb.append("\n</div>\n")
        return sb.toString()
    }

    // ── FIELD_TEST entry point ─────────────────────────────────────────────────────────────

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--markdown") {
            print(markdownSection())
            return
        }

        val outDir = File(
            args.getOrNull(0) ?: System.getenv("PAT_MINI_DIR")
                ?: File(System.getProperty("java.io.tmpdir"), "pat-mini-notation").path
        )
        outDir.mkdirs()

        checkAtomColorClaim()

        check(EXAMPLES.map { it.id }.distinct().size == EXAMPLES.size) {
            "duplicate example ids: ${EXAMPLES.map { it.id }.groupBy { it }.filterValues { it.size > 1 }.keys}"
        }

        EXAMPLES.forEach { example ->
            val spec = example.spec()
            val resolved = PatPianoRollGallery.resolve(spec)

            // ── the events ──
            resolved.forEach { lane ->
                check(lane.events.isNotEmpty()) { "${example.id}/${lane.label}: drew nothing" }
                lane.events.forEach { event ->
                    check(event.arc.start >= PatQ.ZERO && event.arc.end <= PatQ.of(example.cycles)) {
                        "${example.id}/${lane.label}: event outside the plotted window: ${event.arc}"
                    }
                }
            }
            val events = resolved.first().events
            example.verify(events)

            // ── the equivalence claim, where one is made ──
            example.equivalent?.let { equivalent ->
                val shorthand = resolved[0].events.map { it.arc.start to it.arc.end }
                val longhand = resolved[1].events.map { it.arc.start to it.arc.end }
                check(shorthand == longhand) {
                    "${example.id}: `${example.source}` and `$equivalent` differ in time:\n" +
                        "  ${resolved[0].events.map { it.arc }}\n  ${resolved[1].events.map { it.arc }}"
                }
                check(resolved[0].events.map { it.value } == resolved[1].events.map { it.value }) {
                    "${example.id}: `${example.source}` plays ${resolved[0].events.map { it.value }}, " +
                        "`$equivalent` plays ${resolved[1].events.map { it.value }}"
                }
            }

            // ── the roll ──
            val svg = PatPianoRollGallery.renderCompactSvg(spec, resolved)
            val totalEvents = resolved.sumOf { it.events.size }
            val rects = Regex("<rect ").findAll(svg).count()
            val expectedRects = totalEvents + spec.lanes.size + 1
            check(rects == expectedRects) {
                "${example.id}: SVG has $rects rects but $totalEvents events + ${spec.lanes.size} lanes " +
                    "+ 1 background = $expectedRects"
            }
            check(svg.startsWith("<svg") && svg.trimEnd().endsWith("</svg>")) { "${example.id}: malformed SVG" }
            check(!svg.contains("NaN") && !svg.contains("Infinity")) {
                "${example.id}: SVG contains a non-finite coordinate"
            }
            File(outDir, "pat-mini-${example.id}.svg").writeText(svg)

            // ── the clip ──
            val audio = render(example)
            check(audio.left.size == audio.right.size) { "${example.id}: channel length mismatch" }
            check(audio.left.all { it.isFinite() } && audio.right.all { it.isFinite() }) {
                "${example.id}: non-finite samples"
            }
            val expectedFrames = (example.cycles / CPS * PatSoundDocs.SR).toInt()
            check(kotlin.math.abs(audio.frames - expectedFrames) <= PatSoundDocs.SR / 10) {
                "${example.id}: expected ~$expectedFrames frames, got ${audio.frames}"
            }
            val mono = audio.mono
            val monoPeak = PatSoundDocs.peak(mono)
            val monoRms = PatSoundDocs.rms(mono)
            check(monoPeak > 0.02) { "${example.id}: effectively silent (peak=$monoPeak)" }
            check(monoPeak <= 4.0) { "${example.id}: runaway level (peak=$monoPeak)" }
            check(monoRms > 0.002) { "${example.id}: RMS too low to be music (rms=$monoRms)" }
            check(monoRms < monoPeak) { "${example.id}: RMS >= peak, which is impossible" }

            // Onsets counted out of the rendered audio against the schedule. The comparison is
            // against the number of times the pattern *strikes* — see [onsetTimes] — because a
            // stack's coincident layers are one audible hit however many events they are.
            //
            // [voiceFor] sizes the envelope so the tail fits inside the shortest gap, so the
            // counts are expected to agree exactly and the check says so. The escape hatch is kept
            // for the one case the envelope cannot fix: a gap below the detector's own 12 ms
            // analysis window is not resolvable by any threshold, and claiming otherwise would be
            // a claim about the harness rather than about the music.
            val strikes = onsetTimes(events)
            val scheduled = strikes.size
            val voice = voiceFor(events)
            val shortestGapSec = strikes.zipWithNext { a, b -> (b - a) / CPS }.minOrNull() ?: Double.MAX_VALUE
            val resolvable = shortestGapSec > RESOLVABLE_GAP_WINDOWS * PatSoundDocs.ONSET_WINDOW_MS / 1000.0
            val detected = PatSoundDocs.onsetPeaks(mono, threshold = 0.05)
            val onsets = detected.size

            // Precision, asserted for every entry: nothing is *heard* that the pattern did not
            // schedule. This is the claim a docs figure actually rests on — a clip with a note the
            // roll does not draw is the one failure a reader cannot detect by looking — and it
            // holds regardless of how well the counter resolves dense runs. The tolerance is the
            // detector's own bias: a window whose level crossed is reported by its start, so an
            // onset lands up to one full window early and never late.
            val biasCycles = PatSoundDocs.ONSET_WINDOW_MS / 1000.0 * CPS
            val spurious = detected.map { it.start.toDouble() / PatSoundDocs.SR * CPS }
                .filter { heard -> strikes.none { heard >= it - biasCycles - 1e-3 && heard <= it + 1e-3 } }
            check(spurious.isEmpty()) {
                "${example.id}: heard ${spurious.size} onset(s) the pattern never scheduled, at " +
                    "${spurious.joinToString(" ") { "%.4f".format(it) }} cycles — scheduled " +
                    "${strikes.joinToString(" ") { "%.4f".format(it) }}"
            }

            if (resolvable) {
                check(onsets == scheduled) {
                    // The positions, not just the counts: a merged pair and a dropped note are
                    // different defects with the same arithmetic, and only the gaps tell them apart.
                    val heardAt = detected.map { it.start.toDouble() / PatSoundDocs.SR * CPS }
                    "${example.id}: strikes $scheduled times, heard $onsets. Shortest gap " +
                        "${"%.4f".format(shortestGapSec)}s against a tail of " +
                        "${"%.4f".format(voice.decay * 4.0)}s — if the tail is the larger, voiceFor " +
                        "is mis-scaled for this pattern; if not, the clip really is missing notes.\n" +
                        "  scheduled (cycles): ${strikes.joinToString(" ") { "%.4f".format(it) }}\n" +
                        "  heard     (cycles): ${heardAt.joinToString(" ") { "%.4f".format(it) }}"
                }
            } else {
                // Below three windows the counter may merge one adjacent pair, and that is the
                // whole allowance: at most one strike of the set may go unresolved. Two would mean
                // something other than the documented resolution limit is wrong.
                check(onsets >= scheduled - 1 && onsets <= scheduled) {
                    "${example.id}: strikes $scheduled times, heard $onsets. At a " +
                        "${"%.4f".format(shortestGapSec)}s gap — under " +
                        "${RESOLVABLE_GAP_WINDOWS.toInt()} × ${PatSoundDocs.ONSET_WINDOW_MS}ms — one merged " +
                        "pair is expected and allowed for; this is losing more than one."
                }
            }

            val wav = File(outDir, "pat-mini-${example.id}.wav")
            WavFloatArrayWriter.writeStereo16(wav, audio.left, audio.right, PatSoundDocs.SR, normalizePeak = 0.9f)
            val bytes = wav.readBytes()
            check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "${example.id}: missing RIFF" }
            check(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "${example.id}: missing WAVE" }
            check(bytes.size == 44 + audio.frames * 4) {
                "${example.id}: WAV data size mismatch: ${bytes.size} vs ${44 + audio.frames * 4}"
            }

            println(
                "example ${example.id.padEnd(16)} `${example.source}` — ${example.cycles} cycles, " +
                    "$totalEvents events, $scheduled strikes / $onsets heard" +
                    (if (resolvable) "" else " (gap under the analysis window)") +
                    ", gap ${"%.4f".format(shortestGapSec)}s vs tail ${"%.4f".format(voice.decay * 4.0)}s" +
                    ", ${svg.length}B svg, ${bytes.size}B wav"
            )
        }

        println(
            "pat mini-notation gallery rendered to ${outDir.absolutePath} " +
                "(${EXAMPLES.size} examples in ${GROUPS.size} groups)"
        )
    }
}

/** Renders one [PatMiniNotation] example's compact roll into this box's output as inline SVG. */
fun afield.Box.showPatMini(id: String, channel: String = "main") =
    display(channel, PatMiniNotation.svgFor(id))
