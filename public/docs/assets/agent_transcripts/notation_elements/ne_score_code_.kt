// A frame becomes a score: eight beats across, MIDI 72 at the top to 60 at the bottom.
// The notes are drawn inside the frame block, so they belong to the frame and move with it.
val frame =
    notation("sheet") {
            frame("score", 560, 180, 480, 240) {
                rect(620, 220, 120, 30, name = "C5", fill = "#1971c2")
                rect(740, 300, 180, 30, name = "E4", fill = "#2f9e44")
                stroke([vec(600, 400), vec(1000, 200)], name = "gliss", stroke = "#e03131")
            }
        }
        .first()
val s = notation.space(frame, x = 0.0..8.0, y = 72.0..60.0)
println("in the score: " + s.marks.map { it.label })
s.events.forEach { println("  $it") }
println("sounding at beat 3: " + s.sounding(3.0).map { it.mark.label })
println("starting in [1, 3): " + s.between(1.0, 3.0).map { it.mark.label })
println("cut at beat 4: " + s.vertical(4.0).map { "${it.mark.label} ${it.value.f1}" })
