// Each named stroke is a track; the tracks change only where two playheads meet.
val strokes = notation.strokes.filter { it.name != null }.associate { it.name!! to it.points }
val tracks = StrokePlayheadsDocs.tracks(strokes) // the three tracks and their settings
val run = StrokePlayheads(tracks, MeetingRule.Crossing(windowBeats = 1.0), seed = 4).run(beats = 32.0)
notation("meetings") {
    stroke = "#f08c00"
    for (m in run.meetings) ellipse(m.where.x - 14.0, m.where.y - 14.0, 28.0, 28.0)
}
println(run.meetings.joinToString("\n") { "beat %.2f: %s meets %s".format(it.beat, it.a, it.b) })
