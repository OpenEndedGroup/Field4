# Domains: how far does a function reach?

Every `Fn` in the Functions small language carries one interval: its `domain`. It is the answer to a simple question — *over what stretch of the number line is this function meant to be looked at?* A `hannWindow()` is shaped across `0.0..1.0` and is silent outside it. A `ramp` rises across the interval you give it. And an oscillator, honestly, goes on forever.

That last case is where the domain stops being bookkeeping and starts mattering, because domains are not just labels: **arithmetic intersects them.** Add two functions and the sum is only defined where *both* operands are defined. Get an operand's domain wrong and the mistake propagates silently into every expression it touches. This page is about getting it right.

## What a domain is

A `Domain` is a closed interval `min..max`, with a few well-known values:

```diffstatic {data-lang=kotlin}
Domain.DEFAULT   // 0.0..1.0 — the everyday unit interval (also Domain.UNIT)
Domain.ALL       // (-inf, +inf) — genuinely unbounded
Domain.EMPTY     // nothing; length 0, contains no point
Domain.point(x)  // a single point, min == max
```

Unless a constructor says otherwise, a fresh `Fn` starts at `Domain.DEFAULT`. You set the construction interval with `domain = ...`, relabel or place an existing function with `over (...)`, and declare "this really is unbounded" with `/ all`.

You rarely read a domain out by hand. You feel it: in *where a curve is drawn*, in *where a sampler walks*, and in *what survives an addition*.

## Domains intersect under arithmetic

This is the rule to internalize. When you combine two functions, the result is defined on the **intersection** of their domains:

```diffstatic {data-lang=kotlin}
val a = ramp(0.0, 1.0, domain = 0.0..2.0)   // defined on 0..2
val b = hannWindow(1.0..3.0)                 // defined on 1..3
val c = a + b                                // defined on 1..2  (the overlap)
```

Numeric parameters are the exception that keeps this liveable. A literal like `0.6`, or any argument passed as a `Number`, becomes a constant `Fn` whose domain is `Domain.ALL`. Intersecting with `ALL` changes nothing, so scaling and offsetting never accidentally narrow a function:

```diffstatic {data-lang=kotlin}
val d = hannWindow(1.0..3.0) * 0.6 + 0.2     // still defined on 1..3
```

Params carrying `ALL` is exactly what makes the next section's fix safe: an *explicit* finite domain, or a genuinely finite-domain operand like a window, still constrains the result — but a bare literal never does.

## Oscillators are defined everywhere

An oscillator is defined on all of ℝ. `sin()` has a value at `x = -100` and at `x = 1000`; nothing about it is confined to any particular window. So the periodic family — `sin`, `cos`, `saw`, `triangle`, `square`, plus `pulseTrain`, `impulseTrain`, and `pulseBurst` — carries `Domain.ALL`.

This matters because arithmetic intersects domains. An operand whose domain is `Domain.ALL` contributes nothing to the intersection, so a sum or product takes its extent from the terms that *do* have a finite reach:

```diffstatic {data-lang=kotlin}
val rising = identity(0.0..3.0) * 0.6   // a slow rise across 0..3
val tone   = sin(2.0)                    // Domain.ALL — unbounded
val stack  = tone + rising               // ALL ∩ 0..3  ==  0..3
// Plot `stack` over 0..3 and the oscillation rides the ramp the whole way.
```

If instead you give the oscillator a finite domain, that window *does* enter the intersection and clamps the whole expression. A `sin` bounded to `0.0..1.0` drags the sum down to `0.0..1.0`, so plotting over `0.0..3.0` stops a third of the way across:

```diffstatic {data-lang=kotlin}
val rising  = identity(0.0..3.0) * 0.6
val bounded = sin(frequency = 2.0, domain = 0.0..1.0) + rising  // 0..1 ∩ 0..3  ==  0..1
// Plot `bounded` over 0..3 and it gives out at x = 1.
```

The picture below runs both on the same box, plotted over the same `0.0..3.0` window. The only difference is the oscillator's domain:

```diffstatic_runner {data-lang=kotlin screenshot="assets/functions/oscillator-domain-clamp-before-after.png" wait="1.0" wait-output="true" capture="box" embed="true" open-outputs="true" w="1200" timeout="120" name="functions-oscillator-domain-clamp-before-after"}
:::static
import afield.stdlib.Functions

with(Functions) {
    // A gentle rise defined across the whole 0..3 window.
    val rising = identity(0.0..3.0) * 0.6

    // A bounded oscillator pinned to 0..1. The sum's domain is
    // 0..1 ∩ 0..3 == 0..1, so plotting over 0..3 stops at x = 1.
    val bounded = sin(frequency = 2.0, domain = 0.0..1.0) + rising

    // An unbounded oscillator (Domain.ALL, the default). The sum's domain
    // is ALL ∩ 0..3 == 0..3, so the oscillation rides the ramp the whole way.
    val unbounded = sin(frequency = 2.0) + rising

    bounded.plot(
        "bounded",
        domain(0.0..3.0),
        title = "bounded — sin(domain = 0..1) + rising  → clamped to 0..1",
        yDomain = domain((-1.2)..2.6),
        heightPx = 170
    )
    unbounded.plot(
        "unbounded",
        domain(0.0..3.0),
        title = "unbounded — sin() is Domain.ALL + rising  → full 0..3",
        yDomain = domain((-1.2)..2.6),
        heightPx = 170
    )
}
```

The top curve gives out at `x = 1`; the bottom one carries across the whole window. Reach for a bounded oscillator when you want a burst that lives on a finite interval — say so with `domain = ...` or `over (...)`, and intersection does the rest. Otherwise leave the default and the oscillator stays out of the way of the terms that set the extent.

## Safe to enumerate: `finiteSweep()` and `plotSafe()`

"Defined on all of ℝ" raises a fair worry: you cannot walk an infinite interval at a fixed sample count. The language resolves this at every point where an implicit domain is turned into actual samples, via `Domain.finiteSweep()`:

- a **finite** domain (including a single **point**) passes through unchanged;
- an **empty** domain passes through unchanged;
- an **unbounded** domain previews over `plotSafe()`'s finite fallback window.

So a bounded function samples over exactly the interval you would expect, while an `ALL`-domain oscillator handed no explicit window quietly previews over a sensible finite range instead of hanging. This applies at the implicit-domain samplers (`toDoubleArray`, `toVec2List`, `toFLine`, the `sample*` family, the cursor/stream/integral/filter streams, and the `repeat`/`mirrorRepeat`/`tile` anchors) and at the domain→`[0,1]` shapers (`tween`, `spliceWindow`, the `window` family) — so even transmuting an unbounded generator into a windowed shape stays finite.

In practice: give a plot or sampler an explicit window when you have one, and rely on the fallback only for a quick look:

```diffstatic {data-lang=kotlin}
sin(3.0).plot("tone", domain(0.0..2.0))   // explicit window — samples 0..2
sin(3.0).toFLine()                          // no window — previews the fallback
```

Graph serialization and rebuild keep the *original* domain, unbounded or not, so a function's declared reach still shows up in its fingerprint — the intent is preserved through a round-trip, not quietly normalized to whatever window last previewed it.

## The exception: `gaussian` stays local

Not every generator is periodic. `gaussian` is a localized bump: it has meaningful value near its center and falls away to nothing on either side. It is *not* defined on all of ℝ in any useful sense, so it keeps `Domain.DEFAULT`:

```diffstatic {data-lang=kotlin}
val bump = gaussian()          // Domain.DEFAULT — a local feature, not a signal
val wave = sin()               // Domain.ALL — genuinely everywhere
```

This is the tell for the whole model. Ask "is this function honestly defined everywhere, or only somewhere?" A signal that oscillates forever answers *everywhere* and takes `Domain.ALL`. A shape that lives in a region — a window, a bump, a ramp — answers *somewhere* and carries a finite domain, so that when it enters an expression, it constrains the result on purpose.

## The short rules

- A `Fn`'s `domain` is where it is meant to be looked at; arithmetic **intersects** operand domains.
- Oscillators (`sin`/`cos`/`saw`/`triangle`/`square`, `pulseTrain`/`impulseTrain`/`pulseBurst`) default to `Domain.ALL` — they no longer clamp the expressions they enter.
- Numeric params carry `Domain.ALL`, so literals never narrow a domain; explicit finite domains and finite-domain operands still do.
- Unbounded domains are safe to enumerate — `finiteSweep()` previews them over a finite window, while finite/point/empty domains pass through untouched.
- `gaussian` keeps `Domain.DEFAULT`: it is local, not periodic.

See also the [Functions examples](functions_examples.md) page, whose *Domains and timeline composition* section covers `domain = ...` versus `over`, `stretch`, and the timeline helpers in more depth.
