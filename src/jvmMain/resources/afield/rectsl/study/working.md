# Working on a RectSL study

## The wall

A RectSL program is a finite animated scene made of rectangles:

- `->wall 16 9 meters` sets the wall. Every frame shows the whole wall and nothing outside it. The
  wall's centre is `(0, 0)`, `+x` is right and **`+y` is up**, so a 16 × 9 wall runs from x -8 to 8
  and y -4.5 to 4.5. `->xy` places a rectangle's centre; `->wh` and `->square` are full widths.
- `->duration 6` is the clip's length in seconds; the window plays it and loops. Time outside the
  keys of a track holds the nearest key. `->fps` is how finely the clip is sampled for analysis and export.
- **Colours are sRGB, as typed.** `0x808080` is mid grey in the window and in every tool image;
  `rgb 0.5 0.5 0.5` is the same grey. `->background 0x101014` colours the wall behind everything.
- Rotation is in radians (`tau` is a full turn). Later rectangles draw over earlier ones; `->layer`
  and `->front`/`->back` change that.

The scene box is a child of a RectSL root, usually the window box. Once run, the window draws the
scene live and follows every edit that compiles, so a person's typing and a model's accepted edit
both appear there. The tools draw their evidence the same way on the same window's GPU, and say so
in `renderedBy`; with no window they use a CPU reference that draws the same geometry at one sample
per pixel. The study card in the scene box's output shows the clip as a filmstrip, holds the window
at a time, and has a slider for every number that previews in the window as it moves.

## Idioms that hold

```rectsl
"sun" rect  3 2 ->xy  1.5 ->square  0xffcc33 solid ->fill ;       # a named rectangle
"bar" rect  '( 0=-6  3=6  6=-6 ) ->x ;                             # a quote in a channel is a track: t=value keys
"blink" rect  1 ->square  '( 0=0  1=1 ) ease-in-out 2 loop-time ->alpha ;   # shape a track, then loop it every 2 s
"pip" rect 0.4 ->square  12 3 '( f0 tau * ->rot ) radial ;         # 12 copies round a circle of radius 3
"cell" rect 0.8 ->square  9 '( fc 6 * 0 ->xy  "viridis" f colormap solid ->fill ) array ;   # one colour per copy
"dot" rect 0.5 ->square  '( 0=-4  2=4 ) ->y  8 '( fc 7 * ->x  i 0.25 * ->delay-all-time ) array ;   # one motion, each copy later
```

- **Name what you make** (`"sun" rect`). Names are what a person reads, what `TraceRectSLStudy`
  reports, and what `CompareRectSLStudy` matches across revisions; an unnamed rectangle's id
  (`rect-12`) moves when a line above it adds one.
- **Reproduce, then vary:** `array`, `radial`, `grid`, `arc`, `spiral` and `sunflower` copy one
  template; inside the body `i`, `f` (0..1), `f0` (0..1, cyclic), `fc` (-1..1), `ic`, and in a grid
  `colc`/`rowc` vary each copy. `->delay-all-time` and `->stagger-time` give copies a canon in time.
- **A seamless loop** ends where it starts: give the last key the first key's value at
  `->duration`, or `loop-time` with a period that divides the duration.
- **Share motion** with a named track: `linear-track "rise" '( 0=-3 6=3 )` once, then
  `"rise" track-ref ->y` in every rectangle that should move together.
- **Colour per copy** comes from the stack through `colormap` (`"magma" f colormap solid ->fill`) or
  from opacity (`f 0.8 * 0.2 + ->alpha`); a two-colour ramp across a rectangle is `linear`.
