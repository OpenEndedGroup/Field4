/*
Fireflies: two swarms each chase a point running a Lissajous curve. Weak alignment and a lot of
wander keep them loose; additive blending turns overlapping trails into light. The point is a
function of t, the step count, evaluated every frame.
*/
var module = computeModuleHere {}

val sim = BoidSketch(module) {
    background = hexColor("#040306")
    blend = BoidBlend.LIGHTER
    trail = 48
    kind("gold") {
        count = 220
        maxSpeed = 2.6
        minSpeed = 0.8
        maxForce = 0.05
        rules(
            seek(0.7) { t -> vec(0.5 + 0.32 * cos(t * 0.0082), 0.5 + 0.3 * sin(t * 0.0131)) },
            separate(10, 1.2),
            align(24, 0.25),
            wander(0.9, 0.5),
        )
        shape = BoidShape.DOT
        size = 1.4
        trailAlpha = 0.5
        trailWidth = 1.2
    }
    kind("green") {
        count = 220
        maxSpeed = 2.6
        minSpeed = 0.8
        maxForce = 0.05
        rules(
            seek(0.7) { t -> vec(0.5 + 0.3 * sin(t * 0.0107), 0.5 + 0.32 * cos(t * 0.0073)) },
            separate(10, 1.2),
            align(24, 0.25),
            wander(0.9, 0.5),
        )
        shape = BoidShape.DOT
        size = 1.4
        trailAlpha = 0.5
        trailWidth = 1.2
    }
}
showControls(sim)
