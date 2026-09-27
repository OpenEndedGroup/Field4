# Field Document: pat_fn_graph_fit

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. 2 · fit the rhythm

<!-- field-box id="pat_fn_fit" index="0" -->

```edn [field-box id="pat_fn_fit" role="properties"]
{"id" "pat_fn_fit" "index" 0 "name" "2 · fit the rhythm" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · fit the rhythm" "_tombstone" false "frame" #afield/rect [60.0 390.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pat_fn_fit_code_.kt" "lines" 25 "characters" 1033 "sha256" "8ac27f8c72d165cedcf4dc9aa643f257ab0560f1d501712c8b872bbf8fcaf9f6"}]}
```

```kotlin [field-property box="pat_fn_fit" name="code" path="pat_fn_fit_code_.kt"]
import afield.stdlib.Functions
import afield.stdlib.fitGradient
import afield.stdlib.graphNode

with(Functions) {
    val target = patFn("g:(0.9,0.18) g:(0.1,0.18)", cps = 1.0, range = 0.9)
    // Right structure, wrong numbers — wrong heights and wrong widths.
    val seed = patFn("g:(0.7,0.25) g:(0.3,0.12)", cps = 1.0, range = 0.9)

    val run = fitGradient(target, seed, domain = 0.0..2.0, iterations = 200, lr = 0.02, samples = 96)

    // A pat Fn runs to +infinity, so the plot is told which window to sweep.
    plot(target, 0.0..2.0, channel = "1 target", title = "target")
    plot(run.fitted, 0.0..2.0, channel = "2 fitted", title = "fitted")

    val report = """
    seed    ${seed.graphNode().strings["source"]}
    fitted  ${run.fitted.graphNode().strings["source"]}
    RMSE    ${"%.4f".format(run.initialError)} -> ${"%.4f".format(run.finalError)}
    """.trimIndent()
    println(report)
    display("<pre>" + report + "</pre>")

    run   // Field renders the FnFitRun: RMSE before -> after + a loss sparkline
}
```

## 2. 1 · the pat node

<!-- field-box id="pat_fn_inspect" index="1" -->

```edn [field-box id="pat_fn_inspect" role="properties"]
{"id" "pat_fn_inspect" "index" 1 "name" "1 · the pat node" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · the pat node" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pat_fn_inspect_code_.kt" "lines" 20 "characters" 665 "sha256" "b451f485a2efb9a2da862275ca6600be2011b291f37c54589ddbc5c6d7b3c3a4"}]}
```

```kotlin [field-property box="pat_fn_inspect" name="code" path="pat_fn_inspect_code_.kt"]
import afield.stdlib.extractParameters
import afield.stdlib.graphNode
import afield.stdlib.graphText

// Each atom is a kernel: g = Gaussian, adsr = a full envelope. The numbers in the
// text are the gesture; `cps` is how fast the pattern runs in seconds.
val gesture = patFn("g:(0.3,0.12) adsr:(0.8,0.01,0.08,0.4,0.2)", cps = 2.0, range = 0.9)
val node = gesture.graphNode()

val report = """
op        ${node.op}
source    ${node.strings["source"]}
knobs     ${node.children.joinToString("  ") { it.role }}
values    ${gesture.extractParameters().joinToString("  ")}

${gesture.graphText()}
""".trimIndent()

println(report)
display("<pre>" + report + "</pre>")
```

## 3. 3 · what it cannot do

<!-- field-box id="pat_fn_permute" index="2" -->

```edn [field-box id="pat_fn_permute" role="properties"]
{"id" "pat_fn_permute" "index" 2 "name" "3 · what it cannot do" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · what it cannot do" "_tombstone" false "frame" #afield/rect [620.0 390.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pat_fn_permute_code_.kt" "lines" 27 "characters" 1218 "sha256" "e1338ee0f31a67428ed2cb8d487fcee11112715ffe6a8511ac875868b1684324"}]}
```

```kotlin [field-property box="pat_fn_permute" name="code" path="pat_fn_permute_code_.kt"]
import afield.stdlib.Functions
import afield.stdlib.fitGradient
import afield.stdlib.graphNode

with(Functions) {
    val target = patFn("g:(0.9,0.18) g:(0.1,0.18)", cps = 1.0, range = 0.9)
    // The same two heights, swapped. Getting to the target needs 0.9 to move from the
    // second atom to the first: a permutation, not a direction.
    val mirrored = patFn("g:(0.2,0.18) g:(0.8,0.18)", cps = 1.0, range = 0.9)

    val run = fitGradient(target, mirrored, domain = 0.0..2.0, iterations = 200, lr = 0.02, samples = 96)

    plot(target, 0.0..2.0, channel = "1 target", title = "target")
    plot(run.fitted, 0.0..2.0, channel = "2 stalled", title = "stalled fit")

    val report = """
    mirrored seed  ${mirrored.graphNode().strings["source"]}
    ended at       ${run.fitted.graphNode().strings["source"]}
    RMSE           ${"%.4f".format(run.initialError)} -> ${"%.4f".format(run.finalError)}

    It descends, but not to the answer: it escapes into wide kernels, where a
    normalised blend flattens toward the mean of its atom values. Structure search
    (evolve) is the tool for re-assigning values between atoms.
    """.trimIndent()
    println(report)
    display("<pre>" + report + "</pre>")
}
```

