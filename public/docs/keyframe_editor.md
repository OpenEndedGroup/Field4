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

Double-click empty graph to add a key. Drag a key to move it in time and value at once. Right-click a key for its menu: its exact time and value, its type, copy and paste, lock and delete. Double-click a key to type its value. A bezier key has handles that bend the curve on either side, and Cmd-Z (Ctrl-Z) undoes any of it:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="700x470" clip="0,0,700,470" hide-toasts=true video="assets/keyframe_editor/editing.mp4" transcript="../../dev_docs/agents/transcripts/docs_capture/keyframe_editing.yaml" fps="12" padding="0" wait="0" timeout="240" embed=true hidden=true name="keyframe-editing" alt="The brightness editor while the playhead plays and the meter follows. A double-click adds a key near time 3, which is then dragged down and to the right. A right-click on the peak at time 1 opens a menu of key types, and choosing Bezier (Continuous) rounds the peak and shows its handles; dragging the right-hand handle up reshapes the curve on both sides. A drag across empty graph from time 2 to 3.7 selects the two keys in that band, and dragging one of them moves both"}
// Wait for the editor and its keys, then find things in its iframe, in page coordinates, as "x,y".
ui.waitForSelector("iframe[src*='/keyframe?']", 20000.0)
// The iframe arrives before its keys do; wait until the editor is drawing all four.
val ready = """
  const f = Array.from(document.querySelectorAll('iframe')).filter(f => (f.src || '').indexOf('/keyframe?') >= 0 && f.getBoundingClientRect().width > 0).pop();
  return f && f.contentDocument ? f.contentDocument.querySelectorAll('.kf-keyframe').length : 0;
""".trimIndent()
var tries = 0
while (ui.eval(ready).toString().trim('"') != "4" && tries++ < 60) ui.pause(250)
ui.pause(200)
// Keep the main page producing frames. The recorder only gets a new one when this page paints, and
// the lanes repaint inside their own frames, so without this the video holds a stale picture while
// the curves, playheads and pad go on changing underneath. One pixel, all but transparent.
ui.eval("const k = document.createElement('div'); k.style = 'position:fixed;left:0;top:0;width:1px;height:1px;pointer-events:none;z-index:2147483647'; document.body.appendChild(k); let on = false; (function tick() { on = !on; k.style.opacity = on ? '0.011' : '0.010'; requestAnimationFrame(tick); })(); return 'ok'")
ui.eval("""
  const frame = Array.from(document.querySelectorAll('iframe')).filter(f => (f.src || '').indexOf('/keyframe?') >= 0 && f.getBoundingClientRect().width > 0).pop();
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
    // The menu is drawn in this page, not in the editor's frame.
    button: title => { const r = document.querySelector('.kfm-type[title="' + title + '"]').getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2].join(','); }
  };
  return 'ok';
""".trimIndent())

fun at(expr: String): Pair<Double, Double> {
    val (x, y) = ui.eval("return window.__kf.$expr").toString().trim('"').split(",").map { it.toDouble() }
    return x to y
}

// The pointer moves in small timed steps, as a hand does, rather than in one call.
fun glide(x0: Double, y0: Double, x1: Double, y1: Double, steps: Int) {
    for (k in 1..steps) {
        ui.moveMouse(x0 + (x1 - x0) * k / steps, y0 + (y1 - y0) * k / steps, 1)
        ui.pause(40)
    }
}

fun drag(from: Pair<Double, Double>, to: Pair<Double, Double>) {
    ui.moveMouse(from.first, from.second, 6)
    ui.pause(250)
    ui.mouseDown()
    glide(from.first, from.second, to.first, to.second, 30)
    ui.pause(150)
    ui.mouseUp()
}

ui.pause(300)

// Double-click empty graph: a new key.
val p = at("pos(3.2, 0.8)")
ui.moveMouse(p.first, p.second, 12)
ui.pause(300)
ui.doubleClickAt(p.first, p.second)
ui.pause(250)

// Drag it: time and value move together.
drag(at("key(3)"), at("pos(3.4, 0.55)"))
ui.pause(250)

// Right-click a key for its menu, and make it a bezier.
val peak = at("key(1)")
ui.moveMouse(peak.first, peak.second, 10)
// A real right-click; occasionally the first does not reach the key, so allow a second.
var opened = false
for (attempt in 1..3) {
    ui.rightClickAt(peak.first, peak.second)
    ui.pause(400)
    opened = ui.eval("return !!document.querySelector('.kfm .kfm-type')").toString() == "true"
    if (opened) break
}
ui.pause(300)
// The menu opens in this page; if it did not, say what is showing instead.
val shown = ui.eval("""
  const f = Array.from(document.querySelectorAll('iframe')).filter(f => (f.src || '').indexOf('/keyframe?') >= 0 && f.getBoundingClientRect().width > 0).pop();
  const d = f.contentDocument, fr = f.getBoundingClientRect();
  const m = document.querySelector('.kfm');
  const el = d.elementFromPoint(${peak.first} - fr.left, ${peak.second} - fr.top);
  return (m ? 'menu: ' + m.textContent.slice(0, 80) : 'no menu') + ' | under pointer: ' +
    (el ? el.tagName + '.' + (el.getAttribute('class') || '') : 'nothing') + ' | menus in frame: ' + d.querySelectorAll('.kfm').length;
""".trimIndent()).toString()
if (!shown.contains("Continuous")) error("key menu did not open on the peak: " + shown)
val b = at("button('Bezier (Continuous)')")
ui.moveMouse(b.first, b.second, 10)
ui.pause(400)
ui.clickAt(b.first, b.second)
ui.pause(250)

