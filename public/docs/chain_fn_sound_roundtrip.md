# A function, played and read back

Take one `Functions.Fn`, make it a sound, analyse the sound, and put what the analysis found next to
the function it came from. Four parts of Field that do not know about each other.
`afield.stdlib.Functions` builds the function, and `FnInterval` certifies its range on any interval.
A Dough graph plays it, rendered offline to a 16-bit `.wav`. `PatOnsetDetection` and
`PatPitchDetection` read the file back. The time-axis viewer draws the function, its certified band,
what came back, and the audio, on one timeline.

The question is how much of the function survives, and where the loss comes from. Most of the
answer is not noise. It is two delays, both one line of code each, and both exact enough to measure
to the sample.

## The function, and two ways to hear it

[`FnSoundDocs.contour`](reference/synthesis.md) is three seconds of a slow curve: a resting level, a
broad swell, a step up to a plateau that wobbles at 6 Hz, and one bump with a 3 ms sigma, narrower
than either analysis window. It is built only from ops that have a formula, so every interval of it
has a certified range.

```diffstatic {data-lang=kotlin}
val f = with(Functions) {
    val d = Functions.Domain(0.0, 3.0)
    c(0.2, d) + gaussian(0.8, 0.2, 0.5, d) +
        smoothStep(1.4, 2.0, d) * (c(0.3, d) + sin(6.0, amplitude = 0.08)) +
        gaussian(2.6, 0.003, 0.25, d)
}
```

It is played two ways. As **loudness**, it is the amplitude of a sine at 375 Hz, a frequency chosen
so one period is exactly 128 samples at 48 kHz. As **pitch**, it is the frequency of a sine at
constant level, `220 · 2^f` Hz, so the curve spans about half an octave. Both renders are
written to disk without normalisation, so the amplitude in the file is the amplitude the function
asked for. Both analyses read the file, not the buffer that was written to it.

Here is the loudness render:

```diffstatic_runner {data-lang=kotlin audio="assets/chain_fn_sound_roundtrip/loudness.wav" audio-spec="loudness" audio-renderer="afield.four.synth.FnSoundDocs" embed=true hidden=true name="chain-fn-loudness-audio"}
// Offline-rendered by FnSoundDocs.renderWav("loudness").
```

And the pitch render, the same curve as a glide:

```diffstatic_runner {data-lang=kotlin audio="assets/chain_fn_sound_roundtrip/pitch.wav" audio-spec="pitch" audio-renderer="afield.four.synth.FnSoundDocs" embed=true hidden=true name="chain-fn-pitch-audio"}
// Offline-rendered by FnSoundDocs.renderWav("pitch").
```

### The bridge this page had to add

Until this page there was one way to bring an `Fn` into a Dough graph: a `Param` whose supplier
reads it, `Param { f(time.toDouble()).toFloat() }`. A supplier is evaluated once per audio block,
at the block's start, and `Param` ramps from the previous block's value to this one across the
block. So at time `t` the audio hears, to first order, the function at `t` minus one block.

`FnSignal`, reached as `f.toSignal()`, is the per-sample alternative: sample `i` of a block is
`f(time + i / sampleRate)`. It is one small class in `FnSignal.kt`. Both routes are measured below, and
the clips above use `toSignal()`.

```diffstatic {data-lang=kotlin}
val perSample = SineOsc(Param(375f)) * f.scaleY(0.8).toSignal()
val perBlock  = SineOsc(Param(375f)) * Param { 0.8f * f(time.toDouble()).toFloat() }
```

## What came back

The loudness is recovered by `patEnergyEnvelope`, an RMS per hop, with a window of two whole carrier
periods. Over a whole number of periods a steady sine's RMS is exactly its amplitude over √2, so the
window adds no ripple of its own. The pitch is recovered by `patF0Frames`, the YIN estimator,
told the register it is listening to. Every analysis frame is one member of the population.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_fn_sound_roundtrip/roundtrip.png" wait="12" capture="box" open-outputs="true" w="1180" h="900" padding="18" timeout="240" name="chain-fn-roundtrip" embed=true alt="A four-lane time-axis viewer over three seconds. The top two lanes draw the function in white, a slow swell, a step up to a wobbling plateau and one narrow spike, with its certified band behind it, too tight to separate from the line at this zoom, and the curves recovered from the loudness and from the pitch of the rendered sound drawn dashed on top of it and hidden by it. The two lower lanes are the recovery errors: flat near zero except for a spike at the narrow bump, with a faint ripple along the pitch lane's plateau. Below, an audio player showing the loudness render's waveform, and a table of frame counts, errors, lags and certified-band containment for both encodings"}
:::static
import afield.four.synth.FnSoundDocs
import afield.four.synth.FnSoundDocs.Encoding

