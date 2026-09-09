package afield.tetgen

import afield.kratos.SimulationBounds
import afield.kratos.SimulationBox
import afield.manifold.ManifoldLibrary
import afield.stdlib.isWatertight
import afield.stdlib.signedVolume
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * The views in `TetMeshViews.kt`, checked against closed forms wherever one exists.
 *
 * Every quality number `public/docs/tetgen.md` prints comes through here, so these are the
 * assertions standing behind that page. A regular tetrahedron pins both measures exactly, which is
 * worth more than a tolerance on a meshed body: a sign error or a swapped radius still produces
 * plausible numbers on a real mesh and cannot survive `1.0` and `sqrt(6)/4`.
 */
class TetMeshViewsTest {

    private fun available(): Boolean {
        if (!TetgenLibrary.isAvailable) {
            println("SKIPPED TetGen view tests: ${TetgenLibrary.describe()}")
            return false
        }
        if (!ManifoldLibrary.isAvailable) {
            println("SKIPPED TetGen view tests: ${ManifoldLibrary.describe()}")
            return false
        }
        return true
    }

    /**
     * A regular tetrahedron of edge 1, centred on the origin.
     *
     * `(±a, 0, -a/sqrt 2)` and `(0, ±a, a/sqrt 2)` with `a = 1/2`. In this corner order its signed
     * volume is *negative*, which is deliberate: it makes every test here run the winding-flip path
     * in `outwardFacesOf` rather than the one that happens to work by accident.
     */
    private fun regular(): TetMesh {
        val a = 0.5
        val z = a / sqrt(2.0)
        return TetMesh(
            doubleArrayOf(a, 0.0, -z, -a, 0.0, -z, 0.0, a, z, 0.0, -a, z),
            intArrayOf(0, 1, 2, 3),
        )
    }

    @Test fun theRegularTetrahedronPinsBothQualityMeasures() {
        val mesh = regular()
        // Edge length 1 by construction; check that before trusting anything derived from it.
        val a = mesh.positions
        val edge = sqrt((a[0] - a[3]) * (a[0] - a[3]) + (a[1] - a[4]) * (a[1] - a[4]) + (a[2] - a[5]) * (a[2] - a[5]))
        assertEquals(1.0, edge, 1e-12)
        // Radius ratio is exactly 1 for the regular tetrahedron — that is the definition of the scale.
        assertEquals(1.0, mesh.qualities()[0], 1e-12)
        // And the radius-edge ratio bottoms out at sqrt(6)/4 = 0.612372, which is the floor TetGen's
        // -q switch is measured against.
        assertEquals(sqrt(6.0) / 4.0, mesh.radiusEdgeRatios()[0], 1e-12)
        assertEquals(1.0 / (6.0 * sqrt(2.0)), mesh.volume(), 1e-15)
    }

