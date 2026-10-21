# charGrid → Field·4: port design

Source: `~/temp/aaaframe/madp/chargrid.html` (served at `:9982/chargrid.html`); `index2.html`
("charGrid 2") is the same program without the `transform: scale(1.7, 1)` on the grid, so it is a
`stretch = false` option here, not a second port. The demo is generative text art in the spirit of
10 PRINT: a student's program writes **glyph indices** into a 40 × 25 grid, a charset string turns
each index into a character, and each cell can carry a CSS colour and raw CSS (`transform:rotate`).
Every glyph index can also be a dough sound, so the picture plays.

## The Field idea

The program is the box's code, in Kotlin; the grid is an output panel. Running the box starts the
grid, stopping it stops it, re-running replaces it, the way any `loop { }` box behaves. The panel
is an iframe (`/chargrid_view.html`) that draws the grid as the demo does, a CSS grid of spans in
Courier New 16 px, `#a0a0a0` on `#080808`; nothing a student writes runs in it.

Two builders carry the demo's two time models:

```kotlin
// frame mode: the block runs once per tick, with PREV() holding the last tick's grid
charGrid(glyphs = "░▒▓█╔╗╚╝║═") {
    for (y in 0 until ROWS) for (x in 0 until COLS) SET(x, y, (x + y + frame) % glyphs.length, "#50fa7b")
}

// stream mode: one long program; every SET waits for the next tick, as in the demo
charStream(glyphs = "╱╲") {
    clearGrid()
    while (true) SET(if (rand() < 0.5) 0 else 1, "#50fa7b")
}
```

`charStream`'s block is a `suspend` lambda, so `SET` and `waitFrame()` suspend to the next tick and
`while (true)` reads exactly as the demo's `async` program does, with no `await` to write (the demo
rewrites `SET(` to `await SET(` with a regex for the same effect).

## API mapping

