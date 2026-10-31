geometry {
    seed(42)
    view(background = "#060806", ground = true, grid = false)

    fun branch(start: Vec3, dir: Vec3, length: Double, radius: Double, depth: Int) {
        val bend = vec(rand(-0.28, 0.28), rand(-0.05, 0.25), rand(-0.28, 0.28))
        val hue = 88 + depth * 18 + rand(-8.0, 10.0)

        mesh("bark depth $depth")
        mat(color = "#ffffff", roughness = 0.88, metalness = 0.01, flat = true)
        tube(9, 6, closed = false) { s ->
            val eased = s * s * (3 - 2 * s)
            val sway = sin(s * PI) * 0.25
            section(
                start + (length * s) * dir + sway * bend,
                radius = radius * (1 - eased * 0.58),
                color = hsl(hue, 0.38, 0.28 + depth * 0.035 + s * 0.08),
            )
        }
        val end = start + length * dir + 0.22 * bend

        if (depth <= 0) {
            mesh("caps")
            mat(color = "#fff4d8", roughness = 0.64, metalness = 0.0, flat = true)
            push()
            translate(end)
            scale(radius * 4.4, radius * 0.9, radius * 4.4)
            revolve(
                [
                    profile(0.04, 0.0, hsl(36, 0.9, 0.72)),
                    profile(0.58, 0.08, hsl(32, 0.72, 0.55)),
                    profile(1.0, 0.0, hsl(24, 0.75, 0.44)),
                    profile(0.42, -0.16, hsl(42, 0.58, 0.62)),
                ],
                18,
            )
            pop()
            return
        }

        val branches = if (depth > 2) 3 else 2
        for (i in 0 until branches) {
            val turn = i.toDouble() / branches * TAU + rand(-0.5, 0.5)
            val lift = 0.48 + rand(0.0, 0.32)
            val next = vec(
                dir.x * 0.62 + cos(turn) * (0.45 + rand(0.0, 0.22)),
                dir.y * 0.52 + lift,
                dir.z * 0.62 + sin(turn) * (0.45 + rand(0.0, 0.22)),
            ).normalize()
            branch(end - (radius * 0.6) * dir, next, length * rand(0.56, 0.76), radius * rand(0.58, 0.72), depth - 1)
        }
    }

    branch(vec(0.0, -1.1, 0.0), vec(0.0, 1.0, 0.0), 1.45, 0.16, 4)
}
