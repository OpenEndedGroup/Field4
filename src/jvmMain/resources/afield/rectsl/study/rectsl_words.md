# RectSL words

Generated from `public/docs/rectsl_cheatsheet.md` by `tools/rectsl_conversation_documents.py`;
do not edit here. Stack effects read `a b -- c`, top of stack on the right; a word whose entry
says it reads `<…>` takes that many literals from the source after it, not from the stack.
Search this guide for a word rather than reading it all.

## Model

RectSL is a small stack language with scene side effects. Numbers, strings, colors, quotes, selections, rects, groups, paints, and scalar tracks can sit on the stack. Most geometry words apply to the active rect/group or to the current selection.

| Syntax | Meaning |
| --- | --- |
| `# comment` | Ignores the rest of the line. |
| `12`, `-0.25`, `1e-3` | Pushes a scalar number. Signed decimals and scientific notation are accepted. |
| `0xffaa33` | Pushes an RGB colour from hexadecimal red, green and blue components, for `solid` to turn into a fill. |
| `"name"` | Pushes a string value. Strings name objects, tags, tracks and palettes according to the following word. |
| `'( ... )` | Stores a block as a quoted value without running it, for a loop, a track constructor or another word that consumes a quote. |
| `: name ... ;` | Defines a reusable word from the enclosed program. |
| `material name '( ... )` | Defines a named fragment material using StackSL source, to be assigned with `->material` once the geometry is arranged. |

## Scene words

Every scene opens by saying how big the wall is, in what units, and how long the clip runs. Below: a wall 1600×900 *pixels* wide, cropped to fill whatever it is drawn into, on a near-black background, four seconds at thirty frames a second — then one gold 200-pixel square in the middle of it. Change `pixels` to `meters` and nothing about the geometry changes; the unit is a statement about what the numbers mean, which the renderer and the `afield.four` lowering both read.

```rectsl
#rectsl
->wall 1600 900 pixels    # or `12 8 meters`, or `12 8 abstract`
->fit cover               # contain | cover | stretch
->background 0x0a0a12
->duration 4
->fps 30

"mark" rect  0 0 ->xy  200 ->square  0xffcc33 solid ->fill ;
```

| Word | Reads | Notes |
| --- | --- | --- |
| `->wall meters` | `->wall <w> <h> meters` | Physical wall size in meters. `meter`/`m` also accepted. |
| `->wall pixels` | `->wall <w> <h> pixels` | Wall size in pixel units. `pixel`/`px` also accepted. |
| `->wall abstract` | `->wall <w> <h> abstract` | Wall size in arbitrary units. `units`/`unit` also accepted. |
| `->duration` | `->duration <seconds>` | Scene duration in seconds. |
| `->fps` | `->fps <framesPerSecond>` | Sampling/render frame rate. |
| `->background` | `->background <0xrrggbb>`, or `color ->background` | Sets scene background. |
| `->fit contain` | `->fit contain` | Preserve aspect ratio and fit inside target. |
| `->fit cover` | `->fit cover` | Preserve aspect ratio and cover target. |
| `->fit stretch` | `->fit stretch` | Stretch wall coordinates to target. |

## Objects and groups

A scene is a tree of named rects and groups, and the names are how you get back to anything after you have moved on. Below: a group `orbit` holding two squares — one authored, one a `clone` of it shifted along +x, both tagged `petal`. Then the whole group is deep-copied into a `twin` three units lower, and finally every `petal` anywhere in the scene (four of them now, two per copy) is gathered out of its existing parent into a new group `petals`.

```rectsl
"orbit" group
  "cell" rect  0 0 ->xy  1.2 ->square  "petal" ->tag ;
  "cell" ->select clone  2.5 0 ->xy ;
end

"orbit" ->select copy-deep  "twin" ->rename  0 -3 ->translate ;
all rects  '( "petal" tagged? ) filter  "petals" ->group-selected ;
```

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

Three rects, three different ways of being animated. `mark` sets each channel by hand: it slides from x=-5 to x=+5 while turning a full revolution, at eight-tenths scale and seven-tenths opacity, on draw layer 2 and at the front of it — and it exists only between t=0.5 and t=3.5. Wherever a channel takes a number it also takes a quote, and the quote becomes a keyed track; that is the whole of how motion is written.

```rectsl
"mark" rect
  0 0 ->xy  2 0.6 ->wh
  '( 0=-5  4=5 ) ->x              # a quote in a channel is a track
  '( 0=0   4=6.28318 ) ->rot
  0.8 ->scale  0.7 ->alpha
  2 ->layer  0.2 ->z  ->front
  0.5 ->appear  3.5 ->vanish
;

"dart" rect  1.1 0.26 ->wh       # drawn along its own +x, so it reads as an arrow
  '( 0=-5  4=5 ) ->x  '( 0=-3  2=3  4=-3 ) ->y
  ->face-travel                   # rotation taken from its own travel
;

"bullet" rect  0.42 ->square  '( 0=-8  2.5=8 ) ->x
  24 '( -3 f 6 * + ->y   i 0.18 *  2.5 ->live ) array
;
```

