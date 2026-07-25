# Field Document: rectsl_gallery

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 14 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory" "isClojureRoot" "isForthRoot" "isFieldMLRoot" "isNanoRoot" "nanoLaunchDelayMs" "nanoMemoryInit" "mainLanguage" "spreadsheet"]}
```

## 1. root

<!-- field-box id="root" index="0" -->

```edn [field-box id="root" role="properties"]
{"id" "root" "index" 0 "name" "root" "parents" ["metaroot"] "children" ["mirrored-orbit" "rising-bars" "orbit-petals" "gradient-sweep" "pinwheel" "lissajous-swarm" "strobe-columns" "spectrum-bars" "phase-fan" "wave-lattice" "two-mirrored" "named-tracks" "groups-material"] "properties" {"children" #{#afield/box "mirrored-orbit" #afield/box "rising-bars" #afield/box "orbit-petals" #afield/box "gradient-sweep" #afield/box "pinwheel" #afield/box "lissajous-swarm" #afield/box "strobe-columns" #afield/box "spectrum-bars" #afield/box "phase-fan" #afield/box "wave-lattice" #afield/box "two-mirrored" #afield/box "named-tracks" #afield/box "groups-material"} "parents" #{#afield/box "metaroot"} "name" "root" "nested" #{}} "blockProperties" []}
```

## 2. two-mirrored

<!-- field-box id="two-mirrored" index="1" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: two-mirrored

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="two-mirrored" role="properties"]
{"id" "two-mirrored" "index" 1 "name" "two-mirrored" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "two-mirrored" "frame" #afield/rect [-300.0 650.0 260.0 150.0] "preamble" "" "nested" #{} "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "two-mirrored_code_.kt" "lines" 59 "characters" 1473 "sha256" "9097940d585965f7b1545e0032bd9c87e58da00f7b9d992d764398b07622e555"}]}
```

```kotlin [field-property box="two-mirrored" name="code" path="two-mirrored_code_.kt"]
// RectSL example: two-mirrored
//
// The `#rectsl` program below is compiled to a scene; each frame is sampled,
// lowered to a triangle mesh, and drawn through the shared rect vertex shader
// plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
// it live. See the "RectSL examples" docs page for the language.

import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
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

val program = RectAnimationOracle.compile(rectsl).program!!
val shader  = rectMaterialShaderCode(program)

val module = moduleHere {
    vertex = RECT_ANIMATION_VERTEX_SHADER
    fragment = shader.exampleFragmentShader
    blend = true
    depthTest = false
    primitiveType = triangles
}
val m = Mesh()
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = afield.stdlib.linalg.Matrix4f().ortho(-ww / 2f, ww / 2f, -wh / 2f, wh / 2f, -1f, 1f)
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
    true
}
```

## 3. pinwheel

<!-- field-box id="pinwheel" index="2" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: pinwheel

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="pinwheel" role="properties"]
{"id" "pinwheel" "index" 2 "name" "pinwheel" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "pinwheel" "frame" #afield/rect [-300.0 80.0 260.0 150.0] "preamble" "" "nested" #{} "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pinwheel_code_.kt" "lines" 61 "characters" 1498 "sha256" "6a4a0a8980fb11f592d770af2f42746c58d711e75a9af3dd2535d39e506f0ead"}]}
```

```kotlin [field-property box="pinwheel" name="code" path="pinwheel_code_.kt"]
// RectSL example: pinwheel
//
// The `#rectsl` program below is compiled to a scene; each frame is sampled,
// lowered to a triangle mesh, and drawn through the shared rect vertex shader
// plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
// it live. See the "RectSL examples" docs page for the language.

import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
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

val program = RectAnimationOracle.compile(rectsl).program!!
val shader  = rectMaterialShaderCode(program)

val module = moduleHere {
    vertex = RECT_ANIMATION_VERTEX_SHADER
    fragment = shader.exampleFragmentShader
    blend = true
    depthTest = false
    primitiveType = triangles
}
val m = Mesh()
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = afield.stdlib.linalg.Matrix4f().ortho(-ww / 2f, ww / 2f, -wh / 2f, wh / 2f, -1f, 1f)
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
    true
}
```

## 4. strobe-columns

<!-- field-box id="strobe-columns" index="3" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: strobe-columns

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="strobe-columns" role="properties"]
{"id" "strobe-columns" "index" 3 "name" "strobe-columns" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "strobe-columns" "frame" #afield/rect [-300.0 270.0 260.0 150.0] "preamble" "" "nested" #{} "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "strobe-columns_code_.kt" "lines" 61 "characters" 1531 "sha256" "baf06c34811db1e6162a176f84f7b4b44fbb7444f092f983d8f332f804a7118f"}]}
```

