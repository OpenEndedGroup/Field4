package afield.examples.ceres

import afield.stdlib.ceres.AxisSet
import afield.stdlib.ceres.CachedCeresSolvers
import afield.stdlib.ceres.FfmCeresBackend
import afield.stdlib.ceres.PointCloud
import afield.stdlib.ceres.SolveOptions
import afield.stdlib.ceres.SolveRequest
import afield.stdlib.ceres.Vec3
import afield.stdlib.ceres.buildCeresProgram
import kotlin.math.abs

fun main() {
    val backend = FfmCeresBackend()
    val cache = CachedCeresSolvers(backend)

    val points = PointCloud.of(
        Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.2, 0.2, 0.8),
        Vec3(0.0, 0.0, 1.0), Vec3(1.0, 0.5, 1.0), Vec3(0.0, 0.0, 2.0), Vec3(1.0, 1.0, 2.0), Vec3(2.0, 0.0, 2.0),
        Vec3(0.0, 2.0, 0.0), Vec3(1.0, 2.0, 0.0), Vec3(0.0, 3.0, 0.0), Vec3(1.0, 3.5, 0.0),
        Vec3(0.0, 4.0, 0.0), Vec3(1.0, 4.0, 0.0), Vec3(0.0, 5.0, 0.0), Vec3(0.5, 6.0, 0.0),
        Vec3(0.0, 0.0, 3.0), Vec3(1.0, 0.0, 3.0), Vec3(0.0, 1.0, 3.0), Vec3(0.0, 0.0, 4.0),
        Vec3(0.0, 0.0, 5.0), Vec3(1.0, 0.0, 5.0), Vec3(0.0, 1.0, 5.0), Vec3(1.2, 1.1, 5.0),
        Vec3(0.3, 0.3, 1.0), Vec3(0.3, 0.3, -1.0),
        Vec3(0.0, 0.0, 6.0), Vec3(1.0, 0.0, 6.0), Vec3(1.0, 1.0, 6.0), Vec3(0.0, 1.0, 6.0),
    )

    val basePlane = listOf(0, 1, 2)
    val program = buildCeresProgram {
        lock(basePlane, AxisSet.X, weight = 1e6)
        lock(basePlane, AxisSet.Y, weight = 1e6)
        lock(basePlane, AxisSet.Z, weight = 1e6)
        lock(listOf(4, 6, 8), AxisSet.X, weight = 1e6)
        lock(listOf(4, 6, 8), AxisSet.Y, weight = 1e6)
        lock(listOf(4, 6, 8), AxisSet.Z, weight = 1e6)

        pointOnPlane(3, 0, 1, 2, offset = 0.0, weight = 100.0)
        pointInHalfspace(3, 0, 1, 2, minSignedDistance = 0.0, weight = 10.0)
        lineDirection(4, 5, Vec3(1.0, 0.0, 0.0), weight = 20.0)
        lineTowardPoint(6, 7, 8, weight = 20.0)
        lineParallel(9, 10, 11, 12, weight = 2.0)
        lineAngle(13, 14, 15, 16, targetCos = 0.0, weight = 2.0)
        dihedralAngle(17, 18, 19, 20, targetCos = 0.0, weight = 2.0)
        equalEdgeVector(21, 22, 23, 24, weight = 1.0)
        mirrorAcrossPlane(25, 26, 0, 1, 2, weight = 5.0)
        panelArea(27, 28, 29, 30, targetArea = 1.0, weight = 5.0)
        planeNormalDirection(0, 1, 2, Vec3(0.0, 0.0, 1.0), targetCos = 1.0, weight = 2.0)
        weakAll(weight = 1e-3)
    }

    val result = cache.solve(
        SolveRequest(
            points,
            program,
            options = SolveOptions(maxIterations = 40, metrics = true, metricGroupCapacity = 64),
        )
    )

    check(result.ok) { "solve failed" }
    check(abs(result.points[3].z) < 1e-2) { "point-on-plane did not settle: ${result.points[3]}" }
    check(abs(result.points[5].y - result.points[4].y) < 5e-2) {
        "line direction did not settle: ${result.points[4]} ${result.points[5]}"
    }

    cache.close()
    println("ok p3=${result.points[3]} p5=${result.points[5]} top=${result.metrics?.top(6)}")
}
