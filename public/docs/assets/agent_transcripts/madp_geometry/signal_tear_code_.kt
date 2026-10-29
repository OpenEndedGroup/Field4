// Another animation: the same grid every frame, torn along random rows as t moves. Launch it (alt+up).
animateGeometry {
    view(background = "#050608", ground = true, grid = false)

    mesh("signal tear")
    mat(
        color = "#ffffff",
        roughness = 0.52,
        metalness = 0.18,
        emissive = "#07121c",
        emissiveIntensity = 0.55,
        flat = true,
    )

    surface(72, 36) { u, v ->
        val row = floor(v * 36)
        val torn = sin(row * 12.989 + t * 4.0) * 0.5 + 0.5
        val tear = torn.pow(8) * (if (sin(t * 1.7 + row) > -0.35) 1.0 else 0.0)

        val x = (u - 0.5) * 6.6 + tear * sin(row * 3.1) * 0.72
        val z = (v - 0.5) * 3.6
        val band = sin((u * 28 + v * 7) + t * 5)
        val y = band * 0.1 + tear * 0.55 + noise(u * 9 + t, v * 9) * 0.12
        vertex(x, y, z, hsl(190 + band * 45 + tear * 120, 0.95, 0.5 + tear * 0.12))
    }
}
