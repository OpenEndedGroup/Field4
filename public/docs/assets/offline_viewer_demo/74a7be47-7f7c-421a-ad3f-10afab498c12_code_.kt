/*
Writes this document's drawing layer: a frame around the two boxes, an arrow between them,
a heading, and a hand-drawn underline. The marks belong to this box: run it again and they
are replaced in place.
*/
val flock = inside.boxes.values.first { it.name == "flock" }
val chart = inside.boxes.values.first { it.name == "chart" }
notation {
    roughness = 0.0
    text("reading a flock", 40, -70, size = 28)
    stroke((0..40).map { vec(40 + it * 6.5, -28 + 4 * sin(it / 3.0)) }, stroke = "#e03131")
    rect(20, 0, 700, 200, stroke = "#1971c2")
    arrow(from = flock, to = chart, label = "plots")
    ellipse(410, 230, 220, 70, label = "converges", fill = "#b2f2bb", fillStyle = "hachure")
    diamond(60, 230, 140, 80, label = "ring", fill = "#ffec99")
}
println("${notation.mine.size} marks")
