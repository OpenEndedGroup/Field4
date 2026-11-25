# Field Document: contrast_scale_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. ramps as area fills

<!-- field-box id="contrast_bands" index="0" -->

```edn [field-box id="contrast_bands" role="properties"]
{"id" "contrast_bands" "index" 0 "name" "ramps as area fills" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "ramps as area fills" "_tombstone" false "frame" #afield/rect [80.0 360.0 380.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "contrast_bands_code_.kt" "lines" 43 "characters" 1391 "sha256" "41ed952c6b2b4812f9e117b6e53ea8c8634bbe8363ae32f5e4f19691773396e1"}]}
```

```kotlin [field-property box="contrast_bands" name="code" path="contrast_bands_code_.kt"]
val white = vec(1.0, 1.0, 1.0, 1.0)
val blues = contrastScale(colorHex("#3b82f6"), white)
val ambers = contrastScale(colorHex("#eab308"), white)

fun bands(scale: ContrastScale, x0: Double, phase: Double): List<FLine> =
    (0 until 6)
        .map { k ->
            val top = { x: Double -> 260.0 - k * 34.0 - 26.0 * sin(x * 0.018 + phase + k * 0.5) }
            fline {
                moveTo(x0, 300.0)
                for (i in 0..100) {
                    val x = x0 + i * 5.0
                    lineTo(x, top(x))
                }
                lineTo(x0 + 500.0, 300.0)
                lineTo(x0, 300.0)
                filled = true
                color = scale.colors[k]
            }
        }
        .reversed()

fun edges(scale: ContrastScale, x0: Double, phase: Double): List<FLine> =
    (0 until 6).map { k ->
        fline {
            for (i in 0..100) {
                val x = x0 + i * 5.0
                val y = 260.0 - k * 34.0 - 26.0 * sin(x * 0.018 + phase + k * 0.5)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            stroked = true
            color = scale.colors[k + 4]
            thickness = 1.5
        }
    }

lines(
    bands(blues, 20.0, 0.0) + edges(blues, 20.0, 0.0) + bands(ambers, 560.0, 1.3) + edges(ambers, 560.0, 1.3),
    channel = "contrast-bands",
    w = 1080.0,
    h = 320.0,
    fitToSize = true,
)
```

## 2. contrastScale on white and near-black

<!-- field-box id="contrast_swatches" index="1" -->

```edn [field-box id="contrast_swatches" role="properties"]
{"id" "contrast_swatches" "index" 1 "name" "contrastScale on white and near-black" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "contrastScale on white and near-black" "_tombstone" false "frame" #afield/rect [80.0 80.0 380.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "contrast_swatches_code_.kt" "lines" 25 "characters" 1284 "sha256" "5152bfa3ffe26320d52854925457799d823ef398ca5d935f32e9a247c71af06c"}]}
```

```kotlin [field-property box="contrast_swatches" name="code" path="contrast_swatches_code_.kt"]
val seeds = ["#3b82f6", "#ef4444", "#22c55e", "#eab308"].map { colorHex(it) }
val white = vec(1.0, 1.0, 1.0, 1.0)
val nearBlack = colorHex("#0a0a0a")

fun css(c: Vec4) = "rgb(${(c.x * 255).roundToInt()},${(c.y * 255).roundToInt()},${(c.z * 255).roundToInt()})"

fun panel(background: Vec4): String {
    val rows =
        seeds.joinToString("") { seed ->
            val scale = contrastScale(seed, background)
            val cells =
                scale.colors.indices.joinToString("") { i ->
                    val step = contrastScaleSteps[i]
                    val ring =
                        if (step == scale.seedStep) "outline:2px solid ${css(scale.textOn[i])};outline-offset:-4px;"
                        else ""
                    "<div style='width:52px;height:52px;background:${css(scale.colors[i])};color:${css(scale.textOn[i])};$ring" +
                        "font:600 11px system-ui;padding:5px;box-sizing:border-box'>$step<br>${scale.swatches[i].achieved.f1}</div>"
                }
            "<div style='display:flex;gap:3px;margin:5px 0'>$cells</div>"
        }
    return "<div style='background:${css(background)};padding:12px'>$rows</div>"
}

display("contrast-scale", "<div style='display:flex;gap:12px'>${panel(white)}${panel(nearBlack)}</div>")
```

## 3. pick a seed, see its scale

<!-- field-box id="contrast_picker" index="2" -->

```edn [field-box id="contrast_picker" role="properties"]
{"id" "contrast_picker" "index" 2 "name" "pick a seed, see its scale" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "pick a seed, see its scale" "_tombstone" false "frame" #afield/rect [80.0 220.0 380.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "contrast_picker_code_.kt" "lines" 28 "characters" 1150 "sha256" "d8595502a4e16404babf2112ae12d416134d3b31adf276028b8e3a17afcb0c2c"}]}
```

```kotlin [field-property box="contrast_picker" name="code" path="contrast_picker_code_.kt"]
var seed = colorHex("#3b82f6")
var dark = false

fun css(c: Vec4) = "rgb(${(c.x * 255).roundToInt()},${(c.y * 255).roundToInt()},${(c.z * 255).roundToInt()})"

fun redraw() {
    val background = if (dark) colorHex("#0a0a0a") else vec(1.0, 1.0, 1.0, 1.0)
    val scale = contrastScale(seed, background)
    val cells =
        scale.colors.indices.joinToString("") { i ->
            val step = contrastScaleSteps[i]
            val ring =
                if (step == scale.seedStep) "outline:2px solid ${css(scale.textOn[i])};outline-offset:-4px;" else ""
            "<div style='width:60px;height:60px;background:${css(scale.colors[i])};color:${css(scale.textOn[i])};$ring" +
                "font:600 11px system-ui;padding:6px;box-sizing:border-box'>$step<br>${scale.swatches[i].achieved.f2}</div>"
        }
    display("scale", "<div style='background:${css(background)};padding:12px;display:flex;gap:3px'>$cells</div>")
}

colorPicker("seed", Vector4f(seed.x, seed.y, seed.z, 1f)) { c ->
    seed = vec(c.x, c.y, c.z, 1.0)
    redraw()
}
checkbox("dark background", dark, channel = "background") { on ->
    dark = on
    redraw()
}
redraw()
```