// Pull its handle to reshape the curve either side.
drag(at("handle(1)"), at("pos(1.9, 0.98)"))
ui.pause(250)

// Sweep a time band to select two keys, then drag them together.
drag(at("pos(2.1, 0.02)"), at("pos(3.7, 0.02)"))
ui.pause(350)
drag(at("key(2)"), at("pos(2.5, 0.7)"))
ui.pause(250)

// Double-click a key and type its value exactly.
val last = at("key(4)")
ui.moveMouse(last.first, last.second, 10)
ui.doubleClickAt(last.first, last.second)
ui.pause(400)
ui.typeSlowly("0.6", 140)
ui.pause(300)
ui.press("Enter")
ui.pause(300)

// And undo it.
ui.down("Meta"); ui.press("KeyZ"); ui.up("Meta")
ui.pause(450)
```

<div class="field-data-table" markdown>

| To | Do |
| --- | --- |
| add a key | double-click empty graph, or right-click it for the curve's menu |
| move keys | drag a key; every selected key moves with it |
| select | click a key; shift-click to select a range; drag across empty graph to select every key in that time band |
| select a box of keys | Alt-drag across empty graph |
| select a moment in every linked lane | Cmd-drag (Ctrl-drag) across empty graph; then drag, nudge or delete them together |
| type a key's time or value | double-click the key, or right-click it; Enter applies |
| change a key's type, lock or delete it | right-click the key; with several selected, the menu acts on all of them |
| delete keys | Delete or Backspace removes the selection |
| nudge keys | the arrow keys move the selection a tenth of a grid step; Shift-arrow a whole step |
| undo, redo | Cmd-Z, Shift-Cmd-Z (Ctrl-Z, Ctrl-Y elsewhere) |
| copy, cut, paste, duplicate | Cmd-C, Cmd-X, Cmd-V, Cmd-D; select all with Cmd-A |
| bend a bezier key | drag one of its handles |
| move without snapping | hold Alt while dragging |
| pan | scroll, or drag with the right or middle button on empty graph |
| zoom time | Ctrl-scroll or pinch |
| zoom time and value | Ctrl-Shift-scroll |
| show every key | Frame curve, in either menu |
| thin out or smooth a dense curve | Simplify and Smooth, in the curve's menu; see [below](#simplifying-and-smoothing-a-take) |

</div>

Copied keys paste into any editor on the page, so you can copy a gesture from one curve to another. They keep their spacing, and land at the playhead when it is in view, or where the pointer last was. Undo goes back to the keys the box last gave the editor.

Here is the menu for a key, open over the curve. With several keys selected it acts on all of them, and a right-click on empty graph gives the curve's own menu instead: add a key there, paste, select all, simplify and smooth, undo and redo.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="700x980" clip="0,0,700,980" hide-toasts=true screenshot="assets/keyframe_editor/key_menu.png" transcript="../../dev_docs/agents/transcripts/docs_capture/keyframe_editing.yaml" padding="0" wait="0" timeout="240" embed=true hidden=true name="keyframe-key-menu" alt="The brightness editor with the menu for the key at time 1 open beside it, reaching past the editor's own frame. The menu is headed KEY and has time and value fields reading 1 and 0.9, five type buttons labelled Linear, Hold, Free, Flat and Continuous with Linear highlighted, the library's Ease table and its Spline, Shape and Noise groups, then Copy, Cut, Duplicate and Paste here with their shortcuts, Lock, Delete, and Frame curve"}
ui.waitForSelector("iframe[src*='/keyframe?']", 20000.0)
val ready = """
  const f = Array.from(document.querySelectorAll('iframe')).filter(f => (f.src || '').indexOf('/keyframe?') >= 0 && f.getBoundingClientRect().width > 0).pop();
  return f && f.contentDocument ? f.contentDocument.querySelectorAll('.kf-keyframe').length : 0;
""".trimIndent()
var tries = 0
while (ui.eval(ready).toString().trim('"') != "4" && tries++ < 60) ui.pause(250)
val peakScript = """
  const f = Array.from(document.querySelectorAll('iframe')).filter(f => (f.src || '').indexOf('/keyframe?') >= 0 && f.getBoundingClientRect().width > 0).pop();
  const fr = f.getBoundingClientRect();
  const r = f.contentDocument.querySelectorAll('.kf-keyframe circle')[1].getBoundingClientRect();
  return Math.round(fr.left + r.left + r.width / 2) + ',' + Math.round(fr.top + r.top + r.height / 2);
""".trimIndent()
// Measured only once the page has stopped moving: the panel may still be opening or panning into view.
fun peakNow(): List<Double> {
    var last = ""
    repeat(30) {
        val now = ui.eval(peakScript).toString().trim('"')
        if (now == last) return now.split(",").map { it.toDouble() }
        last = now
        ui.pause(150)
    }
    return last.split(",").map { it.toDouble() }
}
val peak = peakNow()
ui.moveMouse(peak[0], peak[1], 4)
ui.rightClickAt(peak[0], peak[1])
ui.pause(800)
```

A dragged key snaps to the grid, to the playhead, halfway between its neighbours, and to the values of the other keys; a dashed yellow line shows what it caught on. A locked key cannot be dragged. The small orange square on the time axis marks time zero.

## Simplifying and smoothing a take

