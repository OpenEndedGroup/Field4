# Field4 Self-Healing Metadata System - Complete Architecture

This document provides a comprehensive overview of the complete self-healing metadata system for Field4, including both the auto-box creation and automatic guide deployment features.

## System Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    Field4 Workspace Ecosystem                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌─────────────────────────┐    ┌──────────────────────────┐   │
│  │   AI Agent/External     │    │    Field4 Application    │   │
│  │        Editor           │◄──►│   (FileWatchManager)     │   │
│  └───────────┬─────────────┘    └───────────┬──────────────┘   │
│              │                               │                   │
│              │ Creates/Edits Files           │ Monitors Changes  │
│              ↓                               ↓                   │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              Workspace Directory                         │   │
│  │  ┌────────────────────────────────────────────────────┐ │   │
│  │  │  Documentation (Auto-Deployed)                     │ │   │
│  │  │  • README.md (human orientation)                   │ │   │
│  │  │  • AGENTS.md (agent onboarding)                     │ │   │
│  │  └────────────────────────────────────────────────────┘ │   │
│  │  ┌────────────────────────────────────────────────────┐ │   │
│  │  │  Metadata (Auto-Managed)                          │ │   │
│  │  │  • metadata.edn (box definitions)                  │ │   │
│  │  └────────────────────────────────────────────────────┘ │   │
│  │  ┌────────────────────────────────────────────────────┐ │   │
│  │  │  Sidecar Files (Agent-Created)                    │ │   │
│  │  │  • boxid_code_.kt                                  │ │   │
│  │  │  • boxid_fragment_.glsl                            │ │   │
│  │  │  • boxid_metadata_.yaml                            │ │   │
│  │  └────────────────────────────────────────────────────┘ │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

## Component Interaction Flow

### 1. Workspace Initialization Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                   WORKSPACE CREATION                             │
└─────────────────────────────────────────────────────────────────┘

User/System Request
      ↓
┌─────────────────────────┐
│ SavingContext.save()    │
│ ensureDirectory(path)   │
└──────────┬──────────────┘
           ↓
┌─────────────────────────┐
│ Create directory        │
│ File(fn).mkdirs()       │
└──────────┬──────────────┘
           ↓
┌─────────────────────────────────────────┐
│ ensureAgentGuides(path)                 │
│ ┌─────────────────────────────────────┐ │
│ │ 1. Find repository root             │ │
│ │ 2. Check source guides exist        │ │
│ │ 3. Copy if destination doesn't exist│ │
│ │ 4. Log operations                   │ │
│ └─────────────────────────────────────┘ │
└──────────┬──────────────────────────────┘
           ↓
┌─────────────────────────┐
│ Workspace Ready         │
│ • Directory exists      │
│ • Guides copied         │
│ • Ready for boxes       │
└─────────────────────────┘
```

### 2. File Watching and Auto-Box Creation Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                  FILE WATCH & AUTO-BOX CREATION                  │
└─────────────────────────────────────────────────────────────────┘

Agent Creates File
 (my-box_code_.kt)
      ↓
┌─────────────────────────┐
│ FileWatchManager        │
│ Detects new file        │
│ (within 5 seconds)      │
└──────────┬──────────────┘
           ↓
┌─────────────────────────┐
│ Parse filename          │
│ → boxId: "my-box"       │
│ → property: "code"      │
│ → extension: ".kt"      │
└──────────┬──────────────┘
           ↓
┌─────────────────────────┐
│ Box exists?             │
└──────┬──────────────────┘
       │ No
       ↓
┌─────────────────────────────────────────┐
│ Auto-Create Box                         │
│ ┌─────────────────────────────────────┐ │
│ │ 1. Create box(boxId)                │ │
│ │ 2. Derive name from filename        │ │
│ │ 3. Calculate frame position         │ │
│ │ 4. Load file content                │ │
│ │ 5. Save to metadata.edn             │ │
│ │ 6. Sync to UI via WebSocket         │ │
│ └─────────────────────────────────────┘ │
└──────────┬──────────────────────────────┘
           ↓
┌─────────────────────────┐
│ Box Ready in UI         │
│ • Visible in browser    │
│ • Code loaded           │
│ • Positioned correctly  │
└─────────────────────────┘
```

