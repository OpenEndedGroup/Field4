geometry {
    seed(91)
    view(background = "#050807", ground = true, grid = false)

    mesh("spiral fronds")
    mat(color = "#ffffff", roughness = 0.82, metalness = 0.02, flat = true, side = GeometrySide.DOUBLE)

    // Phyllotaxis: each leaf turns the golden angle from the last.
    val golden = PI * (3 - sqrt(5.0))
    for (i in 0 until 190) {
        val f = i / 189.0
        val angle = i * golden + f * 0.12
        val radius = 0.18 + sqrt(f) * 2.4
        val lift = -0.95 + f * 2.0 + sin(f * 1.4 + i * 0.11) * 0.035
        val cx = cos(angle) * radius
        val cz = sin(angle) * radius
        val tangent = angle + PI * 0.5
        val width = 0.07 + (1 - f) * 0.18
        val length = 0.28 + (1 - f) * 0.42
        val curl = sin(f + i * 0.17) * 0.12

        val root = vec(cx - cos(angle) * length * 0.35, lift, cz - sin(angle) * length * 0.35)
        val left = vec(cx + cos(tangent) * width, lift + curl, cz + sin(tangent) * width)
        val tip = vec(cx + cos(angle) * length, lift + 0.08 + curl, cz + sin(angle) * length)
        val right = vec(cx - cos(tangent) * width, lift - curl * 0.5, cz - sin(tangent) * width)

        val hue = 112 + f * 84 + sin(i.toDouble()) * 8
        quad(
            vertex(root, hsl(hue - 12, 0.66, 0.24 + f * 0.12)),
            vertex(left, hsl(hue, 0.72, 0.34 + f * 0.18)),
            vertex(tip, hsl(hue + 20, 0.78, 0.48 + f * 0.18)),
            vertex(right, hsl(hue + 8, 0.72, 0.32 + f * 0.16)),
        )
    }
}
