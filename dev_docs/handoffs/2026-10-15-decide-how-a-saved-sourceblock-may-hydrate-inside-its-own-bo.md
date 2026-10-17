# Decide how a saved sourceBlock may hydrate inside its own box's run

- **Handoff:** `f7e945ea-1a77-431d-83b9-cfd964260a6a` (project)
- **Filed from:** https://openendedgroup.com/field4/running_code/; also affects keyframer_examples.md
- **Filed by:** human, 2026-10-15 23:29
- **Close with:** `python tools/docs_notes.py handoff-close f7e945ea "what happened"`

Refiled from 43488706 (which merged 73b7cbd0, keyframer_examples.md "The push-constant convention"). The running_code.md warning and the keyframer sentence are still on their pages.

State, 2026-10-15: `SourceBackedBlock.hydrate` (`src/jvmMain/kotlin/afield/study/source/SourceBackedBlock.kt`) compiles and evaluates the stored source on its own box's engine. Evaluating there while the body is mid-run re-runs the body. `SourceBackedBlockSelfBoxReentry` already refuses the *second* hydration on a thread (via the shared `hydratingBoxes` set), so it's 2 body runs, not 327. The first can't be refused, because it looks exactly like a legitimate frame-loop or callback hydration.

The fix the brief asks for can't be a guard. The block returns its value synchronously, so it can't be deferred, and a file-loaded block has no lambda from "the current run". The remaining option is to hydrate a block called during its own box's run (`Box.context.get()?.id == boxId`) on a **different engine** — a scratch engine with the Box as implicit receiver. That changes semantics: the stored source could no longer see declarations made in the box's own code. This needs a decision on whether that is acceptable (or whether such blocks may only use the receiver plus library API), and then a test mirroring the `kotlin.source-backed-block.{self-box,two-box}` smoke probes (`FnRewriteKotlinLiveBoxProbe.kt`). The same choice applies to `SourceBackedFnRewriteNumberExpression` (`rewriteKotlin` `@{}`) and to the keyframe code-blend path in `afield/animation/keyframes/`.

## Decision, 2026-10-16 (Marc)

**Error.** A `sourceBlock` (and `SourceBackedFnRewriteNumberExpression`, and the keyframe code-blend path) that would hydrate on its own box's engine while that box's body is running throws a clear error, not a re-run and not a scratch-engine hydration. Hydrate such blocks from the frame loop or a callback instead.

Implementation note: `Box.context.get()?.id == boxId` cannot tell a body run from a frame-loop/callback hydration, so execution needs an explicit "body executing" marker around the box's body run (not set during frames or callbacks), and `hydrate` checks that marker. Pin it with a test mirroring the `kotlin.source-backed-block.{self-box,two-box}` probes (`FnRewriteKotlinLiveBoxProbe.kt`): self-box during the body errors, and hydration from a frame still works. Then update the running_code.md warning and the keyframer sentence to state the error.
