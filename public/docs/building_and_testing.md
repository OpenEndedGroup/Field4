# Building the Docs & Running the Tests

Most pages in this site contain *live* examples. The code you see ran inside
Field, the screenshots and videos are captures of its output, and rebuilding the
docs means asking Field to re-execute all of it. The test suites exercise Field
end-to-end through the same machinery, so this page is the short version of how
to drive both locally.

For the full reference (every flag, the intent matrix, and the internals) see
[`AGENTS.md`](https://github.com/OpenEndedGroup/Field) and
`dev_docs/testing/TESTING_STRATEGY.md` in the repository.

## Prerequisites

- **[uv](https://docs.astral.sh/uv/)** — runs the Python tooling. The commands
  below use `uv run --project . --group <name>` so the right dependencies are
  fetched automatically; no manual virtualenv needed. If a docs build cannot
  find Zensical, install `uv` rather than installing Zensical by hand; the
  `docs` group in `pyproject.toml` already pins it.
- **A JDK + the Gradle wrapper** (`./gradlew`, already in the repo) — builds and
  runs the Field JVM. `./field.sh` wraps `./gradlew runJvm` if you just want the
  app.
- Everything runs against a **Field server**. The docs and test runners start
  one for you on a port you choose. Pick a dedicated high port like `9477`,
  not the one your interactive Field is on.

## Building the docs

One command builds the site, re-running example captures according to a *mode*:

```diffstatic {data-lang=bash}
:::static
FIELD_DOCS_RUNNER_MODE=incremental FIELD_DOCS_RUNNER_PORT=9477 \
  uv run --project . --group docs python tools/build_zensical_docs.py
```

The mode controls how much Field work happens:

| `FIELD_DOCS_RUNNER_MODE` | What it does | Use when |
| --- | --- | --- |
| `never` | Skip all capture execution; reuse whatever screenshots/videos already exist. Doesn't start Field. | You only touched prose/HTML and want the fastest rebuild. |
| `incremental` | Reuse valid cached captures, run only changed or missing ones. | Normal authoring loop against an already-clean cache. |
| `strict` | Invalidate captures when the runner code or a transcript changes (via a digest), then re-run those. | You changed capture/runner logic and want stale captures caught. |
| `always` | Regenerate every screenshot and video, ignoring the cache. | A full recapture, or when you don't trust the cache. |

The runner owns `FIELD_DOCS_RUNNER_PORT` outright. In every mode except
`never` it insists on a server it started itself, so if something is already
answering on that port it posts a shutdown request to it, waits, and gives up
with "Stop the stale Field process" if it is still there. Aim a build at the
Field you are working in and you lose that session.

Handy flags for `tools/build_zensical_docs.py`:

- `--page <name>` — only run captures for matching page(s) (stem, filename, or
  glob); other pages still build but with mode `never`. Great for iterating on
  one page: `--page fline_examples`.
- `--skip-build` — regenerate the preprocessed sources without running the final
  Zensical site build.
- `--clean` — wipe the built site first.

The built site lands in `public/field4/`. Preview it the way Field's own docs
iframe loads it:

```diffstatic {data-lang=bash}
:::static
cd public && python3 -m http.server 8000
# then open http://localhost:8000/field4/
```

Monaco, Iosevka and the offline viewer are vendored under `src/` rather than
fetched, and `tools/sync_offline_assets.py` copies them into the built site so
that the read-only viewer iframe resolves without a running Field.
`uv run python tools/check_offline_assets.py` checks that every canonical source
is still there and that no synced copy has been committed back into `public/` or
`dev_docs/`. Run it after moving any of those assets around; it needs no Field
and no network.

> **A red error marker is a real failure.** If a rebuilt screenshot shows
> Field's red error marker, or empty/incorrect output, treat it as a genuine
> failure — not a flaky capture. Fix the snippet (or the runtime), then rebuild
> that page.

> **Optional warm-session mode.** Set `FIELD_DOCS_RUNNER_SESSION=1` to route
> eligible captures through one persistent, pre-warmed Field session instead of a
> fresh process per capture. It is opt-in and falls back to the ordinary capture
> path after a configured session failure.

## Checking whether a published figure is still true

A green docs build does not answer that question, because a cache hit and a
correct figure look the same from outside. Two mechanisms narrow the gap.

The capture cache keys each figure on the fence's own code, its options, a digest
of the runner machinery, and a digest of the source files the fence's code text
names (`sourceRefsDigest`), mapped through the committed
[codebase reference](reference/index.md). So an `incremental` build re-captures
the figures a stdlib edit can reach directly. What it cannot see is an
*indirect* change — a function called by the function you edited.
`FIELD_DOCS_SOURCE_REFS=stdlib` hashes the whole indexed stdlib for every fence
instead, which catches that at the cost of re-capturing everything; `=off` drops
the source digest, leaving a fence keyed on its own code and options alone.

For a direct answer, `tools/docs_capture_audit.py` re-runs captures from the
current code and classifies each result against the *measured* run-to-run noise
floor, so it can say "stale" rather than merely "different":

```diffstatic {data-lang=bash}
:::static
# does this page's committed figures still match the code? (two runs = rigorous)
uv run --group docs python tools/docs_capture_audit.py --page functions_examples --repeat 2

# no Field, instant: rank capture assets by when they were last regenerated
uv run --group docs python tools/docs_capture_audit.py --inventory

# no Field: which source files does each fence reach, and what would touching one invalidate?
uv run --group docs python tools/docs_capture_audit.py --plan --touched src/jvmMain/kotlin/afield/stdlib/Functions.kt
```

Captures are not byte-reproducible — only a handful are byte-stable run to run — so
the comparison is per pixel against `git show HEAD:`, with jitter (≤0.02% of pixels
at max channel delta ≤19) reported separately from a genuine re-render, and
`--repeat 2` distinguishing a *nondeterministic* capture from a stale one. The
working tree is restored after every run; the regenerated files are kept under
`.field-backdoor/docs-audit/<timestamp>/` so you can look at the before/after
yourself. Full background, including the noise-band measurements, is in
`dev_docs/docs_capture_staleness.md`.

## Watching progress & debugging a build

The runner prints per-capture progress as it goes, so a slow build is
distinguishable from a hung one:

```diffstatic {data-lang=text}
:::static
Field docs runner [12] capturing fline_examples.md -> arcs_arcto.png
Field docs runner … still capturing fline_examples.md -> arcs_arcto.png (8s)
```

Each capture logs a numbered start line; if one runs long (for example while a
graphics-context capture starts Field), a heartbeat prints every 8s so you can
see it is alive. Progress is `INFO`-level and on by default;
set `FIELD_DOCS_LOG_LEVEL=WARNING` to quiet it or `=DEBUG` for more.

The same `INFO` channel carries one summary line at the end of a build. It
counts `regenerated=` against `skipped=` and breaks both down (`screenshots=`
and `videos=`; `cache_hits=`, `reused_existing=` and `missing=`), then reports
`failures=`, `warm_session_captures=`, `server_starts=`, `server_restarts=` and
`elapsed=`. Read that line first when a build felt suspiciously fast or quiet,
because a run that regenerated nothing and skipped everything looks like a
successful one until you count the captures.

To *watch* the capture browser — a capture that stalls, or one that renders
wrong — run the build with `AFIELD_PLAYWRIGHT_VISIBLE=true`:

```diffstatic {data-lang=bash}
:::static
AFIELD_PLAYWRIGHT_VISIBLE=true FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 \
  uv run --project . --group docs python tools/build_zensical_docs.py --page fline_examples
```

That launches a visible, GPU-accelerated Chrome (instead of the headless capture
browser) that you can attach DevTools to for profiling. It's an isolated
Playwright profile, separate from your own Chrome. Server logs for a stuck
capture are under `.field-backdoor/logs/`.

## Running the tests

**Smoke catalog.** Exercises the annotated `FIELD_TEST` examples through the
backdoor:

```diffstatic {data-lang=bash}
:::static
uv run --project . --group backdoor python tools/run_field_tests.py --refresh-catalog
```

`--refresh-catalog` rebuilds `build/field-tests/catalog.json` first, then runs
each discovered test one at a time. The runner prewarms one persistent Field on
`--port` for the whole run (pass `--no-prewarm` to reuse an already-running
server instead). Per-test timeouts come from each test's annotation, so **don't**
pass `--timeout` to this runner.

**Committed generated assets.** Several things in the repo are generated from
source and then committed, so that a change to one shows up as a reviewable diff.
`./gradlew check` re-derives each and fails if the committed copy has drifted:

| task | guards | fix a failure with |
| --- | --- | --- |
| `checkCodebaseReference` | `public/docs/reference/` and `dev_docs/kdoc_worklist.md` | `./gradlew generateCodebaseReference` |
| `checkKDocAttachment` | every KDoc block in `src/` attaching to the declaration below it | move the block — see `dev_docs/kdoc_reference_contract.md` |
| `checkAgentTranscripts` | `public/docs/assets/agent_transcripts/`, the welcome screen's starting points | `./gradlew regenerateAgentTranscriptStartingPoints` |
| `checkAgentTranscriptIndex` | `public/docs/agent_transcripts.md` being a true index of that corpus | write the missing entry |
| `checkCompletionCorpusFixtures` | `tools/completion-corpus/boxes` after real macro expansion | re-run it with `AFIELD_HARVEST_WRITE=1` |
| `checkAgentDemos` | a `dev_docs/agents/demos` inline fallback matching its sidecar | `./gradlew regenerateAgentDemos` |
| `citationGates` | every `path:line` and `path#symbol` cited by a guarded page | fix the citation |
| `checkPythonTools` | the `tools/test_*.py` suites | fix the test |

None of them needs a Field server, a GPU or a network, and none of them writes a
committed asset while checking: they generate into a temp directory and compare.

**Browser UI regressions.** Drives Playwright-backed UI workflows and keeps
screenshots:

```diffstatic {data-lang=bash}
:::static
uv run --project . --group backdoor python tools/run_ui_regressions.py --port 9490 --timeout 45
```

It prints `RUN` / `PASS` / `FAIL` lines; artifacts are retained under
`build/ui-regressions/artifacts/<test-id>/` (including `latest-pass.png` and
`latest-failure.png`).

## What both runners share

Both pipelines talk to Field through `tools/field_backdoor.py`. Each puts a
single Field server on your chosen port, creates scratch boxes in it, runs the
example code, and screenshots the resulting output areas — the same ones you see
when you use Field interactively. They differ over who owns that server: the
docs runner always starts its own, as above, while `run_field_tests.py` prewarms
one by default and attaches to a server already listening on `--port` if you
pass `--no-prewarm`. Either way one server serves many captures, so keep runs on
a dedicated port. When something misbehaves, the backdoor's logs under
`.field-backdoor/logs/` are the first place to look.
