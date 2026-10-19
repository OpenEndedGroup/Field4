// Every kind of Excalidraw mark, written from code. The marks belong to this box:
// run it again and they are replaced in place.
val score = inside.boxes.values.first { it.name == "score" }
notation {
    roughness = 0.0
    text("shapes", 80, 180, size = 16)
    rect(80, 210, 120, 70, label = "rect", fill = "#a5d8ff")
    ellipse(220, 210, 120, 70, label = "ellipse", fill = "#b2f2bb", fillStyle = "cross-hatch")
    diamond(360, 205, 120, 80, label = "diamond", fill = "#ffec99", fillStyle = "hachure")

    text("strokes and lines", 80, 310, size = 16)
    line(listOf(vec(80, 350), vec(160, 330), vec(240, 370)), stroke = "#e03131")
    dashed = true
    line(listOf(vec(260, 350), vec(340, 330), vec(420, 370)), stroke = "#1971c2")
    dashed = false
    stroke((0..60).map { vec(80 + it * 6.0, 420 + 18 * sin(it / 6.0)) }, name = "wave",
        stroke = "#9c36b5", pressure = { 0.2 + 0.8 * it })

    text("a group: these drag together", 80, 470, size = 16)
    group {
        rect(80, 500, 60, 40, fill = "#ffc9c9", opacity = 60)
        ellipse(150, 500, 60, 40, fill = "#ffc9c9", opacity = 60)
        diamond(220, 495, 60, 50, fill = "#ffc9c9", opacity = 60)
    }

    arrow(from = thisBox, to = score, label = "reads")
}
println("${notation.mine.size} marks: " + notation.mine.groupingBy { it.kind.name.lowercase() }.eachCount())
