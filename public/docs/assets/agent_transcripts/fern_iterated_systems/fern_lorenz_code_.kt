/*
The Lorenz attractor. Each particle starts near the others and is carried along the
Lorenz flow, a continuous rule, unlike the fern's jumps. The trajectories never settle
and never repeat, yet every one of them traces the same two-lobed shape. The 3D path is
projected to the screen in project(), and coloured by height.
*/
var module = computeModuleHere {}

val lorenz = IteratedSystem(module) {
    viewport(-33.0, 33.0, -6.0, 57.0)
    background = hexColor("#020305")
    particles = 12_000
    steps = 2
    dt = 0.006
    fade = 0.08
    fps = 30.0
    exposure = 0.3
}
showControls(lorenz)
