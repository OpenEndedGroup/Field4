# RectSL cheatsheet

RectSL is Field's rectangle scene language. It creates animated rectangle geometry on the CPU, samples exact object state for analysis, and lowers sampled scenes into `afield.four` buffers. StackSL remains the material language: RectSL decides where rectangles are and what object/material metadata they carry; StackSL decides how a material shades.

A RectSL string starts with `#rectsl` when embedded in Kotlin:

```diffstatic {data-lang=kotlin}
import afield.rectsl.*

val source = """
#rectsl
->wall 4 4 meters
->duration 4
->fps 20

"left" rect
  -1 0 ->xy
  1 ->square
  '( 0 -1  2 0  4 3 ) ->x
  '( 0 0   2 1  4 0 ) ->alpha
;

dup "right" ->rename
  ->mirror-x
;
"""

val program = RectAnimationOracle.compile(source).program!!
val frame = program.sample(2.0)
```

## Model

RectSL is a small stack language with scene side effects. Numbers, strings, colors, quotes, selections, rects, groups, paints, and scalar tracks can sit on the stack. Most geometry words apply to the active rect/group or to the current selection.

Words beginning with `->` are mutators: they consume values and write through to
the active rect, group, selection, or scene. Ordinary words transform stack values,
query the scene, control evaluation, or construct new values/nodes. Thus
`f0 tau *` computes an angle while `f0 tau * ->rot` commits that angle to the
active object. The arrow is part of the word, not optional punctuation.

| Syntax | Meaning |
| --- | --- |
| `# comment` | comment to end of line |
| `12`, `-0.25`, `1e-3` | number |
| `0xffaa33` | RGB color literal |
| `"name"` | string literal |
| `'( ... )` | quoted RectSL block |
| `: name ... ;` | definition |
| `material name '( ... )` | named StackSL material definition |

## Kotlin entry points

| API | Purpose |
| --- | --- |
| `RectAnimationOracle.compile(source)` | Parse, type-check, and build a `RectProgram`. |
| `RectProgram.sample(time)` | Sample exact rectangle/group state at a time in seconds. |
| `lowerSampledScene(sample)` | Convert a sampled scene to CPU mesh data. |
| `RectSLRenderRunner.renderJpgSequence(source, settings)` | Render a JPG frame sequence through a Field renderer. |
| `RectSLRenderSettings(...)` | Output path, fps, quality, renderer, frame inclusion, and wait policy. |
| `RectSLEditorExtension.javascript()` | Monaco embedded-language support for `#rectsl` Kotlin strings. |

## Scene words

| Word | Stack | Notes |
| --- | --- | --- |
| `->wall meters` | `width height --` | Sets physical wall size in meters. |
| `->wall pixels` | `width height --` | Sets wall size in pixel units. |
| `->wall abstract` | `width height --` | Sets wall size in arbitrary units. |
| `->duration` | `seconds --` | Scene duration in seconds. |
| `->fps` | `framesPerSecond --` | Sampling/render frame rate. |
| `->background` | `color --` or `0xrrggbb ->background` | Sets scene background. |
| `->fit contain` | `--` | Preserve aspect ratio and fit inside target. |
| `->fit cover` | `--` | Preserve aspect ratio and cover target. |
| `->fit stretch` | `--` | Stretch wall coordinates to target. |

## Objects and groups

| Word | Stack | Notes |
| --- | --- | --- |
| `rect` | `[id] -- rect` | Creates a rect. If the top stack value is a string, it becomes the id. |
| `group` | `[id] -- group` | Opens a group scope. New objects become group children until `end` / `end-group`. |
| `end`, `end-group` | `--` | Closes the current group. |
| `->select` | `id -- object` | Selects a rect or group and pushes its reference. |
| `->rename` | `name -- object` | Renames the active object or a reference from the stack. |
| `->tag` | `name --` | Adds a tag to the active object or current selection. |
| `id` | `-- id` | Pushes the active object id. |
| `rect?` | `-- bool` | `1` when the active object is a rect. |
| `group?` | `-- bool` | `1` when the active object is a group. |
| `tagged?` | `tag -- bool` | Tests the active object for a tag. |
| `copy`, `clone` | `-- object` | Shallow copy of the active object. |
| `copy-deep`, `clone-deep` | `-- object` | Deep copy of a group and its descendants. |
| `->group-selected` | `selection [id] -- group` | Reparents selected objects into a new group. |

