# Decide what ThreeCameraSpec.distance means in the Three.js viewer

- **Handoff:** `4ee69c76-acf4-4500-827d-c05a6a76a9a6` (project)
- **Filed from:** https://openendedgroup.com/field4/three_output/
- **Filed by:** human, 2026-10-16 02:48
- **Close with:** `python tools/docs_notes.py handoff-close 4ee69c76 "what happened"`

Refiled from db14dbf7-55c3-43a4-ba14-4290353acd57, 2026-10-16. The aside "In the viewer, `distance` is a padding factor" and the "Framing differs" bullet on `three_output.md` stand.

What the code does: `threejs_viewer.html` `applyCameraSpec(..., fit = true)` places the eye at `radius × distance` (as the rasterizer does) and then `fitCameraToBounds` re-fits the silhouette with margin `max(1.02, distance / 2.7 × 1.06)`. Every caller passes `fit = true`, so below about 2.6 all distances frame identically.

Why this wasn't changed here: ten captured docs figures pass an explicit `distance`: `polytope_examples.md` 2.3, 2.4, 2.5, 3.2 (×3) and `solid_notation.md` 3.6. Today the low values all get the tight 1.02 fit.
- **Honour `distance` literally**, skipping the fit when it isn't the default: at the viewer's 40° vertical field of view, a camera at 2.3 r sees only ±0.84 r, so those figures would crop.
- **Drop the floor** (margin = distance / 2.7 × 1.06): 2.3 gives a 0.90 margin, which also crops slightly and re-frames every figure.

Decide one of:
1. Align: make the viewer's distance mean the rasterizer's (with a field of view that fits a sphere at 2.7), and re-tune and recapture those figures.
2. Rename: split `ThreeCameraSpec.distance` into the rasterizer's `distance` and a viewer `padding`, so the name says what each renderer does.
3. Keep fit-to-panel as the viewer contract and document it in the KDoc (then the aside can go and the bullet stays).

## Decision, 2026-10-16 (Marc)

**Split.** `ThreeCameraSpec.distance` keeps the rasterizer's meaning (eye at radius × distance). The Three.js viewer gets its own `padding` (the fit margin) and stops deriving its framing from `distance`. Choose `padding`'s default so figures that pass no value frame as today, and migrate the ten callers so their viewer framing is unchanged (today they all get the 1.02 floor). The aside "In the viewer, `distance` is a padding factor" goes; the "Framing differs" bullet becomes a short sentence naming both fields. Filed as a code handoff.
