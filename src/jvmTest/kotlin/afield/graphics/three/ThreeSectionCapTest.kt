package afield.graphics.three

import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.boxMesh
import afield.geometry.mesh.icosphereMesh
import afield.geometry.mesh.torusMesh
import afield.geometry.solid.manifold.ManifoldLibrary
import afield.geometry.solid.manifold.toSolid
import afield.ui.visualization.describe
import afield.graphics.render.filled
import afield.graphics.render.fline
import afield.graphics.render.label
import afield.graphics.render.stroked
import org.graalvm.polyglot.Value
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Section caps — the viewer's `sectionCapTriangles`, **executing the shipped JavaScript**.
 *
 * A cap is the cut as a region: the per-triangle crossings chained into closed loops, sorted into
 * outlines and holes by nesting depth, triangulated in the plane. Each step has a way to be wrong that
 * still draws *something*, so each is pinned to a number known before the code runs:
 *
 *  * **chaining** — a slab's cut is one loop, and stays one loop when the mesh is unindexed (every
 *    crossing computed twice, from either side of its edge) or doubled as two coincident shells;
 *  * **nesting** — a torus cut flat is an annulus, whose cap must have a hole where the torus has
 *    one; a part embedded in another caps over it in its own colour; an inward-wound shell is a
 *    cavity and stays open; parts that only touch sit side by side. The counterfactual fills a cavity
 *    by ignoring winding;
 *  * **triangulation** — the triangles' own area equals the region's, so the fill neither overlaps
 *    itself nor leaves gaps;
 *  * **honesty** — an open surface is not capped, and is counted as open rather than filled with a
 *    guess.
 *
 * And against Manifold, which nobody here wrote: the cap's area is the area of `Solid.slice(z)`.
 */
class ThreeSectionCapTest {

    private fun session(perturbation: (String) -> String = ViewerJavaScript.UNPERTURBED) =
        ViewerJavaScript.Session.open(
            regionName = "inspector-section",
            exports = ViewerJavaScript.KNOWN_REGIONS.getValue("inspector-section"),
            perturbation = perturbation,
        )

    private fun FloatArray.json() = joinToString(",", "[", "]") { it.toDouble().toString() }
    private fun IntArray.json() = joinToString(",", "[", "]")

    private fun Value.doubles(): DoubleArray =
        DoubleArray(arraySize.toInt()) { getArrayElement(it.toLong()).asDouble() }

    /** What the viewer does for one solid on a slider tick: cut it, then cap the cut. */
    private class Cap(val positions: DoubleArray, val area: Double, val loops: Int, val open: Int)

    private fun ViewerJavaScript.Session.cap(positions: FloatArray, index: IntArray?, z: Double): Cap {
        val plane = fromJson("""{"n": [0,0,1], "d": ${-z}}""")
        val cut = call(
            "sectionCutTriangles",
            fromJson(positions.json()),
            index?.let { fromJson(it.json()) },
            plane,
        )
        val cap = call("sectionCapTriangles", cut, plane)
        return Cap(
            cap.getMember("positions").doubles(),
            cap.getMember("area").asDouble(),
            cap.getMember("loops").asInt(),
            cap.getMember("open").asInt(),
        )
    }

    private fun ViewerJavaScript.Session.cap(mesh: TriMesh, z: Double) = cap(mesh.positions, mesh.indices, z)

    /** The summed area of the cap's own triangles, and the worst distance of a vertex off the plane. */
    private fun Cap.triangleArea(): Double {
        var total = 0.0
        for (t in positions.indices step 9) {
            val ux = positions[t + 3] - positions[t]
            val uy = positions[t + 4] - positions[t + 1]
            val vx = positions[t + 6] - positions[t]
            val vy = positions[t + 7] - positions[t + 1]
            total += abs(ux * vy - uy * vx) / 2.0
        }
        return total
    }

    private fun Cap.worstOffPlane(z: Double): Double =
        (2 until positions.size step 3).maxOfOrNull { abs(positions[it] - z) } ?: 0.0

    /** Whether any cap triangle covers the point `(x, y)` in the plane. */
    private fun Cap.covers(x: Double, y: Double): Boolean {
        for (t in positions.indices step 9) {
            fun side(i: Int, j: Int): Double =
                (positions[t + j] - positions[t + i]) * (y - positions[t + i + 1]) -
                    (positions[t + j + 1] - positions[t + i + 1]) * (x - positions[t + i])
            val a = side(0, 3)
            val b = side(3, 6)
            val c = side(6, 0)
            if ((a >= 0 && b >= 0 && c >= 0) || (a <= 0 && b <= 0 && c <= 0)) return true
        }
        return false
    }

