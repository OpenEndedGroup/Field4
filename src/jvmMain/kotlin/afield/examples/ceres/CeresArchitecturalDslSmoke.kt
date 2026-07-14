package afield.examples.ceres

import afield.stdlib.ceres.AxisSet
import afield.stdlib.ceres.FfmCeresBackend
import afield.stdlib.ceres.PointRef
import afield.stdlib.ceres.SolveOptions
import afield.stdlib.ceres.Vec3
import afield.stdlib.ceres.geometryModel
import kotlin.math.abs
import afield.stdlib.linalg.Vector3f

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
}
