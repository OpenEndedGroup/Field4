package afield.geometry.rhino

import afield.geometry.mesh.TriMesh
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Closed Breps built here (no native reader needed) whose faces sample their shared edges at
 * different points, as Rhino's independent trim curves do. [BrepTessellator] must stitch them into
 * a closed mesh: no edge used by only one triangle once vertices are joined by position.
 */
class BrepWatertightTest {

    /** A flat bilinear patch with corners `o`, `o + a`, `o + b`, `o + a + b`, over `[0, 1]^2`. */
    private fun plane(o: DoubleArray, a: DoubleArray, b: DoubleArray): NurbsSurface {
        val cv = DoubleArray(16)
        val pts = listOf(o, DoubleArray(3) { o[it] + b[it] }, DoubleArray(3) { o[it] + a[it] }, DoubleArray(3) { o[it] + a[it] + b[it] })
        pts.forEachIndexed { k, p -> for (c in 0..2) cv[4 * k + c] = p[c]; cv[4 * k + 3] = 1.0 }
        val knots = doubleArrayOf(0.0, 0.0, 1.0, 1.0)
        return NurbsSurface(1, 1, 2, 2, knots, knots, cv)
    }

    /** A full rational circle of radius [r] about z in u (`[0, 1]`), from [z0] to [z1] in v. */
    private fun cylinder(r: Double, z0: Double, z1: Double): NurbsSurface {
        val w = sqrt(0.5)
        val circle = listOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1, 1 to 0)
        val cv = DoubleArray(9 * 2 * 4)
        circle.forEachIndexed { i, (x, y) ->
            val wi = if (i % 2 == 1) w else 1.0
            for ((j, z) in listOf(z0, z1).withIndex()) {
                val b = (i * 2 + j) * 4
                cv[b] = x * r * wi; cv[b + 1] = y * r * wi; cv[b + 2] = z * wi; cv[b + 3] = wi
            }
        }
        return NurbsSurface(2, 1, 9, 2, doubleArrayOf(0.0, 0.0, 0.0, .25, .25, .5, .5, .75, .75, 1.0, 1.0, 1.0), doubleArrayOf(0.0, 0.0, 1.0, 1.0), cv)
    }

    /** The unit square's boundary with [n] points per side (counter-clockwise). */
    private fun square(n: Int, u0: Double = 0.0, u1: Double = 1.0, v0: Double = 0.0, v1: Double = 1.0): DoubleArray {
        val out = ArrayList<Double>()
        for (k in 0 until n) { out.add(u0 + (u1 - u0) * k / n); out.add(v0) }
        for (k in 0 until n) { out.add(u1); out.add(v0 + (v1 - v0) * k / n) }
        for (k in 0 until n) { out.add(u1 - (u1 - u0) * k / n); out.add(v1) }
        for (k in 0 until n) { out.add(u0); out.add(v1 - (v1 - v0) * k / n) }
        return out.toDoubleArray()
    }

    /** A circle in a plane's `[0, 1]^2` parameters, centre `(cu, cv)`, radius `ru` by `rv`, [n] points. */
    private fun circle(n: Int, cu: Double, cv: Double, ru: Double, rv: Double, phase: Double = 0.0): DoubleArray =
        DoubleArray(2 * n) { if (it % 2 == 0) cu + ru * cos(2 * PI * (it / 2 + phase) / n) else cv + rv * sin(2 * PI * (it / 2 + phase) / n) }

    /** A face whose triangles face [outward] at its parameter centre. */
    private fun face(s: NurbsSurface, outward: DoubleArray, outer: DoubleArray, inner: List<DoubleArray> = emptyList(), at: DoubleArray = doubleArrayOf(0.5, 0.5)): BrepFace {
        val e = DoubleArray(6); s.evaluate(at[0], at[1], e)
        val dot = e[3] * outward[0] + e[4] * outward[1] + e[5] * outward[2]
        return BrepFace(s, dot < 0, false, listOf(outer), inner)
    }

    private fun v(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)

    /**
     * A 10-unit cube; each face samples its edges with a different count. It closes even before
     * stitching: `Area` drops the collinear extra points of a flat face's straight trims.
     */
    private fun box(): List<BrepFace> = listOf(
        face(plane(v(-5.0, -5.0, -5.0), v(10.0, 0.0, 0.0), v(0.0, 10.0, 0.0)), v(0.0, 0.0, -1.0), square(3)),
        face(plane(v(-5.0, -5.0, 5.0), v(10.0, 0.0, 0.0), v(0.0, 10.0, 0.0)), v(0.0, 0.0, 1.0), square(4)),
        face(plane(v(-5.0, -5.0, -5.0), v(10.0, 0.0, 0.0), v(0.0, 0.0, 10.0)), v(0.0, -1.0, 0.0), square(5)),
        face(plane(v(-5.0, 5.0, -5.0), v(10.0, 0.0, 0.0), v(0.0, 0.0, 10.0)), v(0.0, 1.0, 0.0), square(7)),
        face(plane(v(-5.0, -5.0, -5.0), v(0.0, 10.0, 0.0), v(0.0, 0.0, 10.0)), v(-1.0, 0.0, 0.0), square(2)),
        face(plane(v(5.0, -5.0, -5.0), v(0.0, 10.0, 0.0), v(0.0, 0.0, 10.0)), v(1.0, 0.0, 0.0), square(6)),
    )

    /** Radius 5, height 20; the side samples its circles at 48 points, the caps at 61 and 37. */
    private fun cylinder(): List<BrepFace> = listOf(
        face(cylinder(5.0, -10.0, 10.0), v(1.0, 0.0, 0.0), sideLoop(48, 3), at = doubleArrayOf(0.0, 0.5)),
        face(plane(v(-5.0, -5.0, -10.0), v(10.0, 0.0, 0.0), v(0.0, 10.0, 0.0)), v(0.0, 0.0, -1.0), circle(61, 0.5, 0.5, 0.5, 0.5)),
        face(plane(v(-5.0, -5.0, 10.0), v(10.0, 0.0, 0.0), v(0.0, 10.0, 0.0)), v(0.0, 0.0, 1.0), circle(37, 0.5, 0.5, 0.5, 0.5, 0.3)),
    )

    /** A cylinder side's parameter rectangle: [n] points along each circle, [m] along the seam. */
    private fun sideLoop(n: Int, m: Int): DoubleArray {
        val out = ArrayList<Double>()
        for (k in 0 until n) { out.add(k.toDouble() / n); out.add(0.0) }
        for (k in 0 until m) { out.add(1.0); out.add(k.toDouble() / m) }
        for (k in 0 until n) { out.add(1.0 - k.toDouble() / n); out.add(1.0) }
        for (k in 0 until m) { out.add(0.0); out.add(1.0 - k.toDouble() / m) }
        return out.toDoubleArray()
    }

    /** A 10-unit block with a radius-2 hole bored through it along z: trimmed faces plus the bore. */
    private fun boredBlock(): List<BrepFace> {
        val sides = box().drop(2)
        val bottom = face(plane(v(-5.0, -5.0, -5.0), v(10.0, 0.0, 0.0), v(0.0, 10.0, 0.0)), v(0.0, 0.0, -1.0), square(3), listOf(circle(41, 0.5, 0.5, 0.2, 0.2).reversedPairs()))
        val top = face(plane(v(-5.0, -5.0, 5.0), v(10.0, 0.0, 0.0), v(0.0, 10.0, 0.0)), v(0.0, 0.0, 1.0), square(4), listOf(circle(29, 0.5, 0.5, 0.2, 0.2, 0.5).reversedPairs()))
        val bore = face(cylinder(2.0, -5.0, 5.0), v(-1.0, 0.0, 0.0), sideLoop(53, 2), at = doubleArrayOf(0.0, 0.5))
        return sides + listOf(bottom, top, bore)
    }

    private fun DoubleArray.reversedPairs(): DoubleArray = DoubleArray(size) { this[(size / 2 - 1 - it / 2) * 2 + it % 2] }

    private class Topology(val open: Int, val nonManifold: Int)

    /** Edges used by one triangle, and by more than two, after joining vertices at the same float position. */
    private fun topology(m: TriMesh): Topology {
        val id = HashMap<Triple<Float, Float, Float>, Int>()
        val ids = IntArray(m.vertexCount) { id.getOrPut(Triple(m.positions[3 * it], m.positions[3 * it + 1], m.positions[3 * it + 2])) { id.size } }
        val count = HashMap<Pair<Int, Int>, Int>()
        for (t in 0 until m.triangleCount) for (e in 0..2) {
            val a = ids[m.indices[3 * t + e]]; val b = ids[m.indices[3 * t + (e + 1) % 3]]
            count.merge(minOf(a, b) to maxOf(a, b), 1, Int::plus)
        }
        return Topology(count.values.count { it == 1 }, count.values.count { it > 2 })
    }

    private fun area(m: TriMesh): Double {
        var a = 0.0
        val p = m.positions
        for (t in 0 until m.triangleCount) {
            val i = m.indices[3 * t] * 3; val j = m.indices[3 * t + 1] * 3; val k = m.indices[3 * t + 2] * 3
            val ex = (p[j] - p[i]).toDouble(); val ey = (p[j + 1] - p[i + 1]).toDouble(); val ez = (p[j + 2] - p[i + 2]).toDouble()
            val fx = (p[k] - p[i]).toDouble(); val fy = (p[k + 1] - p[i + 1]).toDouble(); val fz = (p[k + 2] - p[i + 2]).toDouble()
            a += 0.5 * sqrt((ey * fz - ez * fy).let { it * it } + (ez * fx - ex * fz).let { it * it } + (ex * fy - ey * fx).let { it * it })
        }
        return a
    }

    private fun volume(m: TriMesh): Double {
        var vol = 0.0
        val p = m.positions
        for (t in 0 until m.triangleCount) {
            val i = m.indices[3 * t] * 3; val j = m.indices[3 * t + 1] * 3; val k = m.indices[3 * t + 2] * 3
            vol += (p[i].toDouble() * (p[j + 1].toDouble() * p[k + 2] - p[j + 2].toDouble() * p[k + 1]) -
                p[i + 1].toDouble() * (p[j].toDouble() * p[k + 2] - p[j + 2].toDouble() * p[k]) +
                p[i + 2].toDouble() * (p[j].toDouble() * p[k + 1] - p[j + 1].toDouble() * p[k])) / 6.0
        }
        return vol
    }

    private fun check(name: String, faces: List<BrepFace>, exactArea: Double, exactVolume: Double, relTol: Double, cracks: Boolean = true) {
        val t = BrepTessellator()
        val m = t.meshFaces(faces)!!
        val topo = topology(m)
        val r = t.lastStitch!!
        val a = area(m); val vol = volume(m)
        println("WATERTIGHT $name: open edges before stitch ${r.openEdgesBefore}, after ${r.openEdgesAfter} (re-welded mesh: ${topo.open}, non-manifold ${topo.nonManifold}); " +
            "${r.splits} splits in ${r.passes} passes; ${m.triangleCount} triangles, ${m.vertexCount} vertices; area $a vs $exactArea; volume $vol vs $exactVolume")
        if (cracks) assertTrue(r.openEdgesBefore > 0, "$name: the fixture should crack before stitching")
        assertEquals(0, topo.open, "$name: open edges after stitching")
        assertEquals(0, topo.nonManifold, "$name: edges shared by more than two triangles")
        assertTrue(abs(a - exactArea) / exactArea < relTol, "$name area $a vs $exactArea")
        assertTrue(abs(vol - exactVolume) / exactVolume < relTol, "$name volume $vol vs $exactVolume")
    }

    @Test fun boxIsClosed() = check("box", box(), 600.0, 1000.0, 1e-6, cracks = false)

    @Test fun cylinderIsClosed() = check("cylinder", cylinder(), 2 * PI * 5 * 20 + 2 * PI * 25, PI * 25 * 20, 0.01)

    @Test fun boredBlockIsClosed() = check("bored block", boredBlock(), 600 - 2 * PI * 4 + 2 * PI * 2 * 10, 1000 - PI * 4 * 10, 0.01)
}
