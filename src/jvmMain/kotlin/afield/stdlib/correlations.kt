@file:afield.CompletionCategory("Math")

package afield.stdlib

import kotlin.math.*

/**
 * Measures of statistical dependence between two equal-length `DoubleArray`s, batch and rolling.
 *
 * [StatsDependence] carries the batch versions: `pearson`, `spearman` (Pearson on average-tie
 * ranks), `kendallTauB`, `chatterjeeXi` (aliased as `xi`), `distanceCorrelation` and
 * `micTicNaive`. Kendall's tau-b and distance correlation are both O(n²) in time, and distance
 * correlation additionally materialises two n×n matrices, so neither suits long series.
 *
 * [RollingDistanceCorrelation] holds a fixed window and updates its maintained row and grand sums
 * in O(n) per `push`; [RollingXiCorrelation] only keeps the window and recomputes xi from
 * scratch. All of it is pure Kotlin with no external dependency. Note the block comment below,
 * which is explicit that `micTicNaive` is an equipartition-grid approximation and not MICe/TICe —
 * do not expect matching values.
 */

// StatsDependence.kt
// Single-file Kotlin implementation of:
// - Pearson correlation
// - Spearman rank correlation (with average-rank ties)
// - Kendall's tau-b (tie-adjusted, O(n^2))
// - Chatterjee's xi rank dependence coefficient
// - Distance correlation (Székely–Rizzo; O(n^2))
// - A *naive* MIC/TIC-style grid mutual-information scan using equipartition bins
//
// IMPORTANT NOTES (honesty about what this is / isn't):
// - The "MIC" here is NOT the published MICe (e.g., Reshef et al. AoAS 2018) and not the original
//   dynamic-programming MINE approximation either. It’s a simple, self-contained approximation:
//     * uses equipartition bins for x and y
//     * scans all (xBins, yBins) with xBins*yBins <= B(n)
//     * computes normalized mutual information on that grid and takes the maximum (MIC-like)
// - The "TIC" here is likewise a simple sum over those normalized scores (TIC-like).
// - These are useful for intuition / prototyping, but do not expect MICe/TICe-identical values.
//
// If you want something closer to MICe/TICe, you’ll likely want to port a reference implementation
// (e.g., minepy or minerva / Reshef’s AoAS code) which is substantially more complex.



/**
 * The batch dependence coefficients: hand any of these two equal-length `DoubleArray`s and get one
 * number back per notion of "related" — [pearson] for linear association, [spearman] and
 * [kendallTauB] for monotone association on ranks, [chatterjeeXi] for "is y a function of x",
 * [distanceCorrelation] for dependence of any shape, [micTicNaive] for a grid mutual-information scan.
 *
 * Every entry point runs the same validation first: the two arrays must be the same length and at
 * least two long, or it throws `IllegalArgumentException`. Nothing here filters non-finite input, so a
 * single NaN in either array propagates straight into the result. Variances and covariances are the
 * population (divide by n) forms, which cancels out of a correlation anyway.
 *
 * The rank-based members sort by value, and each has its own answer for ties. [ranksAverageTies] hands
 * tied values their average rank; the private helper inside [micTicNaive] uses plain order ranks, which
 * is all its equipartition binning needs; [chatterjeeXi] resolves ties among the `x` values by taking the
 * expectation over all their orderings, so — unlike the other two — its answer does not depend on the
 * order the pairs were presented in.
 */
object StatsDependence {

    // -------------------------
    // Validation / helpers
    // -------------------------
    private fun requireSameSize(x: DoubleArray, y: DoubleArray) {
        require(x.size == y.size) { "x and y must have same length (x=${x.size}, y=${y.size})" }
        require(x.size >= 2) { "Need at least 2 points" }
    }

    private fun mean(a: DoubleArray): Double {
        var s = 0.0
        for (v in a) s += v
        return s / a.size
    }

    private fun variance(a: DoubleArray, mu: Double): Double {
        var s = 0.0
        for (v in a) {
            val d = v - mu
            s += d * d
        }
        // population variance (divide by n); for correlation it cancels anyway
        return s / a.size
    }

    private fun covariance(x: DoubleArray, y: DoubleArray, mx: Double, my: Double): Double {
        var s = 0.0
        for (i in x.indices) s += (x[i] - mx) * (y[i] - my)
        // population covariance (divide by n)
        return s / x.size
    }

