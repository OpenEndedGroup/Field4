@file:Suppress("MemberVisibilityCanBePrivate", "unused")
@file:afield.CompletionCategory("Audio")

package afield.petri

import afield.four.synth.PatArc
import afield.four.synth.PatEvent
import afield.four.synth.PatPattern
import afield.four.synth.PatQ
import afield.four.synth.bjorklund
import afield.four.synth.signalOf
import afield.four.synth.squeezeJoin

/*
 Where a Petri net stops being a graph and becomes music.

 The whole file rests on one claim, and it is worth stating plainly because everything else follows
 from it: **a net is a pure function of time**, so a net can be a `PatPattern`.

 `PatPattern` is not a list of notes. It is a function from a time arc to the events inside it, and
 the language's combinators are free to call it with any arc, in any order, as many times as they
 like — `rev()` reads a cycle backwards, `every(4)` re-queries under a transform, the scheduler
 walks forward one cycle at a time. A stateful sequencer cannot satisfy that contract. A Petri net
 with a deterministic conflict policy can, because [PetriRun] memoises an append-only trajectory:
 the answer for cycle 3 is the same object whether you ask before or after asking about cycle 9.

 The payoff is that nothing here has to reimplement anything:

     val net = petri {
         place("kick", tokens = 1)
         t("hit",  dur = cyc(1, 16), emit = "bd") { from("kick"); to("hats") }
         t("back", dur = cyc(1, 16), emit = "sd") { from("hats"); to("kick") }
     }

     net.emits()                                  // PatPattern<String>
        .every(4) { it.fast(2) }                  // …and the entire language applies
        .jux { it.rev() }
        .respond(cps = 2.0) { println(it) }

 Three doors from a net into the language
 ----------------------------------------

 1. **Firings are events.** [pattern] gives you `PatPattern<PetriFiring>`; [emits] narrows it to
    what each firing sounds. This is the short-term rhythm layer, and it is where a net behaves
    like a drum machine.

 2. **Tokens are patterns.** A token's colour can be a whole `PatPattern`, so a place is a section
    and a transition is an edit. [unfold] plays whatever pattern a firing consumed across that
    firing's arc, which makes a slow net a *song structure* over the fast nets it schedules. One
    mechanism, both timescales.

 3. **Markings are control signals.** [signal] turns a place's token count into a continuous
    `PatPattern<Double>`, optionally interpolated, so tension accumulating in a place can drive a
    filter cutoff: `with cutoff(net.signal("tension").rangex(200, 8000))`.
*/

// ── firings as events ──────────────────────────────────────────────────────────────────────

/**
 * The net's firings as a pattern.
 *
 * Each firing becomes one event spanning the firing's own arc — `delay` and `duration` already
 * applied — clipped to whatever the query asked about, with the full arc kept as the event's
 * [PatEvent.whole] so the scheduler strikes the note once, at its onset, at its real length.
 *
 * Every event is annotated with `petri` (the transition name) and, when there is one, `emit`, so
 * that information survives `map`, `with`, and the trip through `PatScheduledEvent`.
 */
fun PetriRun.pattern(): PatPattern<PetriFiring> = PatPattern { arc ->
    firingsOver(arc).mapNotNull { firing ->
        val visible = firing.arc.intersect(arc) ?: return@mapNotNull null
        PatEvent(
            arc = visible,
            value = firing,
            annotations = buildMap {
                put("petri", firing.transition)
                firing.emit?.let { put("emit", it.toString()) }
            },
            whole = firing.arc,
        )
    }
        // Two transitions firing in the same maximal step land on the same instant, and the order
        // the engine happened to visit them in is not a musical fact. Sorting by name breaks the
        // tie the same way every time, so `rev()` and friends do not silently shuffle a chord.
        .sortedWith(compareBy({ it.arc.start }, { it.value.transition }))
}

/**
 * The net's firings as a pattern, from a fresh [PetriRun].
 *
 * A run is the memo, so two calls give two independent (but identical) trajectories. Hold a run
 * yourself and call [PetriRun.pattern] when you want several views onto the *same* performance —
 * which is what you want if one of them is the visualizer.
 */
