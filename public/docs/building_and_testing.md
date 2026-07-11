# Building the Docs & Running the Tests

Most pages in this site contain *live* examples: the code you see was actually
run inside Field, and the screenshots and videos were captured from real output.
Rebuilding the docs therefore means asking Field to re-execute those examples.
The test suites use the same machinery to exercise Field end-to-end. This page
is the short version of how to drive both locally.

For the full reference (every flag, the intent matrix, and the internals) see
[`AGENTS.md`](https://github.com/OpenEndedGroup/Field) and
`dev_docs/testing/TESTING_STRATEGY.md` in the repository.

## Prerequisites

- **[uv](https://docs.astral.sh/uv/)** — runs the Python tooling. The commands
  below use `uv run --project . --group <name>` so the right dependencies are
  fetched automatically; no manual virtualenv needed.
- **A JDK + the Gradle wrapper** (`./gradlew`, already in the repo) — builds and
  runs the Field JVM. `./field.sh` wraps `./gradlew runJvm` if you just want the
  app.
- Everything runs against a **Field server**. The docs and test runners start
  one for you (or reuse a healthy one) on a port you choose — pick a dedicated
  high port like `9477` so you don't collide with a Field you're using
  interactively.

## Building the docs

One command builds the site, re-running example captures according to a *mode*:

```bash
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

Handy flags for `tools/build_zensical_docs.py`:

- `--page <name>` — only run captures for matching page(s) (stem, filename, or
  glob); other pages still build but with mode `never`. Great for iterating on
  one page: `--page fline_examples`.
- `--skip-build` — regenerate the preprocessed sources without running the final
  Zensical site build.
- `--clean` — wipe the built site first.

The built site lands in `public/field4/`. Preview it the way Field's own docs
iframe loads it:

```bash
cd public && python3 -m http.server 8000
# then open http://localhost:8000/field4/
```

> **A red error marker is a real failure.** If a rebuilt screenshot shows
> Field's red error marker, or empty/incorrect output, treat it as a genuine
> failure — not a flaky capture. Fix the snippet (or the runtime), then rebuild
> that page.

> **Optional warm-session speedup.** Set `FIELD_DOCS_RUNNER_SESSION=1` to route
> eligible captures through one persistent, pre-warmed Field session instead of a
> fresh process per capture (~2x faster on capture-heavy pages). It's opt-in and
> falls back automatically, so the output is identical either way.

## Watching progress & debugging a build

The runner prints per-capture progress as it goes, so a slow build is
distinguishable from a hung one:

```
Field docs runner [12] capturing fline_examples.md -> arcs_arcto.png
Field docs runner … still capturing fline_examples.md -> arcs_arcto.png (8s)
```

Each capture logs a numbered start line; if one runs long (a graphics-context
capture cold-boots a fresh Field JVM, which can take 20–40s), a heartbeat prints
every 8s so you can see it's alive. Progress is `INFO`-level and on by default;
set `FIELD_DOCS_LOG_LEVEL=WARNING` to quiet it or `=DEBUG` for more.

To *watch* the capture browser — e.g. a capture that stalls or renders wrong —
run the build with `AFIELD_PLAYWRIGHT_VISIBLE=true`:

```bash
AFIELD_PLAYWRIGHT_VISIBLE=true FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 \
  uv run --project . --group docs python tools/build_zensical_docs.py --page fline_examples
```

That launches a visible, GPU-accelerated Chrome (instead of the headless capture
browser) that you can attach DevTools to for profiling. It's an isolated
Playwright profile, separate from your own Chrome. Server logs for a stuck
capture are under `.field-backdoor/logs/`.

## Running the tests

**Smoke catalog** — exercises the annotated `FIELD_TEST` examples through the
backdoor:

```bash
uv run --project . --group backdoor python tools/run_field_tests.py --refresh-catalog
```

`--refresh-catalog` rebuilds `build/field-tests/catalog.json` first, then runs
each discovered test one at a time. The runner prewarms one persistent Field on
`--port` for the whole run (pass `--no-prewarm` to reuse an already-running
server instead). Per-test timeouts come from each test's annotation — **don't**
pass `--timeout` to this runner.

**Browser UI regressions** — drives Playwright-backed UI workflows and keeps
screenshots:

```bash
uv run --project . --group backdoor python tools/run_ui_regressions.py --port 9490 --timeout 45
```

It prints `RUN` / `PASS` / `FAIL` lines; artifacts are retained under
`build/ui-regressions/artifacts/<test-id>/` (including `latest-pass.png` and
`latest-failure.png`).

## How it works, briefly

Both pipelines talk to Field through `tools/field_backdoor.py`: they start (or
reuse) a single Field server on your chosen port, create scratch boxes, run the
example code, and screenshot the resulting output areas — the same output areas
you see when you use Field interactively. Because one server is shared across
many captures, keep runs on a dedicated port and let the runner own it. When
something misbehaves, the backdoor's logs under `.field-backdoor/logs/` are the
first place to look.
