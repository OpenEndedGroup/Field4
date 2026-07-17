# FileWatchManager

## Overview

`FileWatchManager` (`src/jvmMain/kotlin/afield/FileWatchManager.kt`) monitors a
workspace directory for external file changes and reloads sidecar files into Box
properties, syncing the change to the frontend. It also watches `metadata.edn`
and self-heals orphaned sidecar files. Authoritative behavior lives in the
class's own KDoc; this doc is the architecture-level explainer.

## Purpose

Field4 stores Box properties in two ways:
1. **Main metadata**: stored in `metadata.edn`
2. **Sidecar files**: large text properties (code, shaders, notes, etc.) are
   stored in separate files

The FileWatchManager detects when these files are modified by external editors
(VS Code, Sublime, etc.) and reloads them into Field4, keeping the UI in sync.

## Architecture

### File Naming Convention

Sidecar files follow the pattern:
```
<boxId>_<propertyName>_.<extension>
```

Examples:
- `abc123_code_.kt` — Kotlin code for box abc123
- `xyz789_fragment_.glsl` — fragment shader for box xyz789
- `def456_customization_.js` — JavaScript customization for box def456

This convention is used across the codebase (saving.kt, workspace/dir.kt,
docs/doc.kt, spreadsheet.kt), not just by this class.

### Supported Properties

The set of sidecar-backed properties and their extensions is defined by
`Box.ioSeparateProperties` in `Boxes.kt` — that map is the single source of
truth. It currently spans code (`code`→kt), customizations (`customization`,
`remote_js`→js), GLSL shaders (`fragment`, `vertex`, `compute`, the
raygen/miss/closesthit/anyhit/intersection/callable ray-tracing family→glsl),
their Slang `_s` variants and `vertex_fragment`→slang, `stackSL`→stacksl,
`notes`→md, and `metadata`→yaml. Consult `Box.ioSeparateProperties` rather than
hardcoding a subset here.

## How It Works

### 1. Initialization (`start()`)

When a SessionInformation is created (a document is opened), FileWatchManager:
- Registers the workspace directory with Java's `WatchService`
  (`ENTRY_MODIFY` + `ENTRY_CREATE`)
- Scans existing sidecar files and records their timestamps
- Records the current `metadata.edn` timestamp
- Starts a background watch coroutine
- Starts a periodic (every 5s) orphan-file check

### 2. External Change Detection

The manager distinguishes:
- **Internal saves**: Field4 itself wrote the file
- **External changes**: another application modified the file

An internal save is recorded via `markInternalSave(file)` (its mtime, size and
wall-clock time). A later watch event is treated as internal — and therefore
skipped — when the file size still matches the recorded save and the event mtime
is within a 15-second grace window (`internalSaveGraceMs`) of the recorded
mtime. Internal-save signatures themselves expire after 2 minutes
(`internalSaveWindowMs`). Events that also correspond to a pending local sidecar
save are likewise skipped.

### 3. Sidecar Reload Process

When an external sidecar change is detected:
1. Parse the filename to extract box ID and property name
2. Look up the box; if it doesn't exist, auto-create one (see Self-Healing)
3. Read the new file content
4. Update the box property if the content actually changed
   (`setPropertyRaw(..., doResync = true)`) and mark it sidecar-pending for
   persistence
5. Notify the user

### 4. metadata.edn Reconciliation

External edits to `metadata.edn` are handled by `handleMetadataChange()`, which
(after skipping internal saves) calls `reconcileMetadata()`:
- Boxes newly present in `metadata.edn` but absent from the session are loaded
  and their properties applied.
- Boxes removed from `metadata.edn` are tombstoned (marked `_tombstone`), not
  hard-deleted.

There is currently no conflict resolution for concurrent metadata edits.

### 5. Self-Healing (Orphaned Sidecar Files)

An orphaned sidecar file (one whose box does not exist) is detected either on
reload or by the periodic orphan check. `autoCreateBoxForSidecarFile()` creates
a box with a generated display name (derived from the filename) and a
non-overlapping grid frame, loads the file content into the property, and
triggers a save so the new box persists.

`calculateNewBoxFrame()` lays boxes out on a grid starting at `(50, 50)` with
`350×250` frames, `20px` padding and `4` columns, scanning up to 100 cells for a
non-overlapping slot. If none is free it falls back to a deterministic offset
`(boxId.hashCode().toLong() and 0x7FFFFFFF) % 200`; the mask keeps the offset
non-negative and gives simultaneously created boxes distinct positions.

### 6. Cleanup (`stop()`)

When all WebSocket sessions for a document close, FileWatchManager cancels the
watch/orphan coroutines and the watch scope, cancels and clears the watch keys,
clears its bookkeeping maps, and closes the `WatchService`.

## Usage

FileWatchManager is instantiated and managed by SessionInformation; no manual
intervention is required.

### For Users

1. Open a Field4 document
2. Edit a sidecar file (e.g. `mybox_code_.kt`) in an external editor and save
3. The change appears automatically in Field4's UI

### For Developers

```kotlin
// On an external change this happens automatically:
box.setPropertyRaw(propertyName, newContent, doResync = true)

// To mark an internal save (done automatically in SavingContext.save()):
sessionInfo.fileWatchManager.markInternalSave(file)
```

## Performance Considerations

- **Poll interval**: the watch service polls every 1 second
- **Debounce**: 100ms delay after a sidecar event to let the write complete
- **Internal-save window**: 15s size-matched grace window; signatures expire
  after 2 minutes
- **Orphan check**: runs every 5 seconds
- **Minimal CPU**: events are only processed when files actually change

## Limitations

1. Only the workspace directory is watched, not subdirectories
2. External *deletion* of a sidecar file (`ENTRY_DELETE`) is observed and handled
   by `handleSidecarDeletion()`, which stops tracking the file but leaves the box
   property untouched (deleting the sidecar on disk does not wipe the property)
3. Requires an active session (watching stops when all sessions disconnect)
4. Changes are detected with ~1 second latency
5. No conflict resolution for concurrent `metadata.edn` edits

## Future Enhancements

- Recursive directory watching for nested workspaces
- Configurable polling intervals
- More sophisticated conflict resolution for simultaneous edits
