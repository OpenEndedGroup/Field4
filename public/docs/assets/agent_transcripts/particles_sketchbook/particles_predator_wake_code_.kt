/*
Predator wake: an invisible hunter cuts through the flock and the school shears around
it. The hunter is a shared agent: no particle owns it, every particle's update() asks
where it is, and draw() shows it. Paint a few separated groups and let it stir them up.
*/
var module = computeModuleHere {}

val sketch = ParticleSketch(module) {
    background = hexColor("#061009")
    particleRadius = 2.4
    damping = 0.9985
    maxSpeed = 210.0
    pairRadius = 28.0
    trail = 0.18
}
showControls(sketch)

// small spirals scattered across the window; drag in the window for more
if (sketch.isEmpty) {
    val random = kotlin.random.Random(7)
    repeat(40) {
        val x = 60 + random.nextDouble() * 980
        val y = 60 + random.nextDouble() * 640
        sketch.paint((0..12).map { i -> vec(x + cos(i.toDouble()) * 30 * i / 12, y + sin(i.toDouble()) * 30 * i / 12) })
    }
}
