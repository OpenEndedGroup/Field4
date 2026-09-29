// Drawing from code. Everything this block makes belongs to this box: run it again and
// the marks are replaced, not duplicated. Marks drawn by hand are never touched.
val listener = inside.boxes.values.first { it.name == "listen" }
notation {
    stroke = "#1971c2"
    text("drawn by ${name}", frame.x, frame.y - 34)
    ellipse(frame.x - 20, frame.y - 20, frame.w + 40, frame.h + 40)
    arrow(from = thisBox, to = listener, label = "tells")   // drag either box: the arrow follows
}
