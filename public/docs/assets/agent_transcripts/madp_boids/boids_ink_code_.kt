/*
Ink: boids ride a slowly changing noise field and barely flock at all. No bodies are drawn and
fade is near zero, so the window keeps every stroke; reset in the controls starts a new page.
The field is flowAngle() in the compute tab, a function of position and time.
*/
var module = computeModuleHere {}

val sim = BoidSketch(module) {
    background = hexColor("#efe9dc")
    trail = 6
    fade = 0.01
    kind("ink") {
        count = 900
        maxSpeed = 1.8; minSpeed = 0.8; maxForce = 0.09
        rules(
            flow(1.0),
            separate(8, 0.9),
            align(18, 0.25),
        )
        shape = BoidShape.NONE
        trailAlpha = 0.8; trailWidth = 0.8
    }
}
showControls(sim)
