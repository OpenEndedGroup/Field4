// Marks on the drawing layer (alt/option-D), owned by this box and redrawn when it reruns.
fun boxNamed(n: String) = inside.boxes.values.first { it.name == n }
notation {
    stroke = "#1971c2"
    ellipse(10, 10, 360, 160)
    text("one value, pushed down", 40, -30)
    arrow(from = boxNamed("palette"), to = boxNamed("gyroid"), label = "and beside it, 3D")
    stroke = "#e8590c"
    rect(2270, 10, 740, 700, label = "Vulkan, streamed into the page")
    arrow(from = boxNamed("probed particles"), to = boxNamed("streamed spiral"), label = "probed")
    stroke = "#2f9e44"
    rect(4380, 40, 1000, 860, label = "a timeline to scrub")
    stroke((0..80).map { k -> vec(1300 + k * 12.0, 1440 + 30 * sin(k / 5.0)) }, stroke = "#2f9e44")
}
markdown("marks", "" + notation.mine.size + " marks drawn on the drawing layer")
