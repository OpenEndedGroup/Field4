package afield.math.distribution

import afield.audio.synth.Param
import afield.audio.synth.PatArc
import afield.audio.synth.PatEvent
import afield.audio.synth.PatPattern
import afield.audio.synth.PatQ
import afield.audio.synth.Signal
import afield.audio.synth.SynthContext
import afield.audio.synth.patQHash
import afield.audio.synth.signalOf
import java.util.SplittableRandom

// ---------------------------------------------------------------------------------------------
// Distributions as live inputs: patterns and audio parameters that draw from a Dist
// ---------------------------------------------------------------------------------------------

/**
 * Where live draws come from: one plan for one or more roots, drawing a joint sample at a time.
 *
 * Without evidence a draw is one pass over the graph. With evidence it is sampling importance
 * resampling: [pool] weighted draws, then one of them chosen by weight, so each draw is
 * (approximately) from the posterior. A bigger pool is a better posterior draw and a slower one.
 */
internal class DrawSource(roots: List<Dist>, private val pool: Int) {
    private val plan = Plan(roots)
    val weighted: Boolean = plan.hasEvidence
    private val width = roots.size

    init { require(pool >= 1) { "posteriorPool must be at least 1" } }

    /** One joint draw of every root. */
    fun draw(rng: SplittableRandom): DoubleArray {
        if (!weighted) { val s = plan.sample(1, rng, batch = 1); return DoubleArray(width) { s[it][0] } }
        val ws = plan.sampleWeighted(pool, rng, batch = pool)
        val w = normalizeLogWeights(ws.logWeights!!)
        var total = 0.0
        for (v in w) total += v
        var u = rng.nextDouble() * total
        var k = 0
        while (k < w.size - 1 && u >= w[k]) { u -= w[k]; k++ }
        return DoubleArray(width) { ws.values[it][k] }
    }

    /** [n] independent draws of the first root, in one pass where possible. */
    fun drawMany(n: Int, rng: SplittableRandom): DoubleArray {
        if (!weighted) return plan.sample(n, rng)[0]
        return DoubleArray(n) { draw(rng)[0] }
    }
}

/** The seed for a draw at an exact musical instant: the same instant always draws the same value. */
private fun seedAt(exact: PatQ, salt: Int): Long = patQHash(exact) xor (salt.toLong() * -0x61c8864680b583ebL)

/**
 * This distribution as a continuous pattern: queried at any instant, it answers with a draw
 * seeded by that instant's exact position, so the same position always gives the same value,
 * on any machine, however the pattern is queried. It is `rand()` with a distribution behind it.
 *
 * Like any signal it is sampled where it is used: `segment(8)` draws eight values a cycle, and
 * `pat("bd*8") with gain(d.pat())` draws one per event.
 *
 * ```kotlin
 * val step = categorical(listOf(0, 2, 4, 7, 9), listOf(4, 1, 2, 3, 1))   // a weighted pentatonic
 * pat("x*8") with note(step.pat().segment(8))                             // eight draws a cycle
 * ```
 *
 * [salt] gives an independent stream for the same distribution. A posterior (one with
 * [Dist.given]) draws [posteriorPool] weighted samples per value and keeps one by weight.
 */
fun Dist.pat(salt: Int = 0, posteriorPool: Int = 256): PatPattern<Double> = jointPatterns(this, salt = salt, posteriorPool = posteriorPool)[0]

/**
 * Several distributions as patterns that draw *together*: at any instant the patterns' values are
 * one joint sample, so their dependencies survive. With `val pitch = 60 + die(12)` and
 * `val loud = (pitch gt 66) * 0.5 + 0.4`, `jointPatterns(pitch, loud)` plays the high notes loud.
 * Separate [pat] calls would draw each independently.
 */
fun jointPatterns(vararg ds: Dist, salt: Int = 0, posteriorPool: Int = 256): List<PatPattern<Double>> {
    require(ds.isNotEmpty()) { "jointPatterns needs at least one distribution" }
    val source = DrawSource(ds.toList(), posteriorPool)
    // the patterns usually ask about the same instant one after another: keep the last few joint draws
    val cache = object : LinkedHashMap<PatQ, DoubleArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PatQ, DoubleArray>?) = size > 64
    }
    fun at(exact: PatQ): DoubleArray = synchronized(cache) {
        cache.getOrPut(exact) { source.draw(SplittableRandom(seedAt(exact, salt))) }
    }
    return ds.indices.map { k -> signalOf { exact, _ -> at(exact)[k] } }
}

