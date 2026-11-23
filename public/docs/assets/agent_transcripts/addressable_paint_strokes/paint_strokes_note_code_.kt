run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }
    val sheet = fromContext<PaintStrokes>().copy()
    // the human pins a note; the agent reads it with the strokes under it
    sheet.pin(660.0, 430.0, "more moss on this side")
    println(sheet.describe().lines().first())

    // the batch the agent answers with: darken the strokes under the note, and add moss along that flank
    val under = sheet.near(660.0, 430.0, radius = 40.0)
    val moss =
        (0 until 9).map { k ->
            val y = 330.0 + k * 22
            val x = 640.0 + 40 * sqrt(1 - ((y - 420) / 120).pow(2))
            """{"op": "add", "points": [$x, $y, ${x + 18}, ${y + 6}], "color": "#4a6a28", "brush": "Fan", "width": 28, "group": "moss"}"""
        }
    sheet.apply(
        """[{"op": "recolor", "at": "${under.joinToString(",")}", "color": "#6a8a30"}, ${moss.joinToString(",")}]""",
    )
    println("recoloured ${under.joinToString(", ")}; added ${sheet.select("@moss").joinToString(", ")}")
    display("main", pngRow(listOf(fromContext<PaintStrokes>().render(), sheet.render())))
}
