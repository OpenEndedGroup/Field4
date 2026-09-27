# Working on a StackSL study

## The frame

The program is called once per pixel of a square frame as `program(vec4(uv, hash, seconds))`:

- `uv` runs `0..1` across the frame, `y` upwards; `p` is the same point centred on zero.
- `hash` (`input.z`) is a different value in `0..1` at every pixel.
- `seconds` (`input.w`, the words `t` and `time`) is time.
- Whatever is on top of the stack at the end is the colour: its `rgb` is clamped to `0..1`, and a
  NaN or infinite colour draws black. Alpha is ignored.
- **Colour values are linear light.** The window encodes them to sRGB for display, and so does every
  tool image: `0.5` shows as sRGB 188, a light grey, and `0.21` as a mid grey. To reach a colour you
  know as sRGB hex, roughly square each channel (`#c08040` ≈ `[0.53,0.22,0.05]`). The palette hex
  codes Observe reports are what the window shows.

The program box is a child of a StackSL root, which opens a window of its own when the program
starts. The window draws the program live and follows every edit that compiles, so a person's typing
and a model's accepted edit both appear there at once. The person can hold the window at one stage
from the card, or have it follow their editor line; Inspect's `personAt` is that line. The tools render their evidence on the same
window's GPU, into an offscreen float target read back exactly — unclamped, NaN included — and say so
in `renderedBy`. When there is no running window they fall back to the CPU reference, which
interprets the same GLSL pixel by pixel: exact, and slow. The study card in the program box's output
has a WebGL2 preview of the same GLSL, for turning knobs.

## Idioms that hold

```stacksl
uv 5 * 0.1t + fbm palette saturate          # a 2-D field sliding diagonally with time
p [3,3,0,0] * t [0,0,0,0.1] * + fbm4        # a volume evolving in place: w carries time
p length 0.3 0.05 pulse white *             # a soft ring of radius 0.3
black orange 0.3 0.32 p length smoothstep mix   # two colours split by a mask
: field >q <q 4 * 0.05t + fbm ;             # a definition: consumes one value, leaves one
```

- **Build coordinates from `uv` or `p`, not `input`.** `input` carries the per-pixel hash in `z`,
  and `fbm3`, `fbm4`, `noise3`, `worley3` read `z` as a coordinate, so `input 3 * fbm4` is grain.
  Observe reports `grain` when features shrink to a pixel. Zero `z`: `p [3,3,0,0] *`.
- **Sliding versus evolving.** Adding `0.1t` moves the whole field. For change in place, give a
  4-D noise time in `w` (`fbm4`), or a 3-D one time in `z` (`worley3`, `noise3`, `fbm3`).
- **`fbm` and `noise` are 2-D** over `xy + zw * 0.07` and reach about `0..0.97`; the volumetric family
  (`fbm3`, `fbm4`, `curl3`, `warp3`, `worley3`) is exactly `0..1`.
- **Colour last.** A scalar field into `palette` (defaults, or four `vec4` operands `a b c d` for
  `a + b·cos(2π(c·t + d))`), then `saturate`. `rgb2oklab … oklab2rgb` for perceptual adjustments.
- **Shaping:** `x low high ramp`, `low high x smoothstep`, `x center width pulse`, `a b amount mix`,
  `x amount sigmoid`, `x levels posterize`.

## Traps

- **A called definition must consume its value and leave one.** Open it with `>name` (move), not
  `=name` (copy). An unbalanced definition is refused at the check. A mark cannot be called `x`,
  `y`, `z` or `w`: `>x` is the word that replaces a lane.
- **A definition cannot be named after a built-in word**: `: fbm3 … ;` is an error. Search the words guide
  before naming one (`StackSLStudyGuide` with `search`).
- **`[2z,1y,0x]` is a stack selector**, not a swizzle: it consumes three values. To rearrange one
  value use a swizzle (`zyx`) and a literal multiply (`[1,0,-1] *`).
- **The warning `reusing wrapped stack values`** means a word found fewer operands than it needs.
  It is admitted, not refused, and it is almost always a mistake.
- `sample`, `prev`, `gather` and feedback are refused: the study renders single passes.

## Tools

| Tool | Use |
| --- | --- |
| `StackSLStudyGuide` | This guide, or the words guide searched for a word. |
| `InspectStackSLStudy` | Checked source, live edit state, history, warnings, stages, knobs. Once per turn. |
| `EditStackSLStudy` | Whole program. First draft or rewrite. |
| `PatchStackSLStudy` | Exact unique replacements to the live source. |
| `SetStackSLNumbers` | New values for knobs by index; keeps `0.2t` a rate and `]wr14` a frequency. |
| `ObserveStackSLStudy` | Frames at chosen times, measurements, optional comparison with another revision. |
| `TraceStackSLStudy` | The top of the stack after each line; `probeU`/`probeV` read the whole stack at a point. |
| `KnobsStackSLStudy` | Knobs and how much a 10% nudge to each changes the frame. |
| `SweepStackSLNumber` | One knob at 2..9 values, unpublished. |
| `TryStackSLVariants` | 1..8 candidates rendered side by side and held for adoption, unpublished. |
| `AdoptStackSLVariant` | Publish a held candidate. |
| `RestoreStackSLRevision` | Make an old revision current again, as a new revision. |
| `CompareStackSLStudy` | What an edit preserved: `rgb`, `luma`, or `stage` (the stack after a given line). |
| `ExportStackSLStudy` | A PNG frame or an MP4 clip into the document's files. |

Every edit is a transaction. It names the revision you read and the hash of the live source you
read; if the person has typed since, it is refused and nothing is overwritten — Inspect again and
build on their edit. A candidate that does not compile, has an unbalanced definition, samples a
texture, or cannot be rendered is refused with the reason, and the box keeps the checked program.
The person can see and edit the same program: their errors appear on its lines as they type, and
when they run the box it is checked in as a revision like any of yours.

## Evidence and its limits

Measurements come from the program's raw output before the clamp, so `clippedAbove` is the share of
colour the clamp threw away and `nonFinite` the share of NaN pixels. `detail` is the mean brightness
gradient per frame width; `featureSize` a typical feature width as a fraction of the frame; the
palette is a five-colour k-means summary. All of it is sampled at pixel centres, so a feature
narrower than a pixel can be missed, and a still frame says nothing about motion: ask for several
times when the request is about how something moves. GPU and CPU agree except in the last bits of
hashes and trigonometry.

## Style

Write programs a person can read and change:

```stacksl
# asked: slow ink blooming in water, indigo into amber
# chosen: fbm4 so it evolves in place; 0.08 s⁻¹ drift; ramp to push mid-tones apart
p [3,3,0,0] *             # frame to noise space: 3 cells across
t [0,0,0,0.08] * +        # time in w, so the volume changes in place
fbm4                      # evolving volumetric noise, 0..1
0.35 0.9 ramp             # stretch the mid-tones
...
```

The header's `(asked)` lines record what the person said; `(chosen)` lines what you decided for
them. When a later request overrides an earlier one, edit the header to say so.
