@file:afield.CompletionCategory("Audio")
/**
 * Field's pattern algebra as a sequencer for Sonic Pi's engine.
 *
 * `pat("bd [hh hh] sd")` and the algebra around it (`TidalPatterns.kt`, `PatAlgebra.kt`,
 * `PatComposition.kt`) keep musical structure exact in rational cycle space and can transform it
 * live. `SonicPi.kt` owns a real Sonic Pi 5: 66 synths, 42 effects and 206 samples in the installed
 * vocabulary, and a scheduler better than Field's own. This file is how a pattern reaches that engine
 * without going through Sonic Pi's own language in a string literal, which the pattern algebra cannot
 * see into.
 *
 * There are two routes, good at different things, and both are here.
 *
 * **compile** — turn a pattern into a Sonic Pi program.
 *
 *     sonicPiProgram(
 *         "drums" to pat("bd [hh hh] sd hh"),
 *         "bass" to pat("<e1 g1> ~ e2 ~").every(4) { it.rev() } on "tb303",
 *         cps = 0.5,
 *         cycles = 4
 *     ).source
 *
 * gives you a `live_loop` per layer, with `sleep`s that are the pattern's own rational cycle
 * positions. The result is ordinary Sonic Pi source: paste it into a `#sonicpi` region and edit it by
 * hand, or hand it to [Box.sonicPiPattern] and hear it. Field's clock is not involved once it is
 * running — Sonic Pi's scheduler is, which is the accurate one.
 *
 * The tempo mapping that makes this readable is [SonicPiProgram.bpm]: `use_bpm 60 * cps` makes one Sonic
 * Pi *beat* exactly one Field *cycle*, so every `sleep` in the emitted program is literally the cycle
 * fraction the pattern computed. A third stays a third — `Rational(1, 3)` is emitted where a decimal
 * would round, and a decimal where it would not.
 *
 * **drive** — Field's clock sequences, Sonic Pi sounds.
 *
 *     pat("bd*4, [~ cp]*2").sonicPiLive(cps = 0.5)
 *
 * One `/run-code` per event, dispatched by the same `PatResponseSpec` machinery that drives every
 * other live pattern in Field, so `.every`, `.degradeBy` and a signal on `gain(...)` stay live while
 * you edit. Costs a network hop of jitter per note; buys you a pattern you can change mid-bar.
 *
 * Both return something a box can be running: compile returns a [SonicPiLaunch], drive returns a
 * `PatResponseSpec`. Either way the box stays lit while the music plays and stopping the box stops it.
 *
 * **Atoms become instruments** through [SonicPiInstruments], which resolves in this order: an explicit
 * override, then a real sample name, then a real synth name, then a note (`c4`, `e2`, `60`), then the
 * drum-machine aliases (`bd`, `sd`, `hh`, `cp`, …). "Real" means *in the installed vocabulary* —
 * `sonicpi-vocabulary.json`, extracted from the app by `tools/generate_sonic_pi_vocabulary.py`. An atom
 * that resolves to nothing is reported in [SonicPiProgram.unresolved] rather than dropped, and a
 * mistyped alias is a test failure rather than silence (`synth.sonic-pi.patterns`).
 *
 * **Parameters** come from `PatAlgebra`'s annotations, so `with(gain(sine().range(0.2, 0.8)))` works
 * unchanged. Three of them are not pass-throughs and the difference matters:
 *
 * - `gain` → `amp:`, unchanged.
 * - `pan` → `pan:`, rescaled: Field's pan is 0..1, Sonic Pi's is -1..1.
 * - `cutoff` → `cutoff:` (synths) or `lpf:` (samples), converted: Field's cutoff is **Hz**, Sonic Pi's
 *   is a **MIDI note number**.
 *
 * Any other annotation is passed through only if the vocabulary says the target accepts it — `cutoff`
 * on `:beep` is not a Sonic Pi opt, so it is dropped and recorded in [SonicPiProgram.droppedOpts]
 * instead of being sent for Sonic Pi to ignore.
 *
 * Notes are emitted as MIDI numbers, not as `:c4`. Field's `patNoteNumber` defaults a bare `c` to
 * octave 5 and Sonic Pi's `:c` is octave 4; emitting the number the pattern actually meant removes a
 * whole class of off-by-an-octave that neither side would report.
 */

package afield.four.synth

import afield.Box
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.ln
import kotlin.math.roundToInt

// ── the installed vocabulary ───────────────────────────────────────────────────────────────────

/**
 * What the installed Sonic Pi can actually be asked to do.
 *
 * Read from the same `sonicpi-vocabulary.json` the editor completes against, via
 * [SonicPiEditorExtension.vocabularyJson], so there is one extracted copy rather than a second
 * hand-written one that rots at the next Sonic Pi release. Everything this file emits is checked
 * against it before it goes out.
 */
object SonicPiVocabulary {

    private val root by lazy {
        ObjectMapper().readTree(SonicPiEditorExtension.vocabularyJson())
    }

    /** The Sonic Pi the vocabulary was extracted from. */
    val version: String by lazy { root["sonicPiVersion"]?.asText() ?: "unknown" }

    val synths: Set<String> by lazy {
        root["synths"].mapNotNull { it["name"]?.asText() }.toSet()
    }

    val fx: Set<String> by lazy {
        root["fx"].mapNotNull { it["name"]?.asText() }.toSet()
    }

    /** Sample group name (`bd`, `drum`, `loop`, …) to the samples in it. */
    val sampleGroups: Map<String, List<String>> by lazy {
        root["sampleGroups"].properties().associate { (group, node) ->
            group to (node["samples"]?.map { it.asText() } ?: emptyList())
        }
    }

    val samples: Set<String> by lazy { sampleGroups.values.flatten().toSet() }

    private val synthOptsByName: Map<String, Set<String>> by lazy {
        root["synths"].associate { node ->
            (node["name"]?.asText() ?: "") to (node["opts"]?.map { it.asText() }?.toSet() ?: emptySet())
        }
    }

    private val fnOptsByName: Map<String, Set<String>> by lazy {
        root["fns"].associate { node ->
            (node["name"]?.asText() ?: "") to (node["opts"]?.map { it.asText() }?.toSet() ?: emptySet())
        }
    }

    /** The opts `sample` accepts — `amp`, `rate`, `lpf`, `hpf`, `beat_stretch`, and forty more. */
    val sampleOpts: Set<String> by lazy { fnOptsByName["sample"] ?: emptySet() }

