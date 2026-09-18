package afield.kratos

import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The two Kratos smoke runs, and the small piece of geometry they share.
 *
 * Both are `jvm-main` entry points rather than unit tests, because both need a worker process.
 * Each asks `Kratos.probeAny` for whichever launch this machine can actually run and prints a
 * passing line naming what was missing when the answer is none, so a checkout with no Kratos
 * installed still runs them green. Both read their startup budget off the resolved launch, since a
 * container under x86-64 emulation takes far longer to reach its first frame than a native venv.
 *
 * `kratos.embedded.thermal` covers the embedded route: one session over a fixed box, a committed
 * solve of [KratosDemo.body], then two preview solves with a shorter arm. What it establishes is
 * the pair of claims the route is for — that a geometric change is a reclassification of the same
 * [BackgroundDomain] instance rather than a remesh, with `buildCount` still 1 and the temperatures
 * moved; and that a preview overtaken by a later edit completes with null instead of arriving late.
 *
 * `kratos.structural.quadratic` covers the body-fitted route, and it checks physics rather than
 * plumbing: the same cantilever meshed at both tetrahedron orders, against the Euler-Bernoulli tip
 * deflection of a uniformly loaded beam. It skips when TetGen or Manifold is absent, because
 * body-fitted meshing goes through both.
 */

/** Reusable cuboid assembly: resize `armEnd` without changing authored identity. */
object KratosDemo {
    /**
     * A base box with an arm reaching out of it, as one union named `body`.
     *
     * [armEnd] moves the arm's far face; the three ids stay `body`, `base` and `arm` whatever it is,
     * which is what lets a formulation keep its region keys across the edit.
     */
    fun body(armEnd: Double = .71): SimulationCSG = SimulationCSG("body",
        SimulationBox("base",SimulationBounds(-.73,-.63,-.52,.23,.53,.62)),
        SimulationBox("arm",SimulationBounds(-.13,-.32,-.42,armEnd,.33,.42)))
}

/*
FIELD_TEST
id: kratos.embedded.thermal
runner: jvm-main
entrypoint: afield.kratos.KratosSmokeTest
tags: [smoke, kratos, optional, simulation]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "Kratos smoke passed"
*/
/**
 * The embedded thermal route end to end: one background grid, three solves, two of them previews.
 *
 * The asserted claim is that the second geometry reaches a different answer on the same grid. A
 * rebuilt domain would produce just as plausible a picture, so the check is on the domain's identity
 * and `buildCount` and not on the numbers alone.
 */
object KratosSmokeTest {
    /** Solves the demo body at three arm lengths and checks the background domain survived all of them. */
    @JvmStatic
    fun main(args: Array<String>) {
        // Whichever launch this machine can run: the venv on a Linux checkout, the container on a
        // macOS one, and a clear skip when neither is installed.
        val found = Kratos.probeAny().get(600,TimeUnit.SECONDS)
        val config = found.config
        println(found.capability)
        println("launch=${config.resolvedLaunch()}")
        if (!found.available) {
            println("Kratos smoke passed (SKIPPED: optional environment absent)")
            return
        }
        // Emulated x86-64 runs this an order of magnitude slower than the native reference.
        val budget = startupBudget(config)
        KratosSession.openAsync(SimulationBounds(-1.0,-1.0,-1.0,1.0,1.0,1.0),config=config).get(budget,TimeUnit.SECONDS).use { session ->
            val a = session.solveAsync(KratosDemo.body()).get(budget,TimeUnit.SECONDS)!!
            val stale = session.solveAsync(KratosDemo.body(.63),preview=true)
            val newest = session.solveAsync(KratosDemo.body(.43),preview=true)
            check(stale.get(budget,TimeUnit.SECONDS) == null)
            val b = newest.get(budget,TimeUnit.SECONDS)!!
            check(a.domain === b.domain && b.domain.buildCount == 1)
            val changed = a.distance.indices.count { (a.distance[it]>0) != (b.distance[it]>0) }
            val delta = a.temperature.indices.maxOf { abs(a.temperature[it]-b.temperature[it]) }
            check(changed > 0 && delta > .01 && session.latest === b)
            println("NO_REMESH background=${b.domain.id} buildCount=1 changedNodes=$changed maxTemperatureDelta=$delta staleRejected=true")
            println("FIRST ${a.diagnostics}")
            println("SECOND ${b.diagnostics}")
            check(!b.sliceMesh().isEmpty)
            println("Kratos smoke passed")
        }
    }

    private fun startupBudget(config: KratosConfig): Long =
        if (config.resolvedLaunch() is KratosLaunch.Container) 300 else 20
}

