// RectSL example: musicality-four-to-the-floor
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
->wall 16 9 abstract
->tempo 120
->beats-per-cycle 4
->duration-cycles 4
->fps 30
->background 0x0a0a12

# The accent: a full-wall flash, behind everything, on every fourth bar only.
"accent" rect
  16 9 ->wh
  0 0 ->xy
  0xffffff solid ->fill
  0 ->alpha
  ->back
  selected 4 '( 0.14 ->alpha ) ->every
;

# The ring: sixteen slots, five of them sounding, the whole figure turning once a bar.
"ring" group
  "hat" rect
    0.26 1.0 ->wh
    0x7cf5d0 solid ->fill
    16 '( f0 tau * ->rot  f0 tau * 3.2 ->polar ) array
    5 16 0 ->euclid
  ;
end
"ring" ->select
  saw tau * ->rotate
;

# The kick: one copy per event, each authored in its own local time.
"kick" rect
  1.3 ->square
  0xff5a3c solid ->fill
  "bd*4" '(
      step 1.5 - 4.2 *  ->x
      '( 0=5.5  0.34=0 ) ease-out ->y
      '( 0=0  0.10=1  0.5=0 ) linear ->alpha
  ) pat
;

# The score: the same kind of string, read as a ruler instead of as a rhythm.
"tick" rect
  0.34 ->square
  0x3c8cff solid ->fill
  "bd [sd sd] hh*4" '( -3.9 ->y  0.55 ->alpha ) strip
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
