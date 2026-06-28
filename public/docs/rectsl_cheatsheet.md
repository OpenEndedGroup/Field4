# RectSL cheatsheet

RectSL is Field's rectangle scene language. It creates animated rectangle geometry on the CPU, samples exact object state for analysis, and lowers sampled scenes into `afield.four` buffers. StackSL remains the material language: RectSL decides where rectangles are and what object/material metadata they carry; StackSL decides how a material shades.

A RectSL string starts with `#rectsl` when embedded in Kotlin:

```kotlin
import afield.rectsl.*

val source = """
#rectsl
wall 4 4 meters
duration 4
fps 20

"left" rect
  -1 0 xy
  1 square
  '( 0 -1  2 0  4 3 ) x
  '( 0 0   2 1  4 0 ) alpha
;

dup "right" rename
  mirror-x
;
"""

val program = RectAnimationOracle.compile(source).program!!
val frame = program.sample(2.0)
```

## Model

RectSL is a small stack language with scene side effects. Numbers, strings, colors, quotes, selections, rects, groups, paints, and scalar tracks can sit on the stack. Most geometry words apply to the active rect/group or to the current selection.

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
| `wall meters` | `width height --` | Sets physical wall size in meters. |
| `wall pixels` | `width height --` | Sets wall size in pixel units. |
| `wall abstract` | `width height --` | Sets wall size in arbitrary units. |
| `duration` | `seconds --` | Scene duration in seconds. |
| `fps` | `framesPerSecond --` | Sampling/render frame rate. |
| `background` | `color --` or `0xrrggbb background` | Sets scene background. |
| `fit contain` | `--` | Preserve aspect ratio and fit inside target. |
| `fit cover` | `--` | Preserve aspect ratio and cover target. |
| `fit stretch` | `--` | Stretch wall coordinates to target. |

## Objects and groups

| Word | Stack | Notes |
| --- | --- | --- |
| `rect` | `[id] -- rect` | Creates a rect. If the top stack value is a string, it becomes the id. |
| `group` | `[id] -- group` | Opens a group scope. New objects become group children until `end` / `end-group`. |
| `end`, `end-group` | `--` | Closes the current group. |
| `select` | `id -- object` | Selects a rect or group and pushes its reference. |
| `rename` | `name -- object` | Renames the active object or a reference from the stack. |
| `tag` | `name --` | Adds a tag to the active object or current selection. |
| `id` | `-- id` | Pushes the active object id. |
| `rect?` | `-- bool` | `1` when the active object is a rect. |
| `group?` | `-- bool` | `1` when the active object is a group. |
| `tagged?` | `tag -- bool` | Tests the active object for a tag. |
| `copy`, `clone` | `-- object` | Shallow copy of the active object. |
| `copy-deep`, `clone-deep` | `-- object` | Deep copy of a group and its descendants. |
| `group-selected` | `selection [id] -- group` | Reparents selected objects into a new group. |

## Geometry and ordering

| Word | Stack | Notes |
| --- | --- | --- |
| `xy` | `x y --` | Center position in wall coordinates. |
| `nxy` | `nx ny --` | Normalized position in `[-1, 1]` wall space. |
| `x`, `y` | `scalar --` | Constant or animated coordinate. A quote becomes a TCB track. |
| `wh` | `width height --` | Rect size. |
| `nwh` | `nw nh --` | Normalized size relative to wall dimensions. |
| `w`, `h` | `scalar --` | Width or height. |
| `square` | `size --` | Sets width and height together. |
| `rot`, `rotation` | `scalar --` | Rotation in radians. |
| `rotate` | `scalar --` | Adds rotation to the active object/selection. |
| `scale` | `scalar --` | Uniform scale. |
| `scale-xy` | `sx sy --` | Nonuniform scale. |
| `alpha` | `scalar --` | Opacity. |
| `opacity*` | `factor --` | Multiplies opacity. |
| `z` | `scalar --` | Per-rect depth metadata. |
| `layer` | `number|string|track --` | Draw layer. Higher layers render later. |
| `front`, `back` | `--` | Move active object/selection within its layer. |

## Paint and material

| Word | Stack | Notes |
| --- | --- | --- |
| `rgb` | `r g b -- color` | Builds an RGB color from 0..1 channels. |
| `rgba` | `r g b a -- color` | Builds an RGBA color from 0..1 channels. |
| `solid` | `color -- paint` | Solid fill paint. |
| `linear` | `color0 color1 x0 y0 x1 y1 -- paint` | CPU-baked two-color fill across the rect. |
| `fill` | `paint --` | Assigns fill paint. |
| `material name '( ... )` | `--` | Compiles the quote source as a named StackSL rect material. |
| `name material` | `materialName --` | Assigns a previously defined or built-in material to the active rect/selection. |
| `shader` | `x y z w --` | Sets per-rect shader data. |

Material StackSL receives rect/material data from the generated shader harness. Prefer RectSL for geometry and StackSL for fragment math; do not duplicate shape layout inside StackSL unless the shape is genuinely shader-only.

## Tracks

Track quotes are flat `time value` pairs:

```text
'( 0 -1  2 0  4 1 ) linear
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
| `mirror-x`, `mirror-y` | `--` | Mirror around axis 0. |
| `mirror-x-at`, `mirror-y-at` | `axis --` | Mirror around a custom axis. |
| `translate` | `dx dy --` | Translate active object/selection. |
| `offset` | `dx dy --` | Offset position constants/tracks. |
| `stagger` | `dx dy --` | Increasing positional offset over selection order. |
| `delay-all-time` | `seconds --` | Delay every track on active object/selection. |
| `scale-all-time` | `factor --` | Scale every key time. |
| `reverse-all-time` | `--` | Reverse every track across scene duration. |
| `loop-all-time` | `period --` | Loop every track. |
| `pingpong-all-time` | `period --` | Ping-pong every track. |
| `stagger-time` | `stepSeconds --` | Increasing time delay over selection order. |

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

## Verification

Focused checks:

```bash
./gradlew compileKotlinJvm
python3 tools/field_backdoor.py --json --port 9478 \
  --command 'run-jvm-main afield.examples.rectsl.RectSLWorkout'
python3 tools/field_backdoor.py --json --port 9479 \
  --command 'run-jvm-main afield.examples.rectsl.RectSLExamples'
```
