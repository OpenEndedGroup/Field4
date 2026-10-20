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

// three pours across the top; drag in the window for more
if (sketch.isEmpty) {
    for (k in 0 until 3) {
        sketch.paint(220.0 + k * 330, 90.0, 380.0 + k * 330, 140.0)
    }
}
