/*
Sine garden: soft orbits, neighbour clumping and sinusoidal forcing layered together.
Sinusoidal motion needs no special case; oscillate() is just another force. No pairs
here, so pairRadius stays 0 and eachPair never runs.
*/
var module = computeModuleHere {}

val sketch = ParticleSketch(module) {
    background = hexColor("#021017")
    particleRadius = 2.6
    damping = 0.994
    maxSpeed = 120.0
    trail = 0.3
}
showControls(sketch)

// a ring and two diagonals; drag in the window for more
if (sketch.isEmpty) {
    sketch.paint(
        (0..60).map { i ->
            val a = i / 60.0 * 2 * PI
            vec(550 + cos(a) * 160, 380 + sin(a) * 160)
        },
    )
    sketch.paint(250.0, 150.0, 850.0, 610.0)
    sketch.paint(850.0, 150.0, 250.0, 610.0)
}
