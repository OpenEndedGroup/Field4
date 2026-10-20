# Field Document: madp_score

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 8 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Glass phase · 67

<!-- field-box id="madp_score_glass_b" index="0" -->

### Narrative

From `code`

The same as the box above, a fifth higher and a little narrower.

```edn [field-box id="madp_score_glass_b" role="properties"]
{"id" "madp_score_glass_b" "index" 0 "name" "Glass phase · 67" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Glass phase · 67" "_tombstone" false "frame" #afield/rect [1140.0 450.0 260.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_score_glass_b_code_.kt" "lines" 9 "characters" 489 "sha256" "a89c3a2ace4680a2f4fc6f7a5d94b159cf5a8bfe62aa874210d60d52f5241a54"}]}
```

```kotlin [field-property box="madp_score_glass_b" name="code" path="madp_score_glass_b_code_.kt"]
// The same as the box above, a fifth higher and a little narrower.
score {
    for (i in 0 until 8) {
        val u = i / 7.0
        val p = boxPoint(u, 0.5 + sin(localT * 2 + i * 0.4) * 0.06)
        draw.line(p.x, boxPoint(u, 0.2).y, p.x, boxPoint(u, 0.8).y, stroke = "rgba(174, 246, 255, 0.8)", width = 1.2 + thisRMS * 2)
    }
    if (onSubdivision(6, "phase")) sound("/dough/play/note/67/duration/0.1/s/tri/gain/0.15/lpf/1200/release/0.08/verb/0.26/pan/0.62", minInterval = 0.05)
}
```

## 2. jump cut

<!-- field-box id="madp_score_jump" index="1" -->

### Narrative

From `code`

A box that cuts itself to somewhere else in the score: on the way in, and five times on the
way across, it jumps near the playhead and leaves a fading ghost of where it was. The box
really moves on the canvas; its frame is part of the instrument.

```edn [field-box id="madp_score_jump" role="properties"]
{"id" "madp_score_jump" "index" 1 "name" "jump cut" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "jump cut" "_tombstone" false "frame" #afield/rect [1430.0 110.0 160.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_score_jump_code_.kt" "lines" 49 "characters" 2237 "sha256" "9c2bd7e67a3ea25a5bf42b581079bbbf2f9f997ebbb0a8d4dc678e4b38026e45"}]}
```

```kotlin [field-property box="madp_score_jump" name="code" path="madp_score_jump_code_.kt"]
// A box that cuts itself to somewhere else in the score: on the way in, and five times on the
// way across, it jumps near the playhead and leaves a fading ghost of where it was. The box
// really moves on the canvas; its frame is part of the instrument.
class Ghost(val x: Double, val y: Double, val w: Double, val h: Double, var life: Double = 1.0)

val ghosts = mutableListOf<Ghost>()
var jumps = 0
var flash = 0.0

score {
    fun jump() {
        val was = box.get()
        ghosts += Ghost(was.x + was.w / 2, was.y + was.h / 2, was.w, was.h)
        if (ghosts.size > 8) ghosts.removeAt(0)

        val w = 120 + rand(0.0, scoreWidth * 0.08)
        val h = 90 + rand(0.0, scoreHeight * 0.12)
        val anchor = playheadX + rand(-scoreWidth * 0.12, scoreWidth * 0.12)
        box.set(
            x = clamp(anchor - w * (0.2 + rand(0.0, 0.5)), scoreLeft, scoreRight - w),
            y = clamp(rand(scoreTop + 10, scoreBottom - h - 10), scoreTop, scoreBottom - h),
            w = w,
            h = h,
        )
        flash = 1.0
        jumps++
        sound("/dough/play/note/${55 + (jumps % 5) * 2}/duration/0.06/s/pulse/pw/0.22/gain/0.13/lpf/${700 + (jumps % 7) * 220}/release/0.04/pan/0.18", key = "jump", minInterval = 0.03)
    }

    if (justEntered) jump()
    if (onSubdivision(5, "jump-grid")) jump()

    ghosts.forEach { it.life *= 0.84 }
    ghosts.removeAll { it.life < 0.06 }
    for (g in ghosts) {
        val p = fromScore(g.x, g.y)
        draw.rect(p.x - g.w * scaleX / 2, p.y - g.h * scaleY / 2, g.w * scaleX, g.h * scaleY, stroke = "rgba(255,255,255,${g.life * 0.28})")
    }

    flash *= 0.82
    val live = box.get()
    val c = fromScore(live.x + live.w / 2, live.y + live.h / 2)
    val ww = live.w * scaleX
    val hh = live.h * scaleY
    draw.rect(c.x - ww / 2, c.y - hh / 2, ww, hh, stroke = "rgba(255, 171, 125, 0.92)", width = 2 + thisRMS * 2.5)
    draw.line(c.x - ww / 2, c.y - hh / 2, c.x + ww / 2, c.y + hh / 2, stroke = "rgba(255, 171, 125, 0.48)", width = 1.2)
    draw.line(c.x + ww / 2, c.y - hh / 2, c.x - ww / 2, c.y + hh / 2, stroke = "rgba(255, 171, 125, 0.48)", width = 1.2)
    draw.circle(c, 8 + flash * 28 + thisRMS * 18, fill = "rgba(255,255,255,${0.05 + flash * 0.14})")
}
```

