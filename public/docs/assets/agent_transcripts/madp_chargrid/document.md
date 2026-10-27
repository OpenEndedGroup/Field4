# Field Document: madp_chargrid

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Life

<!-- field-box id="madp_chargrid_life" index="0" -->

### Narrative

From `notes`

Life

From `code`

Conway's Game of Life: PREV() reads the grid as the last tick left it. Only cells that change
are SET, so the sound marks the births.

```edn [field-box id="madp_chargrid_life" role="properties"]
{"id" "madp_chargrid_life" "index" 0 "name" "Life" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Life" "_tombstone" false "frame" #afield/rect [1240.0 60.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_chargrid_life_notes_.md" "lines" 6 "characters" 375 "sha256" "ccb1e5853e7323f9fcda8dca7c68b559242ca0ee21d9f9603f7cbd9a796d4f5f"} {"name" "code" "language" "kotlin" "path" "madp_chargrid_life_code_.kt" "lines" 27 "characters" 1035 "sha256" "d3ce46106bc1e057b8691ba982c130c2ee3ab9dbe8ff28a1e7a55f206ecb3ac6"}]}
```

```markdown [field-property box="madp_chargrid_life" name="notes" path="madp_chargrid_life_notes_.md"]
# Life

Conway's Game of Life in **frame** mode. `PREV(x, y)` is the grid as the last tick left it, so the
rule reads only the past and writes the present. Only cells that change are `SET`, so the note marks
every birth. The first tick seeds a random 35 % and returns, so the second tick reads that seed;
the madp original judged its seed in the same frame and drew nothing.
```

```kotlin [field-property box="madp_chargrid_life" name="code" path="madp_chargrid_life_code_.kt"]
// Conway's Game of Life: PREV() reads the grid as the last tick left it. Only cells that change
// are SET, so the sound marks the births.
charGrid(
    glyphs = " █",
    sounds = [
        "",
        "/dough/play/note/57/duration/0.1/s/tri/fm/1.5/fmd/0.08/lpf/500/lpe/2/lpd/0.06/release/0.05/verb/0.2/chorus/0.1",
    ],
) {
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
```

## 2. C64 typewriter

<!-- field-box id="madp_chargrid_typewriter" index="1" -->

### Narrative

From `notes`

C64 typewriter

From `code`

A C64 boot screen typed out in stream mode, then 10 PRINT forever. type() is an ordinary local
suspend function: SET waits a tick, and `delay` more waitFrame()s slow the typing down.

```edn [field-box id="madp_chargrid_typewriter" role="properties"]
{"id" "madp_chargrid_typewriter" "index" 1 "name" "C64 typewriter" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "C64 typewriter" "_tombstone" false "frame" #afield/rect [40.0 1260.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_chargrid_typewriter_notes_.md" "lines" 6 "characters" 333 "sha256" "b8e3b88e9ca3d4239e781126b5934840aaef9e82efc7aba357002d3cab99313c"} {"name" "code" "language" "kotlin" "path" "madp_chargrid_typewriter_code_.kt" "lines" 29 "characters" 1004 "sha256" "3aba7e8772889f2c20b130291527fde0376c011a1e39340d7dc4d7e07d03bd08"}]}
```

```markdown [field-property box="madp_chargrid_typewriter" name="notes" path="madp_chargrid_typewriter_notes_.md"]
# C64 typewriter

A Commodore 64 boot screen typed in **stream** mode, then 10 PRINT forever. `type()` is an ordinary
local `suspend fun`: `SET` waits a tick and `delay` more `waitFrame()`s slow it down, while
`print` and `setText` write at once. The charset is printable ASCII (`CHARGRID_ASCII`), so
`glyphIndex(ch)` finds any key.
```

```kotlin [field-property box="madp_chargrid_typewriter" name="code" path="madp_chargrid_typewriter_code_.kt"]
// A C64 boot screen typed out in stream mode, then 10 PRINT forever. type() is an ordinary local
// suspend function: SET waits a tick, and `delay` more waitFrame()s slow the typing down.
charStream(glyphs = CHARGRID_ASCII) {
    clearGrid()

    suspend fun type(text: String, color: String? = null, delay: Int = 1) {
        for (ch in text) {
            if (ch == '\n') newLine() else SET(glyphIndex(ch), color)
            repeat(delay) { waitFrame() }
        }
    }

    type("**** CHARGRID 64 BASIC V2 ****\n", "#6a9fdb", 2)
    type("\n")
    type("40K RAM SYSTEM  38911 BASIC BYTES FREE\n", "#6a9fdb", 2)
    type("\nREADY.\n", "#50fa7b", 2)
    repeat(30) { waitFrame() }

    type("> 10 PRINT CHR$(205.5+RND(1));\n")
    type("> 20 GOTO 10\n")
    type("> RUN\n")
    repeat(12) { waitFrame() }

    while (true) {
        val spin = if (rand() < 0.5) "transform:rotate(45deg)" else null
        SET(glyphIndex(if (rand() < 0.5) '/' else '\\'), "#50fa7b", spin)
        waitFrame()
    }
}
```

