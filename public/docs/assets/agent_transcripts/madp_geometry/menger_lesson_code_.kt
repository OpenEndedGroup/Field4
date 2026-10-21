geometry {
    view(background = "#070707", ground = true, grid = true)
    mat(color = "#ffffff", roughness = 0.7, metalness = 0.02, flat = true)

    fun sponge(center: Vec3, size: Double, depth: Int) {
        if (depth == 0) {
            color(hsl(180 + center.x * 28 + center.y * 42 - center.z * 18, 0.62, 0.42 + center.y * 0.08))
            box(center, size * 0.96)
            return
        }
        val step = size / 3
        for (x in -1..1) for (y in -1..1) for (z in -1..1) {
            val holes = listOf(x, y, z).count { it == 0 }
            if (holes >= 2) continue
            sponge(center + vec(x * step, y * step, z * step), step, depth - 1)
        }
    }

    sponge(vec(0, 0, 0), 3.1, 2)
}
