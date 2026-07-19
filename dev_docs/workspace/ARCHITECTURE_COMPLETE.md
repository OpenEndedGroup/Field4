# Workspace persistence and external edits

A Field document is a directory containing `metadata.edn` plus optional sidecars for
large text properties. Three code areas own the behavior:

- [`saving.kt`](../../src/jvmMain/kotlin/afield/saving.kt) snapshots the live boxes,
  writes metadata and sidecars, and records writes so they are not reloaded as external
  changes.
- [`FileWatchManager.kt`](../../src/jvmMain/kotlin/afield/FileWatchManager.kt) observes
  external filesystem events and reconciles them with the open session.
- [`Boxes.kt`](../../src/jvmMain/kotlin/afield/Boxes.kt) defines the persistent and
  sidecar-backed property sets.

The detailed watcher contract is colocated as `FileWatchManager` KDoc and summarized in
[the external synchronization note](../FileWatchManager.md). In particular, metadata
reconciliation is deliberately asymmetric: it adds boxes newly found on disk and
tombstones boxes removed from disk, but it does not overwrite every property of boxes
already present in the session.

## Workspace orientation files

`SavingContext.ensureDirectory(...)` also calls the private `ensureAgentGuides(...)`.
That helper copies:

- `templates/WORKSPACE_README.md` to `README.md`
- `templates/WORKSPACE_AGENTS.md` to `AGENTS.md`

It copies only missing destinations and treats failure as non-fatal. The exact discovery,
copy, and fallback behavior belongs to the KDoc beside `ensureAgentGuides(...)` and
`findRepositoryRoot()` in `saving.kt`; it is not duplicated here.

The copied files orient people and agents to an existing workspace. They are not part of
the document serialization format, and Field does not update a destination after it has
been created.

## Verification

- Sidecar-name parsing: `examples.file-watch.sidecar-path-parse` in the `FIELD_TEST`
  catalog.
- Save/load behavior: persistence smoke tests under
  `src/jvmMain/kotlin/afield/examples/`.
- Filesystem event and live-session behavior: the focused manual checks in
  [FileWatchManager_Testing.md](../FileWatchManager_Testing.md).

Avoid copying timing, grid, property, or control-flow constants into this document. Those
details change with the implementation and are documented beside the code that owns them.
