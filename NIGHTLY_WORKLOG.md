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

## Execution log — DONE

Status: **done** (a complete, verified plotting layer; further polish possible).

### Files changed
- `src/jvmMain/kotlin/afield/rectsl/RectSL.kt` — the plotting layer. New VM state
  (`plotXDomain`/`plotYDomain` + four margins, all default null/0) and words
  `margins`, `frame`, `domain`, `xdomain`, `ydomain`, `axes`, `line`/`plot`,
  `area`, `points`, `colorbar`, `legend`; helpers `plotFrame`, domain resolvers,
  `niceNum`/`niceTicks` (Heckbert), `formatTick`, `drawNumeral`, `plotRect`; and a
  file-scope `PlotFrame` + `PLOT_NUMERAL_GLYPHS` 3×5 rect font. Classic
  `bars`/`heat`/`scatter` are untouched.
- `src/jvmMain/kotlin/afield/examples/rectsl/RectSLDataVizProof.kt` — new
  `plot-axes` pixel-proof program (domain/frame/axes + overlaid area/line/points
  + colorbar/legend); rasterizer bumped 512→900px for legible numerals.
- `public/docs/rectsl_cheatsheet.md` — new "Plotting layer" subsection (table +
  illustrative block).
- `public/docs/rectsl_examples.md` — two worked `data-lang=rectsl` examples (shared
  figure + manual-axes variant); intro cross-links the plotting layer.
- `src/jvmMain/kotlin/afield/examples/rectsl/RectSLExamples.kt` — `line`/`plot`
  alias in the cheatsheet-coverage guard.
- `dev_docs/agents/transcripts/rectsl_plot_axes.yaml` — loadable demo.
- `src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt` — registered
  the transcript in COMPILE_ONLY.

### Verification (what I actually observed)
- `./gradlew -q compileKotlinJvm` — green.
- `rectsl.examples.smoke` — PASS; stdout: **"rectsl cheatsheet coverage: all 221
  operators demonstrated"** (my 12 new operators covered by the new worked
  examples; guard genuinely ran).
- `rectsl.dataviz.proof` — PASS; **I read the rendered `plot-axes.png`** (900px,
  via `RECTSL_PROOF_DIR`). Confirmed by eye: left y-axis with `0 0.2 0.4 0.6 0.8 1`
  rect-numeral labels, bottom x-axis `0 2 4 6 8 10`, faint gridlines, a viridis
  filled `area` (sine), a plasma `line` (cos dipping to 0 near x≈5.7), turbo
  `points`, a viridis `colorbar` labelled `0/0.5/1`, and a 5-swatch turbo `legend`.
  Not black/garbled — the figure is correct. (First render clipped the colorbar's
  `0.5`; fixed by auto-sizing colorbar labels + wider default right margin, re-ran,
  re-read the PNG — now clean.)
- **Demo replay:** `examples.demo-transcript.exec` — PASS. The `rectsl_plot_axes`
  transcript's TWO box bodies were compiled through the REAL K2 pipeline (eval
  skipped for graphics demos, per the harness); the figure box's generated GLSL
  compiled to SPIR-V; run ended `DemoTranscriptExecSmokeTest ok`. The RectSL
  program body itself (byte-identical to the transcript's `rectsl` string) was
  additionally driven through the real render path (compile→sample→raster) in the
  pixel proof and eyeballed. I did NOT launch a live on-screen GPU window (headless
  nightly) — that is the COMPILE_ONLY path this harness exists for.

### Risks & unknowns
- No text primitive in RectSL: labels are numerals only (digits/`-`/`.`). Axis
  titles / series names are out of scope and would need a real text feature — I did
  not invent one.
- Not run on a live GPU window; only the CPU raster + compile paths are exercised
  (same limitation as the existing `rectsl_dataviz` demo).
- Auto label sizing is heuristic; very long domains or tiny walls could still crowd
  labels (they shrink/skip rather than overflow).

### Recommendation
**Merge.** Additive, compat-preserving (classic chart words untouched; all guards
green), and verified to the pixels + replayed. Natural follow-ups: gridline/tick
styling words, a stroked (outline) line option, and a rect letter-font for titles.
