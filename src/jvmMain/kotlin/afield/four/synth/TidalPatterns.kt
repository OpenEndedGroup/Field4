/*
 Pattern language and event model for live-coded musical structure.

 This is inspired by Tidal's style of compact pattern notation, but the goal
 here is not strict compatibility. The Field-facing API is intentionally small,
 direct, and biased toward generic event streams rather than drum machines or
 sample triggers.

 The design split is:

 - parse short textual pattern expressions with `pat("...")`
 - keep transforms exact in rational cycle space using `PatQ` / `PatArc`
 - expose scheduled events in seconds at the public boundary
 - allow atoms to mean anything, not just instruments or samples
 - bridge easily into the existing note-process / piano-roll system
 - also launch directly as a generic side-effecting event source

 Read the surface from the outside in:

 1. Parse:

        val hats = pat("bd [hh hh] sd")

 2. Transform in cycle space:

        val varied = pat("a b c d")
            .every(4) { it.rev() }
            .sometimes { it.fast(2) }

        val layered = stack(
            pat("bd ~ sd"),
            pat("[hh hh hh hh]").degradeBy(0.2)
        )

     Simultaneous events (chords in music contexts):

        val triad = pat("c, e, g")     // All three fire at once during the same beat
        val voicing = pat("<c e g>, <g b d>")  // Pick one set per cycle, stacked

     Arpeggiate over a chord or note sequence:

        val bassArp = pat("bass bass bass").arp(60, 64, 67, speed = 4) // C major chord, 4 steps
        val upAndDown = pat("pad").arp(listOf("C3", "E3", "G3"), mode = ArpMode.UPDOWN, speed = 8)
        val randomArp = pat("keys").arp(48, 50, 52, 55, 57, mode = ArpMode.RANDOM, seed = 42)

 3. Schedule into seconds:

        val events = pat("kick <snare rim>")
            .scheduled(cps = 2.0, startAt = 1.5)
            .take(8)
            .toList()

        events.first().start
        events.first().duration

     Or align directly to a shared musical clock:

        val master = musicClock()
        val sixteenth = master.subdivide(4.0)
        val upcoming = pat("bd [hh hh] sd")
            .scheduled(cps = 0.5, clock = sixteenth)
            .take(8)
            .toList()

     Euclidean suffixes attach directly to the preceding event or subgroup:

        val groove = pat("[bd cp](3,8) hh!2")

     Repetition and density are distinct:

        val replicated = pat("bd!2 cp")
        val densified = pat("bd*2 cp")

     Elongation holds an event across later steps:

        val held = pat("superpiano@3 superpiano")

     Curly-brace polymeters can also take an explicit subdivision:

        val polymeter = pat("{bd hh 808:4}%8")
        val layeredPolymeter = pat("{bd sd stab, cp arpy cr arpy}")

     Inside square brackets, `|` picks one alternative per cycle:

        val randomChoice = pat("[bd*4|hh*12|cp*2]")

     Atom names can still carry sample-style parameter payloads:

        val parameterized = pat("bd:(1,2,3) hh")

     By default, `scheduled(...)`, `respond(...)`, `respondAhead(...)`, and
     `asNotes(...)` use the ambient `PatClock` from box context if one exists,
     otherwise they fall back to `musicClock()`. Pass `clock = null` if you
     want a zero-based schedule instead.

 4. Map atoms into existing note processes:

        val bass = pat("0 3 5 7").asNotes(cps = 2.0) { event ->
            val degree = event.value.toIntOrNull() ?: return@asNotes emptyList()
            listOf(
                note(
                    pitch = 36 + degree,
                    duration = event.duration * 0.95,
                    start = event.start,
                    label = event.value
                )
            )
        }

 5. Or respond directly, without assuming anything about the atom:

        pat("flash spin fade").respond(cps = 1.0) { event ->
            box.display("atoms", "${event.value} @ ${event.start}")
        }

     If you want callback time slightly before event time, use lookahead:

        pat("flash spin fade").respondAhead(ahead = 0.05, clock = musicClock()) { event ->
            box.display("atoms", "${event.value} in ${event.start - now}")
        }

     Scheduled events also retain the source span of the atom that produced
     them, so live debug tools can point back into the pattern text:

        pat("bd [hh hh] sd").respond(cps = 2.0) { event ->
            __m("pat").patHighlight(event)
        }

 You can also map the parsed atom text immediately:

        data class Cue(val color: String, val gain: Float)

        val cues = pat("calm bright danger") { atom ->
            when (atom) {
                "calm" -> Cue("#0af", 0.15f)
                "bright" -> Cue("#fff", 0.4f)
                else -> Cue("#f80", 0.8f)
            }
        }

 Important units and expectations:

 - `start`, `end`, and `duration` on scheduled events are in seconds.
 - `cps` still means cycles per second; that is the conversion boundary from
   cycle space into scheduled real time.
 - Most pattern combinators are pure transforms over cycle queries.
 - `respond(...)` is a coroutine event driver. `respondAhead(...)` lets the
   callback see future events before their target `start`.
 - `asNotes(...)` is the memoized bridge when you want the same future schedule
   to drive playback logic and piano-roll inspection without recomputing it.

 Three companion files extend this one, and are worth knowing about before you reach for a
 `map { }`:

 - `PatSignals.kt` — continuous/signal patterns (`sine`, `saw`, `perlin`, `rand`) and the
   discretisers that turn them back into events (`segment`, `struct`, `range`, `quantise`).
 - `PatAlgebra.kt` — how two patterns of different types meet, and whose rhythm survives
   (`|+|` / `|+` / `+|`, `#`, and named parameters via `with(gain(...), pan(...))`).
 - `PatComposition.kt` — arrangement (`off`, `superimpose`, `jux`, `iter`, `linger`, `randcat`,
   `shuffle`) and pitch (`scale`, `chords`, `voiceLead`).

 The intentionally generic hooks in this file are `respond(...)` and
 `respondAhead(...)`: atoms can drive synth voices, visuals, property
 mutations, OSC, sample playback, box launches, or any other live-coded
 behavior. If the pattern language evolves away from Pat over time, `pat(...)`
 is the stable user-facing name we expect to keep.
 */
package afield.four.synth

import afield.Box
import afield.KotlinInterface
import afield.PreparedSnippet
import afield.RawDecoration
import afield.decorationState
import afield.locationForTextStringOnStack
import afield.logger.lprintln
import afield.stdlib.Marker
import java.math.BigInteger
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.script.experimental.api.ResultValue
import kotlinx.coroutines.runBlocking

data class PatQ(val n: BigInteger, val d: BigInteger) : Comparable<PatQ> {
    init { require(d != BigInteger.ZERO) { "Denominator cannot be 0" } }

    companion object {
        val ZERO = PatQ(BigInteger.ZERO, BigInteger.ONE)
        val ONE = PatQ(BigInteger.ONE, BigInteger.ONE)

        fun of(n: Long, d: Long = 1) = normalize(BigInteger.valueOf(n), BigInteger.valueOf(d))
        fun of(n: Int, d: Int = 1) = of(n.toLong(), d.toLong())

        fun fromDouble(x: Double, denom: Long = 1_000_000L): PatQ =
            of((x * denom).toLong(), denom)

        private fun normalize(n: BigInteger, d: BigInteger): PatQ {
            var nn = n
            var dd = d
            if (dd.signum() < 0) {
                nn = nn.negate()
                dd = dd.negate()
            }
            val g = nn.abs().gcd(dd)
            return PatQ(nn.divide(g), dd.divide(g))
        }
    }

    private fun norm(n: BigInteger, d: BigInteger) = Companion.normalize(n, d)

    operator fun plus(other: PatQ) = norm(n * other.d + other.n * d, d * other.d)
    operator fun minus(other: PatQ) = norm(n * other.d - other.n * d, d * other.d)
    operator fun times(other: PatQ) = norm(n * other.n, d * other.d)
    operator fun div(other: PatQ) = norm(n * other.d, d * other.n)
    operator fun unaryMinus() = PatQ(n.negate(), d)

    override fun compareTo(other: PatQ): Int = (n * other.d).compareTo(other.n * d)

    fun toDouble(): Double =
        n.toBigDecimal().divide(d.toBigDecimal(), 12, java.math.RoundingMode.HALF_UP).toDouble()

    override fun toString(): String = if (d == BigInteger.ONE) n.toString() else "$n/$d"
}

data class PatArc(val start: PatQ, val end: PatQ) {
    init { require(end > start) { "Arc end must be > start" } }

    val dur: PatQ
        get() = end - start

    fun contains(t: PatQ): Boolean = t >= start && t < end

    fun shift(by: PatQ) = PatArc(start + by, end + by)

    fun scale(by: PatQ) = PatArc(start * by, end * by)

    /**
     * Applies [f] to both ends. Every time transform in this file should go through this rather than
     * rebuilding a `PatArc` by hand, so that an event's [PatEvent.whole] can be carried along by the
     * same map — see [PatEvent.mapTime].
     *
     * [f] may be decreasing (`rev` reflects time about the cycle), in which case the image is still
     * the interval between the two results — just with the ends the other way round.
     */
    inline fun map(f: (PatQ) -> PatQ): PatArc {
        val a = f(start)
        val b = f(end)
        return if (b > a) PatArc(a, b) else PatArc(b, a)
    }

    fun intersect(other: PatArc): PatArc? {
        val s = if (start > other.start) start else other.start
        val e = if (end < other.end) end else other.end
        return if (e > s) PatArc(s, e) else null
    }

    override fun toString(): String = "$start -> $end"
}

data class PatParseFrame(
    val className: String?,
    val methodName: String?,
    val fileName: String?,
    val lineNumber: Int
) {
    override fun toString(): String = listOfNotNull(
        className,
        methodName?.let { ".$it" },
        fileName?.let { " ($it:$lineNumber)" }
    ).joinToString("").ifBlank { "frame:$lineNumber" }
}

data class PatParseContext(
    val source: String,
    val lineHint: Int? = null,
    val stackTrace: List<PatParseFrame> = emptyList()
) {
    override fun toString(): String {
        val line = lineHint?.let { ", lineHint=$it" } ?: ""
        val stack = if (stackTrace.isEmpty()) "" else ", stack=${stackTrace.size}"
        return "PatParseContext(source=${source.prettyQuoted(40)}$line$stack)"
    }
}

