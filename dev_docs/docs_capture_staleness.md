# Is that published figure real? — docs-capture staleness

Started 2026-07-28 from the pass that tried to use a docs build as a staleness detector for a stdlib
change and found the obvious way does not work. Extended 2026-07-29 with the fix and the tool.

**This is the canonical answer to "does that figure still match the code?".** The short version:

| question | answer |
| --- | --- |
| Did the docs build go green? | Irrelevant to freshness on its own. See §1. |
| Did an **incremental** build re-capture the figures my stdlib edit affects? | Yes now, for source the fence names *directly*. §4 |
| Are the committed figures on page X actually current? | Run the auditor. §5 |
| Two captures differ — does that mean anything? | Only above the measured noise band. §2 |

## 1. Why a normal docs build could not detect stdlib staleness

The capture cache digest (`public/docs_hooks.py`, `compute_digest` / `compute_cache_metadata`) is
keyed on:

- the fence's own code text (`codeDigest`),
- its options (`optionsDigest`), page path and block index,
- `SCRIPT_VERSION`,
- `runnerSourceDigest` — a hash of the **fixed list** `RUNNER_DIGEST_SOURCE_FILES`,
- `sceneInputs`, for bulk-render gallery scenes only,
- and, since 2026-07-29, `sourceRefsDigest` (§4).

`RUNNER_DIGEST_SOURCE_FILES` is eleven files: `docs_hooks.py`, `Main.kt`, `screenshot.kt`,
`FLine.kt`, `FLineGeometry.kt`, `StandardRendererDocsHarness.kt`, `NanoVisualization.kt`,
`someSkiko.kt`, `outputareas2.js`, `field_backdoor.py`, `run_field_tests.py`. **No stdlib file is in
it.** Before `sourceRefsDigest`, changing `Functions.kt`, `timeseries.kt` or `linalg.kt` left every
digest unchanged, so an incremental build was a 100% cache hit and rewrote nothing.

Worse, `reuse_existing_asset_for_incremental` explicitly reused the committed asset when the *only*
thing that moved was `runnerSourceDigest` — so merely adding a stdlib file to that list would not
have been enough on its own. Both halves had to change.

`FIELD_DOCS_RUNNER_MODE=always` has always bypassed the cache per capture (`cache_allowed = False`);
`strict`/`fresh`/`rebuild` are also available, see `RUNNER_MODE_ALIASES`. That is the raw material
the auditor uses.

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

Independently reconfirmed 2026-07-29 by `tools/docs_capture_audit.py` on `timeseries_examples`:
0.0066% and 0.0071% of pixels at maxΔ 13 and 18 — inside the band to three significant figures.

Two gotchas when measuring:

- **`Image.getbbox()` defaults to `alpha_only=True` on RGBA** in modern Pillow, so it returns `None`
  — indistinguishable from "identical" — whenever only RGB differs. It reported 118 of 121 files as
  pixel-identical when in fact none were. Use `ImageChops.difference(...).getextrema()` and a
  histogram, on images converted to `RGB`.
- Compare against `git show HEAD:<path>`, not against a working file you may already have
  overwritten.

Because the 0.25–1.5% nondeterministic band *overlaps* the magnitude of a genuine re-render, a
single run cannot tell them apart. Two runs can: compare run 1 against run 2 as well as against
`HEAD`. That is what `--repeat 2` is for, and it is the difference between a report you can act on
and a list of file names.

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

**This is inherited from `main`.** `ramp` and `crossfade` were untouched by the branch that found it,
and so was `FunctionGraphBrowser.kt`, which does the autoscaling.

## 4. The digest now follows the source a fence reaches

`sourceRefsDigest` (2026-07-29) hashes the contents of the source files a fence's **code text
actually names**. The symbol → file map is read out of the committed codebase reference
(`public/docs/reference/*.md`), which `afield.docs.StdlibReferenceGenerator` emits from
`KotlinSourceIndex.parseTopLevelDefinitions` and the `docs.codebase-reference.generate` smoke keeps
from drifting. So no Kotlin has to run to compute a digest, and there is no second hand-maintained
list to rot.

`reuse_existing_asset_for_incremental` now refuses to reuse when `sourceRefsDigest` moved, but still
reuses when the cache simply predates the key — so adopting this costs one grandfathering pass, not
one full-site re-capture.

