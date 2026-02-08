# Field4 Agent Guide

## Overview

This guide helps external AI agents (like Claude, Codex, Cursor, etc.) work effectively with Field4 workspaces while Field is running. Field4 now has a **self-healing metadata system** that makes it easy to add, edit, and delete files without manual metadata management.

## Quick Start for Agents

### What You Can Do

As an external agent, you can now:

1. **Create new code files** - Field will automatically create boxes for them
2. **Edit existing files** - Changes sync immediately to Field's UI
3. **Delete files** - Field will mark corresponding boxes as removed
4. **Work in parallel** - Multiple agents can work simultaneously

### What You DON'T Need to Do

- ❌ Don't manually edit `metadata.edn` (Field handles this)
- ❌ Don't worry about box IDs or frame positions (auto-generated)
- ❌ Don't manage box hierarchy (handled automatically)

## File Structure

### Workspace Layout

A typical Field4 workspace looks like this:

```
my-workspace/
├── metadata.edn              # Auto-managed box definitions
├── boxid_code_.kt            # Kotlin code for a box
├── boxid_fragment_.glsl      # Fragment shader for a box
├── boxid_vertex_.glsl        # Vertex shader for a box
├── boxid_metadata_.yaml      # Metadata/tags for a box
├── other-box_code_.kt        # Another box's code
└── ...
```

### Sidecar File Naming Convention

Files follow this pattern: `<boxId>_<propertyName>_.<extension>`

**Supported Properties & Extensions:**

| Property | Extension | Purpose |
|----------|-----------|---------|
| `code` | `.kt` | Kotlin code |
| `fragment` | `.glsl` | Fragment shader |
| `vertex` | `.glsl` | Vertex shader |
| `compute` | `.glsl` | Compute shader |
| `task` | `.glsl` | Task shader |
| `mesh` | `.glsl` | Mesh shader |
| `customization` | `.js` | JavaScript customization |
| `remote_js` | `.js` | Remote JavaScript execution |
| `metadata` | `.yaml` | YAML metadata (tags, description) |

### Box IDs

Box IDs can be:
- Simple identifiers: `hello`, `particle-system`, `shader1`
- UUIDs: `550e8400-e29b-41d4-a716-446655440000`
- Any unique string (no spaces, use hyphens or underscores)

**Important:** The underscore before the dot (`_.`) is mandatory!

## How to Create a New Box

### Option 1: Let Field Auto-Create (Recommended)

Simply create a sidecar file with a new box ID:

```bash
# Create a new Kotlin code file
echo 'println("Hello from my new box!")' > my-new-box_code_.kt
```

**What happens:**
1. Field detects the orphaned file within 5 seconds
2. Auto-creates a box with ID `my-new-box`
3. Assigns a sensible name: "My New Box"
4. Places it at a non-overlapping position
5. Saves to `metadata.edn` automatically
6. Syncs to the UI immediately

### Option 2: Create Multiple Files for One Box

```bash
# Create code and shader for the same box
echo 'println("Drawing...")' > graphics-box_code_.kt
echo 'void main() { gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0); }' > graphics-box_fragment_.glsl
```

Both files will be associated with the `graphics-box` box.

### Option 3: Add Metadata

```yaml
# particle-system_metadata_.yaml
tags:
  - particles
  - graphics
  - demo
description: A beautiful particle system with physics simulation
author: AI Agent
version: 1.0
```

## How to Edit Existing Boxes

### Find Existing Files

```bash
# List all code files
ls *_code_.kt

# List all shader files
ls *_fragment_.glsl *_vertex_.glsl

# View metadata to understand box structure
cat metadata.edn
```

### Edit a File

```bash
# Edit existing code
echo 'println("Updated code!")' > existing-box_code_.kt
```

**What happens:**
1. Field detects the change within seconds
2. Reloads the file content
3. Updates the box property
4. Syncs to the UI
5. Marks for persistence

### Safety Features

- **Grace Period**: 15-second grace period prevents Field's own saves from triggering reloads
- **Change Detection**: Only reloads if content actually changed
- **Error Recovery**: Parse errors are logged but don't break the system

## Working with metadata.edn

### Understanding metadata.edn

```clojure
{:version 2
 :boxes [{:id "my-box"
          :properties {:name "My Box"
                       :frame [50.0 50.0 350.0 250.0]
                       :code "(println \"Hello!\")"}}
         {:id "another-box"
          :properties {:name "Another Box"
                       :frame [420.0 50.0 350.0 250.0]}}]
 :persistentProperties ["frame" "name" "code" ...]}
```

### When to Edit metadata.edn

**Only edit if you need to:**
- Reposition boxes (change `frame` coordinates)
- Rename boxes (change `name` property)
- Adjust box hierarchy (change `parents`/`children`)
- Set special properties (like `autorun`, `layers`)

**Format:**
- EDN (Extensible Data Notation) - similar to JSON but Clojure-based
- Frame format: `[x y width height]` (all doubles)
- IDs must match sidecar file prefixes

### External Edits to metadata.edn

If you edit `metadata.edn`:

1. Field detects the change
2. Reconciles with current session:
   - Adds new boxes found in metadata
   - Marks removed boxes as tombstones
3. Resyncs all affected boxes to UI

## Frame Positioning

### Auto-Generated Frames

When Field auto-creates boxes, it uses a grid layout:

- **Start position**: (50, 50)
- **Box size**: 350×250 pixels
- **Padding**: 20 pixels
- **Columns**: 4 boxes per row
- **Overflow**: Random offset if grid is full

### Manual Frame Positioning

To position a box at specific coordinates, edit `metadata.edn`:

```clojure
{:id "centered-box"
 :properties {:frame [500.0 300.0 400.0 300.0]
              :name "Centered Box"}}
```

