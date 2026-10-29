// Per-cell rotation from a wave, plus one CSS variable that every cell adds to its own angle, so the
// whole field spins with a single CSSVAR call a tick.
val notes = [60, 62, 64, 65, 67, 69, 71, 72, 74, 76, 77, 79]
val sounds = notes.mapIndexed { i, note ->
    "/dough/play/note/$note/duration/0.06/s/sine/lpf/${2000 + i * 200}/release/0.03/verb/0.3"
}

charGrid(glyphs = "|/-\\+×•◦○●★✶", sounds = sounds) {
    CSSVAR("--spin", "${frame * 6 % 360}deg")
    for (y in 0 until ROWS) {
        for (x in 0 until COLS) {
            val t = frame * 0.04
            val wave = sin(x * 0.4 + t) * cos(y * 0.3 + t * 0.6)
            val g = ((wave + 1) / 2 * glyphs.length).toInt()
            val hue = (x * 12 + y * 8 + frame * 3) % 360
            SET(
                x,
                y,
                g,
                "hsl($hue,70%,50%)",
                "transform:rotate(calc(${(wave * 180).roundToInt()}deg + var(--spin,0deg)))",
            )
        }
    }
}
