run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }
    val sheet = paintSheet
    val edited = sheet.copy()
    edited.apply(
        """[
      {"op": "recolor", "at": "@leaves", "color": "#c0602a"},
      {"op": "bend", "at": "@mouth", "dy": -45},
      {"op": "move", "at": "@smudge", "dx": 200}
    ]""",
    )
    val before = sheet.render()
    val after = edited.render()
    // how much of the picture changed, and how much of that lies outside the edited strokes
    val a = before.render()
    val b = after.render()
    val w = before.widthPx
    val touched = edited.select("@leaves, @mouth, @smudge").flatMap { [sheet[it]!!.bounds(), edited[it]!!.bounds()] }
    fun inside(i: Int) = touched.any { r ->
        val x = (i % w) * 1000.0 / w
        val y = (i / w) * 1000.0 / w
        x in r[0] - 20..r[2] + 20 && y in r[1] - 20..r[3] + 20
    }
    val changed = a.indices.filter { a[it] != b[it] }
    println(
        "${changed.size} of ${a.size} pixels changed; ${changed.count { !inside(it) }} more than 20 units outside the edited strokes",
    )
    display("main", pngRow([before, after]))
}
