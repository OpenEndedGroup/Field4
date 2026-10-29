package afield.examples.ceres

import afield.math.optimization.ceres.AxisSet
import afield.math.optimization.ceres.FfmCeresBackend
import afield.math.optimization.ceres.GeometrySolveSession
import afield.math.optimization.ceres.SolveOptions
import afield.math.optimization.ceres.geometryModel
import java.util.concurrent.Executors

/**
 * A native session built on one thread and solved and closed on another.
 *
 * The house sketch builds its first session on its own executor and rebuilds it later from the runloop. With the
 * solver's buffers in a confined arena, closing from the runloop freed the native problem and then threw
 * `WrongThreadException` before marking the solver closed, so the caller's retry freed it again and the JVM died in
 * `ceres::Problem::~Problem()` (house host, 2026-10-22). Run before the fix, this smoke fails at the first foreign
 * close.
 */
fun main() {
    val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ceres-session-owner").apply { isDaemon = true } }
    try {
        // Closed from a thread that did not create it: the house sketch's rebuild.
        val session = worker.submit<GeometrySolveSession> { openSolvedSession() }.get()
        val failure = runCatching { session.close() }.exceptionOrNull()
        check(failure == null) { "closing on a foreign thread threw ${failure!!::class.simpleName}: ${failure.message}" }
        // A second close, on either thread, must not free the native solver again.
        session.close()
        worker.submit { session.close() }.get()

        // Solved, then closed, from a thread that did not create it.
        val moved = worker.submit<GeometrySolveSession> { openSolvedSession() }.get()
        check(moved.solve(SolveOptions(maxIterations = 20))) { "solve on a foreign thread failed" }
        moved.close()

        // And the other way round: built here, closed on the worker.
        val local = openSolvedSession()
        worker.submit { local.close() }.get()
        local.close()

        println("ok cross-thread close")
    } finally {
        worker.shutdown()
    }
}

private fun openSolvedSession(): GeometrySolveSession {
    val model = geometryModel {
        val a = point("a", 0.0, 0.0, 0.0)
        val b = point("b", 1.2, 0.1, 0.0)
        val c = point("c", 1.1, 0.9, 0.0)
        constraints {
            a.lock(AxisSet.XYZ, 1e9)
            listOf(a, b, c).forEach { it.lock(AxisSet.Z, 1e8) }
            line(a, b).minLength(.3, 1e7)
            line(b, c).minLength(.3, 1e7)
            same(listOf(a, b), AxisSet.Y, 1e8)
        }
    }
    val session = model.openSession(backend = FfmCeresBackend())
    check(session.solve(SolveOptions(maxIterations = 20))) { "solve on the owning thread failed" }
    return session
}
