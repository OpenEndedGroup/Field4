@file:afield.runtime.completion.CompletionCategory("Polytopes")

package afield.geometry.polytope

import afield.math.linalg.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Canonicalization: the step that turns combinatorially-correct-but-lumpy Conway output into the
 * solid you were picturing.
 *
 * A polyhedron's **canonical form** is the embedding in which every edge is tangent to one common
 * sphere, the centroid of those tangency points is the origin, and every face is planar. For a convex
 * polyhedron it exists, and — up to Möbius transformations of the sphere, which the origin-centring
 * fixes — it is unique. It is also determined by the *combinatorics alone*, which is the fact that
 * makes this file useful: the coordinates you hand in are only a starting guess, so
 * `canonicalConway("gD")` and a hand-built pentagonal hexecontahedron converge to the same solid.
 *
 * ```kotlin
 * val solid = canonicalConway("bkD").polyhedron        // notation straight to geometry
 * val result = cube().ambo().polyhedron.canonicalize() // or repair an embedding you already have
 * ```
 *
 * ## The two iterations
 *
 * [CanonicalMethod.RECIPROCAL] alternates polar reciprocation between primal and dual and reaches the
 * fixed point in tens of steps, but requires every face plane to stay away from the origin.
 * [CanonicalMethod.LOCAL_RELAXATION] takes small bounded local steps — tangency, recentring,
 * planarity — and is slow but robust. The default runs the first and polishes with the second. When an
 * attempt fails, [CanonicalizeOptions.reseed] restarts from coordinates built from the topology alone;
 * [CanonicalizeResult.reseeded] always says whether that happened.
 *
 * ## Read the diagnostics
 *
 * [CanonicalizeResult.converged] means the iteration stopped moving, and no more than that.
 * [CanonicalDiagnostics] is the evidence, reported before and after, and a run that ends with
 * a large `tangencyRadiusSpread` has failed and says so. The floor on all of it is about `1e-7`
 * relative, because [Realization3] stores single-precision coordinates.
 */

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
    /**
     * Smallest distance from the origin to any edge's nearest point. Zero when there are no edges.
     */
    val tangencyRadiusMin: Double,
    /**
     * Largest such distance. Compare with [tangencyRadiusMin] through [tangencyRadiusSpread] rather than
     * directly, since both scale with the object.
     */
    val tangencyRadiusMax: Double,
    /**
     * Mean tangency radius — the midsphere's radius, once the spread is small enough for the word to mean
     * anything. `canonicalize` normalises this to 1.
     */
    val tangencyRadiusMean: Double,
    /** `(max - min) / mean`: zero exactly when every edge touches one common sphere. */
    val tangencyRadiusSpread: Double,
    /** Distance from the origin to the centroid of the edge tangency points. */
    val tangencyCentroidOffset: Double,
    /**
     * The worst single corner-to-plane distance, over every corner of every face, against that face's own
     * best-fit plane. A length, so read it against [tangencyRadiusMean].
     */
    val maxFacePlanarityError: Double,
    /**
     * Root-mean-square of the same corner-to-plane distances. Says whether the worst corner is an outlier
     * or the whole object is bent.
     */
    val facePlanarityRmsError: Double,
    /**
     * Smallest interior dihedral angle, in radians, measured between adjacent faces' unit normals.
     *
     * Only edges with exactly two incident faces are measured; the rest are skipped silently, so on a
     * non-manifold complex this summarises a subset. Zero when nothing qualified.
     */
    val dihedralAngleMin: Double,
    /**
     * Largest interior dihedral angle, in radians.
     */
    val dihedralAngleMax: Double,
    /**
     * Mean interior dihedral angle, in radians, over the measured edges.
     */
    val dihedralAngleMean: Double,
    /** `max - min` over edges, in radians. Small means one Archimedean-looking solid, not a lump. */
    val dihedralAngleSpread: Double,
    /**
     * Faces whose best-fit plane has no usable normal — collapsed, or all corners colinear.
     *
     * These are reported rather than excluded: their corners still contribute to the planarity figures and
     * their meaningless normal still enters the dihedral angles of their edges. A non-empty list means the
     * other numbers on this record are not to be trusted.
     */
    val degenerateFaces: List<ElementId>,
    /**
     * Signed volume enclosed by the faces, by the divergence theorem over a centroid fan of each face.
     * Negative means the embedding has turned inside out.
     */
    val signedVolume: Double = 0.0,
    /**
     * Pairs of faces whose surfaces cross, counted between triangles of the faces' centroid fans that
     * share no corner. Non-zero means the surface passes through itself: tangency and planarity can
     * still all be satisfied, and a `Solid` made from it still reports `NO_ERROR`.
     */
    val selfIntersectingFacePairs: Int = 0,
) {
    /**
     * Whether this is an embedding of a solid at all: positive volume and a surface that does not pass
     * through itself. Read it before the canonical figures, which can all be excellent when it is false.
     */
    val isEmbedded: Boolean get() = signedVolume > 0.0 && selfIntersectingFacePairs == 0
}

