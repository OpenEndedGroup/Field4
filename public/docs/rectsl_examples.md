# RectSL examples

RectSL is meant to be small enough to mutate live, but concrete enough that the CPU can sample exact rectangle state. These examples use `afield.rectsl.*` from normal Field Kotlin.

## Rendered gallery

Every clip below is a live render, not a mock-up. Unlike StackSL (a fullscreen
fragment), a RectSL program is a whole *scene* — a wall, a duration, rectangles,
animation tracks, groups, and StackSL materials. It compiles to a `SceneProgram`;
each frame is sampled, lowered to a triangle mesh, and drawn through the shared
`RECT_ANIMATION_VERTEX_SHADER` plus a StackSL-compiled material fragment (the same
path as `RectSLRenderRunner`). The whole gallery is produced inside a *single*
Field document — one renderer, one graphics context — by `RectSLGalleryHarness`:
it reads back a still per program (sampled at `stillTime`) and sweeps each
program's timeline to an mp4.

The programs are not baked into the harness. Each is an editable file in a
workspace at `assets/rectsl_gallery/` (`<name>_rectsl_.rectsl` + a
`<name>_metadata_.yaml` with `docOrder`/`stillTime`/`animate`). That workspace is
also a real openable Field document — browse, live-edit, run, and download every
example below in the offline viewer. A `window + renderer` box sets up the
graphics context, and each example is a child of it, inheriting `renderer` and
drawing its scene live:

<iframe
  src="/offline.html?file=/field4/assets/rectsl_gallery/metadata.edn"
  title="RectSL gallery workspace"
  style="width:100%; height:560px; border:1px solid #444; border-radius:8px; background:#111;"
  loading="lazy"
  allowfullscreen>
</iframe>

<!--
FIELD_TEST
id: docs.rectsl.gallery
runner: backdoor-box
graphicsContext: true
tags: [docs, rectsl, shaders, graphics]
timeoutSec: 600
execution: launch
waitSec: 130.0
expect:
  allowMissingOutput: true
  image:
    path: /tmp/docs-rectsl-gallery-hero.png
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/rectsl/_gallery-hero.png" source-image="/tmp/docs-rectsl-gallery-hero.png" wait="130.0" timeout="600" execution="launch" embed=false graphics-context=true name="rectsl-gallery" hidden=true fabricate-scene="rectsl-gallery"}
```

<!--
FIELD_TEST
id: docs.rectsl.animations
runner: backdoor-box
graphicsContext: true
tags: [docs, rectsl, shaders, graphics, video]
timeoutSec: 1800
execution: launch
waitSec: 560.0
expect:
  allowMissingOutput: true
  image:
    path: /tmp/docs-rectsl-animations-hero.png
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/rectsl/_animations-hero.png" source-image="/tmp/docs-rectsl-animations-hero.png" wait="560.0" timeout="1800" execution="launch" embed=false graphics-context=true name="rectsl-animations" hidden=true fabricate-scene="rectsl-animations"}
```

Each example shows its source first, then the render. The source is the whole
`.rectsl` program — copy it into a `#rectsl` box or edit it in the viewer above.

## Reproduction with modification — array primitives

Motion-graphic design, from Oskar Fischinger's marching squares to today's
title sequences, runs on one idea: take a single element and *multiply it with
modification* — copies spaced across a row, a grid, a ring, or a beat, each one
shifted a little in position, size, timing, or colour. RectSL's **array
primitives** make that a first-class move. They reproduce a subexpression — a
built rect or group, or a quote that builds one — and hand every copy a small
**array context** so you can vary it terse-ly:

- `array` — reproduce the active object `count` times; element 0 is the template,
  the rest are fresh clones (deep for groups). The modifier quote runs on each.
- `grid` — build `cols·rows` fresh cells with `col`/`row`/`cols`/`rows` (and the
  centered `colc`/`rowc`) in scope.
