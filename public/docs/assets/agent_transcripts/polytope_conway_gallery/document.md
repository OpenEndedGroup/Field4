# Field Document: polytope_conway_gallery

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. 1 — Conway expressions, retype the list

<!-- field-box id="polytope_expression_gallery" index="0" -->

```edn [field-box id="polytope_expression_gallery" role="properties"]
{"id" "polytope_expression_gallery" "index" 0 "name" "1 — Conway expressions, retype the list" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 — Conway expressions, retype the list" "_tombstone" false "frame" #afield/rect [80.0 80.0 480.0 88.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "polytope_expression_gallery_code_.kt" "lines" 52 "characters" 2246 "sha256" "cd015cc1988acad5de535ab21487a3b4f69915c8211349e162f021a8d1aa6f64"}]}
```

```kotlin [field-property box="polytope_expression_gallery" name="code" path="polytope_expression_gallery_code_.kt"]
import afield.stdlib.polytope.*
import java.util.Base64

// ***** CHANGE THIS LINE *****  Any prefix expression over the seeds T C O I D and the
// operators d a k t g j n z o e s m b, with optional kN / tN degree indices. Re-run and
// the whole contact sheet rebuilds — including the captions, which are read off the
// topology and not off the pictures.
val expressions = listOf("C", "dC", "aC", "kC", "tC", "gC", "bC", "k4aC", "sD")

val view = MeshView(width = 300, height = 252, wireframe = false, distance = 2.3)
val cells = expressions.map { source ->
    val result = conway(source)
    val topology = result.polyhedron.topology
    val faces = topology.elements(2)
        .groupingBy { topology.faceCycle(it.id).size }
        .eachCount()
        .toSortedMap()
    val png = Base64.getEncoder().encodeToString(
        result.polyhedron.toTriMesh().renderPng(view, caption = "", title = null),
    )
    """
    <div>
      <div style='color:#e6ecf4;font:600 13px monospace;padding-bottom:3px'>$source
        <span style='color:#6d7787;font-weight:400'>
          &nbsp;${result.history.joinToString("") { it.symbol.toString() }}
        </span>
      </div>
      <img src='data:image/png;base64,$png' style='width:100%;border-radius:5px;display:block'/>
      <div style='color:#93a4ba;font:11px monospace;padding-top:4px'>
        ${topology.elements(0).size}v ${topology.elements(1).size}e ${topology.elements(2).size}f
        &middot; ${faces.entries.joinToString(" ") { "${it.value}&times;${it.key}-gon" }}
        &middot; &chi;=${topology.eulerCharacteristic()}
      </div>
    </div>
    """.trimIndent()
}
display(
    "gallery",
    "<div style='display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px'>" +
        cells.joinToString("") + "</div>",
)

// Second column of the caption is `history`: the PRIMITIVES that actually ran, seed
// outward. Composites are rewritten, never reimplemented, so `bC` reports `at`.
println(expressions.joinToString("\n") { source ->
    val r = conway(source)
    "%-6s  notationHistory %-6s  history %-6s".format(
        source,
        r.notationHistory.joinToString("") { it.symbol.toString() },
        r.history.joinToString("") { it.symbol.toString() },
    )
})
```

## 2. 4 — realization before and after, with diagnostics

<!-- field-box id="polytope_realization_relax" index="1" -->

```edn [field-box id="polytope_realization_relax" role="properties"]
{"id" "polytope_realization_relax" "index" 1 "name" "4 — realization before and after, with diagnostics" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 — realization before and after, with diagnostics" "_tombstone" false "frame" #afield/rect [1120.0 1000.0 480.0 88.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "polytope_realization_relax_code_.kt" "lines" 68 "characters" 2804 "sha256" "23d2b00802e604ef7fb00be079ce17191d130b1591d631135e54c84406f6d251"}]}
```

```kotlin [field-property box="polytope_realization_relax" name="code" path="polytope_realization_relax_code_.kt"]
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
```

