@file:afield.CompletionCategory("Patterns")
/**
 * The transport everything scheduled in `afield.four.synth` reads: one monotonic number that can be
 * paused, sought and subdivided, so that patterns, Boolean networks and audio launches share a
 * single notion of the current time.
 *
 * A [PatClock] reports that number as `now`, in the clock's own units. The shared clock advances
 * one unit per second of wall time, so a unit is a second until you say otherwise. There is no
 * tempo field: a faster grid comes from `subdivide`, which returns a child reading exactly `factor`
 * times its parent and following the parent's pauses and seeks.
 *
 * The constructor is internal, so a box reaches a clock two ways. `musicClock()` returns the one
 * process-wide clock, built on first call. `Box.patClock` is an inherited property, and the
 * metaroot is given the shared clock when it is created, so a box reads whatever its nearest
 * ancestor holds. Assigning `patClock` writes locally, which puts that box and everything under it
 * on a different timeline and leaves the rest of the document on the old one.
 *
 * Every scheduling entry point in this package spells its `clock` default the same way, the box's
 * inherited `patClock` and then the shared clock. The two `respond` families accept `null` there
 * instead, and their callback scope then measures time as seconds since the launch, which nothing
 * can pause or seek.
 *
 * Each method synchronises on one lock and returns the clock, so a setup line reads as one
 * expression:
 *
 *     val clock = musicClock().reset(started = true)
 *     val sixteenths = clock.subdivide(4.0, label = "16ths")
 */

package afield.four.synth

import afield.Box
import afield.stdlib.Inheritance

/**
 * A pausable, seekable clock: `now` is the reading the clock was last set to plus the time that has
 * passed since, scaled by [rate] and accumulated only while the clock is running.
 *
 * A clock with no [parent] reads the system nanosecond timer. A child made by [subdivide] reads its
 * parent's `now` instead, so the whole tree stops when the root does.
 *
 * Every accessor takes the same lock, so a clock can be read from an audio thread, a coroutine and
 * the run loop at once. The mutators all return `this`.
 *
 * @param parent the clock this one measures against, or null to measure against wall time
 * @param rate units of this clock per unit of its source, which must be finite and positive
 * @param label a name for `toString`, and nothing else
 */
