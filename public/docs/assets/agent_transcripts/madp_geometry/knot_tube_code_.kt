geometry {
    view(background = "#05060b", ground = true, grid = false)

    mesh("parametric knot")
    mat(color = "#ffffff", roughness = 0.48, metalness = 0.18, flat = true)

    tube(260, 10) { s ->
        val a = s * TAU * 2
        val pulse = 1.48 + cos(a * 3) * 0.32
        section(
            vec(cos(a) * pulse, sin(a * 1.5) * 0.78, sin(a) * pulse),
            radius = 0.115 + sin(a * 5) * 0.018,
            color = hsl(205 + s * 180, 0.88, 0.56),
        )
    }
}