/**
 * A vector of distributions as one cycle of steps: step `k` of every cycle plays `this[k]`, and the
 * whole vector is drawn *jointly*, once per cycle, seeded by the cycle's number. So a rhythm
 * conditioned to have five hits, or a curve from a Gaussian process, plays as one coherent draw per
 * cycle, a different one each cycle, the same one every time that cycle comes round.
 *
 * ```kotlin
 * val steps = List(16) { bernoulli(0.4) }
 * val rhythm = steps.given(steps.sum() eq 5)                // any five of sixteen, a new five each bar
 * struct(rhythm.pat().map { it != 0.0 }, pat("hh"))
 * ```
 *
 * The events are discrete (each step is a note with a start), unlike [Dist.pat]'s continuous signal.
 */
fun List<Dist>.pat(salt: Int = 0, posteriorPool: Int = 256): PatPattern<Double> {
    require(isNotEmpty()) { "a pattern of no distributions" }
    val steps = size
    val source = DrawSource(this, posteriorPool)
    val cache = object : LinkedHashMap<Long, DoubleArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, DoubleArray>?) = size > 32
    }
    fun cycle(c: Long): DoubleArray = synchronized(cache) {
        cache.getOrPut(c) { source.draw(SplittableRandom(seedAt(PatQ.of(c), salt + 0x5eed))) }
    }
    return PatPattern { arc ->
        val out = ArrayList<PatEvent<Double>>()
        val qr = arc.start.n.divideAndRemainder(arc.start.d)
        var c = qr[0].toLong() - if (qr[1].signum() < 0) 1 else 0
        while (PatQ.of(c) < arc.end) {
            val values = cycle(c)
            for (k in 0 until steps) {
                val ws = PatQ.of(c) + PatQ.of(k.toLong(), steps.toLong())
                val we = PatQ.of(c) + PatQ.of(k + 1L, steps.toLong())
                val s = if (ws > arc.start) ws else arc.start
                val e = if (we < arc.end) we else arc.end
                if (s < e || (s == e && ws == we)) out += PatEvent(PatArc(s, e), values[k], whole = PatArc(ws, we))
            }
            c++
        }
        out
    }
}

/**
 * An audio [Param] whose value is a draw from a distribution, made by [Dist.param].
 *
 * It owns its random stream and its draws are made on the audio thread, in [generate], so nothing
 * else advances it; reading the value from elsewhere goes through [played]. A new value is drawn
 * once per audio block, every [everySeconds] or [everyBeats], or on each rising edge of [trigger];
 * between draws the value holds (sample-and-hold), and a per-block draw ramps across its block.
 */
