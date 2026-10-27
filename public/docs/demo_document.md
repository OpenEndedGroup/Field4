# The Demo Document

The demo document is one canvas that shows most of what Field does at once. It is the film behind
this site's home page, and it is built from nothing by an [operation
transcript](agent_transcripts.md), `demo_document.yaml`, so the film and the document can be made
again at any time.

On it:

- **Connected boxes.** `palette` pushes a value, `petals`, down to three boxes connected beneath it
  with `propDown`. Change it and run `palette` again, and all three run again with the new value.
  `sketch` holds two more boxes nested inside it, in its `nested` panel. See [Connecting
  Boxes](tour_connecting_boxes.md).
- **3D and 2D in output panels.** `gyroid` shows a marched gyroid surface and its open rims in a
  three.js view you can orbit ([3D Output](three_output.md)). `editable flower` shows FLines you can
  edit: drag a point and the curve changes ([FLine Examples](fline_examples.md)).
- **Vulkan, streamed into the page.** `renderer` owns a Vulkan window. `streamed spiral` draws a
  turning spiral with it and shows the frames in its own output panel ([Graphics in an Output
  Area](four_in_output_areas.md)). `probed particles` runs a compute shader over 100,000 particles,
  and a [Field probe](field_probes.md) plots how far each one moved. `draw particles` draws them in
  the window.
- **Time.** `brightness` is a keyframe curve, played in a loop with its playhead running and a
  meter it drives ([Keyframe Editor](keyframe_editor.md)). `metrics` draws a histogram, a history
  and a summary beside its own lines of code when its editor is open
  ([Visualizers](visualizers.md)). `t` is a time slider: drag it sideways and it starts each cue box
  it enters and stops each one it leaves.
- **Drawing.** `notes` draws circles, arrows, frames and labels on the drawing layer, over the
  boxes they point at ([Drawing and Notation](drawing_and_notation.md)). `alt/option-D` turns the
  layer on, so you can draw on it by hand.

## The film

The film starts on the whole document, then visits each part of it and uses it: it drags a box and
its connection follows, orbits the gyroid, pulls two points of the flower, watches the spiral and
the probe, adds and drags keys on the curve, scrubs the time slider across the cues, draws a loop on
the drawing layer, and launches `metrics` with its editor open. It ends on the whole document
again, so it can play as a loop.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1280x720" hide-toasts=true video="assets/demo_document/film.mp4" fps="15" transcript="../../dev_docs/agents/transcripts/demo_document.yaml" capture-profile="macos-electron" padding="0" wait="0" timeout="900" embed=true hidden=true name="demo-document-film" alt="The demo document filmed in Field's desktop app. It opens on the whole canvas, then glides to a palette box and drags one of its children, whose connection follows; orbits a white gyroid in a three.js panel; drags two points of a pink editable flower; holds on an orange and blue spiral streamed from Vulkan into a box's panel beside a probe's blue histogram; adds a key to a keyframe curve and drags it while the playhead runs; drags a red time slider across four cue boxes, whose coloured bars appear as it passes; draws a white loop on the drawing layer; and opens a box's editor, where a histogram, a history and a summary draw beside the code. It ends on the whole canvas again"}
// Film the demo document: glide the canvas camera from area to area and use each thing there by hand.
// The page is 1280 x 720 CSS px; the camera puts world point (cx, cy) at the window's centre at zoom z.
val W = 1280.0
val H = 720.0

fun js(body: String): String = ui.eval(body).toString().trim('"')

fun xy(body: String): Pair<Double, Double> {
    val (x, y) = js(body).split(",").map { it.toDouble() }
    return x to y
}

fun look(cx: Double, cy: Double, z: Double) {
    ui.eval("window.moveCanvasCamera(" + (W / 2 - cx * z) + ", " + (H / 2 - cy * z) + ", " + z + "); return 1")
}