### 3. File Edit Synchronization Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                    FILE EDIT SYNCHRONIZATION                     │
└─────────────────────────────────────────────────────────────────┘

Agent Edits File
 (my-box_code_.kt)
      ↓
┌─────────────────────────┐
│ FileWatchManager        │
│ Detects change          │
│ (immediate)             │
└──────────┬──────────────┘
           ↓
┌─────────────────────────┐
│ Grace Period Check      │
│ (15-second window)      │
│ Skip if internal save   │
└──────────┬──────────────┘
           │ External change
           ↓
┌─────────────────────────┐
│ Parse filename          │
│ Find existing box       │
└──────────┬──────────────┘
           ↓
┌─────────────────────────────────────────┐
│ Update Box                              │
│ ┌─────────────────────────────────────┐ │
│ │ 1. Read new file content            │ │
│ │ 2. Update box property              │ │
│ │ 3. Mark for persistence             │ │
│ │ 4. Sync to UI via needsResync       │ │
│ └─────────────────────────────────────┘ │
└──────────┬──────────────────────────────┘
           ↓
┌─────────────────────────┐
│ UI Updates              │
│ • Content refreshed     │
│ • Editor shows changes  │
└─────────────────────────┘
```

### 4. Metadata Reconciliation Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                   METADATA RECONCILIATION                        │
└─────────────────────────────────────────────────────────────────┘

Agent Edits metadata.edn
      ↓
┌─────────────────────────┐
│ FileWatchManager        │
│ Detects metadata change │
└──────────┬──────────────┘
           ↓
┌─────────────────────────┐
│ Grace Period Check      │
│ Skip if internal save   │
└──────────┬──────────────┘
           │ External change
           ↓
┌─────────────────────────────────────────┐
│ Load & Compare                          │
│ ┌─────────────────────────────────────┐ │
│ │ 1. Read metadata.edn                │ │
│ │ 2. Parse box transfers              │ │
│ │ 3. Compare with current session     │ │
│ └─────────────────────────────────────┘ │
└──────────┬──────────────────────────────┘
           ↓
┌─────────────────────────────────────────┐
│ Reconcile Differences                   │
│ ┌─────────────────────────────────────┐ │
│ │ New boxes in metadata:              │ │
│ │ → Create boxes from transfers       │ │
│ │ → Apply properties                  │ │
│ │ → Sync to UI                        │ │
│ │                                     │ │
│ │ Boxes removed from metadata:        │ │
│ │ → Mark as tombstones                │ │
│ │ → Sync to UI                        │ │
│ └─────────────────────────────────────┘ │
└──────────┬──────────────────────────────┘
           ↓
┌─────────────────────────┐
│ Session Synchronized    │
│ • New boxes appear      │
│ • Removed boxes hidden  │
└─────────────────────────┘
```

## Key Components

### 1. FileWatchManager (FileWatchManager.kt)

**Responsibilities:**
- Monitor workspace directory for file changes
- Detect orphaned sidecar files (every 5 seconds)
- Auto-create boxes for orphaned files
- Watch metadata.edn for external changes
- Reconcile metadata with current session
- Maintain 15-second grace period

**Key Methods:**
- `start()` - Initialize watching with proper coroutine scope
- `handleFileChange()` - Process sidecar file changes
- `handleMetadataChange()` - Process metadata.edn changes
- `checkForOrphanedFiles()` - Periodic orphan detection
- `autoCreateBoxForSidecarFile()` - Create box from orphaned file
- `reconcileMetadata()` - Sync metadata with session
- `calculateNewBoxFrame()` - Deterministic frame positioning

### 2. SavingContext (saving.kt)

**Responsibilities:**
- Save workspace to disk
- Ensure workspace directory exists
- Copy agent guides to workspace
- Manage metadata.edn file
- Handle sidecar file creation

