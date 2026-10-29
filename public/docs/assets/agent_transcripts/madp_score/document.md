# Field Document: madp_score

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 8 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Glass phase · 67

<!-- field-box id="madp_score_glass_b" index="0" -->

### Narrative

From `notes`

Glass phase · 67

From `code`

The same as the box above, a fifth higher and a little narrower.

```edn [field-box id="madp_score_glass_b" role="properties"]
{"id" "madp_score_glass_b" "index" 0 "name" "Glass phase · 67" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Glass phase · 67" "_tombstone" false "frame" #afield/rect [1140.0 450.0 260.0 110.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_score_glass_b_notes_.md" "lines" 3 "characters" 120 "sha256" "ebabcef57329a3cffb1f1e41caba72629638c360b2c4cfeab2799b468d0df338"} {"name" "code" "language" "kotlin" "path" "madp_score_glass_b_code_.kt" "lines" 20 "characters" 615 "sha256" "a8193e1661cccff817abbf6232430137944abfa846bc98ac0d65a5ca7d3d9e08"}]}
```

```markdown [field-property box="madp_score_glass_b" name="notes" path="madp_score_glass_b_notes_.md"]
# Glass phase · 67

The partner of **Glass phase · 60**: the same code a fifth higher, panned right, in a narrower box.
```

```kotlin [field-property box="madp_score_glass_b" name="code" path="madp_score_glass_b_code_.kt"]
// The same as the box above, a fifth higher and a little narrower.
score {
    for (i in 0 until 8) {
        val u = i / 7.0
        val p = boxPoint(u, 0.5 + sin(localT * 2 + i * 0.4) * 0.06)
        draw.line(
            p.x,
            boxPoint(u, 0.2).y,
            p.x,
            boxPoint(u, 0.8).y,
            stroke = "rgba(174, 246, 255, 0.8)",
            width = 1.2 + thisRMS * 2,
        )
    }
    if (onSubdivision(6, "phase"))
        sound(
            "/dough/play/note/67/duration/0.1/s/tri/gain/0.15/lpf/1200/release/0.08/verb/0.26/pan/0.62",
            minInterval = 0.05,
        )
}
```

## 2. jump cut

<!-- field-box id="madp_score_jump" index="1" -->

### Narrative

From `notes`

jump cut

From `code`

A box that cuts itself to somewhere else in the score: on the way in, and five times on the
way across, it jumps near the playhead and leaves a fading ghost of where it was. The box
really moves on the canvas; its frame is part of the instrument.

```edn [field-box id="madp_score_jump" role="properties"]
{"id" "madp_score_jump" "index" 1 "name" "jump cut" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "jump cut" "_tombstone" false "frame" #afield/rect [1430.0 110.0 160.0 120.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_score_jump_notes_.md" "lines" 6 "characters" 326 "sha256" "748bc1c009322a10b90a84ee5bb952ecadd66baf8654d2ff39e54e0e13a78962"} {"name" "code" "language" "kotlin" "path" "madp_score_jump_code_.kt" "lines" 61 "characters" 2352 "sha256" "e4de11aa52a0842687497970949b3d3f736faea2c1f8b9c42a2cbbe9bd1e9b80"}]}
```

```markdown [field-property box="madp_score_jump" name="notes" path="madp_score_jump_notes_.md"]
# jump cut

A box that edits the score while it plays. On the way in and five times on the way across, it picks a
new size and a place near the playhead and moves there with `box.set`, leaving a fading ghost of where
it was. It really moves on the canvas, so the score is different every pass. Drag it back whenever you
like.
```