data class PatSourceSpan(
    val context: PatParseContext,
    val start: Int,
    val endExclusive: Int
) {
    init {
        require(start in 0..endExclusive) { "start must be in 0..endExclusive" }
        require(endExclusive <= context.source.length) { "endExclusive must be within source length" }
    }

    val source: String
        get() = context.source

    val lineHint: Int?
        get() = context.lineHint

    val stackTrace: List<PatParseFrame>
        get() = context.stackTrace

    val text: String
        get() = source.substring(start, endExclusive)

    val length: Int
        get() = endExclusive - start

    override fun toString(): String {
        val line = lineHint?.let { ", line=$it" } ?: ""
        return "PatSourceSpan(${start}..$endExclusive${line}, text=${text.prettyQuoted(24)})"
    }
}

/**
 * One event: a value sounding over [arc].
 *
 * [arc] is the **part** — the slice of the event that the query being answered can actually see. A
 * query never returns anything outside its own window, so an event longer than the window, or one
 * straddling the window's edge, comes back clipped. [whole] is the **logical** extent it was clipped
 * from: where the note really starts and ends, regardless of who is looking.
 *
 * Keeping both is what makes [hasOnset] answerable, and [hasOnset] is what stops a note being played
 * twice. `pat("a b c").slow(2)` puts `b` across the bar line at `2/3 → 4/3`; a per-cycle scheduler
 * asks for `[0,1)` and then `[1,2)`, and gets `b` back both times. Without [whole] those two
 * fragments are indistinguishable from two separate notes, and the note is struck twice — once at
 * `2/3` and again at the bar line. With it, only the fragment whose part starts where the whole
 * starts is an onset, and `b` is struck once, for its full length. This is Tidal's whole/part
 * distinction and it exists for exactly this reason.
 *
 * `whole == null` means an **analog** event: a sample of a continuous signal, which has no intrinsic
 * start because it was never a note in the first place. Analog events count as their own onset, so a
 * signal read per cycle behaves the way you would expect; discretise with `segment` to turn one into
 * events with real onsets.
 *
 * Move an event with [mapTime] and re-value it with [withValue]; both carry [whole] across. Building
 * a new `PatEvent` positionally from an old one silently drops it back to the part, which is the bug
 * described above.
 */
data class PatEvent<A>(
    val arc: PatArc,
    val value: A,
    val sourceSpan: PatSourceSpan? = null,
    val annotations: Map<String, String> = emptyMap(),
    val additionalSpans: List<PatSourceSpan> = emptyList(),
    val whole: PatArc? = arc
) {
    /**
     * True when this fragment carries the start of its event, so a scheduler should strike it.
     *
     * Analog events (`whole == null`) are always onsets: there is no earlier fragment they could be
     * the tail of.
     */
    val hasOnset: Boolean
        get() = whole == null || whole.start == arc.start

    /** The event's real length — [whole] where there is one, otherwise the part that was visible. */
    val extent: PatArc
        get() = whole ?: arc

    fun shift(by: PatQ) = copy(arc = arc.shift(by), whole = whole?.shift(by))
    fun scale(by: PatQ) = copy(arc = arc.scale(by), whole = whole?.scale(by))

    /** Moves the event in time, applying [f] to the part and the whole alike. */
    inline fun mapTime(f: (PatQ) -> PatQ) = copy(arc = arc.map(f), whole = whole?.map(f))

    /** Same event, same time, different value — keeps [whole], which a positional rebuild drops. */
    fun <B> withValue(newValue: B): PatEvent<B> =
        PatEvent(arc, newValue, sourceSpan, annotations, additionalSpans, whole)

    /** Restricts the visible part to [part] without touching where the event logically begins. */
    fun clipTo(part: PatArc) = copy(arc = part, whole = whole)

    fun mergeAnnotations(extra: Map<String, String>) = if (extra.isEmpty()) this else copy(annotations = annotations + extra)
    fun addSpan(span: PatSourceSpan?) = if (span == null) this else copy(additionalSpans = additionalSpans + span)

    override fun toString(): String {
        val source = sourceSpan?.let { ", source=$it" } ?: ""
        val ann = if (annotations.isEmpty()) "" else ", annotations=$annotations"
        val extra = if (additionalSpans.isEmpty()) "" else ", +${additionalSpans.size} spans"
        // Only worth showing when it differs from the part — otherwise every event grows a duplicate.
        val extent = when {
            whole == null -> ", analog"
            whole != arc -> ", whole=$whole"
            else -> ""
        }
        return "PatEvent(arc=$arc$extent, value=${synthPrettyValue(value)}$source$ann$extra)"
    }
}

data class PatScheduledEvent<A>(
    val start: Double,
    val end: Double,
    val value: A,
    val cycleIndex: Long,
    val arc: PatArc,
    val sourceSpan: PatSourceSpan? = null,
    val annotations: Map<String, String> = emptyMap(),
    val additionalSpans: List<PatSourceSpan> = emptyList()
) {
    init {
        require(start.isFinite()) { "start must be finite" }
        require(end.isFinite()) { "end must be finite" }
        require(end >= start) { "end must be >= start" }
    }

    val duration: Double
        get() = end - start

    val sourceText: String?
        get() = sourceSpan?.text

    operator fun get(key: String): String? = annotations[key]

    val allSpans: List<PatSourceSpan>
        get() = listOfNotNull(sourceSpan) + additionalSpans

    override fun toString(): String {
        val source = sourceText?.let { ", source=${it.prettyQuoted(24)}" } ?: ""
        val ann = if (annotations.isEmpty()) "" else ", annotations=$annotations"
        val extra = if (additionalSpans.isEmpty()) "" else ", +${additionalSpans.size} spans"
        return "PatScheduledEvent(start=${start.prettyNumber()}, end=${end.prettyNumber()}, value=${synthPrettyValue(value)}, cycle=$cycleIndex, arc=$arc$source$ann$extra)"
    }
}

class PatPattern<A>(private val renderAbs: (PatArc) -> List<PatEvent<A>>) {

    fun events(absArc: PatArc): List<PatEvent<A>> = renderAbs(absArc)

    /**
     * The events in playing order, one cycle at a time, each yielded **once** at its own start.
     *
     * Querying cycle by cycle is what a scheduler has to do — it cannot ask for all of time — but it
     * means any event crossing a bar line comes back from two consecutive queries, clipped both
     * times. Yielding both fragments strikes the note twice: `pat("a b c").slow(2)` would fire `b` at
     * `2/3` and again at the bar line, because three steps do not divide into two cycles. So only the
     * fragment carrying the onset is yielded, and it is widened back to its [PatEvent.whole] so the
     * consumer gets the note's real length rather than the part that fitted inside one cycle.
     *
     * A note already sounding when [fromCycle] begins is therefore *not* replayed: its onset is in an
     * earlier cycle, which is the same answer a musician would give.
     */
    fun eventSequence(fromCycle: Long = 0): Sequence<PatEvent<A>> = sequence {
        var cycle = fromCycle
        while (true) {
            val arc = PatArc(PatQ.of(cycle), PatQ.of(cycle + 1))
            val events = events(arc).sortedBy { it.arc.start.toDouble() }
            for (event in events) {
                if (!event.hasOnset) continue
                yield(if (event.whole != null) event.copy(arc = event.whole) else event)
            }
            cycle++
        }
    }

    fun scheduled(
        cps: Number = 1.0,
        fromCycle: Long? = null,
        startAt: Number = 0.0,
        clock: PatClock? = defaultPatClock()
    ): Sequence<PatScheduledEvent<A>> = sequence {
        val cpsValue = cps.toDouble().coerceAtLeast(1e-6)
        val secPerCycle = 1.0 / cpsValue
        val offset = startAt.toDouble()
        val queryStart = clock?.now ?: offset
        val firstCycle = when {
            fromCycle != null -> fromCycle
            clock == null -> 0L
            else -> floor((queryStart - offset) * cpsValue).toLong()
        }
        for (event in eventSequence(firstCycle)) {
            val scheduled = PatScheduledEvent(
                start = offset + event.arc.start.toDouble() * secPerCycle,
                end = offset + event.arc.end.toDouble() * secPerCycle,
                value = event.value,
                cycleIndex = qFloor(event.arc.start),
                arc = event.arc,
                sourceSpan = event.sourceSpan,
                annotations = event.annotations,
                additionalSpans = event.additionalSpans
            )
            if (clock == null || scheduled.start + 1e-9 >= queryStart) {
                yield(scheduled)
            }
        }
    }

    fun <B> map(transform: (A) -> B): PatPattern<B> = PatPattern { absArc ->
        events(absArc).map { it.withValue(transform(it.value)) }
    }

    fun respond(
        cps: Number = 1.0,
        fromCycle: Long? = null,
        dispatchOnMain: Boolean = true,
        clock: PatClock? = defaultPatClock(),
        ahead: Number = 0.0,
        callback: PatResponseScope<A>.(PatScheduledEvent<A>) -> Unit
    ): PatResponseSpec<A> = PatResponseSpec(
        pattern = this,
        cps = cps.toDouble().coerceAtLeast(1e-6),
        fromCycle = fromCycle,
        dispatchOnMain = dispatchOnMain,
        clock = clock,
        ahead = ahead.toDouble().coerceAtLeast(0.0),
        callback = callback
    )

    fun respondAhead(
        ahead: Number,
        cps: Number = 1.0,
        fromCycle: Long? = null,
        dispatchOnMain: Boolean = true,
        clock: PatClock? = defaultPatClock(),
        callback: PatResponseScope<A>.(PatScheduledEvent<A>) -> Unit
    ): PatResponseSpec<A> = respond(
        cps = cps,
        fromCycle = fromCycle,
        dispatchOnMain = dispatchOnMain,
        clock = clock,
        ahead = ahead,
        callback = callback
    )

    override fun toString(): String = "PatPattern(...)"
}

private val PAT_CYCLE = PatArc(PatQ.ZERO, PatQ.ONE)

fun <A> fromCycle(renderCycle: (PatArc) -> List<PatEvent<A>>): PatPattern<A> =
    fromCycleIndexed { subArc, _ -> renderCycle(subArc) }

fun <A> fromCycleIndexed(renderCycle: (PatArc, Long) -> List<PatEvent<A>>): PatPattern<A> =
    PatPattern { absArc ->
        val startTile = qFloor(absArc.start)
        val endTile = qCeil(absArc.end)
        val out = mutableListOf<PatEvent<A>>()
        for (tile in startTile until endTile) {
            val base = PatQ.of(tile)
            val tileAbs = PatArc(base, base + PatQ.ONE)
            val interAbs = absArc.intersect(tileAbs) ?: continue
            val localSpan = interAbs.shift(-base)
            out += renderCycle(localSpan, tile).map { it.shift(base) }
        }
        out
    }