    @Test fun aFlattenedTetrahedronReadsAsZeroRatherThanNaN() {
        // Four coplanar points: volume 0, circumradius infinite. Both measures must degrade to a
        // number a histogram can hold, because this is exactly the element a caller wants to count.
        val flat = TetMesh(
            doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0),
            intArrayOf(0, 1, 2, 3),
        )
        assertEquals(0.0, flat.qualities()[0])
        assertTrue(flat.radiusEdgeRatios()[0].let { it.isInfinite() || it > 1e6 })
    }

    @Test fun aSingleTetrahedronIsItsOwnBoundary() {
        val mesh = regular()
        val surface = mesh.boundary()
        assertEquals(4, surface.triangleCount)
        assertEquals(4, surface.vertexCount)
        // Outward winding: the four face normals must point away from the centroid, not into it.
        // A face wound inwards disappears under back-face culling, which reads as a mesher bug.
        // The centroid of a regular tetrahedron placed this way is the origin.
        fun at(v: Int, c: Int) = surface.positions[3 * v + c].toDouble()
        for (t in 0 until surface.triangleCount) {
            val i0 = surface.indices[3 * t]
            val i1 = surface.indices[3 * t + 1]
            val i2 = surface.indices[3 * t + 2]
            val ax = at(i1, 0) - at(i0, 0); val ay = at(i1, 1) - at(i0, 1); val az = at(i1, 2) - at(i0, 2)
            val bx = at(i2, 0) - at(i0, 0); val by = at(i2, 1) - at(i0, 1); val bz = at(i2, 2) - at(i0, 2)
            val nx = ay * bz - az * by
            val ny = az * bx - ax * bz
            val nz = ax * by - ay * bx
            val outward = nx * at(i0, 0) + ny * at(i0, 1) + nz * at(i0, 2)
            assertTrue(outward > 0.0, "face $t is wound inwards")
        }
    }

    @Test fun twoTetrahedraSharingAFaceHaveSixBoundaryTriangles() {
        // A bipyramid: the shared face must cancel, leaving 8 - 2 = 6.
        val mesh = TetMesh(
            doubleArrayOf(
                0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 1.0, 0.0, 0.0, -1.0,
            ),
            intArrayOf(0, 1, 2, 3, 0, 1, 2, 4),
        )
        assertEquals(6, mesh.boundary().triangleCount)
    }

    @Test fun filteringRenumbersAndDropsOrphanNodes() {
        val mesh = TetMesh(
            doubleArrayOf(
                0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 1.0, 0.0, 0.0, -1.0,
            ),
            intArrayOf(0, 1, 2, 3, 0, 1, 2, 4),
        )
        val lower = mesh.filtered { it == 1 }
        assertEquals(1, lower.cellCount)
        assertEquals(4, lower.nodeCount, "node 3 is now unreferenced and must be dropped")
        assertEquals(abs(mesh.volumes()[1]), lower.volume(), 1e-15)
        assertEquals(0, mesh.filtered { false }.cellCount)
    }

    @Test fun shrinkingSeparatesTheElementsAndScalesTheirVolume() {
        val mesh = regular()
        val whole = mesh.toTriMesh()
        assertEquals(4, whole.triangleCount)
        assertEquals(4, whole.vertexCount, "each cell owns its own four corners, shared with no other cell")
        // Shrinking about the centroid is a similarity, so volume goes as the cube of the factor.
        val shrunk = mesh.toTriMesh(shrink = 0.5)
        assertTrue(abs(abs(shrunk.signedVolume()) - mesh.volume() / 8.0) < 1e-6,
            "shrunk volume ${shrunk.signedVolume()} against ${mesh.volume() / 8.0}")
        assertFails { mesh.toTriMesh(shrink = 0.0) }
        assertFails { mesh.toTriMesh(shrink = 1.5) }
        assertFails { mesh.toTriMesh(cellColors = FloatArray(2)) }
    }

    @Test fun aMeshedCubeKeepsItsVolumeThroughEveryView() {
        if (!available()) return
        val cube = SimulationBox("c", SimulationBounds(0.0, 0.0, 0.0, 2.0, 3.0, 4.0))
        val mesh = Tetgen.tetrahedralize(cube.surface(), targetEdgeLength = 0.6)

        // The boundary of a solid mesh is closed, and its enclosed volume is the body's.
        val surface = mesh.boundary()
        assertTrue(surface.isWatertight(), "the boundary of a conforming mesh must be closed")
        assertTrue(abs(abs(surface.signedVolume()) - 24.0) / 24.0 < 1e-5,
            "boundary encloses ${surface.signedVolume()}")

        // The shell view sums to the same volume when nothing is shrunk.
        assertTrue(abs(abs(mesh.toTriMesh().signedVolume()) - 24.0) / 24.0 < 1e-5)

        // And a half-space cutaway keeps a proportional share of the cells, which is the property
        // that makes it usable as a figure rather than a coin flip.
        val centroids = mesh.centroids()
        val half = mesh.filtered { centroids[3 * it] < 1.0 }
        assertTrue(half.cellCount in (mesh.cellCount / 4)..(3 * mesh.cellCount / 4),
            "cutaway kept ${half.cellCount} of ${mesh.cellCount}")
    }

    @Test fun theRequestedQualityBoundIsHonouredByAlmostEveryElement() {
        if (!available()) return
        val cube = SimulationBox("c", SimulationBounds(0.0, 0.0, 0.0, 2.0, 3.0, 4.0))
        val mesh = Tetgen.tetrahedralize(cube.surface(), quality = 1.2, targetEdgeLength = 0.5)
        val ratios = mesh.radiusEdgeRatios()
        // No element can be better than the regular one.
        assertTrue(ratios.min() >= sqrt(6.0) / 4.0 - 1e-9, "min radius-edge ${ratios.min()}")
        // TetGen's guarantee is not absolute — see radiusEdgeRatios' KDoc — so this bounds the
        // exceptions rather than forbidding them.
        val over = ratios.count { it > 1.2 }
        assertTrue(over < mesh.cellCount / 50,
            "$over of ${mesh.cellCount} elements exceed the 1.2 bound")
        assertTrue(mesh.qualities().min() > 0.0, "no element may be degenerate")
    }
}
