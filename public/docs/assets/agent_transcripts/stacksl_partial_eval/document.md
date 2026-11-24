# Field Document: stacksl_partial_eval

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Partial evaluation — edit the program, rerun, read the residual

<!-- field-box id="pe-stacksl" index="0" -->

```edn [field-box id="pe-stacksl" role="properties"]
{"id" "pe-stacksl" "index" 0 "name" "Partial evaluation — edit the program, rerun, read the residual" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Partial evaluation — edit the program, rerun, read the residual" "_tombstone" false "frame" #afield/rect [40.0 180.0 320.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pe-stacksl_code_.kt" "lines" 23 "characters" 1114 "sha256" "7903e9f9cded9c21d1668a5b429e935b64408e1f69e65a181fa40f7e75d372bf"}]}
```

````kotlin [field-property box="pe-stacksl" name="code" path="pe-stacksl_code_.kt"]
import afield.lang.stacksl.cpu.*

// Edit this and rerun: the residual is re-derived from the program each time.
val program = "uv 4.0 * fbm palette saturate"

val source = StackSLCpu.expandStackAuto(StackSLCpu.stillTemplate(), program)
val started = System.nanoTime()
val residual = GlslPartialEvaluator.specialize(source)
val specializeMs = (System.nanoTime() - started) / 1e6

// The same picture twice: interpreted, then the residual's register program.
val interpreted = StackSLCpu.renderFragment(source, 160, 160)
val specialized = residual.render(160, 160)
val identical = interpreted.rgba.indices.all { interpreted.rgba[it].toRawBits() == specialized.rgba[it].toRawBits() }

display(specialized.toPngBytes())
println(
    "${source.lines().size} lines of GLSL -> ${residual.operationCount} residual operations in %.1f ms"
        .format(specializeMs),
)
println("bit-identical to the interpreter: $identical")
println(residual.operationHistogram().take(6).joinToString(", ") { (op, n) -> "$op $n" })
markdown("residual", "```glsl\n" + residual.toGlsl().lines().take(40).joinToString("\n") + "\n...\n```")
````

## 2. Partial evaluation — power(x, 5) becomes four multiplies

<!-- field-box id="pe-power" index="1" -->

```edn [field-box id="pe-power" role="properties"]
{"id" "pe-power" "index" 1 "name" "Partial evaluation — power(x, 5) becomes four multiplies" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Partial evaluation — power(x, 5) becomes four multiplies" "_tombstone" false "frame" #afield/rect [40.0 40.0 320.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pe-power_code_.kt" "lines" 18 "characters" 603 "sha256" "99154a7c1eaa2efd647a915bede99bb034d543ed4368bc3f53fd0d501393719a"}]}
```

````kotlin [field-property box="pe-power" name="code" path="pe-power_code_.kt"]
import afield.lang.stacksl.cpu.*

// n is known, x is not: the loop runs while specializing and only the multiplies remain.
val exponent = 5
val shader =
    """
    #version 450
    layout(location = 0) in vec2 fragUV;
    layout(location = 0) out vec4 outColor;
    float power(float x, int n) {
        float r = 1.0;
        for (int i = 0; i < n; i++) r *= x;
        return r;
    }
    void main() { outColor = vec4(power(fragUV.x, $exponent), 0.0, 0.0, 1.0); }""".trimIndent()

val residual = GlslPartialEvaluator.specialize(shader)
markdown("residual", "```glsl\n" + residual.toGlsl() + "```")
````

