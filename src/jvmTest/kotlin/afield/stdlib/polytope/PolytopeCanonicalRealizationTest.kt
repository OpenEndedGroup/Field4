package afield.stdlib.polytope

import afield.stdlib.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Numeric invariants of the canonical form. These are the tests that fail if canonicalization
 * regresses: they assert the three defining conditions (one common edge-tangency radius, the
 * tangency centroid at the origin, planar faces) plus the dihedral spread that says the result
 * actually looks like the solid it should be.
 *
 * Tolerances are set an order of magnitude above the achievable floor, which is about `1e-7`
 * relative: [Realization3] stores single-precision [Vec3], so a double-precision fixed point is
 * rounded on the way out and no assertion below that measures the algorithm.
 */
class PolytopeCanonicalRealizationTest {

    /** One Conway prefix expression per implemented operator, over one seed. */
    private val operatorCoverage = ConwayOperator.entries.map { "${it.symbol}C" }

    @Test
    fun everyImplementedOperatorHasACanonicalFormWithMeasurableProperties() {
        for (expression in listOf("C") + operatorCoverage) {
            val before = conway(expression).polyhedron
            val result = before.canonicalize()
            val after = result.finalDiagnostics
            val report = "$expression: ${result.reason} after ${result.iterations} iterations " +
                "(reciprocal ${result.reciprocalIterations}, relaxation ${result.relaxationIterations}, " +
                "reseeded ${result.reseeded})\n  before ${before.canonicalDiagnostics()}\n  after  $after"

            assertTrue(result.converged, report)
            assertTrue(after.degenerateFaces.isEmpty(), report)
            assertTrue(after.tangencyRadiusSpread < 5e-6, report)
            assertTrue(after.tangencyCentroidOffset < 1e-6, report)
            assertTrue(after.maxFacePlanarityError < 1e-6, report)
            // Every edge is tangent to the unit midsphere, because that is the scale the gauge
            // declared and the tangency radii all agree.
            assertTrue(abs(after.tangencyRadiusMean - 1.0) < 1e-6, report)
            // A canonical Conway solid is convex: every dihedral is a genuine interior angle.
            assertTrue(after.dihedralAngleMin > 0.0 && after.dihedralAngleMax < PI, report)
            // Only coordinates move.
            assertTrue(before.topology === result.polyhedron.topology, "topology identity lost for $expression")
            assertEquals(before.attributes, result.polyhedron.attributes, expression)
        }
    }

    @Test
    fun canonicalCubeIsTheCubeAndTheCanonicalTruncatedIcosahedronIsTheFootball() {
        val cube = cube().canonicalize().finalDiagnostics
        assertTrue(cube.tangencyRadiusSpread < 1e-7, "$cube")
        // The cube's dihedral angle is a right angle; the canonical form must reproduce it exactly.
        assertTrue(abs(cube.dihedralAngleMean - PI / 2) < 1e-6, "$cube")
        assertTrue(cube.dihedralAngleSpread < 1e-6, "$cube")

        // tI: the football. 60 vertices, 90 edges, 12 pentagons and 20 hexagons. Its two dihedral
        // angles are 138.19° (hexagon-hexagon) and 142.62° (pentagon-hexagon) — a bounded spread
        // that no lumpy embedding reproduces by accident.
        val football = canonicalConway("tI").polyhedron
        assertEquals(60, football.topology.elements(0).size)
        assertEquals(90, football.topology.elements(1).size)
        assertEquals(32, football.topology.elements(2).size)
        assertEquals(
            mapOf(5 to 12, 6 to 20),
            football.topology.elements(2).groupingBy { football.topology.faceCycle(it.id).size }.eachCount(),
        )

        val diagnostics = football.canonicalDiagnostics()
        assertTrue(diagnostics.tangencyRadiusSpread < 5e-6, "$diagnostics")
        assertTrue(diagnostics.maxFacePlanarityError < 1e-6, "$diagnostics")
        assertTrue(
            abs(Math.toDegrees(diagnostics.dihedralAngleMin) - 138.19) < 0.05,
            "hexagon-hexagon dihedral was ${Math.toDegrees(diagnostics.dihedralAngleMin)}",
        )
        assertTrue(
            abs(Math.toDegrees(diagnostics.dihedralAngleMax) - 142.62) < 0.05,
            "pentagon-hexagon dihedral was ${Math.toDegrees(diagnostics.dihedralAngleMax)}",
        )
    }

