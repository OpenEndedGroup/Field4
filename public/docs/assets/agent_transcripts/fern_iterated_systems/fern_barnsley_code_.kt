/*
The Barnsley fern. Four affine maps, each picked at random with its own weight: every
particle plays the "chaos game" — pick a map, apply it, plot the point — and the points
pile up on the one shape the four maps together send onto itself. The rule is in this
box's compute tab; edit it and the shader reloads.

Low fade keeps the whole leaf; low fps shows it building up at random.
*/
var module = computeModuleHere {}

val fern = IteratedSystem(module) {
    viewport(-3.1, 3.1, 0.0, 10.4)
    background = hexColor("#010301")
    particles = 200_000
    steps = 1
    fade = 0.06
    fps = 24.0
    exposure = 0.25
}
showControls(fern)
