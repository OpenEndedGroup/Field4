package afield.stdlib

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Invariants of the SDF layer and the marcher (`mesh_sdf.kt`).
 *
 * The two claims worth testing are the two the file makes:
 *
 *  1. **The primitives are real signed distance functions.** Checked against analytically known
 *     distances at points inside, outside and on each surface — not just at the surface, since a
 *     wrong interior formula is invisible to a marcher and fatal to a smooth-union.
 *  2. **The marcher is watertight and converges.** `volume(marchingCubes(sdfSphere(1)))` must approach
 *     `4/3 π` *and the error must fall monotonically with resolution*. Convergence is the part a
 *     single-resolution test cannot see, and it is what distinguishes a correct marcher from one that
 *     happens to be close at one grid size. The topology assertions (`χ = 2` for a sphere, `0` for a
 *     torus) are the other half: they are exactly what a marcher with an inconsistent ambiguous-face
 *     rule fails, because its leaks show up as boundary edges.
 */
class MeshMarchingTest {

    private val unitBounds = boundsAround(radius = 1.6)

    // ==========================================================================================
    // The primitives really are distance functions
    // ==========================================================================================

    @Test
    fun sphereDistanceIsExactInsideAndOutside() {
        val f = sdfSphere(2.0)
        assertEquals(-2.0f, f(Vec3(0f, 0f, 0f)), 1e-6f, "centre is radius-deep inside")
        assertEquals(0.0f, f(Vec3(2f, 0f, 0f)), 1e-6f, "on the surface")
        assertEquals(1.0f, f(Vec3(3f, 0f, 0f)), 1e-6f, "one unit outside")
        assertEquals(-1.0f, f(Vec3(1f, 0f, 0f)), 1e-6f, "one unit inside")
        // Off-axis, and offset.
        val g = sdfSphere(1.0, Vec3(5f, 0f, 0f))
        assertEquals(4.0f, g(Vec3(0f, 0f, 0f)), 1e-6f)
    }

    @Test
    fun boxDistanceIsExactOnFacesEdgesCornersAndInside() {
        val f = sdfBox(Vec3(2f, 2f, 2f)) // half-extents 1
        assertEquals(-1.0f, f(Vec3(0f, 0f, 0f)), 1e-6f, "centre is one unit from the nearest face")
        assertEquals(0.0f, f(Vec3(1f, 0f, 0f)), 1e-6f, "face centre")
        assertEquals(1.0f, f(Vec3(2f, 0f, 0f)), 1e-6f, "straight out from a face")
        assertEquals(kotlin.math.sqrt(2.0).toFloat(), f(Vec3(2f, 2f, 0f)), 1e-5f, "diagonally out from an edge")
        assertEquals(kotlin.math.sqrt(3.0).toFloat(), f(Vec3(2f, 2f, 2f)), 1e-5f, "out from a corner")
        assertEquals(-0.5f, f(Vec3(0.5f, 0f, 0f)), 1e-6f, "half a unit inside")
    }

    @Test
    fun torusAndCapsuleAndPlaneDistancesAreExact() {
        val torus = sdfTorus(2.0, 0.5)
        assertEquals(0.0f, torus(Vec3(2.5f, 0f, 0f)), 1e-6f, "on the outer equator")
        assertEquals(0.0f, torus(Vec3(1.5f, 0f, 0f)), 1e-6f, "on the inner equator")
        assertEquals(0.0f, torus(Vec3(2f, 0.5f, 0f)), 1e-6f, "on the top of the tube")
        assertEquals(1.5f, torus(Vec3(0f, 0f, 0f)), 1e-6f, "the hole's centre is 1.5 from the tube")

        val capsule = sdfCapsule(Vec3(-1f, 0f, 0f), Vec3(1f, 0f, 0f), 0.5)
        assertEquals(-0.5f, capsule(Vec3(0f, 0f, 0f)), 1e-6f)
        assertEquals(0.0f, capsule(Vec3(1.5f, 0f, 0f)), 1e-6f, "the end cap is spherical")
        assertEquals(0.5f, capsule(Vec3(0f, 1f, 0f)), 1e-6f)

        val plane = sdfPlane(Vec3(0f, 1f, 0f), 2.0)
        assertEquals(-2.0f, plane(Vec3(0f, 0f, 0f)), 1e-6f)
        assertEquals(1.0f, plane(Vec3(9f, 3f, -4f)), 1e-6f, "distance depends only on the normal direction")
    }

