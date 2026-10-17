# Make self-box sourceBlock hydration during the body's run an error

- **Handoff:** `787d2538-cf34-4dac-bbb1-61c202e7095e` (project)
- **Filed from:** https://openendedgroup.com/field4/running_code/; also affects keyframer_examples.md
- **Filed by:** human, 2026-10-16 09:06
- **Close with:** `python tools/docs_notes.py handoff-close 787d2538 "what happened"`

Decided 2026-10-16 (Marc, handoff f7e945ea; see `dev_docs/handoffs/2026-10-15-decide-how-a-saved-sourceblock-may-hydrate-inside-its-own-bo.md`): a saved `sourceBlock` that would hydrate on its own box's engine while that box's body is running throws a clear error. Today it re-runs the body once before `SourceBackedBlockSelfBoxReentry` refuses the second hydration.

Work:

1. `Box.context.get()?.id == boxId` can't tell a body run from a frame-loop or callback hydration. Add an explicit "body executing" marker that execution (`afield/runtime/execution/execution.kt`) sets around the box's body run and that frames and callbacks don't set.
2. `SourceBackedBlock.hydrate` (`src/jvmMain/kotlin/afield/study/source/SourceBackedBlock.kt`) checks the marker and throws an error that names the box and says to hydrate from a frame or callback. Apply the same check to `SourceBackedFnRewriteNumberExpression` (`rewriteKotlin` `@{}`) and to the keyframe code-blend path in `afield/animation/keyframes/`.
3. Add a test mirroring the `kotlin.source-backed-block.{self-box,two-box}` smoke probes (`FnRewriteKotlinLiveBoxProbe.kt`): self-box hydration during the body errors and runs the body only once; hydration from a frame still works; the two-box case is unchanged.
4. Update the warning in running_code.md and the sentence in keyframer_examples.md ("The push-constant convention") to describe the error.