class PatClock internal constructor(
    private val parent: PatClock? = null,
    val rate: Double = 1.0,
    val label: String? = null
) {
    init {
        require(rate.isFinite() && rate > 0.0) { "PatClock.rate must be finite and > 0" }
    }

    companion object {
        @Volatile
        private var shared: PatClock? = null

        /**
         * The one process-wide clock, created on first call with `rate` 1.0 and the label
         * `"global"`.
         *
         * With [started] left true this also calls [start] on it, which starts a clock that is
         * stopped and leaves the reading of one that is already running alone. Pass false to read
         * the shared clock without starting it either.
         */
        fun shared(started: Boolean = true): PatClock {
            val existing = shared
            if (existing != null) {
                if (started) existing.start()
                return existing
            }
            return synchronized(this) {
                shared?.also {
                    if (started) it.start()
                } ?: PatClock(label = "global").also {
                    shared = it
                    if (started) it.start()
                }
            }
        }
    }

    private val lock = Any()
    private var accumulated = 0.0
    private var sourceAnchor = 0.0
    private var running = false

    /** Whether the clock is currently accumulating time. A paused clock still reports its `now`. */
    val isRunning: Boolean
        get() = synchronized(lock) { running }

    /**
     * The clock's current reading, in the clock's own units.
     *
     * This is what every scheduler in the package compares an event time against, and it is the
     * unit `every` is measured in throughout `BeatLaunch.kt`.
     */
    val now: Double
        get() = synchronized(lock) { nowLocked() }

    /**
     * Start accumulating time, optionally setting the reading to [at] first.
     *
     * The reading never moves on its own here. Resuming a paused clock continues from where it
     * stopped, and calling this on a clock that is already running keeps the reading it has: the
     * time since the last anchor is folded in before the clock is re-anchored against its source.
     * A bare `start()` is therefore safe to call on a clock someone else is already scheduling
     * against, which is what makes a bare `musicClock()` safe.
     *
     * Pass [at] to set the reading, or use [seek] and [reset], when you do want it to jump.
     */
    fun start(at: Number? = null): PatClock = apply {
        synchronized(lock) {
            // nowLocked() is the reading in all three cases: `accumulated` for a clock that is
            // paused or has never run, and the accrued reading for one that is running. Folding it
            // in before re-anchoring is what keeps start() from discarding the elapsed time.
            accumulated = at?.toDouble() ?: nowLocked()
            sourceAnchor = sourceNow()
            running = true
        }
    }

    /** Stop accumulating time, keeping the current reading. `start` resumes from there. */
    fun pause(): PatClock = apply {
        synchronized(lock) {
            accumulated = nowLocked()
            running = false
        }
    }

    /** `pause` when running, `start` when not. The two calls are not atomic with each other. */
    fun toggle(): PatClock = apply {
        if (isRunning) pause() else start()
    }

    /**
     * Jump the reading to [time], leaving the running state alone.
     *
     * Seeking a running clock keeps it running from the new reading. A waiter's target is a fixed
     * number rather than a recomputed one, so seeking past a pending target releases it on the
     * next poll, and seeking back behind one makes it wait until the clock arrives there again.
     */
    fun seek(time: Number): PatClock = apply {
        synchronized(lock) {
            accumulated = time.toDouble()
            sourceAnchor = sourceNow()
        }
    }

    /**
     * Set the reading to zero and the running state to [started].
     *
     * Note the default: a bare `reset()` leaves the clock stopped, where a bare `musicClock()`
     * leaves it running. `reset(started = true)` is the usual opening line of a live fence.
     */
    fun reset(started: Boolean = false): PatClock = apply {
        synchronized(lock) {
            accumulated = 0.0
            sourceAnchor = sourceNow()
            running = started
        }
    }

    /**
     * A child clock running [factor] times as fast as this one, seeded so that it reads
     * `this.now * factor`.
     *
     * The child measures against this clock rather than against wall time, and it holds that
     * product for as long as it is left running, whatever the parent does: pausing or seeking the
     * parent moves the child with it, and the two cannot drift apart. Pausing the *child* is what
     * breaks the relationship, by the length of the pause.
     *
     * This is how the package spells a tempo. `musicClock().subdivide(2.0)` is a grid of
     * half-second beats, on which `every = 1` means what `every = 0.5` means on its parent.
     *
     * [started] defaults to the parent's running state at the moment of the call.
     */
    fun subdivide(factor: Number, label: String? = null, started: Boolean = isRunning): PatClock {
        val scale = factor.toDouble()
        require(scale.isFinite() && scale > 0.0) { "PatClock subdivision factor must be finite and > 0" }
        val seed = now * scale
        val child = PatClock(parent = this, rate = scale, label = label)
        child.seek(seed)
        if (started) child.start(seed)
        return child
    }

    override fun toString(): String =
        "PatClock(label=${label ?: "clock"}, now=${"%.4f".format(now)}, rate=$rate, running=$isRunning)"

    private fun sourceNow(): Double = parent?.now ?: System.nanoTime().toDouble() / 1_000_000_000.0

    private fun nowLocked(): Double {
        if (!running) return accumulated
        val elapsed = sourceNow() - sourceAnchor
        return accumulated + elapsed * rate
    }
}

/**
 * The clock this box schedules against, inherited from the nearest ancestor that has one.
 *
 * The metaroot is given the shared clock when it is created, so this resolves for any box in a
 * document without anyone setting it. Assignment writes locally, so putting a sub-tree on its own
 * timeline is one line in the parent box:
 *
 *     patClock = musicClock().subdivide(3.0, label = "triplets")
 */
var Box.patClock by Inheritance<PatClock>()

/**
 * The shared clock, started unless you say otherwise.
 *
 * Every scheduling entry point in the package falls back to this when there is no box context to
 * read `patClock` from. It is also what the metaroot's `patClock` is set to, so a box that has
 * never been told about clocks is already on it.
 */
fun musicClock(started: Boolean = true): PatClock = PatClock.shared(started)
