// Oskar Fischinger's geometry: a planet on a ring, and a bell three times a pass.
score {
    val c = boxPoint(0.5, 0.5)
    val q = boxPoint(0.5 + cos(localT * 2.1) * 0.34, 0.5 + sin(localT * 2.1) * 0.34)
    draw.ring(c, min(bounds.w * scaleX, bounds.h * scaleY) * 0.32, color = "rgba(245, 202, 92, 0.9)", width = 2.0)
    draw.line(c, q, stroke = "rgba(245, 202, 92, 0.45)", width = 1.6)
    draw.circle(q, 7 + thisRMS * 14, fill = "rgba(245, 202, 92, 0.92)")

    if (onSubdivision(3, "bell"))
        sound("/dough/play/note/72/duration/0.12/s/sine/gain/0.15/lpf/2600/release/0.1/verb/0.42", minInterval = 0.08)
}
