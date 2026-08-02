package afield.stdlib.polytope

import afield.stdlib.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * How close an embedding is to the canonical form, in the canonical form's own terms.
 *
 * The canonical form of a polyhedron is the embedding in which every edge is tangent to one common
 * sphere, the centroid of those tangency points is the origin, and every face is planar. These are
 * the three quantities to report, plus a dihedral summary that says whether the result *looks*
 * like the solid it should be.
 *
 * All lengths are in the units of the realization. Because [canonicalize] normalises the mean
 * tangency radius to 1 by default, [tangencyRadiusSpread] and [maxFacePlanarityError] read directly
 * as relative errors after a canonical run.
 */
data class CanonicalDiagnostics(
    val tangencyRadiusMin: Double,
    val tangencyRadiusMax: Double,
    val tangencyRadiusMean: Double,
    /** `(max - min) / mean`: zero exactly when every edge touches one common sphere. */
    val tangencyRadiusSpread: Double,
    /** Distance from the origin to the centroid of the edge tangency points. */
    val tangencyCentroidOffset: Double,
    val maxFacePlanarityError: Double,
    val facePlanarityRmsError: Double,
    val dihedralAngleMin: Double,
    val dihedralAngleMax: Double,
    val dihedralAngleMean: Double,
    /** `max - min` over edges, in radians. Small means one Archimedean-looking solid, not a lump. */
    val dihedralAngleSpread: Double,
    val degenerateFaces: List<ElementId>,
)

/** Which iteration drives the embedding toward the canonical conditions. */
enum class CanonicalMethod {
    /**
     * Dual reciprocation. Each step replaces the dual's vertices by the poles of the primal's face
     * planes and then does the same in reverse; the canonical form is exactly the joint fixed point,
     * where primal and dual are polar reciprocals about one common midsphere. Converges in tens of
     * steps rather than thousands, and needs the face planes to stay away from the origin — which
     * is true for a convex solid and can fail spectacularly for one that is not.
     */
    RECIPROCAL,

    /**
     * Local relaxation: nudge every edge's tangency radius toward one, recentre on the tangency
     * points, and pull every face's corners onto that face's own plane. Slow — hundreds to
     * thousands of steps — but each step is a small, bounded, local move, so it degrades gracefully
     * where reciprocation blows up.
     */
    LOCAL_RELAXATION,

    /** Reciprocate to get close, then relax locally to polish. The default. */
    RECIPROCAL_THEN_LOCAL,
}

/**
 * Configuration for [PolyhedralObject.canonicalize].
 *
 * [stability] is the fraction of each local correction applied per relaxation iteration. The
 * published algorithm uses 0.1 and it is a genuine trade: larger values converge in fewer
 * iterations and oscillate or diverge on high-degree Conway outputs.
 *
 * The residual floor is about `1e-7` relative: [Realization3] stores Field's single-precision
 * [Vec3], so the iteration's double-precision answer is rounded on the way out. Asking for a
 * tolerance far below that measures float rounding, not the algorithm.
 */
