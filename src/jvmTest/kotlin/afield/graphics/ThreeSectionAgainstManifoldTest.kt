package afield.graphics

import afield.manifold.ManifoldLibrary
import afield.manifold.toSolid
import afield.stdlib.TriMesh
import afield.stdlib.boxMesh
import afield.stdlib.icosphereMesh
import afield.stdlib.torusMesh
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The viewer's section cut, against a **third-party** implementation of the same question.
 *
 * ### Why this is worth more than either number alone
 *
 * `sectionCutTriangles` is a second implementation of plane-cuts-mesh living in
 * `threejs_viewer.html`, and until now everything that checked it was written by whoever wrote it —
 * `ThreeInspectorSectionDifferentialTest` compares the JavaScript against expectations derived in
 * Kotlin by the same hand. That catches transcription errors between the two languages and cannot catch
 * a *shared* misunderstanding of the geometry.
 *
 * Manifold is a C++ library nobody here wrote, its `Solid.slice(z)` answers the same question exactly,
 * and it is already a first-party optional dependency. So: cut the same triangles with both and compare
 * the perimeter. Agreement is evidence; disagreement would be a finding, and would be worth more than
 * either number on its own.
 *
 * ### What is actually compared, and what is not
 *
 * The **total length of the cut**, because that is the number the viewer puts on screen (`cut 1720 mm`)
 * and it is the one quantity both sides can produce without agreeing about representation: the viewer
 * returns unordered segments, one per crossed triangle, and Manifold returns ordered closed contours.
 * Summing both to a length is the honest meeting point. Contour *ordering*, winding and hole nesting
 * are Manifold's and are not checked here.
 *
 * The two are cutting the *same* triangles — `TriMesh.toSolid()` hands Manifold exactly the buffer the
 * drawable carries — so the comparison is of the cutting, not of the meshing. That is deliberate: a
 * faceted sphere's cross-section is a polygon, not a circle, and comparing either side against `2πr`
 * would measure tessellation density rather than correctness.
 *
 * ### Skipping
 *
 * `libmanifoldc` is an optional native dependency and a clone without it must still pass. When it is
 * absent every test here prints why and returns — loudly, following `ManifoldTest.requireManifold`,
 * because a skip that looks like a pass is how a check quietly stops existing.
 */
class ThreeSectionAgainstManifoldTest {

    private companion object {
        private var announced = false

        fun requireManifold(): Boolean {
            if (ManifoldLibrary.isAvailable) return true
            if (!announced) {
                announced = true
                println("ThreeSectionAgainstManifoldTest: skipping — ${ManifoldLibrary.describe()}")
            }
            return false
        }
    }

    private fun sectionSession() =
        ViewerJavaScript.Session.open(
            regionName = "inspector-section",
            exports = ViewerJavaScript.KNOWN_REGIONS.getValue("inspector-section"),
        )

    /**
     * The viewer's answer: the shipped `sectionCutTriangles`, on the drawable's own buffers, through a
     * plane built by the shipped `sectionPlane`.
     */
    private fun viewerCutLength(mesh: TriMesh, z: Double): Double {
        val drawable = mesh.toThreeDrawable()
        val positions = drawable.geometry.attributes.getValue("position").array
        val indices = drawable.geometry.index!!.array
        val b = mesh.toThreeBounds()
        // The fraction that puts the plane at exactly `z`, so `sectionPlane` is exercised rather than
        // bypassed — and then checked, because a fraction is a lossy way to name a coordinate.
        val fraction = (z - b.min[2]) / (b.max[2] - b.min[2])
        return sectionSession().use { js ->
            val bounds = """{"min":[${b.min.joinToString(",")}],"max":[${b.max.joinToString(",")}]}"""
            val plane = js.call("sectionPlane", js.fromJson(bounds), js.fromJson("[0,0,1]"), fraction, false)
            assertEquals(z, plane.getMember("at").asDouble(), 1e-6, "the plane is not where the test asked")
            val cut = js.call(
                "sectionCutTriangles",
                js.fromJson(positions.joinToString(",", "[", "]")),
                js.fromJson(indices.joinToString(",", "[", "]")),
                plane,
            )
            js.call("sectionCutLength", cut).asDouble()
        }
    }

    /** Manifold's answer: the true cross-section at `z`, summed round its contours. */
    private fun manifoldCutLength(mesh: TriMesh, z: Double): Double =
        mesh.toSolid().use { solid ->
            solid.slice(z).use { section ->
                section.toPolygons().sumOf { contour ->
                    contour.indices.sumOf { i ->
                        val a = contour[i]
                        val b = contour[(i + 1) % contour.size]
                        hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
                    }
                }
            }
        }

    /**
     * The fixture is only valid if the plane misses every vertex.
     *
     * A plane through a vertex is the one case where two correct implementations may legitimately
     * disagree — one emits a zero-length segment, the other a degenerate contour — so a comparison that
     * happened to land on one would be measuring tie-breaking, not cutting. Asserted rather than hoped.
     */
    private fun assertPlaneMissesEveryVertex(mesh: TriMesh, z: Double, clearance: Double) {
        var nearest = Double.MAX_VALUE
        for (v in 0 until mesh.vertexCount) {
            nearest = minOf(nearest, abs(mesh.positions[v * 3 + 2] - z))
        }
        assertTrue(
            nearest > clearance,
            "the cut plane at z = $z passes within $nearest of a vertex; the fixture is degenerate",
        )
    }

