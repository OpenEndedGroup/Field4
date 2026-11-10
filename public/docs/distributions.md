# Distributions: values that are probability distributions

`afield.math.distribution` lets a box compute with random variables as if they were numbers. A die
is a value, two dice added together is a value, and "the chance that two of 23 people share a
birthday" is a question you ask of a value. Field answers by sampling, and every answer comes with
its standard error, so you can see how far to trust it.

The idea comes from [NoiseLang](https://manualmeida.dev/articles/noiselang), a small language in
which every value is a distribution. This is the same idea written as Kotlin, plus two things a box
gets for free: the samples draw themselves next to your code as they arrive, and the same
expression can be run on the GPU.

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
| `mixture(1 to a, 3 to b)`, `categorical(values, weights)` | pick one of several, by weight |

They combine with `+ - * / %`, with numbers on either side, and with `abs()`, `sqrt()`, `exp()`,
`ln()`, `sin()`, `cos()`, `pow(e)`, `min(o)`, `max(o)` and `squared()`. Comparisons are written
`lt le gt ge eq ne`, because Kotlin's `<` must return a plain `Boolean`. A comparison is itself a
distribution that is 1 or 0, which is what makes `P(x + y lt 10)` work: a probability is the mean
of a 0–1 variable. `and`, `or`, `!` and `where` combine those.

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

Exact probabilities come from convolving independent parts: dice, coins, constants, and `+ - *`
and comparisons between variables that share no random part. `exactPmf(d)` and `exactPdf(d)` return
them, or `null` when the graph is outside what they can do. `x + x` is refused rather than wrongly
treated as two independent dice.

For your own plots, `Progressive(d)` is the accumulator the visualizer uses: `draw(n)` adds a batch,
`estimate` is the running answer, `trace` the history of (n, mean, SE), and `kept()` the samples.
`distributionSvg(progressive)` draws it.

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
(`tools/run_field_tests.py --id four.noiselang.gpu-parity`). Every GPU answer is within 0.4 of its
own standard error of the exact value, and within 2 combined standard errors of the CPU's:

| | CPU, 200 000 samples | GPU, 4 194 304 samples | exact |
| --- | --- | --- | --- |
| E(5 + d6 + d6) | 12.01 ± 0.0054 | 12.00 ± 0.0012 | 12 |
| P(X + Y < 10) | 0.8327 ± 0.00083 | 0.8334 ± 0.00018 | 0.83333 |
| P(shared birthday, 23) | 0.5052 ± 0.0011 | 0.5073 ± 0.00024 | 0.50730 |
| P(u² + v² < 1) | 0.7852 ± 0.00092 | 0.7854 ± 0.00020 | 0.78540 (π/4) |
| E(x − x) | 0 ± 0 | 0 ± 0 | 0 |
| E(N(2, 3)²) | 13.02 ± 0.039 | 13.00 ± 0.0085 | 13 |
| E(mixture of two normals) | 2.511 ± 0.0060 | 2.500 ± 0.0013 | 2.5 |

`GpuMonteCarlo(renderer, d, raw = true)` writes the samples themselves instead, four per
invocation, for a histogram of GPU draws.

??? aside "How the GPU estimate is accumulated"
    Each invocation sums its samples in fp32 after subtracting its own first sample, and writes
    `(sum, sum of squares, count, first)`; the host undoes the shift in double and merges the 16 384
    blocks (`Moments.addBlock`). Without the shift, `sum of squares − sum²/n` cancels badly in
    fp32 for a variable like `5 + d6 + d6` whose mean is large next to its spread. The uniform is a
    PCG hash with 24 bits per draw, and normals are Box–Muller on both sides, so CPU and GPU use the
    same constructions but different random streams.

The replayable demo is `dev_docs/agents/transcripts/noiselang_distributions.yaml`: the spike, the
die and the triangle, the questions above, the densities, and the walks, as four boxes.
