/*
Particles: paint them into the renderer window by dragging, and four hooks in this box's
compute tab decide what they do. Each hook runs on the GPU, once for every particle:

  spawn(at)              a particle is painted at `at`: set up p
  eachPair(other, pair)  p and each neighbour within pairRadius: springs, collisions, links
  update()               p's own behaviour: forces, steering, colour
  draw()                 overlays, once a frame

Every frame runs in the same order: gravity, the neighbour grid, eachPair, update, then
vel += acc * dt, damping, the speed limit and pos += vel * dt. The other particles are
read as they were at the top of the frame; only p changes.

This one is a cruising school: flocking, a noise flow and a speed floor keep it alive.
The world settings below are the demo's init().
*/
var module = computeModuleHere {}

val sketch = ParticleSketch(module) {
    background = hexColor("#03060a")
    particleColor = rgba(185, 255, 232, 0.92)
    particleRadius = 2.5
    damping = 0.998
    maxSpeed = 190.0
    pairRadius = 30.0 // eachPair reaches this far
    trail = 0.22
}
showControls(sketch)

// The world starts empty; these loops are there so it isn't. Drag in the window for more.
if (sketch.isEmpty) {
    for (k in 0 until 5) {
        sketch.paint((0..40).map { i ->
            val a = i / 40.0 * 2 * PI
            vec(170 + k * 190 + cos(a) * 55, 380 + sin(a) * 150)
        })
    }
}
