# External workspace synchronization

`FileWatchManager` keeps an open Field document synchronized with external edits to
its `metadata.edn` and sidecar files. The authoritative lifecycle, merge semantics,
internal-save suppression, deletion behavior, and orphan-file rules are documented
on [`FileWatchManager`](../src/jvmMain/kotlin/afield/document/persistence/FileWatchManager.kt).

Sidecars are named by `SavingContext.filenameFor(...)`:

```text
<box-id>_<property>_.<extension>
```

Both box ids and property names may contain underscores. The watcher resolves the
property from the known suffixes in `Box.ioSeparateProperties`, preferring an existing
box when a filename has more than one valid interpretation. The regression coverage
for this rule is
[`SidecarPathParseSmokeTest`](../src/jvmMain/kotlin/afield/examples/SidecarPathParseSmokeTest.kt).

The division of ownership is:

- `Boxes.kt`: the authoritative sidecar-backed properties and extensions.
- `saving.kt`: filenames, persistence, internal-save markers, and workspace guide
  seeding.
- `FileWatchManager.kt`: external events and reconciliation with the live session.
- `Server.kt`: the manager's document-session lifecycle.

The watcher is intentionally shallow: it observes the document directory, not nested
directories. External deletion of a sidecar does not delete the box property, and
external deletion of `metadata.edn` is ignored. Existing sidecars form the startup
baseline; automatic box creation applies to files created or changed after watching
begins.

Three behaviours added on 2026-10-19 (unit coverage in `FileWatchMetadataReconcileTest`):

- An edit to the inline text of a `#afield/file-inline` value in `metadata.edn` (same path,
  new inline) is applied from the inline text; previously it was resolved through the untouched
  sidecar, changed nothing, and was still logged as "applied". Values already equal to the
  session are now logged as unchanged rather than counted.
- When a sidecar event yields to an unsaved in-Field edit (`watch-skip-pending-local`), the
  disk text is first copied to `backups/<sidecar>.external-<millis>` and a `file-watch`
  notification names it (`watch-preserve-overridden` in `save_reload.log`).
- The box property `onExternalEdit` (`run` | `reload-shader`, default unset) follows a
  reloaded sidecar with `ReactiveRuns.relaunch` or the box's Reload Shader action.

Closing a document (`SessionInformation.cleanup`) also force-flushes and forgets its
`PropertyDependencies` record and calls `ReactiveRuns.forgetDocument`, so a closed document leaves
no reactive bookkeeping behind.

End-to-end checks that require a running document and real filesystem events remain in
[FileWatchManager_Testing.md](./FileWatchManager_Testing.md).
