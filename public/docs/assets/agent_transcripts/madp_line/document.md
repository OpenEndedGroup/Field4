# Field Document: madp_line

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. magnet mesh · between strokes

<!-- field-box id="madp_line_magnet_mesh" index="0" -->

### Narrative

From `notes`

Magnet mesh: between strokes

From `code`

Magnet Mesh: draw two or more strokes near each other. Each point is pulled towards the
nearest point of another stroke (closestPoint()), and every fifth one sprouts a filament to
it, so neighbouring strokes slowly knit into a moving web.

```edn [field-box id="madp_line_magnet_mesh" role="properties"]
{"id" "madp_line_magnet_mesh" "index" 0 "name" "magnet mesh · between strokes" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "magnet mesh · between strokes" "_tombstone" false "frame" #afield/rect [40.0 600.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_line_magnet_mesh_notes_.md" "lines" 6 "characters" 380 "sha256" "6e8d4dfbbfa5ad8c237abad5be9e2ded81fee1e5a7fe27bb3ebf9a0bee2962c1"} {"name" "code" "language" "kotlin" "path" "madp_line_magnet_mesh_code_.kt" "lines" 33 "characters" 1407 "sha256" "6221cd90bdf4f2a22431300755eb03337479df17356f9f0daa8ae92c84bd2952"}]}
```

```markdown [field-property box="madp_line_magnet_mesh" name="notes" path="madp_line_magnet_mesh_notes_.md"]
# Magnet mesh: between strokes

Draw two or three strokes near each other. Every point is pulled towards the nearest point of
another stroke (`closestPoint`), and every fifth point grows a filament to it, so neighbouring
strokes slowly knit together. This is the physics: the block sets `ax`/`ay`, and Field moves the
points. A single stroke on its own just sits there. No sound.
```

```kotlin [field-property box="madp_line_magnet_mesh" name="code" path="madp_line_magnet_mesh_code_.kt"]
// Magnet Mesh: draw two or more strokes near each other. Each point is pulled towards the
// nearest point of another stroke (closestPoint()), and every fifth one sprouts a filament to
// it, so neighbouring strokes slowly knit into a moving web.
eachStroke(trail = 0.14) {
    draw { t ->
        val links = FLine()
        links.overshoot = 3.0
        val body = ArrayList<Vec2>()
        val hues = ArrayList<Double>()
        for ((i, p) in points.withIndex()) {
            val near = closestPoint(p.x, p.y)
            var hue = 200.0
            if (near != null && near.dist < 180) {
                val dx = near.x - p.x
                val dy = near.y - p.y
                val dist = max(1e-9, hypot(dx, dy))
                val force = (180 - near.dist) * 0.08
                p.ax += dx / dist * force
                p.ay += dy / dist * force
                hue = 110 + (180 - near.dist) * 0.8
                if (i % 5 == 0) links.segment(p.x, p.y, near.x, near.y, rgba(120, 255, 170, 0.1 + (180 - near.dist) / 220))
            }
            body += p.at
            hues += hue
        }
        val line = smooth(body)
        line.color = cssHsla(hues.last(), 0.92, 0.68, 0.88)
        line.overshoot = 6.0
        val beads = dots(body, 1.3)
        beads.nodes.forEachIndexed { i, node -> node.color = cssHsla(hues[i], 0.92, 0.68, 0.88) }
        listOf(links, line, beads)
    }
}
```

## 2. step lattice · a stroke as a sequencer

<!-- field-box id="madp_line_step_lattice" index="1" -->

### Narrative

From `notes`

Step lattice: a stroke as a sequencer

From `code`

Step Lattice: left to right is time (twelve steps a bar), up is pitch (fourteen lanes of a
C major scale). The stroke is snapped onto that grid once, when it is drawn, into a melody
and a bass line; the grid, the snapped notes and the cursor on the sounding step are drawn
over it. cycle is the sound clock, so the cursor is the note you hear.

