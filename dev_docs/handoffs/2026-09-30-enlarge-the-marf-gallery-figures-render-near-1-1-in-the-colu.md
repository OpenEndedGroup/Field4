# Enlarge the marf gallery figures (render near 1:1 in the column)

- **Handoff:** `3f584304-d825-4e56-a3f6-b313fc25c67c` (project)
- **Filed from:** https://openendedgroup.com/field4/marf/
- **Filed by:** agent:claude, 2026-09-30 20:38
- **Close with:** `python tools/docs_notes.py handoff-close 3f584304 "what happened"`

## The notes that led here

- `8838d57d` (public/docs/marf.md:148): image could be bigger
  > The bank stops being five slots and becomes a sampled one-dimensional family of functions.

## What was asked

On `public/docs/marf.md` ("Addresses between slots"), the note on the continuous-address figure
(`assets/marf/marf-continuous-address.svg`) says "image could be bigger".

## Why this is bigger than a docs edit

The figure already fills the text column. `.md-typeset img` is `max-width: 100%` (plus a 63px
left bleed at ≥ 76em) in `public/docs/stylesheets/extra.css` (the "images: no upscale" block,
around line 120–165), and nothing on the site lets a single image extend past the column. The SVG
is 1100×348 user units, so in a ~680px column it is drawn at about 0.6×, and its 12px lane labels
come out near 7px. That is why it reads as small.

The SVG is generated, not hand-drawn: `MarfGallery.render` in
`src/jvmMain/kotlin/afield/four/synth/MarfGallery.kt` (constants `WIDTH = 1100`, `LANE_H = 74`,
`TOP`, `LEFT = 220`, the font sizes in the SVG text elements). All eight `assets/marf/*.svg` go
through that one renderer, and `MarfDocsGuardTest` holds the page to the gallery. Changing the
drawing means editing Kotlin and regenerating through the `synth.marf.gallery` capture, which
needs Gradle. Per AGENTS.md the pipeline owns captures, so they cannot be hand-edited.

## Approaches

1. **Re-proportion the gallery renderer** (recommended). Narrow the viewBox (e.g. `WIDTH` ≈ 760,
   `LEFT` ≈ 150) or enlarge the text and lane heights. The figure then renders near 1:1 in the
   column and its labels become readable. This applies to all eight marf figures, which share the
   problem.
2. **A site-wide wide-figure class**, e.g. `{ .field-wide }` through `attr_list`, that lets an
   image bleed into the margins the way wide tables already do (the "wide-table bleed" rules in
   `extra.css`). This is crosscutting, and it collides with `??? aside`, which floats into the
   same right margin at ≥ 82em.
3. **Click to zoom only.** There is a `field-docs-image-zoomable` class. Check whether marf SVGs
   already get it. This does not make the inline figure bigger.

## Questions for the human

- Should this be larger on this figure only, or on every marf figure? (They share one renderer.)
- Should the fix be larger text inside the drawing (option 1) or a wider figure on the page
  (option 2)?

## What the page needs afterwards

Nothing in the prose, as long as the figure keeps its id and file name. If the renderer changes,
re-run the gallery capture and check that `MarfDocsGuardTest` still passes.
