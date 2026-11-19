package afield.geometry.spatial

import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Approximate one-to-one transport between equal-size XYZ point clouds using projected [BspOt2D] proposals. */
object BspOt3D {
    /** Search budget and deterministic seed. The first three projections are XY, XZ and YZ. */
    data class Options(val projections: Int = 9, val treesPerProjection: Int = 8, val seed: Long = 1L) {
        init {
            require(projections >= 3 && treesPerProjection >= 1)
        }
    }

    /** Source-to-target permutation and the squared XYZ travel before and after each projection. */
    class Result internal constructor(val targetOfSource: IntArray, val costHistory: DoubleArray) {
        /** Total squared XYZ travel of the returned permutation. */
        val squaredCost: Double get() = costHistory.last()
    }

    /**
     * Match packed XYZ triples. Each 2D proposal can contribute cycles and swaps that improve the
     * actual squared 3D cost. Storage is linear in point count and no dense cost matrix is formed.
     * An optional [initial] permutation replaces the identity baseline and is copied before
     * search. [progress] reports completed projections and the total, starting at zero.
     */
    fun match(
        x: FloatArray,
        y: FloatArray,
        options: Options = Options(),
        initial: IntArray? = null,
        progress: ((Int, Int) -> Unit)? = null,
    ): Result {
        require(x.size == y.size && x.size % 3 == 0 && x.all { it.isFinite() } && y.all { it.isFinite() }) {
            "Expected equal-size clouds of finite packed XYZ triples"
        }
        val n = x.size / 3
        if (initial != null) BspOt2D.validatePermutation(initial, n)
        fun cost(i: Int, j: Int): Double {
            var c = 0.0
            for (k in 0..2) {
                val d = x[i * 3 + k].toDouble() - y[j * 3 + k]
                c += d * d
            }
            return c
        }
        val best = initial?.copyOf() ?: IntArray(n) { it }
        val inverse = IntArray(n)
        val successor = IntArray(n)
        val visited = BooleanArray(n)
        val history = DoubleArray(options.projections + 1)
        history[0] = (0 until n).sumOf { cost(it, best[it]) }
        val scale = max(x.maxOfOrNull { abs(it.toDouble()) } ?: 0.0, y.maxOfOrNull { abs(it.toDouble()) } ?: 0.0)
            .coerceAtLeast(1.0)
        progress?.invoke(0, options.projections)
        for (view in 0 until options.projections) {
            if (Thread.currentThread().isInterrupted) throw CancellationException("3D matching interrupted")

            // First XY, XZ, YZ, then oblique orthogonal views, never discarding Z in acceptance.
            val e = when (view % 3) {
                0 -> doubleArrayOf(1.0, 0.0, 0.0)
                1 -> doubleArrayOf(1.0, 0.0, 0.0)
                else -> doubleArrayOf(0.0, 1.0, 0.0)
            }
            val f = when (view % 3) {
                0 -> doubleArrayOf(0.0, 1.0, 0.0)
                else -> doubleArrayOf(0.0, 0.0, 1.0)
            }
            if (view >= 3) {
                val angle = (view + 1) * 2.399963229728653
                val normal =
                    doubleArrayOf(e[1] * f[2] - e[2] * f[1], e[2] * f[0] - e[0] * f[2], e[0] * f[1] - e[1] * f[0])
                for (k in 0..2) e[k] = e[k] * cos(angle) + normal[k] * sin(angle)
            }
            fun project(p: FloatArray, v: DoubleArray) =
                FloatArray(n) { i -> ((p[i * 3] * v[0] + p[i * 3 + 1] * v[1] + p[i * 3 + 2] * v[2]) / scale).toFloat() }
            val candidate =
                BspOt2D.match(
                        project(x, e),
                        project(x, f),
                        project(y, e),
                        project(y, f),
                        BspOt2D.Options(
                            trees = options.treesPerProjection,
                            seed = options.seed + view,
                            parallelism = 1,
                            simd = false,
                        ),
                        best,
                    )
                    .targetOfSource
            for (i in 0 until n) inverse[best[i]] = i
            for (i in 0 until n) successor[i] = inverse[candidate[i]]
            visited.fill(false)
            for (start in 0 until n) {
                if (visited[start]) continue
                var i = start
                var old = 0.0
                var fresh = 0.0
                do {
                    visited[i] = true
                    old += cost(i, best[i])
                    fresh += cost(i, candidate[i])
                    i = successor[i]
                } while (i != start)
                if (fresh < old && old - fresh > 1e-12 * old) {
                    i = start
                    do {
                        val previous = best[i]
                        best[i] = candidate[i]
                        candidate[i] = previous
                        i = successor[i]
                    } while (i != start)
                }
            }
            for (i in 0 until n) inverse[best[i]] = i
            repeat(2) {
                for (i in 0 until n) {
                    val j = inverse[candidate[i]]
                    if (j == i) continue
                    val old = cost(i, best[i]) + cost(j, best[j])
                    val fresh = cost(i, best[j]) + cost(j, best[i])
                    if (fresh < old && old - fresh > 1e-12 * old) {
                        val target = best[i]
                        best[i] = best[j]
                        best[j] = target
                        inverse[best[i]] = i
                        inverse[best[j]] = j
                    }
                }
            }
            history[view + 1] = (0 until n).sumOf { cost(it, best[it]) }
            check(history[view + 1] <= history[view] + max(1e-9, history[view] * 1e-12))
            progress?.invoke(view + 1, options.projections)
        }
        return Result(best, history)
    }
}