// Each roundTrip renders through FnSignal, writes a 16-bit .wav with no normalisation, reads that
// file back, and analyses what it read. `inside` is checked against the proven bounds of
// certifiedRange over the samples each frame read, not against a sampled curve.
val loud = FnSoundDocs.roundTrip(Encoding.LOUDNESS)
val pitch = FnSoundDocs.roundTrip(Encoding.PITCH)
val f = loud.fn

:::fold the viewer, the audio, and the numbers
val sync = "chain-fn-roundtrip"
val viewer = SignalViewer(listOf(
    certifiedSignalChannel("f, and its loudness read back", f, 0.1, 0.95).copy(comparison = loud::recoveredAt),
    certifiedSignalChannel("f, and its pitch read back", f, 0.1, 0.95).copy(comparison = pitch::recoveredAt),
    SignalChannel("loudness − f", loud::errorAt, -0.1, 0.1),
    SignalChannel("pitch − f", pitch::errorAt, -0.1, 0.1),
), 0.0, FnSoundDocs.SECONDS, sampleStep = 1.0 / 4000, gaps = true)
displaySignal(viewer, "viewer", SignalViewerOptions(width = 1100, laneHeight = 120,
    label = "one function, played two ways and read back", comparisonLabel = "read back from the .wav",
    syncGroup = sync, viewKey = "chain-fn-roundtrip:viewer"))
displayAudioBytes(loud.wav.readBytes(), channel = "audio", durationSeconds = FnSoundDocs.SECONDS,
    label = "the loudness render, as analysed", options = AudioVisualizerOptions(width = 1100, syncGroup = sync))

fun row(label: String, t: FnSoundDocs.RoundTrip) =
    "| $label | ${t.measured.size} of ${t.frames.size} | ${"%.5f".format(t.rmsError)} | ${"%.4f".format(t.maxAbsError)} at ${"%.3f".format(t.maxErrorAtSeconds)} s | " +
        "${t.insideCount} of ${t.measured.size}, worst ${"%.1e".format(t.worstOutside)} outside | ${"%.2f".format(t.blip.survival)} |"
markdown("readout", """
| read back from | frames | RMS error | worst error | inside the certified range | of the bump's height |
|---|---|---|---|---|---|
${row("loudness (RMS)", loud)}
${row("pitch (YIN)", pitch)}
""", width = 1080)
:::endfold
```

Over the slow parts both recovered curves sit on the function, close enough to hide under it at this
zoom, and the error lanes are nearly flat. The worst error
of each lies at the narrow bump, and the last column says how much of that bump's height each
encoding kept. Neither window is much narrower than the bump, and a window can only report an
average over what it read.

The certified band is the honest test of that. A windowed measurement cannot be expected to land on
the function's value at one instant. It *can* be expected to land inside the range the function
covers over the samples the window read, and `certifiedRange` bounds that range with a proof rather
than a sample grid. Nearly every loudness frame lands inside. The few that do not miss by less than
one 16-bit step. Every pitch frame lands inside, which says less than it seems to: a YIN frame reads
nearly three times as many samples as an RMS frame here, and a wider window has a wider range to land in.

## Two delays, measured to the sample

The harness also asks what delay best aligns each recovered curve with the function, searching two
blocks either way at one-sample resolution. Four trips: two encodings, each played through both
bridges.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_fn_sound_roundtrip/delays.png" wait="12" capture="box" open-outputs="true" w="1180" h="640" padding="18" timeout="240" name="chain-fn-delays" embed=true alt="A two-lane time-axis viewer zoomed to thirty milliseconds around the narrow bump. In the top lane the curve read back from the per-sample render sits on the function; in the lower lane the curve read back from the per-block Param render is visibly shifted to the right of it. Below, a table of the four trips giving each one's RMS error, the delay that best aligns it in samples, the RMS error at that delay, and, for pitch, the delay measured from the centre of the samples YIN compared"}
:::static
import afield.four.synth.FnSoundDocs
import afield.four.synth.FnSoundDocs.Encoding
import afield.four.synth.FnSoundDocs.Route

// Two encodings through two bridges. bestLagSamples reads f at each frame's reported centre minus
// a lag; bestLagFromSupportSamples does the same from the centre of the samples the frame compared.
val trips = Encoding.values().flatMap { e -> Route.values().map { r -> FnSoundDocs.roundTrip(e, r) } }
fun trip(e: Encoding, r: Route) = trips.single { it.encoding == e && it.route == r }

:::fold the bump up close, and the four delays
val f = trips.first().fn
val viewer = SignalViewer(listOf(
    SignalChannel("loudness through toSignal()", f::eval, 0.35, 0.8, comparison = trip(Encoding.LOUDNESS, Route.PER_SAMPLE)::recoveredAt),
    SignalChannel("loudness through Param { }", f::eval, 0.35, 0.8, comparison = trip(Encoding.LOUDNESS, Route.PER_BLOCK)::recoveredAt),
), 0.0, FnSoundDocs.SECONDS, sampleStep = 1.0 / 48000, gaps = true)
displaySignal(viewer, "viewer", SignalViewerOptions(width = 1100, laneHeight = 150,
    label = "the bump at 2.6 s, 30 ms wide", comparisonLabel = "read back from the .wav",
    initialStart = FnSoundDocs.BLIP_AT - 0.015, initialDuration = 0.03, viewKey = "chain-fn-roundtrip:delays"))

val rows = trips.joinToString("\n") { t ->
    "| ${t.encoding.name.lowercase()} | ${if (t.route == Route.PER_SAMPLE) "`toSignal()`" else "`Param { }`"} | " +
        "${"%.5f".format(t.rmsError)} | ${t.bestLagSamples} | ${"%.5f".format(t.rmsErrorAtBestLag)} | " +
        (if (t.encoding == Encoding.PITCH) "${t.bestLagFromSupportSamples}" else "—") + " |"
}
markdown("delays", """
One audio block is ${FnSoundDocs.BLOCK} samples, ${"%.2f".format(1000.0 * FnSoundDocs.BLOCK / FnSoundDocs.SR)} ms.

| encoding | bridge | RMS error | best delay, samples | RMS error at that delay | delay from the compared samples |
|---|---|---|---|---|---|
$rows
""", width = 1080)
:::endfold
```

