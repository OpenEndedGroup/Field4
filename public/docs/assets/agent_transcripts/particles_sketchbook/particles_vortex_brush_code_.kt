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

// eight arcs around the middle; drag in the window for more
if (sketch.isEmpty) {
    for (k in 0 until 8) {
        sketch.paint(
            (0..40).map { i ->
                val a = k * PI / 4 + i / 40.0 * 2.2
                vec(550 + cos(a) * (70 + k * 30), 380 + sin(a) * (70 + k * 30))
            },
        )
    }
}
