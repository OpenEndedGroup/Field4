# Local references under the published path prefix

The docs site is published under a **path prefix**, not at a host root:
`public/zensical.yml:361` says `site_url: http://openendedgroup.com/Field4/`. Almost nothing in
this repo's tooling ever served it that way, and one whole class of broken reference lived in
that gap for as long as the page that carried it.

Written 2026-09-16, from `nightly/2026-09-16/2-docs-asset-prefix-guard`. Every number below was
measured on the build described in *Reproducing it*, and every number is stated with its
population. **Read [DOCS_REFERENCE_FAILURE_MODES.md](DOCS_REFERENCE_FAILURE_MODES.md) alongside
this.** This page is the prefix family and the guard's design; that one is the four distinct
failure modes the guard turned up, three of which are not the prefix family at all, and it
carries the corrected populations from 2026-09-17.

## The mechanism, which is a frame-of-reference mistake

Zensical re-bases relative `href` and `src` **inside raw HTML** exactly as it re-bases markdown
links: it reads the reference as relative to the **markdown file's own directory**, then rewrites
it for the page's published URL depth. `use_directory_urls` is on
(`tools/build_zensical_docs.py:318`), so `public/docs/foo.md` is served at `/Field4/foo/` — one
directory deeper than the source sat. The build adds the `../` that closes that gap.

The house convention therefore is: **write a raw-HTML ref file-relative to the docs root**
(`assets/x.png`), and let the build add the `../`. This is recorded, from measurement, at
`public/docs_hooks.py:267-274` (`page_directory_depth`), and the `<video>`/`<audio>` embeds
(`:2635`, `:2646`) and citation hrefs (`:258`) all follow it deliberately.

A page that helpfully writes the `../` itself gets a second one:

| source (`public/docs/rectsl_examples.md`) | rendered at `/Field4/rectsl_examples/` | browser requests | |
|---|---|---|---|
| `<img src="../assets/rectsl/x.png">` | `../../assets/rectsl/x.png` | `/assets/rectsl/x.png` | **404** |
| `<img src="assets/rectsl/x.png">` | `../assets/rectsl/x.png` | `/Field4/assets/rectsl/x.png` | 200 |

**Why nobody saw it.** At the server root, `..` clamps: `/foo/../../assets/x.png` and
`/foo/../assets/x.png` both land on `/assets/x.png`. So the over-climb is invisible unless the
site is served under its prefix — and of the four configs in `.claude/launch.json`, `field4-site`
(8125) and `field4-docs` (8130) both serve `public/field4` **as the document root**, which is the
one configuration in which this bug cannot appear. (`docs` on 8123 serves `public/`, so it does
put the site under a `/field4/` prefix and would have shown it — at the *lowercase* directory
name, not the published `/Field4/`. Close enough to catch this, and nobody ran it against the
figures.)

## Zensical's rebasing is attribute-selective, and `poster` is the exception

This cost a build to learn and is written down nowhere else. Rewriting one `<video>` tag on
`rectsl_examples.md` to be file-relative throughout produced:

```
<video src="assets/rectsl/x.mp4" poster="assets/rectsl/x.png">   # what the source said
<video src="../assets/rectsl/x.mp4" poster="assets/rectsl/x.png"># what was rendered
```

`src` was re-based. `poster` was **not**, so it resolved inside the page's own URL directory
(`/Field4/rectsl_examples/assets/...`) and 404'd. So:

- **`src` and `href`** must be written **file-relative** (`assets/x.png`).
- **`poster`** must be written in the **published URL frame** (`../assets/x.png`).

`public/docs/vision_geometry.md:340` carries both in a single tag, each correct — someone had
already found this empirically and left no note. Census: all **39** `poster=` attributes in the
tree, on **2** of **500** markdown files under `public/docs`, are `../`-framed, and all 39
resolve. Do not "normalise" them.

## What was actually wrong, versus what the first survey said

The brief that started this said `rectsl_examples.md` was the only page of ~500 using raw
`<img src="../assets/…">`, carrying 12 of them. Re-derived over the full population of **500**
markdown files (`git ls-files 'public/docs' | grep '\.md$' | wc -l`; 20 under `reference/`, 480
elsewhere):

- `git grep -c -E '<img[^>]*src="\.\./'` → exactly one page, `rectsl_examples.md`, **12** hits.
  The literal claim is true.
