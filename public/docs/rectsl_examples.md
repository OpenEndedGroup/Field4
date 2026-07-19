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

Editing or adding an example is picked up by *building the docs* — no extra step.
The gallery directory is declared as a build input of the `rectsl-gallery` /
`rectsl-animations` render scenes (`FABRICATE_SCENE_INPUTS` in
`public/fabricate_docs_scenes.py`), so touching a `*_rectsl_.rectsl` file re-renders
the stills and mp4s on the next docs build (`tools/build_zensical_docs.py`);
`tools/check_doc_assets.py` then fails the build if any referenced clip was not
rendered and committed.

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


## Operator coverage — the rest of the language

The gallery above is chosen for beauty; the clips below are chosen for *coverage*. Together
they exercise every operator in the [cheatsheet](rectsl_cheatsheet.md) at least once — a smoke
test (`rectsl.examples.smoke`) fails the build if that ever stops being true, treating aliases
(`add`/`+`, `lte`/`<=`, `clone`/`copy`, …) as one. Each clip is a real headless render of an
editable program in the same `assets/rectsl_gallery/` workspace.

### Easing ladder

Four markers ride the same up-and-down key track, but each shapes it with a different Penner curve — a linear reference, then `ease-in`, `ease-out`, and `ease-in-out` — so acceleration, deceleration, and the symmetric case visibly pull apart. `fit contain` keeps the wall's aspect ratio inside the frame.

```diffstatic {data-lang=rectsl}
#rectsl
wall 12 8 meters
duration 4
fps 30
fit contain

"linear" rect
  -4.5 0 xy  1.1 square  0xff5a3c solid fill
  '( 0 -3  2 3  4 -3 ) 4 loop-time y ;

"ease-in" rect
  -1.5 0 xy  1.1 square  0xffd23c solid fill
  '( 0 -3  2 3  4 -3 ) ease-in 4 loop-time y ;

"ease-out" rect
  1.5 0 xy  1.1 square  0x38e1b0 solid fill
  '( 0 -3  2 3  4 -3 ) ease-out 4 loop-time y ;

"ease-inout" rect
  4.5 0 xy  1.1 square  0x3c8cff solid fill
  '( 0 -3  2 3  4 -3 ) ease-in-out 4 loop-time y ;
```

<video src="../assets/rectsl/rectsl-easing-ladder.mp4" poster="../assets/rectsl/rectsl-easing-ladder.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

### Time canon

One left-to-right sweep, reproduced as eight rows, each warped only in *time*: `delay-time` starts later, `scale-time` and `time*` change tempo, `time+` nudges the keys forward, `reverse-time` runs it backward, `pingpong-time` bounces, and `snap-time` quantises the key times to a grid. The shared motion falls into a round.

```diffstatic {data-lang=rectsl}
#rectsl
wall 20 12 meters
duration 6
fps 30

"plain" rect  -8 5 xy  1 square  0xff5a3c solid fill
  '( 0 -8  3 0  6 8 ) x ;
"delayed" rect  -8 3.4 xy  1 square  0xff8c3c solid fill
  '( 0 -8  3 0  6 8 ) 1.5 delay-time x ;
"stretched" rect  -8 1.8 xy  1 square  0xffd23c solid fill
  '( 0 -8  3 0  6 8 ) 0.5 scale-time x ;
"shifted" rect  -8 0.2 xy  1 square  0xa6e22e solid fill
  '( 0 -8  3 0  6 8 ) 1 time+ x ;
"faster" rect  -8 -1.4 xy  1 square  0x38e1b0 solid fill
  '( 0 -8  3 0  6 8 ) 1.4 time* x ;
"reversed" rect  -8 -3 xy  1 square  0x3c8cff solid fill
  '( 0 -8  3 0  6 8 ) reverse-time x ;
"pinged" rect  -8 -4.6 xy  1 square  0xb45cff solid fill
  '( 0 -8  1.5 8  3 -8 ) 3 pingpong-time x ;
"snapped" rect  -8 -6 xy  1 square  0xff5ab4 solid fill
  '( 0 -8  1.2 -2  2.4 4  3.6 8 ) 1 snap-time x ;
```