The first delay is the bridge. Through `toSignal()` the loudness comes back with no delay at all.
Through `Param { }` it comes back exactly one block late, and removing that block removes most of the
error. That is the ramp described above, and it is why a sharp feature driven through a supplier
arrives a block after it was asked for. `Param` is built for control values set once a block, and
it does that job. It is the wrong bridge for a function whose shape you mean to hear.

The second delay is in the analysis, and it corrects a claim. `PatF0Frame.centerSample` is the
middle of the samples a YIN frame spans, `window + tauMax`, and its KDoc said that was where the
estimate is true. But the difference function at the lag YIN chose, `tau`, compares only
`window + tau` of them. So for any pitch above the lowest the detector was told about, the estimate
belongs to an instant `(tauMax − tau) / 2` samples earlier than the one it is reported at. Re-centre
each frame on the samples it compared and the pitch delay through `toSignal()` falls to within a
sample or two of zero. Through `Param { }`, what is left is one block again. The two delays add.

!!! note "Reading a pitch track against time"

    `patF0Frames` attributes each estimate a little late, by an amount that depends on the pitch:
    nothing at `minHz`, and up to half of `tauMax` well above it. Telling the detector the true
    register, which its KDoc already asks for, keeps `tauMax` small and the bias with it. When
    timing matters to the sample, centre a frame at
    `startSample + (spanSamples − tauMax + sampleRate / hz) / 2`, the way `FnSoundDocs` does.

## Where each piece is documented

* [Talking a Function into Existence](function_conversation.md) and the `Functions` pages for the
  `Fn` algebra, and `dev_docs/FN_INTERVAL.md` for the certified enclosures: what they contain, what
  they refuse, and why a bound a function attains cannot be proven exactly by bisection.
* [Visualizers](visualizers.md#one-viewer-for-functions-sounds-and-signals) for the time-axis
  viewer, its `syncGroup` timeline, and the certified band. `dev_docs/SIGNAL_VIEWER.md` is the
  component's own record.
* [DoughDSP: hear the primitives](dough_dsp_sound.md) for the signal graph and `Param`, and
  [Render Field music, then analyse it](audio_render_analysis.md) for the same render-then-analyse
  loop through the Field Audio service, whose pYIN and Basic Pitch this page does not use.
* [Making a Sound, Every Path There Is](sound_paths.md) for why every audio figure in these docs
  comes out of an offline render.

Every number on this page comes out of `FnSoundDocs`, the same code the figures call. The
`synth.fn.sound.roundtrip` FIELD_TEST runs all four trips and checks the delays. Replay it with
`uv run --project . --group backdoor python tools/run_field_tests.py --id synth.fn.sound.roundtrip`.
It writes only to a temp directory. `FnSoundRoundTripTest` holds each printed number to a floor.