## 3. Glass phase · 60

<!-- field-box id="madp_score_glass_a" index="2" -->

### Narrative

From `code`

Philip Glass's phasing, done with box widths: this box and the one below run the same code,
six pulses a pass each, but a wider box takes longer to cross, so the two pulses drift apart.
Stretch either box and listen to the phase move.

```edn [field-box id="madp_score_glass_a" role="properties"]
{"id" "madp_score_glass_a" "index" 2 "name" "Glass phase · 60" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Glass phase · 60" "_tombstone" false "frame" #afield/rect [1070.0 300.0 320.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_score_glass_a_code_.kt" "lines" 11 "characters" 665 "sha256" "d86cc42aeaf5e529c58f47ed5781d1155df0ea2b10176c0d2099b33f8341fa01"}]}
```

```kotlin [field-property box="madp_score_glass_a" name="code" path="madp_score_glass_a_code_.kt"]
// Philip Glass's phasing, done with box widths: this box and the one below run the same code,
// six pulses a pass each, but a wider box takes longer to cross, so the two pulses drift apart.
// Stretch either box and listen to the phase move.
score {
    for (i in 0 until 8) {
        val u = i / 7.0
        val p = boxPoint(u, 0.5 + sin(localT * 2 + i * 0.4) * 0.06)
        draw.line(p.x, boxPoint(u, 0.2).y, p.x, boxPoint(u, 0.8).y, stroke = "rgba(255, 186, 129, 0.8)", width = 1.2 + thisRMS * 2)
    }
    if (onSubdivision(6, "phase")) sound("/dough/play/note/60/duration/0.1/s/tri/gain/0.15/lpf/1200/release/0.08/verb/0.26/pan/0.22", minInterval = 0.05)
}
```

## 4. score · run me

<!-- field-box id="madp_score_lead" index="3" -->

### Narrative

From `code`

The canvas is the score. Run this box: the orange playhead sweeps from here to the right once
every eleven seconds, and every box it crosses runs its score { } block each frame, drawing into the
dark panel below and playing dough sounds. Every box in the score is armed when this one starts.

