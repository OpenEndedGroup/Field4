# Nightly worklog — rectsl-plot-axes (2026-07-20)

Kind: A (big swing). Branch `nightly/2026-07-20/1-rectsl-plot-axes`.

## Plan (refined against the real code)

**Goal:** a real plotting layer for the RectSL data-viz family — a shared
coordinate frame so several series overlay in one domain, plus axes, ticks,
numeric tick labels, a legend/colorbar, and new domain-aware chart forms. Pure
RectSL builders (rects only) — no renderer changes.

**Findings that shape the design**
- RectSL renders ONLY rects (`createRect`) + groups. There is **no text/glyph
  primitive** anywhere (grepped `src/jvmMain/kotlin/afield/rectsl/`). So tick
  labels cannot use a font. Per the mission's honest-fallback rule I build a
  tiny **3×5 dot-matrix numeral font out of rects** (digits, `-`, `.`) — this is
  still "pure RectSL builders", not an invented renderer feature. Letters are
  not attempted, so textual titles/series names are out of scope (noted).
- `Colormaps`, `VData`, `normalizeValues`, `createRect/setX/Y/W/H/setFill/
  setRotation/translate`, `constantTrack`, `PaintSpec.Solid`, wall (centred at
  origin, +y up) are all reused exactly like the existing `bars`/`heat`/
  `scatter` words.
- **Hard compat guard:** `RectSLExamples.checkCheatsheetCoverage()` fails the
  build unless every cheatsheet operator appears in a worked example. Every new
  word documented gets demonstrated in a new `rectsl_examples.md` block; the
  `plot` alias collapses to `line` via `ALIAS_GROUPS`.
- **Hard compat guard 2:** existing `bars`/`scatter`/`heat`/`heatmap` are left
  **byte-identical** — untouched. The plotting layer is entirely NEW words, so
  existing programs cannot change behaviour.

**New VM state** (all default null/0 → invisible to existing programs):
`plotXDomain`, `plotYDomain`, margins L/R/B/T (wall fractions).

**New words**
1. Frame/domain: `frame` (auto default margins), `margins` (l r b t),
   `domain` (x0 x1 y0 y1), `xdomain`, `ydomain`. Domains lazily adopt the first
   series' extent so later series + axes share it.
2. `axes` — x/y axis rules + "nice-number" ticks (Heckbert algorithm) + numeric
   labels (rect-numeral font) drawn in the margins + faint gridlines.
3. `colorbar` (continuous ramp + min/mid/max numerals) and `legend`
   (n discrete colour swatches) — the "coloured swatches / numeric legend".
4. New domain-aware chart forms: `line`/`plot` (rotated-rect polyline),
   `area` (column-filled area to baseline), `points` (domain-aware scatter).

**Verification plan:** compile jvm; run `rectsl.examples.smoke`; extend the
pixel proof (`RectSLDataVizProof`) with an axes+legend+overlay program, render
to PNG, and eyeball it. Ship a transcript demo and replay it through the render
path.

---

## Execution log
(filled in below as work proceeds)
