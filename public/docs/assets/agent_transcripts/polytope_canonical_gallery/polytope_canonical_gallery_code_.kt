import afield.stdlib.polytope.*
import afield.stdlib.Vec3

// ---- the one parameter to change -------------------------------------------------
// Conway prefix notation, read right to left, over the seeds T C O I D and the
// operators d a k t g j n z o e s m b. Try "gD", "ktC", "bD", "sD", "dkD", "tI", "eC".
val expression = "gD"
// ----------------------------------------------------------------------------------

val plain = conway(expression)
val raw = plain.polyhedron
val run = raw.canonicalize()
val solid = run.polyhedron
val orbits = solid.combinatorialOrbits()

println(
    """
    $expression = ${plain.summary().primitiveOperations.joinToString(" ")}
    ${solid.topology.elements(0).size} vertices, ${solid.topology.elements(1).size} edges, ${solid.topology.elements(2).size} faces
    before: spread ${"%.3e".format(raw.canonicalDiagnostics().tangencyRadiusSpread)}  planarity ${"%.3e".format(raw.canonicalDiagnostics().maxFacePlanarityError)}
    after:  spread ${"%.3e".format(run.finalDiagnostics.tangencyRadiusSpread)}  planarity ${"%.3e".format(run.finalDiagnostics.maxFacePlanarityError)}  centre ${"%.3e".format(run.finalDiagnostics.tangencyCentroidOffset)}
    ${run.reason} after ${run.iterations} iterations (reciprocal ${run.reciprocalIterations}, relaxation ${run.relaxationIterations}, reseeded ${run.reseeded})
    orbits: ${orbits.summary()}
    """.trimIndent(),
)

val status: (String) -> Unit = { message -> markdown("canonical face", message) }

// Face colour by polygon degree, so the Archimedean/Catalan structure reads at a glance.
val hues = mapOf(
    3 to vec(1.00, 0.60, 0.30),
    4 to vec(0.35, 0.77, 1.00),
    5 to vec(0.62, 0.91, 0.42),
    6 to vec(1.00, 0.44, 0.60),
    8 to vec(1.00, 0.88, 0.40),
    10 to vec(0.72, 0.78, 0.86),
)

fun PolyhedralObject.shiftedBy(dx: Float): PolyhedralObject {
    val coordinates = realization!!.vertexCoordinates.map { Vec3(it).add(dx, 0f, 0f) }
    return copy(realization = Realization3(coordinates))
}

fun PolyhedralObject.panel(label: String, dx: Float, alpha: Double) =
    shiftedBy(dx).toFaceFLines { face, line ->
        val degree = topology.faceCycle(face.id).size
        val hue = hues[degree] ?: vec(0.70, 0.74, 0.80)
        val orbit = orbits.faceClasses.indexOfFirst { face.id in it }
        val normal = faceNormal(face.id)
        val centre = faceCentroids()[topology.elements(2).indexOfFirst { it.id == face.id }]
        val depth = (centre.x * normal.x + centre.y * normal.y + centre.z * normal.z).toDouble()

        line.filled = true
        line.stroked = true
        line.thickness = 1.2
        line.color = vec(hue.x, hue.y, hue.z, alpha)
        line.hoverColor = vec(0.98, 0.99, 1.00, 0.98)
        line.onClick = {
            status(
                """
                **$label — face ${face.id.value}**

                - degree: `$degree`
                - combinatorial orbit: `$orbit of ${orbits.faceClassCount}`
                - face-plane distance from centre: `${"%.5f".format(depth)}`
                """.trimIndent(),
            )
        }
    }

// Left: the per-operator realization the Conway transform produced. Right: its canonical
// representative. Both are centred and scaled by the same rigid gauge, so the difference
// on screen is shape and nothing else.
val before = raw.normalize(RigidGauge.CANONICAL, reference = raw.realization)
val faceLines = before.panel("realized", -1.4f, 0.34) + solid.panel("canonical", 1.4f, 0.86)

threeLines(
    faceLines,
    channel = "polytope",
    name = "canonical-gallery",
    height = 560,
    autoFrame = true,
    editable = false,
)
displayOpen("polytope")

viz("diagnostics", listOf(run.initialDiagnostics, run.finalDiagnostics, orbits))
