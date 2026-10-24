package afield.examples.ceres

import afield.math.optimization.ceres.AxisSet
import afield.math.optimization.ceres.EqualDistance
import afield.math.optimization.ceres.PointId
import afield.math.optimization.ceres.FfmCeresBackend
import afield.math.optimization.ceres.PointRef
import afield.math.optimization.ceres.SolveOptions
import afield.math.optimization.ceres.Vec3
import afield.math.optimization.ceres.geometryModel
import kotlin.math.abs
import afield.math.linalg.Vector3f

fun main() {
    lateinit var liftedCorner: PointRef
    lateinit var vectorCorner: PointRef

    val model = geometryModel {
        val a = point("a", 0.0, 0.0, 0.0)
        val b = point("b", 1.0, 0.0, 0.0)
        val c = point("c", 0.0, 1.0, 0.0)
        val d = point("d", 1.0, 1.0, 0.4)
        val vp = point("vp", 3.0, 1.0, 0.0)
        val rawCorner = Vector3f(0.5f, 0.5f, 0.0f)
        val midpoint = point("midpoint", rawCorner)
        liftedCorner = d

        val floor = plane(a, b, c, "floor")
        val panel = panel(a, b, d, c, "adventurous panel")
        val leadingEdge = line(a, b, "leading edge")
        val recedingEdge = b lineTo d
        val reusedEdge = line(rawCorner, d, "vector backed edge")
        vectorCorner = reusedEdge.a

        constraints {
            check(rawCorner.c == vectorCorner) { "Vector3f.c did not reuse the point created by line(Vector3f, ...)" }
            lock(listOf(a, b, c, vp), AxisSet.XYZ)
            weakAll(weight = 1e-2)

            floor.normal(Vec3(0.0, 0.0, 1.0), weight = 10.0)
            panel.planar(weight = 80.0)
            panel.area(target = 1.0, weight = 5.0)
            d.inside(floor, minSignedDistance = 0.0, weight = 10.0)
            (a + b * 0.5) ceq midpoint

            leadingEdge.direction(Vec3(1.0, 0.0, 0.0), weight = 20.0)
            recedingEdge.vanishesAt(vp, weight = 4.0)
            (leadingEdge dot (a lineTo rawCorner)) ceq 1.0
        }
    }

    val session = model.openSession(
        backend = FfmCeresBackend(),
        baselinePrior = 1e-2,
    )
    try {
        check(session.solve(SolveOptions(maxIterations = 40, metrics = true, metricGroupCapacity = 128))) {
            "initial solve failed"
        }
        check(abs(session.get(liftedCorner).z) < 2e-2) {
            "panel did not settle toward the floor plane: ${session.get(liftedCorner)}"
        }

        session.drag(liftedCorner, Vec3(1.0, 1.0, 0.25), strength = 1e3)
        session.release(liftedCorner)

        println("ok lifted=${session.get(liftedCorner)} top=${session.report.top(6)}")
    } finally {
        session.close()
    }
    sharedCornerSameLength()
}

// Two walls meeting at a corner, made the same length. Before the re-encoding this aborted the JVM inside Ceres
// ("Duplicate parameter blocks in a residual"), taking a house host down with it.
private fun sharedCornerSameLength() {
    lateinit var end1: PointRef
    lateinit var end2: PointRef
    val model = geometryModel {
        val corner = point("corner", 0.0, 0.0, 0.0)
        end1 = point("east", 4.0, 0.0, 0.0)
        end2 = point("north", 0.0, 2.5, 0.0)
        constraints {
            corner.lock(AxisSet.XYZ, 1e9)
            listOf(end1, end2).forEach { it.lock(AxisSet.Z, 1e8) }
            same(listOf(corner, end1), AxisSet.Y, 1e8)
            same(listOf(corner, end2), AxisSet.X, 1e8)
            line(corner, end1).sameLengthAs(line(corner, end2), 1e6)
        }
    }
    val session = model.openSession(backend = FfmCeresBackend())
    try {
        check(session.solve(SolveOptions(maxIterations = 60))) { "shared-corner solve failed" }
        val east = session.get(end1).x
        val north = session.get(end2).y
        check(abs(east - north) < 1e-4) { "shared-corner walls did not reach one length: $east vs $north" }
        println("ok shared corner same length: $east $north")
    } finally {
        session.close()
    }
    val refused = runCatching { EqualDistance(PointId(0), PointId(1), PointId(0), PointId(2)) }.exceptionOrNull()
    check(refused is IllegalArgumentException) { "EqualDistance with a repeated point was not refused: $refused" }
    println("ok EqualDistance refuses a repeated point")
}
