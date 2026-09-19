// The other half of the same property. A note used to be one unwrapped line; this one
// is 142 characters, and it is verbatim the note the tetgen corner figure shipped with
// before someone shortened it by hand to work around exactly this.
val note = "a 0.24 x 0.24 window on a body 2 x 3 across. The true corner is at " +
    "(1.0, 1.5); every level set cuts it off, and halving h halves what is left."

fun figure(clip: Boolean) =
    Chart(title = "a long note", xLabel = "x", yLabel = "y", note = note)
        .line("ramp", (0..10).toList(), (0..10).toList())
        .also { it.width = 780.0; it.height = 440.0; it.clip = clip }

showChart(figure(clip = true))

// Flip the argument to `false` and re-run: the note goes back to one line running off
// the right edge, and the whole figure shrinks to fit beside it.
listOf(true, false).forEach { on ->
    val ps = figure(on).toFLines().flatMap { l -> l.nodes.mapNotNull { it.toOrNull } }
    val w = ps.maxOf { it.x } - ps.minOf { it.x }
    println("clip = %-5s  figure %4.0f px wide in a 780 px frame  ->  %3.0f%%"
        .format(on, w, 100.0 * minOf(1.0, 780.0 / w)))
}
