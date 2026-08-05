@file:Suppress("unused")

package afield.rectsl

import afield.four.synth.PatArc
import afield.four.synth.PatEvent
import afield.four.synth.PatPattern
import afield.four.synth.PatQ
import afield.four.synth.bipolar
import afield.four.synth.cosine
import afield.four.synth.euclid
import afield.four.synth.isaw
import afield.four.synth.num
import afield.four.synth.nums
import afield.four.synth.pat
import afield.four.synth.perlin
import afield.four.synth.quantise
import afield.four.synth.rand
import afield.four.synth.range
import afield.four.synth.saw
import afield.four.synth.segment
import afield.four.synth.sine
import afield.four.synth.square
import afield.four.synth.texts
import afield.four.synth.tri

/**
 * # RectSL's musical layer — the Tidal pattern language as scene structure
 *
 * RectSL already had *time*: tracks, `->live birth life`, `->stagger-time`, `->split`. What it
 * did not have was **meter** — a shared clock, subdivision, and (the one that actually matters)
 * any way for repetition *n+1* to differ from repetition *n*. The array primitives distribute
 * copies in space and every copy plays the same clip. That is the difference between a loop and
 * a groove, and it is exactly the gap `afield.four.synth`'s pattern language fills.
 *
 * The two languages turn out to be the same shape underneath, which is why this bridge is thin
 * rather than a translation layer:
 *
 * ```
 * RectSL   interface Track<out T> { fun sample(t: Double): T }
 * Pattern  class PatPattern<A>    { fun events(arc: PatArc): List<PatEvent<A>> }
 * ```
 *
 * Both are **pure functions of time**, queryable out of order, with no simulation state —
 * `PatContractTest`'s purity and query-order properties assert it on one side, and RectSL's
 * closed-form tracks (the reason `HeadingTrack` can recover velocity by finite difference)
 * assert it on the other. So composing them needs no scheduler and no frame loop: a pattern
 * *is* a track ([PatTrack]), and an event *is* a life window ([patArray]).
 *
 * ## What lives here
 *
 * | § | words |
 * | --- | --- |
 * | clock | `->cps` `->tempo` `->beats-per-cycle` `->duration-cycles` `beats` `cycles` `cycle-secs` |
 * | pat | `pat` and its per-event context `t` `dur` `cyc` `step` `atom` `param` `param?` |
 * | patterns as values | `mini` `sine` `cosine` `saw` `isaw` `tri` `square` `rand` `perlin` `nums` `texts` `segment` `pat-range` `quantise` `bipolar` `euclid` |
 * | variation | `->every` `->off` `->jux` `->degrade` `->swing` `->press` `->iter` `->chunk` |
 * | layout | `strip` `->euclid` |
 * | out | `pat-events` |
 *
 * ## Why a separate file
 *
 * RectSL.kt is already 4.6k lines, and this layer is the only part of the language that depends
 * on `afield.four.synth`. Keeping the dependency in one file means the rest of RectSL still
 * compiles and reads without knowing the pattern language exists. The seams it needs from
 * RectSL.kt are deliberately small and marked `internal`: [RectLangVm.word]/[RectLangVm.mutator]
 * (so the musical words register through the same one path as every other word),
 * [RectLangVm.loopStack] and [LoopFrame.pat] (so `i`/`n`/`f` keep working inside a `pat` body),
 * [SceneBuilder.mapScalarTracks], and [SceneBuilder.cps].
 *
 * @see afield.four.synth.PatPattern the event model
 * @see patArray the keystone: one template copy per event
 * @see PatTrack the keystone the other way: a pattern sampled as a track
 */

// --- Values ------------------------------------------------------------------------------

/**
 * An event pattern on the RectSL stack — a rhythm. Holds `PatPattern<String>`, the same type
 * `pat("bd*4")` produces, so the whole mini-notation surface (polymeter, Euclid, `<a b>`,
 * `?` degrade, `.` grouping) arrives intact rather than being re-parsed here.
 */
data class VPat(val pattern: PatPattern<String>) : Value

/**
 * A numeric pattern on the RectSL stack — a signal. Separate from [VPat] because the round trip
 * through `texts()`/`nums()` formats to nine decimal places, and a value that is *about* to
 * become a track should not be quantised on the way. Coerces to a [PatTrack] wherever RectSL
 * wants a `Track<Double>`.
 */
