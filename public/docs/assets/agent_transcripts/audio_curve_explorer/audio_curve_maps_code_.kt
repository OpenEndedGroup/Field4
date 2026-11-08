val wav = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
// A sheet of 12 × 12 prototypes and a lattice of 6 × 6 × 6, each learning with a 0.5 s time constant.
val sheet = audioCurves(wav, AudioCurveSettings(dimensions = 3, somLearnSeconds = 0.5, somGrid = [12, 12])).first()
val lattice = audioCurves(wav, AudioCurveSettings(dimensions = 3, somLearnSeconds = 0.5, somGrid = [6, 6, 6])).first()

// Start six seconds in, so a learned map shows before you press play.
var t = 6.0
displayAudioBytes(
    wav.toFile().readBytes(),
    options = AudioVisualizerOptions(initialPositionSeconds = t, onTime = { t = it }),
)

// A faint room around each map, so the view holds still while the maps turn.
fun room(cx: Double) = fline {
    for (z in [-80.0, 80.0]) {
        moveTo(cx - 80, -80.0, z)
        lineTo(cx + 80, -80.0, z)
        lineTo(cx + 80, 80.0, z)
        lineTo(cx - 80, 80.0, z)
        lineTo(cx - 80, -80.0, z)
    }
    for (x in [cx - 80, cx + 80]) for (y in [-80.0, 80.0]) {
        moveTo(x, y, -80.0)
        lineTo(x, y, 80.0)
    }
    color = vec(1, 1, 1, 0.1)
}
val rooms = [room(-100.0), room(100.0)]

// Turn a map slowly about the vertical axis as the sound plays, then move it into its room.
fun place(lines: List<FLine>, turn: Double, cx: Double) = lines.map { line ->
    line.transform { p -> vec(p.x * cos(turn) + p.z * sin(turn) + cx, p.y, -p.x * sin(turn) + p.z * cos(turn)) }
}

var shown = Double.NaN
var first = true
loop {
    val a = sheet.frameAt(t) ?: sheet.frame(0)
    if (a.timeSeconds != shown) {
        shown = a.timeSeconds
        val b = lattice.frameAt(t) ?: lattice.frame(0)
        val turn = 0.3 * t
        val left = place(
            a.flines(55 / a.spread(), centered = true, stretch = true, trail = sheet.recentMatches(t, 0.5)),
            turn,
            -100.0,
        )
        val right = place(
            b.flines(55 / b.spread(), centered = true, stretch = true, trail = lattice.recentMatches(t, 0.5)),
            turn,
            100.0,
        )
        // Frame the view once; after that the rooms hold it still while the maps turn.
        threeLines(left + right + rooms, channel = "maps", height = 560, autoFrame = first)
        first = false
    }
}
    .end {
        sheet.close()
        lattice.close()
    }
