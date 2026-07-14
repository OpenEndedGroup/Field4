# Visualizers, Displays, and Little Instruments

Field has a surprisingly rich vocabulary for showing values, not just computing them.

Some visuals live **inline in the editor** next to a line of code. Some live in **output areas** under a box. Some turn output areas into **inspectors** or **little control panels**. And some helpers, like `.h` and `.h2`, are really about turning arbitrary Kotlin values into usefully formatted things that are easy to read.

This page is a tour of the whole family.

## Inline editor visualizers

These are the ones that make the editor itself feel alive.

The core pattern is:

```diffstatic {data-lang=kotlin}
:::static
val m = __m("some-marker")
m.histogram(0.42)
```

The marker remembers a little state and renders HTML back into the editor gutter / inline output location for that line.

### Histogram, history, and summary stats

Histograms answer “what values have I seen?”, history answers “how did the value move?”, and stats gives you the quick executive summary.

<!--
FIELD_TEST
id: docs.visualizers.inline-metrics
runner: backdoor-box
tags: [docs, visualizers, inline, histogram, history, stats]
timeoutSec: 120
waitSec: 2.0
expect:
  outputContains:
    - "inline metrics ready"
  eventKinds:
    - output
  screenshot:
    kind: editor
    padding: 10
-->
```diffstatic_runner {data-lang=kotlin video="assets/visualizers-inline-metrics.mp4" wait="1.0" capture="editor" padding="10" execution="launch" duration="3" fps="12" timeout="120" embed=true name="inline-metrics"}
:::static
var tick = 0.0
var visualizerFrame = 0

println("inline metrics ready")

loop {
    tick += 0.4
    visualizerFrame += 1

    if (visualizerFrame % 12 == 0) {
        val value = sin(tick * 0.22) * 0.7 +
            cos(tick * 0.11) * 0.2 +
            tick * 0.01

        __m("histogram").histogram(value, dur = 120)
        __m("trail").history(value, dur = 48)
        __m("summary").stats(value, dur = 48)
    }
    frame()
}
```

Those three calls all share the same basic idea:

- `ᛟ.histogram(value)` keeps a rolling distribution.
- `ᛟ.history(value)` keeps a rolling time series.
- `ᛟ.stats(value)` gives a compact summary panel.

In the editor you can add a marker rune (ᛟ), with f1, "Insert Stack Mark", or shift+command period:

![Insert stack mark command](assets/screenshot_26.png)

These functions are **passthroughs**. They return the value they were given, so you can drop them into a chain when you want to inspect the value flowing through a wire without changing the computation:

```diffstatic {data-lang=kotlin}
:::static
val filtered = __m("cutoff").history(rawCutoff)
```

That makes them excellent debugging instruments: tiny scopes for values, not transformations of values.

If you want a **live** feed rather than a one-shot summary, write a loop and **launch** the box:

<!--
FIELD_TEST
id: docs.visualizers.inline-live-history
runner: backdoor-box
tags: [docs, visualizers, inline, history, live]
timeoutSec: 120
execution: launch
waitSec: 5.0
expect:
  screenshot:
    kind: editor
    padding: 10
-->
```diffstatic_runner {data-lang=kotlin video="assets/visualizers-inline-live-history.mp4" wait="2.0" capture="editor" padding="10" execution="launch" duration="3" fps="12" timeout="120" embed=true name="inline-live-history"}
:::static
println("live history launched")

var phase = 0.0
var visualizerFrame = 0

loop {
    visualizerFrame += 1
    if (visualizerFrame % 12 == 0) {
        val y = sin(phase * 0.8) + 0.2 * sin(phase * 7.0)
        __m("live").history(y, dur = 120)
    }
    phase += 0.07
    frame()
}
```

```diffstatic {data-lang=kotlin}
:::static
loop {
    val t = time
    val y = sin(t * 0.8) + 0.2 * sin(t * 7.0)
    __m("live").history(y, dur = 120)
    frame()
}
```

Run that as a launch, not a plain execute. For docs or screenshots, though, finite fake data is usually easier and more stable.

### Piano rolls in the editor

The piano-roll visualizer is the same idea, but for scheduled notes rather than scalar values.

