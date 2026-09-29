// Eight beats across, MIDI 84 at the top down to 48 at the bottom.
val score = notation.space("score", x = 0.0..8.0, y = 84.0..48.0)
println(score.notes().joinToString("\n") { n ->
    "beat %.3f to %.3f: MIDI %.2f (%s)".format(n.start, n.end, n.pitch, n.event.mark.label ?: n.event.mark.kind.name.lowercase())
})
