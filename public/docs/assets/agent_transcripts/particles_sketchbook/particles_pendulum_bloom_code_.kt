/*
Pendulum bloom: home tethers and neighbour springs turn the painted field into a
hanging fabric. Every particle remembers where it was painted (p.home); springs join
particles that were painted close together, at the distance they were painted apart.
Paint loose curtains or bunches.
*/
var module = computeModuleHere {}

val sketch = ParticleSketch(module) {
    background = hexColor("#081016")
    gravity = vec(0, 42)
    particleRadius = 3.0
    damping = 0.993
    maxSpeed = 120.0
    pairRadius = 58.0
    trail = 0.2
}
showControls(sketch)

// curtains hanging from the top; drag in the window for more
if (sketch.isEmpty) {
    for (k in 0 until 9) {
        val x = 230.0 + k * 80
        sketch.paint((0..16).map { i -> vec(x + sin(i * 0.4 + k) * 14, 110 + i * 26) })
    }
}