    // Average-rank ties:
    // returns ranks in [1..n] with average ranks for tied values.
    /**
     * Ranks in 1..n, smallest value first, with tied values sharing the average of the positions they
     * occupy: `3, 1, 4, 1, 5` comes back as `3.0, 1.5, 4.0, 1.5, 5.0`, since the two 1s sit at positions
     * 1 and 2. The result therefore always sums to n(n+1)/2 whatever the input looks like.
     *
     * Ties are broken by original index, so the ordering is stable and the ranks do not depend on the
     * sort's internals. Equality is exact `Double` comparison — values a hair apart count as distinct
     * and get consecutive ranks rather than a shared average. O(n log n); [spearman] is built on it.
     */
    fun ranksAverageTies(values: DoubleArray): DoubleArray {
        val n = values.size
        // sortedWith returns a new list; index through that, never through the unsorted 0..n-1.
        val order = (0 until n).sortedWith { i, j ->
            val a = values[i]
            val b = values[j]
            when {
                a < b -> -1
                a > b -> 1
                else -> i - j // stable tie-break
            }
        }

        val ranks = DoubleArray(n)
        var pos = 0
        while (pos < n) {
            var end = pos
            val v = values[order[pos]]
            while (end + 1 < n && values[order[end + 1]] == v) end++

            // positions are 0-based; rank is 1-based
            val avgRank = ((pos + 1) + (end + 1)) / 2.0
            for (k in pos..end) {
                ranks[order[k]] = avgRank
            }
            pos = end + 1
        }
        return ranks
    }

    // -------------------------
    // Pearson
    // -------------------------
    /**
     * Pearson's product-moment correlation, in -1..1: 1 is a perfect increasing straight line, -1 a
     * perfect decreasing one, and 0 means no *linear* relationship — which is emphatically not the same
     * as no relationship, as the symmetric parabola in [main] demonstrates.
     *
     * Returns NaN, not 0, when either array has zero variance (every value identical), because the
     * coefficient is genuinely undefined there; note that [distanceCorrelation] answers 0.0 in the same
     * situation. Throws if the arrays differ in length or hold fewer than two points. O(n), one pass
     * each for the means and one for the covariance.
     */
    fun pearson(x: DoubleArray, y: DoubleArray): Double {
        requireSameSize(x, y)
        val mx = mean(x)
        val my = mean(y)
        val vx = variance(x, mx)
        val vy = variance(y, my)
        if (vx == 0.0 || vy == 0.0) return Double.NaN
        val cov = covariance(x, y, mx, my)
        return cov / sqrt(vx * vy)
    }

    // -------------------------
    // Spearman (Pearson on ranks)
    // -------------------------
    /**
     * Spearman's rank correlation — [pearson] applied to each array's [ranksAverageTies], which puts a
     * monotone-but-curved relationship at ±1 where Pearson reads less: exactly +1 for any strictly
     * increasing pair, exactly -1 for any strictly decreasing one, whatever curve connects them.
     *
     * Monotone is the whole of what it sees, so a symmetric parabola reads 0 here just as it does under
     * [pearson] — reach for [chatterjeeXi] or [distanceCorrelation] for non-monotone shapes. Ties pull
     * the magnitude in from ±1 by way of the shared average ranks, and NaN comes back when either array
     * is constant, since every rank is then identical and [pearson] has no variance to divide by.
     */
    fun spearman(x: DoubleArray, y: DoubleArray): Double {
        requireSameSize(x, y)
        val rx = ranksAverageTies(x)
        val ry = ranksAverageTies(y)
        return pearson(rx, ry)
    }

    // -------------------------
    // Kendall tau-b (O(n^2))
    // tau-b = (C - D) / sqrt((C + D + Tx) * (C + D + Ty))
    // where Tx is number of pairs tied only in x; Ty tied only in y.
    //
    // This handles ties (tau-b). If all x or all y are identical -> NaN.
    // -------------------------
    /**
     * Kendall's tau-b, in -1..1: concordant minus discordant pairs over all pairs, corrected for ties in
     * either variable. Like [spearman] it measures monotone rather than linear association, and the two
     * agree in sign throughout; tau-b is the more conservative of the pair in magnitude.
     *
     * Returns NaN when the tie-adjusted denominator collapses — all x identical, or all y identical.
     * Pairs tied in *both* variables are counted apart and excluded from the correction, which is what
     * makes this tau-b rather than tau-a or tau-c. O(n²) time with no allocation, so it is fine for a
     * few thousand points and hopeless for a long signal.
     */
    fun kendallTauB(x: DoubleArray, y: DoubleArray): Double {
        requireSameSize(x, y)
        val n = x.size

        var concordant = 0L
        var discordant = 0L
        var tiesXOnly = 0L
        var tiesYOnly = 0L
        var tiesBoth = 0L

        for (i in 0 until n - 1) {
            for (j in i + 1 until n) {
                val dx = x[i].compareTo(x[j]) // -1,0,1
                val dy = y[i].compareTo(y[j])
                when {
                    dx == 0 && dy == 0 -> tiesBoth++
                    dx == 0 && dy != 0 -> tiesXOnly++
                    dx != 0 && dy == 0 -> tiesYOnly++
                    else -> {
                        // both non-zero
                        if (dx == dy) concordant++ else discordant++
                    }
                }
            }
        }

        val num = (concordant - discordant).toDouble()
        val denomX = (concordant + discordant + tiesXOnly).toDouble()
        val denomY = (concordant + discordant + tiesYOnly).toDouble()
        val denom = sqrt(denomX * denomY)
        if (denom == 0.0) return Double.NaN
        return num / denom
    }