A curve baked from a recording, such as a take from a [gesture pad](#from-a-performed-gesture) or numbers logged from a sensor, can have a key for every frame: too many to edit by hand, and every tremor of the hand is in them. Right-click empty graph, and the curve's menu has two sliders for this:

- **Simplify** removes every key it can while the curve stays close to where it was. Drag right to allow it to move further; the readout counts the keys left. The keys that stay become continuous beziers, with handles along the curve's slope, so a few keys still make the same smooth shape.
- **Smooth** averages each key's value with its neighbours' across a stretch of time, given in the readout, so jitter goes and the shape stays. The first and last keys don't move, and no key moves in time.

The curve changes as you drag, and letting go keeps it. Everything you do with the sliders while the menu is open is one undo step. Smoothing first, then simplifying, gives the fewest keys: there is less jitter for the simplified curve to follow. With three or more keys selected, both sliders work on the selection only, and the keys either side of it stay where they are. A key's own menu has them too, when it is one of a selection. Locked keys and hold keys are always kept as they are.

Here is a four-second curve with a key every 60th of a second, standing in for a take:

```diffstatic {data-lang=kotlin}
// Four seconds of a wobbling hand, a key every 60th of a second.
val rnd = java.util.Random(4)
val take = keyframes(name = "take", keys = (0..240).map { i ->
    val t = i / 60.0
    Keyframe(t, 0.5 + 0.35 * sin(t * 2.1) + 0.1 * sin(t * 7) + 0.04 * (rnd.nextDouble() - 0.5), "linear")
})

val start = System.nanoTime()
loop {
    take.setTime(((System.nanoTime() - start) / 1e9) % 4.0)
}
```

Smoothed, then simplified, from the curve's menu:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="960x470" clip="0,0,960,470" hide-toasts=true video="assets/keyframe_editor/tidy_take.mp4" transcript="../../dev_docs/agents/transcripts/docs_capture/keyframe_tidy_take.yaml" fps="15" padding="0" wait="0" timeout="240" embed=true hidden=true name="keyframe-tidy-take" alt="The take editor full of keys, a ring for every frame, jittering along a wave while the playhead plays. A right-click on empty graph opens the curve's menu, headed Whole curve, 241 keys. Dragging the Smooth slider to the right irons the jitter out of the curve while the keys stay put in time; letting go keeps it. Dragging the Simplify slider then thins the keys out until the readout says 17 are left, spread along a curve of the same shape, and Undo in the menu lights up"}
ui.waitForSelector("iframe[src*='/keyframe?']", 20000.0)
// The iframe arrives before its keys do; wait until the editor is drawing the whole take.
val ready = """
  const f = Array.from(document.querySelectorAll('iframe')).filter(f => (f.src || '').indexOf('/keyframe?') >= 0 && f.getBoundingClientRect().width > 0).pop();
  return f && f.contentDocument ? f.contentDocument.querySelectorAll('.kf-keyframe').length : 0;
""".trimIndent()
var tries = 0
while (ui.eval(ready).toString().trim('"') != "241" && tries++ < 60) ui.pause(250)
// The plotting area, in page coordinates, once it has stopped moving.
val area = """
  const f = Array.from(document.querySelectorAll('iframe')).filter(f => (f.src || '').indexOf('/keyframe?') >= 0 && f.getBoundingClientRect().width > 0).pop();
  const fr = f.getBoundingClientRect();
  const hit = Array.from(f.contentDocument.querySelectorAll('rect')).find(r => r.style.cursor === 'crosshair');
  const r = hit.getBoundingClientRect();
  return [fr.left + r.left, fr.top + r.top, r.width, r.height].map(Math.round).join(' ');
""".trimIndent()
var rect = ""
tries = 0
while (tries++ < 40) {
    val now = ui.eval(area).toString().trim('"')
    if (now.isNotEmpty() && now == rect) break
    rect = now
    ui.pause(200)
}
val g = rect.split(" ").map { it.toDouble() }
// The pointer moves in small timed steps, as a hand does, rather than in one call.
fun glide(x0: Double, y0: Double, x1: Double, y1: Double, steps: Int) {
    for (k in 1..steps) {
        ui.moveMouse(x0 + (x1 - x0) * k / steps, y0 + (y1 - y0) * k / steps, 1)
        ui.pause(40)
    }
}
// A slider in the open menu, by its label: the page coordinates of its left end and its width.
fun slider(label: String): List<Double> = ui.eval("""
  const row = Array.from(document.querySelectorAll('.kfm .kfm-tool')).find(r => r.textContent.indexOf('$label') === 0);
  const r = row.querySelector('input').getBoundingClientRect();
  return [r.left, r.top + r.height / 2, r.width].map(Math.round).join(' ');
""".trimIndent()).toString().trim('"').split(" ").map { it.toDouble() }

// Keep the main page producing frames. The recorder only gets a new one when this page paints, and
// the editor repaints inside its own frame, so without this the video holds a stale picture while
// the playhead goes on moving underneath. One pixel, all but transparent.
ui.eval("const k = document.createElement('div'); k.style = 'position:fixed;left:0;top:0;width:1px;height:1px;pointer-events:none;z-index:2147483647'; document.body.appendChild(k); let on = false; (function tick() { on = !on; k.style.opacity = on ? '0.011' : '0.010'; requestAnimationFrame(tick); })(); return 'ok'")
ui.pause(600)
// The curve's menu, from empty graph at the right, so it opens beside the curve rather than over it.
val mx = g[0] + g[2] * 0.97
val my = g[1] + g[3] * 0.08
ui.moveMouse(mx, my, 10)
ui.rightClickAt(mx, my)
ui.pause(700)
// Smooth: drag its thumb right, and let go.
val sm = slider("Smooth")
ui.moveMouse(sm[0] + 6, sm[1], 10)
ui.pause(200)
ui.mouseDown()
glide(sm[0] + 6, sm[1], sm[0] + sm[2] * 0.55, sm[1], 30)
ui.mouseUp()
ui.pause(900)
// Simplify: the same, until about twenty keys are left.
val si = slider("Simplify")
ui.moveMouse(si[0] + 6, si[1], 10)
ui.pause(200)
ui.mouseDown()
glide(si[0] + 6, si[1], si[0] + si[2] * 0.45, si[1], 40)
ui.mouseUp()
ui.pause(1500)
```

From code, `simplify(tolerance)` and `smooth(width)` do the same to the whole curve. `tolerance` is how far the curve may move, in its own units, and `width` is the stretch of time each key is averaged over, the standard deviation of a Gaussian:

```diffstatic {data-lang=kotlin}
take.smooth(0.05)      // over about a twentieth of a second
take.simplify(0.01)    // within 0.01 of the smoothed curve, everywhere
```

A take from a gesture pad bakes into two lanes whose keys share their times. Smoothing a lane leaves its times alone, but simplifying the lanes one at a time does not. To thin out a take, smooth it and bake it again, as in [A shaky take](#a-shaky-take).

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

To start a curve with keys of particular types, pass `keys` instead of `defaults`; to add types of your own, see below. This curve has one of each:

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

### The library

Beyond those five, every editor has fifty more types, grouped in the key menu:

- **Ease**: the standard easing family, each as in, out and in-out: sine, quad, cubic, quart, quint, expo, circ, back (a small overshoot), elastic and bounce.
- **Spline**: curves that look past the segment to the keys either side. Catmull-Rom passes smoothly through every key; the four TCB (Kochanek–Bartels) variants change its tension, bias and continuity, for a tighter curve, a looser one, one that overshoots, or one with a corner at the key. Monotone (Fritsch–Carlson) and Steffen never overshoot: between two keys the curve stays between their values, which is what you want for anything that must not run past its target.
- **Shape**: smoothstep, smootherstep, cosine, sigmoid, approach (fast, then easing in), slow middle, a damped spring that settles on the next key, and 4 or 8 steps.
- **Noise**: gradient noise around the straight line between the keys, pinned to both of them: noise, busy (several octaves), drift (slow), and jitter (a new value sixteen times a segment). The noise is the same every time for the same keys.

The key menu lists them all:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="760x1000" clip-selector=".kfm" hide-toasts=true screenshot="assets/keyframe_editor/library_menu.png" transcript="../../dev_docs/agents/transcripts/docs_capture/keyframe_editing.yaml" padding="0" wait="0" timeout="240" embed=true hidden=true name="keyframe-library-menu" alt="The key menu, tall: the key's time and value fields; the five built-in types; under EASE a table of ten families, sine, quad, cubic, quart, quint, expo, circ, back, elastic and bounce, each with small icons for in, out and in-out, the elastic and bounce icons visibly wobbling; under SPLINE, Catmull-Rom, TCB tense, TCB loose, TCB overshoot, TCB corner, monotone and Steffen; under SHAPE, smoothstep, smootherstep, cosine, sigmoid, approach, slow middle, spring, 4 steps and 8 steps; under NOISE, noise, busy, drift and jitter; then Copy, Cut, Duplicate, Paste here, Lock, Delete and Frame curve"}
ui.waitForSelector("iframe[src*='/keyframe?']", 20000.0)
val ready = """
  const f = Array.from(document.querySelectorAll('iframe')).filter(f => (f.src || '').indexOf('/keyframe?') >= 0 && f.getBoundingClientRect().width > 0).pop();
  return f && f.contentDocument ? f.contentDocument.querySelectorAll('.kf-keyframe').length : 0;
""".trimIndent()
var tries = 0
while (ui.eval(ready).toString().trim('"') != "4" && tries++ < 60) ui.pause(250)
val keyScript = """
  const f = Array.from(document.querySelectorAll('iframe')).filter(f => (f.src || '').indexOf('/keyframe?') >= 0 && f.getBoundingClientRect().width > 0).pop();
  const fr = f.getBoundingClientRect();
  const r = f.contentDocument.querySelectorAll('.kf-keyframe circle')[0].getBoundingClientRect();
  return Math.round(fr.left + r.left + r.width / 2) + ',' + Math.round(fr.top + r.top + r.height / 2);
""".trimIndent()
// Measured only once the page has stopped moving: the panel may still be opening or panning into view.
fun keyNow(): List<Double> {
    var last = ""
    repeat(30) {
        val now = ui.eval(keyScript).toString().trim('"')
        if (now == last) return now.split(",").map { it.toDouble() }
        last = now
        ui.pause(150)
    }
    return last.split(",").map { it.toDouble() }
}
val key = keyNow()
ui.moveMouse(key[0], key[1], 4)
for (attempt in 1..3) {
    val k = keyNow()
    ui.rightClickAt(k[0], k[1])
    ui.pause(600)
    if (ui.eval("return !!document.querySelector('.kfm .kfm-ease')").toString() == "true") break
}
val state = ui.eval("""
  const f = Array.from(document.querySelectorAll('iframe')).filter(f => (f.src || '').indexOf('/keyframe?') >= 0 && f.getBoundingClientRect().width > 0).pop();
  const d = f.contentDocument, fr = f.getBoundingClientRect();
  const el = d.elementFromPoint(${key[0]} - fr.left, ${key[1]} - fr.top);
  const top = document.elementFromPoint(${key[0]}, ${key[1]});
  return 'host menu ' + !!document.querySelector('.kfm') + ', frame menu ' + d.querySelectorAll('.kfm').length +
    ', under pointer in frame ' + (el ? el.tagName + '.' + (el.getAttribute('class') || '') : 'none') +
    ', in page ' + (top ? top.tagName + '.' + (top.className || '') : 'none');
""".trimIndent()).toString().replace("\"", "'")
if (!state.contains("host menu true")) error("KEYMENU-DIAG " + state)
ui.pause(400)
```

### Your own key types

`interpolant("name") { ... }` defines a key type of your own: a segment shape written in Kotlin, as the value the curve should have at each point of the segment. Inside the braces `u` runs from 0 to 1 across the segment, `t` is the time itself, `v0` and `v1` are the values of the keys either side, and `lerp(f)` is "this far from `v0` to `v1`", so `lerp(u)` is a straight line. `before` and `after` are the keys beyond the segment's two, when there are any, for a shape that needs to know where the curve came from and where it is going, as the splines do. The new types appear in the key menu, after the five built in. This box defines three: an overshoot, a wobble that dies away into the next key, and deterministic chaos, which shows that a type can be any Kotlin at all, loops and variables included:

```diffstatic {data-lang=kotlin}
interpolant("overshoot") { lerp(1 + 2.7 * (u - 1).pow(3) + 1.7 * (u - 1).pow(2)) }
interpolant("wobble") { lerp(u) + 0.08 * sin(u * 6 * PI) * (1 - u) }
// Deterministic chaos: the logistic map, seeded by where we are, and pinned to both keys.
interpolant("chaos") {
    var x = 0.1 + 0.8 * u
    repeat(30) { x = 3.97 * x * (1 - x) }
    lerp(u) + 0.25 * sin(PI * u) * (x - 0.5)
}

val custom = keyframes(name = "custom", keys = listOf(
    Keyframe(0.0, 0.1, "overshoot"),
    Keyframe(1.5, 0.8, "wobble"),
    Keyframe(3.0, 0.3, "chaos"),
    Keyframe(4.5, 0.6, "linear"),
))
```

Drag a key and the shapes either side of it follow, worked out in Kotlin as you move:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="700x330" clip="0,0,700,330" hide-toasts=true video="assets/keyframe_editor/custom_types.mp4" transcript="../../dev_docs/agents/transcripts/docs_capture/keyframe_custom_types.yaml" fps="20" padding="0" wait="0" timeout="240" embed=true hidden=true name="keyframe-custom-types" alt="The custom curve of four keys: an overshoot from 0.1 up to the key at time 1.5, a wobble down to 0.3 at time 3, and a jagged chaotic scribble up to 0.6 at time 4.5. The second key is dragged through half a circle over the top and let go further right and level with where it began, and the overshoot before it and the wobble after it re-form as it moves and stay as they are when it is let go; then the third key is dragged through half a circle below and let go to the right, the wobble and the chaotic scribble either side of it following it the same way"}
ui.waitForSelector("iframe[src*='/keyframe?']", 20000.0)
val ready = """
  const f = Array.from(document.querySelectorAll('iframe')).filter(f => (f.src || '').indexOf('/keyframe?') >= 0 && f.getBoundingClientRect().width > 0).pop();
  return f && f.contentDocument ? f.contentDocument.querySelectorAll('.kf-keyframe').length : 0;
""".trimIndent()
var tries = 0
while (ui.eval(ready).toString().trim('"') != "4" && tries++ < 60) ui.pause(250)
// Key [i] in page coordinates, measured once the page has stopped moving.
fun keyAt(i: Int): List<Double> {
    val script = """
      const f = Array.from(document.querySelectorAll('iframe')).filter(f => (f.src || '').indexOf('/keyframe?') >= 0 && f.getBoundingClientRect().width > 0).pop();
      const fr = f.getBoundingClientRect();
      const r = f.contentDocument.querySelectorAll('.kf-keyframe circle')[$i].getBoundingClientRect();
      return Math.round(fr.left + r.left + r.width / 2) + ',' + Math.round(fr.top + r.top + r.height / 2);
    """.trimIndent()
    var last = ""
    repeat(30) {
        val now = ui.eval(script).toString().trim('"')
        if (now == last) return now.split(",").map { it.toDouble() }
        last = now
        ui.pause(150)
    }
    return last.split(",").map { it.toDouble() }
}

