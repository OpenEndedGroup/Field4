@file:afield.runtime.completion.CompletionCategory("Math")

package afield.math.matrixprofile

import afield.audio.analysis.FFT
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Matrix profiles of a single series: for every subsequence, the distance to its nearest
 * neighbour. The result, [MatrixProfile], yields motifs (repeated shapes) and discords (shapes
 * that occur once).
 *
 * [matrixProfile] computes a self-join or, given two series, an AB-join; [joinBothWays] returns
 * both directions of a join, and [distanceProfile] the distances from one query to every
 * subsequence of a series. Its `motifs` and `discords` return [Motif]s and [Discord]s. The
 * multichannel and contrast variants are in `MultiDimensional.kt`. Tests: `MatrixProfileTest`.
 */

/**
 * The matrix profile of a series (Yeh, Keogh et al., 2016): for every subsequence of length [m],
 * the z-normalized Euclidean distance to its nearest neighbour somewhere else in the series (or,
 * for a join, in another series) and where that neighbour is.
 *
 * Low values are motifs — shapes that happen at least twice. High values are discords — shapes
 * that happen nowhere else. [motifs] and [discords] enumerate them.
 *
 * Distances run from 0 (identical after z-normalization) to `2 √m` (perfectly anti-correlated).
 * A subsequence with no valid neighbour (one containing NaN, or a join with nothing to join) has
 * distance `+∞` and index `-1`. A constant subsequence has zero correlation with everything, so
 * every distance to or from it is `√(2m)`.
 */
class MatrixProfile internal constructor(
    /** The subsequence length. */
    val m: Int,
    /** Nearest-neighbour distance per subsequence start, length `n - m + 1`. */
    val distances: DoubleArray,
    /** Start of the nearest neighbour per subsequence, or `-1`. */
    val indices: IntArray,
    /** Nearest neighbour that starts *earlier* (self-joins only; empty for a join). */
    val leftDistances: DoubleArray,
    /** Index of [leftDistances]' neighbour, or `-1`. */
    val leftIndices: IntArray,
    /** Nearest neighbour that starts *later* (self-joins only; empty for a join). */
    val rightDistances: DoubleArray,
    /** Index of [rightDistances]' neighbour, or `-1`. */
    val rightIndices: IntArray,
    /** Half-width of the trivial-match exclusion zone used. */
    val exclusion: Int,
    /** The series the subsequences come from. */
    val series: DoubleArray,
    /** For a join, the series the neighbours come from; for a self-join, [series]. */
    val other: DoubleArray,
    /** Fraction of the distance matrix's diagonals evaluated (1.0 = exact). */
    val fraction: Double,
) {
    /** Number of subsequences. */
    val size: Int get() = distances.size

    /** True for a self-join. */
    val isSelfJoin: Boolean get() = series === other

    /** [distances] as a list, ready for `showSignals("mp" to mp.profile)`. Infinite values become NaN. */
    val profile: List<Double> get() = distances.map { if (it.isFinite()) it else Double.NaN }

    /**
     * The [count] best motifs, best first. Each starts from the closest remaining pair in the
     * profile; its members are every subsequence within `radius × pair distance` of the first
     * (at most [maxMembers], no two trivially overlapping). Members of a motif, and their exclusion
     * zones, are not used again.
     *
     * Stops early when the next pair is further apart than [maxDistance].
     *
     * For a join, a motif's members are positions in [other]: the shapes there that resemble the
     * subsequence of [series] at [Motif.seed].
     */
    fun motifs(
        count: Int = 3,
        radius: Double = 2.0,
        maxMembers: Int = 10,
        maxDistance: Double = Double.POSITIVE_INFINITY,
    ): List<Motif> {
        val taken = BooleanArray(size)
        val out = ArrayList<Motif>()
        while (out.size < count) {
            var best = -1
            for (i in 0 until size) if (!taken[i] && distances[i].isFinite() && (best < 0 || distances[i] < distances[best])) best = i
            if (best < 0 || distances[best] > maxDistance) break
            val d0 = distances[best]
            val profile = distanceProfile(series.copyOfRange(best, best + m), other)
            if (isSelfJoin) taken.let { for (j in 0 until size) if (it[j]) profile[j] = Double.POSITIVE_INFINITY }
            val members = pickMembers(profile, max(d0 * radius, 1e-9), maxMembers, exclusion, forced = (if (isSelfJoin) listOf(best, indices[best]) else listOf(indices[best])).filter { it >= 0 })
            out += Motif(members.map { it.first }, members.map { it.second }, d0, best)
            if (isSelfJoin) members.forEach { (j, _) -> exclude(taken, j, exclusion) }
            exclude(taken, best, exclusion)
            indices[best].takeIf { it >= 0 && isSelfJoin }?.let { exclude(taken, it, exclusion) }
        }
        return out
    }

    /** The [count] most unusual subsequences, most unusual first, none trivially overlapping. */
    fun discords(count: Int = 3): List<Discord> {
        val taken = BooleanArray(size)
        val out = ArrayList<Discord>()
        while (out.size < count) {
            var best = -1
            for (i in 0 until size) if (!taken[i] && distances[i].isFinite() && (best < 0 || distances[i] > distances[best])) best = i
            if (best < 0) break
            out += Discord(best, distances[best], indices[best])
            exclude(taken, best, exclusion)
        }
        return out
    }

    /** The subsequence starting at [i]. */
    fun subsequence(i: Int): DoubleArray = series.copyOfRange(i, i + m)

    override fun toString(): String =
        "MatrixProfile(m=$m, n=$size, ${if (isSelfJoin) "self-join" else "join"}, fraction=$fraction)"
}

