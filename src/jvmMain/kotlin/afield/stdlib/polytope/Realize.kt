@file:afield.CompletionCategory("Polytopes")

package afield.stdlib.polytope

import afield.stdlib.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Coordinates from combinatorics alone, and the gauge that makes an answer a *representative*.
 *
 * Two unrelated jobs, kept in one file because the second is what makes the first reproducible.
 *
 * [PolytopeTopology.realize] is the first of the library's three realization verbs. It differs from
 * [RealizationPolicy3], which computes a Conway output's coordinates from its *source's*: a
 * [RealizationConstruction] sees only the incidence structure and must invent an embedding from
 * nothing. [SpectralRealization] is the one built in — the low Laplacian modes of the vertex graph,
 * which is exactly the symmetric solid for a vertex-transitive graph and a good symmetry-respecting
 * starting point otherwise.
 *
 * [RigidGauge] is the other half. Every constraint family in this package is invariant under rigid
 * motion, and most under scale, so a solver's answer is only defined up to seven degrees of freedom.
 * Leaving that to a weak proximity term makes results depend on solver damping; instead
 * [PolyhedralObject.normalize] translates a declared centre to the origin, scales a declared measured
 * length to a target, and — given a reference embedding — applies the one rotation that best matches
 * it. The rotation gauge is anchored to a reference rather than to the inertia tensor, because the
 * principal axes of a symmetric solid are degenerate, so an inertia gauge would spin.
 *
 * Nothing here claims a shape is *good*. A construction can return a self-intersecting embedding with
 * non-planar faces and this file will not notice; that is what [PolyhedralObject.canonicalDiagnostics]
 * and [PolyhedralObject.geometryDiagnostics] are for.
 */

/**
 * Construct coordinates for an abstract topology according to a declared policy.
 *
 * This is the first of the library's three realization verbs. It differs from [RealizationPolicy3],
 * which realizes the *output of one Conway transform* from its source's coordinates: a
 * [RealizationConstruction] sees only combinatorics and must invent an embedding from nothing.
 */
fun interface RealizationConstruction {
    /**
     * Invent coordinates for [topology]. Must return one point per rank-0 element, in `elements(0)` order.
     */
    fun construct(topology: PolytopeTopology): Realization3
}

/** Give an abstract topology coordinates. The result carries no attributes. */
fun PolytopeTopology.realize(policy: RealizationConstruction): PolyhedralObject =
    PolyhedralObject(this, policy.construct(this))

/**
 * Replace this object's coordinates with ones constructed from its topology alone, keeping the
 * topology and attributes. Useful after an abstract [PolytopeTopology.dual], whose combinatorics
 * are exact but whose coordinates either do not exist or came from a centroid heuristic.
 */
fun PolyhedralObject.realize(policy: RealizationConstruction): PolyhedralObject =
    copy(realization = policy.construct(topology))

/**
 * Spectral (Laplacian eigenvector) embedding: the classic "draw the graph with its low modes"
 * construction.
 *
 * Coordinates are the three eigenvectors of the vertex graph Laplacian with the smallest non-zero
 * eigenvalues. For a vertex-transitive polyhedral graph those three modes span one degenerate
 * eigenspace and the embedding *is* the symmetric solid, up to an arbitrary rotation inside that
 * eigenspace and an overall scale. For a graph whose fourth-smallest eigenvalue is not degenerate
 * with the second and third — many Conway outputs — the embedding is a good, symmetry-respecting
 * *starting point* and not a finished solid; follow it with [PolyhedralObject.canonicalize].
 *
 * What this does not claim: that the result is planar-faced, convex, or free of
 * self-intersection. Check [PolyhedralObject.geometryDiagnostics] and
 * [PolyhedralObject.canonicalDiagnostics] rather than trusting the construction.
 *
 * @param projectToSphere scale every vertex to unit length, which removes the wildly different
 *        eigenvector magnitudes that otherwise flatten a non-degenerate embedding.
 */