// Half a circle from the key, ending somewhere else, then let go: Alt held so nothing snaps.
fun halfCircle(i: Int, radius: Double, up: Boolean) {
    val key = keyAt(i)
    val cx = key[0] + radius
    val cy = key[1]
    ui.moveMouse(key[0], key[1], 6)
    ui.pause(300)
    ui.down("Alt")
    ui.mouseDown()
    for (step in 1..70) {
        val a = Math.PI + step / 70.0 * Math.PI
        ui.moveMouse(cx + radius * Math.cos(a), cy + (if (up) 1 else -1) * radius * Math.sin(a), 1)
        ui.pause(22)
    }
    ui.mouseUp()
    ui.up("Alt")
}

halfCircle(1, 40.0, up = true)
ui.pause(1200)
halfCircle(2, 40.0, up = false)
ui.pause(1400)
```

A key type is saved with the box, as its source, so a curve keeps its shape even before the box defines its types again, or after the definition has been deleted. Define a type again to change it; every segment that uses it follows.

## Reading the curve

`evaluateValueAt(t)` is the value at time `t`, and the one call most boxes need. Before the first key it is the first key's value, and after the last key it is the last key's value, so a curve holds still outside the range you drew. To loop it, wrap the time, as the first example does with `% 4.0`. The rest of the evaluator describes the curve:

```diffstatic {data-lang=kotlin}
brightness.evaluateValueAt(1.5)        // the value at time 1.5
brightness.evaluateDerivativeAt(1.5)   // how fast it is changing there, per unit of time (exact)
brightness.getTimeRange()              // (first key time, last key time)
brightness.getKeyframes()              // the keys: time, value, type, handles
brightness.setTime(1.5)                // where the editor draws its playhead
```


`getTimeRange()` is how a loop wraps a curve whose length you change in the editor: `val (t0, t1) = brightness.getTimeRange()`, then use `t0 + (t % (t1 - t0))`.

## Changing the curve from code

A box can edit the keys too. Each of these changes the curve the way an edit in the editor would: the editor shows the new keys and they are saved with the box. This one adds a key and then halves every value:

```diffstatic {data-lang=kotlin}
brightness.add(5.0, 0.8, "bezierFlat")
brightness.edit { replaceAll { it.copy(value = it.value * 0.5) } }
```

`setKeys(listOf(...))` replaces them all; `remove(key)`, `removeAt(index)` and `clear()` take them away; `simplify(tolerance)` and `smooth(width)` [tidy a take](#simplifying-and-smoothing-a-take). Changes from code do not call `onChange`, which is for edits made in the editor.

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

```diffstatic {data-lang=kotlin}
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

