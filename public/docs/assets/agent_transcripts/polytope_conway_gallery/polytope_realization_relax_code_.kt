import afield.stdlib.polytope.*
import afield.stdlib.Vec3
import java.util.Base64
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

val view = MeshView(width = 420, height = 360, wireframe = false, distance = 2.5)
val panels = listOf(
    "aC as built" to clean,
    "jittered" to jittered,
    "solveRealization()" to solved.polyhedron,
)
display(
    "relax",
    "<div style='display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px'>" +
        panels.joinToString("") { (label, p) ->
            val png = Base64.getEncoder().encodeToString(
                p.toTriMesh().renderPng(view, caption = "", title = null),
            )
            "<div><div style='color:#e6ecf4;font:600 13px monospace;padding-bottom:3px'>$label</div>" +
                "<img src='data:image/png;base64,$png' style='width:100%;border-radius:5px;display:block'/></div>"
        } + "</div>",
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