## 3. Glass arpeggios

<!-- field-box id="madp_chargrid_glass" index="2" -->

### Narrative

From `notes`

Glass arpeggios

From `code`

Philip Glass: minimalist additive arpeggios, and here the glyphs are notes. The eight bars of the
charset are A minor from E2 to E4, so a taller bar is a higher note. Stream mode: each SET
waits a tick and two waitFrame()s hold the note, three ticks a note.

```edn [field-box id="madp_chargrid_glass" role="properties"]
{"id" "madp_chargrid_glass" "index" 2 "name" "Glass arpeggios" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Glass arpeggios" "_tombstone" false "frame" #afield/rect [640.0 660.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_chargrid_glass_notes_.md" "lines" 6 "characters" 335 "sha256" "602e0119ba9e145803e83bff9fb6b41ccdd7f6fe43cb5f693b397ddf2d58431c"} {"name" "code" "language" "kotlin" "path" "madp_chargrid_glass_code_.kt" "lines" 36 "characters" 1299 "sha256" "9e473b13936f5b3da241b00f7fa8d4901046d44a36afb7a827ffe8ec7efab5fe"}]}
```

```markdown [field-property box="madp_chargrid_glass" name="notes" path="madp_chargrid_glass_notes_.md"]
# Glass arpeggios

After Philip Glass: additive arpeggios in A minor, and here **glyphs are notes**. The charset is
eight bars, `▁▂▃▄▅▆▇█`, tuned from E2 to E4, so a taller bar is a higher note and the grid is a
score you can read. Stream mode: each `SET` waits a tick and two `waitFrame()`s hold the note. Edit
`patterns` to compose.
```

```kotlin [field-property box="madp_chargrid_glass" name="code" path="madp_chargrid_glass_code_.kt"]
/*
Philip Glass: minimalist additive arpeggios, and here the glyphs are notes. The eight bars of the
charset are A minor from E2 to E4, so a taller bar is a higher note. Stream mode: each SET
waits a tick and two waitFrame()s hold the note, three ticks a note.
*/
val notes = [40, 45, 48, 52, 55, 57, 60, 64]
val cutoffs = [500, 550, 600, 650, 700, 750, 800, 900]
val sounds =
    notes.indices.map { i ->
        "/dough/play/note/${notes[i]}/duration/0.4/s/saw/attack/0.02/decay/0.15/sustain/0.3/release/0.2" +
            "/vib/4/vibmod/0.15/lpf/${cutoffs[i]}/lpe/2/lpd/0.25/lpq/0.15/verb/0.3/verbdecay/0.5/chorus/0.05"
    }
val patterns = [
    [0, 2, 4, 5, 4, 2], // i: A minor, low
    [1, 3, 5, 6, 5, 3], // ii: rising
    [0, 2, 4, 6, 7, 6, 4, 2], // iii: the full sweep
    [2, 4, 5, 7, 5, 4], // iv: upper
    [0, 3, 5, 7, 5, 3, 0, 2], // v: wide
    [1, 2, 4, 5, 7, 5, 4, 2], // vi: opening
]

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
```

## 4. spinners

<!-- field-box id="madp_chargrid_spinners" index="3" -->

### Narrative

From `notes`

spinners

From `code`

Per-cell rotation from a wave, plus one CSS variable that every cell adds to its own angle, so the
whole field spins with a single CSSVAR call a tick.

```edn [field-box id="madp_chargrid_spinners" role="properties"]
{"id" "madp_chargrid_spinners" "index" 3 "name" "spinners" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "spinners" "_tombstone" false "frame" #afield/rect [1240.0 660.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_chargrid_spinners_notes_.md" "lines" 5 "characters" 234 "sha256" "694bef82a31b3f2d1924c85dbfb5e931517ab6061e5c73c8968243c9a5cfae6e"} {"name" "code" "language" "kotlin" "path" "madp_chargrid_spinners_code_.kt" "lines" 25 "characters" 975 "sha256" "7abe87708cc46367b140f7ee0c5a62e02d5c22367618e7b89c8125c6255af571"}]}
```

