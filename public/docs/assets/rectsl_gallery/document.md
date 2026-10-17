# Field Document: rectsl_gallery

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 39 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole" "isCsoundRoot" "isPdRoot" "isDatalogRoot" "isClojureRoot" "isFieldMLRoot" "isForthRoot" "isLuaRoot" "isMorphoRoot" "isNanoRoot" "nanoLaunchDelayMs" "nanoMemoryInit" "isKotlinJsRoot" "aiStudy" "mainLanguage" "spreadsheet"]}
```

## 1. window + renderer

<!-- field-box id="renderer" index="0" -->

```edn [field-box id="renderer" role="properties"]
{"id" "renderer" "index" 0 "name" "window + renderer" "parents" ["root"] "children" ["musicality-four-to-the-floor" "musicality-variation" "musicality-signals" "musicality-surface" "mirrored-orbit" "rising-bars" "orbit-petals" "gradient-sweep" "pinwheel" "lissajous-swarm" "strobe-columns" "spectrum-bars" "phase-fan" "wave-lattice" "two-mirrored" "named-tracks" "groups-material" "radial-bloom" "grid-ripple" "stagger-cascade" "spiral-shards" "gaussian-comb" "eased-orbit" "inline-functions" "easing-ladder" "time-canon" "key-lenses" "selection-logic" "all-time" "kaleidoscope" "grid-field" "curve-transforms" "paint-kit" "auto-orient" "emitter-stream" "shell-split" "array-mandala" "cascade-split"] "properties" {"children" #{#afield/box "musicality-four-to-the-floor" #afield/box "musicality-variation" #afield/box "musicality-signals" #afield/box "musicality-surface" #afield/box "mirrored-orbit" #afield/box "rising-bars" #afield/box "orbit-petals" #afield/box "gradient-sweep" #afield/box "pinwheel" #afield/box "lissajous-swarm" #afield/box "strobe-columns" #afield/box "spectrum-bars" #afield/box "phase-fan" #afield/box "wave-lattice" #afield/box "two-mirrored" #afield/box "named-tracks" #afield/box "groups-material" #afield/box "radial-bloom" #afield/box "grid-ripple" #afield/box "stagger-cascade" #afield/box "spiral-shards" #afield/box "gaussian-comb" #afield/box "eased-orbit" #afield/box "inline-functions" #afield/box "easing-ladder" #afield/box "time-canon" #afield/box "key-lenses" #afield/box "selection-logic" #afield/box "all-time" #afield/box "kaleidoscope" #afield/box "grid-field" #afield/box "curve-transforms" #afield/box "paint-kit" #afield/box "auto-orient" #afield/box "emitter-stream" #afield/box "shell-split" #afield/box "array-mandala" #afield/box "cascade-split"} "parents" #{#afield/box "root"} "name" "window + renderer" "frame" #afield/rect [-640.0 -360.0 240.0 90.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "renderer_code_.kt" "lines" 13 "characters" 229 "sha256" "030f2000953462d06c709711ec400eff758f7ee2c7ba053dceffd46b813936db"}]}
```

```kotlin [field-property box="renderer" name="code" path="renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = true

once.renderer {
    window = Window("Field \u2014 RectSL gallery")
    Renderer(window)
}

start(renderer::run)
```

## 2. mirrored-orbit

<!-- field-box id="mirrored-orbit" index="1" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: mirrored-orbit

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="mirrored-orbit" role="properties"]
{"id" "mirrored-orbit" "index" 1 "name" "mirrored-orbit" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "mirrored-orbit" "frame" #afield/rect [-300.0 -220.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mirrored-orbit_code_.kt" "lines" 74 "characters" 2015 "sha256" "8acd0cf17579eec2a92925973700739df091c1a8a9e68e1d28d082ad40740456"}]}
```

```kotlin [field-property box="mirrored-orbit" name="code" path="mirrored-orbit_code_.kt"]
/*
RectSL example: mirrored-orbit

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 16 9 meters
->duration 6
->fps 30

"left" rect
  -4 0 ->xy
  2.2 ->square
  0xff5a3c solid ->fill
  '( 0 -4  3 0  6 4 ) ->x
  '( 0 -2  1.5 2  3 -2  4.5 2  6 -2 ) ->y
  '( 0 0  6 6.28318 ) ->rot
;

dup "right" ->rename
  ->mirror-x
  0x3c8cff solid ->fill
;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 3. rising-bars

<!-- field-box id="rising-bars" index="2" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: rising-bars

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="rising-bars" role="properties"]
{"id" "rising-bars" "index" 2 "name" "rising-bars" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "rising-bars" "frame" #afield/rect [0.0 -220.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rising-bars_code_.kt" "lines" 79 "characters" 2120 "sha256" "4cc6523f311fb6843c78ba6002dbe13079cf640972be8a7c42cbc42a2bfeaacb"}]}
```

```kotlin [field-property box="rising-bars" name="code" path="rising-bars_code_.kt"]
/*
RectSL example: rising-bars

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 18 8 meters
->duration 6
->fps 30

linear-track "rise" '( 0=-2.6   3=0   6=2.6 )
hold-track "pulse" '( 0=0.2   2=1.0   4=0.45 )

"bar-template" rect
  0 -1.6 ->xy
  0.9 2.2 ->wh
  "rise" track-ref ->y
  "pulse" track-ref smooth ->alpha
  0xeeeeee solid ->fill
;

8 '(
  "bar-template" ->select
    clone
    i ->layer
    i 0.1 * ->z
    i 3.5 - 2.0 * ->x
) repeat
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 4. orbit-petals

<!-- field-box id="orbit-petals" index="3" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: orbit-petals

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="orbit-petals" role="properties"]
{"id" "orbit-petals" "index" 3 "name" "orbit-petals" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "orbit-petals" "frame" #afield/rect [-300.0 -30.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "orbit-petals_code_.kt" "lines" 81 "characters" 2093 "sha256" "68b19a862527196666cc3281d01847f3701af629205f71ac2a8f6f68a22a81e4"}]}
```

```kotlin [field-property box="orbit-petals" name="code" path="orbit-petals_code_.kt"]
/*
RectSL example: orbit-petals

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 18 8 meters
->duration 8
->fps 30

material bands '(
  uv 6.0 * sin abs
)

"row" group
  "petal" rect
    0 0 ->xy
    1.7 ->square
    '( 0 0   4 3.14159   8 6.28318 ) ->rot
    0xffffff solid ->fill
    bands ->material
  ;

  0 7 '(
    "petal" ->select
      clone
      i 3 - 2.4 * 0 ->xy
      bands ->material
  ) range
end-group
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 5. gradient-sweep

<!-- field-box id="gradient-sweep" index="4" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: gradient-sweep

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="gradient-sweep" role="properties"]
{"id" "gradient-sweep" "index" 4 "name" "gradient-sweep" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "gradient-sweep" "frame" #afield/rect [0.0 -30.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gradient-sweep_code_.kt" "lines" 68 "characters" 1952 "sha256" "ad94f888f51a2e78b8ddb722e5b1ebd9a8c26b1e9ba29caa4d60ed2001a8cea0"}]}
```

```kotlin [field-property box="gradient-sweep" name="code" path="gradient-sweep_code_.kt"]
/*
RectSL example: gradient-sweep

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 18 8 meters
->duration 6
->fps 30

"panel" rect
  0 0 ->xy
  15 6 ->wh
  0xff2d55 0x2d7bff  -7.5 -3  7.5 3  linear ->fill
  '( 0 -0.3  3 0.3  6 -0.3 ) ->rot
  '( 0 0.7  3 1.0  6 0.7 ) ->scale
;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 6. pinwheel

<!-- field-box id="pinwheel" index="5" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: pinwheel

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="pinwheel" role="properties"]
{"id" "pinwheel" "index" 5 "name" "pinwheel" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "pinwheel" "frame" #afield/rect [-300.0 160.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pinwheel_code_.kt" "lines" 75 "characters" 2000 "sha256" "20b9c2f7da2e552022d597f998ba953307be9c09b8296b8667131bb276c34e4b"}]}
```

```kotlin [field-property box="pinwheel" name="code" path="pinwheel_code_.kt"]
/*
RectSL example: pinwheel

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 18 8 meters
->duration 6
->fps 30

"blade" rect
  0 0 ->xy
  1.2 ->square
  0xffd23c solid ->fill
;

0 16 '(
  "blade" ->select
    clone
    i tau * 16 /
    dup dup cos 7.0 * swap sin 3.0 * ->xy
    ->rot
    '( 0 0  6 6.28318 ) ->rotate
) range
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 7. lissajous-swarm

<!-- field-box id="lissajous-swarm" index="6" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: lissajous-swarm

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="lissajous-swarm" role="properties"]
{"id" "lissajous-swarm" "index" 6 "name" "lissajous-swarm" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "lissajous-swarm" "frame" #afield/rect [0.0 160.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lissajous-swarm_code_.kt" "lines" 78 "characters" 2108 "sha256" "3fcb4bf46cc0859d0c79820d609720f4a2d523c58c9fa0784d3c878369bb8dad"}]}
```

```kotlin [field-property box="lissajous-swarm" name="code" path="lissajous-swarm_code_.kt"]
/*
RectSL example: lissajous-swarm

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 18 9 meters
->duration 8
->fps 30

material glow '(
  uv 5.0 * time 0.6 * + fbm palette
)

"dot" rect
  0 0 ->xy
  0.8 ->square
  '( 0 -7  1 0  2 7  3 0  4 -7 ) 4 loop-time ->x
  '( 0 -3.5  0.5 0  1 3.5  1.5 0  2 -3.5 ) 2 loop-time ->y
  0xffffff solid ->fill
  glow ->material
;

16 '(
  "dot" ->select clone
    i 0.25 * ->delay-all-time
) repeat
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 8. strobe-columns

<!-- field-box id="strobe-columns" index="7" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: strobe-columns

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="strobe-columns" role="properties"]
{"id" "strobe-columns" "index" 7 "name" "strobe-columns" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "strobe-columns" "frame" #afield/rect [-300.0 350.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "strobe-columns_code_.kt" "lines" 75 "characters" 2033 "sha256" "7ae3a75414f0b77cb0280c1a8e7c19a684af00ca4945201febf2aa4da3ccf380"}]}
```

```kotlin [field-property box="strobe-columns" name="code" path="strobe-columns_code_.kt"]
/*
RectSL example: strobe-columns

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 18 8 meters
->duration 6
->fps 30

"cell" rect
  0 0 ->xy
  1.4 ->square
  '( 0 1  1 0  2 1 ) 2 loop-time ->alpha
  0x44e0ff solid ->fill
;

36 '(
  "cell" ->select clone
    i 9 mod 4 - 2.0 *
    i 9 / floor 1.5 - 2.0 *
    ->xy
    i 9 mod 0.22 * ->delay-all-time
) repeat
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 9. spectrum-bars

<!-- field-box id="spectrum-bars" index="8" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: spectrum-bars

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="spectrum-bars" role="properties"]
{"id" "spectrum-bars" "index" 8 "name" "spectrum-bars" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "spectrum-bars" "frame" #afield/rect [0.0 350.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectrum-bars_code_.kt" "lines" 78 "characters" 2079 "sha256" "00ce95b24879b4a1e3d5b2eb68e2e305e9e9519ebdbb837ebb8acfa7c0d3691d"}]}
```

```kotlin [field-property box="spectrum-bars" name="code" path="spectrum-bars_code_.kt"]
/*
RectSL example: spectrum-bars

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 20 8 meters
->duration 6
->fps 30

material spectrum '(
  uv 3.0 * time 0.5 * + fbm palette
)

"bar" rect
  0 0 ->xy
  0.9 1 ->wh
  '( 0 0.6  0.75 6.5  1.5 0.6 ) 1.5 loop-time ->h
  0xffffff solid ->fill
  spectrum ->material
;

16 '(
  "bar" ->select clone
    i 7.5 - 1.2 * 0 ->xy
    i 0.14 * ->delay-all-time
) repeat
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 10. phase-fan

<!-- field-box id="phase-fan" index="9" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: phase-fan

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="phase-fan" role="properties"]
{"id" "phase-fan" "index" 9 "name" "phase-fan" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "phase-fan" "frame" #afield/rect [-300.0 540.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "phase-fan_code_.kt" "lines" 73 "characters" 1999 "sha256" "326dd644be477c24e8968b22708962f6ad252b04c749078d1b28b6694df9e512"}]}
```

```kotlin [field-property box="phase-fan" name="code" path="phase-fan_code_.kt"]
/*
RectSL example: phase-fan

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 18 8 meters
->duration 6
->fps 30

"blade" rect
  0 0 ->xy
  0.35 4.5 ->wh
  '( 0 -1.0  1 1.0  2 -1.0 ) 2 loop-time ->rot
  0xff3caa solid ->fill
;

24 '(
  "blade" ->select clone
    i 11.5 - 0.72 * 0 ->xy
    i 0.11 * ->delay-all-time
) repeat
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 11. wave-lattice

<!-- field-box id="wave-lattice" index="10" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: wave-lattice

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="wave-lattice" role="properties"]
{"id" "wave-lattice" "index" 10 "name" "wave-lattice" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "wave-lattice" "frame" #afield/rect [0.0 540.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "wave-lattice_code_.kt" "lines" 76 "characters" 2100 "sha256" "1c90cb846575534f2bb1e124b1c551ef5c5c93692ca1b9421e605666137a9eed"}]}
```

```kotlin [field-property box="wave-lattice" name="code" path="wave-lattice_code_.kt"]
/*
RectSL example: wave-lattice

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 18 9 meters
->duration 8
->fps 30

"tile" rect
  0 0 ->xy
  1.0 ->square
  '( 0 0.15  1 1.15  2 0.15 ) 2 loop-time ->scale
  '( 0 0  2 6.28318 ) 2 loop-time ->rot
  0x66ffcc solid ->fill
;

60 '(
  "tile" ->select clone
    i 10 mod 4.5 - 1.7 *
    i 10 / floor 2.5 - 1.7 *
    ->xy
    i 10 mod i 10 / floor + 0.14 * ->delay-all-time
) repeat
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 12. two-mirrored

<!-- field-box id="two-mirrored" index="11" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: two-mirrored

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="two-mirrored" role="properties"]
{"id" "two-mirrored" "index" 11 "name" "two-mirrored" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "two-mirrored" "frame" #afield/rect [-300.0 730.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "two-mirrored_code_.kt" "lines" 73 "characters" 1977 "sha256" "d70ada6840fff2af4029e8d0a1bf5c454a8834322fd2281183053ac955f13aa0"}]}
```

```kotlin [field-property box="two-mirrored" name="code" path="two-mirrored_code_.kt"]
/*
RectSL example: two-mirrored

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 8 4 meters
->duration 4
->fps 24

"left" rect
  -2 0 ->xy
  1.4 ->square
  0xff8c1a solid ->fill
  '( 0 -2  2 0  4 2 ) ->x
  '( 0 0.15  2 1  4 0.15 ) ->alpha
;

dup "right" ->rename
  ->mirror-x
  0x1ac6ff solid ->fill
;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 13. named-tracks

<!-- field-box id="named-tracks" index="12" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: named-tracks

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="named-tracks" role="properties"]
{"id" "named-tracks" "index" 12 "name" "named-tracks" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "named-tracks" "frame" #afield/rect [0.0 730.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "named-tracks_code_.kt" "lines" 79 "characters" 2114 "sha256" "3c44c777b00fd3b3d8c2e3388d21664e1495277c051ca6485bb775202bc66494"}]}
```

```kotlin [field-property box="named-tracks" name="code" path="named-tracks_code_.kt"]
/*
RectSL example: named-tracks

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 18 8 meters
->duration 6
->fps 30

linear-track "rise" '( 0=-3   3=0   6=3 )
hold-track "pulse" '( 0=0.2   2=1.0   4=0.45 )

"bar-template" rect
  0 0 ->xy
  0.7 1.8 ->wh
  "rise" track-ref ->y
  "pulse" track-ref smooth ->alpha
  0xeeeeee solid ->fill
;

8 '(
  "bar-template" ->select
    clone
    i ->layer
    i 0.1 * ->z
    i 3.5 - 2.0 * ->x
) repeat
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 14. groups-material

<!-- field-box id="groups-material" index="13" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: groups-material

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="groups-material" role="properties"]
{"id" "groups-material" "index" 13 "name" "groups-material" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "groups-material" "frame" #afield/rect [-300.0 920.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "groups-material_code_.kt" "lines" 81 "characters" 2110 "sha256" "687553c14b0fa2ecb0fffc7cd80c2c3836b5202d13ce5ab3bbe9129ef77240d9"}]}
```

```kotlin [field-property box="groups-material" name="code" path="groups-material_code_.kt"]
/*
RectSL example: groups-material

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 16 8 meters
->duration 8
->fps 30

material tint '(
  uv 4.0 * sin abs
)

"orbit" group
  "cell" rect
    0 0 ->xy
    1.5 ->square
    "petal" ->tag
    '( 0 0  4 3.14159  8 6.28318 ) ->rot
    0xffffff solid ->fill
    tint ->material
  ;
  0 7 '(
    "cell" ->select
      clone
      i 2.2 * 6.6 - 0 ->xy
      tint ->material
  ) range
end-group
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 15. radial-bloom

<!-- field-box id="radial-bloom" index="14" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: radial-bloom

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="radial-bloom" role="properties"]
{"id" "radial-bloom" "index" 14 "name" "radial-bloom" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "radial-bloom" "frame" #afield/rect [-300.0 720.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "radial-bloom_code_.kt" "lines" 75 "characters" 2026 "sha256" "c4d36f966ed31bdb1ba24b2829d55801d6507760c49d66d9c3f66240e3e3d48d"}]}
```

```kotlin [field-property box="radial-bloom" name="code" path="radial-bloom_code_.kt"]
/*
RectSL example: radial-bloom

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 12 12 abstract
->duration 6
->fps 30

material bloom '(
  uv 5.0 * time 0.8 * + fbm palette
)

"petal" rect
  0.5 2.6 ->wh
  0xffffff solid ->fill
  16 4.0 '(
    f0 tau * ->rot
    '( 0 0  6 6.28318 ) ->rotate
    0.35 1 f mix ->alpha
    bloom ->material
  ) radial
;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 16. musicality-four-to-the-floor

<!-- field-box id="musicality-four-to-the-floor" index="15" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: musicality-four-to-the-floor

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="musicality-four-to-the-floor" role="properties"]
{"id" "musicality-four-to-the-floor" "index" 15 "name" "musicality-four-to-the-floor" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "musicality-four-to-the-floor" "frame" #afield/rect [-300.0 2400.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "musicality-four-to-the-floor_code_.kt" "lines" 104 "characters" 2792 "sha256" "1c1c40f19d476163524630346f167084e1274377c36c27a3e063ca6a79dfd208"}]}
```

```kotlin [field-property box="musicality-four-to-the-floor" name="code" path="musicality-four-to-the-floor_code_.kt"]
/*
RectSL example: musicality-four-to-the-floor

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 16 9 abstract
->tempo 120
->beats-per-cycle 4
->duration-cycles 4
->fps 30
->background 0x0a0a12

# The accent: a full-wall flash, behind everything, on every fourth bar only.
"accent" rect
  16 9 ->wh
  0 0 ->xy
  0xffffff solid ->fill
  0 ->alpha
  ->back
  selected 4 '( 0.14 ->alpha ) ->every
;

# The ring: sixteen slots, five of them sounding, the whole figure turning once a bar.
"ring" group
  "hat" rect
    0.26 1.0 ->wh
    0x7cf5d0 solid ->fill
    16 '( f0 tau * ->rot  f0 tau * 3.2 ->polar ) array
    5 16 0 ->euclid
  ;
end
"ring" ->select
  saw tau * ->rotate
;

# The kick: one copy per event, each authored in its own local time.
"kick" rect
  1.3 ->square
  0xff5a3c solid ->fill
  "bd*4" '(
      step 1.5 - 4.2 *  ->x
      '( 0=5.5  0.34=0 ) ease-out ->y
      '( 0=0  0.10=1  0.5=0 ) linear ->alpha
  ) pat
;

# The score: the same kind of string, read as a ruler instead of as a rhythm.
"tick" rect
  0.34 ->square
  0x3c8cff solid ->fill
  "bd [sd sd] hh*4" '( -3.9 ->y  0.55 ->alpha ) strip
;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 17. musicality-variation

<!-- field-box id="musicality-variation" index="16" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: musicality-variation

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="musicality-variation" role="properties"]
{"id" "musicality-variation" "index" 16 "name" "musicality-variation" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "musicality-variation" "frame" #afield/rect [-300.0 2580.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "musicality-variation_code_.kt" "lines" 82 "characters" 2765 "sha256" "883a85b868b71641b5b0ea783daf6da0de3297fa68e4915bb0a8b3b653da797e"}]}
```

```kotlin [field-property box="musicality-variation" name="code" path="musicality-variation_code_.kt"]
/*
RectSL example: musicality-variation

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 18 10 abstract
->cps 1
->duration-cycles 8
->fps 30

# Pattern-level variation: each row is the same three-hit figure, varied differently.
# `every` fires on cycles 0, 3, 6…; `iter` walks the bar one quarter further each cycle;
# `off` superimposes a shifted echo; `sometimes-by` transforms a deterministic half.
"plain" rect  0.7 ->square  0xff5a3c solid ->fill
  "bd*3" '( f0 14 * 7 -  4.2  ->xy ) pat ;

"faster" rect  0.7 ->square  0xffd23c solid ->fill
  "bd*3" mini 3 '( 2 fast ) every '( f0 14 * 7 -  2.4  ->xy ) pat ;

"walking" rect  0.7 ->square  0xa6e22e solid ->fill
  "bd sd hh cp" mini 4 iter '( f0 14 * 7 -  0.6  ->xy ) pat ;

"echoed" rect  0.7 ->square  0x38e1b0 solid ->fill
  "bd*3" mini 1 8 '( 2 ply ) off '( f0 14 * 7 -  -1.2  ->xy ) pat ;

"swung" rect  0.7 ->square  0x3c8cff solid ->fill
  "bd*8" mini 4 swing '( f0 14 * 7 -  -3.0  ->xy ) pat ;

"sometimes" rect  0.7 ->square  0xc678dd solid ->fill
  "bd*4" mini 0.5 '( press ) sometimes-by '( f0 14 * 7 -  -4.6  ->xy ) pat ;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 18. musicality-signals

<!-- field-box id="musicality-signals" index="17" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: musicality-signals

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="musicality-signals" role="properties"]
{"id" "musicality-signals" "index" 17 "name" "musicality-signals" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "musicality-signals" "frame" #afield/rect [-300.0 2760.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "musicality-signals_code_.kt" "lines" 99 "characters" 3266 "sha256" "76d1766b152bcb2698b4c4e93cb905c287f93c544ab054f3f4253e3211e8f711"}]}
```

```kotlin [field-property box="musicality-signals" name="code" path="musicality-signals_code_.kt"]
/*
RectSL example: musicality-signals

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 18 10 abstract
->tempo 90
->duration-cycles 4
->fps 30

# Smooth: sine straight into a rotation track, and into x through the scalar operators.
"smooth" rect  1.0 ->square  0xff5a3c solid ->fill
  sine 12 * 6 -  ->x   3.4 ->y   sine tau * ->rot ;

# Stepped: the same sine, sampled eight times a cycle. It holds, then snaps.
"stepped" rect  1.0 ->square  0xffd23c solid ->fill
  sine 8 segment  12 *  6 -  ->x   1.6 ->y ;

# A numeric rhythm read directly as a track, and a rest held rather than zeroed.
"steps" rect  0.9 ->square  0xa6e22e solid ->fill
  "0 3 -3 6" ->x   -0.2 ->y ;
"held" rect  0.9 ->square  0x38e1b0 solid ->fill
  "-6 ~ 6" mini nums hold-pat ->x   -2.0 ->y ;

# Remapped, quantised, bipolar, and seeded noise — all the same kind of value.
"remapped" rect  0.8 ->square  0x3c8cff solid ->fill
  tri -5 5 pat-range ->x   -3.6 ->y ;
"quantised" rect  0.8 ->square  0xc678dd solid ->fill
  saw 0.25 quantise 16 * 8 -  ->x   -4.6 ->y ;
"noisy" rect  0.8 ->square  0x56b6c2 solid ->fill
  7 perlin bipolar 6 *  ->x   4.4 ->y ;

# Selection variation: a mirrored twin, a delayed echo, a swung stagger, a dropout.
"twin" rect  0.7 ->square  0xe06c75 solid ->fill
  5 0 ->xy  2.6 ->y
  selected '( 0.45 ->alpha ) ->jux
;
"echo" rect  0.6 ->square  0xd19a66 solid ->fill
  '( 0=-8  4=8 ) linear ->x   0.4 ->y
  selected 0.5 '( 0.4 ->alpha ) ->off
;
"row" rect  0.5 ->square  0x98c379 solid ->fill
  -7 -5.4 ->xy
  8 '( ic 1.6 * 0 ->offset ) array
  0.12 0.6 ->stagger-swing
  0.25 ->degrade
;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 19. musicality-surface

<!-- field-box id="musicality-surface" index="18" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: musicality-surface

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="musicality-surface" role="properties"]
{"id" "musicality-surface" "index" 18 "name" "musicality-surface" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "musicality-surface" "frame" #afield/rect [-300.0 2940.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "musicality-surface_code_.kt" "lines" 133 "characters" 5090 "sha256" "42dac199c357690daafbdc2df9004bb09837db5adaff6e6150db3bac6085186d"}]}
```

```kotlin [field-property box="musicality-surface" name="code" path="musicality-surface_code_.kt"]
/*
RectSL example: musicality-surface

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 20 12 abstract
->tempo 120
->beats-per-cycle 4
->duration-cycles 4
->fps 30

# Clock conversions: seconds computed from musical units rather than typed.
"beat" rect  0.6 ->square  0xff5a3c solid ->fill
  1 beats 8 *  2 cycles 4 * ->xy ;
"cyc" rect  0.6 ->square  0xffd23c solid ->fill
  cycle-secs 4 *  scene-cycles 1 -  ->xy ;

# The per-event context: onset, length, cycle, step, atom, and named parameters.
"ctx" rect  0.5 ->square  0xa6e22e solid ->fill
  "bd:2 sd cp*2" '(
      t 2 *  9 -  ->x
      cyc 2 *  step 0.4 *  +  3 -  ->y
      "n" param 0.1 *  0.4 +  ->alpha
      "n" param? 0.2 *  0.5 +  ->square
      "bd:2" atom? 0.4 *  ->rotate
      dur 3 *  0.3 +  ->scale
      atom ->tag
  ) pat
;

# The remaining pattern transforms.
"rev" rect  0.4 ->square  0x38e1b0 solid ->fill
  "0 1 2" mini rev '( f 16 * 8 -  -1 ->xy ) pat ;
"pal" rect  0.4 ->square  0x3c8cff solid ->fill
  "bd sd" mini palindrome '( f 16 * 8 -  -2 ->xy ) pat ;
"back" rect  0.4 ->square  0xc678dd solid ->fill
  "bd sd hh cp" mini 4 iter-back '( f 16 * 8 -  -3 ->xy ) pat ;
"slowed" rect  0.4 ->square  0x56b6c2 solid ->fill
  "bd sd hh" mini 2 slow '( f 16 * 8 -  -4 ->xy ) pat ;
"lingered" rect  0.4 ->square  0xe06c75 solid ->fill
  "bd sd hh cp" mini 1 2 linger '( f 16 * 8 -  -5 ->xy ) pat ;
"rotated" rect  0.4 ->square  0xd19a66 solid ->fill
  "0 1 2 3" mini 1 pat-rot '( f 16 * 8 -  1 ->xy ) pat ;
"shuffled" rect  0.4 ->square  0x98c379 solid ->fill
  "bd sd hh cp" mini 4 shuffle '( f 16 * 8 -  2 ->xy ) pat ;
"thinned" rect  0.4 ->square  0x61afef solid ->fill
  "bd*8" mini 0.4 degrade-by '( f 16 * 8 -  3 ->xy ) pat ;
"swungby" rect  0.4 ->square  0xbe5046 solid ->fill
  "bd*8" mini 1 4 4 swing-by '( f 16 * 8 -  4 ->xy ) pat ;
"pressed" rect  0.4 ->square  0x7cf5d0 solid ->fill
  "bd*4" mini 1 3 press-by '( f 16 * 8 -  5 ->xy ) pat ;
"shifted" rect  0.4 ->square  0xffffff solid ->fill
  "bd*4" mini 1 8 pat-delay '( f 16 * 8 -  4.6 ->xy ) pat ;
"stuttered" rect  0.4 ->square  0xff9e64 solid ->fill
  "bd sd" mini 3 16 stut '( f 16 * 8 -  -5.6 ->xy ) pat ;
"over" rect  0.4 ->square  0x9ece6a solid ->fill
  "bd*2" mini '( 2 fast ) superimpose '( f 16 * 8 -  0 ->xy ) pat ;
"in" rect  0.4 ->square  0x7aa2f7 solid ->fill
  "bd sd hh cp" mini 2 '( rev ) inside '( f 16 * 8 -  -0.5 ->xy ) pat ;
"out" rect  0.4 ->square  0xbb9af7 solid ->fill
  "bd sd" mini 2 '( rev ) outside '( f 16 * 8 -  0.5 ->xy ) pat ;

# Building patterns from other patterns, and a Euclidean figure as events.
"together" rect  0.4 ->square  0xf7768e solid ->fill
  "bd*2" mini "sd*3" mini stack-pat '( f 16 * 8 -  -1.5 ->xy ) pat ;
"sequenced" rect  0.4 ->square  0x2ac3de solid ->fill
  "bd*2" mini "sd*4" mini cat-pat '( f 16 * 8 -  1.5 ->xy ) pat ;
"euc" rect  0.4 ->square  0xe0af68 solid ->fill
  3 8 0 euclid '( f 16 * 8 -  5.5 ->xy ) pat ;

# Signals not otherwise shown, and the alternate spelling of quantise.
"cos" rect  0.4 ->square  0x41a6b5 solid ->fill
  cosine 10 * 5 -  -6.4 ->xy ;
"isaw" rect  0.4 ->square  0x73daca solid ->fill
  isaw 10 * 5 -  -6.9 ->xy ;
"sq" rect  0.4 ->square  0xff007c solid ->fill
  square 8 * 4 -  6.4 ->xy ;
"rnd" rect  0.4 ->square  0xb4f9f8 solid ->fill
  3 rand 0.2 quantize 10 * 5 -  6.9 ->xy ;
"asText" rect  0.4 ->square  0xcfc9c2 solid ->fill
  "0 1" mini nums texts '( f 16 * 8 -  -6 ->xy ) pat ;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 20. grid-ripple

<!-- field-box id="grid-ripple" index="19" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: grid-ripple

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="grid-ripple" role="properties"]
{"id" "grid-ripple" "index" 19 "name" "grid-ripple" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "grid-ripple" "frame" #afield/rect [0.0 720.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "grid-ripple_code_.kt" "lines" 70 "characters" 2000 "sha256" "48b58f75bb744bba380eb18cde098b5f5bce11b0259244b7f9f3656976135304"}]}
```

```kotlin [field-property box="grid-ripple" name="code" path="grid-ripple_code_.kt"]
/*
RectSL example: grid-ripple

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 20 9 meters
->duration 4
->fps 30

10 5 '(
  "c" rect
    colc 1.7 *  rowc 1.7 *  ->xy
    0.6 ->square
    '( 0 0.15  1 1.15  2 0.15 ) 2 loop-time ->scale
    colc abs  rowc abs  +  0.16 *  ->delay-all-time
    0x14e0ff solid ->fill
  ;
) grid
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 21. stagger-cascade

<!-- field-box id="stagger-cascade" index="20" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: stagger-cascade

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="stagger-cascade" role="properties"]
{"id" "stagger-cascade" "index" 20 "name" "stagger-cascade" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "stagger-cascade" "frame" #afield/rect [-300.0 900.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stagger-cascade_code_.kt" "lines" 69 "characters" 1959 "sha256" "a0eea846e7a4a466b2337f4e3631afa814487f9e8d1162a951499d0bdfbc74d5"}]}
```

```kotlin [field-property box="stagger-cascade" name="code" path="stagger-cascade_code_.kt"]
/*
RectSL example: stagger-cascade

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 22 8 meters
->duration 3
->fps 30

"bar" rect
  0 0 ->xy
  0.55 1 ->wh
  '( 0 0.4  0.5 4.5  1 0.4 ) 1 loop-time ->h
  0xffd23c solid ->fill
  26 '( ic 0.8 * 0 ->offset ) array
  0.05 ->stagger-time
;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 22. spiral-shards

<!-- field-box id="spiral-shards" index="21" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: spiral-shards

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="spiral-shards" role="properties"]
{"id" "spiral-shards" "index" 21 "name" "spiral-shards" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "spiral-shards" "frame" #afield/rect [0.0 900.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spiral-shards_code_.kt" "lines" 76 "characters" 2065 "sha256" "8e57c6fc03f475e9b699a375ed92414f28c5e265f7ed6afb85eae706e71bc1fa"}]}
```

```kotlin [field-property box="spiral-shards" name="code" path="spiral-shards_code_.kt"]
/*
RectSL example: spiral-shards

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 12 12 abstract
->duration 8
->fps 30

material shimmer '(
  uv 4.0 * time 0.5 * + fbm palette
)

"shard" rect
  0.32 1.05 ->wh
  0xffffff solid ->fill
  48 '(
    i 2.39996 *  f 4.6 *  ->polar
    i 2.39996 *  ->rot
    '( 0 0  8 6.28318 ) ->rotate
    0.4 1 f mix ->alpha
    shimmer ->material
  ) array
;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 23. gaussian-comb

<!-- field-box id="gaussian-comb" index="22" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: gaussian-comb

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="gaussian-comb" role="properties"]
{"id" "gaussian-comb" "index" 22 "name" "gaussian-comb" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "gaussian-comb" "frame" #afield/rect [-300.0 1080.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gaussian-comb_code_.kt" "lines" 73 "characters" 2086 "sha256" "2f1c99cbb1bd0b621ddc029c052f3cd86ae6cd0f9f9643203088e5e3e2c04dcc"}]}
```

```kotlin [field-property box="gaussian-comb" name="code" path="gaussian-comb_code_.kt"]
/*
RectSL example: gaussian-comb

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 22 8 meters
->duration 3
->fps 30

"bar" rect
  0 -3 ->xy
  0.42 1 ->wh
  0xffd23c solid ->fill
  33 '(
    ic 0.62 * 0 ->offset
    '( 0 0.12  0.5 1  1 0.12 ) 1 loop-time  gaussian f sample 6.5 * value*  ->h
    7 noise f sample 0.45 *  0 ->translate
    gaussian f sample 0.25 +  sat  ->alpha
  ) array
  0.05 ->stagger-time
;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 24. eased-orbit

<!-- field-box id="eased-orbit" index="23" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: eased-orbit

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="eased-orbit" role="properties"]
{"id" "eased-orbit" "index" 23 "name" "eased-orbit" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "eased-orbit" "frame" #afield/rect [0.0 1080.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "eased-orbit_code_.kt" "lines" 75 "characters" 2052 "sha256" "a0dad0ee3765e3383e7d917b953c185535b93869f4e22631537f498e7f7d0ab7"}]}
```

```kotlin [field-property box="eased-orbit" name="code" path="eased-orbit_code_.kt"]
/*
RectSL example: eased-orbit

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 12 12 abstract
->duration 6
->fps 30

material tint '(
  uv 3.0 * time 0.6 * + fbm palette
)

"dot" rect
  0.9 ->square
  0xffffff solid ->fill
  20 4.2 '(
    f "back-out" ease 0.25 +  ->scale
    5 noise f sample 0.6 *  ->rotate
    '( 0 0  6 6.28318 ) ->rotate
    tint ->material
  ) radial
;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 25. inline-functions

<!-- field-box id="inline-functions" index="24" -->

### Narrative

From `code`

RectSL example: inline-functions

This program uses a `{{ ... }}` Kotlin escape, so it is compiled with a
dedicated* Kotlin engine rather than this box's own engine — the box engine is
busy running this code and a nested compile on it would re-enter and deadlock.
See the "RectSL examples" docs page for the escape mechanism.

```edn [field-box id="inline-functions" role="properties"]
{"id" "inline-functions" "index" 24 "name" "inline-functions" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "inline-functions" "frame" #afield/rect [-300.0 1260.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "inline-functions_code_.kt" "lines" 76 "characters" 2261 "sha256" "3ab8ecd34db2ce8fd5db825a1f3a6fc28d68045056aae1ac2f3771f5377a7a75"}]}
```

```kotlin [field-property box="inline-functions" name="code" path="inline-functions_code_.kt"]
/*
RectSL example: inline-functions

This program uses a `{{ ... }}` Kotlin escape, so it is compiled with a
*dedicated* Kotlin engine rather than this box's own engine — the box engine is
busy running this code and a nested compile on it would re-enter and deadlock.
See the "RectSL examples" docs page for the escape mechanism.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.compileRectSL
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 22 8 meters
->duration 3
->fps 30

"bar" rect
  0 0 ->xy
  0.42 1 ->wh
  0x38e1b0 solid ->fill
  32 '(
    ic 0.66 * 0 ->offset
    '( 0 0.15  0.5 1.15  1 0.15 ) 1 loop-time
    {{ (gaussian(0.5, 0.2) + fbm(9) * 0.4).eval(f) }}  5.5 *  0.25 +  value*
    ->h
  ) array
  0.05 ->stagger-time
;
"""

val engine = afield.runtime.kotlin.KotlinInterface2(filePrefix = "INLINE_FN_VIEW_")
// The dedicated compiler belongs to this run, including stop and rerun cleanup.
RunScope.current()?.own { engine.dispose() }
val program = compileRectSL(rectsl, engine = engine).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 26. easing-ladder

<!-- field-box id="easing-ladder" index="25" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: easing-ladder

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="easing-ladder" role="properties"]
{"id" "easing-ladder" "index" 25 "name" "easing-ladder" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "easing-ladder" "frame" #afield/rect [-300.0 900.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "easing-ladder_code_.kt" "lines" 77 "characters" 2267 "sha256" "9b223ec8e23e464ba5b6a4b0e98c50786ebcce7a689a107df9133a5c1fc44df2"}]}
```

```kotlin [field-property box="easing-ladder" name="code" path="easing-ladder_code_.kt"]
/*
RectSL example: easing-ladder

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 12 8 meters
->duration 4
->fps 30
->fit contain

"linear" rect
  -4.5 0 ->xy  1.1 ->square  0xff5a3c solid ->fill
  '( 0 -3  2 3  4 -3 ) 4 loop-time ->y ;

"ease-in" rect
  -1.5 0 ->xy  1.1 ->square  0xffd23c solid ->fill
  '( 0 -3  2 3  4 -3 ) ease-in 4 loop-time ->y ;

"ease-out" rect
  1.5 0 ->xy  1.1 ->square  0x38e1b0 solid ->fill
  '( 0 -3  2 3  4 -3 ) ease-out 4 loop-time ->y ;

"ease-inout" rect
  4.5 0 ->xy  1.1 ->square  0x3c8cff solid ->fill
  '( 0 -3  2 3  4 -3 ) ease-in-out 4 loop-time ->y ;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 27. time-canon

<!-- field-box id="time-canon" index="26" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: time-canon

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="time-canon" role="properties"]
{"id" "time-canon" "index" 26 "name" "time-canon" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "time-canon" "frame" #afield/rect [-300.0 1075.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "time-canon_code_.kt" "lines" 77 "characters" 2619 "sha256" "a424e0c6ee7b41b21bb89d453c4a410788472b7a0325bee9ed45bc88d4f4fd63"}]}
```

```kotlin [field-property box="time-canon" name="code" path="time-canon_code_.kt"]
/*
RectSL example: time-canon

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 20 12 meters
->duration 6
->fps 30

"plain" rect  -8 5 ->xy  1 ->square  0xff5a3c solid ->fill
  '( 0 -8  3 0  6 8 ) ->x ;
"delayed" rect  -8 3.4 ->xy  1 ->square  0xff8c3c solid ->fill
  '( 0 -8  3 0  6 8 ) 1.5 delay-time ->x ;
"stretched" rect  -8 1.8 ->xy  1 ->square  0xffd23c solid ->fill
  '( 0 -8  3 0  6 8 ) 0.5 scale-time ->x ;
"shifted" rect  -8 0.2 ->xy  1 ->square  0xa6e22e solid ->fill
  '( 0 -8  3 0  6 8 ) 1 time+ ->x ;
"faster" rect  -8 -1.4 ->xy  1 ->square  0x38e1b0 solid ->fill
  '( 0 -8  3 0  6 8 ) 1.4 time* ->x ;
"reversed" rect  -8 -3 ->xy  1 ->square  0x3c8cff solid ->fill
  '( 0 -8  3 0  6 8 ) reverse-time ->x ;
"pinged" rect  -8 -4.6 ->xy  1 ->square  0xb45cff solid ->fill
  '( 0 -8  1.5 8  3 -8 ) 3 pingpong-time ->x ;
"snapped" rect  -8 -6 ->xy  1 ->square  0xff5ab4 solid ->fill
  '( 0 -8  1.2 -2  2.4 4  3.6 8 ) 1 snap-time ->x ;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 28. key-lenses

<!-- field-box id="key-lenses" index="27" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: key-lenses

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="key-lenses" role="properties"]
{"id" "key-lenses" "index" 27 "name" "key-lenses" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "key-lenses" "frame" #afield/rect [-300.0 1250.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "key-lenses_code_.kt" "lines" 93 "characters" 3177 "sha256" "9165e000e5649bc1ded41eb020e6b4995c2b681942bc89d4ca55829666dadd80"}]}
```

```kotlin [field-property box="key-lenses" name="code" path="key-lenses_code_.kt"]
/*
RectSL example: key-lenses

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 16 10 meters
->duration 4
->fps 30

track named-wobble '( 0 -1  1 1  2 -1  3 1  4 -1 )

"lx" rect  -7 4 ->xy  1 ->square  0xff5a3c solid ->fill
  '( 0 -2  4 2 ) ->x
  x-track  3 value+  ->x ;
"ly" rect  -5 4 ->xy  1 ->square  0xffd23c solid ->fill
  '( 0 -4  2 4  4 -4 ) ->y
  y-track  -2 2 clamp-values  ->y ;
"lw" rect  -3 4 ->xy  2 1 ->wh  0x38e1b0 solid ->fill
  '( 0 0.5  2 3  4 0.5 ) ->w
  w-track  2 keys-before  ->w ;
"lh" rect  -1 4 ->xy  1 2 ->wh  0x3c8cff solid ->fill
  '( 0 0.5  2 3  4 0.5 ) ->h
  h-track  2 keys-after  ->h ;
"lr" rect  1 4 ->xy  1.4 ->square  0xb45cff solid ->fill
  '( 0 0  2 3.14159  4 6.28318 ) ->rot
  rot-track  1 3 keys-between  ->rot ;
"ls" rect  3 4 ->xy  1.2 ->square  0xff5ab4 solid ->fill
  '( 0 0.6  2 1.6  4 0.6 )  '( 0 1.4  2 0.6  4 1.4 ) ->scale-xy
  scale-x-track  1.1 value*  scale-y-track  ->scale-xy ;
"la" rect  5 4 ->xy  1.4 ->square  0xffffff solid ->fill
  '( 0 0.15  2 1  4 0.15 ) ->alpha
  alpha-track  0.2 value+  0 1 clamp-values  ->alpha ;
"lz" rect  0 -1 ->xy  3 ->square  0x224466 solid ->fill
  '( 0 0  4 2 ) ->z
  z-track  1 value+  ->z
  layer-track  0 4 keys-between  ->layer ;
"modes-tcb" rect  -6 -4 ->xy  0.9 ->square  0xff5a3c solid ->fill
  "named-wobble" track-ref  1.5 value*  ->y
  '( 0 0.6  2 1.6  4 0.6 ) tcb  -0.4 0 0 tcb!  ->scale ;
"modes-hold" rect  -4 -4 ->xy  0.9 ->square  0xffd23c solid ->fill
  '( 0 -3  1 -1  2 -3  3 1  4 3 ) hold ->y ;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 29. selection-logic

<!-- field-box id="selection-logic" index="28" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: selection-logic

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="selection-logic" role="properties"]
{"id" "selection-logic" "index" 28 "name" "selection-logic" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "selection-logic" "frame" #afield/rect [-300.0 1425.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "selection-logic_code_.kt" "lines" 94 "characters" 2955 "sha256" "45424b3066c0753a76b12c46dc6751690d7db1cd1694923a9a607b8f2d08f836"}]}
```

```kotlin [field-property box="selection-logic" name="code" path="selection-logic_code_.kt"]
/*
RectSL example: selection-logic

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 20 10 meters
->duration 4
->fps 30

"strip" group
  8 '(
    "cell" rect
      i 3.5 - 2.2 *  0  ->xy
      1.3 ->square
      "cell" ->tag
      0x2e5a86 solid ->fill
      i 2 >=  i 6 <=  and  not   0.55 *  0.45 +   ->alpha
      i 3 <  i 4 >  or   i 5 != and   0.25 *  0.85 +   ->scale
      '( 0 -0.2  2 0.2  4 -0.2 ) 4 loop-time ->rot
  ) times
end

"decor" group
  "dot-a" rect  -8 3.5 ->xy  0.6 ->square  0xffd23c solid ->fill  "spark" ->tag ;
  "dot-b" rect   8 3.5 ->xy  0.6 ->square  0xffd23c solid ->fill  "spark" ->tag ;
end

all rects  '( 0.85 ->opacity* ) each ;
all  '( "spark" tagged? ) filter  '( 1.4 ->scale ) map ;
all  '( rect? ) filter  '( ) each ;
all  '( group? ) filter  '( ) each ;
all groups  '( ) each ;
all  "spark" tagged  '( ) map ;
all rects  '( id "cell" = ) filter  '( 0xff5a3c solid ->fill ) each ;
all rects  '( id "cell" != ) filter  '( ) each ;
roots  '( ) each ;
"strip" ->select selected  '( ) each ;
"strip" ->select children  '( 0.95 ->opacity* ) each ;
"strip" ->select descendants rects  '( ) each ;
"strip" ->select copy-deep "strip2" ->rename ;
"strip2" ->select  0 -4 ->translate ;
all rects  '( "spark" tagged? ) filter  "sparks" ->group-selected ;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 30. all-time

<!-- field-box id="all-time" index="29" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: all-time

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="all-time" role="properties"]
{"id" "all-time" "index" 29 "name" "all-time" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "all-time" "frame" #afield/rect [-300.0 1600.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "all-time_code_.kt" "lines" 78 "characters" 2526 "sha256" "52adeffa04fc12eb54d275bb5b8eae8784e18bee8ecd66783ed4b1f03ada73e3"}]}
```

```kotlin [field-property box="all-time" name="code" path="all-time_code_.kt"]
/*
RectSL example: all-time

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 20 10 meters
->duration 6
->fps 30

"loopy" rect  -6 2.5 ->xy  1.6 ->square  0xff5a3c solid ->fill
  '( 0 0  3 6.28318 ) ->rot  '( 0 0.6  1.5 1.5  3 0.6 ) ->scale
  2 ->loop-all-time ;
"pongy" rect  -2 2.5 ->xy  1.6 ->square  0xffd23c solid ->fill
  '( 0 0  3 6.28318 ) ->rot  '( 0 0.6  1.5 1.5  3 0.6 ) ->scale
  2 ->pingpong-all-time ;
"revvy" rect  2 2.5 ->xy  1.6 ->square  0x38e1b0 solid ->fill
  '( 0 -6  6 6 ) ->x  '( 0 0  6 6.28318 ) ->rot
  ->reverse-all-time ;
"slowy" rect  6 2.5 ->xy  1.6 ->square  0x3c8cff solid ->fill
  '( 0 0  3 6.28318 ) ->rot  '( 0 0.6  1.5 1.5  3 0.6 ) ->scale
  0.5 ->scale-all-time ;

"fan" rect  0 -3 ->xy  0.7 ->square  0xffffff solid ->fill
  '( 0 0  6 6.28318 ) ->rot
  9 '( fc abs 0.7 *  0.3 +  ->alpha ) array
  1.7 0 ->stagger ;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 31. kaleidoscope

<!-- field-box id="kaleidoscope" index="30" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: kaleidoscope

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="kaleidoscope" role="properties"]
{"id" "kaleidoscope" "index" 30 "name" "kaleidoscope" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "kaleidoscope" "frame" #afield/rect [-300.0 1775.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kaleidoscope_code_.kt" "lines" 73 "characters" 2199 "sha256" "ee2cf13c39522567fe8caf0fd20771a79044071a023473b27f437839eb460128"}]}
```

```kotlin [field-property box="kaleidoscope" name="code" path="kaleidoscope_code_.kt"]
/*
RectSL example: kaleidoscope

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 14 14 abstract
->background 0x101018
->duration 6
->fps 30

"seed" rect
  -3.5 -3.5 ->xy  2.4 ->square  0xff5a3c solid ->fill
  '( 0 0  6 6.28318 ) ->rot
  '( 0 -4.5  3 -2.5  6 -4.5 ) ->x ;

"seed" ->select clone "mx" ->rename
  3.5 ->mirror-x-at  0x38e1b0 solid ->fill  ->front ;
"seed" ->select clone "my" ->rename
  0 ->mirror-y-at  0xffd23c solid ->fill  ->back ;
"seed" ->select clone "myy" ->rename
  ->mirror-y  0x3c8cff solid ->fill ;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 32. grid-field

<!-- field-box id="grid-field" index="31" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: grid-field

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="grid-field" role="properties"]
{"id" "grid-field" "index" 31 "name" "grid-field" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "grid-field" "frame" #afield/rect [-300.0 1950.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "grid-field_code_.kt" "lines" 72 "characters" 2139 "sha256" "72eba7ddaf4f17e2b852a3eee716971314f8f9d66b4a3464c0a60a0f964b3810"}]}
```

```kotlin [field-property box="grid-field" name="code" path="grid-field_code_.kt"]
/*
RectSL example: grid-field

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 1600 900 pixels
->fit cover
->duration 4
->fps 30

12 7 '(
  "c" rect
    colc 110 *  rowc 110 *  ->xy
    76 ->square
    colc dup *  rowc dup *  +  sqrt
    dup  0 5 smoothstep  neg 1 +  0.05 max  0 1 clamp  0.9 *  0.1 +  ->alpha
    5 min  neg 5 +  5 /  0.4 pow  40 *  40 +  ->w
    col cols 1 - /  row rows 1 - /  over  +  0.5 *  swap drop
    4 *  ceil  4 /  pi *  ->rot
  ) grid
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 33. curve-transforms

<!-- field-box id="curve-transforms" index="32" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: curve-transforms

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="curve-transforms" role="properties"]
{"id" "curve-transforms" "index" 32 "name" "curve-transforms" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "curve-transforms" "frame" #afield/rect [-300.0 2125.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "curve-transforms_code_.kt" "lines" 70 "characters" 2047 "sha256" "a484f130292a4792035b3371ff466728a2de4328c0f34206c7e3dedb3440eebd"}]}
```

```kotlin [field-property box="curve-transforms" name="code" path="curve-transforms_code_.kt"]
/*
RectSL example: curve-transforms

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 22 8 meters
->duration 3
->fps 30

"bar" rect  0 -3 ->xy  0.5 1 ->wh  0x38e1b0 solid ->fill
  24 '(
    ic 0.85 * 0 ->offset
    '( 0 0.15  1.5 1.1  3 0.15 ) 3 loop-time
    gaussian 3 tile  f sample  5.5 *  value*
    ->h
    7 noise reflect  f sample  0.3 *  0 ->translate
  ) array
;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 34. paint-kit

<!-- field-box id="paint-kit" index="33" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: paint-kit

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="paint-kit" role="properties"]
{"id" "paint-kit" "index" 33 "name" "paint-kit" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "paint-kit" "frame" #afield/rect [-300.0 2300.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "paint-kit_code_.kt" "lines" 83 "characters" 2242 "sha256" "0b9fa22bdba7d83eb89f8828968984a6d47db5592edd08dc862a99cc6f722f48"}]}
```

```kotlin [field-property box="paint-kit" name="code" path="paint-kit_code_.kt"]
/*
RectSL example: paint-kit

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 12 8 meters
->background 0x0a0a12
->fit stretch
->duration 4
->fps 30

material chip '(
  uv 3.0 * time 0.6 * + fbm palette
)

"panel" rect
  0.5 0.5 ->nxy
  0.9 0.5 ->nwh
  rgb 0.12 0.34 0.62 solid ->fill
  '( 0 -0.2  2 0.2  4 -0.2 ) ->rot ;

6 '(
  "chip" rect
    i loop-count 1 - /  fract  4.5 *  2.25 -  -2.2 ->xy
    0.7 ->square
    rgba 0.9 0.4 0.2 0.85 solid ->fill
    i 0.15 *  0.2  0.5  0  ->shader
    chip ->material
    '( 0.9 ->opacity* ) apply
    '( ) defer drop
) times
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 35. auto-orient

<!-- field-box id="auto-orient" index="34" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: auto-orient

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="auto-orient" role="properties"]
{"id" "auto-orient" "index" 34 "name" "auto-orient" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "auto-orient" "frame" #afield/rect [0.0 1350.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "auto-orient_code_.kt" "lines" 79 "characters" 2173 "sha256" "746f0b9f404a15078e93a4423b296ccffdbda98cf5991614b0ff80c1a50c6c89"}]}
```

```kotlin [field-property box="auto-orient" name="code" path="auto-orient_code_.kt"]
/*
RectSL example: auto-orient

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 12 12 abstract
->duration 6
->fps 30

"dart" rect
  1.1 0.26 ->wh
  0xffcc33 solid ->fill
  '( 0=-5  3=0  6=5 ) ->x
  '( 0=-4  1.5=3  3=-3  4.5=3  6=-4 ) ->y
  ->face-travel
;

"pip" rect
  0.7 0.24 ->wh
  0x66ccff solid ->fill
  16 '(
    '( 0=0.6  3=5.4  6=0.6 )  f 6.28318 * cos  value*  ->x
    '( 0=0.6  3=5.4  6=0.6 )  f 6.28318 * sin  value*  ->y
    1.5708 ->face-travel-at
    0.3 1 f mix ->alpha
  ) array
;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 36. emitter-stream

<!-- field-box id="emitter-stream" index="35" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: emitter-stream

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="emitter-stream" role="properties"]
{"id" "emitter-stream" "index" 35 "name" "emitter-stream" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "emitter-stream" "frame" #afield/rect [300.0 1350.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "emitter-stream_code_.kt" "lines" 78 "characters" 2030 "sha256" "f181deeab6137efd10f035adefce31380484b91eca43f411a7c0fa1eaa409294"}]}
```

```kotlin [field-property box="emitter-stream" name="code" path="emitter-stream_code_.kt"]
/*
RectSL example: emitter-stream

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 16 9 abstract
->duration 6
->fps 30

"beacon" rect
  0 3 ->xy
  0.6 ->square
  0xff2266 solid ->fill
  1 ->appear
  4 ->vanish
;

"bullet" rect
  0.42 ->square
  0xffcc33 solid ->fill
  '( 0=-8  2.5=8 ) ->x
  24 '(
    -3 f 6 * + ->y
    i 0.18 *  2.5  ->live
  ) array
;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 37. shell-split

<!-- field-box id="shell-split" index="36" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: shell-split

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="shell-split" role="properties"]
{"id" "shell-split" "index" 36 "name" "shell-split" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "shell-split" "frame" #afield/rect [600.0 1350.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "shell-split_code_.kt" "lines" 74 "characters" 2062 "sha256" "0b1a82c554c8ff2585608193be6a47909e3b1c6a8c93b90317bf6c2f28259785"}]}
```

```kotlin [field-property box="shell-split" name="code" path="shell-split_code_.kt"]
/*
RectSL example: shell-split

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 12 12 abstract
->duration 6
->fps 30

"shell" rect
  0.5 ->square
  0xffee88 solid ->fill
  '( 0=-5  2.5=0 ) ->y
  14 2.5 '(
    0.7 0.22 ->wh
    0xff5522 solid ->fill
    '( 0=0  3.5=6 )  f 6.28318 * cos  value*
    '( 0=0  3.5=6 )  f 6.28318 * sin  value*
    ->translate
    ->face-travel
  ) ->split
;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 38. array-mandala

<!-- field-box id="array-mandala" index="37" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: array-mandala

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="array-mandala" role="properties"]
{"id" "array-mandala" "index" 37 "name" "array-mandala" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "array-mandala" "frame" #afield/rect [900.0 1350.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "array-mandala_code_.kt" "lines" 87 "characters" 2318 "sha256" "d9481a49a7a0821685f08d99e957246aa25c1fe1037467f2daf7fa1a98409bad"}]}
```

```kotlin [field-property box="array-mandala" name="code" path="array-mandala_code_.kt"]
/*
RectSL example: array-mandala

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 18 7 abstract
->duration 8
->fps 30

"a" rect
  0.8 0.22 ->wh
  0xff8844 solid ->fill
  7 2.6 4.71238 1.6 '(
    4.71238  f 1.6 *  +  ->rot
  ) arc
  "arcG" ->group-selected  -5.6 0 ->xy  '( 0=0  8=1.6 ) ->rot
;

"b" rect
  0.3 ->square
  0x44ccff solid ->fill
  56 0.4 2.7 3 '(
    0.25 1 f mix ->alpha
  ) spiral
  "spiralG" ->group-selected  0 0 ->xy  '( 0=0  8=6.28318 ) ->rot
;

"c" rect
  0.22 ->square
  0xffffff solid ->fill
  120 0.3 '(
    "turbo" f cmap solid ->fill
  ) sunflower
  "seedG" ->group-selected  5.6 0 ->xy  '( 0=0  8=-3.14159 ) ->rot
;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

## 39. cascade-split

<!-- field-box id="cascade-split" index="38" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: cascade-split

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="cascade-split" role="properties"]
{"id" "cascade-split" "index" 38 "name" "cascade-split" "parents" ["renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "renderer"} "name" "cascade-split" "frame" #afield/rect [0.0 1600.0 260.0 150.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cascade-split_code_.kt" "lines" 86 "characters" 2547 "sha256" "03cd53f450d59419edecbbe07788db2adfb43de774052651c1da1ad1932ec095"}]}
```

```kotlin [field-property box="cascade-split" name="code" path="cascade-split_code_.kt"]
/*
RectSL example: cascade-split

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 12 10 abstract
->duration 7
->fps 30

"seed" rect
  0.5 0.28 ->wh
  0xfff0a0 solid ->fill
  '( 0=-5    1.4=-0.5 ) ->x
  '( 0=-4.5  1.4=2.5 ) ->y
  ->face-travel
  14 1.4 '(
    0.4 0.15 ->wh
    "turbo" 0.35 f 0.6 * + cmap solid ->fill
    '( 0=0  3.8=1 )  3.5 f 3 * +  value*   0.4 fc 2.6 * +  cos  value*
    '( 0=0  3.8=1 )  3.5 f 3 * +  value*   0.4 fc 2.6 * +  sin  value*
    ->translate
    0  '( 0=0  3.8=-1.6 )  ->translate
    ->face-travel
    5  2.2 f 1.3 * +  '(
      0.24 0.09 ->wh
      0xffe08a solid ->fill
      '( 0=0  2.4=1 )  1.1 fc 0.8 * +  value*   fc 1.2 * 0.3 +  cos  value*
      '( 0=0  2.4=1 )  1.1 fc 0.8 * +  value*   fc 1.2 * 0.3 +  sin  value*
      ->translate
      0  '( 0=0  2.4=-1.1 )  ->translate
      ->face-travel
    ) ->split  drop
  ) ->split
;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
```