    // -------------------------
    // Chatterjee's xi
    //
    // Tie-aware finite-sample form:
    // xi_n = 1 - n * sum_{i=1}^{n-1} |R_{i+1} - R_i| / (2 * sum_i L_i (n - L_i))
    // where:
    // - observations are ordered by x
    // - R_i = #{j : y_j <= y_i}
    // - L_i = #{j : y_j >= y_i}
    //
    // For continuous y (no ties), this reduces to:
    // 1 - 3 * sum |rank(y_{i+1}) - rank(y_i)| / (n^2 - 1)
    //
    // Chatterjee (2021) defines the estimator for tied x by breaking those ties *uniformly at random*,
    // which makes xi_n a random variable. Rather than pick a seed we evaluate its expectation in closed
    // form. The denominator does not depend on the tie-breaking at all, so
    //   E[xi_n] = 1 - n * E[sum |R_{i+1} - R_i|] / (2 * sum_i L_i (n - L_i))
    // exactly, and only the numerator needs an expectation. Group the x-order into runs of equal x:
    // inside a run of size m a uniformly random permutation makes each of the m-1 adjacent pairs a
    // uniformly chosen unordered pair of distinct members, and across the boundary between runs g and h
    // the adjacent pair is one uniform member of each. So
    //   E[intra(g)]   = (2/m) * sum_{a<b in g} |R_a - R_b|
    //   E[cross(g,h)] = (1/(|g|*|h|)) * sum_{a in g, b in h} |R_a - R_b|
    // Both are computable in O(m log m) from the run's sorted R values, so the whole function stays
    // O(n log n). For distinct x every run has m = 1: intra vanishes and cross degenerates to the plain
    // |R_a - R_b|, the same arithmetic in the same order as the untied form, so untied inputs are
    // bit-identical to the simple sum.
    //
    // Breaking x-ties by input index instead — which this did until 2026-07-30 — silently scores how
    // nearly y is a function of *arrival order*: a constant predictor read 0.9702970297029703 at n=100
    // and a two-valued one read 0.9702330025889176 at n=300, for predictors carrying no information.
    // -------------------------
    /**
     * Chatterjee's xi, which is **asymmetric, and that is the point**: it measures how nearly `y` is a *function* of
     * `x`, so swapping the arguments asks a different question and returns a different number. Near 0 for
     * independence, near 1 when y is a noiseless function of x — including a non-monotone one such as a
     * parabola, where [pearson] reads 0 and [kendallTauB] not much more.
     *
     * Values are asymptotically in 0..1, but the finite-sample form used here can come out slightly
     * negative for small n, so do not clamp it and call it a probability. When every y is identical the
     * denominator vanishes and it returns 0.0. O(n log n) — the cheap one to reach for on a long series.
     *
     * **Ties.** Chatterjee's estimator is only defined up to a *random* breaking of the ties among the
     * `x` values, so on tied `x` this returns the exact expectation of xi over all such breakings —
     * deterministic, reproducible, and invariant to the order you present the pairs in. Two consequences
     * worth knowing: a constant predictor reads 0 — the closed form is *exactly* zero for every n, and in
     * `Double` it lands within one ulp of that (over n = 2..400, 54 values of n come back non-zero, the
     * largest being 2.2e-16) — and quantised or categorical `x` is answered correctly rather than being
     * scored on arrival order. Inputs with all-distinct `x` are unaffected, bit for bit.
     *
     * Until 2026-07-30 x-ties broke by input index instead, so a constant predictor read **0.970** and a
     * two-valued predictor of nothing but the arrival index read **0.970** — high scores for carrying no
     * information at all. If you have recorded xi values on tied `x` from before that, they are wrong.
     */
    fun chatterjeeXi(x: DoubleArray, y: DoubleArray): Double {
        requireSameSize(x, y)
        val n = x.size

        val orderByX = (0 until n).sortedWith(
            compareBy<Int>({ x[it] }, { it })
        )
        val orderByY = (0 until n).sortedWith(
            compareBy<Int>({ y[it] }, { it })
        )

        // R_i = #<=, L_i = #>= (tie-aware)
        val r = IntArray(n)
        val l = IntArray(n)
        var pos = 0
        while (pos < n) {
            var end = pos
            val v = y[orderByY[pos]]
            while (end + 1 < n && y[orderByY[end + 1]] == v) end++

            val lessOrEqual = end + 1
            val greaterOrEqual = n - pos
            for (k in pos..end) {
                val idx = orderByY[k]
                r[idx] = lessOrEqual
                l[idx] = greaterOrEqual
            }
            pos = end + 1
        }

        var denomTerm = 0L
        for (i in 0 until n) {
            val li = l[i].toLong()
            denomTerm += li * (n - li)
        }

        val denominator = 2.0 * denomTerm.toDouble()
        if (denominator == 0.0) return 0.0 // e.g. all y identical

        // E[sum |R_{i+1} - R_i|] over uniformly random breakings of the x-ties. See the note above for
        // why the expectation of the ratio is the ratio with the expectation, and why m = 1 everywhere
        // (all-distinct x) reproduces the plain sum exactly.
        var expectedSumAbsDiff = 0.0
        var previousRun: DoubleArray? = null
        var runStart = 0
        while (runStart < n) {
            var runEnd = runStart
            val xv = x[orderByX[runStart]]
            while (runEnd + 1 < n && x[orderByX[runEnd + 1]] == xv) runEnd++

            val m = runEnd - runStart + 1
            val run = DoubleArray(m) { r[orderByX[runStart + it]].toDouble() }
            run.sort()

            if (m >= 2) expectedSumAbsDiff += 2.0 / m * pairwiseAbsSum(run)
            previousRun?.let { p ->
                expectedSumAbsDiff += crossAbsSum(p, run) / (p.size.toDouble() * m.toDouble())
            }

            previousRun = run
            runStart = runEnd + 1
        }

        val numerator = n.toDouble() * expectedSumAbsDiff
        return 1.0 - (numerator / denominator)
    }

