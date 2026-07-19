# External workspace synchronization

`FileWatchManager` keeps an open Field document synchronized with external edits to
its `metadata.edn` and sidecar files. The authoritative lifecycle, merge semantics,
internal-save suppression, deletion behavior, and orphan-file rules are documented
on [`FileWatchManager`](../src/jvmMain/kotlin/afield/FileWatchManager.kt).

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

End-to-end checks that require a running document and real filesystem events remain in
[FileWatchManager_Testing.md](./FileWatchManager_Testing.md).
