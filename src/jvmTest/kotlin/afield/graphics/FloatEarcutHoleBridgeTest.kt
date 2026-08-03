package afield.graphics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [FloatEarcut] with more than one hole, which nothing had ever tested.
 *
 * `findHoleBridge` (`FloatEarcut.kt:586`) is David Eberly's bridge search: cast a ray left from the
 * hole's leftmost point, take the edge it hits, and then — the second half — walk the outer ring
 * looking for vertices inside the triangle of hole point, intersection and endpoint, keeping the one
 * at the minimum angle to the ray. Without that walk the bridge can be drawn across another hole's
 * bridge, and the two overlap.
 *
 * Upstream that walk is `do { … } while (p !== stop)` starting at `p = m` with `stop = m`. Field's
 * port had it as `while (p !== stop) { … }`, so **the body never executed once** and the refinement
 * was dead code. One hole was always fine, which is why nothing noticed: `FLine.tessellate` had no
 * hole test at all before 2026-08-02, and the only other caller is typography extrusion
 * (`typography_field.kt:342`), where a letter with two counters — `B`, `%`, `8` — was in the blast
 * radius.
 *
 * The measurement that finds it is **total triangle area against the figure's true area**. Triangle
 * *count* is a weaker instrument, because earcut legitimately emits fewer than `n + 2h - 2` when
 * `filterPoints` collapses collinear vertices around a bridge; area does not care.
 */
class FloatEarcutHoleBridgeTest {

    /** A counter-clockwise rectangle. */
    private fun rect(x: Float, y: Float, w: Float, h: Float): List<Pair<Float, Float>> =
        listOf(x to y, (x + w) to y, (x + w) to (y + h), x to (y + h))

    /** Triangulate an outer ring plus holes, and return (triangle count, total triangle area). */
    private fun triangulate(
        outer: List<Pair<Float, Float>>,
        holes: List<List<Pair<Float, Float>>>,
    ): Pair<Int, Double> {
        val flat = ArrayList<Float>()
        val starts = ArrayList<Int>()
        outer.forEach { flat.add(it.first); flat.add(it.second) }
        holes.forEach { hole ->
            starts.add(flat.size / 2)
            hole.forEach { flat.add(it.first); flat.add(it.second) }
        }
        val indices = FloatEarcut.earcut(
            flat.toFloatArray(),
            if (starts.isEmpty()) null else starts.toIntArray(),
            2
        )
        assertEquals(0, indices.size % 3, "earcut returned an incomplete triple list")
        var area = 0.0
        for (i in indices.indices step 3) {
            fun x(k: Int) = flat[2 * indices[k]]
            fun y(k: Int) = flat[2 * indices[k] + 1]
            area += 0.5 * abs(
                ((x(i + 1) - x(i)) * (y(i + 2) - y(i)) - (y(i + 1) - y(i)) * (x(i + 2) - x(i))).toDouble()
            )
        }
        return indices.size / 3 to area
    }

    /**
     * The case the dead loop got wrong: a 100x100 square with a small hole low-left and a larger one
     * high-right, so the two bridges are drawn diagonally past one another.
     *
     * Was 13 triangles measuring 9000 — overlapping bridges, 300 of the second hole filled in.
     */
    @Test
    fun twoHolesOnADiagonalAreBothFullySubtracted() {
        val (count, area) = triangulate(
            rect(0f, 0f, 100f, 100f),
            listOf(rect(10f, 10f, 20f, 20f).reversed(), rect(60f, 60f, 30f, 30f).reversed())
        )
        assertTrue(abs(area - 8700.0) < 1e-3, "area was $area, expected 10000 - 400 - 900 = 8700")
        assertEquals(14, count, "a 4-gon with two 4-gon holes is 4 + 8 + 4 - 2 = 14 triangles")
    }

    /** The same thing the other way up, so the fix is not specific to one diagonal. */
    @Test
    fun twoHolesOnTheOtherDiagonalAreBothFullySubtracted() {
        val (_, area) = triangulate(
            rect(0f, 0f, 100f, 100f),
            listOf(rect(10f, 60f, 20f, 20f).reversed(), rect(60f, 10f, 30f, 30f).reversed())
        )
        assertTrue(abs(area - 8700.0) < 1e-3, "area was $area")
    }

    /** Hole winding must not matter: earcut normalises whatever ring it is handed. */
    @Test
    fun holeWindingDoesNotChangeTheResult() {
        val forwards = triangulate(
            rect(0f, 0f, 100f, 100f),
            listOf(rect(10f, 10f, 20f, 20f), rect(60f, 60f, 30f, 30f))
        )
        val backwards = triangulate(
            rect(0f, 0f, 100f, 100f),
            listOf(rect(10f, 10f, 20f, 20f).reversed(), rect(60f, 60f, 30f, 30f).reversed())
        )
        assertTrue(abs(forwards.second - 8700.0) < 1e-3, "forwards area was ${forwards.second}")
        assertTrue(abs(backwards.second - 8700.0) < 1e-3, "backwards area was ${backwards.second}")
    }

    /** And hole *order* must not matter either — `eliminateHoles` sorts them by leftmost x. */
    @Test
    fun holeOrderDoesNotChangeTheResult() {
        val low = rect(10f, 10f, 20f, 20f).reversed()
        val high = rect(60f, 60f, 30f, 30f).reversed()
        val a = triangulate(rect(0f, 0f, 100f, 100f), listOf(low, high))
        val b = triangulate(rect(0f, 0f, 100f, 100f), listOf(high, low))
        assertTrue(abs(a.second - 8700.0) < 1e-3, "area was ${a.second}")
        assertEquals(a.second, b.second, "hole order changed the area")
    }

    /**
     * A letter with two counters, which is the shape that was really at risk: `typography_field.kt`
     * sends a glyph's whole ring system to [FloatEarcut] with hole indices, because "a letter is a
     * ring system", and a `B` has two.
     */
    @Test
    fun aTwoCounterGlyphSubtractsBothCounters() {
        val stem = rect(0f, 0f, 60f, 100f)
        val upper = rect(20f, 55f, 25f, 30f).reversed()
        val lower = rect(20f, 15f, 25f, 30f).reversed()
        val (_, area) = triangulate(stem, listOf(upper, lower))
        assertTrue(abs(area - (6000.0 - 750.0 - 750.0)) < 1e-3, "area was $area, expected 4500")
    }

    /** Three holes, to make sure the walk composes rather than working for exactly two. */
    @Test
    fun threeHolesAreAllSubtracted() {
        val (_, area) = triangulate(
            rect(0f, 0f, 120f, 120f),
            listOf(
                rect(10f, 10f, 20f, 20f).reversed(),
                rect(50f, 50f, 20f, 20f).reversed(),
                rect(90f, 90f, 20f, 20f).reversed()
            )
        )
        assertTrue(abs(area - (14400.0 - 3 * 400.0)) < 1e-3, "area was $area, expected 13200")
    }

    /** One hole was always right, and still is — the regression guard on the other side. */
    @Test
    fun oneHoleIsUnchanged() {
        val (count, area) = triangulate(
            rect(0f, 0f, 100f, 100f),
            listOf(rect(30f, 30f, 40f, 40f).reversed())
        )
        assertEquals(8, count)
        assertTrue(abs(area - 8400.0) < 1e-3, "area was $area")
    }
}