Edit a box and run it again to change what it does; stop a box to silence it; drag a box to move
it in time, or stretch it to make it last longer. Inside score { }: localProgress (0 to 1 across
the box), localT (seconds into it), justEntered, onSubdivision(n), boxPoint(u, v), draw.*,
sound(path), thisRMS (how loud this box's own sound is right now) and box.move/set.

```edn [field-box id="madp_score_lead" role="properties"]
{"id" "madp_score_lead" "index" 3 "name" "score · run me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "score · run me" "_tombstone" false "frame" #afield/rect [64.0 60.0 200.0 140.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_score_lead_code_.kt" "lines" 22 "characters" 1201 "sha256" "4c043dd5b0948b054c40c1c01d2a3321a6b6dd72c8d3f1adfe8dc46de9378907"}]}
```

```kotlin [field-property box="madp_score_lead" name="code" path="madp_score_lead_code_.kt"]
/*
The canvas is the score. Run this box: the orange playhead sweeps from here to the right once
every eleven seconds, and every box it crosses runs its score { } block each frame, drawing into the
dark panel below and playing dough sounds. Every box in the score is armed when this one starts.

Edit a box and run it again to change what it does; stop a box to silence it; drag a box to move
it in time, or stretch it to make it last longer. Inside score { }: localProgress (0 to 1 across
the box), localT (seconds into it), justEntered, onSubdivision(n), boxPoint(u, v), draw.*,
sound(path), thisRMS (how loud this box's own sound is right now) and box.move/set.
*/
score {
    val center = boxPoint(0.5, 0.5)
    val size = min(bounds.w * scaleX, bounds.h * scaleY)
    val radius = size * (0.12 + thisRMS * 0.42 + tri01(localT * 2) * 0.14)

    draw.ring(center, radius, color = "rgba(255, 238, 205, 0.96)", width = 2.5)
    draw.circle(center, radius * 0.18, fill = "rgba(255, 238, 205, 0.18)")

    if (justEntered) sound("/dough/play/note/48/duration/0.18/s/sine/gain/0.45/lpf/900/release/0.14/verb/0.25")
}

playScore(loop = 11.0, x = 40.0..1880.0, y = 40.0..600.0, width = 520, height = 320)
```

## 5. playhead

<!-- field-box id="madp_score_playhead" index="4" -->

### Narrative

From `code`

The score box moves this line across the canvas; nothing to run here. Its look is the
`customization` the score box gives it, the same trick as Field's time slider.

```edn [field-box id="madp_score_playhead" role="properties"]
{"id" "madp_score_playhead" "index" 4 "name" "playhead" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "playhead" "_tombstone" false "frame" #afield/rect [40.0 40.0 12.0 560.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_score_playhead_code_.kt" "lines" 2 "characters" 171 "sha256" "4f8fd77b205708e1db799586ecebdb2b161868896163db57192edeb22de91359"}]}
```

```kotlin [field-property box="madp_score_playhead" name="code" path="madp_score_playhead_code_.kt"]
// The score box moves this line across the canvas; nothing to run here. Its look is the
// `customization` the score box gives it, the same trick as Field's time slider.
```

## 6. McLaren scratches

<!-- field-box id="madp_score_scratches" index="5" -->

### Narrative

From `code`

Norman McLaren scratched straight onto film: nine lines that twitch every frame, a grain of
noise seven times on the way across, and a click on the way in.

```edn [field-box id="madp_score_scratches" role="properties"]
{"id" "madp_score_scratches" "index" 5 "name" "McLaren scratches" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "McLaren scratches" "_tombstone" false "frame" #afield/rect [620.0 330.0 180.0 160.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_score_scratches_code_.kt" "lines" 15 "characters" 862 "sha256" "18aabb0ee57fdb7f4fe143a41a391e91651bbbe28f202f8be6f71a18f6afcd4d"}]}
```

```kotlin [field-property box="madp_score_scratches" name="code" path="madp_score_scratches_code_.kt"]
// Norman McLaren scratched straight onto film: nine lines that twitch every frame, a grain of
// noise seven times on the way across, and a click on the way in.
score {
    for (i in 0 until 9) {
        val u = i / 8.0
        val a = boxPoint(0.0, u)
        val b = boxPoint(1.0, 1 - u)
        val kick = sin(localT * 26 + 0.1 + i * 0.75) * (10 + thisRMS * 24)
        draw.line(a.x, a.y + kick, b.x, b.y - kick, stroke = "rgba(255,255,255,${0.12 + u * 0.62})", width = 1 + u * 1.2)
    }
    draw.circle(boxPoint(0.5 + sin(localT * 18 + 0.1) * 0.2, 0.5), 4 + thisRMS * 11, fill = "rgba(255, 107, 107, 0.88)")

    if (onSubdivision(7, "grain")) sound("/dough/play/s/white/duration/0.03/gain/0.11/lpf/5200/release/0.02/pan/0.45", minInterval = 0.035)
    if (justEntered) sound("/dough/play/note/72/duration/0.04/s/pulse/gain/0.12/lpf/1800/release/0.03")
}
```

## 7. duck

<!-- field-box id="madp_score_duck" index="6" -->

### Narrative

From `code`

A silly one, done rigorously: a duck that bobs, drifts a little across the score each pass
(box.move), and quacks twice.

```edn [field-box id="madp_score_duck" role="properties"]
{"id" "madp_score_duck" "index" 6 "name" "duck" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "duck" "_tombstone" false "frame" #afield/rect [1640.0 300.0 200.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_score_duck_code_.kt" "lines" 21 "characters" 1068 "sha256" "0b89aea66b2713c627685e14aa7841a7580b288603af0682b4db801aeae32162"}]}
```

```kotlin [field-property box="madp_score_duck" name="code" path="madp_score_duck_code_.kt"]
// A silly one, done rigorously: a duck that bobs, drifts a little across the score each pass
// (box.move), and quacks twice.
score {
    box.move(sin(transportT * 1.4) * 9 * dt, sin(transportT * 3.1) * 6 * dt)

    val body = boxPoint(0.45, 0.62)
    val head = boxPoint(0.68, 0.34 + sin(localT * 4) * 0.06)
    draw.circle(body, 26 + thisRMS * 12, fill = "rgba(255, 224, 96, 0.94)")
    draw.circle(head, 14 + thisRMS * 8, fill = "rgba(255, 224, 96, 0.94)")
    draw.circle(head.x + 5, head.y - 4, 3.0, fill = "rgba(18,18,18,0.95)")
    draw.polyline(
        listOf(pt(head.x + 10, head.y - 6), pt(head.x + 30 + thisRMS * 10, head.y), pt(head.x + 10, head.y + 6)),
        closed = true,
        fill = "rgba(255, 128, 52, 0.96)",
        stroke = "rgba(255, 153, 74, 0.96)",
        width = 1.4,
    )

    if (justEntered) sound("/dough/play/note/64/duration/0.08/s/pulse/pw/0.42/gain/0.16/lpf/1500/release/0.05")
    if (onSubdivision(2, "quack")) sound("/dough/play/note/69/duration/0.06/s/pulse/pw/0.28/gain/0.16/lpf/1300/release/0.05", minInterval = 0.08)
}
```

## 8. Fischinger orbits

<!-- field-box id="madp_score_orbits" index="7" -->

### Narrative

From `code`

Oskar Fischinger's geometry: a planet on a ring, and a bell three times a pass.

```edn [field-box id="madp_score_orbits" role="properties"]
{"id" "madp_score_orbits" "index" 7 "name" "Fischinger orbits" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Fischinger orbits" "_tombstone" false "frame" #afield/rect [840.0 70.0 200.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_score_orbits_code_.kt" "lines" 10 "characters" 612 "sha256" "d234c05b0cb26efd96812ad7cc26d12adaa2413c477daa4259b118d6f68de802"}]}
```

```kotlin [field-property box="madp_score_orbits" name="code" path="madp_score_orbits_code_.kt"]
// Oskar Fischinger's geometry: a planet on a ring, and a bell three times a pass.
score {
    val c = boxPoint(0.5, 0.5)
    val q = boxPoint(0.5 + cos(localT * 2.1) * 0.34, 0.5 + sin(localT * 2.1) * 0.34)
    draw.ring(c, min(bounds.w * scaleX, bounds.h * scaleY) * 0.32, color = "rgba(245, 202, 92, 0.9)", width = 2.0)
    draw.line(c, q, stroke = "rgba(245, 202, 92, 0.45)", width = 1.6)
    draw.circle(q, 7 + thisRMS * 14, fill = "rgba(245, 202, 92, 0.92)")

    if (onSubdivision(3, "bell")) sound("/dough/play/note/72/duration/0.12/s/sine/gain/0.15/lpf/2600/release/0.1/verb/0.42", minInterval = 0.08)
}
```