/**
 * A repeated shape: where it occurs (closest first), each occurrence's distance from [seed], the
 * distance of the pair it was found from, and the subsequence it was grown from.
 */
data class Motif(val indices: List<Int>, val distances: List<Double>, val pairDistance: Double, val seed: Int = indices.first())

/** An unusual shape: where it starts, how far it is from its nearest neighbour, and where that is. */
data class Discord(val index: Int, val distance: Double, val neighbour: Int)

internal fun exclude(taken: BooleanArray, at: Int, zone: Int) {
    for (j in max(0, at - zone)..min(taken.size - 1, at + zone)) taken[j] = true
}

internal fun pickMembers(profile: DoubleArray, limit: Double, maxMembers: Int, zone: Int, forced: List<Int>): List<Pair<Int, Double>> {
    val chosen = ArrayList<Pair<Int, Double>>()
    val blocked = BooleanArray(profile.size)
    for (f in forced.distinct()) if (f < profile.size && !blocked[f]) {
        chosen += f to profile[f].let { if (it.isFinite()) it else 0.0 }
        exclude(blocked, f, zone)
    }
    val order = profile.indices.filter { profile[it] <= limit }.sortedBy { profile[it] }
    for (j in order) {
        if (chosen.size >= maxMembers) break
        if (blocked[j]) continue
        chosen += j to profile[j]
        exclude(blocked, j, zone)
    }
    return chosen.sortedBy { it.second }
}

/** Default trivial-match exclusion zone: a quarter of the window, as in STUMPY and SCAMP. */
fun defaultExclusion(m: Int): Int = ceil(m / 4.0).toInt()

/**
 * Per-window statistics for the SCAMP covariance recurrence. NaN samples are replaced by 0 in
 * [x] and their windows are marked invalid by a NaN [invNorm], so a NaN never enters a running
 * covariance and an invalid window never wins a comparison.
 */
internal class Windows(raw: DoubleArray, val m: Int) {
    val n = raw.size - m + 1
    val x = DoubleArray(raw.size) { if (raw[it].isNaN()) 0.0 else raw[it] }
    val mu = DoubleArray(max(n, 0))
    val invNorm = DoubleArray(max(n, 0))
    val df = DoubleArray(max(n, 0))
    val dg = DoubleArray(max(n, 0))

