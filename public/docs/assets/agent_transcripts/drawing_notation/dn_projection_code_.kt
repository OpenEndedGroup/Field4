// After Morton Feldman's Projection 1 (1950): time runs left to right; the grid's three rows
// are high, middle and low. Draw boxes (Alt+D, then R) in the grid, and this box reads them
// as events: when each starts, how long it lasts, which register it sits in.
notation("grid") {
    stroke = "#868e96"
    frame("projection", 100, 700, 960, 270)
    for (row in 1..2) line(listOf(vec(100, 700 + row * 90), vec(1060, 700 + row * 90)))
    rect(160, 715, 120, 60, fill = "#1971c2")                     // one to start with
}
val score = notation.space("projection", x = 0.0..16.0, y = 3.0..0.0)   // 16 beats; 3 rows
fun register(v: Double) = when { v >= 2 -> "high"; v >= 1 -> "middle"; else -> "low" }
fun read() = score.events.filter { it.mark.kind == MarkType.RECTANGLE }.forEach {
    println("beat %.1f for %.1f, %s".format(it.start, it.duration, register(it.value)))
}
read()
notation.onChange("projection") { read() }