// Ease the camera to (cx, cy, z): the centre in a straight line, the zoom at an even rate.
fun glide(cx: Double, cy: Double, z: Double, ms: Int) {
    val (x0, y0, z0) = js("const c = window.fieldCanvasFlight.camera(); return [c.x, c.y, c.zoom].join(',')")
        .split(",").map { it.toDouble() }
    val cx0 = (W / 2 - x0) / z0
    val cy0 = (H / 2 - y0) / z0
    val n = (ms / 40).coerceAtLeast(1)
    for (k in 1..n) {
        val u = k.toDouble() / n
        val e = u * u * (3 - 2 * u)
        look(cx0 + (cx - cx0) * e, cy0 + (cy - cy0) * e, Math.exp(Math.log(z0) + (Math.log(z) - Math.log(z0)) * e))
        ui.pause(40)
    }
}

// The pointer moves in small timed steps, as a hand does.
fun hand(from: Pair<Double, Double>, to: Pair<Double, Double>, steps: Int, stepMs: Long = 40) {
    for (k in 1..steps) {
        ui.moveMouse(from.first + (to.first - from.first) * k / steps, from.second + (to.second - from.second) * k / steps, 1)
        ui.pause(stepMs)
    }
}

fun drag(from: Pair<Double, Double>, to: Pair<Double, Double>, steps: Int = 30) {
    ui.moveMouse(from.first, from.second, 8)
    ui.pause(200)
    ui.mouseDown()
    hand(from, to, steps)
    ui.pause(120)
    ui.mouseUp()
}

// Where a box is on the page, by name.
fun boxAt(name: String, fx: Double = 0.5, fy: Double = 0.5): Pair<Double, Double> =
    xy("const b = [...document.querySelectorAll('.box')].find(b => b.name === '" + name + "'); const r = b.getBoundingClientRect(); return [r.left + r.width * " + fx + ", r.top + r.height * " + fy + "].join(',')")

// An iframe's centre on the page, by a fragment of its URL.
fun frameAt(src: String): Pair<Double, Double> =
    xy("const f = [...document.querySelectorAll('iframe')].filter(f => (f.src || '').indexOf('" + src + "') >= 0 && f.getBoundingClientRect().width > 0).pop(); const r = f.getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2].join(',')")

// Click empty canvas, so no box is selected and the code editor goes away; again if it is still there.
fun editorShowing(): Boolean =
    js("const e = document.querySelector('.monaco-editor .view-lines'); if (!e) return false; const r = e.getBoundingClientRect(); if (r.width < 50) return false; const h = document.elementFromPoint(r.left + r.width / 2, r.top + Math.min(r.height / 2, 200)); return !!(h && h.closest('.monaco-editor'))") == "true"

fun deselect() {
    for (attempt in 0 until 4) {
        val p = xy("for (let y = 0.94; y > 0.1; y -= 0.05) for (let x = 0.03; x < 0.97; x += 0.03) { const e = document.elementFromPoint(innerWidth * x, innerHeight * y); if (e && !e.closest('.box, .outputArea, iframe, .monaco-editor, .monaco-editor-container, .disclosure, svg, button, input, .excalidraw')) return [innerWidth * x, innerHeight * y].join(','); } return '4,4'")
        ui.clickAt(p.first, p.second)
        ui.pause(250)
        if (!editorShowing()) return
        ui.press("Escape")
        ui.pause(150)
    }
}

// Wait for the slow arrivals: the three.js scene and the keyframe editor's keys.
ui.waitForSelector("iframe[src*='/threejs?']", 30000.0)
ui.waitForSelector("iframe[src*='/keyframe?']", 30000.0)
var tries = 0
while (js("const f = [...document.querySelectorAll('iframe')].filter(f => (f.src || '').indexOf('/keyframe?') >= 0).pop(); return f && f.contentDocument ? f.contentDocument.querySelectorAll('.kf-keyframe').length : 0") != "4" && tries++ < 80) ui.pause(250)

