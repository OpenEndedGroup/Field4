# Tuning by Gaussian process: optimisation and A/B preference

Some parameters are tuned by trying them: a drawing's three dials, a synth patch, a simulation's
constants. When each try is slow, or the judge is you, the number of tries is what matters.
Field has two tools for this, both in `afield.math.gp` and so available in every box without an
import. Each one fits a [Gaussian process](gaussian_processes.md) to the tries so far and uses
it to choose the next one.

- `GpOptimizer` searches for the setting that maximises (or minimises) a number you can compute.
- `GpPreference` searches for the setting you like best, from nothing more than your answers to
  "A or B?".

## Optimising a number

Name each parameter with its range, then call `step` with the thing to score. Here the score is
how far a three-dial curve is from a target curve:

```diffstatic {data-lang=kotlin}
val opt = GpOptimizer("wobble" to 0.0..0.6, "phase" to 0.0..2 * PI, "squash" to 0.4..1.4, minimize = true)
repeat(30) { opt.step { p -> misfit(p) } }

println(opt.best)          // GpTrial(parameters={wobble=…, phase=…, squash=…}, value=…)
println(opt.bestSoFar)     // the best value after each try
```

`p` is a `Map<String, Double>` of the parameter values to try. The first few tries (four, or the
number of parameters plus two) are spread over the ranges. After that, every `step` fits a GP to
everything tried so far and picks the point with the largest *expected improvement* on the best
value: somewhere it expects a better score, or somewhere it does not yet know, whichever is
worth more.

You can drive the loop yourself instead of handing over a function. This is how to use it when
the score comes from somewhere else, such as a render you time or a slider you set:

```diffstatic {data-lang=kotlin}
val p = opt.suggest()                 // what to try next
opt.observe(p, measure(p))            // what it scored
val four = opt.suggest(4)             // four to try at once, spread apart
opt.predict(p)                        // the model's mean and variance there
```

`GpOptimizer` maximises unless you pass `minimize = true`. Other options:

- `acquisition = GpAcquisition.UpperConfidenceBound(beta = 2.0)` chooses by mean + `beta` × standard
  deviation, which explores more as `beta` grows. `ExpectedImprovement(xi = 0.01)` is the default.
- `seed` makes a run repeatable: the same seed and the same scores give the same suggestions.
- `hyperparameters` shows the fitted length scale of each parameter (in units of its range) and
  the noise. A short length scale means the score changes quickly along that parameter.

!!! note "Thirty tries, not three thousand"
    Each step refits a GP to every observation, which takes longer as tries accumulate. It is
    meant for tens to a few hundred evaluations, where each one is expensive. For a score that
    costs microseconds, a grid or random search is quicker. Ranges should be finite, and a
    parameter that only takes whole numbers can be rounded inside your score function.

## Learning what you prefer

`GpPreference` takes the same named ranges. `nextPair()` gives two settings to show, and `choose`
records which one won:

```diffstatic {data-lang=kotlin}
val taste = GpPreference("petals" to 3.0..9.0, "depth" to 0.1..0.8, "curl" to 0.0..1.0, "hue" to 0.0..360.0)
var pair = taste.nextPair()

fun answer(aWins: Boolean) {
    taste.choose(pair, aWins)
    pair = taste.nextPair()
    lines(rosette(pair.first, 0.0), rosette(pair.second, 300.0), w = 600.0, h = 280.0, fitToSize = true)
}

controlGroup {
    button("A is better") { down -> if (down) answer(true) }
    button("B is better") { down -> if (down) answer(false) }
}
```

Each answer updates a GP over a hidden "how much you like it" score. The next pair is the
current favourite against the setting with the most promise: one that is likely better, or
that the model knows little about. `taste.best` is the favourite so far, and `taste.ranking()`
lists every setting you have seen, best first.

The scores are relative. A setting's `utility(p)` can be compared with another's, but its zero
means nothing.

- `noise` (default 0.1) says how inconsistent your answers are allowed to be. A smaller value
  trusts each click more, and changes its mind more abruptly.
- `lengthScale` (default 0.25, in units of each range) says how far apart two settings must be
  before your opinion of one says little about the other.
- `exploration` (default 1.0) weighs promise against what is already known when choosing the
  challenger.

Answering the same pair both ways is fine: the two settings end up tied.

## The demo

The document `dev_docs/agents/demos/gp_instruments/` has three boxes. Box 1 tunes a curve to a
target with `GpOptimizer` and charts the best misfit after each try. Box 2 is the A/B loop
above, waiting for you to click. Box 3 runs the same loop with a simulated judge whose taste is
known, and draws the favourite after every six answers next to that judge's ideal.

??? aside "How the two models work"
    `GpOptimizer` rescales every parameter to [0, 1] and standardises the scores. Its surrogate
    is a Matérn-5/2 GP with one length scale per parameter, refitted with the noise by maximum
    evidence after every observation. The acquisition function is maximised over random points
    and jitters of the best observations, then refined by gradient ascent; the gradient is a
    central difference, because expected improvement needs the gradient of the posterior
    standard deviation and the GP library exposes only the gradient of the mean. Batches use the
    "kriging believer": each chosen point is provisionally observed at the model's own mean.

    `GpPreference` is the probit preference GP of Chu and Ghahramani (2005). The posterior over
    the latent utility is replaced by its Laplace approximation, found by Newton's method. The
    design notes are in `dev_docs/GP_INSTRUMENTS.md`.