## 3. 3 — a non-uniform parameter field on the faces

<!-- field-box id="polytope_parameter_field" index="2" -->

```edn [field-box id="polytope_parameter_field" role="properties"]
{"id" "polytope_parameter_field" "index" 2 "name" "3 — a non-uniform parameter field on the faces" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 — a non-uniform parameter field on the faces" "_tombstone" false "frame" #afield/rect [80.0 1000.0 480.0 88.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "polytope_parameter_field_code_.kt" "lines" 77 "characters" 3148 "sha256" "821d844c938df9c1c39cdeaab9b9f79b06ee7c285fa8aa3713fa017f923a780d"}]}
```

```kotlin [field-property box="polytope_parameter_field" name="code" path="polytope_parameter_field_code_.kt"]
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
```

## 4. 2 — a dual with no coordinates

<!-- field-box id="polytope_abstract_dual" index="3" -->

```edn [field-box id="polytope_abstract_dual" role="properties"]
{"id" "polytope_abstract_dual" "index" 3 "name" "2 — a dual with no coordinates" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 — a dual with no coordinates" "_tombstone" false "frame" #afield/rect [1120.0 80.0 480.0 88.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "polytope_abstract_dual_code_.kt" "lines" 36 "characters" 2293 "sha256" "0eff0a398106c19ff4d55174458a29093ca2fa42a08dc94a49d76213917407c5"}]}
```

```kotlin [field-property box="polytope_abstract_dual" name="code" path="polytope_abstract_dual_code_.kt"]
import afield.stdlib.polytope.*

// Take the cube's coordinates away. The topology is untouched, and every combinatorial
// question about the dual is still decidable.
val abstractCube = PolyhedralObject(cube().topology)
val abstract = abstractCube.transform(ConwayOperator.DUAL).polyhedron

// Ask the abstract object for geometry anyway, and keep whatever it actually says.
// These are real caught exceptions; nothing here paraphrases an error message.
val flineRefusal = runCatching { abstract.toFaceFLines() }
    .fold({ "no exception — unexpected" }, { "${it::class.simpleName}: ${it.message}" })
val triangleRefusal = runCatching { abstract.triangulate() }
    .fold({ "no exception — unexpected" }, { "${it::class.simpleName}: ${it.message}" })

// ***** CHANGE THIS *****  Swap DualCentroidRealization for any RealizationPolicy3 —
// `RealizationPolicy3 { _, _ -> null }` keeps the object abstract forever, which is a
// perfectly legal thing for a polytope to be.
val realized = cube().dual(DualCentroidRealization).polyhedron

markdown("honesty", """
    #### `cube()` &rarr; dual, with and without an embedding

    | | abstract | after a realization policy |
    |---|---|---|
    | `realization` | `${abstract.realization}` | `Realization3(${realized.realization?.size} vertices)` |
    | counts | ${abstract.topology.elements(0).size}v ${abstract.topology.elements(1).size}e ${abstract.topology.elements(2).size}f | ${realized.topology.elements(0).size}v ${realized.topology.elements(1).size}e ${realized.topology.elements(2).size}f |
    | closed manifold | ${abstract.topology.validate(checkManifold = true).isValid} | ${realized.topology.validate(checkManifold = true).isValid} |
    | isomorphic to `octahedron()` | ${abstract.topology.isCombinatoriallyIsomorphicTo(octahedron().topology)} | ${realized.topology.isCombinatoriallyIsomorphicTo(octahedron().topology)} |
    | `toFaceFLines()` | `$flineRefusal` | ${realized.toFaceFLines().size} FLines |
    | `triangulate()` | `$triangleRefusal` | ${realized.triangulate().triangleCount} triangles |

    The left column is a complete, valid, checkable octahedron. It has nowhere to be.
""".trimIndent(), width = 900)

// The registered value printer reduces this to a public data class from Summaries.kt.
abstract.topology
```