data class CanonicalizeOptions(
    val method: CanonicalMethod = CanonicalMethod.RECIPROCAL_THEN_LOCAL,
    val reciprocalIterations: Int = 600,
    val maxIterations: Int = 2000,
    /** Local steps run after a *converged* reciprocal phase, purely to polish planarity. */
    val polishIterations: Int = 64,
    val stability: Double = 0.1,
    /** Convergence test on the largest single-vertex movement in one iteration. */
    val movementTolerance: Double = 1e-10,
    val planarize: Boolean = true,
    val tangentify: Boolean = true,
    /**
     * The canonical form of a polyhedron is determined by its *combinatorics* — the supplied
     * coordinates are only a starting point. So when the supplied embedding cannot be canonicalized
     * (the Conway realization policies happily produce coplanar adjacent faces, which have no
     * distinct dual vertices to reciprocate through, and deep `kis` chains produce spikes the local
     * relaxation walks off), restart from coordinates built from the topology alone.
     *
     * Set to null to make a failure stay a failure, which is what a test of the iteration itself
     * wants. [CanonicalizeResult.reseeded] always says which embedding the answer came from.
     */
    val reseed: RealizationConstruction? = SpectralRealization(),
    /** Above this tangency-radius spread an attempt counts as failed and triggers [reseed]. */
    val acceptableSpread: Double = 1e-5,
    /** Applied once at the end; the canonical gauge is what makes the answer a representative. */
    val gauge: RigidGauge? = RigidGauge.CANONICAL,
) {
    init {
        require(maxIterations > 0) { "canonicalize needs at least one iteration" }
        require(reciprocalIterations > 0) { "canonicalize needs at least one reciprocal iteration" }
        require(polishIterations >= 0) { "polish iterations must be non-negative" }
        require(stability > 0.0 && stability <= 1.0) { "canonicalize stability must be in (0, 1]" }
        require(movementTolerance >= 0.0) { "movement tolerance must be non-negative" }
        require(acceptableSpread > 0.0) { "acceptable spread must be positive" }
        require(planarize || tangentify) { "canonicalize with neither planarize nor tangentify does nothing" }
    }
}

/**
 * The outcome of a canonicalization run, including the honest before/after evidence.
 *
 * [converged] means the iteration stopped moving, not that the result is the canonical form: only
 * the diagnostics can say that, and for a non-convex or non-polyhedral input no canonical form need
 * exist at all.
 */
data class CanonicalizeResult(
    val polyhedron: PolyhedralObject,
    val initialDiagnostics: CanonicalDiagnostics,
    val finalDiagnostics: CanonicalDiagnostics,
    val iterations: Int,
    val converged: Boolean,
    /** Largest single-vertex movement in the final iteration. */
    val finalMovement: Double,
    val reason: String,
    val reciprocalIterations: Int = 0,
    val relaxationIterations: Int = 0,
    /** True when the supplied embedding failed and the run restarted from the topology alone. */
    val reseeded: Boolean = false,
)

/**
 * Seek the canonical, symmetry-respecting representative of this embedding.
 *
 * The third of the library's realization verbs. Where [relax] minimises whatever a caller declared
 * and [PolytopeTopology.realize] invents coordinates from combinatorics, [canonicalize] pursues one
 * specific, classical target: the embedding whose edges are all tangent to a common sphere, whose
 * tangency points are centred on the origin, and whose faces are planar.
 *
 * **Guarantees, stated.** For a convex polyhedron — equivalently, by Steinitz's theorem, for any
 * 3-connected planar graph — that embedding exists and is unique up to Möbius transformations of
 * the sphere; fixing the tangency centroid at the origin (which the iteration does every step)
 * picks one representative out of that Möbius family, and the [RigidGauge] then removes the
 * remaining rigid-motion and scale freedom. That is the case every Conway operator applied to a
 * Platonic seed lands in, and it is where this function is meaningful.
 *
 * **What is not guaranteed.** The iteration is a numerical search, not a proof. Neither method is
 * guaranteed to converge for non-convex, self-intersecting, higher-genus, or non-3-connected input,
 * and this function will not pretend otherwise: it returns [CanonicalizeResult.converged] plus
 * [CanonicalDiagnostics] and expects the caller to look. A run that stops at
 * [CanonicalizeOptions.maxIterations] with a large [CanonicalDiagnostics.tangencyRadiusSpread] has
 * failed, and says so. A [CanonicalMethod.RECIPROCAL] phase that diverges is detected and rolled
 * back to its last finite state rather than returned as coordinates full of infinities.
 *
 * The topology and attributes are returned unchanged; only coordinates move.
 */
