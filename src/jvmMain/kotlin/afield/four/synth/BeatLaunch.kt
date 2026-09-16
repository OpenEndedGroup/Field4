@file:afield.CompletionCategory("Patterns")
/**
 * Quantised launching: `.onBeat(…)` holds any launchable value until the next boundary of a
 * [PatClock]'s grid, and `waitForBeat(…)` is the same wait spelled as a suspend call for code
 * inside `frames { }` or `loop { }`.
 *
 * A grid here is two numbers against a clock. `every` is the spacing, in that clock's units, and
 * `offset` slides the whole grid, so `every = 4, offset = 1` puts boundaries at 1, 5, 9 and so on.
 * `preroll` moves only the wake-up, not the boundary: waking a little early is how work that takes
 * time can still land on the beat, and the value reported back is always the boundary itself.
 *
 * `.onBeat` is an extension on `T`, not on any launch type, so it wraps whatever it is given,
 * including a bundle built with `+`. It returns a [BeatLaunchSpec], which does nothing on its own.
 * The handler registered at the bottom of this file recognises that spec when it arrives as a
 * box's last expression, waits, and then hands the value it was wrapping back to the normal launch
 * machinery. A bundle wrapped once therefore starts as a unit:
 *
 *     (audio { saw * 0.05f } +
 *         pat("bd [hh hh] sd").respond(cps = 0.5) { event ->
 *             __m("pat").patHighlight(event)
 *         }).onBeat(every = 4, preroll = 0.02)
 *
 * Because the wait runs as a job owned by the box, stopping the box before the boundary arrives
 * cancels the launch rather than deferring it.
 *
 * A boundary the clock is already sitting on, within `1e-6` of a clock unit, counts as reached, so
 * `.onBeat` on the downbeat starts now rather than a full period later.
 */

package afield.four.synth

import afield.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToLong

/** How close to a boundary counts as on it, in clock units, both when snapping and when waiting. */
private const val BEAT_ALIGNMENT_EPSILON = 1e-6

/**
 * The longest a wait against a real clock sleeps in one go. Slicing is what lets a pause, a seek or
 * a stop take effect part way through a long wait instead of at the end of it.
 */
private const val CLOCK_WAIT_SLICE_SECONDS = 0.05

/** How often a wait re-checks a paused clock, which is not advancing towards anything. */
private const val PAUSED_CLOCK_POLL_MILLIS = 20L

internal fun currentPatClock(): PatClock = Box.context.get()?.patClock ?: musicClock()

private data class BeatAlignment(
    val clock: PatClock,
    val every: Double,
    val offset: Double,
    val preroll: Double
) {
    init {
        require(every.isFinite() && every > 0.0) { "Beat alignment period must be finite and > 0" }
        require(offset.isFinite()) { "Beat alignment offset must be finite" }
        require(preroll.isFinite() && preroll >= 0.0) { "Beat alignment preroll must be finite and >= 0" }
    }

    fun boundary(from: Double = clock.now): Double {
        require(from.isFinite()) { "Beat alignment start time must be finite" }
        val phase = positiveMod(from - offset, every)
        val delta = when {
            phase <= BEAT_ALIGNMENT_EPSILON -> 0.0
            every - phase <= BEAT_ALIGNMENT_EPSILON -> 0.0
            else -> every - phase
        }
        return from + delta
    }

    fun release(from: Double = clock.now): Double {
        val boundary = boundary(from)
        return max(from, boundary - preroll)
    }
}

private fun positiveMod(value: Double, modulus: Double): Double {
    val mod = value % modulus
    return if (mod < 0.0) mod + modulus else mod
}

internal suspend fun waitForPatTime(
    target: Double,
    now: () -> Double,
    isRunning: (() -> Boolean)? = null,
    shouldContinue: () -> Boolean = { true }
) {
    while (currentCoroutineContext().isActive && shouldContinue()) {
        val remaining = target - now()
        if (remaining <= BEAT_ALIGNMENT_EPSILON) return

        val waitMillis = when {
            isRunning == null -> (remaining * 1000.0).roundToLong().coerceAtLeast(1L)
            !isRunning() -> PAUSED_CLOCK_POLL_MILLIS
            else -> (remaining.coerceAtMost(CLOCK_WAIT_SLICE_SECONDS) * 1000.0).roundToLong().coerceAtLeast(1L)
        }
        delay(waitMillis)
    }
}

/**
 * The next grid boundary at or after [from], without waiting for it.
 *
 * Returns [from] unchanged when it is already within `1e-6` of a boundary on either side, so a
 * reading that falls a hair short is treated as having arrived rather than being pushed a whole
 * period on. Use it to line a hand-built schedule up with the grid something else launches on.
 */
fun PatClock.nextBeat(
    every: Number = 1.0,
    offset: Number = 0.0,
    from: Number = now
): Double = BeatAlignment(
    clock = this,
    every = every.toDouble(),
    offset = offset.toDouble(),
    preroll = 0.0
).boundary(from.toDouble())