fun <A> pure(value: A, sourceSpan: PatSourceSpan? = null): PatPattern<A> = fromCycle { subArc ->
    val inter = PAT_CYCLE.intersect(subArc) ?: return@fromCycle emptyList()
    // The event is the whole cycle; `inter` is only as much of it as this query asked to see. Every
    // clipped event in the language is born here, so this is where `whole` has to be set — see
    // [PatEvent].
    listOf(PatEvent(inter, value, sourceSpan, whole = PAT_CYCLE))
}

fun <A> silence(): PatPattern<A> = PatPattern { emptyList() }

fun <A> within(window: PatArc, pattern: PatPattern<A>): PatPattern<A> =
    fromCycleIndexed { subArc, tile ->
        val base = PatQ.of(tile)
        val absSub = subArc.shift(base)
        val windowAbs = PatArc(base + window.start, base + window.end)
        val interAbs = absSub.intersect(windowAbs) ?: return@fromCycleIndexed emptyList()
        val toLocal = PatQ.ONE / window.dur
        val localInWindow = PatArc(
            (interAbs.start - windowAbs.start) * toLocal,
            (interAbs.end - windowAbs.start) * toLocal
        )
        val childAbs = localInWindow.shift(base)
        pattern.events(childAbs).map { event ->
            event.mapTime { t -> windowAbs.start + (t - base) * window.dur - base }
        }
    }

/**
 * Maps cycle [cycleIndex] of [pattern] onto the **absolute** arc [window].
 *
 * The absolute-time counterpart of [within], which reads its window as a local arc inside a single
 * cycle (`base + window.start`). That distinction was a real bug: `MiniPat.compilePolyLayer` builds
 * absolute windows — repetition *r* of a three-slot layer against an eight-pulse grid sits at
 * `[3r/8, (3r+1)/8)`, which leaves `[0, 1)` as soon as `r >= 3` — and handed them to `within`, whose
 * tile arithmetic then found no intersection. The result was that **every polymeter went silent
 * after cycle 0**: `pat("{bd hh 808:4}%8")` played eight events in the first bar and nothing ever
 * again. `MiniNotationTest` pins the fix.
 *
 * [cycleIndex] is the child's own cycle, so per-cycle constructs inside a polymeter layer — `<a b>`,
 * `every`, `?` — advance once per repetition rather than being frozen on cycle 0.
 */
internal fun <A> patFocus(window: PatArc, cycleIndex: Long, pattern: PatPattern<A>): PatPattern<A> =
    PatPattern { arc ->
        val visible = arc.intersect(window) ?: return@PatPattern emptyList()
        val span = window.dur
        if (span <= PatQ.ZERO) return@PatPattern emptyList()
        val base = PatQ.of(cycleIndex)
        val localStart = base + (visible.start - window.start) / span
        val localEnd = base + (visible.end - window.start) / span
        if (localEnd <= localStart) return@PatPattern emptyList()
        pattern.events(PatArc(localStart, localEnd)).map { event ->
            event.mapTime { t -> window.start + (t - base) * span }
        }
    }

/**
 * Fits every pattern into one cycle, side by side.
 *
 * **Note for readers coming from Tidal: this is Tidal's `fastcat`, not Tidal's `cat`.** Tidal's `cat`
 * plays one pattern per cycle, which here is [slowcat]. [fastcat] is available as a spelling of this
 * one that says so out loud.
 */
fun <A> cat(vararg patterns: PatPattern<A>): PatPattern<A> =
    fromCycleIndexed { subArc, tile ->
        val base = PatQ.of(tile)
        val absSub = subArc.shift(base)
        val out = mutableListOf<PatEvent<A>>()
        val count = max(1, patterns.size)
        for (index in 0 until count) {
            val segment = PatArc(PatQ.of(index, count), PatQ.of(index + 1, count))
            val windowAbs = PatArc(base + segment.start, base + segment.end)
            val interAbs = absSub.intersect(windowAbs) ?: continue
            out += within(segment, patterns[index]).events(interAbs).map { it.shift(-base) }
        }
        out
    }

/** [cat] under the name Tidal gives this behaviour, for when the distinction matters to the reader. */
fun <A> fastcat(vararg patterns: PatPattern<A>): PatPattern<A> = cat(*patterns)

/**
 * Plays one pattern per cycle, in turn — **Tidal's `cat`** (also its `slowcat`).
 *
 * `slowcat(pat("a b"), pat("c"))` reads `a b` in one cycle and `c` in the next, where [cat] would
 * fit both into every cycle. It is what mini-notation's `<a b>` alternation has always been built
 * from; that construction was inlined in the compiler until [chunk] needed it too.
 */
fun <A> slowcat(vararg patterns: PatPattern<A>): PatPattern<A> {
    if (patterns.isEmpty()) return silence()
    return fromCycleIndexed { subArc, tile ->
        val base = PatQ.of(tile)
        val size = patterns.size.toLong()
        val index = (((tile % size) + size) % size).toInt()
        patterns[index].events(subArc.shift(base)).map { it.shift(-base) }
    }
}

fun <A> stack(vararg patterns: PatPattern<A>): PatPattern<A> =
    fromCycleIndexed { subArc, tile ->
        val base = PatQ.of(tile)
        val absSub = subArc.shift(base)
        patterns.flatMap { pattern -> pattern.events(absSub).map { it.shift(-base) } }
    }

fun <A> PatPattern<A>.rev(): PatPattern<A> =
    fromCycleIndexed { subArc, tile ->
        val base = PatQ.of(tile)
        val reflectedAbs = PatArc(base + PatQ.ONE - subArc.end, base + PatQ.ONE - subArc.start)
        events(reflectedAbs).map { event ->
            // Decreasing map — PatArc.map puts the reflected ends back in order.
            event.mapTime { t -> PatQ.ONE - (t - base) }
        }
    }

fun <A> PatPattern<A>.palindrome(): PatPattern<A> = cat(this, rev())

fun <A> PatPattern<A>.zoom(start: PatQ, len: PatQ): PatPattern<A> =
    fromCycleIndexed { subArc, tile ->
        val base = PatQ.of(tile)
        val absArc = PatArc(base + start + subArc.start * len, base + start + subArc.end * len)
        events(absArc).map { event -> event.mapTime { t -> (t - (base + start)) / len } }
    }

fun <A> PatPattern<A>.delay(by: PatQ): PatPattern<A> =
    fromCycleIndexed { subArc, tile ->
        val base = PatQ.of(tile)
        val absArc = subArc.shift(base - by)
        events(absArc).map { event -> event.shift(-base + by) }
    }

/**
 * Multiplies the pattern's timeline by [by] — the one time transform, in **absolute** cycle time.
 *
 * `stretch(PatQ.of(2))` is twice as slow and genuinely spans two cycles; `stretch(PatQ.of(1, 2))` is
 * twice as fast. Every other time operator in this file is defined in terms of it.
 *
 * This replaced three per-cycle-anchored implementations that all silently *dropped material*.
 * `pat("a b c d").slow(2)` used to give `a b` in every cycle — `c d` was never heard, because the
 * old implementation rescaled inside each cycle rather than across cycles. Same for mini-notation's
 * `/n`, for `timeScale`, `density`, and therefore for `chunk`, whose whole point is to apply a
 * transform to a different quarter each cycle and which used to apply it to the same one forever.
 * `PatContractTest` pins the difference: `slow` is now (correctly) a per-cycle-varying pattern.
 */
fun <A> PatPattern<A>.stretch(by: PatQ): PatPattern<A> {
    if (by <= PatQ.ZERO) return this
    return PatPattern { arc ->
        // The whole rides along with the part: an event stretched across a bar line keeps knowing
        // where it really began, which is what stops `eventSequence` striking it twice.
        events(PatArc(arc.start / by, arc.end / by)).map { event -> event.scale(by) }
    }
}

/** [stretch] by an exact fraction — `fastBy(PatQ.of(3, 2))` is one and a half times as fast. */
fun <A> PatPattern<A>.fastBy(factor: PatQ): PatPattern<A> =
    if (factor <= PatQ.ZERO) this else stretch(PatQ.ONE / factor)

/** [stretch] by an exact fraction — `slowBy(PatQ.of(3, 2))` takes one and a half cycles per cycle. */
fun <A> PatPattern<A>.slowBy(factor: PatQ): PatPattern<A> = stretch(factor)

/**
 * Plays the pattern [n] times per cycle.
 *
 * Cycle-spanning, so `pat("<a b>").fast(2)` reads `a b` in one cycle rather than `a a`. For a
 * cycle-invariant pattern it is identical to the old `cat`-of-copies implementation.
 */
fun <A> PatPattern<A>.fast(n: Int): PatPattern<A> = fastBy(PatQ.of(max(1, n)))

/**
 * Takes [n] cycles to play the pattern once.
 *
 * `pat("a b c d").slow(2)` reads `a b` in one cycle and `c d` in the next — the second half really is
 * heard. See [stretch] for what this used to do instead.
 */
fun <A> PatPattern<A>.slow(n: Int): PatPattern<A> = stretch(PatQ.of(max(1, n)))

fun <A> PatPattern<A>.every(k: Int, transform: (PatPattern<A>) -> PatPattern<A>): PatPattern<A> =
    fromCycleIndexed { subArc, tile ->
        val use = if (k <= 0) this else if ((tile % k) == 0L) transform(this) else this
        val base = PatQ.of(tile)
        use.events(subArc.shift(base)).map { it.shift(-base) }
    }

fun <A> PatPattern<A>.switchEvery(k: Int, alt: PatPattern<A>): PatPattern<A> = every(k) { alt }

fun <A> PatPattern<A>.whenCycle(
    predicate: (Long) -> Boolean,
    transform: (PatPattern<A>) -> PatPattern<A>
): PatPattern<A> =
    fromCycleIndexed { subArc, tile ->
        val use = if (predicate(tile)) transform(this) else this
        val base = PatQ.of(tile)
        use.events(subArc.shift(base)).map { it.shift(-base) }
    }

fun <A> join(patterns: PatPattern<PatPattern<A>>): PatPattern<A> =
    PatPattern { absArc ->
        patterns.events(absArc).flatMap { controller -> controller.value.events(controller.arc) }
    }

fun <A> patOf(vararg values: A): PatPattern<A> = cat(*values.map { pure(it) }.toTypedArray())

