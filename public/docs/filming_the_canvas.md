# Filming the canvas

A canvas flight moves the camera smoothly through a document's bookmarks, and Field can record it
to an MP4. The same flight replays the same way every time, so you can film a demo, change a box
and film it again. Every three.js view on the canvas (a box's interactive 3D output, see
[3D Output](three_output.md)) turns slowly while the canvas flies, then goes
back to its own camera.

## Fly through your bookmarks

Save a few views as [bookmarks](moving_around_the_canvas.md#bookmarks) (`ctrl/command-shift-F1`
to `F9`), and name them in the bookmarks panel (`alt/option-B`). **Fly Through Bookmarks** in the
command palette then flies through every saved slot in slot order. Press `Escape`, pan or zoom to
stop the flight where it is, or choose **Stop Canvas Flight**.

The flight pauses at the first bookmark, then travels to each one in turn. Each leg starts
slowly, speeds up and settles. The zoom changes at an even rate,
whether the canvas is at 20% or 300%, and the middle of the window travels in a straight line
across the canvas. When two bookmarks are far apart and the straight line between them crosses
empty canvas, the leg zooms out on the way, far enough to keep both in sight. A leg over boxes the
whole way pans straight across them.

## From a box

`canvasFlight` names the bookmarks to fly through, in order. Use the names the bookmarks panel
shows, or slot numbers such as `"3"`. This flies through three of them, taking 4 seconds to fly to
each and holding 1.5 seconds at each:

```diffstatic {data-lang=kotlin}
canvasFlight(["overview", "plan", "close-ups"], legSeconds = 4.0, holdSeconds = 1.5)
```

The flight plays in every window open on the document. A misspelt bookmark is an error in the box,
and the error message lists the bookmarks the document has.

To give each stop its own timing, pass `FlightStop`s. A stop that leaves `legSeconds` or
`holdSeconds` out takes the flight's values. This flight has a long, slow middle leg:

```diffstatic {data-lang=kotlin}
canvasFlight(
    FlightStop("overview", holdSeconds = 3.0),
    FlightStop("plan", legSeconds = 20.0, holdSeconds = 0.5),
    FlightStop("close-ups"),
    legSeconds = 6.0,
    holdSeconds = 2.0,
)
```

| Argument | Meaning |
| --- | --- |
| `legSeconds = 6.0` | the time a leg takes to fly to the next stop |
| `holdSeconds = 2.0` | the time the camera holds at each stop, the first included |
| `orbitDegreesPerSecond = 8.0` | how fast each three.js view turns about its target; `0` keeps them still, and a negative rate turns them the other way |
| `pullBack = true` | let a leg that would cross empty canvas zoom out on the way; `false` flies every leg straight |
| `record = null` | a path to an `.mp4`: film the flight instead of playing it (below) |

## Filming it to an MP4

Give `record` a path, and the flight is filmed rather than played:

```diffstatic {data-lang=kotlin}
canvasFlight(
    ["overview", "plan", "close-ups"],
    legSeconds = 4.0,
    holdSeconds = 1.5,
    record = "~/Movies/field-flight.mp4",
)
```

Field renders the film one frame at a time. It sets the flight's clock to the frame's time, waits
until the window has drawn that frame, captures it, and hands it to ffmpeg. Nothing depends on how
fast the machine is: a frame that takes a second to capture shows the same thing as one that takes
ten milliseconds, so the film never drops or repeats a frame. When the file is written, the box
prints how many frames it holds and where it is. Any `shot()` a three.js view plays (its scripted camera move, such as the dolly,
explode and x-ray loop on [3D Output](three_output.md)) runs on the film's clock too.