fun PolyhedralObject.canonicalize(options: CanonicalizeOptions = CanonicalizeOptions()): CanonicalizeResult {
    val problem = RealizationProblem.of(this)
    val initialDiagnostics = canonicalDiagnostics()

    var attempt = runCanonicalIteration(problem, startingCoordinates(this), options)
    var reseeded = false
    val reseed = options.reseed
    if (reseed != null && !attempt.isAcceptable(options)) {
        val seeded = runCatching { runCanonicalIteration(problem, startingCoordinates(realize(reseed)), options) }
            .getOrNull()
        if (seeded != null && seeded.isBetterThan(attempt)) {
            attempt = seeded
            reseeded = true
        }
    }

    val relaxed = withCoordinateVector(attempt.coordinates)
    val gauged = options.gauge?.let { relaxed.normalize(it, reference = realization) } ?: relaxed
    return CanonicalizeResult(
        gauged,
        initialDiagnostics,
        gauged.canonicalDiagnostics(),
        attempt.reciprocalIterations + attempt.relaxationIterations,
        attempt.converged,
        attempt.movement,
        attempt.reason,
        attempt.reciprocalIterations,
        attempt.relaxationIterations,
        reseeded,
    )
}

/** Tangency-centred at unit radius: both iterations' fixed point is defined at radius one. */
private fun startingCoordinates(polyhedron: PolyhedralObject): DoubleArray = polyhedron.normalize(
    RigidGauge(GaugeCentre.EDGE_TANGENCY_CENTROID, GaugeScale.MEAN_EDGE_TANGENCY_RADIUS, alignToReference = false),
).coordinateVector()

private class CanonicalAttempt(
    val coordinates: DoubleArray,
    val reciprocalIterations: Int,
    val relaxationIterations: Int,
    val converged: Boolean,
    val movement: Double,
    val reason: String,
    val spread: Double,
) {
    fun isAcceptable(options: CanonicalizeOptions) =
        reason != "diverged" && spread.isFinite() && spread <= options.acceptableSpread

    fun isBetterThan(other: CanonicalAttempt) =
        (spread.isFinite() && !other.spread.isFinite()) || spread < other.spread
}

private fun runCanonicalIteration(
    problem: RealizationProblem,
    x: DoubleArray,
    options: CanonicalizeOptions,
): CanonicalAttempt {
    var reciprocalIterations = 0
    var relaxationIterations = 0
    var converged = false
    var movement = Double.NaN
    var reason = "max iterations"

    if (options.method != CanonicalMethod.LOCAL_RELAXATION) {
        val phase = reciprocalPhase(problem, x, options)
        reciprocalIterations = phase.iterations
        converged = phase.converged
        movement = phase.movement
        reason = phase.reason
    }
    if (options.method != CanonicalMethod.RECIPROCAL && reason != "diverged") {
        // A converged reciprocal phase has already found the fixed point; the local pass is then a
        // short planarity polish that must not be able to report the whole run as unconverged.
        val budget = if (converged) options.polishIterations else options.maxIterations
        if (budget > 0) {
            val phase = relaxationPhase(problem, x, options.copy(maxIterations = budget))
            relaxationIterations = phase.iterations
            if (!converged) {
                converged = phase.converged
                movement = phase.movement
                reason = phase.reason
            }
        }
    }
    return CanonicalAttempt(
        x,
        reciprocalIterations,
        relaxationIterations,
        converged,
        movement,
        reason,
        tangencyRadiusSpread(problem, x),
    )
}

private fun tangencyRadiusSpread(problem: RealizationProblem, x: DoubleArray): Double {
    if (problem.edgeCount == 0 || !x.all(Double::isFinite)) return Double.NaN
    val point = DoubleArray(3)
    var min = Double.MAX_VALUE
    var max = -Double.MAX_VALUE
    var sum = 0.0
    for (edge in 0 until problem.edgeCount) {
        edgeTangentPoint(x, problem.edgeA(edge), problem.edgeB(edge), point)
        val radius = sqrt(point[0] * point[0] + point[1] * point[1] + point[2] * point[2])
        if (radius < min) min = radius
        if (radius > max) max = radius
        sum += radius
    }
    val mean = sum / problem.edgeCount
    return if (mean <= 1e-12) Double.NaN else (max - min) / mean
}

private class PhaseOutcome(
    val iterations: Int,
    val converged: Boolean,
    val movement: Double,
    val reason: String,
)

