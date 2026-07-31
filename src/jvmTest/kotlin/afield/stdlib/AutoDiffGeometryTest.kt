package afield.stdlib

import kotlin.math.absoluteValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Oracle checks for the tracked vector ops, and real-convergence checks for the two geometry
 * solvers — the same house rule as [AutoDiffGradientCheckTest]: every vector op is compared with a
 * finite-difference oracle at at least three distinct inputs, and every solver is asserted to have
 * *arrived*, not merely to have improved.
 */
class AutoDiffGeometryTest {

    /** Fourth-order central first derivative; the vector ops here are all smooth at these points. */
    private fun fd1(x: Double, h: Double = 1e-4, f: (Double) -> Double): Double =
        (f(x - 2 * h) - 8 * f(x - h) + 8 * f(x + h) - f(x + 2 * h)) / (12 * h)

    private fun close(label: String, actual: Double, expected: Double, tol: Double = 1e-6) {
        val scale = 1.0 + expected.absoluteValue
        val err = (actual - expected).absoluteValue / scale
        assertTrue(err <= tol, "$label: got $actual, oracle $expected, relative error $err > $tol")
    }

    // ---- tracked vectors ----------------------------------------------------------------------

    private val vec2Points = listOf(
        doubleArrayOf(1.0, 2.0, -0.5, 3.0),
        doubleArrayOf(-2.4, 0.7, 1.1, -1.3),
        doubleArrayOf(0.3, -0.9, 2.2, 0.45),
        doubleArrayOf(4.0, 3.0, -1.0, -2.0)
    )

    /** One scalar-valued expression over two 2-vectors: the AD form and the plain reference. */
    private data class Vec2Case(
        val name: String,
        val ad: (ADVec2, ADVec2) -> AD,
        val plain: (DoubleArray) -> Double
    )

    private val vec2Cases = listOf(
        Vec2Case("dot", { a, b -> a dot b }, { p -> p[0] * p[2] + p[1] * p[3] }),
        Vec2Case("cross", { a, b -> a cross b }, { p -> p[0] * p[3] - p[1] * p[2] }),
        Vec2Case("lengthSquared", { a, _ -> a.lengthSquared() }, { p -> p[0] * p[0] + p[1] * p[1] }),
        Vec2Case("length", { a, _ -> a.length() }, { p -> kotlin.math.sqrt(p[0] * p[0] + p[1] * p[1]) }),
        Vec2Case(
            "distanceTo", { a, b -> a.distanceTo(b) },
            { p -> kotlin.math.hypot(p[0] - p[2], p[1] - p[3]) }
        ),
        Vec2Case(
            "distanceSquaredTo", { a, b -> a.distanceSquaredTo(b) },
            { p -> (p[0] - p[2]) * (p[0] - p[2]) + (p[1] - p[3]) * (p[1] - p[3]) }
        ),
        Vec2Case(
            "normalized dot", { a, b -> a.normalized() dot b },
            { p -> (p[0] * p[2] + p[1] * p[3]) / kotlin.math.sqrt(p[0] * p[0] + p[1] * p[1]) }
        ),
        Vec2Case("angle", { a, _ -> a.angle() }, { p -> kotlin.math.atan2(p[1], p[0]) }),
        Vec2Case(
            "sum then length", { a, b -> (a + b).length() },
            { p -> kotlin.math.hypot(p[0] + p[2], p[1] + p[3]) }
        ),
        Vec2Case(
            "difference then length", { a, b -> (a - b).length() },
            { p -> kotlin.math.hypot(p[0] - p[2], p[1] - p[3]) }
        ),
        Vec2Case(
            "hadamard dot", { a, b -> (a * b) dot b },
            { p -> p[0] * p[2] * p[2] + p[1] * p[3] * p[3] }
        ),
        Vec2Case(
            "scaled", { a, b -> (a * 2.5) dot b },
            { p -> 2.5 * (p[0] * p[2] + p[1] * p[3]) }
        ),
        Vec2Case(
            "divided", { a, b -> (a / 2.0) dot b },
            { p -> 0.5 * (p[0] * p[2] + p[1] * p[3]) }
        ),
        Vec2Case("negated", { a, _ -> (-a).lengthSquared() }, { p -> p[0] * p[0] + p[1] * p[1] }),
        Vec2Case(
            "rotated by b.x", { a, b -> a.rotated(b.x).y },
            { p -> p[0] * kotlin.math.sin(p[2]) + p[1] * kotlin.math.cos(p[2]) }
        ),
        Vec2Case(
            "lerp at b.y", { a, b -> a.lerp(b, b.y).x },
            { p -> p[0] + (p[2] - p[0]) * p[3] }
        )
    )

