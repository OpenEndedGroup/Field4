# Field Document: madp_chargrid

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Life

<!-- field-box id="madp_chargrid_life" index="0" -->

### Narrative

From `code`

Conway's Game of Life: PREV() reads the grid as the last tick left it. Only cells that change
are SET, so the sound marks the births.

```edn [field-box id="madp_chargrid_life" role="properties"]
{"id" "madp_chargrid_life" "index" 0 "name" "Life" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Life" "_tombstone" false "frame" #afield/rect [1240.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_chargrid_life_code_.kt" "lines" 21 "characters" 1007 "sha256" "29566b91141d87d1ccbd73577078dfb4a7a74fbc6b92bcc7dcb4ad7b603052d1"}]}
```

```kotlin [field-property box="madp_chargrid_life" name="code" path="madp_chargrid_life_code_.kt"]
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
```

## 2. C64 typewriter

<!-- field-box id="madp_chargrid_typewriter" index="1" -->

### Narrative

From `code`

A C64 boot screen typed out in stream mode, then 10 PRINT forever. type() is an ordinary local
suspend function: SET waits a tick, and `delay` more waitFrame()s slow the typing down.

```edn [field-box id="madp_chargrid_typewriter" role="properties"]
{"id" "madp_chargrid_typewriter" "index" 1 "name" "C64 typewriter" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "C64 typewriter" "_tombstone" false "frame" #afield/rect [40.0 1260.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_chargrid_typewriter_code_.kt" "lines" 29 "characters" 1004 "sha256" "3aba7e8772889f2c20b130291527fde0376c011a1e39340d7dc4d7e07d03bd08"}]}
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

From `code`

Philip Glass: minimalist additive arpeggios, and here the glyphs are notes. The eight bars of the
charset are A minor from E2 to E4, so a taller bar is a higher note. Stream mode: each SET
waits a tick and two waitFrame()s hold the note, three ticks a note.

```edn [field-box id="madp_chargrid_glass" role="properties"]
{"id" "madp_chargrid_glass" "index" 2 "name" "Glass arpeggios" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Glass arpeggios" "_tombstone" false "frame" #afield/rect [640.0 660.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_chargrid_glass_code_.kt" "lines" 33 "characters" 1358 "sha256" "4355c439735c54223d9664dc716e3827223b2e3f9453f425c72ba557cdd5e695"}]}
```

```kotlin [field-property box="madp_chargrid_glass" name="code" path="madp_chargrid_glass_code_.kt"]
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
```

## 4. spinners

<!-- field-box id="madp_chargrid_spinners" index="3" -->

### Narrative

From `code`

Per-cell rotation from a wave, plus one CSS variable that every cell adds to its own angle, so the
whole field spins with a single CSSVAR call a tick.

```edn [field-box id="madp_chargrid_spinners" role="properties"]
{"id" "madp_chargrid_spinners" "index" 3 "name" "spinners" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "spinners" "_tombstone" false "frame" #afield/rect [1240.0 660.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_chargrid_spinners_code_.kt" "lines" 17 "characters" 882 "sha256" "06887afe80d0b06165bbc8ed5316bc0ec7be388d50b7262efbab05eb65631099"}]}
```

```kotlin [field-property box="madp_chargrid_spinners" name="code" path="madp_chargrid_spinners_code_.kt"]
// Per-cell rotation from a wave, plus one CSS variable that every cell adds to its own angle, so the
// whole field spins with a single CSSVAR call a tick.
val notes = listOf(60, 62, 64, 65, 67, 69, 71, 72, 74, 76, 77, 79)
val sounds = notes.mapIndexed { i, note -> "/dough/play/note/$note/duration/0.06/s/sine/lpf/${2000 + i * 200}/release/0.03/verb/0.3" }

charGrid(glyphs = "|/-\\+×•◦○●★✶", sounds = sounds) {
    CSSVAR("--spin", "${frame * 6 % 360}deg")
    for (y in 0 until ROWS) {
        for (x in 0 until COLS) {
            val t = frame * 0.04
            val wave = sin(x * 0.4 + t) * cos(y * 0.3 + t * 0.6)
            val g = ((wave + 1) / 2 * glyphs.length).toInt()
            val hue = (x * 12 + y * 8 + frame * 3) % 360
            SET(x, y, g, "hsl($hue,70%,50%)", "transform:rotate(calc(${(wave * 180).roundToInt()}deg + var(--spin,0deg)))")
        }
    }
}
```

## 5. 10 PRINT

<!-- field-box id="madp_chargrid_10print" index="4" -->

### Narrative

From `code`

10 PRINT CHR$(205.5+RND(1)); : GOTO 10
Stream mode: one long program, and every SET waits for the next tick, so the maze types itself
out a cell at a time and scrolls when it reaches the bottom. Each diagonal is a note.

```edn [field-box id="madp_chargrid_10print" role="properties"]
{"id" "madp_chargrid_10print" "index" 4 "name" "10 PRINT" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "10 PRINT" "_tombstone" false "frame" #afield/rect [640.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_chargrid_10print_code_.kt" "lines" 14 "characters" 630 "sha256" "82cd0a0bfd34041654787084455bc6912d4910e5d61ae17ccc30c5e453c07654"}]}
```

```kotlin [field-property box="madp_chargrid_10print" name="code" path="madp_chargrid_10print_code_.kt"]
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
```

## 6. matrix rain

<!-- field-box id="madp_chargrid_rain" index="5" -->

### Narrative

From `code`

Matrix rain. The drops are ordinary Kotlin values declared above charGrid { }, so they last as long
as the run (the demo keeps them in `state`). Each tick clears the grid and redraws every drop.

```edn [field-box id="madp_chargrid_rain" role="properties"]
{"id" "madp_chargrid_rain" "index" 5 "name" "matrix rain" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "matrix rain" "_tombstone" false "frame" #afield/rect [40.0 660.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_chargrid_rain_code_.kt" "lines" 41 "characters" 2381 "sha256" "9ef93c3c724aa41fe0b6ffa8907e58d9b11153acbb707d2761ac717898610f4a"}]}
```

```kotlin [field-property box="madp_chargrid_rain" name="code" path="madp_chargrid_rain_code_.kt"]
// Matrix rain. The drops are ordinary Kotlin values declared above charGrid { }, so they last as long
// as the run (the demo keeps them in `state`). Each tick clears the grid and redraws every drop.
class Drop(var y: Int, var len: Int)

val drops = mutableListOf<Drop>()
// The sixteen digits and letters ring, high and bright; the nine symbols are silent.
val sounds = listOf(
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
)

charGrid(glyphs = "0123456789ABCDEF:.*+=@#$%", sounds = sounds) {
    if (drops.isEmpty()) repeat(COLS) { drops += Drop(-rand(25), 5 + rand(14)) }
    clearGrid()
    for (x in 0 until COLS) {
        val d = drops[x]
        for (i in 0 until d.len) {
            val y = d.y - i
            if (y in 0 until ROWS) SET(x, y, rand(glyphs.length), if (i == 0) "#fff" else "hsl(120,80%,${max(15, 65 - i * 4)}%)")
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
{"id" "madp_chargrid_plasma" "index" 6 "name" "charGrid · plasma" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "charGrid · plasma" "_tombstone" false "frame" #afield/rect [40.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_chargrid_plasma_code_.kt" "lines" 40 "characters" 2449 "sha256" "2a42790e9eb38db8c4a44e9cca2f0ab5643d7f6a3cf173e523612bd9e9f96d5f"}]}
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
val sounds = listOf(
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
)

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