    /** The opts every synth accepts, whichever one is in force. */
    val commonPlayOpts: Set<String> by lazy { fnOptsByName["play"] ?: emptySet() }

    /**
     * The opts [synth] accepts.
     *
     * Per-synth, because `:tb303` has `cutoff` and `res`, `:beep` has neither, and sending
     * `cutoff:` to `:beep` is silently ignored by Sonic Pi rather than reported.
     */
    fun synthOpts(synth: String): Set<String> = synthOptsByName[synth] ?: commonPlayOpts

    private val fxOptsByName: Map<String, Set<String>> by lazy {
        root["fx"].associate { node ->
            (node["name"]?.asText() ?: "") to (node["opts"]?.map { it.asText() }?.toSet() ?: emptySet())
        }
    }

    /** The opts FX [name] accepts — `:reverb` has `room` and `damp`, `:echo` has `phase` and `decay`. */
    fun fxOpts(name: String): Set<String> = fxOptsByName[name] ?: emptySet()

    fun isSample(name: String): Boolean = name in samples
    fun isSynth(name: String): Boolean = name in synths
    fun isFx(name: String): Boolean = name in fx

    /** A one-line description, for a box that wants to prove the vocabulary loaded. */
    override fun toString(): String =
        "SonicPiVocabulary(version=$version, synths=${synths.size}, fx=${fx.size}, " +
            "samples=${samples.size} in ${sampleGroups.size} groups)"
}

// ── atoms become instruments ───────────────────────────────────────────────────────────────────

/** What one pattern atom turns into when Sonic Pi plays it. */
sealed class SonicPiVoice {

    /** `sample :bd_haus` — a name from the installed sample library. */
    data class Sample(val name: String) : SonicPiVoice() {
        override fun toString() = "sample :$name"
    }

    /**
     * `play 60` — a MIDI note, optionally after switching synth.
     *
     * [synth] `null` means "whatever synth is in force", which is what makes `use_synth` at the top
     * of a `live_loop` do the obvious thing.
     */
    data class Note(val note: Int, val synth: String? = null) : SonicPiVoice() {
        override fun toString() = if (synth == null) "play $note" else "play $note on :$synth"
    }

    /** Nothing to play. Distinct from *unresolved*: this atom is a rest, and says so. */
    object Silent : SonicPiVoice() {
        override fun toString() = "silent"
    }
}

/**
 * The drum-machine names, resolved to real samples in the installed library.
 *
 * These are the Tidal/Dirt-ish shorthand that anyone writing `pat("bd sd")` expects to hear, and
 * every target here is asserted to be in `sonicpi-vocabulary.json` by `synth.sonic-pi.patterns` —
 * a typo becomes a failing test rather than a `live_loop` that plays two of its four voices.
 */
val SONIC_PI_DRUM_ALIASES: Map<String, SonicPiVoice> = buildMap {
    fun s(alias: String, sample: String) = put(alias, SonicPiVoice.Sample(sample))

    s("bd", "bd_haus"); s("kick", "bd_haus"); s("bass drum", "bd_haus")
    s("bd808", "bd_808"); s("808", "bd_808")
    s("sd", "sn_dolf"); s("sn", "sn_dolf"); s("snare", "sn_dolf")
    s("sd2", "drum_snare_hard")
    s("hh", "drum_cymbal_closed"); s("ch", "drum_cymbal_closed"); s("hat", "drum_cymbal_closed")
    s("oh", "drum_cymbal_open"); s("openhat", "drum_cymbal_open")
    s("pedal", "drum_cymbal_pedal")
    s("cp", "perc_snap"); s("clap", "perc_snap")
    s("rim", "perc_snap2")
    s("cr", "drum_splash_hard"); s("crash", "drum_splash_hard")
    s("rd", "ride_tri"); s("ride", "ride_tri")
    s("lt", "drum_tom_lo_hard"); s("mt", "drum_tom_mid_hard"); s("ht", "drum_tom_hi_hard")
    s("cb", "drum_cowbell"); s("cowbell", "drum_cowbell")
    s("tabla", "tabla_ghe1")
    s("amen", "loop_amen"); s("break", "loop_breakbeat")
    s("vinyl", "vinyl_hiss"); s("scratch", "vinyl_scratch")
    s("glitch", "glitch_perc1")
    s("choir", "ambi_choir"); s("drone", "ambi_drone")
    s("blip", "elec_blip"); s("tick", "elec_tick"); s("ping", "elec_ping"); s("pop", "elec_pop")
    s("bell", "perc_bell")

    // Two synth aliases, for the atoms people write when they mean a pitched voice rather than a
    // drum. Both play at a fixed note; use `note(...)` or a pitched atom for anything else.
    put("bass", SonicPiVoice.Note(note = 40, synth = "tb303"))
    put("lead", SonicPiVoice.Note(note = 64, synth = "prophet"))

    put("~", SonicPiVoice.Silent)
    put("-", SonicPiVoice.Silent)
}

/**
 * How an atom becomes a voice.
 *
 * Resolution is ordered, and the order is the interesting part:
 *
 * 1. [overrides] — whatever you said, without argument.
 * 2. a real sample name (`bd_haus`, `loop_amen`) — the library, exactly as installed.
 * 3. a real synth name (`tb303`, `prophet`) — played at [defaultNote].
 * 4. a note: `c4`, `ef3`, `60`, via `patNoteNumber`, played on the ambient synth.
 * 5. [aliases] — the drum shorthand.
 *
 * Samples and synths come before notes: `fm` is a synth and would otherwise be read as
 * F♯-something, and `e2` really is a note that no sample is called.
 *
 * An atom that matches nothing resolves to `null`, which becomes an entry in
 * [SonicPiProgram.unresolved]. Silently skipping it is the one behaviour that is never right — a
 * pattern that plays three of its four voices with no complaint is very hard to debug from the
 * sound.
 */