```kotlin [field-property box="strobe-columns" name="code" path="strobe-columns_code_.kt"]
// RectSL example: strobe-columns
//
// The `#rectsl` program below is compiled to a scene; each frame is sampled,
// lowered to a triangle mesh, and drawn through the shared rect vertex shader
// plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
// it live. See the "RectSL examples" docs page for the language.

import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
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

val program = RectAnimationOracle.compile(rectsl).program!!
val shader  = rectMaterialShaderCode(program)

val module = moduleHere {
    vertex = RECT_ANIMATION_VERTEX_SHADER
    fragment = shader.exampleFragmentShader
    blend = true
    depthTest = false
    primitiveType = triangles
}
val m = Mesh()
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = afield.stdlib.linalg.Matrix4f().ortho(-ww / 2f, ww / 2f, -wh / 2f, wh / 2f, -1f, 1f)
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
    true
}
```

## 5. spectrum-bars

<!-- field-box id="spectrum-bars" index="4" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: spectrum-bars

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="spectrum-bars" role="properties"]
{"id" "spectrum-bars" "index" 4 "name" "spectrum-bars" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "spectrum-bars" "frame" #afield/rect [0.0 270.0 260.0 150.0] "nested" #{} "autorun" false "code_editorState" "{\"cursorState\":[{\"inSelectionMode\":false,\"selectionStart\":{\"lineNumber\":47,\"column\":2},\"position\":{\"lineNumber\":47,\"column\":2}}],\"viewState\":{\"scrollLeft\":0,\"firstPosition\":{\"lineNumber\":24,\"column\":1},\"firstPositionDeltaTop\":-14},\"contributionsState\":{\"editor.contrib.folding\":{\"lineCount\":65,\"provider\":\"indent\",\"foldedImports\":false},\"editor.contrib.wordHighlighter\":false}}"} "blockProperties" [{"name" "preamble" "language" "text" "lines" 13 "characters" 1176 "sha256" "2015cbc8921cce0e52eca0860ace30cae649c404efce99ddbfc0943ef143f427"} {"name" "code" "language" "kotlin" "path" "spectrum-bars_code_.kt" "lines" 64 "characters" 1575 "sha256" "b58810fdde4afafe333c636a8ca87da4d1eb9b80f38d804297980eb8b12743c3"} {"name" "fragment" "language" "glsl" "path" "spectrum-bars_fragment_.glsl" "lines" 6 "characters" 113 "sha256" "5787ed4e86e51fd1ef4dcbeafb79b13f7a94073b7509b1e9b8a2c738d051f752"} {"name" "vertex" "language" "glsl" "path" "spectrum-bars_vertex_.glsl" "lines" 12 "characters" 182 "sha256" "18bf72c79df919c81dcbf9e7991288a5600a2599563706e4916f8ac2600102ef"}]}
```

