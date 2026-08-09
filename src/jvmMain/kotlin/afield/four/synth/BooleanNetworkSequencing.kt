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

    val end: Double
        get() = start + duration

    fun isActive(lane: Int): Boolean = lane in state.indices && state[lane]

    fun wasActive(lane: Int): Boolean = previousState?.let { lane in it.indices && it[lane] } ?: false

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
    val launchElapsed: Double
        get() = (System.nanoTime() - launchNanoTime).toDouble() / 1_000_000_000.0

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
