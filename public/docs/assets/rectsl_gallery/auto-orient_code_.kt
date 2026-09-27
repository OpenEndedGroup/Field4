// RectSL example: auto-orient
//
// The `#rectsl` program below is compiled to a scene; each frame is sampled,
// lowered to a triangle mesh, and drawn through the shared rect vertex shader
// plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
// it live. See the "RectSL examples" docs page for the language.

import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.four.modules.Mesh
import afield.four.modules.Module
import afield.four.modules.parameters
import afield.four.modules.pointer

val rectsl = """
#rectsl
->wall 12 12 abstract
->duration 6
->fps 30

"dart" rect
  1.1 0.26 ->wh
  0xffcc33 solid ->fill
  '( 0=-5  3=0  6=5 ) ->x
  '( 0=-4  1.5=3  3=-3  4.5=3  6=-4 ) ->y
  ->face-travel
;

"pip" rect
  0.7 0.24 ->wh
  0x66ccff solid ->fill
  16 '(
    '( 0=0.6  3=5.4  6=0.6 )  f 6.28318 * cos  value*  ->x
    '( 0=0.6  3=5.4  6=0.6 )  f 6.28318 * sin  value*  ->y
    1.5708 ->face-travel-at
    0.3 1 f mix ->alpha
  ) array
;
"""

val program = RectAnimationOracle.compile(rectsl).program!!
val shader  = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

// Built directly, not with `moduleHere { }`: that takes its shaders from this box's
// `vertex`/`fragment` properties and ignores the ones assigned in its block, and this
// fragment shader is regenerated from the program's materials on every run.
val module = Module(parameters(renderer) {
    name = "rectsl"
    vertex = RECT_ANIMATION_VERTEX_SHADER
    fragment = shader.exampleFragmentShader
    blend = true
    depthTest = false
    primitiveType = triangles
}).pointer()
val m = Mesh(renderer, module)
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
