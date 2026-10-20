/*
Vortex brush: a drifting vortex field that tightens into rings and loosens back out.
Move the pointer over the renderer window and the vortex centre follows it; mouse and
mouseInside are the pointer, in the same pixels as everything else.
*/
var module = computeModuleHere {}

val sketch = ParticleSketch(module) {
    background = hexColor("#050613")
    particleRadius = 2.7
    damping = 0.996
    maxSpeed = 190.0
    trail = 0.24
}
showControls(sketch)

// four arcs around the middle; drag in the window for more
if (sketch.isEmpty) {
    for (k in 0 until 4) {
        sketch.paint((0..30).map { i ->
            val a = k * PI / 2 + i / 30.0 * 1.3
            vec(550 + cos(a) * (90 + k * 40), 380 + sin(a) * (90 + k * 40))
        })
    }
}
