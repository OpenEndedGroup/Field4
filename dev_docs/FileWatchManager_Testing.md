# FileWatchManager end-to-end checks

The sidecar filename parser has automated smoke coverage in
[`SidecarPathParseSmokeTest`](../src/jvmMain/kotlin/afield/examples/SidecarPathParseSmokeTest.kt).
The checks below cover the remaining operating-system watch events and live-session
effects; they are manual until an isolated workspace/session harness owns that lifecycle.

Use a disposable document. Record its backdoor session and managed-server logs before
editing files so every expected event can be tied to the current process.

## External sidecar modification

1. Open a document containing a box with a sidecar-backed property such as `code`.
2. Edit `<box-id>_code_.kt` outside Field and save it.
3. Confirm the editor receives the exact new contents and the session log contains
   `watch-reload` followed by `reload-sidecar` for that box and property.
4. Save from Field and confirm the resulting filesystem event produces
   `watch-skip-internal` or `watch-skip-pending-local`, not a second reload.

## Sidecar creation and deletion

1. After the watcher has started, create `<new-box-id>_code_.kt` in the document
   directory.
2. Confirm Field creates one box with that exact id, loads its code, assigns a frame,
   and persists it to `metadata.edn`.
3. Delete the sidecar externally. Confirm `External deletion of sidecar ... observed`
   is logged and the in-memory property remains unchanged.
4. Recreate the file with different contents and confirm it is treated as a fresh
   external change.

An orphaned sidecar that already exists when the document opens is a startup baseline;
the watcher does not auto-create a box for that unchanged file.

## External metadata modification

1. Add a new, non-tombstoned box record to `metadata.edn` and save externally.
2. Confirm the box appears and topology references resolve only to active boxes or the
   root.
3. Remove a different box record and save again. Confirm the live box is marked
   `_tombstone`; it is not hard-deleted.
4. Change a property of a box that already exists in both places. Confirm reconciliation
   leaves that live property unchanged.
5. Delete `metadata.edn` and confirm the event is ignored.

## Lifecycle

1. Open the document and confirm `FileWatchManager started ...` appears once.
2. Close every WebSocket session for it and confirm `FileWatchManager stopped ...`.
3. Confirm no later edit produces a watch event from that manager instance.

These checks establish observable behavior only. CPU, memory, and file-descriptor leak
claims require profiling and should not be inferred from an idle manual run.
