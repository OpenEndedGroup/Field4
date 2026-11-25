# Give the colour library a Leonardo-style overhaul and expand the colours page

- **Handoff:** `828b4418-213c-4cb8-867e-fe0902d1ef44` (project, blocking: notes on the page wait for this)
- **Filed from:** https://openendedgroup.com/field4/colors_examples/
- **Filed by:** agent:claude, 2026-11-24 19:19
- **Close with:** `python tools/docs_notes.py handoff-close 828b4418 "what happened"`

## The notes that led here

- `c42691c7` (colors_examples.md): This page could be 4x as long and be bursting with color, and contain a great deal more color spaces than this — let's radically improve this library and give it the full leonardo-color treatment
  > A colour can look good on its own and disappear beside its neighbours. These examples draw a whole palette as geometry, so you can see how its colours work together.

## What was asked

The note on `public/docs/colors_examples.md` (intro, lines 3–4) asks for the page to be about four
times longer, "bursting with color", covering many more colour spaces, and for the colour
library itself to be "radically improved" and given "the full leonardo-color treatment". That
refers to Adobe's Leonardo (`@adobe/leonardo-contrast-colors`): palettes generated from key
colours and target contrast ratios against a background, interpolated in a chosen space (CAM02,
LCh, Lab, HSLuv, OKLab, OKLCh, ...), with themes that respond to lightness/contrast changes.

## Why this is bigger than a docs edit

The page can only show what `src/jvmMain/kotlin/afield/graphics/color/colors.kt` exposes
(761 lines). Its public surface today is `rgb`/`rgba`/`rgb255`, `hsl`/`hsla` (linear-light, not
CSS), `cssHsla`, `okhsl`, `okhsv`, the `hueSet*`/`*Set` hue-ring generators, and
`paletteForBackground` (`colors.kt:729#paletteForBackground`), which already nudges OKHSL
lightness toward a contrast ratio (`contrastAdjustedOkhsl`, `colors.kt:668`). Oklab exists only as
private helpers. There is no public OKLab/OKLCh, CIELAB/LCh, CAM02/CAM16, HSLuv, Display-P3, no
interpolation-space parameter on a ramp type, no contrast-targeted scale or theme object. Writing
four times as much page without those would be padding; the page grows when the library does.

## Proposed approach

1. Public colour-space constructors and conversions: `oklab`, `oklch`, `lab`, `lch`, `hsluv`,
   optionally `cam16`/`displayP3`, each returning `Vec4` with `toOklch()`-style readers.
2. A `ColorScale(keys, space = Space.OKLCH, smooth = ...)` that samples at `t` and by target
   contrast ratio (`scale.atContrast(4.5, against = bg)`), Leonardo's core idea.
3. A `Theme(background, scales, ratios)` object with lightness/contrast knobs that regenerate
   every swatch, so a slider can drive a whole UI palette live.
4. Gamut mapping (chroma reduction in OKLCh) and a WCAG/APCA contrast readout.
5. Then rewrite the page: one section per space with the same endpoints compared across routes,
   contrast-ladder figures, a live theme with sliders, and palettes on real geometry.

Alternatives: wrap Leonardo itself through RemoteJS (fast, but colour lives in the browser,
not box code), or port only OKLCh + contrast scales first and leave CAM/HSLuv for later.

## Questions for the human

- Native Kotlin port, or call Leonardo/culori in JS?
- Which spaces are must-have (OKLCh, CIELAB/LCh, HSLuv, CAM16, P3)?
- WCAG 2 contrast, APCA, or both?
- Should `hsl()` keep its linear-light meaning once a CSS-correct HSL is a first-class option?

## What the page needs afterwards

A full rewrite of `colors_examples.md` around the new API: a section per space, contrast-target
scales, a live themed example, and many more real captures.