- **Materials are StackSL** fragment programs over `vec4(uv of the rectangle, object id, seconds)`,
  multiplied by the rectangle's colour: `material stripes '( uv 8 * sin abs )`, then
  `stripes ->material`. Geometry belongs in RectSL, shading in the material.

## Traps

- **`rgb`, `rgba`, `material`, `track`, `->wall`, `->duration`, `->fps` read literals forward** out of
  the source, not off the stack: `rgb 0.9 0.4 0.2`, never `0.9 0.4 0.2 rgb`, and no expression can
  go in those slots. For a computed colour use `colormap`.
- **A clone's `->xy` replaces both x and y**, including a y track it inherited. To keep the
  template's y motion on its copies, set only `->x`.
- **Give a material to each copy as it is made**, inside the `array` body; applying one afterwards
  through `descendants … each` has been seen to reach only some rectangles.
- **Rectangles are mostly outside the wall** is the usual reason for a blank frame: check the wall's
  extent and `TraceRectSLStudy`. The check warns when nothing is visible at its probe times, and
  about rectangles that are never visible or leave early.
- **`{{ … }}` Kotlin escapes** compile Kotlin the first time they are seen (about a tenth of a
  second each); prefer RectSL words and `colormap` when they will do.

## Tools

| Tool | Use |
| --- | --- |
| `RectSLStudyGuide` | This guide, or the words guide searched for a word. |
| `InspectRectSLStudy` | Checked source, live edit state, history, the scene's shape, warnings, lines, knobs. Once per turn. |
| `EditRectSLStudy` | Whole program. First draft or rewrite. |
| `PatchRectSLStudy` | Exact unique replacements to the live source. |
| `SetRectSLNumbers` | New values for knobs by index; a hex colour takes its 24-bit number. |
| `ObserveRectSLStudy` | Frames at chosen times, measurements, optional comparison with another revision. |
| `TimelineRectSLStudy` | The whole clip as frames, plus what is visible and moving over time and the events in it. |
| `TraceRectSLStudy` | `identify`: every rectangle outlined and labelled with its line; `build`: the scene after each line. |
| `KnobsRectSLStudy` | Knobs, what reads each, and how much a 10% nudge to each changes the frames. |
| `SweepRectSLNumber` | One knob at 2..9 values, unpublished. |
| `TryRectSLVariants` | 1..8 candidates drawn side by side and held for adoption, unpublished. |
| `AdoptRectSLVariant` | Publish a held candidate. |
| `RestoreRectSLRevision` | Make an old revision current again, as a new revision. |
| `CompareRectSLStudy` | What an edit preserved: pixels outside a region, and which rectangles changed, by line. |
| `ExportRectSLStudy` | A PNG frame or an MP4 of the clip into the document's files. |

Every edit is a transaction. It names the revision you read and the hash of the live source you
read; if the person has typed since, it is refused and nothing is overwritten — Inspect again and
build on their edit. A candidate that does not compile, or whose materials do not, is refused with
the problem and its line, and the box keeps the checked scene. The person sees and edits the same
program: its problems appear on its lines as they type, and when they run the box it is checked in
as a revision like any of yours.

## Evidence and its limits

Frames are drawn at pixel centres, so a rectangle thinner than a pixel at the size asked for can
vanish from a frame without vanishing from the scene: ask for a larger `size`, or read `Trace`'s
numbers. Visible counts and events come from the scene itself, not the pixels: a rectangle is
visible when it is not transparent and meets the wall, even if another covers it. A still frame
says nothing about motion; use `TimelineRectSLStudy` or several times when the request is about
how something moves or when.

## Style

Write scenes a person can read and change:

```rectsl
# asked: three moons crossing a violet sky, slowly
# chosen: 12 s clip; each moon a named rect on its own named track, so they can be moved separately
->wall 16 9 meters
->duration 12
->fps 30
0x1a1030 ->background

# the moons, left to right, each rising a little later
linear-track "cross" '( 0=-9  12=9 )
"moon-a" rect  0 1.5 ->xy  1.2 ->square  0xf4efe0 solid ->fill  "cross" track-ref ->x ;
...
```

The header's `(asked)` lines record what the person said, the `(chosen)` lines what you decided for
them. One object per paragraph, each with a comment saying what it is. When a later request
overrides an earlier one, edit the header to say so.
