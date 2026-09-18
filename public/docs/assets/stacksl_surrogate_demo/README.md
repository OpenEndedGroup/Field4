# A jump surrogate for differentiable StackSL — a loadable demo

Open this directory as a Field document. Three boxes; press run on each, left to right.

| box | what it shows | cost |
| --- | --- | --- |
| **The wall** | `uv x 0.35 threshold`, and its edge wire's **exact** gradient — which is not small, it is exactly `0.0`. Then the surrogate's number at four temperatures, beside the width-`t` central difference of the *true* loss, which is what that number approximates. | ~1 s |
| **Recovery** | The same fit twice — same seed, same target, same sixty Adam steps — surrogate off and then on. The off arm's fitted value comes back bit-identical to the seed: a zero gradient is a zero step. Four panels: target, seed, off, on. | ~1 s |
| **The cost** | That same soft `floor` switched on over `fbm`'s lattice, where the exact gradient already worked. Seven orders of magnitude wrong, and the sign flips, which is why the surrogate is per family rather than one switch. | ~3 s |

No GPU. No window. No graphics context.

## Which words are the walls

`floor` inside noise is not one of them. The hash is `fract(p * vec2(123.34, 456.21))`,
`p += dot(p, p + 45.32)`, `fract(p.x * p.y)` — every operation of which this backend
differentiates, and none of which is bit-twiddling. It is perfectly differentiable and perfectly
useless: a chaotic map. Inside a value-noise cell the four corner hashes **are** constants, so zero
is the *true* derivative of the lattice index rather than a wall, and `floor` is what holds that
chaos out of the answer.

The walls are the shapers and the predicates — `threshold`, `posterize`, `step`, `select`,
`bit-crush`, and `floor` used as a *quantiser* rather than as a cell index. Those are what the first
two boxes move; the third switches a soft `floor` on over `fbm` anyway, and shows what it costs.

## What the surrogate is

Every one of those operations is piecewise constant with jumps at known places, so its
distributional derivative is a **delta train**. Mollify it: the partial becomes
`jumpHeight × K_t(distance to the nearest jump)` with `K_t(u) = sech²(u / 2t) / 4t`. It carries
`tanh(1 / 4t)` of the jump it stands for over a unit period and converges to the delta as `t → 0`,
so a temperature is a well-defined thing to choose rather than a knob with no limit.

Whether *cooling* it during a fit helps is a separate question, and the measured answer is **no**: a
fixed `t = 0.25` recovers marginally better than a `0.25 → 0.01` schedule on every continuous case.
Cooling is roughly free rather than harmful, so pick one hot temperature and leave it there. The
second box anneals anyway, because that is the shape a reader will reach for first and it
costs nothing here — swap `AdAnnealing.stepOnly(iterations)` for
`AdAnnealing(0.25, 0.25, iterations, setOf(AdSurrogateFamily.STEP))` and watch it not get worse.

It is **straight-through**: only the local partial changes, so every value pushed is still the value
the `Float` path computes and the differentiable pass's primal stays bit-identical. The loss a
descent reads is therefore the *true* loss, and only the direction is a surrogate: there is no
relaxation gap left to close at the end. The first box's loss column is exact at every temperature.

And it is **per family** (`STEP`, `STAIRCASE`, `SIGN`), because `floor` is not one thing. The third
box is why.

## How well a parameter recovers

A **continuous** parameter behind a wall — an edge, an offset, a phase — recovers to about 2%
relative. An **integer-valued** one — a posterize level count, a bit depth, a band count — recovers
to about 20%, because the loss is itself a staircase in that parameter and a bump of width `t`
cannot see across its shelves.

A **nested predicate costs nothing extra.** `select`/`if` lowers to `mix(f, t, step(0.5, abs(cond)))`,
and when `cond` is itself a `step` output that outer `step` is restricted to a 0/1 mask, where it is
the *identity*, so the chain carries the inner jump's kernel once and not twice. A `select` over a
`threshold` tracks the bare `threshold` to within 1.54× at every temperature. `not` and `band` still
nest, because they are built from `−` and the 0/1 tracking does not follow it; keep `t` at or above
0.1 for those.

A surrogate partial is **local**, and can therefore be **fictitious**. It mollifies the jump in
front of it and knows nothing about what happens downstream, so `any` and `ceil` both gain a
gradient the true loss flatly denies: both render an image pinned at 1.0, where the still
template's own `clamp(c, 0.0, 1.0)` has already erased the jump being stood on. Of the twelve words
the surrogate reaches, ten are real and two are that.

## Editing it

Change `0.35` to `0.55` in the first box and run again. The exact gradient stays exactly zero — a
wall does not get shorter as you approach it — while the loss falls and the surrogate's number
grows. That asymmetry is the whole reason the surrogate exists.

In the second box, swap `threshold` for `6.0 posterize palette` and the family for `STAIRCASE`. In
the third, swap `staircaseOnly` for `stepOnly` and watch the numbers snap straight back to the exact
gradient, because that program contains no `step` at all, which is the argument for a per-family
surrogate rather than one switch.

If you edit the loose `*_code_.kt` files rather than the boxes, note that Field prefers the loose
file over the copy inlined in `metadata.edn`.

## Reading

`dev_docs/STACKSL_SURROGATE.md` — the measurements, the populations and the floors.
`dev_docs/STACKSL_DIFFERENTIABLE.md` — the differentiable mechanism this sits on top of.