```edn [field-box id="madp_line_step_lattice" role="properties"]
{"id" "madp_line_step_lattice" "index" 1 "name" "step lattice · a stroke as a sequencer" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "step lattice · a stroke as a sequencer" "_tombstone" false "frame" #afield/rect [40.0 740.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_line_step_lattice_notes_.md" "lines" 6 "characters" 417 "sha256" "38ec6403e68d5b07b24ec747cb10511ebcfa7878364757217392c4a08b87701e"} {"name" "code" "language" "kotlin" "path" "madp_line_step_lattice_code_.kt" "lines" 71 "characters" 3687 "sha256" "5ed3dd8b5a0efd2894e69e5fd654eaa7423a3ee56c991e127beeac75d9a9502e"}]}
```

```markdown [field-property box="madp_line_step_lattice" name="notes" path="madp_line_step_lattice_notes_.md"]
# Step lattice: a stroke as a sequencer

Left to right is time, twelve steps a bar; up is pitch, a C major scale. When you draw, the stroke is
snapped onto that grid once, into a melody and a bass line, and they loop. The grid, the snapped notes
and a cursor are drawn over the stroke. The cursor reads `cycle`, the sound's own clock, so it always
marks the note you hear, and the note it is on brightens the filter.
```

```kotlin [field-property box="madp_line_step_lattice" name="code" path="madp_line_step_lattice_code_.kt"]
// Step Lattice: left to right is time (twelve steps a bar), up is pitch (fourteen lanes of a
// C major scale). The stroke is snapped onto that grid once, when it is drawn, into a melody
// and a bass line; the grid, the snapped notes and the cursor on the sounding step are drawn
// over it. cycle is the sound clock, so the cursor is the note you hear.
eachStroke(trail = 0.12) {
    val noteRows = listOf("c4", "d4", "e4", "f4", "g4", "a4", "b4", "c5", "d5", "e5", "f5", "g5", "a5", "c6")
    val bassRows = listOf("c3", "d3", "e3", "f3", "g3", "a3", "b3", "c4", "d4", "e4", "f4", "g4", "a4", "c5")
    val steps = 12
    val rows = noteRows.size
    val minX = points.minOf { it.x } - 24
    val maxX = points.maxOf { it.x } + 24
    val minY = points.minOf { it.y } - 28
    val maxY = points.maxOf { it.y } + 28
    val w = max(1.0, maxX - minX)
    val h = max(1.0, maxY - minY)
    fun gx(step: Int) = minX + w * step / (steps - 1)
    fun gy(row: Int) = maxY - h * row / (rows - 1)

    val rowSum = IntArray(steps)
    val count = IntArray(steps)
    val rawX = DoubleArray(steps)
    val rawY = DoubleArray(steps)
    for (p in points) {
        val step = Math.round(clamp01((p.x - minX) / w) * (steps - 1)).toInt()
        rowSum[step] += Math.round((1 - clamp01((p.y - minY) / h)) * (rows - 1)).toInt()
        count[step]++
        rawX[step] += p.x
        rawY[step] += p.y
    }
    val row = IntArray(steps) { if (count[it] > 0) Math.round(rowSum[it].toDouble() / count[it]).toInt() else -1 }
    val density = row.count { it >= 0 } / steps.toDouble()
    val panSigned = clamp01((points.last().x - points.first().x) / max(40.0, w * 0.7)) * 2 - 1
    fun toPan(v: Double) = 0.5 + v.coerceIn(-1.0, 1.0) * 0.5

    var cursorRow = 0.4
    var pulse = 0.2
    val melody = pat(row.joinToString(" ") { if (it < 0) "~" else noteRows[it] }) with s("triangle") with
        gain(signal { 0.02 + density * 0.05 + pulse * 0.08 }) with lpf(signal { 200 + cursorRow * 2600 + pulse * 600 }) with
        pan(toPan(panSigned * 0.7)) with room(0.38)
    val bass = pat(row.joinToString(" ") { if (it < 0) "~" else bassRows[max(0, it - 3)] }) with s("sine") with
        gain(0.01 + density * 0.06) with lpf(signal { 150 + cursorRow * 800 }) with pan(toPan(panSigned * -0.35))
    sound(stack(melody, bass))

    draw { t ->
        val step = floor((cycle % 1.0) * steps).toInt().coerceIn(0, steps - 1)
        val sounding = row[step] >= 0
        cursorRow = if (sounding) row[step] / (rows - 1.0) else 0.15
        pulse = if (sounding) 1.0 else 0.22

        val guides = FLine()
        guides.ignore = true
        for (r in 0 until rows) guides.segment(minX, gy(r), maxX, gy(r), if (r == row[step]) rgba(120, 190, 255, 0.18) else rgba(255, 255, 255, 0.05))
        for (s in 0 until steps) guides.segment(gx(s), minY, gx(s), maxY, if (s == step) rgba(255, 235, 120, 0.32) else rgba(255, 255, 255, 0.06))

        val connectors = FLine()
        connectors.ignore = true
        connectors.color = rgba(255, 215, 120, 0.18)
        val snapped = ArrayList<Vec2>()
        val beads = FLine().apply { pointed = true; stroked = false }
        for (s in 0 until steps) {
            if (row[s] < 0) continue
            connectors.segment(rawX[s] / count[s], rawY[s] / count[s], gx(s), gy(row[s]))
            snapped += vec(gx(s), gy(row[s]))
            if (s == step) beads.dot(gx(s), gy(row[s]), 4.6, rgba(255, 245, 170, 0.96))
            else beads.dot(gx(s), gy(row[s]), 2.6, rgba(255, 160, 120, 0.88))
        }
        val melodyLine = smooth(snapped)
        melodyLine.color = rgba(120, 220, 255, 0.75)
        listOf(guides, connectors, melodyLine, beads)
    }
}
```

