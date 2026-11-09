// Five glyphs as the matcher sees them, 10x up: the cell in gray, its six circles in orange,
// and the six just outside it in blue.
val ascii = ShapeAscii()
val k = 10.0
fun circle(cx: Double, cy: Double, r: Double) = fline {
    for (i in 0..48) {
        val a = 2 * PI * i / 48
        if (i == 0) moveTo(cx + r * cos(a), cy + r * sin(a)) else lineTo(cx + r * cos(a), cy + r * sin(a))
    }
}
val circles = ascii.samplingCircles()
val panels =
    "TL/o@"
        .map { ch ->
            val i = ascii.glyphs.indexOf(ch)
            // pad the cell so the outer circles fit, then draw the circles over it
            val pad = ascii.cellWidth
            val cell = ascii.glyphImg(i).pad(pad, Border.ZERO)
            val inner =
                circles.take(6).map { (x, y, r) ->
                    circle(x + pad, y + pad, r).apply { color = Vec4(1f, 0.55f, 0.1f, 1f) }
                }
            val outer =
                circles.drop(6).map { (x, y, r) ->
                    circle(x + pad, y + pad, r).apply { color = Vec4(0.3f, 0.6f, 1f, 1f) }
                }
            val w = ascii.cellWidth.toDouble()
            val h = ascii.cellHeight.toDouble()
            val box = fline {
                moveTo(pad, pad)
                lineTo(pad + w, pad)
                lineTo(pad + w, pad + h)
                lineTo(pad, pad + h)
                lineTo(pad, pad)
            }
            box.color = Vec4(0.5f, 0.5f, 0.5f, 1f)
            "'$ch'" to cell.drawFLines(inner + outer + box, scale = k, thickness = 2.0)
        }
showImgGrid(*panels.toTypedArray(), columns = 5, cellSide = 200)
