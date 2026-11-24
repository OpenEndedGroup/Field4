val white = vec(0.94, 0.93, 0.89, 1.0)
val red = vec(0.82, 0.22, 0.2, 1.0)
val scene = vectorScene {
    cylinder(30.0, 200.0, white)
    group({ translate(0.0, 200.0, 0.0) }) { cone(36.0, 40.0, red) }
    group({ translate(90.0, 20.0, 0.0) }) { box(60.0, 40.0, 40.0, vec(0.4, 0.6, 0.85, 1.0), top = red) }
    group({ translate(-80.0, 0.0, 20.0) }) { hemisphere(24.0, vec(0.55, 0.52, 0.5, 1.0)) }
}

var yaw = 1.2
fun draw() = lines(scene.render(yaw = yaw, pitch = -0.25), w = 420.0, h = 300.0, fitToSize = true)

slider("camera", min = 0.0, max = 6.28, step = 0.01, initialValue = yaw, channel = "camera") {
    yaw = it
    draw()
}
draw()
