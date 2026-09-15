@file:afield.CompletionCategory("Patterns")
/**
 * Gives a [BooleanNetwork]'s states a duration, which is all it takes to read one as music.
 *
 * [BooleanNetwork] itself is untimed: it is a list of nodes whose update rules all fire at once,
 * and `step` takes one state vector to the next. Everything here is an extension on that class, so
 * the dependency runs one way only. Nothing in the network model knows about clocks, notes or
 * boxes, and the network can be built, explored and drawn without any of this.
 *
 * A timed run is a [BooleanNetworkStep] per update: the state, the state before it, and a start
 * and duration in seconds. One node is one lane, indexed as the network indexes its nodes, and
 * [BooleanTriggerMode] decides what counts as an event on a lane. Choosing the mode does most of
 * the musical work, because a node that stays on for six steps is six notes under `ACTIVE` and one
 * under `RISING_EDGE`.
 *
 * There are three ways out, in increasing order of commitment:
 *
 * - `scheduledSteps` and `scheduledDrivenSteps` hand back the raw timed states as an `Iterator`,
 *   for anything that wants to read the rhythm itself.
 * - `scheduledNotes` and `asNoteProcess` take a mapper from a step to [ScheduledNote]s and give
 *   back an iterator or a [NoteProcess], which is the same thing a hand-written sequence produces,
 *   so `notesBetween` and `pianoRollSvg` work on it unchanged.
 * - `respond` and `respondAhead` build a [BooleanNetworkResponseSpec], which runs the network
 *   against a [PatClock] in real time and calls back per step. Returning one from a box is what
 *   starts it.
 *
 * The `Driven` half of each pair takes a [BooleanNetworkStateDriver], a lambda that writes into the
 * state vector before the network reads it. The network then updates from the forced value as
 * though it had produced it, which is how an oscillator, a MIDI input or a second network gets to
 * be one of the lanes.
 *
 * Leaving `steps` null makes every one of these iterators infinite. That is the right answer for a
 * responder and the wrong one for anything that collects, so pass a bound when you are building a
 * list.
 */

package afield.four.synth

import afield.*
import afield.stdlib.BooleanNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * What counts as a lane firing on a given step, given the lane's state now and on the step before.
 *
 * `ACTIVE` fires while the lane is on, `RISING_EDGE` when it turns on, `FALLING_EDGE` when it
 * turns off, and `CHANGE` on either transition. Sustained nodes are dense under `ACTIVE` and sparse
 * under the edge modes, which is the main dial between a shimmer and a drum part.
 *
 * The first step of a run has no previous state, so every lane that starts on reads as a rising
 * edge and none reads as falling. A network started on a fixed point therefore fires under
 * `CHANGE` at step zero, on whichever lanes are already on, and never again.
 */
enum class BooleanTriggerMode {
    ACTIVE,
    RISING_EDGE,
    FALLING_EDGE,
    CHANGE,
}

/**
 * Mutates the Boolean state that will be emitted for a scheduled step before the
 * network advances. Useful for forcing clock lanes or other external inputs.
 */
typealias BooleanNetworkStateDriver =
    (stepIndex: Long, start: Double, duration: Double, state: BooleanArray) -> Unit

/**
 * One update of a Boolean network, placed in time: the state it emitted, the state before it, and
 * the seconds it occupies.
 *
 * [previousState] is the state that was *emitted* on the step before, which on a driven run means
 * the driver's writes are included in it. Edges are therefore measured against what was heard, not
 * against what the network would have produced on its own. It is null on the first step.
 *
 * `state` and `previousState` are private copies, so holding a step is safe while the run
 * continues. Being a data class over arrays, though, the generated `equals` compares them by
 * identity: two steps with the same bits are not equal to each other.
 *
 * The bit string in `toString` reads lane 0 first, which is the reverse of
 * `BooleanNetwork.stateString`.
 */
