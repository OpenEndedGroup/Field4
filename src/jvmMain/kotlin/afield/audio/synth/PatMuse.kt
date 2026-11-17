package afield.audio.synth

import afield.ui.output.ControlGroupBuilder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * A probabilistic sequencer for the pattern language: set weights, listen, then lock what you liked.
 *
 * A [PatMuseChannel] draws each event from three weight tables — scale degree, chord type and
 * rhythm (how many steps the event lasts) — so the stream it plays is shaped by the tables rather
 * than written down. When a stretch of it is worth keeping, [PatMuseChannel.lock] freezes the last
 * `n` events (up to 64) into a [PatLoop]. The loop repeats exactly, between its own [PatLoop.start]
 * and [PatLoop.end], forwards, backwards or back and forth; its [PatLoop.improvise] probability lets
 * each pass vary the loop's velocities and decorate its notes with Baroque ornaments (mordent, turn,
 * trill, passing tone) without changing the loop itself.
 *
 * Both the free stream and the loop are ordinary `PatPattern<String>`s whose atoms are MIDI note
 * numbers with a `gain` annotation, so everything that plays a pattern plays them:
 * [Piano.play] (one channel per hand), `showPatRollOf(..., pitched = true)`, `notes()` and the
 * inline `pianoRoll` visualizer, `respond`, and every pattern transform.
 *
 * The method follows the description of the norns script *Muses* (llllllll.co/t/muses); no code
 * comes from it. Everything is a pure function of the seed and the weights: the same seed and
 * tables give the same stream, and the same loop and seed give the same improvisation on every
 * pass `k`. Tests: `PatMuseTest`.
 */

/**
 * A row of weights, one per choice, sampled in proportion — the table a [PatMuseChannel] draws from.
 *
 * Weights are non-negative and need not sum to one. A table whose weights are all zero picks its
 * first entry. Show one as sliders with [ControlGroupBuilder.weights].
 */
class PatWeights(val labels: List<String>, vararg initial: Double) {
    private val w = DoubleArray(labels.size) { i -> initial.getOrElse(i) { 0.0 }.coerceAtLeast(0.0) }

    /** Bumped on every change, so a channel knows its generated stream is stale. */
    var version: Int = 0
        private set

    val size: Int get() = w.size

    operator fun get(i: Int): Double = w[i]

    operator fun set(i: Int, weight: Double) {
        val v = weight.coerceAtLeast(0.0)
        if (w[i] != v) { w[i] = v; version++ }
    }

    /** Replaces every weight at once. */
    fun set(vararg weights: Double) = weights.forEachIndexed { i, v -> if (i < size) set(i, v) }

    /** The weights as a list, in [labels] order. */
    fun toList(): List<Double> = w.toList()

    /** The index chosen by [unit] in `[0, 1)`: each entry owns a share of the line as wide as its weight. */
    fun sample(unit: Double): Int {
        val total = w.sum()
        if (total <= 0.0) return 0
        var acc = 0.0
        val target = unit * total
        for (i in w.indices) {
            acc += w[i]
            if (target < acc) return i
        }
        return w.indices.last { w[it] > 0.0 }
    }

    override fun toString(): String = labels.indices.joinToString(", ", "PatWeights(", ")") { "${labels[it]}=${w[it]}" }
}

/**
 * One event of a [PatMuseChannel]: [length] steps from [step], sounding scale [degrees]
 * (empty for a rest) at [velocity].
 *
 * The degrees are counted in the channel's scale from its root, so `0` is the root and `7` the
 * octave above in a seven-note scale; a chord lists each of its notes.
 */
data class PatMuseEvent(val step: Long, val length: Int, val degrees: List<Int>, val velocity: Double) {
    val isRest: Boolean get() = degrees.isEmpty()
}

/** The direction a [PatLoop] plays its window in. */
enum class PatLoopDirection { Forward, Reverse, PingPong }

/** The ornaments a [PatLoop] can add while improvising. */
enum class PatOrnament { Mordent, Turn, Trill, Passing }

private const val TICKS_PER_STEP = 4

