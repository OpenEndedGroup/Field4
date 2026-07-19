// RectSL example: curve-transforms
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
wall 22 8 meters
duration 3
fps 30

"bar" rect  0 -3 xy  0.5 1 wh  0x38e1b0 solid fill
  24 '(
    ic 0.85 * 0 offset
    '( 0 0.15  1.5 1.1  3 0.15 ) 3 loop-time
    gaussian 3 tile  f sample  5.5 *  value*
    h
    7 noise reflect  f sample  0.3 *  0 translate
  ) array
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
