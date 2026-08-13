# A law, a button, and a meter — Field's control surface

`controlGroup { }` could express a number, a colour, a vector, a string and a latching boolean. Three
things it could not express turned out to be the same three things every parameter surface eventually
needs, and today's Faust merge put them in writing as "known gaps" — but they were never Faust's gaps.

1. **A slider had no law.** Travel was proportional to value, always. A 20 Hz–20 kHz control on a
   linear track puts every note below A2 inside the first 1% of the travel, so the first pixel is a
   200 Hz jump. That is not a resolution problem; no number of pixels fixes it. It is the wrong shape.
2. **There was no momentary control.** `checkbox` latches, so "while I am holding this" — a synth gate,
   a jog, a preview, a shutter — could only be expressed from code.
3. **There was no way to *see* a level.** A value that moves thirty times a second had no home in the
   UI at all; you printed it.

## The law

`ControlLaw` (`src/jvmMain/kotlin/afield/ControlLaw.kt`), four cases:

| law | `valueAt(t)` | domain | for |
|---|---|---|---|
| `LINEAR` | `min + t(max−min)` | any | the default; mix levels, blends |
| `LOGARITHMIC` | `min·(max/min)^t` | **`min > 0`** | frequency, time, anything "times more" |
| `EXPONENTIAL` | `min + (max−min)·t^γ` | any | a taper from zero: amplitude, attack time |
| `BIPOLAR` | a taper each side of a centre | `min < centre < max` | pan, detune, bipolar offsets |

```kotlin
controlGroup("filter") {
    slider("cutoff", min = 20.0, max = 20000.0, initialValue = 1000.0, law = ControlLaw.LOGARITHMIC) {
        cutoff = it
    }
}
```

Measured, on that exact control: **1 kHz sits at 56.6% of a logarithmic track and 4.90% of a linear
one** — 11.6× further along. Mid-travel is the geometric mean, 632.4555320336758 Hz.

### The four decisions worth knowing

**A logarithmic law over a non-positive minimum throws.** At declaration time, from
`ControlLawSpec.validate(label)`, with the control's name in the message and the two laws that do work
named in it. The alternatives were all worse: clamping the minimum up to an epsilon gives a control
that cannot reach its own stated minimum; falling back to linear makes the declared law a lie; doing
neither produces `-Infinity` and then the string `"NaN"` in `data-value`, which wedges the handle and
is diagnosed three files away.

**Both endpoints are short-circuited, in both directions and in both languages.** The naive
`min * (max/min).pow(1.0)` is exact for 20..20000 — which is exactly why this class of bug survives
review — and inexact for **4319 of the 79 000** small integer ranges `ControlLawTest` sweeps. Over
`7..29` it returns `29.000000000000004`, a value *above* the declared maximum, and that number is the
one the callback receives.

**`step` still snaps in value space.** A caller who says `step = 1.0` on a 20–20000 Hz log control
wants integer hertz and gets it. Snapping travel instead would make the step size vary with position,
which is unpredictable and undocumentable. Note the ordering: **snap first, then clamp**. `snap()` can
land outside the range (20000 onto a 0.03 step from a minimum of 20 gives 20000.0000000000036) and
clamping afterwards is the only thing that stops that escaping.

**Decimal places become adaptive when the law is nonlinear.** `calculateDecimalPlaces(step)` assumes
linearity and cannot be right at both ends of a log slider — the same pixel is worth 0.05 Hz at the
bottom and 460 Hz at the top. `controlLawDecimals(value, significantDigits, stepDecimals)` tracks the
magnitude instead, so one control reads `20.00`, `1000` and `20000`. A `significantDigits` of `0` means
"use the old rule", which is how **every existing linear slider keeps its formatting to the digit**.

## The momentary button

```kotlin
controlGroup("voice") {
    button("gate") { down -> if (down) voice.start() else voice.stop() }
}
```

The interesting half is the second event. The classic defect in this control class is a `pointerdown`
whose `pointerup` is delivered somewhere else — the pointer left the element, a touch turned into a
scroll, the window lost focus — leaving the control held and, behind it, a gate open. So the release is
listened for on the **window**, from five directions (`pointerup`, `pointercancel`, `touchend`,
`touchcancel`, `blur`), guarded by a held-set so exactly one release is sent per press and a stray
release with nothing held sends nothing. It is a real `<button>` inside the control shell, so tab, space
and enter reach it.