## 3. comet dust

<!-- field-box id="madp_line_comet_dust" index="2" -->

### Narrative

From `notes`

Comet dust

From `code`

Comet Dust: great with quick slashes and zig-zags. atomize() breaks the stroke into segments
that wiggle() shakes apart, dots() scatters sparks, and the long trail turns them to dust.

```edn [field-box id="madp_line_comet_dust" role="properties"]
{"id" "madp_line_comet_dust" "index" 2 "name" "comet dust" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "comet dust" "_tombstone" false "frame" #afield/rect [40.0 320.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_line_comet_dust_notes_.md" "lines" 5 "characters" 299 "sha256" "89327cef6f252c314c9e36eaf9789de47a14bfc8f7733759a1e1fe59380aa697"} {"name" "code" "language" "kotlin" "path" "madp_line_comet_dust_code_.kt" "lines" 20 "characters" 1005 "sha256" "89784ba2a674b8197e464b5e3bf56ecda633cfdf4901f084941de6d9b7050723"}]}
```

```markdown [field-property box="madp_line_comet_dust" name="notes" path="madp_line_comet_dust_notes_.md"]
# Comet dust

Best with quick slashes and zig-zags. `atomize` breaks the stroke into separate segments, `wiggle`
shakes them apart with Perlin noise, and two rings of `dots` scatter sparks around it. The trail is
0.92, so each frame keeps most of the last and the motion smears into dust. No sound.
```

```kotlin [field-property box="madp_line_comet_dust" name="code" path="madp_line_comet_dust_code_.kt"]
// Comet Dust: great with quick slashes and zig-zags. atomize() breaks the stroke into segments
// that wiggle() shakes apart, dots() scatters sparks, and the long trail turns them to dust.
eachStroke(trail = 0.92) {
    draw { t ->
        val base = resample(points, 42)
        val surge = 5 + tri01(t * 0.55 + heldNoise(2.4, t, 5.0) * 0.3) * 4
        val segments = wiggle(atomize(base), surge, 0.02, t * 0.8)
        segments.overshoot = 4.0
        segments.nodes.forEachIndexed { i, node ->
            if (node !is FLine.MoveTo) node.color = cssHsla(40 + i / 2 * 7 + t * 40, 0.95, 0.62, 0.42)
        }

        val sparks = dots(wiggle(scale(base, 1.12), 10.0, 0.03, -t * 0.7), 1.5)
        sparks.nodes.forEachIndexed { i, node -> node.color = cssHsla(200 + i * 11 + t * 65, 0.95, 0.72, 0.88) }

        val halo = dots(scale(base, 1.24 + tri01(t * 0.35 + heldNoise(4.1, t, 4.0) * 0.5) * 0.18), 0.8)
        halo.color = rgba(255, 255, 255, 0.22)
        listOf(segments, sparks, halo)
    }
}
```