data class SonicPiInstruments(
    val overrides: Map<String, SonicPiVoice> = emptyMap(),
    val aliases: Map<String, SonicPiVoice> = SONIC_PI_DRUM_ALIASES,
    /** The synth a bare note plays on, emitted as `use_synth` at the top of each `live_loop`. */
    val defaultSynth: String = "beep",
    /** The note a bare synth-name atom plays. */
    val defaultNote: Int = 60
) {
    init {
        require(SonicPiVocabulary.isSynth(defaultSynth)) {
            "defaultSynth :$defaultSynth is not a synth in Sonic Pi ${SonicPiVocabulary.version}"
        }
    }

    /** Resolve one atom, or `null` if nothing in the installed vocabulary matches it. */
    fun resolve(atom: String): SonicPiVoice? {
        val text = atom.trim()
        if (text.isEmpty()) return SonicPiVoice.Silent

        // `bd:3` — the sample-index suffix the mini-notation allows. The index selects among a
        // group in Tidal; Sonic Pi names its samples individually, so the base name is what counts.
        val base = text.substringBefore(':').trim().ifEmpty { text }

        overrides[text]?.let { return it }
        overrides[base]?.let { return it }
        if (SonicPiVocabulary.isSample(base)) return SonicPiVoice.Sample(base)
        if (SonicPiVocabulary.isSynth(base)) return SonicPiVoice.Note(defaultNote, base)
        patNoteNumber(base)?.let { return SonicPiVoice.Note(it, null) }
        aliases[base]?.let { return it }
        return null
    }

    /** Add or replace one atom's meaning: `instruments.mapping("kick" to sonicPiSample("bd_tek"))`. */
    fun mapping(vararg pairs: Pair<String, SonicPiVoice>): SonicPiInstruments =
        copy(overrides = overrides + pairs)
}

/** `sonicPiSample("bd_tek")` — a checked sample name, for [SonicPiInstruments.mapping]. */
fun sonicPiSample(name: String): SonicPiVoice.Sample {
    require(SonicPiVocabulary.isSample(name)) {
        "'$name' is not a sample in Sonic Pi ${SonicPiVocabulary.version}"
    }
    return SonicPiVoice.Sample(name)
}

/** `sonicPiNote(60, "tb303")` — a checked synth name, for [SonicPiInstruments.mapping]. */
fun sonicPiNote(note: Int, synth: String? = null): SonicPiVoice.Note {
    if (synth != null) {
        require(SonicPiVocabulary.isSynth(synth)) {
            "'$synth' is not a synth in Sonic Pi ${SonicPiVocabulary.version}"
        }
    }
    return SonicPiVoice.Note(note, synth)
}

// ── the compiled program ───────────────────────────────────────────────────────────────────────

/** One thing Sonic Pi is asked to do, at one exact position in cycle space. */
data class SonicPiPatternEvent(
    /** The `live_loop` this belongs to. */
    val layer: String,
    /** Cycles from the start of the loop body. Exact — this is the pattern's own number. */
    val at: PatQ,
    val voice: SonicPiVoice,
    /** Rendered Sonic Pi opts, in emission order. */
    val opts: List<Pair<String, String>>,
    /** The atom this came from, for pointing back at the pattern text. */
    val atom: String
)

/** An annotation the target could not accept, kept so a dropped parameter is visible. */
data class SonicPiDroppedOpt(val layer: String, val atom: String, val key: String, val why: String)

/** An atom no part of the installed vocabulary matched. */
data class SonicPiUnresolvedAtom(val layer: String, val atom: String, val count: Int)

/**
 * A Field pattern, lowered to a Sonic Pi program.
 *
 * [source] is the whole point: ordinary Sonic Pi that a person can read, paste into a `#sonicpi`
 * region and edit. The rest is what the lowering knows and the text cannot say — where every event
 * landed in exact cycle space ([events]), what it could not resolve ([unresolved]) and what it had
 * to throw away ([droppedOpts]).
 */
data class SonicPiProgram(
    val source: String,
    val events: List<SonicPiPatternEvent>,
    val cps: Double,
    val cycles: Int,
    val layers: List<String>,
    val unresolved: List<SonicPiUnresolvedAtom> = emptyList(),
    val droppedOpts: List<SonicPiDroppedOpt> = emptyList()
) {
    /** Sonic Pi beats per minute such that one beat is one Field cycle. */
    val bpm: Double get() = 60.0 * cps

    /** Seconds per cycle, which is what [cps] means at the other boundary. */
    val secondsPerCycle: Double get() = 1.0 / cps

    /** Event onsets in seconds from the start of the loop, the way `scheduled(cps)` reports them. */
    fun onsetSeconds(): List<Double> = events.map { it.at.toDouble() * secondsPerCycle }

    override fun toString(): String = synthPrettyObject(
        this,
        "layers" to layers,
        "events" to events.size,
        "cps" to cps,
        "cycles" to cycles,
        "bpm" to bpm,
        "unresolved" to unresolved.map { it.atom },
        "droppedOpts" to droppedOpts.size
    )
}

// ── the lowering ───────────────────────────────────────────────────────────────────────────────

private const val SONIC_PI_MAX_EVENTS = 20_000

/**
 * Lower one pattern to a single-`live_loop` Sonic Pi program.
 *
 * ```
 * println(pat("bd [hh hh] sd hh").toSonicPi(cps = 0.5))
 * ```
 *
 * [cycles] is the length of the emitted loop in cycles, and it is the one parameter that needs
 * thought: a pattern that varies per cycle (`<a b>`, `.every(4)`, `.iter`) needs a loop long enough
 * to contain the variation, or the emitted program plays cycle 0 forever. Four is a common answer;
 * `pat("<a b c>")` wants three.
 */
fun PatPattern<String>.toSonicPi(
    cps: Number = 1.0,
    cycles: Int = 1,
    name: String = "field",
    instruments: SonicPiInstruments = SonicPiInstruments()
): String = sonicPiProgram(name to this, cps = cps, cycles = cycles, instruments = instruments).source

/**
 * Lower one pattern and keep everything the lowering learned — see [SonicPiProgram].
 *
 * Use this over [toSonicPi] when you want to check [SonicPiProgram.unresolved] before you play it.
 */
fun PatPattern<String>.toSonicPiProgram(
    cps: Number = 1.0,
    cycles: Int = 1,
    name: String = "field",
    instruments: SonicPiInstruments = SonicPiInstruments()
): SonicPiProgram = sonicPiProgram(name to this, cps = cps, cycles = cycles, instruments = instruments)