```text [field-property box="spectrum-bars" name="preamble"]
import afield.four.buffers.*
import afield.four.modules.*
import arrow.core.left
import arrow.core.right
import java.nio.*
/*graphics-input:pos*/ val Mesh.pos by FromInput()
/*supermap*/ class MappedStructure {;             lateinit var tri : IntBuffer; lateinit var pos : FloatBuffer; ;         };         fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;             this.resize(numVert, numTriangle);             val mesh = this;             MappedStructure().apply {;                  mesh.write("pos") { pos = this ; mesh.t?.mapAndWrite_ints { tri = this ; builder() } } ;            };         }
/*graphics-pc-structure:p*/ var Structure.p by Structure.FromStructure<Any>()
/*graphics-pc-binding:p*/ var Mesh.p by Mesh.BoundFromStructure<afield.stdlib.linalg.Matrix4f>()
/*graphics-pc-binding-group:p*/ var MeshGroup.p by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Matrix4f>()
/*graphics-pc-structure:m*/ var Structure.m by Structure.FromStructure<Any>()
/*graphics-pc-binding:m*/ var Mesh.m by Mesh.BoundFromStructure<afield.stdlib.linalg.Matrix4f>()
/*graphics-pc-binding-group:m*/ var MeshGroup.m by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Matrix4f>()
```

```kotlin [field-property box="spectrum-bars" name="code" path="spectrum-bars_code_.kt"]
// RectSL example: spectrum-bars
//
// The `#rectsl` program below is compiled to a scene; each frame is sampled,
// lowered to a triangle mesh, and drawn through the shared rect vertex shader
// plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
// it live. See the "RectSL examples" docs page for the language.

import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
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

val program = RectAnimationOracle.compile(rectsl).program!!
val shader  = rectMaterialShaderCode(program)

val module = moduleHere {
    vertex = RECT_ANIMATION_VERTEX_SHADER
    fragment = shader.exampleFragmentShader
    blend = true
    depthTest = false
    primitiveType = triangles
}
val m = Mesh()
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = afield.stdlib.linalg.Matrix4f().ortho(-ww / 2f, ww / 2f, -wh / 2f, wh / 2f, -1f, 1f)
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
    true
}
```

```glsl [field-property box="spectrum-bars" name="fragment" path="spectrum-bars_fragment_.glsl"]
#version 450
layout(location = 0) out vec4 uFragColor;
void main()
{
    uFragColor = vec4(1.0, 0.8, 0.4, 1.0);
}```

```glsl [field-property box="spectrum-bars" name="vertex" path="spectrum-bars_vertex_.glsl"]
#version 450
layout (location = 0) in vec3 pos;

layout(push_constant) uniform matrices {
    mat4 p;
    mat4 m;
} pc;

void main()
{
    gl_Position = pc.p * pc.m * vec4(pos, 1);
}```

## 6. lissajous-swarm

<!-- field-box id="lissajous-swarm" index="5" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: lissajous-swarm

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="lissajous-swarm" role="properties"]
{"id" "lissajous-swarm" "index" 5 "name" "lissajous-swarm" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "lissajous-swarm" "frame" #afield/rect [0.0 80.0 260.0 150.0] "preamble" "" "nested" #{} "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lissajous-swarm_code_.kt" "lines" 64 "characters" 1604 "sha256" "363331996811690615f570d595ccc7cb2608f8263967c6c3141a527a819f09e7"}]}
```

```kotlin [field-property box="lissajous-swarm" name="code" path="lissajous-swarm_code_.kt"]
// RectSL example: lissajous-swarm
//
// The `#rectsl` program below is compiled to a scene; each frame is sampled,
// lowered to a triangle mesh, and drawn through the shared rect vertex shader
// plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
// it live. See the "RectSL examples" docs page for the language.

import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
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

val program = RectAnimationOracle.compile(rectsl).program!!
val shader  = rectMaterialShaderCode(program)