    /**
     * The torus stood up: `torusMesh` lies in the xz plane, and a horizontal cut through *that* is two
     * separate tube discs. Rotated a quarter turn about x, so its axis is z, a horizontal cut is the
     * annulus these tests want.
     */
    private fun standingTorus(): TriMesh {
        val flat = torusMesh(48, 24, 100.0, 30.0)
        val p = flat.positions.copyOf()
        for (v in 0 until flat.vertexCount) {
            val y = flat.positions[v * 3 + 1]
            val z = flat.positions[v * 3 + 2]
            p[v * 3 + 1] = -z
            p[v * 3 + 2] = y
        }
        return TriMesh(positions = p, indices = flat.indices)
    }

    /** The same triangles with no index: every vertex repeated per triangle, as an exporter writes them. */
    private fun TriMesh.unindexed(): FloatArray {
        val out = FloatArray(indices.size * 3)
        indices.forEachIndexed { k, v ->
            out[k * 3] = positions[v * 3]
            out[k * 3 + 1] = positions[v * 3 + 1]
            out[k * 3 + 2] = positions[v * 3 + 2]
        }
        return out
    }

    // ---------------------------------------------------------------- chaining

    /**
     * A 1200 × 800 slab cut through its thickness caps as one loop of area 960 000, exactly.
     */
    @Test
    fun `a slab's cap is one loop with the slab's plan area`() {
        val slab = boxMesh(1200.0, 800.0, 60.0)
        session().use { js ->
            val cap = js.cap(slab, 4.0)
            assertEquals(1, cap.loops, "a slab's cut should chain into one loop")
            assertEquals(0, cap.open, "a closed slab left a chain open")
            assertEquals(960_000.0, cap.area, 1e-6, "the cap's area")
            assertEquals(960_000.0, cap.triangleArea(), 1e-3, "the cap's triangles should cover the region exactly")
            assertTrue(cap.worstOffPlane(4.0) < 1e-9, "a cap vertex sat off the plane")
        }
    }

    /**
     * Unindexed and doubled meshes cap the same as the clean one.
     *
     * Unindexed, each crossing is computed twice — once from each triangle on the edge, interpolating
     * from opposite ends — and the two need not agree to the last bit, which is why endpoints are
     * welded rather than compared. Doubled (the slab's triangles twice over, as two-sided geometry is
     * sometimes modelled) every segment arrives twice and must cap once, not as two loops that the
     * even–odd rule would then cancel to nothing.
     */
    @Test
    fun `an unindexed or doubled slab still caps as one loop`() {
        val slab = boxMesh(1200.0, 800.0, 60.0)
        session().use { js ->
            val loose = js.cap(slab.unindexed(), null, 4.0)
            assertEquals(1, loose.loops, "the unindexed slab")
            assertEquals(960_000.0, loose.area, 1e-3, "the unindexed slab's cap area")

            val doubled = js.cap(slab.unindexed() + slab.unindexed(), null, 4.0)
            assertEquals(1, doubled.loops, "the doubled slab should cap once")
            assertEquals(960_000.0, doubled.area, 1e-3, "the doubled slab's cap area")
        }
    }

    /**
     * A surface that is not a solid is not capped, and says so.
     *
     * Four planar wall skins, cut across: each gives one open chain. Filling those would invent material
     * that is not in the model.
     */
    @Test
    fun `open skins leave no cap and count as open`() {
        fun skin(name: String, x: Double) = fline {
            moveTo(x, 0.0, 0.0)
            lineTo(x, 200.0, 0.0)
            lineTo(x, 200.0, 300.0)
            lineTo(x, 0.0, 300.0)
            close()
            label = name
            filled = true
            stroked = false
        }
        val skins = listOf(skin("a", 0.0), skin("b", 12.0), skin("c", 24.0), skin("d", 36.0))
        val fills = buildThreeGeometryPayload(
            uid = "test", lines = skins, samplesPerCubic = 12, autoFrame = true, editable = false,
            groupKeys = skins.map { it.label ?: "" }, inspector = ThreeInspector(),
        ).fills!!
        session().use { js ->
            val cap = js.cap(fills.attributes.getValue("position").array, fills.index!!.array, 150.0)
            assertEquals(0, cap.loops, "an open skin was capped")
            assertEquals(4, cap.open, "each of the four skins should be one open cut")
            assertEquals(0, cap.positions.size, "a cap was drawn for open surfaces")
        }
    }

    // ---------------------------------------------------------------- nesting

