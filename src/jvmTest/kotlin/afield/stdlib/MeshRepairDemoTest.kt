package afield.stdlib

import afield.manifold.ManifoldLibrary
import afield.manifold.Solid
import afield.manifold.isValidSolid
import afield.manifold.toSolid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The exact geometry the loadable demo under `public/docs/assets/mesh_repair_demo/` builds.
 *
 * A demo document that does not do what its caption says is worse than no demo, and nothing in the
 * build compiles a box's code. So the shape, the defects it is supposed to exhibit, and the boolean
 * at the end are asserted here — if this test goes red, the demo is lying.
 */
class MeshRepairDemoTest {

    /**
     * Exactly what the demo's first box builds: an icosphere with four faces removed, whose triangle
     * 0 has also been given its own three vertices — one open hole plus the crack an STL export leaves.
     *
     * An earlier version duplicated a face instead of cracking one, and it taught something worth
     * keeping: on a one-ring cylinder every side triangle touches a rim, so the duplicate turned that
     * rim edge into a three-face edge, which stopped being a *boundary* edge, which broke the rim's
     * ring walk — the mesh then reported one open loop where there were two. Defects interfere with
     * the detection of other defects, which is why the diagnosis is ordered causally and why the
     * repair pipeline re-diagnoses at the end rather than trusting its first look.
     */
    private fun demoMesh(): TriMesh {
        val holed = icosphereMesh(3, 1.0).filterTriangles { it >= 4 }
        val pos = holed.positions.copyOf(holed.positions.size + 9)
        val idx = holed.indices.copyOf()
        for (k in 0..2) {
            val v = holed.indices[k]
            val d = (holed.vertexCount + k) * 3
            pos[d] = holed.x(v); pos[d + 1] = holed.y(v); pos[d + 2] = holed.z(v)
            idx[k] = holed.vertexCount + k
        }
        return TriMesh(pos, idx)
    }

    @Test
    fun theDemoMeshHasTheDefectsItAdvertises() {
        val broken = demoMesh()
        val d = broken.diagnose()
        println("demo mesh diagnosis:\n${d.report()}")
        val kinds = d.defects.map { it.kind }
        assertTrue(MeshDefectKind.OPEN_BOUNDARY in kinds, "the demo promises an open hole, got $kinds")
        assertTrue(MeshDefectKind.UNWELDED_VERTICES in kinds, "and an unwelded crack, got $kinds")
        assertTrue(d.blockers.size >= 2, "both are blocking, got ${d.blockers.map { it.kind }}")
    }

    @Test
    fun theDemoRepairsAndTheCutIsTheNumberInTheCaption() {
        if (!ManifoldLibrary.isAvailable) { println("MeshRepairDemoTest: no Manifold, skipping"); return }
        val broken = demoMesh()
        assertTrue(!broken.isValidSolid(), "the demo's premise is that this mesh is refused")

        val result = broken.repairToSolid()
        assertTrue(result.isSolid, "the demo repairs it:\n${result.report()}")

        // Four faces were removed and capped flat, so the repaired volume is the icosphere's less
        // the slivers under those caps — close, and close is the honest claim rather than exact.
        val whole = icosphereMesh(3, 1.0).signedVolume()
        result.mesh.toSolid().use { solid ->
            assertEquals(whole, solid.volume, whole * 0.01, "the cap restores the sphere to within 1%")
            assertTrue(solid.volume > 0.0, "and positive, i.e. not inside out")
            Solid.cube(4.0, 4.0, 4.0, center = true).translated(0.0, 0.0, 2.0).use { knife ->
                (solid - knife).use { lower ->
                    assertTrue(lower.volume > 0.0, "the cut must leave something")
                    assertTrue(lower.volume < solid.volume, "and must remove something")
                    assertEquals(
                        solid.volume / 2.0, lower.volume, whole * 0.02,
                        "cutting a sphere at its equator leaves half of it",
                    )
                    println("demo: repaired volume ${solid.volume} (whole sphere $whole), after the cut ${lower.volume}")
                }
            }
        }
    }
}
