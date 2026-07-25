// RectSL example: selection-logic
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