fun PetriNet.pattern(from: PetriMarking = initial): PatPattern<PetriFiring> = run(from).pattern()

/** Keeps only the firings of the named transitions — one lane out of a multi-track net. */
fun PatPattern<PetriFiring>.only(vararg transitions: String): PatPattern<PetriFiring> {
    val wanted = transitions.toSet()
    return PatPattern { arc -> events(arc).filter { it.value.transition in wanted } }
}

/**
 * Drops the firings of the named transitions.
 *
 * Not called `without`: the parameter algebra already has a `PatPattern<A>.without(vararg keys)`
 * that removes *parameters*, and two same-named extensions on the same receiver doing different
 * things is a trap even when overload resolution picks the right one.
 */
fun PatPattern<PetriFiring>.withoutTransitions(vararg transitions: String): PatPattern<PetriFiring> {
    val unwanted = transitions.toSet()
    return PatPattern { arc -> events(arc).filter { it.value.transition !in unwanted } }
}

/**
 * What each firing sounds, as text — the door to `notes()`, the Dirt sample vocabulary, and
 * everything else in the language that speaks `PatPattern<String>`.
 *
 * Silent transitions (those with no `emit`) are **dropped**, not rendered as `"null"`: a net's
 * plumbing transitions exist to move tokens and should not turn into notes.
 */
fun PatPattern<PetriFiring>.emits(): PatPattern<String> = PatPattern { arc ->
    events(arc).mapNotNull { event ->
        val emit = event.value.emit ?: return@mapNotNull null
        event.withValue(emit.toString())
    }
}

/** [emits] for a typed emit — `emitsAs<Int>()` for note numbers. Firings emitting anything else are dropped. */
inline fun <reified T> PatPattern<PetriFiring>.emitsAs(): PatPattern<T> = PatPattern { arc ->
    events(arc).mapNotNull { event ->
        val emit = event.value.emit as? T ?: return@mapNotNull null
        event.withValue(emit)
    }
}

/** The net's emitted sounds, straight from the net. The one-liner most nets want. */
fun PetriNet.emits(from: PetriMarking = initial): PatPattern<String> = pattern(from).emits()

/** The colours a firing consumed, one event per firing that consumed a [T]. */
inline fun <reified T> PatPattern<PetriFiring>.consumed(): PatPattern<T> = PatPattern { arc ->
    events(arc).mapNotNull { event ->
        val colour = event.value.consumed.firstNotNullOfOrNull { it.colour as? T } ?: return@mapNotNull null
        event.withValue(colour)
    }
}

// ── tokens that are patterns ───────────────────────────────────────────────────────────────

/** How [unfold] fits a pattern-valued token into the arc of the firing that consumed it. */
enum class PetriUnfold {
    /**
     * Fit one whole cycle of the inner pattern into the firing's arc, Tidal's `squeeze`. A
     * four-cycle firing plays the inner pattern four times slower. Good for ornaments, wrong for
     * sections.
     */
    SQUEEZE,

    /**
     * Let the inner pattern run at its own rate, in absolute time, and show only the part inside
     * the firing's arc. A four-cycle firing plays four cycles of a one-cycle loop. This is the
     * song-structure behaviour, and the default.
     */
    NATURAL,

    /**
     * As [NATURAL], but with the inner pattern re-phased so the firing's arc starts at the inner
     * pattern's cycle 0 — the section starts at the top of the loop however the bar lines fell.
     */
    RESTART,
}

