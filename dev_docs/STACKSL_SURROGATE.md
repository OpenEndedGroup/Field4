# A jump surrogate for differentiable StackSL

What a smoothed `floor` buys a differentiable StackSL fit, measured in both directions — and the
first thing it buys is a correction to the question.

Landed 2026-09-17 on `nightly/2026-09-17/2-stacksl-floor-surrogate`, based on `d6d164822`.
Reads on from `dev_docs/STACKSL_DIFFERENTIABLE.md`, which is the mechanism this extends.

## The premise was backwards, and the landed branch already said so

The brief for this work named `floor` as the wall: *"`fbm` frequency IS fittable, and the `floor`ed
cell index is what blocks the hash's gradient."* Both halves are true and the conclusion drawn from
them is inverted. `dev_docs/STACKSL_DIFFERENTIABLE.md` puts it plainly: **`floor` is what makes a
noise frequency differentiable.**

Look at what the emitter actually produces. The hash is

```
float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
```

That is `fract`, `dot`, `*` and `+` — **not** bit-twiddling. `GlslAdScalars` differentiates every
one of those, and `fract` carries derivative one, so the hash is perfectly differentiable. It is
also perfectly useless: a chaotic map whose chain rule is enormous and points nowhere. And
`_noise`'s lattice is

```
vec2 i = floor(p);
vec2 f = fract(p);
vec2 u = f * f * (3.0 - 2.0 * f);
float a = hash21(i + vec2(0.0, 0.0));   // ... b, c, d
return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
```

Inside a cell the four corner hashes **are constants**. Zero is not a wall there, it is the *true*
derivative of a piecewise-smooth function, and the gradient already flows — through `u`, which is
smooth. `floor` is the thing holding the hash's chain rule out of the answer.