<video src="../assets/rectsl/rectsl-time-canon.mp4" poster="../assets/rectsl/rectsl-time-canon.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

### Key lenses

Every animated channel is also readable and re-writable. Each small square sets one track, then pulls it back out with an accessor — `x-track`, `y-track`, `w-track`, `h-track`, `rot-track`, `scale-x-track`/`scale-y-track`, `alpha-track`, `z-track`/`layer-track` — and reshapes it with `value+`, `clamp-values`, or the key-window filters `keys-before`/`keys-after`/`keys-between` before assigning it back. The last two squares show the three key modes: a named `track` (TCB), a `tcb` curve tuned with `tcb!`, and a stepped `hold`.

```diffstatic {data-lang=rectsl}
#rectsl
wall 16 10 meters
duration 4
fps 30

track named-wobble '( 0 -1  1 1  2 -1  3 1  4 -1 )

"lx" rect  -7 4 xy  1 square  0xff5a3c solid fill
  '( 0 -2  4 2 ) x
  x-track  3 value+  x ;
"ly" rect  -5 4 xy  1 square  0xffd23c solid fill
  '( 0 -4  2 4  4 -4 ) y
  y-track  -2 2 clamp-values  y ;
"lw" rect  -3 4 xy  2 1 wh  0x38e1b0 solid fill
  '( 0 0.5  2 3  4 0.5 ) w
  w-track  2 keys-before  w ;
"lh" rect  -1 4 xy  1 2 wh  0x3c8cff solid fill
  '( 0 0.5  2 3  4 0.5 ) h
  h-track  2 keys-after  h ;
"lr" rect  1 4 xy  1.4 square  0xb45cff solid fill
  '( 0 0  2 3.14159  4 6.28318 ) rot
  rot-track  1 3 keys-between  rot ;
"ls" rect  3 4 xy  1.2 square  0xff5ab4 solid fill
  '( 0 0.6  2 1.6  4 0.6 )  '( 0 1.4  2 0.6  4 1.4 ) scale-xy
  scale-x-track  1.1 value*  scale-y-track  scale-xy ;
"la" rect  5 4 xy  1.4 square  0xffffff solid fill
  '( 0 0.15  2 1  4 0.15 ) alpha
  alpha-track  0.2 value+  0 1 clamp-values  alpha ;
"lz" rect  0 -1 xy  3 square  0x224466 solid fill
  '( 0 0  4 2 ) z
  z-track  1 value+  z
  layer-track  0 4 keys-between  layer ;
"modes-tcb" rect  -6 -4 xy  0.9 square  0xff5a3c solid fill
  "named-wobble" track-ref  1.5 value*  y
  '( 0 0.6  2 1.6  4 0.6 ) tcb  -0.4 0 0 tcb!  scale ;
"modes-hold" rect  -4 -4 xy  0.9 square  0xffd23c solid fill
  '( 0 -3  1 -1  2 -3  3 1  4 3 ) hold y ;
```

<video src="../assets/rectsl/rectsl-key-lenses.mp4" poster="../assets/rectsl/rectsl-key-lenses.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

### Selection & logic

Build first, sort by logic second. A `times` loop lays down a strip of tagged cells whose brightness and size are gated by comparisons (`<`, `<=`, `>`, `>=`, `=`, `!=`) and booleans (`and`, `or`, `not`) on the loop index. Then the scene organises itself: `all`, `roots`, `children`, and `descendants` walk the tree; `rects`, `groups`, `tagged` and the predicates `rect?`/`group?`/`tagged?`/`id` filter it; and `each`/`map`/`filter` transform the survivors — finishing with a `copy-deep` of the whole strip and a `group-selected` that gathers the sparks into a new group.