fun <A> PatPattern<A>.subdivide(n: Int): PatPattern<A> =
    cat(*Array(max(1, n)) { zoom(PatQ.ZERO, PatQ.ONE / PatQ.of(max(1, n))) })

fun <A> PatPattern<A>.sometimesBy(
    probability: Double,
    salt: Int = 0,
    transform: (PatPattern<A>) -> PatPattern<A>
): PatPattern<A> =
    fromCycleIndexed { subArc, tile ->
        val unit = toUnitDouble(splitmix64(tile xor (salt.toLong() shl 1)))
        val use = if (unit < probability.coerceIn(0.0, 1.0)) transform(this) else this
        val base = PatQ.of(tile)
        use.events(subArc.shift(base)).map { it.shift(-base) }
    }

fun <A> PatPattern<A>.sometimes(transform: (PatPattern<A>) -> PatPattern<A>) =
    sometimesBy(0.5, 0, transform)

fun <A> PatPattern<A>.degradeBy(probability: Double, salt: Int = 0): PatPattern<A> =
    mapEventsPerCycle { cycle, events ->
        val p = probability.coerceIn(0.0, 1.0)
        events.filter { event ->
            val unit = toUnitDouble(splitmix64(eventKey(cycle, event.arc, salt)))
            unit >= p
        }
    }

fun <A> PatPattern<A>.degrade(): PatPattern<A> = degradeBy(0.5)

fun bjorklund(pulses: Int, steps: Int): List<Boolean> {
    require(pulses in 0..steps)
    if (pulses == 0) return List(steps) { false }
    if (pulses == steps) return List(steps) { true }

    var pattern = MutableList(pulses) { listOf(true) } + MutableList(steps - pulses) { listOf(false) }
    while (true) {
        val (head, tail) = pattern.partition { it == pattern.first() }
        if (tail.isEmpty() || head.size <= 1) break
        val next = mutableListOf<List<Boolean>>()
        val pairs = head.zip(tail)
        next.addAll(pairs.map { it.first + it.second })
        if (head.size > tail.size) next.addAll(head.drop(tail.size))
        if (tail.size > head.size) next.addAll(tail.drop(head.size))
        pattern = next
    }
    return pattern.flatten()
}

fun euclid(pulses: Int, steps: Int, rotation: Int = 0): PatPattern<Boolean> =
    fromCycle { subArc ->
        val stepCount = steps.coerceAtLeast(1)
        val mask = bjorklund(pulses, stepCount).rotateRight(rotation)
        buildList {
            for (index in 0 until stepCount) {
                if (index < mask.size && mask[index]) {
                    val window = PatArc(PatQ.of(index, stepCount), PatQ.of(index + 1, stepCount))
                    val inter = window.intersect(subArc) ?: continue
                    // The pulse lasts `window`; `inter` is only as much of it as this query can see.
                    add(PatEvent(inter, true, whole = window))
                }
            }
        }
    }

fun <A> mask(mask: PatPattern<Boolean>, on: PatPattern<A>): PatPattern<A> =
    fromCycleIndexed { subArc, tile ->
        val base = PatQ.of(tile)
        val absSub = subArc.shift(base)
        val maskEvents = mask.events(absSub)
        val out = mutableListOf<PatEvent<A>>()
        for (maskEvent in maskEvents) {
            if (!maskEvent.value) continue
            out += within(maskEvent.arc.shift(-base), on).events(absSub).map { it.shift(-base) }
        }
        out
    }

fun <A> euclidFill(pulses: Int, steps: Int, rotation: Int = 0, on: PatPattern<A>): PatPattern<A> =
    mask(euclid(pulses, steps, rotation), on)

fun <A> PatPattern<A>.striate(n: Int, rotation: Int = 0): PatPattern<A> =
    mapEventsPerCycle { _, events ->
        val slices = n.coerceAtLeast(1)
        val rot = ((rotation % slices) + slices) % slices
        buildList {
            for (event in events) {
                val step = event.arc.dur / PatQ.of(slices)
                for (index in 0 until slices) {
                    val rotated = (index + rot) % slices
                    val start = event.arc.start + step * PatQ.of(rotated)
                    add(PatEvent(PatArc(start, start + step), event.value, event.sourceSpan, event.annotations, event.additionalSpans))
                }
            }
        }
    }

fun <A> PatPattern<A>.stutter(n: Int, gap: PatQ = PatQ.ZERO): PatPattern<A> =
    mapEventsPerCycle { _, events ->
        val repeats = n.coerceAtLeast(1)
        buildList {
            for (event in events) {
                val slot = event.arc.dur / PatQ.of(repeats)
                val play = slot * (PatQ.ONE - gap)
                for (index in 0 until repeats) {
                    val start = event.arc.start + slot * PatQ.of(index)
                    val end = start + play
                    if (end > start) add(PatEvent(PatArc(start, end), event.value, event.sourceSpan, event.annotations, event.additionalSpans))
                }
            }
        }
    }

fun <A> PatPattern<A>.whenMod(
    k: Int,
    n: Int,
    transform: (PatPattern<A>) -> PatPattern<A>
): PatPattern<A> = whenCycle({ tile -> (tile % n) == (k % n).toLong() }, transform)

/**
 * Keeps only the events whose **onset** satisfies [predicate], which is asked about cycle position.
 *
 * Filtering on the onset rather than on the visible part is the point: an event straddling the edge
 * of a region either belongs to it or does not, and clipping it in half would invent a note. Tidal's
 * `playWhen` — and only expressible here once [PatEvent.whole] could say where an event begins.
 */
fun <A> PatPattern<A>.playWhen(predicate: (cyclePos: PatQ) -> Boolean): PatPattern<A> =
    PatPattern { arc ->
        events(arc).filter { event ->
            val start = event.extent.start
            predicate(start - PatQ.of(qFloor(start)))
        }
    }

/**
 * Applies [transform] only to the events starting inside [window] of each cycle; the rest play on
 * untouched.
 *
 * This is **Tidal's `within`**. Field's existing [within] is a different operator that happens to
 * share the name: it squeezes a whole pattern *into* a window, which is Tidal's `compress`.
 */
fun <A> PatPattern<A>.withinCycle(window: PatArc, transform: (PatPattern<A>) -> PatPattern<A>): PatPattern<A> {
    val inside = { pos: PatQ -> pos >= window.start && pos < window.end }
    return stack(transform(this).playWhen(inside), playWhen { !inside(it) })
}

/**
 * Applies [transform] to a different `1/n` of the cycle each cycle, walking through all [n] of them
 * before it repeats.
 *
 * `pat("a b c d").chunk(4) { it.rev() }` reverses the first quarter in cycle 0, the second in cycle
 * 1, and so on: the transform travels through the bar. That travelling is the whole point of the
 * operator, and this is the first implementation here that does it. `chunk` used to be
 * `slow(n).let(transform).fast(n)` — a real and useful Tidal operator, but a *different* one. That
 * is `inside`: it is cycle-invariant and applies the transform to the same material every cycle. The
 * two were conflated, and the commit and documentation that introduced the conflation claimed
 * `chunk` had been fixed to walk when it never had. [inside] now carries the old behaviour under its
 * own name.
 */
fun <A> PatPattern<A>.chunk(n: Int, transform: (PatPattern<A>) -> PatPattern<A>): PatPattern<A> {
    val parts = max(1, n)
    // slowcat, not cat: the point is a different quarter *each cycle*. Field's `cat` fits all of
    // its arguments into one cycle, which is what made the first draft of this walk nowhere.
    return slowcat(*Array(parts) { index ->
        withinCycle(PatArc(PatQ.of(index, parts), PatQ.of(index + 1, parts)), transform)
    })
}

/**
 * Slows by [n], applies [transform], speeds back up — so the transform sees [n] cycles' worth of
 * material as a single cycle. Tidal's `inside`.
 *
 * Cycle-invariant, which is exactly how it differs from [chunk].
 */
fun <A> PatPattern<A>.inside(n: Int, transform: (PatPattern<A>) -> PatPattern<A>): PatPattern<A> =
    slow(max(1, n)).let(transform).fast(max(1, n))

/** The mirror of [inside]: speeds up, transforms, slows back down. Tidal's `outside`. */
fun <A> PatPattern<A>.outside(n: Int, transform: (PatPattern<A>) -> PatPattern<A>): PatPattern<A> =
    fast(max(1, n)).let(transform).slow(max(1, n))

/** Scales density by an exact ratio: `timeScale(PatQ.of(3, 2))` is half again as fast. [stretch]'s inverse. */
fun <A> PatPattern<A>.timeScale(ratio: PatQ): PatPattern<A> = fastBy(ratio)

fun <A> PatPattern<A>.density(ratio: Double): PatPattern<A> =
    timeScale(if (ratio <= 0.0) PatQ.ONE else PatQ.fromDouble(ratio))

enum class ArpMode {
    UP, DOWN, UPDOWN, DOWNUP, RANDOM, PINGPONG
}

/**
 * Turns each event into an arpeggio over [notes].
 *
 * Each input event is subdivided into at least one, and otherwise [speed], steps and mapped through
 * [mode]; an empty note list produces silence. After the first traversal, [hold] keeps using
 * `notes.last()` instead of wrapping, and [seed] makes random mode repeatable.
 */
fun <A> PatPattern<A>.arp(
    notes: List<A>,
    mode: ArpMode = ArpMode.UP,
    speed: Int = 1,
    hold: Boolean = false,
    seed: Int = 0
): PatPattern<A> {
    if (notes.isEmpty()) return silence()
    
    val noteCount = notes.size
    val arpLength = max(1, speed)
    val modeSequence = when (mode) {
        ArpMode.UP -> (0 until noteCount).toList()
        ArpMode.DOWN -> (noteCount - 1 downTo 0).toList()
        ArpMode.UPDOWN -> (0 until noteCount).toList() + (noteCount - 2 downTo 1).toList()
        ArpMode.DOWNUP -> (noteCount - 1 downTo 0).toList() + (1 until noteCount - 1).toList()
        ArpMode.PINGPONG -> (0 until noteCount).toList() + (noteCount - 2 downTo 1).toList()
        ArpMode.RANDOM -> {
            val rand = kotlin.random.Random(seed)
            (0 until noteCount).map { rand.nextInt(noteCount) }
        }
    }
    
    return fromCycleIndexed { subArc, tile ->
        val base = PatQ.of(tile)
        val absArc = subArc.shift(base)
        val events = events(absArc)
        val out = mutableListOf<PatEvent<A>>()
        
        var eventCounter = 0
        for (event in events) {
            val eventDur = event.arc.dur
            val stepDur = eventDur / PatQ.of(arpLength)
            
            for (step in 0 until arpLength) {
                val globalStep = eventCounter * arpLength + step
                val noteIdx = (globalStep % modeSequence.size).let { 
                    if (globalStep >= modeSequence.size && hold) noteCount - 1 
                    else modeSequence[it]
                }
                
                val stepStart = event.arc.start + stepDur * PatQ.of(step)
                val stepEnd = if (step == arpLength - 1) event.arc.end 
                              else event.arc.start + stepDur * PatQ.of(step + 1)
                
                out += PatEvent(
                    PatArc(stepStart, stepEnd).shift(-base),
                    notes[noteIdx],
                    event.sourceSpan,
                    event.annotations,
                    event.additionalSpans
                )
            }
            eventCounter++
        }
        out
    }
}