    init {
        require(m >= 3) { "subsequence length m must be at least 3 (got $m)" }
        require(n >= 1) { "series of length ${raw.size} is shorter than m = $m" }
        var bad = 0
        for (k in 0 until m) if (raw[k].isNaN()) bad++
        for (i in 0 until n) {
            if (i > 0) {
                if (raw[i - 1].isNaN()) bad--
                if (raw[i + m - 1].isNaN()) bad++
            }
            // Mean and norm per window are computed exactly (no running sums), which costs n·m
            // once and keeps long series free of drift.
            var s = 0.0
            for (k in 0 until m) s += x[i + k]
            val mean = s / m
            var ss = 0.0
            for (k in 0 until m) { val d = x[i + k] - mean; ss += d * d }
            mu[i] = mean
            invNorm[i] = when {
                bad > 0 -> Double.NaN
                ss <= 1e-12 * m * (1.0 + mean * mean) -> 0.0
                else -> 1.0 / sqrt(ss)
            }
        }
        for (i in 1 until n) {
            df[i] = (x[i + m - 1] - x[i - 1]) / 2.0
            dg[i] = (x[i + m - 1] - mu[i]) + (x[i - 1] - mu[i - 1])
        }
    }

    /** Exact mean-centred covariance of window [i] here with window [j] of [o]. */
    fun cov(i: Int, o: Windows, j: Int): Double {
        var c = 0.0
        for (k in 0 until m) c += (x[i + k] - mu[i]) * (o.x[j + k] - o.mu[j])
        return c
    }
}

internal fun corrToDistance(c: Double, m: Int): Double =
    if (c.isNaN() || c == Double.NEGATIVE_INFINITY) Double.POSITIVE_INFINITY else sqrt(max(0.0, 2.0 * m * (1.0 - min(c, 1.0))))

internal fun threadCount(work: Long): Int =
    if (work < 2_000_000L) 1 else Runtime.getRuntime().availableProcessors().coerceIn(1, 64)

/**
 * The diagonals to walk, as (start in a, start in b) pairs, in the order to walk them. For a
 * partial ([fraction] < 1) profile they are shuffled first — SCRIMP's anytime ordering — so any
 * prefix is an unbiased sample of the whole matrix.
 */
internal fun diagonals(selfJoin: Boolean, na: Int, nb: Int, zone: Int, fraction: Double, seed: Long): IntArray {
    val ks = if (selfJoin) (zone + 1 until na).toList().toIntArray() else (-(nb - 1) until na).toList().toIntArray()
    if (fraction >= 1.0) return ks
    val rnd = java.util.Random(seed)
    for (i in ks.size - 1 downTo 1) { val j = rnd.nextInt(i + 1); val t = ks[i]; ks[i] = ks[j]; ks[j] = t }
    return ks.copyOf(max(1, ceil(ks.size * fraction.coerceIn(0.0, 1.0)).toInt()).coerceAtMost(ks.size))
}

/** Runs [body] over [items] on [threads] workers, each with its own state from [local]; returns the states. */
internal fun <S> parallelOver(items: IntArray, threads: Int, local: () -> S, body: (S, Int) -> Unit): List<S> {
    val states = List(threads) { local() }
    if (threads == 1) { items.forEach { body(states[0], it) }; return states }
    val next = AtomicInteger(0)
    val chunk = max(1, items.size / (threads * 16))
    val workers = states.map { s ->
        Thread {
            while (true) {
                val from = next.getAndAdd(chunk)
                if (from >= items.size) break
                for (q in from until min(items.size, from + chunk)) body(s, items[q])
            }
        }.apply { isDaemon = true; start() }
    }
    workers.forEach { it.join() }
    return states
}

private class Best(n: Int, nb: Int) {
    val corr = DoubleArray(n) { Double.NEGATIVE_INFINITY }
    val idx = IntArray(n) { -1 }
    val corrL = DoubleArray(nb) { Double.NEGATIVE_INFINITY }
    val idxL = IntArray(nb) { -1 }
}