The editors in one box share a time axis, like the lanes of a dope sheet. Pan or zoom time in one and the others follow, the pointer's time is marked in all of them, and Frame curve frames every lane's keys together. Each keeps its own value axis.

To work on a moment across all of them, hold Cmd (Ctrl elsewhere) and sweep a band: it selects the keys in that stretch of time in every lane. Drag one of them and the selected keys in the other lanes move by the same time, keeping their values; the arrow keys and Delete act on every lane's selection too. A click on another key, or Escape, goes back to working in one lane:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="700x900" clip="0,0,700,900" hide-toasts=true video="assets/keyframe_editor/two_curves.mp4" transcript="../../dev_docs/agents/transcripts/docs_capture/keyframe_two_lanes.yaml" fps="15" padding="0" wait="0" timeout="240" embed=true hidden=true name="keyframe-two-curves" alt="Two editors, x and y, stacked above an XY pad named spot whose handle traces a loop while both playheads sweep. The pointer moves across the x lane and a dashed time line follows it in the y lane. A zoom in the x lane narrows the time axis of both lanes at once; a scroll in the y lane slides both along in time; Frame curve, chosen from the y lane's menu, returns both to showing all their keys. Then a band swept across x's second key selects a key in each lane, and dragging x's key later in time carries y's selected key along by the same amount"}
ui.waitForSelector("iframe[src*='/keyframe?']", 20000.0)
// The two lanes' plotting areas, in page coordinates, once both are showing their keys.
val lanes = """
  const frames = Array.from(document.querySelectorAll('iframe')).filter(f => (f.src || '').indexOf('/keyframe?') >= 0 && f.getBoundingClientRect().width > 0);
  if (frames.length < 2) return '';
  const parts = frames.map(f => {
    const fr = f.getBoundingClientRect();
    const hit = Array.from(f.contentDocument.querySelectorAll('rect')).find(r => r.style.cursor === 'crosshair');
    if (!hit || f.contentDocument.querySelectorAll('.kf-keyframe').length < 3) return '';
    const r = hit.getBoundingClientRect();
    return [fr.left + r.left, fr.top + r.top, r.width, r.height].map(Math.round).join(' ');
  });
  return parts.every(p => p) ? parts.join(';') : '';
""".trimIndent()
var rects = ""
var tries = 0
while (tries++ < 60) {
    val now = ui.eval(lanes).toString().trim('"')
    if (now.isNotEmpty() && now == rects) break
    rects = now
    ui.pause(200)
}
val (lx, ly) = rects.split(";").map { r -> r.split(" ").map { it.toDouble() } }
fun at(lane: List<Double>, fx: Double, fy: Double) = (lane[0] + lane[2] * fx) to (lane[1] + lane[3] * fy)
// The pointer moves in small timed steps, as a hand does, rather than in one call.
fun glide(x0: Double, y0: Double, x1: Double, y1: Double, steps: Int) {
    for (k in 1..steps) {
        ui.moveMouse(x0 + (x1 - x0) * k / steps, y0 + (y1 - y0) * k / steps, 1)
        ui.pause(40)
    }
}

