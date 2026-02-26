package afield.stdlib

import kotlin.math.*

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
    fun ranksAverageTies(values: DoubleArray): DoubleArray {
        val n = values.size
        val idx = IntArray(n) { it }
        idx.sortedWith { i, j ->
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
            val v = values[idx[pos]]
            while (end + 1 < n && values[idx[end + 1]] == v) end++

            // positions are 0-based; rank is 1-based
            val avgRank = ((pos + 1) + (end + 1)) / 2.0
            for (k in pos..end) {
                ranks[idx[k]] = avgRank
            }
            pos = end + 1
        }
        return ranks
    }

    // -------------------------
    // Pearson
    // -------------------------
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
    // - observations are ordered by x (ties are broken deterministically by index)
    // - R_i = #{j : y_j <= y_i}
    // - L_i = #{j : y_j >= y_i}
    //
    // For continuous y (no ties), this reduces to:
    // 1 - 3 * sum |rank(y_{i+1}) - rank(y_i)| / (n^2 - 1)
    // -------------------------
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

        var sumAbsDiff = 0L
        for (i in 0 until n - 1) {
            val a = r[orderByX[i]]
            val b = r[orderByX[i + 1]]
            sumAbsDiff += abs(a - b).toLong()
        }

        var denomTerm = 0L
        for (i in 0 until n) {
            val li = l[i].toLong()
            denomTerm += li * (n - li).toLong()
        }

        val denominator = 2.0 * denomTerm.toDouble()
        if (denominator == 0.0) return 0.0 // e.g. all y identical

        val numerator = n.toDouble() * sumAbsDiff.toDouble()
        return 1.0 - (numerator / denominator)
    }

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
    data class MineLikeResult(
        val mic: Double,
        val tic: Double,
        val bestXBins: Int,
        val bestYBins: Int
    )

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
            val idx = IntArray(n) { it }
            idx.sortedWith { i, j ->
                val a = values[i]
                val b = values[j]
                when {
                    a < b -> -1
                    a > b -> 1
                    else -> i - j
                }
            }
            val rank = IntArray(n)
            for (r in 0 until n) rank[idx[r]] = r
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

class RollingXiCorrelation(private val windowSize: Int) {
    init {
        require(windowSize >= 2) { "windowSize must be >= 2" }
    }

    private val xBuf = DoubleArray(windowSize)
    private val yBuf = DoubleArray(windowSize)
    private var n = 0
    private var head = 0
    private var tail = 0

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

    fun clear() {
        n = 0
        head = 0
        tail = 0
    }
}
