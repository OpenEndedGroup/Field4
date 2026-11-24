# Field Document: vector_scenes

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. lighthouse from four angles

<!-- field-box id="vs_lighthouse" index="0" -->

```edn [field-box id="vs_lighthouse" role="properties"]
{"id" "vs_lighthouse" "index" 0 "name" "lighthouse from four angles" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "lighthouse from four angles" "_tombstone" false "frame" #afield/rect [40.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "vs_lighthouse_code_.kt" "lines" 22 "characters" 1219 "sha256" "a6cd1c7a04427fba2c2201e52f2c5f62b9ffd78f8b6300d825b60b5ac08b0add"}]}
```

```kotlin [field-property box="vs_lighthouse" name="code" path="vs_lighthouse_code_.kt"]
val white = vec(0.94, 0.93, 0.89, 1.0)
val red = vec(0.82, 0.22, 0.2, 1.0)
val lighthouse = vectorScene {
    segments(20)
    group({ translate(0.0, -6.0, 0.0) }) { box(260.0, 12.0, 200.0, vec(0.35, 0.55, 0.4, 1.0)) }
    cylinder(34.0, 70.0, white, topRadius = 30.0)
    group({ translate(0.0, 70.0, 0.0) }) { cylinder(30.0, 60.0, red, topRadius = 26.0) }
    group({ translate(0.0, 130.0, 0.0) }) { cylinder(26.0, 50.0, white, topRadius = 22.0) }
    group({ translate(0.0, 180.0, 0.0) }) { cylinder(30.0, 6.0, vec(0.25, 0.25, 0.28, 1.0)) }
    group({ translate(0.0, 186.0, 0.0) }) { cylinder(18.0, 22.0, vec(1.0, 0.85, 0.35, 1.0)) }
    group({ translate(0.0, 208.0, 0.0) }) { cone(24.0, 26.0, red) }
    group({ translate(80.0, 25.0, 30.0) }) { box(70.0, 50.0, 50.0, white, top = red) }
    group({ translate(-70.0, 0.0, 50.0) }) { hemisphere(22.0, vec(0.55, 0.52, 0.5, 1.0)) }
    group({ translate(-40.0, 0.0, 75.0) }) { hemisphere(14.0, vec(0.5, 0.48, 0.47, 1.0)) }
}

// four quarter turns, side by side
val views =
    (0 until 4).flatMap { k ->
        lighthouse.render(yaw = 0.4 + k * Math.PI / 2, pitch = -0.35).map { it + vec(k * 330.0, 0.0) }
    }
lines(views, w = 720.0, h = 220.0, fitToSize = true)
```

## 2. turn the camera

<!-- field-box id="vs_turn" index="1" -->

```edn [field-box id="vs_turn" role="properties"]
{"id" "vs_turn" "index" 1 "name" "turn the camera" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "turn the camera" "_tombstone" false "frame" #afield/rect [400.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "vs_turn_code_.kt" "lines" 17 "characters" 643 "sha256" "81493557ca881f561d98a9eb2a693ac672a4f4803f38eb45240e44412a94d795"}]}
```

```kotlin [field-property box="vs_turn" name="code" path="vs_turn_code_.kt"]
val white = vec(0.94, 0.93, 0.89, 1.0)
val red = vec(0.82, 0.22, 0.2, 1.0)
val scene = vectorScene {
    cylinder(30.0, 200.0, white)
    group({ translate(0.0, 200.0, 0.0) }) { cone(36.0, 40.0, red) }
    group({ translate(90.0, 20.0, 0.0) }) { box(60.0, 40.0, 40.0, vec(0.4, 0.6, 0.85, 1.0), top = red) }
    group({ translate(-80.0, 0.0, 20.0) }) { hemisphere(24.0, vec(0.55, 0.52, 0.5, 1.0)) }
}

var yaw = 1.2
fun draw() = lines(scene.render(yaw = yaw, pitch = -0.25), w = 420.0, h = 300.0, fitToSize = true)

slider("camera", min = 0.0, max = 6.28, step = 0.01, initialValue = yaw, channel = "camera") {
    yaw = it
    draw()
}
draw()
```

## 3. wrong-order pixels

<!-- field-box id="vs_benchmark" index="2" -->

```edn [field-box id="vs_benchmark" role="properties"]
{"id" "vs_benchmark" "index" 2 "name" "wrong-order pixels" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "wrong-order pixels" "_tombstone" false "frame" #afield/rect [1120.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "vs_benchmark_code_.kt" "lines" 66 "characters" 2425 "sha256" "5a2745395e1920ca6d9ce4d71fa1c173064dea476f0afec532e189c3d5118974"}]}
```