// Keep the main page producing frames. The recorder only gets a new one when this page paints, and
// the lanes repaint inside their own frames, so without this the video holds a stale picture while
// the curves, playheads and pad go on changing underneath. One pixel, all but transparent.
ui.eval("const k = document.createElement('div'); k.style = 'position:fixed;left:0;top:0;width:1px;height:1px;pointer-events:none;z-index:2147483647'; document.body.appendChild(k); let on = false; (function tick() { on = !on; k.style.opacity = on ? '0.011' : '0.010'; requestAnimationFrame(tick); })(); return 'ok'")
ui.pause(600)
// The pointer's time is marked in the other lane.
val (h0x, h0y) = at(lx, 0.15, 0.5)
ui.moveMouse(h0x, h0y, 4)
val (h1x, h1y) = at(lx, 0.85, 0.5)
glide(h0x, h0y, h1x, h1y, 30)
ui.pause(400)
// Zoom time in x: y follows.
val (zx, zy) = at(lx, 0.35, 0.5)
ui.moveMouse(zx, zy, 10)
repeat(8) { ui.wheel(zx, zy, 0.0, -50.0, "Control"); ui.pause(70) }
ui.pause(700)
// Slide along in time from y: x follows.
val (px, py) = at(ly, 0.5, 0.5)
ui.moveMouse(px, py, 16)
repeat(10) { ui.wheel(px, py, 30.0, 0.0); ui.pause(60) }
ui.pause(700)
// Frame curve, from y's own menu: both lanes show all their keys again.
val (fx, fy) = at(ly, 0.9, 0.15)
ui.moveMouse(fx, fy, 8)
ui.rightClickAt(fx, fy)
ui.pause(600)
val fit = ui.eval("""
  const b = Array.from(document.querySelectorAll('.kfm .kfm-item')).find(e => e.textContent.indexOf('Frame curve') >= 0);
  if (!b) return '';
  const r = b.getBoundingClientRect();
  return Math.round(r.left + r.width / 2) + ',' + Math.round(r.top + r.height / 2);
""".trimIndent()).toString().trim('"')
if (fit.isNotEmpty()) {
    val (bx, by) = fit.split(",").map { it.toDouble() }
    ui.moveMouse(bx, by, 8)
    ui.pause(300)
    ui.clickAt(bx, by)
}
ui.pause(800)

