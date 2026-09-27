# Talking a shader into existence

Describe a picture in AI4 chat. The model writes it as [StackSL](stacksl_examples.md) in a Field box,
and a window draws the program live while you talk. Before replying, the model looks at the result:
exact frames at chosen times, stage-by-stage traces, variants side by side, and comparisons showing
what an edit left alone. Each accepted change is a saved revision. You can move a slider, adopt a
variant or type into the program yourself, and the model builds on what you did.

This is the StackSL counterpart of [Talking a function into existence](function_conversation.md), and
[Talking a scene into existence](rectsl_conversation.md) does the same for RectSL.
StackSL is one of Field's main languages: a box under a StackSL root is a program, drawn live in
the window above it. Its AI4 chat has tools that check, render, compare and version the program,
and a document can add tools of its own.

## Start

In any document, select a box — press **n** to make one — and choose **Make a StackSL Program** from
the command palette. A root box, **StackSL window**, appears above it and opens a square window; the
box becomes a program, layered noise over slowly drifting coordinates, and draws in it. Select the
program and press **command/ctrl-4** to open AI4 chat. Configure an AI provider through Field's
welcome page if you have not already; see [AI features](aiFeatures.md).

The program box edits its `stackSL` property, and the window follows every edit that compiles; an
edit with an error is marked on its line, and the window keeps the last program that compiled.
Running the box (command/ctrl-return) checks your program in as a revision. The root holds no code:
it is the box whose main language is StackSL, and it opens its window when a program beneath it
starts. Stopping the root, or closing the window, stops the programs in it; running one again reopens
the window, and a program whose window is closed still has its card's WebGL preview. **Set Main
Language: StackSL** on a box you made yourself does the same, and every box beneath it is a program.

The recorded sessions below started from a flat grey.
[Download that starter](assets/stacksl_conversation/stacksl_conversation.zip) to begin from the same
place: unzip it into your Field workspace, keeping the `stacksl_conversation` folder intact, and open
it. It has two boxes:

| Box | What it is |
| --- | --- |
| **StackSL window** | The StackSL root. It holds no code; its window opens when the program starts. |
| **the shader — talk here (AI4)** | The program. Select it and press **command/ctrl-4** to open AI4 chat. |

The program starts as one flat grey:

<!-- stacksl-study-source:starter -->
```diffstatic {data-lang=stacksl}
# A blank page: one flat grey. Describe what you want to see in this box's AI4 chat.
0.15
```
<!-- /stacksl-study-source:starter -->

Try this in the program box's chat:

> Slow ink blooming in water: deep indigo spreading into warm amber, evolving in place rather than
> sliding across the frame.

An accepted edit appears in the program box as StackSL, one stage per line with a comment on each,
and in the window as soon as it is accepted. Completion in the program box offers every word the
compiler accepts, with its stack effect and the cheatsheet's description, and the program's own
definitions and marks. Every StackSL box's chat has the study tools, and each program keeps its own
history under `studies/<box id>/` in its document.

## The window, the card and the GPU

A running program draws a fullscreen triangle in the window above it. Its fragment shader is the
study's template with the program in its `{{ stack.auto.program }}` slot, and time in a push
constant. A new edit that compiles builds a new pipeline and replaces the old one, which is retired
once the frames using it have finished.

The tools render their evidence on the same GPU. Each render draws the candidate program into a
floating-point offscreen target, waits one renderer frame, and reads the pixels back, so the model
sees exact frames at the times it asks for rather than wherever the window's clock has got to. In
the recorded sessions an accepted edit, including its before/after preview, took 0.7–1.3 s; a sheet
of three or four variants at three times took 0.9–2.4 s; a comparison took under 0.7 s.

StackSL also has a CPU interpreter, which this study used first. It is exact, but a program built
from several octaves of noise runs at a few thousand pixels per second, and a single variant sheet
took most of a minute. It is still here: it renders when there is no window, and every result says
which renderer drew it. The tests use it as an independent reference.

The window presents through an sRGB swapchain: the shader writes linear colour, and the display
encodes it. The study encodes its images the same way, so the model and the person judge the same
picture. A raw readback, without that encoding, looks much darker than the window.