## Geometry and ordering

| Word | Stack | Notes |
| --- | --- | --- |
| `->xy` | `x y --` | Center position in wall coordinates. |
| `->nxy` | `nx ny --` | Normalized position in `[-1, 1]` wall space. |
| `->x`, `->y` | `scalar --` | Constant or animated coordinate. A quote becomes a TCB track. |
| `->wh` | `width height --` | Rect size. |
| `->nwh` | `nw nh --` | Normalized size relative to wall dimensions. |
| `->w`, `->h` | `scalar --` | Width or height. |
| `->square` | `size --` | Sets width and height together. |
| `->rot`, `->rotation` | `scalar --` | Rotation in radians. |
| `->rotate` | `scalar --` | Adds rotation to the active object/selection. |
| `->face-travel` | `--` | Auto-orient: sets rotation to the heading of the object's own x/y tracks (local +x points along travel). |
| `->face-travel-at` | `offset --` | Like `->face-travel`, but adds a fixed angle offset (correct for how the art is drawn). |
| `->scale` | `scalar --` | Uniform scale. |
| `->scale-xy` | `sx sy --` | Nonuniform scale. |
| `->alpha` | `scalar --` | Opacity. |
| `->opacity*` | `factor --` | Multiplies opacity. |
| `->appear` | `t --` | Hard-gate visibility on: invisible before `t`, unchanged from `t`. |
| `->vanish` | `t --` | Hard-gate visibility off: unchanged before `t`, invisible from `t`. |
| `->live` | `birth life --` | Emitter primitive: play the object's animation from `birth` (local time 0 = `birth`) and show it only during `[birth, birth+life]`. |
| `->z` | `scalar --` | Per-rect depth metadata. |
| `->layer` | `number|string|track --` | Draw layer. Higher layers render later. |
| `->front`, `->back` | `--` | Move active object/selection within its layer. |

## Paint and material

| Word | Stack | Notes |
| --- | --- | --- |
| `rgb` | `r g b -- color` | Builds an RGB color from 0..1 channels. |
| `rgba` | `r g b a -- color` | Builds an RGBA color from 0..1 channels. |
| `solid` | `color -- paint` | Solid fill paint. |
| `linear` | `color0 color1 x0 y0 x1 y1 -- paint` | CPU-baked two-color fill across the rect. |
| `->fill` | `paint --` | Assigns fill paint. |
| `material name '( ... )` | `--` | Compiles the quote source as a named StackSL rect material. |
| `name ->material` | `materialName --` | Assigns a previously defined or built-in material to the active rect/selection. |
| `->shader` | `x y z w --` | Sets per-rect shader data. |

Material StackSL receives rect/material data from the generated shader harness. Prefer RectSL for geometry and StackSL for fragment math; do not duplicate shape layout inside StackSL unless the shape is genuinely shader-only.

## Tracks

Track quotes are flat `time value` pairs. An optional `=` may sit between the
time and value of a key to read the pairing aloud — `0=0.2` means "0.2 at time
0". The two forms are interchangeable and may be mixed:

```diffstatic {data-lang=text}
'( 0 -1  2 0  4 1 ) linear
'( 0=-1  2=0  4=1 ) linear
```