/**
 * Which iteration drives the embedding toward the canonical conditions.
 *
 * `RECIPROCAL` reciprocates primal against dual — tens of steps, exact fixed point, and it needs every
 * face plane to stay clear of the origin, which is true of a convex solid and can fail spectacularly
 * for one that is not. `LOCAL_RELAXATION` nudges tangency radii, recentres and planarizes — hundreds to
 * thousands of small bounded steps, slow but graceful where reciprocation blows up.
 * `RECIPROCAL_THEN_LOCAL` does the first to get close and the second to polish, and is the default.
 */
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
    /**
     * Which iteration to run. Changing this is the first thing to try on an input that will not converge.
     */
    val method: CanonicalMethod = CanonicalMethod.RECIPROCAL_THEN_LOCAL,
    /**
     * Cap on the reciprocal phase. Generous: convergence is usually two orders of magnitude faster, and a
     * run that needs all 600 has almost certainly diverged and been rolled back.
     */
    val reciprocalIterations: Int = 600,
    /**
     * Cap on the local relaxation phase when it is doing the real work rather than polishing.
     */
    val maxIterations: Int = 2000,
    /** Local steps run after a *converged* reciprocal phase, purely to polish planarity. */
    val polishIterations: Int = 64,
    /**
     * Fraction of each local correction applied per relaxation step, in (0, 1]. See this class's own note:
     * larger is faster and oscillates on high-degree Conway outputs.
     */
    val stability: Double = 0.1,
    /** Convergence test on the largest single-vertex movement in one iteration. */
    val movementTolerance: Double = 1e-10,
    /**
     * Include the step that pulls each face's corners onto that face's best-fit plane. Turning both this
     * and [tangentify] off is rejected, since it would leave nothing to iterate.
     */
    val planarize: Boolean = true,
    /**
     * Include the step that drives every edge's tangency radius toward one, and the recentring that goes
     * with it.
     */
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
 * The outcome of a canonicalization run, including the before/after evidence.
 *
 * [converged] means the iteration stopped moving, not that the result is the canonical form: only
 * the diagnostics can say that, and for a non-convex or non-polyhedral input no canonical form need
 * exist at all.
 */
data class CanonicalizeResult(
    /**
     * The answer: same topology, same attributes, new coordinates, gauged if a gauge was asked for.
     */
    val polyhedron: PolyhedralObject,
    val initialDiagnostics: CanonicalDiagnostics,
    val finalDiagnostics: CanonicalDiagnostics,
    /**
     * [reciprocalIterations] plus [relaxationIterations] — total steps actually taken, across both phases
     * and for whichever attempt was kept.
     */
    val iterations: Int,
    /**
     * The iteration stopped moving. **Not** a claim that the result is canonical — read
     * [finalDiagnostics] for that.
     */
    val converged: Boolean,
    /** Largest single-vertex movement in the final iteration. */
    val finalMovement: Double,
    /**
     * Why the iteration stopped, in words, for a log or an error message. Not a stable enumeration; do not
     * branch on it.
     */
    val reason: String,
    /**
     * Steps spent reciprocating. Zero for [CanonicalMethod.LOCAL_RELAXATION], and zero when the topology
     * offered no dual structure to reciprocate through.
     */
    val reciprocalIterations: Int = 0,
    /**
     * Steps spent in local relaxation — including the short polish pass after a converged reciprocal
     * phase.
     */
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
        signedVolume = fanSignedVolume(x, problem.faceCycles),
        selfIntersectingFacePairs = crossingFacePairs(x, problem.faceCycles),
    )
}

/** One centroid-fan triangle of a face, as three points, plus the corner indices it touches. */
private class FanTriangle(val face: Int, val a: DoubleArray, val b: DoubleArray, val c: DoubleArray, val corners: IntArray)

private fun corner(x: DoubleArray, i: Int) = doubleArrayOf(x[3 * i], x[3 * i + 1], x[3 * i + 2])

