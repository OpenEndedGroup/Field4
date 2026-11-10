# Distributions: values that are probability distributions

`afield.math.distribution` lets a box compute with random variables as if they were numbers. A die
is a value, two dice added together is a value, and "the chance that two of 23 people share a
birthday" is a question you ask of a value. Field answers by sampling, and every answer comes with
its standard error, so you can see how far to trust it.

The idea comes from [NoiseLang](https://manualmeida.dev/articles/noiselang), a small language in
which every value is a distribution. This is the same idea written as Kotlin, plus two things a box
gets for free: the samples draw themselves next to your code as they arrive, and the same
expression can be run on the GPU.

It then goes past NoiseLang in three directions. A distribution can be
[conditioned on what you saw](#conditioning-on-what-you-saw), which turns a model into a posterior.
It can be [played](#patterns-from-a-distribution), as a pattern or as a
[sound parameter](#distributions-as-sound). And a fitted
[Gaussian process](#gaussian-processes-as-distributions) can be turned into distributions, so you
can ask questions about whole curves.

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

val scale = categorical(listOf(0, 3, 5, 7, 10, 12), listOf(4, 1, 2, 3, 1, 2))
val tune = (60 + scale).pat().segment(8).texts()

showPatRollOf("60 + scale" to tune, title = "eight draws a cycle from a weighted pentatonic", cycles = 4, pitched = true)
showPatAudio(tune, cycles = 4, cps = 0.5, voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.05, sustain = 0.7), channel = "audio")
```

```diffstatic_runner {data-lang=kotlin audio="assets/distributions/weighted-scale.wav" audio-spec="weighted-scale" audio-renderer="afield.math.distribution.DistributionSoundDocs" embed=true hidden=true name="distributions-weighted-scale-audio"}
// Offline-rendered by DistributionSoundDocs.renderWav("weighted-scale"): the box above, four cycles at 0.5 cycles per second.
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

val scale = categorical(listOf(0, 3, 5, 7, 10, 12), listOf(4, 1, 2, 3, 1, 2))
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
// Offline-rendered by DistributionSoundDocs.renderWav("gp-melody"): sixteen steps of a GP posterior through five anchor notes, drawn once per cycle, four cycles.
```

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

The replayable demo is `dev_docs/agents/transcripts/noiselang_distributions.yaml`: the spike, the
die and the triangle, the questions above, the densities, and the walks, as four boxes.

## Limitations and what's next

What this library does not do yet, so you know where the edges are:

- **Conditioning is by weighting, so a vague prior is expensive.** `given` weighs samples drawn
  from the prior. When the evidence is sharp and the prior is wide, most samples weigh almost
  nothing. The effective sample size reports this, but nothing moves the samples towards the data
  yet. There is no MCMC and no sequential Monte Carlo.
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
- **A Gaussian process becomes one node per point per normal.** `gp.dists(xs)` is fine for tens or
  a few hundred points. For thousands, sample the `GpJoint` directly.
- **`ᛟ.distribution(param)` keeps up with the last 8192 draws.** If more than that arrive between two
  calls of the view (90 seconds of a parameter that draws every block), the oldest are missing from
  the histogram. The sound is unaffected.

Where it could go next:

- **Sequential inference.** A posterior updated as observations arrive, by resampling and moving
  its samples (sequential Monte Carlo): a performer's tempo from onsets, or a tracked hand. This
  would also fix the wasted samples of a vague prior.
- **Observations from live inputs.** Patterns and parameters draw from distributions now; the other
  direction is open. Onsets from a pattern or a microphone, or positions from a tracked hand, could
  arrive as `observed` data and update a posterior that another pattern plays.
- **The GLSL lowering as an include.** `toGlsl()` already writes a distribution as one function.
  Shipped as a `#include` with its seed plumbing, a fragment, compute or particle shader could draw
  from a distribution defined in a box, and the box's histogram would describe what the GPU is
  drawing.
- **Richer inline views.** Quantile bands, an empirical CDF beside the bars, and two distributions
  overlaid with the distance between them, all updating as samples arrive.
- **Gradients.** Normals and uniforms can be reparameterized, so `E(f(x; θ))` has a gradient in θ.
  Joined to the autodiff work, you could optimize a distribution: tune a scatter of FLines or a
  random rhythm by gradient descent instead of by hand.
- **More exact cases.** Symmetry would make the birthday question exact, where enumerating 365²³
  joint values cannot. Characteristic functions would give exact densities for more continuous
  sums, and conjugate pairs (a Beta prior with coin flips) exact posteriors.
