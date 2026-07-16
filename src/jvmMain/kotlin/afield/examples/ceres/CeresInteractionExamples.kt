package afield.examples.ceres

import afield.stdlib.ceres.AxisSet
import afield.stdlib.ceres.FfmCeresBackend
import afield.stdlib.ceres.InteractionPolicy
import afield.stdlib.ceres.LineRef
import afield.stdlib.ceres.PointRef
import afield.stdlib.ceres.SolveOptions
import afield.stdlib.ceres.Vec3
import afield.stdlib.ceres.geometryModel
import afield.stdlib.ceres.liveGeometry
import afield.stdlib.linalg.Vector3f
import kotlin.math.abs
import kotlin.math.sqrt

private val backend = FfmCeresBackend()

fun main() {
    preserveUnrelatedPointsDuringDrag()
    pinnedPointsStayPinned()
    panelPlanarityAfterRelease()
    finiteVanishingPointExample()
    mirrorAndHalfspaceExample()
    repeatedDragLoopExample()
    liveBoundPointLifecycleExample()
    liveHardLockPolishExample()
    polishUsesLastExplicitDragTarget()
    polishRepeatsNativePasses()

    println("ok interaction examples")
}

private fun preserveUnrelatedPointsDuringDrag() {
    lateinit var a: PointRef
    lateinit var b: PointRef
    lateinit var observer: PointRef

    val model = geometryModel {
        a = point("anchor-ish", 0.0, 0.0, 0.0)
        b = point("dragged", 1.0, 0.0, 0.0)
        observer = point("observer", 0.0, 8.0, 0.0)
        val edge = line(a, b)

        constraints {
            edge.length(1.0, weight = 100.0)
        }
    }

    val session = model.openSession(backend, policy = policy())
    try {
        val observerBefore = session.get(observer)
        val drag = session.drag(b)
        drag.to(2.0, 0.0, 0.0)
        drag.release()

        assertNear(distance(session.get(observer), observerBefore), 0.0, 1e-4, "unrelated point drifted")
        check(session.get(b).x > 1.5) { "dragged point did not mostly follow the handle: ${session.get(b)}" }
        check(session.get(a).x > 0.25) { "constraint did not pull the coupled endpoint: ${session.get(a)}" }
    } finally {
        session.close()
    }
}

private fun pinnedPointsStayPinned() {
    lateinit var pinned: PointRef
    lateinit var dragged: PointRef
    lateinit var witness: PointRef

    val model = geometryModel {
        pinned = point("pinned", 0.0, 0.0, 0.0)
        dragged = point("dragged", 1.0, 0.0, 0.0)
        witness = point("witness", 0.0, 4.0, 0.0)
        val edge = line(pinned, dragged)

        constraints {
            edge.length(1.0, weight = 1.0)
        }
    }

    val session = model.openSession(backend, policy = policy())
    try {
        val pinnedBefore = session.get(pinned)
        val witnessBefore = session.get(witness)
        session.pin(pinned)

        session.drag(dragged).apply {
            to(2.0, 0.0, 0.0)
            release()
        }

        assertNear(distance(session.get(pinned), pinnedBefore), 0.0, 1e-5, "pinned point moved")
        assertNear(distance(session.get(witness), witnessBefore), 0.0, 1e-4, "witness drifted")
        check(session.get(dragged).x > 1.5) { "dragged point should still follow the handle strongly" }
    } finally {
        session.close()
    }
}

private fun panelPlanarityAfterRelease() {
    lateinit var corner: PointRef

    val model = geometryModel {
        val a = point("a", 0.0, 0.0, 0.0)
        val b = point("b", 1.0, 0.0, 0.0)
        val c = point("c", 0.0, 1.0, 0.0)
        val d = point("d", 1.0, 1.0, 0.5)
        corner = d
        val panel = panel(a, b, d, c)

        constraints {
            lock(listOf(a, b, c), AxisSet.XYZ)
            panel.planar(weight = 100.0)
            panel.area(target = 1.0, weight = 5.0)
        }
    }

    val session = model.openSession(backend, policy = policy())
    try {
        session.drag(corner).apply {
            to(1.0, 1.0, 0.7)
            release()
        }

        assertNear(session.get(corner).z, 0.0, 2e-2, "released panel corner should settle back to the plane")
    } finally {
        session.close()
    }
}

private fun finiteVanishingPointExample() {
    lateinit var free: PointRef
    lateinit var a: PointRef
    lateinit var vp: PointRef

    val model = geometryModel {
        a = point("line start", 0.0, 0.0, 0.0)
        free = point("line end", 1.0, 0.5, 0.0)
        vp = point("vanishing point", 4.0, 0.0, 0.0)
        val ray = line(a, free)

        constraints {
            lock(listOf(a, vp), AxisSet.XYZ)
            ray.vanishesAt(vp, weight = 120.0)
        }
    }

    val session = model.openSession(backend, policy = policy())
    try {
        session.drag(free).apply {
            to(2.0, 1.0, 0.0)
            release()
        }

        assertNear(pointLineDistance(session.get(vp), session.get(a), session.get(free)), 0.0, 4e-2, "line missed vanishing point")
    } finally {
        session.close()
    }
}

