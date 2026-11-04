/*
RectSL example: cheat-pat

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
->duration 4
->fps 30
->tempo 120
->duration-cycles 4

# one template, sixteen strikes: four a bar, each alive for its own quarter
"kick" rect  1.3 ->square  0xff5a3c solid ->fill
  "bd*4" '(
      step 1.5 -  4.2 * ->x
      '( 0=5.5  0.34=0 ) ease-out ->y
      '( 0=0  0.10=1  0.5=0 ) linear ->alpha
  ) pat
;

# the per-event context: which atom, how long, which cycle, what it carries
"ctx" rect  0.5 ->square
  "bd:2 sd cp*2" '(
      t 2 * 9 - ->x
      cyc 2 *  step 0.4 *  +  3 - ->y
      "n" param 0.1 *  0.4 + ->alpha
      "bd:2" atom? 0.4 * ->rotate
      dur 3 *  0.3 + ->scale
      atom ->tag
  ) pat
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
