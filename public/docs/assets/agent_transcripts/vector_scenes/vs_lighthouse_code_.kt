val white = vec(0.94, 0.93, 0.89, 1.0)
val red = vec(0.82, 0.22, 0.2, 1.0)
val lighthouse = vectorScene {
    segments(20)
    group({ translate(0.0, -6.0, 0.0) }) { box(260.0, 12.0, 200.0, vec(0.35, 0.55, 0.4, 1.0)) }
    cylinder(34.0, 70.0, white, topRadius = 30.0)
    group({ translate(0.0, 70.0, 0.0) }) { cylinder(30.0, 60.0, red, topRadius = 26.0) }
    group({ translate(0.0, 130.0, 0.0) }) { cylinder(26.0, 50.0, white, topRadius = 22.0) }
    group({ translate(0.0, 180.0, 0.0) }) { cylinder(30.0, 6.0, vec(0.25, 0.25, 0.28, 1.0)) }
    group({ translate(0.0, 186.0, 0.0) }) { cylinder(18.0, 22.0, vec(1.0, 0.85, 0.35, 1.0)) }
    group({ translate(0.0, 208.0, 0.0) }) { cone(24.0, 26.0, red) }
    group({ translate(80.0, 25.0, 30.0) }) { box(70.0, 50.0, 50.0, white, top = red) }
    group({ translate(-70.0, 0.0, 50.0) }) { hemisphere(22.0, vec(0.55, 0.52, 0.5, 1.0)) }
    group({ translate(-40.0, 0.0, 75.0) }) { hemisphere(14.0, vec(0.5, 0.48, 0.47, 1.0)) }
}

// four quarter turns, side by side
val views =
    (0 until 4).flatMap { k ->
        lighthouse.render(yaw = 0.4 + k * Math.PI / 2, pitch = -0.35).map { it + vec(k * 330.0, 0.0) }
    }
lines(views, w = 720.0, h = 220.0, fitToSize = true)