data class VSig(val signal: PatPattern<Double>) : Value

// --- A pattern as a track ------------------------------------------------------------------

/**
 * A pattern sampled as a RectSL scalar track. `t` is scene seconds; `cps` converts to the
 * pattern's cycles.
 *
 * The query is a *microscopically wide* arc rather than an instant, because [PatArc] requires
 * `end > start` — a pattern has no notion of a zero-width query, and §1 of PATTERN_LANGUAGE.md
 * explains why (an instant could be missed; an arc never is). The width is one part in
 * [SAMPLE_DENOM], which is a microcycle: far below a frame at any sane tempo, and small enough
 * that a continuous signal's arc-midpoint convention returns essentially the value at `t`.
 *
 * Two behaviours worth knowing:
 * - **A rest is a zero, not a hold.** `"1 ~ 1"` returns [fallback] in the middle third, because
 *   the pattern genuinely has no event there. Holding would require remembering the previous
 *   sample, which is state, which would break the purity the rest of this bridge depends on.
 *   `hold` (the word) is the pure way to ask for sample-and-hold instead.
 * - **A stack resolves to its topmost layer.** `stack(a, b)` yields `a` then `b` for the same
 *   arc; a scalar track has to pick one, and the later layer is the one drawn on top.
 */
data class PatTrack(
    val signal: PatPattern<Double>,
    val cps: Double,
    val fallback: Double = 0.0
) : Track<Double> {
    override val kind: TrackValueKind = TrackValueKind.Double

    override fun sample(t: Double): Double {
        val start = PatQ.fromDouble(t * cps, SAMPLE_DENOM)
        val arc = PatArc(start, start + PatQ.of(1L, SAMPLE_DENOM))
        return signal.events(arc).lastOrNull()?.value ?: fallback
    }

    companion object {
        /** One microcycle — the width of the instant a track sample stands in for. */
        const val SAMPLE_DENOM = 1_000_000L
    }
}

/**
 * Sample-and-hold: like [PatTrack] but a rest keeps the previous event's value instead of
 * falling back to zero. Pure, not stateful — it looks *backwards* from `t` over a bounded
 * window and takes the latest event that has already started, so it still answers any time in
 * any order with the same value.
 */
data class PatHoldTrack(
    val signal: PatPattern<Double>,
    val cps: Double,
    val fallback: Double = 0.0,
    val lookBackCycles: Long = 2L
) : Track<Double> {
    override val kind: TrackValueKind = TrackValueKind.Double

    override fun sample(t: Double): Double {
        val now = t * cps
        val here = PatQ.fromDouble(now, PatTrack.SAMPLE_DENOM)
        val from = PatQ.fromDouble(now - lookBackCycles, PatTrack.SAMPLE_DENOM)
        val arc = PatArc(from, here + PatQ.of(1L, PatTrack.SAMPLE_DENOM))
        return signal.events(arc)
            .filter { it.arc.start <= here }
            .maxByOrNull { it.arc.start }
            ?.value
            ?: fallback
    }
}

/** A signal read as a track. */
internal fun VSig.asTrack(cps: Double): Track<Double> = PatTrack(signal, cps)

/**
 * A bare string read as a numeric rhythm — `"0 1 2 3" ->x` — or null if it is not one.
 *
 * The guard is the whole design of this function. A String reaching a track slot has always
 * been an error, so accepting mini-notation there only adds meaning; but accepting *any* string
 * would turn `"viridis" ->x` (a colormap name in the wrong place) from a clear error into a
 * silent zero. So the text has to actually contain a number before it counts as a pattern, and
 * anything else falls through to the error message it has always produced.
 *
 * The *order* of that guard matters, and used not to. Asking [pat] first and treating any
 * failure as "not a pattern" hides bugs in the mini-notation machinery behind a message about
 * the caller's string: a crash in `pat`'s source-location helper made `"0 1 2 3" ->x` inside a
 * live Field box report "a String is read as mini-notation only when it contains numbers".
 * So the cheap textual question is asked first, and once the text looks like a rhythm any
 * failure to read it is reported as itself. The semantic decision is unchanged — a numeric
 * *atom* is still what makes it a pattern, so `"magma256"` is still not one.
 */
