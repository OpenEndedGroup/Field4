package afield.math.oracle

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * A Variable Markov Oracle (Wang and Dubnov, 2014) over a [Trajectory]: a Factor Oracle
 * (Allauzen, Crochemore and Raffinot, 1999) built on continuous frames, where two frames count
 * as "the same symbol" when their [GestureFeatures] lie within [threshold] of each other.
 *
 * State `i` (1-based) holds sample `i - 1`; state 0 is the empty start. The structure is three
 * arrays:
 * - forward transitions — `i → i + 1` along the recording, plus oracle transitions to later
 *   states that continue the same context;
 * - suffix links [sfx] — the earlier state ending the longest repeated suffix of the material
 *   up to `i`;
 * - [lrs] — the length of that repeated suffix, the amount of shared context.
 *
 * Suffix links are what make recombination possible: jumping from `i` to `sfx[i]` and carrying
 * on from there continues something that, for the last `lrs[i]` samples, sounded the same.
 *
 * Episode boundaries are respected: the first sample of an episode gets no forward transition
 * from the last sample of the previous one, and [lrs] never reaches back across a boundary.
 *
 * This is a Kotlin port of the construction in Cheng-i Wang's reference Python `vmo` package
 * (GPL-3.0, compatible with Field's AGPL-3.0+), kept in Kotlin so it can be stepped inside the
 * frame loop. `VmoReferenceTest` cross-checks the suffix links against the pinned package.
 */
class VariableMarkovOracle internal constructor(
    /** The remembered material. */
    val trajectory: Trajectory,
    /** The scaling and feature construction used for every comparison. */
    val features: GestureFeatures,
    /** Feature frames, one per sample. */
    val frames: Array<DoubleArray>,
    /** Frames closer than this are the same symbol. */
    val threshold: Double,
) {
    private val n = frames.size
    private val trn = Array(n + 1) { IntArrayList() }
    private val rsfx = Array(n + 1) { IntArrayList() }

    /** Suffix link per state; `sfx[0] = -1`. */
    val sfx = IntArray(n + 1)

    /** Length of the repeated suffix per state. */
    val lrs = IntArray(n + 1)

    /** Symbol (cluster of frames within [threshold]) per state; state 0 is symbol 0. */
    val symbol = IntArray(n + 1)
    private var symbolCount = 1

    init {
        sfx[0] = -1
        for (i in 1..n) add(i)
    }

    /** Number of states (samples + 1). */
    val size: Int get() = n + 1

    private fun episodeStartState(i: Int): Int = trajectory.episodeStarts[trajectory.episodeOf(i - 1)] + 1

    private fun add(i: Int) {
        val x = frames[i - 1]
        val newEpisode = i > 1 && trajectory.startsEpisode(i - 1)
        var k: Int
        var pi1: Int
        if (newEpisode) { k = 0; pi1 = 0 } else { trn[i - 1].add(i); k = sfx[i - 1]; pi1 = i - 1 }
        var best = -1
        while (k != -1) {
            var bd = Double.POSITIVE_INFINITY
            trn[k].forEach { t -> if (t < i) { val d = distance(frames[t - 1], x); if (d < threshold && d < bd) { bd = d; best = t } } }
            if (best >= 0) break
            trn[k].add(i); pi1 = k; k = sfx[k]
        }
        if (k == -1 || best < 0) { sfx[i] = 0; lrs[i] = 0; symbol[i] = symbolCount++ }
        else {
            sfx[i] = best
            lrs[i] = commonSuffix(pi1, best - 1) + 1
            symbol[i] = symbol[best]
        }
        // the reference's "_find_better": prefer a sibling whose context extends one further
        val want = symbol[i - lrs[i]]
        val siblings = rsfx[sfx[i]]
        for (q in 0 until siblings.size) {
            val j = siblings[q]
            if (lrs[j] == lrs[i] && j - lrs[i] >= 0 && symbol[j - lrs[i]] == want) { lrs[i]++; sfx[i] = j; break }
        }
        lrs[i] = min(lrs[i], i - episodeStartState(i) + 1)
        rsfx[sfx[i]].add(i)
    }

    private fun commonSuffix(p1: Int, p2In: Int): Int {
        var p2 = p2In
        if (p2 == sfx[p1]) return lrs[p1]
        while (p2 != 0 && sfx[p2] != sfx[p1]) p2 = sfx[p2]
        return min(lrs[p1], lrs[p2])
    }

    /** Forward transitions out of state [i]. */
    fun transitions(i: Int): IntArray = trn[i].toArray()

    /** States whose suffix link points at [i]. */
    fun reverseSuffixes(i: Int): IntArray = rsfx[i].toArray()

    /** Number of distinct symbols: states whose suffix link is the start state. */
    val alphabetSize: Int get() = (1..n).count { sfx[it] == 0 }

    /**
     * Information rate per sample, in bits: the reference package's `IR(ir_type='cum')`.
     *
     * The oracle is compressed into Compror codewords (a new symbol, or a copy of a repeated
     * block). At sample `i`, `h0` is `log2` of the number of distinct symbols so far and `h1` is
     * `log2` of the number of codewords so far divided by the length of the block containing `i`.
     * The rate is `max(0, h0 - h1)`: how much cheaper the past makes the present.
     *
     * Too small a threshold makes every frame a new symbol (nothing repeats, rate ≈ 0); too
     * large makes everything one symbol (nothing to say, rate ≈ 0). [build] keeps the threshold
     * whose [totalInformationRate] is highest, as the reference's `find_threshold` does.
     */
    fun informationRate(): DoubleArray {
        val cw0 = DoubleArray(n); val cw1 = DoubleArray(n); val bl = DoubleArray(n)
        var j = 0; var i = 0; var at = 0
        while (j < n) {
            while (i < n && lrs[i + 1] >= i - j + 1) i++
            if (i == j) { i++; cw0[at] = 1.0; cw1[at] = 1.0; bl[at] = 1.0; at++ }
            else { val l = i - j; cw1[at] = 1.0; for (q in at until min(n, at + l)) bl[q] = l.toDouble(); at += l }
            j = i
        }
        val out = DoubleArray(n)
        var c0 = 0.0; var c1 = 0.0
        val log2 = ln(2.0)
        for (q in 0 until n) {
            c0 += cw0[q]; c1 += cw1[q]
            val h0 = if (c0 > 0) ln(c0) / log2 else Double.NEGATIVE_INFINITY
            val h1 = if (c1 > 0) ln(c1) / log2 / bl[q] else Double.NEGATIVE_INFINITY
            out[q] = max(0.0, h0 - h1).let { if (it.isNaN()) 0.0 else it }
        }
        return out
    }

    /** Sum of [informationRate] over the oracle: the quantity the threshold search maximises. */
    fun totalInformationRate(): Double = informationRate().sum()

    /**
     * Ranked places in memory whose preceding [window] samples best match [history], the
     * most recent samples last (`history[t][channel]`, raw values).
     *
     * Only channels whose [mask] entry is true are compared, so a cue can be made from the
     * channels a performer is driving while the rest are free. Candidates whose window would
     * cross an episode boundary are skipped. Each [Cue]'s `position` is the sample to continue
     * *from*: the memory's next sample after the match is `position + 1`.
     */
    fun cue(history: List<DoubleArray>, window: Int = min(history.size, (trajectory.sampleRate).toInt()), mask: BooleanArray = BooleanArray(features.width) { true }, count: Int = 5, separation: Int = window / 2): List<Cue> {
        val w = min(window, history.size)
        require(w >= 1) { "cue needs at least one sample of history" }
        val m = Matcher(history, w, mask)
        val scored = ArrayList<Cue>()
        for (end in w - 1 until n) {
            val d = m.at(end)
            if (d.isFinite()) scored.add(Cue(end, d, lrs[end + 1]))
        }
        scored.sortBy { it.distance }
        val picked = ArrayList<Cue>()
        for (c in scored) {
            if (picked.size >= count) break
            if (picked.none { kotlin.math.abs(it.position - c.position) < separation }) picked.add(c)
        }
        return picked
    }

    /**
     * How well [history] matches the [window] samples of memory ending at [position] (the same
     * distance [cue] ranks by), or `+∞` if that window crosses an episode boundary.
     */
    fun matchDistance(history: List<DoubleArray>, position: Int, window: Int = min(history.size, (trajectory.sampleRate).toInt()), mask: BooleanArray = BooleanArray(features.width) { true }): Double =
        if (position < 0 || position >= n) Double.POSITIVE_INFINITY else Matcher(history, min(window, history.size), mask).at(position)

    private inner class Matcher(history: List<DoubleArray>, val w: Int, mask: BooleanArray) {
        private val h = history.subList(history.size - w, history.size)
        private val hf = Array(w) { k -> features.frame(h[k], if (k > 0) h[k - 1] else null) }
        private val dims = BooleanArray(features.dimension) { d -> mask[d % features.width] }
        private val firstVelocity = features.dimension - features.width

        fun at(end: Int): Double {
            val start = end - w + 1
            if (start < 0 || trajectory.episodeOf(start) != trajectory.episodeOf(end)) return Double.POSITIVE_INFINITY
            var s = 0.0
            for (k in 0 until w) {
                val a = hf[k]; val b = frames[start + k]
                // the first history sample has no velocity: compare its values only
                for (d in a.indices) if (dims[d] && (k > 0 || d < firstVelocity)) { val e = a[d] - b[d]; s += e * e }
            }
            return kotlin.math.sqrt(s / w)
        }
    }

    override fun toString() = "VariableMarkovOracle(${n} samples, threshold=${"%.3g".format(threshold)}, alphabet=$alphabetSize, IR=${"%.1f".format(totalInformationRate())} bits)"

    companion object {
        /**
         * Builds the oracle for [t]. With no [threshold] it scans [candidates] thresholds and keeps
         * the one with the highest [totalInformationRate], as the reference `find_threshold` does.
         */
        fun build(t: Trajectory, features: GestureFeatures = GestureFeatures.fit(t), threshold: Double? = null, candidates: Int = 24): VariableMarkovOracle {
            val frames = features.frames(t)
            if (threshold != null) return VariableMarkovOracle(t, features, frames, threshold)
            return thresholdScan(t, features, frames, candidates).maxBy { it.second }.first
        }

        /** Every candidate oracle with its information rate, for plotting the threshold choice. */
        fun thresholdScan(t: Trajectory, features: GestureFeatures = GestureFeatures.fit(t), candidates: Int = 24): List<Pair<VariableMarkovOracle, Double>> =
            thresholdScan(t, features, features.frames(t), candidates)

        private fun thresholdScan(t: Trajectory, features: GestureFeatures, frames: Array<DoubleArray>, candidates: Int): List<Pair<VariableMarkovOracle, Double>> {
            // spread of typical distances, from a deterministic sample of pairs
            val r = java.util.Random(0)
            val ds = DoubleArray(min(4000, frames.size * 4)) { distance(frames[r.nextInt(frames.size)], frames[r.nextInt(frames.size)]) }.sorted()
            val hi = ds[ds.size / 2].coerceAtLeast(1e-6)
            val lo = hi / 200
            return (0 until candidates).map { k ->
                val th = lo * Math.pow(hi / lo, k / (candidates - 1.0))
                val o = VariableMarkovOracle(t, features, frames, th)
                o to o.totalInformationRate()
            }
        }
    }
}

/** A place in memory matching a cue: continue from sample [position]. Lower [distance] is better. */
data class Cue(val position: Int, val distance: Double, val context: Int)

internal class IntArrayList {
    private var a = IntArray(2)
    var size = 0; private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    inline fun forEach(f: (Int) -> Unit) { for (i in 0 until size) f(get(i)) }
    fun toArray(): IntArray = a.copyOf(size)
}
