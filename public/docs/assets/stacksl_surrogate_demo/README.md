# A jump surrogate for differentiable StackSL — a loadable demo

Open this directory as a Field document. Three boxes; press run on each, left to right.

| box | what it shows | cost |
| --- | --- | --- |
| **The wall** | `uv x 0.35 threshold`, and its edge wire's **exact** gradient — which is not small, it is exactly `0.0`. Then the surrogate's number at four temperatures, beside the width-`t` central difference of the *true* loss, which is the only honest oracle for it. | ~1 s |
| **Recovery** | The same fit twice — same seed, same target, same sixty Adam steps — surrogate off and then on. The off arm's fitted value comes back bit-identical to the seed: a zero gradient is a zero step. Four panels: target, seed, off, on. | ~1 s |
| **The cost** | That same soft `floor` switched on over `fbm`'s lattice, where the exact gradient already worked. Seven orders of magnitude wrong, and the sign flips. This is the box that says why there is no global switch. | ~3 s |

No GPU. No window. No graphics context.

## The point, which starts with the question being wrong

This work was asked for on the premise that `floor` is the wall blocking a noise fit. It is the
opposite, and the branch it builds on had already measured that: *"`floor` is what makes a noise
frequency differentiable"*.

The hash is `fract(p * vec2(123.34, 456.21))`, `p += dot(p, p + 45.32)`, `fract(p.x * p.y)` — every
operation of which this backend differentiates, and none of which is bit-twiddling. It is perfectly
differentiable and perfectly useless: a chaotic map. And inside a value-noise cell the four corner
hashes **are** constants, so zero is the *true* derivative of the lattice index rather than a wall.
`floor` is the thing holding that chaos out of the answer. The third box runs the experiment the
premise implies, and watches it fail on purpose.

The real walls are the shapers and the predicates — `threshold`, `posterize`, `step`, `select`,
`bit-crush`, and `floor` used as a *quantiser* rather than as a cell index. Those are what the first
two boxes move.

## What the surrogate is, in one paragraph

Every one of those operations is piecewise constant with jumps at known places, so its
distributional derivative is a **delta train**. Mollify it: the partial becomes
`jumpHeight × K_t(distance to the nearest jump)` with `K_t(u) = sech²(u / 2t) / 4t`. It carries
`tanh(1 / 4t)` of the jump it stands for over a unit period and converges to the delta as `t → 0`,
which is what makes an annealing schedule mean something rather than being decoration.

It is **straight-through**: only the local partial changes, so every value pushed is still the value
the `Float` path computes and the differentiable pass's primal stays bit-identical. The loss a
descent reads is therefore the *true* loss, and only the direction is a surrogate — there is no
relaxation gap left to close at the end. The first box's table is that claim made visible: the loss
column is exact at every temperature.

And it is **per family** (`STEP`, `STAIRCASE`, `SIGN`), because `floor` is not one thing. The third
box is why.

## What to expect, and what not to

A **continuous** parameter behind a wall — an edge, an offset, a phase — recovers to about 2%
relative. An **integer-valued** one — a posterize level count, a bit depth, a band count — recovers
to about 20%, because the loss is itself a staircase in that parameter and a bump of width `t`
cannot see across its shelves. That split is measured, floored, and asserted two-sided.

A **nested predicate is worse than it looks.** `select`/`if` lowers to
`mix(f, t, step(0.5, abs(cond)))`, and when `cond` is itself a `step` output, `abs(cond)` is exactly
0 or 1 — so the outer jump sits at distance exactly 0.5 forever and `K_t(0.5)` decays like
`exp(−1/2t)`. Measured: the gradient falls by 1.45e+11 from `t = 0.25` to `t = 0.016`. A nested
predicate wants a temperature **floor near 0.1**, not 0.01.

A surrogate partial is **local**, and can therefore be **fictitious**. It mollifies the jump in
front of it and knows nothing about what happens downstream, so `any` and `ceil` both gain a
gradient the true loss flatly denies — both render an image pinned at 1.0, where the still
template's own `clamp(c, 0.0, 1.0)` has already erased the jump being stood on. Of the twelve words
the surrogate reaches, ten are real and two are that.

## Editing it

Change `0.35` to `0.55` in the first box and run again. The exact gradient stays exactly zero — a
wall does not get shorter as you approach it — while the loss falls and the surrogate's number
grows. That asymmetry is the whole reason the surrogate exists.

In the second box, swap `threshold` for `6.0 posterize palette` and the family for `STAIRCASE`. In
the third, swap `staircaseOnly` for `stepOnly` and watch the numbers snap straight back to the exact
gradient, because that program contains no `step` at all — which is the argument for a per-family
surrogate rather than one switch.

If you edit the loose `*_code_.kt` files rather than the boxes, note that Field prefers the loose
file over the copy inlined in `metadata.edn`.

## Reading

`dev_docs/STACKSL_SURROGATE.md` — the measurements, the populations and the floors.
`dev_docs/STACKSL_DIFFERENTIABLE.md` — the differentiable mechanism this sits on top of.