```markdown [field-property box="madp_chargrid_spinners" name="notes" path="madp_chargrid_spinners_notes_.md"]
# spinners

Per-cell CSS. Every cell is rotated by a wave, and `CSSVAR("--spin", …)` sets one CSS variable that
every cell adds to its own angle, so a single call a tick spins the whole field. Twelve glyphs, twelve
rising sine notes.
```

```kotlin [field-property box="madp_chargrid_spinners" name="code" path="madp_chargrid_spinners_code_.kt"]
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
```

## 5. 10 PRINT

<!-- field-box id="madp_chargrid_10print" index="4" -->

### Narrative

From `notes`

10 PRINT

From `code`

10 PRINT CHR$(205.5+RND(1)); : GOTO 10
Stream mode: one long program, and every SET waits for the next tick, so the maze types itself
out a cell at a time and scrolls when it reaches the bottom. Each diagonal is a note.

```edn [field-box id="madp_chargrid_10print" role="properties"]
{"id" "madp_chargrid_10print" "index" 4 "name" "10 PRINT" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "10 PRINT" "_tombstone" false "frame" #afield/rect [640.0 60.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_chargrid_10print_notes_.md" "lines" 6 "characters" 396 "sha256" "6f11e20d19f1dbe4773e7af55b7f1db7a1ab4a0b59d56deca8733516d3c465dd"} {"name" "code" "language" "kotlin" "path" "madp_chargrid_10print_code_.kt" "lines" 16 "characters" 621 "sha256" "ed0746d2f82a65dc4653aac73a52b3863f2851117028daeb19007eaa93739dd5"}]}
```

```markdown [field-property box="madp_chargrid_10print" name="notes" path="madp_chargrid_10print_notes_.md"]
# 10 PRINT

The program the whole demo is named after: `10 PRINT CHR$(205.5+RND(1)); : GOTO 10` picks one of two
diagonals at random, forever. In **stream** mode every `SET` waits a tick, so the maze types itself
out one cell at a time and scrolls when it reaches the bottom. Each diagonal is its own pulse note,
so the maze is also a random two-note melody. Try a third glyph, or `fps = 120.0`.
```

```kotlin [field-property box="madp_chargrid_10print" name="code" path="madp_chargrid_10print_code_.kt"]
/*
10 PRINT CHR$(205.5+RND(1)); : GOTO 10
Stream mode: one long program, and every SET waits for the next tick, so the maze types itself
out a cell at a time and scrolls when it reaches the bottom. Each diagonal is a note.
*/
val sounds = [
    "/dough/play/note/64/duration/0.08/s/pulse/pw/0.35/lpf/700/lpe/3/lpd/0.05/release/0.04/delay/0.15/delaytime/0.06",
    "/dough/play/note/67/duration/0.08/s/pulse/pw/0.45/lpf/700/lpe/3/lpd/0.05/release/0.04/delay/0.15/delaytime/0.08",
]

charStream(glyphs = "╱╲", sounds = sounds) {
    clearGrid()
    while (true) {
        SET(if (rand() < 0.5) 0 else 1, "#50fa7b")
    }
}
```

## 6. matrix rain

<!-- field-box id="madp_chargrid_rain" index="5" -->

### Narrative

From `notes`

matrix rain

From `code`

Matrix rain. The drops are ordinary Kotlin values declared above charGrid { }, so they last as long
as the run (the demo keeps them in `state`). Each tick clears the grid and redraws every drop.

```edn [field-box id="madp_chargrid_rain" role="properties"]
{"id" "madp_chargrid_rain" "index" 5 "name" "matrix rain" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "matrix rain" "_tombstone" false "frame" #afield/rect [40.0 660.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_chargrid_rain_notes_.md" "lines" 6 "characters" 360 "sha256" "f54de1067ac0fb861c37a637cd7069cada09d11176c8d4f8e931f25fbe46a10b"} {"name" "code" "language" "kotlin" "path" "madp_chargrid_rain_code_.kt" "lines" 42 "characters" 2391 "sha256" "c060325b2bc9f30488903163b23954a6904f808e057d3a4b6fec2173434064d3"}]}
```

```markdown [field-property box="madp_chargrid_rain" name="notes" path="madp_chargrid_rain_notes_.md"]
# matrix rain

Falling columns of digits and symbols. The drops are ordinary Kotlin objects declared above
`charGrid { }`, so they last as long as the run: that is where the demo's `state` went. Every tick
clears the grid and redraws each drop, a white head over a green tail. The sixteen digits and
letters ring, high and bright; the nine symbols are silent.
```

