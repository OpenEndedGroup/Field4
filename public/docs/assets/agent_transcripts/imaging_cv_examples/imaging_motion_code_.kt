// Two frames related by a transform we choose, so the answer is known in advance.
// Multi-octave on purpose. Lucas-Kanade is coarse-to-fine: it halves the image several
// times and tracks the big picture first. A texture with only fine detail (period ~30 px)
// aliases away after four halvings and the tracker has nothing to lock onto — that
// version tracked 0 of 125 seeds at this size. Coarse octaves survive to the top of the
// pyramid; the fine ones sharpen the final refinement.
val a = Img.generate(2000, 2000) { x, y ->
    (0.5 +
        0.20 * kotlin.math.sin(x * 0.021) * kotlin.math.cos(y * 0.017) +
        0.14 * kotlin.math.sin((x + y) * 0.011) +
        0.10 * kotlin.math.sin(x * 0.21) * kotlin.math.cos(y * 0.17) +
        0.06 * kotlin.math.cos((x - 2.0 * y) * 0.07)
        ).toFloat().coerceIn(0f, 1f)
}
val planted = Affine2D.rotationAbout(0.05, 1000.0, 1000.0).compose(Affine2D.translation(50.0, -30.0))
val b = a.warpAffine(planted, border = Border.CLAMP)

// Seed only where the 2x2 tracking system is well conditioned. The displacement is now
// 10x larger in pixels, so the pyramid needs another level to keep it inside the window.
val seeds = a.goodFeaturesToTrack(maxCount = 200, minDistance = 90)
    .filter { it.x in 240.0..1760.0 && it.y in 240.0..1760.0 }
    .map { it.toVec2() }
val flow = opticalFlowLK(a, b, seeds, windowRadius = 8, levels = 5)
val fit = findHomographyRansac(
    flow.sourcePoints(), flow.targetPoints(),
    threshold = 1.5, iterations = 1200, seed = 3L
)

// How far is the recovered model from the one we planted, in pixels, on a probe grid?
var worst = 0.0
for (gx in 400..1600 step 400) for (gy in 400..1600 step 400) {
    val (fx, fy) = fit.homography.apply(gx.toDouble(), gy.toDouble())
    val (tx, ty) = planted.apply(gx.toDouble(), gy.toDouble())
    worst = maxOf(worst, hypot(fx - tx, fy - ty))
}

showImgGrid(
    "frame A" to a,
    "frame B (rot 0.05 rad + shift 50,-30)" to b,
    "|A - B| (inferno)" to frameDifference(a, b).normalized().colormap("inferno"),
    "B warped back by the fitted model" to b.warpPerspective(fit.homography.inverse(), border = Border.CLAMP),
    columns = 2,
    cellSide = 280,
)
val (mdx, mdy) = flow.medianDisplacement()
println("${flow.tracked().size}/${seeds.size} tracked; median displacement " +
    "(${"%.2f".format(mdx)}, ${"%.2f".format(mdy)}) px")
println("RANSAC ${fit.inlierCount} inliers of ${flow.tracked().size} after ${fit.iterations} iterations")
println("recovered model agrees with the planted transform to ${"%.3f".format(worst)} px over the probe grid")
println(fit.homography.toString())

// One arrow FLine per track. This is the whole family's shape in one line: raster in,
// geometry out, and the geometry goes to lines() like anything else you draw in Field.
lines(flow.toFLines(scale = 6.0, minMagnitude = 0.5))
