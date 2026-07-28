package afield.stdlib

import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Invariants of the processing layer (`mesh_process.kt`): normals, Loop subdivision, the two
 * smoothers and QEM decimation.
 *
 * Each of these has one property that is worth more than a dozen plausibility checks:
 *
 *  - **Subdivision** must preserve the Euler characteristic *exactly* and quadruple the triangle
 *    count, and — because the Loop limit surface lies inside the control mesh's convex hull — must
 *    move a convex shape's volume monotonically toward its limit rather than wandering.
 *  - **Smoothing**: Laplacian must shrink (that is what a diffusion does) and Taubin must not. Both
 *    claims are asserted, in both directions, on the same input, so neither can pass by accident.
 *  - **Decimation** must hit its triangle budget, keep the mesh watertight, and stay within a bounded
 *    Hausdorff-ish distance of the original. Any two of those are easy; all three together is the
 *    actual requirement.
 *  - **Crease-aware normals** must leave a smooth mesh alone and split a creased one only where the
 *    crease is — checked by vertex count *and* by the normals themselves being axis-aligned on a box.
 */
class MeshProcessTest {

    // ==========================================================================================
    // Normals
    // ==========================================================================================

    @Test
    fun vertexNormalsOfASphereAreRadial() {
        val ball = icosphereMesh(3, 2.0).withoutAttributes().withVertexNormals()
        for (v in 0 until ball.vertexCount) {
            val n = assertNotNull(ball.normal(v))
            assertEquals(1.0f, n.length(), 1e-4f, "normal $v should be unit length")
            assertTrue(
                angleBetweenDegrees(n, ball.position(v)) < 1.0,
                "normal $v should point radially out, was ${angleBetweenDegrees(n, ball.position(v))} deg off",
            )
        }
    }

    @Test
    fun flatShadedGivesOneNormalPerTriangleAndAgreesWithTheWinding() {
        val cube = boxMesh().flatShaded()
        assertEquals(36, cube.vertexCount, "12 triangles, 3 unshared vertices each")
        assertEquals(12, cube.triangleCount)
        for (t in 0 until cube.triangleCount) {
            val face = cube.faceNormal(t)
            for (k in 0..2) {
                val n = assertNotNull(cube.normal(cube.indices[t * 3 + k]))
                assertEquals(0.0, angleBetweenDegrees(n, face), 1e-2, "vertex normal must equal the face normal")
            }
        }
    }

    @Test
    fun creaseAwareNormalsSplitABoxAndLeaveASphereAlone() {
        val cube = boxMesh().shadedNormals(35.0)
        assertEquals(24, cube.vertexCount, "each of the 8 corners splits into its 3 faces")
        assertEquals(12, cube.triangleCount)
        for (v in 0 until cube.vertexCount) {
            val n = assertNotNull(cube.normal(v))
            // A cube's face normals are axis-aligned, so exactly one component must be +-1.
            val components = listOf(n.x, n.y, n.z).map { abs(it) }.sortedDescending()
            assertEquals(1.0f, components[0], 1e-4f, "a box normal should be axis aligned, got $n")
            assertEquals(0.0f, components[1], 1e-4f, "a box normal should be axis aligned, got $n")
        }

        val ball = icosphereMesh(2).withoutAttributes()
        val shaded = ball.shadedNormals(60.0)
        assertEquals(ball.vertexCount, shaded.vertexCount, "a smooth mesh must not be split at 60 deg")

        // A cylinder is the interesting case: smooth around, hard at the rim.
        val tube = cylinderMesh(32, caps = true).shadedNormals(35.0)
        assertTrue(tube.vertexCount > cylinderMesh(32).vertexCount, "the rim vertices must split")
        val sideNormals = (0 until tube.vertexCount).mapNotNull { tube.normal(it) }.filter { abs(it.y) < 0.01f }
        assertTrue(sideNormals.size >= 32, "the side should keep smooth radial normals, found ${sideNormals.size}")
    }

    // ==========================================================================================
    // Subdivision
    // ==========================================================================================

    @Test
    fun loopSubdivisionQuadruplesTheTrianglesAndPreservesTheTopology() {
        for (seed in listOf(icosahedronMesh(), torusMesh(8, 6), boxMesh())) {
            val before = seed.topology()
            val after = seed.loopSubdivide(1)
            assertEquals(seed.triangleCount * 4, after.triangleCount, "4x triangles; ${seed.summary()}")
            assertEquals(
                before.eulerCharacteristic, after.topology().eulerCharacteristic,
                "subdivision must preserve chi; ${seed.summary()}",
            )
            assertEquals(before.genus, after.topology().genus, "and the genus")
            assertTrue(after.isWatertight(), "and watertightness; ${after.summary()}")
            assertTrue(after.signedVolume() > 0.0, "and the outward winding")
        }
    }