```diffstatic {data-lang=rectsl}
#rectsl
wall 20 10 meters
duration 4
fps 30

"strip" group
  8 '(
    "cell" rect
      i 3.5 - 2.2 *  0  xy
      1.3 square
      "cell" tag
      0x2e5a86 solid fill
      i 2 >=  i 6 <=  and  not   0.55 *  0.45 +   alpha
      i 3 <  i 4 >  or   i 5 != and   0.25 *  0.85 +   scale
      '( 0 -0.2  2 0.2  4 -0.2 ) 4 loop-time rot
  ) times
end

"decor" group
  "dot-a" rect  -8 3.5 xy  0.6 square  0xffd23c solid fill  "spark" tag ;
  "dot-b" rect   8 3.5 xy  0.6 square  0xffd23c solid fill  "spark" tag ;
end

all rects  '( 0.85 opacity* ) each ;
all  '( "spark" tagged? ) filter  '( 1.4 scale ) map ;
all  '( rect? ) filter  '( ) each ;
all  '( group? ) filter  '( ) each ;
all groups  '( ) each ;
all  "spark" tagged  '( ) map ;
all rects  '( id "cell" = ) filter  '( 0xff5a3c solid fill ) each ;
all rects  '( id "cell" != ) filter  '( ) each ;
roots  '( ) each ;
"strip" select selected  '( ) each ;
"strip" select children  '( 0.95 opacity* ) each ;
"strip" select descendants rects  '( ) each ;
"strip" select copy-deep "strip2" rename ;
"strip2" select  0 -4 translate ;
all rects  '( "spark" tagged? ) filter  "sparks" group-selected ;
```

<video src="../assets/rectsl/rectsl-selection-logic.mp4" poster="../assets/rectsl/rectsl-selection-logic.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

### Whole-timeline transforms

Where the key-lens words retime one track, the `*-all-time` words retime *every* track on an object at once: `loop-all-time`, `pingpong-all-time`, `reverse-all-time`, and `scale-all-time` each bend one spinning square's whole timeline. The fan below reproduces a square with `array` and spreads the copies with `stagger`, tinting each by its centered fraction `fc`.

```diffstatic {data-lang=rectsl}
#rectsl
wall 20 10 meters
duration 6
fps 30

"loopy" rect  -6 2.5 xy  1.6 square  0xff5a3c solid fill
  '( 0 0  3 6.28318 ) rot  '( 0 0.6  1.5 1.5  3 0.6 ) scale
  2 loop-all-time ;
"pongy" rect  -2 2.5 xy  1.6 square  0xffd23c solid fill
  '( 0 0  3 6.28318 ) rot  '( 0 0.6  1.5 1.5  3 0.6 ) scale
  2 pingpong-all-time ;
"revvy" rect  2 2.5 xy  1.6 square  0x38e1b0 solid fill
  '( 0 -6  6 6 ) x  '( 0 0  6 6.28318 ) rot
  reverse-all-time ;
"slowy" rect  6 2.5 xy  1.6 square  0x3c8cff solid fill
  '( 0 0  3 6.28318 ) rot  '( 0 0.6  1.5 1.5  3 0.6 ) scale
  0.5 scale-all-time ;

"fan" rect  0 -3 xy  0.7 square  0xffffff solid fill
  '( 0 0  6 6.28318 ) rot
  9 '( fc abs 0.7 *  0.3 +  alpha ) array
  1.7 0 stagger ;
```

<video src="../assets/rectsl/rectsl-all-time.mp4" poster="../assets/rectsl/rectsl-all-time.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

### Kaleidoscope

A single travelling seed, mirrored three ways: `mirror-x-at` and `mirror-y-at` fold it across custom axes and `mirror-y` across the origin, while `front` and `back` decide which copy wins where they overlap. `background` sets the dark field behind them.

```diffstatic {data-lang=rectsl}
#rectsl
wall 14 14 abstract
background 0x101018
duration 6
fps 30

"seed" rect
  -3.5 -3.5 xy  2.4 square  0xff5a3c solid fill
  '( 0 0  6 6.28318 ) rot
  '( 0 -4.5  3 -2.5  6 -4.5 ) x ;

"seed" select clone "mx" rename
  3.5 mirror-x-at  0x38e1b0 solid fill  front ;
"seed" select clone "my" rename
  0 mirror-y-at  0xffd23c solid fill  back ;
"seed" select clone "myy" rename
  mirror-y  0x3c8cff solid fill ;
```

