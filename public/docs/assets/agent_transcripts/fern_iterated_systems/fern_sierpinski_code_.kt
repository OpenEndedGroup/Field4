/*
The smallest chaos game: three corners, and each step moves the particle halfway
towards one of them, picked at random. Nothing in the rule mentions holes, but the
points only ever land on the Sierpinski triangle. Colour shows which corner the last
step went towards, so each third of the triangle is a half-size copy of the whole.
*/
var module = computeModuleHere {}

val sierpinski = IteratedSystem(module) {
    viewport(-1.05, 1.05, -0.95, 1.05)
    background = hexColor("#030204")
    particles = 150_000
    fade = 0.05
    fps = 24.0
    exposure = 0.5
}
showControls(sierpinski)