| Word | Stack | Notes |
| --- | --- | --- |
| `tcb` | `quote -- track` | Smooth scalar track using TCB interpolation. |
| `linear` | `quote -- track` | Linear scalar track. |
| `hold` | `quote -- track` | Step/hold scalar track. |
| `tcb!` | `track tension continuity bias -- track` | Sets default TCB parameters. |
| `track` | `name quote --` or `track name quote` | Defines a named TCB track. |
| `linear-track` | `linear-track name quote` | Prefix word defining a named linear track. |
| `hold-track` | `hold-track name quote` | Prefix word defining a named hold track. |
| `track-ref` | `name -- track` | Pushes a named track for reuse. |
| `x-track`, `y-track` | `-- track` | Pushes the active x/y track. |
| `w-track`, `h-track` | `-- track` | Pushes width/height tracks. |
| `rot-track`, `rotation-track` | `-- track` | Pushes rotation track. |
| `scale-x-track`, `scale-y-track` | `-- track` | Pushes scale tracks. |
| `alpha-track` | `-- track` | Pushes alpha track. |
| `z-track`, `layer-track` | `-- track` | Pushes ordering tracks. |

## Track transforms

| Word | Stack | Notes |
| --- | --- | --- |
| `delay-time` | `track seconds -- track` | Shifts key times later. |
| `scale-time` | `track factor -- track` | Scales key times. |
| `reverse-time` | `track -- track` | Reverses key times. |
| `loop-time` | `track period -- track` | Loops over a period. |
| `pingpong-time` | `track period -- track` | Alternates forward/backward over a period. |
| `time+` | `track seconds -- track` | Alias-style key-time shift. |
| `time*` | `track factor -- track` | Alias-style key-time scale. |
| `snap-time` | `track grid -- track` | Snaps key times to a grid. |
| `value+` | `track amount -- track` | Adds to all key values. |
| `value*` | `track factor -- track` | Multiplies all key values. |
| `clamp-values` | `track low high -- track` | Clamps key values. |
| `keys-before` | `track time -- track` | Keeps keys before time. |
| `keys-after` | `track time -- track` | Keeps keys after time. |
| `keys-between` | `track start end -- track` | Keeps keys in a time window. |
| `ease-in` | `scalar -- track` | Quadratic ease-in. |
| `ease-out` | `scalar -- track` | Quadratic ease-out. |
| `ease-in-out` | `scalar -- track` | Symmetric ease-in/out. |
| `ease-smoothstep`, `smooth` | `scalar -- track` | Hermite smoothstep. |

## Selection and transforms

| Word | Stack | Notes |
| --- | --- | --- |
| `all` | `-- selection` | Every node. |
| `roots` | `-- selection` | Top-level nodes. |
| `selected` | `-- selection` | Current active object/selection. |
| `children` | `selection -- selection` | Direct children of groups. |
| `descendants` | `selection -- selection` | All descendants. |
| `rects`, `groups` | `selection -- selection` | Type filters. |
| `tagged` | `selection tag -- selection` | Tag filter. |
| `each` | `selection quote -- selection` | Apply a quote to each selected object. |
| `map` | `selection quote -- selection` | Map a quote over selected objects. |
| `filter`, `where` | `selection quote -- selection` | Keep objects whose quote returns true. |
| `->mirror-x`, `->mirror-y` | `--` | Mirror around axis 0. |
| `->mirror-x-at`, `->mirror-y-at` | `axis --` | Mirror around a custom axis. |
| `->translate` | `dx dy --` | Translate active object/selection. |
| `->offset` | `dx dy --` | Offset position constants/tracks. |
| `->stagger` | `dx dy --` | Increasing positional offset over selection order. |
| `->delay-all-time` | `seconds --` | Delay every track on active object/selection. |
| `->scale-all-time` | `factor --` | Scale every key time. |
| `->reverse-all-time` | `--` | Reverse every track across scene duration. |
| `->loop-all-time` | `period --` | Loop every track. |
| `->pingpong-all-time` | `period --` | Ping-pong every track. |
| `->stagger-time` | `stepSeconds --` | Increasing time delay over selection order. |