## 4. prism choir · run me, then draw

<!-- field-box id="madp_line_prism_choir" index="3" -->

### Narrative

From `notes`

Line: strokes that come alive

From `code`

Prism Choir: draw long arcs or loops.

Run this box to take the pen. Press Alt+D, choose the pencil and draw on the empty canvas to
the right. Each stroke comes alive: this block runs once for it (its shape sets up a synth
choir: length is loudness, rise is brightness, direction is pan), and draw { } runs sixty
times a second, returning the lines to show. The louder the choir, the wider the ribbon swings.

Edit this box and run it again: strokes you draw from then on use the new code, and the ones
already on the canvas keep theirs. Run another box to give it the pen; clearStrokes() wipes
the page. Local variables here belong to one stroke, shared by its sound and its drawing.

```edn [field-box id="madp_line_prism_choir" role="properties"]
{"id" "madp_line_prism_choir" "index" 3 "name" "prism choir · run me, then draw" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "prism choir · run me, then draw" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_line_prism_choir_notes_.md" "lines" 74 "characters" 4428 "sha256" "019b7d85d0faab6329d1ea6e72c76d908dfcdddb73eebf74791fb41b0e8fe82f"} {"name" "code" "language" "kotlin" "path" "madp_line_prism_choir_code_.kt" "lines" 59 "characters" 2834 "sha256" "7f95f3740e350a1e28176ae33521c820fda73c3d77921ca4cdc9e7a82261ecc2"}]}
```

```markdown [field-property box="madp_line_prism_choir" name="notes" path="madp_line_prism_choir_notes_.md"]
# Line: strokes that come alive

**Line** is one of the madp classroom demos: a black page beside a code editor. Every stroke you draw
becomes an object with a life of its own. It moves, it makes sound, and the way it moves and sounds
comes from code you wrote. The point it teaches is that a gesture can be the start of a program
rather than its output: the shape of your hand sets up a synth and a motion, and from then on the
stroke plays and draws itself.

This document is that demo in Field. You draw on Field's own canvas, with the drawing layer, and the
strokes come alive on the canvas where you drew them.

## Playing with it

1. Run this box. It takes the **pen**.
2. Press **Alt+D** for the drawing layer, choose the pencil, and draw on the empty canvas to the right.
   Long arcs and loops suit this one. Press Alt+D again when you are done.
3. Each stroke turns into a ribbon that hums. The longer the stroke, the louder; the more it rises,
   the brighter; left-to-right or right-to-left sets the pan. The louder the choir plays, the wider
   the ribbon swings.
4. Edit the code and run the box again. Strokes you draw from then on use the new code; the ones
   already on the canvas keep the code they were drawn with.
5. Run another box to give it the pen. `clearStrokes()` in any box wipes the page.

No pen handy? Run **draw for me**: it sends three strokes the way the drawing layer does when you
lift the pencil.

## How a stroke's code is written

`eachStroke(trail = 0.18) { … }` is the whole program. Its block runs **once for each stroke**, when
it is drawn: that is where you look at the stroke's `points` and start a `sound(…)`. Inside it,
`draw { t -> … }` runs **sixty times a second** and returns the lines to show (FLines). Local `var`s
in the block belong to that one stroke and are shared by its sound and its drawing, which is how the
ribbon and the choir talk to each other.

`points` are physics points (`x`, `y`, `vx`, `vy`, `ax`, `ay`): set `ax`/`ay` to push them and Field
moves them after each frame. `trail` is how much of each frame the next one keeps, so 0.9 leaves long
trails. The verbs are the demo's: `resample`, `smooth`, `arcs`, `dots`, `atomize`, `wiggle`, `warp`,
`add`, `subtract`, `project`, `scale`, `transformTo`, `closestPoint`, `closestOutput`, `rms()`, and
the noise helpers `triWave`, `tri01`, `heldNoise`, `driftNoise`, `perlin2D`.

## From the demo to Field

| the demo | here |
| --- | --- |
| `onStart(inputPoints, lineIndex)` | the `eachStroke { }` block, with `points` and `index` |
| `manipulatePoints(inputPoints, t)` | `draw { t -> }` |
| `ctrl.x`, shared by image and sound | a local `var x` in the block |
| draw commands (`smooth`, `arc`, `dot`, `{x0,y0,x1,y1}`, `connected:false`) | FLines: `smooth(curve)`, `arcs(curve)`, `dots(curve, r)`, `segment(…)`, `moveTo` |
| `color`, `fill`, `overshoot`, `ignore` | `line.color`, `line.filled`, `line.overshoot`, `line.ignore` |
| `'hsla(205, 96%, 68%, 0.9)'` | `cssHsla(205, 0.96, 0.68, 0.9)` |
| Strudel: `note("c3 e3").sound("sawtooth").gain(signal(() => …))` | `pat("c4 e4") with s("sawtooth") with gain(signal { … })` |
| `.stack(…)` | `stack(a, b)` |
| the editor's code, snapshotted at mouseup | the box's code when it last ran |
| the preset menu | one box per preset |
| Reset | `clearStrokes()` |

The sound runs on the same `dough.wasm` engine as the other madp demos, through Field's pattern
language, which is Tidal's. Two things differ from Strudel. Note names are an octave higher here
(`c5` is middle C), so every pattern is written up an octave and sounds at the same pitch. And
Strudel's synths are about a quarter as loud as dough's at the same gain, so the strokes play at that
level: the demo's gains sound as loud as they did, and `rms()` reads what it read there.

## What is different

- The demo's own editor, AI completion, agent chat and saves are Field's editor, completion, AI4 and
  documents.
- Strokes are not saved with the document, as in the demo.
- The demo steps its physics once per screen refresh, so on a 120 Hz display its strokes move twice
  as fast as on a 60 Hz one. Here they always step 60 times a second.
- Panning or zooming the canvas clears the trails.
- Five of the demo's seventeen presets are here: ribbon bloom, comet dust, curve choir, magnet mesh
  and step lattice. The rest repeat mechanisms these show.

The design notes are in `dev_docs/madp/line.md`.
```

