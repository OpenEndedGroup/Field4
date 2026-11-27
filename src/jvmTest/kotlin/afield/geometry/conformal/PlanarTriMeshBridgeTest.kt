package afield.geometry.conformal

import afield.geometry.mesh.cotangentLaplacianMatrix
import afield.geometry.mesh.planeMesh
import afield.geometry.mesh.topology
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [toPlanarMesh] / [toTriMesh], and [LinearBeltramiSolver] at `μ = 0` against the shared cotangent Laplacian. */
class PlanarTriMeshBridgeTest {

    /** At `μ = 0` the Beltrami system is the cotangent Laplacian: the same energy `xᵀ L x` for any `x`. */
    @Test
    fun beltramiAtZeroIsTheSharedCotangentLaplacian() {
        val planar = PlanarMesh.grid(7).smoothed(2)
        val tri = planar.toTriMesh()
        val l = tri.topology().cotangentLaplacianMatrix()
        val solver = LinearBeltramiSolver(planar)
        solver.assemble(DoubleArray(2 * planar.triangleCount))
        for (seed in 1..3) {
            val f = DoubleArray(planar.vertexCount) { sin(seed * 1.3 * planar.x[it] + 0.7 * it) + planar.y[it] * planar.y[it] }
            var shared = 0.0
            for (col in 0 until l.numCols) for (k in l.col_idx[col] until l.col_idx[col + 1]) shared += f[l.nz_rows[k]] * l.nz_values[k] * f[col]
            val beltrami = solver.energy(f)
            println("seed $seed: xᵀLx shared=%.12f beltrami=%.12f".format(shared, beltrami))
            // TriMesh stores Float positions, so the two agree to Float rounding (~1e-8 relative), not to Double.
            assertEquals(shared, beltrami, 1e-6 * maxOf(1.0, abs(shared)))
        }
    }

    /** A flat [afield.geometry.mesh.TriMesh] goes to a planar mesh and back unchanged, and a harmonic map with the identity on its boundary is the identity. */
    @Test
    fun aFlatTriMeshRunsAHarmonicMap() {
        val tri = planeMesh(6, 5, 2.0, 1.5)
        val planar = tri.toPlanarMesh()
        assertEquals(tri.vertexCount, planar.vertexCount)
        assertEquals(2 * (6 + 5), planar.boundary.size)
        var signed = 0.0
        for (k in planar.boundary.indices) {
            val i = planar.boundary[k]; val j = planar.boundary[(k + 1) % planar.boundary.size]
            signed += planar.x[i] * planar.y[j] - planar.x[j] * planar.y[i]
        }
        assertTrue(signed > 0, "boundary should run counter-clockwise")
        for (t in 0 until planar.triangleCount) assertTrue(planar.areas[t] > 0)
        val fixed = planar.boundary.associateWith { doubleArrayOf(planar.x[it], planar.y[it]) }
        val map = harmonicMap(planar, fixed, LinearBeltramiSolver(planar))
        for (i in 0 until planar.vertexCount) {
            assertEquals(planar.x[i], map.u[i], 1e-6)
            assertEquals(planar.y[i], map.v[i], 1e-6)
        }
        val back = planar.toTriMesh()
        for (i in 0 until tri.vertexCount) assertEquals(tri.x(i), back.x(i), 1e-6f)
    }
}
