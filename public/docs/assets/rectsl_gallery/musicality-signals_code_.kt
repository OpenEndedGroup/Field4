// RectSL example: musicality-signals
//
// The `#rectsl` program below is compiled to a scene; each frame is sampled,
// lowered to a triangle mesh, and drawn through the shared rect vertex shader
// plus a StackSL-compiled material fragment. Edit `rectsl` and re-run to change
// it live. See the "RectSL examples" docs page for the language.

import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
#rectsl
->wall 18 10 abstract
->tempo 90
->duration-cycles 4
->fps 30

# Smooth: sine straight into a rotation track, and into x through the scalar operators.
"smooth" rect  1.0 ->square  0xff5a3c solid ->fill
  sine 12 * 6 -  ->x   3.4 ->y   sine tau * ->rot ;

# Stepped: the same sine, sampled eight times a cycle. It holds, then snaps.
"stepped" rect  1.0 ->square  0xffd23c solid ->fill
  sine 8 segment  12 *  6 -  ->x   1.6 ->y ;

# A numeric rhythm read directly as a track, and a rest held rather than zeroed.
"steps" rect  0.9 ->square  0xa6e22e solid ->fill
  "0 3 -3 6" ->x   -0.2 ->y ;
"held" rect  0.9 ->square  0x38e1b0 solid ->fill
  "-6 ~ 6" mini nums hold-pat ->x   -2.0 ->y ;

# Remapped, quantised, bipolar, and seeded noise — all the same kind of value.
"remapped" rect  0.8 ->square  0x3c8cff solid ->fill
  tri -5 5 pat-range ->x   -3.6 ->y ;
"quantised" rect  0.8 ->square  0xc678dd solid ->fill
  saw 0.25 quantise 16 * 8 -  ->x   -4.6 ->y ;
"noisy" rect  0.8 ->square  0x56b6c2 solid ->fill
  7 perlin bipolar 6 *  ->x   4.4 ->y ;

# Selection variation: a mirrored twin, a delayed echo, a swung stagger, a dropout.
"twin" rect  0.7 ->square  0xe06c75 solid ->fill
  5 0 ->xy  2.6 ->y
  selected '( 0.45 ->alpha ) ->jux
;
"echo" rect  0.6 ->square  0xd19a66 solid ->fill
  '( 0=-8  4=8 ) linear ->x   0.4 ->y
  selected 0.5 '( 0.4 ->alpha ) ->off
;
"row" rect  0.5 ->square  0x98c379 solid ->fill
  -7 -5.4 ->xy
  8 '( ic 1.6 * 0 ->offset ) array
  0.12 0.6 ->stagger-swing
  0.25 ->degrade
;
"""

val program = RectAnimationOracle.compile(rectsl).program!!
val shader  = rectMaterialShaderCode(program)

val module = moduleHere {
    vertex = RECT_ANIMATION_VERTEX_SHADER
    fragment = shader.exampleFragmentShader
    blend = true
    depthTest = false
    primitiveType = triangles
}
val m = Mesh()
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
