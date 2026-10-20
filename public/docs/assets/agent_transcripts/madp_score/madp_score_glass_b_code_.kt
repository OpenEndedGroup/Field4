// The same as the box above, a fifth higher and a little narrower.
score {
    for (i in 0 until 8) {
        val u = i / 7.0
        val p = boxPoint(u, 0.5 + sin(localT * 2 + i * 0.4) * 0.06)
        draw.line(p.x, boxPoint(u, 0.2).y, p.x, boxPoint(u, 0.8).y, stroke = "rgba(174, 246, 255, 0.8)", width = 1.2 + thisRMS * 2)
    }
    if (onSubdivision(6, "phase")) sound("/dough/play/note/67/duration/0.1/s/tri/gain/0.15/lpf/1200/release/0.08/verb/0.26/pan/0.62", minInterval = 0.05)
}