class DistParam internal constructor(
    /** The distribution drawn from. */
    val dist: Dist,
    /** Seconds between draws, or null. */
    val everySeconds: Double?,
    /** Beats between draws (Ableton Link beats, or [fallbackBpm] without Link), or null. */
    val everyBeats: Double?,
    /** A signal whose rising edges (through zero, upwards) each make a draw, or null. */
    val trigger: Signal?,
    /** The tempo used for [everyBeats] when no Ableton Link session is running. */
    val fallbackBpm: Double,
    seed: Long,
    posteriorPool: Int,
) : Param() {
    private val source = DrawSource(listOf(dist), posteriorPool)
    private val rng = SplittableRandom(seed)
    private val pool = DoubleArray(256)
    private var poolNext = pool.size
    private var value = Float.NaN
    private var startPos = Double.NaN // seconds or beats at the first draw
    private var nextK = 1L            // the next draw is at startPos + nextK * period
    private var lastTrigger = 0f
    // our own frame clock: ctx.time is a Float and drifts by whole frames within seconds
    private var frame = 0L
    private var lastCycle = Long.MIN_VALUE
    private var lastFrames = 0

    // what was heard, for the visualizer: a ring the audio thread writes and anyone may copy
    private val ring = FloatArray(8192)
    @Volatile private var ringCount = 0L

    private fun nextDraw(): Float {
        if (poolNext >= pool.size) {
            val fresh = source.drawMany(pool.size, rng)
            System.arraycopy(fresh, 0, pool, 0, pool.size)
            poolNext = 0
        }
        val v = pool[poolNext++].toFloat()
        ring[(ringCount % ring.size).toInt()] = v
        ringCount++
        return v
    }

    /** How many values have been drawn so far. */
    val drawCount: Long get() = ringCount

    /** The most recent draws, oldest first: up to the last 8192, copied, so any thread may read them. */
    fun played(): DoubleArray {
        val c = ringCount
        val k = minOf(c, ring.size.toLong()).toInt()
        val start = c - k
        return DoubleArray(k) { ring[((start + it) % ring.size).toInt()].toDouble() }
    }

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        frame = if (ctx.cycleId == lastCycle + 1) frame + lastFrames else Math.round(ctx.time.toDouble() * ctx.sampleRate)
        lastCycle = ctx.cycleId
        lastFrames = numFrames
        if (value.isNaN()) value = nextDraw()
        when {
            trigger != null -> {
                val t = ctx.acquireBuf(numFrames)
                try {
                    trigger.render(t, numFrames, ctx)
                    for (i in 0 until numFrames) {
                        if (lastTrigger <= 0f && t[i] > 0f) value = nextDraw()
                        lastTrigger = t[i]
                        output[i] = value
                    }
                } finally { ctx.releaseBuf(t) }
            }
            everySeconds != null || everyBeats != null -> {
                // position in seconds or beats at the start of this block, and its step per frame
                val beats = everyBeats != null
                val period = everyBeats ?: everySeconds!!
                val start: Double
                val perFrame: Double
                val timing = if (beats) ctx.linkTiming else null
                if (timing != null) {
                    start = timing.beat
                    perFrame = (if (timing.tempo > 0) timing.tempo else fallbackBpm) / 60.0 / ctx.sampleRate
                } else {
                    val unitsPerSecond = if (beats) fallbackBpm / 60.0 else 1.0
                    perFrame = unitsPerSecond / ctx.sampleRate
                    start = frame * perFrame
                }
                if (startPos.isNaN()) startPos = start
                for (i in 0 until numFrames) {
                    val pos = start + i * perFrame
                    // compare against startPos + k * period rather than a running sum, so no drift
                    // builds up, with a sliver of tolerance for the boundary frame itself
                    if (pos >= startPos + nextK * period - 1e-9 * period) {
                        value = nextDraw()
                        nextK++
                        if (startPos + nextK * period <= pos) { startPos = pos; nextK = 1 } // a jump in time
                    }
                    output[i] = value
                }
            }
            else -> {
                val from = value
                val to = nextDraw()
                val step = (to - from) / numFrames
                for (i in 0 until numFrames) output[i] = from + step * (i + 1)
                value = to
            }
        }
        if (numFrames > 0) publishObservedValue(output[numFrames - 1])
    }

    override fun toString(): String = "DistParam(${dist.describe()})"
}

/**
 * This distribution as an audio parameter: anything that takes a [Param] (an oscillator's
 * frequency, a filter's cutoff, a gain) can take a stream of draws.
 *
 * - With no arguments, a new draw every audio block, ramped across the block: a jittering value.
 * - `everySeconds = 0.25`: a new draw four times a second, held in between.
 * - `everyBeats = 1.0`: a new draw every beat of the Ableton Link session, or of [fallbackBpm]
 *   when there is none.
 * - `trigger = someSignal`: a new draw at each rising edge of the signal, sample-accurately.
 *
 * ```kotlin
 * val pitch = categorical(listOf(220, 247.5, 275, 330, 366.7), listOf(3, 1, 2, 2, 1))
 * val osc = SineOsc(freq = pitch.param(everySeconds = 0.25))
 * ```
 *
 * The draws are made on the audio thread from the parameter's own stream ([seed]); [DistParam.played]
 * returns the values heard, which is what `ᛟ.distribution(param)` draws.
 */
fun Dist.param(
    everySeconds: Double? = null,
    everyBeats: Double? = null,
    trigger: Signal? = null,
    seed: Long = 1L,
    fallbackBpm: Double = 120.0,
    posteriorPool: Int = 256,
): DistParam {
    require(listOfNotNull(everySeconds, everyBeats, trigger).size <= 1) { "give at most one of everySeconds, everyBeats and trigger" }
    everySeconds?.let { require(it > 0) { "everySeconds must be positive" } }
    everyBeats?.let { require(it > 0) { "everyBeats must be positive" } }
    return DistParam(this, everySeconds, everyBeats, trigger, fallbackBpm, seed, posteriorPool)
}
