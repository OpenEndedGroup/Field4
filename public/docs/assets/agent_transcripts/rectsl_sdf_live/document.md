# Field Document: rectsl_sdf_live

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. window + renderer

<!-- field-box id="rectsl_sdf_renderer" index="0" -->

```edn [field-box id="rectsl_sdf_renderer" role="properties"]
{"id" "rectsl_sdf_renderer" "index" 0 "name" "window + renderer" "parents" ["root"] "children" ["rectsl_sdf_live"] "properties" {"children" #{#afield/box "rectsl_sdf_live"} "parents" #{#afield/box "root"} "name" "window + renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rectsl_sdf_renderer_code_.kt" "lines" 15 "characters" 276 "sha256" "119c36f12add916d6de62dab2dc8266cee2c018890c2497f264f39c57c4848e6"}]}
```

```kotlin [field-property box="rectsl_sdf_renderer" name="code" path="rectsl_sdf_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.profiling = false

once.renderer {
    window = Window("RectSL SDF")
    Renderer(window)
}

println("rectsl sdf renderer ready")
start(renderer::run)
```

## 2. rectsl scene as a distance field

<!-- field-box id="rectsl_sdf_live" index="1" -->

### Narrative

From `code`

A RectSL scene as a signed distance field, on the GPU, moving.

RectSL normally draws by lowering each sampled frame to a triangle mesh —
painter's-algorithm 2D, one quad per rect. This does something else: it lowers
the whole *program* once, to a closed-form `float scene(vec2 p, float t)`, and
then every pixel asks that function how far away the nearest visible rectangle
is. Time is a uniform, so nothing is re-uploaded per frame; the only thing that
changes between frames is one float.

What that buys, and what it costs, is in rectsl_cheatsheet.md under
"Lowering to GLSL". The short version: outlines and glows at any width, `smin`
blending *between* rectangles, boolean ops, per-pixel normals and sub-frame
motion blur — in exchange for fill, material and z order, because `min` is
union and a union has no layers.

```edn [field-box id="rectsl_sdf_live" role="properties"]
{"id" "rectsl_sdf_live" "index" 1 "name" "rectsl scene as a distance field" "parents" ["root" "rectsl_sdf_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "rectsl_sdf_renderer"} "name" "rectsl scene as a distance field" "_tombstone" false "frame" #afield/rect [300.0 140.0 330.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rectsl_sdf_live_code_.kt" "lines" 103 "characters" 4136 "sha256" "e3cdb0141539848ac1979e529cf980f226a341c4a1be6fd114e8ee068f2a9bf8"}]}
```

```kotlin [field-property box="rectsl_sdf_live" name="code" path="rectsl_sdf_live_code_.kt"]
/*
A RectSL scene as a signed distance field, on the GPU, moving.

RectSL normally draws by lowering each sampled frame to a triangle mesh —
painter's-algorithm 2D, one quad per rect. This does something else: it lowers
the whole *program* once, to a closed-form `float scene(vec2 p, float t)`, and
then every pixel asks that function how far away the nearest visible rectangle
is. Time is a uniform, so nothing is re-uploaded per frame; the only thing that
changes between frames is one float.

What that buys, and what it costs, is in rectsl_cheatsheet.md under
"Lowering to GLSL". The short version: outlines and glows at any width, `smin`
blending *between* rectangles, boolean ops, per-pixel normals and sub-frame
motion blur — in exchange for fill, material and z order, because `min` is
union and a union has no layers.
*/
import afield.graphics.render.Module
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.RectSLGlsl
import afield.lang.rectsl.RectSLSdfLook
import afield.lang.rectsl.RectSLSdfShader

// 1. An ordinary RectSL scene. Nothing here is SDF-specific: one animated
//    template, six copies from `array`. Edit it and re-run.
val rectsl =
    """
#rectsl
->wall 9 5 abstract
->duration 4
->fps 30

"blade" rect  0.85 ->square
  '( 0=0  4=6.28318 ) ->rot
  '( 0=-2.4  2=2.4  4=-2.4 ) tcb ->x
  '( 0=-0.7  1=0.7  2=-0.7  3=0.7  4=-0.7 ) tcb ->y
  6 '( ic 0.8 * 0 ->offset ) array
;
"""
val program = RectAnimationOracle.compile(rectsl).program

/*
2. Pick a look. One of: fill, outline, glow, contours, relief, blend,
   carve (needs a second scene), motion-blur. Change it and re-run.
     contours    — iso-distance lines, the clearest proof this is a distance
     blend       — smin, so the six copies fuse into one body
     motion-blur — 24 instants inside one frame, from closed-form tracks
*/
val look = RectSLSdfLook.preset("relief")

/*
3. Lower the scene and wrap it in the look. Both shader stages are generated:
   there is no hand-written GLSL in this box.

   Note the name: `fragmentSource`, not `fragment`. Inside `parameters { … }` the
   implicit receiver has its own `fragment` property, and in a *box* your top-level
   `val`s are properties of the script rather than locals — so the receiver's one
   wins, `fragment = fragment` assigns the empty string to itself, and shaderc is
   handed a 0-character shader. It fails with "Desktop shaders for Vulkan SPIR-V
   require version 140 or higher", which is not a hint about anything.
*/
val field = RectSLGlsl.emitSdf(program, "scene", look.emitOptions())
val fragmentSource = RectSLSdfShader.fragment(field, look)
println(
    "look=${look.name}  rects=${field.rectCount}  curves=${field.trackFunctionCount}  " +
        "fragment=${fragmentSource.length} chars",
)
if (!field.ok) println("fidelity caveats: ${field.notes.joinToString("; ")}")

// 4. One fullscreen triangle, no vertex attributes, and a clock. `time` is the
//    only thing that changes per frame — the geometry never moves on the CPU.
val module =
    Module(
            parameters(renderer) {
                name = "rectsl-sdf-live"
                vertex = RectSLSdfShader.VERTEX
                fragment = fragmentSource
                blend = false
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()

val mesh = fullscreenTriangle(module)

val startedAt = System.nanoTime()
mesh.push.bind("time") {
    (((System.nanoTime() - startedAt) / 1e9) % program.duration).toFloat()
}
// Keep the wall's height fixed and let its width follow the window, so resizing
// shows more of the world instead of stretching it.
mesh.push.bind("wallWidth") {
    (program.wall.height * renderer.window.width / renderer.window.height.toDouble()).toFloat()
}
mesh.push["wallHeight"] = program.wall.height.toFloat()
// Declared in the shared push block for the probe shader; unused by a look.
mesh.push["probeChannel"] = 0f
mesh.push["probeRange"] = 1f

module.current.meshes["blades"] = mesh
renderer += "rectsl-sdf-live" to module

println("rectsl sdf live — edit the scene or the look and re-run")
```

