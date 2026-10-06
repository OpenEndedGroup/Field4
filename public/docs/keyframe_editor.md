# The keyframe editor: drawing a value over time

`keyframes(...)` puts a curve editor in a box's output: a graph of one value against time that you draw with the mouse. The box asks the curve for its value at any moment, and gets back what you drew. Use it for anything you would rather shape by hand than type: a brightness that swells and fades, the gain of an envelope, how fast a camera moves, a parameter over the course of a performance.

Each editor holds one number over time. A box can have as many as it likes, one per parameter.

!!! note "Not the Keyframer"
    This page is about a single hand-drawn curve. [Keyframes: capturing, persisting and replaying live state](keyframer_examples.md) is something else: it snapshots a whole set of a box's values and blends between the snapshots.

## A curve that drives something

Here a curve drives a meter. `keyframes(name = "brightness", ...)` shows the editor, seeded with four keys the first time it appears. The `loop` reads the time off the clock, wraps it every four seconds, moves the editor's playhead there with `setTime`, and sends the curve's value to the meter:

```diffstatic_runner {data-lang=kotlin video="assets/keyframe_editor/playback.mp4" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" execution="launch" duration="5" fps="20" timeout="180" w="700" embed=true name="keyframe-playback" alt="The brightness editor: four keys joined by straight lines, rising from 0.1 at time 0 to 0.9 at time 1 and falling back to 0.1 at time 4, with an orange playhead sweeping left to right and a dot riding the curve. Below it a meter labelled brightness rises and falls with the dot"}
:::static
val brightness = keyframes(name = "brightness", defaults = listOf(vec(0.0, 0.1), vec(1.0, 0.9), vec(2.5, 0.4), vec(4.0, 0.1)))

lateinit var level: MeterHandle
controlGroup("level") { level = meter("brightness") }
displayOpen("level")

val start = System.nanoTime()
loop {
    val t = ((System.nanoTime() - start) / 1e9) % 4.0
    brightness.setTime(t)
    level.set(brightness.evaluateValueAt(t))
}
```

`defaults` are `(time, value)` pairs, and they are only a starting point: once you edit the curve, your edits are what the box reads, and they are saved with the document. Rerunning the box keeps them, and so does closing Field. To start over from the defaults, give the editor a new `name`.

Time is whatever unit your loop counts in; the editor labels it in seconds. Values are not limited to 0 to 1: drag a key above or below and the graph makes room.

## Editing the curve

Double-click empty graph to add a key. Drag a key to move it in time and value at once. Right-click a key for its menu, which changes its type, locks it, or deletes it. A bezier key has handles that bend the curve on either side:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="700x470" clip="0,0,700,470" hide-toasts=true video="assets/keyframe_editor/editing.mp4" transcript="../../dev_docs/agents/transcripts/docs_capture/keyframe_editing.yaml" fps="12" padding="0" wait="0" timeout="240" embed=true hidden=true name="keyframe-editing" alt="The brightness editor while the playhead plays and the meter follows. A double-click adds a key near time 3, which is then dragged down and to the right. A right-click on the peak at time 1 opens a menu of key types, and choosing Bezier (Continuous) rounds the peak and shows its handles; dragging the right-hand handle up reshapes the curve on both sides. A drag across empty graph from time 2 to 3.7 selects the two keys in that band, and dragging one of them moves both"}
// Wait for the editor and its keys, then find things in its iframe, in page coordinates, as "x,y".
ui.waitForSelector("iframe[src*='/keyframe?']", 20000.0)
// The iframe arrives before its keys do; wait until the editor is drawing all four.
val ready = """
  const f = Array.from(document.querySelectorAll('iframe')).find(f => (f.src || '').indexOf('/keyframe?') >= 0);
  return f && f.contentDocument ? f.contentDocument.querySelectorAll('.kf-keyframe').length : 0;
""".trimIndent()
var tries = 0
while (ui.eval(ready).toString().trim('"') != "4" && tries++ < 60) ui.pause(250)
ui.pause(800)
ui.eval("""
  const frame = Array.from(document.querySelectorAll('iframe')).find(f => (f.src || '').indexOf('/keyframe?') >= 0);
  const d = frame.contentDocument;
  const fr = frame.getBoundingClientRect();
  const centre = el => { const r = el.getBoundingClientRect(); return [fr.left + r.left + r.width / 2, fr.top + r.top + r.height / 2]; };
  const keys = () => Array.from(d.querySelectorAll('.kf-keyframe')).map(g => centre(g.querySelector('circle')));
  // time/value -> page, from the first two keys as drawn: (0, 0.1) and (1, 0.9).
  const k = keys();
  const sx = (k[1][0] - k[0][0]) / 1.0, sy = (k[1][1] - k[0][1]) / 0.8;
  window.__kf = {
    pos: (t, v) => [k[0][0] + t * sx, k[0][1] + (v - 0.1) * sy].join(','),
    key: i => keys()[i].join(','),
    handle: i => {
      const kp = keys()[i];
      const hs = Array.from(d.querySelectorAll('.kf-handle')).map(centre).filter(p => p[0] > kp[0] + 2);
      hs.sort((a, b) => Math.hypot(a[0] - kp[0], a[1] - kp[1]) - Math.hypot(b[0] - kp[0], b[1] - kp[1]));
      return hs[0].join(',');
    },
    button: title => centre(Array.from(d.querySelectorAll('.kf-type-icon')).find(b => b.title === title)).join(',')
  };
  return 'ok';
""".trimIndent())

