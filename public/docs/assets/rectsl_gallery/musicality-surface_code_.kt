// RectSL example: musicality-surface
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
->wall 20 12 abstract
->tempo 120
->beats-per-cycle 4
->duration-cycles 4
->fps 30

# Clock conversions: seconds computed from musical units rather than typed.
"beat" rect  0.6 ->square  0xff5a3c solid ->fill
  1 beats 8 *  2 cycles 4 * ->xy ;
"cyc" rect  0.6 ->square  0xffd23c solid ->fill
  cycle-secs 4 *  scene-cycles 1 -  ->xy ;

# The per-event context: onset, length, cycle, step, atom, and named parameters.
"ctx" rect  0.5 ->square  0xa6e22e solid ->fill
  "bd:2 sd cp*2" '(
      t 2 *  9 -  ->x
      cyc 2 *  step 0.4 *  +  3 -  ->y
      "n" param 0.1 *  0.4 +  ->alpha
      "n" param? 0.2 *  0.5 +  ->square
      "bd:2" atom? 0.4 *  ->rotate
      dur 3 *  0.3 +  ->scale
      atom ->tag
  ) pat
;

# The remaining pattern transforms.
"rev" rect  0.4 ->square  0x38e1b0 solid ->fill
  "0 1 2" mini rev '( f 16 * 8 -  -1 ->xy ) pat ;
"pal" rect  0.4 ->square  0x3c8cff solid ->fill
  "bd sd" mini palindrome '( f 16 * 8 -  -2 ->xy ) pat ;
"back" rect  0.4 ->square  0xc678dd solid ->fill
  "bd sd hh cp" mini 4 iter-back '( f 16 * 8 -  -3 ->xy ) pat ;
"slowed" rect  0.4 ->square  0x56b6c2 solid ->fill
  "bd sd hh" mini 2 slow '( f 16 * 8 -  -4 ->xy ) pat ;
"lingered" rect  0.4 ->square  0xe06c75 solid ->fill
  "bd sd hh cp" mini 1 2 linger '( f 16 * 8 -  -5 ->xy ) pat ;
"rotated" rect  0.4 ->square  0xd19a66 solid ->fill
  "0 1 2 3" mini 1 pat-rot '( f 16 * 8 -  1 ->xy ) pat ;
"shuffled" rect  0.4 ->square  0x98c379 solid ->fill
  "bd sd hh cp" mini 4 shuffle '( f 16 * 8 -  2 ->xy ) pat ;
"thinned" rect  0.4 ->square  0x61afef solid ->fill
  "bd*8" mini 0.4 degrade-by '( f 16 * 8 -  3 ->xy ) pat ;
"swungby" rect  0.4 ->square  0xbe5046 solid ->fill
  "bd*8" mini 1 4 4 swing-by '( f 16 * 8 -  4 ->xy ) pat ;
"pressed" rect  0.4 ->square  0x7cf5d0 solid ->fill
  "bd*4" mini 1 3 press-by '( f 16 * 8 -  5 ->xy ) pat ;
"shifted" rect  0.4 ->square  0xffffff solid ->fill
  "bd*4" mini 1 8 pat-delay '( f 16 * 8 -  4.6 ->xy ) pat ;
"stuttered" rect  0.4 ->square  0xff9e64 solid ->fill
  "bd sd" mini 3 16 stut '( f 16 * 8 -  -5.6 ->xy ) pat ;
"over" rect  0.4 ->square  0x9ece6a solid ->fill
  "bd*2" mini '( 2 fast ) superimpose '( f 16 * 8 -  0 ->xy ) pat ;
"in" rect  0.4 ->square  0x7aa2f7 solid ->fill
  "bd sd hh cp" mini 2 '( rev ) inside '( f 16 * 8 -  -0.5 ->xy ) pat ;
"out" rect  0.4 ->square  0xbb9af7 solid ->fill
  "bd sd" mini 2 '( rev ) outside '( f 16 * 8 -  0.5 ->xy ) pat ;

# Building patterns from other patterns, and a Euclidean figure as events.
"together" rect  0.4 ->square  0xf7768e solid ->fill
  "bd*2" mini "sd*3" mini stack-pat '( f 16 * 8 -  -1.5 ->xy ) pat ;
"sequenced" rect  0.4 ->square  0x2ac3de solid ->fill
  "bd*2" mini "sd*4" mini cat-pat '( f 16 * 8 -  1.5 ->xy ) pat ;
"euc" rect  0.4 ->square  0xe0af68 solid ->fill
  3 8 0 euclid '( f 16 * 8 -  5.5 ->xy ) pat ;

# Signals not otherwise shown, and the alternate spelling of quantise.
"cos" rect  0.4 ->square  0x41a6b5 solid ->fill
  cosine 10 * 5 -  -6.4 ->xy ;
"isaw" rect  0.4 ->square  0x73daca solid ->fill
  isaw 10 * 5 -  -6.9 ->xy ;
"sq" rect  0.4 ->square  0xff007c solid ->fill
  square 8 * 4 -  6.4 ->xy ;
"rnd" rect  0.4 ->square  0xb4f9f8 solid ->fill
  3 rand 0.2 quantize 10 * 5 -  6.9 ->xy ;
"asText" rect  0.4 ->square  0xcfc9c2 solid ->fill
  "0 1" mini nums texts '( f 16 * 8 -  -6 ->xy ) pat ;
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
