import afield.graphics.scene.reading.readingWalk
import afield.graphics.vulkan.targets.OpenXRHmdTarget.Hand

// Translate the reading world opposite the virtual reader; the room and browser remain tracked.
val navigationDrawing = drawing
val previousNavigationUpdate = navigationDrawing.beforeUpdate
prop<Boolean>(readingWalkEnabled, true)
prop<Float>(readingWalkSpeed, .65f) // metres per second at full deflection
var navigationLastTime = System.nanoTime()
var navigationPlaced = renderer.openXRHmdTarget?.headPose() == true
val navigationUpdate: () -> Unit = {
    previousNavigationUpdate()
    val now = System.nanoTime()
    val dt = ((now - navigationLastTime) / 1e9).coerceIn(0.0, .05).toFloat()
    navigationLastTime = now
    val xr = renderer.openXRHmdTarget
    // A synchronized OpenXR session can precede its first visible, located head pose.
    // Place the poem on that first real pose, rather than around an identity eye matrix.
    if (!navigationPlaced && xr?.headPose() == true) {
        navigationDrawing.recenter()
        navigationPlaced = true
    }
    if (readingWalkEnabled && xr != null) {
        val stick = xr.controllerState(Hand.LEFT)
        if (stick.active) {
            val eye = stereo.eyeViewMatrix(0).invert()
            val movement = readingWalk(
                stick.thumbstickX,
                stick.thumbstickY,
                vec(-eye.m20, -eye.m21, -eye.m22),
                dt.toDouble(),
                readingWalkSpeed,
            )
            navigationDrawing.origin.sub(movement)
        }
    }
}
navigationDrawing.beforeUpdate = navigationUpdate

loop { frame() }
    .end {
        if (navigationDrawing.beforeUpdate === navigationUpdate)
            navigationDrawing.beforeUpdate = previousNavigationUpdate
    }
