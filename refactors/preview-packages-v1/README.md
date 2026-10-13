# Preview package migration

This candidate migration follows dev_docs/PACKAGE_REFACTOR_PROPOSAL.md. It is being prepared in a
private repository worktree and workspace copy; no workspace upgrade has been published.

Use expanded.json and assignments.json for the current ownership map. trial.json is the original
seed, not the current recipe. See symbol-map-workflow.md for generation and intro-migration.md for
box defaults. The current map has zero unassigned split-package families and zero inventory errors.

Remaining work is implementation and verification: relocate mixed source files and their private
helpers, repair same-package consumers, regenerate owner-managed outputs, resolve ambiguous
split-family uses and dynamic entrypoints, then compile/test JVM and JS and exercise representative
live boxes and EDN/sidecar pairs. Preserve persistent protocol identities such as EDN tags.

Private plans, inventories, source snapshots and recovery journals stay above this worktree in the
refactor session. Replan after source edits. Review decisions do not release held edits, and mapping
completion does not mean the package migration has compiled or landed.