**A press is deliberately not cached.** Every other control in the builder restores its value across a
rerun — that is `cachedControl`, and it is exactly what live-coding wants. A cached *press* would
re-fire on every rerun: edit one character of a DSP and the note retriggers. `controlIdentity` is split
out of `cachedControl` so the button keeps its stable, label-derived id (a tablet still points at the
same control after a rerun) while the value is not remembered.

## The meter

```kotlin
controlGroup("voice") {
    val level = meter("level", min = 0.0, max = 1.0, source = { launch.meter("level") })
}
```

Returns a `MeterHandle`, so a box with a loop of its own can push instead: `level.set(rms)`.

**Who drives it, decided by the rates.** A level display wants ~30 Hz. A box rerun is a human
keystroke, so "update on rerun" was never a candidate. Browser polling was rejected: an HTTP round trip
per frame per client, with worse jitter, to fetch a number the server already knows it should send. So
it pushes, coalesced to `meterUpdateHz` = 30, and rides the **existing** `control-value-update` message
— which is why it needed no new message type, no new dispatcher, and reaches both surfaces. The desktop
output area gets the full 30 Hz; the remote tablet polls at **450 ms ≈ 2.2 Hz**, so there it is an
indicator rather than a VU meter. Making the tablet faster is a change to its polling loop and was left
alone.

Two consequences that look like details and are not:

* `updateControllerControlValueInMemory` exists because the ordinary update path writes the box's value
  cache, and that ends in `setPropertyRaw` — a document mutation. Thirty of those a second would churn
  `operations.yaml` for a number that is an output and has nothing to preserve across a rerun.
* `controllers.js` strips meter values out of its render signature. Otherwise a value that changes on
  every poll would rebuild the whole control group every 450 ms, throwing away the DOM and any slider a
  hand happened to be on.

`source` is sampled on one shared daemon thread, retired when the box runs again without declaring that
meter, and dropped permanently the first time it throws — which is what a stopped engine does, and which
would otherwise fill the log at 30 Hz.

## One law module, two surfaces

A control group is a single `ControllerControl` record served to two renderers: `sliders.js` gets it
over the websocket as `control-value-update`, and `controllers.js` (the remote tablet) polls it from
`/controllers/controls`. So the law lives in **one** module both pages load —
`src/commonMain/resources/controlLaws.js`, `window.FieldControlLaw` — rather than in two places that
agree today. `index.html` loads it before `sliders.js`; `controllers.html` before `controllers.js`.

The tablet's nonlinear sliders run their native `<input type=range>` over **travel**, 0..1, and map
through the law. Handing the native element the value bounds is precisely the bug; linear controls take
the unchanged old path.

## Seeing it

```
python3 tools/field_backdoor.py --port <free port> --with-playwright \
  --command "transcript write control_surface_laws --file dev_docs/agents/transcripts/control_surface_laws.yaml" \
  --command "transcript run control_surface_laws"
```

(one invocation: a scratch document's lifetime is per-invocation, so a `write` in one call and a `run`
in the next land in different scratch sessions.)

Three boxes. **Open the first one's output disclosure and drag `cutoff`, then drag `cutoff, linear`
directly below it** — identical ranges, and the handles are nowhere near each other. Press and hold the
button in the second box, then drag off it before letting go. Watch the two meters in the third.

## What is checked, and how

* `ControlLawTest` — the Kotlin half. Round trip `1.33e-15` in travel and `1.03e-15` relative in value
  over 11 laws × 101 positions (floors at `1e-13`); endpoint exactness by `assertEquals` on the exact
  double over all 4319 inexact ranges; the refusal as a thrown `IllegalArgumentException`; 242 hostile
  evaluations (NaN, ±Inf, out of range) that must all come back finite.
* `ControlSurfaceProbeTest` + `src/jvmTest/resources/controlSurface/probe.mjs` — **the shipped
  JavaScript, run under node against the HTML the real builder emitted.** `controlLaws.js` and
  `sliders.js` are staged verbatim off the classpath, which also makes them declared inputs of
  `jvmTest`, so editing either re-runs this rather than leaving a stale `UP-TO-DATE` pass. Worst
  Kotlin↔JavaScript law disagreement **1.33e-15** relative over 6 laws × 7 positions; the button
  sequence in full (`press+release [true,false]`, `release-far-outside [false]`, `pointercancel
  [false]`, `blur [false]`, `stray release []`); a meter's bar at 0 / 73 / 146 / 262.8 / 292 px of a
  292 px track with **zero** messages posted back; 13 139 characters of markup swept for `NaN`.
  Modelled on `FLineHitRecordProbeTest`, which is the house pattern for this.
