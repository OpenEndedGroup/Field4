import afield.stdlib.polytope.*
import afield.stdlib.Vec3
import kotlin.random.Random

// A real Conway output, then every vertex jittered. The topology is not touched: the
// solver only ever returns copy(realization = ...).
val clean = conway("aC").polyhedron
val rng = Random(7)
// ***** CHANGE THIS *****  the jitter amplitude. Push it far enough and the solver stops
// converging, and `reason` will say so instead of quietly returning something wrong.
val amplitude = 0.62f
val jittered = clean.copy(realization = Realization3(
    clean.realization!!.vertexCoordinates.map {
        Vec3(it).add(
            (rng.nextDouble() - 0.5).toFloat() * amplitude,
            (rng.nextDouble() - 0.5).toFloat() * amplitude,
            (rng.nextDouble() - 0.5).toFloat() * amplitude,
        )
    },
))

val solved = jittered.solveRealization()

val view = MeshView(wireframe = false, distance = 2.5)
threeGrid(
    "aC as built" to clean.toTriMesh(),
    "jittered" to jittered.toTriMesh(),
    "solveRealization()" to solved.polyhedron.toTriMesh(),
    channel = "relax", name = "relax", columns = 3, height = 360, cellWidth = 420, view = view,
)

fun row(label: String, d: RealizationDiagnostics) =
    "| $label | %.5f | %.3e | %.3e | %.3e | ${d.degenerateFaces.size} |".format(
        d.edgeLengthMean,
        d.edgeLengthRmsDeviation,
        d.maxFacePlanarityError,
        d.facePlanarityRmsError,
    )

markdown("diagnostics", """
    | embedding | edge mean | edge RMS dev | max planarity err | planarity RMS | degenerate |
    |---|---|---|---|---|---|
    ${row("as built", clean.geometryDiagnostics())}
    ${row("jittered", solved.initialDiagnostics)}
    ${row("solved", solved.finalDiagnostics)}

    converged = **${solved.converged}** &middot; iterations = ${solved.iterations} &middot;
    reason = `${solved.reason}` &middot;
    topology object identical = **${solved.polyhedron.topology === jittered.topology}**

    This is constraint relaxation, not canonicalisation. It has no symmetry model and it
    cannot tell you the shape you wish you had given it — only how far the shape you did
    give it is from a common edge length, a common radius and planar faces.
""".trimIndent(), width = 940)

// The solve result is already a data class, so it visualizes without a registered printer.
solved