data class SpectralRealization(
    /**
     * Scale every vertex to unit length. On by default because the three eigenvectors have wildly
     * different magnitudes whenever the modes are not degenerate, which flattens the embedding into a
     * ribbon; the projection trades that for a shape that is at least round.
     */
    val projectToSphere: Boolean = true,
    /**
     * Cyclic Jacobi sweeps allowed for the eigendecomposition. The iteration exits early once the
     * off-diagonal mass is negligible, so this is a ceiling rather than a cost.
     */
    val sweeps: Int = 64,
) : RealizationConstruction {
    init {
        require(sweeps > 0) { "spectral realization needs at least one Jacobi sweep" }
    }

    override fun construct(topology: PolytopeTopology): Realization3 {
        val vertices = topology.elements(0)
        require(vertices.size >= 4) { "spectral realization needs at least four vertices" }
        val index = vertices.mapIndexed { at, vertex -> vertex.id.value to at }.toMap()
        val n = vertices.size
        val laplacian = Array(n) { DoubleArray(n) }
        for (edge in topology.elements(1)) {
            val ends = topology.incidentDown(edge.id)
            require(ends.size == 2) { "edge ${edge.id.value} does not have exactly two endpoints" }
            val a = index.getValue(ends[0])
            val b = index.getValue(ends[1])
            if (a == b) continue
            laplacian[a][b] -= 1.0
            laplacian[b][a] -= 1.0
            laplacian[a][a] += 1.0
            laplacian[b][b] += 1.0
        }

        val (values, vectors) = symmetricEigen(laplacian, sweeps)
        // Skip the constant mode(s): a connected polyhedral graph has exactly one zero eigenvalue.
        val tolerance = 1e-8 * (values.lastOrNull()?.let { abs(it) } ?: 1.0).coerceAtLeast(1.0)
        var first = 0
        while (first < n && values[first] <= tolerance) first++
        require(first + 2 < n) { "vertex graph is disconnected or too small for a spectral embedding" }

        val coordinates = (0 until n).map { vertex ->
            Vec3(
                vectors[vertex][first].toFloat(),
                vectors[vertex][first + 1].toFloat(),
                vectors[vertex][first + 2].toFloat(),
            )
        }
        if (!projectToSphere) return Realization3(coordinates)
        return Realization3(
            coordinates.map { point ->
                val length = point.length()
                if (length <= 1e-9f) Vec3(0f, 0f, 1f) else Vec3(point).mul(1f / length)
            },
        )
    }
}

// ==============================================================================================
// Explicit removal of the rigid-motion and scale null spaces
// ==============================================================================================

/**
 * Which point of the embedding is moved to the origin.
 *
 * `VERTEX_CENTROID` is cheap and unambiguous. `EDGE_TANGENCY_CENTROID` is the canonical form's own
 * centre and costs a short fixed-point iteration, because the tangency points are defined relative to
 * the origin and therefore move whenever the object does. `NONE` leaves the position alone.
 */
enum class GaugeCentre {
    NONE,
    VERTEX_CENTROID,

    /** The mean of every edge's nearest point to the origin — the canonical form's own centre. */
    EDGE_TANGENCY_CENTROID,
}

/**
 * Which measured length is driven to the gauge's scale target.
 *
 * `MEAN_EDGE_LENGTH` is the everyday choice and the default. `MEAN_VERTEX_RADIUS` and
 * `MAX_VERTEX_RADIUS` size the object by its circumsphere instead, which is what you want when
 * comparing two objects side by side. `MEAN_EDGE_TANGENCY_RADIUS` is the canonical form's own scale,
 * so it is the one that makes a canonicalized solid's diagnostics read as relative errors. `NONE`
 * leaves the size alone.
 */
enum class GaugeScale {
    NONE,
    MEAN_EDGE_LENGTH,
    MEAN_VERTEX_RADIUS,
    MAX_VERTEX_RADIUS,
    MEAN_EDGE_TANGENCY_RADIUS,
}

