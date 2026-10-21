geometry {
    view(background = "#060807", ground = true, grid = true)

    val body = List(36) { i ->
        val v = i / 35.0
        val waist = 0.72 + sin(v * PI) * 0.42
        val ripple = sin(v * PI * 8) * 0.045
        val shoulder = exp(-(v - 0.72) * (v - 0.72) * 38) * 0.22
        profile(waist + ripple + shoulder, (v - 0.5) * 3.2, hsl(28 + v * 34, 0.58, 0.42 + v * 0.16))
    }

    mesh("vessel body")
    mat(color = "#ffffff", roughness = 0.72, metalness = 0.02, flat = true)
    revolve(body, 96)

    mesh("rim")
    mat(color = "#f4d28a", roughness = 0.38, metalness = 0.65, flat = true)
    revolve(listOf(profile(1.0, 1.54, "#ffe4a0"), profile(1.16, 1.6, "#fff2bd"), profile(1.02, 1.68, "#c99a45")), 96)
}