    @Test
    fun loopSubdivisionOfAnOpenPatchKeepsItsBorder() {
        val patch = planeMesh(3, 3)
        val fine = patch.loopSubdivide(2)
        assertEquals(1, fine.topology().eulerCharacteristic, "still a disc")
        assertEquals(1, fine.topology().boundaryLoops.size)
        val before = patch.bounds()
        val after = fine.bounds()
        // The boundary rules keep the border curve where it was, so the patch must not shrink inward.
        assertEquals(before.min.x, after.min.x, 1e-5f)
        assertEquals(before.max.y, after.max.y, 1e-5f)
    }

    @Test
    fun subdividingAnIcosahedronConvergesGeometricallyOnItsLimitSurface() {
        // Loop subdivision converges on the *Loop limit surface*, not on the circumscribing sphere —
        // the control mesh's corners are pulled in toward the average of their neighbours, so the volume
        // falls. What must be true is that it falls monotonically and that the steps shrink
        // geometrically, which is what "converges" means and what a broken weight would break.
        val report = mutableListOf<String>()
        var mesh = icosahedronMesh(1.0)
        val volumes = mutableListOf(mesh.signedVolume())
        repeat(4) {
            mesh = mesh.loopSubdivide(1)
            volumes.add(mesh.signedVolume())
            report.add("tris=${mesh.triangleCount} volume=${"%.5f".format(volumes.last())}")
        }
        val deltas = (1 until volumes.size).map { volumes[it] - volumes[it - 1] }
        assertTrue(deltas.all { it < 0.0 }, "the volume must fall monotonically: $report")
        for (i in 1 until deltas.size) {
            assertTrue(
                abs(deltas[i]) < abs(deltas[i - 1]) * 0.6,
                "step $i (${deltas[i]}) must be well under step ${i - 1} (${deltas[i - 1]}): $report",
            )
        }
        // And the limit surface is a rounded icosahedron, safely inside the ball its corners sit on.
        assertTrue(volumes.last() > 0.0 && volumes.last() < 4.0 / 3.0 * PI, "$report")
        println("loopSubdivide convergence: ${report.joinToString(" | ")} deltas=$deltas")
    }

    // ==========================================================================================
    // Smoothing
    // ==========================================================================================

    /** An icosphere with its vertices pushed radially by deterministic noise. */
    private fun noisySphere(amplitude: Float = 0.08f, seed: Long = 7L): TriMesh {
        val random = Random(seed)
        return icosphereMesh(3, 1.0).transformed { p ->
            val s = 1f + (random.nextFloat() - 0.5f) * 2f * amplitude
            Vec3(p.x * s, p.y * s, p.z * s)
        }
    }

    @Test
    fun laplacianShrinksAndTaubinDoesNot() {
        val noisy = noisySphere()
        val start = noisy.signedVolume()
        val laplacian = noisy.laplacianSmooth(iterations = 30, lambda = 0.6)
        val taubin = noisy.taubinSmooth(iterations = 30, lambda = 0.6, mu = -0.63)
        assertTrue(
            laplacian.signedVolume() < start * 0.9,
            "30 Laplacian passes should visibly deflate the ball: $start -> ${laplacian.signedVolume()}",
        )
        assertEquals(
            start, taubin.signedVolume(), start * 0.03,
            "Taubin should hold the volume: $start -> ${taubin.signedVolume()}",
        )
        println(
            "smoothing volumes: noisy=${"%.5f".format(start)} " +
                "laplacian=${"%.5f".format(laplacian.signedVolume())} taubin=${"%.5f".format(taubin.signedVolume())}"
        )
    }

    @Test
    fun taubinSmoothingMovesANoisySphereCloserToTheCleanOne() {
        val clean = icosphereMesh(3, 1.0)
        val noisy = noisySphere()
        fun radialSpread(mesh: TriMesh): Double {
            var worst = 0.0
            for (v in 0 until mesh.vertexCount) worst = kotlin.math.max(worst, abs(mesh.position(v).length() - 1f).toDouble())
            return worst
        }
        val before = radialSpread(noisy)
        val after = radialSpread(noisy.taubinSmooth(iterations = 25, lambda = 0.6, mu = -0.63))
        assertTrue(before > 0.05, "the noise should be real to start with: $before")
        assertTrue(after < before * 0.6, "smoothing should cut the radial spread: $before -> $after")
        assertTrue(clean.isWatertight())
    }

    @Test
    fun smoothingPreservesTopologyAndBoundaries() {
        val patch = planeMesh(8, 8).transformed { p -> Vec3(p.x, p.y, (Random(p.x.toRawBits().toLong()).nextFloat() - 0.5f) * 0.1f) }
        val smoothed = patch.taubinSmooth(iterations = 10, preserveBoundary = true)
        assertEquals(patch.topology().eulerCharacteristic, smoothed.topology().eulerCharacteristic)
        assertEquals(patch.bounds().min.x, smoothed.bounds().min.x, 1e-5f, "the border must not move")
        assertEquals(patch.bounds().max.x, smoothed.bounds().max.x, 1e-5f)
    }

