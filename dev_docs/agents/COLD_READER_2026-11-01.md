# Cold-reader review, 2026-11-01

Continues `COLD_READER_2026-10-31.md` with the pages it skipped: the *Long Way Round* overview
(`chains.md`), the first page of *Working in a Document* (`moving_around_the_canvas.md`), and the
two long pages left for length, `gaussian_processes.md` and `paint_sweeps.md`. Same method: each
page read once, cold, prose and fence headers; findings limited to what `DOCS_WRITING_RULES.md`
supports. No open docs-notes existed for these pages. Every finding below was fixed in this
branch. No fence's code changed, so no capture was retaken.

| Page | Finding | Category | Fix |
|---|---|---|---|
| `chains.md` | "mini-notation" used without saying what it is | undefined-term | named as the TidalCycles-style rhythm syntax |
| `chains.md` | "Dirt-Samples" | undefined-term | "the Dirt-Samples drum library" |
| `chains.md` | "certified range" | undefined-term | the range interval arithmetic proves |
| `chains.md` | "canonical", "Ceres solve" | undefined-term | every edge tangent to one sphere; the Ceres optimiser |
| `chains.md` | "Linear blend skinning" | undefined-term | glossed |
| `chains.md` | "MADP Practice Core" heading is internal vocabulary | undefined-term | "Studio tools from MADP" |
| `chains.md` | "Gaussian splat", "CLIP" | undefined-term | glossed |
| `moving_around_the_canvas.md` | "How the neighbours are chosen" sat after Bookmarks and Layers, far from Cmd Cmd | dense / order | moved under *Stepping between boxes* |
| `moving_around_the_canvas.md` | `F` chip mentioned before bookmarks exist on the page | forward-ref | linked to #bookmarks |
| `moving_around_the_canvas.md` | "Monaco" | undefined-term | "the editor" |
| `moving_around_the_canvas.md` | `viewport` as `(-x, -y, w, h)` and "world units" unexplained, sign differs from `viewports` silently | unparseable | explained both |
| `gaussian_processes.md` | "latent draw" before "latent" is defined | undefined-term | "draw" |
| `gaussian_processes.md` | RBF, Matérn named with no gloss | undefined-term | glossed at first use |
| `gaussian_processes.md` | "mean-square differentiable" in the table | undefined-term | plain wording |
| `gaussian_processes.md` | "Stationary kernels" | undefined-term | defined in place |
| `gaussian_processes.md` | "a feature path below": nothing named that yet | forward-ref | points to next section |
| `gaussian_processes.md` | Smile, L-BFGS, GpParameter argument order unexplained | undefined-term | Smile named; bounds explained |
| `gaussian_processes.md` | "projected gradient ... local stationarity diagnostic" | dense | plain wording |
| `gaussian_processes.md` | "heteroscedastic" | undefined-term | "per-observation" |
| `paint_sweeps.md` | settings named with no word on where each is set | unparseable | paragraph mapping settings to BrushConfig / load / set / gesture |
| `paint_sweeps.md` | "canvas units" never defined, but `width` 200 is "10 cm" | unparseable | 1000 units across 50 cm = 0.5 mm |
| `paint_sweeps.md` | "flat", "fat" painter's jargon | undefined-term | glossed |
| `paint_sweeps.md` | "guide hairs" | undefined-term | defined (from `DynamicBrush` KDoc) |
| `paint_sweeps.md` | "the old model" (changelog voice); "cohesion 4" before cohesion is introduced | page-meta / forward-ref | "the neighbour model"; forward gloss |

Totals: 24 found, 24 fixed.

## Left for later

- `gaussian_processes.md` "Cost and numerical behaviour" is dense for a newcomer (sampling rank,
  jitter); it could move most of that into an aside or `dev_docs/GAUSSIAN_PROCESSES.md`.
- The rest of *Working in a Document* (`filming_the_canvas.md` onward) and the 16 chain pages were
  not read in this pass.
