# Dev Docs Index

`dev_docs/` mixes long-lived implementation notes with newer topical folders. This index calls out the cleaned-up clusters that used to live in the repository root. After a large consolidation pass, many quickstart/visual-guide/summary variants were merged into a single canonical note per feature — the entries below point at those survivors.

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
- [`kdoc_reference_contract.md`](./kdoc_reference_contract.md) — how a KDoc block reaches the completion panel and `public/docs/reference/`, which declaration forms the extractor can and cannot see, and what `checkKDocAttachment` enforces
- [`stdlib_behaviour_changes.md`](./stdlib_behaviour_changes.md) — the running ledger of `afield.stdlib` fixes that change what already-written box code *computes*, with the caller sweep behind each one
- [`FABRICATE_RENDERER_ARCHITECTURE.md`](./FABRICATE_RENDERER_ARCHITECTURE.md)
- [`DOCS_RUNNER_GPU_CAPTURES.md`](./DOCS_RUNNER_GPU_CAPTURES.md) — authoring `diffstatic_runner` fences for GPU/compute/probe captures, and the compute+draw-in-one-box limitation

## Feature Notes (post-consolidation survivors)

- [`SLIDESHOW_FEATURE.md`](./SLIDESHOW_FEATURE.md) — the slideshow DSL (absorbed the former architecture/quickstart notes); annotations live in [`SLIDESHOW_ANNOTATIONS.md`](./SLIDESHOW_ANNOTATIONS.md), images in [`SLIDESHOW_IMAGES.md`](./SLIDESHOW_IMAGES.md)
- [`INTERACTIVE_GUIDES.md`](./INTERACTIVE_GUIDES.md) and [`GUIDE_CHAT_QUICKSTART.md`](./GUIDE_CHAT_QUICKSTART.md) — interactive onboarding + optional guide chat
- [`SNAPPING_SYSTEM.md`](./SNAPPING_SYSTEM.md) — the snapping system (absorbed the former visual guides/examples)
- [`VALUE_VISUALIZER.md`](./VALUE_VISUALIZER.md) — value visualizer (absorbed the implementation/quick-ref notes); runnable demo in [`viz_demo.kt`](./viz_demo.kt)
- [`OFFLINE_VIEWER.md`](./OFFLINE_VIEWER.md) and [`OFFLINE_DEPLOYMENT.md`](./OFFLINE_DEPLOYMENT.md) — offline viewer + deployment (absorbed the offline quickstart)
- [`LAYERS_TESTING.md`](./LAYERS_TESTING.md) — layers (absorbed the layers implementation note)
- [`DOCUMENT_HISTORY.md`](./DOCUMENT_HISTORY.md) / [`HISTORY_TESTING_GUIDE.md`](./HISTORY_TESTING_GUIDE.md) — document history + its manual test guide
- [`FileWatchManager.md`](./FileWatchManager.md) / [`FileWatchManager_Testing.md`](./FileWatchManager_Testing.md) — watcher ownership map and focused end-to-end checks; implementation detail lives in KDoc
- [`physics/SIMDVERLET_4_CONSTRAINTS.md`](./physics/SIMDVERLET_4_CONSTRAINTS.md) — SIMD Verlet constraint solver

`AGENTS.md` remains in the repository root because agent tooling expects it there.
