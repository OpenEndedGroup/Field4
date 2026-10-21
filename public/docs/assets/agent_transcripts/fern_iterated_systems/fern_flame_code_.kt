/*
A flame-style IFS. The same weighted chaos game as the fern, but each affine map is
followed by a nonlinear variation (swirl, handkerchief, spherical), which bends the
straight-edged copies into curls. That is the step from an IFS to a fractal flame.
Flames are shown as log density, so the tone map is LOG: sparse threads and dense
knots stay visible together.
*/
var module = computeModuleHere {}

val flame = IteratedSystem(module) {
    viewport(-1.95, 1.95, -0.3, 2.9)
    background = hexColor("#040103")
    particles = 250_000
    steps = 2
    fade = 0.04
    fps = 24.0
    toneMap = PointToneMap.LOG
    exposure = 1.0
}
showControls(flame)
