// Norman McLaren scratched straight onto film: nine lines that twitch every frame, a grain of
// noise seven times on the way across, and a click on the way in.
score {
    for (i in 0 until 9) {
        val u = i / 8.0
        val a = boxPoint(0.0, u)
        val b = boxPoint(1.0, 1 - u)
        val kick = sin(localT * 26 + 0.1 + i * 0.75) * (10 + thisRMS * 24)
        draw.line(a.x, a.y + kick, b.x, b.y - kick, stroke = "rgba(255,255,255,${0.12 + u * 0.62})", width = 1 + u * 1.2)
    }
    draw.circle(boxPoint(0.5 + sin(localT * 18 + 0.1) * 0.2, 0.5), 4 + thisRMS * 11, fill = "rgba(255, 107, 107, 0.88)")

    if (onSubdivision(7, "grain")) sound("/dough/play/s/white/duration/0.03/gain/0.11/lpf/5200/release/0.02/pan/0.45", minInterval = 0.035)
    if (justEntered) sound("/dough/play/note/72/duration/0.04/s/pulse/gain/0.12/lpf/1800/release/0.03")
}
