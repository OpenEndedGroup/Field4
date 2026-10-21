/*
Bounce house: gravity, wall bounces and soft ball collisions. Drag to pour in more mass.

Gravity is a world setting, added to every particle before anything else. Collisions
are pairwise, in eachPair; the walls are each particle's own business, in update().
*/
var module = computeModuleHere {}

val sketch = ParticleSketch(module) {
    background = hexColor("#09060a")
    gravity = vec(0, 420)
    damping = 0.999
    particleRadius = 5.0
    pairRadius = 22.0 // the two largest balls touch at 20
    trail = 0.08
}
showControls(sketch)

// six pours across the top; drag in the window for more
if (sketch.isEmpty) {
    for (k in 0 until 6) {
        sketch.paint(120.0 + k * 160, 80.0 + (k % 2) * 60, 260.0 + k * 160, 200.0 - (k % 2) * 60)
        sketch.paint(120.0 + k * 160, 260.0, 260.0 + k * 160, 300.0)
    }
}