/**
 * The self-join matrix profile of [series] with subsequence length [m].
 *
 * Exact and O(n²): it walks every diagonal of the distance matrix with SCAMP's mean-centred
 * covariance update, a handful of flops per cell, split across cores: n = 10 000 takes about 20 ms on ten cores, n = 100 000 about 1.5 s.
 *
 * [fraction] below 1 evaluates that share of the diagonals in a random order (SCRIMP's anytime
 * mode): every value is an upper bound on the exact one, and the motifs and discords usually
 * settle long before the profile does. [exclusion] is the half-width of the zone around each
 * subsequence whose neighbours are trivial matches.
 */
fun matrixProfile(
    series: DoubleArray,
    m: Int,
    exclusion: Int = defaultExclusion(m),
    fraction: Double = 1.0,
    seed: Long = 0,
): MatrixProfile {
    val w = Windows(series, m)
    val n = w.n
    val ks = diagonals(true, n, n, exclusion, fraction, seed)
    val threads = threadCount(n.toLong() * n / 2)
    val states = parallelOver(ks, threads, { Best(n, n) }) { s, k ->
        // Diagonal k: pairs (i, i + k). corr = right neighbour of i, corrL = left neighbour of j.
        var c = w.cov(0, w, k)
        var i = 0
        var j = k
        while (true) {
            val r = c * w.invNorm[i] * w.invNorm[j]
            if (r > s.corr[i]) { s.corr[i] = r; s.idx[i] = j }
            if (r > s.corrL[j]) { s.corrL[j] = r; s.idxL[j] = i }
            i++; j++
            if (j >= n) break
            c += w.df[i] * w.dg[j] + w.df[j] * w.dg[i]
        }
    }
    val rc = DoubleArray(n) { Double.NEGATIVE_INFINITY }; val ri = IntArray(n) { -1 }
    val lc = DoubleArray(n) { Double.NEGATIVE_INFINITY }; val li = IntArray(n) { -1 }
    for (s in states) for (i in 0 until n) {
        if (s.corr[i] > rc[i]) { rc[i] = s.corr[i]; ri[i] = s.idx[i] }
        if (s.corrL[i] > lc[i]) { lc[i] = s.corrL[i]; li[i] = s.idxL[i] }
    }
    val right = DoubleArray(n) { corrToDistance(rc[it], m) }
    val left = DoubleArray(n) { corrToDistance(lc[it], m) }
    val d = DoubleArray(n) { min(left[it], right[it]) }
    val idx = IntArray(n) { if (left[it] <= right[it]) li[it] else ri[it] }
    return MatrixProfile(m, d, idx, left, li, right, ri, exclusion, series, series, fraction.coerceIn(0.0, 1.0))
}

/**
 * The join matrix profile: for every subsequence of [a], its nearest neighbour in [b]. There is no
 * exclusion zone, since the two series are different. The profile of [b] against [a] comes out of
 * the same pass; ask for it with [joinBothWays].
 */
fun matrixProfile(a: DoubleArray, b: DoubleArray, m: Int, fraction: Double = 1.0, seed: Long = 0): MatrixProfile =
    joinBothWays(a, b, m, fraction, seed).first

