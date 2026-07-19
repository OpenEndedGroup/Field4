// RectSL example: grid-field
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
wall 1600 900 pixels
fit cover
duration 4
fps 30

12 7 '(
  "c" rect
    colc 110 *  rowc 110 *  xy
    76 square
    colc dup *  rowc dup *  +  sqrt
    dup  0 5 smoothstep  neg 1 +  0.05 max  0 1 clamp  0.9 *  0.1 +  alpha
    5 min  neg 5 +  5 /  0.4 pow  40 *  40 +  w
    col cols 1 - /  row rows 1 - /  over  +  0.5 *  swap drop
    4 *  ceil  4 /  pi *  rot
  ) grid
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