    /**
     * `sum_{a<b} |v_a - v_b|` over an **ascending-sorted** array, in one pass: each element is the larger
     * of the `k` pairs below it and the smaller of the `m-1-k` above, so it contributes with weight
     * `2k - m + 1`. O(m) given the sort.
     */
    private fun pairwiseAbsSum(sortedValues: DoubleArray): Double {
        val m = sortedValues.size
        var total = 0.0
        for (k in 0 until m) total += (2 * k - m + 1) * sortedValues[k]
        return total
    }

    /**
     * `sum_{a in A, b in B} |A_a - B_b|` over two **ascending-sorted** arrays, by walking a pointer into
     * `b` and keeping its running prefix sum, so each `a` splits `b` into the part below it and the part
     * above. O(|A| + |B|) rather than the naive product.
     */
    private fun crossAbsSum(a: DoubleArray, b: DoubleArray): Double {
        var sumB = 0.0
        for (v in b) sumB += v

        var total = 0.0
        var j = 0
        var prefix = 0.0 // sum of b[0 until j], i.e. the entries known to be <= the current a
        for (va in a) {
            while (j < b.size && b[j] <= va) {
                prefix += b[j]
                j++
            }
            // below: j entries, each contributing va - b; above: b.size - j entries, contributing b - va
            total += va * j - prefix + (sumB - prefix) - va * (b.size - j)
        }
        return total
    }

    /**
     * Short alias for [chatterjeeXi], for when the formula rather than the surname is what you have in
     * mind. Same asymmetry: the first argument is the predictor.
     */
    fun xi(x: DoubleArray, y: DoubleArray): Double = chatterjeeXi(x, y)

