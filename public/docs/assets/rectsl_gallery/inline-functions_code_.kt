/*
RectSL example: inline-functions

This program uses a `{{ ... }}` Kotlin escape, so it is compiled with a
*dedicated* Kotlin engine rather than this box's own engine — the box engine is
busy running this code and a nested compile on it would re-enter and deadlock.
See the "RectSL examples" docs page for the escape mechanism.
*/

import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.compileRectSL
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 22 8 meters
->duration 3
->fps 30

"bar" rect
  0 0 ->xy
  0.42 1 ->wh
  0x38e1b0 solid ->fill
  32 '(
    ic 0.66 * 0 ->offset
    '( 0 0.15  0.5 1.15  1 0.15 ) 1 loop-time
    {{ (gaussian(0.5, 0.2) + fbm(9) * 0.4).eval(f) }}  5.5 *  0.25 +  value*
    ->h
  ) array
  0.05 ->stagger-time
;
"""

val engine = afield.runtime.kotlin.KotlinInterface2(filePrefix = "INLINE_FN_VIEW_")
// The dedicated compiler belongs to this run, including stop and rerun cleanup.
RunScope.current()?.own { engine.dispose() }
val program = compileRectSL(rectsl, engine = engine).program
val shader = rectMaterialShaderCode(program)
check(shader.ok) { shader.diagnostics.joinToString("\n") }

/*
Built directly, not with `moduleHere { }`: that takes its shaders from this box's
`vertex`/`fragment` properties and ignores the ones assigned in its block, and this
fragment shader is regenerated from the program's materials on every run.
*/
val module =
    Module(
            parameters(renderer) {
                name = "rectsl"
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shader.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
            },
        )
        .pointer()
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
