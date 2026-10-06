# Field Document: three_inspector_wall

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_session" "fileManagerAccessHistory"]}
```

## 1. The 3D output area as an instrument

<!-- field-box id="three-inspector-wall" index="0" -->

### Narrative

From `code`

The three.js output area as an INSTRUMENT.

Run this box. A wall detail appears -- four skins and five conduit runs -- and
the viewer's toolbar has three things a 3D preview never had before:

[ruler]    click it, then click two things in the scene. The CLOSEST APPROACH
between them is drawn as a dimension, in millimetres. Not centroid
to centroid: on a panel drawing those are different numbers, and
only one of them is the one you would take to a saw. Click a third
time to start over.

[section]  turn it on and a second slider appears. The fills clip -- and the
CUT is drawn over them in cyan, with a dot wherever a conduit
crosses the plane. The status line, top right, reports the total
length of the cut and where the plane sits. Hiding the front half
shows you the inside of a shell; the cut line is the section.

explode    drag it and the four skins come apart, spaced evenly by their
ORDER through the wall rather than by how far apart they actually
are -- 90, 50 and 12 mm, which under a distance-proportional
explode would leave three of them in a clump. Drag it back to 0
and the geometry is exactly what the server sent, bit for bit.

The two dimensions below are authored from Kotlin, so the figure carries them
from the moment it is drawn. `ThreeMeasureElement.face(line)` names a whole
panel; `.segment(...)` and `.point(...)` name the other two shapes a hit can
resolve to, which is not a coincidence -- a measurement you pick in the viewer
produces exactly these.

```edn [field-box id="three-inspector-wall" role="properties"]
{"id" "three-inspector-wall" "index" 0 "name" "The 3D output area as an instrument" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "The 3D output area as an instrument" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "three-inspector-wall_code_.kt" "lines" 101 "characters" 4382 "sha256" "0bea1d65cdf73f3fca2b2fa51c329e2119bd343adbade572e0cebadb13a513e6"}]}
```

```kotlin [field-property box="three-inspector-wall" name="code" path="three-inspector-wall_code_.kt"]
// The three.js output area as an INSTRUMENT.
//
// Run this box. A wall detail appears -- four skins and five conduit runs -- and
// the viewer's toolbar has three things a 3D preview never had before:
//
//   [ruler]    click it, then click two things in the scene. The CLOSEST APPROACH
//              between them is drawn as a dimension, in millimetres. Not centroid
//              to centroid: on a panel drawing those are different numbers, and
//              only one of them is the one you would take to a saw. Click a third
//              time to start over.
//
//   [section]  turn it on and a second slider appears. The fills clip -- and the
//              CUT is drawn over them in cyan, with a dot wherever a conduit
//              crosses the plane. The status line, top right, reports the total
//              length of the cut and where the plane sits. Hiding the front half
//              shows you the inside of a shell; the cut line is the section.
//
//   explode    drag it and the four skins come apart, spaced evenly by their
//              ORDER through the wall rather than by how far apart they actually
//              are -- 90, 50 and 12 mm, which under a distance-proportional
//              explode would leave three of them in a clump. Drag it back to 0
//              and the geometry is exactly what the server sent, bit for bit.
//
// The two dimensions below are authored from Kotlin, so the figure carries them
// from the moment it is drawn. `ThreeMeasureElement.face(line)` names a whole
// panel; `.segment(...)` and `.point(...)` name the other two shapes a hit can
// resolve to, which is not a coincidence -- a measurement you pick in the viewer
// produces exactly these.

fun skin(name: String, x: Double, c: Vec4) = fline {
    moveTo(x, 0.0, 0.0)
    lineTo(x, 1600.0, 0.0)
    lineTo(x, 1600.0, 1800.0)
    lineTo(x, 0.0, 1800.0)
    close()
    label = name
    filled = true
    stroked = true
    thickness = 1.4
    color = c
}

// x is the depth through the wall, in millimetres. 152 mm of build-up.
val wall = listOf(
    skin("sheathing", 0.0, vec(0.36, 0.46, 0.58, 0.5)),
    skin("studs", 90.0, vec(0.85, 0.62, 0.32, 0.28)),
    skin("services", 140.0, vec(0.35, 0.72, 0.86, 0.28)),
    skin("lining", 152.0, vec(0.90, 0.92, 0.96, 0.4))
)

val conduits = (0 until 5).map { i ->
    fline {
        val y = 200.0 + i * 320.0
        moveTo(120.0, y, 80.0)
        lineTo(120.0, y, 1720.0)
        label = "conduit $i"
        stroked = true
        thickness = 4.0
        color = vec(1.0, 0.45, 0.30, 1.0)
    }
}

// `groupBy` decides what counts as one PART of the assembly. It defaults to the
// FLine's label, which is why the five conduits fan out separately here; group
// them together with `groupBy = { if (it.label?.startsWith("conduit") == true) "conduits" else it.label ?: "" }`.
val view = threeInspect(
    wall + conduits,
    channel = "wall",
    height = 560,
    units = "mm",
    explode = ThreeExplode(mode = "axis", axis = floatArrayOf(1f, 0f, 0f), sliderMax = 1400.0),
    measurements = listOf(
        // the build-up: 152 by construction, and the figure now says so
        ThreeMeasurement(
            ThreeMeasureElement.face(wall[0]),
            ThreeMeasureElement.face(wall[3]),
            "build-up "
        ),
        // the clearance from a conduit to the inside of the lining: 152 - 120 = 32,
        // which is written down nowhere in the geometry
        ThreeMeasurement(
            ThreeMeasureElement.segment(120.0, 840.0, 80.0, 120.0, 840.0, 1720.0),
            ThreeMeasureElement.face(wall[3]),
            "clear "
        )
    )
)

// And because the explosion is a PARAMETER rather than a literal you retype, the
// box can drive it. Uncomment any of these and re-run -- no rebuild of the
// geometry, just a new question:
//
// view.explode(700.0)                                  // pull it apart
// view.explodeAlong(0.0, 0.0, 1.0, 500.0)              // ...along z instead
// view.section(0.0, 0.0, 1.0, fraction = 0.55)         // cut it
// view.clearSection()
// view.measureFaces(wall[1], wall[2], "cavity ")       // one more dimension
// view.clearMeasurements()

println("wall: ${view.inspector().groups.size} assembly groups, ${view.inspector().measurements.size} dimensions")
println("drag the explode slider; press the ruler and click two things")
```

