// RectSL example: easing-ladder
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
->wall 12 8 meters
->duration 4
->fps 30
->fit contain

"linear" rect
  -4.5 0 ->xy  1.1 ->square  0xff5a3c solid ->fill
  '( 0 -3  2 3  4 -3 ) 4 loop-time ->y ;

"ease-in" rect
  -1.5 0 ->xy  1.1 ->square  0xffd23c solid ->fill
  '( 0 -3  2 3  4 -3 ) ease-in 4 loop-time ->y ;

"ease-out" rect
  1.5 0 ->xy  1.1 ->square  0x38e1b0 solid ->fill
  '( 0 -3  2 3  4 -3 ) ease-out 4 loop-time ->y ;

"ease-inout" rect
  4.5 0 ->xy  1.1 ->square  0x3c8cff solid ->fill
  '( 0 -3  2 3  4 -3 ) ease-in-out 4 loop-time ->y ;
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