<!--
FIELD_TEST
id: docs.visualizers.inline-pianoroll
runner: backdoor-box
tags: [docs, visualizers, inline, synth, pianoroll]
timeoutSec: 120
execution: launch
waitSec: 5.0
expect:
  # The box's println lands on process stdout, not on a collectable "output" event,
  # and the pianoRoll() visualizer draws to the editor rather than emitting output
  # events — so neither outputContains (which scans events) nor eventKinds:[output]
  # can see it. The editor screenshot is the reliable signal the visualizer rendered.
  allowMissingOutput: true
  screenshot:
    kind: editor
    padding: 10
-->
```diffstatic_runner {data-lang=kotlin video="assets/visualizers-inline-pianoroll.mp4" wait="2.0" capture="editor" padding="10" execution="launch" duration="3" fps="12" timeout="120" embed=true name="inline-pianoroll"}
:::static
val arp = noteProcess {
    val chord = listOf(48, 55, 60, 64)
    var step = 0
    while (true) {
        note(
            pitch = chord[step % chord.size],
            duration = 0.18,
            velocity = 0.85,
            label = "n$step"
        )
        rest(0.25)
        step++
    }
}

println("inline piano roll launched")

var playhead = 0.0
var visualizerFrame = 0

loop {
    visualizerFrame += 1
    if (visualizerFrame % 12 == 0) {
        __m("arp").pianoRoll(arp.at(playhead))
    }
    playhead += 0.08
    frame()
}
```

This is wonderful for sequencers, generative rhythm systems, or anything where you want to see future note structure without leaving the code.

## Display channels: the big canvas under the box

Inline visuals are intimate. `display(...)` is theatrical.

Anything you pass to `display("channel", thing)` becomes HTML in a named output area. That HTML can be handwritten, auto-generated, interactive, or even an embedded browser/inspector.

### Raw HTML, pretty HTML, and heavy HTML

- `display("channel", "<div>...</div>")` is the raw escape hatch.
- `.h` turns a value into a lightweight pretty HTML view.
- `.h2` turns a heavier structure into a paged browser / explorer.

<!--
FIELD_TEST
id: docs.visualizers.display-html
runner: backdoor-box
tags: [docs, visualizers, display, html, h, h2]
timeoutSec: 45
waitSec: 2.5
expect:
  outputContains:
    - "display helpers ready"
  eventKinds:
    - set-output
  screenshot:
    kind: box
    padding: 16
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/visualizers-display-html.png" wait="4.0" capture="box" open-outputs="true" embed=true name="display-html"}
:::static
val tiny = linkedMapOf(
    "tempo" to 122,
    "mode" to "mixolydian",
    "active" to true,
    "notes" to listOf("C", "E", "G", "Bb")
)

val big = (0 until 18).associate { row ->
    "row-$row" to (0 until 10).associate { col ->
        "c$col" to row * col
    }
}

display(
    "hello",
    "<div style=\"padding:18px;background:#101820;color:#f2aa4c;border:1px solid #2d4a5d\">" +
        "<b>display(...)</b> can show any HTML you like." +
        "</div>"
)
display("pretty", tiny.h)
display("heavy", big.h2)

println("display helpers ready")
```

Use `.h` when the structure is small enough that you want a pleasant, immediate rendering. Use `.h2` when you want to browse a large map, list, array, or buffer without turning the output area into soup.

### Trees and columns

Sometimes the right answer is not “render this as a card”, it is “let me inspect this like a little filesystem”.

<!--
FIELD_TEST
id: docs.visualizers.display-browsers
runner: backdoor-box
tags: [docs, visualizers, display, tree, column, browser]
timeoutSec: 45
waitSec: 2.5
expect:
  outputContains:
    - "inspector panes ready"
  eventKinds:
    - set-output
  screenshot:
    kind: box
    padding: 16
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/visualizers-display-browsers.png" wait="3.0" capture="box" open-outputs="true" w="1200" h="260" embed=true name="display-browsers"}
:::static
val scene = linkedMapOf(
    "camera" to linkedMapOf(
        "x" to 10,
        "y" to 20,
        "zoom" to 1.5,
        "active" to true
    ),
    "layers" to listOf(
        linkedMapOf(
            "name" to "dots",
            "visible" to true,
            "points" to listOf(2, 4, 8, 16)
        ),
        linkedMapOf(
            "name" to "lines",
            "visible" to false,
            "style" to linkedMapOf("width" to 2, "color" to "#f2aa4c")
        )
    ),
    "palette" to linkedMapOf(
        "background" to "#101820",
        "accent" to "#f2aa4c"
    )
)

tree("outline", scene)
column("columns", scene)

println("inspector panes ready")
```

