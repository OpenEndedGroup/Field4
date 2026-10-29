/*
Murmuration: the three original rules. Keep your distance, match your neighbours' heading,
drift toward their centre. Colour is heading, so turning waves show as colour waves. The pointer
is a hawk: move it over the window and the flock parts around it.

The flock is this box's code: kinds of boid, each a count, speed limits, a field of view and a
list of rules. A rule is a Reynolds steering force times a weight, and a boid's acceleration is
their sum. How the boids look is the compute tab's color().
*/
var module = computeModuleHere {}

val sim = BoidSketch(module) {
    background = hexColor("#06080b")
    edges = BoidEdges.WRAP
    trail = 14
    fade = 1.0
    kind("starling") {
        count = 800
        maxSpeed = 3.2
        minSpeed = 1.6
        maxForce = 0.07
        fov = 280.0 // degrees; boids can't see directly behind
        rules(
            separate(12, 1.7),
            align(32, 1.1),
            cohere(44, 0.95),
            wander(0.15),
            mouse(-3, 110), // negative weight: flee the pointer
        )
        shape = BoidShape.ARROW
        size = 3.2
        trailAlpha = 0.45
    }
}
showControls(sim)