```kotlin [field-property box="madp_line_prism_choir" name="code" path="madp_line_prism_choir_code_.kt"]
/*
Prism Choir: draw long arcs or loops.

Run this box to take the pen. Press Alt+D, choose the pencil and draw on the empty canvas to
the right. Each stroke comes alive: this block runs once for it (its shape sets up a synth
choir: length is loudness, rise is brightness, direction is pan), and draw { } runs sixty
times a second, returning the lines to show. The louder the choir, the wider the ribbon swings.

Edit this box and run it again: strokes you draw from then on use the new code, and the ones
already on the canvas keep theirs. Run another box to give it the pen; clearStrokes() wipes
the page. Local variables here belong to one stroke, shared by its sound and its drawing.
*/
eachStroke(trail = 0.18) {
    val first = points.first()
    val last = points.last()
    val span = min(1.0, length(curve) / 520)
    var panning = ((last.x - first.x) / 260).coerceIn(-1.0, 1.0)
    var height = min(1.0, abs(last.y - first.y) / 240)
    var flow = 0.0
    var spark = 0.0
    fun toPan(v: Double) = 0.5 + v.coerceIn(-1.0, 1.0) * 0.5

    val choir = pat("c4 e4 g4 b4") with s("sawtooth") with gain(signal { 0.06 + span * 0.16 }) with
        lpf(signal { 240 + height * 1600 + spark * 1400 }) with pan(signal { toPan(panning) }) with room(0.45)
    val bass = pat("c3 g3 c4 g3") with s("triangle") with gain(signal { 0.03 + flow * 0.12 }) with
        lpf(signal { 180 + spark * 900 }) with pan(signal { toPan(panning * -0.6) })
    sound(stack(choir, bass))

    draw { t ->
        val base = resample(points, 72)
        flow = min(1.0, length(base) / 420)
        panning = ((base.last().x - base.first().x) / 260.0).coerceIn(-1.0, 1.0)
        height = min(1.0, abs(base.last().y - base.first().y) / 240.0)

        val audioHit = min(1.0, rms() * 7)
        val hue = 205 + audioHit * 80 + t * 20
        var motion = 0.0
        val ribbon = ArrayList<Vec2>()
        val sparks = FLine().apply { pointed = true; stroked = false }
        for (i in base.indices) {
            val p = base[i]
            val n = normalAt(base, i)
            val wave = (triWave(t * 0.48 + i * 0.08 + audioHit * 0.25) * 0.65 + driftNoise(i * 0.21 + audioHit * 0.5, t, 0.9)) *
                (7 + audioHit * 60) * envelope(i, base.size)
            motion += abs(wave)
            ribbon += vec(p.x + n.x * wave, p.y + n.y * wave)
            if (i % 8 == 0) {
                val size = 1.1 + max(audioHit, min(1.0, abs(wave) / 28)) * 6
                sparks.dot(p.x - n.x * wave * 0.35, p.y - n.y * wave * 0.35, size, cssHsla(hue + i * 3 + 40, 0.98, 0.78, 0.86))
            }
        }
        spark = max(audioHit * 0.7, min(1.0, motion / (base.size * 22)))

        val line = smooth(ribbon)
        line.color = cssHsla(hue + base.size * 3, 0.96, 0.68, 0.88)
        line.overshoot = 10.0
        listOf(line, sparks)
    }
}
```

