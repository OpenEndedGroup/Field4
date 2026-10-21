# charGrid

**charGrid** is generative text art in the spirit of *10 PRINT CHR$(205.5+RND(1)); : GOTO 10*, the
one-line Commodore 64 program that draws an endless maze out of two diagonal characters. Here a
picture is a **grid of glyph indices**: a program writes numbers into a 40 × 25 grid, and a charset
string turns each number into a character. A cell can also carry a CSS colour and raw per-cell CSS
(a `transform:rotate(…)`, say), and every glyph can be a **sound**, so the picture plays.

This box is the demo's default program, a plasma: two sine waves pick a glyph from the charset
`░▒▓█╔╗╚╝║═` for every cell, a hue runs diagonally across the grid, and each cell is rotated a
little.

## How to play

- **Run** this box to start the grid in the panel below; **stop** it to stop. Edit and run again.
- Change `glyphs` to change the whole picture's texture without touching the program.
- Change a path in `sounds`: glyph `i` plays `sounds[i]` every time it is `SET`.
- Try `stretch = false` (charGrid 2, square cells), `cols = 80, rows = 50`, or `fps = 8.0`.

## The two time models

- `charGrid { }` is **frame** mode: the block runs once a tick (24 a second), and `PREV(x, y)`
  reads the grid as the last tick left it. Neighbourhood rules such as Life need nothing else.
- `charStream { }` is **stream** mode: one long program in which every `SET` waits for the next
  tick, so `while (true) SET(…)` types a cell at a time (see **10 PRINT** and **C64 typewriter**).

## The vocabulary

| | |
| --- | --- |
| `SET(x, y, g, color, style)` | glyph index `g` at (x, y); colour and style optional |
| `SET(g, color, style)` | the same at the cursor, which wraps and scrolls |
| `GET(x, y)`, `PREV(x, y)`, `PREVC(x, y)` | this tick's glyph; last tick's glyph and colour |
| `setText`, `print`, `newLine`, `CURSOR`, `getCursor` | text, at a place or at the cursor |
| `glyphIndex(ch)`, `clearGrid()`, `CSSVAR(name, value)` | lookup, wipe, a CSS variable every cell can use |
| `frame`, `COLS`, `ROWS`, `glyphs`, `rand()` | the tick, the size, the charset, a random number |

## From the madp demo

A port of aaaframe's `madp/chargrid.html` (and `index2.html`, charGrid 2, which is the same program
without the 1.7× horizontal stretch). The JavaScript program becomes Kotlin in this box; the grid is
drawn as the demo draws it, Courier New on near-black, in a panel that receives only the cells that
changed. The demo's sound panel becomes the `sounds` list (same dough paths, same `dough.wasm`), and
its `${expr}` paths become a `sound = { … }` lambda over `N`, `x`, `y`, `frame`. In frame mode a
tick's SETs of one sound play together at the demo's auto-gain. The demo's `state` object is a
`val` above the call (see **matrix rain**).

Not carried over: the Yjs `?room=` collaboration and the AI ghost completion (Field's own
completion and AI chat stand in). The demo's own Life preset draws nothing, because it seeds and then
judges that seed against an empty `PREV` in the same frame; the **Life** box here seeds, then waits a
tick. Design notes: `dev_docs/madp/chargrid.md`.
