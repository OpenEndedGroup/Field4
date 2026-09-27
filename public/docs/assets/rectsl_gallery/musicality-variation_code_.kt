// RectSL example: musicality-variation
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
->wall 18 10 abstract
->cps 1
->duration-cycles 8
->fps 30

# Pattern-level variation: each row is the same three-hit figure, varied differently.
# `every` fires on cycles 0, 3, 6…; `iter` walks the bar one quarter further each cycle;
# `off` superimposes a shifted echo; `sometimes-by` transforms a deterministic half.
"plain" rect  0.7 ->square  0xff5a3c solid ->fill
  "bd*3" '( f0 14 * 7 -  4.2  ->xy ) pat ;

"faster" rect  0.7 ->square  0xffd23c solid ->fill
  "bd*3" mini 3 '( 2 fast ) every '( f0 14 * 7 -  2.4  ->xy ) pat ;

"walking" rect  0.7 ->square  0xa6e22e solid ->fill
  "bd sd hh cp" mini 4 iter '( f0 14 * 7 -  0.6  ->xy ) pat ;

"echoed" rect  0.7 ->square  0x38e1b0 solid ->fill
  "bd*3" mini 1 8 '( 2 ply ) off '( f0 14 * 7 -  -1.2  ->xy ) pat ;

"swung" rect  0.7 ->square  0x3c8cff solid ->fill
  "bd*8" mini 4 swing '( f0 14 * 7 -  -3.0  ->xy ) pat ;

"sometimes" rect  0.7 ->square  0xc678dd solid ->fill
  "bd*4" mini 0.5 '( press ) sometimes-by '( f0 14 * 7 -  -4.6  ->xy ) pat ;
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
