# FileWatchManager

## Overview

The `FileWatchManager` is a component that monitors workspace directories for external file changes and automatically reloads sidecar files into Box properties, syncing changes to the frontend.

## Purpose

Field4 stores Box properties in two ways:
1. **Main metadata**: Stored in `metadata.edn`
2. **Sidecar files**: Large text properties like code, shaders, and customizations are stored in separate files

The FileWatchManager addresses the need to detect when these sidecar files are modified by external editors (like VS Code, Sublime, etc.) and automatically reload them into Field4, keeping the UI in sync.

## Architecture

### File Naming Convention

Sidecar files follow this naming pattern:
```
<boxId>_<propertyName>_.<extension>
```

Example:
- `abc123_code_.kt` - Kotlin code for box abc123
- `xyz789_fragment_.glsl` - Fragment shader for box xyz789
- `def456_customization_.js` - JavaScript customization for box def456

### Supported Properties

The following properties are stored in separate sidecar files:

| Property | Extension |
|----------|-----------|
| fragment | glsl |
| vertex | glsl |
| compute | glsl |
| task | glsl |
| mesh | glsl |
| code | kt |
| customization | js |
| remote_js | js |
| metadata | yaml |

## How It Works

### 1. Initialization

When a SessionInformation is created (i.e., when a document is opened), the FileWatchManager:
- Registers the workspace directory with Java's WatchService
- Scans all existing sidecar files and records their timestamps
- Starts a background coroutine to monitor file changes

### 2. External Change Detection

The FileWatchManager distinguishes between:
- **Internal saves**: When Field4 itself saves a file
- **External changes**: When a file is modified by another application

This is achieved by:
1. Marking files with timestamps when Field4 saves them
2. Ignoring file changes within 2 seconds of an internal save
3. Only reloading files that have been modified externally

### 3. Reload Process

When an external change is detected:
1. Parse the filename to extract box ID and property name
2. Look up the box in the session's box registry
3. Read the new file content
4. Update the box property (if content has actually changed)
5. Trigger `needsResync` to push the change to the frontend
6. Display a notification to the user

### 4. Cleanup

When all WebSocket sessions for a document are closed, the FileWatchManager:
- Stops the watch loop
- Closes the WatchService
- Cleans up all internal data structures

## Usage

The FileWatchManager is automatically instantiated and managed by SessionInformation. No manual intervention is required.

### For Users

1. Open a Field4 document
2. Edit any sidecar file (e.g., `mybox_code_.kt`) in an external editor
3. Save the file
4. The changes will automatically appear in Field4's UI

### For Developers

The FileWatchManager integrates with the existing Box property system:

```kotlin
// When a file changes externally, this happens automatically:
box.setPropertyRaw(propertyName, newContent, doResync = true)

// To mark an internal save (done automatically in SavingContext.save()):
sessionInfo.fileWatchManager.markInternalSave(file)
```

## Performance Considerations

- **Polling Interval**: The watch service polls every 1 second
- **Debouncing**: 100ms delay after detecting a change to ensure file write is complete
- **Internal Save Window**: 2-second window to ignore our own saves
- **Minimal CPU Usage**: Only processes events when files actually change

## Limitations

1. Only monitors the workspace directory (not subdirectories)
2. Only tracks sidecar files (not the main metadata.edn)
3. Requires the session to be active (closes when all sessions disconnect)
4. File changes are detected with ~1 second latency

## Future Enhancements

Potential improvements:
- Recursive directory watching for nested workspaces
- Configurable polling intervals
- Support for monitoring metadata.edn changes
- More sophisticated conflict resolution for simultaneous edits
