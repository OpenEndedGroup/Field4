/*
charGrid: a picture as a grid of glyph indices. Run this box and the panel below plays a plasma;
stop it to stop the grid, edit and run again to change it.

charGrid { } runs its block once a tick (24 a second), and inside it:
  SET(x, y, g, color, style)   glyph index g at (x, y); g is taken modulo the charset `glyphs`,
                               color is any CSS colour, style is raw per-cell CSS
  SET(g, color, style)         the same at the cursor, which wraps and scrolls
  GET(x, y)  PREV(x, y)  PREVC(x, y)   this tick's glyph, and the last tick's glyph and colour
  setText(x, y, str)  print(str)  newLine()  CURSOR(x, y)  glyphIndex(ch)  clearGrid()
  CSSVAR(name, value)          a CSS variable every cell can use in its style
  frame  COLS  ROWS  glyphs  rand()
charStream { } is the other time model: one long program in which every SET waits a tick (see
the 10 PRINT box). `sounds` gives each glyph index a dough path, so every SET of it plays.
*/
val sounds = [
    "/dough/play/note/48/duration/0.12/s/tri/fm/2/fmd/0.1/lpf/600/lpe/3/lpd/0.08/release/0.06/verb/0.3",
    "/dough/play/note/50/duration/0.12/s/tri/fm/2/fmd/0.1/lpf/700/lpe/3/lpd/0.08/release/0.06/verb/0.3",
    "/dough/play/note/53/duration/0.12/s/tri/fm/3/fmd/0.1/lpf/800/lpe/3/lpd/0.08/release/0.06/verb/0.3",
    "/dough/play/note/55/duration/0.15/s/tri/fm/3/fmd/0.12/lpf/900/lpe/3/lpd/0.1/release/0.08/verb/0.3",
    "/dough/play/note/57/duration/0.08/s/pulse/pw/0.3/lpf/500/lpe/2/lpd/0.06/release/0.04",
    "/dough/play/note/60/duration/0.08/s/pulse/pw/0.3/lpf/500/lpe/2/lpd/0.06/release/0.04",
    "/dough/play/note/62/duration/0.08/s/pulse/pw/0.3/lpf/500/lpe/2/lpd/0.06/release/0.04",
    "/dough/play/note/64/duration/0.08/s/pulse/pw/0.3/lpf/500/lpe/2/lpd/0.06/release/0.04",
    "/dough/play/note/65/duration/0.06/s/saw/lpf/400/lpe/4/lpd/0.05/release/0.03",
    "/dough/play/note/67/duration/0.06/s/saw/lpf/400/lpe/4/lpd/0.05/release/0.03",
]

charGrid(glyphs = "░▒▓█╔╗╚╝║═", sounds = sounds) {
    for (y in 0 until ROWS) {
        for (x in 0 until COLS) {
            val t = frame * 0.05
            val v = sin(x * 0.3 + t) + sin(y * 0.3 + t * 0.7)
            val i = ((v + 2) / 4 * glyphs.length).toInt()
            val hue = ((x + y) * 4 + frame) % 360
            val rot = sin(t + x * 0.5) * 30 + cos(t * 0.8 + y * 0.4) * 20
            SET(x, y, i, "hsl($hue,60%,45%)", "transform:rotate(${rot.roundToInt()}deg)")
        }
    }
}