- `radial` — like `array`, but each copy lands on a circle before the modifier runs.
- Context words `f` (fraction `0…1`), `fc` (centered `-1…1`), `f0` (cyclic
  fraction), and `ic` (centered index) turn "which copy is this?" into a number
  you can feed straight into `mix`, `offset`, `rotate`, `polar`, or `delay-all-time`.
- For shapes richer than linear, the [Functions bridge](#distribution-curves-the-functions-bridge)
  lets you `ease` a fraction through the Penner family or `sample` a `gaussian`,
  `noise`, or `fbm` curve at `f`.

See the [cheatsheet](rectsl_cheatsheet.md#array-primitives) for the full table.

### Radial bloom

`radial` drops sixteen petals onto a circle; `f0 tau *` turns each one to face
around the ring, a shared `rotate` track spins the whole bloom, and `f` fades the
alpha across the array. Colour comes from a StackSL `palette` material applied
per copy.

```diffstatic {data-lang=rectsl}
#rectsl
wall 12 12 abstract
duration 6
fps 30

material bloom '(
  uv 5.0 * time 0.8 * + fbm palette
)

"petal" rect
  0.5 2.6 wh
  0xffffff solid fill
  16 4.0 '(
    f0 tau * rot
    '( 0 0  6 6.28318 ) rotate
    0.35 1 f mix alpha
    bloom material
  ) radial
;
```

<video src="../assets/rectsl/rectsl-radial-bloom.mp4" poster="../assets/rectsl/rectsl-radial-bloom.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

### Grid ripple

A centered `grid` of fifty cells: `colc`/`rowc` place each cell symmetrically,
a looping `scale` oscillator makes them breathe, and `delay-all-time` phased by
Manhattan distance from the centre (`colc abs rowc abs +`) turns that breath into
a concentric ripple — pure Fischinger *Studie*.

```diffstatic {data-lang=rectsl}
#rectsl
wall 20 9 meters
duration 4
fps 30

10 5 '(
  "c" rect
    colc 1.7 *  rowc 1.7 *  xy
    0.6 square
    '( 0 0.15  1 1.15  2 0.15 ) 2 loop-time scale
    colc abs  rowc abs  +  0.16 *  delay-all-time
    0x14e0ff solid fill
  ;
) grid
```

<video src="../assets/rectsl/rectsl-grid-ripple.mp4" poster="../assets/rectsl/rectsl-grid-ripple.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

### Stagger cascade

One oscillating bar, reproduced twenty-six times with `array` and spread by the
centered index `ic`. A single `stagger-time` then delays each copy's whole
timeline in order, so the shared height oscillation reads as a travelling wave.

```diffstatic {data-lang=rectsl}
#rectsl
wall 22 8 meters
duration 3
fps 30

"bar" rect
  0 0 xy
  0.55 1 wh
  '( 0 0.4  0.5 4.5  1 0.4 ) 1 loop-time h
  0xffd23c solid fill
  26 '( ic 0.8 * 0 offset ) array
  0.05 stagger-time
;
```

<video src="../assets/rectsl/rectsl-stagger-cascade.mp4" poster="../assets/rectsl/rectsl-stagger-cascade.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

### Spiral shards

`array` plus `polar` builds a sunflower: each of forty-eight shards is placed at
the golden angle (`i 2.39996 *`) with a radius that grows with the fraction `f`,
then oriented and spun. A phyllotaxis pattern from three lines of RectSL.

```diffstatic {data-lang=rectsl}
#rectsl
wall 12 12 abstract
duration 8
fps 30

material shimmer '(
  uv 4.0 * time 0.5 * + fbm palette
)

"shard" rect
  0.32 1.05 wh
  0xffffff solid fill
  48 '(
    i 2.39996 *  f 4.6 *  polar
    i 2.39996 *  rot
    '( 0 0  8 6.28318 ) rotate
    0.4 1 f mix alpha
    shimmer material
  ) array
;
```

<video src="../assets/rectsl/rectsl-spiral-shards.mp4" poster="../assets/rectsl/rectsl-spiral-shards.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

### Distribution curves — the Functions bridge

Linear `mix` is rarely the shape you want across an array. RectSL borrows
[`afield.stdlib.Functions`](functions_examples.md) — a tested library of easings,
distributions, and noise defined over `[0, 1]`, which is exactly the range of the
array fraction `f`. `ease` shapes a value through the full Penner family; `gaussian`,
`noise`, and `fbm` push a reusable curve you `sample` at `f`; `reflect` and `tile`
reshape it. Because the curves are graph-backed, the Functions rewrite/`evolve`
tooling can even optimise an array's distribution toward a target.

#### Gaussian comb

Thirty-three bars, each a looping oscillator whose amplitude is a `gaussian`
sampled at `f` — so the row breathes as a bell rather than a flat block. A seeded
`noise` curve nudges each bar sideways, and `stagger-time` sweeps the motion into
a wave.

```diffstatic {data-lang=rectsl}
#rectsl
wall 22 8 meters
duration 3
fps 30

"bar" rect
  0 -3 xy
  0.42 1 wh
  0xffd23c solid fill
  33 '(
    ic 0.62 * 0 offset
    '( 0 0.12  0.5 1  1 0.12 ) 1 loop-time  gaussian f sample 6.5 * value*  h
    7 noise f sample 0.45 *  0 translate
    gaussian f sample 0.25 +  sat  alpha
  ) array
  0.05 stagger-time
;
```

<video src="../assets/rectsl/rectsl-gaussian-comb.mp4" poster="../assets/rectsl/rectsl-gaussian-comb.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

#### Eased orbit

A `radial` ring of twenty dots whose `scale` grows around the ring through a
`back-out` easing — so they overshoot and settle rather than ramp linearly — with
a seeded `noise` curve jittering each dot's rotation. Colour is a StackSL palette
material.

```diffstatic {data-lang=rectsl}
#rectsl
wall 12 12 abstract
duration 6
fps 30

material tint '(
  uv 3.0 * time 0.6 * + fbm palette
)

"dot" rect
  0.9 square
  0xffffff solid fill
  20 4.2 '(
    f "back-out" ease 0.25 +  scale
    5 noise f sample 0.6 *  rotate
    '( 0 0  6 6.28318 ) rotate
    tint material
  ) radial
;
```

<video src="../assets/rectsl/rectsl-eased-orbit.mp4" poster="../assets/rectsl/rectsl-eased-orbit.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

#### Inline Kotlin — the escape hatch

When the built-in words run out, `{{ … }}` drops into a `with(Functions){ … }`
Kotlin scope right inside the program. Here each bar's height envelope is a whole
Functions expression — a gaussian plus seeded fBm — composed and sampled at the
array fraction `f` inline, then multiplied by a looping oscillator:

```diffstatic {data-lang=rectsl}
#rectsl
wall 22 8 meters
duration 3
fps 30

"bar" rect
  0 0 xy
  0.42 1 wh
  0x38e1b0 solid fill
  32 '(
    ic 0.66 * 0 offset
    '( 0 0.15  0.5 1.15  1 0.15 ) 1 loop-time
    {{ (gaussian(0.5, 0.2) + fbm(9) * 0.4).eval(f) }}  5.5 *  0.25 +  value*
    h
  ) array
  0.05 stagger-time
;
```

<img src="../assets/rectsl/rectsl-inline-functions.png" alt="inline Functions escape driving an array"
     style="width:100%;max-width:640px;border-radius:8px;">

The escape compiles once (K2) and is invoked per copy. This is a real
headless-rendered gallery program, so it exercises the two places escapes must be
handled off a busy engine: the render harness pre-warms every escape on a
dedicated engine before drawing, and the CPU gallery smoke check compiles
escape-bearing files with a dedicated engine too.

### Evolving a distribution toward a target

This is the payoff — and the hardest idea to hold onto. A distribution curve is
not just a function; it is an inspectable **graph** whose numeric leaves
(positions, widths, heights) are tunable "genes". So you can hand the *host* a
target shape and let it **search curve-space** — by gradient descent (`fitBest`)
or genetically (`evolve`) — and then let RectSL lay out the array from the winner.
RectSL itself does not change: it still just `sample`s a curve. What changes is
that the curve came from an optimiser rather than from your fingers.

The RectSL scene is ordinary — three rows of bars, each an `array` sampling a
curve bound *by name* (`target`, `seed`, `fit`):

```diffstatic {data-lang=rectsl}
#rectsl
wall 22 12 abstract
duration 1
fps 1

"trow" rect  0 -4 xy  0.5 1 wh  0x2e5a86 solid fill
  40 '( ic 0.55 * 0 offset  target f sample 4 * 0.12 +  h ) array ;

"srow" rect  0 0 xy  0.5 1 wh  0x8a5a2b solid fill
  40 '( ic 0.55 * 0 offset  seed f sample 4 * 0.12 +  h ) array ;

"frow" rect  0 4 xy  0.5 1 wh  0xffd23c solid fill
  40 '( ic 0.55 * 0 offset  fit f sample {{ 4.0 }} * 0.12 +  h ) array ;
```

The gold row's height scale is written as a `{{ 4.0 }}` Kotlin escape rather than a literal — the same inline-Kotlin hatch, here proving escapes compile and run even inside the headless render. (That render is a long-running graphics loop on the box's own Kotlin engine, so the escape can't compile lazily on that engine mid-render; the harness pre-warms every escape on a *separate* idle engine before the render starts, then the render only invokes the cached lambdas.)

The host builds a two-lobe target, seeds a curve with the *same structure* but
deliberately wrong numbers, fits it, and binds all three curves into the program
with `compileRectSL(source, curves)` — the Functions→RectSL half of the bridge:

```diffstatic {data-lang=kotlin}
import afield.rectsl.compileRectSL
import afield.stdlib.Functions
import afield.stdlib.fitBest

with(Functions) {
    val target = gaussian(0.30, 0.11) + gaussian(0.72, 0.13) * 0.80
    val seed   = gaussian(0.45, 0.20) + gaussian(0.60, 0.20) * 0.50
    val run    = fitBest(target, seed, restarts = 12)   // RMSE collapses toward ~0

    val program = compileRectSL(
        source,
        mapOf("target" to target, "seed" to seed, "fit" to run.fitted)
    ).program!!
    // ... render program like any other scene ...
}
```

<!--
FIELD_TEST
id: docs.rectsl.fit
runner: backdoor-box
graphicsContext: true
tags: [docs, rectsl, shaders, graphics, functions]
timeoutSec: 360
execution: launch
waitSec: 60.0
expect:
  allowMissingOutput: true
  image:
    path: /tmp/docs-rectsl-fit-hero.png
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/rectsl/_fit-hero.png" source-image="/tmp/docs-rectsl-fit-hero.png" wait="60.0" timeout="360" execution="launch" embed=false graphics-context=true name="rectsl-fit" hidden=true fabricate-scene="rectsl-fit"}
```

<img src="../assets/rectsl/rectsl-fit-to-target.png" alt="fit a distribution to a target"
     style="width:100%;max-width:640px;border-radius:8px;">

The top (blue) row is the target silhouette; the middle (brown) row is the seed,
whose two lobes start merged in the wrong place; the bottom (gold) row is the
fitted curve — the same two-lobe shape as the target, recovered by the optimiser.
`fitBest` tuned the seed's lobe positions, widths, and heights by gradient descent;
RectSL only sampled the result. Reach for `fitBest` when you have a shape in mind
and just need the numbers dialed in, and for `evolve(...)` when the *structure*
itself (which operators) is still unknown.

## Rendered gallery — more examples

### Mirrored orbit

One rect bounces along keyed `x`/`y` tracks while spinning; `dup … mirror-x` gives it a mirrored twin in a complementary colour.

```diffstatic {data-lang=rectsl}
#rectsl
wall 16 9 meters
duration 6
fps 30

"left" rect
  -4 0 xy
  2.2 square
  0xff5a3c solid fill
  '( 0 -4  3 0  6 4 ) x
  '( 0 -2  1.5 2  3 -2  4.5 2  6 -2 ) y
  '( 0 0  6 6.28318 ) rot
;

dup "right" rename
  mirror-x
  0x3c8cff solid fill
;
```

<video src="../assets/rectsl/rectsl-mirrored-orbit.mp4" poster="../assets/rectsl/rectsl-mirrored-orbit.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

### Rising bars

A template rect is cloned eight times; a shared `linear-track` lifts them all while a smoothed `hold-track` pulses their alpha.

```diffstatic {data-lang=rectsl}
#rectsl
wall 18 8 meters
duration 6
fps 30

linear-track "rise" '( 0 -2.6   3 0   6 2.6 )
hold-track "pulse" '( 0 0.2   2 1.0   4 0.45 )

"bar-template" rect
  0 -1.6 xy
  0.9 2.2 wh
  "rise" track-ref y
  "pulse" track-ref smooth alpha
  0xeeeeee solid fill
;

8 '(
  "bar-template" select
    clone
    i layer
    i 0.1 * z
    i 3.5 - 2.0 * x
) repeat
```

<video src="../assets/rectsl/rectsl-rising-bars.mp4" poster="../assets/rectsl/rectsl-rising-bars.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

### Orbit petals

A `group` holds a `range`-cloned row of spinning squares, each painted with a StackSL `material` — shader behaviour in the same language as the rest of Field.

```diffstatic {data-lang=rectsl}
#rectsl
wall 18 8 meters
duration 8
fps 30

material bands '(
  uv 6.0 * sin abs
)

