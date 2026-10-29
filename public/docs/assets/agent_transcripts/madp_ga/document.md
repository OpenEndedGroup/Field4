# Field Document: madp_ga

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. ga · run me

<!-- field-box id="madp_ga_lead" index="0" -->

### Narrative

From `notes`

GA

From `code`

GA: interactive evolution, after Karl Sims. Each tile is a little program run once per pixel,
and you are the fitness function: click two tiles and they become the parents of the next
generation. A child copies one parent, sometimes borrows a few words from the other, and
mutates. Reroll breeds again from the same parents; Fresh Parents starts from noise.

The language is reverse Polish: every word takes its inputs off a stack.
x y          position, 0..1 (y grows downward)
r a          distance from the centre, angle around it (both 0..1)
rgb hsl      three numbers -> a colour
sat hue lum  set one part of a colour
+ - * /      numbers, or colours channel by channel (wrapped into 0..1)
sin cos tan asin acos atan exp ln invert normalize   change one value
perlin       phase -> noise      fbm   phase lacunarity -> layered noise
dot          two values -> a number
a b c if     b when c > 0.5, else a
blend        a number and two colours -> the colours mixed
dup          copy the top
An empty stack reads 0.5 and unknown words are skipped, so every program draws something.

These two parents are the demo's starters. Edit them and run the box again (Alt+Enter); the
parents you pick are printed under the grid, ready to paste back in here.

```edn [field-box id="madp_ga_lead" role="properties"]
{"id" "madp_ga_lead" "index" 0 "name" "ga · run me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "ga · run me" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_ga_lead_notes_.md" "lines" 86 "characters" 4964 "sha256" "27470a954493906b457efb9d5c747b404d1f8e892fe046cc5b13d61302c662b2"} {"name" "code" "language" "kotlin" "path" "madp_ga_lead_code_.kt" "lines" 39 "characters" 1536 "sha256" "55d4513fc84b297a8bc7ea5ea4be5ce3699f56ee70d12335e89557ad82dfcaa7"}]}
```

```markdown [field-property box="madp_ga_lead" name="notes" path="madp_ga_lead_notes_.md"]
# GA

Interactive evolution, the way Karl Sims did it in *Genetic Images* (1991): **the code is the
genome, and your eye is the fitness function.** Every tile is a little program, run once for each
pixel. Nothing scores the pictures. You look at nine of them, click the two you like, and those two
become the parents of the next nine.

This is the madp **GA** demo (`ga.html`) in Field. The programs, the breeding rules and the look of
the grid are the demo's; a program pasted from one draws the same picture in the other.

## Breeding

Run this box. Its output panel shows a 3×3 grid: **parent a** and **parent b** (blue outlines), then
seven children.

- **Click two tiles** and they become the new parents; seven new children appear. The first click
  marks a tile in amber; click it again to take it back.
- **Reroll** breeds seven new children from the same parents.
- **Fresh Parents** throws both parents away for two random programs.
- **Start over** goes back to the two parents written in the code.
- **mut**, **len** and **res** are the mutation rate, the length a child is nudged toward, and the
  resolution each tile renders at. Changing one breeds again.

Each child copies one parent at random. About four times in ten it first borrows one to three words
from the other parent (`seed` in its header). Then it takes a few mutations, named in its header:
`num` nudges a number, `var` turns a number into `x`, `y`, `r` or `a`, `op` swaps a word,
`splice` borrows from the other parent again, `copy` repeats a stretch of itself, `drop` deletes,
`add` inserts and `swap` exchanges two words. Last, its length is topped up or trimmed toward
**len**, give or take two.

The parents you have picked are printed in full under the grid. To keep one, copy it into the code
above and run the box again; editing a parent in the code and re-running (alt-R) is how you steer.

## The language

Reverse Polish: each word takes its inputs off a stack and pushes its result. Values are numbers or
colours.

| words | what they do |
| --- | --- |
| `x` `y` | position, 0 to 1 across the tile (`y` grows downward) |
| `r` `a` | distance from the centre (1 at a corner), angle around it (0 to 1) |
| `rgb` `hsl` | three numbers → a colour |
| `sat` `hue` `lum` | a colour and a number → the colour with that part set |
| `+ - * /` | numbers, or colours channel by channel, wrapped into 0..1; `-` on colours is the difference; `/` never divides by zero |
| `sin cos tan asin acos atan exp ln invert normalize` | change one value; `invert` is `1 - v` |
| `perlin` | phase → smooth noise; `fbm` phase lacunarity → four octaves of it |
| `dot` | two values → a number |
| `a b c if` | `b` when `c` > 0.5, otherwise `a` |
| `blend` | a number and two colours → the colours mixed |
| `dup` | copy the top |

Nothing fails. An empty stack reads 0.5, unknown words are skipped, and whatever is left becomes a
colour: a colour on top is the answer; two colours average; a colour and a number mix 70/30; two
numbers make a blue-to-orange colour; one number is a grey. So every program draws something, and
mutation never has to be careful.

`hsl` is the quickest route to something good: `x 9 * sin 0.18 +  y 0.55 +  0.62  hsl` puts a
wave in the hue and a ramp in the saturation.

## How this maps to Field

| ga.html | here |
| --- | --- |
| the two Monaco editors | the two strings in this box |
| the 3×3 grid, clicking two tiles | `colorRpnEvolution(...)`, a grid in the box's `ga` output panel |
| toolbar mut / len / res, Reroll, Fresh Parents | the same, in the grid's toolbar; `ColorRpnBreeding` sets the starting values |
| the breeding rules | `ColorRpnBreeding`: rate, length, splice chance, the mutation mix (see the last box) |
| the interpreter | `ColorRpn.compile(source)`; `program.png(256)` draws one |

The grid itself is `evolveByEye(...)`, which takes any genome with a picture and a way to breed it,
so the same click-to-breed loop works for StackSL programs, FLines or anything else you can draw.

**Why the demo's language and not StackSL.** StackSL is Field's own GPU stack language and has an
evolve layer of its own, but the point here is reading the genome: a tile's caption is a program
you can follow, change and paste back. Keeping the demo's language keeps its programs, its
starters and its examples working unchanged. It runs on the CPU: nine tiles at 256 pixels are well
under a second, and no window or GPU is needed. (Compiling it to a shader was not worth it: `if`
can choose a number on one pixel and a colour on the next, so the stack's types change per pixel.)

## What differs

- Picking a tile does not rewrite the code; the parents are printed under the grid for you to copy.
- Tiles appear when the whole generation is drawn, without the demo's per-tile progress bars.
- The demo's AI completion, "Copy LLM intro" and local saves are not here: Field has its own
  completion and the AI4 chat, and the document keeps its own history.
```