/**
 * Lower several named patterns to one Sonic Pi program, a `live_loop` per layer.
 *
 * ```
 * val program = sonicPiProgram(
 *     "drums" to pat("bd [hh hh] sd hh"),
 *     "bass" to pat("<e1 g1> ~ e2 ~") with gain(0.6),
 *     cps = 0.5,
 *     cycles = 4
 * )
 * ```
 *
 * Layers rather than [stack] because `stack` merges its arguments into one pattern and there is no
 * way back out: the emitted program should have the shape the person wrote, and a `live_loop` per
 * voice is both what Sonic Pi is good at and what is editable afterwards. `stack` still works
 * inside a layer — it just lands as several notes at the same `sleep` position, which is right.
 *
 * Name a layer's synth with [on] when its notes should not play on the program's default one —
 * `"bass" to pat("e1 ~ e2 ~") on "tb303"` — which is also what makes `cutoff` reach it, since
 * `:beep` has no such opt and the lowering will not send one that the target does not accept.
 */
fun sonicPiProgram(
    vararg layers: Pair<String, PatPattern<String>>,
    cps: Number = 1.0,
    cycles: Int = 1,
    instruments: SonicPiInstruments = SonicPiInstruments()
): SonicPiProgram = sonicPiProgram(
    layers = layers.map { SonicPiLayer(it.first, it.second) }.toTypedArray(),
    cps = cps,
    cycles = cycles,
    instruments = instruments
)

/**
 * One `live_loop`: a name, a pattern, and optionally its own instrument set.
 *
 * The per-layer [instruments] is the whole reason this exists rather than a `Pair`. Drums want
 * samples and a bass line wants `:tb303`, and `use_synth` is emitted per `live_loop` anyway, so
 * "which synth" is naturally a property of the layer rather than of the program.
 */
data class SonicPiLayer(
    val name: String,
    val pattern: PatPattern<String>,
    val instruments: SonicPiInstruments? = null,
    /** FX to wrap this loop's body in, outermost first. See [sonicPiFx]. */
    val fx: List<SonicPiFx> = emptyList()
)

/**
 * One `with_fx` around a layer's body.
 *
 * Build these with [sonicPiFx] rather than the constructor: the name and *every opt* are checked
 * against the installed vocabulary there, and Sonic Pi's response to `with_fx :revreb` is a runtime
 * error on the loop's own thread minutes into a performance — the exact failure `Box.sonicPi`'s
 * error reporting exists to make visible, and which is nicer still not to have.
 */
data class SonicPiFx(val name: String, val opts: Map<String, Double> = emptyMap()) {
    override fun toString(): String =
        if (opts.isEmpty()) "with_fx :$name"
        else "with_fx :$name, " + opts.entries.joinToString(", ") { "${it.key}: ${it.value}" }
}

/**
 * A checked `with_fx`: `sonicPiFx("reverb", "room" to 0.8, "mix" to 0.4)`.
 *
 * ```
 * sonicPiLayer("bass", pat("e3 ~ e4 ~"), synth = "tb303", fx = listOf(sonicPiFx("reverb", "room" to 0.6)))
 * ```
 */
fun sonicPiFx(name: String, vararg opts: Pair<String, Number>): SonicPiFx {
    require(SonicPiVocabulary.isFx(name)) {
        "'$name' is not an FX in Sonic Pi ${SonicPiVocabulary.version}"
    }
    val accepted = SonicPiVocabulary.fxOpts(name)
    for ((key, _) in opts) {
        require(key in accepted) {
            "with_fx :$name has no opt '$key' — it accepts ${accepted.sorted()}"
        }
    }
    return SonicPiFx(name, opts.associate { it.first to it.second.toDouble() })
}

/** `pat("bd sd") on "beep" through sonicPiFx("echo")` — add FX to a layer. */
infix fun SonicPiLayer.through(effect: SonicPiFx): SonicPiLayer = copy(fx = fx + effect)

/**
 * A layer, spelled out.
 *
 * Kotlin will not mix a `Pair` and a [SonicPiLayer] in one `vararg`, so a program in which *any*
 * layer names its own synth has to be written entirely in one form or the other. This is that form:
 *
 * ```
 * sonicPiProgram(
 *     sonicPiLayer("drums", pat("bd ~ [bd bd bd] bd")),
 *     sonicPiLayer("bass", pat("<e1 g1> ~ e2 ~") with cutoff(700.0), synth = "tb303"),
 *     cps = 0.5
 * )
 * ```
 */
fun sonicPiLayer(
    name: String,
    pattern: PatPattern<String>,
    synth: String? = null,
    instruments: SonicPiInstruments? = null,
    fx: List<SonicPiFx> = emptyList()
): SonicPiLayer = SonicPiLayer(
    name = name,
    pattern = pattern,
    instruments = instruments ?: synth?.let { SonicPiInstruments(defaultSynth = it) },
    fx = fx
)

/** `"bass" to pat("e1 ~ e2 ~") on "tb303"` — this layer's notes play on [synth]. */
infix fun Pair<String, PatPattern<String>>.on(synth: String): SonicPiLayer =
    SonicPiLayer(first, second, SonicPiInstruments(defaultSynth = synth))

/** `"drums" to pat("bd sd") using myKit` — this layer resolves its atoms with [instruments]. */
infix fun Pair<String, PatPattern<String>>.using(instruments: SonicPiInstruments): SonicPiLayer =
    SonicPiLayer(first, second, instruments)

/** Lower named layers, each free to name its own synth. See [sonicPiProgram] and [on]. */
fun sonicPiProgram(
    vararg layers: SonicPiLayer,
    cps: Number = 1.0,
    cycles: Int = 1,
    instruments: SonicPiInstruments = SonicPiInstruments()
): SonicPiProgram {
    require(layers.isNotEmpty()) { "a Sonic Pi program needs at least one layer" }
    require(cycles >= 1) { "cycles must be at least 1, was $cycles" }
    val cpsValue = cps.toDouble()
    require(cpsValue.isFinite() && cpsValue > 0.0) { "cps must be finite and > 0, was $cps" }

    val allEvents = mutableListOf<SonicPiPatternEvent>()
    val unresolved = mutableListOf<SonicPiUnresolvedAtom>()
    val dropped = mutableListOf<SonicPiDroppedOpt>()
    val bodies = mutableListOf<String>()

    for (spec in layers) {
        val (rawName, pattern) = spec.name to spec.pattern
        val kit = spec.instruments ?: instruments
        val layer = rubyLoopName(rawName)
        val missing = LinkedHashMap<String, Int>()
        val lowered = mutableListOf<SonicPiPatternEvent>()

        for (event in patternEventsForCycles(pattern, cycles)) {
            val atom = event.value
            val voice = kit.resolve(atom)
            if (voice == null) {
                missing[atom] = (missing[atom] ?: 0) + 1
                continue
            }
            if (voice is SonicPiVoice.Silent) continue
            val opts = sonicPiOptsFor(voice, event.annotations, layer, atom, kit.defaultSynth, dropped)
            lowered += SonicPiPatternEvent(layer, event.arc.start, voice, opts, atom)
        }

        missing.forEach { (atom, count) -> unresolved += SonicPiUnresolvedAtom(layer, atom, count) }
        allEvents += lowered
        bodies += renderLiveLoop(layer, lowered, cycles, cpsValue, kit.defaultSynth, spec.fx)
    }

    val header = renderHeader(layers.map { it.name }, cpsValue, cycles, unresolved, dropped)
    val source = (listOf(header) + bodies).joinToString("\n")

    return SonicPiProgram(
        source = source,
        events = allEvents,
        cps = cpsValue,
        cycles = cycles,
        layers = layers.map { rubyLoopName(it.name) },
        unresolved = unresolved,
        droppedOpts = dropped
    )
}

