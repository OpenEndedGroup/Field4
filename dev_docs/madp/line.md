# Line → Field·4: port design

Source: `~/temp/aaaframe/madp/line.html` (served at `:9982/line.html`). The demo is a black canvas
with an editor beside it. Each stroke you draw becomes a living object. When you lift the mouse, the
editor's current `onStart(inputPoints, lineIndex)` and `manipulatePoints(inputPoints, t)` are
snapshotted onto that stroke. `onStart` runs once and may return a Strudel pattern; every stroke's
pattern is stacked and played. `manipulatePoints` runs every frame on the stroke's own physics
points and returns draw commands. The canvas fades to black at `1 − trail` each frame, so motion
leaves trails. Seventeen presets cover pure visuals, curve maths, lines that react to each other,
and lines whose drawing and sound drive each other.

## The Field idea

**The canvas is the page you draw on, and a box is the pen.** You draw with Field's drawing layer
(Alt+D, the freedraw tool). A box that runs `eachStroke { … }` takes the pen. From then on, every
stroke drawn by hand is handed to it: `notation.onStroke` gives the stroke's world points, the hand
mark is removed from the scene, and a living stroke takes its place. The block is the per-stroke
program, and it is captured when the stroke is drawn. Edit the box and run it again, and only the
strokes you draw next use the new code. The others keep theirs, as in the demo. Running a different
preset box gives it the pen.

Living strokes are drawn on a **stroke layer**, a world-space canvas laid over the Field canvas
between the background and the boxes. It follows pan and zoom, and it fades for trails. Boxes and
their output panels stay on top and readable, and the drawing happens on the canvas's own dark
ground, where the strokes were drawn.

## API mapping