Frame values: `[x y width height]`

### Common Layouts

```clojure
; Top-left corner
:frame [50.0 50.0 350.0 250.0]

; Centered (assuming 1920×1080 screen)
:frame [785.0 415.0 350.0 250.0]

; Large box
:frame [50.0 50.0 600.0 400.0]

; Narrow vertical box
:frame [50.0 50.0 250.0 400.0]
```

## Best Practices

### Naming Conventions

**Box IDs:**
- Use lowercase with hyphens: `particle-system`, `shader-utils`, `main-scene`
- Or use descriptive names: `physics`, `renderer`, `ui-controller`
- Avoid: spaces, special characters (except `-_`)

**Box Names (display):**
- Use proper capitalization: "Particle System", "Shader Utils"
- Can include spaces and special characters
- Field derives these from filenames automatically

### File Organization

**Good:**
```
workspace/
├── scene_code_.kt              # Main scene
├── scene_fragment_.glsl        # Scene shader
├── particles_code_.kt          # Particle system
├── physics_code_.kt            # Physics engine
└── metadata.edn
```

**Avoid:**
```
workspace/
├── box1_code_.kt              # Non-descriptive ID
├── box2_code_.kt
├── temp_code_.kt              # Temporary/test files
└── test123_code_.kt
```

### Workflow for Complex Changes

1. **Plan**: List files to create/modify
2. **Create files**: Start with sidecar files
3. **Wait**: Give Field 5-10 seconds to detect
4. **Verify**: Check Field's UI or logs
5. **Refine**: Edit files as needed
6. **Finalize**: Field auto-saves changes

### Working with Multiple Agents

If multiple agents work simultaneously:

- **Use unique box IDs**: Coordinate to avoid conflicts
- **Wait for sync**: Allow 5-10 seconds between operations
- **Check logs**: Field logs all file operations
- **Graceful handling**: Field's grace period prevents conflicts

## Debugging

### Check Field's Logs

Field logs all file watching events:

```
info,file-watch: FileWatchManager started with self-healing enabled
info,file-watch: Found orphaned sidecar file: new-box_code_.kt
info,file-watch: Auto-created box: New Box (new-box) with frame Rect(50.0, 50.0, 350.0, 250.0)
info,file-watch: Reloaded external change: code in My Box (my-box)
```

### Common Issues

**Box not appearing:**
- Check filename format: `boxid_property_.extension`
- Verify extension is supported (see table above)
- Wait 5-10 seconds for detection
- Check Field logs for errors

**Changes not syncing:**
- Ensure file has correct box ID
- Check if Field is running
- Look for permission errors in logs
- Verify file write completed fully

**Frame position issues:**
- Edit `metadata.edn` to adjust coordinates
- Use layout helper to calculate positions
- Avoid overlapping frames (Field detects but doesn't prevent)

## Advanced: Direct metadata.edn Management

If you need fine control, you can manually create entries in `metadata.edn`:

```clojure
{:version 2
 :boxes [{:id "my-custom-box"
          :properties {:name "My Custom Box"
                       :frame [100.0 100.0 400.0 300.0]
                       :autorun true
                       :layers "graphics"
                       :metadata "tags:\n  - custom\n  - advanced"}}]}
```

**Available properties:**
- `frame`: `[x y width height]`
- `name`: Display name (string)
- `code`: Inline Kotlin code (or use sidecar)
- `autorun`: Auto-execute on load (boolean)
- `layers`: Layer assignment (string)
- `children`: List of child box IDs
- `parents`: List of parent box IDs
- `templateId`: Template this was created from
- `metadata`: YAML metadata (or use sidecar)
- `customization`: JavaScript (or use sidecar)
- Many more - see `Box.ioPersistentProperties` in code

## Examples

### Example 1: Create a Simple Box

```bash
# Create a hello world box
cat > hello-world_code_.kt << 'EOF'
println("Hello from AI agent!")
println("Current time: ${System.currentTimeMillis()}")
EOF
```

### Example 2: Create a Shader Box

```bash
# Create shader box with both vertex and fragment
cat > rainbow-shader_vertex_.glsl << 'EOF'
#version 330
in vec3 position;
out vec3 vColor;
void main() {
    gl_Position = vec4(position, 1.0);
    vColor = position * 0.5 + 0.5;
}
EOF

cat > rainbow-shader_fragment_.glsl << 'EOF'
#version 330
in vec3 vColor;
out vec4 fragColor;
void main() {
    fragColor = vec4(vColor, 1.0);
}
EOF
```

### Example 3: Create a Tagged Box

```bash
# Create code
echo 'println("Physics simulation")' > physics-engine_code_.kt

# Create metadata
cat > physics-engine_metadata_.yaml << 'EOF'
tags:
  - physics
  - simulation
  - performance-critical
description: High-performance physics engine with collision detection
notes: |
  Uses Verlet integration for stability
  Optimized spatial hashing for broad phase
EOF
```

### Example 4: Bulk Create Boxes

```bash
# Create multiple related boxes
for i in {1..5}; do
  echo "println(\"Particle $i\")" > particle-$i_code_.kt
done

# Field will auto-create 5 boxes in a grid layout
```

## Summary

The self-healing metadata system makes Field4 **agent-friendly**:

✅ **Just create files** - Field handles the rest
✅ **Edit freely** - Changes sync automatically  
✅ **No manual metadata** - Generated and maintained automatically
✅ **Live collaboration** - Work while Field is running
✅ **Error tolerant** - Graceful handling of issues

For questions or issues, check Field's console logs or the `FileWatchManager.kt` source code.

---

**Version**: Field4 with Self-Healing Metadata (2024)
**Last Updated**: 2026-02-08
