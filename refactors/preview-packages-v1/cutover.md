# Source cutover

The private refactor worktree now contains the actual declaration relocation. This is an authored change, not application of a stale planner blob. Replan after source and document repairs, then run the configured checks before delivery.

The original workspace is untouched. The active session contains `cutover-20261014/source-before` and `before.patch`, preserving all pre-cutover source and the earlier authored edits. `relocations.json` records each original source and its destination.

The move uses Kotlin PSI top-level partitions. Shared private helpers remain one declaration and are widened to internal only when extracted siblings need them. Generated linear algebra comes from its generator configuration. Steam Audio Java bindings move with their Kotlin wrappers.

Two collisions required distinct destinations: the FLine upload helper is `graphics/render/FLineUpload.kt`; the StackSL box integration is `lang/stacksl/StackSLBox.kt`, separate from `StackSL.kt` even on case-insensitive filesystems. The evolution toolkit uses `lang/stacksl/evolution` to keep its StackSLWord distinct from the compiler's word type.

Persistent EDN tags and configuration keys retain their identity. Kotlin class names in generated code, reflection and launcher configuration follow the new names. JavaScript module variables are not Kotlin package references.

The assignment inventory describes the original source snapshot. Refresh the inventory for checks against relocated source; do not rerun original-path assignments on moved files.