val module = moduleHere {
    vertex = RECT_ANIMATION_VERTEX_SHADER
    fragment = shader.exampleFragmentShader
    blend = true
    depthTest = false
    primitiveType = triangles
}
val m = Mesh()
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = afield.stdlib.linalg.Matrix4f().ortho(-ww / 2f, ww / 2f, -wh / 2f, wh / 2f, -1f, 1f)
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
    true
}
```

## 7. phase-fan

<!-- field-box id="phase-fan" index="6" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: phase-fan

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="phase-fan" role="properties"]
{"id" "phase-fan" "index" 6 "name" "phase-fan" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "phase-fan" "frame" #afield/rect [-300.0 460.0 260.0 150.0] "preamble" "" "nested" #{} "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "phase-fan_code_.kt" "lines" 59 "characters" 1497 "sha256" "37dab6cf29c5c48a37fd1882ef3b0d2de144c64810b5fa70b477f45cd3302853"}]}
```

```kotlin [field-property box="phase-fan" name="code" path="phase-fan_code_.kt"]
// RectSL example: phase-fan
//
// The `#rectsl` program below is compiled to a scene; each frame is sampled,
// lowered to a triangle mesh, and drawn through the shared rect vertex shader
// plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
// it live. See the "RectSL examples" docs page for the language.

import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
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

val program = RectAnimationOracle.compile(rectsl).program!!
val shader  = rectMaterialShaderCode(program)

val module = moduleHere {
    vertex = RECT_ANIMATION_VERTEX_SHADER
    fragment = shader.exampleFragmentShader
    blend = true
    depthTest = false
    primitiveType = triangles
}
val m = Mesh()
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = afield.stdlib.linalg.Matrix4f().ortho(-ww / 2f, ww / 2f, -wh / 2f, wh / 2f, -1f, 1f)
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
    true
}
```

## 8. wave-lattice

<!-- field-box id="wave-lattice" index="7" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: wave-lattice

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="wave-lattice" role="properties"]
{"id" "wave-lattice" "index" 7 "name" "wave-lattice" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "wave-lattice" "frame" #afield/rect [0.0 460.0 260.0 150.0] "preamble" "" "nested" #{} "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "wave-lattice_code_.kt" "lines" 62 "characters" 1596 "sha256" "3315abc498d68fede3d7392fb0702d0549db7bde5b1bb11058a5334093b7f74a"}]}
```

```kotlin [field-property box="wave-lattice" name="code" path="wave-lattice_code_.kt"]
// RectSL example: wave-lattice
//
// The `#rectsl` program below is compiled to a scene; each frame is sampled,
// lowered to a triangle mesh, and drawn through the shared rect vertex shader
// plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
// it live. See the "RectSL examples" docs page for the language.

import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
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

val program = RectAnimationOracle.compile(rectsl).program!!
val shader  = rectMaterialShaderCode(program)

val module = moduleHere {
    vertex = RECT_ANIMATION_VERTEX_SHADER
    fragment = shader.exampleFragmentShader
    blend = true
    depthTest = false
    primitiveType = triangles
}
val m = Mesh()
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = afield.stdlib.linalg.Matrix4f().ortho(-ww / 2f, ww / 2f, -wh / 2f, wh / 2f, -1f, 1f)
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
    true
}
```

## 9. mirrored-orbit

<!-- field-box id="mirrored-orbit" index="8" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: mirrored-orbit

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="mirrored-orbit" role="properties"]
{"id" "mirrored-orbit" "index" 8 "name" "mirrored-orbit" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "mirrored-orbit" "frame" #afield/rect [-300.0 -300.0 260.0 150.0] "preamble" "" "nested" #{} "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mirrored-orbit_code_.kt" "lines" 60 "characters" 1509 "sha256" "c116f8c536db463d2e2bd3d5e29e67648080efd8bb7603cbb8c18fe1c106a154"}]}
```

```kotlin [field-property box="mirrored-orbit" name="code" path="mirrored-orbit_code_.kt"]
// RectSL example: mirrored-orbit
//
// The `#rectsl` program below is compiled to a scene; each frame is sampled,
// lowered to a triangle mesh, and drawn through the shared rect vertex shader
// plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
// it live. See the "RectSL examples" docs page for the language.