    // -------------------------
    // Distance correlation (Székely–Rizzo)
    //
    // Steps:
    // 1) Compute pairwise distances a_ij = |x_i - x_j|, b_ij = |y_i - y_j|
    // 2) Double-center each distance matrix:
    //    A_ij = a_ij - a_i. - a_.j + a_..
    // 3) dCov^2 = mean_ij (A_ij * B_ij)
    //    dVarX^2 = mean_ij (A_ij * A_ij), dVarY^2 similar
    // 4) dCor = dCov / sqrt(dVarX * dVarY)
    //
    // Complexity O(n^2) memory O(n^2). For large n, you'd want streaming or blocks.
    // -------------------------
    /**
     * Székely–Rizzo distance correlation, in 0..1 — and unlike [pearson] it is 0 *only* for genuine
     * independence, which is what makes it the one to reach for when you do not want to guess the shape
     * of the relationship first. It is symmetric in its arguments and never negative, so it tells you how
     * much dependence there is and nothing at all about direction.
     *
     * Returns 0.0 rather than NaN when either array is constant, and the result is clamped into 0..1 to
     * absorb floating-point overshoot. Scalar inputs only here, though the coefficient is defined for
     * vectors. O(n²) in time and, more painfully, in memory: it materialises two full n×n distance
     * matrices, which puts the practical ceiling at a few thousand points.
     * [RollingDistanceCorrelation] is the streaming alternative.
     */
    fun distanceCorrelation(x: DoubleArray, y: DoubleArray): Double {
        requireSameSize(x, y)
        val n = x.size

        // Build distance matrices
        val a = Array(n) { DoubleArray(n) }
        val b = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            a[i][i] = 0.0
            b[i][i] = 0.0
            for (j in i + 1 until n) {
                val da = abs(x[i] - x[j])
                val db = abs(y[i] - y[j])
                a[i][j] = da
                a[j][i] = da
                b[i][j] = db
                b[j][i] = db
            }
        }

        fun doubleCenter(m: Array<DoubleArray>): Array<DoubleArray> {
            val rowMean = DoubleArray(n)
            val colMean = DoubleArray(n)
            var grand = 0.0

            // row means + grand
            for (i in 0 until n) {
                var s = 0.0
                for (j in 0 until n) s += m[i][j]
                rowMean[i] = s / n
                grand += rowMean[i]
            }
            grand /= n

            // col means
            for (j in 0 until n) {
                var s = 0.0
                for (i in 0 until n) s += m[i][j]
                colMean[j] = s / n
            }

            val centered = Array(n) { DoubleArray(n) }
            for (i in 0 until n) {
                for (j in 0 until n) {
                    centered[i][j] = m[i][j] - rowMean[i] - colMean[j] + grand
                }
            }
            return centered
        }

        val A = doubleCenter(a)
        val B = doubleCenter(b)

        var dcov2 = 0.0
        var dvarx2 = 0.0
        var dvary2 = 0.0
        val denom = (n.toDouble() * n.toDouble())

        for (i in 0 until n) {
            for (j in 0 until n) {
                val aij = A[i][j]
                val bij = B[i][j]
                dcov2 += aij * bij
                dvarx2 += aij * aij
                dvary2 += bij * bij
            }
        }

        dcov2 /= denom
        dvarx2 /= denom
        dvary2 /= denom

        if (dvarx2 <= 0.0 || dvary2 <= 0.0) return 0.0
        val dcov = sqrt(max(0.0, dcov2))
        val dvarx = sqrt(max(0.0, dvarx2))
        val dvary = sqrt(max(0.0, dvary2))