    @Test
    fun transformCombinatorsMoveTheShapeAndKeepTheMetric() {
        val base = sdfSphere(1.0)
        val moved = base.translated(Vec3(3f, 0f, 0f))
        assertEquals(-1.0f, moved(Vec3(3f, 0f, 0f)), 1e-6f)
        assertEquals(1.0f, moved(Vec3(5f, 0f, 0f)), 1e-6f)
        val big = base.scaled(2.0)
        assertEquals(0.0f, big(Vec3(2f, 0f, 0f)), 1e-5f, "scaling by 2 doubles the radius")
        assertEquals(1.0f, big(Vec3(3f, 0f, 0f)), 1e-5f, "and the distances stay true distances")
        val turned = sdfBox(Vec3(4f, 1f, 1f)).rotated(quat(PI / 2, Vec3(0f, 1f, 0f)))
        // A long x-box rotated 90 degrees about y becomes a long z-box.
        assertTrue(turned(Vec3(0f, 0f, 1.8f)) < 0f, "the long axis should now be z; got ${turned(Vec3(0f, 0f, 1.8f))}")
        assertTrue(turned(Vec3(1.8f, 0f, 0f)) > 0f, "and x should now be short")
    }

    @Test
    fun booleanCombinatorsBehaveLikeSetOperations() {
        val a = sdfSphere(1.0, Vec3(-0.5f, 0f, 0f))
        val b = sdfSphere(1.0, Vec3(0.5f, 0f, 0f))
        val union = sdfUnion(a, b)
        val inter = sdfIntersect(a, b)
        val diff = sdfSubtract(a, b)
        val onlyInA = Vec3(-1.2f, 0f, 0f)
        val inBoth = Vec3(0f, 0f, 0f)
        val onlyInB = Vec3(1.2f, 0f, 0f)
        assertTrue(union(onlyInA) < 0f && union(inBoth) < 0f && union(onlyInB) < 0f)
        assertTrue(inter(onlyInA) > 0f && inter(inBoth) < 0f && inter(onlyInB) > 0f)
        assertTrue(diff(onlyInA) < 0f && diff(inBoth) > 0f && diff(onlyInB) > 0f)
        // A smooth union is never outside the sharp one (the fillet only adds material).
        val smooth = sdfSmoothUnion(a, b, 0.3)
        for (p in listOf(onlyInA, inBoth, onlyInB, Vec3(0f, 0.9f, 0f), Vec3(0.3f, 0.6f, 0.2f))) {
            assertTrue(smooth(p) <= union(p) + 1e-5f, "the fillet must not remove material at $p")
        }
    }

    @Test
    fun gradientOfAnExactSdfIsTheUnitOutwardNormal() {
        val f = sdfSphere(1.0)
        for (p in listOf(Vec3(1f, 0f, 0f), Vec3(0f, -1f, 0f), Vec3(0.577f, 0.577f, 0.577f))) {
            val n = f.normalAt(p)
            assertEquals(1.0f, n.length(), 1e-3f)
            assertEquals(0.0, angleBetweenDegrees(n, p), 1.0, "the normal should point radially out at $p")
        }
    }

    // ==========================================================================================
    // The marcher
    // ==========================================================================================