| Word | Stack | Notes |
| --- | --- | --- |
| `->xy` | `x y --` | Sets the object centre in wall coordinates. The wall’s declared units determine the meaning of x and y. |
| `->nxy` | `nx ny --` | Sets the centre using normalized wall coordinates in `[-1,1]`. |
| `->x`, `->y` | `scalar --` | Constant or animated coordinate. A quote becomes a TCB track. |
| `->wh` | `width height --` | Sets width and height in wall coordinates. |
| `->nwh` | `nw nh --` | Sets width and height relative to the wall dimensions. |
| `->w`, `->h` | `scalar --` | Sets the width or height channel to a scalar or track. This changes dimensions independently of the object’s scale transform. |
| `->square` | `size --` | Sets width and height together. |
| `->rot`, `->rotation` | `scalar --` | Sets rotation in radians. A track animates the angle over time. |
| `->rotate` | `scalar --` | Adds rotation to the active object/selection. |
| `->face-travel` | `--` | Auto-orient: sets rotation to the heading of the object's own x/y tracks (local +x points along travel). |
| `->face-travel-at` | `offset --` | Like `->face-travel`, but adds a fixed angle offset (correct for how the art is drawn). |
| `->scale` | `scalar --` | Sets uniform scale on the active object or selection. |
| `->scale-xy` | `sx sy --` | Sets separate horizontal and vertical scale factors. |
| `->alpha` | `scalar --` | Sets the opacity channel. Supply a track to fade the object over time. |
| `->opacity*` | `factor --` | Multiplies the existing opacity by a factor, so relative fades survive dimming a selection. |
| `->appear` | `t --` | Hard-gate visibility on: invisible before `t`, unchanged from `t`. |
| `->vanish` | `t --` | Hard-gate visibility off: unchanged before `t`, invisible from `t`. |
| `->live` | `birth life --` | Emitter primitive: play the object's animation from `birth` (local time 0 = `birth`) and show it only during `[birth, birth+life]`. |
| `->z` | `scalar --` | Sets per-rectangle depth metadata. Use draw layers and ordering words when the intended change is paint order. |
| `->layer` | `number|string|track --` | Draw layer. Higher layers render later. |
| `->front`, `->back` | `--` | Move active object/selection within its layer. |

## Paint and material

A rect is filled in one of three ways, and all three are here. `panel` takes a flat mid-blue built channel-by-channel, and places itself in normalised wall space — dead centre, four-fifths of the wall wide and two-fifths tall — rather than in wall units. `chip` takes a gold-to-magenta ramp baked across its own width on the CPU, *and* a StackSL material `tint` on top of it, fed four numbers of its own through `->shader` that the shader body can read. `ghost` shows the shorthand: a colour with alpha in it, no track needed.

```rectsl
material tint '(
  uv 4.0 * sin abs
)

"panel" rect  0.5 0.5 ->nxy  0.8 0.4 ->nwh
  rgb 0.12 0.34 0.62  solid ->fill
;

"chip" rect  0 -2.6 ->xy  1 ->square
  0xffcc33 0xff2266  -0.5 0  0.5 0  linear ->fill   # two-colour ramp across the rect
  0.1 0.2 0.5 0 ->shader                            # per-rect data its material reads
  tint ->material
;

"ghost" rect  3 -2.6 ->xy  1 ->square  rgba 0.9 0.4 0.2 0.5 solid ->fill ;
```

| Word | Stack / reads | Notes |
| --- | --- | --- |
| `rgb` | `rgb <r> <g> <b>` → `-- color` | Builds an RGB color from three 0..1 literals. |
| `rgba` | `rgba <r> <g> <b> <a>` → `-- color` | Builds an RGBA color from four 0..1 literals. |
| `solid` | `color -- paint` | Wraps a colour as a solid paint value. Assign it with `->fill` to colour a rectangle. |
| `linear` | `color0 color1 x0 y0 x1 y1 -- paint` | CPU-baked two-color fill across the rect. |
| `->fill` | `paint --` | Assigns the paint on the stack to the active rectangle or selection. The paint can be solid or a two-colour ramp. |
| `material` | `material <name> '( ... )` | Compiles the quote source as a named StackSL rect material. |
| `name ->material` | `materialName --` | Assigns a material defined earlier in the program, or a built-in one, to the active rect/selection. |
| `->shader` | `x y z w --` | Stores four per-rectangle values for the material shader. Their meaning is chosen by the StackSL material that reads them. |

## Tracks

Track quotes are flat `time value` pairs. An optional `=` may sit between the time and value of a key to read the pairing aloud: `0=0.2` means "0.2 at time 0". The two forms are interchangeable and may be mixed:

```rectsl
linear-track "rise" '( 0=-3  2=0  4=3 )
hold-track "pulse" '( 0=0.2  2=1  3=0.45 )

"bar" rect  0 0 ->xy  0.7 1.8 ->wh
  "rise" track-ref ->y
  "pulse" track-ref smooth ->alpha
  '( 0=0.6  2=1.6  4=0.6 ) tcb  -0.4 0 0 tcb!  ->scale
  alpha-track  0.1 value+  0 1 clamp-values  ->alpha   # read it back, reshape, reassign
;
```

| Word | Stack / reads | Notes |
| --- | --- | --- |
| `tcb` | `quote -- track` | Smooth scalar track using TCB interpolation. |
| `linear` | `quote -- track` | Linear scalar track. |
| `hold` | `quote -- track` | Step/hold scalar track. |
| `tcb!` | `track tension continuity bias -- track` | Sets default TCB parameters. |
| `track` | `track <name> '( ... )` | Defines a named TCB track. |
| `linear-track` | `linear-track <name> '( ... )` | Defines a named linear track. |
| `hold-track` | `hold-track <name> '( ... )` | Defines a named hold track. |
| `track-ref` | `name -- track` | Pushes a named track for reuse. |
| `x-track`, `y-track` | `-- track` | Pushes the active x/y track. |
| `w-track`, `h-track` | `-- track` | Pushes width/height tracks. |
| `rot-track`, `rotation-track` | `-- track` | Pushes rotation track. |
| `scale-x-track`, `scale-y-track` | `-- track` | Pushes scale tracks. |
| `alpha-track` | `-- track` | Pushes alpha track. |
| `z-track`, `layer-track` | `-- track` | Pushes ordering tracks. |

## Track transforms

A track is a value, so it can be bent before it is assigned. All five rects below ride the *same* three-key sweep from x=-8 to x=+8; only the time axis differs. `delayed` starts a second and a half late, `slower` spaces its keys 1.4× farther apart, `bounced` plays forward then backward over a two-second period, and `eased` shapes the interpolation with a Penner curve and then loops it. Stacked in rows, one motion falls into a round — which is the cheapest way to see what a retiming word actually did.