internal fun numericPatternTrackOrNull(text: String, cps: Double): Track<Double>? {
    if (!DIGIT.containsMatchIn(text)) return null
    val pattern: PatPattern<String>
    val hasNumericAtom: Boolean
    try {
        pattern = pat(text)
        hasNumericAtom = pattern.events(PatArc(PatQ.ZERO, PatQ.ONE))
            .any { it.value.trim().toDoubleOrNull() != null }
    } catch (t: Throwable) {
        throw RectLangException(
            "\"$text\" has digits in it, so it was read as a numeric rhythm, but mini-notation " +
                "could not parse it: ${t.message ?: t.toString()}"
        )
    }
    return if (hasNumericAtom) PatTrack(pattern.nums(), cps) else null
}

private val DIGIT = Regex("""\d""")

/**
 * A rhythm read as a track: its atoms are read as numbers, so `"0 0.5 1"` is a stepped ramp and
 * `"bd sd"` — whose atoms are not numbers — is silence rather than an error, the same reading
 * [afield.four.synth.nums] gives.
 */
internal fun VPat.asTrack(cps: Double): Track<Double> = PatTrack(pattern.nums(), cps)

// --- The per-event context a `pat` body sees ------------------------------------------------

/**
 * The musical identity of one event, readable inside a `pat` body through `t`, `dur`, `cyc`,
 * `step`, `atom` and `param`. Times are already in **scene seconds**, converted once here, so
 * nothing downstream has to remember the tempo.
 */
data class PatEventContext(
    /** Onset, in scene seconds. */
    val t: Double,
    /** Length, in scene seconds — the event's *whole*, not the fragment a query happened to see. */
    val dur: Double,
    /** Which cycle the onset falls in. */
    val cycle: Int,
    /** Index of this event within its own cycle, counting from 0. */
    val step: Int,
    /** The event's mini-notation value: `"bd"`, `"808:4"`, `"c'maj7"`. */
    val atom: String,
    /** Named parameters carried on the event — `gain`, `pan`, `n`, `note`, … */
    val params: Map<String, String>
)

// --- Installation ---------------------------------------------------------------------------

internal fun RectLangVm.installPatternWords() {
    installClockWords()
    installPatternValueWords()
    installPatWord()
    installVariationWords()
    installLayoutWords()
}

// --- §1 Clock ---------------------------------------------------------------------------------

private fun RectLangVm.installClockWords() {
    // Scene configuration reads its argument as the *following token*, the way `->duration 4`
    // and `->fps 20` already do, rather than off the stack. Keeping the clock in the same shape
    // as the rest of the scene header is worth more than consistency with the value words.
    mutator("cps") { vm -> vm.builder().setCps(vm.consumeNumber("->cps")) }
    mutator("tempo") { vm -> vm.builder().setTempo(vm.consumeNumber("->tempo")) }
    mutator("beats-per-cycle") { vm -> vm.builder().setBeatsPerCycle(vm.consumeNumber("->beats-per-cycle")) }
    mutator("duration-cycles") { vm ->
        val b = vm.builder()
        b.duration = vm.consumeNumber("->duration-cycles") / b.cps.coerceAtLeast(1e-9)
    }

    word("beats") { vm ->
        val b = vm.builder()
        vm.push(VNum(vm.popNum() / b.cps.coerceAtLeast(1e-9) / b.beatsPerCycle.coerceAtLeast(1e-9)))
    }
    word("cycles") { vm -> vm.push(VNum(vm.popNum() / vm.builder().cps.coerceAtLeast(1e-9))) }
    word("cycle-secs") { vm -> vm.push(VNum(1.0 / vm.builder().cps.coerceAtLeast(1e-9))) }
    word("scene-cycles") { vm ->
        val b = vm.builder()
        vm.push(VNum(b.duration * b.cps))
    }
}

/**
 * `->tempo` and `->beats-per-cycle` are order-independent on purpose: a scene that says
 * `->tempo 120 ->beats-per-cycle 3` and one that says them the other way round must agree, so
 * the bpm is remembered and `cps` re-derived whenever either changes. Setting `->cps` directly
 * forgets the bpm, because a directly-stated cps is not a tempo divided by anything.
 */