private fun mirrorAndHalfspaceExample() {
    lateinit var above: PointRef
    lateinit var below: PointRef

    val model = geometryModel {
        val a = point("plane-a", 0.0, 0.0, 0.0)
        val b = point("plane-b", 1.0, 0.0, 0.0)
        val c = point("plane-c", 0.0, 1.0, 0.0)
        above = point("above", 0.25, 0.25, 0.8)
        below = point("below", 0.25, 0.25, -0.75)
        val ground = plane(a, b, c)

        constraints {
            lock(listOf(a, b, c), AxisSet.XYZ)
            mirror(above, below, across = ground, weight = 80.0)
            above.inside(ground, minSignedDistance = 0.0, weight = 10.0)
        }
    }

    val session = model.openSession(backend, policy = policy())
    try {
        check(session.solve(maxIterations = 40, metrics = true)) { "mirror solve failed" }

        assertNear(session.get(above).x, session.get(below).x, 1e-3, "mirror x mismatch")
        assertNear(session.get(above).y, session.get(below).y, 1e-3, "mirror y mismatch")
        assertNear(session.get(above).z + session.get(below).z, 0.0, 2e-2, "mirror z mismatch")
        check(session.get(above).z >= -1e-4) { "halfspace let the above point cross the ground" }
    } finally {
        session.close()
    }
}

private fun repeatedDragLoopExample() {
    lateinit var handlePoint: PointRef
    lateinit var spine: LineRef

    val model = geometryModel {
        val a = point("spine-a", 0.0, 0.0, 0.0)
        val b = point("spine-b", 1.0, 0.0, 0.0)
        handlePoint = b
        spine = line(a, b)

        constraints {
            a.lock(AxisSet.XYZ)
            spine.length(1.0, weight = 50.0)
            spine.direction(Vec3(1.0, 0.0, 0.0), weight = 10.0)
        }
    }

    val session = model.openSession(backend, policy = policy(dragWeight = 5e3))
    try {
        val drag = session.drag(handlePoint)
        for (step in 0 until 24) {
            val targetX = 1.0 + 0.25 * step / 23.0
            check(drag.to(targetX, 0.1, 0.0)) { "drag step $step failed" }
        }
        drag.release()

        assertNear(session.get(handlePoint).y, 0.0, 2e-2, "spine direction should clean up y drift")
        check(session.report.ok) { "final repeated drag report should be ok" }
    } finally {
        session.close()
    }
}

private fun liveBoundPointLifecycleExample() {
    val anchorSource = Vector3f(0f, 0f, 0f)
    val handleSource = Vector3f(1f, 0f, 0f)
    lateinit var handleRef: PointRef
    var reusedWithinBuild = false

    val live = liveGeometry(backend = backend, policy = policy(dragWeight = 5e4)) {
        val anchor = anchorSource.point
        val handle = handleSource.point
        reusedWithinBuild = handle == handleSource.point
        handleRef = handle
        val edge = anchor lineTo handle

        constraints {
            anchor.lock(AxisSet.XYZ)
            edge.length(1.0, weight = 250.0)
        }
    }

    try {
        val first = live.step()
        check(first.rebuilt) { "first live step should compile a session" }
        check(first.moved.isEmpty()) { "fresh live step should not infer a drag" }
        check(reusedWithinBuild) { "Vector3f.point did not reuse the point binding for the same mutable vector" }
        assertNear(distance(anchorSource.vec3(), handleSource.vec3()), 1.0, 1e-3, "initial live solve did not write constrained points")

        handleSource.set(1.35f, 0.2f, 0f)
        val dragged = live.step()
        check(!dragged.rebuilt) { "stable live program should reuse its compiled session" }
        check(dragged.moved.size == 1) { "expected one moved binding, got ${dragged.moved.size}" }
        check(dragged.moved.single().binding.point == handleRef) {
            "live reader movement should be attributed to the handle binding"
        }
        check(handleSource.x > 1.05) { "drag prior did not keep the handle near the external interaction target: $handleSource" }

        val held = live.step(handleSource)
        check(!held.rebuilt) { "explicit live drag should still reuse the compiled session" }
        check(held.moved.size == 1 && held.moved.single().explicit) {
            "explicitly dragged Vector3f should stay in the moved set even when the reader value is unchanged"
        }
        check(!held.report.totalCost.isNaN()) { "live drag steps should report metrics by default" }
    } finally {
        live.close()
    }
}