/**
 * Hart's dual reciprocation, in place.
 *
 * At the canonical form the primal and its dual are polar reciprocals about one common sphere, so
 * replacing the dual's vertices by the poles of the primal's face planes and then repeating in the
 * other direction has the canonical form as its fixed point. The `(1 + meanEdgeTangency) / 2`
 * factor on each pole is the published convergence damping; without it the map overshoots.
 */
private fun reciprocalPhase(problem: RealizationProblem, x: DoubleArray, options: CanonicalizeOptions): PhaseOutcome {
    val dualFaces = problem.dualFaceCycles ?: return PhaseOutcome(0, false, Double.NaN, "no reciprocal structure")
    val dual = DoubleArray(problem.faceCount * 3)
    val previous = DoubleArray(x.size)
    var iterations = 0
    var movement = Double.NaN

    while (iterations < options.reciprocalIterations) {
        x.copyInto(previous)
        if (!reciprocate(x, problem.faceCycles, dual) || !reciprocate(dual, dualFaces, x)) {
            previous.copyInto(x)
            return PhaseOutcome(iterations, false, Double.POSITIVE_INFINITY, "diverged")
        }
        recentreOnTangencyPoints(problem, x)
        iterations++
        if (!x.all(Double::isFinite)) {
            previous.copyInto(x)
            return PhaseOutcome(iterations, false, Double.POSITIVE_INFINITY, "diverged")
        }
        movement = maxVertexMovement(previous, x)
        if (movement <= options.movementTolerance) return PhaseOutcome(iterations, true, movement, "movement")
    }
    return PhaseOutcome(iterations, false, movement, "max iterations")
}

/** Local planarize / tangentify / recentre relaxation, in place. */
private fun relaxationPhase(problem: RealizationProblem, x: DoubleArray, options: CanonicalizeOptions): PhaseOutcome {
    val previous = DoubleArray(x.size)
    var iterations = 0
    var movement = Double.NaN

    while (iterations < options.maxIterations) {
        x.copyInto(previous)
        if (options.tangentify) {
            tangentifyStep(problem, x, options.stability)
            recentreOnTangencyPoints(problem, x)
        }
        if (options.planarize) planarizeStep(problem, x, options.stability)
        iterations++

        if (!x.all(Double::isFinite)) {
            previous.copyInto(x)
            return PhaseOutcome(iterations, false, Double.POSITIVE_INFINITY, "diverged")
        }
        movement = maxVertexMovement(previous, x)
        if (movement <= options.movementTolerance) return PhaseOutcome(iterations, true, movement, "movement")
    }
    return PhaseOutcome(iterations, false, movement, "max iterations")
}

/**
 * Write the polar reciprocal of every face plane of [faces] into [out], one point per face.
 *
 * Returns false when a face plane passes through the origin, which has no pole; the caller treats
 * that as divergence rather than inventing a point.
 */
private fun reciprocate(x: DoubleArray, faces: List<IntArray>, out: DoubleArray): Boolean {
    val point = DoubleArray(3)
    faces.forEachIndexed { at, cycle ->
        val plane = facePlane(x, cycle)
        if (plane.normalLength <= 1e-14) return false
        val nx = plane.nx / plane.normalLength
        val ny = plane.ny / plane.normalLength
        val nz = plane.nz / plane.normalLength
        val distance = plane.cx * nx + plane.cy * ny + plane.cz * nz
        if (abs(distance) <= 1e-12) return false

        var tangency = 0.0
        for (i in cycle.indices) {
            edgeTangentPoint(x, cycle[i], cycle[(i + 1) % cycle.size], point)
            tangency += sqrt(point[0] * point[0] + point[1] * point[1] + point[2] * point[2])
        }
        val damping = (1.0 + tangency / cycle.size) / 2.0
        out[3 * at] = damping * nx / distance
        out[3 * at + 1] = damping * ny / distance
        out[3 * at + 2] = damping * nz / distance
    }
    return out.all(Double::isFinite)
}

