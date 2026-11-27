# Field Document: kirigami_solid_chain

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. the same solid, opened

<!-- field-box id="kirigami_deployed" index="0" -->

```edn [field-box id="kirigami_deployed" role="properties"]
{"id" "kirigami_deployed" "index" 0 "name" "the same solid, opened" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "the same solid, opened" "_tombstone" false "frame" #afield/rect [60.0 1600.0 420.0 90.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kirigami_deployed_code_.kt" "lines" 35 "characters" 1050 "sha256" "24ef0aca55216979bbe8e748c7119bc1ddc222002c57bb62ebc00ae24fec614d"}]}
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
        "%4.0f deg  genus %d  parts %d  hull area x%.3f  (cos+sin)^2 %.3f  hinge gap %.0e"
            .format(
                deg,
                m.genus,
                m.parts,
                m.hullArea / closed.hullArea,
                s * s,
                m.hingeGap,
            ),
    )
}

showImgGrid(
    *stages
        .map { (deg, m) ->
            "${deg.toInt()} deg, genus ${m.genus}" to
                imgFromBytes(
                    m.mesh.renderPng(
                        MeshView(yawDegrees = 25.0, pitchDegrees = 40.0, distance = 2.7, width = 520, height = 520),
                    ),
                )
        }
        .toTypedArray(),
    columns = 4,
    cellSide = 300,
)
```

## 2. the sheet that opens into a circle, as a solid

<!-- field-box id="kirigami_circle_solid" index="1" -->

```edn [field-box id="kirigami_circle_solid" role="properties"]
{"id" "kirigami_circle_solid" "index" 1 "name" "the sheet that opens into a circle, as a solid" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "the sheet that opens into a circle, as a solid" "_tombstone" false "frame" #afield/rect [60.0 3200.0 420.0 90.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kirigami_circle_solid_code_.kt" "lines" 31 "characters" 1512 "sha256" "d91f18f2f889718411813d0375e36a9f48e5b7d7173b1715a8f3c1967b4da526"}]}
```

```kotlin [field-property box="kirigami_circle_solid" name="code" path="kirigami_circle_solid_code_.kt"]
import afield.examples.chain.KirigamiSolidChain
import afield.geometry.conformal.KirigamiInverseDesign

watchdog = 300 // the solve takes about half a minute the first time; then it is cached
val design = cached("kirigami-circle-8") { KirigamiInverseDesign(n = 8).solve() }
println("design residual %.1e after %d iterations".format(design.residual, design.iterations))

// Tiles here are as small as a third of a unit and as sharp as 0.4 rad, so the slit is narrower.
fun report(gap: Double): List<String> {
    val (shut, open) = KirigamiSolidChain.measureDesign(design, gap = gap)
    return listOf(
        "gap $gap: closed genus ${shut.genus}, opened genus ${open.genus} (hinges - tiles + 1 = ${shut.predictedGenus})",
        "  ${shut.parts} part closed, ${open.parts} opened, watertight ${shut.watertight && open.watertight}",
        "  volume %.4f closed, %.4f opened; opened hull / closed hull %.3f"
            .format(shut.volume, open.volume, open.hullArea / shut.hullArea),
    )
}
val lines = report(0.01) + report(0.04)
lines.forEach { println(it) }
display("numbers", "<pre>${lines.joinToString("\n")}</pre>")

val (shut, open) = KirigamiSolidChain.measureDesign(design, gap = 0.01)
fun view(m: KirigamiSolidChain.Measured) = imgFromBytes(
    m.mesh.renderPng(MeshView(yawDegrees = 25.0, pitchDegrees = 40.0, distance = 2.7, width = 620, height = 520)),
)
showImgGrid(
    "cut pattern, closed" to view(shut),
    "opened onto the circle" to view(open),
    columns = 2,
    cellSide = 420,
)
```

## 3. the closed sheet, cut with hinges you can make

<!-- field-box id="kirigami_cut_plate" index="2" -->

```edn [field-box id="kirigami_cut_plate" role="properties"]
{"id" "kirigami_cut_plate" "index" 2 "name" "the closed sheet, cut with hinges you can make" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "the closed sheet, cut with hinges you can make" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 90.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kirigami_cut_plate_code_.kt" "lines" 28 "characters" 1015 "sha256" "1da7757af41c4bf1a20fff1c7ae215e0cdbbcdf33de17345bcf33d349f27132b"}]}
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

// A three-quarter view from above; the CPU rasteriser needs no Window.
showImgGrid(
    "the cut plate, 0.1 thick" to
        imgFromBytes(
            plate.mesh.renderPng(
                MeshView(yawDegrees = 25.0, pitchDegrees = 40.0, distance = 2.7, width = 900, height = 600),
            ),
        ),
    columns = 1,
    cellSide = 600,
)
```

