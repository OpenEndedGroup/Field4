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

    /**
     * The order the input solid was *built* in changes the mesh, and `public/docs/tetgen.md` says so.
     *
     * Found on 2026-09-09 by a docs capture reporting 66,108 elements where every test reported
     * 66,489 — the two fences on that page had written the same bracket's Booleans in a different
     * order. Manifold hands back bit-identical vertex *positions* either way and a different
     * *index* order; `field_tetgen.cpp` makes one facet per triangle in the order given, and
     * Delaunay refinement depends on insertion order.
     *
     * Asserted as an inequality on the counts and an equality on the volume, because that pair is
     * the whole point: the two meshes are different and both are right.
     */
    @Test
    fun theOrderTheSolidWasBuiltInChangesTheMeshButNotTheBody() {
        if (!available()) return
        fun bolts() = listOf(12.0, 28.0).map {
            afield.manifold.Solid.cylinder(40.0, 5.0, segments = 48).translated(55.0, it, -5.0)
        }
        fun eye() = afield.manifold.Solid.cylinder(60.0, 6.0, segments = 48)
            .rotated(0.0, 90.0, 0.0).translated(-20.0, 20.0, 52.0)

        val blocksFirst = afield.manifold.Solid.scope {
            val foot = afield.manifold.Solid.cube(80.0, 40.0, 15.0)
            val post = afield.manifold.Solid.cube(15.0, 40.0, 70.0)
            ((foot + post) - afield.manifold.Solid.union(bolts() + eye())).copy()
        }
        val holesFirst = afield.manifold.Solid.scope {
            val holes = afield.manifold.Solid.union(bolts() + eye())
            ((afield.manifold.Solid.cube(80.0, 40.0, 15.0) +
                afield.manifold.Solid.cube(15.0, 40.0, 70.0)) - holes).copy()
        }
        val a = blocksFirst.toTriMesh()
        val b = holesFirst.toTriMesh()

        assertEquals(blocksFirst.volume, holesFirst.volume, 0.0, "the same body, exactly")
        assertTrue(a.positions.contentEquals(b.positions),
            "Manifold's vertex positions are expected to be bit-identical either way")
        assertTrue(!a.indices.contentEquals(b.indices),
            "if the index orders ever agree, this page's warning is obsolete — delete it")

        val meshA = Tetgen.tetrahedralize(a, targetEdgeLength = 3.0)
        val meshB = Tetgen.tetrahedralize(b, targetEdgeLength = 3.0)
        assertTrue(meshA.cellCount != meshB.cellCount,
            "expected different meshes, both got ${meshA.cellCount}")
        assertTrue(abs(meshA.volume() - meshB.volume()) / meshA.volume() < 1e-9,
            "and yet the same body: ${meshA.volume()} against ${meshB.volume()}")
        println("blocks first ${meshA.cellCount} elements, holes first ${meshB.cellCount}, " +
            "differing by ${"%.2f".format(100.0 * abs(meshA.cellCount - meshB.cellCount) / meshA.cellCount)}%")
        blocksFirst.close(); holesFirst.close()
    }

    /**
     * A 10-node mesh measures and draws exactly as the 4-node mesh it was promoted from.
     *
     * `TetMesh.quadratic()` (2026-09-09, `ad7e97b43`) made `nodesPerCell` a variable, and every view
     * in `TetMeshViews.kt` predates it. Reading a 10-node mesh at stride 4 is the dangerous kind of
     * wrong: `cellCount` is right, every index landed on is a real node, nothing throws, and the
     * histogram and the picture are quietly garbage. So this compares view for view rather than
     * asserting a plausible range.
     */
    @Test
    fun everyViewSeesTheSameBodyBeforeAndAfterPromotionToTenNodes() {
        val linear = TetMesh(
            doubleArrayOf(
                0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 1.0, 0.0, 0.0, -1.0,
            ),
            intArrayOf(0, 1, 2, 3, 0, 1, 2, 4),
        )
        val quadratic = linear.quadratic()
        assertEquals(10, quadratic.nodesPerCell)
        assertEquals(2, quadratic.cellCount)
        // The promotion adds nodes, so a stride-4 read would not even fail on bounds.
        assertTrue(quadratic.nodeCount > linear.nodeCount)

        assertTrue(linear.qualities().contentEquals(quadratic.qualities()), "radius ratios differ")
        assertTrue(linear.radiusEdgeRatios().contentEquals(quadratic.radiusEdgeRatios()))
        assertTrue(linear.centroids().contentEquals(quadratic.centroids()))
        assertEquals(linear.volume(), quadratic.volume(), 0.0)

        assertEquals(linear.boundary().triangleCount, quadratic.boundary().triangleCount)
        assertEquals(
            abs(linear.boundary().signedVolume()),
            abs(quadratic.boundary().signedVolume()),
            1e-12,
        )
        assertTrue(linear.toTriMesh(shrink = 0.8).positions
            .contentEquals(quadratic.toTriMesh(shrink = 0.8).positions))

        // A cutaway of a quadratic mesh drops back to 4 nodes, because it is a picture.
        val half = quadratic.filtered { it == 0 }
        assertEquals(4, half.nodesPerCell)
        assertEquals(1, half.cellCount)
        assertEquals(abs(linear.volumes()[0]), half.volume(), 1e-15)
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
