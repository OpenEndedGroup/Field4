# RectSL's track IR becomes differentiable — the third backend

Nightly agent, 2026-08-06, kind A. Branch `nightly/2026-08-06/1-rectsl-differentiable-tracks`,
based on `50d838851`.

## Plan

`ddf732260` made the RectSL track IR symbolic and named four capabilities the change unlocked —
printing, comparison, serialization, rewriting — then delivered exactly one of them (GLSL).
`TrackOp1/2/3` and `TimeWarpKind` each own an `eval:` and a `glsl:`. This adds the third column:
`fn:`, lowering a track to an `afield.stdlib.Functions.Fn`, which is Field's exact-autodiff
expression type. That turns every RectSL animation channel into something `fitGradient` can
*solve*.

### 1. The lowering (`RectSLFn.kt`, plus `fn:` on the op enums in `RectSL.kt`)

`Track<Double>.toFn(domain)` walks the closed IR and returns
`RectSLFnLowering.Lowered(fn)` or `RectSLFnLowering.Refused(why)`. The refusal is in the type,
not in a null or a silent zero derivative, because a wrong derivative is worse than no
derivative — the same discipline `FnNonDifferentiable` already applies inside the stdlib.

Two non-obvious things make this possible at all:

- **`Fn` has no "apply a unary function to another `Fn`'s value" op.** Only `flip`, `abs`, `exp`,
  `pow`, `log`, `clamp`, `scaleY`, `offsetY` and pointwise arithmetic are differentiable value
  transforms. `sin`/`cos` are *oscillators* over the sample position. But their `phase` child is
  evaluated at x, so `sin(frequency = 0, phase = u, amplitude = 1, offset = 0)` is exactly
  `sin(u(x))`. That one identity unlocks every trigonometric easing.
- **`Fn` has no piecewise-in-x op, and every keyframed track is piecewise.** A clamped-ramp sum
  fixes it: for keys `(t_i, v_i)` and any segment shape `S_i` with `S_i(0) = v_i`,
  `S_i(1) = v_{i+1}`,
  `v(t) = v_0 + Σ_i [ S_i(clamp01((t - t_i)/(t_{i+1} - t_i))) - v_i ]`
  is *identically* the clamped piecewise interpolant, using only clamp/divide/multiply/add — all
  differentiable, and exact at both ends. Linear, eased and TCB (Hermite, tangents resolved by
  `hermiteSegments()`) tracks all lower through it.

Refusals, stated per op rather than in aggregate: `FLOOR`/`CEIL`/`FRACT`/`MOD` (a lattice, whose
derivative is zero almost everywhere and undefined on it), `LOOP`/`PINGPONG` time warps outside a
single period, `HoldScalarTrack` with more than one key, `GateTrack` straddling an edge,
`HeadingTrack` (`atan2` has no `Fn` op), the pattern tracks, and the near-zero guards on `DIV`
and `SMOOTHSTEP` when the divisor is not statically constant (a constant one is folded exactly).

### 2. The oracle — the 37k-line golden, read as data

`ddf732260` shipped `src/jvmTest/resources/rectsl/track_characterization.txt`: every sampled rect
of all 34 gallery programs at 41 times, to full double precision, **generated from pre-refactor
code**. So it is an oracle with no code path in common with the lowering. The differential test
parses it and recomputes each rect's centre/size/rotation/scale/alpha/z through *Fn-lowered*
tracks, reporting the worst disagreement as a number and the lowering coverage as a fraction.

### 3. Then make the derivative do work

- `paramTrack` — a RectSL word pushing a named symbolic unknown into a track tree.
- `fitTrackParameters` — masked Adam on the exact gradient (`Fn.gradientAt`), moving only the
  named free parameters and leaving the structural constants alone, since `fitGradient` moves
  every numeric leaf. Reports the residual before and after.
- Exact-vs-central-difference agreement test over a spread of tracks.
- A rendered PNG with target / seed / fitted in the same image.

### 4. Stretch, if there is time

A printer for a track tree (the second of the four capabilities `ddf732260` named), and/or
pushing the fit up to the whole scene.

## Status

In progress — see below.