This is the flight above, filmed:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1280x720" hide-toasts=true video="assets/filming_the_canvas/flight.mp4" fps="30" transcript="../../dev_docs/agents/transcripts/docs_capture/canvas_flight.yaml" padding="0" wait="0" timeout="300" embed=true hidden=true name="canvas-flight-film" alt="A canvas flight filmed to MP4. It opens on the whole document: a title card, a spiral drawn over a grid, and a two-panel three.js sheet of a ring and a tower. It flies in to the spiral, then down and across to the three.js sheet, where the ring and the tower turn slowly about their own centres."}
// Save the three bookmarks, each framing a box and its open output panel, then film the flight
// through them with the recorder canvasFlight(record = ...) uses.
ui.eval(
    """
  const frame = () => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
  const field = await import('./afield.mjs');
  const names = ['FlightTitle', 'FlightPlan', 'FlightModel'];
  const box = n => [...document.querySelectorAll('.box')].find(b => b.name === n);
  for (let i = 0; i < 400 && !names.every(box); i++) await new Promise(r => setTimeout(r, 50));
  const viewer = () => [...document.querySelectorAll('iframe')].map(f => { try { return f.contentWindow.FieldViewer } catch (e) { return null } }).find(v => v && v.film);
  for (let i = 0; i < 400 && !(viewer() && viewer().captureStatus().ready); i++) await new Promise(r => setTimeout(r, 50));
  await new Promise(r => setTimeout(r, 800));
  const world = el => { const c = window.fieldCanvasFlight.camera(), r = el.getBoundingClientRect();
    return {x: (r.left - c.x) / c.zoom, y: (r.top - c.y) / c.zoom, w: r.width / c.zoom, h: r.height / c.zoom}; };
  const extent = b => [b, ...b.querySelectorAll('.outputArea .disclosure')].filter(e => e.getBoundingClientRect().width > 0).map(world);
  const save = async (slot, name, rs, margin) => {
    const x = Math.min(...rs.map(r => r.x)), y = Math.min(...rs.map(r => r.y));
    const w = Math.max(...rs.map(r => r.x + r.w)) - x, h = Math.max(...rs.map(r => r.y + r.h)) - y;
    const z = Math.max(.1, Math.min(4, margin * Math.min(innerWidth / w, innerHeight / h)));
    window.moveCanvasCamera(innerWidth / 2 - (x + w / 2) * z, innerHeight / 2 - (y + h / 2) * z, z);
    await frame();
    field.saveBookmarkSlot(slot); field.nameBookmarkSlot(slot, name);
  };
  await save(1, 'overview', names.flatMap(n => extent(box(n))), .85);
  await save(2, 'plan', extent(box('FlightPlan')), .8);
  await save(3, 'close-ups', extent(box('FlightModel')), .9);
  return 'saved';
""",
)
ui.pause(1500)
ui.filmCanvasFlight(
    vid,
    ["overview", "plan", "close-ups"],
    legSeconds = 4.0,
    holdSeconds = 1.5,
    width = 1280,
    height = 720,
    pixelRatio = 1.0,
)
```

These arguments control the film:

| Argument | Meaning |
| --- | --- |
| `fps = 30` | frames per second |
| `width = 1920`, `height = 1080` | the film's size in pixels; `null` for both keeps the capture window's size |
| `pixelRatio = 1.5` | pixels of film per CSS pixel of the window. At the defaults the window is 1280 by 720 CSS px, so text and box chrome read at a comfortable size in a 1080p film |

Field records in a separate capture browser sized for the film, leaving your window free. If a
bookmark was saved in a window of a different size or shape, the flight adjusts its zoom to fit
everything the bookmark showed.

??? aside "Agents: filming the Electron app"
    To film Field's Electron app itself, open the capture page there first with the backdoor's
    `playwright-open --electron`.

`canvasFlight` returns a `CanvasFlight`. `durationSeconds` is the flight's length, `done` says
whether the film is written, and `result` then reports the frame count, the size and, for each
bookmark, the frame that reaches it.

## From the backdoor

Agents and scripts fly and film the same way with the [backdoor](automating_field.md):

```diffstatic {data-lang=bash}
uv run --project . --group backdoor python tools/field_backdoor.py --json --document my-demo \
  --command 'canvas-flight overview plan close-ups --leg 4 --hold 1.5 --record /tmp/flight.mp4'
```

Without `--record` the flight plays in the document's windows. `--size 1280x720`,
`--pixel-ratio`, `--fps` and `--orbit` match the box arguments, and `--expect-frames N` fails the
command unless the finished file holds exactly `N` frames. `film-page <url> <path>` films any other
web page the same way, frame by frame. It takes a setup script run once and a frame script run
before each frame (scroll a column, say), and `--drag DX,DY` to orbit a three.js page with the
mouse.

## Limits

- On a split canvas the flight moves the main (bottom-right) pane.
- The flight only moves the camera. It does not reselect boxes, reopen the editor or open and close
  output panels the way restoring a bookmark does.
- Only three.js views turn.
- A live flight runs on the window's own clock, so a busy machine can stutter. A filmed flight
  cannot.

??? aside "Agents: how the film on this page is made"
    The docs runner builds it. The setup transcript (`canvas_flight.yaml`) creates the three boxes.
    The drive script saves the three bookmarks through the bookmark code, as `ctrl/command-shift-F1`
    to `F3` would. `ui.filmCanvasFlight` then films the flight with the recorder `canvasFlight(record = …)`
    uses, in place of the drive's real-time recording. The `canvas-flight` UI regression checks the
    same recorder: one frame per tick, each bookmark reached exactly where restoring it puts the
    camera, and the three.js view turned at the requested rate and given its camera back afterwards.
