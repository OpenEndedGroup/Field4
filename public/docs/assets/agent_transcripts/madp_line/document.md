# Field Document: madp_line

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. magnet mesh · between strokes

<!-- field-box id="madp_line_magnet_mesh" index="0" -->

### Narrative

From `code`

Magnet Mesh: draw two or more strokes near each other. Each point is pulled towards the
nearest point of another stroke (closestPoint()), and every fifth one sprouts a filament to
it, so neighbouring strokes slowly knit into a moving web.

```edn [field-box id="madp_line_magnet_mesh" role="properties"]
{"id" "madp_line_magnet_mesh" "index" 0 "name" "magnet mesh · between strokes" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "magnet mesh · between strokes" "_tombstone" false "frame" #afield/rect [40.0 600.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_line_magnet_mesh_code_.kt" "lines" 33 "characters" 1407 "sha256" "6221cd90bdf4f2a22431300755eb03337479df17356f9f0daa8ae92c84bd2952"}]}
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

From `code`

Step Lattice: left to right is time (twelve steps a bar), up is pitch (fourteen lanes of a
C major scale). The stroke is snapped onto that grid once, when it is drawn, into a melody
and a bass line; the grid, the snapped notes and the cursor on the sounding step are drawn
over it. cycle is the sound clock, so the cursor is the note you hear.

```edn [field-box id="madp_line_step_lattice" role="properties"]
{"id" "madp_line_step_lattice" "index" 1 "name" "step lattice · a stroke as a sequencer" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "step lattice · a stroke as a sequencer" "_tombstone" false "frame" #afield/rect [40.0 740.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_line_step_lattice_code_.kt" "lines" 71 "characters" 3687 "sha256" "5ed3dd8b5a0efd2894e69e5fd654eaa7423a3ee56c991e127beeac75d9a9502e"}]}
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

From `code`

Comet Dust: great with quick slashes and zig-zags. atomize() breaks the stroke into segments
that wiggle() shakes apart, dots() scatters sparks, and the long trail turns them to dust.

```edn [field-box id="madp_line_comet_dust" role="properties"]
{"id" "madp_line_comet_dust" "index" 2 "name" "comet dust" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "comet dust" "_tombstone" false "frame" #afield/rect [40.0 320.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_line_comet_dust_code_.kt" "lines" 20 "characters" 1005 "sha256" "89784ba2a674b8197e464b5e3bf56ecda633cfdf4901f084941de6d9b7050723"}]}
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
{"id" "madp_line_prism_choir" "index" 3 "name" "prism choir · run me, then draw" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "prism choir · run me, then draw" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_line_prism_choir_code_.kt" "lines" 59 "characters" 2834 "sha256" "7f95f3740e350a1e28176ae33521c820fda73c3d77921ca4cdc9e7a82261ecc2"}]}
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

From `code`

Ribbon Bloom: draw long swoops and loops. The stroke becomes a filled, breathing ribbon with
a bright spine. Run this box to take the pen; it makes no sound.

```edn [field-box id="madp_line_ribbon_bloom" role="properties"]
{"id" "madp_line_ribbon_bloom" "index" 4 "name" "ribbon bloom" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "ribbon bloom" "_tombstone" false "frame" #afield/rect [40.0 180.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_line_ribbon_bloom_code_.kt" "lines" 32 "characters" 1403 "sha256" "e387e32a96ce78a526add780de8d1a474921c0d1b5bc452d9b13347d207bb870"}]}
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

From `code`

Curve Choir: one stroke blooms into four related curves. A noise wind warps the stroke;
add() and subtract() push the stroke by the wind's shape and against it, scale() blows up a
halo, and project() squeezes one curve into the other's directions.

```edn [field-box id="madp_line_curve_choir" role="properties"]
{"id" "madp_line_curve_choir" "index" 5 "name" "curve choir · curve maths" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "curve choir · curve maths" "_tombstone" false "frame" #afield/rect [40.0 460.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_line_curve_choir_code_.kt" "lines" 31 "characters" 1303 "sha256" "19c10adb79e1ec276e57dcc1625d1287ce5ebc0a3fd3f32854b5ed69701f8ded"}]}
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

