package afield.data.query.datalog

import afield.audio.synth.PatArc
import afield.audio.synth.PatEvent
import afield.audio.synth.PatPattern
import afield.audio.synth.PatQ
import afield.audio.synth.bjorklund
import afield.audio.synth.patDrumHz
import afield.audio.synth.qCeil
import afield.audio.synth.qFloor

/**
 * Rules that decide when notes play: a Datalog program whose `event(Instrument, Note, Velocity, T)`
 * facts are a score, played by Field's pattern engine.
 *
 * The idea is Dogalog's (https://github.com/danja/dogalog, MIT, by Danny Ayers), which asks a Prolog
 * engine on a sixteenth-note grid. Here the logic engine is Field's own [Datalog] and the player is
 * Field's own [PatPattern]: [rhythm] turns a program into a `PatPattern<String>`, so anything that
 * plays, renders or draws a Pat pattern plays the rules.
 *
 * ```
 * event(kick, 36, 100, T) :- euc(T, 4, 16, 0).
 * event(hat, 42, 60, T)   :- every(T, 2, 1).
 * event(lead, N, 90, T)   :- every(T, 4, 0), D = (T / 4) mod 5, scale(60, minor_pent, D, 0, N).
 * ```
 *
 * ## Time predicates
 *
 * `T` is an integer step on a grid of [DlRhythm.stepsPerCycle] steps per Pat cycle (16 by default:
 * one cycle is one bar of sixteenths). The time predicates are ordinary base relations, filled in
 * for the window being evaluated, so they compose with everything else Datalog does (negation,
 * aggregates, `#why`):
 *
 * - `step(T)` — every step in the window.
 * - `bar(T, B)`, `pos(T, P)` — `B` is the bar (cycle) number, `P` the step within the bar.
 * - `euc(T, K, N, R)` — `T mod N` is an onset of Bjorklund's `K` in `N`, rotated right by `R`;
 *   the same figure as Pat's `euclid(K, N, R)`, from the same [bjorklund].
 * - `every(T, N, O)` — `T mod N = O`.
 * - `within(T, A, B)` — `A <= T < B`; song structure in absolute steps (`within(T, 64, 128)` is bars 4–7).
 * - `scale(Root, Name, Degree, Octave, Note)` — the `Degree`th note of a scale (degrees past the
 *   top wrap up an octave); names in [DlRhythm.scales].
 * - `chord(Root, Quality, Index, Note)` — the `Index`th note of a chord; qualities in [DlRhythm.chords].
 *   Dogalog returns a chord as a list; Datalog has no lists, so here a chord is a relation, one row
 *   per note, which is what lets `event(pad, N, 70, T) :- every(T, 16, 0), chord(57, minor, _, N).`
 *   strike all of them.
 *
 * `euc`, `every`, `within`, `scale` and `chord` need their parameters written as constants, because
 * the rows are generated for exactly the parameter tuples the program mentions. A variable there is
 * reported, not guessed.
 *
 * ## Why bottom-up over a window
 *
 * A Datalog fixpoint needs a finite domain, and a score is infinite. Bounding `T` to one cycle (plus
 * [DlRhythm.lookbehind] steps of context, so `T - 1` rules see the end of the previous bar) makes it
 * finite and tiny — sixteen steps — so each cycle is solved bottom-up on its own and memoised. That
 * matches how the scheduler asks: Pat queries a cycle ahead of the audio, any number of times, in
 * any order, and gets the same answer. Top-down (Dogalog's Prolog) answers "does anything sound at
 * step T?" one step at a time; bottom-up answers a whole bar at once, which is the question the
 * scheduler's lookahead is actually asking, and it keeps `#why` provenance for free. What it gives
 * up is unbounded recursion *across* time: a rule cannot see further back than the lookbehind.
 */
