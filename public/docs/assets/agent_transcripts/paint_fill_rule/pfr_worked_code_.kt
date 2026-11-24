import afield.graphics.nile.*

run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }

    val canvas = PaintCanvas(900, aspect = 2.0, sizeMm = 300.0)

    fun squares(dx: Double, rule: FillRule) =
        FLine().rect(dx + 60.0, 60.0, 220.0, 220.0).rect(dx + 180.0, 180.0, 220.0, 220.0).also { it.fillRule = rule }

    canvas.work(squares(0.0, FillRule.NON_ZERO), rgb(0.2, 0.4, 0.6), Handling.Body, angle = 0.5, clip = true)
    canvas.work(squares(500.0, FillRule.EVEN_ODD), rgb(0.2, 0.4, 0.6), Handling.Body, angle = 0.5, clip = true)

    display("main", canvas.png())
}
