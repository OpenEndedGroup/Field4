/*
Conga: no alignment, no cohesion. One hand-written rule: find the closest boid ahead of you and
aim just behind it. Chains form, merge and chase their own tails. The rule's body is the three
custom hooks in the compute tab; custom(46, 1.6) says how far it looks and how much it counts.
*/
var module = computeModuleHere {}

val sim = BoidSketch(module) {
    background = hexColor("#0b0710")
    steps = 2
    trail = 24
    kind("dancer") {
        count = 360
        maxSpeed = 2.6; minSpeed = 1.2; maxForce = 0.08; fov = 200.0
        rules(
            custom(46, 1.6),
            separate(7, 2.0),
            wander(0.35),
        )
        shape = BoidShape.ARROW; size = 3.0
        trailAlpha = 0.5
    }
}
showControls(sim)