"row" group
  "petal" rect
    0 0 xy
    1.7 square
    '( 0 0   4 3.14159   8 6.28318 ) rot
    0xffffff solid fill
    bands material
  ;

  0 7 '(
    "petal" select
      clone
      i 3 - 2.4 * 0 xy
      bands material
  ) range
end-group
```

<video src="../assets/rectsl/rectsl-orbit-petals.mp4" poster="../assets/rectsl/rectsl-orbit-petals.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

### Gradient sweep

A single panel with a two-stop `linear` gradient fill, rocking and breathing on keyed `rot`/`scale` tracks.

```diffstatic {data-lang=rectsl}
#rectsl
wall 18 8 meters
duration 6
fps 30

"panel" rect
  0 0 xy
  15 6 wh
  0xff2d55 0x2d7bff  -7.5 -3  7.5 3  linear fill
  '( 0 -0.3  3 0.3  6 -0.3 ) rot
  '( 0 0.7  3 1.0  6 0.7 ) scale
;
```

<video src="../assets/rectsl/rectsl-gradient-sweep.mp4" poster="../assets/rectsl/rectsl-gradient-sweep.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

### Pinwheel

A `range` loop places sixteen blades on an ellipse using `cos`/`sin` of the loop index `i`, each given a base angle plus an added spin track via `rotate`.

```diffstatic {data-lang=rectsl}
#rectsl
wall 18 8 meters
duration 6
fps 30

