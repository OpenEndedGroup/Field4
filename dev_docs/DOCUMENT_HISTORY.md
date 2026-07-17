# Document History (backend / RPC contract)

Dev-facing reference for the document-history backend: the `HistoryManager` API,
the RPC message shapes, the on-disk EDN formats, and the restore/tombstone
semantics.

For the **current user-facing UI** (timeline rail, diff view, search, named
versions, cherry-pick restore, activity/screenshots) see the single authoritative
description in `public/docs/document_history.md` — that doc is kept in sync with
`src/commonMain/resources/history.html` and is not restated here.

## Backend components

### `history.kt` — `HistoryManager`
Core functionality for managing document history:

- `listVersions(documentPath)` — scans the `backups/` directory and the current
  `metadata.edn` to build a complete version list.
- `restoreVersion(documentPath, sessionInfo, targetVersionIndex)` — performs a
  full version restoration (see semantics below).
- `getTransitions(documentPath)` — returns all recorded version transitions.
- `diffVersionAgainstLive(...)` / `diffVersionAgainstPrevious(...)` / `boxDiff(...)`
  — box-level diffs between versions / against the live document.
- `searchVersionTextChanges(...)` / `searchVersionTextChangesAgainstLive(...)`
  — content search across versions.
- `setVersionName(...)` / `getVersionNames(...)` — per-version friendly names.

### RPC handlers — `history.kt`, `installHistoryHandlers()`
The WebSocket RPC handlers are registered in `installHistoryHandlers()` in
`history.kt` (not in `Server.kt`). Key messages: `list-history-versions`,
`restore-history-version`, `search-version-text`, and the diff/name RPCs.
`Server.kt` only serves the static HTTP page routes `/history` and
`/history-screenshot`.

### Data structures — `rpc.kt`
- `HistoryVersion` — a single version with metadata.
- `RestoreVersion` — restoration request message.
- `VersionTransition` — records a transition between versions.
- `HistoryVersionsList` — response payload wrapper.

## Restore & tombstone semantics

`restoreVersion` does a full, non-destructive restore:

- Boxes present in the target version are fully restored (all persistent
  properties) and marked with `_restored_from_version`.
- Boxes **not** present in the target version are tombstoned (`_tombstone: true`)
  rather than deleted, so they remain recoverable.
- Previously tombstoned boxes that exist in the target version are un-tombstoned.
- A backup of the current state is created before restoring, and the transition
  is recorded in `versions.edn`.

Tombstoning (instead of deletion) preserves data for future restoration,
selective un-deletion, and merge scenarios.

## Data files

### `backups/` directory
Backup copies of `metadata.edn`, named `metadata_backup_NNNNN.edn`
(`metadata_backup_00000.edn`, `_00001.edn`, …). Created by the save path in
`saving.kt` (`safeSave()`) whenever the document changes.

### Version numbering
- Sequential from 0. Index 0 = oldest backup; the live `metadata.edn` is included
  as the current (highest-index) version even when `backups/` is absent.

### Backup EDN format
Same shape as `metadata.edn`:
```clojure
{:version 2
 :boxes [{:id "box-id"
          :properties {:name "Box Name"
                       :code "println(\"Hello\")"
                       :frame [100.0 100.0 300.0 200.0]}}]
 :persistentProperties ["name" "code" "frame" ...]}
```

### `versions.edn`
Records transitions and per-version names. `saveVersionsData` always writes both
`:transitions` and `:names` keys (even when `:names` is empty):
```clojure
{:transitions [{:fromIndex 5 :toIndex 2 :timestamp 1704225600000}
               {:fromIndex 2 :toIndex 6 :timestamp 1704226800000}]
 :names {2 "before refactor"}}
```

## Access

The history view is served at `http://localhost:9092/history?file=<document-name>`
where `<document-name>` is the document's folder name.

## Performance

- Version scanning is on-demand when the history page opens.
- Large numbers of backups (>100) may take a moment to load.
- Box IDs are extracted without full parsing for efficiency.

## API reference

### Request `list-history-versions`
```json
{ "name": "list-history-versions", "payload": {} }
```

### Response `history-versions-list`
```json
{
  "name": "history-versions-list",
  "payload": {
    "versions": [
      { "index": 0, "filename": "metadata_backup_00000.edn",
        "timestamp": 1704225600000, "boxCount": 5, "message": "" }
    ],
    "transitions": [
      { "fromIndex": 5, "toIndex": 2, "timestamp": 1704225700000 }
    ]
  }
}
```

### Request `restore-history-version`
```json
{ "name": "restore-history-version", "payload": { "versionIndex": 2 } }
```
On failure the client receives the generic message `Failed to restore version`;
the detailed reason (e.g. an invalid version index) is only logged server-side.

## Code examples

```kotlin
// List versions
val versions = HistoryManager.listVersions("/path/to/document")
versions.forEach { v -> println("Version ${v.index}: ${v.boxCount} boxes at ${v.timestamp}") }

// Restore a version
val success = HistoryManager.restoreVersion(
    documentPath = "/path/to/document",
    sessionInfo = currentSessionInfo,
    targetVersionIndex = 3
)

// Get transitions
HistoryManager.getTransitions("/path/to/document").forEach { t ->
    println("Jumped from version ${t.fromIndex} to ${t.toIndex}")
}
```

## Testing
Manual QA checklist: `dev_docs/HISTORY_TESTING_GUIDE.md`. Automated `history-panel`
coverage lives in the browser UI regression suite (see
`dev_docs/testing/TESTING_STRATEGY.md`).

## Related files
- `src/jvmMain/kotlin/afield/history.kt` — `HistoryManager` + `installHistoryHandlers()` (RPC handlers).
- `src/jvmMain/kotlin/afield/Server.kt` — static `/history` and `/history-screenshot` page routes only.
- `src/commonMain/kotlin/rpc.kt` — message type definitions.
- `src/commonMain/resources/history.html` — frontend UI.
- `src/jvmMain/kotlin/afield/saving.kt` — backup creation (`safeSave()`).
- `public/docs/document_history.md` — current user-facing UI reference.