    /**
     * A torus cut flat caps as an annulus: two loops, one hole, nothing at the centre.
     *
     * The triangle area matching the reported area says the hole is a hole in the triangulation too,
     * not just in the arithmetic; the centre test says the hole is in the right place.
     */
    @Test
    fun `a torus caps as an annulus with its hole left open`() {
        val torus = standingTorus()
        session().use { js ->
            val cap = js.cap(torus, 5.0)
            assertEquals(2, cap.loops, "a flat cut through a torus is two loops")
            assertEquals(0, cap.open)
            assertEquals(cap.area, cap.triangleArea(), cap.area * 1e-6, "the triangles should cover the annulus only")
            assertTrue(!cap.covers(0.0, 0.0), "the cap covers the torus's hole")
            assertTrue(cap.covers(100.0, 0.0), "the cap misses the torus's own material")
            // Faceted, so not the analytic annulus — but within a few percent of it.
            val r = kotlin.math.sqrt(30.0 * 30.0 - 5.0 * 5.0)
            val analytic = Math.PI * ((100.0 + r) * (100.0 + r) - (100.0 - r) * (100.0 - r))
            assertTrue(abs(cap.area - analytic) / analytic < 0.03, "annulus area ${cap.area} vs analytic $analytic")
        }
    }

    // ---------------------------------------------------------------- several parts at once

    private fun TriMesh.moved(dx: Double, dy: Double, dz: Double): TriMesh {
        val p = positions.copyOf()
        for (v in 0 until vertexCount) {
            p[v * 3] += dx.toFloat(); p[v * 3 + 1] += dy.toFloat(); p[v * 3 + 2] += dz.toFloat()
        }
        return TriMesh(positions = p, indices = indices)
    }

    /** The same surface wound the other way: what an inside-out export, or a cavity's wall, looks like. */
    private fun TriMesh.inverted(): TriMesh {
        val i = indices.copyOf()
        for (t in 0 until i.size / 3) { val b = i[t * 3 + 1]; i[t * 3 + 1] = i[t * 3 + 2]; i[t * 3 + 2] = b }
        return TriMesh(positions = positions, indices = i)
    }

    /** One owner's share of a cut: its area by the region's own triangles, and the positions. */
    private inner class Region(val owner: Int, val positions: DoubleArray) {
        fun area() = Cap(positions, 0.0, 0, 0).triangleArea()
        fun covers(x: Double, y: Double) = Cap(positions, 0.0, 0, 0).covers(x, y)
    }

    /** What `inspectorDrawCut` does for a scene: every part cut, all of them capped together. */
    private fun ViewerJavaScript.Session.capScene(parts: List<Pair<Int, TriMesh>>, z: Double): Pair<Double, Map<Int, Region>> {
        val plane = fromJson("""{"n": [0,0,1], "d": ${-z}}""")
        val segments = ArrayList<Double>()
        val owners = ArrayList<Int>()
        parts.forEach { (owner, mesh) ->
            val cut = call("sectionCutTriangles", fromJson(mesh.positions.json()), fromJson(mesh.indices.json()), plane).doubles()
            cut.forEach { segments += it }
            repeat(cut.size / 6) { owners += owner }
        }
        val capped = call(
            "sectionCapRegions",
            fromJson(segments.joinToString(",", "[", "]")),
            fromJson(owners.joinToString(",", "[", "]")),
            plane,
        )
        val regions = capped.getMember("regions")
        val byOwner = (0 until regions.arraySize).associate { k ->
            val r = regions.getArrayElement(k)
            val owner = r.getMember("owner").asInt()
            owner to Region(owner, r.getMember("positions").doubles())
        }
        return capped.getMember("area").asDouble() to byOwner
    }

    private val outer = boxMesh(1000.0, 600.0, 200.0)
    private val inner = boxMesh(400.0, 200.0, 100.0).moved(100.0, 0.0, 0.0)

    /**
     * A box inside a box, as two parts: the inner one caps in its own colour, and the outer cap is holed
     * round it rather than lying coplanar underneath. 600 000 − 80 000 and 80 000, exactly.
     */
    @Test
    fun `a part embedded in another caps over it, not under it`() {
        session().use { js ->
            val (area, regions) = js.capScene(listOf(0 to outer, 1 to inner), 7.0)
            assertEquals(600_000.0, area, 1e-6, "the two caps together cover the outer box's plan")
            assertEquals(520_000.0, regions.getValue(0).area(), 1e-3, "the outer part's cap, holed round the inner")
            assertEquals(80_000.0, regions.getValue(1).area(), 1e-3, "the inner part's cap")
            assertTrue(regions.getValue(1).covers(100.0, 0.0), "the inner part does not cap its own centre")
            assertTrue(!regions.getValue(0).covers(100.0, 0.0), "the outer cap lies under the inner one")
        }
    }

