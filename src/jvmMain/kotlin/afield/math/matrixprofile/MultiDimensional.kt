@file:afield.runtime.completion.CompletionCategory("Math")

package afield.math.matrixprofile

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The multidimensional matrix profile of a `d`-channel series (mSTAMP, Yeh, Kavantzas & Keogh,
 * 2017). A motif in many channels is rarely in *all* of them: a gesture shows up in the wrist's
 * x and z but not y, a fault in two of eight sensors. So instead of one profile there are `d`:
 * profile `k` (1-based, [profile]) is, for every subsequence, the smallest mean distance to a
 * neighbour over the best `k` channels, choosing the channels per pair.
 *
 * Comparing profiles across `k` is how you choose a motif's dimensionality: the `k`-channel
 * value stays low while the channels that share the motif are added, then jumps. [subspace]
 * says which channels those were.
 */
class MultiMatrixProfile internal constructor(
    /** The subsequence length. */
    val m: Int,
    /** The channels, each the same length. */
    val channels: List<DoubleArray>,
    private val dist: Array<DoubleArray>,
    private val idx: Array<IntArray>,
    /** Half-width of the trivial-match exclusion zone used. */
    val exclusion: Int,
) {
    /** Number of channels. */
    val dimensions: Int get() = channels.size

    /** Number of subsequences. */
    val size: Int get() = dist[0].size

    /** Distances of the [k]-channel profile (`1..dimensions`). */
    fun distances(k: Int): DoubleArray = dist[checkK(k) - 1]

    /** Neighbour indices of the [k]-channel profile. */
    fun indices(k: Int): IntArray = idx[checkK(k) - 1]

    /** The [k]-channel profile as a plot-ready list (infinities become NaN). */
    fun profile(k: Int): List<Double> = distances(k).map { if (it.isFinite()) it else Double.NaN }

    /**
     * The [k]-channel profile as a one-dimensional [MatrixProfile] over channel [channel], so
     * [MatrixProfile.motifs]-style questions can be asked of it — the distances are the
     * [k]-channel ones.
     */
    fun asMatrixProfile(k: Int, channel: Int = 0): MatrixProfile {
        val e = DoubleArray(0); val ne = IntArray(0)
        return MatrixProfile(m, distances(k), indices(k), e, ne, e, ne, exclusion, channels[channel], channels[channel], 1.0)
    }

    /** The [k] channels, most similar first, in which subsequence [i] matches its [k]-channel neighbour. */
    fun subspace(k: Int, i: Int): IntArray {
        val j = indices(k)[i]
        if (j < 0) return IntArray(0)
        val per = DoubleArray(dimensions) { c -> zDistance(channels[c], i, j, m) }
        return per.indices.sortedBy { per[it] }.take(k).toIntArray()
    }

    /**
     * The [count] best [k]-channel motifs. Each grows from the closest remaining pair; its
     * members are subsequences within `radius ×` that pair's distance, measured as the mean
     * distance over the pair's own [subspace].
     */
    fun motifs(k: Int, count: Int = 3, radius: Double = 2.0, maxMembers: Int = 10): List<MultiMotif> {
        val d = distances(k)
        val ix = indices(k)
        val taken = BooleanArray(size)
        val out = ArrayList<MultiMotif>()
        while (out.size < count) {
            var best = -1
            for (i in 0 until size) if (!taken[i] && d[i].isFinite() && (best < 0 || d[i] < d[best])) best = i
            if (best < 0) break
            val sub = subspace(k, best)
            val mean = DoubleArray(size)
            for (c in sub) {
                val p = distanceProfile(channels[c].copyOfRange(best, best + m), channels[c])
                for (j in 0 until size) mean[j] += p[j] / sub.size
            }
            for (j in 0 until size) if (taken[j]) mean[j] = Double.POSITIVE_INFINITY
            val members = pickMembers(mean, max(d[best] * radius, 1e-9), maxMembers, exclusion, listOf(best, ix[best]).filter { it >= 0 })
            out += MultiMotif(members.map { it.first }, members.map { it.second }, d[best], sub.toList())
            members.forEach { (j, _) -> exclude(taken, j, exclusion) }
            exclude(taken, best, exclusion)
        }
        return out
    }

    /** The [count] most unusual [k]-channel subsequences. */
    fun discords(k: Int, count: Int = 3): List<Discord> = asMatrixProfile(k).discords(count)

    private fun checkK(k: Int): Int {
        require(k in 1..dimensions) { "k must be in 1..$dimensions (got $k)" }
        return k
    }

    override fun toString(): String = "MultiMatrixProfile(m=$m, n=$size, d=$dimensions)"
}