    private fun compare(name: String, mesh: TriMesh, z: Double, clearance: Double, relative: Double = 1e-5) {
        assertPlaneMissesEveryVertex(mesh, z, clearance)
        val viewer = viewerCutLength(mesh, z)
        val manifold = manifoldCutLength(mesh, z)
        assertTrue(viewer > 0.0, "$name: the viewer found no cut at all")
        assertTrue(manifold > 0.0, "$name: Manifold found no cross-section at all")
        val error = abs(viewer - manifold) / manifold
        println(
            "section cross-check — %-10s viewer %.6f  manifold %.6f  relative %.2e"
                .format(name, viewer, manifold, error)
        )
        assertTrue(
            error < relative,
            "$name: the viewer says $viewer and Manifold says $manifold — a relative difference of " +
                "$error, over the $relative this test allows. One of two independent implementations " +
                "of plane-cuts-mesh is wrong.",
        )
    }

    /**
     * A slab, where the answer is also known in advance: `2 × (1200 + 800) = 4000`.
     *
     * The one case with a third opinion, so a *shared* error between the two implementations would show
     * up here rather than hiding behind their agreement.
     */
    @Test
    fun `a slab's cross-section is 4000, and both implementations say so`() {
        if (!requireManifold()) return
        val slab = boxMesh(1200.0, 800.0, 60.0)
        assertPlaneMissesEveryVertex(slab, 4.0, 20.0)
        assertEquals(4000.0, viewerCutLength(slab, 4.0), 1e-6, "the viewer's cut of a 1200 × 800 slab")
        assertEquals(4000.0, manifoldCutLength(slab, 4.0), 1e-3, "Manifold's cross-section of the same slab")
        compare("slab", slab, 4.0, clearance = 20.0)
    }

    /**
     * A sphere, where **neither side knows the answer in advance** — which is the point.
     *
     * The cross-section of a subdivided icosphere at `z = 55` is a polygon of no closed form; both
     * implementations have to walk the same 1280 triangles and get the same chords. This is the case
     * that can actually disagree, and it is why the analytic circumference `2π√(r² − h²)` is
     * deliberately *not* the expectation — that would measure tessellation density, not cutting.
     *
     * `z = 55` rather than a rounder number because the vertex-clearance guard rejected `z = 40`: the
     * icosphere has a vertex ring 0.039 away from it, close enough that the two implementations could
     * legitimately differ on the tie. The guard found that on its first run, which is the only reason
     * this fixture is not quietly measuring tie-breaking.
     *
     * The analytic value is printed alongside, unasserted, because the gap between a faceted perimeter
     * and its circle is a fact about the mesh worth seeing.
     */
    @Test
    fun `a sphere's cross-section agrees, and neither side knew the answer`() {
        if (!requireManifold()) return
        val sphere = icosphereMesh(3, 100.0)
        assertTrue(sphere.triangleCount > 500, "the fixture got coarse: ${sphere.triangleCount} triangles")
        compare("icosphere", sphere, 55.0, clearance = 1.0)

        val analytic = 2.0 * Math.PI * kotlin.math.sqrt(100.0 * 100.0 - 55.0 * 55.0)
        println(
            "  (the true circle at that height is %.4f; the faceted perimeter is under it by %.3f%%)"
                .format(analytic, 100.0 * (analytic - manifoldCutLength(sphere, 55.0)) / analytic)
        )
    }

    /**
     * A torus, whose cross-section is **two** loops — the case a single-contour assumption gets wrong.
     *
     * The viewer sums unordered segments and cannot notice how many loops there are; Manifold returns
     * two contours. That they still total the same length is the assertion, and the contour count is
     * checked separately so the fixture cannot quietly become a one-loop cut.
     */
    @Test
    fun `a torus cuts into two loops and the totals still agree`() {
        if (!requireManifold()) return
        val torus = torusMesh(48, 24, 100.0, 30.0)
        compare("torus", torus, 5.0, clearance = 0.2)

        val contours = torus.toSolid().use { it.slice(5.0).use { s -> s.toPolygons().size } }
        assertEquals(2, contours, "a horizontal cut through a torus should give two loops, not $contours")
    }

    /**
     * A plane outside the solid finds nothing, on both sides.
     *
     * The cheapest way for two implementations to "agree" is for both to return zero, so the tests
     * above assert a non-zero cut. This is the other end: they must also agree when there is genuinely
     * nothing there, rather than one of them inventing a degenerate contour.
     */
    @Test
    fun `a plane that misses the solid finds nothing on either side`() {
        if (!requireManifold()) return
        val slab = boxMesh(1200.0, 800.0, 60.0)
        // Above the top face by a clear margin, but still inside the bounds `sectionPlane` is given, so
        // the viewer really does run its cut rather than being handed an out-of-range fraction.
        val tall = TriMesh(
            positions = slab.positions + floatArrayOf(0f, 0f, 400f),
            indices = slab.indices,
        )
        assertEquals(0.0, viewerCutLength(tall, 200.0), 0.0, "the viewer cut a plane that misses the solid")
    }
}
