package afield.geometry.mesh

import afield.geometry.path.ScalarField2D
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Measures [SdfGrid2D.repaired] and [SdfGrid2D.upsampled] against true distances: max and mean
 * `|d - true|` before and after, on pseudo-SDFs from CSG and smooth-min.
 */
class SdfRepairTest {
    private val lo = -1.5
    private val hi = 1.5

    private fun circle(cx: Double, cy: Double, r: Double): ScalarField2D = { x, y -> hypot(x - cx, y - cy) - r }

    private fun box(hx: Double, hy: Double): ScalarField2D = { x, y ->
        val qx = abs(x) - hx
        val qy = abs(y) - hy
        hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0)
    }

    /** Dense points on a circle's boundary for which [keep] holds. */
    private fun arc(cx: Double, cy: Double, r: Double, keep: (Double, Double) -> Boolean): List<Double> {
        val out = ArrayList<Double>()
        val n = 20_000
        for (k in 0 until n) {
            val t = 2 * PI * k / n
            val x = cx + r * cos(t)
            val y = cy + r * sin(t)
            if (keep(x, y)) {
                out += x
                out += y
            }
        }
        return out
    }

    /** Dense points along a segment for which [keep] holds. */
    private fun segment(ax: Double, ay: Double, bx: Double, by: Double, keep: (Double, Double) -> Boolean): List<Double> {
        val out = ArrayList<Double>()
        val n = 10_000
        for (k in 0..n) {
            val x = ax + (bx - ax) * k / n
            val y = ay + (by - ay) * k / n
            if (keep(x, y)) {
                out += x
                out += y
            }
        }
        return out
    }

    private fun truth(boundary: List<Double>, sign: ScalarField2D) =
        sdfFromBoundary(boundary.toDoubleArray(), sign, lo, lo, 0.02, 151, 151)

    private fun report(name: String, pseudo: SdfGrid2D, truth: ScalarField2D): Pair<SdfError, SdfError> {
        val fixed = pseudo.repaired()
        val before = pseudo.errorAgainst(truth)
        val after = fixed.errorAgainst(truth)
        println("$name: before $before | after $after")
        println("  consistency before ${pseudo.consistency()}")
        println("  consistency after  ${fixed.consistency()}")
        return before to after
    }

    @Test
    fun `repairing a CSG union of circles brings its inside to the true distance`() {
        val a = circle(-0.4, 0.0, 0.6)
        val b = circle(0.4, 0.0, 0.6)
        val union: ScalarField2D = { x, y -> min(a(x, y), b(x, y)) }
        val boundary = arc(-0.4, 0.0, 0.6) { x, y -> b(x, y) >= 0 } + arc(0.4, 0.0, 0.6) { x, y -> a(x, y) >= 0 }
        val (before, after) = report("union", sdfGrid2D(lo, lo, hi, hi, 97, union), truth(boundary, union))
        assertTrue(after.max < 0.5 * before.max, "max error should at least halve: $before -> $after")
        assertTrue(after.mean < before.mean, "mean error should drop: $before -> $after")
        assertTrue(after.max < 0.02, "repaired max error should be within ~h/2: $after")
    }

    @Test
    fun `repairing a box minus a circle fixes the outside the max left as a bound`() {
        val square = box(0.8, 0.6)
        val hole = circle(0.8, 0.6, 0.5)
        val cut: ScalarField2D = { x, y -> max(square(x, y), -hole(x, y)) }
        val boundary =
            segment(-0.8, -0.6, 0.8, -0.6) { x, y -> hole(x, y) >= 0 } +
                segment(0.8, -0.6, 0.8, 0.6) { x, y -> hole(x, y) >= 0 } +
                segment(0.8, 0.6, -0.8, 0.6) { x, y -> hole(x, y) >= 0 } +
                segment(-0.8, 0.6, -0.8, -0.6) { x, y -> hole(x, y) >= 0 } +
                arc(0.8, 0.6, 0.5) { x, y -> square(x, y) <= 0 }
        val (before, after) = report("box - circle", sdfGrid2D(lo, lo, hi, hi, 97, cut), truth(boundary, cut))
        assertTrue(after.max < 0.5 * before.max, "max error should at least halve: $before -> $after")
        assertTrue(after.mean < before.mean, "mean error should drop: $before -> $after")
    }

    @Test
    fun `repairing a smooth-min union matches the distance to its own zero set`() {
        val a = circle(-0.5, -0.2, 0.55)
        val b = circle(0.45, 0.25, 0.45)
        val k = 0.1
        val smooth: ScalarField2D = { x, y -> -k * ln(exp(-a(x, y) / k) + exp(-b(x, y) / k)) }
        // Brute force: the zero set of the smooth function itself, located on a very fine grid.
        val fine = sdfGrid2D(lo, lo, hi, hi, 3001, smooth).surfacePoints()
        val exact = sdfFromBoundary(fine, smooth, lo, lo, 0.02, 151, 151)
        val (before, after) = report("smooth union", sdfGrid2D(lo, lo, hi, hi, 97, smooth), exact)
        assertTrue(after.max < 0.5 * before.max, "max error should at least halve: $before -> $after")
        assertTrue(after.mean < before.mean, "mean error should drop: $before -> $after")
    }

    @Test
    fun `an exact SDF is already consistent and repair leaves it nearly alone`() {
        val disc = circle(0.1, -0.2, 0.7)
        val grid = sdfGrid2D(lo, lo, hi, hi, 97, disc)
        val c = grid.consistency()
        println("exact circle consistency $c")
        val after = grid.repaired().errorAgainst(disc)
        println("exact circle after repair $after")
        assertTrue(c.maxOvershoot < 0.01 && c.maxUndershoot < 0.01, "an exact SDF should be consistent: $c")
        assertTrue(after.max < 0.01, "repair should not damage an exact SDF: $after")
    }

    @Test
    fun `upsampling from circle constraints beats bilinear on a coarse exact SDF`() {
        val a = circle(-0.4, 0.0, 0.6)
        val b = circle(0.4, 0.0, 0.6)
        val union: ScalarField2D = { x, y -> min(a(x, y), b(x, y)) }
        val boundary = arc(-0.4, 0.0, 0.6) { x, y -> b(x, y) >= 0 } + arc(0.4, 0.0, 0.6) { x, y -> a(x, y) >= 0 }
        val exact = truth(boundary, union)
        // The coarse grid holds exact distances (sampled from the brute-force truth), 16 cells across.
        val coarse = sdfGrid2D(lo, lo, hi, hi, 17, exact)
        val lifted = coarse.upsampled(4).errorAgainst(exact)
        val bilinear = coarse.bilinearUpsampled(4).errorAgainst(exact)
        println("upsample x4 from 17x17: circle constraints $lifted | bilinear $bilinear")
        assertTrue(lifted.mean < bilinear.mean, "constraint upsampling should beat bilinear on mean: $lifted vs $bilinear")
    }
}
