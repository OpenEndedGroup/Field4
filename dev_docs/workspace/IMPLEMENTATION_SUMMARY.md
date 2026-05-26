# Self-Healing Metadata System - Implementation Summary

## Problem Statement

External AI agents (like Claude, Codex, Cursor) need to work on Field4 documents in parallel while Field is running. Previously, agents couldn't add or delete files because they would need to understand and correctly update the `metadata.edn` file.

## Solution

Implemented a **self-healing metadata system** that automatically:
1. Detects orphaned sidecar files (files without corresponding boxes)
2. Auto-creates boxes with sensible defaults (names, frames)
3. Watches `metadata.edn` for external changes
4. Reconciles the box hierarchy when metadata is edited
5. Enables live collaboration between Field and external agents

## Technical Implementation

### Enhanced FileWatchManager

**File**: `src/jvmMain/kotlin/afield/FileWatchManager.kt`

#### Key Features Added:

1. **Re-enabled File Watching** (Line 40-73)
   - Previously disabled with early return
   - Now fully functional with proper lifecycle management
   - Uses dedicated `CoroutineScope` instead of `GlobalScope`

2. **Orphan File Detection** (Lines 399-424)
   - Runs every 5 seconds
   - Scans workspace for sidecar files without boxes
   - Triggers auto-box creation
   - Async operation in dedicated coroutine

3. **Metadata.edn Watching** (Lines 282-329)
   - Detects external changes to metadata.edn
   - 15-second grace period prevents false positives
   - Triggers reconciliation on change

4. **Auto-Box Creation** (Lines 426-491)
   - Parses filename: `<boxId>_<propertyName>_.<extension>`
   - Derives display name from filename
   - Calculates non-overlapping frame position
   - Loads file content into property
   - Persists to metadata.edn automatically

5. **Metadata Reconciliation** (Lines 331-397)
   - Loads metadata.edn and compares with current session
   - Adds boxes found in metadata but not in session
   - Marks boxes removed from metadata as tombstones
   - Preserves data integrity

6. **Deterministic Frame Layout** (Lines 493-541)
   - Grid layout: 350×250 boxes, 20px padding, 4 columns
   - Starts at (50, 50)
   - Checks for overlaps with existing frames
   - Fallback: hash-based deterministic offset (not random)

### Architecture

```
FileWatchManager
├── watchScope (CoroutineScope)
│   ├── watchJob (monitors filesystem)
│   └── orphanCheckJob (checks for orphans every 5s)
├── WatchService (Java NIO)
├── File Timestamps (grace period tracking)
└── Methods
    ├── start() / stop()
    ├── handleFileChange()
    ├── handleMetadataChange()
    ├── checkForOrphanedFiles()
    ├── autoCreateBoxForSidecarFile()
    ├── reconcileMetadata()
    └── calculateNewBoxFrame()
```

### File Patterns Supported

| Pattern | Extension | Purpose |
|---------|-----------|---------|
| `id_code_.kt` | `.kt` | Kotlin code |
| `id_fragment_.glsl` | `.glsl` | Fragment shader |
| `id_vertex_.glsl` | `.glsl` | Vertex shader |
| `id_compute_.glsl` | `.glsl` | Compute shader |
| `id_task_.glsl` | `.glsl` | Task shader |
| `id_mesh_.glsl` | `.glsl` | Mesh shader |
| `id_customization_.js` | `.js` | JavaScript customization |
| `id_remote_js_.js` | `.js` | Remote JavaScript |
| `id_metadata_.yaml` | `.yaml` | YAML metadata |

**Important**: Underscore before dot (`_.`) is mandatory!

### Flow Diagrams

#### Auto-Box Creation Flow

```
External Agent creates file: my-box_code_.kt
    ↓
FileWatchManager detects new file (within 5s)
    ↓
Parses filename → boxId: "my-box", property: "code"
    ↓
Box not found in session
    ↓
Auto-create box:
  - ID: "my-box"
  - Name: "My Box" (derived from filename)
  - Frame: Rect(50.0, 50.0, 350.0, 250.0) (calculated)
  - Property "code": file content
    ↓
Save to metadata.edn
    ↓
Sync to UI via WebSocket (needsResync)
    ↓
Agent sees box appear in Field's UI! ✅
```

#### File Edit Flow

```
External Agent edits: my-box_code_.kt
    ↓
FileWatchManager detects change (immediate)
    ↓
15-second grace check (skip if internal save)
    ↓
Box found in session
    ↓
Read new file content
    ↓
Update box.code property (doResync = true)
    ↓
Mark for persistence
    ↓
Sync to UI via WebSocket
    ↓
Field's UI updates! ✅
```

#### Metadata Reconciliation Flow

```
External Agent edits: metadata.edn
    ↓
FileWatchManager detects change
    ↓
15-second grace check
    ↓
Load metadata transfers
    ↓
Compare with current session:
  - New boxes in metadata → create them
  - Removed boxes → mark as tombstones
    ↓
Sync all changes to UI
    ↓
Boxes appear/disappear in Field! ✅
```

