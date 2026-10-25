// 10 PRINT CHR$(205.5+RND(1)); : GOTO 10
// Stream mode: one long program, and every SET waits for the next tick, so the maze types itself
// out a cell at a time and scrolls when it reaches the bottom. Each diagonal is a note.
val sounds = listOf(
    "/dough/play/note/64/duration/0.08/s/pulse/pw/0.35/lpf/700/lpe/3/lpd/0.05/release/0.04/delay/0.15/delaytime/0.06",
    "/dough/play/note/67/duration/0.08/s/pulse/pw/0.45/lpf/700/lpe/3/lpd/0.05/release/0.04/delay/0.15/delaytime/0.08",
)

charStream(glyphs = "╱╲", sounds = sounds) {
    clearGrid()
    while (true) {
        SET(if (rand() < 0.5) 0 else 1, "#50fa7b")
    }
}