## 5. ribbon bloom

<!-- field-box id="madp_line_ribbon_bloom" index="4" -->

### Narrative

From `notes`

Ribbon bloom

From `code`

Ribbon Bloom: draw long swoops and loops. The stroke becomes a filled, breathing ribbon with
a bright spine. Run this box to take the pen; it makes no sound.

```edn [field-box id="madp_line_ribbon_bloom" role="properties"]
{"id" "madp_line_ribbon_bloom" "index" 4 "name" "ribbon bloom" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "ribbon bloom" "_tombstone" false "frame" #afield/rect [40.0 180.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_line_ribbon_bloom_notes_.md" "lines" 6 "characters" 350 "sha256" "9bcbce30e0662c734551e1edcea6206c3e85e4f2950423b468719df8d392e99a"} {"name" "code" "language" "kotlin" "path" "madp_line_ribbon_bloom_code_.kt" "lines" 32 "characters" 1403 "sha256" "e387e32a96ce78a526add780de8d1a474921c0d1b5bc452d9b13347d207bb870"}]}
```

```markdown [field-property box="madp_line_ribbon_bloom" name="notes" path="madp_line_ribbon_bloom_notes_.md"]
# Ribbon bloom

Draw long swoops and loops. The stroke opens into a filled ribbon that breathes along its length,
with a bright spine down the middle. It shows how one curve becomes a shape: points pushed out along
the normal on one side (`normalAt`), back along the other, and the outline filled (`filled = true`)
under a separate stroke. No sound.
```

```kotlin [field-property box="madp_line_ribbon_bloom" name="code" path="madp_line_ribbon_bloom_code_.kt"]
// Ribbon Bloom: draw long swoops and loops. The stroke becomes a filled, breathing ribbon with
// a bright spine. Run this box to take the pen; it makes no sound.
eachStroke(trail = 0.08) {
    draw { t ->
        val base = resample(points, 72)
        val upper = ArrayList<Vec2>()
        val lower = ArrayList<Vec2>()
        for (i in base.indices) {
            val p = base[i]
            val n = normalAt(base, i)
            val billow = tri01(t * 0.45 + i * 0.07 + driftNoise(i * 0.21, t, 0.2) * 0.6)
            val spread = (8 + envelope(i, base.size) * 30) * (0.45 + billow * 0.78) + heldNoise(i * 0.31, t, 6.0) * 5
            upper += vec(p.x + n.x * spread, p.y + n.y * spread)
            lower += vec(p.x - n.x * spread * 0.8, p.y - n.y * spread * 0.8)
        }
        val hue = t * 50
        // Up one side and back down the other. (`+` on two point lists adds them point by point.)
        val around = ArrayList(upper)
        around.addAll(lower.asReversed())
        val outline = smooth(around)
        val fill = outline.copy()
        fill.filled = true
        fill.stroked = false
        fill.color = cssHsla(hue + 35, 0.92, 0.54, 0.18)
        outline.color = cssHsla(hue + 80 + base.size * 2, 0.95, 0.6, 0.72)

        val spine = smooth(base)
        spine.color = rgba(255, 255, 255, 0.35)
        spine.overshoot = 10.0
        listOf(fill, outline, spine)
    }
}
```

