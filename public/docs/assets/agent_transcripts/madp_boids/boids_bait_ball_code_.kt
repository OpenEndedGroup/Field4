/*
Bait ball: sardines circle a point in a tight mill. Three sharks cruise through; the ball blows
open where they pass and re-forms behind them. orbit() circles a point given as fractions of
the window, at a radius in pixels.
*/
var module = computeModuleHere {}

val sim = BoidSketch(module) {
    background = hexColor("#02101a")
    blend = BoidBlend.LIGHTER
    trail = 20
    kind("sardine") {
        count = 700
        maxSpeed = 3.2; minSpeed = 1.6; maxForce = 0.09; fov = 280.0
        rules(
            orbit(0.5, 0.5, 80, 1.0),
            separate(8, 1.6),
            align(22, 0.9),
            cohere(30, 0.5),
            flee("shark", 70, 5),
        )
        shape = BoidShape.LINE; size = 4.0
        trailAlpha = 0.25
    }
    kind("shark") {
        count = 3
        maxSpeed = 3.0; minSpeed = 2.0; maxForce = 0.035
        rules(
            orbit(0.5, 0.5, 130, 0.8), // a wide lap that clips the ball
            chase("sardine", 90, 0.7),
            separate(140, 1.0, among = "same"),
            wander(0.5),
        )
        shape = BoidShape.KITE; size = 10.0
        trail = 2.0; trailAlpha = 0.4; trailWidth = 2.0
    }
}
showControls(sim)