fun <A> PatPattern<A>.arp(
    vararg notes: A,
    mode: ArpMode = ArpMode.UP,
    speed: Int = 1,
    hold: Boolean = false,
    seed: Int = 0
): PatPattern<A> = arp(notes.toList(), mode, speed, hold, seed)

fun PatPattern<String>.arp(indexPattern: String): PatPattern<String> = arp(pat(indexPattern))

fun PatPattern<String>.arp(indexPattern: PatPattern<String>): PatPattern<String> = fromCycleIndexed { subArc, tile ->
    val base = PatQ.of(tile)
    val absArc = subArc.shift(base)
    val sourceEvents = events(absArc)
    if (sourceEvents.isEmpty()) return@fromCycleIndexed emptyList()

    val groupedByArc = sourceEvents
        .groupBy { it.arc }
        .entries
        .sortedWith(compareBy({ it.key.start }, { it.key.end }))

    val out = mutableListOf<PatEvent<String>>()

    for ((arc, chordEvents) in groupedByArc) {
        if (chordEvents.isEmpty()) continue
        val indexEvents = indexPattern.events(arc)

        for (indexEvent in indexEvents) {
            val inter = indexEvent.arc.intersect(arc) ?: continue
            val parsedIndex = parseArpIndexToken(indexEvent.value) ?: continue
            val selected = chordEvents[floorMod(parsedIndex.toLong(), chordEvents.size)]
            out += PatEvent(inter.shift(-base), selected.value, selected.sourceSpan, selected.annotations, selected.additionalSpans + listOfNotNull(indexEvent.sourceSpan))
        }
    }

    out
}

fun PatPattern<String>.zip(valuePattern: String, key: String): PatPattern<String> = zip(pat(valuePattern), key)

fun PatPattern<String>.zip(valuePattern: PatPattern<String>, key: String): PatPattern<String> = fromCycleIndexed { subArc, tile ->
    val base = PatQ.of(tile)
    val absArc = subArc.shift(base)
    val sourceEvents = events(absArc)
    if (sourceEvents.isEmpty()) return@fromCycleIndexed emptyList()

    val zipEvents = valuePattern.events(absArc)
    sourceEvents.map { event ->
        val overlapping = zipEvents.firstOrNull { it.arc.intersect(event.arc) != null }
        // `shift`, not `copy(arc = …)`: the whole has to return to local time along with the part,
        // or the event ends up claiming to begin a cycle away from where it actually sounds.
        if (overlapping != null) {
            event.mergeAnnotations(mapOf(key to overlapping.value)).addSpan(overlapping.sourceSpan).shift(-base)
        } else {
            event.shift(-base)
        }
    }
}

object MiniPat {
    sealed interface Node
    data class Atom(val name: String, val sourceSpan: PatSourceSpan) : Node
    data object Rest : Node
    data class Seq(val items: List<Node>) : Node
    data class Stk(val layers: List<Node>) : Node
    data class Choice(val alts: List<Node>) : Node
    data class RandomChoice(val alts: List<Node>, val salt: Int) : Node
    data class Poly(val parts: List<Node>, val subdivision: Int? = null) : Node
    data class Repeat(val expr: Node, val times: Int) : Node
    data class Fast(val expr: Node, val n: Int) : Node
    data class Slow(val expr: Node, val n: Int) : Node
    data class Elongate(val expr: Node, val steps: Int) : Node
    data class Euclid(val expr: Node, val pulses: Int, val steps: Int, val offset: Int = 0) : Node
    /** `a?` / `a?0.3` — drop this term with the given probability, independently per event. */
    data class Degrade(val expr: Node, val probability: Double, val salt: Int) : Node
    data class Expr(val body: String, val sourceSpan: PatSourceSpan) : Node

    fun parseToAst(
        source: String,
        parseContext: PatParseContext = PatParseContext(source)
    ): Node = Parser(source, parseContext).parseTop()

    fun compile(ast: Node): PatPattern<String> = compileNode(ast)

    fun parse(
        source: String,
        parseContext: PatParseContext = PatParseContext(source)
    ): PatPattern<String> = compile(parseToAst(source, parseContext))

    /** Characters that may follow a standalone `.` / `_` / `!` step marker. */
    private val STANDALONE_FOLLOWERS = setOf(']', '}', '>', ')', ',', '|', '.', '_', '!')

    private class Parser(private val src: String, private val parseContext: PatParseContext) {
        private var index = 0
        private var nextSalt = 1

        fun parseTop(): Node {
            val node = parseStack(stop = setOf())
            skipWs()
            if (!eof()) error("Unexpected '${peek()}' at $index")
            return node
        }

        private fun parseRandom(stop: Set<Char>): Node {
            val alts = mutableListOf<Node>()
            do {
                alts += parseStack(stop + '|')
                skipWs()
            } while (match('|'))
            return when (alts.size) {
                0 -> Seq(emptyList())
                1 -> alts[0]
                else -> RandomChoice(alts, nextSalt++)
            }
        }

        private fun parseStack(stop: Set<Char>): Node {
            val layers = mutableListOf<Node>()
            do {
                layers += parseSeq(stop + ',')
                skipWs()
            } while (match(','))
            return when (layers.size) {
                0 -> Seq(emptyList())
                1 -> layers[0]
                else -> Stk(layers)
            }
        }

        /**
         * A sequence of terms, with the three standalone step markers Tidal allows between them:
         *
         *  - `.` groups what precedes it against what follows: `bd sd . hh hh hh` is
         *    `[bd sd] [hh hh hh]`, two equal halves rather than five equal fifths;
         *  - `_` extends the previous step by one slot: `bd _ _ sd` is `bd@3 sd`.
         *
         * A bare `!` — `bd ! !` for `bd bd bd` — needs nothing here: [parseTerm]'s postfix loop
         * already skips whitespace before an operator, so it sees each `!` itself. What it needed was
         * for repeats to *accumulate* instead of nest; see `accumulateRepeat`.
         *
         * Both are only markers when they stand alone — followed by whitespace, the end of the
         * string, or a closing/separating character. That is what keeps `0.5`, `my_atom`, `bd!2` and
         * `g:(0,0.12)` parsing exactly as they did before: their `.`, `_` and `!` are inside a token,
         * so `readAtom` consumes them and this loop never sees them.
         */
        private fun parseSeq(stop: Set<Char>): Node {
            val groups = mutableListOf(mutableListOf<Node>())
            while (true) {
                skipWs()
                if (eof() || peekIn(stop) || peek() == ',') break
                when {
                    isStandaloneMarker('.') -> {
                        index++
                        groups += mutableListOf<Node>()
                    }
                    isStandaloneMarker('_') -> {
                        index++
                        groups.last().let { g -> if (g.isNotEmpty()) g[g.size - 1] = elongateOnce(g.last()) }
                    }
                    else -> groups.last() += parseTerm(stop)
                }
                skipWs()
                if (eof() || peekIn(stop) || peek() == ',') break
            }
            val used = groups.filter { it.isNotEmpty() }
            // One group is the pre-existing path, byte for byte: no `.` means no behaviour change.
            if (used.size <= 1) {
                val terms = used.firstOrNull() ?: emptyList<Node>()
                return when (terms.size) {
                    0 -> Seq(emptyList())
                    1 -> terms[0]
                    else -> Seq(terms)
                }
            }
            return Seq(used.map { group -> if (group.size == 1) group[0] else Seq(group) })
        }

        /** True when [marker] is at the cursor *and* stands alone rather than opening a token. */
        private fun isStandaloneMarker(marker: Char): Boolean {
            if (eof() || src[index] != marker) return false
            val next = src.getOrNull(index + 1) ?: return true
            return next.isWhitespace() || next in STANDALONE_FOLLOWERS
        }

        private fun accumulateRepeat(node: Node, count: Int): Node {
            val extra = (count.coerceAtLeast(1) - 1)
            return when (node) {
                is Repeat -> node.copy(times = node.times.coerceAtLeast(1) + extra)
                else -> Repeat(node, count.coerceAtLeast(1))
            }
        }

        private fun elongateOnce(node: Node): Node = when (node) {
            is Elongate -> node.copy(steps = node.steps.coerceAtLeast(1) + 1)
            else -> Elongate(node, 2)
        }



        private fun parseTerm(stop: Set<Char>): Node {
            var node = parseFactor(stop)
            while (true) {
                skipWs()
                when {
                    match('(') -> {
                        val pulses = readRequiredInt("Expected Euclidean pulse count at $index")
                        skipWs()
                        expect(',')
                        val steps = readRequiredInt("Expected Euclidean step count at $index")
                        skipWs()
                        val offset = if (match(',')) readRequiredInt("Expected Euclidean offset at $index") else 0
                        skipWs()
                        expect(')')
                        node = Euclid(node, pulses, steps, offset)
                    }
                    match('%') -> {
                        val subdivision = readRequiredInt("Expected polymetric subdivision at $index")
                        node = when (node) {
                            is Poly -> node.copy(subdivision = subdivision)
                            else -> error("% is only supported after polymetric { ... } forms at $index")
                        }
                    }
                    match('?') -> node = Degrade(node, readDouble() ?: 0.5, nextSalt++)
                    match('@') -> node = Elongate(node, readInt() ?: 2)
                    // Accumulate rather than nest. This loop already tolerates whitespace before a
                    // postfix operator, so `bd ! !` reaches here as two separate `!`s — and nesting
                    // them (`Repeat(Repeat(bd, 2), 2)`) gives FOUR copies where Tidal gives three.
                    // Each `!n` adds n-1 more copies of what is already there.
                    match('!') -> node = accumulateRepeat(node, readInt() ?: 2)
                    match('*') -> node = Fast(node, readInt() ?: 2)
                    match('/') -> node = Slow(node, readInt() ?: 2)
                    else -> return node
                }
            }
        }

