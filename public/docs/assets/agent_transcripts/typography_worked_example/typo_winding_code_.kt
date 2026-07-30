// Both cells below go through the identical filler at the identical fill rule. The only
// difference is whether hole rings run the opposite way to their bowls. NON_ZERO fill —
// which is what Skia and Java2D both do — cancels a +1 outer against a -1 hole and leaves a
// counter; give it two rings wound the same way and it fills them solid.
val face = typeface("SansSerif", 220.0)
val layout = layoutText("open", face)
val window = layout.bounds

val withConvention = rasterizeText(
    layout, 900, 460, window = window,
    fillRule = TextFillRule.NON_ZERO, rings = layout.toContours(honourHoles = true)
)
val withoutConvention = rasterizeText(
    layout, 900, 460, window = window,
    fillRule = TextFillRule.NON_ZERO, rings = layout.toContours(honourHoles = false)
)

// EVEN_ODD counts crossings and ignores their direction, so it depends on nesting parity
// instead and fills the counters correctly either way. Two rules that agree on correct input
// and disagree on broken input is a cross-check, which is why both are in the API.
val evenOdd = rasterizeText(layout, 900, 460, window = window, fillRule = TextFillRule.EVEN_ODD)

var worst = 0.0
for (y in 0 until withConvention.height) for (x in 0 until withConvention.width) {
    worst = maxOf(worst, kotlin.math.abs(withConvention.at(x, y, 0) - evenOdd.at(x, y, 0)).toDouble())
}

showImgGrid(
    "NON_ZERO, convention on: counters are holes" to withConvention,
    "NON_ZERO, convention off: counters fill solid" to withoutConvention,
    "EVEN_ODD over the correct rings" to evenOdd,
    columns = 3, cellSide = 420
)
println("ink with the convention  : ${"%.2f".format(100 * withConvention.inkFraction())}%")
println("ink without it           : ${"%.2f".format(100 * withoutConvention.inkFraction())}%  <- the counters closed up")
println("worst NON_ZERO vs EVEN_ODD difference over the correct rings: $worst (0.0 means the two rules agree exactly)")
