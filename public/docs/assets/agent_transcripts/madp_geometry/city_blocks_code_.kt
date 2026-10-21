geometry {
    seed(7)
    view(background = "#060708", ground = true, grid = true)

    // Boxes are triangle recipes too; the material state changes by district.
    mat(color = "#ffffff", roughness = 0.8, metalness = 0.04, flat = true)
    for (x in -5..5) for (z in -5..5) {
        if (noise(x * 0.7, z * 0.7) > 0.38) continue
        val height = 0.18 + rand(0.05, 1.0).pow(1.8) * 2.8
        val warm = (x + z + 10) / 20.0
        color(hsl(178 + warm * 46 + height * 10, 0.5, 0.28 + height * 0.08))
        box(vec(x * 0.42, height * 0.5 - 1.05, z * 0.42), vec(0.31, height, 0.31))
    }

    mesh("copper towers")
    mat(color = "#e2a75a", roughness = 0.5, metalness = 0.32, flat = true)
    for (i in 0 until 18) {
        val angle = i / 18.0 * TAU
        val r = 2.75 + noise(i.toDouble(), 0.0) * 0.28
        color(hsl(35 + i * 4, 0.7, 0.46))
        box(vec(cos(angle) * r, 0.25, sin(angle) * r), vec(0.16, 2.2 + rand(0.0, 0.85), 0.16))
    }
}
