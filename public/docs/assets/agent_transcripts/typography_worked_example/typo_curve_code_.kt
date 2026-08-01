// onCurve does not distort a glyph. It looks its advance-box centre up at that distance
// along the path with FLine.cursor()'s arc-length table — the same machinery
// sampleByDistance and byDashing use — and then sets that glyph's rotation and pivot. So the
// letters stay letters, and the spacing is even in DISTANCE rather than in curve parameter.
val face = typeface("SansSerif", 78.0)
val ring = layoutText("AROUND AND AROUND", face, tracking = 7.0)

// Size the circle to the text rather than the text to the circle: a circumference equal to
// the layout's width means the string closes exactly.
val circle = textCircle(radius = ring.advanceWidth / (2 * Math.PI))
val onCircle = ring.onCurve(circle, align = TextAlign.CENTER)
println("circle circumference ${"%.1f".format(circle.arcLength())} vs layout width ${"%.1f".format(ring.advanceWidth)}")
println("${onCircle.glyphs.count { kotlin.math.abs(it.rotation) > 0.05 }} of ${onCircle.glyphs.size} glyphs turned with the curve")

val wave = fline {
    moveTo(0.0, 0.0)
    cubicTo(300.0, -240.0, 720.0, 240.0, 1020.0, 0.0)
}
val onWave = layoutText("a rolling line of type", face, tracking = 2.0).onCurve(wave, align = TextAlign.CENTER)

// onCurve returns a COPY, so the straight layout is still straight and can be bent again.
println("the original layout is untouched: ${ring.glyphs.all { it.isUntransformed }}")

showImgGrid(
    "on a circle" to rasterizeText(onCircle, 620, 620),
    "on a cubic" to rasterizeText(onWave, 900, 420),
    columns = 2, cellSide = 430
)
showPlaneGrid(
    "the paths, and the letters as outlines" to
        (listOf(circle.copy { color = vec(0.4, 0.6, 0.8, 0.5); thickness = 1.0 }) + onCircle.toFLines(thickness = 1.0)),
    columns = 1, cellWidth = 420, cellHeight = 420
)
