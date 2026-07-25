# DoughDSP: hear the primitives

The [`DoughDsp`](https://codeberg.org/uzu/dough) port gives Field a handful of low-level
DSP building blocks — a plate reverb, an LFO-swept phaser, a resonant lowpass, a click-free
envelope, a soft clipper, and two deliberately-aliasing oscillators. A picture of a filter
curve only gets you so far; the honest way to know what one of these *does* is to run a real
signal through it and listen.

Every plot and every audio clip on this page is produced by actually running the primitive on
the CPU — no hand-drawn curves, no pre-baked recordings. The waveforms are block-rendered
offline (exactly like the audio engine renders them, minus the sound card) and written to a
16-bit PCM WAV at docs-build time by
[`DoughDspSoundDocs`](https://github.com/){: .external }; the plots are `showSignals` Plotly
figures of the same run. Rebuild the page and the sound regenerates from the code.

Each section shows a plot (what the signal *looks* like), a short player (what it *sounds*
like), and, in essence, the graph that made it.

---

## PlateReverb — a pluck and its tail

A single 120 ms pluck at 330 Hz is fed into the Dattorro plate (`decay = 0.85`, `mix = 0.5`)
and the stereo output is folded to mono. The dry note is gone almost immediately; what you hear
after it is entirely the plate ringing down. The envelope plot shows the same decay: a sharp
onset, then a long smooth tail.

```diffstatic_runner {data-lang=kotlin screenshot="assets/dough_dsp_sound/plate-reverb.png" wait="1.5" capture="box" open-outputs="true" name="dough-sound-plate"}
:::static
plotDoughSound("plate-reverb")
```

![Abs-amplitude envelope of a pluck through PlateReverb: a sharp onset then a long decaying tail](assets/dough_dsp_sound/plate-reverb.png)

```diffstatic_runner {data-lang=kotlin audio="assets/dough_dsp_sound/plate-reverb.wav" audio-spec="plate-reverb" embed=true hidden=true name="dough-sound-plate-audio"}
// offline-rendered by DoughDspSoundDocs — listen below
```

In essence:

```kotlin
val pluck = SineOsc(Param(330f)) * decayEnvelope(0.045f, gatedTo = 0.12f)
val verb  = PlateReverb(source = pluck, decay = Param(0.85f), mix = Param(0.5f))
```

---

## Phaser — notches sweeping across a sawtooth

A sustained 220 Hz sawtooth through the LFO-swept cascaded notches (`rate = 0.5 Hz`,
`depth = 0.7`). As the notches slide across the sawtooth's harmonics you get the classic hollow,
watery whoosh. The plot traces the short-time RMS: it breathes in time with the LFO.

```diffstatic_runner {data-lang=kotlin screenshot="assets/dough_dsp_sound/phaser.png" wait="1.5" capture="box" open-outputs="true" name="dough-sound-phaser"}
:::static
plotDoughSound("phaser")
```

![Short-time RMS of a sawtooth through the phaser, modulating in time with the LFO](assets/dough_dsp_sound/phaser.png)

```diffstatic_runner {data-lang=kotlin audio="assets/dough_dsp_sound/phaser.wav" audio-spec="phaser" embed=true hidden=true name="dough-sound-phaser-audio"}
// offline-rendered by DoughDspSoundDocs — listen below
```

In essence:

```kotlin
val saw = ZawOsc(Param(220f)) * 0.4f
val out = saw.through(Phaser(rate = Param(0.5f), depth = Param(0.7f), center = Param(700f), sweep = Param(1400f)))
```

---

## ChamberlinLowpass — a resonant cutoff sweep

A raw 110 Hz sawtooth through the superdough 2-pole lowpass with `resonance = 0.6`, while the
cutoff sweeps 150 Hz → 6 kHz. You hear the filter open up, the resonance whistling along the top
of the sweep. The plot tracks a cheap brightness proxy (high-frequency energy) climbing as the
cutoff rises.

```diffstatic_runner {data-lang=kotlin screenshot="assets/dough_dsp_sound/chamberlin-lowpass.png" wait="1.5" capture="box" open-outputs="true" name="dough-sound-chamberlin"}
:::static
plotDoughSound("chamberlin-lowpass")
```

![Brightness (high-frequency energy) rising over time as the lowpass cutoff sweeps up](assets/dough_dsp_sound/chamberlin-lowpass.png)

```diffstatic_runner {data-lang=kotlin audio="assets/dough_dsp_sound/chamberlin-lowpass.wav" audio-spec="chamberlin-lowpass" embed=true hidden=true name="dough-sound-chamberlin-audio"}
// offline-rendered by DoughDspSoundDocs — listen below
```

In essence:

```kotlin
val cutoff = Param(150f).also { it.supplier = { 150f + 5850f * (time / 3f).coerceIn(0f, 1f) } }
val out    = ZawOsc(Param(110f)).through(ChamberlinLowpass(cutoff, Param(0.6f)))
```

---

## CurvedADSR — a gated three-note rhythm

A 220 Hz sine multiplied by the click-free curved envelope, gated on and off three times. Each
note swells in and fades out with real silence between — no clicks at the retrigger, which is the
whole point of the curved variant. The envelope plot shows three clean humps.

```diffstatic_runner {data-lang=kotlin screenshot="assets/dough_dsp_sound/curved-adsr.png" wait="1.5" capture="box" open-outputs="true" name="dough-sound-adsr"}
:::static
plotDoughSound("curved-adsr")
```

![Amplitude envelope showing three notes with silent gaps between them](assets/dough_dsp_sound/curved-adsr.png)

```diffstatic_runner {data-lang=kotlin audio="assets/dough_dsp_sound/curved-adsr.wav" audio-spec="curved-adsr" embed=true hidden=true name="dough-sound-adsr-audio"}
// offline-rendered by DoughDspSoundDocs — listen below
```

In essence:

```kotlin
val gate  = Gate()
val voice = SineOsc(Param(220f)) * CurvedADSR(gate, Param(0.02f), Param(0.08f), Param(0.6f), Param(0.15f))
// gate.open()/close() on a rhythm: three notes with gaps
```

---

## SoftDistort — from clean to crunchy

A 220 Hz sine through dough's `expm1` soft clipper while the drive sweeps 0.2 → 7. It starts as
a pure tone and grows a buzzy edge as the drive climbs and the clipper starts folding the peaks.
The plot overlays two two-cycle windows: an early clean sine and a late, flat-topped, harmonically
rich one.

```diffstatic_runner {data-lang=kotlin screenshot="assets/dough_dsp_sound/soft-distort.png" wait="1.5" capture="box" open-outputs="true" name="dough-sound-distort"}
:::static
plotDoughSound("soft-distort")
```

![Two waveform windows: a clean sine early and a flat-topped distorted one late in the drive sweep](assets/dough_dsp_sound/soft-distort.png)

```diffstatic_runner {data-lang=kotlin audio="assets/dough_dsp_sound/soft-distort.wav" audio-spec="soft-distort" embed=true hidden=true name="dough-sound-distort-audio"}
// offline-rendered by DoughDspSoundDocs — listen below
```

In essence:

```kotlin
val drive = Param(0.2f).also { it.supplier = { 0.2f + 6.8f * (time / 3f).coerceIn(0f, 1f) } }
val out   = SineOsc(Param(220f)).through(SoftDistort(drive, Param(0.8f)))
```

---

## ZawOsc / PulzeOsc — raw, aliasing tones

The two naive oscillators, deliberately un-bandlimited: one second of a 200 Hz sawtooth
(`ZawOsc`) then one second of a 200 Hz narrow pulse (`PulzeOsc`, duty 0.3). The raw ramps buzz
with aliasing — that grit is the character, not a bug. The plot shows two periods of each: a clean
descending ramp and a narrow rectangular pulse.

```diffstatic_runner {data-lang=kotlin screenshot="assets/dough_dsp_sound/zaw-pulze.png" wait="1.5" capture="box" open-outputs="true" name="dough-sound-oscillators"}
:::static
plotDoughSound("zaw-pulze")
```

![Two periods of a raw sawtooth and a narrow pulse waveform](assets/dough_dsp_sound/zaw-pulze.png)

```diffstatic_runner {data-lang=kotlin audio="assets/dough_dsp_sound/zaw-pulze.wav" audio-spec="zaw-pulze" embed=true hidden=true name="dough-sound-oscillators-audio"}
// offline-rendered by DoughDspSoundDocs — listen below
```

In essence:

```kotlin
val saw   = ZawOsc(Param(200f)) * 0.7f          // one second, then…
val pulse = PulzeOsc(Param(200f), Param(0.3f)) * 0.7f
```