/** A note as it sounds: tick span, MIDI pitch, velocity, and the ornament it belongs to if any. */
private data class Sounding(val start: Long, val end: Long, val midi: Int, val velocity: Double, val ornament: PatOrnament?, val improvised: Boolean)

private fun mix(vararg parts: Long): Long {
    var h = 0x2545F4914F6CDD1DL
    for (p in parts) h = splitmix64(h xor p)
    return h
}

private fun unit(vararg parts: Long): Double = toUnitDouble(mix(*parts))

/** Turns soundings on a tick grid into a pattern; [render] answers which ones touch a tick range. */
private fun soundingPattern(stepsPerCycle: Int, channel: String, render: (fromTick: Long, toTick: Long) -> List<Sounding>): PatPattern<String> {
    val ticksPerCycle = (stepsPerCycle * TICKS_PER_STEP).toLong()
    return PatPattern { arc ->
        val from = floor(arc.start.toDouble() * ticksPerCycle).toLong()
        val to = kotlin.math.ceil(arc.end.toDouble() * ticksPerCycle).toLong()
        if (to <= from) return@PatPattern emptyList()
        render(from, to).mapNotNull { s ->
            val whole = PatArc(PatQ.of(s.start, ticksPerCycle), PatQ.of(s.end, ticksPerCycle))
            val part = whole.intersect(arc) ?: return@mapNotNull null
            if (part.start >= part.end) return@mapNotNull null
            val ann = buildMap {
                put("gain", "%.3f".format(java.util.Locale.ROOT, s.velocity))
                put("channel", channel)
                if (s.ornament != null) put("ornament", s.ornament.name.lowercase())
                if (s.improvised) put("improvised", "1")
            }
            PatEvent(part, s.midi.toString(), annotations = ann, whole = whole)
        }
    }
}

/**
 * One voice of the sequencer: three weight tables and the stream they produce.
 *
 * [degree] weighs scale degrees `0..7` (root to octave) in [scale] above [root]; [chord] weighs
 * stacking a single note, a third, a triad or a seventh in the scale on that degree; [rhythm]
 * weighs event lengths of 1, 2, 3, 4, 6 and 8 steps. [rest] is the chance an event is silent.
 * [stepsPerCycle] steps make one pattern cycle.
 *
 * ```
 * val right = PatMuseChannel(seed = 7, root = 60)
 * right.degree.set(4.0, 0.0, 2.0, 0.0, 3.0, 1.0, 0.0, 1.0)
 * Piano().play(right.pattern(), cycles = 8, cps = 0.5)
 * ```
 */