data class BooleanNetworkStep(
    val stepIndex: Long,
    val start: Double,
    val duration: Double,
    val state: BooleanArray,
    val previousState: BooleanArray? = null,
) {
    init {
        require(start.isFinite()) { "BooleanNetworkStep.start must be finite" }
        require(duration.isFinite() && duration > 0.0) { "BooleanNetworkStep.duration must be finite and > 0" }
    }

    /** When the next step begins. Steps are contiguous, so this is also the following `start`. */
    val end: Double
        get() = start + duration

    /** Whether [lane] is on now. A lane outside the network's range is off rather than an error. */
    fun isActive(lane: Int): Boolean = lane in state.indices && state[lane]

    /** Whether [lane] was on one step ago. False on the first step, which has nothing to compare. */
    fun wasActive(lane: Int): Boolean = previousState?.let { lane in it.indices && it[lane] } ?: false

    /** Whether [lane] counts as an event under [mode]. See [BooleanTriggerMode] for the four. */
    fun fires(lane: Int, mode: BooleanTriggerMode = BooleanTriggerMode.ACTIVE): Boolean {
        val active = isActive(lane)
        val previous = wasActive(lane)
        return when (mode) {
            BooleanTriggerMode.ACTIVE -> active
            BooleanTriggerMode.RISING_EDGE -> active && !previous
            BooleanTriggerMode.FALLING_EDGE -> !active && previous
            BooleanTriggerMode.CHANGE -> active != previous
        }
    }

    /**
     * Every lane firing under [mode], in ascending lane order. The usual body of a step mapper:
     *
     *     step.lanes(BooleanTriggerMode.RISING_EDGE).map { lane -> note(pitch = 36 + lane * 3, …) }
     */
    fun lanes(mode: BooleanTriggerMode = BooleanTriggerMode.ACTIVE): List<Int> =
        state.indices.filter { fires(it, mode) }

    override fun toString(): String = synthPrettyObject(
        this,
        "stepIndex" to stepIndex,
        "start" to start,
        "duration" to duration,
        "state" to state.toBitString(),
        "previousState" to previousState?.toBitString()
    )
}

private fun BooleanArray.toBitString(): String = joinToString(separator = "") { if (it) "1" else "0" }

private fun defaultBooleanNetworkClock(): PatClock? = Box.context.get()?.patClock ?: musicClock()

private fun validateBooleanSchedule(
    network: BooleanNetwork,
    initialState: BooleanArray,
    stepDuration: Number,
    startSeconds: Number,
    steps: Int?,
): Pair<Double, Double> {
    require(initialState.size == network.size) {
        "Initial Boolean state size ${initialState.size} does not match network size ${network.size}"
    }
    val duration = stepDuration.toDouble()
    require(duration.isFinite() && duration > 0.0) { "stepDuration must be finite and > 0" }
    val start = startSeconds.toDouble()
    require(start.isFinite()) { "startSeconds must be finite" }
    require(steps == null || steps >= 0) { "steps must be >= 0 when provided" }
    return start to duration
}

private fun BooleanNetwork.scheduledStepsIterator(
    initialState: BooleanArray,
    stepDuration: Number = 0.25,
    startSeconds: Number = 0.0,
    steps: Int? = null,
    driver: BooleanNetworkStateDriver? = null,
): Iterator<BooleanNetworkStep> {
    val (start, duration) = validateBooleanSchedule(this, initialState, stepDuration, startSeconds, steps)
    val seed = initialState.copyOf()

    return object : Iterator<BooleanNetworkStep> {
        private var emitted = 0
        private var current = seed
        private var previous: BooleanArray? = null

        override fun hasNext(): Boolean = steps == null || emitted < steps

        override fun next(): BooleanNetworkStep {
            if (!hasNext()) throw NoSuchElementException("No more Boolean network steps")

            val stepIndex = emitted.toLong()
            val stepStart = start + emitted * duration
            val drivenState = current.copyOf().also { driver?.invoke(stepIndex, stepStart, duration, it) }

            val step = BooleanNetworkStep(
                stepIndex = stepIndex,
                start = stepStart,
                duration = duration,
                state = drivenState.copyOf(),
                previousState = previous?.copyOf(),
            )

            previous = drivenState.copyOf()
            current = this@scheduledStepsIterator.step(drivenState)
            emitted += 1

            return step
        }
    }
}

/**
 * Run the network from [initialState] and yield one [BooleanNetworkStep] per update, each
 * [stepDuration] seconds long and starting at [startSeconds].
 *
 * The iterator is infinite unless [steps] is given. Times are absolute seconds on whatever timeline
 * the consumer is using; nothing here reads a clock.
 *
 * @throws IllegalArgumentException if [initialState] is not the network's size, or if a duration or
 * a start time is not finite and positive where it has to be
 */
fun BooleanNetwork.scheduledSteps(
    initialState: BooleanArray,
    stepDuration: Number = 0.25,
    startSeconds: Number = 0.0,
    steps: Int? = null,
): Iterator<BooleanNetworkStep> = scheduledStepsIterator(
    initialState = initialState,
    stepDuration = stepDuration,
    startSeconds = startSeconds,
    steps = steps,
)

