// A silly one, done rigorously: a duck that bobs, drifts a little across the score each pass
// (box.move), and quacks twice.
score {
    box.move(sin(transportT * 1.4) * 9 * dt, sin(transportT * 3.1) * 6 * dt)

    val body = boxPoint(0.45, 0.62)
    val head = boxPoint(0.68, 0.34 + sin(localT * 4) * 0.06)
    draw.circle(body, 26 + thisRMS * 12, fill = "rgba(255, 224, 96, 0.94)")
    draw.circle(head, 14 + thisRMS * 8, fill = "rgba(255, 224, 96, 0.94)")
    draw.circle(head.x + 5, head.y - 4, 3.0, fill = "rgba(18,18,18,0.95)")
    draw.polyline(
        listOf(pt(head.x + 10, head.y - 6), pt(head.x + 30 + thisRMS * 10, head.y), pt(head.x + 10, head.y + 6)),
        closed = true,
        fill = "rgba(255, 128, 52, 0.96)",
        stroke = "rgba(255, 153, 74, 0.96)",
        width = 1.4,
    )

    if (justEntered) sound("/dough/play/note/64/duration/0.08/s/pulse/pw/0.42/gain/0.16/lpf/1500/release/0.05")
    if (onSubdivision(2, "quack")) sound("/dough/play/note/69/duration/0.06/s/pulse/pw/0.28/gain/0.16/lpf/1300/release/0.05", minInterval = 0.08)
}
