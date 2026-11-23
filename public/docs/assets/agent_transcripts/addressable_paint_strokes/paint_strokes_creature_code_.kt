// points around an ellipse from angle a0 to a1 (radians), as [x0, y0, x1, y1, ...]
fun arc(cx: Double, cy: Double, rx: Double, ry: Double, a0: Double, a1: Double, n: Int = 24) = (0..n).flatMap {
    val a = a0 + (a1 - a0) * it / n
    listOf(cx + rx * cos(a), cy + ry * sin(a))
}

val sheet = PaintStrokes(700, aspect = 1.4, seed = 3)
// the body: rows of a hog flat, filling an ellipse
for (i in -5..5) {
    val y = 400.0 + i * 26
    val w = 190 * sqrt(1 - (i / 6.0).pow(2))
    sheet.add(
        listOf(500 - w, y, 500 + w, y + 6),
        color = if (i % 2 == 0) "#8a9a3a" else "#a0a848",
        brush = BrushKind.HogFlat,
        width = 34.0,
        group = "body",
    )
}
// legs, and leaves sprouting from the head
sheet.add(
    listOf(430.0, 540.0, 420.0, 640.0),
    color = "#4a3a20",
    brush = BrushKind.HogFlat,
    width = 26.0,
    group = "legs",
)
sheet.add(
    listOf(570.0, 540.0, 585.0, 640.0),
    color = "#4a3a20",
    brush = BrushKind.HogFlat,
    width = 26.0,
    group = "legs",
)
for (a in listOf(-2.3, -1.9, -1.57, -1.25, -0.85)) {
    val x = 500 + 60 * cos(a)
    val y = 290 + 30 * sin(a)
    sheet.add(
        listOf(x, y, x + 60 * cos(a) - 12, y + 60 * sin(a), x + 120 * cos(a), y + 110 * sin(a)),
        color = "#2f6a2a",
        brush = BrushKind.Filbert,
        width = 30.0,
        pressure = 0.9 to 0.4,
        group = "leaves",
    )
}
// eyes, a smiling mouth and a pink smudge on one cheek
for (x in listOf(440.0, 560.0)) {
    sheet.add(
        listOf(x - 8, 360.0, x + 8, 362.0),
        color = "#f4f0e4",
        width = 44.0,
        pressure = 1.0 to 1.0,
        group = "eyes",
    )
    sheet.add(listOf(x, 364.0, x + 8, 365.0), color = "#1a1a1a", width = 16.0, pressure = 1.0 to 1.0, group = "eyes")
}
sheet.add(arc(500.0, 420.0, 70.0, 45.0, 0.4, PI - 0.4), color = "#3a1a10", width = 10.0, group = "mouth")
sheet.add(
    listOf(400.0, 440.0, 410.0, 445.0),
    color = "#e07080",
    brush = BrushKind.Badger,
    width = 50.0,
    hiding = 0.6,
    group = "smudge",
)

// the records need no paint library; painting them does
if (PaintCanvas.isAvailable) display("main", sheet.png()) else markdown(PaintLibrary.describe())
println(sheet.describe())
