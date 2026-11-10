import afield.media.image.util.PngEncoder
import afield.simulation.optics.*

// A sensor held up to the scene with nothing in front of it. One point on each ball sends light to
// every detector, and so does every other point: each detector gets the same blend.
val scene = OpticsScene.testScene()
val bare = SensorCamera(z = -4.0)
val image = bare.renderBare(scene, 48, 32, samples = 256)
val corners = 5 { i -> 4 { j -> bare.toWorld(-0.6 + 0.3 * i, -0.36 + 0.24 * j, 0.0) } }.flatten()
fun fan(x: Double, y: Double, z: Double, c: Vec4) = corners.map { p ->
    fline {
        moveTo(x, y, z)
        lineTo(p[0], p[1], p[2])
        color = c
        thickness = 1.0
    }
}

three(channel = "rays", height = 320, width = 640) {
    mesh(scene.mesh(), wireframe = false)
    mesh(bare.sensorMesh(image), wireframe = false, unlit = true, cullBackFaces = false)
    lines(fan(-0.9, 1.4, -0.8, vec(1.0, 0.6, 0.2, 1.0)) + fan(1.0, 1.2, 1.3, vec(0.4, 0.9, 1.0, 1.0)))
    camera(yaw = 235.0, pitch = 18.0, distance = 1.5)
    background("#d8dde3")
}
display("sensor", PngEncoder.encode(image.toArgb(scale = 10), 480, 320))
