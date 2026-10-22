/*
Hawks: two hawks chase the nearest starling and eat it on contact, then rest a while. Starlings
flee anything hawk-shaped within 90 pixels; the flock splits and heals around each pass. Rules
see other kinds by name: flee("hawk", ...), chase("starling", ...).
*/
var module = computeModuleHere {}

val sim = BoidSketch(module) {
    background = hexColor("#07070a")
    trail = 12
    kind("starling") {
        count = 700
        maxSpeed = 3.0; minSpeed = 1.4; maxForce = 0.08; fov = 300.0
        rules(
            separate(11, 1.8),
            align(30, 1.0),
            cohere(42, 0.9),
            flee("hawk", 90, 4.5),
            wander(0.1),
        )
        shape = BoidShape.ARROW; size = 2.8
        trailAlpha = 0.35
    }
    kind("hawk") {
        count = 2
        maxSpeed = 3.7; minSpeed = 2.2; maxForce = 0.05
        rules(
            chase("starling", 260, 1.3),
            eat("starling", 7, 90), // reach 7 px, then rest 90 steps
            separate(90, 1.5, among = "same"),
            wander(0.5),
        )
        shape = BoidShape.ARROW; size = 7.0
        trail = 3.0; trailAlpha = 0.8; trailWidth = 1.5
    }
}
showControls(sim)
