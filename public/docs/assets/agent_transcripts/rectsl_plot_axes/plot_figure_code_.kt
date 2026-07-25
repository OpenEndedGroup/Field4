// RectSL plotting-layer demo.
//
// The #rectsl program sets ONE shared domain (`->domain` + `->frame`), draws
// `axes` (rules, nice-number ticks, numeric rect-numeral labels, gridlines),
// then overlays three series in that domain (`area`, `line`, `points`) and
// finishes with a `colorbar` and a discrete swatch `legend`. Edit `rectsl`
// and re-run to change it live. See rectsl_cheatsheet.md ("Plotting layer")
// / rectsl_examples.md ("A plotted figure").
import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
#rectsl
->wall 16 10 abstract
->duration 3
->fps 30

# shared coordinate frame: explicit ->domain + auto ->margins, then axes underneath
0 10  0 1  ->domain
->frame
axes

# three series overlaid in the ONE shared domain
0 10 40 linspace  dup '( 0.7 * sin 1 + 2 / ) data-map   "viridis" area
0 10 40 linspace  dup '( 0.55 * cos 1 + 2 / ) data-map  "plasma"  line
0 10 12 linspace  dup '( 0.9 * sin 1 + 2 / ) data-map   "turbo"   points

# a colour-bar legend (numeric labels) and a discrete swatch key
"viridis" colorbar
5 "turbo" legend
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
renderer += "rectsl-plot-axes" to module

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
