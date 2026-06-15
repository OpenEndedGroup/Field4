package afield.four.synth

import afield.Box
import afield.stdlib.Inheritance

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

        fun shared(started: Boolean = true): PatClock {
            val existing = shared
            if (existing != null) {
                if (started) existing.start()
                return existing
            }
            return synchronized(this) {
                shared?.also {
                    if (started) it.start()
                    it
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

    val isRunning: Boolean
        get() = synchronized(lock) { running }

    val now: Double
        get() = synchronized(lock) { nowLocked() }

    fun start(at: Number? = null): PatClock = apply {
        synchronized(lock) {
            at?.let { accumulated = it.toDouble() }
            sourceAnchor = sourceNow()
            running = true
        }
    }

    fun pause(): PatClock = apply {
        synchronized(lock) {
            accumulated = nowLocked()
            running = false
        }
    }

    fun toggle(): PatClock = apply {
        if (isRunning) pause() else start()
    }

    fun seek(time: Number): PatClock = apply {
        synchronized(lock) {
            accumulated = time.toDouble()
            sourceAnchor = sourceNow()
        }
    }

    fun reset(started: Boolean = false): PatClock = apply {
        synchronized(lock) {
            accumulated = 0.0
            sourceAnchor = sourceNow()
            running = started
        }
    }

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

var Box.patClock by Inheritance<PatClock>()

fun musicClock(started: Boolean = true): PatClock = PatClock.shared(started)
