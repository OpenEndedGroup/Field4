import afield.graphics.scene.fabricated.FabricateBuiltChain
import afield.graphics.scene.fabricated.FabricatePassChain
import afield.graphics.scene.fabricated.FabricatePassLibrary
import afield.graphics.scene.fabricated.addPassChain

prop<FabricateBuiltChain>(chain)

// Edit these declarations with Alt-Enter while the scene runs.
var historyBlend = 0.92f // History weight, from 0 (current frame only) to just below 1.
var motionGain = 20f // Debug display gain; large gains clip the red/green motion channels.

// Angles are radians, distances are world units, speeds are radians per simulation second.
var yawAmplitude = 0.10
var yawSpeed = 0.45
var dollyAmplitude = 1.7
var dollySpeed = 0.6
var bobAmplitude = 0.45
var bobSpeed = 1.7
var driftAmplitude = 0.18
var driftSpeed = 0.8

// Persistent pixels plus frame history let accumulation follow the moving scene.
// Resolution must match the bundle's 1024x1024 input. Per-pixel motion includes the balls.
chain = bundle.addPassChain(
    FabricatePassLibrary.temporalAccumulate(
        name = "taa",
        source = FabricatePassChain.CHAIN_INPUT,
        blend = historyBlend,
        resolution = 1024 to 1024,
        perPixelMotion = true,
    ) +
        FabricatePassLibrary.temporalDebug(
            name = "display",
            source = "taa",
            mode = 0,
            motionScale = motionGain,
            perPixelMotion = true,
        ),
    printReport = true,
)
chain["blend"] = { historyBlend }
chain["motionScale"] = { motionGain }
bundle.attachTo(renderer)
bundle.present(renderer = renderer)

// Reset one stable pose on launch. Translate eye and target together, then apply bounded
// world-Y yaw to the original direction; no camera rotation accumulates between frames/runs.
val camera = bundle.scene.camera
val initialEye = Vector3f(0f, 1.4f, -4.2f)
val initialTarget = Vector3f(0f, 0f, 0f)
val initialDirection = Vector3f(initialTarget).sub(initialEye)
val direction = Vector3f()
camera.at.set(initialEye)
camera.target.set(initialTarget)
camera.up.set(0f, 1f, 0f)
var time = 0.0
bundle.invalidateFrameHistory() // Relaunching is an intentional camera/object cut.

frames {
    while (true) {
        time += 1.0 / 60.0 // One fixed simulation step per Field frame.
        for (i in balls.indices) {
            val ix = i / 5 - 2
            val iz = i % 5 - 2
            // Different phases separate neighbouring balls, exposing local correspondence.
            val bob = sin(time * bobSpeed + ix * 0.9 + iz * 0.6) * bobAmplitude
            val drift = sin(time * driftSpeed + iz * 1.3) * driftAmplitude
            balls[i].setModel(
                Matrix4f().translate(ix * 0.85f + drift.toFloat(), bob.toFloat(), iz * 0.85f),
            )
        }
        val dolly = sin(time * dollySpeed) * dollyAmplitude
        val yaw = sin(time * yawSpeed) * yawAmplitude
        camera.at.set(initialEye).add(0f, 0f, dolly.toFloat())
        val cosYaw = kotlin.math.cos(yaw)
        val sinYaw = sin(yaw)
        direction.set(
            (initialDirection.x * cosYaw + initialDirection.z * sinYaw).toFloat(),
            initialDirection.y,
            (initialDirection.z * cosYaw - initialDirection.x * sinYaw).toFloat(),
        )
        camera.target.set(camera.at).add(direction)
        frame()
    }
}