```rectsl
"plain"   rect  0  3 ->xy  1 ->square  '( 0=-8  2=0  4=8 ) ->x ;
"delayed" rect  0  1 ->xy  1 ->square  '( 0=-8  2=0  4=8 ) 1.5 delay-time ->x ;
"slower"  rect  0 -1 ->xy  1 ->square  '( 0=-8  2=0  4=8 ) 1.4 time* ->x ;
"bounced" rect  0 -3 ->xy  1 ->square  '( 0=-8  1=8  2=-8 ) 2 pingpong-time ->x ;
"eased"   rect  0 -5 ->xy  1 ->square  '( 0=-8  2=8 ) ease-in-out 2 loop-time ->x ;
```

| Word | Stack | Notes |
| --- | --- | --- |
| `delay-time` | `track seconds -- track` | Moves every key later by the supplied number of seconds. The value curve is preserved. |
| `scale-time` | `track factor -- track` | Multiplies key times by a factor. A factor above one makes the same motion occupy more time. |
| `reverse-time` | `track -- track` | Reverses the track’s key times. Use it to traverse an existing motion backwards. |
| `loop-time` | `track period -- track` | Repeats a track over the supplied period in seconds. The original track remains the motion sampled within each period. |
| `pingpong-time` | `track period -- track` | Alternates forward and backward traversal of the track. Use it to avoid an abrupt jump from the end back to the start. |
| `time+` | `track seconds -- track` | Shifts the track’s key times by a number of seconds. It is the compact form of a time offset. |
| `time*` | `track factor -- track` | Scales the track’s key times by a factor. Use it to retime the same key values. |
| `snap-time` | `track grid -- track` | Snaps key times to the supplied time grid. This quantizes timing rather than key values. |
| `value+` | `track amount -- track` | Adds the supplied amount to every key value. Timing is preserved while the whole curve moves up or down. |
| `value*` | `track factor -- track` | Multiplies every key value by the supplied factor. Timing is preserved while the amplitude changes. |
| `clamp-values` | `track low high -- track` | Clamps key values between low and high. Use it after a transform when opacity or another bounded channel must stay in range. |
| `keys-before` | `track time -- track` | Keeps the keys at or before the supplied time. Use it to select an early portion of a track. |
| `keys-after` | `track time -- track` | Keeps the keys at or after the supplied time. Use it to discard an earlier part of a track. |
| `keys-between` | `track start end -- track` | Keeps keys in the supplied start/end window. The arguments are times rather than key indices. |
| `ease-in` | `scalar -- track` | Builds quadratic ease-in timing from the scalar input. Motion begins slowly and accelerates. |
| `ease-out` | `scalar -- track` | Builds quadratic ease-out timing. Motion decelerates toward its endpoint. |
| `ease-in-out` | `scalar -- track` | Builds symmetric ease-in and ease-out timing. Motion is slow at both ends and faster in the middle. |
| `ease-smoothstep`, `smooth` | `scalar -- track` | Uses Hermite smoothstep timing. `smooth` is the short alias. |

## Selection and transforms

Build first, then sort the scene out by logic. `pip` is one spinning square tagged `spark`, reproduced nine times by `array` — each copy's opacity set from its centred fraction, so the row is brightest at the ends — and then spread 1.4 units apart by `->stagger`. The next four lines never name an individual rect again: a mirrored clone is folded across the axis x=3 and pushed to the front of its layer; every rect in the scene is dimmed a further 10%; every object *tagged* `spark` is scaled up; and the original's whole timeline is made to loop every two seconds.

```rectsl
"pip" rect  0 0 ->xy  0.8 ->square  "spark" ->tag
  '( 0=0  4=6.28318 ) ->rot
  9 '( fc abs 0.7 *  0.3 +  ->alpha ) array
  1.4 0 ->stagger
;

"pip" ->select clone  "mirrored" ->rename  3 ->mirror-x-at  ->front ;
all rects  '( 0.9 ->opacity* ) each ;
all  '( "spark" tagged? ) filter  '( 1.2 ->scale ) map ;
"pip" ->select  2 ->loop-all-time ;
```

| Word | Stack | Notes |
| --- | --- | --- |
| `all` | `-- selection` | Selects every scene node. Follow with `rects`, `groups` or a predicate to narrow the selection. |
| `roots` | `-- selection` | Selects only top-level scene nodes. Children remain reachable through their groups. |
| `selected` | `-- selection` | Pushes the current active object or selection. This makes it available to words that explicitly consume a selection. |
| `children` | `selection -- selection` | Replaces a group selection with its direct children. Use `descendants` when deeper nested objects should also be included. |
| `descendants` | `selection -- selection` | Selects every descendant of the selected groups. Unlike `children`, it traverses the nested hierarchy. |
| `rects`, `groups` | `selection -- selection` | Filters the selection to rectangles or, with `groups`, to groups. Other node types are removed from the selection. |
| `tagged` | `selection tag -- selection` | Keeps selected objects carrying the supplied tag. Push the tag string after the selection. |
| `each` | `selection quote -- selection` | Runs a quote on each selected object and returns the selection. Use it to apply a shared edit to many objects. |
| `map` | `selection quote -- selection` | Runs a mapping quote over the selected objects. The selection remains the unit passed between scene operations. |
| `filter`, `where` | `selection quote -- selection` | Keeps objects for which the quote returns true. `where` is the equivalent spelling. |
| `->mirror-x`, `->mirror-y` | `--` | Mirrors about the zero x or y axis. Use the corresponding `-at` form for an axis away from the origin. |
| `->mirror-x-at`, `->mirror-y-at` | `axis --` | Mirrors about the supplied axis coordinate. This places the reflection relative to an explicit line in the scene. |
| `->translate` | `dx dy --` | Translates the active object or selection by dx and dy. Push the horizontal offset before the vertical one. |
| `->offset` | `dx dy --` | Adds offsets to the object’s position constants or tracks. Animated motion keeps its shape but moves in space. |
| `->stagger` | `dx dy --` | Adds increasing positional offsets in selection order. Each next object receives another dx and dy step. |
| `->delay-all-time` | `seconds --` | Shifts all tracks on the object or selection later by the supplied seconds. Their relative timing stays together. |
| `->scale-all-time` | `factor --` | Multiplies every key time by the supplied factor. Values above one spread keys farther apart in time. |
| `->reverse-all-time` | `--` | Reverses every track across the scene duration. This keeps the object’s channels synchronized while reversing the motion. |
| `->loop-all-time` | `period --` | Repeats all tracks with the supplied period. The period is in seconds. |
| `->pingpong-all-time` | `period --` | Alternates forward and backward playback of all tracks. The supplied period controls the repeated time mapping. |
| `->stagger-time` | `stepSeconds --` | Adds increasing delays in selection order. The step is measured in seconds per object. |