    @Test
    fun marchedSphereIsWatertightAndItsVolumeConvergesOnFourThirdsPi() {
        val exact = 4.0 / 3.0 * PI
        val report = mutableListOf<String>()
        var previousError = Double.MAX_VALUE
        for (resolution in listOf(16, 32, 64)) {
            val mesh = marchingCubes(sdfSphere(1.0), unitBounds, resolution)
            val topo = mesh.topology()
            assertTrue(topo.isManifold, "res $resolution should be manifold; $topo")
            assertTrue(topo.isWatertight, "res $resolution should be watertight; $topo")
            assertEquals(2, topo.eulerCharacteristic, "res $resolution chi; $topo")
            assertTrue(mesh.signedVolume() > 0.0, "res $resolution should be wound outward")
            val error = abs(mesh.signedVolume() - exact) / exact
            report.add("res=$resolution tris=${mesh.triangleCount} volume=${"%.6f".format(mesh.signedVolume())} relErr=${"%.5f".format(error)}")
            assertTrue(error < previousError, "the volume error must fall with resolution: $report")
            previousError = error
        }
        assertTrue(previousError < 2e-3, "res 64 volume error was $previousError; $report")
        println("marchingCubes sphere convergence: ${report.joinToString(" | ")}")
    }

    @Test
    fun marchedSphereVerticesLieOnTheAnalyticSurfaceAndItsNormalsPointOut() {
        val mesh = marchingCubes(sdfSphere(1.0), unitBounds, 48)
        var worst = 0.0
        for (v in 0 until mesh.vertexCount) {
            val p = mesh.position(v)
            worst = kotlin.math.max(worst, abs(p.length() - 1f).toDouble())
            val n = mesh.normal(v)
            if (n != null) assertTrue(n.dot(p) > 0f, "vertex $v normal points inward")
        }
        // Linear interpolation of an exact distance along a grid edge is second-order accurate, so a
        // marched vertex sits very close to the true sphere rather than merely inside the cell.
        assertTrue(worst < 0.005, "worst radial deviation was $worst")
    }

    @Test
    fun marchedBoxRecoversItsExactVolume() {
        // Sides deliberately off the grid lines, so no crossing lands exactly on a sample.
        val mesh = marchingCubes(sdfBox(Vec3(1.3f, 0.9f, 1.1f)), boundsAround(radius = 1.2), 96)
        assertTrue(mesh.isWatertight(), "marched box should be watertight; ${mesh.summary()}")
        assertEquals(1.3 * 0.9 * 1.1, mesh.signedVolume(), 0.02, mesh.summary())
    }

    @Test
    fun marchedTorusIsGenusOne() {
        val mesh = marchingCubes(sdfTorus(1.0, 0.35), boundsAround(radius = 1.5), 64)
        val topo = mesh.topology()
        assertTrue(topo.isWatertight, "marched torus should be watertight; $topo")
        assertEquals(0, topo.eulerCharacteristic, "a torus has chi = 0; $topo")
        assertEquals(1, topo.genus)
        assertEquals(2.0 * PI * PI * 1.0 * 0.35 * 0.35, mesh.signedVolume(), 0.05, mesh.summary())
    }

    @Test
    fun marchedCsgSolidIsStillWatertightAndItsVolumeIsTheDifference() {
        val body = sdfSphere(1.0)
        val drill = sdfCylinder(0.3, 4.0)
        val mesh = marchingCubes(sdfSubtract(body, drill), unitBounds, 80)
        val topo = mesh.topology()
        assertTrue(topo.isWatertight, "a drilled sphere should still be closed; $topo")
        assertEquals(1, topo.genus, "drilling a hole right through adds one handle; $topo")
        // The removed plug is the intersection of the y-axis cylinder with the unit sphere. Its
        // cross-section at height y is a disc of radius min(r, sqrt(1 - y^2)), so integrate that
        // rather than reaching for a closed form that only holds away from the caps.
        val r = 0.3
        var plug = 0.0
        val steps = 200_000
        for (i in 0 until steps) {
            val y = -1.0 + 2.0 * (i + 0.5) / steps
            val rr = kotlin.math.min(r, kotlin.math.sqrt(1.0 - y * y))
            plug += PI * rr * rr * (2.0 / steps)
        }
        assertEquals(4.0 / 3.0 * PI - plug, mesh.signedVolume(), 0.03, mesh.summary())
    }

