# Dev Docs Index

`dev_docs/` contains current implementation contracts, operating procedures, and focused design
notes. The entries below point to the canonical note for each maintained subsystem.

## Topical Folders

- `agents/`: agent-facing workspace guides and references (plus `agents/transcripts/` operation-transcript fixtures)
- `testing/`: smoke-test discovery, annotation, and runner strategy notes
- `workspace/`: self-healing metadata, guide deployment, and workspace architecture notes
- `animation/`: `animate {}` syntax and implementation notes
- `ai/`: AI-assisted docs/search notes
- `stdlib/`: user-facing Kotlin/stdlib feature notes
- `physics/`: simulation- and math-heavy implementation notes
- `experiments/`, `manual_tests/`: throwaway HTML fixtures and manual test pages

## Good Entry Points

- [`agents/README.md`](./agents/README.md) — the map of the agent automation path (start here)
- [`agents/OPERATION_TRANSCRIPT.md`](./agents/OPERATION_TRANSCRIPT.md) — canonical build/edit/launch/save automation format
- [`agents/FIELD_BACKDOOR.md`](./agents/FIELD_BACKDOOR.md) — low-level run/capture/screenshot harness
- [`testing/SMOKE_TEST_ANNOTATION_STRATEGY.md`](./testing/SMOKE_TEST_ANNOTATION_STRATEGY.md)
- [`testing/TESTING_STRATEGY.md`](./testing/TESTING_STRATEGY.md) — where to add coverage and the specialized manual-test references
- [`workspace/ARCHITECTURE_COMPLETE.md`](./workspace/ARCHITECTURE_COMPLETE.md) — short ownership map for persistence, external edits, and workspace guide seeding
- [`animation/ANIMATE_BLOCK_SYNTAX.md`](./animation/ANIMATE_BLOCK_SYNTAX.md)
- [`ai/DOCS_SEARCH_FEATURE.md`](./ai/DOCS_SEARCH_FEATURE.md)
- [`stdlib/GENERATED_LINALG_ARCHITECTURE.md`](./stdlib/GENERATED_LINALG_ARCHITECTURE.md) — generated linear-algebra source and storage architecture
- [`COMPLETION_ENGINE.md`](./COMPLETION_ENGINE.md)
- [`completion_popup_cover.md`](./completion_popup_cover.md) — the *other* half of completion: what `fieldSuggest.js` does to candidates in the browser (ranking, grouping, category titles, doc pane), the `check`-wired test that runs that real JavaScript under node, and what it deliberately does not cover
- [`kdoc_reference_contract.md`](./kdoc_reference_contract.md) — how a KDoc block reaches the completion panel and `public/docs/reference/`, which declaration forms the extractor can and cannot see, and what `checkKDocAttachment` enforces
- [`citation_contracts.md`](./citation_contracts.md) — how a `file:line` in a dev_doc becomes a checked fact: the three citation guards and what each actually covers, symbol anchors and why line numbers are deliberately advisory, how to bring a new page under guard, and the four failure modes (stale line, stale derived count, unguarded prose, and the `check` gate that silently did not run)
- [`private_boundary.md`](./private_boundary.md) — the one-way rule around `src/personal/`: Field may read private work, nothing committed may depend on it. What `.gitignore` protects and what it does not, the six assertions `PersonalBoundaryTest` makes and the one that cannot run outside the author's machine, why the import allowlist is inverted, where the debt ledger lives and why its staleness check is the half most ledgers omit, what the twelve debts were and how each closed, and what to do when you hit the failure. Read this before generalising a lesson out of the private tree
- [`guard_population_hygiene.md`](./guard_population_hygiene.md) — how a guard goes green over a population that no longer resembles the one its name describes: the loose floor, the number printed but never asserted, the undeclared task input, and a population that differs between two checkouts of the same commit
- [`chart_primitive.md`](./chart_primitive.md) — `Chart`, the FLine plot primitive: what sixteen files were each hand-rolling (and the five private copies of Heckbert's tick algorithm), what was *not* missing before you add to it, the three glyph-label traps, why marks are hoverable by construction, `expect` as a drawn claim, and the two harnesses that verify it with floors set near their measurements. Loadable demo in [`agents/demos/chart_primitive/`](./agents/demos/chart_primitive/)
- [`path_tracer_instrument.md`](./path_tracer_instrument.md) — reading a running path tracer, and replacing its integrator without rebuilding it: what the reflective `.h2` *already* showed (33 properties, which is exactly the class's public property count) against what `.h` showed (a `@19266ca3` and nothing else), the fact no property tree can reach — the tracer builds its own up as `right × forward` and never reads `camera.up`, 11.94° apart on the shipped demo — the frame of lag that made the first verification compare an image with itself, why a sustained camera drag is *not* a falling sample count, the one `showSignals` call that is the entire plotting story, and the five backdoor rig traps that cost four failed runs. Loadable demo in [`agents/demos/path_tracer_instrument/`](./agents/demos/path_tracer_instrument/)
- [`stdlib_behaviour_changes.md`](./stdlib_behaviour_changes.md) — the running ledger of `afield.stdlib` fixes that change what already-written box code *computes*, with the caller sweep behind each one
- [`docs_capture_staleness.md`](./docs_capture_staleness.md) — current execution, cache-freshness, and two-run determinism contracts for generated documentation media
- [`STACKSL_CPU_BACKEND.md`](./STACKSL_CPU_BACKEND.md) — `afield.stacksl.cpu`, which runs StackSL with no GPU by interpreting the GLSL the emitter emits: why an interpreter for the output rather than an `eval:` per word (StackSL has no symbolic op node to hang one on), the measured subset, the four *different* accuracy mechanisms and the four different honest thresholds they earn — `_hash21` is exact-but-amplifying while `_hash11` is `sin`-based and implementation-defined — the characterisation against the 31 committed GPU stills, and the bit-exactness cross-check that found `VolumetricNoiseReference`'s `mix`. Loadable demo in [`../public/docs/assets/stacksl_cpu_demo/`](../public/docs/assets/stacksl_cpu_demo/)
- [`FABRICATE_RENDERER_ARCHITECTURE.md`](./FABRICATE_RENDERER_ARCHITECTURE.md)
- [`GPU_PROBE.md`](./GPU_PROBE.md) — compute-shader probes with fixture buffers, dispatch completion, readback checks, and teardown
- [`DOCS_RUNNER_GPU_CAPTURES.md`](./DOCS_RUNNER_GPU_CAPTURES.md) — authoring `diffstatic_runner` fences for GPU/compute/probe captures, and the compute+draw-in-one-box limitation
- [`box_shared_state_races.md`](./box_shared_state_races.md) — current concurrency ownership for box/session collections, remaining ownership gaps, and deterministic regression requirements
- [`DOCS_IMPROVEMENT_AGENT.md`](./DOCS_IMPROVEMENT_AGENT.md) — the documentation annotation panel and the `codex exec` run behind it: the threat model (Field binds `0.0.0.0` on purpose, so a route that must not be reachable from the network guards itself), the enable/token/working-directory contract, and what is deliberately left unsolved
- [`field_audio_seam_stub.md`](./field_audio_seam_stub.md) — how the `afield.stdlib.noir.audioai` seam is tested when the Python service it talks to is not installed: the in-process Ktor stub, the protocol facts it takes from the service rather than from the Kotlin, the explicit list of what it deliberately does *not* model, and the defects it found — each quoted with the failure it was seen to produce
- **The log layer** — no dev_doc of its own, because the published page carries the whole story: [`public/docs/logging.md`](../public/docs/logging.md) ("Where Field's Output Goes", filed under Internals). What a channel is (a flat comma-separated tag list, not a level and not a logger name), `LogDispatcher`'s open sink list and the eight sinks on it, and the thing that will actually catch you: **three mechanisms select on a channel and all three match differently** — `ConsoleMute` on the exact whole string, the pinned console on an exact comma-split tag, `LogStream`'s filter on a case-insensitive substring — which differ over 101 real tag-and-channel pairs in this tree today. Also why muting is a console policy rather than a filter (a muted line is still archived and still delivered), what the `lblock` indent really holds, and why `SessionErrorLog` exists at all: `System.err` never enters the dispatcher. Guarded by `CitationContractTest` and by five derivations in `ProseNumeralContractTest`. Loadable demo in [`agents/demos/logging_layer/`](./agents/demos/logging_layer/)
- [`worktree_retention_policy.md`](./worktree_retention_policy.md) — what `tools/reap_merged_worktrees.py` will and will not delete: the named retention families and why they pair a worktree *root* with a branch prefix rather than widening a prefix list (the 61 GiB half of the population was unreachable by branch name alone), the idle guard and the morning it was needed — a dry run offered a live agent's worktree a minute before that agent committed, because the `lsof` cwd check is a race and its coverage changes run to run — and the report/`--apply` disagreement that let the ranked report promise space the tool could not free
- [`MIDI.md`](./MIDI.md) — the Standard MIDI File surface: why the work was a *lift* rather than a new reader (both directions already ran in production, privately, inside `AbletonSet.kt`), the three time representations and which conversion has to stay an exact rational, the two keys the `Pat` vocabulary does not have and what export reads instead, the measured fact that opening a MIDI input stops the JVM exiting, and why the audio assertion is on peak amplitude and not file size

## Feature Notes (post-consolidation survivors)

- [`SLIDESHOW_FEATURE.md`](./SLIDESHOW_FEATURE.md) — the slideshow DSL (absorbed the former architecture/quickstart notes); annotations live in [`SLIDESHOW_ANNOTATIONS.md`](./SLIDESHOW_ANNOTATIONS.md), images in [`SLIDESHOW_IMAGES.md`](./SLIDESHOW_IMAGES.md)
- [`INTERACTIVE_GUIDES.md`](./INTERACTIVE_GUIDES.md) and [`GUIDE_CHAT_QUICKSTART.md`](./GUIDE_CHAT_QUICKSTART.md) — interactive onboarding + optional guide chat
- [`SNAPPING_SYSTEM.md`](./SNAPPING_SYSTEM.md) — the snapping system (absorbed the former visual guides/examples)
- [`VALUE_VISUALIZER.md`](./VALUE_VISUALIZER.md) — value visualizer (absorbed the implementation/quick-ref notes); runnable demo in [`viz_demo.kt`](./viz_demo.kt)
- [`DATAFRAME_PLUGIN.md`](./DATAFRAME_PLUGIN.md) — Kotlin DataFrame's compiler plugin inside the K2 REPL: why the plugin jar is off the app classpath and the runtime version is a *pin*, the five spike probes and what each measured, why the inferred type cannot cross a compile boundary and why a compiled `@DataSchema` gives you the type but not the accessors, what `typed()` does instead, the `//%-` fragment/preamble re-entry fact, and the unwired `schemasPath` route. Public page: `public/docs/dataframe.md`
- [`OFFLINE_VIEWER.md`](./OFFLINE_VIEWER.md) and [`OFFLINE_DEPLOYMENT.md`](./OFFLINE_DEPLOYMENT.md) — offline viewer + deployment (absorbed the offline quickstart)
- [`LAYERS_TESTING.md`](./LAYERS_TESTING.md) — layers (absorbed the layers implementation note)
- [`DOCUMENT_HISTORY.md`](./DOCUMENT_HISTORY.md) / [`HISTORY_TESTING_GUIDE.md`](./HISTORY_TESTING_GUIDE.md) — document history + its manual test guide
- [`FileWatchManager.md`](./FileWatchManager.md) / [`FileWatchManager_Testing.md`](./FileWatchManager_Testing.md) — watcher ownership map and focused end-to-end checks; implementation detail lives in KDoc
- [`physics/SIMDVERLET_4_CONSTRAINTS.md`](./physics/SIMDVERLET_4_CONSTRAINTS.md) — SIMD Verlet constraint solver
- [`NVFLOW_BINDINGS.md`](./NVFLOW_BINDINGS.md) — reconnaissance, not a contract: what an `afield.flow` binding to NVIDIA Flow would involve, the two integration paths and the buffer-only interop constraint that separates them, and the platform fact (Windows/Linux only) that decides where any of it can run
- [`COMPRESSED_SPLAT_PLY.md`](./COMPRESSED_SPLAT_PLY.md) — reading PlayCanvas `.compressed.ply` Gaussian splats: the chunk/vertex/sh element layout, the four packed words and their bit fields, the quaternion largest-component trick, the one deliberate difference from the reference decoder (a saturated opacity byte inverts to an infinite logit, which renders 1.2% of a real capture half-transparent), and how the reader was checked against splat-transform itself

`AGENTS.md` remains in the repository root because agent tooling expects it there.