// A moment across both lanes: Cmd-sweep a band in x, around its second key.
fun keyIn(lane: Int, i: Int): Pair<Double, Double> {
    val p = ui.eval("""
      const frames = Array.from(document.querySelectorAll('iframe')).filter(f => (f.src || '').indexOf('/keyframe?') >= 0 && f.getBoundingClientRect().width > 0);
      const f = frames[$lane], fr = f.getBoundingClientRect();
      const r = f.contentDocument.querySelectorAll('.kf-keyframe circle')[$i].getBoundingClientRect();
      return Math.round(fr.left + r.left + r.width / 2) + ',' + Math.round(fr.top + r.top + r.height / 2);
    """.trimIndent()).toString().trim('"').split(",").map { it.toDouble() }
    return p[0] to p[1]
}
val (k1x, k1y) = keyIn(0, 1)
val bandY = lx[1] + lx[3] * 0.92
ui.moveMouse(k1x - 30, bandY, 10)
ui.down("Meta")
ui.mouseDown()
glide(k1x - 30, bandY, k1x + 90, bandY, 20)
ui.mouseUp()
ui.up("Meta")
ui.pause(700)
// Drag x's key later in time: y's selected key goes with it.
ui.moveMouse(k1x, k1y, 10)
ui.pause(250)
ui.down("Alt")
ui.mouseDown()
glide(k1x, k1y, k1x + 70, k1y, 30)
ui.mouseUp()
ui.up("Alt")
ui.pause(1500)
```

Each editor shows in its own output panel, named after it. To keep separate sets in one box, give each set a channel: `keyframes("motion", "x")` and `keyframes("motion", "y")` show as `motion/x` and `motion/y`, and share a time axis with each other but not with the box's other editors.

## From a performed gesture

A [gesture pad](visualizers.md#recording-a-gesture) records a movement as you perform it; the editor is where you shape one by hand. `bakeGesture` turns a take into a pair of lanes you can edit, and `setTake` sends edited lanes back to the pad:

```diffstatic {data-lang=kotlin}
lateinit var pad: GestureHandle
controlGroup("gesture") { pad = gesture("take") { } }   // shift-drag on the pad to record

val (x, y) = bakeGesture(pad, tolerance = 0.02)   // the take, as editable x and y lanes
println(pad.fit(x, y))                            // GestureFit(keys=28 of 240 samples, max=0.0185, ...)

pad.setTake(x, y)                                 // later: the edited lanes, replayed through the pad
```

A recorded take has hundreds of samples, far too many keys to edit. `bakeGesture` keeps only the samples it needs so that neither lane is ever more than `tolerance` (in pad units) from what you performed, at any moment. The keys are `linear`, and both lanes have keys at the same times, so Cmd-dragging across a moment selects it in both. The lanes run from 0 to the end of the take, so they loop as the take did. Baking replaces whatever keys the lanes held. The lanes are `x` and `y` unless you name them with `xName` and `yName`, and `channel` groups them as it does for `keyframes(...)`.

For a two-second figure eight drawn by hand, 240 samples:

<div class="field-data-table" markdown>

| tolerance | keys | largest difference | mean difference |
| --- | --- | --- | --- |
| 0.005 | 77 | 0.0050 | 0.0017 |
| 0.01 | 35 | 0.0096 | 0.0031 |
| 0.02 | 28 | 0.0185 | 0.0049 |
| 0.05 | 16 | 0.0338 | 0.0162 |
| 0.1 | 14 | 0.0980 | 0.0251 |

</div>

`setTake(x, y)` samples the two curves 60 times a second (`samplesPerSecond` changes it), always including every key's time, and that becomes the pad's take: the trail redraws and the take is saved with the box, as if you had recorded it. Bezier keys come back as a smooth run of short lines, within a fraction of a thousandth of the curve. `toKeyframes(tolerance)` gives the two lists of keys without touching any editor, and `samples` gives the take itself, in the form `gesture(initialTake = ...)` accepts.

### A shaky take

The tolerance keeps what you performed, tremor and all. The same figure eight drawn by a shakier hand bakes to 146 keys of its 240 samples, most of them following the shake. To keep the movement and lose the tremor, smooth the take before you bake it: bake every sample, smooth both lanes, send them back to the pad, and bake again. The second bake needs 29 keys, and both lanes still have their keys at the same times. Using **Simplify** on each lane separately would give each lane different key times.

```diffstatic {data-lang=kotlin}
fun smoothTake() {
    val (sx, sy) = bakeGesture(pad, tolerance = 0.0, channel = "lanes")   // every sample, as keys
    sx.smooth(0.04)                                                       // over 40 ms
    sy.smooth(0.04)
    pad.setTake(sx, sy)                                                   // the smoothed take, on the pad
    bakeGesture(pad, tolerance = 0.02, channel = "lanes")                 // and back, as a few keys
}

