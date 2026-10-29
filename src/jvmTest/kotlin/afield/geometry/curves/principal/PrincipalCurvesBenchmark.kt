package afield.geometry.curves.principal

import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test

/**
 * Timings at 10^4, 10^5 and 10^6 points, 2D and 3D, curves and trees. Off by default; run with
 *
 *     AFIELD_PRINCIPAL_CURVES_BENCHMARK=1 ./gradlew jvmTest --tests 'afield.geometry.curves.principal.PrincipalCurvesBenchmark'
 *
 * The table is printed and written to build/principal-curves-benchmark.md.
 */
class PrincipalCurvesBenchmark {
    private fun ms(t: Long) = (System.nanoTime() - t) / 1e6

    @Test
    fun timings() {
        if (System.getenv("AFIELD_PRINCIPAL_CURVES_BENCHMARK") != "1" || !PrincipalCurvesLibrary.isAvailable) {
            println("SKIPPED principal curves benchmark (set AFIELD_PRINCIPAL_CURVES_BENCHMARK=1; ${PrincipalCurvesLibrary.describe()})")
            return
        }
        val rows = mutableListOf(
            "| points | dim | graph | grow to | grow (ms) | refine 5 iterations (ms) | node statistics (ms) | network (ms) |",
            "| ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |",
        )
        for (n in listOf(10_000, 100_000, 1_000_000)) for (d in listOf(2, 3)) for (tree in listOf(false, true)) {
            val r = Random(3)
            // a Y of three noisy arms (a tree's worth of structure for either kind of graph)
            val pts = DoubleArray(n * d) { 0.0 }
            for (i in 0 until n) {
                val t = r.nextDouble() * 100
                val a = (i % 3) * 2.1
                pts[i * d] = t * cos(a) + r.nextGaussian(); pts[i * d + 1] = t * sin(a) + r.nextGaussian()
                if (d == 3) pts[i * d + 2] = 0.2 * t + r.nextGaussian()
            }
            val target = if (tree) 40 else 30
            PrincipalCurves.create(pts, d).use { f ->
                f.initComponents(1, 3)
                var t = System.nanoTime(); f.grow(target, if (tree) Growth.TREES else Growth.CURVES); val grow = ms(t)
                t = System.nanoTime(); f.refine(5); val refine = ms(t)
                t = System.nanoTime(); f.nodeStatistics(); val stats = ms(t)
                t = System.nanoTime(); f.network; val net = ms(t)
                rows += "| $n | $d | ${if (tree) "tree" else "curves"} | $target | %.0f | %.0f | %.0f | %.0f |".format(grow, refine, stats, net)
                println(rows.last())
            }
        }
        java.io.File("build/principal-curves-benchmark.md").writeText(rows.joinToString("\n") + "\n")
    }
}