/**
 * The explicit normalization that makes a realization a *representative* rather than a member of a
 * seven-dimensional family.
 *
 * Every constraint family here is invariant under rigid motion and most are invariant under scale,
 * so a solver's answer is only defined up to those seven degrees of freedom. Leaving that to a weak
 * proximity term makes results depend on solver damping. Instead: translate a declared [centre] to
 * the origin, scale a declared measured length to [scaleTarget], and — when [alignToReference] and
 * a reference embedding are given — apply the single rotation that best matches the reference
 * (Kabsch/Horn). The rotation gauge is anchored to a reference rather than to the
 * inertia tensor, whose principal axes are degenerate for exactly the symmetric solids this
 * library produces.
 */
data class RigidGauge(
    /**
     * Which point of the embedding is moved to the origin. [GaugeCentre.EDGE_TANGENCY_CENTROID] is not a
     * single subtraction — tangency points move when the object does, so it is iterated to a fixed point.
     */
    val centre: GaugeCentre = GaugeCentre.VERTEX_CENTROID,
    /**
     * Which measured length is driven to [scaleTarget]. A measurement smaller than `1e-12` is left alone
     * rather than dividing by it, so a collapsed object survives normalization unscaled.
     */
    val scale: GaugeScale = GaugeScale.MEAN_EDGE_LENGTH,
    /**
     * What that measured length becomes. Must be finite and positive.
     */
    val scaleTarget: Double = 1.0,
    /**
     * Rotate to best match the reference embedding, when one is supplied. With no reference this does
     * nothing and the input orientation is kept — still deterministic, just not canonical.
     */
    val alignToReference: Boolean = true,
) {
    init {
        require(scaleTarget.isFinite() && scaleTarget > 0.0) { "gauge scale target must be positive" }
    }

    companion object {
        /** Centre and size the way the canonical form defines them. */
        val CANONICAL = RigidGauge(GaugeCentre.EDGE_TANGENCY_CENTROID, GaugeScale.MEAN_EDGE_TANGENCY_RADIUS)

        /** Translation and scale only; leave orientation exactly as the caller supplied it. */
        val CENTRE_AND_SCALE = RigidGauge(alignToReference = false)
    }
}

/**
 * Apply [gauge] to this object's coordinates. [reference] supplies the orientation to align to;
 * when it is null the orientation is left alone, which is still deterministic because the input
 * orientation is.
 */
fun PolyhedralObject.normalize(gauge: RigidGauge, reference: Realization3? = null): PolyhedralObject {
    realization ?: throw IllegalStateException("normalization requires a 3D realization")
    val x = coordinateVector()
    val problem = RealizationProblem.of(this)

    when (gauge.centre) {
        GaugeCentre.NONE -> Unit
        GaugeCentre.VERTEX_CENTROID -> translate(x, centroidOf(x))
        // Tangency points are defined relative to the origin, so they move when the object does:
        // one subtraction does not put their centroid at the origin. Iterate to the fixed point.
        GaugeCentre.EDGE_TANGENCY_CENTROID -> repeat(TANGENCY_CENTRING_STEPS) {
            val centre = tangencyCentroid(problem, x)
            translate(x, centre)
            if (sqrt(centre[0] * centre[0] + centre[1] * centre[1] + centre[2] * centre[2]) <= 1e-14) return@repeat
        }
    }

    val measured = when (gauge.scale) {
        GaugeScale.NONE -> gauge.scaleTarget
        GaugeScale.MEAN_EDGE_LENGTH -> meanEdgeLength(problem, x)
        GaugeScale.MEAN_VERTEX_RADIUS -> meanVertexRadius(x)
        GaugeScale.MAX_VERTEX_RADIUS -> maxVertexRadius(x)
        GaugeScale.MEAN_EDGE_TANGENCY_RADIUS -> meanTangencyRadius(problem, x)
    }
    if (measured > 1e-12) {
        val factor = gauge.scaleTarget / measured
        for (i in x.indices) x[i] *= factor
    }

    if (gauge.alignToReference && reference != null) {
        require(reference.size == problem.vertexCount) {
            "alignment reference has ${reference.size} vertices for ${problem.vertexCount} coordinates"
        }
        val target = DoubleArray(x.size)
        for (i in 0 until reference.size) {
            target[3 * i] = reference.x(i).toDouble()
            target[3 * i + 1] = reference.y(i).toDouble()
            target[3 * i + 2] = reference.z(i).toDouble()
        }
        val targetCentre = centroidOf(target)
        for (vertex in 0 until problem.vertexCount) for (axis in 0..2) target[3 * vertex + axis] -= targetCentre[axis]
        applyRotation(x, optimalRotation(x, target))
    }
    return withCoordinateVector(x)
}