/**
 * `scheduledSteps` with an outside hand on the state vector.
 *
 * [driver] is called with a copy of the state the network is about to emit, and may write into it.
 * The step carries what the driver left, and the network's next update is computed from that too,
 * so a forced lane propagates through the rules exactly as a self-produced one would. This is the
 * seam for an oscillator, a MIDI input or a second network.
 */
fun BooleanNetwork.scheduledDrivenSteps(
    initialState: BooleanArray,
    stepDuration: Number = 0.25,
    startSeconds: Number = 0.0,
    steps: Int? = null,
    driver: BooleanNetworkStateDriver,
): Iterator<BooleanNetworkStep> = scheduledStepsIterator(
    initialState = initialState,
    stepDuration = stepDuration,
    startSeconds = startSeconds,
    steps = steps,
    driver = driver,
)

private fun scheduledNotesFromSteps(
    events: Iterator<BooleanNetworkStep>,
    mapper: (BooleanNetworkStep) -> Iterable<ScheduledNote>,
): Iterator<ScheduledNote> {
    return object : Iterator<ScheduledNote> {
        private val pending = ArrayDeque<ScheduledNote>()

        private fun prime() {
            while (pending.isEmpty() && events.hasNext()) {
                pending.addAll(
                    mapper(events.next()).sortedWith(compareBy<ScheduledNote> { it.start }.thenByDescending { it.pitch })
                )
            }
        }

        override fun hasNext(): Boolean {
            prime()
            return pending.isNotEmpty()
        }

        override fun next(): ScheduledNote {
            if (!hasNext()) throw NoSuchElementException("No more scheduled Boolean-network notes")
            return pending.removeFirst()
        }
    }
}

/**
 * `scheduledSteps` with a [mapper] from each step to the notes it should sound, flattened into one
 * note iterator.
 *
 * A step's notes are sorted by start and then by descending pitch before they are handed out, so a
 * chord arrives top voice first. That ordering is within a step: the iterator does not sort across
 * steps, which it could not do without buffering an unbounded run.
 */
fun BooleanNetwork.scheduledNotes(
    initialState: BooleanArray,
    stepDuration: Number = 0.25,
    startSeconds: Number = 0.0,
    steps: Int? = null,
    mapper: (BooleanNetworkStep) -> Iterable<ScheduledNote>,
): Iterator<ScheduledNote> {
    val events = scheduledSteps(
        initialState = initialState,
        stepDuration = stepDuration,
        startSeconds = startSeconds,
        steps = steps,
    )
    return scheduledNotesFromSteps(events, mapper)
}

/** `scheduledNotes` over a driven run: [driver] writes the state, [mapper] reads the step. */
fun BooleanNetwork.scheduledDrivenNotes(
    initialState: BooleanArray,
    stepDuration: Number = 0.25,
    startSeconds: Number = 0.0,
    steps: Int? = null,
    driver: BooleanNetworkStateDriver,
    mapper: (BooleanNetworkStep) -> Iterable<ScheduledNote>,
): Iterator<ScheduledNote> {
    val events = scheduledDrivenSteps(
        initialState = initialState,
        stepDuration = stepDuration,
        startSeconds = startSeconds,
        steps = steps,
        driver = driver,
    )
    return scheduledNotesFromSteps(events, mapper)
}

/**
 * The mapped run as a [NoteProcess], which is what the piano-roll and playback tooling takes.
 *
 * The process holds an iterator *factory* rather than an iterator, so it can be rewound: `reset`
 * re-runs the network from a fresh copy of [initialState] and calls [mapper] again, which for a
 * pure mapper reproduces the same notes. [historyRetentionSeconds] bounds how far back played
 * notes are kept, and the two `default…Seconds` arguments are the window `pianoRollSvg` draws when
 * it is given neither.
 */
fun BooleanNetwork.asNoteProcess(
    initialState: BooleanArray,
    stepDuration: Number = 0.25,
    startSeconds: Number = 0.0,
    steps: Int? = null,
    historyRetentionSeconds: Double = 8.0,
    defaultPastSeconds: Double = 1.0,
    defaultFutureSeconds: Double = 6.0,
    mapper: (BooleanNetworkStep) -> Iterable<ScheduledNote>,
): NoteProcess {
    val start = startSeconds.toDouble()
    return NoteProcess(
        iteratorFactory = {
            scheduledNotes(
                initialState = initialState.copyOf(),
                stepDuration = stepDuration,
                startSeconds = start,
                steps = steps,
                mapper = mapper,
            )
        },
        historyRetentionSeconds = historyRetentionSeconds,
        defaultPastSeconds = defaultPastSeconds,
        defaultFutureSeconds = defaultFutureSeconds,
    ).reset(start)
}

