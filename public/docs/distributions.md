# Distributions: values that are probability distributions

`afield.math.distribution` lets a box compute with random variables as if they were numbers. A die
is a value, two dice added together is a value, and "the chance that two of 23 people share a
birthday" is a question you ask of a value. Field answers by sampling, and every answer comes with
its standard error, so you can see how far to trust it.

The idea comes from [NoiseLang](https://manualmeida.dev/articles/noiselang), a small language in
which every value is a distribution. This is the same idea written as Kotlin, plus two things a box
gets for free: the samples draw themselves next to your code as they arrive, and the same
expression can be run on the GPU.

It then goes past NoiseLang in several directions. A distribution can be
[conditioned on what you saw](#conditioning-on-what-you-saw), which turns a model into a posterior,
and a posterior can [learn as data arrives](#learning-as-data-arrives).
It can be [played](#patterns-from-a-distribution), as a pattern or as a
[sound parameter](#distributions-as-sound). And a fitted
[Gaussian process](#gaussian-processes-as-distributions) can be turned into distributions, so you
can ask questions about whole curves. All of it can be
[drawn inside a shader](#distributions-in-a-shader).

```diffstatic {data-lang=kotlin}
:::static
import afield.math.distribution.*

val x = die(6)
val y = die(6)
println(P(x + y lt 10))      // 0.8327 ± 0.0008345 (n=200000)
println(E(5 + x + y))        // 12.01 ± 0.005401 (n=200000)
```

## Building a distribution

Nothing is drawn when you write `die(6) + die(6)`. You get a `Dist`: one node of a small graph that
remembers how it was made. Drawing happens only when you ask a question of it.

The building blocks:

| | |
| --- | --- |
| `Dist.dirac(5)`, or any number in an expression | a constant: all its weight on one value |
| `die(6)`, `uniformInt(1, 365)` | whole numbers, each equally likely |
| `uniform(a, b)`, `normal(mu, sigma)`, `exponential(rate)` | continuous distributions |
| `bernoulli(p)` | 1 with probability `p`, else 0 |
| `poisson(rate)`, `gamma(shape, rate)`, `beta(a, b)` | counts, positive amounts, probabilities |
| `mixture(1 to a, 3 to b)`, `categorical(values, weights)` | pick one of several, by weight |

They combine with `+ - * / %`, with numbers on either side, and with `abs()`, `sqrt()`, `exp()`,
`ln()`, `sin()`, `cos()`, `pow(e)`, `min(o)`, `max(o)` and `squared()`. Comparisons are written
`lt le gt ge eq ne`, because Kotlin's `<` must return a plain `Boolean`. A comparison is itself a
distribution that is 1 or 0, which is what makes `P(x + y lt 10)` work: a probability is the mean
of a 0–1 variable. `and`, `or`, `!` and `where` combine those.

A parameter can itself be a distribution: `normal(mu, 1)` with `mu = normal(0, 10)` draws a new mean
for every sample. That is how you write a model whose parameters you want to learn from data
([Conditioning on what you saw](#conditioning-on-what-you-saw)).

### A name is one variable

A `val` names one variable, not a recipe for drawing a new one. Every sample computes each node
once, so wherever `x` appears it has the same value:

```diffstatic {data-lang=kotlin}
:::static
val x = normal(3, 2)
E(x - x)        // exactly 0, with SE 0
E(x + x)        // the same as E(2 * x)
```

For a second, independent variable with the same distribution, ask for a copy: `x.iid()`. For a
vector of them, `draws(23, uniformInt(1, 365))`. `Var(x - x.iid())` is 8 here, twice the variance
of `x`, where `Var(x - x)` is 0.

## Asking questions

Four questions sample the graph and return an `Estimate`, which prints as `value ± standard error
(n=samples)`:

- `P(c)`: the probability that `c` is true (non-zero).
- `E(x)`: the mean of `x`.
- `Var(x)`: its variance.
- `quantile(x, q)`: the value below which a fraction `q` of `x` falls.

Each takes `n = ` (200 000 by default) and `seed = `. Read `0.507 ± 0.002` as "the true value is
within about two of those of 0.507, nineteen times in twenty". `estimate.within(exact)` is that
test, and `estimate.zFrom(exact)` is how many standard errors away an estimate is.

The questions from the NoiseLang article, with the answers you can check by hand:

<!--
FIELD_TEST
id: docs.distributions.queries
runner: backdoor-box
tags: [docs, distributions, statistics]
timeoutSec: 120
expect:
  outputContains:
    - "P(shared birthday)"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/distributions-queries.png" alt="seven estimates printed beside their exact values, each within about two standard errors" wait="1.0" capture="editor" outputs="inline" padding="10" timeout="120" embed=true name="distributions-queries"}
:::static
import afield.math.distribution.*

fun show(label: String, e: Estimate, exact: Double) =
    println("%-24s %-28s exact %.5f   z = %+.2f".format(label, e, exact, e.zFrom(exact)))

val x = die(6)
val y = die(6)
show("E(5 + X + Y)", E(5 + x + y), 12.0)
show("P(X + Y < 10)", P(x + y lt 10), 30.0 / 36)
show("Var(X - X')", Var(x - x.iid()), 35.0 / 6)

val birthday = draws(23, uniformInt(1, 365))
val noShared = (0 until 23).fold(1.0) { p, k -> p * (365.0 - k) / 365.0 }
show("P(shared birthday)", P(hasDuplicates(birthday)), 1 - noShared)

val u = uniform(-1, 1)
val v = uniform(-1, 1)
show("4 P(u² + v² < 1)", P(u * u + v * v lt 1) * 4, Math.PI)
show("median of exp(1)", quantile(exponential(1), 0.5), kotlin.math.ln(2.0))
```

`hasDuplicates(list)` is 1 when any two of its variables are equal on a sample; `distinctCount`
counts the different values. With 23 birthdays the answer is about 0.507: better than even.

## Watching the samples arrive

`ᛟ.distribution(d)` is an inline visualizer, like `ᛟ.histogram`
([Inline editor visualizers](visualizers.md#inline-editor-visualizers)). Each call draws another
batch of `d` and redraws, under that line of code:

- the samples so far, as bars, with one bar per value when there are few values (a die gets six
  bars, not the bins a rule for continuous data would cut through them);
- the exact probabilities as orange ticks, or the exact density as an orange curve, when Field can
  work them out; otherwise a dashed kernel estimate;
- the mean, dashed white, inside a band one standard error wide;
- underneath, the running mean against the number of samples (on a log scale), inside its ±2 SE
  band. Pass `exact = ` to draw the true value across it.

Put it in a `loop { }` and **launch** the box to watch the estimate settle. Here a constant, a die,
and the constant plus two dice: the spike, the flat die, and the triangle that two dice make, with
the mean of the last settling on 12.

<!--
FIELD_TEST
id: docs.distributions.spike-die-triangle
runner: backdoor-box
tags: [docs, distributions, inline, visualizers]
timeoutSec: 120
execution: launch
waitSec: 4.0
expect:
  screenshot:
    kind: editor
    padding: 10
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/distributions-spike-die-triangle.png" alt="three inline distribution views: a single bar at 5, six equal bars for a die, and an eleven-bar triangle from 7 to 17 under orange exact-probability ticks, its running mean settling on the dashed line at 12" wait="4.0" capture="editor" outputs="inline" padding="10" execution="launch" timeout="120" embed=true name="distributions-spike-die-triangle"}
:::static
import afield.math.distribution.*

val a = Dist.dirac(5)
val d1 = die(6)
val d2 = die(6)
val s = a + d1 + d2

loop {
    __m("spike").distribution(a, batch = 500, title = "A = 5, a Dirac spike")
    __m("die").distribution(d1, batch = 500, title = "D1, a fair die")
    __m("sum").distribution(s, batch = 500, title = "S = A + D1 + D2", exact = 12.0)
    frame()
}
```

The samples keep accumulating for as long as the expression stays the same. Edit it and re-run, and
the view starts again.

Continuous variables are drawn as a histogram under their density. The sum of two independent
normals is again normal, so Field draws its exact density; a squared normal has no closed form
here, so it gets a kernel estimate, dashed:

<!--
FIELD_TEST
id: docs.distributions.density
runner: backdoor-box
tags: [docs, distributions, inline, visualizers]
timeoutSec: 120
execution: launch
waitSec: 4.0
expect:
  screenshot:
    kind: editor
    padding: 10
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/distributions-density.png" alt="a bell-shaped histogram under an orange exact density curve, and a sharply skewed histogram under a dashed kernel estimate" wait="4.0" capture="editor" outputs="inline" padding="10" execution="launch" timeout="120" embed=true name="distributions-density"}
:::static
import afield.math.distribution.*

val sum = normal(0, 1) + normal(2, 0.5)
val squared = normal(0, 1).squared()

loop {
    __m("sum").distribution(sum, batch = 400, title = "N(0,1) + N(2,0.5)", exact = 2.0)
    __m("sq").distribution(squared, batch = 400, title = "Z², no closed form here", exact = 1.0)
    frame()
}
```

Exact probabilities come from two places. Independent parts (dice, coins, constants, and `+ - *`
and comparisons between variables that share no random part) are convolved. Anything else built
from discrete leaves, such as `x + x`, `x * x lt 10` or a `categorical`, is enumerated: Field
visits every joint value of the dice and coins, up to a million of them. `exactPmf(d)` and
`exactPdf(d)` return the answer, or `null` when the graph is outside what they can do (a continuous
leaf, or too many joint values, as with 23 birthdays).

For your own plots, `Progressive(d)` is the accumulator the visualizer uses: `draw(n)` adds a batch,
`estimate` is the running answer, `trace` the history of (n, mean, SE), and `kept()` the samples.
`distributionSvg(progressive)` draws it.

## Conditioning on what you saw

`x given c` is `x` restricted to the samples where `c` is true. If two dice show 10 or more between
them, how likely is it that the first one is a 6?

```diffstatic {data-lang=kotlin}
:::static
import afield.math.distribution.*

val x = die(6)
val y = die(6)
P((x eq 6) given (x + y ge 10))       // 0.5042 ± 0.002734 (n=200000, ess=33455)
exactPmf(x given (x + y ge 10))       // {4.0=0.1666…, 5.0=0.3333…, 6.0=0.5}
```

Six of the 36 rolls make 10 or more, and three of those six start with a 6, so the answer is 1/2.
Field gets it by drawing 200 000 pairs and keeping only the ones where the condition holds.
`ess` is how many samples the answer rests on, here about one in six. A rare condition leaves few,
and the standard error grows to say so. A condition that no sample meets throws
`EvidenceImpossibleException` instead of answering NaN.

A condition is the simplest kind of evidence. The other kind is data. `observed(model, data)` says
"these numbers were drawn from `model`", and `model` may have random parameters. Here a coin of
unknown bias, every bias equally likely to begin with, comes up heads seven times in ten:

```diffstatic {data-lang=kotlin}
:::static
import afield.math.distribution.*

val p = uniform(0, 1)
val coin = p given observed(bernoulli(p), 1, 1, 0, 1, 1, 1, 0, 1, 1, 0)
E(coin)                         // 0.6674 ± 0.0003210 (n=200000, ess=93637); exactly 8/12
quantile(coin, 0.05)            // about 0.436: the bias is above this nineteen times in twenty
```

Each sample of `p` is weighed by how likely the seven heads are for that bias, `p⁷(1 − p)³`, and
every question becomes a weighted average. The result is the posterior: what you believe about `p`
after the flips. Here it is Beta(8, 4), so its mean is 8/12.

These families take random parameters, so they can be the `model` in `observed`:

| | |
| --- | --- |
| `normal(mu, sigma)` | a measurement of `mu` with noise `sigma` |
| `bernoulli(p)` | a yes or no with probability `p` |
| `poisson(rate)` | a count of events at a rate |
| `exponential(rate)`, `gamma(shape, rate)` | waiting times, positive amounts |
| `uniform(a, b)` | anywhere between two ends |
| `beta(a, b)`, `die(n)`, `uniformInt(lo, hi)` | with constant parameters only |

A model has to be one of these. `mu + normal(0, 1)` is the same distribution as `normal(mu, 1)`,
but it is arithmetic on a leaf with no density, and `observed` refuses it.

The posterior is a `Dist` like any other. You can add to it, compare it, draw it, or condition it
again. Join two pieces of evidence with `and`. For a score that is not one of the families,
`logLikelihood(d)` uses any variable as a log-likelihood. Any non-negative variable can also be a
weight on its own: `x given x` is a die weighted by its face, so a 6 comes up six times as often as
a 1. `post.prior()` is the same graph with the evidence taken out.

`samples(n)` and `jointSamples` of a posterior draw weighted samples and resample them by weight,
so the arrays they return hold posterior draws, with repeats. `weightedSamples(n)` gives the draws
and their log-weights.

`ᛟ.distribution(post)` draws a posterior the same way it draws anything else, with the bars
weighted by the evidence. The prior is drawn as a dashed outline behind them, so you can see what
the evidence changed. The footer gives the effective sample size. Here the mean of a measurement
with a vague prior, `normal(0, 10)`, after three readings near 3:

<!--
FIELD_TEST
id: docs.distributions.posterior
runner: backdoor-box
tags: [docs, distributions, inline, visualizers, conditioning]
timeoutSec: 120
execution: launch
waitSec: 4.0
expect:
  screenshot:
    kind: editor
    padding: 10
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/distributions-posterior.png" alt="two inline distribution views: the posterior of mu, a bell of bars centred on 3.09 with the wide normal prior a nearly flat dashed line beneath it, and the posterior of a coin's bias peaking near 0.7 above the flat dashed outline of its uniform prior; each footer gives n and the effective sample size" wait="4.0" capture="editor" outputs="inline" padding="10" execution="launch" timeout="120" embed=true name="distributions-posterior"}
:::static
import afield.math.distribution.*

val mu = normal(0, 10)
val reading = mu given observed(normal(mu, 1), 2.9, 3.3, 3.1)

val p = uniform(0, 1)
val coin = p given observed(bernoulli(p), 1, 1, 0, 1, 1, 1, 0, 1, 1, 0)

loop {
    __m("reading").distribution(reading, batch = 2000, title = "mu given three readings", exact = 9.3 / 3.01)
    __m("coin").distribution(coin, batch = 2000, title = "bias given 7 heads in 10", exact = 8.0 / 12)
    frame()
}
```

The view frames the posterior, so the prior on `mu`, spread from about −20 to 20, is a nearly flat
dashed line under it. Most of the prior's samples are far from 3 and weigh almost nothing, so the
effective sample size is under a tenth of the draws. That is the honest count. A tighter prior
keeps more of them.

??? aside "How the weights are summed"
    A query is self-normalised importance sampling: with weights `w`, `E(x)` is `Σwx / Σw`, and its
    standard error is `√(Σw²(x − mean)²) / Σw`. For a 0–1 condition these are exactly the mean and
    standard error of the accepted samples. Weights travel as logs and are scaled by the largest
    seen (`WeightedMoments`), so three hundred readings' worth of small likelihoods neither
    underflow nor lose the samples that matter. `ConditioningTest` checks each posterior here
    against its closed form: Beta for the coin, a normal prior and normal readings, and a gamma
    prior with Poisson counts.

## Geometry from a distribution

A whole drawing can be one random variable. Here a walker turns by an independent normal amount at
each of 40 steps, so its position after every step is a node of one graph. `jointSamples` draws all
those nodes together, one sample per walk, and each sample becomes an `FLine`. The distance from the
start is another node of the same graph, so its distribution and mean come from the same
expression:

<!--
FIELD_TEST
id: docs.distributions.walks
runner: backdoor-box
tags: [docs, distributions, fline]
timeoutSec: 120
expect:
  outputContains:
    - "E(reach)"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/distributions-walks.png" alt="120 pale blue random walks fanning out to the right from a common start" wait="1.5" capture="box" open-outputs="true" w="640" h="380" timeout="120" embed=true name="distributions-walks"}
:::static
import afield.math.distribution.*

val steps = 40
var heading: Dist = Dist.dirac(0)
var x: Dist = Dist.dirac(0)
var y: Dist = Dist.dirac(0)
val xs = ArrayList<Dist>()
val ys = ArrayList<Dist>()
for (k in 0 until steps) {
    heading = heading + normal(0, 0.25)
    x = x + heading.cos() * 4
    y = y + heading.sin() * 4
    xs += x
    ys += y
}
val reach = (x * x + y * y).sqrt()

val walks = 120
val drawn = jointSamples(*(xs + ys).toTypedArray(), n = walks, seed = 7)
val paths = (0 until walks).map { w ->
    fline {
        moveTo(200, 150)
        for (k in 0 until steps) lineTo(200 + drawn[k][w], 150 - drawn[steps + k][w])
        color = vec(0.0, 0.67, 1.0, 0.35)
    }
}
lines(paths, channel = "walks", w = 400.0, h = 300.0)
println("E(reach) = ${E(reach, n = 50_000)}   (a straight line would be ${steps * 4})")
```

It prints `E(reach) = 130.5 ± 0.1009 (n=50000)`: forty steps of 4 end on average 130 units out,
not 160.

## Patterns from a distribution

`d.pat()` turns a distribution into a pattern for the [pattern language](pattern_language.md). It is
the same kind of signal as `rand()`: ask it about any instant and it answers with a draw, seeded by
that instant's exact position. The same moment always draws the same value, however the pattern is
queried, so a tune drawn from a distribution repeats when you replay it and changes when you change
the distribution.

Here a weighted minor pentatonic, with the root and the fifth most likely, is drawn eight times a
cycle:

<!--
FIELD_TEST
id: docs.distributions.weighted-scale
runner: backdoor-box
tags: [docs, distributions, patterns, audio]
timeoutSec: 120
expect:
  screenshot:
    kind: box
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/distributions-weighted-scale.png" alt="a one-lane piano roll of four cycles of eight notes, most of them on C, G and the C above, a few on E-flat, F and B-flat, above an audio player" wait="3" capture="box" open-outputs="true" w="1000" h="360" padding="18" timeout="120" embed=true name="distributions-weighted-scale"}
:::static
import afield.math.distribution.*

val scale = categorical([0, 3, 5, 7, 10, 12], [4, 1, 2, 3, 1, 2])
val tune = (60 + scale).pat().segment(8).texts()

showPatRollOf("60 + scale" to tune, title = "eight draws a cycle from a weighted pentatonic", cycles = 4, pitched = true)
showPatAudio(tune, cycles = 4, cps = 0.5, voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.05, sustain = 0.7), channel = "audio")
```

```diffstatic_runner {data-lang=kotlin audio="assets/distributions/weighted-scale.wav" audio-spec="weighted-scale" audio-renderer="afield.math.distribution.DistributionSoundDocs" embed=true hidden=true name="distributions-weighted-scale-audio"}
// Offline-rendered by DistributionSoundDocs.renderWav("weighted-scale"): the box above, four cycles at 0.5 cycles per
// second.
```

`segment(8)` is what makes eight notes: `d.pat()` is continuous, like `sine()`, and is drawn wherever
it is sampled. `texts()` turns the numbers into note names the synth plays, here MIDI notes. Use it as a parameter (`gain(d.pat())`) and it draws once per event of the pattern it
is attached to. `d.pat(salt = 1)` is a second, independent stream from the same distribution.

### Drawing several patterns together

Two `pat()` calls draw independently, even from related variables. `jointPatterns(a, b, …)` draws
them together: at every instant the patterns' values are one joint sample of the graph, so the
relationships you wrote survive into the music. Here the loudness depends on the pitch, so the high
notes are loud and the low ones soft:

```diffstatic {data-lang=kotlin}
:::static
import afield.math.distribution.*

val pitch = 55 + die(12)
val loud = (pitch gt 61) * 0.6 + 0.25
val (p, l) = jointPatterns(pitch, loud)
val tune = pat("x*8") with note(p.segment(8)) with gain(l.segment(8))
```

```diffstatic_runner {data-lang=kotlin audio="assets/distributions/joint-pitch-gain.wav" audio-spec="joint-pitch-gain" audio-renderer="afield.math.distribution.DistributionSoundDocs" embed=true hidden=true name="distributions-joint-audio"}
// Offline-rendered by DistributionSoundDocs.renderWav("joint-pitch-gain").
```

### A rhythm conditioned to have five hits

A list of distributions plays as one cycle of steps: `steps.pat()` plays `steps[k]` at step `k`, and
draws the whole list jointly, once per cycle. Combined with [conditioning](#conditioning-on-what-you-saw),
this gives patterns that satisfy a rule. Sixteen coin flips, each landing heads two times in five,
conditioned on exactly five heads, are a rhythm of five hits in sixteen steps. Any five positions are
equally likely, and each cycle gets a new five:

<!--
FIELD_TEST
id: docs.distributions.five-in-sixteen
runner: backdoor-box
tags: [docs, distributions, patterns, conditioning, audio]
timeoutSec: 120
expect:
  screenshot:
    kind: box
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/distributions-five-in-sixteen.png" alt="a two-lane piano roll over four cycles: a kick on every downbeat, and hats in a different arrangement of five each cycle, above an audio player" wait="3" capture="box" open-outputs="true" w="1000" h="320" padding="18" timeout="120" embed=true name="distributions-five-in-sixteen"}
:::static
import afield.math.distribution.*

val steps = List(16) { bernoulli(0.4) }
val rhythm = steps.given(steps.sum() eq 5)
val hats = struct(rhythm.pat().map { it != 0.0 }, pat("hh"))
val beat = stack(hats, pat("bd"))

showPatRollOf("kick" to pat("bd"), "hats" to hats, title = "five hits in sixteen, a new five each cycle", cycles = 4)
showPatAudio(beat, cycles = 4, cps = 0.5, voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.03, sustain = 0.3), channel = "audio")
```

```diffstatic_runner {data-lang=kotlin audio="assets/distributions/five-in-sixteen.wav" audio-spec="five-in-sixteen" audio-renderer="afield.math.distribution.DistributionSoundDocs" embed=true hidden=true name="distributions-five-in-sixteen-audio"}
// Offline-rendered by DistributionSoundDocs.renderWav("five-in-sixteen"): the hats above with a kick on each downbeat.
```

`steps.given(e)` conditions every step on the same evidence, and that evidence counts once: it is
one observation about the rhythm, not sixteen. Each drawn cycle is a sample from the posterior,
chosen by weight from 256 weighted draws (`posteriorPool`). For a rule that is rarely met, raise
`posteriorPool`, or the same few accepted rhythms will repeat.

To play any of these live, launch `showPatSynth(tune, cps = 0.5)` instead of rendering a clip.

## Distributions as sound

`d.param()` turns a distribution into an audio parameter, a `Param`, so anything that takes one (an
oscillator's frequency, a filter's cutoff, a gain) can take a stream of draws. The draws are made on
the audio thread, from the parameter's own random stream. You choose when it draws:

| | a new value |
| --- | --- |
| `d.param()` | every audio block, ramped across the block: a jittering value |
| `d.param(everySeconds = 0.25)` | four times a second, held in between |
| `d.param(everyBeats = 1.0)` | every beat of the Ableton Link session, or of `fallbackBpm` (120) without one |
| `d.param(trigger = clock)` | at each rising edge of the signal `clock`, to the sample |

Here the weighted scale from above, as frequencies from 220 Hz, picks the pitch of a sine at every
rising edge of an 8 Hz pulse, and the same pulse gates its envelope. The box renders four seconds
offline with `renderSignalsOffline`. Then it draws the pitches it played (`pitch.played()`) as a
histogram under the exact probabilities of the distribution:

<!--
FIELD_TEST
id: docs.distributions.held-pitch
runner: backdoor-box
tags: [docs, distributions, synth, audio]
timeoutSec: 120
expect:
  screenshot:
    kind: box
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/distributions-held-pitch.png" alt="an audio player above a histogram of the 33 pitches a sine played over four seconds, from 220 to 440 Hz, under orange ticks at the scale's exact probabilities, the bars roughly following the ticks" wait="3" capture="box" open-outputs="true" w="760" h="420" padding="18" timeout="120" embed=true name="distributions-held-pitch"}
:::static
import afield.math.distribution.*

val scale = categorical([0, 3, 5, 7, 10, 12], [4, 1, 2, 3, 1, 2])
val hz = 220 * (scale * (kotlin.math.ln(2.0) / 12)).exp()

val clock = PulseOsc(freq = Param(8f), width = Param(0.35f))
val pitch = hz.param(trigger = clock)
val env = ADSR(gate = clock, attack = Param(0.004f), decay = Param(0.06f), sustain = Param(0.35f), release = Param(0.08f))
val voice = SineOsc(freq = pitch) * env * 0.5f

val sound = renderSignalsOffline(4.0, voice, sampleRate = 44_100)[0]
display("audio", afield.audio.engine.WavFloatArrayWriter.encodeMono16(sound, 44_100, normalizePeak = 0.9f))

val heard = Progressive(hz, suppliedDraws = true).addDraws(pitch.played())
display("heard", distributionSvg(heard, title = "the ${pitch.drawCount} pitches it played, in Hz"))
```

Thirty-three draws is not many, so the bars only roughly follow the ticks; left running live, they
settle onto them.

```diffstatic_runner {data-lang=kotlin audio="assets/distributions/held-pitch.wav" audio-spec="held-pitch" audio-renderer="afield.math.distribution.DistributionSoundDocs" embed=true hidden=true name="distributions-held-pitch-audio"}
// Offline-rendered by DistributionSoundDocs.renderWav("held-pitch"): the box above.
```

Played live, the box returns the sound and a loop that watches it. `ᛟ.distribution(param)` draws the
pitches as they are heard, not a separate sample, so you watch the sound settle onto the
distribution you wrote:

```diffstatic {data-lang=kotlin}
:::static
audio(voice) + loop {
    __m("heard").distribution(pitch, title = "heard")
    frame()
}
```

A posterior works here too. `(mu given observed(normal(mu, 1), readings)).param()` plays draws
from the posterior, each chosen by weight from `posteriorPool` weighted draws.

## Gaussian processes as distributions

A fitted [Gaussian process](gaussian_processes.md) knows the mean and the uncertainty of a curve at
every point. `gp.dists(xs)` hands you the curve at `xs` as distributions, one per point. They are
correlated the way the curve is: neighbouring points move together, so `jointSamples` of them draws
whole, smooth curves. Each one on its own is a normal with the GP's own mean and variance.

With the curve as distributions, you can ask it the questions a Gaussian process has no closed form
for. Where is its highest point? How likely is it to go above a line anywhere? Here a curve is
fitted through five readings, forty draws of it are drawn as FLines, and the distribution of where
its peak lies is drawn beneath it:

<!--
FIELD_TEST
id: docs.distributions.gp
runner: backdoor-box
tags: [docs, distributions, gaussian-process, fline]
timeoutSec: 120
expect:
  outputContains:
    - "P(tops 1.2 somewhere)"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/distributions-gp.png" alt="forty pale blue curves threaded through five orange readings, spreading apart between them and beyond the ends; beneath, a histogram of where each curve's highest point lies, in two separate humps, one near the second reading and one near the fourth, with the dashed mean in the empty gap between them" wait="3" capture="box" open-outputs="true" w="760" h="560" padding="18" timeout="120" embed=true name="distributions-gp"}
:::static
import afield.math.distribution.*
import afield.math.gp.*

val xs = doubleArrayOf(0.5, 2.0, 3.5, 6.0, 7.5)
val ys = doubleArrayOf(0.2, 1.1, 0.4, 0.9, -0.3)
val gp = gp1D(xs, ys, GpKernels.rbf(lengthScale = 1.0), noiseStd = 0.1)

val grid = DoubleArray(41) { it * 0.2 }
val f = gp.dists(grid)

fun px(x: Double) = 20 + x * 45
fun py(y: Double) = 150 - y * 70
val curves = jointSamples(*f.toTypedArray(), n = 40, seed = 3)
val drawn = (0 until 40).map { c ->
    fline {
        moveTo(px(grid[0]), py(curves[0][c]))
        for (i in 1 until grid.size) lineTo(px(grid[i]), py(curves[i][c]))
        color = vec(0.0, 0.67, 1.0, 0.3)
    }
}
val readings = xs.indices.map { i ->
    fline { circle(px(xs[i]), py(ys[i]), 4.0) }.apply { filled = true; color = vec(1.0, 0.67, 0.0, 1.0) }
}
lines(drawn + readings, channel = "curves", w = 400.0, h = 260.0)

val peakAt = argmax(f) * 0.2
println("P(tops 1.2 somewhere) = ${P(maximum(f) gt 1.2)}")
println("E(where the peak is)  = ${E(peakAt)}")
display("peak", distributionSvg(Progressive(peakAt).draw(20_000), title = "x of the curve's highest point"))
```

The peak is in one of two places, near the second reading or near the fourth, and the histogram
shows both. Its mean, about 3.2, lies in the gap between them, where the peak almost never is. That is
a question a mean and a variance per point cannot answer, and the distribution answers it at a
glance.

`maximum(ds)`, `minimum(ds)` and `argmax(ds)` work on any list of distributions; on a curve they
are its peak, its trough and where the peak is. `gp.dist(x)` is the GP at one point, and
`joint.toDists()` turns any `GpJoint` into distributions: a posterior over several points, its
slopes (`gp.derivativePosterior(xs)`), or a linear transform of either (`joint.linearTransform(weights)`). The curve is built from ordinary nodes,
`mean + L · z` for independent normals `z`, so it lowers to GLSL like anything else. The graph has
one node per point per normal, so this is for tens or a few hundred points.

The pattern language can play a curve: `f.map { (it + 0.5).floor() }.pat().texts()` is a melody that passes
near the notes it was fitted to, a different one each cycle. Here the five anchors are MIDI notes 62,
69, 65, 72 and 64 at steps 0, 4, 8, 12 and 15:

```diffstatic_runner {data-lang=kotlin audio="assets/distributions/gp-melody.wav" audio-spec="gp-melody" audio-renderer="afield.math.distribution.DistributionSoundDocs" embed=true hidden=true name="distributions-gp-melody-audio"}
// Offline-rendered by DistributionSoundDocs.renderWav("gp-melody"): sixteen steps of a GP posterior through five anchor
// notes, drawn once per cycle, four cycles.
```

## Learning as data arrives

`given` and `observed` condition on everything at once. Often the data comes one piece at a time:
a performer's next note, the next reading from a sensor, the next position of a tracked hand. A
`Posterior` keeps a population of particles, each a guess at the unknowns, and updates them as each
observation arrives (sequential Monte Carlo):

| | |
| --- | --- |
| `Posterior(a, b, …, particles = 2000)` | start from the priors of the unknowns `a`, `b`, … |
| `post.evolve(a to expression, …)` | how the unknowns move between observations; noise in the expression spreads the guesses |
| `post.observe(model, value)` | weigh each guess by how likely `value` is under `model`, which may use the unknowns |
| `post.observe(condition)` | keep the guesses for which the condition holds |
| `post.E(d)`, `Var`, `quantile`, `P` | ask about any expression of the unknowns, now |
| `post.ess`, `post.logEvidence` | how many guesses are still in play, and how well the model has explained the data so far |
| `post.dist(d)`, `post.dists(a, b)`, `post.live(d)` | the posterior as a distribution, to play, draw or put in a shader |

When too few guesses carry the weight, the population is resampled: guesses that explain the data
are copied and the rest are dropped. An unknown that does not move between observations collapses
onto a few repeated values after many resamples. Set `rejuvenateAfterResample = 0.95` to spread
them again.

### Following a performer

Here the data is a performance. Forty-eight notes speed up from 84 to 132 beats per minute, then
ease back to 108, each note early or late by about 12 ms. The box renders them and finds where each
note starts in the sound itself. It knows only those onset times, not the tempo. The unknowns are
the period between beats, anywhere from 0.3 to 1 second, and the time of the last beat. At each onset
the beat moves on by a period, the period drifts by about 3%, and the onset is the beat, give or
take 15 ms. Before each onset the box asks where the next beat should fall. An accompaniment plays
on those predictions, so it can only follow what it has already heard:

<!--
FIELD_TEST
id: docs.distributions.tempo-follow
runner: backdoor-box
tags: [docs, distributions, sequential, patterns, audio]
timeoutSec: 180
expect:
  outputContains:
    - "onsets heard; tempo now"
    - "log evidence with 3.0% drift"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/distributions-tempo-follow.png" alt="an audio player above a plot of tempo against time: a grey line, the tempo played, rises from 84 to 132 bpm and falls back to 108; a blue line, the tempo the posterior heard, follows it closely inside a pale blue band two standard deviations wide; small orange ticks along the bottom mark the 48 onsets found in the sound" wait="3" capture="box" open-outputs="true" w="860" h="560" padding="18" timeout="180" embed=true name="distributions-tempo-follow"}
:::static
import afield.math.distribution.*

// 1. A performance whose tempo we know: 48 beats speeding up from 84 to 132 bpm and easing back to
//    108, every beat early or late by about 12 ms; a low note on each bar, a higher one between.
val beats = 48
fun bpm(k: Int) = when {
    k < 24 -> 84.0 + 48.0 * k / 24
    k < 36 -> 132.0 - 24.0 * (k - 24) / 12
    else -> 108.0
}
val late = normal(0, 0.012).samples(beats, seed = 4)
val beatTimes = DoubleArray(beats)
var at = 0.5
for (k in 0 until beats) {
    beatTimes[k] = at + late[k]
    at += 60.0 / bpm(k)
}
val length = beatTimes.last() + 1.0
val performance = patTimed(beatTimes.mapIndexed { k, t -> TimedNote(t, t + 0.1, if (k % 4 == 0) 45.0 else 52.0) }, beats = length)
val voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.05, sustain = 0.3)
val heard = patRenderStereo(performance, cycles = 1, cps = 1 / length, voice = voice)

// 2. Where the notes start, found in the sound itself
val onsets = patDetectOnsetSamples(heard.mono, PatSoundDocs.SR, minGapSeconds = 0.3).map { it.toDouble() / PatSoundDocs.SR }

// 3. Follow the beat. Two unknowns: the period, and when the last beat fell. At each onset the beat
//    moves on by a period, the period drifts by about 3%, and the onset is the beat, give or take 15 ms.
val period = uniform(0.3, 1.0)              // 60 to 200 bpm: we do not know the tempo
val beat = known(onsets[0])                 // the first onset was a beat
val post = Posterior(period, beat, particles = 3000)
val predicted = ArrayList<Double>()         // where each next beat should fall, before it is heard
val tempo = ArrayList<Triple<Double, Double, Double>>() // time, mean bpm, sd
for (t in onsets.drop(1)) {
    predicted += post.E(beat + period).value
    post.evolve(beat to beat + period + normal(0, 0.004), period to period * normal(0, 0.03).exp())
    post.observe(normal(beat, 0.015), t)
    tempo += Triple(t, post.E(60 / period).value, kotlin.math.sqrt(post.Var(60 / period).value))
}

// 4. An accompaniment on the predicted beats: it can only follow what it has already heard
val accompaniment = patTimed(predicted.map { p -> TimedNote(p, p + 0.05, 76.0, gain = 0.45) }, beats = length)
val reply = patRenderStereo(accompaniment, cycles = 1, cps = 1 / length, voice = patVoice(wave = PatWave.SINE, decay = 0.03, sustain = 0.2))
val n = minOf(heard.frames, reply.frames)
display("audio", afield.audio.engine.WavFloatArrayWriter.encodeStereo16(
    FloatArray(n) { heard.left[it] * 0.8f + reply.left[it] * 0.5f }, FloatArray(n) { heard.right[it] * 0.8f + reply.right[it] * 0.5f },
    PatSoundDocs.SR, normalizePeak = 0.9f))

// 5. The tempo it heard, against the tempo that was played
fun px(t: Double) = 30 + t / length * 600
fun py(b: Double) = 230 - (b - 60) / 110 * 210
val truth = fline {
    moveTo(px(beatTimes[0]), py(bpm(0)))
    for (k in 1 until beats) lineTo(px(beatTimes[k]), py(bpm(k - 1)))
    color = vec(1.0, 1.0, 1.0, 0.5)
}
val followed = fline {
    moveTo(px(tempo[0].first), py(tempo[0].second))
    for ((t, m, _) in tempo) lineTo(px(t), py(m))
    color = vec(0.0, 0.67, 1.0, 1.0)
}
val band = fline {
    moveTo(px(tempo[0].first), py(minOf(170.0, tempo[0].second + 2 * tempo[0].third)))
    for ((t, m, sd) in tempo) lineTo(px(t), py(minOf(170.0, m + 2 * sd)))
    for ((t, m, sd) in tempo.reversed()) lineTo(px(t), py(maxOf(60.0, m - 2 * sd)))
    filled = true
    stroked = false
    color = vec(0.0, 0.67, 1.0, 0.25)
}
val heardAt = onsets.map { t -> fline { moveTo(px(t), 236.0); lineTo(px(t), 242.0); color = vec(1.0, 0.67, 0.0, 1.0) } }
val grid = listOf(80, 100, 120, 140, 160).map { b -> fline { moveTo(30.0, py(b.toDouble())); lineTo(630.0, py(b.toDouble())); color = vec(1.0, 1.0, 1.0, 0.12) } }
lines(grid + band + truth + followed + heardAt, channel = "tempo", w = 660.0, h = 250.0)

val errors = onsets.drop(1).indices.map { kotlin.math.abs(predicted[it] - onsets[it + 1]) * 1000 }
val now = tempo.last()
println("${onsets.size} onsets heard; tempo now ${now.second.f1} bpm, give or take ${now.third.f1} (played: 108)")
fun median(xs: List<Double>) = xs.sorted()[xs.size / 2].toInt()
println("prediction error, first 8 beats: median ${median(errors.take(8))} ms; after them: median ${median(errors.drop(8))} ms")

// 6. How much does the tempo drift? Ask the evidence: the same filter with three drift rates
fun evidenceFor(drift: Double): Double {
    val p = uniform(0.3, 1.0)
    val b = known(onsets[0])
    val f = Posterior(p, b, particles = 3000)
    for (t in onsets.drop(1)) {
        f.evolve(b to b + p + normal(0, 0.004), p to p * normal(0, drift).exp())
        f.observe(normal(b, 0.015), t)
    }
    return f.logEvidence
}
for (drift in [0.01, 0.03, 0.1]) println("log evidence with ${drift * 100}% drift per beat: ${evidenceFor(drift).f1}")
```

```diffstatic_runner {data-lang=kotlin audio="assets/distributions/tempo-follow.wav" audio-spec="tempo-follow" audio-renderer="afield.math.distribution.DistributionSoundDocs" embed=true hidden=true name="distributions-tempo-follow-audio"}
// Offline-rendered by DistributionSoundDocs.renderWav("tempo-follow"): the performance, low, with the accompaniment's
// high notes on the predicted beats.
```

It prints:

```diffstatic {data-lang=text}
:::static
48 onsets heard; tempo now 108.7 bpm, give or take 4.2 (played: 108)
prediction error, first 8 beats: median 28 ms; after them: median 18 ms
log evidence with 1.0% drift per beat: 8.2
log evidence with 3.0% drift per beat: 103.7
log evidence with 10.0% drift per beat: 81.3
```

The first beats are a guess: before the second onset the period could be anything from 0.3 to 1
second. After a few beats the predictions fall within about 20 ms of the notes, close enough for
the accompaniment to sound together with the player, even through the accelerando.

The log evidence answers a different question: how much drift should the model expect? Each of
the three filters is the same except for its drift. With 1% per beat the filter cannot keep up with
the accelerando, and with 10% it believes in changes that never happen. 3% explains the onsets best,
by a log-evidence margin of about 22 over 10%: odds of billions to one. Use it the same way to
choose between any two models of the same data.

### Tracking a point through noise

Two unknowns can be a position. A point wanders, moving about 3 units a step and drifting slowly
right, and is seen 60 times through noise of about 12 units. The posterior's path stays much closer
to the truth than the sightings do. At the end, 300 joint draws of the position (`post.samples(x, y)`)
show where it could be now:

<!--
FIELD_TEST
id: docs.distributions.track
runner: backdoor-box
tags: [docs, distributions, sequential, fline]
timeoutSec: 120
expect:
  outputContains:
    - "distance from the truth"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/distributions-track.png" alt="a wandering grey path, the truth, with orange dots scattered widely around it, the noisy sightings; a blue path, the posterior's mean, follows the grey one closely; at its right-hand end, a small cloud of pale blue dots shows where the point could be now" wait="2" capture="box" open-outputs="true" w="760" h="460" padding="18" timeout="120" embed=true name="distributions-track"}
:::static
import afield.math.distribution.*

// A point wanders, moving about 3 units a step, and is seen only through noise of about 12 units.
val steps = 60
val moves = normal(0, 3).samples(2 * steps, seed = 8)
val noise = normal(0, 12).samples(2 * steps, seed = 9)
val truth = ArrayList<Pair<Double, Double>>()
var tx = 200.0
var ty = 150.0
for (k in 0 until steps) {
    tx += moves[2 * k] + 1.5           // a slow drift to the right
    ty += moves[2 * k + 1]
    truth += tx to ty
}
val seen = truth.mapIndexed { k, (a, b) -> (a + noise[2 * k]) to (b + noise[2 * k + 1]) }

// Where is it? Two unknowns, a position. Between sightings it moves; each sighting is it, ±12.
val x = normal(200, 20)
val y = normal(150, 20)
val post = Posterior(x, y, particles = 2000)
val path = ArrayList<Pair<Double, Double>>()
for ((sx, sy) in seen) {
    post.evolve(x to x + 1.5 + normal(0, 3), y to y + normal(0, 3))
    post.observe(normal(x, 12), sx)
    post.observe(normal(y, 12), sy)
    path += post.E(x).value to post.E(y).value
}

// The truth, what was seen, the posterior's path, and 300 draws of where it is now
val drawn = post.samples(x, y, n = 300)
val pictures = [
    fline { moveTo(truth[0].first, truth[0].second); for ((a, b) in truth) lineTo(a, b); color = vec(1.0, 1.0, 1.0, 0.6) },
    fline { moveTo(path[0].first, path[0].second); for ((a, b) in path) lineTo(a, b); color = vec(0.0, 0.67, 1.0, 1.0) },
] + seen.map { (a, b) -> fline { circle(a, b, 0.8) }.apply { filled = true; color = vec(1.0, 0.67, 0.0, 0.8) } } +
    (0 until 300).map { i -> fline { circle(drawn[0][i], drawn[1][i], 0.45) }.apply { filled = true; color = vec(0.0, 0.67, 1.0, 0.35) } }
lines(pictures, channel = "track", w = 480.0, h = 300.0)

fun rms(ps: List<Pair<Double, Double>>) = kotlin.math.sqrt(ps.indices.sumOf { k ->
    val dx = ps[k].first - truth[k].first; val dy = ps[k].second - truth[k].second; dx * dx + dy * dy
} / ps.size)
println("distance from the truth: sightings ${rms(seen).f1}, posterior ${rms(path).f1}")
```

It prints `distance from the truth: sightings 17.1, posterior 7.1`. This model is linear and
Gaussian, so a Kalman filter would get the same answer exactly. `SequentialTest` checks that against
one. The particles are worth having when the model is not linear or Gaussian: a period that
multiplies, a condition, a count, a sighting that is sometimes missing.

### Playing a posterior

A posterior is still a distribution. `post.dist(60 / period)` is the tempo as the posterior has it
now, ready for `ᛟ.distribution`, `pat()`, `param()` or a shader. `post.live(d)` reads the posterior
at the moment it is drawn, so a pattern or a parameter built from it follows the posterior as
observations arrive:

```diffstatic {data-lang=kotlin}
:::static
// a pulse at the beat rate the posterior believes, redrawn four times a second as onsets come in
val beatRate = (1 / post.live(period)).param(everySeconds = 0.25)
val pulse = PulseOsc(freq = beatRate, width = Param(0.1f))
// a posterior over a point, drawn in a fragment shader: one particle by weight, x and y together
distHere("where", post.dists(x, y))
```

??? aside "How the particles are checked"
    `SequentialTest` holds the filter to what is known exactly. Five normal readings arriving one at a
    time give the conjugate posterior. Ten coin flips give Beta(8, 4). The log evidence of three readings
    matches their marginal likelihood, a multivariate normal with covariance `100·11ᵀ + I`, to within 0.1.
    And a random walk seen through noise matches the Kalman filter's mean and variance after 40
    steps. Resampling is systematic. `rejuvenate` is Liu and West's shrinkage kernel, which keeps each
    variable's weighted mean and variance. The log evidence adds, at every observation, the log of
    the weighted mean likelihood.

## On the GPU

Every building block except `map { }` can be written as GLSL. `d.toGlsl()` turns the graph into one
GLSL function, `float nlSample(inout uint s)`, with one local per node, so a name is still one
variable on the GPU and `x - x` is still exactly 0. `map { }` runs arbitrary Kotlin per sample and
stays on the CPU; `toGlsl` names the node that stopped it.

`GpuMonteCarlo(renderer, d)` runs that function in a compute shader, 256 samples in each of 16 384
invocations, so one dispatch draws about 4.2 million samples, and returns the same `Estimate` as
`E(d)`. Give it a 0–1 variable for a probability. From a box beneath a renderer it waits inside
`frames { }`:

```diffstatic {data-lang=kotlin}
:::static
import afield.math.distribution.*

val gpu = GpuMonteCarlo(renderer, hasDuplicates(draws(23, uniformInt(1, 365))))
frames {
    try {
        gpu.arm()
        while (gpu.stillWaiting()) frame()
        println(gpu.result())        // 0.5071 ± 0.0002441 (n=4194304)
    } finally {
        gpu.close()
    }
}
```

The same graphs on both, from the smoke test `four.noiselang.gpu-parity`
(`tools/run_field_tests.py --id four.noiselang.gpu-parity`). Every GPU answer is within one of its
own standard errors of the exact value, and within about two combined standard errors of the
CPU's. The last four rows are posteriors, built with `given` and `observed` as in
[Conditioning on what you saw](#conditioning-on-what-you-saw):

| | CPU, 200 000 samples | GPU, 4 194 304 samples | exact |
| --- | --- | --- | --- |
| E(5 + d6 + d6) | 12.01 ± 0.0054 | 12.00 ± 0.0012 | 12 |
| P(X + Y < 10) | 0.8327 ± 0.00083 | 0.8334 ± 0.00018 | 0.83333 |
| P(shared birthday, 23) | 0.5052 ± 0.0011 | 0.5073 ± 0.00024 | 0.50730 |
| P(u² + v² < 1) | 0.7852 ± 0.00092 | 0.7854 ± 0.00020 | 0.78540 (π/4) |
| E(x − x) | 0 ± 0 | 0 ± 0 | 0 |
| E(N(2, 3)²) | 13.02 ± 0.039 | 13.00 ± 0.0085 | 13 |
| E(mixture of two normals) | 2.511 ± 0.0060 | 2.500 ± 0.0013 | 2.5 |
| E(poisson(50)) | 50.00 ± 0.016 | 50.00 ± 0.0035 | 50 |
| P(x = 6, given x + y ≥ 10) | 0.5042 ± 0.0027 | 0.4994 ± 0.00060 | 0.5 |
| E(coin's bias, given 7 heads in 10) | 0.6674 ± 0.00032 | 0.6667 ± 0.000070 | 0.66667 (8/12) |
| E(mu, given three normal readings) | 3.089 ± 0.0033 | 3.090 ± 0.00072 | 3.08970 |
| E(Poisson rate, given three counts) | 3.502 ± 0.0030 | 3.500 ± 0.00065 | 3.5 |

`GpuMonteCarlo(renderer, d, raw = true)` writes the samples themselves instead, four per
invocation, for a histogram of GPU draws.

A posterior runs on the GPU too. With evidence, the lowered function becomes
`float nlSample(inout uint s, inout float lw)`. Each `given` adds its log-likelihood to `lw`, and
`observed` data is compiled into the shader as a constant array. `GpuMonteCarlo` then returns the
weighted estimate. With `raw = true` it writes two samples per invocation, each with its log-weight
(`samples()`, `logWeights()`).

??? aside "How the GPU estimate is accumulated"
    Each invocation sums its samples in fp32 after subtracting its own first sample, and writes
    `(sum, sum of squares, count, first)`; the host undoes the shift in double and merges the 16 384
    blocks (`Moments.addBlock`). Without the shift, `sum of squares − sum²/n` cancels badly in
    fp32 for a variable like `5 + d6 + d6` whose mean is large next to its spread. The uniform is a
    PCG hash with 24 bits per draw, and normals are Box–Muller on both sides, so CPU and GPU use the
    same constructions but different random streams.

## Distributions in a shader

`GpuMonteCarlo` answers a question about a distribution on the GPU. You can also draw from a
distribution inside your own shaders, so a fragment shader colours a pixel, or a compute shader
places a particle, with a draw from a graph you wrote in a box. `distHere("ring", d)` makes `d`
available to the shaders of that box and of every box beneath it, as `#include "dist/ring"`:

| in the box | in the shader, after `#include "dist/name"` |
| --- | --- |
| `distHere("name", d)` | `float name(inout uint s)`: one draw |
| `distHere("name", listOf(a, b, …))` | `void name(inout uint s, out float v[N])`: one joint draw, and `name_SIZE` is `N` |
| `distHere("name", posterior)` | the same, an approximate posterior draw; `bool name_try(…)` says whether it found one |
| `gpHere("name", GpShaderModel(…))` | `float name(x, uint curve)`, `float name_mean(x)`, `float name_sd(x)` |

`s` is the random state, and every draw advances it. Seed it once per pixel with
`nlSeed(gl_FragCoord.xy, frame)`, or once per invocation with `nlSeed(gl_GlobalInvocationID, frame)`.
To draw the same values at every pixel, seed by a number of your own: `nlSeed(uint(k), salt)` is
draw `k`.

Define the distributions in a box above the shader's box, and run it first. A shader's push
constants become Kotlin properties of its box when the box compiles, so the shader has to compile
before the box that owns it runs. Includes defined in that same box would not exist yet.

Here one box defines a ring, the same ring given `x + y > 0.5`, and a Gaussian process through five
readings:

<!--
FIELD_TEST
id: docs.distributions.gpu-includes
runner: backdoor-box
tags: [docs, distributions, gpu, graphics]
timeoutSec: 180
expect:
  screenshot:
    kind: native-window
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/distributions-gpu.png" alt="three panels drawn by one fragment shader: on the left a blue ring of 320 points; in the middle, orange points on the part of the same ring above a thin grey diagonal line, the posterior given x + y > 0.5; on the right, twenty pale blue Gaussian-process curves threaded through five orange readings, a white mean curve, and a dark band two standard deviations wide that is narrow at the readings and wide between and beyond them" timeout="180" capture="native-window" native-wait-frames="30" max-width="1600" embed=true name="distributions-gpu" macro-file="../../dev_docs/agents/transcripts/noiselang_gpu.yaml" capture-box="noiselang_gpu"}
:::static
import afield.math.distribution.*

// a ring: radius about 1, any angle; drawn jointly, so x and y share the same radius and angle
val radius = normal(1, 0.08)
val angle = uniform(0, 2 * Math.PI)
val ring = [radius * angle.cos(), radius * angle.sin()]
distHere("ring", ring)

// the same ring, given x + y > 0.5: a posterior the shader draws by weighted reservoir sampling
val arc = ring.given(ring[0] + ring[1] gt 0.5)
distHere("arc", arc, posteriorPool = 16)

// a Gaussian process through five readings: each `curve` number is one whole random function
val xs = doubleArrayOf(0.5, 2.0, 3.5, 6.0, 7.5)
val ys = doubleArrayOf(0.2, 1.1, 0.4, 0.9, -0.3)
gpHere("ridge", GpShaderModel.of(xs, ys, lengthScale = 1.0, noiseStd = 0.1))
```

The shader box beneath it draws one fullscreen triangle, as [the atmospheric sky](afield_four_graphics.md#atmospheric-sky)
does. Its fragment shader includes all three and splats the same 320 draws at every pixel, seeding
draw `k` with `nlSeed(uint(k), salt)`:

```diffstatic {data-lang=glsl}
:::static
#include "dist/ring"
#include "dist/arc"
#include "gp/ridge"

float splats(vec2 p, bool conditioned, float r) {
    float acc = 0.0;
    for (int k = 0; k < 320; k++) {
        uint s = nlSeed(uint(k), conditioned ? 23u : 11u);
        float v[2];
        if (conditioned) { if (!arc_try(s, v)) continue; } else ring(s, v);
        vec2 d = p - vec2(v[0], v[1]);
        acc += exp(-dot(d, d) / (2.0 * r * r));
    }
    return min(acc, 1.0);
}

// and in the third panel, at the pixel's x and y:
float m = ridge_mean(x), sd = ridge_sd(x);   // the band
for (int k = 0; k < 20; k++) {
    float f = ridge(x, uint(k));               // curve k at this x
    ...
}
```

The middle panel is a posterior drawn on the GPU. Each of its points is one of 16 draws of the ring,
chosen by weight, and the condition gives a draw outside the arc a weight of 0. `arc_try` returns
false when none of the 16 was inside, about once in two thousand draws here, and the shader skips
those. `name(…)` alone would return 0. A posterior with soft evidence, such as
`mu given observed(normal(mu, 1), readings)`, works the same way, with weights that are likelihoods.
A larger `posteriorPool` is a better draw and a slower one.

A `GpShaderModel` is the random-feature form of a Gaussian process with an RBF kernel: 64 sine and
cosine pairs by default, and up to 64 readings in one to three dimensions, so `x` is a `float`, a
`vec2` or a `vec3`. `ridge(x, curve)` builds draw number `curve` from its random weights, corrects it
towards the readings, and evaluates it at `x`. Every pixel that passes the same `curve` sees the same
function. Each call costs about `frequencies × readings` multiply-adds. `model.cpu` is the same
model on the CPU, so a box can plot or question what the shader draws.

??? aside "How the shader GP is checked"
    The draw is pathwise conditioning (Matheron's rule): a function from the prior's random features,
    plus the fitted covariance applied to how far that function misses the readings. That is the same
    construction as `GpFeatureModel.samplePath`, written out in GLSL. The smoke test
    `four.noiselang.gpu-includes` draws 16 384 curves on the GPU at four points, one of them far past the
    last reading, and checks their mean and variance against the finite-feature posterior's own
    `predict`. The worst gaps were 0.01 in the mean, at the extrapolated point, and about 1% in the variance. The same
    smoke checks the mixture, posterior and ring includes against exact answers.
    `GlslIncludeTest` compiles fragment and compute shaders that include several distributions and
    processes together, so the guards that keep shared helpers to one copy are tested.

The replayable demos are `dev_docs/agents/transcripts/noiselang_distributions.yaml`, eleven boxes
that need no renderer (every section above, from the spike to the tracked point), and
`noiselang_gpu.yaml`, the shader scene. Both are [starting points](agent_transcripts.md#noiselang_distributions).

## Limitations and what's next

What this library does not do yet, so you know where the edges are:

- **Conditioning is by weighting, so a vague prior is expensive.** `given` weighs samples drawn
  from the prior. When the evidence is sharp and the prior is wide, most samples weigh almost
  nothing. The effective sample size reports this. A `Posterior` that takes the data a piece at a
  time, resampling as it goes, wastes far fewer. There is no MCMC.
- **Exact answers only in simple cases.** `exactPmf` handles graphs of discrete leaves, by
  convolution or by visiting up to a million joint values, and gives up beyond 20 000 support
  points. `exactPdf` knows continuous leaves, their affine images, and sums of independent normals.
  A continuous graph with a shared leaf, such as `x * y` where `y` depends on `x`, or any continuous
  posterior, falls back to sampling. A plot then shows a dashed kernel estimate instead of the exact
  curve.
- **Comparisons are words.** Kotlin's `<` has to return a `Boolean`, so a comparison that returns
  a distribution is spelled `lt`, `le`, `gt`, `ge`, `eq` or `ne`.
- **The GPU is single precision.** Uniforms carry 24 random bits, and the sums are fp32 (shifted so
  they do not cancel, see the aside above). That is ample for the questions on this page. It is not
  enough for probabilities near 10⁻⁷ or for tails that need more than about four million samples per
  dispatch. The CPU and the GPU draw from different random streams, so their answers agree to
  within standard error, not sample for sample.
- **Distributions stay in their own package.** Short names like `die`, `normal`, `P` and `E` would
  collide with other box code, so a box imports `afield.math.distribution.*` itself.
- **A posterior pattern or parameter draws approximately.** Each value is one of `posteriorPool`
  (256) weighted draws, chosen by weight. When the evidence is rare, that pool holds few accepted
  draws, and the same values repeat more often than they would in the true posterior.
- **A shader's distributions come from a box above it.** Includes defined in the shader's own box
  do not exist yet when that box first compiles its shaders. The shader GP is the random-feature
  approximation, which is close to the RBF process for 64 frequencies but not identical to it.
- **A Gaussian process becomes one node per point per normal.** `gp.dists(xs)` is fine for tens or
  a few hundred points. For thousands, sample the `GpJoint` directly.
- **`ᛟ.distribution(param)` keeps up with the last 8192 draws.** If more than that arrive between two
  calls of the view (90 seconds of a parameter that draws every block), the oldest are missing from
  the histogram. The sound is unaffected.

Where it could go next:

- **Moves that use the model.** A `Posterior` spreads collapsed particles by jitter (`rejuvenate`),
  which does not look at the data. A Metropolis move would, and would let a static parameter be
  learned with fewer particles.
- **Live onsets.** The performer example detects onsets in a rendered clip. Feeding it a microphone
  needs an onset detector on the audio input, observing into the posterior as notes arrive.
- **Observations from live inputs.** Patterns and parameters draw from distributions now; the other
  direction is open. Onsets from a pattern or a microphone, or positions from a tracked hand, could
  arrive as `observed` data and update a posterior that another pattern plays.
- **Richer inline views.** Quantile bands, an empirical CDF beside the bars, and two distributions
  overlaid with the distance between them, all updating as samples arrive.
- **Gradients.** Normals and uniforms can be reparameterized, so `E(f(x; θ))` has a gradient in θ.
  Joined to the autodiff work, you could optimize a distribution: tune a scatter of FLines or a
  random rhythm by gradient descent instead of by hand.
- **More exact cases.** Symmetry would make the birthday question exact, where enumerating 365²³
  joint values cannot. Characteristic functions would give exact densities for more continuous
  sums, and conjugate pairs (a Beta prior with coin flips) exact posteriors.