    @Test
    fun canonicalGyroDodecahedronIsThePentagonalHexecontahedron() {
        val solid = canonicalConway("gD").polyhedron
        assertEquals(92, solid.topology.elements(0).size)
        assertEquals(150, solid.topology.elements(1).size)
        assertEquals(60, solid.topology.elements(2).size)
        assertTrue(solid.topology.elements(2).all { solid.topology.faceCycle(it.id).size == 5 })

        val diagnostics = solid.canonicalDiagnostics()
        assertTrue(diagnostics.tangencyRadiusSpread < 5e-6, "$diagnostics")
        assertTrue(diagnostics.maxFacePlanarityError < 1e-6, "$diagnostics")
        // The pentagonal hexecontahedron is face-transitive: one dihedral angle, everywhere. Its
        // published value is 153.1786°.
        assertTrue(diagnostics.dihedralAngleSpread < 1e-4, "$diagnostics")
        assertTrue(
            abs(Math.toDegrees(diagnostics.dihedralAngleMean) - 153.1786) < 0.01,
            "hexecontahedron dihedral was ${Math.toDegrees(diagnostics.dihedralAngleMean)}",
        )
    }

    @Test
    fun canonicalizationIsAnImprovementAndNotJustAnEndState() {
        // The per-operator realization policies are local heuristics; their output is lumpy, and a
        // gyro leaves whole groups of faces coplanar. Each of these starts far from canonical.
        for (expression in listOf("gC", "sC", "gD", "bD", "tC", "mC")) {
            val raw = conway(expression).polyhedron
            val before = raw.canonicalDiagnostics()
            val result = raw.canonicalize()
            val after = result.finalDiagnostics
            val report = "$expression (reseeded ${result.reseeded})\n  before $before\n  after  $after"
            assertTrue(before.tangencyRadiusSpread > 1e-3, "$expression was already canonical: $before")
            assertTrue(after.tangencyRadiusSpread < before.tangencyRadiusSpread * 1e-2, report)
            assertTrue(after.maxFacePlanarityError < 1e-6, report)
        }
    }

