# Slider handling (2026-10-11)

All `.field-slider` elements (standalone `slider`, `sliders`, `controlGroup.slider`, `syncToProperty`)
share one interaction layer in `src/commonMain/resources/sliders.js`:

- plain press: absolute jump-and-follow (unchanged); shift: relative drag at `SLIDER_FINE` (0.1),
  switchable mid-drag without a leap. A collapsed (0-width) panel uses the SVG's nominal track width.
- focused keys: arrows = `step` (shift x10, alt /10) on linear laws, 1% of travel on curved laws;
  PageUp/Down 10%, Home/End, Backspace/Delete reset, Enter edits, Esc blurs.
- double-click track resets to `data-default` (`buildSliderHtml(defaultValue=)`, the declared
  `initialValue`, not a cached value); click the readout to type (quantized + clamped).
- `syncToProperty` now starts from the stored property and has a `min`/`max` overload; it writes the
  starting value on first run.

Known gaps: vec2/colour controls got none of this; `display` to the same channel replaces earlier
controls (a standalone `slider` and `syncToProperty` both default to "controls"); output panels
size well beyond their controls. Verified via backdoor page-eval synthetic events, not real input.
