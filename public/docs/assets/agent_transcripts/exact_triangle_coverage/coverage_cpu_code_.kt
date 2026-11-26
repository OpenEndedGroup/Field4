// Eight triangles on a 32 x 24 grid, y down, divided into the unit square TriangleSplat uses.
fun tri(
    ax: Double,
    ay: Double,
    bx: Double,
    by: Double,
    cx: Double,
    cy: Double,
    r: Double,
    g: Double,
    b: Double,
    a: Double = 1.0,
) = CoverageTriangle(ax / 32, ay / 24, bx / 32, by / 24, cx / 32, cy / 24, r, g, b, a)

val scene = [
    tri(2.0, 20.0, 14.0, 3.0, 20.0, 18.0, 1.0, 0.55, 0.1),
    tri(18.0, 8.0, 31.0, 11.0, 24.0, 22.0, 0.2, 0.8, 0.4, 0.7),
    tri(3.0, 2.0, 30.0, 4.0, 30.0, 4.35, 0.3, 0.6, 1.0),
    tri(6.0, 22.0, 28.0, 21.6, 28.0, 22.0, 1.0, 1.0, 1.0),
] +
        (0 until 4).map { i ->
            // Four triangles of 0.08 pixels each, tucked between pixel centres.
            val x = 2.0 + 2.0 * i
            tri(x + 0.05, 5.05, x + 0.45, 5.1, x + 0.1, 5.45, 1.0, 1.0, 1.0)
        }

val point = TriangleSplat.render(scene, 32, 24, CoverageSampling.POINT)
val exact = TriangleSplat.render(scene, 32, 24, CoverageSampling.EXACT)
val reference = TriangleSplat.render(scene, 512, 384, CoverageSampling.EXACT)
showImgGrid(
    "point-sampled, 32 x 24" to point.image.upscaleNearest(16),
    "exact coverage, 32 x 24" to exact.image.upscaleNearest(16),
    "exact coverage, 512 x 384" to reference.image,
    columns = 3,
    cellSide = 512,
)
println("point-sampled: ${point.visible} of ${scene.size} triangles reach a pixel")
println("exact:         ${exact.visible} of ${scene.size} triangles reach a pixel")

// Culling: 20,000 triangles, most far smaller than a pixel, with a soft window (sigma = 1).
val rng = kotlin.random.Random(1)
val dust = List(20000) {
    val s = 0.0004 + 0.01 * rng.nextDouble() * rng.nextDouble()
    val x = rng.nextDouble()
    val y = rng.nextDouble()
    CoverageTriangle(x, y, x + s, y + s * rng.nextDouble(), x + s * rng.nextDouble(), y + s, 0.9, 0.8, 0.6, 0.8)
}
val culledDust = TriangleSplat.render(dust, 128, 128, sigma = 1.0)
println(
    "culling at 128 px: ${culledDust.culled} of ${dust.size} culled, ${culledDust.drawn} drawn, ${culledDust.visible} visible",
)