/**
 * Plays the pattern each firing consumed, across that firing's arc.
 *
 * This is the medium-term structure layer. A slow net whose places hold `PatPattern` tokens is an
 * arrangement: each transition is a section change, each firing's duration is a section length,
 * and `unfold` renders the whole thing back down to one flat pattern of notes.
 *
 *     val song = petri {
 *         timing = PetriTiming.TIMED
 *         place("verse", pat("bd ~ sd ~"))
 *         t("play-verse",  dur = cyc(8)) { from("verse");  to("chorus") }
 *         t("play-chorus", dur = cyc(8)) { from("chorus"); to("verse") }
 *     }
 *     song.pattern().unfold<String>()      // eight bars of each, forever
 *
 * Firings whose consumed tokens hold no pattern contribute nothing — they are structure, not sound.
 */
inline fun <reified A> PatPattern<PetriFiring>.unfold(
    mode: PetriUnfold = PetriUnfold.NATURAL,
): PatPattern<A> {
    val inners: PatPattern<PatPattern<A>> = PatPattern { arc ->
        events(arc).mapNotNull { event ->
            @Suppress("UNCHECKED_CAST")
            val inner = event.value.consumed
                .firstNotNullOfOrNull { it.colour as? PatPattern<*> } as? PatPattern<A>
                ?: return@mapNotNull null
            event.withValue(inner)
        }
    }
    return petriUnfoldJoin(inners, mode)
}

/** The join behind [unfold], separated out so the reified wrapper stays small. */
fun <A> petriUnfoldJoin(inners: PatPattern<PatPattern<A>>, mode: PetriUnfold): PatPattern<A> =
    when (mode) {
        PetriUnfold.SQUEEZE -> squeezeJoin(inners)
        PetriUnfold.NATURAL, PetriUnfold.RESTART -> PatPattern { arc ->
            inners.events(arc).flatMap { outer ->
                val extent = outer.extent
                val visible = extent.intersect(arc) ?: return@flatMap emptyList()
                // NATURAL asks the inner pattern about absolute time; RESTART slides the section
                // back so its start is the inner pattern's cycle 0.
                val shift = if (mode == PetriUnfold.RESTART) extent.start else PatQ.ZERO
                val asked = PatArc(visible.start - shift, visible.end - shift)
                outer.value.events(asked).mapNotNull { inner ->
                    val placed = inner.arc.shift(shift).intersect(arc) ?: return@mapNotNull null
                    PatEvent(
                        arc = placed,
                        value = inner.value,
                        sourceSpan = inner.sourceSpan ?: outer.sourceSpan,
                        annotations = outer.annotations + inner.annotations,
                        additionalSpans = outer.additionalSpans + inner.additionalSpans,
                        // Clamp the inner note's extent to the section: a note may not sound past
                        // the transition that ended the section holding it.
                        whole = inner.whole?.shift(shift)?.intersect(extent),
                    )
                }
            }
        }
    }

// ── markings as control signals ────────────────────────────────────────────────────────────

/** How [signal] fills in the space between the instants at which a place's token count changed. */
enum class PetriInterp {
    /** The raw step function. What the marking literally is. */
    STEP,

    /** Straight lines between changes — the count as a ramp. */
    LINEAR,

    /** Smoothstep between changes: no corners, so it is safe on a filter cutoff. */
    SMOOTH,
}

/**
 * A place's token count as a continuous signal.
 *
 * Signals are the language's control-value currency, so this hands a net straight to the parameter
 * algebra: a place that accumulates tokens as a section builds is a tension curve, and
 * `net.signal("tension").rangex(200, 8000)` is a filter sweep driven by the structure rather than
 * bolted alongside it.
 *
 * The result is a genuine continuous pattern — one event per query, sampled at the arc's exact
 * rational midpoint — so `segment(16)` discretises it and `with gain(...)` samples it per note,
 * exactly as for [afield.four.synth.sine].
 */