class PatMuseChannel(
    var seed: Long = 1,
    var scale: String = "major",
    var root: Int = 60,
    val stepsPerCycle: Int = 8,
    val name: String = "muse",
) {
    val degree = PatWeights(listOf("1", "2", "3", "4", "5", "6", "7", "8"), 4.0, 1.0, 2.0, 1.0, 3.0, 1.0, 0.5, 1.0)
    val chord = PatWeights(listOf("note", "third", "triad", "seventh"), 6.0, 1.0, 1.0, 0.0)
    val rhythm = PatWeights(listOf("1", "2", "3", "4", "6", "8"), 1.0, 3.0, 0.5, 1.0, 0.0, 0.0)

    /** Chance in `[0, 1]` that an event is a rest. */
    var rest: Double = 0.1
        set(v) { if (field != v) { field = v; touched++ } }

    /** Base velocity; each event varies around it by up to ±[velocitySpread]. */
    var velocity: Double = 0.6
        set(v) { if (field != v) { field = v; touched++ } }
    var velocitySpread: Double = 0.1
        set(v) { if (field != v) { field = v; touched++ } }

    private var touched = 0
    private val events = ArrayList<PatMuseEvent>()
    private var builtFor: List<Any>? = null

    private fun stamp(): List<Any> = listOf(seed, scale, root, degree.version, chord.version, rhythm.version, touched)

    /** The events up to (and including the one sounding at) [untilStep], generating as needed. */
    @Synchronized
    fun eventsUntil(untilStep: Long): List<PatMuseEvent> {
        val s = stamp()
        if (s != builtFor) { events.clear(); builtFor = s }
        var next = events.lastOrNull()?.let { it.step + it.length } ?: 0L
        var i = events.size.toLong()
        while (next <= untilStep) {
            val e = draw(i, next)
            events += e
            next += e.length
            i++
        }
        return events.toList()
    }

    private fun draw(index: Long, step: Long): PatMuseEvent {
        val salt = name.hashCode().toLong()
        val len = RHYTHM_STEPS[rhythm.sample(unit(seed, salt, index, 1))]
        val vel = (velocity + velocitySpread * (2 * unit(seed, salt, index, 4) - 1)).coerceIn(0.02, 1.0)
        if (unit(seed, salt, index, 2) < rest) return PatMuseEvent(step, len, emptyList(), vel)
        val d = degree.sample(unit(seed, salt, index, 3))
        val notes = chord.sample(unit(seed, salt, index, 5)) + 1
        return PatMuseEvent(step, len, List(notes) { d + 2 * it }, vel)
    }

    /** MIDI pitch of scale [degree] in this channel. */
    fun midi(degree: Int): Int = root + patScaleDegree(patScaleSteps(scale), degree)

    /** The free-running stream as a pattern: a new event each time the last one ends. */
    fun pattern(): PatPattern<String> {
        val steps = patScaleSteps(scale)
        val r = root
        return soundingPattern(stepsPerCycle, name) { from, to ->
            eventsUntil(to / TICKS_PER_STEP + 1).flatMap { e ->
                val s = e.step * TICKS_PER_STEP
                val end = s + e.length * TICKS_PER_STEP
                if (end <= from || s >= to) emptyList()
                else e.degrees.map { Sounding(s, end, r + patScaleDegree(steps, it), e.velocity, null, false) }
            }
        }
    }

    /**
     * Freezes the last [n] events (at most 64) that begin before cycle [atCycle] into a loop.
     *
     * The loop copies the events, scale and root, so changing the weights afterwards changes the
     * free stream and leaves the loop alone.
     */
    fun lock(n: Int, atCycle: Number): PatLoop {
        val count = n.coerceIn(1, 64)
        val atStep = floor(atCycle.toDouble() * stepsPerCycle).toLong()
        val before = eventsUntil(atStep).filter { it.step < atStep }
        val taken = before.takeLast(count)
        require(taken.isNotEmpty()) { "nothing to lock before cycle $atCycle" }
        return PatLoop(taken.map { it.copy(step = 0) }, scale, root, stepsPerCycle, name, seed)
    }

    companion object {
        /** The event lengths, in steps, that [rhythm] weighs. */
        val RHYTHM_STEPS = intArrayOf(1, 2, 3, 4, 6, 8)
    }
}

/**
 * A locked stretch of a [PatMuseChannel], played as a loop.
 *
 * [events] is the loop's content in the order it was played; edit it with [set] (change a
 * degree, a chord or a velocity) and the pattern follows. [start] and [end] choose the window
 * of events that plays (end exclusive), and [direction] the order. With [improvise] above zero
 * each event of each pass is, with that probability, played with a changed velocity and often an
 * ornament; [seed] fixes which, so pass `k` sounds the same every time it is asked for.
 */