## Code Quality

### Addressed Code Review Issues:

1. ✅ **Coroutine Scope Management**
   - Replaced `GlobalScope` with dedicated `watchScope`
   - Added `SupervisorJob()` for proper cancellation
   - Scope cancelled in `stop()` method
   - No leaked coroutines

2. ✅ **Name Extraction Logic**
   - Added step-by-step comments
   - Improved readability with intermediate variables
   - Better handling of edge cases

3. ✅ **Deterministic Positioning**
   - Replaced `Math.random()` with box ID hash
   - Ensures unique positions for simultaneous creation
   - Predictable and reproducible behavior

### Error Handling:

- Try-catch blocks in all async operations
- Detailed logging for debugging
- Graceful degradation on errors
- No crashes, just warnings in logs

### Thread Safety:

- `ConcurrentHashMap` for shared state
- `ConcurrentSet` for resync tracking
- `inMain {}` for UI thread operations
- Proper async/await patterns

## Documentation

### 1. AGENT_GUIDE.md (400+ lines)

Comprehensive guide covering:
- Quick start instructions
- File structure and conventions
- How to create/edit/delete boxes
- Working with metadata.edn
- Frame positioning
- Best practices
- Debugging tips
- Complete examples

### 2. AGENT_QUICK_REF.md

Quick reference card with:
- TL;DR usage
- File type table
- Common patterns
- Troubleshooting

### 3. Demo Workspace

`metadata_store/agent_demo/` includes:
- Example workspace with 3 boxes
- README with hands-on tutorials
- Sample shader file
- Interactive learning environment

## Testing Strategy

### Manual Testing Steps:

1. **Test Auto-Creation**:
   ```bash
   cd workspace
   echo 'println("Hello!")' > test-box_code_.kt
   # Wait 5-10 seconds
   # Verify box appears in Field's UI
   ```

2. **Test Live Editing**:
   ```bash
   echo 'println("Updated!")' > test-box_code_.kt
   # Check that UI updates
   ```

3. **Test Multiple Files**:
   ```bash
   echo 'println("Code")' > multi_code_.kt
   echo 'void main() { gl_FragColor = vec4(1.0); }' > multi_fragment_.glsl
   # Both properties should be loaded into one box
   ```

4. **Test Metadata Reconciliation**:
   - Edit metadata.edn externally
   - Add a new box
   - Verify it appears in Field
   - Remove a box
   - Verify it's marked as removed

5. **Test Frame Layout**:
   - Create 10+ boxes
   - Verify they're laid out in grid
   - No overlapping frames

### Expected Behaviors:

- ✅ Orphan detection within 5-10 seconds
- ✅ File changes detected immediately
- ✅ Grace period prevents false positives
- ✅ Non-overlapping frame positions
- ✅ Sensible names derived from filenames
- ✅ Metadata auto-saved
- ✅ UI syncs via WebSocket

## Performance Considerations

- **Orphan Check**: Every 5 seconds (low overhead)
- **File Watch**: Native OS events (efficient)
- **Grace Period**: 15 seconds (prevents unnecessary reloads)
- **Grid Layout**: O(n) where n = box count
- **Hash-based Fallback**: O(1)

## Security Considerations

- File watching limited to workspace directory
- No arbitrary code execution (just data loading)
- Proper exception handling prevents exploits
- Validated file patterns (no directory traversal)

## Future Enhancements

Possible improvements:
1. Configurable grace period
2. Configurable grid layout parameters
3. Visual feedback in UI for auto-created boxes
4. Undo/redo for auto-creation
5. Batch operations for multiple files
6. Integration with external file pickers
7. Support for nested directories

## Impact

### For External Agents:

✅ **Zero Configuration**: Just create files, Field handles rest
✅ **Live Collaboration**: Work while Field is running
✅ **No Metadata Management**: Auto-managed
✅ **Error Tolerant**: Graceful handling
✅ **Multi-Agent Safe**: Grace period prevents conflicts

### For Users:

✅ **Faster Workflows**: Less manual metadata editing
✅ **Better AI Integration**: Agents can help effectively
✅ **Live Updates**: See changes immediately
✅ **Preserved Data**: Safe reconciliation
✅ **Flexible**: Manual metadata editing still possible

## Summary

This implementation successfully enables external AI agents to work with Field4 workspaces without manual metadata management. The self-healing system automatically detects, creates, and reconciles boxes from external file operations while Field is running, providing a seamless collaborative experience.

---

**Implementation Date**: 2026-02-08
**Files Changed**: 1 (FileWatchManager.kt)
**Lines Added**: ~350
**Documentation**: 3 files (AGENT_GUIDE.md, AGENT_QUICK_REF.md, demo workspace)
**Testing**: Manual testing required
**Security**: CodeQL passed
