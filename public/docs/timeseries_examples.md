# Timeseries examples

*Every public declaration used below is listed, with its KDoc, in the codebase reference: [`timeseries.kt`](reference/math.md#timeseries-kt). Start from [what's already in the box](reference/index.md).*

Signal processing needs pictures. A filter can reveal structure or erase the detail you cared about. An event detector can catch a gesture or promote a random spike. These examples keep the raw stream and the processed stream in the same frame.

## Filter chains as layered curves

Stacking three filters is easy; seeing whether the stack helped is not. What you want from a smoothing chain is less junk and the same gesture, and those two are traded against each other.

```diffstatic_runner {data-lang=kotlin screenshot="assets/timeseries_examples/filter_chains.png" wait="1.5" capture="box" open-outputs="true" padding="20"}
:::static
val raw = mutableListOf<Float>()
val smooth = mutableListOf<Float>()
val filtered = mutableListOf<Float>()

val ema = EMAFilter(alpha = 0.08f)
val oneEuro = OneEuroFilter(minCutoff = 1.0f, beta = 0.02f)
val band = BandPassFilter(frequency = 5f, sampleRate = 120f, q = 0.8f)

for (i in 0 until 420) {
    val t = i / 120f
    val signal = (
        sin(2.0 * PI * 1.2 * t).toFloat() * 0.7f +
        sin(2.0 * PI * 4.7 * t + 0.3).toFloat() * 0.22f +
        (-0.12f + random().toFloat() * 0.24f)
    )
    raw += signal
    val s = oneEuro(ema(signal))
    smooth += s
    filtered += band(s)
}

// One call puts all three streams on one axis — raw, the smoothed chain, and the band-passed
// component each get their own trace (dark theme + cyan/orange palette applied).
showSignals(
    "raw" to raw,
    "EMA + One-Euro (smoothed)" to smooth,
    "band-pass (5 Hz)" to filtered,
    channel = "main",
    xTitle = "sample",
)
```

One `showSignals` call puts all three streams on one axis. Watch the chain order: `band` is applied to `s`, the output of `ema` and `oneEuro`, not to `signal`, so the band-pass sees a stream the two smoothers have already been through.

![Three traces over 420 samples: a jagged blue raw signal swinging between +1 and -1, an orange smoothed trace attenuated to roughly a fifth of it and lagging behind, and a green band-pass trace that stays almost flat at zero](assets/timeseries_examples/filter_chains.png)

That order is why the band-pass trace sits nearly flat: most of the 4.7 Hz content it is tuned for was taken out upstream. The smoothed trace has a catch of its own. `OneEuroFilter` derives its `dt` from `System.currentTimeMillis()` between calls, so a `for` loop over a stored array hands it about a millisecond per sample and it smooths far harder than its 1 Hz `minCutoff` implies; the attenuation and the lag visible here are that, not a missing fundamental. Its cutoffs are in Hz and mean what they say when the samples really do arrive at 120 Hz.

## Distribution-aware event flags

Event detectors answer a different question from the filters above: did something noteworthy happen? Both of these fit a distribution to the last `windowSize` samples and fire when the newest one lands in a tail, so nothing fires at all until the window has filled: 64 samples of the 360 here.

```diffstatic_runner {data-lang=kotlin screenshot="assets/timeseries_examples/distribution_events.png" wait="1.5" capture="box" open-outputs="true" padding="20"}
:::static
val gammaEvents = GammaEventDetector(windowSize = 64, tailProbabilityThreshold = 0.03f, influence = 0.15f)
val betaEvents = BetaEventDetector(windowSize = 64, supportMin = -1f, supportMax = 1f, lowerTailThreshold = 0.04f, upperTailThreshold = 0.04f)

val raw = mutableListOf<Float>()
val eg = mutableListOf<Float>()
val eb = mutableListOf<Float>()

for (i in 0 until 360) {
    val t = i / 120f
    var x = (sin(2.0 * PI * 1.1 * t) * 0.55 + (-0.08 + random() * 0.16)).toFloat()
    if (i % 83 == 0) x += 0.95f
    if (i % 127 == 0) x -= 0.8f
    raw += x
    eg += gammaEvents(kotlin.math.abs(x))
    eb += betaEvents(x)
}

// The raw stream and both detectors' verdicts on one axis — the event traces flag their tail
// crossings (beta fires either way, ±1) over the raw motion, so which departures triggered is clear.
showSignals(
    "raw" to raw,
    "gamma events" to eg,
    "beta events" to eb,
    channel = "main",
    xTitle = "sample",
)
```

The stream gets a `+0.95` spike at every 83rd sample and a `-0.8` dip at every 127th, and both detectors report their verdict on the same axis as the signal: `1f` from the gamma detector on the sample that fired, and from the beta detector `+1f` for an upper-tail event and `-1f` for a lower-tail one.

![Three traces over 360 samples: a blue raw signal riding a slow sine, an orange gamma-event trace that spikes to 1 on single samples, and a green beta-event trace that holds at +1 or -1 across long stretches](assets/timeseries_examples/distribution_events.png)

The gamma detector, fed `abs(x)`, marks single samples. The beta detector holds `+1` and `-1` across whole stretches instead, and its window is why: 64 samples is shorter than the 109 the 1.1 Hz sine takes to come round at 120 samples a second, so every fit is a fit over a trend and the trend's own continuation keeps landing in a tail. Give a distribution fit at least one full cycle of any periodic component, or detrend before the detector sees it.

For inline diagnostics of similar live values, continue with [Visualizers](visualizers.md). When the value you care about is *hidden* behind the noise rather than merely buried in it, a state you can only measure indirectly, reach for [State estimation](state_estimation_examples.md).

These filters all work in the time domain. When the question is which *frequencies* a signal is made of (brightness, timbre, a chirp's trajectory), cross over to the frequency-domain companion: [Spectral examples](spectral_examples.md) (FFT, STFT / spectrograms, and streaming spectral features that compose with the same `then` chain).