controlGroup("gesture") {
    pad = gesture("take", initialTake = eight) { p = it }
    button("smooth take") { if (it) smoothTake() }
}
```

Here it is in the [starting point](#start-from-a-working-document)'s box, given a shakier take, with the lanes above the pad. Pressing **smooth take** thins out both lanes and redraws the pad's trail without the shake:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="600x1110" clip="0,0,600,1110" hide-toasts=true video="assets/keyframe_editor/smooth_take.mp4" transcript="../../dev_docs/agents/transcripts/docs_capture/gesture_smooth_take.yaml" fps="15" padding="0" wait="0" timeout="240" embed=true hidden=true name="gesture-smooth-take" alt="Two keyframe lanes, lanes/x and lanes/y, packed with keys that follow a shaky sine in x and a shaky double wave in y, above a gesture pad whose trail is a figure eight with a ragged, trembling outline. Playheads sweep both lanes while a dot travels the eight. The hold button beside smooth take is pressed: both lanes thin out to a few smooth keys along the same waves, and the pad's trail redraws as a clean figure eight"}
ui.waitForSelector("iframe[src*='/keyframe?']", 20000.0)
// Both lanes showing their keys, and the page no longer moving.
val ready = """
  const frames = Array.from(document.querySelectorAll('iframe')).filter(f => (f.src || '').indexOf('/keyframe?') >= 0 && f.getBoundingClientRect().width > 0);
  return frames.length === 2 && frames.every(f => f.contentDocument && f.contentDocument.querySelectorAll('.kf-keyframe').length > 20) ? 'yes' : '';
""".trimIndent()
var tries = 0
while (ui.eval(ready).toString().trim('"') != "yes" && tries++ < 80) ui.pause(250)
// The hold button in the row labelled smooth take, in page coordinates, once it has stopped moving.
val button = """
  const b = Array.from(document.querySelectorAll('button')).find(b => {
    let row = b.parentElement;
    for (let k = 0; k < 3 && row; k++, row = row.parentElement) if ((row.textContent || '').toLowerCase().indexOf('smooth take') >= 0 && row.querySelectorAll('button').length === 1) return true;
    return false;
  });
  if (!b) return '';
  const r = b.getBoundingClientRect();
  return Math.round(r.left + r.width / 2) + ',' + Math.round(r.top + r.height / 2);
""".trimIndent()
var at = ""
tries = 0
while (tries++ < 40) {
    val now = ui.eval(button).toString().trim('"')
    if (now.isNotEmpty() && now == at) break
    at = now
    ui.pause(200)
}
val (bx, by) = at.split(",").map { it.toDouble() }

// Keep the main page producing frames. The recorder only gets a new one when this page paints, and
// the lanes repaint inside their own frames, so without this the video holds a stale picture while
// the playheads go on moving underneath. One pixel, all but transparent.
ui.eval("const k = document.createElement('div'); k.style = 'position:fixed;left:0;top:0;width:1px;height:1px;pointer-events:none;z-index:2147483647'; document.body.appendChild(k); let on = false; (function tick() { on = !on; k.style.opacity = on ? '0.011' : '0.010'; requestAnimationFrame(tick); })(); return 'ok'")
ui.pause(1800)
ui.moveMouse(bx, by, 16)
ui.pause(300)
// Pressed from the keyboard, as a focused button takes Space: a click would also select the box and
// open its code over the panels.
ui.eval(button.replace("if (!b) return '';", "if (!b) return ''; b.focus();"))
ui.press("Space")
ui.pause(3200)
```

## Start from a working document

Open **keyframe_editor** from Starting points on the welcome screen, or [open it here](offline.html?file=assets/agent_transcripts/keyframe_editor/metadata.edn). It has four boxes from this page:

- `brightness`: the curve and meter (launch it to play);
- `shape`: the five key types;
- `smoothed`: the curve with a smoothed overlay that follows your edits;
- `custom types`: an overshoot, a wobble and some chaos written with `interpolant(...)`.

Its entry in [Agent transcripts](agent_transcripts.md#keyframe_editor) lists what each box does.

## See also

- [Values over time: interpolants & keyframes](interpolants_examples.md): easing a value to a target from code, rather than drawing it.
- [Visualizers & Controls](visualizers.md): sliders, multisliders and the gesture recorder, for values you set or perform rather than draw.
- [Keyframes: capturing, persisting and replaying live state](keyframer_examples.md): snapshots of a box's whole state.
- [Drawing and Notation](drawing_and_notation.md#reading-by-intersection): to draw the change in one gesture rather than place keys, draw a line or a hairpin inside a frame on the canvas, and the box reads it back as a value at each moment with `notation.space(...).vertical(t)`, as the [crescendo](drawing_and_notation.md#a-crescendo) there does.