```kotlin [field-property box="madp_score_jump" name="code" path="madp_score_jump_code_.kt"]
/*
A box that cuts itself to somewhere else in the score: on the way in, and five times on the
way across, it jumps near the playhead and leaves a fading ghost of where it was. The box
really moves on the canvas; its frame is part of the instrument.
*/
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
        sound(
            "/dough/play/note/${55 + (jumps % 5) * 2}/duration/0.06/s/pulse/pw/0.22/gain/0.13/lpf/${700 + (jumps % 7) * 220}/release/0.04/pan/0.18",
            key = "jump",
            minInterval = 0.03,
        )
    }

    if (justEntered) jump()
    if (onSubdivision(5, "jump-grid")) jump()

    ghosts.forEach { it.life *= 0.84 }
    ghosts.removeAll { it.life < 0.06 }
    for (g in ghosts) {
        val p = fromScore(g.x, g.y)
        draw.rect(
            p.x - g.w * scaleX / 2,
            p.y - g.h * scaleY / 2,
            g.w * scaleX,
            g.h * scaleY,
            stroke = "rgba(255,255,255,${g.life * 0.28})",
        )
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

From `notes`

Glass phase · 60

From `code`

Philip Glass's phasing, done with box widths: this box and the one below run the same code,
six pulses a pass each, but a wider box takes longer to cross, so the two pulses drift apart.
Stretch either box and listen to the phase move.

```edn [field-box id="madp_score_glass_a" role="properties"]
{"id" "madp_score_glass_a" "index" 2 "name" "Glass phase · 60" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Glass phase · 60" "_tombstone" false "frame" #afield/rect [1070.0 300.0 320.0 110.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_score_glass_a_notes_.md" "lines" 6 "characters" 351 "sha256" "4d7f3e76aca499b045f1cb9446750a0601682d66a40aee2de6a771262d06b3ee"} {"name" "code" "language" "kotlin" "path" "madp_score_glass_a_code_.kt" "lines" 24 "characters" 788 "sha256" "b31275cfe876ae8f815189f661b7f8be99512a9e042959af0e9dcf0cbc41880c"}]}
```

```markdown [field-property box="madp_score_glass_a" name="notes" path="madp_score_glass_a_notes_.md"]
# Glass phase · 60

After Philip Glass. This box and **Glass phase · 67** run the same code: eight bars and six pulses per
pass. They differ in width, so the playhead takes longer to cross this one and its pulses come further
apart, and where the two overlap the two pulses drift against each other. Stretch either box and
listen to the phase change.
```

```kotlin [field-property box="madp_score_glass_a" name="code" path="madp_score_glass_a_code_.kt"]
/*
Philip Glass's phasing, done with box widths: this box and the one below run the same code,
six pulses a pass each, but a wider box takes longer to cross, so the two pulses drift apart.
Stretch either box and listen to the phase move.
*/
score {
    for (i in 0 until 8) {
        val u = i / 7.0
        val p = boxPoint(u, 0.5 + sin(localT * 2 + i * 0.4) * 0.06)
        draw.line(
            p.x,
            boxPoint(u, 0.2).y,
            p.x,
            boxPoint(u, 0.8).y,
            stroke = "rgba(255, 186, 129, 0.8)",
            width = 1.2 + thisRMS * 2,
        )
    }
    if (onSubdivision(6, "phase"))
        sound(
            "/dough/play/note/60/duration/0.1/s/tri/gain/0.15/lpf/1200/release/0.08/verb/0.26/pan/0.22",
            minInterval = 0.05,
        )
}
```

## 4. score · run me

<!-- field-box id="madp_score_lead" index="3" -->

### Narrative

From `notes`

Score: the canvas as a timeline

From `code`

The canvas is the score. Run this box: the orange playhead sweeps from here to the right once
every eleven seconds, and every box it crosses runs its score { } block each frame, drawing into the
dark panel below and playing dough sounds. Every box in the score is armed when this one starts.

Edit a box and run it again to change what it does; stop a box to silence it; drag a box to move
it in time, or stretch it to make it last longer. Inside score { }: localProgress (0 to 1 across
the box), localT (seconds into it), justEntered, onSubdivision(n), boxPoint(u, v), draw.*,
sound(path), thisRMS (how loud this box's own sound is right now) and box.move/set.

```edn [field-box id="madp_score_lead" role="properties"]
{"id" "madp_score_lead" "index" 3 "name" "score · run me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "score · run me" "_tombstone" false "frame" #afield/rect [64.0 60.0 200.0 140.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_score_lead_notes_.md" "lines" 59 "characters" 3409 "sha256" "393a45531cc3d574d34e65c1a056bb473295dc652badc8978fb0994ea2d47ad9"} {"name" "code" "language" "kotlin" "path" "madp_score_lead_code_.kt" "lines" 22 "characters" 1201 "sha256" "4c043dd5b0948b054c40c1c01d2a3321a6b6dd72c8d3f1adfe8dc46de9378907"}]}
```

```markdown [field-property box="madp_score_lead" name="notes" path="madp_score_lead_notes_.md"]
# Score: the canvas as a timeline