## Loops and blocks

| Word | Stack | Notes |
| --- | --- | --- |
| `apply` | `quote --` | Evaluate a quote immediately. |
| `defer` | `quote -- quote` | No-op marker; quotes are already values. |
| `times` | `count quote --` | Runs quote `count` times and pushes the index before each run. |
| `repeat` | `count quote --` | Runs quote `count` times. Use `i`, `n`, or `loop-count` inside. |
| `range` | `start end quote --` | Runs quote for each integer in `[start, end)`, or descending when `end < start`. |
| `i` | `-- number` | Current loop index/value. |
| `loop-count`, `n` | `-- number` | Count for the current loop. |

## Array primitives

Array primitives *reproduce a subexpression with modification* — the Fischinger idea of one shape multiplied into a rhythm of copies, each shifted in space, time, size, or colour. Inside any `repeat`/`range`/`array`/`grid`/`radial` body the per-copy context words below are available; combine them with `mix`, `->offset`, `->rotate`, `->polar`, and `->delay-all-time` to vary each copy.

| Word | Stack | Notes |
| --- | --- | --- |
| `array` | `[template] count quote -- selection` | Reproduce a template object `count` times, running `quote` on each copy with array context. Element 0 is the template itself; the rest are fresh clones (deep for groups). Template is the active object, or a rect/group taken from the stack. Pushes the selection of all copies. |
| `radial` | `[template] count radius quote -- selection` | Like `array`, but each copy is first placed on a circle of `radius` at angle `ordinal/count · tau`, then `quote` runs. |
| `arc` | `[template] count radius start sweep quote -- selection` | Aimed spread: places `count` copies on an arc of `radius`, from angle `start` across `sweep` radians (endpoints inclusive), then runs `quote`. |
| `spiral` | `[template] count r0 r1 turns quote -- selection` | Places `count` copies on a spiral: radius ramps `r0`→`r1` while the angle sweeps `turns · tau`. |
| `phyllotaxis`, `sunflower` | `[template] count scale quote -- selection` | Golden-angle sunflower: copy `i` at angle `i · 137.5°` and radius `scale · √i`. |
| `grid` | `cols rows quote -- selection` | Runs `quote` `cols·rows` times to build fresh cells, with grid context available. Close each cell with `;` like `repeat`. Pushes the selection of created objects. |
| `->split` | `[template] count t quote -- selection` | At time `t` the template vanishes and `count` shards are born at its split-point position (sampled from its tracks). Each shard's flight is authored by `quote` around the origin in split-relative local time (array context available), then placed at the split point. Pushes the shard selection. |
| `->polar` | `angle radius --` | Place the active object on a circle: `x = radius·cos angle`, `y = radius·sin angle`. |
| `f` | `-- number` | Normalized fraction `ordinal/(n-1)` in `[0, 1]`; `0` for a single element. Pairs with `mix`. |
| `f0` | `-- number` | Cyclic fraction `ordinal/n` in `[0, 1)`; handy for rings (`f0 tau *`). |
| `fc` | `-- number` | Centered fraction `2f-1` in `[-1, 1]`. |
| `ic` | `-- number` | Centered index `ordinal - (n-1)/2`, symmetric around 0. |
| `col`, `row` | `-- number` | Current column/row inside `grid`. |
| `cols`, `rows` | `-- number` | Grid dimensions inside `grid`. |
| `colc`, `rowc` | `-- number` | Centered column/row: `col-(cols-1)/2`, `row-(rows-1)/2`. |

```diffstatic {data-lang=text}
# eight petals fanned around a centre, each rotated to face outward
"petal" rect  0.4 1.2 wh
  8 3 '( f0 tau * rot ) radial
;

# a centered grid whose cell alpha ripples with distance from the middle
5 3 '(
  "c" rect  colc 1.4 *  rowc 1.4 *  xy  1 square
    colc abs rowc abs max  0.4 *  1 swap - alpha
  ;
) grid
```

