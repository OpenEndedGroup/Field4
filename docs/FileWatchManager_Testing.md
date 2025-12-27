# Testing Guide for FileWatchManager

## Prerequisites
- Field4 server running
- A workspace with at least one box containing code or shader properties

## Manual Testing Steps

### Test 1: External Code Edit
1. Open a Field4 document
2. Note a box with code (e.g., box ID: `abc123`)
3. Locate the sidecar file: `<workspace>/abc123_code_.kt`
4. Open this file in an external editor (VS Code, Sublime, etc.)
5. Make a change and save
6. **Expected Result**: 
   - Field4 console shows: "Reloaded external change: code in box [name] (abc123)"
   - User sees notification: "Reloaded external change to code in [name]"
   - The code editor in Field4 UI updates with the new content

### Test 2: External Shader Edit
1. Find a box with a fragment shader (e.g., box ID: `xyz789`)
2. Locate the file: `<workspace>/xyz789_fragment_.glsl`
3. Edit the shader in an external editor
4. Save the file
5. **Expected Result**: 
   - Console message about reload
   - UI updates with new shader code
   - If shader is active, rendering updates

### Test 3: Internal Save (Should Not Reload)
1. Edit code directly in Field4 UI
2. Save the document (Field4 writes to disk)
3. **Expected Result**:
   - Normal save notification
   - NO reload message
   - File watcher marks this as internal save

### Test 4: Multiple Rapid Edits
1. Make several quick edits to a sidecar file
2. Save multiple times in external editor
3. **Expected Result**:
   - Each distinct change is detected
   - Content stays in sync
   - No errors or race conditions

### Test 5: Session Cleanup
1. Open a document (file watcher starts)
2. Check console for: "FileWatchManager started for [path]"
3. Close all browser tabs for that document
4. **Expected Result**:
   - Console shows: "FileWatchManager stopped for [path]"
   - No lingering watch threads

### Test 6: New Box Created
1. Create a new box in Field4
2. Add some code to it
3. Save the document
4. Edit the new box's sidecar file externally
5. **Expected Result**:
   - File watcher detects the new box's file
   - Reload works for the new box

## Debugging

### Enable Verbose Logging
Check the Field4 console for these log messages:
- `FileWatchManager started for <path>`
- `FileWatchManager stopped for <path>`
- `Reloaded external change: <property> in box <name> (<id>)`
- `Box not found for sidecar file: <filename>` (if box doesn't exist)
- `Error reloading sidecar file <name>: <message>` (on errors)

### Common Issues

**Issue**: Changes not detected
- Verify the file follows naming pattern: `<id>_<property>_.<ext>`
- Check file extension is in supported list (kt, glsl, js, yaml)
- Ensure at least 2 seconds have passed since Field4's last save

**Issue**: Wrong box updated
- Verify box ID in filename matches actual box ID
- Check that box exists in session's box registry

**Issue**: Watcher doesn't start
- Check that SessionInformation was created correctly
- Verify workspace directory exists and is accessible

## Performance Monitoring

Watch for:
- CPU usage (should be minimal when idle)
- Memory usage (file watcher should not leak)
- File handle count (should close cleanly on session end)
- Latency (changes should appear within 1-2 seconds)

## Edge Cases to Test

1. **File deletion**: Delete a sidecar file externally (should handle gracefully)
2. **File creation**: Create a new sidecar file externally (should start tracking it)
3. **Large files**: Edit a very large code file (should handle without issues)
4. **Binary corruption**: Try to load a corrupted file (should log error and continue)
5. **Permission issues**: Make file read-only (should handle gracefully)

## Success Criteria

✅ External edits are detected within 1-2 seconds  
✅ Field4 UI updates automatically  
✅ Internal saves don't trigger reloads  
✅ No performance degradation  
✅ Clean startup and shutdown  
✅ No memory leaks after multiple session cycles  
✅ Robust error handling