    @Test
    fun theGyroidComesOutAsOneHighGenusSheet() {
        // A triply-periodic minimal surface. It is cut by the march window, so it is open, but it must
        // be a manifold with a strongly negative Euler characteristic — many handles.
        val mesh = marchingCubes(sdfGyroid(3.0, 0.25), boundsAround(radius = 1.0), 72)
        val topo = mesh.topology()
        assertTrue(mesh.triangleCount > 5000, "the gyroid should be a substantial surface; ${mesh.summary()}")
        assertTrue(topo.isManifold, "the gyroid should still be manifold; $topo")
        assertTrue(topo.eulerCharacteristic < 0, "a gyroid has many handles; chi was ${topo.eulerCharacteristic}")
        assertTrue(mesh.isFinite())
        println("gyroid: ${mesh.summary()}")
    }

    @Test
    fun metaballsMergeIntoOneComponentAsTheyApproach() {
        fun blobs(separation: Float): TriMesh = marchingCubes(
            sdfMetaballs(listOf(Vec3(-separation, 0f, 0f), Vec3(separation, 0f, 0f)), radius = 1.0, iso = 0.5),
            boundsAround(radius = 2.0), 64,
        )
        val apart = blobs(0.95f)
        val together = blobs(0.35f)
        assertEquals(2, apart.topology().componentCount, "far apart, two blobs; ${apart.summary()}")
        assertEquals(1, together.topology().componentCount, "close together, one; ${together.summary()}")
        assertTrue(apart.isWatertight() && together.isWatertight())
    }

    @Test
    fun anEmptyFieldMarchesToAnEmptyMesh() {
        val nothing = marchingCubes({ _ -> 1f }, unitBounds, 8)
        assertTrue(nothing.isEmpty)
        assertEquals(0, nothing.triangleCount)
        val everything = marchingCubes({ _ -> -1f }, unitBounds, 8)
        assertTrue(everything.isEmpty, "a field with no sign change has no surface")
    }

    @Test
    fun densityFieldsAreMarchedOutwardWhenPositiveOutsideIsFalse() {
        // A density blob: inside is the LARGER value, the opposite of the SDF convention.
        val density: ScalarField3D = { p -> 1f - p.length() }
        val wrong = marchingCubes(density, unitBounds, 32, iso = 0.0, positiveOutside = true)
        val right = marchingCubes(density, unitBounds, 32, iso = 0.0, positiveOutside = false)
        assertTrue(wrong.signedVolume() < 0.0, "with the wrong convention it comes out inside-out")
        assertTrue(right.signedVolume() > 0.0, "with the right one it comes out outward")
        assertEquals(4.0 / 3.0 * PI, right.signedVolume(), 0.03)
    }

    @Test
    fun theBvhOfAMarchedMeshReproducesTheFieldItWasMarchedFrom() {
        // Round trip: SDF -> mesh -> BVH signed distance. They must agree away from the surface.
        val field = sdfSmoothUnion(sdfSphere(0.8), sdfBox(Vec3(1.6f, 0.4f, 0.4f)), 0.25)
        val mesh = marchingCubes(field, boundsAround(radius = 1.4), 72)
        assertTrue(mesh.isWatertight(), mesh.summary())
        val tree = mesh.bvh()
        val probes = listOf(
            Vec3(0f, 0f, 0f), Vec3(1.2f, 0f, 0f), Vec3(0f, 1.2f, 0f), Vec3(0.6f, 0.6f, 0.6f), Vec3(-1.1f, 0.2f, 0.1f),
        )
        for (p in probes) {
            val fromMesh = tree.signedDistance(p)
            val fromField = field(p).toDouble()
            assertTrue(
                (fromMesh < 0.0) == (fromField < 0.0),
                "inside/outside disagreed at $p: mesh $fromMesh vs field $fromField",
            )
            // A smooth-union is not an exact distance function, so only the sign and a loose magnitude
            // can be asserted; 0.08 is well inside the fillet's own distortion.
            assertEquals(fromField, fromMesh, 0.1, "signed distance at $p")
        }
    }
}