## 6. curve choir · curve maths

<!-- field-box id="madp_line_curve_choir" index="5" -->

### Narrative

From `notes`

Curve choir: curve maths

From `code`

Curve Choir: one stroke blooms into four related curves. A noise wind warps the stroke;
add() and subtract() push the stroke by the wind's shape and against it, scale() blows up a
halo, and project() squeezes one curve into the other's directions.

```edn [field-box id="madp_line_curve_choir" role="properties"]
{"id" "madp_line_curve_choir" "index" 5 "name" "curve choir · curve maths" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "curve choir · curve maths" "_tombstone" false "frame" #afield/rect [40.0 460.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_line_curve_choir_notes_.md" "lines" 6 "characters" 410 "sha256" "320c015d91c3ef547346b00bf68ca0bd46c01dbec2e39eb23318f32250ab78c7"} {"name" "code" "language" "kotlin" "path" "madp_line_curve_choir_code_.kt" "lines" 31 "characters" 1303 "sha256" "19c10adb79e1ec276e57dcc1625d1287ce5ebc0a3fd3f32854b5ed69701f8ded"}]}
```

```markdown [field-property box="madp_line_curve_choir" name="notes" path="madp_line_curve_choir_notes_.md"]
# Curve choir: curve maths

One stroke becomes four related curves. A noise "wind" warps the stroke; `add` pushes the stroke by
the wind's shape (which doubles it out from its centre), `subtract` pushes against it (which cancels
the shape, leaving a knot at the centre), `scale` makes a halo, and `project` squeezes one curve into
another's directions. Change one line and watch which curves follow. No sound.
```

```kotlin [field-property box="madp_line_curve_choir" name="code" path="madp_line_curve_choir_code_.kt"]
// Curve Choir: one stroke blooms into four related curves. A noise wind warps the stroke;
// add() and subtract() push the stroke by the wind's shape and against it, scale() blows up a
// halo, and project() squeezes one curve into the other's directions.
eachStroke(trail = 0.1) {
    fun paint(curve: List<Vec2>, hue: Double, alpha: Double, overshoot: Double): FLine {
        val line = smooth(curve)
        line.color = cssHsla(hue + (curve.size - 1) * 2, 0.92, 0.68, alpha)
        if (overshoot > 0) line.overshoot = overshoot
        return line
    }

    draw { t ->
        val base = resample(points, 64)
        val wind = warp(base) { p ->
            vec(
                p.x + driftNoise(p.y * 0.031, t, 0.65) * 20,
                p.y + driftNoise(p.x * 0.027 + 30, t, 0.52) * 18 + triWave(t * 0.28 + p.x * 0.002) * 8,
            )
        }
        val halo = scale(base, 1.16 + tri01(t * 0.24 + heldNoise(1.7, t, 4.0) * 0.6) * 0.16)
        val rise = add(base, wind)
        val fall = subtract(base, wind)
        val needle = project(rise, halo)
        listOf(
            paint(halo, 12 + t * 18, 0.3, 0.0),
            paint(rise, 200 + t * 14, 0.74, 7.0),
            paint(needle, 82 + t * 10, 0.58, 0.0),
            paint(fall, 318 + t * 16, 0.74, 7.0),
        )
    }
}
```

## 7. draw for me

<!-- field-box id="madp_line_draw_for_me" index="6" -->

### Narrative

From `notes`

Draw for me

From `code`

Draws three strokes for you, as a hand would: the page sends them over the same `notation-scene`
message the drawing layer sends when you lift the pencil, so whichever box holds the pen takes
them. Needs the document open in a window.