fun PetriRun.signal(place: String, interpolate: PetriInterp = PetriInterp.STEP): PatPattern<Double> =
    signalOf { exact, _ ->
        when (interpolate) {
            PetriInterp.STEP -> countAt(place, exact).toDouble()
            PetriInterp.LINEAR, PetriInterp.SMOOTH -> {
                val history = changes(place, exact + PatQ.ONE)
                val index = history.indexOfLast { it.first <= exact }
                if (index < 0) history.firstOrNull()?.second?.toDouble() ?: 0.0
                else {
                    val (t0, c0) = history[index]
                    val next = history.getOrNull(index + 1)
                    if (next == null) c0.toDouble()
                    else {
                        val (t1, c1) = next
                        val span = (t1 - t0).toDouble()
                        val raw = if (span <= 0.0) 0.0 else ((exact - t0).toDouble() / span).coerceIn(0.0, 1.0)
                        val u = if (interpolate == PetriInterp.SMOOTH) raw * raw * (3.0 - 2.0 * raw) else raw
                        c0 + (c1 - c0) * u
                    }
                }
            }
        }
    }

/** [signal] from a fresh run — convenient, but see [PetriRun.pattern] on sharing a run. */
fun PetriNet.signal(place: String, interpolate: PetriInterp = PetriInterp.STEP): PatPattern<Double> =
    run().signal(place, interpolate)

/**
 * A place's occupancy as a **0..1** signal — the form the shaping operators expect.
 *
 * [signal] gives you the raw token count, which is the honest number but the wrong range:
 * `range(lo, hi)` and `rangex(lo, hi)` map `0..1` onto their interval and *extrapolate* outside it,
 * so feeding them a count of 3 sends a filter three times past the top of its sweep. Divide first:
 *
 *     with cutoff(net.level("tension", 4).rangex(200, 8000))
 *
 * [full] defaults to the place's declared capacity, so a bounded place needs no second number.
 * Values are clamped, because a signal that leaves 0..1 is almost always a mistake rather than an
 * intention — reach for [signal] when you want the raw count.
 */
fun PetriRun.level(
    place: String,
    full: Int? = net.place(place)?.capacity,
    interpolate: PetriInterp = PetriInterp.STEP,
): PatPattern<Double> {
    val ceiling = (full ?: 1).coerceAtLeast(1).toDouble()
    return signal(place, interpolate).let { raw -> PatPattern { arc -> raw.events(arc).map { it.withValue((it.value / ceiling).coerceIn(0.0, 1.0)) } } }
}

/** [level] from a fresh run. */
fun PetriNet.level(
    place: String,
    full: Int? = place(place)?.capacity,
    interpolate: PetriInterp = PetriInterp.STEP,
): PatPattern<Double> = run().level(place, full, interpolate)

/**
 * A signal that is 1.0 while [place] holds at least [atLeast] tokens and 0.0 otherwise — a gate.
 *
 * Multiply a pattern's gain by this and a place becomes a mute button that the net itself operates,
 * which is how a section net silences a rhythm net without either knowing about the other.
 */
fun PetriRun.gate(place: String, atLeast: Int = 1): PatPattern<Double> =
    signalOf { exact, _ -> if (countAt(place, exact) >= atLeast) 1.0 else 0.0 }

// ── nets you would otherwise write out by hand ─────────────────────────────────────────────

/**
 * A Euclidean token ring: [steps] places in a circle, one token walking round them, and a sound at
 * the [pulses] positions the Bjorklund algorithm picks out.
 *
 * This is the beats-btw drum primitive, and it is here because it is genuinely the right net for a
 * drum part — but note what you get that a hard-coded ring does not. It is a real net, so you can
 * hang an inhibitor arc off one of its places to duck the kick, read a position to trigger
 * something else, or merge it with another ring and let them contend for a token.
 *
 * Place *i* is `"$name.$i"` and the transition leaving it is `"$name.$i.go"`, so those names are what
 * you wire against. They are deliberately spellable in the `#petri` notation, so a generated ring
 * and a hand-written one are the same kind of thing.
 */
fun petriRing(
    name: String,
    pulses: Int,
    steps: Int,
    rotation: Int = 0,
    emit: Any? = name,
    build: PetriBuilder.() -> Unit = {},
): PetriNet = petri {
    grid = cyc(1, maxOf(1, steps))
    petriRingInto(this, name, pulses, steps, rotation, emit)
    build()
}