/*
FIELD_TEST
id: kratos.structural.quadratic
runner: jvm-main
entrypoint: afield.kratos.KratosStructuralSmokeTest
tags: [smoke, kratos, tetgen, optional, simulation]
timeoutSec: 900
expect:
  exitCode: 0
  stdoutContains:
    - "Kratos structural smoke passed"
*/
/**
 * The body-fitted path end to end, at both tetrahedron orders, against closed-form beam theory.
 *
 * Every piece that only exists for quadratic elements is on this route and nowhere else: TetGen's
 * output promoted to 10 nodes in [afield.tetgen.TetMesh.quadratic], the width carried over the wire
 * and checked in the reply, `SmallDisplacementElement3D10N` chosen by the worker from the mesh, the
 * peak-over-integration-points stress recovery, and the subdivided boundary in
 * [SimulationResult.boundaryMesh]. The unit tests cover each of those separately; this is the one
 * thing that shows they compose.
 *
 * The assertion is the contrast, because the contrast is the whole reason the work was done: the
 * same beam, the same loads, the same solver, and a linear mesh that reads far too stiff against a
 * quadratic one that lands within a few percent of Euler-Bernoulli.
 */
object KratosStructuralSmokeTest {
    // A 10:1 cantilever, slender enough for beam theory to be a fair reference.
    private const val L = 1.0
    private const val B = 0.1
    private const val E = 2.0e9
    private const val RHO = 1000.0
    private const val G = 9.81

    /** Meshes the cantilever at order 1 and order 2 and reports each tip deflection against theory. */
    @JvmStatic
    fun main(args: Array<String>) {
        if (!afield.tetgen.TetgenLibrary.isAvailable) {
            println("Kratos structural smoke passed (SKIPPED: ${afield.tetgen.TetgenLibrary.describe()})")
            return
        }
        if (!afield.manifold.ManifoldLibrary.isAvailable) {
            println("Kratos structural smoke passed (SKIPPED: ${afield.manifold.ManifoldLibrary.describe()})")
            return
        }
        val found = Kratos.probeAny().get(900, TimeUnit.SECONDS)
        if (!found.available) {
            println("Kratos structural smoke passed (SKIPPED: optional environment absent)")
            return
        }
        val config = found.config
        val budget = if (config.resolvedLaunch() is KratosLaunch.Container) 600L else 60L
        println("launch=${config.resolvedLaunch()}")

        val beam = SimulationBox("beam", SimulationBounds(0.0, -B / 2, -B / 2, L, B / 2, B / 2))
        // Euler-Bernoulli tip deflection of a uniformly loaded cantilever, w L^4 / 8 E I.
        val inertia = B * B * B * B / 12.0
        val analytic = RHO * G * B * B * L * L * L * L / (8 * E * inertia)
        val parameters = StructuralParameters(
            youngModulus = E, poissonRatio = 0.3, density = RHO,
            gravity = Triple(0.0, 0.0, -G),
            clamp = SimulationBounds(-1e-9, -1.0, -1.0, 1e-9, 1.0, 1.0))

        val deflection = HashMap<Int, Double>()
        for (order in listOf(1, 2)) {
            val mesh = afield.tetgen.Tetgen.tetrahedralize(
                beam.surface(), targetEdgeLength = 0.06, order = order)
            check(mesh.nodesPerCell == if (order == 2) 10 else 4)
            println("MESH order=$order $mesh")
            KratosSession.openMeshAsync(mesh, config).get(budget, TimeUnit.SECONDS).use { session ->
                check(session.isBodyFitted)
                val result = session.solveAsync(parameters).get(budget, TimeUnit.SECONDS)!!
                // The tip: nodes on the free end face, whose vertical displacement beam theory
                // predicts. Averaged, because the end face is not perfectly plane after loading.
                val tip = (0 until result.domain.nodeCount)
                    .filter { result.domain.positions[3 * it] > L - 1e-9 }
                check(tip.isNotEmpty()) { "the free end face caught no node" }
                val displacement = result.named("displacement")
                deflection[order] = tip.sumOf { abs(displacement[3 * it + 2]) } / tip.size / analytic

                check(result.diagnostics.number("nodesPerCell") == mesh.nodesPerCell.toLong())
                check(result.diagnostics["element"] == "SmallDisplacementElement3D${mesh.nodesPerCell}N")
                // A deformed picture must actually differ from the undeformed one, and on a
                // quadratic mesh it draws four triangles per boundary face rather than one.
                val flat = result.boundaryMesh("vonMises")
                val bent = result.deformedMesh(displacementScale = 500.0)
                check(!flat.isEmpty && bent.indices.size == flat.indices.size)
                check(flat.positions.indices.any { abs(bent.positions[it] - flat.positions[it]) > 1e-6 })
                println("ORDER $order ${result.diagnostics}")
            }
        }

        val linear = deflection.getValue(1)
        val quadratic = deflection.getValue(2)
        println("DEFLECTION analytic=%.6e linear=%.1f%% quadratic=%.1f%%"
            .format(analytic, 100 * linear, 100 * quadratic))
        // Neither may exceed the theory by much: both elements are stiff, and one that overshot
        // would mean the load or the clamp was wrong rather than the interpolation being good.
        check(linear < 1.05 && quadratic < 1.05) { "deflection above theory: $linear, $quadratic" }
        check(quadratic > 0.9) { "quadratic elements should reach 90% of theory, got $quadratic" }
        check(linear < 0.8) { "the linear element is expected to lock here; got $linear" }
        println("Kratos structural smoke passed")
    }
}
