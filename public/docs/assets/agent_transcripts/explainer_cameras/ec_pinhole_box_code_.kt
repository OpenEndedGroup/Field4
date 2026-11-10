import afield.media.image.util.PngEncoder
import afield.simulation.optics.*

// A closed box with a small hole, looking at the test scene. The picture forms on the back wall.
val scene = OpticsScene.testScene()
val objects = scene.mesh()
var hole = 0.04
var distance = 1.0
var fixedExposure = false

fun draw() {
    val pinhole = SensorCamera(z = -5.0, sensorDistance = distance, apertureDiameter = hole)
    val image = pinhole.render(scene, 96, 64, samples = 48)
    // Fixed: right for a hole 0.08 wide at distance 1. Otherwise each picture is scaled to look right.
    val exposure = if (fixedExposure) 1.0 / (PI * 0.04 * 0.04) else 1.0 / image.reference
    three(channel = "box", height = 320, width = 640) {
        mesh(objects, wireframe = false)
        mesh(pinhole.sensorMesh(image, exposure), wireframe = false, unlit = true, cullBackFaces = false)
        lines(pinhole.outline())
        camera(yaw = 235.0, pitch = 18.0, distance = 1.5)
        background("#d8dde3")
    }
    display("sensor", PngEncoder.encode(image.toArgb(exposure, scale = 5), 480, 320))
}

draw()
controlGroup {
    slider("hole diameter", 0.005, 0.3, step = 0.005, initialValue = 0.04) {
        hole = it
        draw()
    }
    slider("sensor distance", 0.4, 2.5, step = 0.05, initialValue = 1.0) {
        distance = it
        draw()
    }
    checkbox("fixed exposure") {
        fixedExposure = it
        draw()
    }
}