/**
 * Suspend until the next grid boundary, and return the boundary's time.
 *
 * [preroll] pulls the *return* forward by that much, never before the current reading, while the
 * number handed back is still the boundary. So a caller that needs 20 ms of setup asks for
 * `preroll = 0.02`, does the work, and schedules it at the value it was given.
 *
 * A clock paused short of the boundary holds the wait open rather than releasing it early: it
 * polls every 20 ms while stopped and goes back to counting down when the clock starts again.
 */
suspend fun PatClock.awaitBeat(
    every: Number = 1.0,
    offset: Number = 0.0,
    preroll: Number = 0.0
): Double {
    val alignment = BeatAlignment(
        clock = this,
        every = every.toDouble(),
        offset = offset.toDouble(),
        preroll = preroll.toDouble()
    )
    val from = now
    val boundary = alignment.boundary(from)
    waitForPatTime(
        target = alignment.release(from),
        now = { now },
        isRunning = { isRunning }
    )
    return boundary
}

/**
 * `awaitBeat` against the box's own clock, for `frames { }` and `loop { }` bodies that want to line
 * up with the grid mid-flight:
 *
 *     frames {
 *         waitForBeat(every = 4, preroll = 0.02)
 *         println("downbeat")
 *     }
 *
 * [clock] defaults to the box's inherited `patClock`, falling back to the shared clock.
 */
suspend fun waitForBeat(
    clock: PatClock = currentPatClock(),
    every: Number = 1.0,
    offset: Number = 0.0,
    preroll: Number = 0.0
): Double = clock.awaitBeat(
    every = every,
    offset = offset,
    preroll = preroll
)

/**
 * A launchable value with a grid attached, waiting to be returned from a box.
 *
 * Holding the value rather than starting it is what makes `.onBeat` composable: the spec is inert
 * until the registered handler picks it out of a box's return value, and at that point the value
 * inside goes through the ordinary dispatch, bundles and all.
 */
class BeatLaunchSpec internal constructor(
    internal val value: Any?,
    internal val clock: PatClock,
    internal val every: Double,
    internal val offset: Double,
    internal val preroll: Double
) {
    override fun toString(): String = synthPrettyObject(
        this,
        "value" to value,
        "clock" to clock,
        "every" to every,
        "offset" to offset,
        "preroll" to preroll
    )
}

/**
 * Hold this value until the next boundary of [clock]'s `every` / [offset] grid, then launch it.
 *
 * The receiver is unconstrained, so this wraps anything a box can return: an `audio { }` patch, a
 * pattern responder, a Boolean network responder, or a `+` bundle of several. The wrapper only
 * matters when the box returns it.
 *
 * [preroll] releases the wait that much early, for a value whose own startup costs time.
 */
fun <T> T.onBeat(
    every: Number = 1.0,
    clock: PatClock = currentPatClock(),
    offset: Number = 0.0,
    preroll: Number = 0.0
): BeatLaunchSpec = BeatLaunchSpec(
    value = this,
    clock = clock,
    every = every.toDouble(),
    offset = offset.toDouble(),
    preroll = preroll.toDouble()
)

/** `onBeat` on the plainest grid there is: every 1 unit of [clock], with no offset. */
fun <T> T.onNextBeat(
    clock: PatClock = currentPatClock(),
    preroll: Number = 0.0
): BeatLaunchSpec = onBeat(
    every = 1.0,
    clock = clock,
    offset = 0.0,
    preroll = preroll
)

/**
 * Teaches box execution about [BeatLaunchSpec]. Called once during return-value registration, not
 * from box code.
 *
 * The handler waits on a job it registers with the box, so the deferred launch is stoppable during
 * the wait, and then passes the wrapped value to the launch machinery under the *same* strand and
 * generation. Reusing the generation is what keeps the deferred value stoppable afterwards too:
 * the thing that finally runs answers to the box's original launch rather than to a fresh one.
 */
@RunsAt(returnValueRegistration, priority = 9)
fun registerBeatLaunchReturnValue() {
    ReturnValueRegistry.handlers += handler@{ inside, returnValue, strand, generation, launchEpoch, notify ->
        val spec = returnValue as? BeatLaunchSpec ?: return@handler false

        CoroutineScope(Dispatchers.Default + Box.boxContextElement(inside)).launch {
            val job = coroutineContext[Job]!!
            if (!inside.registerLaunchJob(job, launchEpoch)) {
                notify(false, strand, generation)
                return@launch
            }
            notify(true, strand, generation)

            var handedOff = false

            try {
                val alignment = BeatAlignment(
                    clock = spec.clock,
                    every = spec.every,
                    offset = spec.offset,
                    preroll = spec.preroll
                )
                val from = spec.clock.now
                waitForPatTime(
                    target = alignment.release(from),
                    now = { spec.clock.now },
                    isRunning = { spec.clock.isRunning },
                    shouldContinue = { inside.runGeneration[strand] == generation }
                )
                if (!isActive || inside.runGeneration[strand] != generation) return@launch

                handedOff = ReturnValueRegistry.launchWithGeneration(
                    inside = inside,
                    returnValue = spec.value,
                    strand = strand,
                    generation = generation,
                    launchEpoch = launchEpoch,
                ) { running, innerStrand, watermark ->
                    if (!running) notify(false, innerStrand, watermark)
                }
            } finally {
                inside.jobs.remove(job)
                if (!handedOff) notify(false, strand, generation)
            }
        }
        true
    }
}
