import afield.graphics.nile.*

run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }

    fun squares(k: Double, rule: FillRule) = FLine().rect(150.0 * k, 50.0 * k, 400.0 * k, 350.0 * k).rect(400.0 * k, 150.0 * k, 450.0 * k, 300.0 * k).also {
        it.fillRule = rule
    }

    for (rule in [FillRule.NON_ZERO, FillRule.EVEN_ODD]) {
        val canvas = PaintCanvas(400, aspect = 2.0)
        canvas.glaze(rgb(0.05, 0.1, 0.6), squares(1.0, rule), coats = 4.0, hiding = 1.0)
        val painted = canvas.render(wet = true).map { ((it shr 16) and 0xff) < 128 }
        val reference = squares(0.4, rule).also {
            it.filled = true
            it.stroked = false
        }
        val gezira = GeziraFLine.render([reference], 400, 200).canvas
        val differ = painted.indices.count { painted[it] != (gezira.alpha(it % 400, it / 400) > 0.5f) }
        println(
            "$rule: $differ of ${painted.size} pixels differ from Gezira; overlap painted: ${painted[110 * 400 + 190]}",
        )
    }
}