        val dcor = dcov / sqrt(dvarx * dvary)
        // numeric guard
        return min(1.0, max(0.0, dcor))
    }

    // -------------------------
    // Naive MIC/TIC-style scan using equipartition bins
    //
    // B(n) default: floor(n^0.6) (common in MIC discussions). You can override.
    //
    // Binning approach:
    // - Sort indices by x. Assign xBin = floor(rank * xBins / n), rank in [0..n-1]
    // - Same for y. This is a crude equipartition by *order*, not by value thresholds.
    //
    // Mutual information:
    // MI = sum_{i,j} p_ij * ln(p_ij / (p_i * p_j))
    // Normalized: MI / ln(min(xBins, yBins)) (0 if minBins < 2)
    //
    // MIC-like score = max normalized MI across grids with xBins*yBins <= B
    // TIC-like score = sum normalized MI across those grids
    // -------------------------
    /**
     * What [micTicNaive] reports: the best (`mic`) and the summed (`tic`) normalized mutual-information
     * score across every grid it scanned, plus the `bestXBins` × `bestYBins` grid that produced the
     * maximum. Read the caveat on [micTicNaive] before trusting any of the four.
     */
    data class MineLikeResult(
        val mic: Double,
        val tic: Double,
        val bestXBins: Int,
        val bestYBins: Int
    )

    /**
     * A MIC/TIC-style scan: bin x and y onto every grid whose cell count fits a budget, score normalized
     * mutual information on each, and report the maximum and the sum. Unlike the correlation
     * coefficients above it is blind to the *shape* of the relationship, so a parabola or a sine scores
     * about as high as a straight line — which is the point of reaching for it.
     *
     * It is not MICe/TICe, and the header comment in this file is emphatic about it: this is an
     * equipartition-by-order grid approximation, good for intuition, and it will not reproduce published
     * numbers. Small samples read high because the mutual-information estimate is biased upward on a
     * sparse grid, so compare scores at one n rather than across sizes. `bMax` caps the product of the
     * two bin counts, defaulting to floor(n^0.6) and never less than 4.
     */
    fun micTicNaive(
        x: DoubleArray,
        y: DoubleArray,
        bMax: Int? = null
    ): MineLikeResult {
        requireSameSize(x, y)
        val n = x.size

        val B = bMax ?: floor(Math.pow(n.toDouble(), 0.6)).toInt().coerceAtLeast(4)
        // Choose max bins to scan; a practical cap.
        val maxBins = min(n, B)

        // ranks by order (0..n-1) without averaging ties, for binning convenience
        fun orderRanks(values: DoubleArray): IntArray {
            // as in ranksAverageTies: use the sorted result, don't discard it
            val order = (0 until n).sortedWith { i, j ->
                val a = values[i]
                val b = values[j]
                when {
                    a < b -> -1
                    a > b -> 1
                    else -> i - j
                }
            }
            val rank = IntArray(n)
            for (r in 0 until n) rank[order[r]] = r
            return rank
        }

        val rx = orderRanks(x)
        val ry = orderRanks(y)

        fun normalizedMI(xBins: Int, yBins: Int): Double {
            if (xBins < 2 || yBins < 2) return 0.0
            val counts = Array(xBins) { IntArray(yBins) }
            val row = IntArray(xBins)
            val col = IntArray(yBins)

            for (i in 0 until n) {
                val xb = ((rx[i] * xBins) / n).coerceIn(0, xBins - 1)
                val yb = ((ry[i] * yBins) / n).coerceIn(0, yBins - 1)
                counts[xb][yb]++
                row[xb]++
                col[yb]++
            }

            var mi = 0.0
            val invN = 1.0 / n.toDouble()
            for (i in 0 until xBins) {
                val pi = row[i] * invN
                if (pi == 0.0) continue
                for (j in 0 until yBins) {
                    val cij = counts[i][j]
                    if (cij == 0) continue
                    val pij = cij * invN
                    val pj = col[j] * invN
                    // pij * ln(pij / (pi * pj))
                    mi += pij * ln(pij / (pi * pj))
                }
            }

            val norm = ln(min(xBins, yBins).toDouble())
            if (norm <= 0.0) return 0.0
            val nmi = mi / norm
            // Clip to [0,1] loosely (estimation can slightly exceed)
            return min(1.0, max(0.0, nmi))
        }

        var best = -1.0
        var bestX = 2
        var bestY = 2
        var tic = 0.0

        for (xBins in 2..maxBins) {
            for (yBins in 2..maxBins) {
                if (xBins * yBins > B) continue
                val score = normalizedMI(xBins, yBins)
                tic += score
                if (score > best) {
                    best = score
                    bestX = xBins
                    bestY = yBins
                }
            }
        }

        val mic = if (best < 0.0) 0.0 else best
        return MineLikeResult(mic = mic, tic = tic, bestXBins = bestX, bestYBins = bestY)
    }
}

/**
 * A scratch demo rather than part of the API: prints every coefficient in this file for `y = x²` over an
 * x grid symmetric about zero — the textbook case where [pearson] reads about 0 while the dependence is
 * in fact total, so [StatsDependence.chatterjeeXi] and [StatsDependence.distanceCorrelation] read high.
 *
 * The two *monotone* rank coefficients, [StatsDependence.spearman] and [StatsDependence.kendallTauB], side
 * with Pearson here and print about 0: the parabola is a function but not a monotone one, and monotone is
 * all either of them can see. `chatterjeeXi` ranks too but is not looking for monotonicity, so it reads
 * high with dCor — as does the MIC-like score, which bins rather than ranks.
 */
fun main() {
    val n = 400
    val x = DoubleArray(n) { i ->
        // symmetric about 0
        (i - n / 2).toDouble() / (n / 2).toDouble()
    }
    val yParabola = DoubleArray(n) { i -> x[i] * x[i] }
    println("Parabola y=x^2 (symmetric x)")
    println("Pearson:  " + StatsDependence.pearson(x, yParabola))
    println("Spearman: " + StatsDependence.spearman(x, yParabola))
    println("Kendall:  " + StatsDependence.kendallTauB(x, yParabola))
    println("Xi:       " + StatsDependence.chatterjeeXi(x, yParabola))
    println("dCor:     " + StatsDependence.distanceCorrelation(x, yParabola))
    val mine = StatsDependence.micTicNaive(x, yParabola)
    println("MIC~:     " + mine.mic + "  (best grid ${mine.bestXBins}x${mine.bestYBins})")
    println("TIC~:     " + mine.tic)
}

