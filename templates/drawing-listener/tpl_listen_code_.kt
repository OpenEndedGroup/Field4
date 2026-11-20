// Run this box, then draw a stroke anywhere on the drawing layer (Alt+D, then P). Each
// finished stroke calls the handler; stopping the box removes it.
var strokes = 0
notation.onStroke { s ->
    strokes++
    val over = s.box?.name ?: "the canvas"
    markdown("stroke $strokes: ${s.points.size} points, ${s.length.toInt()} long, over $over")
}
