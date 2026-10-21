// Philip Glass: minimalist additive arpeggios, and here the glyphs are notes. The eight bars of the
// charset are A minor from E2 to E4, so a taller bar is a higher note. Stream mode: each SET
// waits a tick and two waitFrame()s hold the note, three ticks a note.
val notes = listOf(40, 45, 48, 52, 55, 57, 60, 64)
val cutoffs = listOf(500, 550, 600, 650, 700, 750, 800, 900)
val sounds = notes.indices.map { i ->
    "/dough/play/note/${notes[i]}/duration/0.4/s/saw/attack/0.02/decay/0.15/sustain/0.3/release/0.2" +
        "/vib/4/vibmod/0.15/lpf/${cutoffs[i]}/lpe/2/lpd/0.25/lpq/0.15/verb/0.3/verbdecay/0.5/chorus/0.05"
}
val patterns = listOf(
    listOf(0, 2, 4, 5, 4, 2),       // i: A minor, low
    listOf(1, 3, 5, 6, 5, 3),       // ii: rising
    listOf(0, 2, 4, 6, 7, 6, 4, 2), // iii: the full sweep
    listOf(2, 4, 5, 7, 5, 4),       // iv: upper
    listOf(0, 3, 5, 7, 5, 3, 0, 2), // v: wide
    listOf(1, 2, 4, 5, 7, 5, 4, 2), // vi: opening
)

charStream(glyphs = "▁▂▃▄▅▆▇█", sounds = sounds) {
    clearGrid()
    var section = 0
    while (true) {
        val pattern = patterns[section % patterns.size]
        repeat(2) {
            for (g in pattern) {
                SET(g, "hsl(${(section * 35 + g * 20) % 360},50%,40%)")
                waitFrame()
                waitFrame()
            }
        }
        section++
    }
}
