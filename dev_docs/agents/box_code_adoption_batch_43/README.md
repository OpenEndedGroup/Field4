# Real tracked analyses and reusable corpus results

Baseline: `5e02bad8c`. Seven findings: `77839dd1b5da-01`, `7f432af2dc54-01`, `69b2bbfea726-01`, `69b2bbfea726-02`, `a4f09a84ef47-01`, `a4f09a84ef47-02`, and the previously implemented/fresh-service-pending `3b06cede4fe6-01`.

## Teaching changes

The beat/pitch page and starting point use `trackAnalysis` for submit/watch/await/inspect, inside an outer `runAsync` that owns lookup, child cancellation, guarded source publication and the final board. HTTP continuations explicitly re-enter the originating RunScope. Shared clients are cached; durable service jobs survive local cancellation. Errors use escaped Markdown. Source windows, algorithm parameters, dashboard dimensions, source captions remain intact in the initial edit. The landing reconciliation below retains main’s newer real cached gallery.

The spectral page uses the already reviewed starting-point implementation: one tracked job, one artifact and three Field-owned distance/projection fits. It retains the original distances, pooling, seeds and fit settings.

The corpus page publishes `nancy`, `preview` and `canonical` as typed box properties after successful operations. Instructions explicitly keep index, query and placement fragments in one box and wait for index completion. The three snippets now work together. Index/query subscriptions and HTTP continuations belong to their initiating run; weights, corpus names and query range remain editable and unchanged.

Real import revealed that the service can return only an original WAV representation. The changed live examples prefer canonical audio and fall back to an actual WAV, matching the existing spectral pattern.

## Real service evidence

A private API on 18765 uses the clean pinned service source at `8ffcb6b545fe04d3138192eec703fcaadb2d64c5`. Its project, workers and Python environment live under this checkout's ignored build directory. Default services and the shared service source are unchanged.

Bootstrap submits a fresh, uncached real spectral job, observes terminal COMPLETE and matching request hash, downloads the real NPZ and checks its arrays. The test input is an explicitly generated six-second WAV; it is neither Nancy nor a fabricated analysis output. The API has no remaining connection after bootstrap, confirming the watch was released despite the missing disconnect log line.

Both authored beat/pitch bodies complete real librosa beat, pYIN and Basic Pitch jobs. Real preview operation names and result metadata are checked. The spectral page decodes the worker artifact and runs all three real Smile t-SNE fits; all four projection axes exist and values are finite. The corpus page imports two distinct test WAVs, completes actual segmentation/descriptors, queries with same-asset exclusion and nonempty neighbours, and executes the placement-enabled follow-up using the exported results. Fixture substitutions affect input paths, service address and short test ranges only; no model outputs are substituted and no published capture is replaced.

### External worker and harness limits

The stock macOS worker applies a TFLite-file checksum to Basic Pitch's auto-selected CoreML directory, causing a real IsADirectoryError. Verification uses an isolated copy of the worker with explicit ONNX model selection and its SHA-256; `worker_setup.py`, the exact portability patch and environment changes make that adaptation reviewable. It runs actual packaged Basic Pitch 0.4.0 inference through ONNX Runtime 1.23.2. This does not establish that the unmodified macOS worker works.

Two simultaneous identical service requests also exposed a UNIQUE constraint race on analysis_results.request_hash. That backend issue is retained in evidence and remains a separate service repair. The lifecycle proof changes the source window on relaunch, as the original finding requests; it does not claim that backend race is fixed.

An initial lifecycle rehearsal used transcript `execute`, which deliberately executes a fragment and retains existing work. The corrected harness uses actual launch/relaunch and preserves fragment semantics. The earlier diagnostics are retained rather than interpreted as evidence that fragments should cancel their surrounding run.

Actual whole-box Stop and changed-window relaunch cancel the old operation. A shared callback ledger records no old-run publication after the replacement starts and exactly one new-run result. Both tests use real, longer pYIN jobs so the replacement occurs while work is pending. Durable old jobs remain server-owned.

## Mechanical coverage

Actual import/ktfmt covers eleven Kotlin bodies/states, including the unchanged offline galleries. The initial edit converted five original transcript listOf calls; main subsequently replaced that old illustrative gallery with real cached results, which this reconciliation retains. Reviewed token/container checks preserve YAML metadata, prose outside the approved edit, raw strings and fragment/fold markers. Scoped transcript compile and idiom gates pass. No GLSL body changes in this batch.

`evidence.tar.gz` contains real verdicts and diagnostic failures. The private virtual environment, mutable service project and model cache are excluded. The full 405-finding sweep and corpus-wide mechanical coverage remain incomplete.

## Landing reconciliation

The original queue item `20261018-065111-codex-box-code-readability-43` bounced on four authored-source conflicts. Main had added a genuine Nancy capture contract, real cached JSON previews, output-card cleanup/opening, waveform dimensions and documentation folds. The repair uses `tools/merge_regen.py main`, retains those incoming changes and integrates the verified run ownership. The transcript live body now matches the page's executable body. The real cached gallery and all incoming operation metadata are preserved. `merge-resolution.json` records the reviewed resolution separately from the original edit; import/ktfmt and token/container verification run again. Fixture harnesses use the actual docs fold extractor, so folds never enter Kotlin as text.

The reconciliation passes merge_regen's required full guard. A fresh checkout-matching Field on 9714 then loads the incoming real cached gallery and replays both beat/pitch bodies, all three spectral fits, and the index/query/placement fragments successfully. No real Nancy captures are regenerated with the synthetic fixture. The initial queue bounce is resolved in code; the repaired branch is queued separately.