| line.html | Field |
| --- | --- |
| `onStart(inputPoints, lineIndex)` | the body of `eachStroke { … }`, with `points` and `index` on the receiver (`Stroke`) |
| return a Strudel pattern from `onStart` | `sound(pattern)` inside the body |
| `manipulatePoints(inputPoints, t)` | `draw { t -> … }` inside the body; returns `List<FLine>` |
| `ctrl.x` (per line, shared by sound and image) | a local `var x` in the body: both `draw { }` and the pattern's `signal { }` close over it |
| `inputPoints[i].x/y/vx/vy/ax/ay` | `points[i].x/y/vx/vy/ax/ay` (`StrokePoint`); the runtime zeroes `ax/ay`, then integrates at dt 1/60, damping 0.98 |
| `{x, y}` polyline | `polyline(curve)` (Field's `polylineFrom`) or `FLine().moveTo(…).lineTo(…)` |
| `connected:false` | `moveTo` in the same FLine |
| `smooth:true` (centripetal Catmull-Rom) | `smooth(curve)` → new `catmullRomCentripetal` in `FLineGeometry.kt` |
| `arc:true` | `arcs(curve)`: each node an arc through the previous two |
| `{x0,y0,x1,y1}` segments | subpaths of one FLine (`segments { … }`, `atomize(…)`) |
| `{dot: r}` | `dots(curve, r)`: a `pointed`, unstroked FLine, radius per node |
| `color` | `FLine.color`, or a node's `color` for per-segment or per-dot colour; `cssHsla(h, s, l, a)` is CSS's `hsla()` |
| `fill` | `FLine.filled` (a second, unstroked copy where the fill colour differs from the stroke) |
| `overshoot` | `FLine.overshoot` (each subpath's ends) and `FLine.Node.overshoot` (that subpath's end) |
| `ignore:true` | `FLine.ignore = true` |
| `closestPoint(x, y)` | `closestPoint(x, y)`: other strokes' physics points |
| `closestOutput(x, y)` | `closestOutput(x, y)`: other strokes' previous frame of output |
| `atomize dots wiggle warp combine resample` | the same names, on curves (`List<Vec2>`) and FLines; `combine` is `listOf` |
| `add subtract project scale transformTo` | the same names and arithmetic, on curves |
| `rms()` | `rms()`: the measured level of everything the strokes play (`DoughLive`) |
| `triWave tri01 clamp01 perlin2D heldNoise driftNoise` | the same names and the same Perlin table |
| trail (0..1) | `eachStroke(trail = 0.18)`; the pen's trail is the layer's |
| Reset | `clearStrokes()` |

### Strudel → Field's pattern language

The demo's sound is Strudel: `note("c3 e3 g3 b3").sound("sawtooth").gain(signal(() => …)).lpf(…).
pan(…).room(0.45).stack(…)`. Field already has the same model, Tidal's: a pattern is a function
from a query arc to events, and `with` attaches parameters, which may themselves be continuous
patterns sampled at each event. So the mapping adds no new pattern machinery:

| Strudel | Field |
| --- | --- |
| `note("c3 e3 g3")` | `pat("c4 e4 g4")`: a note-name atom is a note |
| `s("bd hh cp")` | `pat("bd hh cp")`: a word atom is a sound |
| `.sound("sawtooth")` | `with s("sawtooth")` |
| `.gain(x)` `.pan(x)` `.room(x)` `.speed(x)` | `with gain(x)`, `with pan(x)`, `with room(x)`, `with speed(x)` (existing params) |
| `.lpf(x)` | `with lpf(x)` |
| `signal(() => expr)` | `signal { expr }`: a continuous pattern read when each event is scheduled |
| `.stack(p)` | `stack(a, b)` |
| `.analyze(1)` | not needed: `rms()` is measured from dough's voices directly |

Two differences are deliberate. **Octaves**: Field's note names are Tidal's (`c5` = MIDI 60), not
Strudel's (`c4` = 60), so every preset's note names are written an octave higher than in the demo,
and they sound at the same pitch. **Synthesis**: Strudel's WebAudio synths are replaced by
`dough.wasm`, the engine the other madp demos already use. Its `s/sawtooth`, `s/triangle`,
`lpf`, `room`, `pan` and `gain` are the same parameters, and dough synthesises `bd`, `hh` and `cp`
itself, so the drum preset needs no sample library.

**Level**: Strudel's synths are quieter than dough's at the same `gain`. Measured with the same
three strokes on the prism choir, the demo's `rms()` read 0.046 in Chrome and dough's read 0.19, so
stroke patterns play at a quarter of dough's level (`STRUDEL_LEVEL`). The demo's gains then sound as
loud as they did, and the drawing that `rms()` drives moves as much.

The patterns run on the shared `DoughLive` engine (built for the Score port). A new
`DoughLive.play(pattern, cps)` queries the pattern on the render thread, one 128-frame block at a
time, so onsets are block-accurate (2.7 ms) and each `signal { }` is read at its event's scheduling
time, as Strudel reads its `signal`. All strokes share one clock at 0.5 cycles per second, Strudel's
default. `cycle` on the stroke says where that clock is, so the step-sequencer presets' cursors
point at the step that is sounding.

## Shared code

1. **`afield.ui.strokes`**: `Box.eachStroke`, `Stroke`, `StrokePoint`, the per-document
   `StrokeSketch` (strokes, pen, a fixed 60 Hz step on the main loop, publishing frames), and the
   curve verbs. The steps call each stroke's code in its own box's context, so an exception is
   shown on that box, once per message, and the sketch carries on.
2. **The stroke layer** (`src/jsMain/kotlin/afield/app/canvas/strokeLayer.kt`): a 2D canvas in
   `.field-main-viewport` above the WebGL background. It draws each frame's FLines in world
   coordinates through the main pane's camera, fades by `trail` per 1/60 s of wall time, and acks
   each frame (`stroke-layer-frame` / `stroke-layer-ack` over `/ws`), so the server never sends
   faster than a window can draw.
3. **FLine additions** in `FLineGeometry.kt`: `catmullRomCentripetal` (the demo's centripetal
   spline; Field's `catmullRomToBezier` is uniform), and the `overshoot` and `ignore` properties.
4. **`cssHsla`** in `colors.kt`: CSS's `hsla()`. Field's `hsla` deliberately is not CSS (it treats
   the HSL cube as linear light and wraps small hues as turns), which would wash out the demo's neon.
5. **`DoughLive.play(pattern, cps)`** and the Strudel-named params `s`, `lpf` and `signal`.

## Starting point

`dev_docs/agents/transcripts/madp_line.yaml`: the lead box **prism choir** (sound, the demo's
default), and five presets: **ribbon bloom** (filled, visual), **comet dust** (atomize, dots, long
trails), **curve choir** (curve maths), **magnet mesh** (between lines, physics), and **step
lattice** (sound-coupled: a sequencer grid whose cursor is the sounding step). Run a box to take
the pen, press Alt+D, choose the pencil, draw.

## Dropped, and why

- The in-page Monaco editor, AI ghost completion, agent chat, local saves and "Copy LLM Intro":
  Field's editor, completion, AI4, documents and KDoc stand in for them.
- The preset dropdown: a preset is a box on the canvas. Six are ported; the other eleven are left
  out because they repeat a mechanism already shown here.
- `.analyze(1)`: `rms()` is measured from dough's voices.

## Compared with the demo

Each box was run in Field with real strokes. The strokes were driven through the drawing layer's
own freedraw tool by pointer events, so they went to `notation.onStroke` by the same path a hand
takes. The same strokes, by mouse events, went into the demo in Chrome. What came out:

- **prism choir, ribbon bloom, comet dust, curve choir**: the same shapes and motion. The curve
  choir's small knot at the centre is the demo's own arithmetic: `subtract(base, wind)` cancels
  the stroke's shape.
- **step lattice** snaps a stroke to the same melody as the demo. The demo never draws its snapped
  melody line, because `connected: snappedLine.length === 0` starts a new segment at every point
  after the first. The port draws the line that code meant.
- **magnet mesh**: strokes pull together and knit, as the demo's equations say. In Chrome the
  demo's strokes tangled far more within 6 s. A replica of its update rule for the same number of
  steps moves no point more than 89 px (speed caps at 11.8 px/s), so that tangle does not follow
  from the demo's own physics. It is not reproduced here.
- **Frame rate**: the demo steps its physics once per `requestAnimationFrame` with dt 1/60, so on
  a 120 Hz display (Chrome measured 120 fps) its strokes move twice as fast as on a 60 Hz one. The
  port steps at 60 per second on every machine, the rate the constants were written for.

## Gaps

- Strokes are runtime state, as in the demo: they are not saved with the document.
- The stroke layer draws through the main pane only; on a split canvas the other panes do not show
  it.
- Panning or zooming clears the trails (a trail is a screen-space fade).
- `rms()` excludes dough's reverb tail, which is mixed after the voices.
- Nothing here was drawn by a real hand: the strokes came from pointer events in a headless page.
  Excalidraw's freedraw records about twice as many points as the demo's mouse handler.
