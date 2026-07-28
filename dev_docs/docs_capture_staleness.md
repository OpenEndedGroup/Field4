# Docs-capture staleness: how to actually detect it

Written 2026-07-28, from the pass that tried to use a docs build as a staleness detector for a
stdlib change and found that the obvious way does not work.

## 1. A normal docs build cannot detect stdlib staleness

The natural assumption is that `tools/build_zensical_docs.py` regenerates captures from the real
code, so any committed asset the build changes is a capture that no longer matches the code.

**It does not.** The capture cache digest (`public/docs_hooks.py`, `compute_digest` /
`compute_cache_metadata`) is keyed on:

- the fence's own code text (`codeDigest`),
- its options (`optionsDigest`), page path and block index,
- `SCRIPT_VERSION`,
- `runnerSourceDigest` — a hash of the **fixed list** `RUNNER_DIGEST_SOURCE_FILES`,
- `sceneInputs`, for bulk-render gallery scenes only.

`RUNNER_DIGEST_SOURCE_FILES` is eleven files: `docs_hooks.py`, `Main.kt`, `screenshot.kt`,
`FLine.kt`, `FLineGeometry.kt`, `StandardRendererDocsHarness.kt`, `NanoVisualization.kt`,
`someSkiko.kt`, `outputareas2.js`, `field_backdoor.py`, `run_field_tests.py`. **No stdlib file is
in it.** Change `Functions.kt`, `timeseries.kt`, `linalg.kt` — anything a fence actually calls —
and every digest is unchanged, so an incremental build is a 100% cache hit and rewrites nothing.

Worse, `reuse_existing_asset_for_incremental` explicitly reuses the committed asset when the *only*
thing that moved is `runnerSourceDigest`. So even adding a stdlib file to that list would not be
enough on its own.

**To actually re-derive captures from the code, set `FIELD_DOCS_RUNNER_MODE=always`.** That bypasses
the cache per capture (`cache_allowed = False`). `strict`/`fresh`/`rebuild` are also available; see
`RUNNER_MODE_ALIASES`.

## 2. Byte inequality is not evidence — and neither is a naive pixel compare

Captures are **not** byte-reproducible, and they are not pixel-reproducible either. Running the same
121 captures twice, from the same worktree at the same commit, 113 of the 121 differed.

The overwhelming majority of that is text/antialiasing jitter: **~0.005–0.01% of pixels at a maximum
channel delta of ≤19**. Treat that band as noise. Above it, two real signals were seen:

| run-to-run delta | what it is |
| --- | --- |
| 0% | genuinely stable capture (only 5 of 121) |
| ≤0.02% of px, Δ≤19 | AA/glyph jitter — **never commit this** |
| 0.25–1.5% of px, Δ~247 | a few plot captures whose curve genuinely re-renders differently run to run |
| output height changes | 4 `capture=box` shots whose box height is unstable (918 vs 800 vs 774 px) |

Two gotchas when measuring:

- **`Image.getbbox()` defaults to `alpha_only=True` on RGBA** in modern Pillow, so it returns `None`
  — indistinguishable from "identical" — whenever only RGB differs. It reported 118 of 121 files as
  pixel-identical when in fact none were. Use `ImageChops.difference(...).getextrema()` and a
  histogram, on images converted to `RGB`.
- Compare against `git show HEAD:<path>`, not against a working file you may already have
  overwritten.

## 3. Pre-existing staleness in `functions_examples`, from 2026-07-14

Running the 121 captures of `functions_examples`, `functions_domains`, `timeseries_examples`,
`interaction_nets_examples`, `graph_connectivity_examples` and `state_estimation_examples` in
`always` mode, twice, and correlating each file's delta band against the date it was last
regenerated gives an almost perfectly clean split:

| delta from committed asset | count | last regenerated |
| --- | --- | --- |
| **~49% of pixels, Δ204** | **54** | 2026-07-09 / 2026-07-10 |
| 3–13% | 34 | 2026-07-14 … 2026-07-19, one 2026-07-28 |
| height changed | 4 | 2026-07-19 |
| ≤0.02% (noise) | 29 | 2026-07-19 / 07-21 / **2026-07-28** |

The 54-file ~49% cluster is **stable across both runs** — it is not jitter — and every one of them
was last captured on 2026-07-09 or 2026-07-10. `bced282b` (2026-07-14) is
*"Fix `Fn.graphBrowseHtml` layout and constant-delay domain"*: it changed the plot browser's layout
and y-autoscale and those 54 captures were never regenerated against it.

What that looks like, concretely, in `assets/functions_examples/reference/ramp.png`
(`ramp(0.1, 0.9).crossfade(ramp(0.9, 0.1), center = 0.5, width = 0.25)`): the committed capture
autoscales its y-axis to roughly −0.1 … 0.51 and draws a zero line; the current code autoscales
tightly to **exactly 0.100 … 0.500**, which is the arithmetically correct range for that
expression (0.1 at both ends, 0.5 at the centre). About half the pixels move because the curve and
every gridline shift with the axis.

**This is inherited from `main`, not caused by any stdlib change.** `ramp` and `crossfade` are
untouched by the KDoc-truth branch, and `FunctionGraphBrowser.kt` — which does the autoscaling — is
not modified by it either. It is four days older than that branch's base and eighteen days older
than the branch.

It is worth fixing, in **its own commit on `main`**, not folded into an unrelated branch: regenerate
with `FIELD_DOCS_RUNNER_MODE=always`, and expect to have to sift the ~54 real ones out of the
run-to-run noise affecting the rest.

## Reproducing

```sh
# your own port: 9477 is shared and a reused server silently runs someone else's code
FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9722 \
  uv run --group docs python tools/build_zensical_docs.py --page functions_examples
git status --short public/docs/assets     # necessary, nowhere near sufficient — see §2
```

Then compare pixel-wise against `HEAD`, twice, and discard everything inside the noise band.