/**
 * Distance correlation over a sliding window, maintained incrementally: `push` a sample pair, get the
 * current 0..1 coefficient back, at O(w) work per sample for a window of w rather than the O(w²) rebuild
 * [StatsDependence.distanceCorrelation] would charge you.
 *
 * There is no warm-up gate. The first `push` returns null, and from the second onwards you get a real
 * coefficient over however few samples are in the window so far — genuine values, but noisy until the
 * window fills, so count pushes yourself if you want to ignore them. `windowSize` must be at least 2 or
 * construction throws. Once full, each `push` evicts the oldest pair. There is no reset: build a new
 * instance, since unlike [RollingXiCorrelation] this has no `clear`.
 *
 * The maintained row sums, grand sums and cross sums are only ever added to and subtracted from, never
 * recomputed, so a very long run accumulates floating-point drift that a batch call would not have.
 */
class RollingDistanceCorrelation(private val windowSize: Int) {
    init {
        require(windowSize >= 2) { "windowSize must be >= 2" }
    }

    private val xBuf = DoubleArray(windowSize)
    private val yBuf = DoubleArray(windowSize)

    // Row sums sA_i = sum_j |x_i - x_j| over current active points (ordered sum including j=i term = 0)
    private val sA = DoubleArray(windowSize)
    private val sB = DoubleArray(windowSize)

    // Active count and circular indices
    private var n = 0
    private var head = 0 // points to oldest element
    private var tail = 0 // points to next write position

    // Grand sums over ordered pairs (i,j):
    // SA = sum_{i,j} a_ij, SB = sum_{i,j} b_ij
    private var SA = 0.0
    private var SB = 0.0

    // Cross sums over ordered pairs:
    // CAB = sum_{i,j} a_ij * b_ij
    // CAA = sum_{i,j} a_ij^2
    // CBB = sum_{i,j} b_ij^2
    private var CAB = 0.0
    private var CAA = 0.0
    private var CBB = 0.0

    private fun idxAt(offset: Int): Int = (head + offset) % windowSize

    private inline fun forEachActive(exceptIndex: Int = -1, block: (slot: Int) -> Unit) {
        for (k in 0 until n) {
            val slot = idxAt(k)
            if (slot != exceptIndex) block(slot)
        }
    }

    /**
     * Push one (x,y) sample. If the window is full, evicts the oldest.
     *
     * @return current distance correlation for the window, or null if fewer than 2 points.
     */
    fun push(x: Double, y: Double): Double? {
        if (n == windowSize) {
            evictOldest()
        }
        insertNewest(x, y)
        return current()
    }

    /**
     * Current distance correlation (no mutation).
     *
     * Null until the window holds two samples; otherwise a value in 0..1, computed from the maintained
     * sums rather than from a rebuilt distance matrix. Returns 0.0, as the batch version does, when
     * either variable is constant across the window, and the result is clamped into 0..1 to absorb the
     * numerical error the incremental sums accumulate.
     */
    fun current(): Double? {
        if (n < 2) return null

        val nn = n.toDouble()
        val n2 = nn * nn
        val n3 = n2 * nn
        val n4 = n2 * n2

        var sumSA_SB = 0.0
        var sumSA2 = 0.0
        var sumSB2 = 0.0

        forEachActive { slot ->
            val a = sA[slot]
            val b = sB[slot]
            sumSA_SB += a * b
            sumSA2 += a * a
            sumSB2 += b * b
        }

        val dcov2 = (CAB / n2) - (2.0 * sumSA_SB / n3) + (SA * SB / n4)
        val dvarX2 = (CAA / n2) - (2.0 * sumSA2 / n3) + (SA * SA / n4)
        val dvarY2 = (CBB / n2) - (2.0 * sumSB2 / n3) + (SB * SB / n4)

        val dcov = sqrt(max(0.0, dcov2))
        val dvarX = sqrt(max(0.0, dvarX2))
        val dvarY = sqrt(max(0.0, dvarY2))

        if (dvarX == 0.0 || dvarY == 0.0) return 0.0

        val denom = sqrt(dvarX * dvarY)
        val dcor = dcov / denom

        // numerical guard
        return min(1.0, max(0.0, dcor))
    }