```edn [field-box id="madp_line_draw_for_me" role="properties"]
{"id" "madp_line_draw_for_me" "index" 6 "name" "draw for me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "draw for me" "_tombstone" false "frame" #afield/rect [380.0 40.0 200.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_line_draw_for_me_notes_.md" "lines" 5 "characters" 290 "sha256" "c8c8545c0dbd2edb41ddbd0172391d3ac7e62068209ed4c00cbde3c4eab6a76f"} {"name" "code" "language" "kotlin" "path" "madp_line_draw_for_me_code_.kt" "lines" 19 "characters" 2347 "sha256" "1edb6a210b857422c339eddffe89f1482b4169b125cbf2eaab3338d4a9a26439"}]}
```

```markdown [field-property box="madp_line_draw_for_me" name="notes" path="madp_line_draw_for_me_notes_.md"]
# Draw for me

Three strokes for when there is no pen to hand. This box sends them from the page over the same
message the drawing layer sends when you lift the pencil, so whichever box holds the pen takes
them, exactly as it takes yours. Run a preset box first to choose what they become.
```

```kotlin [field-property box="madp_line_draw_for_me" name="code" path="madp_line_draw_for_me_code_.kt"]
// Draws three strokes for you, as a hand would: the page sends them over the same `notation-scene`
// message the drawing layer sends when you lift the pencil, so whichever box holds the pen takes
// them. Needs the document open in a window.
val strokes = """[{"x":480,"y":520,"points":[[0,0],[19,-26],[39,-52],[58,-78],[78,-103],[97,-127],[117,-151],[136,-173],[155,-194],[175,-213],[194,-231],[214,-248],[233,-262],[252,-275],[272,-285],[291,-294],[311,-300],[330,-304],[350,-306],[369,-306],[388,-304],[408,-300],[427,-293],[447,-285],[466,-275],[486,-263],[505,-250],[524,-235],[544,-218],[563,-200],[583,-182],[602,-162],[621,-142],[641,-121],[660,-101],[670,-90]]},{"x":977,"y":348,"points":[[0,0],[-7,16],[-18,30],[-33,43],[-52,53],[-75,61],[-100,66],[-127,69],[-156,68],[-185,66],[-215,61],[-243,53],[-268,44],[-291,32],[-311,20],[-326,6],[-337,-8],[-343,-22],[-344,-37],[-341,-51],[-334,-64],[-323,-77],[-309,-88],[-292,-99],[-273,-107],[-253,-115],[-232,-121],[-211,-125],[-189,-128],[-168,-129],[-147,-129],[-127,-127],[-109,-124],[-91,-119],[-74,-113],[-59,-106],[-45,-97],[-32,-87],[-21,-76],[-13,-64],[-7,-52],[-3,-38],[-3,-25],[-6,-11],[-13,2],[-23,15],[-37,27],[-55,37],[-76,47],[-100,54],[-113,57]]},{"x":500,"y":680,"points":[[0,0],[20,8],[39,17],[59,25],[79,33],[99,41],[118,49],[138,56],[158,62],[177,68],[197,74],[217,79],[237,83],[256,87],[276,90],[296,92],[315,94],[335,95],[355,95],[374,94],[394,93],[414,91],[434,88],[453,85],[473,80],[493,76],[512,70],[532,65],[552,58],[572,52],[591,45],[611,37],[631,30],[650,22],[670,14],[680,10]]}]"""
val stamp = System.currentTimeMillis()
display(
    "hand",
    """
<script type="module">
import { send } from "./afield.mjs";
const elements = $strokes.map((s, i) => ({ id: "hand-$stamp-" + i, type: "freedraw", x: s.x, y: s.y,
    width: 1, height: 1, angle: 0, strokeColor: "#1e1e1e", backgroundColor: "transparent", fillStyle: "solid",
    strokeWidth: 2, strokeStyle: "solid", roughness: 1, opacity: 100, groupIds: [], frameId: null, roundness: null,
    seed: 1, version: 1, versionNonce: 1, isDeleted: false, boundElements: [], link: null, locked: false,
    points: s.points, pressures: [], simulatePressure: true }));
send("notation-scene", { scene: JSON.stringify({ type: "excalidraw", version: 2, elements }) });
</script>three strokes sent"""
        .trimIndent(),
)
```