/** A motif in a subset of channels: occurrences, their distances, the seed pair's distance and the channels it lives in. */
data class MultiMotif(val indices: List<Int>, val distances: List<Double>, val pairDistance: Double, val channels: List<Int>)

internal fun zDistance(x: DoubleArray, i: Int, j: Int, m: Int): Double {
    var ma = 0.0; var mb = 0.0
    for (k in 0 until m) { ma += x[i + k]; mb += x[j + k] }
    ma /= m; mb /= m
    var sab = 0.0; var saa = 0.0; var sbb = 0.0
    for (k in 0 until m) {
        val a = x[i + k] - ma; val b = x[j + k] - mb
        sab += a * b; saa += a * a; sbb += b * b
    }
    if (saa.isNaN() || sbb.isNaN()) return Double.POSITIVE_INFINITY
    val r = if (saa <= 1e-12 || sbb <= 1e-12) 0.0 else sab / sqrt(saa * sbb)
    return corrToDistance(r, m)
}

private class MultiBest(d: Int, n: Int) {
    val dist = Array(d) { DoubleArray(n) { Double.POSITIVE_INFINITY } }
    val idx = Array(d) { IntArray(n) { -1 } }
    val cov = DoubleArray(d)
    val per = DoubleArray(d)
}

/**
 * The multidimensional self-join matrix profile of [channels] (all the same length) with
 * subsequence length [m]. Exact, O(d · n² + d log d · n²) — the per-channel covariance
 * recurrence of [matrixProfile] run in lock-step over every channel, with the channels sorted
 * per cell.
 */
fun multiMatrixProfile(channels: List<DoubleArray>, m: Int, exclusion: Int = defaultExclusion(m)): MultiMatrixProfile {
    require(channels.isNotEmpty()) { "need at least one channel" }
    require(channels.all { it.size == channels[0].size }) { "all channels must be the same length" }
    val ws = channels.map { Windows(it, m) }
    val d = ws.size
    val n = ws[0].n
    val ks = diagonals(true, n, n, exclusion, 1.0, 0)
    val threads = threadCount(n.toLong() * n * d / 2)
    val states = parallelOver(ks, threads, { MultiBest(d, n) }) { s, k ->
        for (c in 0 until d) s.cov[c] = ws[c].cov(0, ws[c], k)
        var i = 0
        var j = k
        while (true) {
            for (c in 0 until d) {
                val w = ws[c]
                s.per[c] = corrToDistance(s.cov[c] * w.invNorm[i] * w.invNorm[j], m)
            }
            // Insertion sort: d is small, and this is the whole of mSTAMP's per-cell cost.
            for (a in 1 until d) {
                val v = s.per[a]; var b = a - 1
                while (b >= 0 && s.per[b] > v) { s.per[b + 1] = s.per[b]; b-- }
                s.per[b + 1] = v
            }
            var sum = 0.0
            for (q in 0 until d) {
                sum += s.per[q]
                val mean = sum / (q + 1)
                if (mean < s.dist[q][i]) { s.dist[q][i] = mean; s.idx[q][i] = j }
                if (mean < s.dist[q][j]) { s.dist[q][j] = mean; s.idx[q][j] = i }
            }
            i++; j++
            if (j >= n) break
            for (c in 0 until d) {
                val w = ws[c]
                s.cov[c] += w.df[i] * w.dg[j] + w.df[j] * w.dg[i]
            }
        }
    }
    val dist = Array(d) { DoubleArray(n) { Double.POSITIVE_INFINITY } }
    val idx = Array(d) { IntArray(n) { -1 } }
    for (s in states) for (q in 0 until d) for (i in 0 until n) if (s.dist[q][i] < dist[q][i]) {
        dist[q][i] = s.dist[q][i]; idx[q][i] = s.idx[q][i]
    }
    return MultiMatrixProfile(m, channels, dist, idx, exclusion)
}

