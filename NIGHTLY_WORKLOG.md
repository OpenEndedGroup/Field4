# Nightly worklog — 2026-07-15 / kind C (documentation/explainer)

Task: write a public worked-example page for the completion doc-panel "recursive
mini-browser" (`faa8382c`), with at least one REAL captured image from the actual
code.

## Plan

**Feature under documentation.** `faa8382c` made KDoc `[Symbol]` cross-refs and
source-location lines in the completion documentation panel clickable. Clicking
re-renders the panel as that symbol's card (recursive `renderNavCard`) with a
floating back/forward + breadcrumb (`.afield-suggest-nav`,
`.afield-suggest-nav-crumbs`). Links are `<a href="field-source:?...">`
(`fieldSuggest.js:766+`, `renderDocumentation`, `navigateToSource`).

**Capture strategy — reuse the existing `capture=ui` drive path** (the same
mechanism that regenerated `autocomplete.png` in `6f4a5058`), not a new harness:
- Fence type `diffstatic_runner` with `capture="ui" region="window"
  clip-selector=".afield-suggest" warm-completion=true` (region/clip pattern
  proven by the `command-palette.png` fence in `survivalGuide.md:50`;
  `warm-completion=true` is required so the K1 completion RPC actually resolves
  headless — see `6f4a5058` and `DocsUiDriver.idle` runloop-pump note).
- Drive script types `val axis = vec`, `Control+Space`, and
  `waitForSelector('a[href^="field-source:"]', ...)` — waiting for the linkified
  `[Vec2]` token to appear inside the popup (not just the item), so the still
  shows the doc panel with a live code-link.
- **Must-have still (fence screenshot):** the linkified panel
  (`assets/docpanel-code-link.png`) — goes through the full validated/cached/
  auto-embedded pipeline.
- **Best-effort second still (in-script `ui.shotClip`):** wrap in Kotlin
  `try/catch` — click the `[Vec2]` link, wait for `.afield-suggest-nav-crumbs`,
  `ui.shotClip(".../assets/docpanel-breadcrumb.png", ".afield-suggest")`, then
  click `data-nav="back"` to return to the link state so the fence end-screenshot
  is still the (safe) linkified panel. A failure here cannot break the must-have.

**Pre-flight validation (done):** `./gradlew runDocsPanelCodeLinksSmokeTest`
passes against the real index (`~/.afield/filestore/kotlin_source_index.json`):
`vec` KDoc linkifies `[Vec2]` → `field-source:...linalg.kt&line=32`; `Vec2` and
`vec` cards resolve; `vec` card recursively linkifies `[Vec2]`. So the live UI
will have a real link to click.

**Explainer page:** new `public/docs/completion_doc_browser.md`, house style of
`semantic_index.md` (prose + `##` sections + embedded assets). Walk: open a
completion → panel shows KDoc with `[Vec2]` link → click to hop to the `Vec2`
card → breadcrumb / back. Wire into `public/mkdocs.yml` nav. Cross-link the
Survival Guide autocomplete section and the semantic index.

**Verify:** eyeball every produced PNG (must show the panel + a real link, not
blank/error); `git add` the asset(s) explicitly (warm-worktree untracked-asset
trap); run the docs build for the page if feasible; commit in small steps.

## Execution log

**Status: DONE.** Both a primary AND the best-effort second still were produced
by the real code and eyeballed.

### Files added / changed
- `public/docs/completion_doc_browser.md` (new worked-example page)
- `public/docs/assets/docpanel-code-link.png` (new, REAL capture, ~104 KB)
- `public/docs/assets/docpanel-breadcrumb.png` (new, REAL capture)
- `public/mkdocs.yml` (nav: "The Documentation Browser" after AI Overview)
- `NIGHTLY_WORKLOG.md`

Committed as `d6028686` (docs + assets + nav) and a follow-up worklog commit.

### How the captures were produced (real, reproducible)
Single `capture=ui` `diffstatic_runner` fence in the page, run via:

```
FIELD_DOCS_RUNNER_PORT=9095 FIELD_DOCS_RUNNER_MODE=strict \
  uv run --project . --group docs python tools/build_zensical_docs.py \
  --skip-build --page completion_doc_browser
```

This boots a real Field JVM (`./gradlew runJvm`), attaches Playwright, and runs
the fence's `drive` script: `warm-completion=true` pre-builds the K1 engine, the
script types `val axis = vec`, presses `ctrl-space`, and
`waitForSelector('a[href^="field-source:"]')` blocks (pumping the runloop) until
the linkified `[Vec2]` anchor actually renders. Runner summary:
`regenerated=1 ... elapsed=51.6s`, no failures. `--skip-build` skips the slow
zensical HTML pass — we only need the capture.

- **Primary still** (fence `screenshot=`, clip `.afield-suggest`, auto-embedded):
  `docpanel-code-link.png`.
- **Second still** (in-script `ui.shotClip(...)`, wrapped in try/catch so a nav
  hiccup can never fail the primary): `docpanel-breadcrumb.png`. After following
  the link, the script clicks the back button to restore the link state, so the
  fence's own end-screenshot stays the linkified panel.

First run produced only the primary: the click selector `a[href^="field-source:"]`
matched two anchors (the KDoc `[Vec2]` and the `vec:504` source footer), and
Playwright's strict `locator.click()` throws on a two-match. Fixed by targeting
`:not(.afield-suggest-source-link)`; second run produced both.

### Eyeballed (I LOOKED at both PNGs)
- `docpanel-code-link.png`: completion list (MATH, `vec` selected) + doc panel
  with `Category Math`, the highlighted `fun vec(...)` signature, the KDoc with
  **`Vec2` underlined as a live link** and **`[Float]` left literal** (dead-link
  avoidance visible in one shot), and a `source vec:504` link. Real, not blank.
- `docpanel-breadcrumb.png`: doc panel re-rendered as the **`Vec2` card**
  (`typealias Vec2 = Vector2f`, `Vec2:32` source link) with the floating
  **`‹ › vec › Vec2` breadcrumb** control bottom-right. Real, shows the feature.

### Verification (honest)
- Pre-flight: `./gradlew runDocsPanelCodeLinksSmokeTest` passes against the real
  index (`vec` KDoc linkifies `[Vec2]`→linalg.kt:32; cards resolve; recursion).
- `tools/check_doc_assets.py`: **OK — every referenced asset is committed**
  (47 nav pages, 419 refs). Both new PNGs are git-tracked (staged→committed), so
  a clean checkout will not 404 — the warm-worktree untracked-asset trap is
  avoided. (Also fixed alt text that contained `[Vec2]` brackets, which had
  hidden the breadcrumb ref from the checker's `![...]` regex and would have
  broken markdown image parsing.)
- The full zensical HTML build was NOT run (`--skip-build`); the capture pipeline
  and asset lint are the load-bearing checks and both pass.

### Risks / unknowns
- The captured symbol docs depend on the machine's Kotlin source index; text
  could drift if `vec`/`Vec2` KDoc changes, but the fence re-captures on rebuild.
- Did not run the whole-site zensical build (out of scope / slow); page renders
  via the standard mkdocs runner hook path used by all other capture pages.

### Recommendation
**MERGE.** Self-contained new page + two real, verified captures + nav wiring;
asset lint green; no source code touched (docs only), so low blast radius.

