# Words becoming weather

Soften one word into a broad Gaussian cloud while neighbouring words, dots and lines stay sharp.
At zero blur, the word returns to analytic Slug outlines. The blur belongs to the text, so turning
your head uses the current eye pose without accumulating old headset images.

## Score a slow entrance and exit

Create separate text items for words that need separate blur clocks. A multiword run blurs
as one item; inline style spans do not have independent blur controls.

```kotlin
val score = diagramScore(graph, duration = 30.0) {
    blurIn(word, at = 0.0, seconds = 8.0, radius = .9f)
    blurOut(word, at = 18.0, seconds = 9.0, radius = 1.2f)
    fadeOut(word, at = 24.0, seconds = 5.0)
}
```

Radius is the Gaussian standard deviation in **ems**, relative to the font size. A radius of
`.9f` is already a very broad blur. Blur and opacity are independent: leave opacity alone to keep
the ink's total energy while it spreads, or fade it separately. Broad clouds have lower
peak brightness; the blur does not artificially renormalize their peaks. The tracks are
seekable and additive, so an entrance and exit can coexist without modifying the graph.

The sixth playground, **Words becoming weather**, provides a complete slow example.

## Live control

Reserve the maximum radius before constructing `StereoDiagramRenderer` / launching Drawing:

```kotlin
val word = text("word", "weather", peg) {
    blurCapacity = 1.5f
}
// Later, from a slider or an animation:
word.blur = .65f
```

Score helpers reserve their ranges automatically when the score exists before Drawing.
Reservations include the base `word.blur` plus the sum of the score tracks' maxima.
`blurCapacity` is also useful when re-scoring live: reserve enough for future edits.
If a new word was not reserved, it stays sharp; if a radius exceeds its reservation, it
clamps. `drawing.textBlurNeedsRebuild` reports either case. Rebuild Drawing to allocate the
new range. No allocation or Gaussian rebuild occurs during normal scrubbing.

## Quality and cost

The cache renders Slug coverage into padded, linear R16F masks and builds fifteen Gaussian
levels once. The renderer interpolates between levels by Gaussian variance, switching from the
analytic outline near zero. Padding extends four standard deviations beyond each side.

The library defaults use a 2048 × 1024 source and retain at least 128 × 128 pixels at broad
levels. This document starts with a **512 × 512 minimum** for close inspection in VR.
Two distinct prepared text shapes share an array batch; a partially filled batch costs the
same. Each default batch uses about 22.3 MiB of mask image storage, excluding driver overhead.
Only shapes with a nonzero reservation allocate a cache. Gallery scenes reserve their masks
together, so switching examples needs no new blur preparation. Identical prepared text can
share masks; changing fonts or text requires the usual typography preparation and Drawing
rebuild.

Use `ReadingTextBlurConfig(width, height, minimumSize)` through the renderer's
`textBlurConfig` argument to change cache resolution. Higher resolution helps very long runs
or close inspection, at a memory and one-time preparation cost. Cache preparation precedes
the scene on its command buffer; its first frame can take longer. Steady frames sample the
cache rather than running fifteen Gaussian passes again. This is not temporal AA or a
headset-image blur. The existing green-controlled scene compositor remains independent.

## Display dither

Drawing's **Dither (8-bit code steps)** control adds monochrome triangular noise after grading
and spatial AA, in perceptual code space. Zero disables it; about 0.65 is a gentle starting
point for a downstream eight-bit display conversion. The noise is static in screen coordinates
and black backgrounds remain black. It changes neither the blur cache nor headset pose timing.


## Blur energy and artistic shaping

Box **12 → text blur appearance** contains three separate controls:

- **Text blur contrast (power)** applies `coverage^power` to the filtered red/blue light.
  **1** retains the normalized linear blur. Values above 1 trim faint tails and make the
  result more concentrated; values below 1 expand the faint glow. Try **1.5–2** to tighten it.
- **Text blur gain** multiplies that shaped light. Its neutral value is **1**. Increase it
  after raising contrast if you want brighter remaining highlights. It affects cached
  blurred text, while the analytic sharp-text contribution remains unchanged.