So the honest verdict is **premise wrong**, and the measurement is the interesting part. See
[the cost](#m4-what-a-soft-floor-costs) for what happens when you switch the soft `floor` on
anyway: the wire the brief wanted to help gets a gradient that is wrong by eight orders of magnitude
and points the wrong way.

The real walls are elsewhere, and the landed test names them: of 325 documentable words, 295 accept
a wire upstream and **74 block** a derivative — the shapers and the predicates, `threshold`,
`posterize`, `step`, `select`, `bit-crush`, `floor` applied as a *quantiser* rather than as a cell
index. Those are what a surrogate can move.

## The design: one kernel, straight through, per family

### Every one of these operations is a jump, so mollify the delta

`floor`, `ceil`, `round`, `trunc`, `sign` and `step` are piecewise constant with jumps at known
places. Their distributional derivative is exactly `Σ (jump height) · δ(x − jump)`. Convolve each
delta with a bump of width `t` and you have a finite, smooth object: the derivative of the operation
*as seen at scale `t`*. `AdSurrogate.kernel` is that bump,

```
K_t(u) = sech²(u / 2t) / 4t
```

the logistic kernel, and each site's surrogate partial is `jumpHeight × K_t(distance to the nearest
jump)`. It integrates to 1 over the line and to `tanh(1 / 4t)` over one unit period, so a staircase
surrogate at `t = 0.25` carries 76% of the jump it stands for and at `t = 0.05` carries 99.3%. As
`t → 0` it converges to the delta and the surrogate converges to the exact (zero, almost everywhere)
derivative — so an annealing schedule is a well-defined thing to ask for rather than a knob with no
limit. Whether cooling *helps* is a separate question, and [M5](#m5--the-schedule-sweep-a-negative-result)
answers it: no.

Computed through `exp(−|z|)` rather than `cosh`: `cosh(z)²` overflows a `Double` near `|z| = 355`,
which `u / t` reaches easily at a small temperature, where the honest answer is an underflow to zero.

The jump lattices, which is what `AdSurrogateFamily` partitions on:

| family | operations | jumps at | height |
| --- | --- | --- | --- |
| `STAIRCASE` | `floor`, `ceil` | every integer | 1 |
| `STAIRCASE` | `trunc` | every integer **except zero** | 1 |
| `STAIRCASE` | `round`, `roundEven` | every half-integer | 1 |
| `STEP` | `step(edge, x)` | `x == edge`, `+K` in `x` and `−K` in `edge` | 1 |
| `SIGN` | `sign` | the origin | **2** |

`trunc` is the one asymmetry: it is flat across the origin — 0 on the whole of `(−1, 1)` — so the
integer at zero is not a jump, and the site passes an infinite distance to say so. `fract` and `mod`
need no site of their own: `GlslAdScalars` already computes them as `x − floor(x)` and
`x − y·floor(x / y)`, so a surrogated `floor` mollifies the sawtooth's `−1` jump for free.

Deliberately **not** surrogated, and each for its own reason: int coercion and `int/int` division
(type conversions that feed loop indices, where a gradient would leak into a control path); the bit
operators, the sampler family (both already refused rather than answered); and `equal`/`notEqual`,
whose mollification is a *bump* rather than a step and therefore a different object.

### Straight-through, so the primal does not move

The surrogate replaces **only the local partial**. Every value pushed is still the value the `Float`
path computes, at the same rounding point. Three things follow, and they are why this was chosen over
smoothing the forward pass:

1. the differentiable pass's primal stays **bit-identical** to `GlslFloatScalars`', so
   `StackSLGradientTest`'s and `StackSLCpuFloatPathManifestTest`'s claims survive untouched;
2. a descent reads the **true** loss and a surrogate *direction*. There is no relaxation gap to
   close at the end — the number the optimiser reports is the number the shader will render;
3. the bias is therefore entirely in the direction, and can be measured against one oracle.

Measured: **4 programs × 4 temperatures, 36 864 floats, 0 differing primal bits, 0 changed loss
values.** With a jump-free **control** (`uv 3.0 * sin 0.5 + palette`) whose *gradient* must also be
untouched at every temperature, so the invariance cannot pass by the surrogate doing nothing —
8 of the 16 comparisons do change a gradient component, and none of the control's.

### Per family, because `floor` is not one thing

A single boolean cannot express "quantise softly but leave the noise lattice alone", and that is
exactly the distinction the measurement demands. `AdSurrogate` carries a temperature *and* a set of
families. `AdSurrogate.stepOnly` is the combination that is safe everywhere measured;
`AdSurrogate.all` is for a probe and is **not** a sensible default.

### The annealing driver, which is an approximation stated rather than hidden

`AdAnnealing` is geometric from `start` to `end` — geometric because the annealed quantity is a
*width* and the interesting range spans two decades, where a linear schedule would spend most of its
iterations in the top one. It is stepped by the **evaluation** counter, not the iteration counter:
`minimizeWithGradient` owns the loop and does not hand a closure its iteration number. Under `ADAM`
and `SGD` those coincide; `LBFGS` line-searches and will cool the schedule *faster* than the
iteration count suggests.

**And the schedule turns out not to be what buys the recovery** — see
[M5](#m5--the-schedule-sweep-a-negative-result). A *fixed* hot temperature recovers marginally
better than the geometric schedule. The class stays, because a fixed temperature is expressible as
equal ends and because converging to the exact gradient is a property worth having, but this section
would be overselling it without that sentence.

## Measurements

Every number below is asserted in `StackSLSurrogateTest`, not printed. Run:

```
./gradlew jvmTest --tests 'afield.stacksl.*' --no-daemon -Pkotlin.daemon.jvmargs=-Xmx6g
```

### M1 — which words gain a gradient, and which of those gains are real

Population: the compiler's own **325** documentable words, probed as `input 1.0 * <word>` so one
wire sits upstream of each. **295** accept that shape; **154** pass a derivative exactly, **74**
block it, **67** refuse outright, and 30 do not compile in that shape. That reproduces the landed
`StackSLDifferentiabilityTest` counts exactly, which is the premise this measurement stands on.

Of the **74 blocked**, the surrogate reaches **12**:

| family | count | words |
| --- | --- | --- |
| `STEP` | 10 | `all and any band if not or select step threshold` |
| `STAIRCASE` | 2 | `ceil floor` |
| `SIGN` | **0** | — |

`SIGN` unblocking nothing is a measurement, not a gap: no documentable word routes a wire to the
image through `sign`. It is asserted as an empty set, two-sided, so a word that starts doing so is
recorded rather than silently absorbed.

**Two of the twelve are fictitious, and that is the caveat this whole page is organised around.** A
surrogate partial is *local*: it mollifies the jump in front of it and knows nothing about what
happens downstream. `any` and `ceil` both render an image pinned at **1.0**, because the still
template's own `clamp(c, 0.0, 1.0)` has already erased the jump the surrogate is standing on. The
surrogate reports ~2e-1; a central difference of the true loss is **exactly 0.0** at three widths
(0.05, 0.2, 0.5) and two resolutions (6², 24²).

So the honest headline is **10 real of 74 blocked**, not 12 — and the same mechanism appears at
program level: `uv 2.0 * sin 0.45 threshold orange 0.8 * saturate` contains a surrogated `step` and
its gradient does not change at any temperature, because its own `saturate` pins every pixel
downstream. *The clamp that matters is whichever one is active*, which is the landed branch's lesson
restated.

Both the unblocked set and the fictitious set are pinned with a **bidirectional ledger** — missing,
stale, and set equality, each with its own message and its own instruction for re-fencing — because
`entries.size >= POPULATION` catches a shrink and nothing catches an addition.

### M2 — parameter recovery, surrogate off versus on

**This is parameter recovery, not fitting.** The target is rendered by the *same* program at a
different value for one wire, so there is known ground truth. Fitting a picture no StackSL program
generated remains unattempted, exactly as `dev_docs/STACKSL_DIFFERENTIABLE.md` says.

Population: **9 walled wires over 8 programs**, 32×32, 60 Adam steps at `lr = 0.05`, annealed
`0.25 → 0.01`, one wire moving per fit. Each wire's exact gradient is asserted to be **exactly
zero** first, so the surrogate is not being credited for a gradient that already worked; each seed
is asserted 27–43% away relatively, so standing still is not a pass; and with the surrogate off the
fitted value is asserted **exactly equal to the seed** — an exact fit does not move a step.

The split by *what kind of parameter* sits behind the wall is the result:

| class | wires | worst relative error | worst off/on improvement | floors |
| --- | --- | --- | --- | --- |
| continuous — an edge, an offset | 6 | **0.0215** | **14.8×** | 0.05, 8× |
| integer-valued — a count, a bit depth | 3 | **0.2197** | **1.8×** | 0.30, 1.5× |

**Six of the nine fits reach a loss of exactly zero.** Per case:

| program | wire | seed → truth | off (rel) | on (rel) | ×  | loss |
| --- | --- | --- | --- | --- | --- | --- |
| `uv x 0.35 threshold` | `0.35` | 0.35 → 0.57 | 0.3860 | **0.0197** | 19.6 | 2.19e-01 → **0** |
| `uv y 0.60 threshold` | `0.60` | 0.60 → 0.82 | 0.2683 | **0.0137** | 19.6 | 2.19e-01 → **0** |
| `red blue uv x 0.40 threshold select` | `0.40` | 0.40 → 0.62 | 0.3548 | **0.0215** | 16.5 | 1.46e-01 → **0** |
| `uv x 2.0 * 0.30 + fract 0.5 threshold` | `0.5` | 0.50 → 0.72 | 0.3056 | **0.0206** | 14.8 | 2.50e-01 → **0** |
| `uv x 0.30 + 3.0 bit-crush` | `0.30` | 0.30 → 0.52 | 0.4231 | **0.0045** | 94.0 | 3.00e-02 → **0** |
| `uv x 0.30 + 6.0 posterize palette` | `0.30` | 0.30 → 0.52 | 0.4231 | **0.0074** | 57.1 | 1.16e-01 → **0** |
| `uv x 4.0 * floor 4.0 /` | `4.0` | 4.0 → 7.0 | 0.4286 | 0.0970 | 4.4 | 9.77e-02 → 7.81e-03 |
| `uv x 0.30 + 3.0 bit-crush` | `3.0` | 3.0 → 5.0 | 0.4000 | 0.0647 | 6.2 | 1.24e-03 → 2.94e-04 |
| `uv x 6.0 posterize palette` | `6.0` | 6.0 → 10.0 | 0.4000 | 0.2197 | 1.8 | 3.18e-02 → 1.87e-02 |

The two classes are asserted **two-sided** — the count class's worst error must exceed the
continuous class's floor — because "a count recovers worse" is a claim rather than a safety margin.
The reason is in M3: the loss is *itself* a staircase in an integer-valued parameter, with shelves a
mollified delta of width `t` cannot see across.

### M3 — the bias, against the only oracle there is

A surrogate at temperature `t` approximates the **`t`-scale central difference** of the true loss,
not its derivative. That is not a convenience: as `h → 0` the truth is exactly zero and there is
nothing left to agree with, which is the problem being worked around in the first place. So the
oracle is `(L(v+t) − L(v−t)) / 2t`, at `h = t`.

Population: the same **9 walled wires × 4 temperatures** (0.25, 0.1, 0.04, 0.016) = 36 pairs.

- **31 of 36 (86.1%)** point the same way as the oracle (floor 75%).
- **3** of the 5 misses are a **vanished oracle** — the true loss is flat over that very window, so
  there is no sign to agree with. Counted, not hidden.
- **2** are genuine sign flips, both on the integer-valued `posterize` count at the two coldest
  temperatures. That is the mechanism behind M2's split, measured rather than asserted.
- Where both are non-zero the magnitude ratio spans **0.077 … 2.177**: the surrogate
  **under-reports hot**, because the kernel carries only `tanh(1/4t)` of its jump, and
  **over-reports cold**. On `uv x 0.30 + 3.0 bit-crush` the ratio walks 0.713 → 1.024 → 1.084 →
  1.394 across the sweep, which is the cleanest view of the bias in the corpus.

### The nested-predicate collapse, which sets a floor on the schedule

`select`/`if` lowers to `mix(f, t, step(0.5, abs(cond)))`, and when `cond` is itself a `step` output
`abs(cond)` is **exactly 0 or 1**. So the outer jump sits at distance **exactly 0.5** from its edge,
forever, and `K_t(0.5) ∝ sech²(1/4t)/t` decays like `exp(−1/2t)`.

Measured on `red blue uv x 0.40 threshold select`: the surrogate gradient runs
`−5.73e-02 → −1.73e-02 → −2.84e-05 → −4.39e-13` over the four temperatures — a ratio fall of
**1.45e+11×**. Annealing switches `select` back *off*.

The practical rule: **a nested predicate wants a temperature floor near 0.1, not 0.01.** The fit in
M2 still recovers to 0.0215 only because its hot early iterations did the work before the schedule
cooled past the collapse. This is fenced as its own assertion so that a `select` lowering which
stops nesting is noticed rather than absorbed.

### M4 — what a soft floor costs

Population: 1 program (the `fbm` gallery program), its one `3` frequency wire, 32×32.

| gradient | value | relative disagreement with a central difference |
| --- | --- | --- |
| exact | 3.048159e-03 | **9.348e-03** |
| `STAIRCASE`, t = 0.25 | −7.521998e+03 | 2.491e+06 |
| `STAIRCASE`, t = 0.1 | 3.871107e+04 | 1.282e+07 |
| `STAIRCASE`, t = 0.04 | 1.816992e+05 | 6.017e+07 |
| `STAIRCASE`, t = 0.016 | 4.002593e+05 | 1.325e+08 |

Eight orders of magnitude, and **the sign flips**. Switching a soft `floor` on does not help this
wire; it is precisely what the wire was being protected from, and it gets *worse* as the schedule
cools, because a narrower kernel has a taller peak and the hash amplifies it.

The exact arm is asserted first, as the premise: if `uv 3 * fbm`'s frequency ever stops agreeing
with a central difference, this measurement is about something else. And the degradation is floored
at 1e5 with the note that a soft floor becoming *harmless* on a noise lattice would mean this page's
central claim is wrong and belongs rewritten, not quietly relaxed.

### M5 — the schedule sweep, a negative result

`AdAnnealing` was built on the assumption that *cooling* is what makes a surrogate work: hot early
to find the jump, cold late to converge on the exact derivative. The second half of that is wrong.

Population: the same **6 continuous walled wires**, 32×32, 60 Adam steps at `lr = 0.05`, four
schedules. A fixed temperature is expressed as an `AdAnnealing` with equal ends, so all four arms run
through the same code path.

| schedule | worst relative error | mean | fits reaching loss exactly 0 |
| --- | --- | --- | --- |
| fixed hot, `t = 0.25` | **0.0204** | **0.0110** | 6 of 6 |
| fixed warm, `t = 0.10` | 0.0264 | 0.0110 | 6 of 6 |
| fixed cold, `t = 0.016` | **0.3548** | 0.0683 | 5 of 6 |
| geometric, `0.25 → 0.01` | 0.0215 | 0.0146 | 6 of 6 |

**A fixed hot temperature is marginally better than the schedule**, on both the worst case and the
mean. So the honest statement is: *the temperature has to be hot enough, and the schedule's shape is
second order.* Annealing is not harmful and the class stays — a fixed temperature is one of its
cases, and converging to the exact gradient is worth having once a fit is nearly done — but it is
not what buys the recovery, and this page would be overselling it to imply otherwise. Asserted
**two-sided**: if annealing ever becomes clearly better than fixed-hot, the test says this paragraph
needs rewriting rather than relaxing.

**And the cold arm's failure is M3's collapse, in a fit.** On
`red blue uv x 0.40 threshold select` the cold arm ends **0.3548** from the truth against a seed
error of **0.3548** — it does not move *at all*, because a nested `step`'s kernel is `K_t(0.5)` and
that is about `1e-12` at `t = 0.016`. Every other continuous case recovers even when cold, which is
what makes this a statement about *nesting* rather than about the surrogate. Asserted as an exact
"did not move", which is the sharpest form the claim has.

## How to use it

| situation | setting |
| --- | --- |
| a `threshold`, `step`, `select` or `if` edge | `AdSurrogate.stepOnly(0.25)` held **fixed**, or `AdAnnealing.stepOnly(iterations)` — measured equivalent, fixed marginally ahead |
| a `posterize` offset, a `bit-crush` offset, a bare `floor` quantiser | the `STAIRCASE` family at `t = 0.25`: `AdAnnealing(0.25, 0.25, iterations, setOf(STAIRCASE))` |
| an integer-valued count or bit depth | the same, and expect ~20% relative, not 2% |
| a nested predicate (`select` over a `threshold`) | keep `t` **at or above 0.1**. At 0.016 the wire does not move at all |
| anything containing `noise`, `fbm`, `noise3`, `fbm3` … | **never** `STAIRCASE`. Use `stepOnly`, or exact |
| a program that ends in `saturate` and is mostly pinned | nothing will help; the active clamp is the wall |

The short version: **pick one hot temperature and leave it there.** Cooling below about 0.1 is the
only setting measured to lose a wire, and no setting measured beats `t = 0.25`.

## Traps hit on this branch

1. **The pre-commit guard cannot tell your edit from a test write**, and it says so about itself:
   `tools/check_test_tree_writes.py:417#report` emits *"If a path above is yours — an edit made
   while the tests were running — this guard cannot tell that apart from a test write. Re-run the
   check to confirm."* `checkTestTreeWrites` baselines the tracked tree, runs the tests and diffs,
   so editing a tracked source file *while* a commit's guard is running fails that commit with
   "modified committed files". It cost one commit on this branch. Bank the commit first, then edit.