/** The same Conway chain with a canonical embedding. Lineage, history and deployments are kept. */
fun PolytopeTransformResult.canonicalize(
    options: CanonicalizeOptions = CanonicalizeOptions(),
): PolytopeTransformResult = PolytopeTransformResult(
    polyhedron.canonicalize(options).polyhedron,
    storedLineage,
    history,
    notationHistory,
    deployments,
)

/**
 * Evaluate a Conway prefix expression and canonicalize its realization — the everyday path from
 * notation to a solid you would recognise.
 *
 * ```kotlin
 * val hexecontahedron = canonicalConway("gD").polyhedron
 * ```
 */
fun canonicalConway(
    expression: String,
    options: CanonicalizeOptions = CanonicalizeOptions(),
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = conway(expression, attributePolicy).canonicalize(options)

/** Measure the three canonical conditions plus a dihedral summary, without changing anything. */
fun PolyhedralObject.canonicalDiagnostics(): CanonicalDiagnostics {
    realization ?: throw IllegalStateException("canonical diagnostics require a 3D realization")
    val problem = RealizationProblem.of(this)
    val x = problem.initialCoordinates
    val point = DoubleArray(3)

    val radii = DoubleArray(problem.edgeCount)
    val centre = DoubleArray(3)
    for (edge in 0 until problem.edgeCount) {
        edgeTangentPoint(x, problem.edgeA(edge), problem.edgeB(edge), point)
        radii[edge] = sqrt(point[0] * point[0] + point[1] * point[1] + point[2] * point[2])
        for (axis in 0..2) centre[axis] += point[axis]
    }
    if (problem.edgeCount > 0) for (axis in 0..2) centre[axis] /= problem.edgeCount
    val radiusMean = if (radii.isEmpty()) 0.0 else radii.average()

    val planarity = mutableListOf<Double>()
    val degenerate = mutableListOf<ElementId>()
    val faceElements = topology.elements(2)
    val normals = ArrayList<DoubleArray>(problem.faceCount)
    problem.faceCycles.forEachIndexed { at, cycle ->
        val plane = facePlane(x, cycle)
        if (plane.normalLength <= 1e-10) degenerate += faceElements[at].id
        val scale = max(plane.normalLength, 1e-10)
        normals += doubleArrayOf(plane.nx / scale, plane.ny / scale, plane.nz / scale)
        cycle.forEach { planarity += abs(signedPlaneDistance(x, it, plane)) }
    }

    val faceIndex = faceElements.mapIndexed { at, face -> face.id.value to at }.toMap()
    val dihedrals = topology.elements(1).mapNotNull { edge ->
        val faces = topology.incidentUp(edge.id).toList().mapNotNull { faceIndex[it] }
        if (faces.size != 2) return@mapNotNull null
        val a = normals[faces[0]]
        val b = normals[faces[1]]
        val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)
        PI - acos(dot)
    }

    return CanonicalDiagnostics(
        tangencyRadiusMin = radii.minOrNull() ?: 0.0,
        tangencyRadiusMax = radii.maxOrNull() ?: 0.0,
        tangencyRadiusMean = radiusMean,
        tangencyRadiusSpread = if (radiusMean <= 1e-12) 0.0 else ((radii.maxOrNull() ?: 0.0) - (radii.minOrNull() ?: 0.0)) / radiusMean,
        tangencyCentroidOffset = sqrt(centre[0] * centre[0] + centre[1] * centre[1] + centre[2] * centre[2]),
        maxFacePlanarityError = planarity.maxOrNull() ?: 0.0,
        facePlanarityRmsError = sqrt(planarity.sumOf { it * it } / max(1, planarity.size)),
        dihedralAngleMin = dihedrals.minOrNull() ?: 0.0,
        dihedralAngleMax = dihedrals.maxOrNull() ?: 0.0,
        dihedralAngleMean = if (dihedrals.isEmpty()) 0.0 else dihedrals.average(),
        dihedralAngleSpread = (dihedrals.maxOrNull() ?: 0.0) - (dihedrals.minOrNull() ?: 0.0),
        degenerateFaces = degenerate,
    )
}

