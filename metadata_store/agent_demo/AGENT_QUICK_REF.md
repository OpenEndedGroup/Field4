# Field4 Quick Reference for AI Agents

## TL;DR: How to Work with Field4

### Create a New Box
```bash
echo 'println("Hello!")' > my-box_code_.kt
```
✅ Field auto-creates box, assigns position, saves to metadata

### Edit Existing Box
```bash
echo 'println("Updated!")' > existing-box_code_.kt
```
✅ Field reloads, updates UI, persists changes

### Supported File Types

| File Pattern | Purpose |
|--------------|---------|
| `id_code_.kt` | Kotlin code |
| `id_fragment_.glsl` | Fragment shader |
| `id_vertex_.glsl` | Vertex shader |
| `id_metadata_.yaml` | Box metadata/tags |
| `id_customization_.js` | JavaScript |

### File Naming Rules

- Format: `<boxId>_<property>_.<ext>`
- Box ID: lowercase with hyphens (`my-box`, `shader-1`)
- Property: one of the types above
- **Important**: Underscore before dot (`_.`) is required!

### What Happens Automatically

1. **Orphan Detection** (every 5 seconds)
   - Finds files without boxes
   - Creates box with sensible name
   - Assigns non-overlapping position
   - Saves to metadata.edn

2. **Change Detection** (immediate)
   - Detects file modifications
   - Reloads content
   - Syncs to UI
   - Persists changes

3. **Metadata Reconciliation** (on edit)
   - Detects metadata.edn changes
   - Adds new boxes from metadata
   - Removes deleted boxes

### Working with metadata.edn

**Auto-managed** - you usually don't need to touch it!

But if you must:
```clojure
{:version 2
 :boxes [{:id "box-id"
          :properties {:name "Display Name"
                       :frame [x y width height]}}]}
```

### Frame Positioning

Auto-generated frames use grid layout:
- Start: (50, 50)
- Size: 350×250
- Padding: 20px
- Columns: 4

To customize, edit `metadata.edn`:
```clojure
:frame [100.0 100.0 400.0 300.0]  ; [x y width height]
```

### Best Practices

✅ **DO:**
- Use descriptive box IDs: `particle-system`, `physics`
- Create sidecar files, let Field manage metadata
- Wait 5-10 seconds after creating files
- Check Field's logs for confirmation

❌ **DON'T:**
- Manually edit metadata.edn unless necessary
- Use spaces or special chars in box IDs
- Create temp/test files (they become boxes!)
- Expect instant updates (5-15 sec latency)

### Workflow Example

```bash
# 1. Create a shader system
echo 'println("Setting up shader")' > shader-system_code_.kt
echo 'void main() { gl_FragColor = vec4(1.0); }' > shader-system_fragment_.glsl

# 2. Add metadata
cat > shader-system_metadata_.yaml << 'EOF'
tags: [graphics, shader, demo]
description: Basic shader system
EOF

# 3. Wait for Field to detect (5-10 seconds)
# 4. Check Field's UI - boxes appear automatically!
# 5. Edit and iterate as needed
```

### Debugging

**Check logs for:**
```
info,file-watch: Auto-created box: Shader System (shader-system)
info,file-watch: Reloaded external change: code in Shader System
```

**Common issues:**
- Wrong filename format → Won't be detected
- Missing underscore-dot (`_.`) → Won't be parsed correctly
- Unsupported extension → Ignored
- Permission errors → Check file permissions

### Multi-Agent Coordination

- Use **unique box IDs** to avoid conflicts
- **Wait 10 seconds** between bulk operations
- Field has **15-second grace period** for saves
- **Logs show** all file operations

---

## Full Documentation

See [AGENT_GUIDE.md](./AGENT_GUIDE.md) for comprehensive documentation.

## Questions?

Check Field's console output or review `src/jvmMain/kotlin/afield/FileWatchManager.kt`
