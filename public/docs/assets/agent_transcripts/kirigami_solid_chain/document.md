# Field Document: kirigami_solid_chain

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. the same solid, opened

<!-- field-box id="kirigami_deployed" index="0" -->

```edn [field-box id="kirigami_deployed" role="properties"]
{"id" "kirigami_deployed" "index" 0 "name" "the same solid, opened" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "the same solid, opened" "_tombstone" false "frame" #afield/rect [60.0 1600.0 420.0 90.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kirigami_deployed_code_.kt" "lines" 25 "characters" 846 "sha256" "470cfb6c2d18675deb57b2d881786f6496315cbd72ed5f1ecdf77570c591cb12"}]}
```

```kotlin [field-property box="kirigami_deployed" name="code" path="kirigami_deployed_code_.kt"]
import afield.examples.chain.KirigamiSolidChain
import kotlin.math.cos
import kotlin.math.sin

val closed = KirigamiSolidChain.measure(angle = 0.0)
val stages = listOf(0.0, 15.0, 30.0, 45.0).map { it to KirigamiSolidChain.measure(angle = Math.toRadians(it)) }
for ((deg, m) in stages) {
    val s = cos(m.angle) + sin(m.angle)
    println(
        "%4.0f deg  genus %d  parts %d  hull area x%.3f  (cos+sin)^2 %.3f  hinge gap %.0e".format(
            deg, m.genus, m.parts, m.hullArea / closed.hullArea, s * s, m.hingeGap,
        ),
    )
}

showImgGrid(
    *stages
        .map { (deg, m) ->
            "${deg.toInt()} deg, genus ${m.genus}" to
                imgFromBytes(m.mesh.renderPng(MeshView(yawDegrees = 0.0, pitchDegrees = 55.0, width = 520, height = 520)))
        }
        .toTypedArray(),
    columns = 4,
    cellSide = 300,
)
```

## 2. the closed sheet, cut with hinges you can make

<!-- field-box id="kirigami_cut_plate" index="1" -->

```edn [field-box id="kirigami_cut_plate" role="properties"]
{"id" "kirigami_cut_plate" "index" 1 "name" "the closed sheet, cut with hinges you can make" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "the closed sheet, cut with hinges you can make" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 90.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kirigami_cut_plate_code_.kt" "lines" 20 "characters" 806 "sha256" "b906c57566380fc8933be3f538bd50e5841980d4a3877ecf6d80e2615c79cbf8"}]}
```

```kotlin [field-property box="kirigami_cut_plate" name="code" path="kirigami_cut_plate_code_.kt"]
import afield.examples.chain.KirigamiSolidChain

// gap: half a slit's width. bridge: how far each hinge bar runs along its cut.
val n = 8
val gap = 0.04
val bridge = 0.12
val plate = KirigamiSolidChain.measure(n, angle = 0.0, thickness = 0.1, gap = gap, bridge = bridge)

val report = listOf(
    "${plate.tiles} tiles, ${plate.hinges} hinges",
    "genus ${plate.genus}, and hinges - tiles + 1 = ${plate.predictedGenus}",
    "${plate.parts} part, watertight=${plate.watertight}",
    "volume %.6f = area %.6f x thickness 0.1".format(plate.volume, plate.sectionArea),
)
report.forEach { println(it) }
display("numbers", "<pre>${report.joinToString("\n")}</pre>")
display(
    "render",
    imgFromBytes(plate.mesh.renderPng(MeshView(yawDegrees = 0.0, pitchDegrees = 70.0, width = 760, height = 620))),
)
```

