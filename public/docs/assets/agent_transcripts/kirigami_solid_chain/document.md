# Field Document: kirigami_solid_chain

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. the same solid, opened

<!-- field-box id="kirigami_deployed" index="0" -->

```edn [field-box id="kirigami_deployed" role="properties"]
{"id" "kirigami_deployed" "index" 0 "name" "the same solid, opened" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "the same solid, opened" "_tombstone" false "frame" #afield/rect [60.0 1600.0 420.0 90.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kirigami_deployed_code_.kt" "lines" 36 "characters" 1071 "sha256" "b8724a65e2337f2bb9cb750719f1007a5c4788bef2c804f64d6fa8de002afdc8"}]}
```

```kotlin [field-property box="kirigami_deployed" name="code" path="kirigami_deployed_code_.kt"]
import afield.examples.chain.KirigamiSolidChain

val anglesDegrees: List<Double> = [0.0, 15.0, 30.0, 45.0]
val closed = KirigamiSolidChain.measure(angle = 0.0)
val stages = anglesDegrees.map { degrees ->
    degrees to KirigamiSolidChain.measure(angle = Math.toRadians(degrees))
}
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
{"id" "kirigami_circle_solid" "index" 1 "name" "the sheet that opens into a circle, as a solid" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "the sheet that opens into a circle, as a solid" "_tombstone" false "frame" #afield/rect [60.0 3200.0 420.0 90.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kirigami_circle_solid_code_.kt" "lines" 38 "characters" 1623 "sha256" "6b989d27ebb8e356ce1729d8ea07e34349755dbad7fea37e8a7f260ced3b7b27"}]}
```

```kotlin [field-property box="kirigami_circle_solid" name="code" path="kirigami_circle_solid_code_.kt"]
import afield.examples.chain.KirigamiSolidChain
import afield.geometry.conformal.KirigamiInverseDesign

val n = 8
val narrowGap = 0.01
val wideGap = 0.04
watchdog = 300 // The first inverse solve takes about half a minute.
val design = cached("kirigami-circle-$n") { KirigamiInverseDesign(n = n).solve() }

// Sharp corners retreat further as the gap widens, so a hinge can miss its tile.
val gaps: List<Double> = [narrowGap, wideGap]
val measurements = gaps.map { KirigamiSolidChain.measureDesign(design, gap = it) }
val rows =
    gaps.zip(measurements).joinToString("\n") { (gap, pair) ->
        val (closed, opened) = pair
        "| $gap | ${closed.genus} / ${opened.genus} | ${closed.predictedGenus} | " +
            "${closed.parts} / ${opened.parts} | ${closed.watertight && opened.watertight} | " +
            "${closed.volume.f4} / ${opened.volume.f4} | ${(opened.hullArea / closed.hullArea).f3} |"
    }
markdown(
    "numbers",
    """
    Design residual ${"%.1e".format(design.residual)} after ${design.iterations} iterations.

    | Half slit width | Genus (closed / open) | Predicted | Parts (closed / open) | Both watertight | Volume (closed / open) | Hull area ratio |
    | --- | --- | --- | --- | --- | --- | --- |""".trimIndent() + "\n" + rows,
)

val (shut, open) = measurements.first()
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
{"id" "kirigami_cut_plate" "index" 2 "name" "the closed sheet, cut with hinges you can make" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "the closed sheet, cut with hinges you can make" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 90.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kirigami_cut_plate_code_.kt" "lines" 27 "characters" 899 "sha256" "acc050418216473fe713fd4a6327497826dcfa652229e9563b9b4167cbc43fae"}]}
```

```kotlin [field-property box="kirigami_cut_plate" name="code" path="kirigami_cut_plate_code_.kt"]
import afield.examples.chain.KirigamiSolidChain

val n = 8
val thickness = 0.1
val gap = 0.04 // Half the slit width.
val bridge = 0.12 // Hinge bar length along the cut.
val plate = KirigamiSolidChain.measure(n, angle = 0.0, thickness = thickness, gap = gap, bridge = bridge)

markdown(
    "numbers",
    """
    - ${plate.tiles} tiles, ${plate.hinges} hinges
    - Genus ${plate.genus}, predicted by hinges − tiles + 1 = ${plate.predictedGenus}
    - Parts: ${plate.parts}, watertight: ${plate.watertight}
    - Volume ${plate.volume.f6} = area ${plate.sectionArea.f6} × thickness $thickness""".trimIndent(),
)

showImgGrid(
    "the cut plate, $thickness thick" to
        imgFromBytes(
            plate.mesh.renderPng(
                MeshView(yawDegrees = 25.0, pitchDegrees = 40.0, distance = 2.7, width = 900, height = 600),
            ),
        ),
    columns = 1,
    cellSide = 600,
)
```

