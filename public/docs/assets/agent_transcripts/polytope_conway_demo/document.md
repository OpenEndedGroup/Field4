# Field Document: polytope_conway_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. Polytope — selective kis, topology to FLine

<!-- field-box id="polytope_selective_kis" index="0" -->

```edn [field-box id="polytope_selective_kis" role="properties"]
{"id" "polytope_selective_kis" "index" 0 "name" "Polytope — selective kis, topology to FLine" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Polytope — selective kis, topology to FLine" "_tombstone" false "frame" #afield/rect [80.0 80.0 440.0 72.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "polytope_selective_kis_code_.kt" "lines" 84 "characters" 2938 "sha256" "75f74230ade0e44969087f322eacc2c5f0116d967b0db05db9c61f62289b9a87"}]}
```

```kotlin [field-property box="polytope_selective_kis" name="code" path="polytope_selective_kis_code_.kt"]
import afield.stdlib.polytope.*

// Topology is the source of truth. Coordinates and the FLine viewer only arrive after
// selection and the abstract Conway operation have decided what the new faces are.
var source = cube()
val sourceFaces = source.topology.elements(2)

// The first pair are the cube's opposite z faces. Attributes do both jobs here: `raise`
// selects the domain of kis, while `height` is deployed independently over that domain.
val raisedFaces = sourceFaces.take(2)
val raisedHeights = listOf(0.22, 0.68)
for (i in raisedFaces.indices) {
    source = source.withAttributes(
        raisedFaces[i].id,
        mapOf("raise" to true, "height" to raisedHeights[i]),
    )
}

val result = source.kis(
    selector = ElementSelectors.attributeEquals("raise", true),
    heightScale = ElementParameters.attribute("height"),
)
val polyhedron = result.polyhedron
val topology = polyhedron.topology
val triangles = polyhedron.triangulate()

println(
    """
    selective kis on two opposite cube faces
    transform = ${result.summary()}
    triangles = ${triangles.summary()}
    orbit the view; hover and click a face to inspect its lineage
    """.trimIndent(),
)

val status: (String) -> Unit = { message -> markdown("face provenance", message) }

// One FLine per authoritative polygonal face. The face attributes propagated through kis
// become styling here; no triangle mesh has been smuggled in as the topology model.
val faceLines = polyhedron.toFaceFLines { face, line ->
    val attributes = polyhedron.attributes[face.id]
    val height = (attributes["height"] as? Number)?.toDouble()
    val lineage = result.lineage(face.id)
    val role = (lineage as? ElementLineage.Derived)?.role ?: "source"
    val sources = result.sourceElements(face.id)
        .joinToString { "rank ${it.rank} / id ${it.id.value}" }

    line.filled = true
    line.stroked = true
    line.thickness = if (height == null) 1.1 else 1.8
    line.color = when {
        height == null -> vec(0.28, 0.32, 0.38, 0.62)
        height < 0.4 -> vec(0.30, 0.82, 0.96, 0.78)
        else -> vec(1.00, 0.62, 0.24, 0.82)
    }
    line.hoverColor = vec(0.96, 0.98, 1.00, 0.98)
    line.onClick = { hit ->
        val deployed = height?.let { "%.2f".format(it) } ?: "not selected"
        status(
            """
            **output face ${face.id.value}**

            - role: `$role`
            - deployed height: `$deployed`
            - source: $sources
            - clicked at: `${hit.position}`
            """.trimIndent(),
        )
    }
}

threeLines(
    faceLines,
    channel = "polytope",
    name = "selective-kis",
    height = 560,
    autoFrame = true,
    editable = false,
)
displayOpen("polytope")

// These are registered polytope value printers. They reduce the larger immutable objects
// to public data-class summaries, then use Field's normal nested data-class visualizer.
viz("inspection", listOf(result, triangles))
```
