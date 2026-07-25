// RectSL example: time-canon
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
