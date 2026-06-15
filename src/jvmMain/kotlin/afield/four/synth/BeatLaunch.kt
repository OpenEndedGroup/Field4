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

private const val BEAT_ALIGNMENT_EPSILON = 1e-6
private const val CLOCK_WAIT_SLICE_SECONDS = 0.05
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

fun <T> T.onNextBeat(
    clock: PatClock = currentPatClock(),
    preroll: Number = 0.0
): BeatLaunchSpec = onBeat(
    every = 1.0,
    clock = clock,
    offset = 0.0,
    preroll = preroll
)

@RunsAt(returnValueRegistration, priority = 9)
fun registerBeatLaunchReturnValue() {
    ReturnValueRegistry.handlers += handler@{ inside, returnValue, strand, generation, notify ->
        val spec = returnValue as? BeatLaunchSpec ?: return@handler false

        CoroutineScope(Dispatchers.Default + Box.boxContextElement(inside)).launch {
            notify(true, strand, generation)
            val job = coroutineContext[Job]!!
            inside.jobs += job

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

                ReturnValueRegistry.launchWithGeneration(
                    inside = inside,
                    returnValue = spec.value,
                    strand = strand,
                    generation = generation
                ) { running, innerStrand, watermark ->
                    if (!running) notify(false, innerStrand, watermark)
                }
                handedOff = true
            } finally {
                if (!handedOff) notify(false, strand, generation)
            }
        }
        true
    }
}