internal fun SceneBuilder.setTempo(bpm: Double) {
    this.bpm = bpm
    cps = bpm / 60.0 / beatsPerCycle.coerceAtLeast(1e-9)
}

internal fun SceneBuilder.setBeatsPerCycle(n: Double) {
    beatsPerCycle = n.coerceAtLeast(1e-9)
    bpm?.let { cps = it / 60.0 / beatsPerCycle }
}

internal fun SceneBuilder.setCps(value: Double) {
    cps = value.coerceAtLeast(1e-9)
    bpm = null
}

// --- §2 Patterns as stack values ---------------------------------------------------------------

private fun RectLangVm.installPatternValueWords() {
    word("mini") { vm -> vm.push(VPat(pat(vm.popString()))) }

    word("sine") { vm -> vm.push(VSig(sine())) }
    word("cosine") { vm -> vm.push(VSig(cosine())) }
    word("saw") { vm -> vm.push(VSig(saw())) }
    word("isaw") { vm -> vm.push(VSig(isaw())) }
    word("tri") { vm -> vm.push(VSig(tri())) }
    word("square") { vm -> vm.push(VSig(square())) }
    word("rand") { vm -> vm.push(VSig(rand(vm.popNum().toInt()))) }
    word("perlin") { vm -> vm.push(VSig(perlin(vm.popNum().toInt()))) }

    word("nums") { vm -> vm.push(VSig(vm.popPat("nums").nums())) }
    word("texts") { vm -> vm.push(VPat(vm.popSig("texts").texts())) }

    word("segment") { vm ->
        val n = vm.popNum().toInt().coerceAtLeast(1)
        vm.push(VSig(vm.popSig("segment").segment(n)))
    }
    // `range` is already the loop word, so the pattern remap needs its own spelling.
    word("pat-range") { vm ->
        val hi = vm.popNum()
        val lo = vm.popNum()
        vm.push(VSig(vm.popSig("pat-range").range(lo, hi)))
    }
    word("quantise") { vm ->
        val step = vm.popNum()
        vm.push(VSig(vm.popSig("quantise").quantise(step)))
    }
    word("quantize") { vm ->
        val step = vm.popNum()
        vm.push(VSig(vm.popSig("quantize").quantise(step)))
    }
    word("bipolar") { vm -> vm.push(VSig(vm.popSig("bipolar").bipolar())) }

    word("euclid") { vm ->
        val rotation = vm.popNum().toInt()
        val steps = vm.popNum().toInt()
        val pulses = vm.popNum().toInt()
        vm.push(VPat(euclid(pulses, steps, rotation).onsetsOnly()))
    }

    // Sample-and-hold, the pure kind: `"0 ~ 1" mini nums hold ->rot` holds through the rest.
    word("hold-pat") { vm ->
        vm.push(VTrack(PatHoldTrack(vm.popSig("hold-pat"), vm.builder().cps)))
    }
}

/**
 * Keep only the `true` steps of a boolean pattern, as `"x"` events — a *rhythm*, not a mask.
 * `euclid` returns a step per slot, on and off alike; `pat` wants one copy per hit, and an
 * off-step is the absence of a hit rather than a hit valued `false`.
 */
private fun PatPattern<Boolean>.onsetsOnly(): PatPattern<String> =
    PatPattern { arc -> events(arc).filter { it.value }.map { it.withValue("x") } }

// --- §3 `pat`: the time-array primitive -----------------------------------------------------

private fun RectLangVm.installPatWord() {
    word("pat") { vm -> vm.patArray() }

    word("t") { vm -> vm.push(VNum(vm.patContext("t").t)) }
    word("dur") { vm -> vm.push(VNum(vm.patContext("dur").dur)) }
    word("cyc") { vm -> vm.push(VNum(vm.patContext("cyc").cycle.toDouble())) }
    word("step") { vm -> vm.push(VNum(vm.patContext("step").step.toDouble())) }
    word("atom") { vm -> vm.push(VStr(vm.patContext("atom").atom)) }
    word("param") { vm ->
        val key = vm.popString()
        vm.push(VNum(vm.patContext("param").params[key]?.toDoubleOrNull() ?: 0.0))
    }
    word("param?") { vm ->
        val key = vm.popString()
        vm.push(VNum(if (vm.patContext("param?").params.containsKey(key)) 1.0 else 0.0))
    }
    word("atom?") { vm ->
        val want = vm.popString()
        vm.push(VNum(if (vm.patContext("atom?").atom == want) 1.0 else 0.0))
    }
}