"blade" rect
  0 0 xy
  1.2 square
  0xffd23c solid fill
;

0 16 '(
  "blade" select
    clone
    i tau * 16 /
    dup dup cos 7.0 * swap sin 3.0 * xy
    rot
    '( 0 0  6 6.28318 ) rotate
) range
```

<video src="../assets/rectsl/rectsl-pinwheel.mp4" poster="../assets/rectsl/rectsl-pinwheel.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

### Lissajous swarm

Sixteen dots ride a 2:1 Lissajous — `loop-time` turns keyframes into seamless oscillators, `delay-all-time` phase-shifts each clone, and a `time`-driven `fbm palette` material cycles their colour.

```diffstatic {data-lang=rectsl}
#rectsl
wall 18 9 meters
duration 8
fps 30

material glow '(
  uv 5.0 * time 0.6 * + fbm palette
)

"dot" rect
  0 0 xy
  0.8 square
  '( 0 -7  1 0  2 7  3 0  4 -7 ) 4 loop-time x
  '( 0 -3.5  0.5 0  1 3.5  1.5 0  2 -3.5 ) 2 loop-time y
  0xffffff solid fill
  glow material
;

16 '(
  "dot" select clone
    i 0.25 * delay-all-time
) repeat
```

<video src="../assets/rectsl/rectsl-lissajous-swarm.mp4" poster="../assets/rectsl/rectsl-lissajous-swarm.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

### Strobe columns

A 9×4 grid whose alpha is a `loop-time` pulse, phase-offset per column with `delay-all-time` — a rhythmic McLaren-style flicker sweeping across the wall.

```diffstatic {data-lang=rectsl}
#rectsl
wall 18 8 meters
duration 6
fps 30