/** The join profiles of [a] against [b] and of [b] against [a], from one pass over the distance matrix. */
fun joinBothWays(a: DoubleArray, b: DoubleArray, m: Int, fraction: Double = 1.0, seed: Long = 0): Pair<MatrixProfile, MatrixProfile> {
    val wa = Windows(a, m)
    val wb = Windows(b, m)
    val na = wa.n
    val nb = wb.n
    val ks = diagonals(false, na, nb, 0, fraction, seed)
    val threads = threadCount(na.toLong() * nb)
    val states = parallelOver(ks, threads, { Best(na, nb) }) { s, k ->
        // Diagonal k: pairs (i, j) with i - j = k.
        var i = max(k, 0)
        var j = i - k
        var c = wa.cov(i, wb, j)
        while (true) {
            val r = c * wa.invNorm[i] * wb.invNorm[j]
            if (r > s.corr[i]) { s.corr[i] = r; s.idx[i] = j }
            if (r > s.corrL[j]) { s.corrL[j] = r; s.idxL[j] = i }
            i++; j++
            if (i >= na || j >= nb) break
            c += wa.df[i] * wb.dg[j] + wb.df[j] * wa.dg[i]
        }
    }
    val ac = DoubleArray(na) { Double.NEGATIVE_INFINITY }; val ai = IntArray(na) { -1 }
    val bc = DoubleArray(nb) { Double.NEGATIVE_INFINITY }; val bi = IntArray(nb) { -1 }
    for (s in states) {
        for (i in 0 until na) if (s.corr[i] > ac[i]) { ac[i] = s.corr[i]; ai[i] = s.idx[i] }
        for (j in 0 until nb) if (s.corrL[j] > bc[j]) { bc[j] = s.corrL[j]; bi[j] = s.idxL[j] }
    }
    val f = fraction.coerceIn(0.0, 1.0)
    val empty = DoubleArray(0); val none = IntArray(0)
    val ab = MatrixProfile(m, DoubleArray(na) { corrToDistance(ac[it], m) }, ai, empty, none, empty, none, defaultExclusion(m), a, b, f)
    val ba = MatrixProfile(m, DoubleArray(nb) { corrToDistance(bc[it], m) }, bi, empty, none, empty, none, defaultExclusion(m), b, a, f)
    return ab to ba
}

/**
 * The z-normalized Euclidean distance from [query] to every subsequence of [series] of the same
 * length, by Mueen's MASS: one FFT convolution for all the dot products, so O(n log n).
 */
fun distanceProfile(query: DoubleArray, series: DoubleArray): DoubleArray {
    val m = query.size
    val w = Windows(series, m)
    val n = w.n
    var qm = 0.0
    for (v in query) qm += v
    qm /= m
    var qss = 0.0
    for (v in query) qss += (v - qm) * (v - qm)
    val qInv = if (query.any { it.isNaN() }) Double.NaN else if (qss <= 1e-12 * m * (1.0 + qm * qm)) 0.0 else 1.0 / sqrt(qss)

    val size = FFT.nextPowerOfTwo(series.size + m)
    val xr = DoubleArray(size); val xi = DoubleArray(size)
    val yr = DoubleArray(size); val yi = DoubleArray(size)
    for (k in series.indices) xr[k] = w.x[k]
    for (k in 0 until m) yr[k] = if (query[m - 1 - k].isNaN()) 0.0 else query[m - 1 - k] - qm
    FFT.transform(xr, xi)
    FFT.transform(yr, yi)
    for (k in 0 until size) {
        val r = xr[k] * yr[k] - xi[k] * yi[k]
        val im = xr[k] * yi[k] + xi[k] * yr[k]
        xr[k] = r; xi[k] = im
    }
    FFT.inverseTransform(xr, xi)
    // The query is mean-centred, so Σ q̃·x over a window equals Σ q̃·(x − μ): its covariance.
    return DoubleArray(n) { corrToDistance(xr[it + m - 1] * qInv * w.invNorm[it], m) }
}

fun matrixProfile(series: List<Number>, m: Int, exclusion: Int = defaultExclusion(m), fraction: Double = 1.0, seed: Long = 0): MatrixProfile =
    matrixProfile(DoubleArray(series.size) { series[it].toDouble() }, m, exclusion, fraction, seed)

fun matrixProfile(series: FloatArray, m: Int, exclusion: Int = defaultExclusion(m), fraction: Double = 1.0, seed: Long = 0): MatrixProfile =
    matrixProfile(DoubleArray(series.size) { series[it].toDouble() }, m, exclusion, fraction, seed)

fun matrixProfile(a: List<Number>, b: List<Number>, m: Int, fraction: Double = 1.0, seed: Long = 0): MatrixProfile =
    matrixProfile(DoubleArray(a.size) { a[it].toDouble() }, DoubleArray(b.size) { b[it].toDouble() }, m, fraction, seed)