// ==============================================================================================
// The three local steps
// ==============================================================================================

/**
 * Move each edge's two endpoints along that edge's tangency vector so the edge's nearest approach
 * to the origin tends to one. Deliberately Gauss-Seidel — each edge sees the previous edges' work —
 * which is what makes the published algorithm converge in hundreds rather than thousands of steps.
 */
private fun tangentifyStep(problem: RealizationProblem, x: DoubleArray, stability: Double) {
    val point = DoubleArray(3)
    for (edge in 0 until problem.edgeCount) {
        val a = problem.edgeA(edge)
        val b = problem.edgeB(edge)
        edgeTangentPoint(x, a, b, point)
        val length = sqrt(point[0] * point[0] + point[1] * point[1] + point[2] * point[2])
        if (length <= 1e-12) continue
        val gain = (1.0 - length) * stability / 2.0
        for (axis in 0..2) {
            val delta = gain * point[axis]
            x[3 * a + axis] += delta
            x[3 * b + axis] += delta
        }
    }
}

/** Translate so the centroid of the edge tangency points is the origin. */
private fun recentreOnTangencyPoints(problem: RealizationProblem, x: DoubleArray) {
    if (problem.edgeCount == 0) return
    val sum = DoubleArray(3)
    val point = DoubleArray(3)
    for (edge in 0 until problem.edgeCount) {
        edgeTangentPoint(x, problem.edgeA(edge), problem.edgeB(edge), point)
        for (axis in 0..2) sum[axis] += point[axis]
    }
    for (axis in 0..2) sum[axis] /= problem.edgeCount
    for (vertex in 0 until problem.vertexCount) for (axis in 0..2) x[3 * vertex + axis] -= sum[axis]
}

/**
 * Pull every face's corners toward that face's own best-fit plane. Jacobi rather than Gauss-Seidel:
 * each face reads the state at the start of the step and the per-vertex corrections accumulate, so
 * a vertex shared by five faces is not biased toward whichever face happened to be visited last.
 */
private fun planarizeStep(problem: RealizationProblem, x: DoubleArray, stability: Double) {
    val read = x.copyOf()
    for (cycle in problem.faceCycles) {
        val plane = facePlane(read, cycle)
        if (plane.normalLength <= 1e-12) continue
        val nx = plane.nx / plane.normalLength
        val ny = plane.ny / plane.normalLength
        val nz = plane.nz / plane.normalLength
        for (vertex in cycle) {
            val gap = (plane.cx - read[3 * vertex]) * nx +
                (plane.cy - read[3 * vertex + 1]) * ny +
                (plane.cz - read[3 * vertex + 2]) * nz
            val step = stability * gap
            x[3 * vertex] += step * nx
            x[3 * vertex + 1] += step * ny
            x[3 * vertex + 2] += step * nz
        }
    }
}

private fun maxVertexMovement(before: DoubleArray, after: DoubleArray): Double {
    var worst = 0.0
    for (vertex in 0 until before.size / 3) {
        val dx = after[3 * vertex] - before[3 * vertex]
        val dy = after[3 * vertex + 1] - before[3 * vertex + 1]
        val dz = after[3 * vertex + 2] - before[3 * vertex + 2]
        val moved = sqrt(dx * dx + dy * dy + dz * dz)
        if (moved > worst) worst = moved
    }
    return worst
}

/** Face centroids, in `topology.elements(2)` order. Handy when inspecting a canonical result. */
fun PolyhedralObject.faceCentroids(): List<Vec3> {
    val problem = RealizationProblem.of(this)
    val x = problem.initialCoordinates
    return problem.faceCycles.map { cycle ->
        val centre = Vec3()
        cycle.forEach { centre.add(Vec3(x[3 * it].toFloat(), x[3 * it + 1].toFloat(), x[3 * it + 2].toFloat())) }
        centre.mul(1f / cycle.size)
    }
}