"cell" rect
  0 0 xy
  1.4 square
  '( 0 1  1 0  2 1 ) 2 loop-time alpha
  0x44e0ff solid fill
;

36 '(
  "cell" select clone
    i 9 mod 4 - 2.0 *
    i 9 / floor 1.5 - 2.0 *
    xy
    i 9 mod 0.22 * delay-all-time
) repeat
```

<video src="../assets/rectsl/rectsl-strobe-columns.mp4" poster="../assets/rectsl/rectsl-strobe-columns.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

### Spectrum bars

Sixteen bars whose height is a looping oscillator, phase-staggered into a travelling wave, under a `time`-cycling StackSL material.

```diffstatic {data-lang=rectsl}
#rectsl
wall 20 8 meters
duration 6
fps 30

material spectrum '(
  uv 3.0 * time 0.5 * + fbm palette
)

"bar" rect
  0 0 xy
  0.9 1 wh
  '( 0 0.6  0.75 6.5  1.5 0.6 ) 1.5 loop-time h
  0xffffff solid fill
  spectrum material
;

16 '(
  "bar" select clone
    i 7.5 - 1.2 * 0 xy
    i 0.14 * delay-all-time
) repeat
```

<video src="../assets/rectsl/rectsl-spectrum-bars.mp4" poster="../assets/rectsl/rectsl-spectrum-bars.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

### Phase fan

Twenty-four tall thin blades rock back and forth on a `loop-time` rotation, each delayed a little more than the last — a travelling wave of angles.

```diffstatic {data-lang=rectsl}
#rectsl
wall 18 8 meters
duration 6
fps 30