<video src="../assets/rectsl/rectsl-kaleidoscope.mp4" poster="../assets/rectsl/rectsl-kaleidoscope.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

### Grid field

A `grid` hands each cell its `col`/`row` and the grid's `cols`/`rows`; from those this field computes a radial falloff and a quantised rotation with the scalar toolbox — `sqrt`, `pow`, `min`, `max`, `neg`, `ceil`, `clamp`, `smoothstep`, `pi` — plus the stack shuffles `over` and `drop`. The wall is sized in `pixels` and `fit cover` fills the frame.

```diffstatic {data-lang=rectsl}
#rectsl
wall 1600 900 pixels
fit cover
duration 4
fps 30

12 7 '(
  "c" rect
    colc 110 *  rowc 110 *  xy
    76 square
    colc dup *  rowc dup *  +  sqrt
    dup  0 5 smoothstep  neg 1 +  0.05 max  0 1 clamp  0.9 *  0.1 +  alpha
    5 min  neg 5 +  5 /  0.4 pow  40 *  40 +  w
    col cols 1 - /  row rows 1 - /  over  +  0.5 *  swap drop
    4 *  ceil  4 /  pi *  rot
  ) grid
```

<video src="../assets/rectsl/rectsl-grid-field.mp4" poster="../assets/rectsl/rectsl-grid-field.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

### Curve transforms

Two more moves from the [Functions bridge](#distribution-curves-the-functions-bridge): `tile` repeats a `gaussian` into a comb of identical bumps that scales each bar's looping height, and `reflect` mirrors a seeded `noise` curve to nudge each bar sideways.

```diffstatic {data-lang=rectsl}
#rectsl
wall 22 8 meters
duration 3
fps 30

"bar" rect  0 -3 xy  0.5 1 wh  0x38e1b0 solid fill
  24 '(
    ic 0.85 * 0 offset
    '( 0 0.15  1.5 1.1  3 0.15 ) 3 loop-time
    gaussian 3 tile  f sample  5.5 *  value*
    h
    7 noise reflect  f sample  0.3 *  0 translate
  ) array
;
```

<video src="../assets/rectsl/rectsl-curve-transforms.mp4" poster="../assets/rectsl/rectsl-curve-transforms.png"
       autoplay loop muted playsinline
       style="width:100%;max-width:640px;border-radius:8px;">
</video>

### Paint & scene kit

The paint and scene-setup words in one panel-and-chips composition: `rgb`/`rgba` build fills channel-by-channel, `nxy`/`nwh` place and size the panel in normalised `[0, 1]` wall space, and each chip carries per-rect `shader` data read by its material. The strip is built with a `times` loop that normalises its index with `loop-count` and `fract`, runs a quote immediately with `apply`, marks one quote with `defer`, and the scene declares `fit stretch`.

```diffstatic {data-lang=rectsl}
#rectsl
wall 12 8 meters
background 0x0a0a12
fit stretch
duration 4
fps 30

material chip '(
  uv 3.0 * time 0.6 * + fbm palette
)

"panel" rect
  0.5 0.5 nxy
  0.9 0.5 nwh
  rgb 0.12 0.34 0.62 solid fill
  '( 0 -0.2  2 0.2  4 -0.2 ) rot ;

6 '(
  "chip" rect
    i loop-count 1 - /  fract  4.5 *  2.25 -  -2.2 xy
    0.7 square
    rgba 0.9 0.4 0.2 0.85 solid fill
    i 0.15 *  0.2  0.5  0  shader
    chip material
    '( 0.9 opacity* ) apply
    '( ) defer drop
) times
```

<video src="../assets/rectsl/rectsl-paint-kit.mp4" poster="../assets/rectsl/rectsl-paint-kit.png"
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