/**
 * The events of [pattern] over the first [cycles] cycles, in playing order.
 *
 * The single enumeration both the emitter and the piano roll use, so the two cannot disagree about
 * which fragment of a note crossing a bar line is the one that strikes. `eventSequence` already
 * answers that (only the onset fragment, widened back to its whole); this only bounds it.
 */
internal fun <A> patternEventsForCycles(pattern: PatPattern<A>, cycles: Int): List<PatEvent<A>> {
    val limit = PatQ.of(cycles)
    val out = mutableListOf<PatEvent<A>>()
    for (event in pattern.eventSequence(0)) {
        if (event.arc.start >= limit) break
        out += event
        require(out.size <= SONIC_PI_MAX_EVENTS) {
            "pattern produced more than $SONIC_PI_MAX_EVENTS events in $cycles cycle(s); " +
                "lower `cycles`, or slow the pattern down"
        }
    }
    return out
}

private fun renderHeader(
    layerNames: List<String>,
    cps: Double,
    cycles: Int,
    unresolved: List<SonicPiUnresolvedAtom>,
    dropped: List<SonicPiDroppedOpt>
): String = buildString {
    appendLine("#sonicpi")
    appendLine("# Generated from a Field pattern. Ordinary Sonic Pi from here on — edit it freely.")
    appendLine("#")
    layerNames.forEach { appendLine("#   layer ${rubyLoopName(it)}") }
    appendLine("#")
    appendLine("# use_bpm makes one beat exactly one Field cycle, so every sleep below is the cycle")
    appendLine("# fraction the pattern computed. Rational(n, d) appears where a decimal would round.")
    appendLine("# ${formatRubyNumber(cps)} cycles/second, ${cycles} cycle(s) per loop.")
    if (unresolved.isNotEmpty()) {
        appendLine("#")
        appendLine("# NOT PLAYED — no sample, synth, note or alias matched these atoms:")
        unresolved.forEach { appendLine("#   ${it.layer}: '${it.atom}' x${it.count}") }
    }
    if (dropped.isNotEmpty()) {
        appendLine("#")
        appendLine("# DROPPED OPTS — the target does not accept these:")
        dropped.distinctBy { it.layer to it.key }.forEach {
            appendLine("#   ${it.layer}: ${it.key} — ${it.why}")
        }
    }
}

private fun renderLiveLoop(
    layer: String,
    events: List<SonicPiPatternEvent>,
    cycles: Int,
    cps: Double,
    defaultSynth: String,
    fx: List<SonicPiFx> = emptyList()
): String = buildString {
    appendLine()
    appendLine("live_loop :$layer do")
    // `60 * cps` rather than the product, so the emitted program says why its tempo is what it is.
    appendLine("  use_bpm 60 * ${formatRubyNumber(cps)}")
    appendLine("  use_synth :$defaultSynth")
    // FX wrap the body, not the `use_bpm`: `with_fx` re-enters its block on every iteration, and a
    // sleep inside one is still a sleep, so the loop's period is unaffected by how deep it is.
    fx.forEachIndexed { depth, effect ->
        appendLine("  " + "  ".repeat(depth) + renderWithFx(effect) + " do")
    }
    val pad = "  " + "  ".repeat(fx.size)

    var cursor = PatQ.ZERO
    var currentSynth = defaultSynth
    for (event in events) {
        val delta = event.at - cursor
        if (delta > PatQ.ZERO) appendLine("${pad}sleep ${patQToRuby(delta)}")
        cursor = event.at
        when (val voice = event.voice) {
            is SonicPiVoice.Sample -> appendLine("${pad}sample :${voice.name}${renderOpts(event.opts)}")
            is SonicPiVoice.Note -> {
                if (voice.synth != null && voice.synth != currentSynth) {
                    appendLine("${pad}use_synth :${voice.synth}")
                    currentSynth = voice.synth
                }
                appendLine("${pad}play ${voice.note}${renderOpts(event.opts)}")
            }
            SonicPiVoice.Silent -> Unit
        }
    }
    val tail = PatQ.of(cycles) - cursor
    if (tail > PatQ.ZERO) appendLine("${pad}sleep ${patQToRuby(tail)}")
    for (depth in fx.indices.reversed()) appendLine("  " + "  ".repeat(depth) + "end")
    appendLine("end")
}

/** `with_fx :reverb, room: 0.8` — opts already checked by [sonicPiFx]. */
private fun renderWithFx(effect: SonicPiFx): String =
    "with_fx :${effect.name}" + effect.opts.entries.sortedBy { it.key }
        .joinToString("") { ", ${it.key}: ${formatRubyNumber(it.value)}" }

private fun renderOpts(opts: List<Pair<String, String>>): String =
    if (opts.isEmpty()) "" else opts.joinToString(", ", prefix = ", ") { "${it.first}: ${it.second}" }

// ── parameters ─────────────────────────────────────────────────────────────────────────────────

/**
 * Field's `PatAlgebra` annotations, translated into opts the target actually accepts.
 *
 * Three are conversions rather than renames, and they are the ones that would otherwise be wrong in
 * a way you can hear but not see: pan's range, and cutoff's *unit*.
 */