- `tree("channel", value)` gives you an outline / tree browser.
- `column("channel", value)` gives you a column-style browser.

These are especially good for nested maps, scene graphs, structured JSON-like data, or anything you want to poke around in rather than summarize.

And while it is not a “visualizer” in the chart sense, `logs(...)` belongs in the same family:

```diffstatic {data-lang=kotlin}
:::static
logs("warnings", latestOnly = true) {
    cat = "warning"
}
```

It gives you a live log iframe inside an output channel, which is often exactly what you want while instrumenting a system.

### Keeping output areas tidy

You can manage channels directly:

```diffstatic {data-lang=kotlin}
:::static
displayOpen("preview")
displayClear("preview")
displayClearAll()
```

This is useful for demos, guided tutorials, and boxes that are meant to be rerun frequently.

## Controls: sliders, toggles, colors, text, and XY pads

Some outputs are not there to be looked at. They are there to be touched.

Field can build small, pleasant control surfaces directly into output areas.

<!--
FIELD_TEST
id: docs.visualizers.controls
runner: backdoor-box
tags: [docs, visualizers, controls, slider, vec2, color]
timeoutSec: 45
waitSec: 0.5
expect:
  outputContains:
    - "controls ready"
  eventKinds:
    - set-output
  screenshot:
    kind: box
    padding: 16
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/visualizers-controls.png" wait="1.0" capture="box" open-outputs="true" w="1200" h="180" embed=true name="controls"}
:::static
controlGroup("controls") {
    slider("gain", min = 0.0, max = 1.0, step = 0.01, initialValue = 0.35) { }
    vec2Control("orbit", initialValue = afield.stdlib.linalg.Vector2f(0.35f, -0.2f)) { }
    checkbox("armed", initialValue = true) { }
    stringInput("name", initialValue = "Field") { }
    colorPicker("accent", initialValue = afield.stdlib.linalg.Vector4f(0.95f, 0.45f, 0.15f, 0.85f)) { }
}

println("controls ready")
```

Available pieces include:

- `slider(...)`
- `sliders(...)`
- `vec2Control(...)`
- `checkbox(...)`
- `stringInput(...)`
- `colorPicker(...)`
- `controlGroup(...) { ... }`

The `vec2Control(...)` is the “xy thing” in the nicest sense: an XY pad with a vector line, magnitude, and angle readout.

### Build one surface with mixed control types

`controlGroup(...)` is the easiest way to build a compact surface for one box. Pair it with a normal display channel when the controls need a readable status or preview panel.

<!--
FIELD_TEST
id: docs.visualizers.controls.mixed-surface
runner: backdoor-box
tags: [docs, visualizers, controls, vec2, checkbox, string, color]
timeoutSec: 45
waitSec: 0.5
expect:
  outputContains:
    - "mixed controls ready"
  eventKinds:
    - set-output
  screenshot:
    kind: box
    padding: 16
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/visualizers-mixed-controls.png" wait="1.0" capture="box" open-outputs="true" w="1200" h="180" embed=true name="mixed-controls"}
:::static
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector4f

var wind = Vector2f(-0.45f, 0.32f)
var bloom = 0.62
var trails = true
var label = "wake field"
var tint = Vector4f(0.25f, 0.75f, 1.0f, 0.9f)

controlGroup("surface") {
    slider("bloom", min = 0.0, max = 1.0, step = 0.01, initialValue = bloom) {
        bloom = it
    }
    vec2Control("wind", initialValue = wind, xRange = -1.0 to 1.0, yRange = -1.0 to 1.0, step = 0.05) {
        wind = it
    }
    checkbox("trails", initialValue = trails) {
        trails = it
    }
    stringInput("label", initialValue = label) {
        label = it
    }
    colorPicker("tint", initialValue = tint) {
        tint = it
    }
}

display(
    "preview",
    """
    <div style="width:620px;padding:18px;background:#07131b;border:1px solid #164a63;color:#83e6ff">
      <div style="font-size:20px;letter-spacing:0.08em;text-transform:uppercase">$label</div>
      <div style="margin-top:12px;height:96px;background:
        radial-gradient(circle at 35% 45%, rgba(64,190,255,0.65), transparent 22%),
        linear-gradient(135deg, rgba(255,120,40,0.18), rgba(40,210,255,0.20));
        border:1px solid rgba(131,230,255,0.35)"></div>
      <div style="margin-top:10px">wind=(${wind.x}, ${wind.y}) bloom=$bloom trails=$trails</div>
    </div>
    """.trimIndent()
)

println("mixed controls ready")
```

