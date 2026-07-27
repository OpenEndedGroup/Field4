// A clean synthetic source, and the same source with 4% salt-and-pepper corruption.
val clean = Img.generate(2400, 1800) { x, y ->
    var v = 0.12f + 0.10f * (y / 1800f)
    if (hypot(x - 600.0, y - 700.0) <= 340.0) v = 0.85f
    if (x in 1200..1850 && y in 300..950) v = 0.65f
    if (y in 1350..1420 && x in 200..2199) v = 0.95f
    v
}
val rng = kotlin.random.Random(11)
val noisy = clean.copy()
repeat((noisy.pixelCount * 0.04).toInt()) {
    noisy.data[rng.nextInt(noisy.pixelCount)] = if (rng.nextBoolean()) 0f else 1f
}

// These radii are in PIXELS, and they deliberately did not scale with the image. The
// separable filters are O(k) per pixel, so gaussianBlur is ~linear in area; median and
// bilateral are O(r^2) per pixel, so scaling their radius with the image would multiply
// their cost by 10,000x rather than 100x. Salt-and-pepper is single-pixel noise at any
// image size, so a pixel-scale radius is also the correct choice here, not a concession.
val gauss = noisy.gaussianBlur(2.0)
val med = noisy.median(2)
val bil = noisy.bilateral(sigmaSpace = 3.0, sigmaRange = 0.12)
val sharp = clean.gaussianBlur(2.0).unsharpMask(sigma = 2.0, amount = 1.4)

showImgGrid(
    "clean source" to clean,
    "+ 4% salt-and-pepper" to noisy,
    "gaussianBlur(2.0)" to gauss,
    "median(2)" to med,
    "bilateral(3.0, 0.12)" to bil,
    "blur then unsharpMask(2.0, 1.4)" to sharp,
    columns = 3,
    cellSide = 260,
)
// The median is the right tool for impulse noise; the numbers say so, not the picture.
println(
    "residual vs clean — gaussian ${"%.4f".format(gauss.meanAbsDiff(clean))}, " +
        "median ${"%.4f".format(med.meanAbsDiff(clean))}, " +
        "bilateral ${"%.4f".format(bil.meanAbsDiff(clean))}"
)