"blade" rect
  0 0 xy
  0.35 4.5 wh
  '( 0 -1.0  1 1.0  2 -1.0 ) 2 loop-time rot
  0xff3caa solid fill
;

24 '(
  "blade" select clone
    i 11.5 - 0.72 * 0 xy
    i 0.11 * delay-all-time
) repeat
```

<video src="../assets/rectsl/rectsl-phase-fan.mp4" poster="../assets/rectsl/rectsl-phase-fan.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

### Wave lattice

A 10×6 lattice where every tile scales and spins on looping tracks, phase = `col + row`, so a diagonal wave rolls through the grid.

```diffstatic {data-lang=rectsl}
#rectsl
wall 18 9 meters
duration 8
fps 30

"tile" rect
  0 0 xy
  1.0 square
  '( 0 0.15  1 1.15  2 0.15 ) 2 loop-time scale
  '( 0 0  2 6.28318 ) 2 loop-time rot
  0x66ffcc solid fill
;

60 '(
  "tile" select clone
    i 10 mod 4.5 - 1.7 *
    i 10 / floor 2.5 - 1.7 *
    xy
    i 10 mod i 10 / floor + 0.14 * delay-all-time
) repeat
```

<video src="../assets/rectsl/rectsl-wave-lattice.mp4" poster="../assets/rectsl/rectsl-wave-lattice.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

## Two mirrored rectangles

`dup` copies the active rectangle, `rename` gives the copy a stable id, and
`mirror-x` mirrors its constants and x-tracks. The still is one orange square
sweeping left-to-right while fading in and out; its mirrored blue twin does the
opposite.

```diffstatic {data-lang=rectsl}
#rectsl
wall 8 4 meters
duration 4
fps 24

