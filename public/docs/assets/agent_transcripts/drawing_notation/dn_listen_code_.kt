// Listening to the drawing: run this, then draw a stroke anywhere (Alt+D, then P).
// The handler runs when the stroke is finished; stop the box to remove it.
notation.onStroke { s ->
    println("a stroke of ${s.points.size} points, ${s.length.toInt()} long, from ${s.start} to ${s.end}")
}
