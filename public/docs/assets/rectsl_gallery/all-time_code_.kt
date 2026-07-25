// RectSL example: all-time
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
