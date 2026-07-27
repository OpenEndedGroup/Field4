val src = Img.generate(2400, 1800) { x, y ->
    var v = 0.12f + 0.08f * (y / 1800f)
    if (hypot(x - 620.0, y - 700.0) <= 340.0) v = 0.85f
    if (x in 1200..1850 && y in 300..950) v = 0.68f
    val tx = x - 2100.0; val ty = y - 400.0
    if (ty in 0.0..600.0 && kotlin.math.abs(tx) <= ty * 0.5) v = 0.95f
    v
}

// Sobel here is scaled to true units-per-pixel: a unit step edge integrates to 1.
val grad = src.sobel()
val edges = src.canny(low = 0.05, high = 0.11, sigma = 1.2)
val auto = src.cannyAuto()          // thresholds derived from this image's own gradients

// Edges are geometry, and geometry in Field is an FLine. minPerimeter and the simplify
// tolerance are pixel distances, so they scale with the image.
val flines = edges.toContourFLines(minPerimeter = 120, simplifyTolerance = 12.0)

showImgGrid(
    "source" to src,
    "sobel().dx (signed, coolwarm)" to grad.dx.colormapDiverging("coolwarm"),
    "sobel().dy (signed, coolwarm)" to grad.dy.colormapDiverging("coolwarm"),
    "magnitude (inferno)" to grad.magnitude().normalized().colormap("inferno"),
    "orientation as hue x magnitude" to grad.toColorImg(),
    "canny(0.05, 0.11)" to edges,
    columns = 3,
    cellSide = 260,
)
println(
    "|grad| max ${"%.3f".format(grad.magnitude().max())}; " +
        "canny ${edges.data.count { it > 0.5f }} px, cannyAuto ${auto.data.count { it > 0.5f }} px; " +
        "${flines.size} FLines, ${flines.sumOf { it.nodes.size }} nodes total"
)

// The raster above is what Canny computed; this is what it *means* — three closed
// outlines you can now transform, intersect or animate like any other Field geometry.
lines(flines)
