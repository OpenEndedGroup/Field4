# Document History Browser

## Overview

The Document History Browser is a new feature that allows you to visualize and navigate through the complete version history of a Field4 document. It provides a vertical timeline showing all versions saved in the `backups/` directory and enables you to restore any previous version.

## Features

### Version Timeline Visualization
- **Vertical SVG Timeline**: All document versions are displayed in chronological order
- **Version Metadata**: Each version shows:
  - Version number
  - Timestamp (both absolute and relative)
  - Number of boxes in that version
  - Filename of the backup
- **Visual Indicators**:
  - Current version highlighted in green
  - Restored versions shown in orange
  - Transition arrows showing version jumps

### Version Restoration
- **Click to Select**: Click any version to see detailed information
- **Restore Button**: Restore the selected version with one click
- **Smart Restoration**:
  - Restores all properties from the target version
  - Tombstones boxes not present in that version (doesn't delete them)
  - Un-tombstones boxes that were present
  - Marks restored boxes with `_restored_from_version` property
  - Creates a backup of the current state before restoring

### Transition Tracking
- All version transitions are recorded in `versions.edn`
- Curved lines show non-linear version jumps
- Prepared for future forking and merging scenarios

## Architecture

### Backend Components

#### `history.kt` - HistoryManager
Core functionality for managing document history:

- **`listVersions(documentPath)`**: Scans the `backups/` directory and current `metadata.edn` to build a complete version list
- **`restoreVersion(documentPath, sessionInfo, targetVersionIndex)`**: Performs the version restoration
- **`getTransitions(documentPath)`**: Returns all recorded version transitions

#### Message Handlers (in Server.kt)
- `list-history-versions`: Returns the complete version list and transitions
- `restore-history-version`: Triggers version restoration

#### Data Structures (in rpc.kt)
- `HistoryVersion`: Represents a single version with metadata
- `RestoreVersion`: Request message for restoration
- `VersionTransition`: Records a transition between versions

### Frontend Components

#### `history.html`
Self-contained HTML page with:
- SVG-based timeline rendering
- WebSocket communication
- Interactive version selection
- Restoration UI with confirmation

### Data Files

#### `backups/` Directory
Contains backup versions of `metadata.edn`:
- `metadata_backup_00000.edn`
- `metadata_backup_00001.edn`
- etc.

These are automatically created by the `safeSave()` function whenever the document changes.

#### `versions.edn`
Records version transitions:
```clojure
{:transitions [{:fromIndex 5
                :toIndex 2
                :timestamp 1704225600000}
               {:fromIndex 2
                :toIndex 6
                :timestamp 1704226800000}]}
```

## Usage

### Accessing the History Browser

1. Open your document in Field4
2. Navigate to: `http://localhost:9092/history?file=<document-name>`
   - Replace `<document-name>` with your document's folder name
   - Example: `http://localhost:9092/history?file=my_project`

### Viewing Version History

1. The timeline loads automatically showing all versions
2. Versions are numbered from 0 (oldest) to N (current)
3. Hover over any version to see the exact timestamp
4. The current version is highlighted in green at the bottom

### Restoring a Previous Version

1. Click on the version you want to restore
2. Review the version details in the right panel:
   - Version number
   - Timestamp
   - Box count
   - Filename
3. Click "Restore This Version"
4. Confirm the restoration in the dialog
5. The system will:
   - Create a backup of the current state
   - Restore all properties from the selected version
   - Tombstone boxes not in that version
   - Un-tombstone boxes that were present
   - Record the transition in `versions.edn`
6. Refresh your main document page to see the restored state

### Understanding Tombstoning

When you restore a previous version:
- **Boxes in the target version**: Fully restored with all properties
- **Boxes NOT in the target version**: Marked with `_tombstone: true`
  - They remain in the document but are hidden
  - Can be un-tombstoned if needed
- **Previously tombstoned boxes**: Restored if they're in the target version

This approach ensures no data is permanently lost during restoration.

## Technical Details

### Version Numbering
- Versions are numbered sequentially starting from 0
- Index 0 = oldest backup (`metadata_backup_00000.edn`)
- Index N-1 = current state (`metadata.edn`)

### EDN File Format
Backup files use the same format as `metadata.edn`:
```clojure
{:version 2
 :boxes [{:id "box-id"
          :properties {:name "Box Name"
                       :code "println(\"Hello\")"
                       :frame [100.0 100.0 300.0 200.0]}}]
 :persistentProperties ["name" "code" "frame" ...]}
```

### Performance Considerations
- Version scanning is done on-demand when opening the history page
- Large numbers of backups (>100) may take a moment to load
- Box IDs are extracted without full parsing for efficiency

## Future Enhancements

### Planned Features
- **Forking**: Create branches from any version
- **Merging**: Merge changes from different branches
- **Diff View**: Visual comparison between versions
- **Search**: Find versions by content or metadata
- **Cleanup**: Archive or delete old versions
- **Auto-save Points**: Automatic version tagging at intervals

### Data Structure Extensibility
The current implementation is designed to support:
- Multiple branches (stored in enhanced `versions.edn`)
- Merge commits (with multiple parent versions)
- Branch metadata (names, descriptions, tags)

## Troubleshooting

### "No version history available"
- Cause: No `backups/` directory exists
- Solution: Make a change to the document to trigger the first backup

### "Failed to restore version"
- Check server console for error details
- Ensure the backup file hasn't been corrupted
- Verify the document session is properly established

### Timeline not rendering
- Check browser console for JavaScript errors
- Ensure WebSocket connection is established
- Verify you're passing the correct `?file=` parameter

### Versions missing from timeline
- Backup files must follow the naming pattern `metadata_backup_NNNNN.edn`
- Files must be valid EDN format
- Check file permissions

## Implementation Notes

### Why Tombstoning Instead of Deletion?
- Preserves data for potential future restoration
- Allows for selective un-deletion
- Supports future merge scenarios where boxes from different versions may need to be combined

### Why Sequential Version Numbers?
- Simple and deterministic
- Easy to understand and reference
- Avoids issues with timestamps (which can be non-monotonic)
- Supports future branch numbering schemes

### Why Separate `versions.edn`?
- Keeps transition history separate from content
- Allows for future graph-based version navigation
- Doesn't clutter backup files with metadata
- Easy to regenerate if corrupted

## API Reference

### RPC Messages

#### Request: `list-history-versions`
```json
{
  "name": "list-history-versions",
  "payload": {}
}
```

#### Response: `history-versions-list`
```json
{
  "name": "history-versions-list",
  "payload": {
    "versions": [
      {
        "index": 0,
        "filename": "metadata_backup_00000.edn",
        "timestamp": 1704225600000,
        "boxCount": 5,
        "message": ""
      }
    ],
    "transitions": [
      {
        "fromIndex": 5,
        "toIndex": 2,
        "timestamp": 1704225700000
      }
    ]
  }
}
```

#### Request: `restore-history-version`
```json
{
  "name": "restore-history-version",
  "payload": {
    "versionIndex": 2
  }
}
```

#### Response: `restore-version-success` or `restore-version-error`
```json
{
  "name": "restore-version-success",
  "payload": {
    "versionIndex": 2
  }
}
```

## Code Examples

### Programmatically List Versions
```kotlin
val versions = HistoryManager.listVersions("/path/to/document")
versions.forEach { version ->
    println("Version ${version.index}: ${version.boxCount} boxes at ${version.timestamp}")
}
```

### Restore a Version Programmatically
```kotlin
val success = HistoryManager.restoreVersion(
    documentPath = "/path/to/document",
    sessionInfo = currentSessionInfo,
    targetVersionIndex = 3
)
if (success) {
    println("Version 3 restored successfully")
}
```

### Get Transition History
```kotlin
val transitions = HistoryManager.getTransitions("/path/to/document")
transitions.forEach { trans ->
    println("Jumped from version ${trans.fromIndex} to ${trans.toIndex}")
}
```

## Testing

### Manual Testing Steps
1. Create a new document
2. Add some boxes
3. Save the document (creates first backup)
4. Modify boxes, add new ones
5. Save again (creates second backup)
6. Repeat several times
7. Open `/history?file=your-document`
8. Verify all versions appear
9. Select an older version and restore it
10. Verify boxes are correctly restored/tombstoned
11. Check `versions.edn` for recorded transition

### Expected Behavior
- Each save should create a new backup file
- Timeline should show all versions chronologically
- Clicking a version should show correct metadata
- Restoring should create a new backup before modifying
- Transitions should be recorded in `versions.edn`

## Related Files
- `/src/jvmMain/kotlin/afield/history.kt` - Backend implementation
- `/src/jvmMain/kotlin/afield/Server.kt` - RPC handlers (search for "history")
- `/src/commonMain/kotlin/rpc.kt` - Message type definitions
- `/src/commonMain/resources/history.html` - Frontend UI
- `/src/jvmMain/kotlin/afield/saving.kt` - Backup creation logic

## See Also
- File watching system: `FileWatchManager.kt`
- Git integration: `git.kt`
- Saving system: `saving.kt` (see `safeSave()` function)
