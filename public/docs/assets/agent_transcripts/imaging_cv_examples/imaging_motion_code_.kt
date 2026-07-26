// Two frames related by a transform we choose, so the answer is known in advance.
val a = Img.generate(200, 200) { x, y ->
    (0.5 + 0.24 * kotlin.math.sin(x * 0.21) * kotlin.math.cos(y * 0.17) +
        0.16 * kotlin.math.sin((x + y) * 0.11)).toFloat().coerceIn(0f, 1f)
}
val planted = Affine2D.rotationAbout(0.05, 100.0, 100.0).compose(Affine2D.translation(5.0, -3.0))
val b = a.warpAffine(planted, border = Border.CLAMP)

// Seed only where the 2x2 tracking system is well conditioned.
val seeds = a.goodFeaturesToTrack(maxCount = 200, minDistance = 9)
    .filter { it.x in 24.0..176.0 && it.y in 24.0..176.0 }
    .map { it.toVec2() }
val flow = opticalFlowLK(a, b, seeds, windowRadius = 8, levels = 3)
val fit = findHomographyRansac(
    flow.sourcePoints(), flow.targetPoints(),
    threshold = 1.5, iterations = 1200, seed = 3L
)

// How far is the recovered model from the one we planted, in pixels, on a probe grid?
var worst = 0.0
for (gx in 40..160 step 40) for (gy in 40..160 step 40) {
    val (fx, fy) = fit.homography.apply(gx.toDouble(), gy.toDouble())
    val (tx, ty) = planted.apply(gx.toDouble(), gy.toDouble())
    worst = maxOf(worst, hypot(fx - tx, fy - ty))
}

showImgGrid(
    "frame A" to a,
    "frame B (rot 0.05 rad + shift 5,-3)" to b,
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