/** [multiMatrixProfile] for channels given as lists. */
@JvmName("multiMatrixProfileLists")
fun multiMatrixProfile(channels: List<List<Number>>, m: Int, exclusion: Int = defaultExclusion(m)): MultiMatrixProfile =
    multiMatrixProfile(channels.map { c -> DoubleArray(c.size) { c[it].toDouble() } }, m, exclusion)

/**
 * The contrast profile (Mercer, Alaee, Abdoli, Singh, Murillo, Keogh, 2021): which shapes occur in
 * the [positive] examples and *not* in the [negative] ones.
 *
 * `contrast[i] = (join[i] − self[i]) / √(2m)` where `join` is the distance from positive
 * subsequence `i` to its nearest neighbour in the negatives and `self` the distance to its nearest
 * neighbour elsewhere in the positives, clamped to `[-1, 1]`. High means "repeated here, absent there". The best
 * candidate is called a *plato* ([platos]).
 */
class ContrastProfile internal constructor(
    /** The subsequence length. */
    val m: Int,
    /** Contrast per subsequence of the concatenated positives; NaN where undefined. */
    val contrast: DoubleArray,
    /** Self-join of the concatenated positives. */
    val self: MatrixProfile,
    /** Join of the concatenated positives against the concatenated negatives. */
    val join: MatrixProfile,
    /** Start of each positive example within the concatenation (examples are separated by one NaN). */
    val segmentStarts: IntArray,
) {
    /** [contrast] as a plot-ready list. */
    val profile: List<Double> get() = contrast.toList()

    /** The concatenated positives that [contrast] indexes. */
    val positive: DoubleArray get() = self.series

    /** The [count] highest-contrast subsequences, best first, none trivially overlapping. */
    fun platos(count: Int = 1): List<Plato> {
        val taken = BooleanArray(contrast.size)
        val out = ArrayList<Plato>()
        while (out.size < count) {
            var best = -1
            for (i in contrast.indices) if (!taken[i] && !contrast[i].isNaN() && (best < 0 || contrast[i] > contrast[best])) best = i
            if (best < 0) break
            val seg = segmentStarts.indexOfLast { it <= best }
            out += Plato(best, contrast[best], seg, best - segmentStarts[seg], self.indices[best], join.indices[best])
            exclude(taken, best, self.exclusion)
        }
        return out
    }

    /** The positive subsequence starting at [i]. */
    fun subsequence(i: Int): DoubleArray = positive.copyOfRange(i, i + m)
}

/**
 * A high-contrast subsequence: where it starts in the concatenated positives, its contrast, which
 * positive example that is and where within it, and its nearest neighbours in the positives and
 * the negatives.
 */
data class Plato(val index: Int, val contrast: Double, val example: Int, val offset: Int, val positiveNeighbour: Int, val negativeNeighbour: Int)

internal fun concatWithGaps(parts: List<DoubleArray>): Pair<DoubleArray, IntArray> {
    val starts = IntArray(parts.size)
    val out = ArrayList<Double>()
    parts.forEachIndexed { p, a ->
        if (p > 0) out += Double.NaN
        starts[p] = out.size
        a.forEach { out += it }
    }
    return out.toDoubleArray() to starts
}

/** The [ContrastProfile] of the [positive] examples against the [negative] ones. */
fun contrastProfile(positive: List<DoubleArray>, negative: List<DoubleArray>, m: Int, exclusion: Int = defaultExclusion(m)): ContrastProfile {
    require(positive.isNotEmpty() && negative.isNotEmpty()) { "need at least one positive and one negative example" }
    val (pos, starts) = concatWithGaps(positive)
    val (neg, _) = concatWithGaps(negative)
    val self = matrixProfile(pos, m, exclusion)
    val join = matrixProfile(pos, neg, m)
    val norm = sqrt(2.0 * m)
    val c = DoubleArray(self.size) {
        val a = self.distances[it]; val b = join.distances[it]
        if (a.isFinite() && b.isFinite()) min(1.0, max(-1.0, (b - a) / norm)) else Double.NaN
    }
    return ContrastProfile(m, c, self, join, starts)
}

/** [contrastProfile] for one positive and one negative series. */
fun contrastProfile(positive: DoubleArray, negative: DoubleArray, m: Int, exclusion: Int = defaultExclusion(m)): ContrastProfile =
    contrastProfile(listOf(positive), listOf(negative), m, exclusion)