- `git grep -c -E '<img'` → **10** pages, **24** hits. Only **15** are raw `<img>` tags naming a
  **local** file, on **2** pages: `rectsl_examples.md` (12, `../assets/…`) and
  **`rectsl_cheatsheet.md` (3, `assets/…` — no `../`)**. The rest are `data:` URIs built inside
  example code (5) or the word `<img>` in prose and a signature table (4).
- and the build's own repair pass counts **50** over-climbing refs on that page once markdown
  images and `<video><source>` are included — not 12. The `<img>` predicate saw a quarter of it.

Two consequences. First, the family had **two members with opposite conventions** and the narrow
predicate hid the second. Second, `rectsl_cheatsheet.md` is the control, and a much better one
than expected: the brief described its figures as "markdown-style images", but they are **raw
`<img>` tags too**. Same construct, opposite path frame, opposite outcome. **The construct was
never the variable.**

## The remedy: normalise the source frame, do not touch the rebasing

The obvious-sounding fix — "make the build tolerate `../assets/…` in raw HTML" — is a
regression. It would break the 3 correct refs on the cheatsheet plus every `<video>`, `<audio>`
and citation href in the tree, all of which are deliberately file-relative.

`tools/build_zensical_docs.py:normalize_over_climbing_refs` instead re-seats refs that were
written in the wrong frame, during preprocessing. It fires only when **both** hold:

1. reading the ref in the source frame lands **outside the docs root** — so it cannot be what the
   author meant; and
2. re-reading it **one level shallower** lands on a file that **actually exists** inside the docs
   tree — so the repair is confirmed, not inferred.

Dropping exactly one `../` is the whole repair, because directory URLs put a page exactly one
directory deeper than its markdown. Anything still unresolved is left alone for the guard to
report: a rewrite that cannot name the file it repaired should not happen. Fenced blocks are
skipped, on the same grounds `docs_hooks.rewrite_source_citations` skips them.

**It lives in `tools/build_zensical_docs.py`, not in `public/docs_hooks.py`, and that is not a
matter of taste.** `docs_hooks.py` is in `RUNNER_DIGEST_SOURCE_FILES`
(`public/docs_hooks.py:414`), so *any* edit to it changes every capture's digest, and a warm
tree's next incremental docs build would re-run all **633** captures to produce identical
pictures. `build_zensical_docs.py` is not in that list. The transform is the same; the cost is
not. (The digest is AST-normalised and strips docstrings and `LOG` calls, so a comment-only edit
is free — but a new function is not.)

The page itself is never edited. It is held by a live branch, and it makes the better proof
anyway: **a page nobody touched starts working.**

## The guard: `tools/check_doc_site_links.py`

Resolves **every local reference on every built page, extracted from the rendered HTML**, against
the built site, under the prefix read from `site_url`. Two verdicts, kept apart because they mean
different things:

- **escape** — the resolved URL left the prefix. Deployment-independent: a 404 under any non-empty
  prefix, and a silently clamped near-miss at the root. Reported even when the clamped target
  happens to exist.
- **missing** — inside the prefix, but names nothing in the built site.

### What it selects — the population, stated

**With the flag that produced it**, which the first version of this table omitted and should not
have: the source mirror (`reference/source/**`) is generated per build and is the largest page
family, so the same site measures two completely different ways. The guard now prints a
`build shape:` line saying which one you are looking at.

```
--no-source-pages, 2026-09-16 at cb4377a6c + that branch
155 built pages          of 287 *.html in public/field4
                         (the other 132 are copied/vendored, see below)
39943 local references   from rendered HTML: 301 site-absolute, 39642 relative
8939 refs skipped        external / data: / mailto: / protocol-relative / bare #fragment
1462 distinct targets    = 1109 resolve + 350 un-generated source mirror + 3 grandfathered

default build (mirror present), 2026-09-17 after the four fixes
613 built pages          of 745 *.html; 455 of the 613 ARE the mirror
121655 local references  170 site-absolute, 121485 relative
25016 refs skipped       external / data: / mailto: / protocol-relative / bare #fragment
51 matches masked        inside <pre>/<code>; 0 of them resolved to a real file
1575 distinct targets    = 1575 resolve. Nothing grandfathered, nothing excused.
```

Before the fix, **50** distinct targets escaped the prefix, all on `rectsl_examples/index.html`.
After it, **0** do. Reverting the fix and rebuilding puts all 50 back — that counterfactual is
the only reason to believe the guard has teeth, and it is re-runnable (see below).

### What it cannot see

A guard's predicate is usually blind to its own failure mode, so:

- **References inside `.css` and `.js` assets** — `url(...)` in a stylesheet is never parsed. Only
  HTML attributes are.
- **References built at runtime by JavaScript.** The code-diff player and the offline viewer both
  construct URLs in JS; none of that is visible here.
- **Whether the bytes are right.** A committed placeholder card resolves at 200.
  `tools/check_doc_assets.py` owns that question, including the pixel fingerprint.
- **The 345 verbatim markdown files** copied by `copy_workspace_markdown`. They are not pages and
  have no rendered HTML.
- **The 132 `*.html` under the site's `assets/`** — a vendored CodeMirror distribution whose 129
  mode-demo pages reference CodeMirror's own source layout (`../../lib/codemirror.js`) that this
  repo does not ship. Already broken at `cb4377a6c`, in no nav, authored by nobody here. Excluded
  by default, **counted and named on every run**, `--include-copied-assets` to check them anyway.
  Measured with the fixed guard: including them takes the population from 155 to **287** pages and
  1462 to **1769** distinct targets, and adds **40** failures (5 escapes, 35 missing). That is
  small enough that deleting or repairing the vendored demo pages is a real option for someone;
  it is simply not this branch's call.

  Worth recording how that number was first got wrong. The first run reported **417** unresolved
  targets in this tree, and nearly all of them were an artefact of a bug in *this guard*: it
  treated a page served under its own name (`scala.html`) as a directory, inventing targets like
  `…/scala.html/clike.js`. **A new guard's first numbers are usually measurements of its own
  defects.** Two of the 25 tests exist to pin that one shut.
- **External links.** Nothing off-site is fetched; a docs build has no network guarantee.
- **Whether a prefix-baked absolute ref is a good idea.** The 301 site-absolute refs already
  carry `/Field4/`, so they satisfy this prefix by construction and would fail under any other.

### The incumbent guard was structurally blind to this, by design

`tools/check_doc_assets.py:resolve_candidates` accepts a ref that is tracked at **either**
resolution convention — markdown frame *or* pretty-URL frame — and its docstring says so. That is
correct for its question ("is the asset committed?") and makes `assets/x.png` and
`../assets/x.png` equally satisfying, though at most one can be right in the rendered HTML. It
checked **148 pages and 1219 asset refs** and reported `OK` on every build while all 50 of these
were broken. The two guards are complements: one reads the source tree, the other the built site.

### Both deployments

`--prefix /` grades the same build as if served at the root. The answer is informative rather than
clean: **164** distinct references fail there, every one of them an absolute ref the theme baked
from `site_url`. So this build is prefix-committed by construction, and a build that only works at
the root cannot satisfy the guard in its default mode either. Stated plainly: the site works under
`/Field4/` and not at `/`, and that is a property of `site_url`, not a defect introduced here.

## Grandfathered debt (`KNOWN_UNRESOLVED`) — retired 2026-09-17, and it is empty

Three references were already broken under the prefix at `cb4377a6c`, on pages this branch could
not edit. They were printed on every run and did not gate; `stale_known_unresolved` **fails** when
an entry stops reproducing, so the list could not quietly become policy, and it is graded only
against the real site — not a fixture, not another prefix.

All three were fixed at source the following night, so the dict is now `{}` and the guard reports
`1575 of 1575`. Two of the three turned out not to be broken references at all. Each is written up
in full, with its re-derived mechanism and its guard, in
[DOCS_REFERENCE_FAILURE_MODES.md](DOCS_REFERENCE_FAILURE_MODES.md); in one line each:

1. **`/offline.html`** — **137** occurrences on **17** authored pages (not "134 pages"), both
   halves server-absolute and case-mismatched against `site_url`. Now viewer-relative
   (`offline.html?file=assets/…`) and guarded by `tools/check_doc_offline_links.py`.
2. **`/extensions/remote_ar/index.html`** — never a live reference: it is the `src` of an
   **escaped** `&lt;iframe` shown inside a `diffstatic` fence on `ar_reference.md:45`, which the
   extractor was reading as markup. Masked with all other displayed code; the page now also says
   in prose that `/extensions/…` is served by a running Field.
3. **`/Field4/reference/3,8`** — `patterns.md` showed `pat("[bd cp](3,8) hh!2")` in a block
   indented **7** spaces inside 4-space-indented KDoc output, one short of Python-Markdown's
   8-space threshold for a code block inside a list item, so markdown read the pat figure as a
   **link**. Fixed in `TidalPatterns.kt` and regenerated. **Still the best argument for reading
   rendered HTML**: no markdown reader would ever flag `[bd cp](3,8)` as a broken reference,
   because in `pat` notation it is not one.