**Scope, measured.** A name declared in more than one source file is dropped rather than mapped to
all of them (the reference lists data-class members, so `name`, `value`, `size`, `step` each appear
in a dozen files). Over all 304 runner fences:

| symbol→file rule | source files per fence (mean / max) |
| --- | --- |
| unambiguous names only (**the default**) | 2.7 / 10 |
| ≤2 files per name | 5.8 / — |
| no ambiguity limit | 10.1 / 44 |
| transitive file-reference closure | **72 of the 75 indexed files** |

That last row is the important one: the stdlib's own internal references are dense enough that any
depth greater than 1 is just "hash the whole stdlib". Hence the default stops at direct references,
and `FIELD_DOCS_SOURCE_REFS=stdlib` is the opt-in for hashing the whole indexed stdlib for every
fence — which does catch an indirect change, at the cost of re-capturing everything.
`FIELD_DOCS_SOURCE_REFS=off` restores the pre-2026-07-29 digests exactly.

**What it covers.** The reference indexes `src/jvmMain/kotlin/afield/stdlib` and
`afield/visualization`, minus the generated `stdlib/linalg`: 2315 unambiguous symbols over 75 files.
275 of the 304 runner fences map to at least one of them. The 29 that map to none are the fences
whose code is not Kotlin-over-stdlib at all (RectSL, StackSL, `afield.four` / `fabricate_renderer`,
`ar_reference`, the audio scenes) — those still ride on `runnerSourceDigest` alone.

**What it does NOT cover, and this matters:** an *indirect* change. `bced282b` moved plot layout
inside `FunctionGraphBrowser.kt`, and only 4 of the 106 `functions_examples` fences name a browser
symbol directly — the rest reach it through `Functions.plot`. The digest would have caught 4 of
those 54 figures. Detecting the other 50 is the auditor's job, not the digest's.

## 5. The auditor: `tools/docs_capture_audit.py`

```sh
# rigorous: two capture runs, so nondeterministic captures are separated from stale ones
uv run --group docs python tools/docs_capture_audit.py --page functions_examples --repeat 2 --port 9540

# no Field, instant: rank capture assets by the date they were last committed
uv run --group docs python tools/docs_capture_audit.py --inventory

# no Field: which files does each fence reach, and what would touching one invalidate?
uv run --group docs python tools/docs_capture_audit.py --plan \
    --touched src/jvmMain/kotlin/afield/stdlib/Functions.kt
```

It runs the real production path (`build_zensical_docs.py --skip-build --page …` with
`FIELD_DOCS_RUNNER_MODE=always`), copies everything the run rewrote into
`.field-backdoor/docs-audit/<timestamp>/run<N>/`, restores the working tree, and classifies each
asset:

| verdict | meaning |
| --- | --- |
| `identical` | byte-identical to the committed asset |
| `jitter` | inside the §2 noise band — not evidence of anything |
| `nondeterministic` | run 1 and run 2 disagree with each other; the capture does not reproduce itself |
| `nondeterministic-size` | output height/width unstable run to run (the `capture=box` class) |
| `stale` | two runs agree with each other and disagree with the committed asset |
| `stale-size` / `stale-text` / `stale-binary` | same, for dimensions, text assets, and mp4/wav |
| `suspect` | outside the band on a **single** run — re-run with `--repeat 2` |
| `unstable` | a run *did* match the committed asset, but the two runs disagree with each other — informational, not a failure |
| `unknown-failed` | the capture FAILED, so the stale file is still in place; **not** clean |

Exit code is 1 when anything is `stale*` or `suspect`, so it can gate. `--jitter-px-pct` /
`--jitter-max-delta` move the band; `--keep` leaves the regenerated tree in place;
`--reclassify <rundir>` re-runs the classification against a previous audit's stored captures with
no JVM at all, which is how to retune a threshold without paying for more capture runs.

**Fixing what it finds:** `--adopt` copies **only** the `stale*` assets from the run directory into
the working tree. Rebuilding a page with `always` and committing everything that changed would also
commit that page's jitter captures, which is pure history noise; adopting per verdict is how one
stale figure gets fixed without dragging fifty antialiasing diffs with it. `--reclassify <rundir>
--adopt` adopts from an audit that already finished. Then **look at each image** before committing.