    /**
     * Evict oldest element at head. Updates all maintained sums in O(n).
     */
    private fun evictOldest() {
        val evict = head
        val ex = xBuf[evict]
        val ey = yBuf[evict]

        // For each other active point k, subtract its pair distances with evict from:
        // - row sums of k
        // - grand sums and cross sums (ordered pairs contribute twice: (evict,k) and (k,evict))
        var sumDx = 0.0
        var sumDy = 0.0
        var sumDxDy = 0.0
        var sumDx2 = 0.0
        var sumDy2 = 0.0

        forEachActive(exceptIndex = evict) { k ->
            val dx = abs(ex - xBuf[k])
            val dy = abs(ey - yBuf[k])

            // update other rows
            sA[k] -= dx
            sB[k] -= dy

            sumDx += dx
            sumDy += dy
            sumDxDy += dx * dy
            sumDx2 += dx * dx
            sumDy2 += dy * dy
        }

        // Remove evict's own row sums from totals by subtracting its contributions:
        // It had sA[evict] == sumDx, sB[evict] == sumDy (for the window before eviction).
        // We'll just clear them.
        sA[evict] = 0.0
        sB[evict] = 0.0

        // Update global sums: ordered pairs => factor 2 for pair contributions
        SA -= 2.0 * sumDx
        SB -= 2.0 * sumDy
        CAB -= 2.0 * sumDxDy
        CAA -= 2.0 * sumDx2
        CBB -= 2.0 * sumDy2

        // Advance head, reduce n
        head = (head + 1) % windowSize
        n -= 1

        // tail always points to next write position
        tail = (head + n) % windowSize
    }

    /**
     * Insert new element at tail. Updates all maintained sums in O(n).
     */
    private fun insertNewest(x: Double, y: Double) {
        val slot = tail
        xBuf[slot] = x
        yBuf[slot] = y

        var sumDx = 0.0
        var sumDy = 0.0
        var sumDxDy = 0.0
        var sumDx2 = 0.0
        var sumDy2 = 0.0

        // Distances to all existing active points
        forEachActive { k ->
            val dx = abs(x - xBuf[k])
            val dy = abs(y - yBuf[k])

            // Update existing row sums
            sA[k] += dx
            sB[k] += dy

            sumDx += dx
            sumDy += dy
            sumDxDy += dx * dy
            sumDx2 += dx * dx
            sumDy2 += dy * dy
        }

        // New row sums
        sA[slot] = sumDx
        sB[slot] = sumDy

        // Update global sums: ordered pair contributions double
        SA += 2.0 * sumDx
        SB += 2.0 * sumDy
        CAB += 2.0 * sumDxDy
        CAA += 2.0 * sumDx2
        CBB += 2.0 * sumDy2

        // Advance tail, increase n
        n += 1
        tail = (tail + 1) % windowSize
    }
}

/**
 * Chatterjee's xi over a sliding window, recomputed from scratch on every push — no incremental trickery,
 * just [StatsDependence.chatterjeeXi] over the window's current contents, which costs O(w log w) per
 * sample and is exactly as trustworthy as the batch answer.
 *
 * It carries the batch function's asymmetry: the first argument of [push] is the predictor, so pushing
 * (x, y) answers "how nearly is y a function of x". `windowSize` must be at least 2 or construction
 * throws; the first push returns null, and thereafter you get xi over however many samples have arrived,
 * the window filling before it starts evicting the oldest. [clear] empties it.
 */
class RollingXiCorrelation(private val windowSize: Int) {
    init {
        require(windowSize >= 2) { "windowSize must be >= 2" }
    }

    private val xBuf = DoubleArray(windowSize)
    private val yBuf = DoubleArray(windowSize)
    private var n = 0
    private var head = 0
    private var tail = 0

    /**
     * Push one (x, y) sample — evicting the oldest if the window is already full — and return xi over the
     * window as it now stands, or null while fewer than two samples have arrived.
     *
     * This recomputes from scratch rather than updating anything, so the cost is O(w log w) in the window
     * size on every call, not amortised away.
     */
    fun push(x: Double, y: Double): Double? {
        if (n == windowSize) {
            head = (head + 1) % windowSize
            n -= 1
        }

        xBuf[tail] = x
        yBuf[tail] = y
        tail = (tail + 1) % windowSize
        n += 1

        return current()
    }

    /**
     * Xi over the window's current contents without pushing anything, or null while fewer than two samples
     * have arrived. Copies the window out in arrival order first, which no longer matters to the answer:
     * [StatsDependence.chatterjeeXi] sorts by x itself and resolves x-ties by expectation rather than by
     * position, so the result depends only on *which* pairs are in the window and not on where in the ring
     * buffer they happen to sit.
     */
    fun current(): Double? {
        if (n < 2) return null

        val x = DoubleArray(n)
        val y = DoubleArray(n)
        for (i in 0 until n) {
            val slot = (head + i) % windowSize
            x[i] = xBuf[slot]
            y[i] = yBuf[slot]
        }
        return StatsDependence.chatterjeeXi(x, y)
    }

    /**
     * Forget every buffered sample. The next [push] starts a fresh window and returns null again, exactly
     * as it did on a new instance.
     */
    fun clear() {
        n = 0
        head = 0
        tail = 0
    }
}