/**
 * `asNoteProcess` over a driven run.
 *
 * [driver] is re-invoked from the beginning on every reset, so a driver that depends only on
 * `stepIndex` rewinds with the network while one that reads a live input does not.
 */
fun BooleanNetwork.asDrivenNoteProcess(
    initialState: BooleanArray,
    stepDuration: Number = 0.25,
    startSeconds: Number = 0.0,
    steps: Int? = null,
    historyRetentionSeconds: Double = 8.0,
    defaultPastSeconds: Double = 1.0,
    defaultFutureSeconds: Double = 6.0,
    driver: BooleanNetworkStateDriver,
    mapper: (BooleanNetworkStep) -> Iterable<ScheduledNote>,
): NoteProcess {
    val start = startSeconds.toDouble()
    return NoteProcess(
        iteratorFactory = {
            scheduledDrivenNotes(
                initialState = initialState.copyOf(),
                stepDuration = stepDuration,
                startSeconds = start,
                steps = steps,
                driver = driver,
                mapper = mapper,
            )
        },
        historyRetentionSeconds = historyRetentionSeconds,
        defaultPastSeconds = defaultPastSeconds,
        defaultFutureSeconds = defaultFutureSeconds,
    ).reset(start)
}

/**
 * The receiver a `respond` callback runs against: the box it belongs to, the network and settings
 * it was launched with, and `now`.
 *
 * `now` is the clock's reading when there is a clock, and seconds since the launch when `clock` was
 * null. That second timeline cannot be paused or sought, which is the trade for not needing one.
 */
class BooleanNetworkResponseScope internal constructor(
    val box: Box,
    val network: BooleanNetwork,
    val initialState: BooleanArray,
    val stepDuration: Double,
    val clock: PatClock?,
    val ahead: Double,
    val launchInstant: Instant,
    internal val launchNanoTime: Long = System.nanoTime(),
) {
    /** Seconds of wall time since the responder started, whether or not there is a clock. */
    val launchElapsed: Double
        get() = (System.nanoTime() - launchNanoTime).toDouble() / 1_000_000_000.0

    /** The timeline this responder is scheduling against: [clock]'s reading, or [launchElapsed]. */
    val now: Double
        get() = clock?.now ?: launchElapsed

    override fun toString(): String = synthPrettyObject(
        this,
        "networkSize" to network.size,
        "initialState" to initialState.toBitString(),
        "stepDuration" to stepDuration,
        "clock" to clock,
        "ahead" to ahead,
        "launchInstant" to launchInstant,
    )
}

/**
 * A clocked Boolean-network run, built and not yet started.
 *
 * Like every other launchable in the package this is inert until a box returns it, which is what
 * lets it be bundled with `+` and wrapped in `.onBeat`.
 */
class BooleanNetworkResponseSpec internal constructor(
    internal val network: BooleanNetwork,
    internal val initialState: BooleanArray,
    internal val stepDuration: Double,
    internal val offsetSeconds: Double,
    internal val steps: Int?,
    internal val dispatchOnMain: Boolean,
    internal val clock: PatClock?,
    internal val ahead: Double,
    internal val callback: BooleanNetworkResponseScope.(BooleanNetworkStep) -> Unit,
) {
    override fun toString(): String = synthPrettyObject(
        this,
        "stepDuration" to stepDuration,
        "ahead" to ahead,
        "offsetSeconds" to offsetSeconds,
        "steps" to steps,
        "clock" to clock,
        "dispatchOnMain" to dispatchOnMain,
        "networkSize" to network.size,
        "initialState" to initialState.toBitString(),
    )
}

/**
 * Build a real-time run of this network that calls [callback] once per step, for a box to return.
 *
 * Note how the start time differs from the iterator functions. Those take an absolute
 * `startSeconds`; this takes [offsetSeconds], measured from the clock reading at the moment the
 * responder starts, so the same expression re-run later picks up from wherever the clock is rather
 * than trying to catch up from zero. Steps whose start has already gone by when they come up are
 * skipped rather than played late.
 *
 * [ahead] moves the callback earlier than the step it describes, for a consumer with its own
 * scheduling queue; the step still reports its true `start`. [dispatchOnMain] puts the callback on
 * the run loop, which is what makes it safe to touch boxes and graphics from inside it.
 *
 * Passing `clock = null` swaps the shared clock for seconds since the launch.
 */
