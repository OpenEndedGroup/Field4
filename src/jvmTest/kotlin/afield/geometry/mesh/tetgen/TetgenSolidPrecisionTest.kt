package afield.geometry.mesh.tetgen

import afield.geometry.mesh.volume
import afield.geometry.solid.manifold.ManifoldLibrary
import afield.geometry.solid.manifold.Solid
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** `Tetgen.tetrahedralize(solid)` keeps Manifold's doubles; the `TriMesh` route narrows them to `Float`. */
class TetgenSolidPrecisionTest {
    /** `public/docs/tetgen.md`'s bracket: two blocks and three 48-segment bores, in millimetres. */
    private fun bracket(): Solid = Solid.scope {
        val foot = Solid.cube(80.0, 40.0, 15.0)
        val post = Solid.cube(15.0, 40.0, 70.0)
        val down = Solid.cylinder(40.0, 5.0, segments = 48).rotated(0.0, 0.0, 0.0)
        val holes = listOf(down.translated(55.0, 12.0, -5.0), down.translated(55.0, 28.0, -5.0))
        val through = Solid.cylinder(60.0, 6.0, segments = 48).rotated(0.0, 90.0, 0.0).translated(-20.0, 20.0, 52.0)
        ((foot + post) - Solid.union(holes + through)).copy()
    }

    @Test
    fun theSolidRouteKeepsManifoldsVolume() {
        if (!TetgenLibrary.isAvailable || !ManifoldLibrary.isAvailable) {
            println("SKIPPED: ${TetgenLibrary.describe()} / ${ManifoldLibrary.describe()}")
            return
        }
        bracket().use { part ->
            val exact = part.volume
            val surface = part.toTriMesh()
            val viaTriMesh = Tetgen.tetrahedralize(surface).volume()
            val viaSolid = Tetgen.tetrahedralize(part).volume()
            val relTriMesh = abs(viaTriMesh - exact) / exact
            val relSolid = abs(viaSolid - exact) / exact
            println("bracket: Manifold $exact, TriMesh ${surface.volume()}, TetGen via TriMesh $viaTriMesh ($relTriMesh), via Solid $viaSolid ($relSolid)")
            assertTrue(relSolid < 1e-12, "via Solid the volume is off by $relSolid relative")
            assertTrue(relTriMesh > 100 * relSolid, "the TriMesh route should be the lossy one: $relTriMesh vs $relSolid")
            val quadratic = Tetgen.tetrahedralize(part, order = 2)
            assertTrue(quadratic.nodesPerCell == 10)
        }
    }
}
