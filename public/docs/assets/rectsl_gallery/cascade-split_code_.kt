// RectSL example: cascade-split
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