fun BooleanNetwork.respond(
    initialState: BooleanArray,
    stepDuration: Number = 0.25,
    offsetSeconds: Number = 0.0,
    steps: Int? = null,
    dispatchOnMain: Boolean = true,
    clock: PatClock? = defaultBooleanNetworkClock(),
    ahead: Number = 0.0,
    callback: BooleanNetworkResponseScope.(BooleanNetworkStep) -> Unit,
): BooleanNetworkResponseSpec {
    validateBooleanSchedule(this, initialState, stepDuration, 0.0, steps)
    val offset = offsetSeconds.toDouble()
    require(offset.isFinite()) { "offsetSeconds must be finite" }
    val aheadSeconds = ahead.toDouble().coerceAtLeast(0.0)
    require(aheadSeconds.isFinite()) { "ahead must be finite" }
    return BooleanNetworkResponseSpec(
        network = this,
        initialState = initialState.copyOf(),
        stepDuration = stepDuration.toDouble(),
        offsetSeconds = offset,
        steps = steps,
        dispatchOnMain = dispatchOnMain,
        clock = clock,
        ahead = aheadSeconds,
        callback = callback,
    )
}

/**
 * `respond` with [ahead] promoted to a required second argument, for the common case of driving
 * something that needs its events a little early:
 *
 *     net.respondAhead(initialState = attr.cycle.first(), ahead = 0.05, stepDuration = 0.25) { step ->
 *         step.lanes(BooleanTriggerMode.RISING_EDGE).forEach { lane -> kit.trigger("bd:$lane") }
 *     }
 */
fun BooleanNetwork.respondAhead(
    initialState: BooleanArray,
    ahead: Number,
    stepDuration: Number = 0.25,
    offsetSeconds: Number = 0.0,
    steps: Int? = null,
    dispatchOnMain: Boolean = true,
    clock: PatClock? = defaultBooleanNetworkClock(),
    callback: BooleanNetworkResponseScope.(BooleanNetworkStep) -> Unit,
): BooleanNetworkResponseSpec = respond(
    initialState = initialState,
    stepDuration = stepDuration,
    offsetSeconds = offsetSeconds,
    steps = steps,
    dispatchOnMain = dispatchOnMain,
    clock = clock,
    ahead = ahead,
    callback = callback,
)

/**
 * Teaches box execution about [BooleanNetworkResponseSpec]. Called once during return-value
 * registration, not from box code.
 *
 * One wait per step rather than one per event, since a step already carries every lane that fires
 * at that instant. Otherwise the loop matches the pattern responder in `PatPatternReturnValue.kt`:
 * a job owned by the box, the run generation re-checked from inside the wait, and late steps
 * skipped instead of played behind the beat.
 */
@RunsAt(returnValueRegistration, priority = 10)
fun registerBooleanNetworkResponseReturnValue() {
    ReturnValueRegistry.handlers += handler@{ inside, returnValue, strand, generation, launchEpoch, notify ->
        val spec = returnValue as? BooleanNetworkResponseSpec ?: return@handler false

        CoroutineScope(Dispatchers.Default + Box.boxContextElement(inside)).launch {
            val job = coroutineContext[Job]!!
            if (!inside.registerLaunchJob(job, launchEpoch)) {
                notify(false, strand, generation)
                return@launch
            }
            notify(true, strand, generation)

            val scope = BooleanNetworkResponseScope(
                box = inside,
                network = spec.network,
                initialState = spec.initialState.copyOf(),
                stepDuration = spec.stepDuration,
                clock = spec.clock,
                ahead = spec.ahead,
                launchInstant = Instant.now(),
            )

            try {
                val iterator = spec.network.scheduledSteps(
                    initialState = spec.initialState.copyOf(),
                    stepDuration = spec.stepDuration,
                    startSeconds = scope.now + spec.offsetSeconds,
                    steps = spec.steps,
                )

                while (isActive && inside.runGeneration[strand] == generation && iterator.hasNext()) {
                    val event = iterator.next()
                    if (scope.now > event.start + 1e-6) continue

                    waitForPatTime(
                        target = event.start - spec.ahead,
                        now = { scope.now },
                        isRunning = spec.clock?.let { { it.isRunning } },
                        shouldContinue = { inside.runGeneration[strand] == generation },
                    )
                    if (!isActive || inside.runGeneration[strand] != generation) break

                    if (spec.dispatchOnMain) {
                        withContext(Runloop.runLoop.main + Box.boxContextElement(inside)) {
                            Box.withBoxContextNow(inside) {
                                spec.callback(scope, event)
                            }
                        }
                    } else {
                        Box.withBoxContextNow(inside) {
                            spec.callback(scope, event)
                        }
                    }
                }
            } finally {
                inside.jobs.remove(job)
                notify(false, strand, generation)
            }
        }
        true
    }
}
