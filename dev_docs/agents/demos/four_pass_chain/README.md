# four_pass_chain — a heterogeneous multi-resolution pass chain, live

**Replay this to see it:**

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --scratch \
  --command 'macro write four_pass_chain --file dev_docs/agents/demos/four_pass_chain/four_pass_chain.yaml' \
  --command 'macro run four_pass_chain'
```

It builds a fresh three-box document — a renderer, a lit scene, and one `addPassChain(…)` call —
opens a Vulkan window titled **Pass chain**, and shows a grid of spheres with a two-disc aperture
blur composited over it. The chain box prints its whole plan when it runs.

Then open the **pass chain** box and edit the three numbers under the `once` block:

```kotlin
chain.setPush("aperture-near", "radius", 4f)
chain.setPush("aperture-far", "radius", 14f)
chain.setPush("composite", "blend", 0.92f)
```

Hit run. The blur changes on the next frame with no pipeline rebuild and no target reallocated,
because `setPush` writes the push constant on the pass's existing fullscreen quad. That is the
difference between a chain you *declare* and a chain you *assemble*.

## Why this is a demo and not a corpus transcript

It lives here rather than in `dev_docs/agents/transcripts/` deliberately. That corpus is a guarded
registry: `checkAgentTranscriptIndex` requires a hand-written entry in
`public/docs/agent_transcripts.md`, `DemoTranscriptExecSmokeTest` carries per-depth totals that a
new file moves, and `checkAgentTranscripts` byte-compares a materialised starting point. Adding a
file there is a four-artifact change, and three of those artifacts are generated. Promoting this
into the corpus is a good follow-up; it is not the same change as the layer it demonstrates.

Unlike its neighbours in `dev_docs/agents/demos/`, this directory has no `metadata.edn`: the scene
wants a live Vulkan window, and `--scratch` documents are not persisted, so there is no saved
document to commit. `AgentDemoDriftGuard` and `AgentTranscriptLayoutGuard` both skip a demo
directory with no `metadata.edn`, so this costs nothing.

## Verified

Replayed end to end into a fresh `--scratch` document on 2026-09-07: `applied=12 skipped=0
errors=0`, all three boxes printing normally, and a `native-screenshot` of the resulting window
showing the blurred sphere grid.

Two traps this file already pays for, so the next reader does not:

- **`afield.four.fabricated` is not among a box's automatic imports.** `afield.four`,
  `afield.four.modules`, `afield.four.targets`, `afield.four.buffers` and `afield.four.texture` all
  are (see `intro` in `src/jvmMain/kotlin/afield/Boxes.kt`), but the fabricated renderer is not — so
  `StandardRenderer.build(renderer)`, which several published pages show being typed straight into a
  box, needs an explicit import first.
- **`prop<T>()`'s code rewrite does not carry the snippet's own `import` statements.** With
  `import afield.four.fabricated.StandardRenderer` at the top,
  `prop<StandardRenderer.Bundle>(bundle)` fails — and the error, `Unresolved reference
  'StandardRenderer'`, is reported against the *import* line, which reads like a missing class
  rather than a rewrite dropping it. Every other reference in the same box resolves through that
  import normally. The workaround here is to write the `prop<>` type argument fully qualified:
  `prop<afield.four.fabricated.StandardRenderer.Bundle>(bundle)`.

## See also

- `dev_docs/FOUR_PASS_CHAIN.md` — what the layer is and why it exists.
- `./gradlew runFabricatePassChainDemo` — the same layer driven headlessly, writing PNGs to
  `build/pass-chain/` and asserting on their pixels (`FIELD_TEST four.pass-chain.smoke`).