        private fun parseFactor(stop: Set<Char>): Node {
            skipWs()
            return when {
                match('[') -> {
                    val inner = parseRandom(stop + ']')
                    expect(']')
                    inner
                }
                match('<') -> {
                    val alts = mutableListOf<Node>()
                    while (true) {
                        skipWs()
                        if (match('>')) break
                        alts += parseTerm(stop + '>')
                    }
                    Choice(alts)
                }
                peek() == '{' && index + 1 < src.length && src[index + 1] == '{' -> {
                    readExpr()
                }
                match('{') -> {
                    val parts = mutableListOf<Node>()
                    while (true) {
                        skipWs()
                        if (match('}')) break
                        parts += parseSeq(stop + setOf('}', ','))
                        skipWs()
                        match(',')
                    }
                    Poly(parts)
                }
                match('~') -> Rest
                else -> {
                    val atom = readAtom()
                    if (atom == null) error("Unexpected '${peek()}' at $index")
                    atom
                }
            }
        }

        private fun skipWs() {
            while (!eof() && src[index].isWhitespace()) index++
        }

        private fun eof(): Boolean = index >= src.length

        private fun peek(): Char = src[index]

        private fun peekIn(set: Set<Char>): Boolean = !eof() && set.contains(src[index])

        private fun match(c: Char): Boolean =
            if (!eof() && src[index] == c) {
                index++
                true
            } else {
                false
            }

        private fun expect(c: Char) {
            if (!match(c)) error("Expected '$c' at $index")
        }

        private fun readInt(): Int? {
            skipWs()
            val start = index
            while (!eof() && src[index].isDigit()) index++
            return if (index > start) src.substring(start, index).toInt() else null
        }

        private fun readRequiredInt(message: String): Int = readInt() ?: error(message)

        /** A bare decimal immediately after a modifier, as in `bd?0.3`. No leading whitespace. */
        private fun readDouble(): Double? {
            val start = index
            while (!eof() && (src[index].isDigit() || src[index] == '.')) index++
            if (index <= start) return null
            return src.substring(start, index).toDoubleOrNull()
        }

        private fun readAtom(): Atom? {
            val start = index
            while (!eof()) {
                val ch = src[index]
                val ok = ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == ':' || ch == '.' ||
                    ch == '+' || ch == '\''
                if (!ok) break
                index++
                if (ch == ':' && !eof() && src[index] == '(') {
                    readBalancedAtomParens()
                }
            }
            if (index <= start) return null
            return Atom(
                src.substring(start, index),
                PatSourceSpan(parseContext, start, index)
            )
        }

        private fun readBalancedAtomParens() {
            var depth = 0
            while (!eof()) {
                val ch = src[index]
                index++
                when (ch) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth <= 0) return
                    }
                }
            }
        }

        private fun readExpr(): Expr {
            val outerStart = index
            index += 2 // skip opening {{
            val bodyStart = index
            var depth = 1
            while (index + 1 < src.length) {
                if (src[index] == '{' && src[index + 1] == '{') {
                    depth++
                    index += 2
                    continue
                }
                if (src[index] == '}' && src[index + 1] == '}') {
                    depth--
                    if (depth == 0) {
                        val body = src.substring(bodyStart, index)
                        val span = PatSourceSpan(parseContext, outerStart, index + 2)
                        index += 2 // skip closing }}
                        return Expr(body, span)
                    }
                    index += 2
                    continue
                }
                index++
            }
            error("Unterminated {{...}} expression starting at $outerStart")
        }
    }

    private fun compileNode(node: Node): PatPattern<String> = when (node) {
        is Atom -> compileAtom(node)
        is Expr -> compileExpr(node)
        is Rest -> silence()
        is Seq -> when (node.items.size) {
            0 -> silence()
            1 -> compileNode(node.items[0])
            else -> compileSeq(node.items)
        }
        is Stk -> when (node.layers.size) {
            0 -> silence()
            1 -> compileNode(node.layers[0])
            else -> stack(*node.layers.map(::compileNode).toTypedArray())
        }
        // `<a b>` is one alternative per cycle, which is exactly [slowcat].
        is Choice -> slowcat(*node.alts.map(::compileNode).toTypedArray())
        is RandomChoice -> {
            val compiled = node.alts.map(::compileNode)
            fromCycleIndexed { subArc, tile ->
                if (compiled.isEmpty()) {
                    emptyList()
                } else {
                    val base = PatQ.of(tile)
                    val absSub = subArc.shift(base)
                    val idx = floorMod(splitmix64(tile xor (node.salt.toLong() shl 17)), compiled.size)
                    compiled[idx].events(absSub).map { it.shift(-base) }
                }
            }
        }
        is Poly -> compilePoly(node)
        is Repeat -> {
            val times = node.times.coerceAtLeast(0)
            val body = compileNode(node.expr)
            if (times <= 0) silence() else cat(*Array(times) { body })
        }
        is Fast -> compileNode(node.expr).fast(node.n.coerceAtLeast(1))
        is Slow -> compileNode(node.expr).slow(node.n.coerceAtLeast(1))
        is Elongate -> compileNode(node.expr)
        is Degrade -> compileNode(node.expr).degradeBy(node.probability, node.salt)
        is Euclid -> {
            val pulses = node.pulses.coerceAtLeast(0)
            val steps = node.steps.coerceAtLeast(1)
            euclidFill(pulses, steps, rotation = -node.offset, on = compileNode(node.expr))
        }
    }

    /**
     * An atom becomes a one-event-per-cycle pattern. A trailing `:n` — the sample-index suffix —
     * is additionally surfaced as an `n` **annotation**, so `pat("bd:3")` is both the atom `bd:3`
     * that every existing caller already sees and an event carrying `n = 3` for the parameter
     * algebra. Nothing is taken away; use [dropSampleIndex] if you want the bare name.
     */
    private fun compileAtom(node: Atom): PatPattern<String> {
        val index = patSampleIndexSuffix(node.name)
        if (index == null) return pure(node.name, node.sourceSpan)
        val annotations = mapOf("n" to index.toString())
        return fromCycle { subArc ->
            val inter = PatArc(PatQ.ZERO, PatQ.ONE).intersect(subArc) ?: return@fromCycle emptyList()
            // A whole-cycle atom, exactly like `pure` — clipped for the query, whole for the player.
            listOf(PatEvent(inter, node.name, node.sourceSpan, annotations, whole = PAT_CYCLE))
        }
    }

    private fun compileExpr(node: Expr): PatPattern<String> {
        val body = node.body
        val span = node.sourceSpan
        return fromCycleIndexed { subArc, tile ->
            val base = PatQ.of(tile)
            val inter = PatArc(PatQ.ZERO, PatQ.ONE).intersect(subArc) ?: return@fromCycleIndexed emptyList()
            val midT = ((inter.start + inter.end) / PatQ.of(2)).toDouble()
            val value = PatExprEvaluator.evaluate(body, cycle = tile, t = midT)
                ?: return@fromCycleIndexed emptyList() // failed expression → silence
            listOf(PatEvent(inter, value, span, whole = PAT_CYCLE))
        }
    }

    private fun compileSeq(items: List<Node>): PatPattern<String> {
        val expanded = expandReplicatedItems(items)
        if (expanded.isEmpty()) return silence()
        val totalSlots = expanded.sumOf(::slotWidth).coerceAtLeast(1)
        return fromCycleIndexed { subArc, tile ->
            val base = PatQ.of(tile)
            val absSub = subArc.shift(base)
            val out = mutableListOf<PatEvent<String>>()
            var cursor = 0
            for (item in expanded) {
                val width = slotWidth(item).coerceAtLeast(1)
                val segment = PatArc(
                    PatQ.of(cursor, totalSlots),
                    PatQ.of(cursor + width, totalSlots)
                )
                val windowAbs = PatArc(base + segment.start, base + segment.end)
                val interAbs = absSub.intersect(windowAbs)
                if (interAbs != null) {
                    out += within(segment, compileNode(sequenceBody(item))).events(interAbs).map { it.shift(-base) }
                }
                cursor += width
            }
            out
        }
    }

    private fun compilePoly(node: Poly): PatPattern<String> {
        val layers = node.parts.filterNot(::isSilentSeq)
        if (layers.isEmpty()) return silence()
        val pulseCount = (node.subdivision ?: slotsForLayer(layers.first())).coerceAtLeast(1)
        return stack(*layers.map { compilePolyLayer(it, pulseCount) }.toTypedArray())
    }

    private fun compilePolyLayer(layer: Node, pulseCount: Int): PatPattern<String> {
        val items = expandLayerItems(layer)
        if (items.isEmpty()) return silence()
        val totalSlots = items.sumOf(::slotWidth).coerceAtLeast(1)
        val slotStarts = IntArray(items.size)
        run {
            var cursor = 0
            items.forEachIndexed { index, item ->
                slotStarts[index] = cursor
                cursor += slotWidth(item).coerceAtLeast(1)
            }
        }
        val maxWidth = maxSlotWidth(items)
        return PatPattern { absArc ->
            val startPulse = qFloor(absArc.start * PatQ.of(pulseCount)) - maxWidth
            val endPulse = qCeil(absArc.end * PatQ.of(pulseCount))
            val out = mutableListOf<PatEvent<String>>()
            items.forEachIndexed { index, item ->
                val width = slotWidth(item).coerceAtLeast(1)
                val startMod = slotStarts[index].toLong()
                val repetitionStart = ceilDiv(startPulse - startMod, totalSlots.toLong())
                val repetitionEnd = floorDiv(endPulse - startMod - 1L, totalSlots.toLong())
                for (repetition in repetitionStart..repetitionEnd) {
                    val globalStartSlot = startMod + repetition * totalSlots.toLong()
                    val window = PatArc(
                        PatQ.of(globalStartSlot, pulseCount.toLong()),
                        PatQ.of(globalStartSlot + width.toLong(), pulseCount.toLong())
                    )
                    val inter = absArc.intersect(window) ?: continue
                    // `patFocus`, not `within`: these windows are ABSOLUTE and run off past cycle 1,
                    // while `within` reads its window as a local, inside-one-cycle arc. That is why
                    // polymeter used to fall silent after cycle 0 — see patFocus's KDoc.
                    out += patFocus(window, repetition, compileNode(sequenceBody(item))).events(inter)
                }
            }
            out
        }
    }

    private fun expandReplicatedItems(items: List<Node>): List<Node> = buildList {
        items.forEach { item ->
            if (item is Repeat) {
                repeat(item.times.coerceAtLeast(0)) {
                    add(item.expr)
                }
            } else {
                add(item)
            }
        }
    }

    private fun expandLayerItems(node: Node): List<Node> = when (node) {
        is Seq -> expandReplicatedItems(node.items)
        else -> expandReplicatedItems(listOf(node))
    }

    private fun slotsForLayer(node: Node): Int = expandLayerItems(node).sumOf(::slotWidth).coerceAtLeast(1)

    private fun maxSlotWidth(items: List<Node>): Long =
        items.maxOfOrNull { slotWidth(it).coerceAtLeast(1) }?.toLong() ?: 1L

    private fun slotWidth(node: Node): Int = when (node) {
        is Elongate -> node.steps.coerceAtLeast(1)
        else -> 1
    }

    private fun sequenceBody(node: Node): Node = when (node) {
        is Elongate -> node.expr
        else -> node
    }

    private fun isSilentSeq(node: Node): Boolean = node is Seq && node.items.isEmpty()
}