/**
 * Adds a Euclidean ring to a net you are already building. See [petriRing].
 *
 * The multi-track form is just several calls:
 *
 *     val kit = petri {
 *         grid = cyc(1, 16)
 *         petriRingInto(this, "kick", 4, 16, emit = "bd")
 *         petriRingInto(this, "hat", 11, 16, emit = "hh")
 *     }
 */
fun petriRingInto(
    into: PetriBuilder,
    name: String,
    pulses: Int,
    steps: Int,
    rotation: Int = 0,
    emit: Any? = name,
) {
    val count = maxOf(1, steps)
    val mask = bjorklund(pulses.coerceIn(0, count), count).let { hits ->
        val r = ((rotation % count) + count) % count
        List(count) { index -> hits[(index + r) % count] }
    }
    with(into) {
        for (index in 0 until count) place("$name.$index", tokens = if (index == 0) 1 else 0)
        for (index in 0 until count) {
            t("$name.$index.go", dur = cyc(1, count), emit = if (mask[index]) emit else null) {
                from("$name.$index")
                to("$name.${(index + 1) % count}")
            }
        }
    }
}

/**
 * A linear song form: one place per section, one transition between consecutive sections, each
 * holding for its own number of cycles, looping at the end.
 *
 * The sections' patterns ride round as token colours, so [unfold] renders the arrangement:
 *
 *     petriSong(
 *         "intro" to (pat("hh*8") to 4),
 *         "verse" to (pat("bd ~ sd ~") to 8),
 *         "drop"  to (pat("bd*4") to 8),
 *     ).pattern().unfold<String>()
 */
fun petriSong(vararg sections: Pair<String, Pair<PatPattern<*>, Int>>, loop: Boolean = true): PetriNet {
    require(sections.isNotEmpty()) { "A song needs at least one section" }
    return petri {
        timing = PetriTiming.TIMED
        for ((index, section) in sections.withIndex()) {
            val (sectionName, body) = section
            place(sectionName, colours = if (index == 0) listOf(body.first) else emptyList())
        }
        for ((index, section) in sections.withIndex()) {
            val (sectionName, body) = section
            val last = index == sections.lastIndex
            if (last && !loop) {
                t("play-$sectionName", dur = cyc(body.second)) { from(sectionName); to("$sectionName.done") }
            } else {
                val next = sections[(index + 1) % sections.size]
                t("play-$sectionName", dur = cyc(body.second)) {
                    from(sectionName)
                    // The token that leaves carries the *next* section's pattern, so the colour
                    // circulating round the ring is always what should be sounding next.
                    to(next.first) { binding -> listOf(binding.token(next.second.first)) }
                }
            }
        }
    }
}

// ── the live runtime ───────────────────────────────────────────────────────────────────────

/**
 * A net you can reach into while it is playing.
 *
 * [PetriRun] is the deterministic half of this file: a trajectory, memoised, queryable in any
 * order, and therefore a `PatPattern`. This is the other half. A performance is not a pure function
 * of time — someone drops a token in, forces a transition, mutes a voice — and none of that can
 * live inside something the pattern combinators are allowed to re-query.
 *
 * So the two are deliberately separate, and the seam is honest:
 *
 * - **[PetriRun]** is what you compose with. `every`, `jux`, `off`, `with gain(…)` — all of it.
 * - **[PetriLive]** is what you perform with. [inject], [remove], [force], and a picture that
 *   changes under your hands via `Box.showPetriNet`.
 *
 * Internally a live net *is* a run — the same engine, so the same semantics — that gets thrown away
 * and rebuilt from the current marking whenever you interfere with it. One consequence worth
 * knowing: under [PetriTiming.TIMED], interfering discards whatever was in flight at that moment.
 * That is the right answer for a performance (you changed the net; the old net's half-finished
 * business is not owed anything) but it is a real behaviour, not an accident.
 */
