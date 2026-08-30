package afield.four.texture.video

import kotlin.math.abs

/**
 * Gives an asynchronous frame set its whole display interval in which to prepare.
 *
 * After frame N is presented, N+1 becomes the pending demand immediately, but it is not due until
 * one source-frame interval later. A slow preparation remains the pending demand even when an
 * external wall clock has moved farther ahead: dropping that useful work is the starvation loop this
 * class exists to prevent. Explicit seeks/reconfiguration revisions may still supersede it.
 */
class FrameSetPacer(private val frameCount: Int) {
    data class Demand(val frame: Int, val notBeforeNanos: Long, val generation: Long) {
        fun isDue(nowNanos: Long): Boolean = nowNanos >= notBeforeNanos
    }

    private data class Cadence(val playing: Boolean, val rate: Double, val loop: Boolean)

    init {
        require(frameCount >= 1) { "frameCount must be at least one" }
    }

    private var revision: Long? = null
    private var cadence: Cadence? = null
    private var pending: Demand? = null
    private var presentedFrame: Int? = null
    private var lastPresentationDeadline = Long.MIN_VALUE
    private var nextGeneration = 0L

    val generation: Long get() = nextGeneration
    val pendingFrame: Int? get() = pending?.frame

    fun select(
        requestedFrame: Int,
        playing: Boolean,
        sourceFramesPerSecond: Double,
        loop: Boolean,
        revision: Long,
        nowNanos: Long
    ): Demand? {
        require(requestedFrame in 0 until frameCount)
        require(sourceFramesPerSecond.isFinite())
        val nextCadence = Cadence(playing, sourceFramesPerSecond, loop)
        val explicitChange = this.revision != revision
        val cadenceChange = cadence != nextCadence
        if (explicitChange || cadenceChange) {
            this.revision = revision
            cadence = nextCadence
            pending = null
            nextGeneration++
            val frame = when {
                explicitChange || presentedFrame == null -> requestedFrame
                !playing || sourceFramesPerSecond == 0.0 ->
                    requestedFrame.takeIf { it != presentedFrame }
                else -> adjacent(checkNotNull(presentedFrame), sourceFramesPerSecond, loop)
            }
            pending = frame?.let {
                Demand(
                    frame = it,
                    notBeforeNanos = if (explicitChange || presentedFrame == null) nowNanos
                    else nowNanos + intervalNanos(sourceFramesPerSecond),
                    generation = nextGeneration
                )
            }
        }
        pending?.let { return it }

        val shown = presentedFrame
        if (shown == null) {
            return Demand(requestedFrame, nowNanos, nextGeneration).also { pending = it }
        }
        if (!playing || sourceFramesPerSecond == 0.0) {
            return requestedFrame.takeIf { it != shown }
                ?.let { Demand(it, nowNanos, nextGeneration).also { demand -> pending = demand } }
        }
        val frame = adjacent(shown, sourceFramesPerSecond, loop) ?: return null
        val due = if (lastPresentationDeadline == Long.MIN_VALUE) nowNanos
        else lastPresentationDeadline + intervalNanos(sourceFramesPerSecond)
        return Demand(frame, due, nextGeneration).also { pending = it }
    }

    fun markPresented(frame: Int, nowNanos: Long) {
        val demand = checkNotNull(pending) { "no paced frame is pending" }
        check(demand.frame == frame) { "presented frame $frame while ${demand.frame} was pending" }
        presentedFrame = frame
        val interval = intervalNanos(checkNotNull(cadence).rate)
        // A normal render-frame quantization miss must not slow the whole movie: retain the ideal
        // phase. A genuine underrun of a full interval resets it, avoiding a burst of catch-up frames.
        lastPresentationDeadline = if (nowNanos - demand.notBeforeNanos >= interval) nowNanos
        else demand.notBeforeNanos
        pending = null
    }

    fun reset() {
        revision = null
        cadence = null
        pending = null
        presentedFrame = null
        lastPresentationDeadline = Long.MIN_VALUE
        nextGeneration++
    }

    private fun adjacent(frame: Int, rate: Double, loop: Boolean): Int? {
        val step = if (rate < 0.0) -1 else 1
        val next = frame + step
        if (next in 0 until frameCount) return next
        return if (loop) Math.floorMod(next, frameCount) else null
    }

    private fun intervalNanos(rate: Double): Long {
        val magnitude = abs(rate)
        check(magnitude > 0.0)
        return (1e9 / magnitude).toLong().coerceAtLeast(1L)
    }
}
