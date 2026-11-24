val model = vectorScene {
    segments(28)
    hemisphere(50.0, vec(0.95, 0.75, 0.3, 1.0))
    group({ translate(0.0, -60.0, 0.0) }) { cylinder(50.0, 60.0, vec(0.3, 0.6, 0.9, 1.0)) }
    group({ translate(0.0, 50.0, 0.0) }) { cone(14.0, 30.0, vec(0.85, 0.25, 0.25, 1.0)) }
}
val flat = model.render(yaw = 0.5, pitch = -0.3, shading = Shading.FLAT)
val soft = model.render(yaw = 0.5, pitch = -0.3, shading = Shading.SOFT).map { it + vec(160.0, 0.0) }
val plotted = model.render(yaw = 0.5, pitch = -0.3, shading = Shading.NONE, outline = vec(0.1, 0.1, 0.1, 1.0)).map {
    it + vec(320.0, 0.0)
}
lines(flat + soft + plotted, w = 600.0, h = 220.0, fitToSize = true)