Three rules the classifier follows, each of which was wrong in the first draft and got fixed by
looking at real captures:

1. **Max channel delta is the discriminator, not changed-pixel count.** AA/glyph jitter is many
   *low-magnitude* deltas; a high-Δ difference over a tiny area is one label drawing differently and
   must not be excused for being small. `graph_query/predicates.png` (0.0202% of px at **Δ2**) is
   plainly noise and an area-first rule called it stale.
2. **Drift is measured against whichever run agrees best with the committed asset.** If any faithful
   re-derivation reproduces what is published, the published figure is not stale.
   `graph_query/pathset.png` matched `HEAD` on run 1 and then differed from *itself* by 1.63% on
   run 2 — `unstable`, not stale.
3. **A figure can be stale *and* a little nondeterministic.** `filter_chains.png` under a perturbed
   `EMAFilter` moved 1.06% against `HEAD` while moving 0.24% between runs. Drift is called stale when
   it is ≥3× the run-to-run instability.

### What a `stale` verdict does and does not prove

It proves that **this machine, at this commit, with this capture environment, reproducibly does not
produce the committed asset**. It does not by itself say the *code* moved. A Chrome or font-metrics
change in the capture browser shifts text layout by a pixel or two, reproducibly, and reads as
`stale` — the two `graph_query` findings below look exactly like that: identical content, columns
shifted ~3 px. So read the magnitude and *look at the image* before calling a figure wrong. The
`--plan` output ("does any fence even reach the file I changed?") is the cheap cross-check.

Cost, measured on this machine: ~85 s wall for a 2-capture page including the Field server start;
a capture is ~5–10 s once the server is warm.

### Verified by catch-then-restore

`EMAFilter` in `timeseries.kt` was perturbed (`alpha` → `min(4·alpha, 1)`) in a worktree, which only
`timeseries_examples.md`'s first fence calls:

- **digest level**: with `FIELD_DOCS_SOURCE_REFS=off` (old behaviour) both fences' digests were
  byte-identical before and after the edit — a silent cache hit. With the default they both changed.
- **incremental build**: `Field docs runner cache miss: …/filter_chains.png (source this fence
  reaches changed, digest 8e84120514b1 -> da4725cf4164)`, and exactly those 2 of 304 assets were
  re-captured, in 20 s.
- **auditor** (`--repeat 2`, `timeseries_examples` + `graph_query` as a control page): exactly one
  figure came back `stale` — `filter_chains.png`, "drift (1.0569% px, maxΔ238) is 4.4x the
  run-to-run instability (0.2407% px, maxΔ60)". `distribution_events.png`, which does not call
  `EMAFilter`, stayed inside the noise band, and no `graph_query` verdict changed.
