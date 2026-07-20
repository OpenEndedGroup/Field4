// RectSL example: array-mandala
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
wall 18 7 abstract
duration 8
fps 30

"a" rect
  0.8 0.22 wh
  0xff8844 solid fill
  7 2.6 4.71238 1.6 '(
    4.71238  f 1.6 *  +  rot
  ) arc
  "arcG" group-selected  -5.6 0 xy  '( 0=0  8=1.6 ) rot
;

"b" rect
  0.3 square
  0x44ccff solid fill
  56 0.4 2.7 3 '(
    0.25 1 f mix alpha
  ) spiral
  "spiralG" group-selected  0 0 xy  '( 0=0  8=6.28318 ) rot
;

"c" rect
  0.22 square
  0xffffff solid fill
  120 0.3 '(
    "turbo" f cmap solid fill
  ) sunflower
  "seedG" group-selected  5.6 0 xy  '( 0=0  8=-3.14159 ) rot
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