    /** The same inner box wound inward and in the same part is a cavity: left open, not filled. */
    @Test
    fun `an inward-wound shell inside a part is a cavity`() {
        session().use { js ->
            val (area, regions) = js.capScene(listOf(0 to outer, 0 to inner.inverted()), 7.0)
            assertEquals(520_000.0, area, 1e-6, "the cavity should be taken out of the cap")
            assertTrue(!regions.getValue(0).covers(100.0, 0.0), "the cavity was filled")
        }
    }

    /** Wound outward, the inner shell of the same part is more material, and the whole plan is filled. */
    @Test
    fun `an outward-wound shell inside the same part stays filled`() {
        session().use { js ->
            val (area, regions) = js.capScene(listOf(0 to outer, 0 to inner), 7.0)
            assertEquals(600_000.0, area, 1e-6)
            assertTrue(regions.getValue(0).covers(100.0, 0.0), "the inner shell's region was left empty")
        }
    }

    /**
     * Two layers laid against each other share a face, and neither is inside the other.
     *
     * The case a containment probe *on* the boundary would get wrong at random; the probes sit just
     * inside each loop instead.
     */
    @Test
    fun `abutting parts cap side by side`() {
        val left = boxMesh(300.0, 400.0, 100.0).moved(-150.0, 0.0, 0.0)
        val right = boxMesh(100.0, 400.0, 100.0).moved(50.0, 0.0, 0.0)
        session().use { js ->
            val (area, regions) = js.capScene(listOf(0 to left, 1 to right), 7.0)
            assertEquals(160_000.0, area, 1e-6)
            assertEquals(120_000.0, regions.getValue(0).area(), 1e-3)
            assertEquals(40_000.0, regions.getValue(1).area(), 1e-3)
        }
    }

    /** A part wound inside out still caps: its signs are flipped whole, not read as one big cavity. */
    @Test
    fun `an inside-out part still caps`() {
        session().use { js ->
            val (area, _) = js.capScene(listOf(0 to outer.inverted()), 7.0)
            assertEquals(600_000.0, area, 1e-6)
        }
    }

    /**
     * Counterfactual: fill a region with the innermost owner whatever its winding, and the cavity of
     * `an inward-wound shell inside a part is a cavity` comes back filled.
     */
    @Test
    fun `counterfactual — ignoring winding fills a cavity`() {
        val broken = session { it.replace("if (item.winding[key] > 0 && item.latest[key] > bestDepth)", "if (item.latest[key] > bestDepth)") }
        broken.use { js ->
            val (area, regions) = js.capScene(listOf(0 to outer, 0 to inner.inverted()), 7.0)
            assertTrue(area > 520_000.0 + 1.0, "ignoring winding should fill the cavity, area $area")
            assertTrue(regions.getValue(0).covers(100.0, 0.0), "ignoring winding should cover the cavity")
        }
    }

    // ---------------------------------------------------------------- against Manifold

    /**
     * The cap's area is Manifold's cross-section area, on a sphere, a torus both ways up, and a slab.
     *
     * Both cut the same triangles (`TriMesh.toSolid()` hands Manifold the drawable's buffer), so this
     * compares the capping, not the meshing.
     */
    @Test
    fun `cap areas agree with Manifold's cross-sections`() {
        if (!ManifoldLibrary.isAvailable) {
            println("ThreeSectionCapTest: skipping the Manifold comparison — ${ManifoldLibrary.describe()}")
            return
        }
        listOf(
            Triple("icosphere", icosphereMesh(3, 100.0), 55.0),
            // Lying flat, cut through as two separate tube discs; standing, cut as an annulus.
            Triple("torus flat", torusMesh(48, 24, 100.0, 30.0), 5.0),
            Triple("torus up", standingTorus(), 5.0),
            Triple("slab", boxMesh(1200.0, 800.0, 60.0), 4.0),
        ).forEach { (name, mesh, z) ->
            val viewer = session().use { it.cap(mesh, z) }
            val manifold = mesh.toSolid().use { solid -> solid.slice(z).use { it.area } }
            val error = abs(viewer.area - manifold) / manifold
            println("cap cross-check — %-10s viewer %.6f  manifold %.6f  relative %.2e".format(name, viewer.area, manifold, error))
            assertTrue(error < 1e-5, "$name: the cap's area ${viewer.area} disagrees with Manifold's $manifold")
        }
    }
}