import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
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

val program = RectAnimationOracle.compile(rectsl).program!!
val shader  = rectMaterialShaderCode(program)

val module = moduleHere {
    vertex = RECT_ANIMATION_VERTEX_SHADER
    fragment = shader.exampleFragmentShader
    blend = true
    depthTest = false
    primitiveType = triangles
}
val m = Mesh()
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = afield.stdlib.linalg.Matrix4f().ortho(-ww / 2f, ww / 2f, -wh / 2f, wh / 2f, -1f, 1f)
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
    true
}
```

## 10. named-tracks

<!-- field-box id="named-tracks" index="9" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: named-tracks

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="named-tracks" role="properties"]
{"id" "named-tracks" "index" 9 "name" "named-tracks" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "named-tracks" "frame" #afield/rect [0.0 650.0 260.0 150.0] "preamble" "" "nested" #{} "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "named-tracks_code_.kt" "lines" 65 "characters" 1608 "sha256" "c13a28408a71b5f0ccbae6343dad680e690ebdec788ccbf47e3ef838bbae3af3"}]}
```

```kotlin [field-property box="named-tracks" name="code" path="named-tracks_code_.kt"]
// RectSL example: named-tracks
//
// The `#rectsl` program below is compiled to a scene; each frame is sampled,
// lowered to a triangle mesh, and drawn through the shared rect vertex shader
// plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
// it live. See the "RectSL examples" docs page for the language.

import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
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

val program = RectAnimationOracle.compile(rectsl).program!!
val shader  = rectMaterialShaderCode(program)

val module = moduleHere {
    vertex = RECT_ANIMATION_VERTEX_SHADER
    fragment = shader.exampleFragmentShader
    blend = true
    depthTest = false
    primitiveType = triangles
}
val m = Mesh()
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = afield.stdlib.linalg.Matrix4f().ortho(-ww / 2f, ww / 2f, -wh / 2f, wh / 2f, -1f, 1f)
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
    true
}
```

## 11. gradient-sweep

<!-- field-box id="gradient-sweep" index="10" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: gradient-sweep

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="gradient-sweep" role="properties"]
{"id" "gradient-sweep" "index" 10 "name" "gradient-sweep" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "gradient-sweep" "frame" #afield/rect [0.0 -110.0 260.0 150.0] "preamble" "" "nested" #{} "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gradient-sweep_code_.kt" "lines" 54 "characters" 1454 "sha256" "ce672cb0afc7b4b8b3f5de0abec9aa9fdef8aa76afa2db8c5562218e2ed07097"}]}
```

```kotlin [field-property box="gradient-sweep" name="code" path="gradient-sweep_code_.kt"]
// RectSL example: gradient-sweep
//
// The `#rectsl` program below is compiled to a scene; each frame is sampled,
// lowered to a triangle mesh, and drawn through the shared rect vertex shader
// plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
// it live. See the "RectSL examples" docs page for the language.

import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
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

val program = RectAnimationOracle.compile(rectsl).program!!
val shader  = rectMaterialShaderCode(program)

val module = moduleHere {
    vertex = RECT_ANIMATION_VERTEX_SHADER
    fragment = shader.exampleFragmentShader
    blend = true
    depthTest = false
    primitiveType = triangles
}
val m = Mesh()
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = afield.stdlib.linalg.Matrix4f().ortho(-ww / 2f, ww / 2f, -wh / 2f, wh / 2f, -1f, 1f)
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
    true
}
```

## 12. rising-bars

<!-- field-box id="rising-bars" index="11" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: rising-bars

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="rising-bars" role="properties"]
{"id" "rising-bars" "index" 11 "name" "rising-bars" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "rising-bars" "frame" #afield/rect [0.0 -300.0 260.0 150.0] "preamble" "" "nested" #{} "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rising-bars_code_.kt" "lines" 65 "characters" 1614 "sha256" "6ff18ca5818e70684559b28e0ffeb83a27fc3bf7284559cbcf74a6968aa77dee"}]}
```

