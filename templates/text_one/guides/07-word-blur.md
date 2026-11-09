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

Defaults use a 2048 × 1024 source and retain at least 128 × 128 pixels at broad levels.
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