    @Test
    fun everyTrackedVec2OpMatchesItsFiniteDifferenceOracle() {
        for (case in vec2Cases) {
            assertTrue(vec2Points.size >= 3, "${case.name}: needs >= 3 check inputs")
            for (point in vec2Points) {
                val g = gradAt(point) { p -> case.ad(ADVec2(p[0], p[1]), ADVec2(p[2], p[3])) }
                for (i in point.indices) {
                    val oracle = fd1(point[i]) { v -> case.plain(point.copyOf().also { it[i] = v }) }
                    close("d/dp$i ${case.name} at ${point.toList()}", g[i], oracle)
                }
            }
        }
    }

    private val vec3Points = listOf(
        doubleArrayOf(1.0, 2.0, 3.0, -0.5, 1.5, 0.25),
        doubleArrayOf(-2.4, 0.7, -1.1, 1.1, -1.3, 2.0),
        doubleArrayOf(0.3, -0.9, 0.6, 2.2, 0.45, -0.8)
    )

    private data class Vec3Case(
        val name: String,
        val ad: (ADVec3, ADVec3) -> AD,
        val plain: (DoubleArray) -> Double
    )

    private val vec3Cases = listOf(
        Vec3Case("dot", { a, b -> a dot b }, { p -> p[0] * p[3] + p[1] * p[4] + p[2] * p[5] }),
        Vec3Case(
            "cross.x", { a, b -> (a cross b).x },
            { p -> p[1] * p[5] - p[2] * p[4] }
        ),
        Vec3Case(
            "cross.z", { a, b -> (a cross b).z },
            { p -> p[0] * p[4] - p[1] * p[3] }
        ),
        Vec3Case(
            "lengthSquared", { a, _ -> a.lengthSquared() },
            { p -> p[0] * p[0] + p[1] * p[1] + p[2] * p[2] }
        ),
        Vec3Case(
            "length", { a, _ -> a.length() },
            { p -> kotlin.math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]) }
        ),
        Vec3Case(
            "distanceTo", { a, b -> a.distanceTo(b) },
            { p ->
                kotlin.math.sqrt(
                    (p[0] - p[3]) * (p[0] - p[3]) + (p[1] - p[4]) * (p[1] - p[4]) + (p[2] - p[5]) * (p[2] - p[5])
                )
            }
        ),
        Vec3Case(
            "distanceSquaredTo", { a, b -> a.distanceSquaredTo(b) },
            { p ->
                (p[0] - p[3]) * (p[0] - p[3]) + (p[1] - p[4]) * (p[1] - p[4]) + (p[2] - p[5]) * (p[2] - p[5])
            }
        ),
        Vec3Case(
            "normalized dot", { a, b -> a.normalized() dot b },
            { p ->
                (p[0] * p[3] + p[1] * p[4] + p[2] * p[5]) /
                    kotlin.math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
            }
        ),
        Vec3Case(
            "sum then length", { a, b -> (a + b).length() },
            { p ->
                kotlin.math.sqrt(
                    (p[0] + p[3]) * (p[0] + p[3]) + (p[1] + p[4]) * (p[1] + p[4]) + (p[2] + p[5]) * (p[2] + p[5])
                )
            }
        ),
        Vec3Case(
            "difference then length", { a, b -> (a - b).length() },
            { p ->
                kotlin.math.sqrt(
                    (p[0] - p[3]) * (p[0] - p[3]) + (p[1] - p[4]) * (p[1] - p[4]) + (p[2] - p[5]) * (p[2] - p[5])
                )
            }
        ),
        Vec3Case("negated dot", { a, b -> (-a) dot b }, { p -> -(p[0] * p[3] + p[1] * p[4] + p[2] * p[5]) }),
        Vec3Case(
            "scaled dot", { a, b -> (a * 1.5) dot b },
            { p -> 1.5 * (p[0] * p[3] + p[1] * p[4] + p[2] * p[5]) }
        ),
        Vec3Case(
            "lerp at b.x", { a, b -> a.lerp(b, b.x).y },
            { p -> p[1] + (p[4] - p[1]) * p[3] }
        )
    )

    @Test
    fun everyTrackedVec3OpMatchesItsFiniteDifferenceOracle() {
        for (case in vec3Cases) {
            assertTrue(vec3Points.size >= 3, "${case.name}: needs >= 3 check inputs")
            for (point in vec3Points) {
                val g = gradAt(point) { p ->
                    case.ad(ADVec3(p[0], p[1], p[2]), ADVec3(p[3], p[4], p[5]))
                }
                for (i in point.indices) {
                    val oracle = fd1(point[i]) { v -> case.plain(point.copyOf().also { it[i] = v }) }
                    close("d/dp$i ${case.name} at ${point.toList()}", g[i], oracle)
                }
            }
        }
    }

    @Test
    fun trackedVectorsRoundTripToFieldsOwnVectorTypes() {
        val tape = ADTape()
        val v2 = Vec2(3.5f, -1.25f)
        val tracked2 = v2.onTape(tape)
        assertEquals(3.5, tracked2.x.value, 0.0)
        assertEquals(-1.25, tracked2.y.value, 0.0)
        assertEquals(v2, tracked2.value)

        val v3 = Vec3(0.5f, 2.0f, -3.0f)
        val tracked3 = v3.onTape(tape)
        assertEquals(v3, tracked3.value)
    }

    // ---- inverse kinematics --------------------------------------------------------------------

    private val chain = IkChain(Vec2(120f, 300f), doubleArrayOf(90.0, 80.0, 70.0, 55.0))

    @Test
    fun forwardKinematicsAgreesBetweenThePlainAndTrackedPaths() {
        val configurations = listOf(
            doubleArrayOf(0.0, 0.0, 0.0, 0.0),
            doubleArrayOf(0.4, -0.3, 0.9, 0.2),
            doubleArrayOf(-1.2, 0.75, -0.4, 1.6)
        )
        for (angles in configurations) {
            val plain = chain.endEffectorAt(angles)
            val tape = ADTape()
            val tracked = chain.endEffectorAD(tape.variables(angles))
            assertEquals(plain.x.toDouble(), tracked.x.value, 1e-4, "x at ${angles.toList()}")
            assertEquals(plain.y.toDouble(), tracked.y.value, 1e-4, "y at ${angles.toList()}")
        }
    }

    @Test
    fun forwardKinematicsJacobianMatchesAFiniteDifferenceOracle() {
        val configurations = listOf(
            doubleArrayOf(0.1, 0.2, -0.3, 0.4),
            doubleArrayOf(-0.7, 1.1, 0.25, -0.9),
            doubleArrayOf(2.0, -1.4, 0.6, 0.05)
        )
        // The plain reference is computed in Double throughout, not via the Float-backed Vec2, so
        // the oracle is not limited by float rounding.
        fun tip(angles: DoubleArray): Pair<Double, Double> {
            var x = 120.0
            var y = 300.0
            var heading = 0.0
            for (i in chain.lengths.indices) {
                heading += angles[i]
                x += chain.lengths[i] * kotlin.math.cos(heading)
                y += chain.lengths[i] * kotlin.math.sin(heading)
            }
            return x to y
        }
        for (angles in configurations) {
            val j = jacobian(angles) { p ->
                val e = chain.endEffectorAD(p)
                listOf(e.x, e.y)
            }
            for (col in angles.indices) {
                val oracleX = fd1(angles[col]) { v -> tip(angles.copyOf().also { it[col] = v }).first }
                val oracleY = fd1(angles[col]) { v -> tip(angles.copyOf().also { it[col] = v }).second }
                close("dTipX/dTheta$col at ${angles.toList()}", j[0][col], oracleX)
                close("dTipY/dTheta$col at ${angles.toList()}", j[1][col], oracleY)
            }
        }
    }

    @Test
    fun ikReachesEveryReachableTargetToSubPixelAccuracy() {
        // The default objective is pure reach, so "reachable" really does mean "reached".
        val targets = listOf(
            Vec2(360f, 150f),
            Vec2(60f, 120f),
            Vec2(200f, 480f),
            Vec2(140f, 305f)
        )
        for (target in targets) {
            val solved = solveIk(chain, target)
            assertTrue(
                solved.reachError < 1e-3,
                "target (${target.x}, ${target.y}): tip landed at " +
                    "(${solved.endEffector.x}, ${solved.endEffector.y}), error ${solved.reachError} " +
                    "after ${solved.run.iterations} iterations (${solved.run.reason})"
            )
            assertEquals(chain.linkCount + 1, solved.joints.size)
            assertEquals(chain.origin, solved.joints.first())
            assertEquals(solved.endEffector, solved.joints.last())
        }
    }

    @Test
    fun ikReportsAnHonestShortfallForAnUnreachableTarget() {
        // The chain reaches 295 units; this target is ~600 away.
        val target = Vec2(720f, 300f)
        val solved = solveIk(chain, target)
        val expectedShortfall = 600.0 - chain.reach
        assertTrue(
            (solved.reachError - expectedShortfall).absoluteValue < 1.0,
            "shortfall should be ~$expectedShortfall, got ${solved.reachError}"
        )
        // Fully extended along the x axis, so every joint angle is ~0.
        assertTrue(solved.angles.all { it.absoluteValue < 1e-2 }, "angles ${solved.angles.toList()}")
    }

    @Test
    fun ikIsDeterministic() {
        val target = Vec2(300f, 180f)
        val a = solveIk(chain, target)
        val b = solveIk(chain, target)
        assertTrue(a.angles.contentEquals(b.angles), "the same call must give the same pose")
        assertEquals(a.reachError, b.reachError, 0.0)
    }

    /**
     * The smoothness term is a genuine trade, not a free regulariser: it buys a straighter pose and
     * charges reach accuracy for it. Both halves of that are asserted here, because a test that only
     * checked the curvature fell would let a solver that had quietly stopped reaching pass.
     */
    @Test
    fun smoothnessTradesReachAccuracyForAStraighterPose() {
        val target = Vec2(300f, 180f)
        fun curvature(angles: DoubleArray) = (1 until angles.size).sumOf { i ->
            (angles[i] - angles[i - 1]) * (angles[i] - angles[i - 1])
        }
        val loose = solveIk(chain, target, options = IkOptions(smoothness = 0.0))
        val tight = solveIk(chain, target, options = IkOptions(smoothness = 2.0))

        assertTrue(loose.reachError < 1e-3, "pure reach should be exact, got ${loose.reachError}")
        assertTrue(
            curvature(tight.angles) < curvature(loose.angles) * 0.5,
            "smoothness=2 curvature ${curvature(tight.angles)} should be well below " +
                "smoothness=0 curvature ${curvature(loose.angles)}"
        )
        assertTrue(
            tight.reachError > loose.reachError,
            "the trade must be real: smoothed reach error ${tight.reachError} should exceed ${loose.reachError}"
        )
        assertTrue(
            tight.reachError < 12.0,
            "but it should still be roughly on target: ${tight.reachError} units off"
        )
    }

    /**
     * The whole point of [IkSolution.reachError]: with a competing objective the solver stops short,
     * and says so, instead of reporting a convergence it did not achieve.
     */
    @Test
    fun aSmoothedSolveNearTheAnchorReportsItsShortfall() {
        val nearBase = Vec2(140f, 305f)
        val exact = solveIk(chain, nearBase, options = IkOptions(smoothness = 0.0))
        val smoothed = solveIk(chain, nearBase, options = IkOptions(smoothness = 0.02))
        assertTrue(exact.reachError < 1e-3, "pure reach folds in to the base: ${exact.reachError}")
        assertTrue(
            smoothed.reachError > 1.0,
            "a smoothness penalty should visibly refuse to fold this tight: ${smoothed.reachError}"
        )
    }

    @Test
    fun theIkFLineTracesTheSolvedJoints() {
        val solved = solveIk(chain, Vec2(330f, 200f))
        val line = solved.toFLine(chain)
        assertEquals(chain.linkCount + 1, line.nodes.size, "one node per joint")
        for ((index, joint) in solved.joints.withIndex()) {
            val node = line.nodes[index].to
            assertEquals(joint.x.toDouble(), node.x.toDouble(), 1e-3, "node $index x")
            assertEquals(joint.y.toDouble(), node.y.toDouble(), 1e-3, "node $index y")
        }
    }

    // ---- packing -------------------------------------------------------------------------------

    @Test
    fun circlePackingResolvesEveryOverlapAndStaysInsideTheBox() {
        val radii = doubleArrayOf(34.0, 28.0, 26.0, 22.0, 20.0, 18.0, 16.0, 14.0, 12.0, 10.0)
        for (seed in listOf(0L, 7L, 42L)) {
            val packing = packCircles(radii, width = 320.0, height = 240.0, rngSeed = seed)
            assertEquals(radii.size, packing.circles.size)
            assertTrue(
                packing.worstOverlap < 0.5,
                "seed $seed: worst overlap ${packing.worstOverlap} after ${packing.run.iterations} iterations"
            )
            assertTrue(
                packing.worstEscape < 0.5,
                "seed $seed: worst escape ${packing.worstEscape}"
            )
            assertTrue(packing.run.value < packing.run.initialValue, "seed $seed: the loss should fall")
        }
    }

    @Test
    fun circlePackingIsSeededAndReproducible() {
        val radii = doubleArrayOf(30.0, 25.0, 20.0, 15.0)
        val a = packCircles(radii, 200.0, 200.0, rngSeed = 3L)
        val b = packCircles(radii, 200.0, 200.0, rngSeed = 3L)
        val c = packCircles(radii, 200.0, 200.0, rngSeed = 4L)
        assertTrue(a.run.x.contentEquals(b.run.x), "same seed must give the same packing")
        assertTrue(!a.run.x.contentEquals(c.run.x), "a different seed must give a different packing")
    }

    @Test
    fun aPackedCircleDrawsAsAClosedRing() {
        val circle = PackedCircle(Vec2(50f, 60f), 20.0)
        val line = circle.toFLine(segments = 24)
        assertEquals(25, line.nodes.size, "segments + 1 nodes, closing the ring")
        val first = line.nodes.first().to
        val last = line.nodes.last().to
        assertEquals(first.x.toDouble(), last.x.toDouble(), 1e-3, "the ring should close in x")
        assertEquals(first.y.toDouble(), last.y.toDouble(), 1e-3, "the ring should close in y")
        for (node in line.nodes) {
            val r = kotlin.math.hypot(node.to.x.toDouble() - 50.0, node.to.y.toDouble() - 60.0)
            assertEquals(20.0, r, 1e-3, "every node should sit on the circle")
        }
    }
}
