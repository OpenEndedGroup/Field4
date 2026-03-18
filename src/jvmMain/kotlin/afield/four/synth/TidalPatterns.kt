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

 The intentionally generic hooks in this file are `respond(...)` and
 `respondAhead(...)`: atoms can drive synth voices, visuals, property
 mutations, OSC, sample playback, box launches, or any other live-coded
 behavior. If the pattern language evolves away from Pat over time, `pat(...)`
 is the stable user-facing name we expect to keep.
 */
package afield.four.synth

import afield.Box
import afield.RawDecoration
import afield.decorationState
import afield.locationForTextStringOnStack
import afield.stdlib.Marker
import java.math.BigInteger
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

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

data class PatEvent<A>(val arc: PatArc, val value: A, val sourceSpan: PatSourceSpan? = null) {
    fun shift(by: PatQ) = copy(arc = arc.shift(by))
    fun scale(by: PatQ) = copy(arc = arc.scale(by))

    override fun toString(): String {
        val source = sourceSpan?.let { ", source=$it" } ?: ""
        return "PatEvent(arc=$arc, value=${synthPrettyValue(value)}$source)"
    }
}

data class PatScheduledEvent<A>(
    val start: Double,
    val end: Double,
    val value: A,
    val cycleIndex: Long,
    val arc: PatArc,
    val sourceSpan: PatSourceSpan? = null
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

    override fun toString(): String {
        val source = sourceText?.let { ", source=${it.prettyQuoted(24)}" } ?: ""
        return "PatScheduledEvent(start=${start.prettyNumber()}, end=${end.prettyNumber()}, value=${synthPrettyValue(value)}, cycle=$cycleIndex, arc=$arc$source)"
    }
}

class PatPattern<A>(private val renderAbs: (PatArc) -> List<PatEvent<A>>) {

    fun events(absArc: PatArc): List<PatEvent<A>> = renderAbs(absArc)

    fun eventSequence(fromCycle: Long = 0): Sequence<PatEvent<A>> = sequence {
        var cycle = fromCycle
        while (true) {
            val arc = PatArc(PatQ.of(cycle), PatQ.of(cycle + 1))
            val events = events(arc).sortedBy { it.arc.start.toDouble() }
            for (event in events) yield(event)
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
                sourceSpan = event.sourceSpan
            )
            if (clock == null || scheduled.start + 1e-9 >= queryStart) {
                yield(scheduled)
            }
        }
    }

    fun <B> map(transform: (A) -> B): PatPattern<B> = PatPattern { absArc ->
        events(absArc).map { PatEvent(it.arc, transform(it.value), it.sourceSpan) }
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
    listOf(PatEvent(inter, value, sourceSpan))
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
            val startAbs = windowAbs.start + (event.arc.start - base) * window.dur
            val endAbs = windowAbs.start + (event.arc.end - base) * window.dur
            PatEvent(PatArc(startAbs, endAbs).shift(-base), event.value, event.sourceSpan)
        }
    }

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
            val startLocal = PatQ.ONE - (event.arc.end - base)
            val endLocal = PatQ.ONE - (event.arc.start - base)
            PatEvent(PatArc(startLocal, endLocal), event.value, event.sourceSpan)
        }
    }

fun <A> PatPattern<A>.palindrome(): PatPattern<A> = cat(this, rev())

fun <A> PatPattern<A>.zoom(start: PatQ, len: PatQ): PatPattern<A> =
    fromCycleIndexed { subArc, tile ->
        val base = PatQ.of(tile)
        val absArc = PatArc(base + start + subArc.start * len, base + start + subArc.end * len)
        events(absArc).map { event ->
            val startLocal = (event.arc.start - (base + start)) / len
            val endLocal = (event.arc.end - (base + start)) / len
            PatEvent(PatArc(startLocal, endLocal), event.value, event.sourceSpan)
        }
    }

fun <A> PatPattern<A>.delay(by: PatQ): PatPattern<A> =
    fromCycleIndexed { subArc, tile ->
        val base = PatQ.of(tile)
        val absArc = subArc.shift(base - by)
        events(absArc).map { event -> PatEvent(event.arc.shift(-base + by), event.value, event.sourceSpan) }
    }

fun <A> PatPattern<A>.fast(n: Int): PatPattern<A> = cat(*Array(max(1, n)) { this })

fun <A> PatPattern<A>.slow(n: Int): PatPattern<A> =
    fromCycleIndexed { subArc, tile ->
        val base = PatQ.of(tile)
        val factor = PatQ.of(1, max(1, n))
        val srcAbs = PatArc(base + subArc.start * factor, base + subArc.end * factor)
        events(srcAbs).map { event ->
            val startLocal = (event.arc.start - base) / factor
            val endLocal = (event.arc.end - base) / factor
            PatEvent(PatArc(startLocal, endLocal), event.value, event.sourceSpan)
        }
    }

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
                    add(PatEvent(inter, true))
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
                    add(PatEvent(PatArc(start, start + step), event.value, event.sourceSpan))
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
                    if (end > start) add(PatEvent(PatArc(start, end), event.value, event.sourceSpan))
                }
            }
        }
    }

fun <A> PatPattern<A>.whenMod(
    k: Int,
    n: Int,
    transform: (PatPattern<A>) -> PatPattern<A>
): PatPattern<A> = whenCycle({ tile -> (tile % n) == (k % n).toLong() }, transform)

fun <A> PatPattern<A>.chunk(n: Int, transform: (PatPattern<A>) -> PatPattern<A>): PatPattern<A> =
    slow(n).let(transform).fast(n)

