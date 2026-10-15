# Package migration recipe

The package relocation is implemented in this repository. The release boundaries are the
annotated tags `before-large-refactor` and `after-large-refactor`; the latter points back here.
External Field workspaces are migrated explicitly, not rewritten automatically when Field starts.

## Current recipe

- [expanded.json](expanded.json) is the migration recipe for old package and symbol references.
- [assignments.json](assignments.json) records the reviewed declaration ownership decisions.
- [Tool guide](../../tools/refactoring/README.md) explains building the standalone engine,
  planning, reviewing held findings, applying changes and undoing them.
- [Reference index](../../public/docs/reference/index.md) shows the resulting package hierarchy.

The recipe's original trial ID and API labels are retained for continuity. `trial.json` and
`refactors/examples/` are historical seeds and rehearsals, not the current upgrade recipe.
The source assignment inventory describes the pre-cutover snapshot: do not replay its original-path
relocations on the already-moved source tree.

## Migrating a workspace

Build the standalone engine as described in the tool guide. First plan against a separate copy
of the workspace, with reports outside the input directory:

```sh
uv run --project . --group backdoor python tools/refactor.py plan \
  refactors/preview-packages-v1/expanded.json \
  --workspace /path/to/workspace-copy --out /path/to/new-migration-report
```

Inspect `report.md`, `changes.diff`, `review.md` and `review-groups.json`. Held findings require
contextual review and a fresh plan; a review decision alone does not release them. Apply and undo
through the documented tool commands, which check input hashes and retain recovery data.
Compile any private Kotlin sources and run the migrated documents on the target platform before
using the migrated copy as your working workspace. Keep private reports and recovery journals local.

The migration preserves persistent EDN tags and configuration identities. It supplies explicit
new box defaults, while standalone Kotlin and other containers retain their own import context.
Syntax-based rewriting does not establish compiler binding equivalence or runtime compatibility.

[Source cutover](cutover.md), [intro migration](intro-migration.md), and
[symbol-map workflow](symbol-map-workflow.md) record the preparation process. Their session-specific
counts and next-step notes are historical; use this page and the tool guide for current entry points.