**Key Methods:**
- `save()` - Two-phase save (snapshot + background I/O)
- `ensureDirectory()` - Create directory + copy guides
- `ensureAgentGuides()` - Copy README.md + AGENTS.md into new workspaces
- `findRepositoryRoot()` - Locate source files
- `exportEdnPackage()` - Generate metadata.edn content
- `load()` - Load workspace from disk

### 3. Box (Boxes.kt)

**Responsibilities:**
- Represent a code unit
- Store properties (code, frame, name, etc.)
- Manage hierarchy (children, parents)
- Track synchronization needs

**Key Properties:**
- `id` - Unique identifier
- `name` - Display name
- `frame` - Position and size
- `code` - Kotlin code
- `fragment`, `vertex`, `compute` - Shader code
- `metadata` - YAML metadata
- `properties` - Dynamic property map

## Data Flow

### Agent → Field4

```
Agent Action                    Field4 Response
────────────────────────────────────────────────────────────
Create file                  → Detect (5s)
  my-box_code_.kt            → Parse filename
                             → Auto-create box
                             → Calculate frame
                             → Load content
                             → Save to metadata.edn
                             → Sync to UI
                             ← Box appears in browser

Edit file                    → Detect (immediate)
  my-box_code_.kt            → Check grace period
                             → Read new content
                             → Update box.code
                             → Sync to UI
                             ← Editor updates

Edit metadata.edn            → Detect change
                             → Load transfers
                             → Compare with session
                             → Create/remove boxes
                             → Sync to UI
                             ← Boxes added/removed
```

### Field4 → Workspace

```
Field4 Action                   Workspace Result
────────────────────────────────────────────────────────────
Create workspace             → Directory created
                             → Guides copied
                             ← README.md
                             ← AGENTS.md

Save session                 → Snapshot properties
                             → Write sidecar files
                             → Update metadata.edn
                             → Mark internal saves
                             ← Files on disk

Load session                 → Read metadata.edn
                             → Create boxes
                             → Load sidecar files
                             ← Session restored
```

## File Naming Conventions

### Sidecar Files

Pattern: `<boxId>_<property>_.<extension>`

Examples:
- `particle-system_code_.kt` - Kotlin code for "particle-system" box
- `shader_fragment_.glsl` - Fragment shader for "shader" box
- `ui-controller_metadata_.yaml` - Metadata for "ui-controller" box

### Supported Properties

The authoritative set of sidecar-backed properties and their extensions is
`Box.ioSeparateProperties` in `Boxes.kt` — consult it rather than hardcoding a
subset. A representative sample:

| Property | Extension | Description |
|----------|-----------|-------------|
| `code` | `.kt` | Kotlin code |
| `fragment` | `.glsl` | Fragment shader |
| `vertex` | `.glsl` | Vertex shader |
| `compute` | `.glsl` | Compute shader |
| `task` / `mesh` | `.glsl` | Task / mesh shaders |
| `raygen` / `miss` / `closesthit` / `anyhit` / `intersection` / `callable` | `.glsl` | Ray-tracing shader stages |
| `vertex_fragment` and the `*_s` variants | `.slang` | Slang shaders |
| `stackSL` | `.stacksl` | StackSL |
| `customization` / `remote_js` | `.js` | JavaScript |
| `notes` | `.md` | Notes |
| `metadata` | `.yaml` | YAML metadata |

## Frame Positioning Algorithm

### Grid Layout

```
┌─────────────────────────────────────────────────────────────┐
│  (50, 50)                                                    │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐       │
│  │  Box 1  │  │  Box 2  │  │  Box 3  │  │  Box 4  │       │
│  │ 350×250 │  │ 350×250 │  │ 350×250 │  │ 350×250 │       │
│  └─────────┘  └─────────┘  └─────────┘  └─────────┘       │
│    20px gap    20px gap     20px gap                        │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐       │
│  │  Box 5  │  │  Box 6  │  │  Box 7  │  │  Box 8  │       │
│  │ 350×250 │  │ 350×250 │  │ 350×250 │  │ 350×250 │       │
│  └─────────┘  └─────────┘  └─────────┘  └─────────┘       │
│  (continues...)                                             │
└─────────────────────────────────────────────────────────────┘

Parameters:
- Start position: (50, 50)
- Box size: 350×250 pixels
- Padding: 20 pixels
- Columns: 4
- Overflow: Hash-based deterministic offset
```

