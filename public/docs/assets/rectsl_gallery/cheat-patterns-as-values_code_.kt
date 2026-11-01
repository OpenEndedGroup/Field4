/*
RectSL example: cheat-patterns-as-values

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
->tempo 90
->duration-cycles 4

"smooth"    rect  1 ->square   sine 12 * 6 -  ->x   3.4 ->y   sine tau * ->rot ;
"stepped"   rect  1 ->square   sine 8 segment  12 *  6 -  ->x   1.6 ->y ;
"steps"     rect  0.9 ->square  "0 3 -3 6" ->x   -0.2 ->y ;   # a string with numbers is mini-notation
"held"      rect  0.9 ->square  "-6 ~ 6" mini nums hold-pat ->x   -2 ->y ;
"remapped"  rect  0.8 ->square  tri -5 5 pat-range ->x   -3.6 ->y ;
"quantised" rect  0.8 ->square  saw 0.25 quantise 16 * 8 -  ->x   -4.6 ->y ;
"noisy"     rect  0.8 ->square  7 perlin bipolar 6 *  ->x   4.4 ->y ;
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