    @Test
    fun aCanonicalFormThatCannotBeFoundIsReportedAndNotFaked() {
        // kis applied twice to a cube makes a spiked 72-triangle solid whose canonical form both
        // iterations walk away from. The contract is that this is *reported*, not smoothed over.
        val hard = conway("kkC").polyhedron.canonicalize()
        assertTrue(!hard.converged, "kkC unexpectedly converged: ${hard.finalDiagnostics}")
        assertTrue(hard.finalDiagnostics.tangencyRadiusSpread > 1e-3, "${hard.finalDiagnostics}")
        assertEquals("max iterations", hard.reason)
        // And the failure never produces non-finite coordinates a renderer would choke on.
        val coordinates = assertNotNull(hard.polyhedron.realization).vertexCoordinates
        assertTrue(coordinates.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
    }

    @Test
    fun bothCanonicalMethodsReachTheSameFixedPoint() {
        val source = conway("tC").polyhedron
        val reciprocal = source.canonicalize(CanonicalizeOptions(method = CanonicalMethod.RECIPROCAL))
        val local = source.canonicalize(
            CanonicalizeOptions(method = CanonicalMethod.LOCAL_RELAXATION, maxIterations = 20000),
        )
        assertEquals(0, reciprocal.relaxationIterations)
        assertEquals(0, local.reciprocalIterations)
        assertTrue(reciprocal.finalDiagnostics.tangencyRadiusSpread < 5e-6, "${reciprocal.finalDiagnostics}")
        assertTrue(local.finalDiagnostics.tangencyRadiusSpread < 1e-3, "${local.finalDiagnostics}")
        // The same solid: an Archimedean truncated cube either way.
        assertTrue(
            abs(reciprocal.finalDiagnostics.dihedralAngleMax - local.finalDiagnostics.dihedralAngleMax) < 1e-3,
            "${reciprocal.finalDiagnostics} vs ${local.finalDiagnostics}",
        )
        // Reciprocation is the fast one, by two orders of magnitude in iteration count.
        assertTrue(
            reciprocal.iterations * 20 < local.iterations,
            "reciprocal ${reciprocal.iterations} vs local ${local.iterations}",
        )
    }

    @Test
    fun combinatorialOrbitsMatchTheKnownSymmetryClassesOfTheSeeds() {
        val cube = cube().combinatorialOrbits()
        assertTrue(cube.isFullyTransitive, "${cube.summary()}")

        // Truncated cube: one vertex orbit, two edge orbits (triangle edges and octagon edges),
        // two face orbits — 8 triangles and 6 octagons.
        val truncated = conway("tC").polyhedron.combinatorialOrbits()
        assertEquals(1, truncated.vertexClassCount, "${truncated.summary()}")
        assertEquals(2, truncated.edgeClassCount, "${truncated.summary()}")
        assertEquals(listOf(6, 8), truncated.faceClasses.map { it.size }.sorted())

        // Rhombicuboctahedron (eC): one vertex orbit, 26 faces in classes refinement can separate.
        val expanded = conway("eC").polyhedron.combinatorialOrbits()
        assertEquals(1, expanded.vertexClassCount, "${expanded.summary()}")
        assertEquals(26, expanded.faceClasses.sumOf { it.size })

        // Pentagonal hexecontahedron (gD): face-transitive, but three vertex classes (12 of degree
        // five, 20 of degree three, 60 of degree three in a different position).
        val hexecontahedron = conway("gD").polyhedron.combinatorialOrbits()
        assertEquals(listOf(60), hexecontahedron.faceClasses.map { it.size })
        assertEquals(listOf(12, 20, 60), hexecontahedron.vertexClasses.map { it.size }.sorted())
    }

    /**
     * The refinement stops when the class count stops growing. This asserts the two halves of that
     * claim separately: the round count is bounded by the rounds the shape actually needs — a
     * handful — and not by the `elements + 2` worst-case budget, and stopping there costs nothing,
     * because spending the whole budget lands on the same partition.
     */
    @Test
    fun combinatorialOrbitRefinementStopsAsSoonAsTheClassCountIsStable() {
        // gD is the largest of the seeds here: 92 vertices, 150 edges, 60 faces.
        for (expression in listOf("C", "tC", "eC", "gD", "bC")) {
            val topology = conway(expression).polyhedron.topology
            val elements = (0..2).sumOf { topology.elements(it).size }
            val budget = elements + 2

            val early = topology.refineCombinatorialOrbits()
            val full = topology.refineCombinatorialOrbits(stopWhenStable = false)
            val report = "$expression: $elements elements, stopped after ${early.rounds} of $budget rounds"

            // Bounded by the rounds needed, not by the budget: colour refinement on these shapes
            // reaches its fixed point almost immediately, and the seed colouring does much of it.
            // Measured: 1 round for C, 2 for tC and bC, 3 for eC, 4 for gD.
            assertTrue(early.rounds in 1..8, report)
            assertTrue(early.rounds < budget, report)
            assertEquals(budget, full.rounds, "the unbounded run should spend the whole budget")

            // ...and the early stop is not an approximation: same classes, same order.
            assertEquals(full.orbits, early.orbits, report)
            assertEquals(early.orbits, topology.combinatorialOrbits(), report)
        }
    }

    @Test
    fun canonicalFormRespectsTheCombinatorialOrbits() {
        for (expression in listOf("C", "tC", "gD", "eC", "bC")) {
            val canonical = canonicalConway(expression).polyhedron
            val deviation = canonical.symmetryDeviation()
            assertTrue(deviation.edgeLengthSpread < 5e-5, "$expression edges: $deviation")
            assertTrue(deviation.vertexRadiusSpread < 5e-5, "$expression vertices: $deviation")
            assertTrue(deviation.faceDistanceSpread < 5e-5, "$expression faces: $deviation")
        }
    }

    @Test
    fun symmetryOrbitConstraintDrivesAPerturbedCubeBackToOneOrbit() {
        val source = cube()
        val perturbed = source.copy(
            realization = Realization3(
                assertNotNull(source.realization).vertexCoordinates.mapIndexed { at, point ->
                    if (at == 0) Vec3(point).mul(1.35f) else point
                },
            ),
        )
        val before = perturbed.symmetryDeviation()
        assertTrue(before.edgeLengthSpread > 0.1, "$before")

        val solved = perturbed.relax(RelaxationPlan.symmetricCanonical(perturbed))
        val after = solved.polyhedron.symmetryDeviation()
        assertTrue(after.edgeLengthSpread < before.edgeLengthSpread * 0.05, "$before -> $after")

        val initialSymmetry = solved.initialConstraints.single { it.name == "symmetry-orbit" }
        val finalSymmetry = solved.finalConstraints.single { it.name == "symmetry-orbit" }
        assertTrue(finalSymmetry.rms < initialSymmetry.rms * 0.05, "$initialSymmetry -> $finalSymmetry")
    }

    @Test
    fun relaxReportsEveryDeclaredFamilyAndTheLegacyWrapperStillRepairs() {
        val source = cube()
        val perturbed = source.copy(
            realization = Realization3(
                assertNotNull(source.realization).vertexCoordinates.mapIndexed { at, point ->
                    if (at == 0) Vec3(point).add(0.28f, -0.17f, 0.31f) else point
                },
            ),
        )

        val solved = perturbed.solveRealization()
        assertEquals(
            listOf("edge-length", "face-planarity", "centroid", "common-radius", "proximity"),
            solved.finalConstraints.map { it.name },
        )
        val edgeBefore = solved.initialConstraints.single { it.name == "edge-length" }
        val edgeAfter = solved.finalConstraints.single { it.name == "edge-length" }
        assertTrue(edgeAfter.rms < edgeBefore.rms * 0.1, "$edgeBefore -> $edgeAfter")
        assertTrue(solved.converged, solved.reason)

        // Reweighting is a plan-level edit of a value, not a new solver.
        val withoutEdgeLengths = RelaxationPlan.regularize().withWeight("edge-length", 0.0)
        assertEquals(0.0, withoutEdgeLengths.constraints.single { it.name == "edge-length" }.weight)
        val relaxed = perturbed.relax(withoutEdgeLengths)
        val planarBefore = relaxed.initialConstraints.single { it.name == "face-planarity" }
        val planarAfter = relaxed.finalConstraints.single { it.name == "face-planarity" }
        assertTrue(planarAfter.rms < planarBefore.rms * 0.5, "$planarBefore -> $planarAfter")
        // The family it was told to ignore is still reported, honestly, as unimproved.
        assertTrue(
            relaxed.finalConstraints.single { it.name == "edge-length" }.rms > 0.05,
            "${relaxed.finalConstraints}",
        )
    }

    @Test
    fun canonicalConditionsCanAlsoBeSolvedAsADeclaredPlan() {
        val source = conway("aC").polyhedron
        val solved = source.relax(RelaxationPlan.canonicalConditions())
        assertEquals(
            // The fourth is the floor guarding edge-tangency's zero-cost minimum, not a shape family.
            listOf("face-planarity", "edge-tangency", "tangency-centroid", "min-edge-length"),
            solved.finalConstraints.map { it.name },
        )
        val diagnostics = solved.polyhedron.canonicalDiagnostics()
        assertTrue(diagnostics.tangencyRadiusSpread < 1e-4, "$diagnostics")
        assertTrue(diagnostics.tangencyCentroidOffset < 1e-4, "$diagnostics")
        // And it agrees with the specialised iteration on the answer.
        val iterated = source.canonicalize().finalDiagnostics
        assertTrue(
            abs(diagnostics.dihedralAngleMean - iterated.dihedralAngleMean) < 1e-3,
            "plan $diagnostics vs iteration $iterated",
        )
    }

    @Test
    fun spectralRealizationPlusCanonicalizationRebuildsASolidFromCombinatoricsAlone() {
        // Deliberately throw the coordinates away: only the abstract topology survives.
        val topologyOnly = PolyhedralObject(dodecahedron().topology)
        assertNull(topologyOnly.realization)

        val spectral = topologyOnly.realize(SpectralRealization())
        assertNotNull(spectral.realization)
        val canonical = spectral.canonicalize()
        val diagnostics = canonical.finalDiagnostics

        assertTrue(canonical.converged, "${canonical.reason} after ${canonical.iterations}: $diagnostics")
        assertTrue(diagnostics.tangencyRadiusSpread < 5e-6, "$diagnostics")
        assertTrue(diagnostics.maxFacePlanarityError < 1e-6, "$diagnostics")
        assertTrue(diagnostics.degenerateFaces.isEmpty(), "$diagnostics")
        // The regular dodecahedron's dihedral angle is 116.5651°, recovered from combinatorics.
        assertTrue(
            abs(Math.toDegrees(diagnostics.dihedralAngleMean) - 116.5651) < 1e-3,
            "dihedral was ${Math.toDegrees(diagnostics.dihedralAngleMean)}",
        )
        assertTrue(diagnostics.dihedralAngleSpread < 1e-5, "$diagnostics")
    }

    @Test
    fun aBadStartingEmbeddingIsReseededFromTheTopologyAlone() {
        // The gyro realization policy leaves every group of five new faces coplanar, so the dual
        // has coincident vertices and reciprocation cannot start. Re-seeding rescues it.
        val gyro = conway("gC").polyhedron
        assertTrue(gyro.canonicalDiagnostics().dihedralAngleMax > PI - 1e-6, "gyro output was not flat-faced")

        val reseeded = gyro.canonicalize()
        assertTrue(reseeded.reseeded, "expected a re-seed: ${reseeded.reason}")
        assertTrue(reseeded.converged, "${reseeded.reason}: ${reseeded.finalDiagnostics}")
        assertTrue(reseeded.finalDiagnostics.tangencyRadiusSpread < 5e-6, "${reseeded.finalDiagnostics}")

        // With re-seeding switched off the same input fails, and says so rather than pretending.
        val strict = gyro.canonicalize(CanonicalizeOptions(reseed = null))
        assertTrue(!strict.reseeded)
        assertEquals("diverged", strict.reason)
        assertTrue(strict.finalDiagnostics.tangencyRadiusSpread > 1e-3, "${strict.finalDiagnostics}")
    }

    @Test
    fun theRigidGaugeRemovesTranslationRotationAndScale() {
        val original = cube().canonicalize().polyhedron
        val angle = 0.7
        val moved = original.copy(
            realization = Realization3(
                assertNotNull(original.realization).vertexCoordinates.map { point ->
                    val x = point.x * cos(angle).toFloat() - point.z * sin(angle).toFloat()
                    val z = point.x * sin(angle).toFloat() + point.z * cos(angle).toFloat()
                    Vec3(x * 3.7f + 12f, point.y * 3.7f - 5f, z * 3.7f + 2f)
                },
            ),
        )
        val restored = moved.normalize(RigidGauge.CANONICAL, reference = original.realization)
        val a = assertNotNull(original.realization).vertexCoordinates
        val b = assertNotNull(restored.realization).vertexCoordinates
        val worst = a.indices.maxOf { Vec3(a[it]).sub(b[it]).length().toDouble() }
        assertTrue(worst < 1e-5, "gauge left a residual displacement of $worst")

        // Without the reference the orientation is left exactly as supplied, so only the
        // translation and scale are removed — the gauge never rotates behind the caller's back.
        val centredOnly = moved.normalize(RigidGauge(alignToReference = false))
        val centre = assertNotNull(centredOnly.realization).vertexCoordinates
            .fold(Vec3()) { sum, point -> sum.add(point) }
            .mul(1f / moved.topology.elements(0).size)
        assertTrue(centre.length() < 1e-5, "centroid gauge left the object at $centre")
    }

    @Test
    fun canonicalizeKeepsLineageAndProvenanceWhenUsedInAConwayChain() {
        val plain = conway("kC")
        val canonical = plain.canonicalize()
        assertEquals(plain.history, canonical.history)
        assertEquals(plain.notationHistory, canonical.notationHistory)
        assertEquals(plain.deployments.size, canonical.deployments.size)
        for (face in canonical.polyhedron.topology.elements(2)) {
            assertEquals(plain.sourceElements(face.id), canonical.sourceElements(face.id))
        }
        assertTrue(canonical.polyhedron.canonicalDiagnostics().tangencyRadiusSpread < 5e-6)
        // And the summary keeps reporting the Conway chain rather than the realization run.
        assertEquals(listOf("kis"), canonical.summary().requestedOperations)
    }

    // ---- MinEdgeLengthConstraint: the guard on EdgeTangencyConstraint's zero-cost minimum --------

    private fun minEdgeOverMean(problem: RealizationProblem, p: PolyhedralObject): Double {
        val coordinates = p.realization!!.vertexCoordinates
        val x = DoubleArray(coordinates.size * 3)
        coordinates.forEachIndexed { i, v ->
            x[3 * i] = v.x.toDouble()
            x[3 * i + 1] = v.y.toDouble()
            x[3 * i + 2] = v.z.toDouble()
        }
        val lengths = (0 until problem.edgeCount).map { edge ->
            val a = problem.edgeA(edge)
            val b = problem.edgeB(edge)
            val dx = x[3 * b] - x[3 * a]
            val dy = x[3 * b + 1] - x[3 * a + 1]
            val dz = x[3 * b + 2] - x[3 * a + 2]
            kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        }
        return lengths.min() / lengths.average()
    }

    @Test
    fun theEdgeLengthFloorContributesExactlyZeroAboveItself() {
        // Not "small" — zero. That is what makes it safe to have on by default, and it is why every
        // healthy solid below is bit-identical with and without it.
        val problem = RealizationProblem.of(conway("kC").polyhedron)
        val residuals = DoubleArray(problem.edgeCount)
        MinEdgeLengthConstraint(fraction = 0.01)
            .residuals(problem, problem.initialCoordinates, residuals, 0)
        assertTrue(residuals.all { it == 0.0 }, "a healthy solid must contribute exactly 0.0, got ${residuals.max()}")
    }

    @Test
    fun theEdgeLengthFloorPushesBackOnlyBelowItself() {
        val problem = RealizationProblem.of(conway("C").polyhedron)
        val x = problem.initialCoordinates.copyOf()
        // Collapse one edge's endpoints onto each other.
        val a = problem.edgeA(0)
        val b = problem.edgeB(0)
        for (axis in 0..2) x[3 * b + axis] = x[3 * a + axis]
        val residuals = DoubleArray(problem.edgeCount)
        val floor = 0.01 * problem.initialMeanEdgeLength
        MinEdgeLengthConstraint(fraction = 0.01).residuals(problem, x, residuals, 0)
        assertEquals(floor, residuals[0], 1e-12, "a collapsed edge must be pushed by the whole floor")
        assertTrue(
            residuals.drop(1).all { it == 0.0 },
            "collapsing one edge must not make any other edge's residual non-zero",
        )
    }

    @Test
    fun canonicalConditionsCarriesTheFloorSoBothRealizersMinimiseTheSameFunction() {
        val plan = RelaxationPlan.canonicalConditions()
        assertEquals(1, plan.constraints.count { it is MinEdgeLengthConstraint })
        assertTrue(RelaxationPlan.canonicalConditions(minEdgeLengthFraction = 0.0)
            .constraints.none { it is MinEdgeLengthConstraint })
        // And it crosses over to Ceres rather than being rejected as an unsupported family.
        assertEquals(emptyList(), CeresRealization.unsupported(plan))
    }

    @Test
    fun theFloorLeavesAHealthySolidBitIdentical() {
        val seed = conway("kC").polyhedron
        val withFloor = seed.relax(RelaxationPlan.canonicalConditions()).polyhedron
        val without = seed.relax(RelaxationPlan.canonicalConditions(minEdgeLengthFraction = 0.0)).polyhedron
        val a = withFloor.realization!!.vertexCoordinates
        val b = without.realization!!.vertexCoordinates
        // Bit-identical, so exact equality rather than a tolerance.
        for (i in a.indices) {
            assertEquals(b[i].x, a[i].x, "vertex $i x moved")
            assertEquals(b[i].y, a[i].y, "vertex $i y moved")
            assertEquals(b[i].z, a[i].z, "vertex $i z moved")
        }
    }

    @Test
    fun theFloorReducesTheEdgeRelaxCollapsesOnKkT() {
        // `kkT` is where `relax` actually falls into the minimum EdgeTangencyConstraint documents:
        // without a floor it returns a tangency spread of 1e-4 bought with an edge at 7e-4 of the
        // mean. The floor is soft — a least-squares penalty, not a hard constraint — so it does not
        // reach the floor itself, but it is an order of magnitude less degenerate. The spread gets
        // *worse* in exchange, which is the point: that number was measuring a folded solid.
        val seed = conway("kkT").polyhedron
        val problem = RealizationProblem.of(seed)
        val without = seed.relax(RelaxationPlan.canonicalConditions(minEdgeLengthFraction = 0.0)).polyhedron
        val withFloor = seed.relax(RelaxationPlan.canonicalConditions()).polyhedron
        val ratioWithout = minEdgeOverMean(problem, without)
        val ratioWith = minEdgeOverMean(problem, withFloor)
        assertTrue(ratioWithout < 1e-3, "expected relax to collapse an edge without a floor, got $ratioWithout")
        assertTrue(
            ratioWith > ratioWithout * 5.0,
            "the floor should materially open the collapsed edge: $ratioWithout -> $ratioWith",
        )
    }
}