/**
 * `[template] pattern quote -- selection` — reproduce a template **once per event**, each copy
 * living for exactly that event's window.
 *
 * This is the keystone. RectSL's existing array primitives (`array`, `radial`, `grid`,
 * `phyllotaxis`) reproduce a subexpression indexed by *ordinal* and distribute the copies in
 * space; `pat` reproduces it indexed by *rhythm* and distributes the copies in time. The mapping
 * is exact rather than approximate, which is the whole reason this is worth doing: a `PatEvent`
 * carries an onset and a real duration (its *whole*, §4b), and `->live birth life` wants exactly
 * an onset and a duration. An event **is** a life window.
 *
 * Everything the mini-notation can say therefore arrives for free — polymeter `{a b c}%8`,
 * Euclid `bd(3,8)`, per-cycle alternation `<a b>`, `?` degrade, `.` grouping, `@` elongation —
 * without RectSL parsing any of it.
 *
 * Three details that are decisions rather than mechanics:
 *
 * 1. **Events come from `eventSequence`, not a wide `events()` query.** A note crossing a bar
 *    line comes back from two consecutive cycle queries, clipped both times; `eventSequence`
 *    yields only the fragment carrying the onset and widens it back to its whole (§4b). Using
 *    the wide query instead would be *almost* right and would silently spawn two half-length
 *    copies of any event that straddles a bar — the visual form of the note struck twice.
 * 2. **`->live` is applied after the body runs**, so the body authors the copy's flight in
 *    local time (`0` is its own birth) exactly as `->split`'s shards do. Authoring in local
 *    time is what lets one template become a stream.
 * 3. **The body's own spawn is gated too.** Anything the body creates — a nested `array`, a
 *    `->split`, a group of children — is captured and given the same window, so `pat` composes
 *    with the rest of the language instead of sitting beside it.
 */
internal fun RectLangVm.patArray() {
    val mods = popQuote()
    val pattern = popPat("pat")
    val b = builder()
    val cps = b.cps.coerceAtLeast(1e-9)
    val cycles = b.duration * cps

    val events = pattern.onsetsWithin(cycles)
    val template = popTemplateRef("pat")

    // A rhythm with no events shows nothing. This differs from `0 array`, which leaves its
    // template visible, and the difference is deliberate: `0 array` is a degenerate call nobody
    // writes, whereas an all-rests pattern, a fully degraded one, or one whose events all fall
    // past the scene's end are ordinary things to write — and each would otherwise leave a
    // stray unexplained rect sitting at the origin.
    if (events.isEmpty()) {
        b.withActive(template) { b.multiplyAlpha(constantTrack(0.0)) }
        push(VSelection(emptyList()))
        return
    }

    val refs = b.instantiateArray(template, events.size)

    // `step` counts within a cycle, so it restarts at each bar line rather than running on.
    var cycleOfPrevious = Int.MIN_VALUE
    var step = 0

    refs.forEachIndexed { ordinal, ref ->
        val event = events[ordinal]
        val onsetCycles = event.arc.start.toDouble()
        val cycle = kotlin.math.floor(onsetCycles).toInt()
        if (cycle != cycleOfPrevious) {
            cycleOfPrevious = cycle
            step = 0
        }
        val birth = onsetCycles / cps
        val life = ((event.extent.end.toDouble() - onsetCycles) / cps).coerceAtLeast(MIN_EVENT_SECONDS)
        val context = PatEventContext(
            t = birth,
            dur = life,
            cycle = cycle,
            step = step,
            atom = event.value,
            params = event.annotations
        )
        step += 1

        val spawned = b.captureNewNodes {
            withSelected(ref) { withPatLoop(ordinal, events.size, context) { evalBlock(mods.tokens) } }
        }
        (listOf(ref) + spawned).forEach { node -> b.withActive(node) { b.live(birth, life) } }
    }

    push(VSelection(refs))
}