private fun fanTriangles(x: DoubleArray, faces: List<IntArray>): List<FanTriangle> = buildList {
    faces.forEachIndexed { f, cycle ->
        val centre = DoubleArray(3)
        cycle.forEach { for (axis in 0..2) centre[axis] += x[3 * it + axis] / cycle.size }
        for (k in cycle.indices) {
            val i = cycle[k]
            val j = cycle[(k + 1) % cycle.size]
            add(FanTriangle(f, centre, corner(x, i), corner(x, j), intArrayOf(i, j)))
        }
    }
}

private fun fanSignedVolume(x: DoubleArray, faces: List<IntArray>): Double =
    fanTriangles(x, faces).sumOf { t ->
        val (a, b, c) = Triple(t.a, t.b, t.c)
        (a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0]) + a[2] * (b[0] * c[1] - b[1] * c[0])) / 6.0
    }

/** Face pairs with a crossing between fan triangles that share no corner and are not of one face. */
private fun crossingFacePairs(x: DoubleArray, faces: List<IntArray>): Int {
    val tris = fanTriangles(x, faces)
    val crossing = HashSet<Long>()
    for (p in tris.indices) for (q in p + 1 until tris.size) {
        val s = tris[p]
        val t = tris[q]
        if (s.face == t.face || s.corners.any { it in t.corners }) continue
        val key = minOf(s.face, t.face).toLong() shl 32 or maxOf(s.face, t.face).toLong()
        if (key in crossing) continue
        if (trianglesCross(s, t)) crossing += key
    }
    return crossing.size
}

private fun trianglesCross(s: FanTriangle, t: FanTriangle): Boolean {
    val sEdges = listOf(s.a to s.b, s.b to s.c, s.c to s.a)
    val tEdges = listOf(t.a to t.b, t.b to t.c, t.c to t.a)
    return sEdges.any { (p, q) -> segmentHitsTriangle(p, q, t) } || tEdges.any { (p, q) -> segmentHitsTriangle(p, q, s) }
}

/** Möller–Trumbore, with the segment's own parameter kept strictly inside (0, 1). */
private fun segmentHitsTriangle(p: DoubleArray, q: DoubleArray, t: FanTriangle): Boolean {
    val d = DoubleArray(3) { q[it] - p[it] }
    val e1 = DoubleArray(3) { t.b[it] - t.a[it] }
    val e2 = DoubleArray(3) { t.c[it] - t.a[it] }
    val h = doubleArrayOf(d[1] * e2[2] - d[2] * e2[1], d[2] * e2[0] - d[0] * e2[2], d[0] * e2[1] - d[1] * e2[0])
    val det = e1[0] * h[0] + e1[1] * h[1] + e1[2] * h[2]
    val scale = max(1e-30, sqrt(d.sumOf { it * it }) * sqrt(e1.sumOf { it * it }) * sqrt(e2.sumOf { it * it }))
    if (abs(det) < 1e-12 * scale) return false
    val inv = 1.0 / det
    val sv = DoubleArray(3) { p[it] - t.a[it] }
    val u = inv * (sv[0] * h[0] + sv[1] * h[1] + sv[2] * h[2])
    if (u <= 1e-9 || u >= 1.0 - 1e-9) return false
    val qv = doubleArrayOf(sv[1] * e1[2] - sv[2] * e1[1], sv[2] * e1[0] - sv[0] * e1[2], sv[0] * e1[1] - sv[1] * e1[0])
    val v = inv * (d[0] * qv[0] + d[1] * qv[1] + d[2] * qv[2])
    if (v <= 1e-9 || u + v >= 1.0 - 1e-9) return false
    val along = inv * (e2[0] * qv[0] + e2[1] * qv[1] + e2[2] * qv[2])
    return along > 1e-9 && along < 1.0 - 1e-9
}

// ==============================================================================================
// The three local steps
// ==============================================================================================

/**
 * Move each edge's two endpoints along that edge's tangency vector so the edge's nearest approach
 * to the origin tends to one. Gauss-Seidel rather than Jacobi — each edge sees the previous edges' work —
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
val PolyhedralObject.faceCentroids: List<Vec3>
    get() {
        val problem = RealizationProblem.of(this)
        val x = problem.initialCoordinates
        return problem.faceCycles.map { cycle ->
            val centre = Vec3()
            cycle.forEach { centre.add(Vec3(x[3 * it].toFloat(), x[3 * it + 1].toFloat(), x[3 * it + 2].toFloat())) }
            centre.mul(1f / cycle.size)
        }
    }
