/*
A RectSL playground under your renderer. Edit the `rectsl` program below and run this box again
to change it live; the transport controls in its output drive the clock (play, loop, speed, and
time, which scrubs while paused). The language is on the "RectSL examples" docs page.
*/
import afield.lang.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.lang.rectsl.RectAnimationOracle
import afield.lang.rectsl.rectMaterialShaderCode
import afield.lang.rectsl.worldToClip
import afield.lang.rectsl.writeFrameToMesh

val rectsl =
    """
#rectsl
->wall 16 9 meters
->duration 6
->fps 30

material spectrum '(
  uv 3.0 * time 0.5 * + fbm palette
)

"bar" rect
  0 0 ->xy
  0.9 1 ->wh
  '( 0 0.6  0.75 6.5  1.5 0.6 ) 1.5 loop-time ->h
  0xffffff solid ->fill
  spectrum ->material
;

16 '(
  "bar" ->select clone
    i 7.5 - 1.2 * 0 ->xy
    i 0.14 * ->delay-all-time
) repeat
"""

val program = RectAnimationOracle.compile(rectsl).program!!
val shader = rectMaterialShaderCode(program)

/*
Set this box's shader sources before `moduleHere {}` runs.
Refresh the generated fragment on every run so material edits become visible.
moduleHere builds its module from the box's `vertex`/`fragment` properties
(not from assignments in the lambda). The constant vertex shader is seeded once;
the generated fragment changes with the RectSL program. Fragments separated by
`//%-` run one at a time, each expanded only after the
previous one ran, so this fragment executes first and the moduleHere fragment then
prepares its shaders from what was set here.
*/
once.vertex { RECT_ANIMATION_VERTEX_SHADER }
fragment = shader.exampleFragmentShader!!

//%-

val module = moduleHere {
    blend = true
    depthTest = false
    primitiveType = triangles
}
val m = Mesh()
module += "scene" to m
// Keyed by this box's id, so a second copy does not replace the first.
renderer += "rectsl_" + id.take(8) to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = program.wall.worldToClip()
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

// --- transport state, driven by the controls below ---
val duration = program.duration.toDouble()
var playing = true
var looping = true
var speed = 1.0
var t = 0.0

controlGroup("transport") {
    checkbox("play", initialValue = playing) { playing = it }
    checkbox("loop", initialValue = looping) { looping = it }
    slider("speed", min = 0.0, max = 3.0, step = 0.05, initialValue = speed) { speed = it }
    slider("time", min = 0.0, max = duration, step = 0.01, initialValue = 0.0) {
        // Scrubbing the timeline while paused sets the clock; while playing it is ignored.
        if (!playing) t = it
    }
}

loop {
    if (playing) {
        t += (1.0 / 60.0) * speed
        if (t > duration) t = if (looping) t - duration else duration
    }
    val sampleTime = if (looping) t % duration else t.coerceIn(0.0, duration)
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(sampleTime, m)
    true
}
