// Each track rendered alone, its onsets and pitches found; the wave's heard transposition.
val run = StrokePlayheadsDocs.run(StrokePlayheadsDocs.readStrokes(thisBox))
val heard = StrokePlayheadsDocs.listen(run)
val shift = StrokePlayheadsDocs.heardTranspositions(run, heard.first { it.track == "wave" })
showSignals(
    "heard transposition" to shift,
    x = run.notes.filter { it.track == "wave" }.map { it.start },
    marks = run.meetings.map { "%s/%s".format(it.a, it.b) to it.beat },
    title = "wave: transposition heard, meetings marked",
    xTitle = "beats",
    yTitle = "semitones",
)
println(heard.joinToString("\n") { it.describe() })
