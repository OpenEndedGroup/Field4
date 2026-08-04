package afield.examples.ceres

import afield.stdlib.ceres.CachedCeresSolvers
import afield.stdlib.ceres.CeresLinearSolver
import afield.stdlib.ceres.FfmCeresBackend
import afield.stdlib.ceres.PointCloud
import afield.stdlib.ceres.RobustLoss
import afield.stdlib.ceres.SolveOptions
import afield.stdlib.ceres.SolveRequest
import afield.stdlib.ceres.Vec3
import afield.stdlib.ceres.buildCeresProgram
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Exercises the solver options, the summary, the selectable loss and the midsphere constraint
 * family, against a problem with a known closed-form answer.
 *
 * The test object is a cube's twelve edges. Ask for every edge tangent to a unit sphere about the
 * origin and the tangency points centred there, and the answer is the cube of half-edge 1 — each
 * edge midpoint is then at distance 1 from the origin. Starting from a deliberately lopsided cube,
 * a correct solve has to find that.
 */
fun main() {
    val backend = FfmCeresBackend()
    println("abi=${backend.abiVersion}")

    val cache = CachedCeresSolvers(backend)

    // A cube, scaled and shoved off-origin so nothing about the answer is already true.
    val corners = listOf(
        Vec3(-1.0, -1.0, -1.0), Vec3(1.0, -1.0, -1.0), Vec3(1.0, 1.0, -1.0), Vec3(-1.0, 1.0, -1.0),
        Vec3(-1.0, -1.0, 1.0), Vec3(1.0, -1.0, 1.0), Vec3(1.0, 1.0, 1.0), Vec3(-1.0, 1.0, 1.0),
    ).map { Vec3(it.x * 1.7 + 0.6, it.y * 1.7 - 0.35, it.z * 1.7 + 0.2) }
    val points = PointCloud.of(*corners.toTypedArray())

    val edges = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 0,
        4 to 5, 5 to 6, 6 to 7, 7 to 4,
        0 to 4, 1 to 5, 2 to 6, 3 to 7,
    )
    val faces = listOf(
        listOf(0, 1, 2, 3), listOf(4, 5, 6, 7), listOf(0, 1, 5, 4),
        listOf(2, 3, 7, 6), listOf(1, 2, 6, 5), listOf(0, 3, 7, 4),
    )

    // Plain least squares: a realization solve wants its residuals driven to zero together, and a
    // robust loss would cap the influence of whichever one is currently largest.
    val program = buildCeresProgram(RobustLoss.NONE) {
        faces.forEach { planar(*it.toIntArray(), weight = 2.0) }
        edges.forEach { (a, b) -> edgeTangency(a, b, radius = 1.0, weight = 1.0) }
        tangencyCentroid(edges, weight = 4.0)
    }

    val result = cache.solve(
        SolveRequest(
            points,
            program,
            options = SolveOptions(
                maxIterations = 200,
                metrics = true,
                metricGroupCapacity = 96,
                linearSolver = CeresLinearSolver.SPARSE_NORMAL_CHOLESKY,
                summary = true,
            ),
        ),
    )

    check(result.ok) { "solve failed" }
    val summary = requireNotNull(result.summary) { "asked for a summary and did not get one" }
    println("summary: $summary")
    check(summary.residualCount > 0) { "summary reported no residuals" }
    check(summary.iterations > 0) { "summary reported no iterations" }

    // Every edge's nearest point to the origin should now sit at radius 1...
    val radii = edges.map { (a, b) ->
        val pa = result.points[a]
        val pb = result.points[b]
        val dx = pb.x - pa.x
        val dy = pb.y - pa.y
        val dz = pb.z - pa.z
        val len2 = dx * dx + dy * dy + dz * dz
        val t = -(pa.x * dx + pa.y * dy + pa.z * dz) / len2
        val px = pa.x + t * dx
        val py = pa.y + t * dy
        val pz = pa.z + t * dz
        sqrt(px * px + py * py + pz * pz)
    }
    val spread = radii.max() - radii.min()
    check(abs(radii.average() - 1.0) < 1e-3) { "mean tangency radius ${radii.average()} != 1" }
    check(spread < 1e-3) { "tangency radii not common: spread $spread over $radii" }

    // ...and that pins the cube's size exactly. A cube with corners at (+-a,+-a,+-a) has edge
    // midpoints at distance a*sqrt(2) from the centre, so a midsphere of radius 1 means a = 1/sqrt(2)
    // and every corner sits at a*sqrt(3) = sqrt(1.5).
    val corner = result.points[0]
    val cornerRadius = sqrt(corner.x * corner.x + corner.y * corner.y + corner.z * corner.z)
    val expectedCornerRadius = sqrt(1.5)
    check(abs(cornerRadius - expectedCornerRadius) < 1e-3) {
        "corner radius $cornerRadius != sqrt(1.5) = $expectedCornerRadius"
    }

    println("ok tangency radius mean=${"%.9f".format(radii.average())} spread=${"%.2e".format(spread)}")
    println("ok corner radius=${"%.9f".format(cornerRadius)} (sqrt 1.5 = ${"%.9f".format(expectedCornerRadius)})")

    // The unknown-opcode channel: a program the native side cannot fully build must be refused,
    // not silently truncated into a smaller problem that "succeeds".
    val truncated = runCatching {
        cache.getOrCompile(
            points,
            buildCeresProgram {
                planar(0, 1, 2, 3)
                raw(9999.0, 1.0, 2.0) // no such opcode
            },
        )
    }
    check(truncated.isFailure) { "a malformed program compiled without complaint" }
    println("ok malformed program refused: ${truncated.exceptionOrNull()?.message}")

    cache.close()
}
