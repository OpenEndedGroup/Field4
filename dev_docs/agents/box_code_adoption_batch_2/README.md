# Helper adoption: async workflows

Branch `codex/box-code-async-adoption`; baseline `e97a8c4a9`.
Nine code bodies changed across three transcripts (ten boxes). No graph, description, frame,
operation-order or fragment-boundary changes. No library API changes.

## Changes

- All remaining Loris examples use `runAsync`. Independent resolution passes each own their
  analysis; composed edit, whirlpool and label/align/morph workflows explicitly cancel each native
  future they start. Late progress, labels, results and errors stay with the originating run.
- Label/align/morph uses the existing asynchronous native methods, keeping blocking workers out
  of publication callbacks. Its two equal-duration crops and marker lists retain their values.
- Whirlpool controls now name excursion in octaves, sweep cycles, shutter Hz and bandwidth blend
  above the transform. The sin-squared entry/exit envelope is explained. Completion/status messages
  distinguish analysis, labeling, alignment, morphing and completed output.
- Field Audio examples use `trackAnalysis` for submit/watch/await/final-state handling. `runAsync`
  owns their enclosing pipelines, cancellation hooks and guarded publication. HTTP continuations
  restore the captured `RunScope` before starting another tracked leg. The shared service/client
  and durable results remain outside operation ownership.
- Spectral pooling/fitting parameters and corpus query/distance controls are named near the top.
  Numeric text uses `.fN`; collection literals and mechanical import/ktfmt formatting are applied.

A composed future does not automatically cancel upstream workers. The explicit `onCancel` lines
are lifecycle behavior, not redundant ceremony. The t-SNE fitter has no interrupt hook: stop
suppresses publication and skips subsequent fits, but an already-running fit can finish. The code
states that limit instead of claiming worker cancellation it cannot provide.

## Evidence

- All three transcripts passed their selected compile/replay guard. The explicit collection-literal
  idiom audit is clean. Formatting includes the cached-gallery collection literal.
- **15 focused JVM tests passed, zero failures/errors/skips.** Two new real HTTP/WebSocket tests
  cover a tracked leg launched from a future continuation and a late continuation after its run
  has stopped. Existing success/cache, failed job/handshake, stop, async-resource and actual native
  Loris cancellation tests also ran.
- The four affected Loris workflows ran before and after. The selected/resynthesized sound, both
  resolution passes, whirlpool and aligned morph have **identical WAV bytes** before/after.
  This compares decoded real audio data, not rendered labels. Native work produced/cache-published
  the baseline results and the revised pipelines reproduced those requests. Final captures show
  the actual trajectories and waveforms; no audition claim is made.
- The real composed synthesis stage was observed and stopped. Its native child exited, its request
  directory disappeared and no stale result was published. The default worker was too brief to
  catch reliably, so a private cancellation fixture stretches the real selected model by 12× to
  make the native process observable. Teaching settings remain unchanged. The unsuccessful short
  observation is retained diagnostically and is not counted as passing.
- The cached spectral gallery computes three real t-SNE projections from committed MessagePack
  analysis frames. Its actual dashboard was captured and inspected.
- Field Audio's configured port 8765 currently serves an unrelated HTTP application. The two live
  boxes were exercised through actual HTTP failure and displayed escaped markdown errors, then
  released their run ownership. Successful **fresh Field Audio jobs were not exercised** here.
  HTTP/WebSocket lifetime tests use the repository's protocol test service, with no model outputs
  fabricated or captured. Full live-service validation remains explicit follow-up evidence.

The revised Loris captures and spectral dashboard were inspected in a separate headless browser;
the user's Electron/workspace was not used. [Verification record](verification.json) binds the
source hashes to the verdicts; [evidence archive](evidence.tar.gz) retains the genuine captures,
compact verdicts, exact emitted WAV bytes/hashes, native process record and test XML. Large inline
HTML is represented by length/hash in compact verdicts; the genuine audio and screenshots are retained.

## Run again

```sh
uv run --project . --group backdoor python tools/field_session.py serve --port 9698 --with-playwright --timeout 120
uv run --project . --group backdoor python dev_docs/agents/box_code_adoption_batch_2/rehearse.py
uv run --project . --group backdoor python dev_docs/agents/box_code_adoption_batch_2/cancel_synthesis.py
```

The two Loris findings `93089e79009a-01` and `93089e79009a-02` are addressed. The shared adapter
finding `3b06cede4fe6-01` is implemented and contract-tested, with fresh-service verification
pending. This batch is one checkpoint in the remaining corpus pass, not a completion claim.