| chargrid.html | Field |
| --- | --- |
| editor + run/loop buttons | the box's code; running the box loops it, stopping stops it |
| mode select: frame / stream | `charGrid { }` / `charStream { }` |
| fps, cols, rows inputs (24, 40 × 25, max 200 × 100) | `fps = 24.0`, `cols = 40`, `rows = 25` arguments (clamped to 1..200 × 1..100) |
| glyphs input | `glyphs = "…"` argument; `glyphs` in the block |
| `COLS`, `ROWS`, `frame` | the same names on the receiver |
| `SET(g[, color[, style]])` at the cursor | `SET(g, color = null, style = null)`; wraps, scrolls at the bottom |
| `SET(x, y, g[, color[, style]])` | `SET(x, y, g, color = null, style = null)` (out of range does nothing) |
| colour sticks when omitted, style clears when omitted | the same (`null` colour keeps the cell's; `null` style clears it) |
| `GET(x, y)`, `PREV(x, y)`, `PREVC(x, y)` | the same; 0 / `""` outside the grid |
| `setText(x, y, str, color)`, `print(str, color)`, `newLine()` | the same; in stream mode these write at once and do not wait, as in the demo |
| `clearGrid()`, `glyphIndex(ch)` | the same |
| `CURSOR(x, y)`, `getCursor()` | `CURSOR(x, y)`, `getCursor()` returning `CharGridCursor(x, y)` |
| `waitFrame()` (stream) | `suspend fun waitFrame(): Int` |
| `CSSVAR(name, value)` | the same; `null` removes it |
| `state` | dropped: a `val`/`var` above the builder lives as long as the run (as Score did) |
| `Math.random()` | `rand()` on the receiver, or Kotlin's `Math.random()` |
| sound panel: per-glyph path, `${expr}` over `N x y frame` | `sounds = listOf("/dough/…", "", …)` per glyph ("" is silent), or `sound = { "/dough/play/note/${55 + y}/…" }`, a lambda over `N`, `x`, `y`, `frame` returning a path or `null` |
| frame-mode auto-gain `1/(enabled·√(COLS·ROWS))` | the same factor (see Sound) |
| `/dough/reset` on stop | the box's voices end with their envelopes |
| stretch `scale(1.7, 1)` (charGrid) vs none (charGrid 2) | `stretch = true` (default) / `stretch = false` |

The receiver keeps the demo's capitalised verbs (`SET`, `GET`, `PREV`) because they are the
vocabulary the teaching uses; everything else is ordinary Kotlin (named arguments, `null` for
"not given", `Int` coordinates).

## Shared code

`afield.ui.output.CharGrid` (`src/jvmMain/kotlin/afield/ui/output/CharGrid.kt`):

1. **`CharGridBuffer`** — the grid model with no Field dependencies, so it is unit-tested directly:
   glyph/colour/style per cell, the PREV snapshot, the cursor with wrap and scroll, CSS variables,
   and the wire encoder below.
2. **`CharGridFrame` / `CharGridStream`** — the two receivers over a shared `CharGridScope`.
3. **`Box.charGrid` / `Box.charStream`** — build the buffer, bind the panel's requests
   (`outputRequests`, as `playScore` does), display the iframe, and return the `loop { }` that ticks
   at `fps` by `AnimationClock`, so a recorded video plays at the right speed.
4. **`chargrid_view.html`** (`src/commonMain/resources/`) — the panel.

### Diffs over the wire

The panel pulls, one request in flight at a time, so a slow panel never builds a backlog. Every
cell carries the version at which it last changed, and a SET that writes what is already there
changes nothing. A request says which version the panel has (`v`) and how many colour and style
strings it already knows (`nc`, `ns`); the reply carries only the cells changed since `v`, as flat
`[index, glyph, colour id, style id, …]` integers, plus any new colour and style strings. Colours
and styles are interned per grid, so a plasma frame of 1,000 cells sends 4,000 small integers and
no strings once its palette is known; a life frame sends only the births and deaths. When the
string tables pass 4,096 entries they are rebuilt from the cells in use and the panel gets one full
frame (a new `epoch`). Blank cells (never set since `clearGrid`) travel as glyph `-1`.

## Sound

Every SET of a glyph with a sound plays it, through the shared box-level `Box.sound` (Score's
`DoughLive`, the same `dough.wasm` as the demo, so the paths are the demo's verbatim).

- **Stream mode** plays each SET as it happens (one SET a tick), keyed by glyph, so the composer
  presets are the demo note for note.
- **Frame mode** can SET a thousand cells a tick. The demo starts a voice for every one and scales
  them by `1/(enabled·√(COLS·ROWS))`. Field collects the tick's SETs by resolved path and plays each
  distinct path once, at that same gain times `√count` (the level of `count` uncorrelated voices),
  at most 24 distinct paths a tick (a random subset beyond that, scaled up to keep the level). A path
  that sets its own `/gain/` is played as written, once a tick, as the demo leaves those alone too.

## Starting point

`dev_docs/agents/transcripts/madp_chargrid.yaml` — a lead box `charGrid · plasma` (the demo's
default program and sounds) and presets spanning both modes: `10 print` (stream), `life` (frame,
PREV), `rain` (frame, per-run state), `glass` (stream, a composer piece where glyphs are notes),
`spinners` (frame, `CSSVAR` + per-cell rotation), `typewriter` (stream, `print`/`newLine`).

## What is not carried over, and why

- **Yjs `?room=` collaboration** — out of scope for every madp port (PLAN.md); a gap.
- **AI ghost completion** — Field's own completion and AI4 chat cover it.
- **local-saves.js** — Field documents and history.
- **The editable sound panel** — the glyph → path table is code (`sounds = listOf(…)`), so it lives
  in the box with the program and is saved with the document.
- **The demo's `life` preset bug** — the demo seeds on frame 0 and then, in the same frame,
  computes the next generation from an all-zero PREV, so every seeded cell dies and the grid stays
  black (checked in the browser). The port seeds and returns, so the next frame reads the seed.
- **`streamCalls` forced yield** — the demo forces a yield after `COLS·ROWS` SETs in a frame, but it
  drops the SET that triggers it; in stream mode every SET waits anyway, so the port omits it.