/**
 * Thread-local context for evaluating `{{...}}` expressions inside patterns.
 * Expression bodies can read [cycle] (0-based cycle index) and [t] (fractional
 * position inside the queried arc, 0.0..1.0).
 */
object PatExprContext {
    internal val current = ThreadLocal<PatExprFrame?>()

    /** The 0-based cycle index of the current query. */
    val cycle: Long get() = current.get()?.cycle ?: 0L

    /** Fractional position inside the queried sub-arc (0.0 .. 1.0). */
    val t: Double get() = current.get()?.t ?: 0.0
}

internal data class PatExprFrame(val cycle: Long, val t: Double)

private inline fun <T> withPatExprFrame(cycle: Long, t: Double, block: () -> T): T {
    val prev = PatExprContext.current.get()
    PatExprContext.current.set(PatExprFrame(cycle, t))
    return try {
        block()
    } finally {
        PatExprContext.current.set(prev)
    }
}

/**
 * Compiles and caches Kotlin expressions that appear inside `{{...}}` tokens
 * in pattern strings.  Each unique expression body is compiled once; evaluations
 * receive [PatExprContext] via a ThreadLocal so the expression can reference
 * `PatExprContext.cycle` and `PatExprContext.t`.
 */
private object PatExprEvaluator {
    private val lock = Any()
    private val compiled = ConcurrentHashMap<String, PreparedSnippet>()
    private val compiler = KotlinInterface(filePrefix = "PAT_EXPR_")

    fun evaluate(body: String, cycle: Long, t: Double): String? {
        val source = buildSource(body)
        val snippet = synchronized(lock) {
            compiled[source] ?: runBlocking {
                compiler.compileSnippet(source, implicits = emptyList(), onCompileError = {
                    lprintln("error,pat", "pat expression compile error: $it")
                })
            }?.also { compiled[source] = it }
        }
        if (snippet == null) {
            lprintln("error,pat", "pat expression failed to compile: $body")
            return null
        }
        return withPatExprFrame(cycle, t) {
            try {
                val result = snippet.evalAndWait(
                    updateContext = false,
                    implicits = emptyList(),
                    onEvalError = { lprintln("error,pat", "pat expression eval error: $it") },
                    onEvalException = { lprintln("error,pat", "pat expression eval exception: $it") }
                )
                when (result) {
                    is ResultValue.Value -> result.value?.toString()
                    is ResultValue.Error -> {
                        lprintln("error,pat", "pat expression runtime error: ${result.error}")
                        null
                    }
                    else -> null
                }
            } catch (ex: Throwable) {
                lprintln("error,pat", "pat expression threw: ${ex.message}")
                null
            }
        }
    }

    private fun buildSource(body: String): String = """
        import kotlin.math.*
        import afield.four.synth.PatExprContext
        run {
            val cycle = PatExprContext.cycle
            val t = PatExprContext.t
            $body
        }
    """.trimIndent()
}

/**
 * The integer after the last `:` of an atom name, when the whole tail is digits — mini-notation's
 * sample-index suffix. `"bd:3"` is `3`; `"bd"`, `"g:(0,0.1)"` and `"808:x"` are all `null`.
 */
fun patSampleIndexSuffix(name: String): Int? {
    val colon = name.lastIndexOf(':')
    if (colon <= 0 || colon == name.length - 1) return null
    val tail = name.substring(colon + 1)
    if (!tail.all { it.isDigit() }) return null
    return tail.toIntOrNull()
}

/** Strips a trailing `:n` sample-index suffix from every atom, leaving the `n` annotation intact. */
fun PatPattern<String>.dropSampleIndex(): PatPattern<String> = PatPattern { arc ->
    events(arc).map { event ->
        val colon = event.value.lastIndexOf(':')
        if (patSampleIndexSuffix(event.value) == null) event
        else event.copy(value = event.value.substring(0, colon))
    }
}

fun pat(source: String): PatPattern<String> = MiniPat.parse(source, parseContext = currentPatParseContext(source))

fun <A> pat(source: String, atomMapper: (String) -> A): PatPattern<A> =
    MiniPat.parse(source, parseContext = currentPatParseContext(source)).map(atomMapper)

/**
 * Quick-inspect shorthand: `pat("bd hh sd").peek()` returns the first [n] scheduled events.
 * Equivalent to `.scheduled(cps).take(n).toList()`.
 */
fun <A> PatPattern<A>.peek(n: Int = 8, cps: Number = 1.0): List<PatScheduledEvent<A>> =
    scheduled(cps = cps, clock = null).take(n).toList()

/**
 * Map pattern atoms to notes using a simple pitch mapping.
 * Each atom string is parsed as a MIDI pitch number.
 * Non-numeric atoms are silently dropped.
 *
 * `pat("60 64 67 72").notes(cps = 2.0)` — no lambda needed for pure MIDI patterns.
 */
fun PatPattern<String>.notes(
    cps: Number = 1.0,
    clock: PatClock? = defaultPatClock(),
    basePitch: Int = 0,
    velocity: Number = 1.0,
    durationScale: Number = 0.95
): NoteProcess = asNotes(cps = cps, clock = clock) { event ->
    val pitch = event.value.toIntOrNull() ?: return@asNotes emptyList()
    listOf(
        ScheduledNote(
            start = event.start,
            duration = event.duration * durationScale.toDouble(),
            pitch = (basePitch + pitch).toFloat(),
            velocity = velocity.toFloat(),
            label = event.value
        )
    )
}

/**
 * Map pattern atoms as scale degrees relative to a root pitch.
 * `pat("0 3 5 7").degrees(root = 36, cps = 2.0)` — each atom is added to `root`.
 */
fun PatPattern<String>.degrees(
    root: Int = 60,
    cps: Number = 1.0,
    clock: PatClock? = defaultPatClock(),
    velocity: Number = 1.0,
    durationScale: Number = 0.95
): NoteProcess = notes(cps = cps, clock = clock, basePitch = root, velocity = velocity, durationScale = durationScale)

fun <A> PatPattern<A>.asNotes(
    cps: Number = 1.0,
    clock: PatClock? = defaultPatClock(),
    historyRetentionSeconds: Double = 8.0,
    defaultPastSeconds: Double = 1.0,
    defaultFutureSeconds: Double = 6.0,
    mapper: (PatScheduledEvent<A>) -> Iterable<ScheduledNote>
): NoteProcess = NoteProcess(
    iteratorFactory = {
        val events = scheduled(cps = cps, clock = clock).iterator()
        object : Iterator<ScheduledNote> {
            private val pending = ArrayDeque<ScheduledNote>()

            private fun prime() {
                while (pending.isEmpty() && events.hasNext()) {
                    val mapped = mapper(events.next()).sortedBy { it.start }
                    pending.addAll(mapped)
                }
            }

            override fun hasNext(): Boolean {
                prime()
                return pending.isNotEmpty()
            }

            override fun next(): ScheduledNote {
                if (!hasNext()) throw NoSuchElementException("No more mapped notes")
                return pending.removeFirst()
            }
        }
    },
    historyRetentionSeconds = historyRetentionSeconds,
    defaultPastSeconds = defaultPastSeconds,
    defaultFutureSeconds = defaultFutureSeconds
).reset()

class PatResponseScope<A> internal constructor(
    val box: Box,
    val pattern: PatPattern<A>,
    val cps: Double,
    val clock: PatClock?,
    val ahead: Double,
    val launchInstant: Instant,
    internal val launchNanoTime: Long = System.nanoTime()
) {
    val launchElapsed: Double
        get() = (System.nanoTime() - launchNanoTime).toDouble() / 1_000_000_000.0

    val now: Double
        get() = clock?.now ?: launchElapsed

    override fun toString(): String = synthPrettyObject(
        this,
        "pattern" to pattern,
        "cps" to cps,
        "clock" to clock,
        "ahead" to ahead,
        "launchInstant" to launchInstant
    )
}

class PatResponseSpec<A> internal constructor(
    internal val pattern: PatPattern<A>,
    internal val cps: Double,
    internal val fromCycle: Long?,
    internal val dispatchOnMain: Boolean,
    internal val clock: PatClock?,
    internal val ahead: Double,
    internal val callback: PatResponseScope<A>.(PatScheduledEvent<A>) -> Unit
) {
    override fun toString(): String = synthPrettyObject(
        this,
        "pattern" to pattern,
        "cps" to cps,
        "fromCycle" to fromCycle,
        "dispatchOnMain" to dispatchOnMain,
        "clock" to clock,
        "ahead" to ahead
    )
}

private fun String.prettyQuoted(limit: Int): String = "\"${if (length <= limit) this else take(limit - 3) + "..."}\""

private fun Double.prettyNumber(): String = String.format(Locale.US, "%.4f", this).trimEnd('0').trimEnd('.')

// internal rather than private so the other Pat* files — and SonicPiPatterns' `sonicPiLive` — can
// spell the same default rather than each inventing one; a second answer to "which clock?" is how
// two patterns in one box end up on two timelines.
internal fun defaultPatClock(): PatClock? {
    return Box.context.get()?.patClock ?: musicClock()
}

internal fun <A> PatPattern<A>.mapEventsPerCycle(
    transform: (cycle: Long, eventsLocal: List<PatEvent<A>>) -> List<PatEvent<A>>
): PatPattern<A> = fromCycleIndexed { subArc, tile ->
    val base = PatQ.of(tile)
    val absArc = subArc.shift(base)
    val eventsLocal = events(absArc).map { it.shift(-base) }
    transform(tile, eventsLocal)
}

