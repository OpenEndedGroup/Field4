import afield.media.vision.*

prop<OpticalFlowDemo>(lab)
val runScope = requireNotNull(RunScope.current()) { "Run this example in a Field box." }
// AUTO: the VK_NV_optical_flow engine where the device has one, portable pyramidal Lucas–Kanade everywhere else.
val ownedLab = runScope.own(OpticalFlowDemo(renderer, backend = OpticalFlowBackend.AUTO))
lab = ownedLab
println("Optical flow backend: ${ownedLab.flow.backend}")
var gain = 1f
var damping = .98f
// The two backends report cost on different scales, so the rejection threshold starts at the demo's per-backend
// default.
var cost = ownedLab.costThreshold
var deform = true
var reset = false
controlGroup("optical flow") {
    slider("Advection gain", min = 0.0, max = 3.0, initialValue = 1.0) { gain = it.toFloat() }
    slider("Feedback retention", min = 0.0, max = .995, initialValue = .98) { damping = it.toFloat() }
    slider(
        "Reject cost above",
        min = 0.0,
        max = 6.0 * ownedLab.costDisplayRange,
        initialValue = ownedLab.costThreshold.toDouble(),
    ) {
        cost = it.toFloat()
    }
    checkbox("Consecutive-frame wiggle", initialValue = true) {
        deform = it
        reset = true
    }
    button("Reset feedback and motion hints") { if (it) reset = true }
}
displayOpen("optical flow")
println(
    "Top: consecutive frames. Below: motion/cost and advected feedback. Turn off wiggle for the (+12, -8) translation check.",
)
frames {
    var time = 0f
    while (true) {
        // Pixel displacement for the translation check; wiggle supplies its own motion.
        ownedLab.dx = if (deform) 0f else 12f
        ownedLab.dy = if (deform) 0f else -8f
        ownedLab.displayRange = if (deform) 3f else 20f
        ownedLab.gain = gain
        ownedLab.damping = damping
        ownedLab.costThreshold = cost
        ownedLab.deformation = deform
        ownedLab.update(time, reset)
        reset = false
        time += ownedLab.pairStep
        frame()
    }
}
    .end { ownedLab.close() }