* Pixels. Screenshotted through the backdoor and looked at: the log handle measured at 56.7% of its
  track against ~4% for the linear one with the same range; readouts `1000` and `1000.00` side by side;
  a button that looks like a button beside a checkbox that does not; and two meters showing a live
  level (`0.05` linear, `0.05461` on a log 0.001–1.0 meter — 58% of that bar, which is where a log law
  puts it), which is also the proof that the 30 Hz push reaches the browser.

---

# Hand-off: the three Faust call sites

**Nothing under `src/jvmMain/kotlin/afield/four/synth/` was touched by this branch** — a sibling branch
owns the Faust package tonight. All three of the "known gaps" at the bottom of `public/docs/faust.md`
close with a one-line change each, in `faustControls` (`FaustBox.kt:212`). After both branches land:

### 1. `[scale:log]` → a real law

`FaustScale` already exists (`FaustEngine.kt:525`) and `FaustControl.scale` (`FaustEngine.kt:554`)
already carries it. The `else ->` branch of `faustControls` (`FaustBox.kt:223`) needs the law passed
through:

```kotlin
else -> slider(
    label = control.displayLabel,
    min = control.min,
    max = control.max,
    step = if (control.step > 0.0) control.step else (control.max - control.min) / 200.0,
    initialValue = control.init,
    law = when {
        // A logarithmic law needs a positive minimum; Faust programs do declare `[scale:log]` on
        // ranges starting at 0 (a gain, say), and Field now throws rather than emitting a NaN — so
        // fall back to the taper, which is the right shape and accepts a zero minimum.
        control.scale == FaustScale.LOG && control.min > 0.0 -> ControlLaw.LOGARITHMIC
        control.scale == FaustScale.LOG -> ControlLaw.EXPONENTIAL
        control.scale == FaustScale.EXP -> ControlLaw.EXPONENTIAL
        else -> ControlLaw.LINEAR
    }
) { value -> launch.set(control, value) }
```

That `control.min > 0.0` guard is not optional. `faust_voice.yaml`'s own voice declares
`hslider("freq [unit:Hz] [scale:log]", 110, 40, 880, 0.01)`, which is fine, but a `[scale:log]` gain
starting at 0 would otherwise throw out of a control group built from a user's DSP.

Also worth deleting after this: `FaustControl.fromNormalised` may become redundant — check whether
anything but the docs page still calls it.

### 2. `button` → the momentary button

`FaustBox.kt:219` currently renders Faust's momentary `button` as a latching checkbox labelled
`(hold)`. Replace the whole branch:

```kotlin
control.isMomentary -> button(control.displayLabel) { down ->
    launch.set(control, if (down) 1.0 else 0.0)
}
```

The `(hold)` suffix goes with it, and so does the caveat in `faustControls`' KDoc. `FaustLaunch.press`
(`FaustBox.kt:163`) stays — it is the *code* path for triggering a gate and is still the right API for
that; it is just no longer the only way.

### 3. `hbargraph`/`vbargraph` → meters

`launch.meters` (`FaustBox.kt:134`) and `launch.meter(label)` (`FaustBox.kt:150`) already exist and are
already the pull API a `source` lambda wants. Add a loop after the `for (control in launch.controls)`
loop inside the same `controlGroup(channel)` block:

```kotlin
for (readout in launch.meters) {
    meter(
        readout.displayLabel,
        min = readout.min,
        max = readout.max,
        initialValue = readout.init,
        law = if (readout.scale == FaustScale.LOG && readout.min > 0.0) ControlLaw.LOGARITHMIC
              else ControlLaw.LINEAR,
        source = { launch.meter(readout.label).toDouble() }
    )
}
```

`launch.meter` returns `Float`, hence the `.toDouble()`. The sampler is retired when the box reruns
without the meter, so a stopped `FaustLaunch` stops being polled — but check that `launch.meter` on a
**closed** launch returns 0 rather than throwing: if it throws, the sampler unregisters itself and logs
once, which is the desired behaviour but should be a deliberate choice rather than a discovery.

With those three edits, all three bullets at `faust.md:102`, `:103` and `:114` become statements about
what works, and the workaround sentence naming `FaustLaunch.press(label, holdMs)` as *the* momentary
path can go.