```kotlin [field-property box="madp_chargrid_rain" name="code" path="madp_chargrid_rain_code_.kt"]
// Matrix rain. The drops are ordinary Kotlin values declared above charGrid { }, so they last as long
// as the run (the demo keeps them in `state`). Each tick clears the grid and redraws every drop.
class Drop(var y: Int, var len: Int)

val drops = mutableListOf<Drop>()
// The sixteen digits and letters ring, high and bright; the nine symbols are silent.
val sounds = [
    "/dough/play/note/72/duration/0.06/s/sine/fm/1/fmd/0.03/lpf/3000/release/0.03/verb/0.4/verbdecay/0.6/pan/0.3",
    "/dough/play/note/74/duration/0.06/s/sine/fm/1/fmd/0.03/lpf/3200/release/0.03/verb/0.4/pan/0.35",
    "/dough/play/note/76/duration/0.06/s/sine/fm/1.5/fmd/0.03/lpf/3400/release/0.03/verb/0.4/pan/0.4",
    "/dough/play/note/77/duration/0.06/s/tri/lpf/2800/release/0.03/verb/0.5/pan/0.45",
    "/dough/play/note/79/duration/0.06/s/tri/lpf/3000/release/0.03/verb/0.5/pan/0.5",
    "/dough/play/note/81/duration/0.06/s/tri/lpf/3200/release/0.03/verb/0.5/pan/0.55",
    "/dough/play/note/83/duration/0.05/s/sine/lpf/3500/release/0.02/verb/0.3/pan/0.6",
    "/dough/play/note/84/duration/0.05/s/sine/lpf/3700/release/0.02/verb/0.3/pan/0.65",
    "/dough/play/note/86/duration/0.05/s/sine/lpf/4000/release/0.02/verb/0.3/pan/0.7",
    "/dough/play/note/88/duration/0.04/s/sine/lpf/4500/release/0.02/delay/0.2/delaytime/0.08",
    "/dough/play/note/89/duration/0.04/s/sine/lpf/4500/release/0.02/delay/0.2/delaytime/0.06",
    "/dough/play/note/91/duration/0.04/s/sine/lpf/5000/release/0.02/delay/0.2/delaytime/0.05",
    "/dough/play/note/93/duration/0.04/s/sine/lpf/5000/release/0.02/delay/0.15",
    "/dough/play/note/95/duration/0.04/s/sine/lpf/5500/release/0.02/delay/0.15",
    "/dough/play/note/96/duration/0.04/s/sine/lpf/5500/release/0.02/delay/0.15",
    "/dough/play/note/98/duration/0.04/s/sine/lpf/6000/release/0.02/delay/0.1",
]

charGrid(glyphs = "0123456789ABCDEF:.*+=@#$%", sounds = sounds) {
    if (drops.isEmpty()) repeat(COLS) { drops += Drop(-rand(25), 5 + rand(14)) }
    clearGrid()
    for (x in 0 until COLS) {
        val d = drops[x]
        for (i in 0 until d.len) {
            val y = d.y - i
            if (y in 0 until ROWS)
                SET(x, y, rand(glyphs.length), if (i == 0) "#fff" else "hsl(120,80%,${max(15, 65 - i * 4)}%)")
        }
        d.y++
        if (d.y - d.len > ROWS) {
            d.y = -rand(15)
            d.len = 5 + rand(14)
        }
    }
}
```

## 7. charGrid · plasma

<!-- field-box id="madp_chargrid_plasma" index="6" -->

### Narrative

From `notes`

charGrid

From `code`

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

```edn [field-box id="madp_chargrid_plasma" role="properties"]
{"id" "madp_chargrid_plasma" "index" 6 "name" "charGrid · plasma" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "charGrid · plasma" "_tombstone" false "frame" #afield/rect [40.0 60.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_chargrid_plasma_notes_.md" "lines" 51 "characters" 3086 "sha256" "d87f038bcf0f9811c2af8ae06a3cb938b278cb8e937877348b56394aa90fee86"} {"name" "code" "language" "kotlin" "path" "madp_chargrid_plasma_code_.kt" "lines" 40 "characters" 2443 "sha256" "a43acc16d23d904c4a5aec7d90a650dd5b43a885c971430aa589cb6db0f56847"}]}
```

```markdown [field-property box="madp_chargrid_plasma" name="notes" path="madp_chargrid_plasma_notes_.md"]
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
```

```kotlin [field-property box="madp_chargrid_plasma" name="code" path="madp_chargrid_plasma_code_.kt"]
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
```

