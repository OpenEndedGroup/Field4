// animateGeometry runs the recipe 30 times a second with t (seconds) moving.
// Launch the box (alt+up) to play it; stop it with alt+down.
animateGeometry {
    view(background = "#05080c", ground = true, grid = false)

    mesh("breathing sheet")
    mat(color = "#ffffff", roughness = 0.74, metalness = 0.05, flat = true)

    surface(64, 28) { u, v ->
        val x = (u - 0.5) * 6.8
        val z = (v - 0.5) * 2.8
        val phase = t * 2.2
        val wave = sin(x * 2.8 + phase) * cos(z * 4.2 - phase * 0.7)
        val curl = sin((u + v) * TAU * 2.0 + phase * 0.55)
        val y = wave * 0.34 + curl * 0.12
        vertex(x, y, z, hsl(188 + wave * 44 + curl * 18, 0.82, 0.48 + y * 0.12))
    }
}
