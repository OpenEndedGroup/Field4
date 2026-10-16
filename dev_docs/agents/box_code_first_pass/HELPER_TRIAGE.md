# Helper triage after the first sweep

All twelve proposals were approved by Marc on 2026-10-16, with the adjustments recorded below.
Implementation is pending. ToolPool notes are reserved for the house-machine agents; Field Audio
uses the shared async mechanism from decision 5. The original proposal evidence remains unchanged.
The complete before/after sketches, checked alternatives, source evidence and required tests
are in [helper-proposals.json](helper-proposals.json). API spellings there are illustrative.

## Twelve new decisions

| Family | Decision | Scope and boundary |
| --- | --- | --- |
| Callable interpolant with `startAt` | **Approved 2026-10-16** | Return a public callable type from `smoothTo` and `inertialTo`; expose the existing restart operation without casts. Preserve callable use, uniform bindings and explicit restart. Implementation pending. |
| RectSL wall projection | **Approved 2026-10-16** | Expose the existing `WallSpec.worldToClip()` publicly; preserve centered/top-left origin semantics and verify both outputs. Implementation pending. |
| Faust render → WAV | **Approved 2026-10-16** | Add `render.wav(normalizePeak = ...)` using the existing stereo encoder. Encode existing samples; preserve level when normalization is omitted. Implementation pending. |
| Syphon client named reuse | **Approved with adjustment 2026-10-16** | `syphonClient(name)` returns the existing open client cached under that name; create/cache when missing or closed. Replaces the original per-call allocation/run-ownership proposal. Automatic close-on-rerun was not approved. Implementation pending. |
| Run-owned future publication | **Approved 2026-10-16; API rehearsal next** | Shared convention for growing long-running cached client/server workflows, including audio AI. Gate publication by originating run, report failures and request supported cancellation. Keep shared client/cache lifetime separate from operation ownership; distinguish stale-output suppression from stopping actual work. |
| Field Audio tracked analysis | **Approved 2026-10-16: use decision 5** | Apply the shared async mechanism to submit/watch/await/inspect. Reuse rendered-audio cancellation/connection cleanup; keep the requested operation explicit. Concrete API spelling follows the decision-5 rehearsal. |
| Box-owned OSC handler | **Approved 2026-10-16** | Agreed syntax: `osc(port).on(address, runOwned = true) { message -> ... }`. Release by registration identity on stop/rerun/retirement. Omitting the option preserves persistent behavior; cached server sockets stay open. Implementation pending. |
| ToolPool versioned notes | **Approved 2026-10-16; house-machine agents** | Marc will take implementation back to the house-machine agents. Excluded from this pass's implementation scope. Preserve revision identity, atomic writes, pagination and authorization contracts; method names remain illustrative. |
| Midpoint solid slice visitor | **Approved 2026-10-16: public API** | `solid.sampleSlices(n) { z, section -> ... }`: evenly spaced layer midpoints, collect callback results and close each native section after its callback. Keep integration math visible and document scoped section lifetime. Implementation pending. |
| Sampling grid FLine | **Approved with adjustment 2026-10-16** | `someFline.grid(bounds, nx, ny)` appends disconnected grid lines and returns the FLine, like `rect`. Counts are cells; styling stays on the FLine. Replaces the standalone factory proposal. Implementation pending. |
| Warmed minimum timing | **Approved 2026-10-16** | `bestTimeMs(repetitions = 5, warmup = 2) { ... }`: untimed warmups, shortest measured duration in milliseconds, ordinary exception propagation. Implementation pending. |
| Interaction snapshot stages | **Approved 2026-10-16** | `showInteractionStages(stage("Before", beforeLines, beforeSummary), ...)` arranges captured stages through the existing overview renderer. Keep mutation, scheduling and capture explicit; earlier snapshots must stay independent. Implementation pending. |

## Eight previously approved families rediscovered

These require implementation/discovery and signature checks, not another approval:

- `uint` push members accepting `Int` lambdas.
- Numeric `.fN` properties; the two proposal keys were consolidated.
- `computeBarrier()` with explicit compute/graphics synchronization semantics.
- `bindPush(m1, m2) { ... }`.
- `fullscreenTriangle()`.
- Bounded `markdownAppend`, including old-run/new-run output ordering.
- `Key` vararg pairs.
- Mesh measurement/seeded-point conveniences (`compare`, `integrate`, `randomPoints`).

The authoritative prior decisions remain in [BOX_CODE_HELPER_IDEAS.md](../BOX_CODE_HELPER_IDEAS.md).
This list contains only families rediscovered by this sweep, not every earlier approval.
Do not rewrite examples to call a helper until its actual implementation compiles.

## Existing operations to reuse

Three proposal families are existing API reuse: `LogDispatcher.withSink` (including its extension
import), lowering a SolidSL field once before dense sampling, and `Solid.scope` for native
lifetimes. Findings also identify existing FLine rectangles/circles, `breakNext`, `data`, structure
indexing and generated push properties. These need no new API decision.

Automatic dispatch sizing, auto-attaching/presenting pass chains and the other declined ideas
remain declined. Explicit dispatch counts, editable values and declaration/assignment reset
points stay visible.

## Suggested sequence

1. Implement/check the eight previously approved families and the new approved work, excluding
   ToolPool notes (Marc will take that to the house-machine agents). Honor the named Syphon cache,
   append-style `FLine.grid`, and opt-in OSC `runOwned = true` adjustments.
2. Resolve the shared async ownership contract before replacing repeated adapters.
3. Apply second-pass edits in bounded groups, using the saved findings and formatter candidates.
4. Compile each changed box in its actual context; run/capture the affected behavior, including
   rerun and stop. Keep fixture fragments and signature examples in their documented roles.