### Overlap Detection

`calculateNewBoxFrame(boxId)` walks the grid (row/col advancing, up to 100
attempts) and returns the first cell that does not intersect any existing,
non-tombstoned box frame. If no free cell is found it falls back to a
deterministic hash-based offset — `(boxId.hashCode().toLong() and 0x7FFFFFFF) %
200`. The `0x7FFFFFFF` mask is important: it keeps the offset non-negative so
boxes never land at negative pixel coordinates, and it makes simultaneously
created boxes land at distinct positions. See `calculateNewBoxFrame` in
`FileWatchManager.kt` for the exact loop.

## Safety Mechanisms

### 1. Grace Period (15 seconds)
- Prevents reloading files Field just saved
- Tracks internal save timestamps
- Only reloads if modification is after grace period

### 2. File Existence Checks
- Verifies file exists before reading
- Handles deleted files gracefully
- Avoids race conditions

### 3. Error Handling
- Try-catch around all file operations
- Logs errors but doesn't crash
- Continues processing other files

### 4. Idempotent Operations
- Safe to call multiple times
- Checks before creating/copying
- Preserves existing data

### 5. Non-Blocking I/O
- Background coroutines for file operations
- Main thread for UI updates
- Proper scope management

## Performance Characteristics

### Time Complexities

| Operation | Complexity | Notes |
|-----------|------------|-------|
| Orphan check | O(n) | n = files in workspace |
| Frame calculation | O(m) | m = existing boxes |
| File copy | O(1) | Fixed number of guides |
| Metadata parse | O(b) | b = boxes in metadata |
| Box creation | O(1) | Single box operation |

### Resource Usage

- **Memory**: Minimal (stores timestamps, not file contents)
- **CPU**: Low (event-driven, not polling)
- **Disk I/O**: Efficient (only changed files)
- **Network**: None (local operations only)

### Timing

- **Orphan detection**: Every 5 seconds
- **File change detection**: < 1 second (OS events)
- **Grace period**: 15 seconds (hardcoded `internalSaveGraceMs`)
- **UI sync**: Immediate (via WebSocket)

## Tuning Constants

These are hardcoded in the source (no runtime/config surface):

```kotlin
// FileWatchManager.kt
private val internalSaveGraceMs = 15000L   // internal-save grace window
private val internalSaveWindowMs = 120000L // how long a save signature is honored
// orphan check cadence is a literal delay(5000.milliseconds) in start()

// calculateNewBoxFrame() (FileWatchManager.kt)
val startX = 50.0
val startY = 50.0
val boxWidth = 350.0
val boxHeight = 250.0
val padding = 20.0
val columns = 4
```

## Summary

This architecture provides:

✅ **Automatic Workspace Setup** - Guides copied on creation
✅ **Self-Healing Metadata** - Orphaned files auto-processed
✅ **Live Synchronization** - Changes appear immediately
✅ **Safe Concurrent Access** - Grace periods prevent conflicts
✅ **Deterministic Behavior** - Hash-based positioning
✅ **Comprehensive Documentation** - Guides in every workspace
✅ **Error Resilience** - Graceful degradation
✅ **Performance** - Low overhead, event-driven

The system enables seamless collaboration between AI agents and Field4, with automatic documentation deployment and intelligent file management.

Component-level detail for the file watcher lives in `dev_docs/FileWatchManager.md`;
guide-deployment mechanics are colocated as KDoc on `ensureAgentGuides` /
`findRepositoryRoot` in `saving.kt`, with the automation canon in `dev_docs/agents/`.