2. **A zero in a survey deserves the same suspicion as an absence.** `posterize` and `bit-crush` do
   not appear in M1's unblocked list, and the reason is not the surrogate: `input 1.0 * posterize`
   renders an image that is **constant**, so every gradient through it is legitimately zero. Several
   plausible-looking corpus candidates (`uv x 6.0 posterize`, `uv 0.30 + 6.0 posterize`,
   `uv x 0.30 + ceil`) render entirely black or entirely pinned at 1.0 and were dropped for that
   reason, not for a surrogate reason. Appending `palette` is what makes a `posterize` program carry
   a signal at all.
3. **`uv x 0.50 - sign 0.5 * 0.5 +` does not compile**, which is part of why the `SIGN` family's
   measured reach is zero. The site exists and is correct; the vocabulary does not use it.

## Files

| file | what |
| --- | --- |
| `src/jvmMain/kotlin/afield/stacksl/cpu/GlslAdSurrogate.kt` | `AdSurrogate`, `AdSurrogateFamily`, `AdAnnealing` |
| `src/jvmMain/kotlin/afield/stacksl/cpu/GlslAd.kt` | the jump sites, and the `jumpNode`/`stepAd` seam |
| `src/jvmMain/kotlin/afield/stacksl/cpu/StackSLFit.kt` | `valueAndGradient(values, surrogate)`, `fit(… annealing =)` |
| `src/jvmTest/kotlin/afield/stacksl/cpu/StackSLSurrogateTest.kt` | all six measurements above |

## What is not here

- **No surrogate for a comparison as a `bvec`.** `mix(x, y, bvec)` is a select, and softening it
  would need the comparison node to keep its parents so a straight-through partial could reach them.
  It is tractable — the primal would stay exact, since `mixAd` at weight exactly 0 or 1 *is* the
  select — and it was not needed, because the three words that matter (`threshold`, `step`,
  `select`/`if`) all lower to a `step` call instead. `equal`/`notEqual` are a separate question.
- **No structural loss term**, so M2's recovery numbers are still a per-pixel sum of squares.
- **No attempt at fitting a target no StackSL program generated.** Same as the landed branch.
- **No fix for a fictitious gradient.** Detecting one needs a *downstream* fact (is this jump
  visible in the image at all?), which a local partial cannot have. The measurement reports them;
  the surrogate cannot suppress them.
