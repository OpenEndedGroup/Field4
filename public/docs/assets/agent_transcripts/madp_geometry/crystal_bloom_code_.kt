geometry {
    seed(18)
    view(background = "#070708", ground = true, grid = false)

    mesh("crystal bloom")
    mat(color = "#ffffff", roughness = 0.42, metalness = 0.06, flat = true)

    for (ring in 0 until 9) {
        val count = 7 + ring * 4
        val radius = 0.35 + ring * 0.28
        val lift = -0.7 + ring * 0.12

        for (i in 0 until count) {
            val a0 = i.toDouble() / count * TAU + ring * 0.19
            val a1 = (i + 0.82) / count * TAU + ring * 0.19
            val mid = (a0 + a1) * 0.5
            val height = 0.35 + rand(0.05, 0.72) + ring * 0.045
            val lean = rand(-0.12, 0.12)
            val jitter = rand(-0.04, 0.04)
            val hue = 182 + ring * 12 + rand(-10.0, 16.0)

            tri(
                vertex(cos(a0) * radius, lift, sin(a0) * radius, hsl(hue - 14, 0.9, 0.38)),
                vertex(cos(a1) * radius, lift + jitter, sin(a1) * radius, hsl(hue + 10, 0.95, 0.52)),
                vertex(cos(mid) * (radius + 0.12 + lean), lift + height, sin(mid) * (radius + 0.12 - lean),
                    hsl(hue + 26, 0.95, 0.68)),
            )
        }
    }
}
