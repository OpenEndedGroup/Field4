/*
Spring mesh: nearby particles knit themselves into a spring web and local clumps.

Springs live in eachPair because a spring is about two particles. The GPU sees every
pair twice, once from each side, and spring() applies only p's half, so the two halves
add up to the whole spring. link() draws the thread once, from one side.
*/
var module = computeModuleHere {}

val sketch = ParticleSketch(module) {
    background = hexColor("#041015")
    particleRadius = 2.8
    damping = 0.992
    maxSpeed = 110.0
    pairRadius = 84.0
    trail = 0.18
}
showControls(sketch)

// a woven block of strokes; drag in the window for more
if (sketch.isEmpty) {
    for (k in 0 until 12) {
        sketch.paint((0..20).map { i -> vec(250 + 600 * i / 20.0, 170 + 36 * k + sin(i * 0.5 + k) * 20) })
    }
}
