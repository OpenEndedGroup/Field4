import afield.simulation.optics.*

// The test scene through a lens. Choose the distance to focus at and the lens puts its sensor
// where that distance comes out sharp; nearer and further things blur.
val scene = OpticsScene.testScene()
var focus = 4.0
var focal = 1.0
var aperture = 0.5

fun draw() {
    val sensor = ThinLens.imageDistance(focal, focus)
    val camera = SensorCamera(z = -4.5, sensorDistance = sensor, apertureDiameter = aperture, focalLength = focal)
    val image = camera.render(scene, 300, 200, samples = 64)
    // Smoothed when scaled: a picture, not a grid of detectors.
    display("sensor", image.html(width = 600))
    markdown(
        "readout",
        "f/${(focal / aperture).f1}, sensor ${sensor.f3} behind the lens, sharp at ${focus.f1} in front",
    )
}

draw()
controlGroup {
    slider("focus distance", 2.0, 12.0, step = 0.05, initialValue = 4.0, law = ControlLaw.LOGARITHMIC) {
        focus = it
        draw()
    }
    slider("aperture", 0.02, 0.8, step = 0.01, initialValue = 0.5) {
        aperture = it
        draw()
    }
    slider("focal length", 0.7, 1.6, step = 0.01, initialValue = 1.0) {
        focal = it
        draw()
    }
}