private fun liveHardLockPolishExample() {
    val dragged = Vector3f(0f, 0f, 0f)
    val free = Vector3f(1f, 0f, 0f)
    val live = liveGeometry(backend = backend, policy = policy(dragWeight = 10.0)) {
        val a = dragged.point
        val b = free.point
        val edge = a lineTo b
        constraints {
            edge.length(0.5, weight = 1e4)
        }
    }

    try {
        check(live.step(options = SolveOptions(maxIterations = 20, metrics = true)).ok) { "initial live polish setup failed" }
        dragged.set(2f, 0f, 0f)
        val polished = live.polishLocked(dragged, options = SolveOptions(maxIterations = 80, metrics = true))
        check(polished.ok) { "hard-lock polish solve failed" }
        check(!polished.report.totalCost.isNaN()) { "hard-lock polish should report metrics" }
        assertNear(distance(dragged.vec3(), free.vec3()), 0.5, 5e-3, "hard-lock polish should move the free point to satisfy the edge")
        assertNear(dragged.x.toDouble(), 2.0, 1e-5, "hard-lock polish should leave the dragged source at its mouse-up target")
    } finally {
        live.close()
    }
}

private fun polishUsesLastExplicitDragTarget() {
    val anchor = Vector3f(0f, 0f, 0f)
    val dragged = Vector3f(0.5f, 0f, 0f)
    val live = liveGeometry(backend = backend, policy = policy(dragWeight = 1.0)) {
        val a = anchor.point
        val b = dragged.point
        val edge = a lineTo b
        constraints {
            a.lock(AxisSet.XYZ, weight = 1e8)
            edge.length(0.5, weight = 1e6)
        }
    }

    try {
        check(live.step(options = SolveOptions(maxIterations = 20, metrics = true)).ok) { "initial explicit target setup failed" }
        dragged.set(2f, 0f, 0f)
        val soft = live.step(dragged, options = SolveOptions(maxIterations = 4, metrics = true))
        check(soft.ok) { "soft drag solve failed" }
        val postSoftDragX = dragged.x.toDouble()
        check(abs(postSoftDragX - 2.0) > 0.25) {
            "test setup expected the soft drag writer to move away from the explicit target, got $postSoftDragX"
        }

        dragged.x = 2f
        val polished = live.polishLocked(dragged, options = SolveOptions(maxIterations = 100, metrics = true))
        check(polished.ok) { "hard-lock polish after a soft drag failed" }
        assertNear(dragged.x.toDouble(), 2.0, 1e-4, "polish should lock to the current mutable vector value at mouse-up")
    } finally {
        live.close()
    }
}

private fun polishRepeatsNativePasses() {
    val locked = Vector3f(0f, 0f, 0f)
    val free = Vector3f(10f, 0f, 0f)
    val live = liveGeometry(backend = backend, policy = policy()) {
        val a = locked.point
        val b = free.point
        constraints {
            (a lineTo b).length(0.5, weight = 10.0)
        }
    }

    try {
        val polished = live.polishLocked(locked, options = SolveOptions(maxIterations = 100, metrics = true))
        check(polished.ok) { "repeated hard-lock polish failed" }
        assertNear(distance(locked.vec3(), free.vec3()), 0.5, 1e-5, "hard-lock polish should use maxIterations as repeated native passes")
    } finally {
        live.close()
    }
}

private fun policy(dragWeight: Double = 1e3): InteractionPolicy =
    InteractionPolicy(
        preserveWeight = 1e-2,
        dragWeight = dragWeight,
        releaseWeight = 1e-2,
        pinnedWeight = 1e8,
        dragIterations = 10,
        settleIterations = 35,
        metricCapacity = 128,
        metricsDuringDrag = false,
        metricsOnRelease = true,
    )

private fun distance(a: Vec3, b: Vec3): Double {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return sqrt(dx * dx + dy * dy + dz * dz)
}

private fun Vector3f.vec3(): Vec3 =
    Vec3(x.toDouble(), y.toDouble(), z.toDouble())

private fun pointLineDistance(point: Vec3, a: Vec3, b: Vec3): Double {
    val ux = b.x - a.x
    val uy = b.y - a.y
    val uz = b.z - a.z
    val vx = point.x - a.x
    val vy = point.y - a.y
    val vz = point.z - a.z
    val cx = uy * vz - uz * vy
    val cy = uz * vx - ux * vz
    val cz = ux * vy - uy * vx
    return sqrt(cx * cx + cy * cy + cz * cz) / (sqrt(ux * ux + uy * uy + uz * uz) + 1e-20)
}

private fun assertNear(actual: Double, expected: Double, tolerance: Double, message: String) {
    check(abs(actual - expected) <= tolerance) {
        "$message: expected $expected +/- $tolerance, got $actual"
    }
}