```kotlin [field-property box="madp_ga_lead" name="code" path="madp_ga_lead_code_.kt"]
/*
GA: interactive evolution, after Karl Sims. Each tile is a little program run once per pixel,
and you are the fitness function: click two tiles and they become the parents of the next
generation. A child copies one parent, sometimes borrows a few words from the other, and
mutates. Reroll breeds again from the same parents; Fresh Parents starts from noise.

The language is reverse Polish: every word takes its inputs off a stack.
  x y          position, 0..1 (y grows downward)
  r a          distance from the centre, angle around it (both 0..1)
  rgb hsl      three numbers -> a colour
  sat hue lum  set one part of a colour
  + - * /      numbers, or colours channel by channel (wrapped into 0..1)
  sin cos tan asin acos atan exp ln invert normalize   change one value
  perlin       phase -> noise      fbm   phase lacunarity -> layered noise
  dot          two values -> a number
  a b c if     b when c > 0.5, else a
  blend        a number and two colours -> the colours mixed
  dup          copy the top
An empty stack reads 0.5 and unknown words are skipped, so every program draws something.

These two parents are the demo's starters. Edit them and run the box again (Alt+Enter); the
parents you pick are printed under the grid, ready to paste back in here.
*/
val ga =
    colorRpnEvolution(
        """
    x 9 * sin 0.18 +
    y 0.55 +
    0.62
    hsl
    """,
        """
    a 14 * cos 0.14 +
    r 0.78 * 0.12 +
    0.48
    hsl
    """,
        ColorRpnBreeding(mutationRate = 0.12, targetLength = 18),
    )
```

## 2. fbm bands × noise hues

<!-- field-box id="madp_ga_fbm" index="1" -->

### Narrative

From `notes`

fbm bands × noise hues

From `code`

Layered noise along a diagonal, and two noises in x and y added into a hue.

```edn [field-box id="madp_ga_fbm" role="properties"]
{"id" "madp_ga_fbm" "index" 1 "name" "fbm bands × noise hues" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "fbm bands × noise hues" "_tombstone" false "frame" #afield/rect [40.0 1320.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_ga_fbm_notes_.md" "lines" 9 "characters" 449 "sha256" "a77c4baf337fb5d49d1aaaafcfacf390d00e61e9989ccd84eac4a6a501190382"} {"name" "code" "language" "kotlin" "path" "madp_ga_fbm_code_.kt" "lines" 5 "characters" 193 "sha256" "ce1e5ab23141a60b5d1b762b679ea836b58373a407542a7d1206fb90c8a16452"}]}
```

```markdown [field-property box="madp_ga_fbm" name="notes" path="madp_ga_fbm_notes_.md"]
# fbm bands × noise hues

`x y + 7 * 2.2 fbm 0.65 0.55 hsl` runs layered noise along the diagonal and uses it as a hue, so
the bands are rough rather than smooth. `x 6 * perlin y 9 * perlin + 0.5 * 0.7 0.55 hsl` adds a
noise in `x` to a noise in `y`.

Both end in `... 0.55 hsl`, so their children keep a bright, even lightness and mostly change
how the hue moves. Watch the numbers in front of `fbm` and `perlin`: they set how busy the
picture is.
```

```kotlin [field-property box="madp_ga_fbm" name="code" path="madp_ga_fbm_code_.kt"]
// Layered noise along a diagonal, and two noises in x and y added into a hue.
colorRpnEvolution(
    "x y + 7 * 2.2 fbm 0.65 0.55 hsl",
    "x 6 * perlin y 9 * perlin + 0.5 * 0.7 0.55 hsl",
)
```

