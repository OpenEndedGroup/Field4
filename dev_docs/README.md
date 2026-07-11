# Dev Docs Index

`dev_docs/` mixes long-lived implementation notes with newer topical folders. This index calls out the cleaned-up clusters that used to live in the repository root.

## Topical Folders

- `agents/`: agent-facing workspace guides and references
- `testing/`: smoke-test discovery, annotation, and runner strategy notes
- `workspace/`: self-healing metadata, guide deployment, and workspace architecture notes
- `animation/`: `animate {}` syntax and implementation notes
- `ai/`: AI-assisted docs/search notes and Dokka/KDoc work
- `stdlib/`: user-facing Kotlin/stdlib feature notes
- `physics/`: simulation- and math-heavy implementation notes

## Good Entry Points

- [`agents/README.md`](./agents/README.md) — the map of the agent automation path (start here)
- [`agents/OPERATION_TRANSCRIPT.md`](./agents/OPERATION_TRANSCRIPT.md) — canonical build/edit/launch/save automation format
- [`agents/FIELD_BACKDOOR.md`](./agents/FIELD_BACKDOOR.md) — low-level run/capture/screenshot harness
- [`testing/SMOKE_TEST_ANNOTATION_STRATEGY.md`](./testing/SMOKE_TEST_ANNOTATION_STRATEGY.md)
- [`workspace/ARCHITECTURE_COMPLETE.md`](./workspace/ARCHITECTURE_COMPLETE.md)
- [`animation/ANIMATE_BLOCK_SYNTAX.md`](./animation/ANIMATE_BLOCK_SYNTAX.md)
- [`ai/DOCS_SEARCH_FEATURE.md`](./ai/DOCS_SEARCH_FEATURE.md)
- [`stdlib/GRAPH_CONNECTIVITY_LIBRARY.md`](./stdlib/GRAPH_CONNECTIVITY_LIBRARY.md)
- [`COMPLETION_ENGINE.md`](./COMPLETION_ENGINE.md)
- [`FABRICATE_RENDERER_ARCHITECTURE.md`](./FABRICATE_RENDERER_ARCHITECTURE.md)
- [`DOCS_RUNNER_GPU_CAPTURES.md`](./DOCS_RUNNER_GPU_CAPTURES.md) — authoring `diffstatic_runner` fences for GPU/compute/probe captures, and the compute+draw-in-one-box limitation

`AGENTS.md` remains in the repository root because agent tooling expects it there.