private fun sonicPiOptsFor(
    voice: SonicPiVoice,
    annotations: Map<String, String>,
    layer: String,
    atom: String,
    ambientSynth: String,
    dropped: MutableList<SonicPiDroppedOpt>
): List<Pair<String, String>> {
    if (annotations.isEmpty()) return emptyList()

    val isSample = voice is SonicPiVoice.Sample
    // A note with no synth of its own plays on the layer's `use_synth`, so that is the synth whose
    // opts decide what may be sent. Asking `play`'s common opts instead would drop `cutoff` from a
    // `:tb303` layer — correct for `:beep`, and silently wrong for the case anyone would write.
    val accepted: Set<String> = when (voice) {
        is SonicPiVoice.Sample -> SonicPiVocabulary.sampleOpts
        is SonicPiVoice.Note -> SonicPiVocabulary.synthOpts(voice.synth ?: ambientSynth)
        SonicPiVoice.Silent -> emptySet()
    }

    val out = mutableListOf<Pair<String, String>>()
    fun emit(key: String, value: String, why: String) {
        if (key in accepted) out += key to value
        else dropped += SonicPiDroppedOpt(layer, atom, key, why)
    }

    // Sorted, so the emitted line is a function of the annotations and not of the order a map
    // happened to be built in — a generated program that differs run to run cannot be diffed.
    for ((key, raw) in annotations.entries.sortedBy { it.key }.map { it.key to it.value }) {
        val number = raw.trim().toDoubleOrNull()
        when {
            key == "gain" && number != null ->
                emit("amp", formatRubyNumber(number), "no amp on this target")

            // Field's pan is 0..1 with 0.5 centred; Sonic Pi's is -1..1 with 0 centred.
            key == "pan" && number != null ->
                emit("pan", formatRubyNumber((number * 2.0 - 1.0).coerceIn(-1.0, 1.0)), "no pan on this target")

            // Field's cutoff is Hz; Sonic Pi's cutoff/lpf are MIDI note numbers.
            key == "cutoff" && number != null -> {
                val midi = formatRubyNumber(hzToMidiNote(number))
                if (isSample) emit("lpf", midi, "no lpf on sample")
                else emit("cutoff", midi, "synth :${(voice as? SonicPiVoice.Note)?.synth ?: ambientSynth} has no cutoff opt")
            }

            key == "resonance" && number != null ->
                emit("res", formatRubyNumber(number), "target has no res opt")

            key == "speed" && number != null && isSample ->
                emit("rate", formatRubyNumber(number), "no rate on this target")

            // The mini-notation's own sample-index suffix; Sonic Pi selects by name, so it is noise.
            key == "n" || key == "sampleIndex" -> Unit

            number != null -> emit(key, formatRubyNumber(number), "not an opt of this target")

            else -> Unit
        }
    }
    return out
}

/** Hz to a MIDI note number, which is the unit Sonic Pi's `cutoff:` and `lpf:` are in. */
internal fun hzToMidiNote(hz: Double): Double {
    if (!(hz > 0.0) || !hz.isFinite()) return 0.0
    return (69.0 + 12.0 * (ln(hz / 440.0) / ln(2.0))).coerceIn(0.0, 130.0)
}

// ── rendering numbers so that a third stays a third ────────────────────────────────────────────

/**
 * A [PatQ] as Ruby, exactly.
 *
 * A decimal when the denominator is a product of 2s and 5s, because then it terminates and reads
 * the way a musician expects. `Rational(n, d)` otherwise — a triplet emitted as `0.333333` drifts
 * by a whole beat inside a minute, and a `live_loop` runs for a lot longer than a minute.
 */
internal fun patQToRuby(q: PatQ): String {
    if (q.d == BigInteger.ONE) return q.n.toString()
    var d = q.d
    while (d.mod(BigInteger.TWO) == BigInteger.ZERO) d = d.divide(BigInteger.TWO)
    while (d.mod(FIVE) == BigInteger.ZERO) d = d.divide(FIVE)
    if (d == BigInteger.ONE) {
        // Terminating: an exact decimal exists and BigDecimal will find it.
        val exact = runCatching {
            BigDecimal(q.n).divide(BigDecimal(q.d)).stripTrailingZeros().toPlainString()
        }.getOrNull()
        if (exact != null) return exact
    }
    return "Rational(${q.n}, ${q.d})"
}

private val FIVE: BigInteger = BigInteger.valueOf(5)

