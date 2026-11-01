/*
RectSL example: cheat-strip

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
->duration-cycles 2

# the same notation, spent on *where* instead of on *when*
"tick" rect  0.34 ->square
  "bd [sd sd] hh*4" '( -3.9 ->y  0.55 ->alpha ) strip
;

# sixteen slots laid out in space; the figure decides which stay lit
"slot" rect  0.55 1.8 ->wh
  16 '( f 15 *  7.5 -  2.8 ->xy ) array
  16 0 ->kolakoski        # or `5 16 0 ->euclid`
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
