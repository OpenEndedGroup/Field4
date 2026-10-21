// Conway's Game of Life: PREV() reads the grid as the last tick left it. Only cells that change
// are SET, so the sound marks the births.
charGrid(glyphs = " █", sounds = listOf("", "/dough/play/note/57/duration/0.1/s/tri/fm/1.5/fmd/0.08/lpf/500/lpe/2/lpd/0.06/release/0.05/verb/0.2/chorus/0.1")) {
    if (frame == 0) {
        clearGrid()
        for (y in 0 until ROWS) for (x in 0 until COLS) if (rand() < 0.35) SET(x, y, 1, "#50fa7b")
        return@charGrid // the next tick reads this seed through PREV
    }
    for (y in 0 until ROWS) {
        for (x in 0 until COLS) {
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                if ((dx != 0 || dy != 0) && PREV((x + dx + COLS) % COLS, (y + dy + ROWS) % ROWS) > 0) n++
            }
            val alive = PREV(x, y) > 0
            val next = if (alive) n == 2 || n == 3 else n == 3
            val g = if (next) 1 else 0
            if (g != GET(x, y)) SET(x, y, g, if (next) "#50fa7b" else "")
        }
    }
}
