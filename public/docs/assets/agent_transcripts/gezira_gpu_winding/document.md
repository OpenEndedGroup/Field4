# Field Document: gezira_gpu_winding

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="ggw_renderer" index="0" -->

```edn [field-box id="ggw_renderer" role="properties"]
{"id" "ggw_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["ggw_raster"] "properties" {"children" #{#afield/box "ggw_raster"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ggw_renderer_code_.kt" "lines" 12 "characters" 321 "sha256" "0f6e8d909d38bf842d87e19fd6332cfbd2bdc2a20bd205df5efb0bc4f86fb89d"}]}
```

```kotlin [field-property box="ggw_renderer" name="code" path="ggw_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("gezira on the gpu", initialDimensions = Rect(80.0, 80.0, 400.0, 300.0))
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 2. gezira on the gpu

<!-- field-box id="ggw_raster" index="1" -->

```edn [field-box id="ggw_raster" role="properties"]
{"id" "ggw_raster" "index" 1 "name" "gezira on the gpu" "parents" ["root" "ggw_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "ggw_renderer"} "name" "gezira on the gpu" "_tombstone" false "frame" #afield/rect [40.0 200.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ggw_raster_code_.kt" "lines" 49 "characters" 1872 "sha256" "a6701924d1818b9341cae9e696c63a4babc2fa3bfd2cfa8620ccb44311ec4dea"}]}
```

```kotlin [field-property box="ggw_raster" name="code" path="ggw_raster_code_.kt"]
import afield.graphics.nile.*
import afield.graphics.vulkan.util.GpuProbe

val W = 120
val H = 48
// Three shapes where the two fill rules differ, in pixel units.
val shape =
    GeziraGpu.pentagram(22.3, 25.4, 21.0) +
        Gezira.circle(54.2, 21.7, 13.0) +
        Gezira.circle(66.6, 27.3, 13.0) +
        GeziraGpu.holedSquare(84.3, 7.6, 32.0, 14.0, sameWay = true)

// The probe owns the buffers and the shader; one dispatch per fill rule.
val probe = GpuProbe(renderer, "gezira-gpu-demo")
val stage = GeziraGpu.stage(probe)
val jobs = FillRule.entries.map { GeziraGpu.dispatch(probe, stage, shape, W, H, it, it.name) }

// One picture, 2 x 3: a row per fill rule; GPU, CPU, and their difference x 100,000.
val gap = 4
val cw = 3 * W + 2 * gap
val white = DoubleArray(cw * (2 * H + gap))
val red = DoubleArray(white.size)
fun place(into: DoubleArray, c: DoubleArray, col: Int, row: Int) {
    for (y in 0 until H) for (x in 0 until W) {
        into[(row * (H + gap) + y) * cw + col * (W + gap) + x] = c[y * W + x]
    }
}
frames {
    try {
        probe.arm(*jobs.map { it.coverage }.toTypedArray())
        while (probe.stillWaiting()) frame()
        for ((n, job) in jobs.withIndex()) {
            val rule = job.packed.rule
            val gpu = job.read()
            val cpu = Gezira.rasterize(shape, W, H, rule)
            val (worst, mean) = Gezira.error(gpu, cpu)
            println("$rule: largest |gpu - cpu| %.2e, mean %.2e over the covered pixels".format(worst, mean))
            place(white, gpu, 0, n)
            place(white, cpu, 1, n)
            place(red, DoubleArray(gpu.size) { Math.abs(gpu[it] - cpu[it]) * 1e5 }, 2, n)
        }
        lines(
            Gezira.pixels(white, cw, 4.0) + Gezira.pixels(red, cw, 4.0, 1.0, 0.25, 0.2),
            channel = "gpu, cpu, difference",
        )
    } finally {
        probe.close()
    }
}
```