The **study card** in the program box's output is the person's side of the study. It shows:

- a WebGL preview of the checked revision, running in the browser, which follows the sliders as you
  move them;
- the model's evidence frame, rendered on the window's GPU;
- a slider for every number in the program, grouped under the line it is on and the comment that
  says what that line does, with a colour swatch for each colour literal, and **Keep these
  numbers** to make the new values a revision;
- the program's stages, each a small picture of what that line produced — for a line that stores
  into a mark (`>sky`), the value it stored. Click one and the window shows that stage instead of
  the whole program; click it again to go back. With **the window shows the editor's line** ticked,
  the window follows the cursor instead, showing what the line under it leaves on the stack. The
  editor offers the same picture of any line: rest the cursor on it, or open completion there, and
  it appears in the note beside the cursor and at the top of the completion popup;
- any variants the model has offered, each with **Adopt**;
- the revision history, each with **Restore**;
- exports in progress, which can be cancelled;
- **Check in the editor's program** when you have typed an edit you have not run.

![The study card for the finished aurora: WebGL preview, the GPU evidence frame, number sliders grouped by line with colour swatches, 13 of 36 stages showing what each line stored, and seven revisions with Restore](assets/stacksl_conversation/card.png)

This is the program box of the finished aurora session, reopened in Field. Past about 24 numbers,
the remaining lines' sliders wait behind a disclosure.

Clicking the tile of line 54, `<rays * >curtain`, holds the window at that stage: the aurora's
curtains alone, before lines 56–59 colour them emerald and violet and lay them over the sky.