class PatLoop internal constructor(
    events: List<PatMuseEvent>,
    val scale: String,
    val root: Int,
    val stepsPerCycle: Int,
    val name: String,
    var seed: Long,
) {
    private val content = events.toMutableList()

    /** The loop's events, each with its length, degrees and velocity (their `step` is unused). */
    val events: List<PatMuseEvent> get() = content.toList()

    val size: Int get() = content.size

    var start: Int = 0
    var end: Int = events.size
    var direction: PatLoopDirection = PatLoopDirection.Forward

    /** Chance in `[0, 1]`, per event per pass, of an improvised variation. */
    var improvise: Double = 0.0

    /** How far an improvised velocity may move, as a fraction of the original. */
    var velocityJitter: Double = 0.35

    /** Which ornaments improvisation may choose from. */
    var ornaments: Set<PatOrnament> = PatOrnament.entries.toSet()

    /** Changes event [index]; pass only what should change. Degrees empty makes it a rest. */
    fun set(index: Int, degrees: List<Int>? = null, velocity: Double? = null, length: Int? = null): PatLoop = apply {
        val e = content[index]
        content[index] = e.copy(degrees = degrees ?: e.degrees, velocity = velocity ?: e.velocity, length = length?.coerceAtLeast(1) ?: e.length)
    }

    /** Shorthand for `set(index, degrees = listOf(degree))`. */
    fun set(index: Int, degree: Int): PatLoop = set(index, degrees = listOf(degree))

    /** The event indices one pass plays, in order. */
    fun order(): List<Int> {
        val lo = start.coerceIn(0, content.size - 1)
        val hi = end.coerceIn(lo + 1, content.size)
        val fwd = (lo until hi).toList()
        return when (direction) {
            PatLoopDirection.Forward -> fwd
            PatLoopDirection.Reverse -> fwd.reversed()
            PatLoopDirection.PingPong -> if (fwd.size <= 2) fwd else fwd + fwd.reversed().drop(1).dropLast(1)
        }
    }

    /** The length of one pass, in steps. */
    val passSteps: Int get() = order().sumOf { content[it].length }

    private fun midi(degree: Int) = root + patScaleDegree(patScaleSteps(scale), degree)

    /** The notes of pass [k], on a tick grid starting at the pass's first tick. */
    private fun pass(k: Long, origin: Long): List<Sounding> {
        val ord = order()
        val out = ArrayList<Sounding>()
        var t = origin
        for ((j, idx) in ord.withIndex()) {
            val e = content[idx]
            val len = e.length.toLong() * TICKS_PER_STEP
            if (!e.isRest) {
                val improvised = improvise > 0 && unit(seed, k, j.toLong(), 11) < improvise
                var vel = e.velocity
                var orn: PatOrnament? = null
                if (improvised) {
                    vel = (vel * (1 + velocityJitter * (2 * unit(seed, k, j.toLong(), 12) - 1))).coerceIn(0.02, 1.0)
                    val next = content[ord[(j + 1) % ord.size]]
                    val top = e.degrees.max()
                    val candidates = ornaments.filter { o ->
                        e.length >= 2 && when (o) {
                            PatOrnament.Passing -> !next.isRest && abs(next.degrees.max() - top) >= 2
                            else -> true
                        }
                    }.sortedBy { it.ordinal }
                    if (candidates.isNotEmpty() && unit(seed, k, j.toLong(), 13) < 0.7) {
                        orn = candidates[(unit(seed, k, j.toLong(), 14) * candidates.size).toInt().coerceAtMost(candidates.size - 1)]
                    }
                    if (orn != null) {
                        for (d in e.degrees) if (d != top) out += Sounding(t, t + len, midi(d), vel, null, true)
                        out += ornament(orn, top, next, t, len, vel)
                        t += len
                        continue
                    }
                }
                for (d in e.degrees) out += Sounding(t, t + len, midi(d), vel, null, improvised)
            }
            t += len
        }
        return out
    }

    /** The figure ornament [o] makes of degree [d] across [len] ticks from [t]. */
    private fun ornament(o: PatOrnament, d: Int, next: PatMuseEvent, t: Long, len: Long, vel: Double): List<Sounding> {
        fun piece(a: Long, b: Long, deg: Int, v: Double = vel) = Sounding(t + a, t + b, midi(deg), v, o, true)
        val q = len / 4
        return when (o) {
            // main, upper neighbour, main held
            PatOrnament.Mordent -> listOf(piece(0, q, d), piece(q, 2 * q, d + 1, vel * 0.85), piece(2 * q, len, d))
            // upper, main, lower, main
            PatOrnament.Turn -> listOf(piece(0, q, d + 1, vel * 0.9), piece(q, 2 * q, d), piece(2 * q, 3 * q, d - 1, vel * 0.9), piece(3 * q, len, d))
            // from the upper note, alternating every half step
            PatOrnament.Trill -> {
                val half = TICKS_PER_STEP / 2L
                val n = (len / half).toInt()
                List(n) { i -> piece(i * half, if (i == n - 1) len else (i + 1) * half, if (i % 2 == 0) d + 1 else d, if (i % 2 == 0) vel else vel * 0.8) }
            }
            // second half walks a scale step towards the next note
            PatOrnament.Passing -> {
                val target = next.degrees.max()
                listOf(piece(0, len / 2, d), piece(len / 2, len, d + sign((target - d).toDouble()).toInt(), vel * 0.8))
            }
        }
    }

    /** The loop as a pattern: passes back to back from cycle 0, pass `k` improvised with seed `(seed, k)`. */
    fun pattern(): PatPattern<String> = soundingPattern(stepsPerCycle, name) { from, to ->
        val passTicks = passSteps.toLong() * TICKS_PER_STEP
        val first = Math.floorDiv(from, passTicks)
        val last = Math.floorDiv(to - 1, passTicks)
        (first..last).flatMap { k -> pass(k, k * passTicks) }.filter { it.end > from && it.start < to }
    }

    override fun toString(): String =
        "PatLoop($name, ${content.size} events, window ${start}..${min(end, content.size)} ${direction.name.lowercase()}, $passSteps steps a pass, improvise=$improvise)"
}