"left" rect
  -2 0 xy
  1.4 square
  0xff8c1a solid fill
  '( 0 -2  2 0  4 2 ) x
  '( 0 0.15  2 1  4 0.15 ) alpha
;

dup "right" rename
  mirror-x
  0x1ac6ff solid fill
;
```

<video src="../assets/rectsl/rectsl-two-mirrored.mp4" poster="../assets/rectsl/rectsl-two-mirrored.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

RectSL's premise is that the CPU can read exact rectangle state, so the same
program is equally a data structure. From normal Field Kotlin:

```diffstatic {data-lang=kotlin}
import afield.rectsl.*

val program = RectAnimationOracle.compile(rectsl).program!!
val mid  = program.sample(2.0)        // exact rect transforms/colours at t = 2s
val mesh = lowerSampledScene(mid)     // lowered to a CPU triangle mesh — no GPU
```

`sample` returns the concrete `SampledRect`s (centre, size, rotation, alpha,
fill, material) at any time, and `lowerSampledScene` turns them into the same
mesh the renderer draws — so analysis, testing, and rendering all share one
source of truth.

## Named tracks and draw layers

A named `linear-track`/`hold-track` is defined once and referenced by many
rects with `track-ref`; `smooth` softens the held pulse into an eased curve. The
template rect owns the shared geometry and tracks, then a `repeat` loop clones it
eight times, spreading them along `x` and stacking their draw order with `layer`
and `z`. All eight ride the same `rise` track up while their alpha pulses.

```diffstatic {data-lang=rectsl}
#rectsl
wall 18 8 meters
duration 6
fps 30

linear-track "rise" '( 0 -3   3 0   6 3 )
hold-track "pulse" '( 0 0.2   2 1.0   4 0.45 )

"bar-template" rect
  0 0 xy
  0.7 1.8 wh
  "rise" track-ref y
  "pulse" track-ref smooth alpha
  0xeeeeee solid fill
;

8 '(
  "bar-template" select
    clone
    i layer
    i 0.1 * z
    i 3.5 - 2.0 * x
) repeat
```

<video src="../assets/rectsl/rectsl-named-tracks.mp4" poster="../assets/rectsl/rectsl-named-tracks.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

## Groups, selections, and StackSL material

A `group` nests its rectangles into a small hierarchy and `tag` labels them
(`descendants`, `tagged`, and `rects` gather such subsets back out as
selections). Each cell is painted with a StackSL `material` — the `material tint
'( … )` body is StackSL, so shader behaviour stays in the same language as the
rest of Field's shader stack. Here seven cells spin in place, banded by the
material.

```diffstatic {data-lang=rectsl}
#rectsl
wall 16 8 meters
duration 8
fps 30

material tint '(
  uv 4.0 * sin abs
)

"orbit" group
  "cell" rect
    0 0 xy
    1.5 square
    "petal" tag
    '( 0 0  4 3.14159  8 6.28318 ) rot
    0xffffff solid fill
    tint material
  ;
  0 7 '(
    "cell" select
      clone
      i 2.2 * 6.6 - 0 xy
      tint material
  ) range
end-group
```

<video src="../assets/rectsl/rectsl-groups-material.mp4" poster="../assets/rectsl/rectsl-groups-material.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>


## Rendering JPG frames

```diffstatic {data-lang=kotlin}
import afield.rectsl.*

val settings = RectSLRenderSettings(
    pixelsPerMeter = 100.0,
    fps = 60.0,
    outputDirectory = "rectsl".path,
    renderer = renderer,
    jpegQuality = 92,
    includeFinalFrame = false,
    driveRendererWhileWaiting = false
)

frames {
    val result = RectSLRenderRunner.renderJpgSequence(rectsl, settings)
    _print(result)
}
```

Use `includeFinalFrame = false` when you want a looping sequence without duplicating the first frame at the end.