/** Iterations of the tangency-centroid fixed point; convergence is geometric and fast. */
private const val TANGENCY_CENTRING_STEPS = 256

private fun translate(x: DoubleArray, by: DoubleArray) {
    for (vertex in 0 until x.size / 3) for (axis in 0..2) x[3 * vertex + axis] -= by[axis]
}

private fun tangencyCentroid(problem: RealizationProblem, x: DoubleArray): DoubleArray {
    val sum = DoubleArray(3)
    if (problem.edgeCount == 0) return sum
    val point = DoubleArray(3)
    for (edge in 0 until problem.edgeCount) {
        edgeTangentPoint(x, problem.edgeA(edge), problem.edgeB(edge), point)
        for (axis in 0..2) sum[axis] += point[axis]
    }
    for (axis in 0..2) sum[axis] /= problem.edgeCount
    return sum
}

private fun meanEdgeLength(problem: RealizationProblem, x: DoubleArray): Double =
    if (problem.edgeCount == 0) 0.0
    else (0 until problem.edgeCount).sumOf { distanceBetween(x, problem.edgeA(it), problem.edgeB(it)) } / problem.edgeCount

private fun meanVertexRadius(x: DoubleArray): Double {
    val count = x.size / 3
    if (count == 0) return 0.0
    val origin = DoubleArray(3)
    return (0 until count).sumOf { distanceToPoint(x, it, origin) } / count
}

private fun maxVertexRadius(x: DoubleArray): Double {
    val origin = DoubleArray(3)
    return (0 until x.size / 3).maxOfOrNull { distanceToPoint(x, it, origin) } ?: 0.0
}

private fun meanTangencyRadius(problem: RealizationProblem, x: DoubleArray): Double {
    if (problem.edgeCount == 0) return 0.0
    val point = DoubleArray(3)
    return (0 until problem.edgeCount).sumOf { edge ->
        edgeTangentPoint(x, problem.edgeA(edge), problem.edgeB(edge), point)
        sqrt(point[0] * point[0] + point[1] * point[1] + point[2] * point[2])
    } / problem.edgeCount
}

/**
 * Horn's quaternion solution of the orthogonal Procrustes problem: the rotation carrying [from]
 * onto [to], both already centred. Reflections are excluded by construction, which is what a
 * *gauge* needs — a mirrored polyhedron is a different object, not a better fit.
 */
