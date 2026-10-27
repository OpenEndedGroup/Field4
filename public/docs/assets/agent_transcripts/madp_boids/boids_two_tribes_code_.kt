/*
Two tribes: everyone separates from everyone, but alignment and cohesion only count your own
colour, and the other colour is mildly repellent. Mixed flocks sort themselves out within a few
hundred steps. align and cohere see only their own kind unless told otherwise.
*/
var module = computeModuleHere {}

val sim = BoidSketch(module) {
    background = hexColor("#0a0808")
    steps = 2
    trail = 16
    kind("red") {
        count = 450
        maxSpeed = 2.8
        minSpeed = 1.2
        maxForce = 0.07
        fov = 300.0
        rules(
            separate(12, 1.6, among = "all"),
            align(32, 1.0), // align and cohere default to "same"
            cohere(44, 0.9),
            flee("blue", 36, 1.1),
            wander(0.1),
        )
        shape = BoidShape.ARROW
        size = 3.0
        trailAlpha = 0.4
    }
    kind("blue") {
        count = 450
        maxSpeed = 2.8
        minSpeed = 1.2
        maxForce = 0.07
        fov = 300.0
        rules(
            separate(12, 1.6, among = "all"),
            align(32, 1.0),
            cohere(44, 0.9),
            flee("red", 36, 1.1),
            wander(0.1),
        )
        shape = BoidShape.ARROW
        size = 3.0
        trailAlpha = 0.4
    }
}
showControls(sim)