## Loops and blocks

Three loops, three shapes of the same idea. `times` runs its body eight times with the index in `i`, so the first row is eight cells spread 2.2 units apart — and their opacity comes out of a *predicate* rather than a curve: cells 2 through 6 are dimmed, the rest bright, written with comparisons and `not`. `range` counts over an explicit interval. `repeat` does the same but reaches for `loop-count` to normalise its index into `[0, 1]` first, which is how a loop places things by fraction rather than by step.

```rectsl
8 '(
  "cell" rect  i 3.5 -  2.2 *  3 ->xy  1.2 ->square
    i 2 >=  i 6 <=  and  not  0.55 *  0.45 +  ->alpha
    '( 0.9 ->opacity* ) apply
) times

0 6 '( "dot" rect  i 2 * 6 -  0 ->xy  0.6 ->square ) range

4 '( "tick" rect  i loop-count 1 - /  8 *  4 -  -3 ->xy  0.5 ->square ) repeat
```

| Word | Stack | Notes |
| --- | --- | --- |
| `apply` | `quote --` | Evaluates the quoted block immediately. This executes the code stored by a quote. |
| `defer` | `quote -- quote` | Leaves a quote on the stack unchanged. Quotes are already deferred values, so this is an explicit marker rather than another execution mode. |
| `times` | `count quote --` | Runs quote `count` times and pushes the index before each run. |
| `repeat` | `count quote --` | Runs quote `count` times. Use `i`, `n`, or `loop-count` inside. |
| `range` | `start end quote --` | Runs quote for each integer in `[start, end)`, or descending when `end < start`. |
| `i` | `-- number` | Pushes the current loop index or range value. Read it inside a loop to vary each iteration. |
| `loop-count`, `n` | `-- number` | Pushes the current loop’s count. `n` is the shorter alias. |

## Array primitives

Array primitives *reproduce a subexpression with modification*: the Fischinger idea of one shape multiplied into a rhythm of copies, each shifted in space, time, size, or colour. Inside any `repeat`/`range`/`array`/`grid`/`radial` body the per-copy context words below are available; combine them with `mix`, `->offset`, `->rotate`, `->polar`, and `->delay-all-time` to vary each copy.

```rectsl
# eight petals fanned around a centre, each rotated to face outward
"petal" rect  0.4 1.2 ->wh
  8 3 '( f0 tau * ->rot ) radial
;

# a centered grid whose cell alpha ripples with distance from the middle
5 3 '(
  "c" rect  colc 1.4 *  rowc 1.4 * ->xy  1 ->square
    colc abs rowc abs max  0.4 *  1 swap -  ->alpha
  ) grid
```

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

## Data visualization

Dense data is an explicit target for RectSL. A **colormap** turns a value in `[0, 1]` into a perceptually-uniform colour; a **`Data`** value is a plain numeric list you can normalize and bind to size/colour; the **chart words** fold both into ready-made bar charts, heatmaps, and scatter plots. All three emit ordinary rects: `each`, `->stagger-time`, `group` and tracks all still apply to the result.

```rectsl
# a colour legend: 24 thin rects sweeping one colormap across the wall
"swatch" rect  0.6 3 ->wh
  24 '( fc 11 *  0 ->offset  "viridis" f colormap solid ->fill ) array
;

# a bar chart from literal data, staggered fade-in, coloured by value
'( 3 7 2 9 5 8 4 6 ) data  normalize  "magma" bars
  '( '( 0=0 0.6=1 ) ease-out ->alpha ) each
  0.08 ->stagger-time

# a heatmap of a computed field (linspace + data-map fill the grid)
0 1 48 linspace  '( 6 * sin 1 + ) data-map  8 6 "turbo" heat

# a scatter plot: y against x, coloured by y
0 1 20 linspace  dup  '( 3 * sin ) data-map  "plasma" scatter

# data helpers: extent / dmin / dmax / data-len / data-at read without consuming
'( 10 20 30 40 ) data
  dup extent drop drop  dup dmin drop  dup dmax drop
  dup data-len drop  dup 2 data-at drop  normalize "coolwarm" bars
```

#### Colormaps

| Word | Stack | Notes |
| --- | --- | --- |
| `colormap`, `cmap` | `name t -- color` | Look up a named ramp at `t` in `[0, 1]` (clamped). Pushes a `Color` usable anywhere colour is (`solid`, `linear`, `->fill`, `rgb`). Names: `viridis`, `magma`, `inferno`, `plasma`, `turbo`, `coolwarm` (diverging), `grayscale`. |

#### Data

| Word | Stack | Notes |
| --- | --- | --- |
| `data` | `quote -- data` | Run `quote` and collect every `Number` it leaves into a `Data` list (`'( 3 1 4 1 5 ) data`, or computed: `'( 8 times [ i i * ] ) data`). |
| `linspace` | `lo hi count -- data` | `count` evenly spaced values from `lo` to `hi` inclusive. |
| `normalize` | `data -- data` | Rescale a list to `[0, 1]` by its own min/max (a flat list maps to `0.5`). |
| `extent` | `data -- min max` | Push the list's minimum then maximum. |
| `dmin`, `dmax` | `data -- number` | Returns the minimum value in a data list; `dmax` returns the maximum. These reduce the list to one number. |
| `data-len` | `data -- number` | Pushes the number of values in the data list. Use it to drive loops over the collected samples. |
| `data-at` | `data i -- number` | Reads the sample at the supplied index. Out-of-range indices are clamped to the available range. |
| `data-map` | `data quote -- data` | Transform each value through `quote` (the value is on the stack; `i`/`f` array context is available). |

