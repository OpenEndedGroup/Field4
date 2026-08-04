package afield.graphics

import afield.stdlib.Vec3
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Two overlapping subpaths of equal area used to hang the renderer.** Found 2026-08-04, by the
 * differential corpus in [ThreeJsViewerTessellationParityTest], which is the first thing that ever ran
 * two rings through [PlanarTessellation] that each contained the other's first point.
 *
 * The nesting pass picks a parent for each ring independently: the smallest-area ring that contains
 * *that ring's first point*. Two independent choices, so nothing stopped ring A choosing B and ring B
 * choosing A. The depth walk that follows — `while (p != -1) { d++; p = parent[p] }` — then spun
 * forever, at 100% of a core, with no output. `threejs_viewer.html` copies the same loop, so the same
 * drawing froze the browser tab rather than the JVM.
 *
 * Two overlapping squares in one filled `FLine` is an ordinary drawing. All it takes is for each ring's
 * first listed vertex to fall inside the other, which any `plusAssign` of two overlapping subpaths can
 * produce, and for the two projected areas to compare equal — exactly equal for congruent rings, and
 * that is the common case rather than the exotic one.
 *
 * Fixed by requiring a container to be **strictly larger** than what it contains, which is true of any
 * simple ring and makes `parent` a forest by construction: following it strictly increases area, so it
 * cannot return to where it started.
 *
 * `@Test(timeout = …)` rather than a plain assertion, because the failure mode is a hang. A hung test
 * reports nothing and looks like a slow build.
 */
class PlanarTessellationParentCycleTest {

    /**
     * Two congruent squares, overlapping, each listing a vertex that falls inside the other first.
     *
     * `a` starts at `(0, 0)`, which is well inside `b`. `b` starts at `(50, 50)`, which is well inside
     * `a`. A ring is a cycle, so which vertex it is *listed* from is free — and it decided whether
     * Field hung.
     */
    private fun mutuallyContainingSquares(): List<List<Vec3>> {
        val a = listOf(Vec3(0f, 0f, 0f), Vec3(100f, 0f, 0f), Vec3(100f, 100f, 0f), Vec3(0f, 100f, 0f))
        val b = listOf(Vec3(50f, 50f, 0f), Vec3(-50f, 50f, 0f), Vec3(-50f, -50f, 0f), Vec3(50f, -50f, 0f))
        return listOf(a, b)
    }

    @Test(timeout = 30_000)
    fun twoOverlappingRingsOfEqualAreaTerminate() {
        val rings = mutuallyContainingSquares()

        // The premise, so a future reader can see the trap rather than take it on trust: each ring's
        // first point really is inside the other, and the two projected areas really are equal.
        assertTrue(contains(rings[1], rings[0][0]), "a's first point should be inside b")
        assertTrue(contains(rings[0], rings[1][0]), "b's first point should be inside a")
        assertEquals(area(rings[0]), area(rings[1]), 0f, "the two rings should have equal projected area")

        val polygons = PlanarTessellation.tessellate(rings)

        // Neither is the other's hole, because neither is larger: two overlapping surfaces, both filled.
        // That is the honest answer to an ambiguous question — the alternative is to pick one, and there
        // is nothing in the geometry to pick it by.
        assertEquals(2, polygons.size, "both overlapping rings should fill")
        assertTrue(polygons.all { it.holes.isEmpty() }, "neither ring encloses the other")
        assertEquals(4, polygons.sumOf { it.triangleCount }, "two quads is four triangles")
    }

    /** And the viewer's JavaScript, which carries the same loop and would have frozen the tab. */
    @Test(timeout = 60_000)
    fun theViewersJavaScriptTerminatesOnTheSameRings() {
        val rings = mutuallyContainingSquares()
        ViewerJavaScript.open().use { js ->
            val json = rings.joinToString(",", "[", "]") { ring ->
                ring.joinToString(",", "[", "]") { "[${it.x.toDouble()},${it.y.toDouble()},${it.z.toDouble()}]" }
            }
            val polygons = js.call("tessellateCoplanarGroups", js.fromJson(json))
            assertEquals(2L, polygons.arraySize)
            for (at in 0 until polygons.arraySize) {
                assertEquals(0L, polygons.getArrayElement(at).getMember("holes").arraySize)
                assertEquals(6L, polygons.getArrayElement(at).getMember("triangles").arraySize)
            }
        }
    }

    /**
     * The nesting *that should still happen* is untouched: a genuinely smaller ring inside a larger one
     * is still a hole. The fix is a strictness, and a strictness can always be too strict.
     */
    @Test(timeout = 30_000)
    fun aStrictlySmallerRingIsStillAHole() {
        val outer = listOf(Vec3(0f, 0f, 0f), Vec3(100f, 0f, 0f), Vec3(100f, 100f, 0f), Vec3(0f, 100f, 0f))
        val hole = listOf(Vec3(30f, 30f, 0f), Vec3(30f, 70f, 0f), Vec3(70f, 70f, 0f), Vec3(70f, 30f, 0f))
        val polygons = PlanarTessellation.tessellate(listOf(outer, hole))
        assertEquals(1, polygons.size)
        assertEquals(listOf(1), polygons.single().holes)
        assertEquals(8, polygons.single().triangleCount)

        // Including a *degenerate* ring, whose projected area is zero: still strictly smaller, so still
        // a hole, so an all-collinear subpath inside a face keeps behaving as it always has.
        val collinear = listOf(Vec3(30f, 50f, 0f), Vec3(50f, 50f, 0f), Vec3(70f, 50f, 0f))
        assertEquals(listOf(1), PlanarTessellation.tessellate(listOf(outer, collinear)).single().holes)
    }

    // ------------------------------------------------------------------------------------ fixtures

    /** The XY projection of the same shoelace `PlanarTessellation` uses, for the premise assertions. */
    private fun area(ring: List<Vec3>): Float {
        var sum = 0f
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            sum += a.x * b.y - b.x * a.y
        }
        return kotlin.math.abs(0.5f * sum)
    }

    private fun contains(ring: List<Vec3>, p: Vec3): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val yi = ring[i].y
            val yj = ring[j].y
            if ((yi > p.y) != (yj > p.y) &&
                p.x < (ring[j].x - ring[i].x) * (p.y - yi) / ((yj - yi) + 1e-12f) + ring[i].x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
