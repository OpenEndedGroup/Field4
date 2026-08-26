# Domains: how far does a function reach?

*Every public declaration used below is listed, with its KDoc, in the codebase reference: [`Functions.kt`](reference/math.md#functions-kt). Start from [what's already in the box](reference/index.md).*

Every `Fn` in the Functions small language carries one interval: its `domain`, the stretch of the number line the function is meant to be looked at over. A `hannWindow()` is shaped across `0.0..1.0` and is silent outside it. A `ramp` rises across the interval you give it. An oscillator goes on forever.

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

## Why oscillators are infinite (a lie and a hazard, fixed)

An oscillator is genuinely defined on all of ℝ. `sin()` has a value at `x = -100` and at `x = 1000`; nothing about it is confined to `0.0..1.0`. Yet for a long time the periodic constructors defaulted to `Domain.DEFAULT`, the unit interval — a convenient lie.

The lie was also a hazard, precisely because arithmetic intersects domains. A freshly built oscillator carried `0.0..1.0`, so the *moment it entered any expression* it silently clamped the whole expression to `0.0..1.0`:

```diffstatic {data-lang=kotlin}
// Old behavior — the trap.
val rising = identity(0.0..3.0) * 0.6         // a slow rise across 0..3
val tone   = sin(2.0)                          // used to carry domain 0..1
val stack  = tone + rising                     // 0..1  ∩  0..3  ==  0..1  (!)
// Plot `stack` over 0..3 and it just stops a third of the way across.
```

Nobody asked for that: the `rising` term was defined across `0.0..3.0`, and the oscillator amputated two-thirds of it.

Today the periodic family — `sin`, `cos`, `saw`, `triangle`, `square`, plus `pulseTrain`, `impulseTrain`, and `pulseBurst` — defaults `domain` to `Domain.ALL`. An unbounded operand contributes nothing to the intersection, so the expression takes its extent from the terms that actually have an opinion:

```diffstatic {data-lang=kotlin}
// New behavior.
val rising = identity(0.0..3.0) * 0.6
val tone   = sin(2.0)                          // Domain.ALL
val stack  = tone + rising                     // ALL ∩ 0..3  ==  0..3  ✔
// Plot `stack` over 0..3 and the oscillation rides the ramp the whole way.
```

The picture below runs both versions on the same box, plotted over the same `0.0..3.0` window. Same expression, one character changed — the oscillator's default domain — and the curve goes from truncated to whole:

```diffstatic_runner {data-lang=kotlin screenshot="assets/functions/oscillator-domain-clamp-before-after.png" wait="1.0" wait-output="true" capture="box" embed="true" open-outputs="true" w="1200" timeout="120" name="functions-oscillator-domain-clamp-before-after"}
:::static
import afield.stdlib.Functions

with(Functions) {
    // A gentle rise defined across the whole 0..3 window.
    val rising = identity(0.0..3.0) * 0.6

    // BEFORE: an oscillator pinned to the old 0..1 default. The sum's domain
    // is 0..1 ∩ 0..3 == 0..1, so plotting over 0..3 stops at x = 1.
    val before = sin(frequency = 2.0, domain = 0.0..1.0) + rising

    // AFTER: today's default — sin() is Domain.ALL. The sum's domain is
    // ALL ∩ 0..3 == 0..3, so the oscillation rides the ramp the whole way.
    val after = sin(frequency = 2.0) + rising

    before.plot(
        "before",
        domain(0.0..3.0),
        title = "BEFORE — sin(domain = 0..1) + rising  → clamped to 0..1",
        yDomain = domain((-1.2)..2.6),
        heightPx = 170
    )
    after.plot(
        "after",
        domain(0.0..3.0),
        title = "AFTER — sin() is Domain.ALL + rising  → full 0..3",
        yDomain = domain((-1.2)..2.6),
        heightPx = 170
    )
}
```

The top curve gives out at `x = 1`; the bottom one carries across the whole window. Same math, same plot window — the only difference is whether the oscillator told the truth about its reach.

Because it is the *default* that changed, the fix reaches you without any edit to your own code. And if you *want* a bounded oscillator — a burst that lives on a finite interval — say so explicitly with `domain = ...` or `over (...)`, and intersection does the rest.

## Safe to enumerate: `finiteSweep()` and `plotSafe()`

"Defined on all of ℝ" raises a fair worry: you cannot walk an infinite interval at a fixed sample count. The language resolves this at every point where an implicit domain is turned into actual samples, via `Domain.finiteSweep()`:

- a **finite** domain (including a single **point**) passes through unchanged;
- an **empty** domain passes through unchanged;
- an **unbounded** domain previews over `plotSafe()`'s finite fallback window.

So a bounded function samples over exactly the interval you would expect, while an `ALL`-domain oscillator handed no explicit window previews over a sensible finite range instead of hanging. This applies at the implicit-domain samplers (`toDoubleArray`, `toVec2List`, `toFLine`, the `sample*` family, the cursor/stream/integral/filter streams, and the `repeat`/`mirrorRepeat`/`tile` anchors) and at the domain→`[0,1]` shapers (`tween`, `spliceWindow`, the `window` family) — so even transmuting an unbounded generator into a windowed shape stays finite.

In practice: give a plot or sampler an explicit window when you have one, and rely on the fallback only for a quick look:

```diffstatic {data-lang=kotlin}
sin(3.0).plot("tone", domain(0.0..2.0))   // explicit window — samples 0..2
sin(3.0).toFLine()                          // no window — previews the fallback
```

Graph serialization and rebuild keep the *original* domain, unbounded or not, so a function's declared reach still shows up in its fingerprint — the intent survives a round-trip rather than being normalized to whatever window last previewed it.

## The exception: `gaussian` stays local

Not every generator is periodic. `gaussian` is a localized bump: it has meaningful value near its center and falls away to nothing on either side. It is *not* defined on all of ℝ in any useful sense, so it keeps `Domain.DEFAULT`:

```diffstatic {data-lang=kotlin}
val bump = gaussian()          // Domain.DEFAULT — a local feature, not a signal
val wave = sin()               // Domain.ALL — genuinely everywhere
```

This is the question the whole model turns on: is this function defined everywhere, or only somewhere? A signal that oscillates forever answers *everywhere* and takes `Domain.ALL`. A shape that lives in a region — a window, a bump, a ramp — answers *somewhere* and carries a finite domain, so that it constrains the result of any expression it enters.

## The short rules

- A `Fn`'s `domain` is where it is meant to be looked at; arithmetic **intersects** operand domains.
- Oscillators (`sin`/`cos`/`saw`/`triangle`/`square`, `pulseTrain`/`impulseTrain`/`pulseBurst`) default to `Domain.ALL` — they no longer clamp the expressions they enter.
- Numeric params carry `Domain.ALL`, so literals never narrow a domain; explicit finite domains and finite-domain operands still do.
- Unbounded domains are safe to enumerate — `finiteSweep()` previews them over a finite window, while finite/point/empty domains pass through untouched.
- `gaussian` keeps `Domain.DEFAULT`: it is local, not periodic.

See also the [Functions examples](functions_examples.md) page, whose *Domains and timeline composition* section covers `domain = ...` versus `over`, `stretch`, and the timeline helpers in more depth.