This pattern is good for surfaces that combine parameter editing with a compact readout:

- small simulation controls
- animation parameters
- quick scene presets
- reusable demo boxes

### Choosing the right control

- Use `slider(...)` for one bounded numeric value.
- Use `vec2Control(...)` for direction, position, velocity, or any paired numeric parameter.
- Use `checkbox(...)` for feature toggles and gates.
- Use `stringInput(...)` for names, labels, prompts, and small text parameters.
- Use `colorPicker(...)` for RGBA values stored as normalized `Vector4f` data.

The `vec2Control(...)` includes an XY pad, a vector line, and magnitude readout, so it works well for motion and layout values where two sliders would hide the relationship between axes.

## Signal and synth visualizers

Field's synth layer also comes with ready-made HTML and SVG visualizers for audio patches, scopes, envelopes, compressors, and related objects.

These are display-based rather than inline, which is usually the right choice because they want a bit more space.

<!--
FIELD_TEST
id: docs.visualizers.signal-panels
runner: backdoor-box
tags: [docs, visualizers, synth, signal, scope]
timeoutSec: 45
waitSec: 1.5
expect:
  outputContains:
    - "signal visualizers ready"
  eventKinds:
    - set-output
  screenshot:
    kind: box
    padding: 16
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/visualizers-signal-panels.png" wait="1.0" capture="box" open-outputs="true" padding="8" w="1200" h="180" embed=true name="signal-panels"}
:::static
import afield.four.synth.*

val sampleRate = 48_000f
val frames = 512
val cycles = 16
val out = FloatArray(frames)

fun renderSignal(signal: Signal) {
    repeat(cycles) {
        signal.render(out, frames, SynthContext(sampleRate, frames))
    }
}

val audioScope = (saw / FreeVerb()).scope()
renderSignal(audioScope)

val monoScope = (saw / MoogLadder(cutoff = 900.p)).scope()
renderSignal(monoScope)

val g = Gate().also { it.open() }
val env = ADSR(gate = g, release = 0.4.p)
renderSignal(env)
g.close()
renderSignal(env)

val comp = Compressor(threshold = (-18).p, ratio = 6.p)
comp.input = saw * 0.35
renderSignal(comp)

display("status", "<div style=\"padding:18px;background:#111827;color:#f9c74f\">signal visualizers ready</div>")
display("audio", audioScope.summaryHtml(widthPx = 560, waveformHeightPx = 72, spectrumHeightPx = 72))
display("scope", monoScope.summaryHtml(widthPx = 560, waveformHeightPx = 72, spectrumHeightPx = 72))
display("env", env.stateSvg())
display("comp", comp.stateSvg())
```

Good things to know here:

- `patch.summaryHtml()` gives you a panel view of an audio patch.
- `patch.liveSummary(...)` and `scope.liveSummary(...)` keep those panels moving while audio is running.
- `scope.summaryHtml()` and `stereoScope.summaryHtml()` render wave/spectrum-style panels when you want a snapshot instead.
- `env.stateSvg()` and `comp.stateSvg()` are compact SVG views for modulators / processors.
- `noteProcess.pianoRollSvg()` is great when you want the piano roll in an output area instead of inline in the editor.

So the choice is mostly about **where you want the visual to live**, not whether the visual exists.

## Choosing between inline and display

Here is the practical rule of thumb:

- If the visual explains one line of code, put it inline with `__m("...")`.
- If the visual explains a whole box or deserves interaction, put it in `display(...)`.
- If the thing is large and structural, use `tree(...)`, `column(...)`, or `.h2`.
- If the thing is tiny and human-readable, `.h` is often perfect.

And if you are documenting or testing it:

- use an **editor screenshot** for inline visuals
- use a **box screenshot** for output channels and controls
- remember that `histogram(...)` and `history(...)` are **passthrough probes** for debugging live values
- prefer **finite fake data** for docs when the visualizer does not need motion
- save `loop {}` + launch for genuinely live examples

The nice part is that all of these tools compose. One box can have a little inline history, a piano roll, a browser in one output channel, and a control surface in another. Field is very happy when code is also an interface.