```kotlin [field-property box="rising-bars" name="code" path="rising-bars_code_.kt"]
// RectSL example: rising-bars
//
// The `#rectsl` program below is compiled to a scene; each frame is sampled,
// lowered to a triangle mesh, and drawn through the shared rect vertex shader
// plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
// it live. See the "RectSL examples" docs page for the language.

import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
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

val program = RectAnimationOracle.compile(rectsl).program!!
val shader  = rectMaterialShaderCode(program)

val module = moduleHere {
    vertex = RECT_ANIMATION_VERTEX_SHADER
    fragment = shader.exampleFragmentShader
    blend = true
    depthTest = false
    primitiveType = triangles
}
val m = Mesh()
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = afield.stdlib.linalg.Matrix4f().ortho(-ww / 2f, ww / 2f, -wh / 2f, wh / 2f, -1f, 1f)
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
    true
}
```

## 13. groups-material

<!-- field-box id="groups-material" index="12" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: groups-material

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="groups-material" role="properties"]
{"id" "groups-material" "index" 12 "name" "groups-material" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "groups-material" "frame" #afield/rect [-300.0 840.0 260.0 150.0] "preamble" "" "nested" #{} "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "groups-material_code_.kt" "lines" 67 "characters" 1604 "sha256" "f9981f6b6cabc28c70a1105299422633f41e9a10dc518cd80b24ab1ffe1d9cfb"}]}
```

```kotlin [field-property box="groups-material" name="code" path="groups-material_code_.kt"]
// RectSL example: groups-material
//
// The `#rectsl` program below is compiled to a scene; each frame is sampled,
// lowered to a triangle mesh, and drawn through the shared rect vertex shader
// plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
// it live. See the "RectSL examples" docs page for the language.

import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
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

val program = RectAnimationOracle.compile(rectsl).program!!
val shader  = rectMaterialShaderCode(program)

val module = moduleHere {
    vertex = RECT_ANIMATION_VERTEX_SHADER
    fragment = shader.exampleFragmentShader
    blend = true
    depthTest = false
    primitiveType = triangles
}
val m = Mesh()
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = afield.stdlib.linalg.Matrix4f().ortho(-ww / 2f, ww / 2f, -wh / 2f, wh / 2f, -1f, 1f)
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
    true
}
```

## 14. orbit-petals

<!-- field-box id="orbit-petals" index="13" -->

### Narrative

From `code` tags: `#rectsl`

RectSL example: orbit-petals

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.

```edn [field-box id="orbit-petals" role="properties"]
{"id" "orbit-petals" "index" 13 "name" "orbit-petals" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "orbit-petals" "frame" #afield/rect [-300.0 -110.0 260.0 150.0] "preamble" "" "nested" #{} "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "orbit-petals_code_.kt" "lines" 67 "characters" 1589 "sha256" "e7ae35578c6772bab3bfa5906c994bf679993788385e0c8b11d7328d3751346d"}]}
```

```kotlin [field-property box="orbit-petals" name="code" path="orbit-petals_code_.kt"]
// RectSL example: orbit-petals
//
// The `#rectsl` program below is compiled to a scene; each frame is sampled,
// lowered to a triangle mesh, and drawn through the shared rect vertex shader
// plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
// it live. See the "RectSL examples" docs page for the language.

import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
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

val program = RectAnimationOracle.compile(rectsl).program!!
val shader  = rectMaterialShaderCode(program)

val module = moduleHere {
    vertex = RECT_ANIMATION_VERTEX_SHADER
    fragment = shader.exampleFragmentShader
    blend = true
    depthTest = false
    primitiveType = triangles
}
val m = Mesh()
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = afield.stdlib.linalg.Matrix4f().ortho(-ww / 2f, ww / 2f, -wh / 2f, wh / 2f, -1f, 1f)
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
    true
}
```