## 3. only the numbers mutate

<!-- field-box id="madp_ga_numbers" index="2" -->

### Narrative

From `notes`

Only the numbers mutate

From `code`

The breeding rules are settings too. ColorRpnBreeding's weights say how often each kind of
mutation happens (the demo's mix: num 33%, var 19%, op 20%, splice 10%, copy 6%, drop 6%,
add 4.5%, swap 1.5%). Here only numbers change and nothing is borrowed from the other
parent, and nudgeLength = false stops the length being topped up with random words, so every
child keeps its parent's shape: the same rings and bands at different frequencies.

```edn [field-box id="madp_ga_numbers" role="properties"]
{"id" "madp_ga_numbers" "index" 2 "name" "only the numbers mutate" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "only the numbers mutate" "_tombstone" false "frame" #afield/rect [860.0 1320.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_ga_numbers_notes_.md" "lines" 10 "characters" 630 "sha256" "20229700ba32aa3a6529e0d2311993e5336dbff15c55c908f74d32caad8e22f4"} {"name" "code" "language" "kotlin" "path" "madp_ga_numbers_code_.kt" "lines" 17 "characters" 703 "sha256" "dda71e2a653cbcd7182f9f1527978011daae496bf950d563fdba853fccc71e37"}]}
```

```markdown [field-property box="madp_ga_numbers" name="notes" path="madp_ga_numbers_notes_.md"]
# Only the numbers mutate

The breeding rules are settings too. `ColorRpnBreeding` here allows only `num` mutations, never
borrows from the other parent (`seedSplice = 0.0`), and does not top the length up with random
words (`nudgeLength = false`). Every child is its parent with different numbers: the same rings
and the same diagonal bands, at new frequencies and colours.

It is a slower, gentler search, good for tuning a picture you already like. The demo's mix is
`ColorRpnBreeding.DEFAULT_WEIGHTS`: num 33%, var 19%, op 20%, splice 10%, copy 6%, drop 6%,
add 4.5%, swap 1.5%. Change the weights and re-run to try your own.
```

```kotlin [field-property box="madp_ga_numbers" name="code" path="madp_ga_numbers_code_.kt"]
/*
The breeding rules are settings too. ColorRpnBreeding's weights say how often each kind of
mutation happens (the demo's mix: num 33%, var 19%, op 20%, splice 10%, copy 6%, drop 6%,
add 4.5%, swap 1.5%). Here only numbers change and nothing is borrowed from the other
parent, and nudgeLength = false stops the length being topped up with random words, so every
child keeps its parent's shape: the same rings and bands at different frequencies.
*/
colorRpnEvolution(
    "r 12 * sin a 9 * cos + 0.6 0.5 hsl",
    "x y + 7 * 2.2 fbm 0.65 0.55 hsl",
    ColorRpnBreeding(
        mutationRate = 0.25,
        seedSplice = 0.0,
        weights = mapOf("num" to 1.0),
        nudgeLength = false,
    ),
)
```

## 4. noise mask × rings

<!-- field-box id="madp_ga_noise" index="3" -->

### Narrative

From `notes`

Noise mask × rings

From `code`

Two of the demo's example programs as parents: a perlin mask blended with its own
inverse, and rings crossed with spokes.

```edn [field-box id="madp_ga_noise" role="properties"]
{"id" "madp_ga_noise" "index" 3 "name" "noise mask × rings" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "noise mask × rings" "_tombstone" false "frame" #afield/rect [860.0 40.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "madp_ga_noise_notes_.md" "lines" 9 "characters" 521 "sha256" "6051a987a3f2a2425da6c51062c4431461a8d04a1a19b562a2ebb23e8d860c70"} {"name" "code" "language" "kotlin" "path" "madp_ga_noise_code_.kt" "lines" 6 "characters" 232 "sha256" "e7593388c234bf0af6aaf8558542c5e06a3868876006753e39e554c2d678384e"}]}
```

```markdown [field-property box="madp_ga_noise" name="notes" path="madp_ga_noise_notes_.md"]
# Noise mask × rings

Two of the demo's example programs as parents. `x 8 * perlin dup invert 0.3 blend` is a perlin
noise in `x` blended with its own inverse, a grey of soft vertical bands. `r 12 * sin a 9 * cos +
0.6 0.5 hsl` adds rings to spokes and puts the sum in the hue.

They share almost nothing, so the children are wild: borrowed words land in the wrong place and
whole pictures collapse to one colour. That is normal. Pick the two you like best anyway; a few
generations in, the grid settles into one family.
```

```kotlin [field-property box="madp_ga_noise" name="code" path="madp_ga_noise_code_.kt"]
// Two of the demo's example programs as parents: a perlin mask blended with its own
// inverse, and rings crossed with spokes.
colorRpnEvolution(
    "x 8 * perlin dup invert 0.3 blend",
    "r 12 * sin a 9 * cos + 0.6 0.5 hsl",
)
```

