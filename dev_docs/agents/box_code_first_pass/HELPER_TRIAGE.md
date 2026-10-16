# Helper triage after the first sweep

These are recommendations for Marc's decision. No new API is approved by this report.
The complete before/after sketches, checked alternatives, source evidence and required tests
are in [helper-proposals.json](helper-proposals.json). API spellings there are illustrative.

## Twelve new decisions

| Family | Recommendation | Reason and boundary |
| --- | --- | --- |
| Callable interpolant with `startAt` | Approve a small public type | Existing implementations support restart, but factories erase that capability behind `() -> T`, forcing casts. Preserve callable use and explicit restart. |
| RectSL wall projection | Approve exposing the existing operation | `WallSpec.worldToClip` already handles origin semantics internally. Expose that calculation and verify centered/top-left output. |
| Faust render → WAV | Approve a conversion on the existing render result | Remove repeated left/right/sample-rate unpacking. Encode the existing samples; no implicit second render or playback. |
| Syphon client run ownership | Approve the ownership convention | Register newly created clients with the current run. Preserve standalone explicit close and shared server lifetimes; prove close once on stop/rerun. |
| Run-owned future publication | Design before applying | Gate progress and completion by run identity. Cancellation must distinguish suppressing stale output from actually stopping native work. This is a shared correctness issue. |
| Field Audio tracked analysis | Design with the preceding ownership contract | Repeated submit/watch/await adapters drop transport ownership. Preserve explicit operation/progress and handle WebSocket handshake failure and cancellation at every stage. |
| Box-owned OSC handler | Design an opt-in registration | Release by registration identity so older cleanup cannot remove a replacement. Keep intentional persistent listeners and cached sockets available. |
| ToolPool versioned notes | Separate feature work | Typed note persistence is worthwhile, but includes revision identity, atomic writes, pagination and authorization contracts. It exceeds the readability pass. |
| Midpoint solid slice visitor | Rehearse locally before adding API | Repeated midpoint slicing and `Section.use` could share a visitor. Keep integration math visible and prevent closed native sections escaping. |
| Sampling grid FLine | Keep a local helper initially | Two examples share a small grid loop. Check whether a broader public drawing operation earns its surface area. |
| Warmed minimum timing | Keep a local/shared example helper initially | Three boxes repeat the same timer. Preserve explicit warmup/repetition policy and exception behavior; avoid implying a rigorous benchmark. |
| Interaction snapshot stages | Rehearse locally before adding API | Several examples repeat stage layout. Keep snapshots, reducer scheduling and mutation visible; reuse the existing overview renderer. |

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

1. Implement/check the eight approved families and the small new proposals Marc selects.
2. Resolve the shared async ownership contract before replacing repeated adapters.
3. Apply second-pass edits in bounded groups, using the saved findings and formatter candidates.
4. Compile each changed box in its actual context; run/capture the affected behavior, including
   rerun and stop. Keep fixture fragments and signature examples in their documented roles.