- **restore**: `timeseries.kt` reverted, same audit re-run — `filter_chains.png` back to `jitter`,
  zero `stale`, exit code 0. (`distribution_events.png` remains `unstable`: one run matches `HEAD`
  within the band, the two runs differ by 0.23% at maxΔ59. That page's plots really are jittery.)

## 6. The 2026-07-29 sweep

Two batches, `--repeat 2`, run serially (one batch's `git checkout` restore would clobber the other's
not-yet-stashed outputs). Run directories, with every regenerated file, are kept under
`.field-backdoor/docs-audit/`.

### `functions_examples` — 106 declared assets, 0 capture failures

| verdict | count |
| --- | --- |
| `stale` | 56 |
| `stale-size` | 2 |
| `unstable` | 1 |
| `jitter` | 47 |

**54 of the 56 are one cause**: ~49% of pixels at maxΔ204 pre-floor, every one of them last committed
2026-07-09 or 2026-07-10, i.e. exactly the cluster §3 predicted, reproduced independently and now
confirmed across two runs. `assets/functions_examples/reference/ramp.png` was inspected directly: the
committed figure autoscales y to ≈−0.1…0.51 and draws a zero line; the current code autoscales to
0.100…0.500, which is the arithmetically correct range for
`ramp(0.1, 0.9).crossfade(ramp(0.9, 0.1), center = 0.5, width = 0.25)`. **The published figure is
wrong and the regenerated one is right** — the committed one implies the curve dips below zero, which
it never does.

The other 2 are `reference/demo.png` (7.5% pre-floor, maxΔ246) and one 2026-07-28 asset.
The 2 `stale-size` are the `capture=box` unstable-height class from §2 (918↔774, 800↔918); two runs
happened to agree on a height, which is not enough for a tri-modal one — use `--repeat 3` there.

The 54 were **regenerated and committed** on the same day (their own commit, selected by verdict —
pre-floor delta > 40% — never by page, so the 47 jitter captures on the same page were left alone).

### The other 36 pages — 198 declared assets, 0 capture failures

Run 1 rewrote 163, run 2 rewrote 165; 39 assets were byte-identical both times.

| verdict | count |
| --- | --- |
| `stale` | 8 |
| `stale-binary` | 2 |
| `nondeterministic` | 3 |
| `nondeterministic-size` | 2 |
| `nondeterministic-binary` | 5 |
| `unstable` | 6 |
| `jitter` | 137 |
| `identical` | 39 |

The 8 + 2, worst first — **not yet regenerated**, because unlike the `functions_examples` cluster they
have no single shared cause and each needs its own look:

| asset | above-floor delta | last committed |
| --- | --- | --- |
| `wfc_examples/neighborhoods.png` | 35.76% at maxΔ227 | 2026-07-28 |
| `command-palette.png` | 3.83% at maxΔ245 | 2026-07-19 |
| `interpolants_examples/smooth_duration.png` | 2.35% at maxΔ238, and run 2 changed height | 2026-07-27 |
| `graph_query/lenses.png` | 2.20% at maxΔ249 | 2026-07-24 |
| `rectsl/rectsl-cascade-split.png` | 1.28% at maxΔ255 | — |
| `visualizers-display-html.png` | 0.031% at maxΔ20 | 2026-07-19 |
| `fline_examples/callbacks_2d.png` | 0.006% at maxΔ20 | 2026-07-20 |
| `closest_point.png` | 0.001% at maxΔ31 | 2026-07-20 |
| `stacksl/stacksl-diffusion-feedback.mp4`, `stacksl/stacksl-feedback-trails.mp4` | two runs agree, disagree with HEAD | — |

`wfc_examples/neighborhoods.png` was inspected: the reported counts are unchanged (473 / 0) and the
source pattern matches, but the whole `main` card sits ~25 px higher and the synthesised middle panel
differs — reproducible across both runs, and it was committed *the day before* this audit.
`graph_query/lenses.png` and `paramset.png` are the pixel-shifted-column pair discussed above and are
the clearest candidates for capture-*environment* drift rather than code drift. The last three, at
maxΔ20–31 over a handful of pixels, are marginal by construction: they clear the Δ19 jitter ceiling by
one or two levels.

The 5 `nondeterministic-binary` are mp4s that simply re-encode differently run to run — expected, and
the reason a video cannot be audited by bytes. The 3 `nondeterministic` (`segment_basins.png`,
`particles-galaxy.png`, `disorder_decay.png`) and the 6 `unstable` are captures that do not reproduce
themselves at all; they are not evidence of staleness and must not be regenerated on the strength of
a diff.

### What this sweep covered, and what it did not

- **Covered:** all 304 assets declared by all 37 pages carrying runner fences, twice each, no
  failures. That is the whole fence-declared capture surface.
- **Not covered:** the ~1350 other tracked files under `public/docs/assets/` — the bulk-render
  galleries (`canonical_graphics`, `stacksl_gallery`, `rectsl_gallery`), materialised
  `agent_transcripts`, theme/mode assets and loose workspace sources. Those are produced by other
  mechanisms and would need their own enumeration.
- The two `stale-size` and two `nondeterministic-size` results are the unstable-height `capture=box`
  class; two runs are not enough to settle a tri-modal height, so treat those verdicts as provisional.

## Reproducing a raw regeneration by hand

```sh
# your own port: 9477 is shared and a reused server silently runs someone else's code
FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9722 \
  uv run --group docs python tools/build_zensical_docs.py --page functions_examples
git status --short public/docs/assets     # necessary, nowhere near sufficient — see §2
```

Then compare pixel-wise against `HEAD`, twice, and discard everything inside the noise band — or
just use the auditor, which does exactly that and restores the tree afterwards.