class PetriLive internal constructor(
    val net: PetriNet,
    from: PetriMarking = net.initial,
    startAt: PatQ = PatQ.ZERO,
) {
    private val lock = Any()
    private var run: PetriRun = net.run(from, startAt)
    private var consumedSteps = 0
    private var firingCounter = 0L

    /** The marking right now. */
    @Volatile
    var marking: PetriMarking = from
        private set

    /** How far the net has been advanced. */
    @Volatile
    var time: PatQ = startAt
        private set

    /**
     * Whether clicking an occupied unbounded place in the visualizer takes a token out rather than
     * putting another in. Off by default, because adding is the more common gesture.
     */
    @Volatile
    var clickRemoves: Boolean = false

    /** Called for every firing, whether it came from the clock or from [force]. */
    val listeners: MutableList<(PetriFiring) -> Unit> = java.util.concurrent.CopyOnWriteArrayList()

    /** Registers [listener] and returns a function that removes it again. */
    fun onFiring(listener: (PetriFiring) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    /** Runs the net forward to [instant], returning everything that fired on the way. */
    fun advanceTo(instant: PatQ): List<PetriFiring> {
        val fired = synchronized(lock) {
            val out = mutableListOf<PetriFiring>()
            while (true) {
                val steps = run.steps(consumedSteps + 1)
                if (steps.size <= consumedSteps) break
                val step = steps[consumedSteps]
                if (step.at > instant) break
                consumedSteps++
                marking = step.after
                time = step.at
                out += step.firings
            }
            out
        }
        fired.forEach { firing -> listeners.forEach { it(firing) } }
        return fired
    }

    /** Puts a token of [colour] into [place], now. */
    fun inject(place: String, colour: Any? = null, count: Int = 1): PetriLive = apply {
        synchronized(lock) {
            require(net.place(place) != null) { "No place called '$place' in this net" }
            rebuildLocked(marking.add(place, List(maxOf(1, count)) { PetriToken(colour, time) }))
        }
    }

    /** Takes the oldest token out of [place], if there is one. */
    fun remove(place: String, count: Int = 1): PetriLive = apply {
        synchronized(lock) {
            val present = marking[place]
            if (present.isEmpty()) return@synchronized
            rebuildLocked(marking.withPlace(place, present.drop(maxOf(1, count))))
        }
    }

    /** Empties [place]. */
    fun clear(place: String): PetriLive = apply {
        synchronized(lock) { rebuildLocked(marking.withPlace(place, emptyList())) }
    }

    /**
     * Fires [transition] right now, whether or not the clock was going to, and returns the firing —
     * or null if it is not enabled, in which case nothing happened.
     *
     * This is the performance gesture the classical model has no word for: the net's logic still
     * has to allow it (a transition with no tokens cannot be forced) but its *timing* is yours.
     */
    fun force(transition: String): PetriFiring? {
        val firing = synchronized(lock) {
            val spec = net.transition(transition) ?: return null
            val bound = petriBind(spec, marking, marking, time) ?: return null
            val produced = petriProduce(spec, bound.binding)
            var next = bound.consumeFrom(marking)
            for ((place, tokens) in produced) next = next.add(place, tokens)

            val start = time + spec.delay
            val length = if (spec.duration > PatQ.ZERO) spec.duration else net.grid
            rebuildLocked(next)
            PetriFiring(
                transition = spec.name,
                at = time,
                arc = afield.four.synth.PatArc(start, start + length),
                cycle = petriCycleOf(time),
                consumed = bound.binding.consumed,
                produced = produced.flatMap { it.second },
                emit = spec.emitOf?.invoke(bound.binding) ?: spec.emit,
                index = firingCounter++,
            )
        }
        listeners.forEach { it(firing) }
        return firing
    }

    /** Back to the marking it started from, at time zero. */
    fun reset(to: PetriMarking = net.initial): PetriLive = apply {
        synchronized(lock) {
            time = PatQ.ZERO
            rebuildLocked(to)
        }
    }

    /** A frame for the visualizer: the marking now, and what it enables. */
    fun frame(layout: PetriLayout = PetriLayout.AUTO, radius: Double = 240.0): PetriDrawing =
        PetriDrawing(net, marking, net.enabled(marking, time).toSet(), emptySet(), time, layout, radius = radius)

    /** Throws away the memoised future and starts a fresh one from [next] at the current instant. */
    private fun rebuildLocked(next: PetriMarking) {
        marking = next
        run = net.run(next, time)
        consumedSteps = 0
    }

    override fun toString(): String = "PetriLive(@$time, $marking)"
}

private fun petriCycleOf(at: PatQ): Long {
    val (quotient, remainder) = at.n.divideAndRemainder(at.d)
    return (if (remainder.signum() < 0) quotient.subtract(java.math.BigInteger.ONE) else quotient).toLong()
}

/** A live, pokeable instance of this net. See [PetriLive]. */
fun PetriNet.live(from: PetriMarking = initial, startAt: PatQ = PatQ.ZERO): PetriLive =
    PetriLive(this, from, startAt)

/**
 * Plays a live net in a box, on the beat.
 *
 * The clock work is not reimplemented here: a pure pattern of grid ticks goes through the language's
 * own `respond`, which already knows about strands, run generations, clock pauses and beat
 * alignment, and each tick advances the live net and hands you whatever fired. So a live net starts,
 * stops and re-evaluates with exactly the same behaviour as any other pattern in a box.
 *
 *     val live = net.live()
 *     live.play(cps = 2.0) { firing -> firing.emit?.let { dirt(it.toString()) } }
 *
 * The net is sampled on its own [PetriNet.grid], or finer if some transition is shorter than that,
 * so a firing is never missed between ticks.
 */
fun PetriLive.play(
    cps: Number = 1.0,
    clock: afield.four.synth.PatClock? = afield.four.synth.musicClock(),
    ahead: Number = 0.0,
    dispatchOnMain: Boolean = true,
    callback: (PetriFiring) -> Unit = {},
): afield.four.synth.PatResponseSpec<Unit> {
    val finest = net.transitions
        .mapNotNull { it.duration.takeIf { d -> d > PatQ.ZERO } }
        .minOrNull()
        ?.let { if (it < net.grid) it else net.grid }
        ?: net.grid
    return petriTicks(finest).respond(
        cps = cps,
        clock = clock,
        ahead = ahead,
        dispatchOnMain = dispatchOnMain,
    ) { event ->
        advanceTo(event.arc.start).forEach(callback)
    }
}

/**
 * A pure pattern of one silent event per [step] of a cycle — the metronome a live net is driven by.
 *
 * Deliberately trivial and deliberately *pure*: all the statefulness lives in the net, and the thing
 * carrying it to the scheduler is something the scheduler already understands.
 */
fun petriTicks(step: PatQ = PatQ.of(1, 16)): PatPattern<Unit> {
    require(step > PatQ.ZERO) { "A tick has to be longer than nothing" }
    return PatPattern { arc ->
        val out = mutableListOf<afield.four.synth.PatEvent<Unit>>()
        var index = java.math.BigInteger.ZERO.max((arc.start / step).let { floorQ(it) })
        // Start one step behind the window so a tick that began just before it is still offered.
        var cursor = step * PatQ(index, java.math.BigInteger.ONE)
        while (cursor < arc.start) {
            index = index.add(java.math.BigInteger.ONE)
            cursor = step * PatQ(index, java.math.BigInteger.ONE)
        }
        while (cursor < arc.end) {
            val cell = PatArc(cursor, cursor + step)
            cell.intersect(arc)?.let { visible ->
                out += afield.four.synth.PatEvent(visible, Unit, whole = cell)
            }
            index = index.add(java.math.BigInteger.ONE)
            cursor = step * PatQ(index, java.math.BigInteger.ONE)
        }
        out
    }
}

private fun floorQ(q: PatQ): java.math.BigInteger {
    val (quotient, remainder) = q.n.divideAndRemainder(q.d)
    return if (remainder.signum() < 0) quotient.subtract(java.math.BigInteger.ONE) else quotient
}
