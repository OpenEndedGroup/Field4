# Field Document: three_mesh_pick_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory" "isClojureRoot" "isForthRoot" "isFieldMLRoot" "isMorphoRoot" "isNanoRoot" "nanoLaunchDelayMs" "nanoMemoryInit" "isKotlinJsRoot" "mainLanguage" "spreadsheet"]}
```

## 1. root

<!-- field-box id="root" index="0" -->

```edn [field-box id="root" role="properties"]
{"id" "root" "index" 0 "name" "root" "parents" ["metaroot"] "children" ["mesh-pick-clearance"] "properties" {"children" #{#afield/box "mesh-pick-clearance"} "parents" #{#afield/box "metaroot"} "name" "root" "nested" #{}} "blockProperties" []}
```

## 2. A solid you can measure to

<!-- field-box id="mesh-pick-clearance" index="1" -->

### Narrative

From `code`

A SOLID and its ANNOTATIONS in one scene -- and, from tonight, a measurement
BETWEEN them.

Run this box. A concrete slab appears with a conduit run floating in front of
it, and the viewer's toolbar carries the measure / section / explode tools
that until now only an all-FLine figure could have.

[ruler]   press it, then click the SLAB and then the CONDUIT. The dimension
that appears is the closest approach between a triangle of a mesh
and a segment of an FLine. Until 2026-08-16 the ruler could not
see the solid at all: the viewer built its pick list out of the
FLine half of the payload, so a ray went straight through every
mesh in the scene.

Where you click on the slab decides what you measure to. Near a
corner of a triangle it snaps to the VERTEX; along an edge, to
the EDGE; in the middle, the whole TRIANGLE is the element. Three
shapes -- the same three an FLine hit resolves to, which is
exactly why one measurement can span the two.

[section] cuts the slab as well as the conduit, and draws the cut line
through both.

With the ruler OFF, clicking either object prints its hit record below.

```edn [field-box id="mesh-pick-clearance" role="properties"]
{"id" "mesh-pick-clearance" "index" 1 "name" "A solid you can measure to" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "A solid you can measure to" "frame" #afield/rect [60.0 60.0 940.0 700.0] "nested" #{} "preamble" "" "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mesh-pick-clearance_code_.kt" "lines" 92 "characters" 3814 "sha256" "585a1d46d1675b641b36676af38a7e66bee7c135ee8c521d7530793e7942c64b"}]}
```

```kotlin [field-property box="mesh-pick-clearance" name="code" path="mesh-pick-clearance_code_.kt"]
// A SOLID and its ANNOTATIONS in one scene -- and, from tonight, a measurement
// BETWEEN them.
//
// Run this box. A concrete slab appears with a conduit run floating in front of
// it, and the viewer's toolbar carries the measure / section / explode tools
// that until now only an all-FLine figure could have.
//
//   [ruler]   press it, then click the SLAB and then the CONDUIT. The dimension
//             that appears is the closest approach between a triangle of a mesh
//             and a segment of an FLine. Until 2026-08-16 the ruler could not
//             see the solid at all: the viewer built its pick list out of the
//             FLine half of the payload, so a ray went straight through every
//             mesh in the scene.
//
//             Where you click on the slab decides what you measure to. Near a
//             corner of a triangle it snaps to the VERTEX; along an edge, to
//             the EDGE; in the middle, the whole TRIANGLE is the element. Three
//             shapes -- the same three an FLine hit resolves to, which is
//             exactly why one measurement can span the two.
//
//   [section] cuts the slab as well as the conduit, and draws the cut line
//             through both.
//
// With the ruler OFF, clicking either object prints its hit record below.

val slab = boxMesh(1200.0, 800.0, 60.0).withColor(0.44, 0.50, 0.58)

// The conduit run, 150 mm proud of the slab's +z face, in the slab's own
// millimetres.
val conduit = fline {
    moveTo(-500.0, -260.0, 180.0)
    lineTo(300.0, -260.0, 180.0)
    lineTo(300.0, 240.0, 180.0)
    lineTo(520.0, 240.0, 180.0)
    label = "conduit A"
    color = vec(1.0, 0.72, 0.25, 1.0)
    thickness = 3.0
    pointed = true
    pointSize = 8.0
    hoverColor = vec(1.0, 0.94, 0.66, 1.0)
    onClick = { hit ->
        println("conduit hit: ${hit.kind} node ${hit.nodeIndex} u=${"%.3f".format(hit.u)} at ${hit.onElement}")
    }
}

// The slab's +z face and the conduit's first leg, named from Kotlin, so the
// figure carries one dimension from the moment it is drawn rather than waiting
// to be asked. 180 - 30 = 150 mm, and the ruler should agree with that.
val slabFace = ThreeMeasureElement(
    "polygon",
    listOf(
        floatArrayOf(-600f, -400f, 30f),
        floatArrayOf(600f, -400f, 30f),
        floatArrayOf(600f, 400f, 30f),
        floatArrayOf(-600f, 400f, 30f),
    ),
)
val firstLeg = ThreeMeasureElement.segment(-500.0, -260.0, 180.0, 300.0, -260.0, 180.0)

three(channel = "main", name = "clearance", height = 560, width = 900) {
    // `pickable` is the new word and `ThreeSurfaceHit` is the new record. Before
    // tonight a mesh in this viewer was a picture: it drew, and nothing could
    // touch it.
    mesh(
        slab,
        label = "slab",
        id = "slab",
        wireframe = true,
        pickable = true,
        hoverColor = vec(0.34, 0.66, 0.98, 1.0),
        onClick = { hit: ThreeSurfaceHit ->
            println(
                "slab hit: ${hit.kind} face ${hit.faceIndex}" +
                    (if (hit.vertexIndex >= 0) " vertex ${hit.vertexIndex}" else "") +
                    (if (hit.edgeIndex >= 0) " edge ${hit.edgeIndex}" else "") +
                    " bary ${"%.3f %.3f %.3f".format(hit.u, hit.v, hit.w)}" +
                    " on ${hit.onElement}" +
                    (if (hit.frontFacing) "" else " [back face]")
            )
        },
    )
    lines(conduit)
    camera(yaw = 30.0, pitch = 22.0, distance = 3.0)
    inspect(
        units = "mm",
        decimals = 0,
        measurements = listOf(ThreeMeasurement(slabFace, firstLeg, "clearance ")),
    )
    title("slab + conduit -- press the ruler and measure from one to the other")
}

println("press the ruler, click the slab, then click the conduit: it should read 150 mm")
```

