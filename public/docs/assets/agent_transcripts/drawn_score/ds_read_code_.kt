// Eight beats across, MIDI 84 at the top down to 48 at the bottom: every mark as an event.
val score = notation.space("score", x = 0.0..8.0, y = 84.0..48.0)
println(score.events.joinToString("\n") { e ->
    "%s: beat %.3f to %.3f, MIDI %.2f to %.2f".format(e.mark.label ?: e.mark.kind.name.lowercase(), e.start, e.end, e.value, e.endValue)
})