class DlRhythm(
    /** The program. Read afresh for each uncached cycle, so editing it changes the next bar. */
    val program: Datalog,
    val stepsPerCycle: Int = 16,
    val lookbehind: Int = stepsPerCycle,
) {
    init {
        require(stepsPerCycle > 0) { "stepsPerCycle must be positive" }
        require(lookbehind >= 0) { "lookbehind must be >= 0" }
    }

    /** One derived note: `event(instrument, note, velocity, step)`. */
    data class Event(val instrument: String, val note: Double, val velocity: Double, val step: Long) {
        override fun toString() = "event(${dlFormat(instrument)}, ${dlFormat(dlNormalize(note))}, ${dlFormat(dlNormalize(velocity))}, $step)"
    }

    private var memoKey: String? = null
    private val memo = HashMap<Long, Pair<Datalog, List<Event>>>()

    /** The model for [cycle]'s window: the program plus the time predicates for that window. */
    fun window(cycle: Long): Datalog = solved(cycle).first

    /** The events that start inside [cycle], sorted by step then instrument. */
    fun events(cycle: Long): List<Event> = solved(cycle).second

    /** Events with `from <= step < until`, across however many cycles that spans. */
    fun events(from: Long, until: Long): List<Event> {
        if (until <= from) return emptyList()
        val first = Math.floorDiv(from, stepsPerCycle.toLong())
        val last = Math.floorDiv(until - 1, stepsPerCycle.toLong())
        return (first..last).flatMap { events(it) }.filter { it.step in from until until }
    }

    /** The grid as text, one line per instrument: `x` where it sounds, `.` where it does not. */
    fun grid(cycle: Long): String {
        val evs = events(cycle)
        val base = cycle * stepsPerCycle
        return evs.map { it.instrument }.distinct().joinToString("\n") { inst ->
            val on = evs.filter { it.instrument == inst }.map { it.step }.toSet()
            inst.padEnd(6) + " " + (0 until stepsPerCycle).joinToString("") { if (base + it in on) "x" else "." }
        }
    }

    /** Why did this note play? `why("event(kick, 36, 100, 12)")` — the step picks the window. */
    fun why(goal: String): DlDerivation? {
        val atom = DatalogParser.parseGoal(goal).singleOrNull() as? DlAtom
            ?: throw DlException("why takes a single atom, e.g. why(\"event(kick, 36, 100, 12)\")")
        val step = (atom.terms.getOrNull(3) as? DlConst)?.value as? Number
            ?: throw DlException("why needs the step (the fourth argument of event) written as a number")
        return window(Math.floorDiv(step.toLong(), stepsPerCycle.toLong())).why(goal)
    }

    /**
     * The rules as a Pat pattern. Each event's value is its instrument; a pitched instrument carries
     * its note as the `note` annotation (a drum name such as `kick` or `hh` keeps its drum voice),
     * velocity becomes `gain` (`velocity / 100`), and the fact itself rides along as `datalog`.
     */
    fun pattern(): PatPattern<String> = PatPattern { arc ->
        val first = qFloor(arc.start)
        val lastExclusive = maxOf(qCeil(arc.end), first + 1)
        buildList {
            for (cycle in first until lastExclusive) {
                for (e in events(cycle)) {
                    val local = e.step - cycle * stepsPerCycle
                    val whole = PatArc(
                        PatQ.of(cycle * stepsPerCycle + local, stepsPerCycle.toLong()),
                        PatQ.of(cycle * stepsPerCycle + local + 1, stepsPerCycle.toLong()),
                    )
                    val visible = whole.intersect(arc) ?: continue
                    add(PatEvent(visible, e.instrument, annotations = buildMap {
                        if (patDrumHz(e.instrument, 0.0).second == null) put("note", dlFormat(dlNormalize(e.note)))
                        put("gain", (e.velocity / 100.0).toString())
                        put("datalog", e.toString())
                    }, whole = whole))
                }
            }
        }
    }

    private fun solved(cycle: Long): Pair<Datalog, List<Event>> {
        // Keyed on the program text, so an edit to the rules drops the memo and the next query —
        // the scheduler's next lookahead — is the new rules, with no restart of the player.
        val key = program.program() + "\u0000" + program.facts.values.joinToString { "${it.name}:${it.rows().size}" }
        if (key != memoKey) { memo.clear(); memoKey = key }
        return memo.getOrPut(cycle) { solve(cycle) }
    }

    private fun solve(cycle: Long): Pair<Datalog, List<Event>> {
        val start = cycle * stepsPerCycle
        val until = start + stepsPerCycle
        val from = start - lookbehind
        val d = Datalog()
        program.rules.forEach { d.addRule(it) }
        program.facts.values.forEach { rel -> if (rel.name !in TIME_PREDICATES) d.facts(rel.name, rel.rows()) }
        addTimeFacts(d, program.rules, from, until)
        val events = d.rowsOf("event").mapNotNull { row ->
            if (row.size != 4) throw DlException("event/${row.size}: an event is event(Instrument, Note, Velocity, T)")
            val t = (row[3] as? Number)?.toLong() ?: return@mapNotNull null
            if (t !in start until until) return@mapNotNull null
            Event(row[0].toString(), (row[1] as? Number)?.toDouble() ?: 0.0, (row[2] as? Number)?.toDouble() ?: 100.0, t)
        }.sortedWith(compareBy({ it.step }, { it.instrument }, { it.note }))
        return d to events
    }

    private fun addTimeFacts(d: Datalog, rules: List<DlRule>, from: Long, until: Long) {
        val steps = from until until
        val n = stepsPerCycle.toLong()
        d.facts("step", steps.map { listOf(it) })
        d.facts("bar", steps.map { listOf(it, Math.floorDiv(it, n)) })
        d.facts("pos", steps.map { listOf(it, Math.floorMod(it, n)) })
        for (atom in bodyAtoms(rules)) {
            fun const(i: Int): Any? {
                val term = atom.terms[i]
                return (term as? DlConst)?.value
                    ?: throw DlException("${atom.pred}: argument ${i + 1} of $atom must be a constant (the rows are generated for the values the program names)")
            }
            fun int(i: Int): Long = (const(i) as? Number)?.toLong()
                ?: throw DlException("${atom.pred}: argument ${i + 1} of $atom must be a number")
            when (atom.pred to atom.arity) {
                "euc" to 4 -> {
                    val k = int(1); val len = int(2); val r = int(3)
                    if (len <= 0 || k !in 0..len) throw DlException("euc: need 0 <= K <= N and N > 0 in $atom")
                    val mask = bjorklund(k.toInt(), len.toInt())
                    for (t in steps) {
                        if (mask[Math.floorMod(t - r, len).toInt()]) d.fact("euc", t, k, len, r)
                    }
                }
                "every" to 3 -> {
                    val m = int(1); val o = int(2)
                    if (m <= 0) throw DlException("every: N must be positive in $atom")
                    for (t in steps) if (Math.floorMod(t, m) == Math.floorMod(o, m)) d.fact("every", t, m, o)
                }
                "within" to 3 -> {
                    val a = int(1); val b = int(2)
                    for (t in steps) if (t in a until b) d.fact("within", t, a, b)
                }
                "scale" to 5 -> {
                    val root = int(0); val name = const(1).toString()
                    val intervals = scales[name] ?: throw DlException("scale: unknown scale '$name'; known: ${scales.keys.joinToString()}")
                    for (oct in -2L..3L) for (deg in 0 until intervals.size * 3) {
                        val note = root + 12 * (oct + deg / intervals.size) + intervals[deg % intervals.size]
                        d.fact("scale", root, name, deg, oct, note)
                    }
                }
                "chord" to 4 -> {
                    val root = int(0); val q = const(1).toString()
                    val intervals = chords[q] ?: throw DlException("chord: unknown quality '$q'; known: ${chords.keys.joinToString()}")
                    intervals.forEachIndexed { i, iv -> d.fact("chord", root, q, i, root + iv) }
                }
            }
        }
    }

    private fun bodyAtoms(rules: List<DlRule>): List<DlAtom> {
        val out = ArrayList<DlAtom>()
        fun walk(body: List<DlLiteral>) {
            body.forEach { lit ->
                when (lit) {
                    is DlAtom -> if (lit.pred in GENERATED) out.add(lit)
                    is DlAggregate -> walk(lit.body)
                    else -> {}
                }
            }
        }
        rules.forEach { walk(it.body) }
        return out.distinctBy { atom ->
            val keyTerms = if (atom.pred == "scale" || atom.pred == "chord") atom.terms.take(2) else atom.terms.drop(1)
            atom.pred to keyTerms.map { t -> (t as? DlConst)?.value?.let(::dlNormalize) ?: t }
        }
    }

    companion object {
        private val GENERATED = setOf("euc", "every", "within", "scale", "chord")
        private val TIME_PREDICATES = GENERATED + setOf("step", "bar", "pos")

        /** Scale intervals in semitones above the root. */
        val scales: Map<String, List<Int>> = mapOf(
            "major" to listOf(0, 2, 4, 5, 7, 9, 11),
            "minor" to listOf(0, 2, 3, 5, 7, 8, 10),
            "dorian" to listOf(0, 2, 3, 5, 7, 9, 10),
            "major_pent" to listOf(0, 2, 4, 7, 9),
            "minor_pent" to listOf(0, 3, 5, 7, 10),
            "blues" to listOf(0, 3, 5, 6, 7, 10),
            "chromatic" to (0..11).toList(),
        )

        /** Chord intervals in semitones above the root. */
        val chords: Map<String, List<Int>> = mapOf(
            "major" to listOf(0, 4, 7),
            "minor" to listOf(0, 3, 7),
            "dim" to listOf(0, 3, 6),
            "aug" to listOf(0, 4, 8),
            "sus4" to listOf(0, 5, 7),
            "maj7" to listOf(0, 4, 7, 11),
            "min7" to listOf(0, 3, 7, 10),
            "dom7" to listOf(0, 4, 7, 10),
        )
    }
}

/** This program as a rhythm: see [DlRhythm]. */
fun Datalog.rhythm(stepsPerCycle: Int = 16, lookbehind: Int = stepsPerCycle): DlRhythm =
    DlRhythm(this, stepsPerCycle, lookbehind)