## Data visualization

Dense data is an explicit target for RectSL. A **colormap** turns a value in `[0, 1]` into a perceptually-uniform colour; a **`Data`** value is a plain numeric list you can normalize and bind to size/colour; the **chart words** fold both into ready-made bar charts, heatmaps, and scatter plots that emit ordinary rects — so everything you already know (`each`, `->stagger-time`, `group`, tracks) still applies to the result.

### Colormaps

| Word | Stack | Notes |
| --- | --- | --- |
| `colormap`, `cmap` | `name t -- color` | Look up a named ramp at `t` in `[0, 1]` (clamped). Pushes a `Color` usable anywhere colour is (`solid`, `linear`, `->fill`, `rgb`). Names: `viridis`, `magma`, `inferno`, `plasma`, `turbo`, `coolwarm` (diverging), `grayscale`. |

### Data

| Word | Stack | Notes |
| --- | --- | --- |
| `data` | `quote -- data` | Run `quote` and collect every `Number` it leaves into a `Data` list (`'( 3 1 4 1 5 ) data`, or computed: `'( 8 times [ i i * ] ) data`). |
| `linspace` | `lo hi count -- data` | `count` evenly spaced values from `lo` to `hi` inclusive. |
| `normalize` | `data -- data` | Rescale a list to `[0, 1]` by its own min/max (a flat list maps to `0.5`). |
| `extent` | `data -- min max` | Push the list's minimum then maximum. |
| `dmin`, `dmax` | `data -- number` | Minimum / maximum of a list. |
| `data-len` | `data -- number` | Number of values. |
| `data-at` | `data i -- number` | Value at index `i` (clamped to range). |
| `data-map` | `data quote -- data` | Transform each value through `quote` (the value is on the stack; `i`/`f` array context is available). |

### Chart idioms

| Word | Stack | Notes |
| --- | --- | --- |
| `bars` | `data name -- selection` | Bottom-anchored bar chart spanning the wall; bar height and colour both from the value normalized by the list's extent, coloured with colormap `name`. Pushes the selection of bars. |
| `heat`, `heatmap` | `data cols rows name -- selection` | Row-major grid heatmap filling the wall (row 0 at top); each cell coloured with colormap `name` by its normalized value. Pushes the selection of cells. |
| `scatter` | `xdata ydata name -- selection` | Plot `(->x, ->y)` points as small squares, each axis normalized to the wall; colour follows normalized `->y` through colormap `name`. Pushes the selection of points. |

```diffstatic {data-lang=text}
# a colour legend: 24 thin rects sweeping one colormap across the wall
"swatch" rect  0.6 3 wh
  24 '( fc 11 * 0 offset  "viridis" f colormap solid fill ) array
;

# a bar chart from literal data, staggered fade-in, coloured by value
'( 3 7 2 9 5 8 4 6 ) data "magma" bars
  '( '( 0=0 0.6=1 ) ease-out alpha ) each  0.08 stagger-time

# a heatmap of a computed field (linspace + data-map fill the grid)
0 1 48 linspace  '( 6 * sin 1 + ) data-map  8 6 "turbo" heat

# a scatter plot: y against x, coloured by y
0 1 20 linspace  dup  '( 3 * sin ) data-map  "plasma" scatter

# data helpers: extent / dmin / dmax / data-len / data-at read without consuming
'( 10 20 30 40 ) data
  dup extent drop drop  dup dmin drop  dup dmax drop
  dup data-len drop  dup 2 data-at drop  normalize "coolwarm" bars
```

### Plotting layer

The chart idioms above each normalize by their own extent and fill the wall. The **plotting layer** adds a *shared* coordinate frame so several series overlay in one domain, with axes, ticks, numeric labels, and legends. Set a `->domain` (or let the first series adopt its own extent) and optional `->frame`/`->margins`; then `axes`, the domain-aware series (`line`/`plot`, `area`, `points`), and `colorbar`/`legend` all map through it. The classic `bars`/`heat`/`scatter` are unchanged — the plotting words are additive. RectSL has no text primitive, so tick/colorbar labels are numerals drawn as a 3×5 grid of rects (numbers only — no titles/series-name text).