    @Test
    fun cotangentWeightedSmoothingWorksOnAnIrregularMesh() {
        // A mesh with wildly varying triangle sizes: uniform weights are biased here, cotangent ones
        // are not, but both must remain stable and preserve the topology.
        val irregular = uvSphereMesh(48, 10, 1.0)
        val uniform = irregular.taubinSmooth(iterations = 15, cotangent = false)
        val cotangent = irregular.taubinSmooth(iterations = 15, cotangent = true)
        for (mesh in listOf(uniform, cotangent)) {
            assertTrue(mesh.isFinite(), "cotangent weights must not produce NaN")
            assertTrue(mesh.isWatertight())
            assertEquals(2, mesh.topology().eulerCharacteristic)
            assertEquals(irregular.signedVolume(), mesh.signedVolume(), irregular.signedVolume() * 0.05)
        }
    }

    // ==========================================================================================
    // Decimation
    // ==========================================================================================

    @Test
    fun decimationHitsItsBudgetStaysClosedAndStaysCloseToTheOriginal() {
        val fine = icosphereMesh(4, 1.0) // 5120 triangles
        assertEquals(5120, fine.triangleCount)
        val report = mutableListOf<String>()
        for (target in listOf(2000, 800, 200)) {
            val coarse = fine.decimate(target)
            val deviation = maxDeviation(fine, coarse)
            report.add("target=$target got=${coarse.triangleCount} deviation=${"%.5f".format(deviation)}")
            assertTrue(coarse.triangleCount <= target, "must not exceed the budget: $report")
            assertTrue(coarse.triangleCount >= target - 8, "and should get there: $report")
            assertTrue(coarse.isWatertight(), "decimation must keep the mesh closed: ${coarse.summary()}")
            assertEquals(2, coarse.topology().eulerCharacteristic, coarse.summary())
            assertTrue(coarse.signedVolume() > 0.0, "and outward")
            // A QEM decimation of a sphere to 200 triangles is still recognisably a unit sphere.
            assertTrue(deviation < 0.06, "deviation too large: $report")
        }
        println("decimate: ${report.joinToString(" | ")}")
    }

    @Test
    fun decimationLeavesAFlatSurfaceAlmostFreeButKeepsItsSilhouette() {
        // A flat grid has zero quadric error everywhere in its interior, so a QEM pass should collapse
        // it to (nearly) nothing while the border, which carries the boundary planes, survives.
        val flat = planeMesh(12, 12)
        assertEquals(288, flat.triangleCount)
        val coarse = flat.decimate(60)
        assertTrue(coarse.triangleCount <= 60)
        assertEquals(1, coarse.topology().eulerCharacteristic, "still a disc; ${coarse.summary()}")
        assertEquals(flat.surfaceArea(), coarse.surfaceArea(), 1e-4, "a flat decimation loses no area")
        assertEquals(flat.bounds().min.x, coarse.bounds().min.x, 1e-5f, "the border must survive")
        assertEquals(flat.bounds().max.y, coarse.bounds().max.y, 1e-5f)
    }

    @Test
    fun decimationAtOrAboveTheCurrentCountIsANoOp() {
        val mesh = icosphereMesh(2)
        assertTrue(mesh.decimate(mesh.triangleCount) === mesh)
        assertTrue(mesh.decimate(mesh.triangleCount * 4) === mesh)
    }

    @Test
    fun decimationRespectsAMaxErrorBudget() {
        val fine = icosphereMesh(4, 1.0)
        // A tiny error budget must stop the collapse early rather than hit the target regardless.
        val guarded = fine.decimate(50, maxError = 1e-9)
        assertTrue(guarded.triangleCount > 50, "a 1e-9 error budget cannot reach 50 triangles from 5120")
        assertTrue(guarded.isWatertight())
    }

    // ==========================================================================================
    // Quality
    // ==========================================================================================

    @Test
    fun icosphereTrianglesAreNearEquilateralAndUvSphereOnesAreNot() {
        val ico = icosphereMesh(3).quality()
        val uv = uvSphereMesh(64, 8).quality()
        assertTrue(ico.minAngleDegrees > 40.0, "an icosphere should have no sliver: $ico")
        assertTrue(ico.meanAspectRatio < 1.15, "and near-equilateral triangles: $ico")
        assertTrue(uv.minAngleDegrees < ico.minAngleDegrees, "a 64x8 UV sphere has slivers at its poles: $uv")
        assertEquals(0, ico.degenerateCount)
        println("quality: icosphere=$ico  uvSphere=$uv")
    }

    @Test
    fun subdivisionImprovesNothingAboutAngleButDecimationOfASphereStaysReasonable() {
        // Loop subdivision of a regular mesh keeps its angle distribution; that is a property worth
        // pinning, since a scheme that degraded it would be quietly producing slivers.
        val before = icosphereMesh(2).quality()
        val after = icosphereMesh(2).loopSubdivide(2).quality()
        assertTrue(after.minAngleDegrees > before.minAngleDegrees - 5.0, "before=$before after=$after")
        val decimated = icosphereMesh(4).decimate(600).quality()
        assertTrue(decimated.minAngleDegrees > 5.0, "QEM should not leave knife-edge triangles: $decimated")
    }
}
