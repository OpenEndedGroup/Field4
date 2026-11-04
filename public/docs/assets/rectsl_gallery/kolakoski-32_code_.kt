/*
RectSL example: kolakoski-32

The `#rectsl` program below is compiled to a scene; each frame is sampled,
lowered to a triangle mesh, and drawn through the shared rect vertex shader
plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
it live. See the "RectSL examples" docs page for the language.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 16 9 abstract
->tempo 120
->beats-per-cycle 4
->duration-cycles 4
->fps 30
->background 0x0a0a12

# The figure in space: thirty-two slots; the first sixteen light as before.
"slot" rect
  0.27 1.8 ->wh
  0x7cf5d0 solid ->fill
  32 '( f 15 *  7.5 -  2.8 ->xy ) array
  32 0 ->kolakoski
;

# The same figure in time: one copy per onset, each alive for its own step.
"run" rect
  0.4 ->square
  0xe0af68 solid ->fill
  32 0 kolakoski '( f 15 *  7.5 -  0 ->xy ) pat
;

# The gap reading of the same word, a step later so the two rows can be compared.
"gap" rect
  0.4 ->square
  0xff5a3c solid ->fill
  32 1 kolakoski-gaps '( f 15 *  7.5 -  -2.8 ->xy ) pat
;
"""

val program = RectAnimationOracle.compile(rectsl).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module = Module(
    parameters(renderer) {
        name = "rectsl"
        vertex = RECT_ANIMATION_VERTEX_SHADER
        fragment = shader.exampleFragmentShader
        blend = true
        depthTest = false
        primitiveType = triangles
    },
).pointer()
val m = Mesh(renderer, module)
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
}