/**
 * Two [PatMuseChannel]s on one seed — a left hand and a right — and their loops.
 *
 * `pattern()` stacks whatever each hand is playing: its locked loop if it has one, otherwise its
 * free stream. `lock(n, atCycle)` locks both hands at the same moment; `release()` lets them run free.
 *
 * ```
 * val muse = PatMuse(seed = 3)
 * val piano = Piano()
 * piano.play(muse.pattern(), cycles = 8, cps = 0.5)
 * ```
 */
class PatMuse(seed: Long = 1, scale: String = "major", stepsPerCycle: Int = 8) {
    val left = PatMuseChannel(seed, scale, root = 48, stepsPerCycle = stepsPerCycle, name = "left").apply {
        degree.set(5.0, 0.5, 1.0, 1.5, 4.0, 1.0, 0.0, 2.0)
        chord.set(3.0, 2.0, 2.0, 0.5)
        rhythm.set(0.0, 1.0, 0.0, 3.0, 1.0, 1.0)
        rest = 0.05
        velocity = 0.45
    }
    val right = PatMuseChannel(seed, scale, root = 60, stepsPerCycle = stepsPerCycle, name = "right").apply {
        velocity = 0.6
    }

    /** Each hand's loop, or null while it plays free. */
    var leftLoop: PatLoop? = null
    var rightLoop: PatLoop? = null

    /** Sets both hands' scale. */
    var scale: String = scale
        set(v) { field = v; left.scale = v; right.scale = v }

    /** Locks both hands' last [n] events before [atCycle]. */
    fun lock(n: Int, atCycle: Number): PatMuse = apply {
        leftLoop = left.lock(n, atCycle)
        rightLoop = right.lock(n, atCycle)
    }

    fun release(): PatMuse = apply { leftLoop = null; rightLoop = null }

    fun leftPattern(): PatPattern<String> = leftLoop?.pattern() ?: left.pattern()
    fun rightPattern(): PatPattern<String> = rightLoop?.pattern() ?: right.pattern()

    /** Both hands together. */
    fun pattern(): PatPattern<String> = stack(leftPattern(), rightPattern())
}

/**
 * One slider per entry of [table], labelled `"$name ${label}"`, each writing its weight; [onChange]
 * runs after every write (redraw a roll there).
 */
fun ControlGroupBuilder.weights(name: String, table: PatWeights, top: Double = 8.0, onChange: () -> Unit = {}) {
    for (i in 0 until table.size) {
        slider("$name ${table.labels[i]}", 0.0, max(top, table[i]), 0.1, table[i]) { v ->
            table[i] = v
            onChange()
        }
    }
}
