# Filming a Timeline

A timeline file says what a film shows at every moment: where each 3D view's camera is, which parts are
hidden, how far a declared motion has gone, what a box's slider or the drag on its drawing is set to, and what
the captions say. It also says where each box output sits on the frame. The backdoor's `film-page` plays it one
frame at a time, so a film of a document comes out the same every time you shoot it, however slow the machine.

This page films one box: a box with a hinged lid, a plan whose right edge you can drag to make it wider, a depth
slider, and a readout of its size.

## The box

**Hinge** draws the same three outputs whenever its width or depth changes. The plan takes drags through
`lines(..., onDrag = …)`, the slider calls back with its value, and the 3D view declares the lid's swing as a
motion (see [Parts that move](three_output.md#parts-that-move)). Two `metadata` entries say what the view was
drawn from, so a film can wait for a drag or a slider to have reached the view:

```diffstatic {data-lang=kotlin}
import afield.geometry.kinematics.RigidMotion
var width = 160.0
var depth = 100.0
fun draw() {
    val body = boxMesh(width, 60.0, depth).translated(0f, 30f, 0f)
    val lid = boxMesh(width, 8.0, depth).translated(0f, 64f, 0f)
    three(name = "model", channel = "model", width = 640, height = 480) {
        mesh(body, id = "body", layer = ["Box"], wireframe = false)
        mesh(lid, id = "lid", layer = ["Lid"], wireframe = false)
        motion("lid", motion = RigidMotion.rotation(vec(0.0, 60.0, -depth / 2), vec(-1.0, 0.0, 0.0), 0.0, 110.0), label = "Lid")
        timeline(periodSeconds = 4.0)
        metadata("edge", width / 2)
        metadata("depth", depth)
        camera(yaw = 30.0, pitch = 25.0)
    }
    val outline = FLine().rect(-width / 2, -depth / 2, width, depth)
    val edge = fline { color = vec(1.0, 0.55, 0.15, 1.0); thickness = 4.0; moveTo(vec(width / 2, -depth / 2, 0.0)); lineTo(vec(width / 2, depth / 2, 0.0)) }
    lines(listOf(outline, edge), channel = "plan", w = 420.0, h = 260.0, framing = LinesFraming.Fixed(-180.0, -100.0, 360.0, 200.0),
        onDrag = { e -> if (e.phase != LinesDragEvent.Phase.END) { width = (2 * e.x).coerceIn(60.0, 340.0); draw() } })
    display("size", "<div style='font:15px ui-monospace,monospace;padding:6px'>${"%.0f".format(width)} × ${"%.0f".format(depth)} mm</div>")
}
slider("depth", 60.0, 160.0, 1.0, depth) { v -> depth = v; draw() }
draw()
```

## The film

This is [the timeline](assets/filming_a_timeline/hinge.film.json) below, filmed at 1280 by 720:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1280x720" hide-toasts=true video="assets/filming_a_timeline/hinge.mp4" fps="30" transcript="../../dev_docs/agents/transcripts/docs_capture/film_timeline.yaml" padding="0" wait="0" timeout="300" embed=true hidden=true name="film-timeline-hinge" alt="A ten-second film on a dark stage. On the left, the box's plan, its depth slider and a size readout; on the right, its 3D view, turning slowly. The plan's orange right edge is dragged out and back, and the box in 3D widens and narrows with it; the depth slider moves and the box deepens and returns; the lid swings open on its hinge and closes; the lid is hidden and shown again. A caption at the bottom left names each step."}
// Film the timeline file with the frame-by-frame filmer `film-page --timeline` uses.
ui.pause(1500)
ui.filmTimeline(vid, "public/docs/assets/filming_a_timeline/hinge.film.json")
```

## The timeline file

A timeline is JSON. This one has a stage of four regions and six tracks:

```diffstatic {data-lang=json}
{
  "format": "field-film-timeline/1",
  "//": "A hinged box, filmed: its plan dragged wider and back, its depth slider moved, its lid swung, its lid hidden and shown.",
  "duration": 10,
  "fps": 30,
  "size": [1280, 720],
  "stage": {
    "background": "#101316",
    "regions": [
      {"id": "model", "box": "Hinge", "channel": "model", "rect": [520, 20, 740, 680]},
      {"id": "plan", "box": "Hinge", "channel": "plan", "rect": [20, 20, 480, 300]},
      {"id": "slider", "box": "Hinge", "channel": "controls", "rect": [20, 340, 480, 50]},
      {"id": "size", "box": "Hinge", "channel": "size", "rect": [20, 400, 480, 50], "scale": 1.6}
    ],
    "caption": {"rect": [20, 560, 480, 140], "style": {"font": "500 26px/1.3 system-ui, sans-serif", "color": "#e8eef2"}}
  },
  "tracks": [
    {"camera": "model", "keys": [
      {"t": 0, "orbit": {"eye": [290, 300, 500], "target": [0, 34, 0], "up": [0, 1, 0], "degreesPerSecond": 12}},
      {"t": 8.5, "pose": {"eye": [-300, 290, 430], "target": [0, 40, 0], "up": [0, 1, 0]}, "ease": "inOut"}
    ]},
    {"drag": {"box": "Hinge", "channel": "plan"}, "until": {"view": "model", "metadata": "edge", "of": "x"}, "keys": [
      {"t": 1.0, "at": [80, 0]},
      {"t": 2.5, "at": [140, 0], "ease": "inOut"},
      {"t": 3.0, "at": [140, 0]},
      {"t": 4.0, "at": [80, 0], "ease": "inOut"},
      {"t": 4.1, "release": true}
    ]},
    {"control": {"box": "Hinge", "label": "depth"}, "until": {"view": "model", "metadata": "depth"}, "keys": [
      {"t": 4.5, "value": 100},
      {"t": 5.5, "value": 150, "ease": "inOut"},
      {"t": 6.2, "value": 100, "ease": "inOut"}
    ]},
    {"motion": "model", "keys": [
      {"t": 6.5, "value": 0},
      {"t": 7.7, "value": 1, "ease": "inOut"},
      {"t": 9.4, "value": 0, "ease": "inOut"}
    ]},
    {"parts": "model", "keys": [
      {"t": 7.9, "hide": ["lid"]},
      {"t": 8.7, "show": {"layer": ["Lid"]}}
    ]},
    {"captions": [
      {"t": 0.2, "text": "A box with a hinged lid", "fade": 0.3},
      {"t": 1.0, "text": "Its plan, dragged wider and back", "fade": 0.3},
      {"t": 4.4, "text": "Its depth slider", "fade": 0.3},
      {"t": 6.5, "text": "Its lid, on its declared motion", "fade": 0.3},
      {"t": 7.9, "text": "The lid hidden by its part id, and shown", "fade": 0.3}
    ]}
  ]
}
```

The top level:

| Field | Meaning |
| --- | --- |
| `format` | `"field-film-timeline/1"` |
| `duration`, `fps`, `size`, `pixelRatio` | the film's length in seconds, frames per second, `[width, height]` in pixels and pixels per CSS pixel; `film-page`'s flags override them |
| `stage` | where each box output sits on the frame (below); without one the film shows the page as it is |
| `tracks` | the keys, one track per thing that changes |
| `"//"` | a comment, anywhere a field can go |

Every key has a time `t` in seconds. A key with an `ease` (`linear`, the default; `in`, `out`, `inOut`, or
`step`) is arrived at from the key before it along that curve. Before a track's first key it leaves what it
controls alone, and after its last key it holds that key.

### The stage

Each region binds one output of a box (its channel: a 3D view, a `lines` drawing, a `display`, a control panel)
to a rectangle of the frame, `[left, top, width, height]` in CSS pixels of the film page. Everything else on the
page is hidden. The outputs are Field's own, so they keep updating while the boxes redraw them.

| Region field | Meaning |
| --- | --- |
| `id` | the name tracks use for the region |
| `box` | the box, by name, uid, or the backdoor session's alias |
| `channel` | the output; a box's first 3D view when left out |
| `rect` | where it goes |
| `fit` | `size` (a 3D view's default: it is drawn at the rectangle's size), `contain` (anything else's default: kept at its own size, scaled to fit), or `width` |
| `scale` | a fixed scale instead of `contain`'s |
| `align` | `start` (top left, the default) or `center` |
| `background` | a CSS background for the region |

`caption` places the captions: a `rect`, and a `style` of CSS properties.

### The tracks

| Track | Keys |
| --- | --- |
| `"camera": view` | `pose`: `{eye, target, up, fov}`; or `orbit`: `{degreesPerSecond, eye, target, up}`, turning about the target and up axis from the key's time. An orbit with no eye starts from wherever the camera is, the view's own camera for the first key. `arc: [x, y, z]` swings the eye that far out halfway into the key |
| `"parts": view` | `hide` or `show`: a list of part ids, or `{"ids": […], "layer": […]}`, a layer path every part under it shares |
| `"motion": view` | `value` from 0 to 1 (every declared motion of the view at that point of its range); `play: {period, shape}` (the view's own `swing`, `loop` or `once`, played on the film's time); `rest: true`; `tracks: true` or `false` |
| `"drag": {box, channel}` | `at: [x, y]` in the drawing's own coordinates (what its `onDrag` receives), then `release: true`. The first `at` presses, each later frame moves, the release lets go; with no release the drag lets go after its last key |
| `"control": {box, label}` | `value`: the slider with that label is set to it, snapped to its step as a hand's drag would be |
| `"captions"` | a list of `{t, text, fade}`; a caption fades in over `fade` seconds and out before the next |

A view is a region's `id`, or `{"box": …, "channel": …}` for a 3D view that is not on the stage.

### Waiting for the view

Before Field captures a frame it waits until every view shows what that frame asked for: the camera reads back
as asked, the parts and the motion are as asked, and the view has drawn since. A drag or a control changes the
box itself, and the box redraws in its own time, so a `drag` or `control` track can say what to wait for:

```diffstatic {data-lang=json}
"until": {"view": "model", "metadata": "edge", "of": "x"}
```

The frame then waits until the view's metadata `edge` equals the drag's `x` (to within `tol`, 1e-6 by default);
for a control, until it equals the slider's value. That is why **Hinge** puts `edge` and `depth` in its view's
metadata. No frame is ever taken on a timer: a frame whose box takes a second to rebuild makes the shoot a second
longer, and the film shows the rebuilt box.

## Shooting it

From the backdoor (see [Automating Field](automating_field.md)), in a scratch document:

```diffstatic {data-lang=bash}
uv run --project . --group backdoor python tools/field_backdoor.py --scratch \
  --command 'new-box --name Hinge --alias hinge' \
  --command 'run hinge --file hinge.kt' \
  --command 'film-page hinge.mp4 --timeline hinge.film.json --frames-dir hinge.frames'
```

With `--timeline` the page is the session's own document, so the url can be left out (it is `@session`).
`film-page` reports as it goes, on stderr:

```diffstatic {data-lang=text}
field_backdoor.py: film-page: filming frame 120 of 300, 15.0 a second, about 12 s left
```

`--frames-dir` writes each frame to a folder and encodes the film from it at the end. If a shoot stops, run
the same command again with `--resume`: it carries on after the last frame written, first making the
timeline's drags of the frames before it again so the boxes are where they were. `--from-frame K` starts at
frame K instead. The folder is removed after a good encode, unless `--keep-frames`. `--frame-timeout` (120 s)
is how long one frame may wait for its views before the shoot fails, naming what it was waiting for.

`--duration`, `--fps`, `--size` and `--pixel-ratio` override the timeline's. A `--frame-file` script, if
given, still runs before each frame, after the timeline's keys.

??? aside "Agents: the page-side API"
    `film-page --timeline` imports `/filmStage.js` into its film page: `install({stage, views})` lays the stage
    out and answers each view's camera, `frame(state)` puts one frame's keys (as `FilmTimeline.frame` evaluates
    them on the server) and resolves once they are drawn, and `status().waiting` names what a frame is still
    waiting for. The drags go to the box on the server (`dragLines` in `someSkiko.kt`), with no pointer
    pixels. `FilmTimelineTest` covers the format and the frame loop's pure parts.

## Limits

- A control key sets a slider; checkboxes and other controls are not keys yet.
- A drag addresses a drawing's `onDrag`; it does not move an `editable` drawing's points.
- The camera keys drive a 3D view's own camera; a canvas flight moves the canvas (see
  [Filming the Canvas](filming_the_canvas.md)).