fun <A> PatPattern<A>.timeScale(ratio: PatQ): PatPattern<A> =
    fromCycleIndexed { subArc, tile ->
        val base = PatQ.of(tile)
        val absArc = PatArc(base + subArc.start * ratio, base + subArc.end * ratio)
        events(absArc).map { event ->
            PatEvent(
                PatArc((event.arc.start - base) / ratio, (event.arc.end - base) / ratio),
                event.value,
                event.sourceSpan
            )
        }
    }

fun <A> PatPattern<A>.density(ratio: Double): PatPattern<A> =
    timeScale(if (ratio <= 0.0) PatQ.ONE else PatQ.fromDouble(ratio))

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

    fun parseToAst(
        source: String,
        parseContext: PatParseContext = PatParseContext(source)
    ): Node = Parser(source, parseContext).parseTop()

    fun compile(ast: Node): PatPattern<String> = compileNode(ast)

    fun parse(
        source: String,
        parseContext: PatParseContext = PatParseContext(source)
    ): PatPattern<String> = compile(parseToAst(source, parseContext))

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

        private fun parseSeq(stop: Set<Char>): Node {
            val terms = mutableListOf<Node>()
            while (true) {
                skipWs()
                if (eof() || peekIn(stop) || peek() == ',') break
                terms += parseTerm(stop)
                skipWs()
                if (eof() || peekIn(stop) || peek() == ',') break
            }
            return when (terms.size) {
                0 -> Seq(emptyList())
                1 -> terms[0]
                else -> Seq(terms)
            }
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
                    match('@') -> node = Elongate(node, readInt() ?: 2)
                    match('!') -> node = Repeat(node, readInt() ?: 2)
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

        private fun readAtom(): Atom? {
            val start = index
            while (!eof()) {
                val ch = src[index]
                val ok = ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == ':' || ch == '.' || ch == '+'
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
    }

    private fun compileNode(node: Node): PatPattern<String> = when (node) {
        is Atom -> pure(node.name, node.sourceSpan)
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
        is Choice -> {
            val compiled = node.alts.map(::compileNode)
            fromCycleIndexed { subArc, tile ->
                if (compiled.isEmpty()) {
                    emptyList()
                } else {
                    val base = PatQ.of(tile)
                    val absSub = subArc.shift(base)
                    val size = compiled.size.toLong()
                    val idx = ((tile % size) + size) % size
                    compiled[idx.toInt()].events(absSub).map { it.shift(-base) }
                }
            }
        }
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
        is Euclid -> {
            val pulses = node.pulses.coerceAtLeast(0)
            val steps = node.steps.coerceAtLeast(1)
            euclidFill(pulses, steps, rotation = -node.offset, on = compileNode(node.expr))
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
                    out += within(window, compileNode(sequenceBody(item))).events(inter)
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

private fun defaultPatClock(): PatClock? {
    return Box.context.get()?.patClock ?: musicClock()
}

private fun <A> PatPattern<A>.mapEventsPerCycle(
    transform: (cycle: Long, eventsLocal: List<PatEvent<A>>) -> List<PatEvent<A>>
): PatPattern<A> = fromCycleIndexed { subArc, tile ->
    val base = PatQ.of(tile)
    val absArc = subArc.shift(base)
    val eventsLocal = events(absArc).map { it.shift(-base) }
    transform(tile, eventsLocal)
}

private fun qFloor(x: PatQ): Long {
    val divRem = x.n.divideAndRemainder(x.d)
    val q = divRem[0]
    val r = divRem[1]
    return if (x.n.signum() < 0 && r != BigInteger.ZERO) q.subtract(BigInteger.ONE).toLong() else q.toLong()
}

private fun qCeil(x: PatQ): Long {
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

private fun splitmix64(seed: Long): Long {
    var z = seed + -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x4b1b3c39ec3d2dcdL
    z = (z xor (z ushr 27)) * -0x441d67c49c2c49c9L
    return z xor (z ushr 31)
}

private fun toUnitDouble(x: Long): Double {
    val value = x ushr 11
    return value.toDouble() / (1L shl 53).toDouble()
}

private fun floorMod(value: Long, modulus: Int): Int {
    require(modulus > 0) { "Modulus must be positive" }
    val mod = value % modulus.toLong()
    return if (mod < 0L) (mod + modulus).toInt() else mod.toInt()
}

private fun hashQ(q: PatQ): Int = 31 * q.n.hashCode() + q.d.hashCode()

private fun eventKey(cycle: Long, arc: PatArc, salt: Int): Long {
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

fun <A> Marker.patHighlight(
    event: PatScheduledEvent<A>,
    key: String = "active",
    color: String = "#0af"
): PatScheduledEvent<A> {
    val decorationKey = "__pat_highlight__${name}__$key"
    val span = event.sourceSpan
    if (span == null) {
        box.decorationState.remove(decorationKey)
        return event
    }
    val range = findPatDecorationRange(box, span, hintLine = span.lineHint ?: patHighlightHintLine())
    if (range == null) {
        box.decorationState.remove(decorationKey)
        return event
    }
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
        range.lineStart,
        range.colStart,
        range.lineEnd,
        range.colEnd,
        className,
        mapOf(className to css)
    )
    return event
}

fun Marker.clearPatHighlight(key: String = "active") {
    box.decorationState.remove("__pat_highlight__${name}__$key")
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
    val code = box.code ?: return null
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
