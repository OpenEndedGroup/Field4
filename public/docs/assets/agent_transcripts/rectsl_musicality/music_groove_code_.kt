// RectSL's musical layer: the pattern language as scene structure.
//
// Two facts do all the work. A RectSL track is `sample(t)` and a pattern is
// `events(arc)` — both pure functions of time — so (1) a pattern IS a track,
// which is why `saw ->rotate` needs no conversion word, and (2) a pattern
// event carries an onset and a real duration, which is exactly what
// `->live birth life` wants, so an event IS a life window. `pat` is `array`
// indexed by rhythm instead of by ordinal.
//
// Nothing below is written in seconds. Edit `rectsl` and re-run to change the
// groove live. See rectsl_cheatsheet.md ("Musicality") / rectsl_examples.md.
import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
#rectsl
->wall 16 9 abstract
->tempo 120
->beats-per-cycle 4
->duration-cycles 4
->fps 30
->background 0x0a0a12

# The accent: a full-wall flash, behind everything, on every fourth bar only.
# This is the thing a loop cannot do and a groove must — `->every` keeps both
# versions of every track and chooses between them per sample.
"accent" rect
  16 9 ->wh
  0 0 ->xy
  0xffffff solid ->fill
  0 ->alpha
  ->back
  selected 4 '( 0.14 ->alpha ) ->every
;

# The ring: sixteen slots in space, five of them sounding, the whole figure
# turning once a bar because `saw` is a pattern read as a rotation track.
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

# The kick: one copy per event, each ->live for exactly its own quarter and
# authored in ITS OWN local time — so sixteen strikes, not a synchronised
# chorus. `step` places each one across the wall, restarting at every bar line.
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
# `pat` spends the subdivision on WHEN, `strip` spends it on WHERE.
"tick" rect
  0.34 ->square
  0x3c8cff solid ->fill
  "bd [sd sd] hh*4" '( -3.9 ->y  0.55 ->alpha ) strip
;
"""

val program = RectAnimationOracle.compile(rectsl).program!!
val shader  = rectMaterialShaderCode(program)

// Seed this box's shader-source properties before `moduleHere {}` runs; the
// `//%-` fragment break makes this fragment execute first (see the playground
// transcript for the why).
once.vertex { RECT_ANIMATION_VERTEX_SHADER }
once.fragment { shader.exampleFragmentShader!! }

//%-

val module = moduleHere {
    blend = true
    depthTest = false
    primitiveType = triangles
}
val m = Mesh()
module += "scene" to m
renderer += "rectsl-musicality" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = afield.stdlib.linalg.Matrix4f().ortho(-ww / 2f, ww / 2f, -wh / 2f, wh / 2f, -1f, 1f)
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

val duration = program.duration.toDouble()
var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % duration, m)
    t += 1.0 / 60.0
    true
}
