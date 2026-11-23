run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }
    val sheet = fromContext<PaintStrokes>()
    // one key per frame: how far the leaves sway and the legs step; every frame is the same records, moved
    val keys = listOf(0.0, 30.0, 0.0, -30.0)
    val frames = keys.map { k ->
        val f = sheet.copy()
        f.apply("""[{"op": "move", "at": "@leaves", "dx": $k},
                    {"op": "bend", "at": "@mouth", "dy": ${abs(k) / 2}},
                    {"op": "move", "at": "@legs", "dy": ${-abs(k) / 3}}]""")
        f.render()
    }
    display("main", pngRow(frames))
}