/** A double as compact Ruby: no exponent, no trailing zeros, no more precision than is meant. */
internal fun formatRubyNumber(value: Double): String {
    if (!value.isFinite()) return "0"
    if (value == value.roundToInt().toDouble() && kotlin.math.abs(value) < 1e9) {
        return value.roundToInt().toString()
    }
    // Four places: enough for a rate or a gain curve, few enough that a MIDI cutoff derived from
    // Hz reads as a number rather than as an artefact of the conversion that produced it.
    return BigDecimal(value).setScale(4, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()
}

/** A Ruby symbol that `live_loop :x do` will accept, from whatever the layer was called. */
internal fun rubyLoopName(name: String): String {
    val cleaned = name.trim().lowercase()
        .map { if (it.isLetterOrDigit() || it == '_') it else '_' }
        .joinToString("")
        .trim('_')
    val safe = cleaned.ifEmpty { "field" }
    return if (safe.first().isDigit()) "l_$safe" else safe
}

// ── reading a program back ─────────────────────────────────────────────────────────────────────

/** One thing a Sonic Pi program was read as doing, and when. */
data class SonicPiReadEvent(
    val layer: String,
    /** Beats from the top of the loop body — which, at `use_bpm 60 * cps`, is cycles. */
    val at: PatQ,
    val kind: String,
    /** Sample name for `sample`, the synth in force for `play`. */
    val name: String,
    val note: Int? = null,
    val opts: Map<String, String> = emptyMap()
)

/** What [SonicPiProgramReader] made of a program: its events, and anything it could not read. */
data class SonicPiReading(
    val events: List<SonicPiReadEvent>,
    /** Total sleep per loop, which for a well-formed loop is its period in cycles. */
    val loopPeriods: Map<String, PatQ>,
    /** Lines that were neither blank, a comment, nor part of the emitted subset. */
    val unparsed: List<String>,
    /** The `with_fx` blocks each loop opened, outermost first. */
    val fx: Map<String, List<SonicPiFx>> = emptyMap()
)

/**
 * Reads a generated program's *text* back into an event lattice.
 *
 * The reason this exists rather than the tests reading [SonicPiProgram.events] directly: a test that
 * compares the emitter's internal list against the pattern only proves the emitter can enumerate.
 * Everything that can actually go wrong — a rational rendered as a rounded decimal, a `sleep` delta
 * that does not accumulate to the position it was derived from, a missing pad at the end of the
 * loop, an opt on the wrong line — lives in the gap between that list and the text. So the oracle
 * parses the text, exactly as Sonic Pi would, and the comparison is against the pattern's own
 * `PatQ` positions.
 *
 * It understands only the subset [sonicPiProgram] emits. Anything else lands in
 * [SonicPiReading.unparsed], so widening the emitter without widening this shows up as a failure
 * rather than as an event the oracle stopped checking without saying so.
 */
object SonicPiProgramReader {

    private val LIVE_LOOP = Regex("""^live_loop\s+:(\w+)\s+do$""")
    private val USE_SYNTH = Regex("""^use_synth\s+:(\w+)$""")
    private val USE_BPM = Regex("""^use_bpm\s+(.+)$""")
    private val WITH_FX = Regex("""^with_fx\s+:(\w+)\s*(?:,\s*(.*?))?\s+do$""")
    private val SLEEP = Regex("""^sleep\s+(.+)$""")
    private val SAMPLE = Regex("""^sample\s+:(\w+)\s*(?:,\s*(.*))?$""")
    private val PLAY = Regex("""^play\s+(-?[\d.]+)\s*(?:,\s*(.*))?$""")
    private val RATIONAL = Regex("""^Rational\(\s*(-?\d+)\s*,\s*(\d+)\s*\)$""")
    private val R_LITERAL = Regex("""^(-?\d+)r\s*/\s*(\d+)$""")
    private val DECIMAL = Regex("""^-?\d*\.\d+$""")

    fun read(source: String): SonicPiReading {
        val events = mutableListOf<SonicPiReadEvent>()
        val periods = LinkedHashMap<String, PatQ>()
        val unparsed = mutableListOf<String>()
        val fxSeen = LinkedHashMap<String, MutableList<SonicPiFx>>()

        var layer: String? = null
        var at = PatQ.ZERO
        var synth = "?"
        // `with_fx ... do` opens a block inside the loop, so its `end` must not be mistaken for the
        // loop's. Counted rather than parsed: nothing about a timing lattice depends on the nesting.
        var openBlocks = 0

        for (rawLine in source.lineSequence()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue

            val loopStart = LIVE_LOOP.matchEntire(line)
            val withFx = WITH_FX.matchEntire(line)
            val useSynth = USE_SYNTH.matchEntire(line)
            val sleepFor = SLEEP.matchEntire(line)
            val sampleAt = SAMPLE.matchEntire(line)
            val playAt = PLAY.matchEntire(line)

            when {
                loopStart != null -> {
                    layer = loopStart.groupValues[1]
                    at = PatQ.ZERO
                    synth = "?"
                    openBlocks = 0
                }
                withFx != null -> {
                    openBlocks++
                    fxSeen.getOrPut(layer ?: "<toplevel>") { mutableListOf() } +=
                        SonicPiFx(withFx.groupValues[1], parseOpts(withFx.groupValues.getOrNull(2))
                            .mapNotNull { (k, v) -> v.toDoubleOrNull()?.let { k to it } }.toMap())
                }
                line == "end" -> {
                    if (openBlocks > 0) {
                        openBlocks--
                    } else {
                        layer?.let { periods[it] = at }
                        layer = null
                    }
                }
                USE_BPM.matches(line) -> Unit
                useSynth != null -> synth = useSynth.groupValues[1]
                sleepFor != null -> {
                    val amount = parseRubyNumber(sleepFor.groupValues[1])
                    if (amount == null) unparsed += rawLine else at += amount
                }
                sampleAt != null -> events += SonicPiReadEvent(
                    layer = layer ?: "<toplevel>",
                    at = at,
                    kind = "sample",
                    name = sampleAt.groupValues[1],
                    opts = parseOpts(sampleAt.groupValues.getOrNull(2))
                )
                playAt != null -> events += SonicPiReadEvent(
                    layer = layer ?: "<toplevel>",
                    at = at,
                    kind = "play",
                    name = synth,
                    note = playAt.groupValues[1].toDoubleOrNull()?.roundToInt(),
                    opts = parseOpts(playAt.groupValues.getOrNull(2))
                )
                else -> unparsed += rawLine
            }
        }
        return SonicPiReading(events, periods, unparsed, fxSeen)
    }

    /**
     * A Ruby numeric literal from the emitted subset, exactly.
     *
     * Exactly, meaning `"0.25"` becomes 25/100 and not `PatQ.fromDouble(0.25)` — the whole point of
     * the oracle is that no comparison goes through a double.
     */
    fun parseRubyNumber(text: String): PatQ? {
        val t = text.trim()
        RATIONAL.matchEntire(t)?.let { return PatQ.of(it.groupValues[1].toLong(), it.groupValues[2].toLong()) }
        R_LITERAL.matchEntire(t)?.let { return PatQ.of(it.groupValues[1].toLong(), it.groupValues[2].toLong()) }
        t.toLongOrNull()?.let { return PatQ.of(it) }
        if (!DECIMAL.matches(t)) return null
        val negative = t.startsWith("-")
        val (whole, frac) = t.removePrefix("-").split(".")
        val denom = BigInteger.TEN.pow(frac.length)
        val numer = BigInteger(whole.ifEmpty { "0" }) * denom + BigInteger(frac)
        val q = normalisePatQ(PatQ(numer, denom))
        return if (negative) -q else q
    }

    private fun parseOpts(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        return text.split(",").mapNotNull { part ->
            val idx = part.indexOf(':')
            if (idx <= 0) null else part.substring(0, idx).trim() to part.substring(idx + 1).trim()
        }.toMap()
    }
}

/**
 * `PatQ`'s constructor does not reduce; its factories do.
 *
 * Building one positionally leaves `2/4` unreduced, and `PatQ` is a data class, so `2/4 != 1/2` by
 * `equals` even though `compareTo` says they are equal. Every comparison in the oracle is by value,
 * so everything that reaches it goes through here first.
 */
internal fun normalisePatQ(q: PatQ): PatQ = PatQ.ZERO + q

// ── playing it ─────────────────────────────────────────────────────────────────────────────────

/**
 * Compile this pattern to Sonic Pi and play it, tied to the box.
 *
 * The compile-mode entry point. The box stays lit while the `live_loop`s run and stopping the box
 * stops them, exactly as [Box.sonicPi] does — this *is* [Box.sonicPi], with the Ruby generated
 * instead of typed. Sonic Pi's scheduler is doing the timing, so this is the accurate mode; the
 * cost is that changing the Kotlin means re-running the box, which redefines the named `live_loop`
 * in place rather than starting a second one.
 *
 * ```
 * pat("bd [hh hh] sd hh").sonicPiPattern(cps = 0.5, cycles = 4)
 * ```
 *
 * Prints the program's unresolved atoms if there are any: an atom that plays nothing is otherwise
 * indistinguishable from a rest.
 */
fun Box.sonicPiPattern(
    pattern: PatPattern<String>,
    cps: Number = 1.0,
    cycles: Int = 1,
    name: String? = null,
    instruments: SonicPiInstruments = SonicPiInstruments()
): SonicPiLaunch = sonicPiPattern(
    (name ?: this.name.ifBlank { "field" }) to pattern,
    cps = cps,
    cycles = cycles,
    instruments = instruments
)

/** Compile several named layers and play them, tied to the box. See [sonicPiProgram]. */
fun Box.sonicPiPattern(
    vararg layers: Pair<String, PatPattern<String>>,
    cps: Number = 1.0,
    cycles: Int = 1,
    instruments: SonicPiInstruments = SonicPiInstruments()
): SonicPiLaunch = sonicPiPattern(
    layers = layers.map { SonicPiLayer(it.first, it.second) }.toTypedArray(),
    cps = cps,
    cycles = cycles,
    instruments = instruments
)

/**
 * Compile layers that may each name their own synth, and play them, tied to the box.
 *
 * ```
 * sonicPiPattern(
 *     "drums" to pat("bd ~ [bd bd bd] bd"),
 *     "bass" to (pat("<e1 g1> ~ e2 ~") with cutoff(700.0)) on "tb303",
 *     cps = 0.5,
 *     cycles = 4
 * )
 * ```
 */
fun Box.sonicPiPattern(
    vararg layers: SonicPiLayer,
    cps: Number = 1.0,
    cycles: Int = 1,
    instruments: SonicPiInstruments = SonicPiInstruments()
): SonicPiLaunch {
    val program = sonicPiProgram(*layers, cps = cps, cycles = cycles, instruments = instruments)
    program.unresolved.forEach {
        println("sonicPiPattern: '${it.atom}' (x${it.count}) in :${it.layer} matched no sample, synth, note or alias — it will not play")
    }
    // lineOffset 0 rather than inferred: the Ruby was generated, so there is no literal in this
    // box's source for Sonic Pi's line numbers to be measured against.
    return sonicPi(program.source, lineOffset = 0)
}

/** The Sonic Pi source for a pattern, ready to paste into a `#sonicpi` region. */
fun PatPattern<String>.sonicPiSource(
    cps: Number = 1.0,
    cycles: Int = 1,
    name: String = "field",
    instruments: SonicPiInstruments = SonicPiInstruments()
): String = sonicPiProgram(name to this, cps = cps, cycles = cycles, instruments = instruments).source

/**
 * Drive Sonic Pi from Field's clock, one `/run-code` per event.
 *
 * The live mode. Field is the sequencer and Sonic Pi is the instrument, so every pattern transform
 * stays live — edit the Kotlin, re-run, and the next event goes out changed, without redefining a
 * `live_loop`. Control signals work the way they do everywhere else in `PatAlgebra`:
 *
 * ```
 * (pat("bd*4, [~ cp]*2") with gain(sine().range(0.3, 0.9))).sonicPiLive(cps = 0.5)
 * ```
 *
 * The trade against [Box.sonicPiPattern] is timing. Each event is a UDP message plus Sonic Pi's own
 * scheduling latency, so onsets carry a roughly constant offset and a small amount of jitter, where
 * a compiled `live_loop` is sample-accurate. A constant offset is not a problem for a sequencer —
 * `sonicpi.pattern.timing` measures the jitter, which is.
 *
 * [lead] sends each event that many seconds early, if you want to trade Field's dispatch latency
 * away and can tolerate the pattern being that much less live.
 */
fun PatPattern<String>.sonicPiLive(
    cps: Number = 1.0,
    instruments: SonicPiInstruments = SonicPiInstruments(),
    lead: Number = 0.0,
    clock: PatClock? = defaultPatClock(),
    onSend: ((String) -> Unit)? = null
): PatResponseSpec<String> {
    val sp = SonicPi.shared()
    sp.awaitReady()
    return respondAhead(ahead = lead, cps = cps, dispatchOnMain = false, clock = clock) { event ->
        val voice = instruments.resolve(event.value)
        if (voice == null) {
            println("sonicPiLive: '${event.value}' matched no sample, synth, note or alias")
            return@respondAhead
        }
        if (voice is SonicPiVoice.Silent) return@respondAhead
        val code = sonicPiOneLiner(voice, event.annotations, instruments.defaultSynth)
        onSend?.invoke(code)
        sp.run(code, workspace = SONIC_PI_LIVE_WORKSPACE, silent = true)
    }
}

/** The workspace live-driven one-shots run in, kept off the compile-mode workspaces. */
const val SONIC_PI_LIVE_WORKSPACE: String = "field-pat-live"

/**
 * One event as a standalone line of Sonic Pi.
 *
 * `use_synth` has to be on the same line as the note rather than a separate message: each
 * `/run-code` is its own Sonic Pi run on its own thread, so a `use_synth` sent separately would set
 * a thread-local on a thread that has already finished.
 */
internal fun sonicPiOneLiner(
    voice: SonicPiVoice,
    annotations: Map<String, String>,
    defaultSynth: String
): String {
    val dropped = mutableListOf<SonicPiDroppedOpt>()
    val opts = renderOpts(sonicPiOptsFor(voice, annotations, "live", "-", defaultSynth, dropped))
    return when (voice) {
        is SonicPiVoice.Sample -> "sample :${voice.name}$opts"
        is SonicPiVoice.Note -> "use_synth :${voice.synth ?: defaultSynth}; play ${voice.note}$opts"
        SonicPiVoice.Silent -> "nil"
    }
}
