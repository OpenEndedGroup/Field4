import afield.graphics.nile.*

// A pentagram crosses itself (winding 2 in its centre); the squares nest, wound the same way.
fun shape(dx: Double, rule: FillRule): FLine {
    val f = FLine()
    for (k in 0..5) {
        val a = Math.PI * (-0.5 + 0.8 * k)
        val x = dx + 45 + 38 * Math.cos(a)
        val y = 50 + 38 * Math.sin(a)
        if (k == 0) f.moveTo(x, y) else f.lineTo(x, y)
    }
    f.close()
    f.moveTo(dx + 100, 15).lineTo(dx + 170, 15).lineTo(dx + 170, 85).lineTo(dx + 100, 85).close()
    f.moveTo(dx + 118, 33).lineTo(dx + 152, 33).lineTo(dx + 152, 67).lineTo(dx + 118, 67).close()
    f.filled = true
    f.stroked = false
    f.color = vec(0.25, 0.55, 0.95, 1.0)
    f.fillRule = rule
    return f
}

val nonZero = shape(0.0, FillRule.NON_ZERO)
val evenOdd = shape(190.0, FillRule.EVEN_ODD)

// Field's own line renderer: the left pair non-zero, the right pair even-odd.
lines([nonZero, evenOdd], w = 380.0, h = 100.0, channel = "field")

// Gezira's winding rasteriser reads the same attribute.
val g = GeziraFLine.render([nonZero, evenOdd], 380, 100)
display("gezira", GeziraSvg.png(g.canvas.rgba, 380, 100))

// The triangles the Vulkan fill is made of, drawn as outlines.
val triangles = FLine()
for (f in [nonZero, evenOdd]) f.tessellate { v, idx ->
    for (t in idx.indices step 3) {
        val (a, b, c) = Triple(idx[t], idx[t + 1], idx[t + 2])
        triangles.moveTo(v[3 * a], v[3 * a + 1]).lineTo(v[3 * b], v[3 * b + 1]).lineTo(v[3 * c], v[3 * c + 1]).close()
    }
}
triangles.color = vec(0.95, 0.6, 0.2, 1.0)
lines([triangles], w = 380.0, h = 100.0, channel = "triangles")

for ((name, f) in ["non-zero" to nonZero, "even-odd" to evenOdd]) {
    val dx = if (f === nonZero) 0f else 190f
    println(
        "$name: contains2D star centre ${f.contains2D(Vec2(dx + 45f, 50f))}, inner square ${f.contains2D(Vec2(dx + 135f, 50f))}",
    )
}