- **Final contrast (power)** applies the compositor's master artistic exponent to the
  whole scene. **1** is neutral; values above 1 darken midtones and increase contrast.
  Internally this is the reciprocal of `grade.gamma.w`. Display sRGB encoding is separate.

The mask is still generated and Gaussian-filtered as linear **R16F coverage**. Text contrast
is a post-filter artistic power, not a different Gaussian averaging space. It intentionally
changes total emitted light. Gain and contrast happen before score opacity is applied,
so entrance/exit fades remain independently controllable. Green control data and alpha
coverage retain the unshaped, linear mask. Premultiplied additive blending keeps those
channels independent from the red/blue artistic light.

`drawing.textBlurConfig.appearance` is `(gain, power, reserved, reserved)` in the core API.
The document's `textBlurGain` and `textBlurContrast` root properties bind the draw shader's
`TextBlurTone.appearance` uniform. These settings need no mask rebuild and no history image;
all word transforms and eye poses remain current-frame values.

The separate **frost gamma** control changes the averaging space of the *backdrop frost*;
it does not change word-local text blur. Frost brightness and black level intentionally
change the light behind a panel too. For a neutral reference use word gain/power 1,
final contrast 1, frost gamma/brightness 1 and frost black level 0.

The real-GPU smoke `four.reading.text-blur-energy` measures every cached Gaussian level's
normalized integral, compares a sharp and blurred word before grading, and checks gain,
contrast, unchanged green/alpha, and an exact neutral reset. This checks numerical linear
energy; it is not a measurement of perceived brightness or headset luminance.


## Resolution and Gaussian tap controls

Box **12 → text blur quality** stages these settings:

| Control | Effect |
| --- | --- |
| Cache width / height | Base Slug coverage resolution; helps small detail and long runs. |
| Broad level minimum | Minimum dimension of later cache levels. Raise this first for blocks seen up close. The document starts at **512**. |
| Levels per size halving | **2** is the original schedule. Higher values retain resolution for more blur levels. |
| Maximum taps per axis | Maximum Gaussian samples in each separable preparation pass. **513** in the document means a radius cap of 256 samples. Actual short kernels use fewer taps. |
| Gaussian support (sigma) | Tail extent, normally **4** standard deviations. Padding expands for support above 4. |

Press **Apply text blur quality** after adjusting the staged values. The masks regenerate
on the next acquired frame, with no new XR session, physics reset or score restart. The
frame output reports the active resolution, minimum size, tap budget and cache MiB. The
first frame can hitch while the GPU rebuilds; subsequent frames reuse the prepared masks.
Sliders themselves only stage values. On a later Drawing launch, those staged values
become the startup quality.

For an A/B comparison, use box 22's **Hold both readings**, keep your head in one place,
and compare broad minimum **128**, **512**, then **1024**. Try **4096 × 2048** if the
base masks are still visible. Larger masks and slower downsampling use more VRAM; the
renderer checks a 2 GiB text-cache image budget before accepting a rebuild. This excludes
other scene/frost images and driver overhead. The previous caches retire through Field's
GPU cleanup queue, so old and new allocations briefly coexist. The budget is available
in code as `drawing.maximumTextBlurBytes`.

The tap limit controls sampling density rather than squeezing the blur radius. When a
kernel needs more samples than the budget, its sample spacing grows to retain the chosen
sigma support. Too few taps can therefore introduce sampling artifacts; more taps reduce
them at a one-time preparation cost. These are preparation taps, not extra sampling work
on every headset frame.

In code, call `drawing.requestTextBlurRebuild(ReadingTextBlurConfig(...))`. The constructor
accepts `width`, `height`, `minimumSize`, `levelsPerHalving`, `maximumRadius`, and
`sigmaCutoff`. The rebuild retains text gain/contrast, current scene pose and clock, and
refreshes the frost captures' borrowed text modules before retiring the old resources.
Changing Field's core API requires a fresh JVM once; later quality applies do not.
