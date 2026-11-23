run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }
    val original = fromContext<PaintStrokes>()
    // the sheet the AI edits is kept on the box, so re-running the box keeps its edits
    val sheet = thisBox.properties.getOrPut("strokes.sheet") {
        original.copy().also { it.pin(660.0, 430.0, "more moss on this side") }
    } as PaintStrokes

    fun show() {
        display("main", pngRow([original.render(), sheet.render()]))
        markdown(
            "status",
            "${sheet.strokes.size} strokes, ${sheet.notes.size} notes " + sheet.notes.joinToString { "\"${it.text}\"" },
        )
    }

    // the strokes_* tools: open this box's AI panel and ask it to act on the note
    agentExtension("strokes") { strokeTools(sheet = { sheet }, changed = { show() }) }
    show()
}
