import afield.media.image.util.PngEncoder
import afield.simulation.optics.*

// A sensor of 32 × 32 detectors, a kilopixel. Bare, each detector counts all light the same.
// With filters each counts only red, green or blue; filling in guesses the other two from neighbours.
val light =
    SensorCamera(z = -5.0, sensorWidth = 1.2, sensorHeight = 1.2, sensorDistance = 1.0, apertureDiameter = 0.03)
        .render(OpticsScene.testScene(), 32, 32, samples = 32)
var filters = false
var fillIn = false

fun draw() {
    val shown = when {
        !filters ->
            SensorImage(
                32,
                32,
                FloatArray(32 * 32 * 3) {
                    (light.rgb[it / 3 * 3] + light.rgb[it / 3 * 3 + 1] + light.rgb[it / 3 * 3 + 2]) / 3
                },
                light.reference,
            )
        !fillIn -> light.mosaic()
        else -> light.mosaic().demosaic()
    }
    display("sensor", PngEncoder.encode(shown.toArgb(exposure = 1 / light.reference, scale = 12), 384, 384))
}

draw()
controlGroup {
    checkbox("colour filters") {
        filters = it
        draw()
    }
    checkbox("fill in colours") {
        fillIn = it
        draw()
    }
}
