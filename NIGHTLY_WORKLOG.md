# Nightly worklog — RectSL data-visualization operator family

Branch: `nightly/2026-07-19/1-rectsl-dataviz-family` (based on `2d3011d0`)
Kind: A (the night's big swing). Status: **done**.

## Plan (refined after reading the real code)

Goal: give RectSL a first-class data-visualization family. On inspection the idea
held up well; the only pivots from the brief were driven by what actually exists:

- **No numeric-list Value type existed.** The `4cb6f3ff` array/grid/radial primitives
  are *layout iteration* (count + body quote), not data containers. So a new
  `VData(List<Double>)` value was the honest, non-forking choice (there was nothing
  to reuse). Colours **do** reuse `Color4`/`PaintSpec` — no parallel colour system.
- **Colormaps** live in a new `Colormaps` object (reusable domain code, single source
  of truth) built from published control points: viridisLite 9-class hex swatches for
  viridis/magma/inferno/plasma, the `turbo_srgb_floats` LUT sampled every 16th entry
  for turbo, Moreland's cool→warm table for coolwarm, plus grayscale. (The web-fetched
  256-float matplotlib arrays came back garbled from the summarizer — swapped/degenerate
  rows — so I used the canonical 9-class hex palettes I could verify, and confirmed every
  ramp by eye against the rendered strip.)
- **Chart words** produce plain rects + colormap colours through the existing
  `SceneBuilder`; the renderer is untouched. They return an ordinary `Selection`, so
  `each`/tracks/`stagger-time`/`group` still compose on the result.

## What changed

Core language — `src/jvmMain/kotlin/afield/rectsl/RectSL.kt`:
- `Colormaps` object + `normalizeValues()` helper.
- `VData` value type (+ `describe()`), `popData`/`stackDepth`/`drainNumbersAbove` VM helpers.
- New words: `colormap`/`cmap`; `data`, `linspace`, `data-len`, `data-at`, `extent`,
  `dmin`, `dmax`, `normalize`, `data-map`; `bars`, `heat`/`heatmap`, `scatter`.

Docs / coverage:
- `public/docs/rectsl_cheatsheet.md` — new "Data visualization" section (every new
  operator documented).
- `public/docs/rectsl_examples.md` — worked, runnable "Data visualization" section
  (this is the coverage corpus).
- `src/jvmMain/kotlin/afield/examples/rectsl/RectSLExamples.kt` — alias groups
  `colormap`↔`cmap`, `heat`↔`heatmap` for the coverage guard.

Verification + demo:
- `src/jvmMain/kotlin/afield/examples/rectsl/RectSLDataVizProof.kt` — FIELD_TEST
  `rectsl.dataviz.proof` (jvm-main): renders every colormap from the table and
  CPU-rasterizes the *real* `SceneProgram.sample()` output of the worked programs;
  asserts colormap endpoints + non-empty coverage. Renders to a temp dir as a test
  (no committed writes); `RECTSL_PROOF_DIR` regenerates the committed PNGs.
- `rectsl-dataviz-proof/*.png` — the eyeballed proof renders.
- `dev_docs/agents/transcripts/rectsl_dataviz.yaml` — the loadable demo.

## Verification (what I actually observed)

- **Compiles:** `./gradlew compileKotlinJvm` — BUILD SUCCESSFUL (only pre-existing
  warnings in unrelated files).
- **Smoke / coverage:** `python3 tools/run_field_tests.py --id rectsl.examples.smoke`
  → PASS. Cheatsheet coverage green (I only use `colormap`/`heat` in the corpus, so
  the pass proves the `cmap`/`heatmap` alias groups compiled and are active).
- **Pixels (eyeballed):** `python3 tools/run_field_tests.py --id rectsl.dataviz.proof`
  → PASS, and I Read every PNG:
  - `colormaps.png` — all 7 ramps correct: viridis dark-purple→teal→yellow, magma/inferno
    black→purple→pink/orange→cream, plasma blue→magenta→yellow, turbo dark-blue→cyan→
    green→yellow→red, coolwarm blue→grey→red (diverging), grayscale black→white.
  - `data-bars.png` (magma), `heatmap-field.png` (turbo 8×6 sine field),
    `scatter-plot.png` (plasma sine, coloured by y), `colormap-legend.png` (viridis via
    `array`+`colormap`), `dataviz-demo.png` (legend + data bars) — geometry and colours
    all correct.
- **Real-engine replay (the demo):** launched a graphics Field via the backdoor and ran
  `transcript run rectsl_dataviz` → `applied=7 skipped=0 errors=0`. The figure box
  compiled and executed with **no diagnostics**: its output shows the generated fragment
  shader (1368 chars) and live "Frames", i.e. `RectAnimationOracle.compile(rectsl)`
  succeeded in the *real in-box engine* (so the new words parse/compile in-box, not just
  via the API) and the render loop runs.
- **Disk:** started ~9.0 GB free, ~7.2 GB free at the last capture — comfortably above
  the 3 GB floor throughout.

### Honest gap

I did **not** drive the GPU `RectSLGalleryHarness` shader path to mp4/png. It has no
lightweight CLI entrypoint (it is invoked only through the docs `fabricate-scene`
pipeline, which renders the whole gallery — heavy, and disk is tight), and adding my
examples to the gallery workspace would also require hand-editing the single-line
`metadata.edn`. Instead the pixel proof CPU-rasterizes the **real** `SceneProgram.sample()`
output (the true geometry + colours these words produce; only the GPU quad shader is
skipped, which these words never touch), and the demo was replayed through the **real**
GPU renderer (frames produced, no diagnostics). Between them the whole path is exercised
honestly.

## Risks & unknowns

- One-line risk: `bars`/`heat`/`scatter` normalize by each list's own extent (no shared
  domain / axes / labels yet), so they are single-series building blocks, not a full
  charting layer — fine for the stated "dense data viz" target, but not a plotting API.
- Colormaps are 9-stop (17 for turbo) linear interpolations of published anchors, not the
  full 256-entry tables — visually faithful (verified) but not bit-exact to matplotlib.

## Recommendation

**Merge.** Self-contained, additive (no renderer or existing-word changes), compiles,
smoke + coverage + pixel-proof tests pass, colours verified by eye, and the loadable demo
was replayed through the real engine with zero diagnostics.

## Open / replay this to see it

Replay the demo into a fresh Field (shows the family live):

```
transcript write rectsl_dataviz --file dev_docs/agents/transcripts/rectsl_dataviz.yaml
transcript run   rectsl_dataviz        # via the agent backdoor / field_run_transcript
```

Static proof renders: `rectsl-dataviz-proof/*.png` (start with `colormaps.png`).
