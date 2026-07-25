// RectSL example: paint-kit
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
->wall 12 8 meters
->background 0x0a0a12
->fit stretch
->duration 4
->fps 30

material chip '(
  uv 3.0 * time 0.6 * + fbm palette
)

"panel" rect
  0.5 0.5 ->nxy
  0.9 0.5 ->nwh
  rgb 0.12 0.34 0.62 solid ->fill
  '( 0 -0.2  2 0.2  4 -0.2 ) ->rot ;

6 '(
  "chip" rect
    i loop-count 1 - /  fract  4.5 *  2.25 -  -2.2 ->xy
    0.7 ->square
    rgba 0.9 0.4 0.2 0.85 solid ->fill
    i 0.15 *  0.2  0.5  0  ->shader
    chip ->material
    '( 0.9 ->opacity* ) apply
    '( ) defer drop
) times
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
