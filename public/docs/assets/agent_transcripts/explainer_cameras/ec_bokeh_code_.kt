import afield.media.image.util.PngEncoder
import afield.simulation.optics.*

// Small lights far behind the point the lens is focused on. Each one spreads into a copy of the
// opening: a disc for a round one, a polygon for an iris of blades.
val lights = OpticsScene(
    12 { k ->
        val hue = Refraction.wavelengthColor(450.0 + 20.0 * k)
        OpticsSphere(
            -3.6 + 0.65 * k,
            0.6 + 1.5 * sin(k * 2.4),
            7.0 + 1.5 * cos(k * 1.7),
            0.12,
            hue[0],
            hue[1],
            hue[2],
            emission = 12.0,
        )
    },
    floorY = null,
    sky = doubleArrayOf(0.0, 0.0, 0.0),
    sun = 0.0,
)
var aperture = 0.5
var blades = 0

fun draw() {
    val camera = SensorCamera(
        y = 0.6,
        z = -2.0,
        lookY = 0.6,
        lookZ = 6.0,
        sensorDistance = ThinLens.imageDistance(1.0, 3.0),
        apertureDiameter = aperture,
        focalLength = 1.0,
        blades = blades,
        bladeRotation = 0.3,
    )
    val image = camera.render(lights, 150, 100, samples = 400)
    display("sensor", PngEncoder.encode(image.toArgb(exposure = 1.0 / camera.reference, scale = 4), 600, 400))
}

draw()
controlGroup {
    slider("aperture", 0.05, 0.8, step = 0.01, initialValue = 0.5) {
        aperture = it
        draw()
    }
    slider("blades", 0.0, 9.0, step = 1.0, initialValue = 0.0) {
        blades = if (it < 3) 0 else it.toInt()
        draw()
    }
}