| Word | Stack | Notes |
| --- | --- | --- |
| `->domain` | `x0 x1 y0 y1 --` | Set an explicit shared x and y data domain. Series and `axes` map data → the plot frame through it. |
| `->xdomain`, `->ydomain` | `lo hi --` | Set just the x (or y) domain. |
| `->margins` | `l r b t --` | Reserve plot-frame margins as wall fractions (left/right/bottom/top) — room for axis labels and a colorbar. |
| `->frame` | `--` | Apply sensible default margins (used only when none are set, so an explicit `->margins` wins). |
| `axes` | `-- selection` | Draw x/y axis rules, nice-number ticks, numeric tick labels (in the margins), and faint gridlines for the current domain. Draw it *before* the series so they paint on top. |
| `line`, `plot` | `xdata ydata name -- selection` | Polyline through the shared domain as rotated thin rects, coloured along colormap `name` by point order. |
| `area` | `xdata ydata name -- selection` | Column-filled area from the `->y = 0` baseline to each sample, coloured by normalized `->y`. |
| `points` | `xdata ydata name -- selection` | Domain-aware scatter (shares the frame with other series); colour follows normalized `->y`. |
| `colorbar` | `name -- selection` | A continuous colour ramp for `name` in the right margin, with min/mid/max numerals from the y domain. |
| `legend` | `count name -- selection` | A key of `count` discrete colour swatches sampling colormap `name`, stacked top-right. |

```diffstatic {data-lang=text}
# one shared domain, axes underneath, three series overlaid, plus legends
wall 16 10 abstract
0 10 xdomain   0 1 ydomain     # or `0 10 0 1 ->domain` in one go
0.12 0.14 0.12 0.06 margins    # or just `->frame` for sensible defaults
axes
0 10 40 linspace  dup '( 0.7 * sin 1 + 2 / ) data-map   "viridis" area
0 10 40 linspace  dup '( 0.55 * cos 1 + 2 / ) data-map  "plasma"  line
0 10 12 linspace  dup '( 0.9 * sin 1 + 2 / ) data-map   "turbo"   points
"viridis" colorbar
5 "turbo" legend
```

## Distribution curves (Functions bridge)

Linear `mix` is rarely the shape you want across an array. RectSL borrows [`afield.stdlib.Functions`](functions_examples.md) — a tested library of easings, distributions, and noise defined over `[0, 1]`, exactly the range of the array fraction `f`. `ease` shapes a scalar/track through the full Penner family; the curve words push a reusable `Fn` value you `sample` at `f` (or any x). The curves are graph-backed, so the Functions rewrite/evolve tooling can optimise an array's distribution toward a target.

| Word | Stack | Notes |
| --- | --- | --- |
| `ease` | `->x name -- ->x` / `track name -- track` | Shape a value through a named Penner easing. `name` is any of `linear`, `quad-in/out/in-out`, `cubic-…`, `quart-…`, `quint-…`, `sine-…`, `expo-…`, `circ-…`, `back-…`, `elastic-…`, `bounce-…`, `smoothstep`, `smootherstep`. A constant eases to a number; a keyed track eases its interpolation. |
| `gaussian` | `-- Fn` | A bell curve centred at `0.5` — bright in the middle of the array, dark at the edges. |
| `noise` | `seed -- Fn` | Seeded value-noise curve — deterministic, reproducible scatter. |
| `fbm` | `seed -- Fn` | Seeded fractal (fBm) noise curve — richer, multi-octave scatter. |
| `sample` | `Fn x -- number` | Evaluate a curve at `x` (use the array fraction `f`). |
| `reflect` | `Fn -- Fn` | Mirror a curve about `0.5`: `reflect(->x) = fn(1-x)`. |
| `tile` | `Fn k -- Fn` | Repeat a curve `k` times across `[0, 1]`. |

