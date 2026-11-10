import afield.media.image.util.PngEncoder
import afield.simulation.optics.*

// A pinhole camera looking at an even sky. Its picture is darker towards the edges: the curve is
// the brightness across the sensor, the faint line cos⁴ of the angle each point looks out at.
val cyan = vec(0.4, 0.9, 1.0, 1.0)
val guide = vec(1.0, 1.0, 1.0, 0.35)
var distance = 0.8

fun draw() {
    val camera = SensorCamera(
        y = 0.0,
        z = 0.0,
        lookY = 0.0,
        lookZ = 1.0,
        sensorWidth = 2.0,
        sensorHeight = 0.25,
        sensorDistance = distance,
        apertureDiameter = 0.01,
    )
    val image = camera.render(OpticsScene.uniformSky(), 160, 20, samples = 8)
    display("sensor", PngEncoder.encode(image.toArgb(scale = 4), 640, 80))
    val measured = fline {
        for (i in 0 until 160) {
            val y = 230 - 200 * image[i, 10, 0] / image.reference
            if (i == 0) moveTo(4.0 * i + 2, y) else lineTo(4.0 * i + 2, y)
        }
        color = cyan
        thickness = 3.0
    }
    val predicted = fline {
        for (i in 0 until 160) {
            val sx = ((i + 0.5) / 160 - 0.5) * 2.0
            val y = 230 - 200 * cos(atan(sx / distance)).pow(4)
            if (i == 0) moveTo(4.0 * i + 2, y) else lineTo(4.0 * i + 2, y)
        }
        color = guide
        thickness = 1.0
    }
    showPlane(
        listOf(
            fline {
                moveTo(0, 230)
                lineTo(640, 230)
                color = guide
                thickness = 1.0
            },
            measured,
            predicted,
        ),
        channel = "brightness",
        width = 600,
        height = 225,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, 240.0),
        interactive = false,
    )
    val edge = image[0, 10, 0] / image.reference
    markdown("readout", "the edge of the sensor gets ${(100 * edge).f0}% of the light the middle gets")
}

draw()
controlGroup {
    slider("sensor distance", 0.3, 2.0, step = 0.01, initialValue = 0.8) {
        distance = it
        draw()
    }
}
