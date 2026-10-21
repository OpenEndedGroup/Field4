// A grammar of triangles: winding (flip swaps two corners), colour interpolated across each face,
// and a small box standing on each one.
geometry {
    view(background = "#070809", ground = true, grid = true)
    mat(color = "#ffffff", roughness = 0.78, metalness = 0.02, flat = true, side = GeometrySide.DOUBLE)

    for (y in 0 until 6) for (x in 0 until 10) {
        val cx = (x - 4.5) * 0.48
        val cz = (y - 2.5) * 0.5
        val tilt = sin(x * 0.7 + y * 1.3) * 0.24
        val flip = (x + y) % 2 == 0
        val a = vec(cx - 0.22, -0.65 + tilt, cz - 0.18)
        val b = vec(cx + 0.24, -0.65 - tilt * 0.5, cz - 0.15)
        val c = vec(cx - 0.02, -0.18 + tilt * 0.8, cz + 0.25)
        val h = 170 + x * 11 + y * 18

        tri(
            vertex(if (flip) a else b, hsl(h, 0.86, 0.42)),
            vertex(if (flip) b else a, hsl(h + 34, 0.86, 0.58)),
            vertex(c, hsl(h + 74, 0.9, 0.67)),
        )

        val m = mix(mix(a, b, 0.5), c, 0.33)
        color(hsl(h + 18, 0.7, 0.36))
        box(vec(m.x, m.y + 0.08, m.z), vec(0.035, 0.28 + abs(tilt), 0.035))
    }
}