## Reproducing it

```sh
UV="$(command -v uv)"          # never type uv's path from memory
FIELD_DOCS_RUNNER_MODE=never "$UV" run --group docs \
    python tools/build_zensical_docs.py --clean --no-source-pages
"$UV" run --group docs python tools/check_doc_site_links.py --summary
```

`FIELD_DOCS_RUNNER_MODE=never` reuses every committed asset and runs **no** captures
(`regenerated=0 (screenshots=0 videos=0), skipped=633 (reused_existing=633 missing=0),
failures=0`), which is what you want when changing link rewriting: the figures are not the
variable. Measured: the zensical step is **20.7 s** cold and **7.2 s** warm, and the guard adds
about a second — so the whole loop is well under a minute, which is what makes the
revert-and-rebuild counterfactual cheap enough to actually run.

To see it as a browser does, serve the site under its prefix — which needs a parent directory
holding a correctly-cased `Field4`:

```sh
mkdir -p /tmp/serve && ln -s "$PWD/public/field4" /tmp/serve/Field4
(cd /tmp/serve && python3 -m http.server 8822 --bind 127.0.0.1) &
curl -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8822/Field4/assets/rectsl/rectsl-sdf-fill.png
```

`python3 -m http.server` **does not stop on SIGINT** — escalate to SIGTERM and verify the port.

### Looking at it, not just counting it

Counting was not enough to trust this, and it is worth saying how the looking was done, because
the obvious route does not work. Headless Chrome's `--screenshot` captures one viewport and
cannot scroll, and `/Field4/rectsl_examples/` is ~4000 rendered lines with 38 autoplaying videos
above the SDF figures, so it never settles and the figures are never in frame. Playwright is a
Node-side dependency here, not in the `docs` Python group.

What worked: extract the `<img src>` strings for the SDF figures **out of each built page's own
rendered HTML**, and write them into a bare probe page at the **same URL depth**
(`<site>/probe/index.html` beside `<site>/rectsl_examples/index.html`). The `src` strings are
byte-identical and the depth is identical, so the browser resolves them exactly as it does on the
real page; all that is gone is the prose and the videos. One viewport then shows all ten.

Result, serving each build under `/Field4/`: **before, ten broken-image icons; after, ten SDF
renders.** The screenshots were 19 KB and 105 KB respectively, which is its own tell.

## Traps, in the order they will cost you time

- **A number from a `git grep` predicate is a number about that predicate.** `<img src="../` found
  12 of the 50 refs that were actually broken. Get the count from the build or the rendered HTML.
- **`git grep -c` exits non-zero when a pattern matches nothing**, which silently truncates a
  `&&`-chained survey. And `**` in a git pathspec does not match a single-level path.
- **Never grade the site at `/`.** It is the one configuration in which this bug does not exist.
- **`poster` is not re-based.** See above. Rewriting it "for consistency" breaks 39 figures.
- **Do not edit `public/docs_hooks.py` for a markdown transform** unless the transform needs to be
  there: it invalidates 633 capture digests.
- **A fresh worktree has no capture cache**, so `docs_hooks.py:2235` takes the `cached_digest is
  None` branch, prints *"using existing checked-in screenshot asset without cache entry"* and
  reuses the committed PNG. That is correct behaviour, not a staleness bug. Forcing recapture with
  `FIELD_DOCS_RUNNER_MODE=always` for a link-rewriting change is pure churn.
- **Grade `docsGates` on its `BUILD SUCCESSFUL` line only.** A clean log contains 4 `FAILED`
  lines, all pytest fixtures whose subject *is* the failure report.
- **`.DS_Store` is gitignored**, so a worktree full of them still reports `git status --porcelain`
  empty. Sweep before claiming a clean tree.

## Where the pieces are

| | |
|---|---|
| `tools/check_doc_site_links.py` | the guard; `--summary`, `--prefix`, `--include-copied-assets` |
| `tools/test_check_doc_site_links.py` | 33 tests, auto-wired into `docsGates` by `run_python_tests.py`'s `tools/test_*.py` glob |
| `tools/build_zensical_docs.py` | `normalize_over_climbing_refs`, and the guard invocation after the site is complete |
| `tools/check_doc_assets.py` | the complementary source-tree guard, and the placeholder-pixel detector |
| `public/docs_hooks.py:267` | `page_directory_depth`, where the rebasing contract was first recorded |