internal fun qFloor(x: PatQ): Long {
    val divRem = x.n.divideAndRemainder(x.d)
    val q = divRem[0]
    val r = divRem[1]
    return if (x.n.signum() < 0 && r != BigInteger.ZERO) q.subtract(BigInteger.ONE).toLong() else q.toLong()
}

internal fun qCeil(x: PatQ): Long {
    val exact = x.n.mod(x.d) == BigInteger.ZERO
    return if (exact) x.n.divide(x.d).toLong() else qFloor(x) + 1
}

private fun floorDiv(n: Long, d: Long): Long {
    require(d > 0) { "Divisor must be positive" }
    val q = n / d
    val r = n % d
    return if (r != 0L && n < 0L) q - 1 else q
}

private fun ceilDiv(n: Long, d: Long): Long {
    require(d > 0) { "Divisor must be positive" }
    return -floorDiv(-n, d)
}

internal fun splitmix64(seed: Long): Long {
    var z = seed + -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x4b1b3c39ec3d2dcdL
    z = (z xor (z ushr 27)) * -0x441d67c49c2c49c9L
    return z xor (z ushr 31)
}

internal fun toUnitDouble(x: Long): Double {
    val value = x ushr 11
    return value.toDouble() / (1L shl 53).toDouble()
}

internal fun floorMod(value: Long, modulus: Int): Int {
    require(modulus > 0) { "Modulus must be positive" }
    val mod = value % modulus.toLong()
    return if (mod < 0L) (mod + modulus).toInt() else mod.toInt()
}

private fun parseArpIndexToken(token: String): Int? = token.trim().toIntOrNull()

internal fun hashQ(q: PatQ): Int = 31 * q.n.hashCode() + q.d.hashCode()

/**
 * The per-event hash `degradeBy` uses to decide what to drop.
 *
 * Internal rather than private because `unDegradeBy` must be its exact complement: two independent
 * implementations of "the same hash" would silently overlap or leave gaps, and `sometimesEventBy`
 * would then double or drop notes.
 */
internal fun eventKey(cycle: Long, arc: PatArc, salt: Int): Long {
    var h = 1469598103934665603L
    fun mix(i: Int) {
        h = (h xor i.toLong()) * 1099511628211L
    }
    mix(cycle.hashCode())
    mix(hashQ(arc.start))
    mix(hashQ(arc.end))
    mix(salt)
    return h
}

private fun <T> List<T>.rotateRight(steps: Int): List<T> {
    if (isEmpty()) return this
    val amount = ((steps % size) + size) % size
    if (amount == 0) return this
    val split = size - amount
    return subList(split, size) + subList(0, split)
}

private val PAT_HIGHLIGHT_EXTRA_COLORS = listOf("#f0a", "#0fa", "#fa0", "#a0f", "#0ff", "#ff0")

fun <A> Marker.patHighlight(
    event: PatScheduledEvent<A>,
    key: String = "active",
    color: String = "#0af"
): PatScheduledEvent<A> {
    val hintLine = patHighlightHintLine()

    // Primary span
    val decorationKey = "__pat_highlight__${name}__$key"
    val span = event.sourceSpan
    if (span == null) {
        box.decorationState.remove(decorationKey)
    } else {
        val range = findPatDecorationRange(box, span, hintLine = span.lineHint ?: hintLine)
        if (range == null) {
            box.decorationState.remove(decorationKey)
        } else {
            val className = "patAtom_${name}_${key}".map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
            val css = """
                .${className} {
                    background: color-mix(in srgb, ${color} 28%, transparent);
                    border-bottom: 1px solid color-mix(in srgb, ${color} 92%, white);
                    box-shadow: inset 0 0 0 1px color-mix(in srgb, ${color} 45%, transparent);
                    border-radius: 3px;
                }
            """.trimIndent().replace("\n", " ")
            box.decorationState[decorationKey] = RawDecoration(
                range.lineStart, range.colStart, range.lineEnd, range.colEnd, className, mapOf(className to css)
            )
        }
    }

    // Additional spans (from arp, zip, etc.)
    val maxExtra = 6
    for (i in 0 until maxExtra) {
        val extraKey = "__pat_highlight__${name}__${key}__x$i"
        if (i < event.additionalSpans.size) {
            val extraSpan = event.additionalSpans[i]
            val extraRange = findPatDecorationRange(box, extraSpan, hintLine = extraSpan.lineHint ?: hintLine)
            if (extraRange != null) {
                val extraColor = PAT_HIGHLIGHT_EXTRA_COLORS[i % PAT_HIGHLIGHT_EXTRA_COLORS.size]
                val extraClassName = "patAtom_${name}_${key}_x$i".map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
                val extraCss = """
                    .${extraClassName} {
                        background: color-mix(in srgb, ${extraColor} 28%, transparent);
                        border-bottom: 1px solid color-mix(in srgb, ${extraColor} 92%, white);
                        box-shadow: inset 0 0 0 1px color-mix(in srgb, ${extraColor} 45%, transparent);
                        border-radius: 3px;
                    }
                """.trimIndent().replace("\n", " ")
                box.decorationState[extraKey] = RawDecoration(
                    extraRange.lineStart, extraRange.colStart, extraRange.lineEnd, extraRange.colEnd, extraClassName, mapOf(extraClassName to extraCss)
                )
            } else {
                box.decorationState.remove(extraKey)
            }
        } else {
            box.decorationState.remove(extraKey)
        }
    }

    return event
}

fun Marker.clearPatHighlight(key: String = "active") {
    box.decorationState.remove("__pat_highlight__${name}__$key")
    for (i in 0 until 6) {
        box.decorationState.remove("__pat_highlight__${name}__${key}__x$i")
    }
}

private data class PatDecorationRange(
    val lineStart: Int,
    val colStart: Int,
    val lineEnd: Int,
    val colEnd: Int
)

private fun currentPatParseContext(source: String): PatParseContext {
    val box = Box.context.get() ?: return PatParseContext(source)
    val stack = Exception().stackTrace.toList()
    val relevantFrames = stack.filter { frame ->
        frame.lineNumber > 0 && (frame.fileName?.contains(box.id) == true)
    }.map { frame ->
        PatParseFrame(
            className = frame.className,
            methodName = frame.methodName,
            fileName = frame.fileName,
            lineNumber = frame.lineNumber
        )
    }
    val lineHint = locationForTextStringOnStack("pat(", box, st = stack.toTypedArray())?.line
        ?: relevantFrames.firstOrNull()?.lineNumber
    return PatParseContext(
        source = source,
        lineHint = lineHint,
        stackTrace = relevantFrames
    )
}

private fun Marker.patHighlightHintLine(): Int? =
    locationForTextStringOnStack(".patHighlight(", box)?.line

private fun findPatDecorationRange(box: Box, span: PatSourceSpan, hintLine: Int?): PatDecorationRange? {
    val anchor = locatePatSourceLiteral(box, span.context, hintLine) ?: return null
    val start = anchor.sourceStart + span.start
    val endExclusive = anchor.sourceStart + span.endExclusive
    return codeRangeFromAbsoluteOffsets(anchor.code, start, endExclusive)
}

private data class PatSourceAnchor(val code: String, val sourceStart: Int, val lineStart: Int)

private fun locatePatSourceLiteral(box: Box, context: PatParseContext, hintLine: Int?): PatSourceAnchor? {
    val code = box.code
    val source = context.source
    if (source.isEmpty()) return null
    val occurrences = mutableListOf<Int>()
    var searchFrom = 0
    while (searchFrom <= code.length - source.length) {
        val found = code.indexOf(source, startIndex = searchFrom)
        if (found < 0) break
        occurrences += found
        searchFrom = found + 1
    }
    if (occurrences.isEmpty()) return null
    val frameLines = context.stackTrace
        .asSequence()
        .filter { it.lineNumber > 0 && (it.fileName?.contains(box.id) == true) }
        .map { it.lineNumber }
        .toList()
    val bestHint = hintLine ?: context.lineHint
    val chosen = occurrences.minWithOrNull(compareBy<Int> { occurrence ->
        val line = lineForAbsoluteOffset(code, occurrence)
        val stackDistance = frameLines.minOfOrNull { abs(it - line) } ?: Int.MAX_VALUE / 4
        val hintDistance = bestHint?.let { abs(it - line) } ?: stackDistance
        min(hintDistance, stackDistance)
    }.thenBy { occurrence ->
        val line = lineForAbsoluteOffset(code, occurrence)
        val stackDistance = frameLines.minOfOrNull { abs(it - line) } ?: Int.MAX_VALUE / 4
        val hintDistance = bestHint?.let { abs(it - line) } ?: stackDistance
        hintDistance + stackDistance
    }.thenBy { occurrence ->
        // Prefer occurrences that are exact string literals (bounded by quotes)
        val before = if (occurrence > 0) code[occurrence - 1] else ' '
        val after = if (occurrence + source.length < code.length) code[occurrence + source.length] else ' '
        if (before == '"' && after == '"') 0 else 1
    }.thenBy { it }) ?: return null
    return PatSourceAnchor(code = code, sourceStart = chosen, lineStart = lineForAbsoluteOffset(code, chosen))
}

private fun lineForAbsoluteOffset(code: String, offset: Int): Int {
    val safe = offset.coerceIn(0, code.length)
    var line = 1
    for (i in 0 until safe) {
        if (code[i] == '\n') line++
    }
    return line
}

private fun codeRangeFromAbsoluteOffsets(code: String, start: Int, endExclusive: Int): PatDecorationRange? {
    val safeStart = start.coerceIn(0, code.length)
    val safeEnd = endExclusive.coerceIn(safeStart, code.length)
    val effectiveEnd = if (safeEnd == safeStart && safeEnd < code.length) safeEnd + 1 else safeEnd
    val startLoc = lineColAt(code, safeStart)
    val endLoc = lineColAt(code, effectiveEnd)
    return PatDecorationRange(
        lineStart = startLoc.first,
        colStart = startLoc.second,
        lineEnd = endLoc.first,
        colEnd = endLoc.second
    )
}

private fun lineColAt(code: String, offset: Int): Pair<Int, Int> {
    val safe = offset.coerceIn(0, code.length)
    var line = 1
    var col = 1
    for (i in 0 until safe) {
        if (code[i] == '\n') {
            line++
            col = 1
        } else {
            col++
        }
    }
    return line to col
}
