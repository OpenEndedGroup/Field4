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
