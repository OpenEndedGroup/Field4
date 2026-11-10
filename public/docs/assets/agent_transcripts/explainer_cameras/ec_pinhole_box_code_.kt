import afield.simulation.optics.*

// A closed box with a small hole, looking at the test scene. The picture forms on the back wall.
val scene = OpticsScene.testScene()
var hole = 0.04
var distance = 1.0
var fixedExposure = false

// Three points of the scene, each with its ray through the hole to the back wall.
val marked = [Triple(-0.9, 1.4, -0.8), Triple(1.0, 1.2, 1.3), Triple(0.3, 0.0, -2.0)]

fun draw() {
    val pinhole = SensorCamera(z = -5.0, sensorDistance = distance, apertureDiameter = hole)
    val image = pinhole.render(scene, 240, 160, samples = 48)
    // A coarser copy for the back wall of the 3D box, which needs a quad per pixel.
    val wall = pinhole.render(scene, 96, 64, samples = 24)
    // Fixed: right for a hole 0.08 wide at distance 1. Otherwise each picture is scaled to look right.
    val exposure = if (fixedExposure) 1.0 / (PI * 0.04 * 0.04) else 1.0 / image.reference
    // Each ray from 1.8 units in front of the hole, through it, to where it lands.
    val rays = marked.map { (x, y, z) ->
        val landing = pinhole.imageOf(x, y, z)
        val dx = x
        val dy = y - 1.2
        val dz = z + 5.0
        val k = 1.8 / sqrt(dx * dx + dy * dy + dz * dz)
        fline {
            moveTo(dx * k, 1.2 + dy * k, -5.0 + dz * k)
            lineTo(0.0, 1.2, -5.0)
            lineTo(landing[0], landing[1], landing[2])
            color = vec(1.0, 0.6, 0.2, 1.0)
            thickness = 1.5
        }
    }
    /*
    The box, the rays through it and the objects they come from: the view frames the box
    (at its longest, with the sensor 2.5 back) and the two spheres, and lets the floor run
    off the edges.
    */
    three(channel = "box", height = 320, width = 640) {
        mesh(scene.mesh(), wireframe = false)
        mesh(pinhole.sensorMesh(wall, exposure), wireframe = false, unlit = true, cullBackFaces = false)
        lines(pinhole.outline() + rays)
        fitTo(vec(-1.6, 0.0, -7.6), vec(1.6, 2.0, 1.6))
        camera(yaw = 240.0, pitch = 16.0)
        // The scene's bright sky, as in the pictures the camera takes.
        background("#d8dde3")
    }
    // Smoothed: these pixels stand for a picture, not for detectors.
    display("sensor", image.html(exposure, width = 480))
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
