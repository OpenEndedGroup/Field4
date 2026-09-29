# Field Document: fn_differentiable_fit_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. differentiable Fn fit

<!-- field-box id="fn_fit_demo" index="0" -->

```edn [field-box id="fn_fit_demo" role="properties"]
{"id" "fn_fit_demo" "index" 0 "name" "differentiable Fn fit" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "differentiable Fn fit" "_tombstone" false "frame" #afield/rect [80.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fn_fit_demo_code_.kt" "lines" 16 "characters" 672 "sha256" "8c3e34d757dfd6ad1ffbc19d05a7599217c38894383ea808c6fc441505fa2a45"}]}
```

```kotlin [field-property box="fn_fit_demo" name="code" path="fn_fit_demo_code_.kt"]
import afield.stdlib.Functions
import afield.stdlib.fitBest

with(Functions) {
    val target = cos(2) * sin(1.5, amplitude = 0.8, offset = 0.2)
    val seed   = cos(3.4) * sin(1.0, amplitude = 2.0)

    // fitBest = jittered restarts; escapes the frequency local-minimum a single
    // fitGradient would stall in. domain defaults to 0..1; no Functions.Domain(...) needed.
    val run = fitBest(target, seed, restarts = 16)

    plot(target,     channel = "1 target", title = "target  cos·sin")
    plot(run.fitted, channel = "2 fitted", title = "gradient-fitted (best of 16 restarts)")

    run   // Field renders the FnFitRun: RMSE before -> after + a loss sparkline
}
```