#### Chart idioms

| Word | Stack | Notes |
| --- | --- | --- |
| `bars` | `data name -- selection` | Bottom-anchored bar chart spanning the wall; bar height and colour both from the value normalized by the list's extent, coloured with colormap `name`. Pushes the selection of bars. |
| `heat`, `heatmap` | `data cols rows name -- selection` | Row-major grid heatmap filling the wall (row 0 at top); each cell coloured with colormap `name` by its normalized value. Pushes the selection of cells. |
| `scatter` | `xdata ydata name -- selection` | Plot `(->x, ->y)` points as small squares, each axis normalized to the wall; colour follows normalized `->y` through colormap `name`. Pushes the selection of points. |

#### Plotting layer

The chart idioms above each normalize by their own extent and fill the wall. The **plotting layer** adds a *shared* coordinate frame so several series overlay in one domain, with axes, ticks, numeric labels, and legends. Set a `->domain` (or let the first series adopt its own extent) and optional `->frame`/`->margins`; then `axes`, the domain-aware series (`line`/`plot`, `area`, `points`), and `colorbar`/`legend` all map through it. `bars`, `heat` and `scatter` do not read that frame; they keep normalizing by their own extent, and the two sets of words sit side by side in one scene. RectSL has no text primitive, so tick and colorbar labels are numerals drawn as a 3×5 grid of rects: numbers only, no titles or series names.

```rectsl
#rectsl
->wall 16 10 abstract
->duration 3
->fps 30

# one shared domain, axes underneath, three series overlaid, plus legends
0 10 ->xdomain   0 1 ->ydomain      # or `0 10  0 1 ->domain` in one go
0.12 0.14 0.12 0.06 ->margins       # or just `->frame` for sensible defaults
axes
0 10 40 linspace  dup '( 0.7 * sin 1 + 2 / ) data-map   "viridis" area
0 10 40 linspace  dup '( 0.55 * cos 1 + 2 / ) data-map  "plasma"  line
0 10 12 linspace  dup '( 0.9 * sin 1 + 2 / ) data-map   "turbo"   points
"viridis" colorbar
5 "turbo" legend
```

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

## Musicality — the pattern language as scene structure

RectSL's array primitives distribute copies in *space*. The musical layer distributes them in *time*, by borrowing [Field's Tidal-inspired pattern language](pattern_language.md) whole. The two fit together because they are already the same shape. A RectSL track is `sample(t)`; a pattern is `events(arc)`. Both are pure functions of time, with no simulation state. So a pattern **is** a track, and a pattern event, which carries an onset and a real duration, **is** a `->live` window.

#### Clock

A cycle is the pattern language's bar. `->cps` states cycles per second directly; `->tempo` states it as a musician would. The two spellings of tempo commute: `->tempo` remembers its bpm, so `->beats-per-cycle` re-derives from it whichever order they appear in.

```rectsl
->tempo 120          # a 4/4 bar at two seconds
->beats-per-cycle 4
->duration-cycles 4  # …so the piece is eight seconds long, unwritten

"beat" rect  0.6 ->square  1 beats 8 *   2 cycles 4 * ->xy ;
"cyc"  rect  0.6 ->square  cycle-secs 4 *  scene-cycles 1 - ->xy ;
```

| Word | Stack / reads | Notes |
| --- | --- | --- |
| `->cps` | `->cps <n>` | Cycles per second. Default `1`, so a cycle is a second and a scene that never mentions the clock is unaffected. |
| `->tempo` | `->tempo <bpm>` | Beats per minute; `cps = bpm / 60 / beatsPerCycle`. |
| `->beats-per-cycle` | `->beats-per-cycle <n>` | Beats in a cycle. Four (a 4/4 bar) by default. |
| `->duration-cycles` | `->duration-cycles <n>` | Scene duration in cycles rather than seconds. Set the tempo first. |
| `beats` | `n -- seconds` | Converts a beat count to seconds using the scene clock. Set tempo before converting durations. |
| `cycles` | `n -- seconds` | Converts a cycle count to seconds using the scene clock. The result can be assigned to an ordinary time channel. |
| `cycle-secs` | `-- seconds` | Pushes the duration of one cycle in seconds. It is the reciprocal of cycles per second. |
| `scene-cycles` | `-- number` | Pushes the scene’s total duration in cycles. This combines scene duration with the current clock. |

#### `pat`: one copy per event

`pat` is `array` indexed by rhythm instead of by ordinal. `"bd*4"` is four events a bar, so over four bars the `kick` template below becomes sixteen copies — one per strike, each alive for exactly its own quarter-note and no longer. Its body is authored in *local* time, where 0 is that copy's own birth: every strike drops from y=5.5 to 0 over 0.34 s and flashes up and out over half a second. `step` is the index within the bar, so the sixteen strikes also march across the wall and restart at each bar line.

```rectsl
->tempo 120
->duration-cycles 4

# one template, sixteen strikes: four a bar, each alive for its own quarter
"kick" rect  1.3 ->square  0xff5a3c solid ->fill
  "bd*4" '(
      step 1.5 -  4.2 * ->x
      '( 0=5.5  0.34=0 ) ease-out ->y
      '( 0=0  0.10=1  0.5=0 ) linear ->alpha
  ) pat
;

# the per-event context: which atom, how long, which cycle, what it carries
"ctx" rect  0.5 ->square
  "bd:2 sd cp*2" '(
      t 2 * 9 - ->x
      cyc 2 *  step 0.4 *  +  3 - ->y
      "n" param 0.1 *  0.4 + ->alpha
      "bd:2" atom? 0.4 * ->rotate
      dur 3 *  0.3 + ->scale
      atom ->tag
  ) pat
;
```

