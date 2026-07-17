# Testing Guide for FileWatchManager

Canonical testing map: `dev_docs/testing/TESTING_STRATEGY.md`. This file is a
feature-specific manual checklist for external sidecar edit behavior (session
cleanup, rapid edits, metadata reconciliation, self-healing). There is no
automated jvmTest for `afield.FileWatchManager`, so this runbook is the canonical
coverage for these scenarios.

## Prerequisites
- Field4 server running
- A workspace with at least one box containing code or shader properties

## Manual Testing Steps

### Test 1: External Code Edit
1. Open a Field4 document
2. Note a box with code (e.g. box ID: `abc123`)
3. Locate the sidecar file: `<workspace>/abc123_code_.kt`
4. Open this file in an external editor (VS Code, Sublime, etc.)
5. Make a change and save
6. **Expected Result**:
   - Field4 console shows: "Reloaded external change: code in box [name] (abc123)"
   - User sees notification: "Reloaded external change to code in [name]"
   - The code editor in Field4 UI updates with the new content

### Test 2: External Shader Edit
1. Find a box with a fragment shader (e.g. box ID: `xyz789`)
2. Locate the file: `<workspace>/xyz789_fragment_.glsl`
3. Edit the shader in an external editor and save
4. **Expected Result**:
   - Console message about reload
   - UI updates with new shader code
   - If the shader is active, rendering updates

### Test 3: Internal Save (Should Not Reload)
1. Edit code directly in Field4 UI
2. Save the document (Field4 writes to disk)
3. **Expected Result**:
   - Normal save notification
   - NO reload message
   - The file watcher marks this as an internal save

### Test 4: Multiple Rapid Edits
1. Make several quick edits to a sidecar file
2. Save multiple times in the external editor
3. **Expected Result**:
   - Each distinct change is detected
   - Content stays in sync
   - No errors or race conditions

### Test 5: Session Cleanup
1. Open a document (file watcher starts)
2. Check console for: "FileWatchManager started for [path] with self-healing enabled"
3. Close all browser tabs for that document
4. **Expected Result**:
   - Console shows: "FileWatchManager stopped for [path]"
   - No lingering watch threads

### Test 6: Orphaned Sidecar File (Self-Healing)
1. Externally create a sidecar file whose box does not exist
   (e.g. `newbox123_code_.kt`) in the workspace, or delete a box in the UI and
   then edit its leftover sidecar file externally
2. **Expected Result**:
   - Console shows: "Found orphaned sidecar file: <filename>" and
     "Auto-created box: <name> (<id>) ..."
   - A new box is auto-created (generated name + non-overlapping frame) with the
     file's content, and the workspace is saved so the box persists

## Debugging

### Relevant Log Messages
Check the Field4 console for:
- `FileWatchManager started for <path> with self-healing enabled`
- `FileWatchManager stopped for <path>`
- `Reloaded external change: <property> in box <name> (<id>)`
- `Box not found for sidecar file: <filename> - attempting auto-creation`
  (the watcher then auto-creates the box rather than giving up)
- `Found orphaned sidecar file: <filename>` (periodic orphan check)
- `External change detected to metadata.edn`
- `Error reloading sidecar file <name>: <message>` (on errors)

### Common Issues

**Issue**: Changes not detected
- Verify the file follows the naming pattern: `<id>_<property>_.<ext>`
- Check the file extension is a sidecar extension — the authoritative set is
  `Box.ioSeparateProperties` (see FileWatchManager.md); it includes kt, glsl,
  slang, stacksl, js, md, and yaml
- Ensure ~15 seconds have passed since Field4's last internal save (the
  internal-save grace window)

**Issue**: Wrong box updated
- Verify the box ID in the filename matches the actual box ID
- Check that the box exists in the session's box registry

**Issue**: Watcher doesn't start
- Check that SessionInformation was created correctly
- Verify the workspace directory exists and is accessible

## Performance Monitoring

Watch for:
- CPU usage (should be minimal when idle)
- Memory usage (the file watcher should not leak)
- File handle count (should close cleanly on session end)
- Latency (changes should appear within 1-2 seconds)

## Edge Cases to Test

1. **File deletion**: Delete a sidecar file externally. The watcher registers
   `ENTRY_DELETE`, so the deletion is observed by `handleSidecarDeletion()`: it
   stops tracking the file (a later re-create is treated as a fresh external
   change) and leaves the box's property untouched.
2. **File creation**: Create a new sidecar file externally (see Test 6 —
   self-healing auto-creates a box for it)
3. **Large files**: Edit a very large code file (should handle without issues)
4. **Binary corruption**: Try to load a corrupted file (should log error and continue)
5. **Permission issues**: Make the file read-only (should handle gracefully)

## Success Criteria

- External edits are detected within 1-2 seconds
- Field4 UI updates automatically
- Internal saves don't trigger reloads
- Orphaned sidecar files auto-create boxes
- No performance degradation
- Clean startup and shutdown
- No memory leaks after multiple session cycles
- Robust error handling