private fun optimalRotation(from: DoubleArray, to: DoubleArray): Array<DoubleArray> {
    val s = Array(3) { DoubleArray(3) }
    for (vertex in 0 until from.size / 3) {
        for (r in 0..2) for (c in 0..2) s[r][c] += from[3 * vertex + r] * to[3 * vertex + c]
    }
    val n = arrayOf(
        doubleArrayOf(s[0][0] + s[1][1] + s[2][2], s[1][2] - s[2][1], s[2][0] - s[0][2], s[0][1] - s[1][0]),
        doubleArrayOf(s[1][2] - s[2][1], s[0][0] - s[1][1] - s[2][2], s[0][1] + s[1][0], s[2][0] + s[0][2]),
        doubleArrayOf(s[2][0] - s[0][2], s[0][1] + s[1][0], -s[0][0] + s[1][1] - s[2][2], s[1][2] + s[2][1]),
        doubleArrayOf(s[0][1] - s[1][0], s[2][0] + s[0][2], s[1][2] + s[2][1], -s[0][0] - s[1][1] + s[2][2]),
    )
    val (values, vectors) = symmetricEigen(n, sweeps = 64)
    val best = values.indices.maxBy { values[it] }
    var q0 = vectors[0][best]
    var q1 = vectors[1][best]
    var q2 = vectors[2][best]
    var q3 = vectors[3][best]
    val norm = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
    if (norm <= 1e-12) return arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
    q0 /= norm
    q1 /= norm
    q2 /= norm
    q3 /= norm
    return arrayOf(
        doubleArrayOf(q0 * q0 + q1 * q1 - q2 * q2 - q3 * q3, 2 * (q1 * q2 - q0 * q3), 2 * (q1 * q3 + q0 * q2)),
        doubleArrayOf(2 * (q2 * q1 + q0 * q3), q0 * q0 - q1 * q1 + q2 * q2 - q3 * q3, 2 * (q2 * q3 - q0 * q1)),
        doubleArrayOf(2 * (q3 * q1 - q0 * q2), 2 * (q3 * q2 + q0 * q1), q0 * q0 - q1 * q1 - q2 * q2 + q3 * q3),
    )
}

private fun applyRotation(x: DoubleArray, rotation: Array<DoubleArray>) {
    for (vertex in 0 until x.size / 3) {
        val px = x[3 * vertex]
        val py = x[3 * vertex + 1]
        val pz = x[3 * vertex + 2]
        for (axis in 0..2) {
            x[3 * vertex + axis] = rotation[axis][0] * px + rotation[axis][1] * py + rotation[axis][2] * pz
        }
    }
}

/**
 * Cyclic Jacobi eigendecomposition of a small symmetric matrix. Eigenvalues ascending;
 * `vectors[row][k]` is component `row` of eigenvector `k`.
 *
 * Kept package-local rather than pulled from `afield.stdlib.imaging_geometry`, whose
 * `smallestEigenvector` returns only one vector, and to keep this package's dependency surface to
 * `Vec3`, `FLine` and the least-squares solver.
 */
internal fun symmetricEigen(matrix: Array<DoubleArray>, sweeps: Int = 64): Pair<DoubleArray, Array<DoubleArray>> {
    val n = matrix.size
    require(matrix.all { it.size == n }) { "eigendecomposition needs a square matrix" }
    val a = Array(n) { matrix[it].copyOf() }
    val v = Array(n) { r -> DoubleArray(n) { c -> if (r == c) 1.0 else 0.0 } }
    repeat(sweeps) {
        var off = 0.0
        for (p in 0 until n) for (q in p + 1 until n) off += a[p][q] * a[p][q]
        if (off < 1e-26) return@repeat
        for (p in 0 until n) for (q in p + 1 until n) {
            if (abs(a[p][q]) < 1e-18) continue
            val theta = (a[q][q] - a[p][p]) / (2.0 * a[p][q])
            val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1.0))
            val c = 1.0 / sqrt(t * t + 1.0)
            val s = t * c
            for (k in 0 until n) {
                val akp = a[k][p]
                val akq = a[k][q]
                a[k][p] = c * akp - s * akq
                a[k][q] = s * akp + c * akq
            }
            for (k in 0 until n) {
                val apk = a[p][k]
                val aqk = a[q][k]
                a[p][k] = c * apk - s * aqk
                a[q][k] = s * apk + c * aqk
            }
            for (k in 0 until n) {
                val vkp = v[k][p]
                val vkq = v[k][q]
                v[k][p] = c * vkp - s * vkq
                v[k][q] = s * vkp + c * vkq
            }
        }
    }
    val order = (0 until n).sortedBy { a[it][it] }
    val values = DoubleArray(n) { a[order[it]][order[it]] }
    val vectors = Array(n) { row -> DoubleArray(n) { k -> v[row][order[k]] } }
    return values to vectors
}