/**
 * Every event that *starts* within the first [cycles] cycles, in playing order, each yielded
 * once and widened to its whole.
 *
 * This is [PatPattern.eventSequence]'s rule — take only the fragment carrying the onset, widen
 * it back to the note's real extent (§4b) — applied over a bounded span rather than forever.
 * The bound is the reason it is written out rather than delegated: `eventSequence` is an
 * infinite generator that simply advances through cycles which produce nothing, so
 * `takeWhile { it.start < cycles }` on a pattern with a silent cycle never terminates. A scene
 * has a duration, so asking cycle by cycle up to it is both correct and finite.
 */
private fun PatPattern<String>.onsetsWithin(cycles: Double): List<PatEvent<String>> {
    val out = ArrayList<PatEvent<String>>()
    val lastCycle = kotlin.math.ceil(cycles).toLong()
    var cycle = 0L
    while (cycle < lastCycle) {
        events(PatArc(PatQ.of(cycle), PatQ.of(cycle + 1)))
            .filter { it.hasOnset && it.arc.start.toDouble() < cycles }
            .sortedBy { it.arc.start.toDouble() }
            .forEach { event -> out += if (event.whole != null) event.copy(arc = event.whole) else event }
        if (out.size > MAX_PAT_EVENTS) {
            throw RectLangException(
                "pat: this rhythm asks for more than $MAX_PAT_EVENTS copies over ${"%.1f".format(cycles)} " +
                    "cycles. Slow the pattern, shorten the scene, or raise ->cps."
            )
        }
        cycle++
    }
    return out
}

/**
 * A guard, not a limit anyone should hit: `pat` instantiates one scene node per event, and a
 * fast pattern over a long scene can ask for more than a scene can hold. Blowing past this
 * throws rather than quietly truncating, because a rhythm that stops halfway through the scene
 * looks like a bug in the pattern rather than a cap being reached.
 */
private const val MAX_PAT_EVENTS = 20_000

/** A zero-length event would give a copy an empty gate and make it invisible. */
private const val MIN_EVENT_SECONDS = 1e-6

// --- Stack helpers ---------------------------------------------------------------------------

/** Accepts a `VPat`, or a bare string, which is auto-parsed — `"bd*4" '( … ) pat` reads best. */
internal fun RectLangVm.popPat(word: String): PatPattern<String> = when (val value = pop()) {
    is VPat -> value.pattern
    is VStr -> pat(value.x)
    is VSig -> value.signal.texts()
    else -> throw RectLangException("$word expected a pattern or a mini-notation String, found ${value.patDescribe()}")
}

/** Accepts a `VSig`, a `VPat` (read as numbers), or a constant. */
internal fun RectLangVm.popSig(word: String): PatPattern<Double> = when (val value = pop()) {
    is VSig -> value.signal
    is VPat -> value.pattern.nums()
    is VStr -> pat(value.x).nums()
    is VNum -> afield.four.synth.constant(value.x)
    else -> throw RectLangException("$word expected a pattern or Number, found ${value.patDescribe()}")
}

private fun Value.patDescribe(): String = when (this) {
    is VPat -> "Pattern"
    is VSig -> "Signal"
    is VNum -> "Number"
    is VStr -> "String"
    else -> this::class.simpleName ?: "Value"
}

internal fun RectLangVm.withPatLoop(
    ordinal: Int,
    count: Int,
    context: PatEventContext,
    block: () -> Unit
) {
    loopStack.addLast(LoopFrame(index = ordinal, count = count, ordinal = ordinal, pat = context))
    try {
        block()
    } finally {
        loopStack.removeLast()
    }
}

internal fun RectLangVm.patContext(word: String): PatEventContext =
    loopStack.lastOrNull()?.pat
        ?: throw RectLangException("$word is only available inside a `pat` body")

// --- §4 Variation: the combinators that make repetition differ ---------------------------------

private fun RectLangVm.installVariationWords() = installVariationWordsImpl()

// --- §5 Layout ----------------------------------------------------------------------------------

private fun RectLangVm.installLayoutWords() = installLayoutWordsImpl()
