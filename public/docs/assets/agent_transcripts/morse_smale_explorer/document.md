# Field Document: morse_smale_explorer

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 2 - Drifting hills, re-analysed every frame

<!-- field-box id="ms_live_hills" index="0" -->

```edn [field-box id="ms_live_hills" role="properties"]
{"id" "ms_live_hills" "index" 0 "name" "2 - Drifting hills, re-analysed every frame" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Drifting hills, re-analysed every frame" "_tombstone" false "frame" #afield/rect [560.0 60.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ms_live_hills_code_.kt" "lines" 45 "characters" 1648 "sha256" "be6d248e240503856454fdb1b1468b3dae27e210693ca97f96531bdb592acf61"}]}
```

```kotlin [field-property box="ms_live_hills" name="code" path="ms_live_hills_code_.kt"]
import afield.geometry.topology.morsesmale.*

/*
Launch this one (Alt+Up). Six hills circle at different speeds; every frame recomputes
the field and its complex, simplified at 0.05. A critical point's colour comes from its
id, so it stays the same colour for as long as the feature stays on the same grid cell.
*/
val hills = (0 until 6).map { i -> Triple(0.4 + 0.25 * i, 30.0 + 9.0 * i, if (i % 3 == 2) -0.7 else 1.0) }

fun colourOf(id: Int): Vec4 {
    val h = (id * -0x61c88647) ushr 8
    return Vec4(
        0.4f + 0.6f * ((h and 0xff) / 255f),
        0.4f + 0.6f * (((h shr 8) and 0xff) / 255f),
        0.4f + 0.6f * (((h shr 16) and 0xff) / 255f),
        1f,
    )
}

loop {
    val t = frameNumber / 60.0
    val field =
        Img.generate(200, 150) { x, y ->
            hills
                .withIndex()
                .sumOf { (i, h) ->
                    val (speed, radius, amplitude) = h
                    val cx = 100 + radius * cos(speed * t + i)
                    val cy = 75 + radius * 0.7 * sin(speed * t * 1.3 + i)
                    amplitude * exp(-((x - cx).pow(2) + (y - cy).pow(2)) / 500.0)
                }
                .toFloat()
        }
    val r = MorseSmale.compute(field, persistence = 0.05)
    val dots =
        r.criticalPointsFLine(7.0).also { line ->
            line.nodes.forEach { it.color = colourOf(it.properties["nodeId"] as Int) }
        }
    showImgGrid(
        "frame $frameNumber: ${r.criticalPoints.size} critical points" to
            field.normalized().colormap("viridis").drawFLines(r.toFLines() + dots, scale = 2.0),
        columns = 1,
        cellSide = 400,
    )
}
```

## 2. 1 - Ridges of a photograph, under a slider

<!-- field-box id="ms_photo_slider" index="1" -->

```edn [field-box id="ms_photo_slider" role="properties"]
{"id" "ms_photo_slider" "index" 1 "name" "1 - Ridges of a photograph, under a slider" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Ridges of a photograph, under a slider" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ms_photo_slider_code_.kt" "lines" 23 "characters" 849 "sha256" "2280e452a7ef12dc1a86ff0e9f75a06cf1e82fa3ffa06948b80be88de2080ff4"}]}
```

```kotlin [field-property box="ms_photo_slider" name="code" path="ms_photo_slider_code_.kt"]
import afield.geometry.topology.morsesmale.*

// The leaf print from the living-room photograph. Build the complex once; the slider
// only re-reads it at a new threshold.
val print = imgFromFile("public/docs/assets/vision/fline-viewer.jpg", 3).crop(78, 262, 128, 236).scaleBy(2.0)
val complex = MorseSmale.build(print.luma().gaussianBlur(3.0))

fun show(p: Double) {
    val r = complex.simplify(p)
    showImgGrid(
        "${r.criticalPoints.size} points, ${r.separatrixCount} curves" to
            print.drawFLines(
                r.toFLines(traceDescending = false, thickness = 2.0) + r.criticalPointsFLine(5.0),
                scale = 2.0,
            ),
        channel = "ridges",
        columns = 1,
        cellSide = 520,
    )
}

slider("persistence", min = 0.0, max = 0.3, step = 0.005, initialValue = 0.05) { show(it) }
show(0.05)
```

## 3. 3 - What a result holds

<!-- field-box id="ms_network_table" index="2" -->

```edn [field-box id="ms_network_table" role="properties"]
{"id" "ms_network_table" "index" 2 "name" "3 - What a result holds" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - What a result holds" "_tombstone" false "frame" #afield/rect [60.0 260.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ms_network_table_code_.kt" "lines" 32 "characters" 1021 "sha256" "ddf077eef7699594fb3923f470a289222f4ef9201397f5a9f972bc63172aee7f"}]}
```

```kotlin [field-property box="ms_network_table" name="code" path="ms_network_table_code_.kt"]
import afield.geometry.topology.morsesmale.*

val bumps = [
    Triple(70.0, 60.0, 1.0),
    Triple(165.0, 50.0, 0.8),
    Triple(120.0, 125.0, 0.9),
    Triple(45.0, 140.0, -0.6),
    Triple(195.0, 135.0, -0.7),
]
val field =
    Img.generate(240, 180) { x, y ->
        bumps.sumOf { (cx, cy, a) -> a * exp(-((x - cx).pow(2) + (y - cy).pow(2)) / 900.0) }.toFloat()
    }
val result = MorseSmale.compute(field)

// The saddle between the two upper hills, and the separatrices that leave it.
val saddle = result.criticalPoints.filter { it.type == CriticalType.SADDLE }.minBy { abs(it.x - 118) + abs(it.y - 55) }
val rows =
    result.network.curvesAt(saddle.id).joinToString("\n") { c ->
        val end = result.complex.critical(c.endNode!!)!!
        "| ${c.id} | ${end.type.name.lowercase()} ${end.id} | ${c.attributes.getValue("persistence").f3} | ${c.vertexCount} |"
    }
markdown(
    """
${result.network}

| curve | ends at | persistence | points |
| --- | --- | ---: | ---: |
$rows
"""
        .trimIndent(),
)
```