fun at(expr: String): Pair<Double, Double> {
    val (x, y) = ui.eval("return window.__kf.$expr").toString().trim('"').split(",").map { it.toDouble() }
    return x to y
}

fun drag(from: Pair<Double, Double>, to: Pair<Double, Double>) {
    ui.moveMouse(from.first, from.second, 6)
    ui.pause(250)
    ui.mouseDown()
    ui.moveMouse(to.first, to.second, 24)
    ui.pause(150)
    ui.mouseUp()
}

ui.pause(1200)

// Double-click empty graph: a new key.
val p = at("pos(3.2, 0.8)")
ui.moveMouse(p.first, p.second, 12)
ui.pause(300)
ui.doubleClickAt(p.first, p.second)
ui.pause(900)

// Drag it: time and value move together.
drag(at("key(3)"), at("pos(3.4, 0.55)"))
ui.pause(900)

// Right-click a key for its menu, and make it a bezier.
val peak = at("key(1)")
ui.moveMouse(peak.first, peak.second, 10)
ui.rightClickAt(peak.first, peak.second)
ui.pause(1000)
val b = at("button('Bezier (Continuous)')")
ui.moveMouse(b.first, b.second, 10)
ui.pause(400)
ui.clickAt(b.first, b.second)
ui.pause(900)

// Pull its handle to reshape the curve either side.
drag(at("handle(1)"), at("pos(1.9, 0.98)"))
ui.pause(900)

// Sweep a time band to select two keys, then drag them together.
drag(at("pos(2.1, 0.02)"), at("pos(3.7, 0.02)"))
ui.pause(600)
drag(at("key(2)"), at("pos(2.5, 0.7)"))
ui.pause(1500)
```

<div class="field-data-table" markdown>

| To | Do |
| --- | --- |
| add a key | double-click empty graph |
| move keys | drag a key; every selected key moves with it |
| select | click a key; shift-click to select a range; drag across empty graph to select every key in that time band |
| select a box of keys | Alt-drag across empty graph |
| change a key's type, lock or delete it | right-click the key; with several selected, the menu acts on all of them |
| delete keys | Delete or Backspace removes the selection |
| bend a bezier key | drag one of its handles |
| move without snapping | hold Alt while dragging |
| pan | scroll, or drag with the right or middle button on empty graph |
| zoom time | Ctrl-scroll or pinch |
| zoom time and value | Ctrl-Shift-scroll |
| fit the curve | Reset view, in a key's right-click menu |

</div>

A dragged key snaps to the grid, to the playhead, halfway between its neighbours, and to the values of the other keys; a dashed yellow line shows what it caught on. A locked key cannot be dragged. The small orange square on the time axis marks time zero.

## Key types

A key's type shapes the segment that leaves it, up to the next key:

<div class="field-data-table" markdown>

| Type | The segment after it |
| --- | --- |
| `linear` | a straight line to the next key |
| `hold` | stays at this key's value, then jumps |
| `bezierFlat` | eases out flat: a smooth start with no overshoot |
| `bezierContinuous` | a smooth curve whose two handles stay in line, so the curve has no corner at the key |
| `bezierFree` | a smooth curve with the two handles independent, for a deliberate corner |

</div>

To start a curve with keys of particular types, pass `keys` instead of `defaults`. This curve has one of each:

```diffstatic_runner {data-lang=kotlin screenshot="assets/keyframe_editor/types.png" capture="box" open-outputs="true" wait="2.0" timeout="120" w="700" embed=true name="keyframe-types" alt="A curve through six keys. From time 0 a straight line climbs to 0.8 at time 1; from there the value holds flat to time 2 and drops on a dashed line to 0.3; it then eases smoothly up to a rounded peak of 0.9 at time 3 and down through 0.5 at time 4, before a straight line to 0.2 at time 5. Bezier keys show their handles"}
:::static
val shape = keyframes(name = "shape", keys = listOf(
    Keyframe(0.0, 0.1, "linear"),
    Keyframe(1.0, 0.8, "hold"),
    Keyframe(2.0, 0.3, "bezierFlat"),
    Keyframe(3.0, 0.9, "bezierContinuous"),
    Keyframe(4.0, 0.5, "bezierFree"),
    Keyframe(5.0, 0.2, "linear"),
))
```

A bezier key given without handles gets the handles the editor would give it, worked out from its neighbours.

## Reading the curve

`evaluateValueAt(t)` is the value at time `t`, and the one call most boxes need. Before the first key it is the first key's value, and after the last key it is the last key's value, so a curve holds still outside the range you drew. To loop it, wrap the time, as the first example does with `% 4.0`. The rest of the evaluator describes the curve:

```diffstatic {data-lang=kotlin}
brightness.evaluateValueAt(1.5)        // the value at time 1.5
brightness.evaluateDerivativeAt(1.5)   // how fast it is changing there, per unit of time
brightness.getTimeRange()              // (first key time, last key time)
brightness.getKeyframes()              // the keys: time, value, type, handles
brightness.setTime(1.5)                // where the editor draws its playhead
```

`getTimeRange()` is how a loop wraps a curve whose length you change in the editor: `val (t0, t1) = brightness.getTimeRange()`, then use `t0 + (t % (t1 - t0))`.

## Reacting to edits, and overlays

An overlay is a second curve drawn on the graph for reference, that you cannot edit. `onChange` runs each time you finish an edit, with the new keys; if it returns a function of time, that function becomes the overlay. Here the overlay is a smoothed version of whatever you draw, worked out with `fromKeyframeEditor` and `gaussianSmooth` from [Functions](functions_examples.md):

```diffstatic_runner {data-lang=kotlin screenshot="assets/keyframe_editor/overlay.png" capture="box" open-outputs="true" wait="2.5" timeout="120" w="700" embed=true name="keyframe-overlay" alt="A jagged curve of five keys zig-zagging between 0.1 and 0.9 over two seconds, drawn dimmed, with a dashed orange overlay running smoothly through the middle of the zig-zag"}
:::static
val wobble = keyframes(name = "wobble", defaults = listOf(
    vec(0.0, 0.2), vec(0.5, 0.9), vec(1.0, 0.1), vec(1.5, 0.8), vec(2.0, 0.3)
))