```diffstatic {data-lang=text}
# alpha eased across the array with an elastic overshoot
"c" rect  1 square
  9 '( ic 1.4 * 0 offset  f "elastic-out" ease alpha ) array
;

# a gaussian falloff and reproducible noise scatter, sampled at f
"c" rect  0.6 square
  12 '( ic 1.1 * 0 offset  gaussian f sample alpha  7 noise f sample 2 * rotate ) array
;
```

Note: `gaussian`/`noise`/`fbm` here build CPU `Fn` curves on the RectSL stack; the `fbm`/`palette` seen inside `material '( … )` quotes are StackSL GPU words in a separate language — same names, different context.

### Kotlin escape (experimental)

For anything the built-in words don't cover, `{{ … }}` drops into Kotlin — in a `with(Functions){ … }` scope — anywhere a value is expected. The result (a Number, an `Fn`, or a `Track<Double>`) is pushed onto the stack, so the whole `Functions` API is available inline. The escape also sees the array context (`i`, `n`, `f`, `f0`, `fc`, `ic`), so it can vary per copy:

```diffstatic {data-lang=text}
# a curve sampled at f, and a per-copy value computed straight in Kotlin
"c" rect  1 square
  9 '( {{ i * 1.2 }} 0 offset  {{ sin(2) through gain(0.5) }} f sample alpha ) array
;
```

Cost: each unique escape is compiled to a lambda **once** (~76 ms K2 compile + ~5 ms), then **invoked per use** as a plain JVM call — so an escape inside an `array` body pays the compile once, not per copy. It needs a live Kotlin engine — a Field box has one (or pass it via `compileRectSL(source, engine = …)`), so escapes work while live-editing. Inside a long-running graphics render you can't compile on the box's *own* engine (it's busy running the render, and its REPL is single-threaded), so the render harness pre-warms every escape on a separate idle engine before the render starts (`prewarmRectSLEscapes` → `compileRectSL(…, precompiledEscapes = …)`), after which the render only invokes cached lambdas. The CPU docs-gallery smoke check likewise compiles escape-bearing `.rectsl` files with a dedicated engine, so escapes are fully supported in the workspace gallery programs too (see the `inline-functions` example).

## Stack and scalar operators

| Word | Stack | Notes |
| --- | --- | --- |
| `dup` | `a -- a a` | Duplicate top value. |
| `drop` | `a --` | Drop top value. |
| `swap` | `a b -- b a` | Swap top two values. |
| `over` | `a b -- a b a` | Copy second value. |
| `+`, `add` | `a b -- out` | Add constants/tracks. |
| `-`, `sub` | `a b -- out` | Subtract. |
| `*`, `mul` | `a b -- out` | Multiply. |
| `/`, `div` | `a b -- out` | Divide, protecting zero. |
| `mod`, `pow`, `min`, `max` | `a b -- out` | Scalar binary math. |
| `neg`, `negate`, `abs`, `floor`, `ceil`, `fract`, `sqrt`, `sin`, `cos` | `a -- out` | Scalar unary math. |
| `clamp` | `value low high -- out` | Clamp to a range. |
| `sat`, `saturate` | `value -- out` | Clamp to `[0, 1]`. |
| `mix`, `lerp` | `a b t -- out` | Linear interpolation. |
| `smoothstep` | `low high x -- out` | Hermite step. |
| `<`, `lt`, `<=`, `lte`, `>`, `gt`, `>=`, `gte`, `=`, `eq`, `!=`, `<>`, `neq` | `a b -- bool` | Numeric/string comparisons where supported. |
| `and`, `or`, `not` | `bool... -- bool` | Boolean operators using nonzero truth. |
| `pi`, `tau` | `-- number` | Constants. |