```kotlin [field-property box="vs_benchmark" name="code" path="vs_benchmark_code_.kt"]
val white = vec(0.94, 0.93, 0.89, 1.0)
val red = vec(0.82, 0.22, 0.2, 1.0)
val scenes = mapOf(
    "lighthouse" to
        vectorScene {
            segments(20)
            group({ translate(0.0, -6.0, 0.0) }) { box(260.0, 12.0, 200.0, vec(0.35, 0.55, 0.4, 1.0)) }
            cylinder(34.0, 180.0, white, topRadius = 22.0)
            group({ translate(0.0, 180.0, 0.0) }) { cone(24.0, 26.0, red) }
            group({ translate(80.0, 25.0, 30.0) }) { box(70.0, 50.0, 50.0, white, top = red) }
            group({ translate(-70.0, 0.0, 50.0) }) { hemisphere(22.0, vec(0.55, 0.52, 0.5, 1.0)) }
        },
    "village" to
        vectorScene {
            segments(16)
            group({ translate(0.0, -4.0, 0.0) }) { box(400.0, 8.0, 400.0, vec(0.4, 0.6, 0.4, 1.0)) }
            for (i in 0 until 4) for (j in 0 until 4) group(
                {
                    translate(i * 90.0 - 135.0, 20.0, j * 90.0 - 135.0)
                    rotateY(i * 0.7 + j)
                },
            ) {
                box(50.0, 40.0, 36.0, white, top = red)
                group({ translate(30.0, -20.0, 30.0) }) { cylinder(6.0, 50.0, vec(0.5, 0.35, 0.2, 1.0)) }
            }
        },
    "sticks" to
        vectorScene {
            for (k in 0 until 12) group(
                {
                    rotateY(k * 0.52)
                    rotateZ(0.3)
                },
            ) {
                line(
                    -120.0,
                    k * 6.0,
                    -30.0,
                    120.0,
                    k * 6.0,
                    30.0,
                    width = 12.0,
                    color = vec(0.3 + k * 0.05, 0.5, 0.8, 1.0),
                )
            }
        },
)
fun pct(s: VectorScene, mode: DepthOrder): String {
    var wrong = 0
    var covered = 0
    for (k in 0 until 8) {
        val (w, c) = s.wrongOrderShare(yaw = k * Math.PI / 4, pitch = -0.35, mode = mode, w = 200, h = 200)
        wrong += w
        covered += c
    }
    return "%.2f%%".format(100.0 * wrong / covered)
}
val rows =
    scenes.entries.joinToString("") { (name, s) ->
        "<tr><td>$name</td><td>${pct(s, DepthOrder.CENTROID)}</td><td>${pct(s, DepthOrder.PAIRWISE)}</td></tr>"
    }
display(
    "main",
    """<table style="font:14px sans-serif;border-collapse:collapse" cellpadding="6">
<tr><th align="left">scene</th><th>centroid sort</th><th>pairwise</th></tr>$rows</table>""",
)
```

## 4. flat, soft and plotted

<!-- field-box id="vs_shading" index="3" -->

```edn [field-box id="vs_shading" role="properties"]
{"id" "vs_shading" "index" 3 "name" "flat, soft and plotted" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "flat, soft and plotted" "_tombstone" false "frame" #afield/rect [760.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "vs_shading_code_.kt" "lines" 12 "characters" 663 "sha256" "9005952b60f9faf8815b2bc3a6dce9a42824b542ea433d98127c5657d105911e"}]}
```

```kotlin [field-property box="vs_shading" name="code" path="vs_shading_code_.kt"]
val model = vectorScene {
    segments(28)
    hemisphere(50.0, vec(0.95, 0.75, 0.3, 1.0))
    group({ translate(0.0, -60.0, 0.0) }) { cylinder(50.0, 60.0, vec(0.3, 0.6, 0.9, 1.0)) }
    group({ translate(0.0, 50.0, 0.0) }) { cone(14.0, 30.0, vec(0.85, 0.25, 0.25, 1.0)) }
}
val flat = model.render(yaw = 0.5, pitch = -0.3, shading = Shading.FLAT)
val soft = model.render(yaw = 0.5, pitch = -0.3, shading = Shading.SOFT).map { it + vec(160.0, 0.0) }
val plotted = model.render(yaw = 0.5, pitch = -0.3, shading = Shading.NONE, outline = vec(0.1, 0.1, 0.1, 1.0)).map {
    it + vec(320.0, 0.0)
}
lines(flat + soft + plotted, w = 600.0, h = 220.0, fitToSize = true)
```

