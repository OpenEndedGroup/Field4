// A lit sphere on black, shaded by hand: brightness is the cosine between the surface normal and a light.
val scene =
    Img.generate(300, 240) { x, y ->
        val nx = (x - 150) / 100.0
        val ny = (y - 120) / 100.0
        val r2 = nx * nx + ny * ny
        if (r2 < 1) {
            val nz = sqrt(1 - r2)
            (-0.5 * nx - 0.6 * ny + 0.62 * nz).coerceAtLeast(0.0).toFloat()
        } else 0f
    }
val ascii = ShapeAscii()
showAscii(
    "brightness" to ascii.convert(scene, 40, shape = false),
    "shape, contrast 2" to ascii.convert(scene, 40, contrast = 2.0),
    cellSide = 420,
)