| Word | Stack | Notes |
| --- | --- | --- |
| `pat` | `[template] pattern quote -- selection` | Reproduce the template **once per event**, each copy `->live` for exactly that event's window. The quote authors the copy's flight in *local* time (0 is its own birth), like `->split`. Anything the quote creates is given the same window, so `pat` composes with `array`, `grid` and `->split`. An all-rests pattern shows nothing. |
| `t` | `-- seconds` | Pushes the current pattern event’s onset in seconds. Use it inside the quote run for that event. |
| `dur` | `-- seconds` | Pushes the whole event’s duration in seconds. A clipped event fragment does not change its whole duration. |
| `cyc` | `-- number` | Pushes the cycle containing the event’s onset. Use it to vary a pattern across repeated bars. |
| `step` | `-- number` | Pushes the event index within its cycle. The index restarts at the next cycle. |
| `atom` | `-- string` | The event's mini-notation value: `bd`, `808:4`, `c'maj7`. |
| `atom?` | `name -- bool` | `1` when this event's atom is `name`. |
| `param` | `key -- number` | A named parameter carried on the event (`gain`, `pan`, `n`, `note`, …). |
| `param?` | `key -- bool` | `1` when the event carries that parameter. |

#### Patterns as values

`mini` turns text into a rhythm; the signal words build continuous patterns. Both coerce to a track wherever RectSL wants one: `sine ->alpha` and `"0 0.25 0.5" ->rot` need no conversion word. A bare string is read as mini-notation **only when it contains numbers**, so a misplaced name still errors rather than silently becoming zero.

```rectsl
->tempo 90
->duration-cycles 4

"smooth"    rect  1 ->square   sine 12 * 6 -  ->x   3.4 ->y   sine tau * ->rot ;
"stepped"   rect  1 ->square   sine 8 segment  12 *  6 -  ->x   1.6 ->y ;
"steps"     rect  0.9 ->square  "0 3 -3 6" ->x   -0.2 ->y ;   # a string with numbers is mini-notation
"held"      rect  0.9 ->square  "-6 ~ 6" mini nums hold-pat ->x   -2 ->y ;
"remapped"  rect  0.8 ->square  tri -5 5 pat-range ->x   -3.6 ->y ;
"quantised" rect  0.8 ->square  saw 0.25 quantise 16 * 8 -  ->x   -4.6 ->y ;
"noisy"     rect  0.8 ->square  7 perlin bipolar 6 *  ->x   4.4 ->y ;
```

| Word | Stack | Notes |
| --- | --- | --- |
| `mini` | `text -- pattern` | Parses the text on the stack as pattern mini-notation. The result is a pattern value for later transformation or placement. |
| `sine`, `cosine`, `saw`, `isaw`, `tri`, `square` | `-- signal` | Continuous signals over `[0,1]`, one cycle long. |
| `rand`, `perlin` | `seed -- signal` | Deterministic noise — granularity-free, identical on any machine. |
| `nums` | `pattern -- signal` | Read a rhythm's atoms as numbers. |
| `texts` | `signal -- pattern` | Converts numeric signal values to text pattern atoms. This is the reverse direction of `nums`. |
| `segment` | `signal n -- signal` | Sample a continuous signal into `n` events per cycle — how a smooth curve becomes stepped. |
| `pat-range` | `signal lo hi -- signal` | Remap `[0,1]` to `[lo,hi]`. (`range` is already the loop word.) |
| `quantise`, `quantize` | `signal step -- signal` | Round values to a grid. |
| `bipolar` | `signal -- signal` | `[0,1]` to `[-1,1]`. |
| `euclid` | `pulses steps rotation -- pattern` | A Euclidean rhythm as events — the onsets only, not a mask. |
| `kolakoski` | `steps rotation -- pattern` | A Kolakoski rhythm as events. The terms of the self-describing word `1 2 2 1 1 2 …` read as alternating on/off run lengths, so the figure's own run lengths spell the word back: `x..xx.x..x..xx.x` over sixteen steps. |
| `kolakoski-gaps` | `steps rotation -- pattern` | `kolakoski`, reading each term as the gap to the next onset instead of a run length: `xx.x.xxx.xx.x.xx` — eleven onsets in sixteen steps against the run reading's eight, and never more than one rest between hits. |
| `hold-pat` | `signal -- track` | Sample-and-hold: a rest keeps the previous value instead of falling to zero. Pure — it looks backwards, it does not remember. |

#### Pattern transforms

Time transforms that work on rhythms and signals alike. Repetition is cheap; repetition that *differs* is what reads as alive, and these are the operators that make bar `n+1` unlike bar `n`.

```rectsl
->cps 1
->duration-cycles 8

