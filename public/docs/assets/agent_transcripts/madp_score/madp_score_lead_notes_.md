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