// Keep the page producing frames: the recorder only gets a new one when this page paints, and the
// panels repaint inside their own frames. One pixel, all but transparent.
ui.eval("const k = document.createElement('div'); k.style = 'position:fixed;left:0;top:0;width:1px;height:1px;pointer-events:none;z-index:2147483647'; document.body.appendChild(k); let on = false; (function tick() { on = !on; k.style.opacity = on ? '0.011' : '0.010'; requestAnimationFrame(tick); })(); return 'ok'")

// 0. The whole document.
look(2700.0, 640.0, 0.2)
ui.pause(1500)

// 1. The graph: drag a child of the palette and its connection follows it.
glide(560.0, 440.0, 0.85, 3200)
drag(boxAt("rosette"), boxAt("rosette").let { it.first + 70 to it.second + 50 }, 36)
ui.pause(500)
deselect()
ui.pause(600)

// 2. The three.js gyroid: orbit it with the mouse.
glide(1620.0, 340.0, 1.0, 2600)
val g = frameAt("/threejs?")
drag(g.first - 120 to g.second, g.first + 160 to g.second + 30, 60)
ui.pause(700)

// 3. The editable flower: pull a control point out, then another.
glide(1520.0, 1110.0, 1.0, 2400)
val handle = { pick: String ->
    xy("const b = [...document.querySelectorAll('.box')].find(b => b.name === 'editable flower'); const cs = [...b.querySelectorAll('.outputArea svg circle')].map(c => c.getBoundingClientRect()).filter(r => r.width > 0).map(r => [r.left + r.width / 2, r.top + r.height / 2]); cs.sort((a, c) => " + pick + "); return cs[0].join(',')")
}
val right = handle("c[0] - a[0]")
drag(right, right.first + 110 to right.second + 70, 40)
ui.pause(400)
val top = handle("a[1] - c[1]")
drag(top, top.first - 40 to top.second - 50, 30)
ui.pause(1200)
deselect()
ui.pause(300)

// 4. Vulkan: the spiral streamed into the page, the probe plotting the compute shader's values.
glide(2850.0, 430.0, 0.78, 3000)
ui.pause(3600)

// 5. The keyframe editor: add a key while the playhead plays, then drag it.
glide(2560.0, 1130.0, 1.15, 2600)
ui.eval("""
  const frame = [...document.querySelectorAll('iframe')].filter(f => (f.src || '').indexOf('/keyframe?') >= 0 && f.getBoundingClientRect().width > 0).pop();
  const d = frame.contentDocument;
  const fr = frame.getBoundingClientRect();
  const centre = el => { const r = el.getBoundingClientRect(); return [fr.left + r.left + r.width / 2, fr.top + r.top + r.height / 2]; };
  const keys = () => [...d.querySelectorAll('.kf-keyframe')].map(g => centre(g.querySelector('circle')));
  const k = keys();
  const sx = (k[1][0] - k[0][0]) / 1.0, sy = (k[1][1] - k[0][1]) / 0.8;
  window.__kf = {
    pos: (t, v) => [k[0][0] + t * sx, k[0][1] + (v - 0.1) * sy].join(','),
    key: i => keys()[i].join(',')
  };
  return 'ok';
""".trimIndent())
val added = xy("return window.__kf.pos(3.2, 0.8)")
hand(added.first - 200 to added.second + 60, added, 14)
ui.pause(300)
ui.doubleClickAt(added.first, added.second)
ui.pause(400)
drag(xy("return window.__kf.key(3)"), xy("return window.__kf.pos(3.4, 0.5)"), 32)
ui.pause(400)
drag(xy("return window.__kf.key(1)"), xy("return window.__kf.pos(1.3, 0.65)"), 28)
ui.pause(900)

// 6. The timeline: scrub the time slider across the cues; each runs while the line is over it.
glide(4880.0, 470.0, 0.62, 3000)
val t0 = boxAt("t", 0.5, 0.5)
ui.moveMouse(t0.first, H * 0.5, 10)
ui.pause(250)
ui.mouseDown()
hand(t0.first to H * 0.5, t0.first + 690 to H * 0.5, 90)
ui.pause(150)
ui.mouseUp()
ui.pause(300)
deselect()
ui.pause(900)

