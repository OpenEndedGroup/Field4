// RectSL playground.
//
// Edit the `rectsl` program below and re-run this box to change it live.
// The transport controls in this box's output area drive the clock:
//   play  — advance time every frame and loop the animation
//   loop  — wrap at the end (off = run once and hold on the last frame)
//   speed — playback rate multiplier
//   time  — scrub to a frame (while paused); moving it freezes the clock there
//
// See the "RectSL examples" docs page / rectsl_cheatsheet.md for the language.
import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
#rectsl
wall 16 9 meters
duration 6
fps 30

material spectrum '(
  uv 3.0 * time 0.5 * + fbm palette
)

"bar" rect
  0 0 xy
  0.9 1 wh
  '( 0 0.6  0.75 6.5  1.5 0.6 ) 1.5 loop-time h
  0xffffff solid fill
  spectrum material
;

16 '(
  "bar" select clone
    i 7.5 - 1.2 * 0 xy
    i 0.14 * delay-all-time
) repeat
"""

val program = RectAnimationOracle.compile(rectsl).program!!
val shader  = rectMaterialShaderCode(program)

// Seed this box's shader-source properties before `moduleHere {}` runs.
// moduleHere builds its module from the box's `vertex`/`fragment` properties
// (not from assignments in the lambda); `once.vertex`/`once.fragment` set them
// once. The `//%-` fragment break is load-bearing: fragments run in order, each
// expanded only after the previous ran, so this fragment executes first and the
// moduleHere fragment then prepares its shaders from what was set here.
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
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = afield.stdlib.linalg.Matrix4f().ortho(-ww / 2f, ww / 2f, -wh / 2f, wh / 2f, -1f, 1f)
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