"plain"     rect  0.7 ->square  "bd*3" '( f0 14 * 7 -  4.2 ->xy ) pat ;
"faster"    rect  0.7 ->square  "bd*3" mini 3 '( 2 fast ) every '( f0 14 * 7 -  2.4 ->xy ) pat ;
"walking"   rect  0.7 ->square  "bd sd hh cp" mini 4 iter '( f0 14 * 7 -  0.6 ->xy ) pat ;
"echoed"    rect  0.7 ->square  "bd*3" mini 1 8 '( 2 ply ) off '( f0 14 * 7 -  -1.2 ->xy ) pat ;
"swung"     rect  0.7 ->square  "bd*8" mini 4 swing '( f0 14 * 7 -  -3 ->xy ) pat ;
"sometimes" rect  0.7 ->square  "bd*4" mini 0.5 '( press ) sometimes-by '( f0 14 * 7 -  -4.6 ->xy ) pat ;
```

| Word | Stack | Notes |
| --- | --- | --- |
| `fast`, `slow` | `pattern n -- pattern` | Absolute-time, so `slow` spans cycles rather than dropping material. |
| `rev`, `palindrome` | `pattern -- pattern` | Reverse; reverse every other cycle. |
| `iter`, `iter-back` | `pattern n -- pattern` | Rotate the bar one `1/n` further each cycle. The walking feel, with no quote needed. |
| `ply` | `pattern n -- pattern` | Repeat each event `n` times in its own slot. |
| `stut` | `pattern n gapDen -- pattern` | `n` strikes per event, a `1/gapDen` of a cycle apart. |
| `linger` | `pattern num den -- pattern` | Repeat the first `num/den` of the cycle for the whole cycle. |
| `pat-rot` | `pattern n -- pattern` | Rotate values against the rhythm, keeping the onsets. |
| `shuffle` | `pattern n -- pattern` | Deterministically reorder `n` slots per cycle. |
| `degrade-by` | `pattern p -- pattern` | Drop events with probability `p`, deterministically. |
| `swing`, `swing-by` | `pattern n -- pattern` / `pattern num den n -- pattern` | Delay every second subdivision of a `2n` grid, in exact rational time — swung but still quantised. |
| `press`, `press-by` | `pattern -- pattern` / `pattern num den -- pattern` | Squeeze each event into the later part of its own slot: four-on-the-floor becomes off-beat without touching the grid. |
| `pat-delay` | `pattern num den -- pattern` | Shift by `num/den` of a cycle. |
| `every` | `pattern k quote -- pattern` | Apply the quote only on cycles `0, k, 2k, …`. |
| `off` | `pattern num den quote -- pattern` | Superimpose a copy shifted `num/den` of a cycle, transformed by the quote. |
| `superimpose` | `pattern quote -- pattern` | Superimpose a transformed copy, unshifted. |
| `inside`, `outside` | `pattern n quote -- pattern` | Apply the quote at a slower / faster time base. |
| `sometimes-by` | `pattern p quote -- pattern` | Transform a proportion `p` of events. The two halves partition exactly — nothing is both transformed and untouched, nothing is dropped. |
| `stack-pat`, `cat-pat` | `a b -- pattern` | Play together; play one per half-cycle. |

#### Variation on a selection

These apply the same ideas to scene objects rather than to rhythms, which makes them useful on material that has nothing to do with patterns.

```rectsl
->tempo 90
->duration-cycles 4

"flash" rect  16 9 ->wh  0 0 ->xy  0xffffff solid ->fill  0 ->alpha  ->back
  selected 4 '( 0.14 ->alpha ) ->every      # only on every fourth bar
;
"twin" rect  0.7 ->square  5 2.6 ->xy
  selected '( 0.45 ->alpha ) ->jux           # a mirrored, dimmer partner
;
"echo" rect  0.6 ->square  '( 0=-8  4=8 ) linear ->x  0.4 ->y
  selected 0.5 '( 0.4 ->alpha ) ->off        # a delayed, transformed copy
;
"row" rect  0.5 ->square  -7 -5.4 ->xy
  8 '( ic 1.6 * 0 ->offset ) array
  0.12 0.6 ->stagger-swing
  0.25 ->degrade
;
```

| Word | Stack | Notes |
| --- | --- | --- |
| `->every` | `selection k quote -- selection` | Let the quote take effect only on cycles `0, k, 2k, …`. Both versions of every track are kept and chosen between per sample, so *any* mutator becomes conditional. |
| `->off` | `selection seconds quote -- selection` | Superimpose a delayed, transformed echo. |
| `->jux` | `selection quote -- selection` | A mirrored twin, transformed by the quote — left/right being the spatial reading of stereo pan. |
| `->degrade` | `selection p -- selection` | Deterministic per-object, per-cycle dropout. |
| `->stagger-swing` | `selection step amount -- selection` | `->stagger-time`, with every odd copy pushed a further `amount` of a step late. |

#### Rhythm in space

`strip` is `pat` spending the subdivision on *where* rather than on *when*: one cycle spans the wall, and each copy is centred on its event and as wide as the event is long. So `"bd [sd sd] hh*4"` comes out as a ruler — one wide mark, two half-width, four quarter-width — from the same string that `pat` would read as a rhythm. That is visual music read literally rather than metaphorically, and it is why mini-notation belongs on the geometry side at all.

```rectsl
->tempo 120
->duration-cycles 2

# the same notation, spent on *where* instead of on *when*
"tick" rect  0.34 ->square
  "bd [sd sd] hh*4" '( -3.9 ->y  0.55 ->alpha ) strip
;

# sixteen slots laid out in space; the figure decides which stay lit
"slot" rect  0.55 1.8 ->wh
  16 '( f 15 *  7.5 -  2.8 ->xy ) array
  16 0 ->kolakoski        # or `5 16 0 ->euclid`
;
```

| Word | Stack | Notes |
| --- | --- | --- |
| `strip` | `[template] pattern quote -- selection` | Lay one copy per event **across the wall** instead of across time. One cycle spans the wall; each copy is centred on its event and as wide as the event is long. The body sees the same `t`/`dur`/`step`/`atom` context a `pat` body does. |
| `->euclid` | `selection pulses steps rotation -- selection` | Keep the Euclidean onsets of a selection and hide the rest. The off-steps are hidden rather than deleted — RectSL has no node removal. |
| `->kolakoski` | `selection steps rotation -- selection` | The same layout on the Kolakoski figure — its run-length reading, which is the one that reads as a rhythm along a row. Turn `steps` up past the selection and the mask wraps, as `->euclid`'s does. |

## Distribution curves (Functions bridge)

Linear `mix` is rarely the shape you want across an array. RectSL borrows [`afield.stdlib.Functions`](functions_examples.md), a tested library of easings, distributions and noise defined over `[0, 1]`. That is exactly the range of the array fraction `f`. `ease` shapes a scalar/track through the full Penner family; the curve words push a reusable `Fn` value you `sample` at `f` (or any x). The curves are graph-backed, so the Functions rewrite/evolve tooling can optimise an array's distribution toward a target.

```rectsl
# alpha eased across the array with an elastic overshoot
"c" rect  1 ->square
  9 '( ic 1.4 *  0 ->offset   f "elastic-out" ease ->alpha ) array
