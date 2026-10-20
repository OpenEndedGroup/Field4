# Score → Field·4: port design

Source: `~/temp/aaaframe/madp/score.html` (served at `:9982/score.html`). The demo is a looping 2D
score: rectangles on a timeline, an orange playhead sweeping left to right over a loop of a few
seconds, and each rectangle's `function score(t, bounds)` running every frame while the playhead
crosses its horizontal span, drawing into a separate output canvas and triggering dough sounds.

## The Field idea

**Field's canvas is the score.** The rectangles are ordinary Field boxes; the playhead is a box
too, a thin orange line on the canvas that the transport moves every frame. A box takes part by
running `score { ... }` once: that arms its behaviour, and the transport calls it every frame while
the playhead is over the box's frame. Re-running the box replaces the behaviour (the live-coding
loop of the demo's editor), stopping it disarms it.

This sits beside Field's existing time slider (`templates/timeSlider`, `funFeatures.md` "Time
sliders & Custom UI"), which `begin()`s and `end()`s whole boxes as a dragged line passes over them.
That mechanism launches a box's whole code on entry, which costs a compile and cannot give a box a
per-frame `localProgress`. The score runner keeps the slider's model (a vertical line box that
selects the boxes whose frames it intersects, `Rect.intersects(x)`) and the slider's look (a
customised line box), and adds what the demo needs: a clock, per-frame calls into an
already-compiled lambda, and entry/exit/subdivision bookkeeping. It runs inside one ordinary
`loop { }` (`animation_time_model.md`), timed by `AnimationClock.nowSeconds()`, so a recorded
video of it plays at the right speed.

## API mapping

| score.html | Field |
| --- | --- |
| `function score(t, bounds) { … }` in the selected box | `score { … }` as the box's last expression; receiver is a `ScoreFrame` |
| the transport (toolbar Loop, Pause) | the lead box: `playScore(loop = 4.0, x = …, y = …)` |
| orange playhead | a box named `playhead` (customised as an orange line); the transport moves it |
| right-hand output canvas | an output panel on the lead box: a dark canvas drawn from the frame's calls |
| `t`, `dt`, `frame`, `transportT`, `loopIndex`, `loopDuration`, `playheadX`, `progress` | same names on `ScoreFrame` |
| `bounds` (`x y w h left top right bottom cx cy`) | `bounds: Rect` plus `left/top/right/bottom/cx/cy` on `ScoreFrame` |
| `localProgress`, `localT`, `boxDuration` | same |
| `justEntered`, `justExited` | same; the box gets one last call with `justExited = true` |
| `onSubdivision(n, key)` | same |
| `boxPoint(u, v)`, `fromScore(x, y)` | same, returning `Vector2f` |
| `width/height`, `scoreWidth/scoreHeight`, `scaleX/scaleY` | same |
| `draw.line/circle/ring/rect/polyline(…, {stroke, fill, color, width, alpha, closed})` | `draw.line(…, stroke = "rgba(…)", width = 2.0)` — named arguments, CSS colour strings carried over verbatim |
| `sound(path, {key, minInterval, force})`, `soundNow(path)` | `sound(path, key = …, minInterval = …, force = …)`, `soundNow(path)` — **shared, box-level** |
| `thisRMS` (estimated from the path's envelope numbers) | `thisRMS` **measured** from the box's own dough voices |
| `box.get/set/move/resize` | same, on the box's canvas `frame` |
| `state` | dropped: a `val`/`var` above `score { }` lives as long as the armed behaviour |
| `clamp lerp triWave tri01 rand randInt` | `clamp`, `lerp`, `triWave`, `tri01`, `rand`, `randInt` on `ScoreFrame` |

## Shared code

1. **`afield.audio.synth.DoughLive`** — the box-level dough helper Score owns, for charGrid and Line
   to reuse. One process-wide dough engine, built and rendered on its own thread (a `DoughEngine`
   admits only its constructing thread), handed to the audio thread through a small
   `PatSpatialRing`. `Box.sound(path, key, minInterval, force)` throttles per box and key, and tags
   the event with dough's own `group` parameter (an int per box). After every 128-frame block the
   render thread reads each live voice's output sample and group out of wasm memory and keeps a
   per-group running mean square, so `Box.thisRMS` is a measurement of what that box's voices are
   actually producing (dry, before the shared reverb), not a re-simulation of the envelope.
   Normalised so a sine at `gain/1` reads about 1, which keeps the demo's `thisRMS * 18` scale.
2. **`afield.animation.score.ScoreRunner`** — `playScore(...)` (the transport and output view) and
   `score { }` (arming a box), the `ScoreFrame` receiver, the draw-call list and its canvas view.
   The output view is an output-panel iframe drawing the latest frame's calls on a 2D canvas, the
   same pull-a-JSON-frame pattern `orca()` uses (`outputRequests`); no student code runs in it.

## Starting point

`dev_docs/agents/transcripts/madp_score.yaml`: the lead box (`score · run me`: the demo's
starter ring as its own `score { }`, then `playScore(loop = 11.0, …)`), the `playhead` box, and six
preset boxes along an 11-second score: McLaren scratches, Fischinger orbits, two Glass phase boxes
(the same code at two widths, so their six-a-pass pulses drift apart: the canon/phase idea carried
by box geometry), a jump-cut box that `box.set`s itself near the playhead, and the duck that
`box.move`s. Running the lead runs every unarmed box inside the score's span, the way the time
slider runs what it crosses, so one run plays the whole document. The lead's panel sits in a gap of
the score (x 64–584) so it covers no box.

Verified from a live capture (Playwright, `record-window`, 14 s at 6 fps): every box draws in the
panel at its place in the score, both Glass boxes sound and draw together around 7 s, and the jump
box moves on the canvas.
## Dropped, and why

- `?room=` Yjs collaboration: out of scope (PLAN.md); recorded as a gap.
- In-page Monaco, AI ghost completion, agent chat, local saves, "Copy LLM Intro": Field's own
  editor, completion, AI4, documents/history, and KDoc stand in for them.
- Drag-to-create boxes on a score pane: Field's canvas already does that.
- The preset dropdown: a preset is a set of boxes; here the best box of each is on the canvas.

## Gaps

- `?room=` collaboration (Yjs) is not ported.
- Active boxes are not tinted on the canvas as the demo's are (Field shows only the lead as running).

- `thisRMS` excludes reverb/delay tails (they are mixed after the voices in one shared orbit).
- The level is sampled once per block (2.7 ms at 48 kHz) and smoothed over ~40 ms, so it is a
  measurement with some jitter rather than an exact per-sample RMS.
- Latency: a triggered sound is heard after the ring (~40 ms) plus `FieldAudioEngine`'s queue.
