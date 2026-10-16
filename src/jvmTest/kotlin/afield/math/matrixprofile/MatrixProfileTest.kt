package afield.math.matrixprofile

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatrixProfileTest {

    private fun walk(n: Int, seed: Long): DoubleArray {
        val r = Random(seed)
        var v = 0.0
        return DoubleArray(n) { v += r.nextGaussian(); v }
    }

    private fun bruteSelf(x: DoubleArray, m: Int, zone: Int): DoubleArray {
        val n = x.size - m + 1
        return DoubleArray(n) { i ->
            var best = Double.POSITIVE_INFINITY
            for (j in 0 until n) if (abs(i - j) > zone) best = minOf(best, zDistance(x, i, j, m))
            best
        }
    }

    private fun assertClose(expected: DoubleArray, actual: DoubleArray, tol: Double = 1e-6) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertTrue(abs(expected[i] - actual[i]) < tol, "at $i: expected ${expected[i]} got ${actual[i]}")
    }

    @Test
    fun selfJoinMatchesBruteForce() {
        val x = walk(600, 1)
        val m = 24
        val mp = matrixProfile(x, m)
        assertClose(bruteSelf(x, m, mp.exclusion), mp.distances)
        for (i in 0 until mp.size) assertEquals(mp.distances[i], zDistance(x, i, mp.indices[i], m), 1e-6)
    }

    @Test
    fun parallelPathMatchesBruteForce() {
        // Large enough that threadCount() splits the diagonals across workers.
        val x = walk(2500, 2)
        val m = 32
        val mp = matrixProfile(x, m)
        val brute = bruteSelf(x, m, mp.exclusion)
        for (i in 0 until mp.size step 37) assertEquals(brute[i], mp.distances[i], 1e-6)
    }

    @Test
    fun leftAndRightProfilesLookOnlyOneWay() {
        val x = walk(400, 3)
        val mp = matrixProfile(x, 16)
        for (i in 0 until mp.size) {
            if (mp.leftIndices[i] >= 0) assertTrue(mp.leftIndices[i] < i - mp.exclusion)
            if (mp.rightIndices[i] >= 0) assertTrue(mp.rightIndices[i] > i + mp.exclusion)
        }
        assertEquals(-1, mp.leftIndices[0])
    }

    @Test
    fun joinMatchesBruteForceBothWays() {
        val a = walk(300, 4)
        val b = walk(450, 5)
        val m = 20
        val (ab, ba) = joinBothWays(a, b, m)
        for (i in 0 until ab.size) {
            var best = Double.POSITIVE_INFINITY
            for (j in 0 until ba.size) best = minOf(best, zDist2(a, i, b, j, m))
            assertEquals(best, ab.distances[i], 1e-6)
        }
        for (j in 0 until ba.size) {
            var best = Double.POSITIVE_INFINITY
            for (i in 0 until ab.size) best = minOf(best, zDist2(a, i, b, j, m))
            assertEquals(best, ba.distances[j], 1e-6)
        }
    }

    private fun zDist2(a: DoubleArray, i: Int, b: DoubleArray, j: Int, m: Int): Double {
        val joined = a.copyOfRange(i, i + m) + b.copyOfRange(j, j + m)
        return zDistance(joined, 0, m, m)
    }

    @Test
    fun distanceProfileMatchesDirect() {
        val x = walk(500, 6)
        val q = x.copyOfRange(123, 123 + 30)
        val d = distanceProfile(q, x)
        for (j in d.indices) assertEquals(zDist2(q, 0, x, j, 30), d[j], 1e-6)
        assertEquals(0.0, d[123], 1e-6)
    }

    @Test
    fun plantedMotifIsFound() {
        val r = Random(7)
        val x = DoubleArray(3000) { r.nextGaussian() * 0.3 }
        val m = 50
        val shape = DoubleArray(m) { sin(it * 2 * PI / m) * 2 + it * 0.04 }
        for (at in listOf(400, 1500, 2600)) for (k in 0 until m) x[at + k] += shape[k]
        val motif = matrixProfile(x, m).motifs(1).first()
        // The best-aligned window may be shifted against the plant, but by the same amount at all three.
        val found = motif.indices.take(3).sorted()
        assertEquals(3, found.size)
        assertTrue(abs((found[1] - found[0]) - 1100) <= 3 && abs((found[2] - found[1]) - 1100) <= 3, "motif at ${motif.indices}")
        assertTrue(abs(found[0] - 400) < m / 2, "motif at ${motif.indices}")
    }

    @Test
    fun malformedBeatIsTheDiscord() {
        val x = heartbeat(seed = 15, odd = 21)
        val discord = matrixProfile(x, 80).discords(1).first()
        assertTrue(discord.index in (21 * 80 - 80)..(21 * 80 + 80), "discord at ${discord.index}")
    }

    /** 40 beats of 80 samples; beat [odd] has its pulse doubled and late. */
    private fun heartbeat(seed: Long, odd: Int): DoubleArray {
        val r = Random(seed)
        return DoubleArray(40 * 80) { i ->
            val beat = i / 80
            val t = (i % 80).toDouble()
            val pulse = if (beat == odd) 2.0 * (bump(t, 35.0, 3.0) + bump(t, 50.0, 3.0)) else 3.0 * bump(t, 30.0, 3.0)
            pulse - 0.6 * bump(t, 45.0, 6.0) + r.nextGaussian() * 0.05
        }
    }

    private fun bump(t: Double, at: Double, w: Double) = kotlin.math.exp(-((t - at) / w).let { it * it })

    @Test
    fun nanWindowsAreSkippedAndDoNotPoisonTheRest() {
        val x = walk(400, 8)
        x[200] = Double.NaN
        val mp = matrixProfile(x, 16)
        for (i in 185..200) assertEquals(Double.POSITIVE_INFINITY, mp.distances[i])
        val clean = x.copyOf().also { it[200] = 0.0 }
        val brute = bruteSelf(clean, 16, mp.exclusion)
        // Windows clear of the NaN agree with brute force wherever their neighbour is clear too.
        for (i in 0 until 180) if (mp.indices[i] !in 185..200) assertTrue(mp.distances[i] >= brute[i] - 1e-6)
        for (i in 0 until 180) assertTrue(mp.distances[i].isFinite())
    }

    @Test
    fun anytimeProfileIsAnUpperBound() {
        val x = walk(800, 9)
        val exact = matrixProfile(x, 24)
        val partial = matrixProfile(x, 24, fraction = 0.2, seed = 3)
        for (i in 0 until exact.size) assertTrue(partial.distances[i] >= exact.distances[i] - 1e-9)
    }

    @Test
    fun multiDimensionalOneChannelEqualsUnivariateAndMatchesBruteForce() {
        val ch = listOf(walk(300, 10), walk(300, 11), walk(300, 12))
        val m = 16
        val mmp = multiMatrixProfile(ch, m)
        assertClose(matrixProfile(ch[0], m).distances, multiMatrixProfile(listOf(ch[0]), m).distances(1))
        val n = mmp.size
        for (i in 0 until n step 7) for (k in 1..3) {
            var best = Double.POSITIVE_INFINITY
            for (j in 0 until n) if (abs(i - j) > mmp.exclusion) {
                val per = ch.map { zDistance(it, i, j, m) }.sorted()
                best = minOf(best, per.take(k).average())
            }
            assertEquals(best, mmp.distances(k)[i], 1e-6, "k=$k i=$i")
        }
    }

    @Test
    fun multiDimensionalMotifFindsItsChannels() {
        val r = Random(13)
        val n = 1500
        val ch = List(4) { DoubleArray(n) { r.nextGaussian() } }
        val m = 40
        val shape = DoubleArray(m) { sin(it * 2 * PI / m) * 4 }
        // The motif lives in channels 1 and 3 only.
        for (at in listOf(300, 1000)) for (c in listOf(1, 3)) for (k in 0 until m) ch[c][at + k] += shape[k] * (if (c == 3) -1 else 1)
        val mmp = multiMatrixProfile(ch, m)
        val motif = mmp.motifs(2, count = 1).first()
        assertEquals(setOf(1, 3), motif.channels.toSet())
        assertTrue(motif.indices.take(2).all { i -> listOf(300, 1000).any { abs(it - i) <= m / 4 } }, "at ${motif.indices}")
        // Adding a channel without the motif makes the profile jump there.
        val at = motif.indices[0]
        assertTrue(mmp.distances(3)[at] > 1.5 * mmp.distances(2)[at], "k=2 ${mmp.distances(2)[at]}, k=3 ${mmp.distances(3)[at]}")
    }

    @Test
    fun contrastProfileFindsTheShapeOnlyInThePositives() {
        val r = Random(14)
        val m = 40
        val common = DoubleArray(m) { sin(it * 2 * PI / m) * 3 }
        val special = DoubleArray(m) { if (it < m / 2) 3.0 else -3.0 }
        fun example(withSpecial: Boolean): DoubleArray {
            val x = DoubleArray(600) { r.nextGaussian() * 0.3 }
            for (k in 0 until m) x[100 + k] += common[k]
            if (withSpecial) for (k in 0 until m) x[400 + k] += special[k]
            return x
        }
        val cp = contrastProfile(listOf(example(true), example(true)), listOf(example(false), example(false)), m)
        val plato = cp.platos(1).first()
        assertTrue(abs(plato.offset - 400) < m / 2, "plato at example ${plato.example} offset ${plato.offset}")
        assertTrue(plato.contrast > cp.contrast[cp.segmentStarts[0] + 100] + 0.15, "contrast ${plato.contrast}")
        // The common shape has low contrast.
        assertTrue(cp.contrast[cp.segmentStarts[0] + 100] < 0.2)
    }
}
