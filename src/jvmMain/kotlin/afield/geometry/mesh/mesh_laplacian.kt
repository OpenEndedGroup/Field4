@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.mesh

import org.ejml.data.DMatrixSparseCSC
import org.ejml.data.DMatrixSparseTriplet
import org.ejml.ops.DConvertMatrixStruct
import kotlin.math.sqrt

/**
 * The cotangent Laplacian of a triangle mesh: the one discrete Laplace-Beltrami operator that
 * smoothing ([TriMesh.laplacianSmooth], [TriMesh.taubinSmooth]), geodesics ([HeatGeodesics]),
 * curvature ([TriMesh.meanCurvature], [TriMesh.mixedVertexAreas]) and the spectral basis
 * (`afield.geometry.spectral.CotangentLaplacian`) are all built on.
 *
 * Three pieces, each indexed the way [MeshTopology] indexes things:
 *
 *  - [MeshTopology.cotangentWeights]: per edge, `(cot α + cot β) / 2`, with α and β the angles
 *    opposite it. A boundary edge has one opposite angle and gets `cot α / 2` — the natural
 *    (Neumann) finite-element convention, which every caller here shares. Obtuse angles make a weight
 *    negative; the weights are returned as they are, and a caller that needs them positive (the
 *    smoother) clamps them itself.
 *  - [TriMesh.barycentricVertexAreas]: the lumped mass, a third of each incident triangle's area.
 *    [TriMesh.mixedVertexAreas] is the Voronoi alternative curvature divides by.
 *  - [MeshTopology.cotangentLaplacianMatrix]: `massScale · M + lScale · L` as a sparse symmetric
 *    EJML matrix, positive semidefinite in `L` (the sign that factors by Cholesky), with optional
 *    Dirichlet rows.
 *
 * ```kotlin
 * val sphere = icosphereMesh(3, 1.0)
 * val topo = sphere.topology()
 * val w = topo.cotangentWeights()
 * val L = topo.cotangentLaplacianMatrix(w)
 * ```
 */

/**
 * Cotangent of the angle at corner [c] between the edges to [a] and [b], on interleaved `xyz`
 * [positions]. `0` for a degenerate corner (the two edges parallel or zero-length).
 */
fun cornerCotangent(positions: DoubleArray, c: Int, a: Int, b: Int): Double {
    val ux = positions[a * 3] - positions[c * 3]; val uy = positions[a * 3 + 1] - positions[c * 3 + 1]; val uz = positions[a * 3 + 2] - positions[c * 3 + 2]
    val vx = positions[b * 3] - positions[c * 3]; val vy = positions[b * 3 + 1] - positions[c * 3 + 1]; val vz = positions[b * 3 + 2] - positions[c * 3 + 2]
    val dot = ux * vx + uy * vy + uz * vz
    val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
    val cross = sqrt(cx * cx + cy * cy + cz * cz)
    return if (cross < 1e-18) 0.0 else dot / cross
}

/** [positions] widened to `Double`, the form [cornerCotangent] reads. */
internal fun widen(positions: FloatArray) = DoubleArray(positions.size) { positions[it].toDouble() }

/** The corner of triangle [t] of [mesh] that is neither [a] nor [b]. */
internal fun oppositeCorner(mesh: TriMesh, t: Int, a: Int, b: Int): Int {
    val ia = mesh.ia(t); val ib = mesh.ib(t)
    return if (ia != a && ia != b) ia else if (ib != a && ib != b) ib else mesh.ic(t)
}

/**
 * Per edge, `(cot α + cot β) / 2`, where α and β are the angles opposite it in its triangles —
 * the discrete Laplace-Beltrami weights, indexed by [MeshTopology.edges] position.
 *
 * A boundary edge gets its single `cot α / 2`. [positions] defaults to the mesh's own, and can be
 * any other interleaved `xyz` array on the same connectivity (a smoother passes its moving copy).
 */
fun MeshTopology.cotangentWeights(positions: DoubleArray = widen(mesh.positions)): DoubleArray {
    val w = DoubleArray(edgeCount)
    for (e in 0 until edgeCount) {
        val (a, b) = edges[e]
        var sum = 0.0
        for (t in edgeTriangles[e]) sum += cornerCotangent(positions, oppositeCorner(mesh, t, a, b), a, b)
        w[e] = sum / 2.0
    }
    return w
}

/** [cotangentWeights] on `Float` positions. */
fun MeshTopology.cotangentWeights(positions: FloatArray): DoubleArray = cotangentWeights(widen(positions))

/**
 * Lumped (barycentric) area per vertex: a third of the area of each triangle around it. The mass
 * matrix of linear finite elements, lumped; sums to the mesh's surface area.
 */
fun TriMesh.barycentricVertexAreas(): DoubleArray {
    val out = DoubleArray(vertexCount)
    for (t in 0 until triangleCount) {
        val third = triangleArea(t) / 3.0
        out[ia(t)] += third; out[ib(t)] += third; out[ic(t)] += third
    }
    return out
}

/**
 * `massScale · M + lScale · L` as a sparse symmetric matrix, where `L` is the cotangent Laplacian
 * built from [weights] (one per edge, as [cotangentWeights] gives them) with `L` positive
 * semidefinite — `L_ii = Σ w`, `L_ij = −w` — and `M` the diagonal [mass] (ignored when
 * [massScale] is `0`).
 *
 * Each vertex in [fixed] keeps only an identity row: a Dirichlet condition at zero, whose column
 * drops out of every other row without changing the right-hand side.
 */
fun MeshTopology.cotangentLaplacianMatrix(
    weights: DoubleArray = cotangentWeights(),
    mass: DoubleArray? = null,
    massScale: Double = 0.0,
    lScale: Double = 1.0,
    fixed: Set<Int> = emptySet(),
): DMatrixSparseCSC {
    val n = mesh.vertexCount
    require(weights.size == edgeCount) { "need one weight per edge ($edgeCount), got ${weights.size}" }
    require(massScale == 0.0 || mass?.size == n) { "a mass term needs one mass per vertex ($n)" }
    val trip = DMatrixSparseTriplet(n, n, n + 2 * edgeCount)
    val diag = DoubleArray(n) { if (massScale == 0.0) 0.0 else massScale * mass!![it] }
    for (e in 0 until edgeCount) {
        val (a, b) = edges[e]
        val w = lScale * weights[e]
        if (a !in fixed && b !in fixed) { trip.addItem(a, b, -w); trip.addItem(b, a, -w) }
        diag[a] += w; diag[b] += w
    }
    for (i in 0 until n) trip.addItem(i, i, if (i in fixed) 1.0 else diag[i])
    return DConvertMatrixStruct.convert(trip, null as DMatrixSparseCSC?)
}
