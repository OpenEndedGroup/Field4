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

## 4. `wfc_examples` — one figure that oscillates, one that is stale (2026-07-29)

Measured while checking whether a `contours.kt` change moved a downstream figure. All eleven
`wfc_examples` screenshots regenerated with `FIELD_DOCS_RUNNER_MODE=always`, **twice**, and compared
pixel-wise against the committed asset and against each other:

| file | committed vs run 2 | run 1 vs run 2 | reading |
| --- | --- | --- | --- |
| `collapse-frames.png` | **0 px** | **32.1% of px, Δ226** | oscillates — run 1 was a different collapse |
| `neighborhoods.png` | **35.7% of px, Δ204** | 59 px (noise) | reproduces run-to-run, but the committed image is **stale** |
| the other nine | ~0.01% of px, Δ≤27 | — | noise (see §2) |

So the two signals from §2 and §3 both show up on one page at once. The noise on the other nine is
confined to `x < 20` — the channel-name gutter and its disclosure triangle — which is a useful
fingerprint: **if every changed pixel is in the left 20 columns, nothing in the picture moved.**

Neither figure is caused by any stdlib change: `collapse-frames` and `neighborhoods` are WFC solver
output, and the branch that found this touches only `contours.kt` and `clustering.kt`. Worth fixing
in its own commit on `main`, and `collapse-frames` needs its seed pinned before regenerating is even
meaningful.

The same run answered the question it was for, and the answer is the useful kind of negative:
`wfc_examples.md` §7 **does** execute `Img.isoContourFLines` in a capture fence (a claim to the
contrary was made and was wrong), but making that function close its closed loops moved
`scalar-field.png` by **one pixel** outside the gutter — because every level set of that blurred
field runs off the image boundary, so `isoContourClosed` returns `false` for all of them.

## Reproducing

```sh
# your own port: 9477 is shared and a reused server silently runs someone else's code
FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9722 \
  uv run --group docs python tools/build_zensical_docs.py --page functions_examples
git status --short public/docs/assets     # necessary, nowhere near sufficient — see §2
```

Then compare pixel-wise against `HEAD`, twice, and discard everything inside the noise band.

## 5. Recording a *sweep*: the recorder's frame clock is not the box's (2026-07-29)

Separate from staleness, and the thing that costs the most builds when adding an animated
`diffstatic_runner` fence. Established while adding the two parameter sweeps to
`spatial_geometry_examples.md`.

A `video=` fence records until it has `duration * fps` frames and then re-times them to `fps`. It does
**not** record for `duration` wall-clock seconds. So the real length of the recording is
`duration * fps / captureRate`, and `captureRate` is whatever the machine manages.

Three consequences, all measured:

| claim | measurement |
| --- | --- |
| `frameNumber` is the wrong clock | the Apollonius box renders ≈4× faster than it is captured, so a ramp with period 160 *frames* ran four sweeps inside one 160-frame clip |
| wall-clock elapsed is the right clock, but `sweepSeconds = duration` is wrong | at `sweepSeconds = 8.0` the iso box's clip contained **1.6** sweeps: 160 frames captured at ≈12.5 fps is 12.8 real seconds, not 8 |
| the capture rate is not even constant *within* one recording | a linear there-and-back came back with limb slopes of **7.5 and 5.9 px/frame**, which a triangle ramp cannot produce |

What to do:

- Drive the swept parameter from `System.nanoTime()`, not `frameNumber`.
- Calibrate `sweepSeconds` against a *recorded* clip rather than setting it to `duration`, then
  re-record and re-measure. 9.0 and 8.6 put one sweep in an 8 s clip for those two boxes.
- Keep the per-frame cost low. Dropping the iso box's grid from 140×84 to 100×60 moved its capture
  rate from ≈12.5 to ≈16 fps and its picture is no worse.
- **Use a triangle ramp on a scalar, not a closed loop in position.** Videos embed as
  `<video controls autoplay loop muted>`, so the seam is visible on every cycle. With a triangle ramp
  a residual phase error is a small parameter offset (measured 4.7% and 6.2% of the swept range). With
  a circular orbit the same error is a *positional jump*: an edge-flip clip came back at 1.42
  revolutions per clip in one run and 0.78 in the next, and at 0.78 the mover hopped 18.7% of its path
  at the loop point — on a clip about edges snapping between configurations, indistinguishable from a
  flip. That clip was dropped rather than shipped for exactly this reason.

Measuring the achieved period does not need guesswork: extract frames with `ffmpeg -vf select`, locate
the moving element by colour, and fit its position against the frame index.