This document is Field's version of **Score**, the madp page `score.html`. There, rectangles sit on a
looping timeline, an orange playhead sweeps across them every few seconds, and each rectangle owns a
little program, `score(t, bounds)`, that runs every frame while the playhead is over it. The programs
draw into a dark output canvas and make sounds, so a rectangle's position is *when* it happens, its
width is *how long*, and its height and place on the page are *where* its drawing lands.

## What it teaches

Time as something you can see and edit. Moving a box moves an event in time; stretching it makes it
last longer; two boxes that overlap play together. The presets come from the animators and composers
who worked this way: Norman McLaren's scratched, flickering film, Oskar Fischinger's rhythmic
geometry, and Philip Glass's pulses that drift in and out of phase. Each one is a few lines of drawing
and one or two sounds, tied together by when the playhead reaches them.

## How to play

1. **Run this box.** The orange `playhead` starts sweeping from here to the right once every eleven
   seconds, and every box in the score is armed.
2. **Watch the panel below.** It is the output canvas. Each box draws there, at the place it sits in
   the score, only while the playhead crosses it, and the box lights up on the canvas in a colour of
   its own while it plays.
3. **Edit a box and run it again** to change what it does, live. Stop a box to silence it. Drag it to
   move it in time, stretch it to make it last longer.

Inside `score { }` you have:

- `localProgress`, 0 to 1 across the box, and `localT`, seconds since the playhead reached it;
- `justEntered` on the first frame, and `onSubdivision(n)`, true `n` times on the way across, for
  pulses;
- `boxPoint(u, v)`, a point inside the box in output pixels (0, 0 its top left), and
  `draw.line/circle/ring/rect/polyline`, with colours as CSS strings;
- `sound(path)`, which plays a dough path (`/dough/play/note/60/s/sine/gain/0.2/...`, the same strings
  as the browser demo), at most once per `minInterval` for the same `key`;
- `thisRMS`, how loud this box's own sounds are right now, measured from its voices, so a shape can
  swell with its note;
- `box.get/set/move/resize`, which move the box itself on the canvas: the instrument can rewrite its
  own place in the score (see **jump cut** and **duck**).

Anything declared above `score { }` lasts between frames, which is where state goes.

## From score.html to Field

