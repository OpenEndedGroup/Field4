# Documentation capture freshness and determinism

Documentation verification has three independent requirements:

1. **Execution:** every declared capture completes successfully on its first attempt.
2. **Freshness:** every generated asset is invalidated when an input that affects it changes.
3. **Determinism:** two runs from the same commit and capture environment produce the same meaningful
   result.

A green docs build proves execution for the captures it actually ran. It proves freshness only for
inputs represented in the capture cache key, and it proves determinism only when two independent runs
are compared.

## Build modes

Run docs through the repository wrapper so Markdown hooks, capture dependencies, and the Zensical
build use one environment:

```sh
FIELD_DOCS_RUNNER_PORT=9477 \
  uv run --project . --group docs python tools/build_zensical_docs.py
```

`FIELD_DOCS_RUNNER_MODE` controls capture selection:

| mode | contract |
| --- | --- |
| `never` | Build HTML and reuse existing assets. This does not verify captures. |
| `incremental` | Capture missing assets and assets whose known inputs changed. |
| `strict` | Invalidate captures when capture-runner code changes. |
| `always` | Run every declared capture. Use this for complete execution evidence. |

The docs runner owns its configured port for the complete invocation. It rejects a foreign or
source-stale Field process before the first capture, starts each required server exactly once, and
terminates its process group at the end. A capture failure fails the page and the build; there is no
retry or continue-on-error path.

The determinism auditor is the deliberate exception to fail-fast traversal: it enables
continue-through-failure so one broken capture does not hide findings later in the corpus. Every
such fallback is recorded as `unknown-failed`, and the audit exits non-zero.

### Video timing

Docs video fences use frame-synchronous recording. The recorder relaunches the target after its
runloop hook is installed, waits for preparation output to cross a browser paint acknowledgement,
clears marker/output state at the first active boundary, and waits for the clear to paint. It
disables caret/CSS animation and the decorative WebGL background, stores exactly one browser paint
per completed Field frame, then advances `AnimationClock` by `1 / fps`. A clip contains exactly
`ceil(duration × fps)` consecutive Field frames; host load changes capture time, not simulation time
or the sequence of states written to the MP4. The aggregate watchdog budget scales with required
frame count, while a stuck output drain or individual screenshot remains independently bounded.
Each ephemeral capture session receives a fresh browser context/page so compositor state cannot leak
between scratch documents. Ad-hoc backdoor recordings remain real-time unless `--frame-synchronous
--restart-target` is requested explicitly.

## Cache coverage

The capture digest currently includes:

- fence code and capture options;
- page path and block index;
- the docs-hook script version and fixed runner-source digest;
- declared bulk-gallery scene inputs;
- referenced macro/transcript contents;
- directly attributable source references found in fence code.

This gives incremental builds useful direct-source coverage, but it is not a complete dependency
graph. An `always` build bypasses the cache when complete execution evidence is required.

## Two-run determinism audit

Use the production capture path twice from one unchanged commit:

```sh
uv run --project . --group docs python tools/docs_capture_audit.py \
  --repeat 2 --port 9540
```

The auditor stores both runs under `.field-backdoor/docs-audit/`, restores tracked capture assets,
and compares each run with both `HEAD` and the other run. A page-scoped invocation reports only that
page:

```sh
uv run --project . --group docs python tools/docs_capture_audit.py \
  --page functions_examples --repeat 2 --port 9540
```

For the video determinism gate, skip still and audio captures and compare decoded frames directly:

```sh
uv run --project . --group docs python tools/docs_capture_audit.py \
  --media video --repeat 2 --port 9540
```

Keep the audit commit and capture environment fixed for both passes. Do not move `HEAD` during the
audit. A dedicated worktree is convenient but not required; the runner owns its port and process,
and each scratch document owns a fresh browser context. Other Field instances and worktrees can
compete for CPU/GPU resources and increase wall time, but they do not choose simulation timestamps or
which Field frames enter a frame-synchronous movie. Avoid them when audit throughput matters. The
auditor records its starting commit and aborts if `HEAD` changes.

Verdicts have the following current meaning:

| verdict | meaning |
| --- | --- |
| `identical` | The meaningful content agrees. Video cadence must match; frames may be exact or meet the per-frame SSIM floor described below. Container bytes may differ. |
| `jitter` | Pixel differences remain inside the deliberately small antialiasing tolerance. |
| `stale*` | The runs reproduce one another but not the committed asset. |
| `suspect` | One run differs meaningfully; a second observation is required. |
| `unknown-failed` | The capture did not complete, so no freshness claim is possible. |
| `nondeterministic*` | The two runs disagree meaningfully. |
| `unstable` | At least one run matches the committed asset, but the two runs disagree meaningfully. |
| `new` | The capture has no committed comparison asset. |

`stale*`, `suspect`, `unknown-failed`, `nondeterministic*`, and `unstable` are findings and produce a
non-zero exit. `jitter` is the only tolerated run-to-run pixel difference. Its threshold exists for
rasterizer antialiasing noise, not for changed geometry, labels, dimensions, text, or timing.

MP4 and MOV comparisons decode each movie to cadence metadata plus an ordered RGB `framemd5`
sequence. Different container bytes with identical decoded timing and frames are `identical`. When
frame hashes differ, the audit computes SSIM per corresponding frame and accepts the pair only when
every frame is at least `0.995`; the post-fix visualizer canary's benign codec/raster differences
measured `0.999165` or better. Missing/reordered frames, cadence changes, and structure below that
floor remain semantic video findings. Opaque binary formats such as WAV, EXR, and GLB retain the
`*-binary` verdicts.

## Interpreting results

- Do not adopt a generated asset merely because it differs from `HEAD`; first determine whether the
  difference is a reproducible code change or capture-environment drift.
- Do not turn a nondeterministic verdict green by selecting the run that happens to match `HEAD`.
- Route elapsed-time animation through `AnimationClock`; it is monotonic wall time interactively and
  exact `frameIndex / fps` time during frame-synchronous capture.
- Fix dimension changes by waiting for the owning DOM/output layout condition, not by adding sleep.
- Inspect images and decoded video frames before committing regenerated media.

## Current video acceptance audit

The first complete audit of the frame-synchronous video contract ran on 2026-08-03 from the working
tree based on `08cf87eba32bdc8fe88c695b4a8162d4a6d2c6ed`:

```sh
uv run --project . --group docs python tools/docs_capture_audit.py \
  --media video --repeat 2 --port 9577 --allow-dirty
```

Run 1 completed in 534.7 seconds and regenerated all 22 declared videos with zero capture failures.
Run 2 completed in 513.2 seconds and regenerated all 22 with zero failures. Decoded cadence and
ordered RGB frames agreed across runs for every video; there were no `unknown-failed`,
`nondeterministic`, or `unstable` results.

| verdict | count |
| --- | ---: |
| `stale` | 22 |

`stale` here means the current capture is reproducible but differs from the old committed movie. It
is not a flaky result. After review, this 22-video reproducible set replaced the old committed
movies. Future video changes should pass the same two-run decoded-frame gate before adoption.