// 7. The drawing layer: draw over the canvas by hand, then put the pen down.
glide(1760.0, 1470.0, 0.9, 3000)
ui.eval("window.toggleDrawingLayer(); return 1")
tries = 0
while (js("const s = window.drawingLayerState(); return !!(s && s.active)") != "true" && tries++ < 60) ui.pause(250)
ui.pause(300)
ui.eval("window.drawingLayerSetTool('freedraw'); return 'ok'")
val cx = W * 0.42
val cy = H * 0.55
fun loopPoint(k: Int): Pair<Double, Double> {
    val a = k / 70.0 * 2.6 * Math.PI
    val r = 60.0 + k * 1.6
    return cx + r * Math.cos(a) * 1.5 to cy + r * Math.sin(a) * 0.7
}
ui.moveMouse(loopPoint(0).first, loopPoint(0).second, 6)
ui.mouseDown()
for (k in 1..90) {
    val p = loopPoint(k)
    ui.moveMouse(p.first, p.second, 1)
    ui.pause(22)
}
ui.mouseUp()
ui.pause(500)
ui.eval("window.drawingLayerSetTool('selection'); window.toggleDrawingLayer(); return 'ok'")
ui.pause(900)

// 8. Inline visualizers: select the metrics box, so its editor opens, and launch it from there; each call draws beside its line.
glide(3450.0, 980.0, 1.0, 2600)
ui.click("demo_metrics")
ui.pause(600)
// Focus the editor directly: the visualizers' zones lie over its lines and take a click.
ui.eval("window.globalEditor.focus(); return 1")
ui.pause(200)
ui.press("Alt+ArrowDown")
ui.pause(300)
ui.press("Alt+ArrowUp")
ui.pause(4500)
deselect()
ui.pause(600)

// 9. Back out to the whole document, the way the film began.
glide(2700.0, 640.0, 0.2, 4200)
ui.pause(1500)
```

## Making it again

To open the document itself, replay the transcript with the [backdoor](automating_field.md):

```diffstatic {data-lang=bash}
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'open demo_document --create' \
  --command 'macro write demo_document --file dev_docs/agents/transcripts/demo_document.yaml' \
  --command 'macro run demo_document'
```

The feel benchmark times the same replay, as its `demo-document` scenario
([`dev_docs/testing/FEEL_BENCHMARKS.md`](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/testing/FEEL_BENCHMARKS.md)).

??? aside "Agents: how the film is made"
    The film is a `drive` capture: the runner opens the capture page in this checkout's Electron
    (`playwright-open --electron`), runs the transcript, and records the window in real time while
    the script runs. The camera moves are `window.moveCanvasCamera` calls, 40 ms apart, eased in
    the script; there is no frame-by-frame clock as there is for a canvas flight, because the gestures
    (drags, double-clicks, drawing) have to happen in real time. A box the script presses on becomes
    selected and opens its editor, so the script clicks empty canvas afterwards to close it again.
    The home page shows the film behind a veil, tilted in CSS (`.field-homepage-video` in
    `stylesheets/extra.css`); the file itself is the flat window.

    Field's documentation build makes the film. It replays `demo_document.yaml` into an empty document
    in this checkout's Field desktop app, then runs the script above, which moves the camera and the
    mouse the way a person would, while Field records the window. The build only films it on macOS with
    `FIELD_DOCS_CAPTURE_PROFILES=macos-electron`, because the spiral's frames only reach a page in
    Field's desktop app. On any other machine the build keeps the film it already has.

    ```diffstatic {data-lang=bash}
    FIELD_DOCS_CAPTURE_PROFILES=macos-electron FIELD_DOCS_RUNNER_MODE=always \
      uv run --project . --group docs python tools/build_zensical_docs.py --page demo_document.md
    ```