| score.html | here |
| --- | --- |
| the score pane | Field's canvas: the boxes are the rectangles |
| the orange playhead | the `playhead` box, moved by `playScore` |
| `function score(t, bounds)` in the selected rectangle | `score { }` in each box |
| the output pane | the `score` panel under this box |
| the Loop field | `playScore(loop = 11.0, ...)` |
| `thisRMS` (estimated from the path) | `thisRMS` (measured from the box's dough voices) |
| the lit active rectangle | `Box.tint`, which any box can set |
| `state` | a `val` or `var` above `score { }` |

The demo's shared rooms (`?room=`, Yjs), its in-page AI helpers and its local saves are not here:
Field has its own documents, history and AI4 chat. The implementation is `playScore` and `score { }`
in `afield.animation.motion` and `sound`/`thisRMS` in `afield.audio.synth`, and their KDoc has the
details.
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

From `notes`

playhead

From `code`

The score box moves this line across the canvas; nothing to run here. Its look is the
`customization` the score box gives it, the same trick as Field's time slider.

```edn [field-box id="madp_score_playhead" role="properties"]
{"id" "madp_score_playhead" "index" 4 "name" "playhead" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "playhead" "_tombstone" false "frame" #afield/rect [40.0 40.0 12.0 560.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_score_playhead_notes_.md" "lines" 5 "characters" 286 "sha256" "8b7d00edc170e77f458ce507891b5f13faac8a85d8ac5a00d221afacba48c1c4"} {"name" "code" "language" "kotlin" "path" "madp_score_playhead_code_.kt" "lines" 2 "characters" 171 "sha256" "4f8fd77b205708e1db799586ecebdb2b161868896163db57192edeb22de91359"}]}
```

```markdown [field-property box="madp_score_playhead" name="notes" path="madp_score_playhead_notes_.md"]
# playhead

The line the score's clock moves. `playScore` in the **score · run me** box styles it as an orange line
and slides it across the canvas every frame; a box is played while this line is over it. Nothing to
run here. Delete it and the score still plays, just without the line.
```

```kotlin [field-property box="madp_score_playhead" name="code" path="madp_score_playhead_code_.kt"]
// The score box moves this line across the canvas; nothing to run here. Its look is the
// `customization` the score box gives it, the same trick as Field's time slider.
```

## 6. McLaren scratches

<!-- field-box id="madp_score_scratches" index="5" -->

### Narrative

From `notes`

McLaren scratches

From `code`

Norman McLaren scratched straight onto film: nine lines that twitch every frame, a grain of
noise seven times on the way across, and a click on the way in.

```edn [field-box id="madp_score_scratches" role="properties"]
{"id" "madp_score_scratches" "index" 5 "name" "McLaren scratches" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "McLaren scratches" "_tombstone" false "frame" #afield/rect [620.0 330.0 180.0 160.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_score_scratches_notes_.md" "lines" 5 "characters" 317 "sha256" "0932e2a14d24b1dbe8deeba75069645d4aaf5ef1760e2dfdb352493d20474e98"} {"name" "code" "language" "kotlin" "path" "madp_score_scratches_code_.kt" "lines" 23 "characters" 953 "sha256" "55238076b20bc350ecc2752631d5fba76dc59ed3e047680307f48a7c116b7d16"}]}
```

```markdown [field-property box="madp_score_scratches" name="notes" path="madp_score_scratches_notes_.md"]
# McLaren scratches

After Norman McLaren, who drew and scratched straight onto film. Nine lines twitch every frame, a red
mark darts across, and `onSubdivision(7)` plays a grain of white noise seven times on the way across,
with a short click on entry. Try a wider box: the same seven grains, spread over more time.
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
        draw.line(
            a.x,
            a.y + kick,
            b.x,
            b.y - kick,
            stroke = "rgba(255,255,255,${0.12 + u * 0.62})",
            width = 1 + u * 1.2,
        )
    }
    draw.circle(boxPoint(0.5 + sin(localT * 18 + 0.1) * 0.2, 0.5), 4 + thisRMS * 11, fill = "rgba(255, 107, 107, 0.88)")

    if (onSubdivision(7, "grain"))
        sound("/dough/play/s/white/duration/0.03/gain/0.11/lpf/5200/release/0.02/pan/0.45", minInterval = 0.035)
    if (justEntered) sound("/dough/play/note/72/duration/0.04/s/pulse/gain/0.12/lpf/1800/release/0.03")
}
```

## 7. duck

<!-- field-box id="madp_score_duck" index="6" -->

### Narrative

From `notes`

duck

From `code`

A silly one, done rigorously: a duck that bobs, drifts a little across the score each pass
(box.move), and quacks twice.

```edn [field-box id="madp_score_duck" role="properties"]
{"id" "madp_score_duck" "index" 6 "name" "duck" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "duck" "_tombstone" false "frame" #afield/rect [1640.0 300.0 200.0 150.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_score_duck_notes_.md" "lines" 4 "characters" 186 "sha256" "e4d9586405e697ab05a3cdf26e60d2e878e5b839508dc8426b00e95fd9eb1276"} {"name" "code" "language" "kotlin" "path" "madp_score_duck_code_.kt" "lines" 22 "characters" 1070 "sha256" "03fc524f35cb48b5987bf696c4faebbee8f16d6fe993cbbe5472eef68302e42d"}]}
```

```markdown [field-property box="madp_score_duck" name="notes" path="madp_score_duck_notes_.md"]
# duck

A silly one, done carefully: a duck that bobs, drifts a little across the score with `box.move`, and
quacks on entry and twice on the way across. `draw.polyline` makes its beak.
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
        [pt(head.x + 10, head.y - 6), pt(head.x + 30 + thisRMS * 10, head.y), pt(head.x + 10, head.y + 6)],
        closed = true,
        fill = "rgba(255, 128, 52, 0.96)",
        stroke = "rgba(255, 153, 74, 0.96)",
        width = 1.4,
    )

    if (justEntered) sound("/dough/play/note/64/duration/0.08/s/pulse/pw/0.42/gain/0.16/lpf/1500/release/0.05")
    if (onSubdivision(2, "quack"))
        sound("/dough/play/note/69/duration/0.06/s/pulse/pw/0.28/gain/0.16/lpf/1300/release/0.05", minInterval = 0.08)
}
```

## 8. Fischinger orbits

<!-- field-box id="madp_score_orbits" index="7" -->

### Narrative

From `notes`

Fischinger orbits

From `code`

Oskar Fischinger's geometry: a planet on a ring, and a bell three times a pass.

```edn [field-box id="madp_score_orbits" role="properties"]
{"id" "madp_score_orbits" "index" 7 "name" "Fischinger orbits" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Fischinger orbits" "_tombstone" false "frame" #afield/rect [840.0 70.0 200.0 150.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_score_orbits_notes_.md" "lines" 4 "characters" 207 "sha256" "09db3a7b6b4e0931d52327de1816574a2d7807dc8eb1507e40db441e24f8e6bb"} {"name" "code" "language" "kotlin" "path" "madp_score_orbits_code_.kt" "lines" 11 "characters" 620 "sha256" "944133bd14ff012a856f3e571768930e155ab4248d378b66dfdd68e8893563b7"}]}
```

```markdown [field-property box="madp_score_orbits" name="notes" path="madp_score_orbits_notes_.md"]
# Fischinger orbits

After Oskar Fischinger's rhythmic geometry. A planet circles a ring, and a bell rings three times a
pass (`onSubdivision(3)`). The planet grows with `thisRMS`, so you can see each bell.
```

```kotlin [field-property box="madp_score_orbits" name="code" path="madp_score_orbits_code_.kt"]
// Oskar Fischinger's geometry: a planet on a ring, and a bell three times a pass.
score {
    val c = boxPoint(0.5, 0.5)
    val q = boxPoint(0.5 + cos(localT * 2.1) * 0.34, 0.5 + sin(localT * 2.1) * 0.34)
    draw.ring(c, min(bounds.w * scaleX, bounds.h * scaleY) * 0.32, color = "rgba(245, 202, 92, 0.9)", width = 2.0)
    draw.line(c, q, stroke = "rgba(245, 202, 92, 0.45)", width = 1.6)
    draw.circle(q, 7 + thisRMS * 14, fill = "rgba(245, 202, 92, 0.92)")

    if (onSubdivision(3, "bell"))
        sound("/dough/play/note/72/duration/0.12/s/sine/gain/0.15/lpf/2600/release/0.1/verb/0.42", minInterval = 0.08)
}
```