val smooth = { keys: List<Keyframe> -> with(Functions) { gaussianSmooth(fromKeyframeEditor(keys), sigma = 0.15) } }
wobble.setOverlay(smooth(wobble.getKeyframes()))
wobble.onChange = smooth
```

`setOverlay` also takes a list of points, a colour, a stroke width and a dash pattern; give overlays different `id`s to show several at once, and remove them with `clearOverlay(id)` or `clearAllOverlays()`. While an overlay is showing, the editable curve is drawn dimmed so you can tell them apart.

## Several curves at once

Each editor is one value, so a movement in two dimensions is two editors. Here `x` and `y` curves move the handle of an XY pad:

```diffstatic_runner {data-lang=kotlin video="assets/keyframe_editor/two_curves.mp4" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" execution="launch" duration="5" fps="20" timeout="180" w="700" embed=true name="keyframe-two-curves" alt="Two editors, x and y, each with a playhead sweeping across it, above an XY pad named spot whose handle traces a loop as the two curves are played together"}
:::static
val x = keyframes(name = "x", defaults = listOf(vec(0.0, -0.6), vec(1.0, 0.6), vec(2.0, 0.3), vec(3.0, -0.6)))
val y = keyframes(name = "y", defaults = listOf(vec(0.0, -0.4), vec(1.5, 0.7), vec(3.0, -0.4)))

lateinit var spot: ControlValueHandle<Vector2f>
controlGroup("spot") { spot = vec2Control("spot") { } }
displayOpen("spot")

val start = System.nanoTime()
loop {
    val t = ((System.nanoTime() - start) / 1e9) % 3.0
    x.setTime(t)
    y.setTime(t)
    spot.set(Vector2f(x.evaluateValueAt(t).toFloat(), y.evaluateValueAt(t).toFloat()))
}
```

Each editor shows in its own output panel, named after it. To group a box's editors under one heading, give them a channel: `keyframes("motion", "x")` shows in `motion/x`.

## Start from a working document

Open **keyframe_editor** from Starting points on the welcome screen, or [open it here](offline.html?file=assets/agent_transcripts/keyframe_editor/metadata.edn). It has the three boxes from this page: `brightness`, the curve and meter (launch it to play), `shape`, the five key types, and `smoothed`, the curve with a smoothed overlay that follows your edits. Its entry in [Agent transcripts](agent_transcripts.md#keyframe_editor) lists what each box does.

## See also

- [Values over time: interpolants & keyframes](interpolants_examples.md): easing a value to a target from code, rather than drawing it.
- [Visualizers & Controls](visualizers.md): sliders, multisliders and the gesture recorder, for values you set or perform rather than draw.
- [Keyframes: capturing, persisting and replaying live state](keyframer_examples.md): snapshots of a box's whole state.