;

# a gaussian falloff and reproducible noise scatter, sampled at f
"d" rect  0.6 ->square  0 -3 ->xy
  12 '(
    ic 1.1 *  0 ->offset
    gaussian f sample ->alpha
    7 noise f sample  2 *  ->rotate
  ) array
;

# `reflect` mirrors a curve about 0.5; `tile` repeats it k times across the array
"e" rect  0.4 ->square  0 3 ->xy
  16 '(
    ic 0.9 *  0 ->offset
    gaussian 3 tile  f sample  2 *  0.2 +  ->scale
    7 noise reflect  f sample  0.3 *  0 ->translate
  ) array
;
```

| Word | Stack | Notes |
| --- | --- | --- |
| `ease` | `->x name -- ->x` / `track name -- track` | Shape a value through a named Penner easing. `name` is any of `linear`, `quad-in/out/in-out`, `cubic-…`, `quart-…`, `quint-…`, `sine-…`, `expo-…`, `circ-…`, `back-…`, `elastic-…`, `bounce-…`, `smoothstep`, `smootherstep`. A constant eases to a number; a keyed track eases its interpolation. |
| `gaussian` | `-- Fn` | A bell curve centred at `0.5` — bright in the middle of the array, dark at the edges. |
| `noise` | `seed -- Fn` | Seeded value-noise curve — deterministic, reproducible scatter. |
| `fbm` | `seed -- Fn` | Seeded fractal (fBm) noise curve — richer, multi-octave scatter. |
| `sample` | `Fn x -- number` | Evaluate a curve at `x` (use the array fraction `f`). |
| `reflect` | `Fn -- Fn` | Mirror a curve about `0.5`: `reflect(->x) = fn(1-x)`. |
| `tile` | `Fn k -- Fn` | Repeat a curve `k` times across `[0, 1]`. |

#### Kotlin escape (experimental)

For anything the built-in words don't cover, `{{ … }}` drops into Kotlin (in a `with(Functions){ … }` scope) anywhere a value is expected. Whatever the Kotlin returns — a Number, an `Fn`, or a `Track<Double>` — is pushed onto the stack: the whole `Functions` API is available inline. The escape also sees the array context (`i`, `n`, `f`, `f0`, `fc`, `ic`) and can vary per copy:

```rectsl
# a curve sampled at f, and a per-copy value computed straight in Kotlin
"c" rect  1 ->square
  9 '( {{ i * 1.2 }} 0 ->offset   {{ sin(2) through gain(0.5) }} f sample ->alpha ) array
;
```

## Stack and scalar operators

One `grid`, and every number in it computed rather than typed. Each cell knows its own centred column and row, so `colc dup * rowc dup * + sqrt` is its distance out from the middle — and from that one quantity the cell derives its opacity (a smoothstepped falloff, floored at 0.05 and clamped), its size (a power curve of the same distance, so cells shrink outward non-linearly), and its rotation (the two axes averaged, then quantised to quarter-turns of π, so the field reads as banded rather than smooth).

```rectsl
12 7 '(
  "c" rect
    colc 1.1 *  rowc 1.1 * ->xy
    colc dup *  rowc dup *  +  sqrt                 # distance from the middle
    dup  0 5 smoothstep  neg 1 +  0.05 max  0 1 clamp  0.9 *  0.1 +  ->alpha
    5 min  neg 5 +  5 /  0.4 pow  0.4 *  0.4 +  ->square
    col cols 1 - /  row rows 1 - /  over  +  0.5 *  swap drop
    4 *  ceil  4 /  pi *  ->rot
  ) grid
```

| Word | Stack | Notes |
| --- | --- | --- |
| `dup` | `a -- a a` | Duplicates the top value. The original stays available below its copy. |
| `drop` | `a --` | Discards the top value while leaving the rest of the stack in place. |
| `swap` | `a b -- b a` | Exchanges the top two values. Use it when a following operation expects the opposite operand order. |
| `over` | `a b -- a b a` | Copies the second value to the top. Both original values remain on the stack. |
| `+`, `add` | `a b -- out` | Adds two constants or tracks. Track arithmetic combines values at the sampled time. |
| `-`, `sub` | `a b -- out` | Subtracts the top operand from the one below it. The operands can be constants or tracks. |
| `*`, `mul` | `a b -- out` | Multiplies constants or sampled track values. A constant factor scales a track’s values. |
| `/`, `div` | `a b -- out` | Divides the lower operand by the top one with protection against zero. Operand order follows the stack signature. |
| `mod`, `pow`, `min`, `max` | `a b -- out` | Applies scalar modulo, power, minimum or maximum according to the selected word. The top value is the second operand. |
| `neg`, `negate`, `abs`, `floor`, `ceil`, `fract`, `sqrt`, `sin`, `cos` | `a -- out` | Applies the selected unary math operation to one scalar or track. Trigonometric functions use radians. |
| `clamp` | `value low high -- out` | Bounds a value between the supplied low and high limits. Push the value, then the two bounds. |
| `sat`, `saturate` | `value -- out` | Clamps a scalar or track to `[0,1]`. `saturate` is the equivalent long form. |
| `mix`, `lerp` | `a b t -- out` | Linearly interpolates between a and b by t. Zero selects a and one selects b. |
| `smoothstep` | `low high x -- out` | Builds a Hermite transition between the lower and upper edges. Values outside the interval reach the end values. |
| `<`, `lt`, `<=`, `lte`, `>`, `gt`, `>=`, `gte`, `=`, `eq`, `!=`, `<>`, `neq` | `a b -- bool` | Numeric/string comparisons where supported. |
| `and`, `or`, `not` | `bool... -- bool` | Boolean operators using nonzero truth. |
| `pi`, `tau` | `-- number` | Pushes pi; `tau` pushes a full turn, twice pi. Both are useful with angles expressed in radians. |
