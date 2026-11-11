import afield.graphics.scene.video.*

// One movie owns decoding and uploads; box 4 owns its editable graphics pipeline.
prop<StereoVideoSurface>(movie) = StereoVideoSurface(renderer, stereo, automaticDrawing = false)
// Capture this launch's instance for cleanup if the inherited property is replaced.
val ownedMovie = movie

val screen = movie.placement
var screenDistance = 2.5f

// UI handles mirror programmatic recenter/aspect changes without re-firing callbacks.
prop<ControlValueHandle<Double>>(aspectControl)
val poseMirrors = mutableListOf<Pair<ControlValueHandle<Double>, () -> Double>>()

fun ControlGroupBuilder.poseSlider(
    label: String,
    min: Double,
    max: Double,
    value: () -> Double,
    change: (Double) -> Unit,
) {
    poseMirrors += slider(label, min = min, max = max, step = .01, initialValue = value(), onUpdate = change) to value
}

// Flatten the gaze direction: recentering keeps the movie upright, even after a head tilt.
fun recenterScreen() {
    val head = Vector3f()
    val orientation = Quaternionf()
    val forward = vec(0f, 0f, -1f)
    if (renderer.openXRHmdTarget?.headPose(head, orientation) == true) orientation.transform(forward)

    forward.y = 0f
    if (forward.lengthSquared() < 1e-6f) forward.set(0f, 0f, -1f)
    forward.normalize()

    screen.center.set(head).fma(screenDistance, forward)
    screen.rotationDegrees.set(
        0f,
        Math.toDegrees(kotlin.math.atan2(-forward.x.toDouble(), -forward.z.toDouble())).toFloat(),
        0f,
    )

    poseMirrors.forEach { (control, value) -> control.set(value()) }
}
recenterScreen()

controlGroup("screen") {
    slider("Arc width (metres)", min = .2, max = 10.0, step = .01, initialValue = 3.0) {
        screen.widthMeters = it.toFloat()
    }
    slider("Bend (degrees)", min = -160.0, max = 160.0, step = .1, initialValue = 35.0) {
        screen.bendDegrees = it.toFloat()
    }
    aspectControl = slider("Displayed aspect ratio", min = .3, max = 4.0, step = .001, initialValue = screen.aspect.toDouble()) {
        screen.aspect = it.toFloat()
    }
    button("Use native per-eye aspect") { down ->
        if (down) {
            screen.aspect = movie.nativeAspect
            aspectControl.set(screen.aspect.toDouble())
        }
    }
    slider("Recenter distance (metres)", min = .5, max = 10.0, step = .01, initialValue = 2.5) {
        screenDistance = it.toFloat()
    }
    button("Bring screen in front") { down -> if (down) recenterScreen() }
}

controlGroup("room position") {
    poseSlider("Center X (metres)", -10.0, 10.0, { screen.center.x.toDouble() }) { screen.center.x = it.toFloat() }
    poseSlider("Center Y (metres)", -3.0, 5.0, { screen.center.y.toDouble() }) { screen.center.y = it.toFloat() }
    poseSlider("Center Z (metres)", -10.0, 2.0, { screen.center.z.toDouble() }) { screen.center.z = it.toFloat() }
    poseSlider("Yaw (degrees)", -180.0, 180.0, { screen.rotationDegrees.y.toDouble() }) {
        screen.rotationDegrees.y = it.toFloat()
    }
    poseSlider("Pitch (degrees)", -89.0, 89.0, { screen.rotationDegrees.x.toDouble() }) {
        screen.rotationDegrees.x = it.toFloat()
    }
    poseSlider("Roll (degrees)", -180.0, 180.0, { screen.rotationDegrees.z.toDouble() }) {
        screen.rotationDegrees.z = it.toFloat()
    }
}

displayOpen("screen")
displayOpen("room position")

// Transport drives renderer.render(); this box only owns the movie lifetime.
loop { frame() }.end { ownedMovie.close() }
