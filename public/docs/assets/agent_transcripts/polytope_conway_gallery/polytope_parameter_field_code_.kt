import afield.stdlib.polytope.*

// A scalar per face, written as an ordinary attribute. Selection and deployment are
// separate concerns: `band` decides WHERE kis happens, `spike` decides HOW MUCH.
var source = dodecahedron()
val realization = source.realization!!
val vertexIndex = source.topology.elements(0).mapIndexed { i, v -> v.id.value to i }.toMap()
for (face in source.topology.elements(2)) {
    val centre = source.topology.faceCycle(face.id)
        .map { realization[vertexIndex.getValue(it)] }
    val x = centre.map { it.x }.average()
    val y = centre.map { it.y }.average()
    source = source.withAttributes(
        face.id,
        // ***** CHANGE THIS *****  any function of the face you like. Make `band` always
        // true for a fully non-uniform kis, or narrow it for selective AND non-uniform.
        mapOf("band" to (y < 0.62), "spike" to (0.10 + 0.80 * (x + 1.0) / 2.0)),
    )
}

val result = source.kis(
    selector = ElementSelectors.attributeEquals("band", true),
    heightScale = ElementParameters.attribute("spike"),
)
val out = result.polyhedron
val deployment = result.deployments.single()
val deployed = deployment.scalarParameters.values.mapNotNull { it["heightScale"] }

println(
    """
    kis deployed over ${deployment.selectedSources?.size} of ${source.topology.elements(2).size} faces
    heightScale resolved over ${deployed.size} sources, %.3f .. %.3f
    output ${out.topology.elements(0).size}v ${out.topology.elements(1).size}e ${out.topology.elements(2).size}f
    hover a face; click it to read the lineage that produced it
    """.trimIndent().format(deployed.min(), deployed.max()),
)

val card: (String) -> Unit = { message -> markdown("face provenance", message) }

// One FLine per authoritative polygon. The colour is the deployed scalar, carried on the
// face by the attribute propagation policy — no coordinate is consulted to choose it.
val lo = deployed.min()
val hi = deployed.max()
val faceLines = out.toFaceFLines { face, line ->
    val spike = (out.attributes[face.id]["spike"] as? Number)?.toDouble()
    val u = if (spike == null) null else ((spike - lo) / (hi - lo)).coerceIn(0.0, 1.0)
    val role = (result.lineage(face.id) as? ElementLineage.Derived)?.role ?: "source"
    line.filled = true
    line.stroked = true
    line.thickness = if (u == null) 1.1 else 1.7
    line.color =
        if (u == null) vec(0.30, 0.34, 0.40, 0.55)
        else vec(0.20 + 0.78 * u, 0.84 - 0.28 * u, 0.98 - 0.72 * u, 0.80)
    line.hoverColor = vec(0.96, 0.98, 1.00, 0.98)
    line.onClick = { hit ->
        card(
            """
            **output face ${face.id.value}**

            - role: `$role`
            - deployed spike: `${spike?.let { "%.3f".format(it) } ?: "not selected"}`
            - source: ${result.sourceElements(face.id).joinToString { "rank ${it.rank} / id ${it.id.value}" }}
            - clicked at: `${hit.position}`
            """.trimIndent(),
        )
    }
}

threeLines(
    faceLines,
    channel = "parameter-field",
    name = "non-uniform kis",
    height = 520,
    autoFrame = true,
    editable = false,
)
displayOpen("parameter-field")
