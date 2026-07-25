// RectSL example: key-lenses
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
