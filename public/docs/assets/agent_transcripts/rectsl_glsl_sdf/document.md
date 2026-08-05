# Field Document: rectsl_glsl_sdf

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. rectsl -> glsl sdf

<!-- field-box id="sdf_lower" index="0" -->

### Narrative

From `code`

Lower a RectSL scene to a GLSL signed distance field.

RectSL is a staged language: the stack VM runs once at compile time, so
`array`, `grid`, selections, charts and the {{ }} escapes are all spent
before this point. What reaches the GPU is a flat list of rects whose
channels are tracks — which is why so little of the language has to be
ported. See rectsl_cheatsheet.md, "Lowering to GLSL".

```edn [field-box id="sdf_lower" role="properties"]
{"id" "sdf_lower" "index" 0 "name" "rectsl -> glsl sdf" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "rectsl -> glsl sdf" "_tombstone" false "frame" #afield/rect [-320.0 -240.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sdf_lower_code_.kt" "lines" 35 "characters" 1199 "sha256" "d686e1bf4978aa39635674b67ba0898c356e17e4121d6403c37527b22fab0060"}]}
```

```kotlin [field-property box="sdf_lower" name="code" path="sdf_lower_code_.kt"]
// Lower a RectSL scene to a GLSL signed distance field.
//
// RectSL is a staged language: the stack VM runs once at compile time, so
// `array`, `grid`, selections, charts and the {{ }} escapes are all spent
// before this point. What reaches the GPU is a flat list of rects whose
// channels are tracks — which is why so little of the language has to be
// ported. See rectsl_cheatsheet.md, "Lowering to GLSL".
import afield.rectsl.RectAnimationOracle
import afield.rectsl.RectSLGlsl

val rectsl = """
#rectsl
->wall 8 5 abstract
->duration 4

"bar" rect
  0.9 2.6 ->wh
  '( 0=-2.6  4=2.6 ) ->x
  '( 0=0  2=1.1  4=0 ) tcb ->y
  '( 0=0  4=1.5708 ) ->rot
;
"""

val program = RectAnimationOracle.compile(rectsl).program
val sdf = RectSLGlsl.emitSdf(program, functionName = "scene")

println("rects unioned      : ${sdf.rectCount}")
println("curves emitted     : ${sdf.trackFunctionCount}")
println("fidelity caveats   : ${if (sdf.ok) "none" else sdf.notes.joinToString("; ")}")
println("shader source chars: ${sdf.glsl.length}")
println()
println(sdf.glsl)

// `sdf.glsl` is self-contained: no uniforms, no buffers, no samplers.
// Paste it into any shader that has a world-space point and a time.
```

## 2. using the distance

<!-- field-box id="sdf_use" index="1" -->

### Narrative

From `code`

A distance is worth more than a coverage mask. Everything below comes
from the single float that `scene` returns, and none of it is available
from the rasterized-quad path that RectSL normally renders through.

```edn [field-box id="sdf_use" role="properties"]
{"id" "sdf_use" "index" 1 "name" "using the distance" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "using the distance" "_tombstone" false "frame" #afield/rect [40.0 -240.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sdf_use_code_.kt" "lines" 52 "characters" 2196 "sha256" "9384bd2c099a1f1feaf6e8c45fce0abd95130f10b818bee3ef45e5783c94e7ea"}]}
```

```kotlin [field-property box="sdf_use" name="code" path="sdf_use_code_.kt"]
// A distance is worth more than a coverage mask. Everything below comes
// from the single float that `scene` returns, and none of it is available
// from the rasterized-quad path that RectSL normally renders through.
import afield.rectsl.RectAnimationOracle
import afield.rectsl.RectSLGlsl
import afield.rectsl.RectSLSdfLook

val rectsl = """
#rectsl
->wall 8 5 abstract
->duration 4
"c" rect  0.7 ->square
  '( 0=0  4=6.28318 ) ->rot
  9 '( ic 0.8 * 0 ->offset ) array
;
"""

val sdf = RectSLGlsl.emitSdf(RectAnimationOracle.compile(rectsl).program, "scene")

// Nine copies, one shared rotation curve: the per-copy offsets are constant
// in time, so they fold to literals instead of nine more functions.
println("rects=${sdf.rectCount} curves=${sdf.trackFunctionCount}")

val fragment = buildString {
    appendLine("#version 450")
    appendLine("layout(location = 0) in vec2 v_world;")
    appendLine("layout(location = 0) out vec4 outColor;")
    appendLine("layout(push_constant) uniform Push { float time; } push;")
    appendLine()
    appendLine(sdf.glsl)
    appendLine()
    appendLine("void main() {")
    appendLine("    float d = scene(v_world, push.time);")
    appendLine("    float fill    = 1.0 - smoothstep(0.0, 0.012, d);")
    appendLine("    float outline = 1.0 - smoothstep(0.0, 0.012, abs(d) - 0.03);")
    appendLine("    float glow    = exp(-6.0 * max(d, 0.0));")
    appendLine("    vec3 c = vec3(0.06, 0.07, 0.10) * glow;")
    appendLine("    c = mix(c, vec3(0.18, 0.62, 0.95), fill);")
    appendLine("    c = mix(c, vec3(1.0), outline * 0.85);")
    appendLine("    outColor = vec4(c, 1.0);")
    appendLine("}")
}

println(fragment.lines().takeLast(12).joinToString("\n"))
println()
println("full fragment: ${fragment.length} chars")
println()
// This box assembles the shader by hand to show what the pieces are. You do not
// have to: RectSLSdfLook is that vocabulary as data, RectSLSdfShader assembles the
// whole thing, and there is one preset per capability the field is good for.
println("presets that do this for you: " + RectSLSdfLook.PRESETS.joinToString(", ") { it.name })
println("to see it drawn and moving, replay the rectsl_sdf_live transcript")
```