![The aurora's window held at line 54: pale vertical curtains of light on black, brightest a little below the middle, with no sky, stars or hills](assets/stacksl_conversation/stage.png)

## The tools

AI4 offers these tools to any conversation opened on a box's `stackSL` property, and each works on
that box's program. They are library code, in
[`afield.stacksl.study`](assets/stacksl_conversation/StackSLStudyTools.kt), registered through
[AI4's tool interface](aiFeatures.md#give-the-ai-a-tool-of-your-own):

| Tool | Purpose |
| --- | --- |
| `StackSLStudyGuide` | How to work on a study — the frame, idioms, traps and style — or the words guide searched for a word. |
| `InspectStackSLStudy` | Read the checked program, any unchecked hand edit, history, warnings, stages, knobs, and the line the person's cursor is on. |
| `EditStackSLStudy` | Replace the whole program: compile, check, activate, and return a preview against the parent. |
| `PatchStackSLStudy` | Exact local text replacements, through the same checks. |
| `SetStackSLNumbers` | Change numbers by knob index, rewritten in place so a time rate stays a time rate. |
| `ObserveStackSLStudy` | Frames at chosen times, with measurements: clipping, NaN, detail, feature size, palette, motion. |
| `TraceStackSLStudy` | What each line produced, as pictures; optionally the stack's values at one point. |
| `KnobsStackSLStudy` | Each number's role, and how much a 10% nudge to it changes the frame. |
| `SweepStackSLNumber` | One number at several values, side by side, without publishing. |
| `TryStackSLVariants` | Up to eight candidate programs beside the current one, without publishing; held for Adopt. |
| `AdoptStackSLVariant` | Publish a held variant as a revision. |
| `RestoreStackSLRevision` | Make an earlier revision current again, as a new revision. |
| `CompareStackSLStudy` | Show that an edit changed only what it should: by colour, by brightness, or by stage. |
| `ExportStackSLStudy` | Render a still or a clip into the document's `files/` folder, in the background. |

Every edit carries the revision and the source hash it was based on. If you type while the model
is preparing an edit, the stale edit is refused and your text stays. A refused or failed edit
changes nothing. The check refuses programs that do not compile, unbalanced definitions, and
`sample`/`prev`: study programs are single-pass over `vec4(uv, hash, seconds)`.

A stage comparison (`mode=stage`) renders both programs only up to a chosen line and compares what
that line produced. A recolour that leaves the field before the palette identical proves it did not
move the geometry. A stage that leaves nothing but the program's input tells two programs apart
from nothing, and the result says so.

### Adding a tool of your own

A document can teach the assistant something more, from a box: register a request class with the
document as its scope, and gate it on the conversation's focus. Run the box and the next turn has
the tool; two documents can each have their own tool of the same name.

<!-- stacksl-study-source:colour-tool -->
```diffstatic {data-lang=kotlin}
import afield.ai.core.AgentToolExtensions
import afield.stacksl.study.StackSLStudyShader
import com.fasterxml.jackson.annotation.JsonClassDescription

@JsonClassDescription("The colours a StackSL program types, as the window shows them.")
data class ColoursOfStackSLStudy(val revision: String = "current")

val document = inside.filename
AgentToolExtensions.register(ColoursOfStackSLStudy::class.java,
    { it.focus?.document == document && it.focus?.property == "stackSL" }, scope = document) { ctx, _ ->
    val program = ctx.focus!!.box.getPropertyRaw("stackSL", "") as String
    StackSLStudyShader.colourKnobs(program).map { it.hex }
}
```
<!-- /stacksl-study-source:colour-tool -->

The model reads what the handler returns, as JSON: here the program's colour literals as the window
shows them, such as `["#f3bc7c"]`.

## Three recorded sessions

Each session below is a real AI4 conversation with the window running, recorded with gpt-6-astra
through OpenAI. The pictures are the images the tools returned to the model. Between turns, a person
acted in Field: adopting variants in the card, keeping a slider value, editing the program by hand.

### Ink in amber water

> Slow ink blooming in water: deep indigo spreading into warm amber, evolving in place rather than
> sliding across the frame.

The model read the guide, wrote the program with one `EditStackSLStudy`, and looked at the window
before replying. Time moves the noise through its fourth dimension (`0.035t >w`), so the bloom
reshapes in place instead of drifting.

![The live window after the first edit: an indigo bloom with curling edges in amber](assets/stacksl_conversation/ink/images/69d1b0d684b715e6f0f4.jpg)

> The indigo body reads heavy and flat in the middle. Could you show me a few different ways to give
> it internal structure — lighter veils or threads inside the ink — so I can pick one?

`TryStackSLVariants` rendered three candidates at 0, 8 and 20 seconds in 2.2 s and published
nothing:

![Variant sheet: the current program and candidates A (soft veils), B (fine threads) and C (warm channels), each at three times](assets/stacksl_conversation/ink/images/8fcfa6f6cbabb16b120f.png)

The person adopted **B** in the card, then moved the drift-speed slider from `0.035t` to `0.06t`
and kept it. Both became revisions. The next request asked for light in the water with the ink
unchanged, and for proof. The model's patch saves the finished ink as `>original`, builds the
lighting separately, and ends with an exact guard: wherever there is any ink, the original pixel
wins.

<!-- stacksl-study-source:ink-water -->
```diff {data-lang=stacksl}
:::before
# Fine pale threads tracing the folded internal field.
[0.25,0.30,0.50]
<flow fbm4 42 * sin abs 1- 4 pow
<ink 0.45 0.95 ramp smooth * 0.58 * mix
saturate
:::after
# Fine pale threads tracing the folded internal field.
[0.25,0.30,0.50]
<flow fbm4 42 * sin abs 1- 4 pow
<ink 0.45 0.95 ramp smooth * 0.58 * mix
saturate
# Keep the entire original ink rendering, including the user's speed and threads.
>original
# Broad water-light ripples evolve independently, more slowly than the ink.
p 2.4 * 0.014t >w fbm4 >waterNoise
p x 7 * p y 4 * + <waterNoise 5 * + 0.07t + sin 0.5 * 0.5 + >light
# Copper shade to honey light, applied only to clear water.
<original
<water [0.64,0.48,0.34,1] * <water [1.22,1.20,1.10,1] * <light mix
<concentration 0.10 0.28 ramp smooth 1- mix
# Exact guard: every pixel with any ink keeps its original RGBA.
<original <ink 0 > select
saturate
```
<!-- /stacksl-study-source:ink-water -->

Its proof compares the old and new programs at the stage where the ink is finished, at 0, 8, 20
and 40 seconds. The difference column is black: the largest difference is zero.

![Stage comparison r4 → r5 at stage 12: before, after and difference at four times, all differences zero](assets/stacksl_conversation/ink/images/8ef3b9b4c73d82e755d1.png)

![The live window after the water edit: honey light and copper shade in the water around the unchanged ink](assets/stacksl_conversation/ink/images/e844737c466692f654a9.jpg)

> That's the one. Export a still and a short clip of it into the document so I can use them
> elsewhere.

The model exported a 2048 × 2048 still at 20 seconds and a 12-second clip starting there, 512 px at
24 fps. Both ran in the background and appeared in the document's `files/` folder. This is the clip:

<video controls loop muted playsinline width="512" src="assets/stacksl_conversation/ink/indigo-amber-clip.mp4"></video>

<!-- stacksl-study-source:ink-final -->
??? example "The complete program · r5-cfb7e379eb87"

    ```diffstatic {data-lang=stacksl}
    # Ink in amber water. Time changes the noise's fourth dimension, never scrolls xy.
    : folds
      >q
      <q fbm4
      <q [7.1,3.8,2.4,0] + fbm4
      vec2 0.5 - 2.8 * <q +
    ;
    # Fixed spatial anchor, slow internal evolution.
    p 3.8 * 0.06t >w >domain
    <domain folds folds >flow
    # Feathered concentration around a stationary central bloom.
    <flow fbm4 0.78 *
    p [1,0.85,0,0] * length 0.92 * - 0.27 + =concentration
    0.28 0.62 ramp smooth >ink
    # Amber water with a gentle falloff toward the edges.
    [0.78,0.40,0.10] [0.42,0.16,0.028] p length 0.85 * mix >water
    # Dilute indigo at the edges, near-black indigo in the pooled pigment.
    <water [0.105,0.095,0.235] <ink 0.0 0.68 ramp smooth mix
    [0.008,0.012,0.060] <ink 0.30 1.0 ramp smooth mix
    # Fine pale threads tracing the folded internal field.
    [0.25,0.30,0.50]
    <flow fbm4 42 * sin abs 1- 4 pow
    <ink 0.45 0.95 ramp smooth * 0.58 * mix
    saturate
    # Keep the entire original ink rendering, including the user's speed and threads.
    >original
    # Broad water-light ripples evolve independently, more slowly than the ink.
    p 2.4 * 0.014t >w fbm4 >waterNoise
    p x 7 * p y 4 * + <waterNoise 5 * + 0.07t + sin 0.5 * 0.5 + >light
    # Copper shade to honey light, applied only to clear water.
    <original
    <water [0.64,0.48,0.34,1] * <water [1.22,1.20,1.10,1] * <light mix
    <concentration 0.10 0.28 ramp smooth 1- mix
    # Exact guard: every pixel with any ink keeps its original RGBA.
    <original <ink 0 > select
    saturate
    ```
<!-- /stacksl-study-source:ink-final -->

### Zellige tiles

> A still image, no animation: a zellige-like tile pattern — eight-fold star rosettes in cobalt and
> white with a little saffron, separated by thin dark grout lines.

The first edit built the pattern from three definitions: `wedge` folds a point into one mirrored
slice of the rosette, `star` measures the distance to the star's edge, and `octagon` the distance to
the central polygon (it became `medallion` when the rosettes gained ten points). Everything depends
on position only.

![The live window: four by four cobalt eight-point rosettes on white, saffron diamonds, dark grout](assets/stacksl_conversation/tiles/images/1ee06c0e9f6b7bd1430c.jpg)

> Before we settle on eight: show me what the rosettes look like with 6, 8, 10 and 12 points, side by
> side.

A still program renders once, so the variant sheet is a single row:

![Variant sheet: the current rosettes, then 6, 8, 10 and 12 points](assets/stacksl_conversation/tiles/images/d1bbeac39c24d913f3e1.png)

The person adopted **10 points** in the card. Next came a recolour in terracotta, cream and deep
green, with the geometry unchanged and a proof. The patch changed fifteen numbers in five colour
literals, plus three comments:

<!-- stacksl-study-source:tiles-recolour -->
```diff {data-lang=stacksl}
:::before
# Alternating cobalt facets, warm white ground, tiny saffron corner diamonds.
[0.88,0.87,0.81,1] >ivory
[0.018,0.055,0.30,1] [0.027,0.10,0.44,1]
:::after
# Alternating terracotta facets, cream ground, tiny deep-green corner diamonds.
[0.92,0.84,0.68,1] >ivory
[0.48,0.135,0.065,1] [0.62,0.205,0.105,1]
```
<!-- /stacksl-study-source:tiles-recolour -->

The model compared three stages. The first, stage 24, ends in `>joint`, so both programs had only
their input on top and the comparison showed nothing; its reasoning noticed the empty-looking stage.
It then compared stage 20, the petal-divider and hub field, and stage 23, the star-masked
background joints. Both were identical:

![Stage comparison at stage 20: the petal and hub field before and after the recolour, difference black](assets/stacksl_conversation/tiles/images/e1db8d56ac8d7c4b114d.png)

That first comparison is why a stage that ends in a store now compares the value it stored — here,
the grout field — and why a comparison of the program's input alone is reported as vacuous.

Then the person widened the grout by hand, changing `0.003 0.005 <joint smoothstep` to
`0.007 0.009 <joint smoothstep`, and did not check it in.

> I thickened the grout myself in the editor. Look at what I changed and keep it — but check the
> small green diamonds: with the wider grout, are they still readable? Fix them if not.

`InspectStackSLStudy` reported the editor as dirty and returned the person's text. The model named
the change, looked at the live window, judged both sizes of diamond readable, and made no edit. The
person then pressed **Check in** in the card.

![The live window with the person's wider grout: terracotta ten-point rosettes, cream ground, deep-green diamonds](assets/stacksl_conversation/tiles/images/ef6f2c1fe78a3a5f37c6.jpg)

<!-- stacksl-study-source:tiles-final -->
??? example "The complete program · r5-95e30638631a"

    ```diffstatic {data-lang=stacksl}
    # Still zellige: all geometry and glaze depend only on position.
    # Fold a point into one of ten mirrored wedges.
    : wedge
      polar >pol
      <pol y 0.314159265 + 0.628318531 mod 0.314159265 - abs
      <pol x swap vec2 cart
    ;
    # Ten-point star with the same tip and valley radii as the original.
    : star
      wedge dup x swap y 1.707717 * + 0.465 - 1.707717 dup * 1 + sqrt /
    ;
    # Ten-sided central white medallion.
    : medallion
      wedge x
    ;
    # Four by four repeating rosettes.
    uv 4 * >grid
    <grid fract 0.5 - >q
    <q star >edge
    <q wedge >fold
    <q medallion 0.132 - >hub
    # Alternating terracotta facets, cream ground, tiny deep-green corner diamonds.
    [0.92,0.84,0.68,1] >ivory
    [0.48,0.135,0.065,1] [0.62,0.205,0.105,1]
    <q polar y 0.314159265 + 0.628318531 / floor 2 mod mix >cobalt
    <ivory <cobalt -0.0015 0.0015 <edge smoothstep 1- mix
    <ivory -0.0015 0.0015 <hub smoothstep 1- mix
    >ceramic
    # A small deep-green diamond sits at each junction of four repeats.
    <q abs 0.5 swap - x+y 0.105 - 0.707107 * >goldedge
    <ceramic [0.012,0.095,0.045,1] -0.0015 0.0015 <goldedge smoothstep 1- mix
    >ceramic
    # Deep-green centre jewel, deliberately much smaller than the terracotta petals.
    <q abs x+y 0.045 - 0.707107 * >jewel
    <ceramic [0.012,0.095,0.045,1] -0.0015 0.0015 <jewel smoothstep 1- mix
    >ceramic
    # Grout follows star edges, hub, centre jewels and the ten petal divisions.
    <edge abs <hub abs min <goldedge abs min <jewel abs min >joint
    <fold x 0.309016994 * <fold y 0.951056516 * - abs
    <hub 0.004 > * <hub 0.004 <= 1 * +
    <joint min >joint
    # Straight background joints end at the rosette and corner diamonds.
    <q abs dup x swap y max 0.5 swap -
    <edge 0.006 > * <edge 0.006 <= 1 * +
    <joint min >joint
    # Subtle static glaze variation; dark, narrow recessed mortar.
    <ceramic uv 95 * noise 0.045 * 0.955 + *
    >glaze
    [0.024,0.028,0.035,1] <glaze 0.007 0.009 <joint smoothstep mix
    ```
<!-- /stacksl-study-source:tiles-final -->

### An aurora over hills

> A night sky over a dark hill line: an aurora curtain of green fading into violet, slowly rippling,
> and a few faint stars.

On the first turn the model traced its draft, reading the stack at one point, patched it, and
checked three frames and the window. The trace sheet shows what each line leaves on top of the
stack. In this recording, lines that end in a store show only the program's input, drawn as a colour
ramp; the trace now shows the value each of those lines stored:

![Trace: one tile per line, from the sky gradient through the hem mask to the finished scene](assets/stacksl_conversation/aurora/images/562d0a9c1ae5c25c85e4.png)

> The hills must stay perfectly still and black. Check whether anything moves below the horizon,
> and if it does, fix it without touching the sky.

To check, the model used variants as instruments: a diagnostic candidate that kept only the pixels
below the ridge, rendered at 0, 4, 9 and 30 seconds. Nothing moved there, but the hills were
blue-black. Its patch added two lines at the end: exact black at and below the unchanged ridge. A
stage comparison showed that everything before those lines was identical, and a second diagnostic
variant confirmed that the hills were uniformly zero.

Then the person pointed at something in the window:

> In the middle, under the curtain, there's a big soft dark mound that reads like a second, nearer
> mountain — with stars in front of it. What is that shape, exactly? Show me what makes it, then make
> it read as open sky.

![The live window before the mound turn: the aurora's lower edge outlines a dark shape like a nearer hill](assets/stacksl_conversation/aurora/images/209540814b0b94be1793.jpg)

The model answered with four variants in one sheet. **sky-only** removes the aurora: the mound
disappears. **cutoff** draws the aurora's lower-edge mask alone: it is the mound's outline. The
other two are candidate fixes.

![Diagnostic variants: current, sky-only, the cutoff mask, soft-glow and open-sky, at three times](assets/stacksl_conversation/aurora/images/28c11eb441fccc6df23f.png)

Its answer: the mound was negative space. The curtain stopped abruptly along a wavy edge, and the
unlit sky below that edge read as a solid shape. The stars were background stars. Since the request
included making it read as open sky, the model adopted **open-sky** itself: ray tips of varied length
that fade out instead of ending on one edge.

> Make the curtain hang more like fabric, with vertical folds. Try two or three versions and I'll
> choose.

The model offered fine pleats, broad drapes and gathered silk; the person adopted **C**, gathered
silk, in the card.

> I took C in the card. Now slow the whole ripple to about half speed, keeping everything else
> exactly as it is, and show me it really moves about half as much.

The model found the three time rates among the program's knobs and halved them with
`SetStackSLNumbers`:

<!-- stacksl-study-source:aurora-slower -->
```diff {data-lang=stacksl}
:::before
: aurora-hem >q
  <q x 7 * time 0.12 * + sin 0.035 * 0.43 +
  <q x 17 * time 0.08 * - sin 0.018 * +

# Uneven gathers: folds bunch together, then open into wider hanging panels.
uv x
uv x 13 * sin 0.035 * +
uv x 6 * time 0.16 * + sin
uv y 1- 2 pow * 0.04 * + >fold
:::after
: aurora-hem >q
  <q x 7 * time 0.06 * + sin 0.035 * 0.43 +
  <q x 17 * time 0.04 * - sin 0.018 * +

# Uneven gathers: folds bunch together, then open into wider hanging panels.
uv x
uv x 13 * sin 0.035 * +
uv x 6 * time 0.08 * + sin
uv y 1- 2 pow * 0.04 * + >fold
```
<!-- /stacksl-study-source:aurora-slower -->

Over the same first second, mean frame-to-frame change fell from 0.693 to 0.348: 50.2% of the
original. The model said that this measures image change, not distance travelled. It also pointed
out that the slowed program at 2 and 4 seconds reproduces the original at 1 and 2 seconds. Every use
of `time` in the program is multiplied by one of those rates, so that is exact, and the replay test
below checks it.

![The final live window: gathered green-to-violet folds with faint stars over black hills](assets/stacksl_conversation/aurora/images/804f9a6512820314a518.jpg)

<!-- stacksl-study-source:aurora-final -->
??? example "The complete program · r7-988cdeeb24a0"

    ```diffstatic {data-lang=stacksl}
    # (asked) Night sky, dark hills, green fading to violet aurora, slow ripples, faint stars.
    # (chosen) A luminous pleated curtain above a low, perfectly still, pure-black horizon.

    # A gently folding lower edge; one coordinate in, one height out.
    : aurora-hem >q
      <q x 7 * time 0.06 * + sin 0.035 * 0.43 +
      <q x 17 * time 0.04 * - sin 0.018 * +
    ;
    # Soft, fixed pinpoints; one star position in, one brightness out.
    : starlight length 620 * dup * neg exp ;
    # Static hills with smaller irregularities along their crest.
    : hill-line >q
      <q x 6 * 0.8 + sin 0.044 * 0.15 +
      <q x 15 * sin 0.022 * +
      <q [32,2,0,0] * fbm 0.018 * +
    ;

    # Midnight blue, subtly lighter toward the horizon.
    [0.002,0.004,0.014] [0.008,0.019,0.033] uv y 1- 2 pow mix >sky

    # A few faint stars, kept still while the curtain moves.
    uv [0.08,0.78] - starlight
    uv [0.19,0.92] - starlight 0.7 * +
    uv [0.31,0.69] - starlight 0.6 * +
    uv [0.43,0.88] - starlight +
    uv [0.57,0.95] - starlight 0.55 * +
    uv [0.69,0.76] - starlight 0.8 * +
    uv [0.83,0.91] - starlight 0.6 * +
    uv [0.94,0.65] - starlight +
    uv [0.13,0.37] - starlight 0.45 * +
    uv [0.75,0.34] - starlight 0.5 * +
    [0.15,0.18,0.24] * <sky + >sky

    # Height above the rippling hem.
    uv time >w aurora-hem >hem
    uv y <hem - >height

    # Uneven gathers: folds bunch together, then open into wider hanging panels.
    uv x
    uv x 13 * sin 0.035 * +
    uv x 6 * time 0.08 * + sin
    uv y 1- 2 pow * 0.04 * + >fold
    <fold 82 * >pleat
    <pleat <pleat sin 0.6 * + cos 0.5 * 0.5 +
    1.7 pow 0.74 * 0.065 + >rays
    <fold 245 * sin 0.5 * 0.5 + 0.18 * 0.82 + <rays * >rays

    # Diffuse lower tips of varied length, rather than a single silhouetted edge.
    <height 0 min
    <fold 37 * 0 vec2 noise 0.1 * 0.08 + /
    dup * neg exp
    <height 0 max 6 * neg exp *
    0.72 1.0 uv y smoothstep 1- *
    <rays * >curtain

    # Emerald at the hem blends into violet higher in the curtain.
    [0.025,0.46,0.13] [0.22,0.028,0.4]
    0.035 0.28 <height smoothstep mix
    <curtain * <sky + >scene

    # A near-black hill silhouette occludes everything behind it.
    uv hill-line >ridge
    [0.001,0.002,0.0035] <scene
    -0.001 0.002 uv y <ridge - smoothstep mix
    saturate
    # Exact black at/below the static ridge; leave every above-ridge pixel untouched.
    black uv y <ridge <= select
    ```
<!-- /stacksl-study-source:aurora-final -->

## The saved conversations

Each turn's transcript keeps the request, the tool activity, every image returned to the model, and
its reply. The person's actions between turns are shown where they happened.

| Session | Turns |
| --- | --- |
| Ink in amber water | [An ink bloom](assets/stacksl_conversation/ink/01-bloom.html) · [Three ways to give it structure](assets/stacksl_conversation/ink/02-structure.html) · [Light in the water](assets/stacksl_conversation/ink/03-water.html) · [A still and a clip](assets/stacksl_conversation/ink/04-export.html) |
| Zellige tiles | [Eight-point rosettes](assets/stacksl_conversation/tiles/01-zellige.html) · [Six, eight, ten or twelve points](assets/stacksl_conversation/tiles/02-symmetry.html) · [Recolour](assets/stacksl_conversation/tiles/03-recolour.html) · [The person's grout](assets/stacksl_conversation/tiles/04-grout.html) |
| An aurora over hills | [Night sky and aurora](assets/stacksl_conversation/aurora/01-aurora.html) · [Still, black hills](assets/stacksl_conversation/aurora/02-still-hills.html) · [What is that mound?](assets/stacksl_conversation/aurora/03-mound.html) · [Folds like fabric](assets/stacksl_conversation/aurora/04-folds.html) · [Half speed, measured](assets/stacksl_conversation/aurora/05-slower.html) |

The three documents ship with Field, under `dev_docs/agents/demos/stacksl_conversation_ink`,
`…_tiles` and `…_aurora`. Each holds its revisions, its held variants, the tool I/O of every turn,
and the chat. Copy one into your workspace rather than opening it in place, because continuing the
conversation adds to its history:

```diffstatic {data-lang=bash}
cp -R dev_docs/agents/demos/stacksl_conversation_aurora ~/Documents/AFieldWorkspace/
```

Open the copy: the window opens and draws the finished program, and the program box shows its
card. The recorded turns are in the program box's AI4 chat. Opening the chat does not call a model;
sending a message continues from the last revision.

![The aurora document's program box with its AI4 chat restored, scrolled to the reply explaining the mound, followed by the folds request and its variant sheet](assets/stacksl_conversation/saved-chat.png)

## Limits

- Study programs are single-pass: `sample`, `prev` and feedback are refused, so ping-pong and
  trail effects from the [StackSL examples](stacksl_examples.md) are out of reach here.
- The evidence is sampled. Frames are exact at the times and sizes asked for; a comparison holds at
  pixel centres and at those times, and a feature narrower than a pixel can fall between samples.
  Motion is measured as image change between frames.
- With the window closed, renders fall back to the CPU interpreter, and a heavy program takes
  minutes to export. The tools report which renderer drew each result.
- The window's clock runs freely. When the model captures the window, it sees whatever moment the
  window is at; tool images are the exact frames.

??? aside "How these sessions were recorded, and what checks them"

    Each turn went through the `ai-assist` RPC that Field's chat panel sends; the prompts are in each
    document's `prompts/`. The person-side actions clicked the card's Adopt, Keep and Check in
    buttons in a headless browser; for Keep, the slider's value was set first. The tiles grout edit
    wrote the program box's `stackSL` through the property the editor syncs to; no keystrokes were
    simulated. Each action is recorded in `conversations/*.human.json`. Both halves are
    `tools/conversation_demo.py` (`record` and `act`), which keeps the document running between
    invocations. The three screenshots of Field itself come from
    `tools/capture_stacksl_conversation.py`, which reopens a copy of the aurora document and first
    checks that the chat Field restores is byte for byte the one saved; the held stage is a click on
    the stage's tile in the card, in the same browser.

    The sessions were recorded when the tools were a box in each document, before they became
    library code and before the card moved onto the program box, and with a box of Kotlin above the
    program that opened the window, where the root now holds no code. The tools and their names were
    the same; one kind of result has changed since, as the tiles session describes: a line that ends
    in a store now shows, and compares, the value it stored. The documents have since been
    rearranged into today's two boxes, keeping each session's revisions and chat.

    `StackSLConversationExamplesTest` replays all three on the CPU interpreter, a different renderer
    from the GPU the sessions used. Every saved revision must still pass the check, the program box
    must hold `HEAD`, every recorded `CompareStackSLStudy` must give the same verdict again, and each
    person-side action must have produced the revision it records. It also checks the aurora's half
    speed directly: the new program at 2t matches the old one at t. The FIELD_TEST
    `stacksl.study.gpu-agreement` compares GPU renders with the CPU interpreter on six programs, with
    tolerances by kind of program, and times four 256² frames of a five-octave noise program.

    The sessions shown are the third recording: after the GPU path replaced CPU rendering, and after
    the evidence images were given the window's sRGB encoding.
