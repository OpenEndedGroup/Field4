// RectSL data-visualization demo.
//
// The #rectsl program draws a viridis colour-bar legend (array + colormap)
// and a bar chart bound to a data list (data / normalize / bars), animated in
// with a staggered eased `->alpha`. Edit `rectsl` and re-run to change it live.
// See rectsl_cheatsheet.md ("Data visualization") / rectsl_examples.md.
import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
#rectsl
->wall 20 10 meters
->duration 6
->fps 30

# colour legend across the top: 40 swatches sweeping viridis
"leg" rect  0.5 0.8 ->wh
  40 '( fc 9.6 *  4.3 ->offset  "viridis" f colormap solid ->fill ) array
;

# bar chart from data, viridis by value, staggered grow-in
'( 4 7 2 9 5 8 3 6 1 6 5 9 4 7 3 8 ) data  normalize  "viridis" bars
  '( '( 0=0 0.7=1 ) ease-out ->alpha ) each
  0.05 ->stagger-time
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
renderer += "rectsl-dataviz" to module

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
