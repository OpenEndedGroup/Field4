import afield.media.image.util.PngEncoder
import afield.simulation.optics.*

// A 48 × 32 sensor collecting photons from the test scene. Light arrives at random, so a short
// exposure is grainy; each detector holds at most 400, so a long one turns bright parts flat white.
val full = 400.0
val light =
    SensorCamera(z = -5.0, sensorDistance = 1.0, apertureDiameter = 0.03)
        .render(OpticsScene.testScene(), 48, 32, samples = 32)
val perSecond = 4000.0 / light.reference // photons per second from the bright sky at the middle

fun draw(time: Double) {
    val counts = light.photons(perSecond * time, seed = 3)
    display("sensor", PngEncoder.encode(counts.toArgb(exposure = 1 / full, scale = 10), 480, 320))
    val sky = 4000.0 * time
    markdown(
        "readout",
        "open ${time.f3} s: about ${sky.f0} photons on a detector facing the sky, which holds ${full.toInt()}",
    )
}

draw(0.05)
controlGroup {
    slider("collection time", 0.001, 0.5, step = 0.001, initialValue = 0.05, law = ControlLaw.LOGARITHMIC) { draw(it) }
}
