@file:afield.runtime.completion.CompletionCategory("Math")

package afield.math.oracle

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Sequence memory for low-dimensional continuous time series: remember one or a few
 * demonstrations, cue the memory with a recent trajectory, and let it continue in a related
 * style while a performer takes over channels.
 *
 * [Trajectory] holds uniformly sampled multichannel recordings with episode boundaries;
 * [GestureFeatures] turns one into scaled value-and-velocity frames; [VariableMarkovOracle]
 * (Wang and Dubnov's VMO, a Factor Oracle over thresholded continuous frames) indexes the
 * repeated material; [Improviser] walks it, recording where every output sample came from.
 * Tests: `VariableMarkovOracleTest`.
 */

/**
 * A uniformly sampled multichannel recording: `values[channel][t]` at [sampleRate] samples per
 * second, split into one or more episodes.
 *
 * Episodes are separate demonstrations stored end to end. [episodeStarts] marks where each
 * begins, so nothing built from a trajectory invents a transition from the last sample of one
 * demonstration to the first of the next.
 */
class Trajectory(
    /** Samples per second. */
    val sampleRate: Double,
    /** One name per channel. */
    val channels: List<String>,
    /** `values[channel][t]`; every channel has the same length. */
    val values: Array<DoubleArray>,
    /** First sample of each episode, ascending, starting with 0. */
    val episodeStarts: IntArray = intArrayOf(0),
    /** Where this recording came from (free text, kept for provenance). */
    val name: String = "",
) {
    init {
        require(channels.size == values.size) { "${channels.size} channel names for ${values.size} channels" }
        require(values.isNotEmpty()) { "a trajectory needs at least one channel" }
        require(values.all { it.size == values[0].size }) { "channels differ in length" }
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(episodeStarts.isNotEmpty() && episodeStarts[0] == 0) { "episodeStarts must begin with 0" }
        for (i in 1 until episodeStarts.size) require(episodeStarts[i] > episodeStarts[i - 1] && episodeStarts[i] < length) { "bad episode start ${episodeStarts[i]}" }
    }

    /** Number of samples. */
    val length: Int get() = values[0].size

    /** Number of channels. */
    val width: Int get() = values.size

    /** Duration in seconds. */
    val duration: Double get() = length / sampleRate

    /** The samples of channel [name]. */
    fun channel(name: String): DoubleArray = values[channels.indexOf(name).also { require(it >= 0) { "no channel '$name' in $channels" } }]

    /** All channels at sample [t]. */
    fun frame(t: Int): DoubleArray = DoubleArray(width) { values[it][t] }

    /** Index of the episode containing sample [t]. */
    fun episodeOf(t: Int): Int {
        var e = 0
        while (e + 1 < episodeStarts.size && episodeStarts[e + 1] <= t) e++
        return e
    }

    /** True when [t] is the last sample of its episode, so `t + 1` is not its continuation. */
    fun endsEpisode(t: Int): Boolean = t == length - 1 || episodeStarts.contains(t + 1)

    /** True when [t] begins an episode. */
    fun startsEpisode(t: Int): Boolean = episodeStarts.contains(t)

    /** Episode [e] as a trajectory of its own. */
    fun episode(e: Int): Trajectory {
        val from = episodeStarts[e]
        val to = if (e + 1 < episodeStarts.size) episodeStarts[e + 1] else length
        return Trajectory(sampleRate, channels, Array(width) { values[it].copyOfRange(from, to) }, name = "$name[$e]")
    }

    /** Every channel as a `name to samples` pair, ready for `showSignals(*t.series())`. */
    fun series(prefix: String = ""): Array<Pair<String, List<Double>>> =
        Array(width) { "$prefix${channels[it]}" to values[it].toList() }

    /** Resampled to [rate] by linear interpolation, episode by episode. */
    fun resample(rate: Double): Trajectory {
        val parts = episodeStarts.indices.map { e ->
            val ep = episode(e)
            val n = max(1, (ep.length * rate / sampleRate).toInt())
            Array(width) { c ->
                DoubleArray(n) { i ->
                    val x = i * sampleRate / rate
                    val a = x.toInt().coerceAtMost(ep.length - 1)
                    val b = (a + 1).coerceAtMost(ep.length - 1)
                    val f = x - a
                    ep.values[c][a] * (1 - f) + ep.values[c][b] * f
                }
            }
        }
        return join(parts.map { Trajectory(rate, channels, it) }, name)
    }

    /**
     * Each episode with its still lead-in and tail removed: samples at the start and end where the
     * speed (RMS over channels of the step, each channel in units of its own typical step) stays
     * below [threshold] times the episode's median speed. [pad] seconds of the stillness are kept
     * at each end, so a take still begins and ends at rest. An episode that is still throughout is
     * kept whole.
     */
    fun trimStill(threshold: Double = 0.25, pad: Double = 0.3): Trajectory {
        val stepSd = DoubleArray(width) { c ->
            var s = 0.0; var n = 0
            for (i in 1 until length) if (!startsEpisode(i)) { val d = values[c][i] - values[c][i - 1]; s += d * d; n++ }
            sqrt(s / max(1, n)).coerceAtLeast(1e-12)
        }
        val keep = (pad * sampleRate).toInt()
        val parts = episodeStarts.indices.map { e ->
            val ep = episode(e)
            val speed = DoubleArray(ep.length) { i ->
                if (i == 0) 0.0 else sqrt((0 until width).sumOf { c -> ((ep.values[c][i] - ep.values[c][i - 1]) / stepSd[c]).let { it * it } } / width)
            }
            val median = speed.sorted()[speed.size / 2]
            val moving = speed.indices.filter { speed[it] >= threshold * median }
            if (moving.isEmpty()) ep
            else {
                val from = (moving.first() - keep).coerceAtLeast(0)
                val to = (moving.last() + keep).coerceAtMost(ep.length - 1)
                Trajectory(sampleRate, channels, Array(width) { c -> ep.values[c].copyOfRange(from, to + 1) }, name = ep.name)
            }
        }
        return join(parts, name)
    }

    override fun toString() = "Trajectory($name: $width ch $channels, $length samples @ ${sampleRate}Hz, ${episodeStarts.size} episode(s))"

    companion object {
        /** A one-episode trajectory from named channels, e.g. `Trajectory.of(30.0, "x" to xs, "y" to ys)`. */
        fun of(sampleRate: Double, vararg channels: Pair<String, List<Number>>, name: String = ""): Trajectory =
            Trajectory(sampleRate, channels.map { it.first }, Array(channels.size) { c -> DoubleArray(channels[c].second.size) { channels[c].second[it].toDouble() } }, name = name)

        /** Episodes kept apart: the result remembers where each one starts. */
        fun join(episodes: List<Trajectory>, name: String = episodes.joinToString("+") { it.name }): Trajectory {
            require(episodes.isNotEmpty())
            val first = episodes[0]
            require(episodes.all { it.channels == first.channels && it.sampleRate == first.sampleRate }) { "episodes must share channels and sample rate" }
            val starts = ArrayList<Int>()
            var at = 0
            for (e in episodes) { for (s in e.episodeStarts) starts.add(at + s); at += e.length }
            return Trajectory(first.sampleRate, first.channels, Array(first.width) { c -> episodes.map { it.values[c] }.reduce { a, b -> a + b } }, starts.toIntArray(), name)
        }
    }
}

/**
 * Frozen per-channel scaling and the feature frames a [VariableMarkovOracle] compares.
 *
 * Each frame is the channel values, centred and divided by their spread, followed by the first
 * differences divided by *their* spread and multiplied by [velocityWeight]. The velocity half is
 * what tells a path crossing itself in one direction from the same point crossed the other way.
 * [channelWeights] scale both halves of a channel; a weight of 0 ignores it.
 *
 * Spreads have a floor ([scaleFloor] times the largest spread, or 1e-9), so a channel that never
 * moves does not divide by zero. With [shapeOnly] the value half is dropped and only motion is
 * matched, regardless of where it happens.
 */
class GestureFeatures(
    val center: DoubleArray,
    val scale: DoubleArray,
    val velocityScale: DoubleArray,
    val velocityWeight: Double = 1.0,
    val channelWeights: DoubleArray = DoubleArray(center.size) { 1.0 },
    val shapeOnly: Boolean = false,
) {
    /** Channels. */
    val width: Int get() = center.size

    /** Length of one feature frame. */
    val dimension: Int get() = if (shapeOnly) width else 2 * width

    /** Features of a single sample given its predecessor's values ([previous] = null at an episode start). */
    fun frame(current: DoubleArray, previous: DoubleArray?, into: DoubleArray = DoubleArray(dimension)): DoubleArray {
        var k = 0
        if (!shapeOnly) for (c in 0 until width) into[k++] = (current[c] - center[c]) / scale[c] * channelWeights[c]
        for (c in 0 until width) into[k++] = if (previous == null) 0.0 else (current[c] - previous[c]) / velocityScale[c] * velocityWeight * channelWeights[c]
        return into
    }

    /** Feature frames for every sample of [t]; velocity is 0 at each episode start. */
    fun frames(t: Trajectory): Array<DoubleArray> {
        val out = Array(t.length) { DoubleArray(dimension) }
        var prev: DoubleArray? = null
        for (i in 0 until t.length) {
            val cur = t.frame(i)
            frame(cur, if (t.startsEpisode(i)) null else prev, out[i])
            prev = cur
        }
        return out
    }

    companion object {
        /** Scales fitted to [t] (standard deviations, episode boundaries excluded from differences). */
        fun fit(
            t: Trajectory,
            velocityWeight: Double = 1.0,
            channelWeights: DoubleArray = DoubleArray(t.width) { 1.0 },
            shapeOnly: Boolean = false,
            scaleFloor: Double = 1e-3,
        ): GestureFeatures {
            val center = DoubleArray(t.width) { t.values[it].average() }
            val sd = DoubleArray(t.width) { c -> sqrt(t.values[c].sumOf { (it - center[c]) * (it - center[c]) } / t.length) }
            val vsd = DoubleArray(t.width) { c ->
                var s = 0.0; var n = 0
                for (i in 1 until t.length) if (!t.startsEpisode(i)) { val d = t.values[c][i] - t.values[c][i - 1]; s += d * d; n++ }
                sqrt(s / max(1, n))
            }
            val f1 = max(1e-9, scaleFloor * (sd.maxOrNull() ?: 0.0))
            val f2 = max(1e-9, scaleFloor * (vsd.maxOrNull() ?: 0.0))
            return GestureFeatures(center, DoubleArray(t.width) { max(sd[it], f1) }, DoubleArray(t.width) { max(vsd[it], f2) }, velocityWeight, channelWeights, shapeOnly)
        }
    }
}

internal fun distance(a: DoubleArray, b: DoubleArray): Double {
    var s = 0.0
    for (i in a.indices) { val d = a[i] - b[i]; s += d * d }
    return sqrt(s)
}
