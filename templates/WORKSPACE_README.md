# Field4 Workspace

Welcome to this Field4 workspace! This directory contains boxes (code units) that can be edited live while Field is running.

## For AI Agents Working Here

Field4 has a **self-healing metadata system** that makes it easy for you to work on files in this workspace without manually managing metadata.

### Quick Start

**Create a new box:**
```bash
# Just create a file with this pattern: <boxId>_<property>_.<extension>
echo 'println("Hello from AI!")' > my-box_code_.kt
```

Within 5-10 seconds, Field will:
- Detect your new file
- Auto-create a box named "My Box"
- Position it in the UI
- Load your code
- Save everything to metadata.edn

**Edit existing boxes:**
```bash
# Edit any file - changes sync immediately
echo 'println("Updated code!")' > my-box_code_.kt
```

### Supported File Types

| File Pattern | Purpose |
|--------------|---------|
| `id_code_.kt` | Kotlin code |
| `id_fragment_.glsl` | Fragment shader |
| `id_vertex_.glsl` | Vertex shader |
| `id_compute_.glsl` | Compute shader |
| `id_metadata_.yaml` | Box metadata/tags |
| `id_customization_.js` | JavaScript |

**Important:** The underscore before the dot (`_.`) is required!

### What You Don't Need to Do

❌ Don't manually edit `metadata.edn` (Field handles it)
❌ Don't worry about frame positions (auto-calculated)
❌ Don't manage box IDs (just use descriptive names)

### Workspace Structure

```
workspace/
├── README.md              ← You are here
├── metadata.edn           ← Auto-managed box definitions
├── boxid_code_.kt         ← Kotlin code files
├── boxid_fragment_.glsl   ← Shader files
└── boxid_metadata_.yaml   ← Metadata files
```

### Best Practices

**Good box IDs:**
- `particle-system`, `shader-utils`, `physics-engine`
- Use lowercase with hyphens
- Descriptive and meaningful

**Avoid:**
- Spaces in IDs (use hyphens instead)
- Generic names like `box1`, `test`, `temp`
- Special characters except `-` and `_`

### Common Tasks

**Add a shader to a box:**
```bash
echo 'void main() { gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0); }' > my-box_fragment_.glsl
```

**Add metadata/tags:**
```bash
cat > my-box_metadata_.yaml << 'EOF'
tags:
  - graphics
  - demo
description: My awesome box
EOF
```

**Create multiple related boxes:**
```bash
for i in {1..3}; do
  echo "println(\"Box $i\")" > demo-$i_code_.kt
done
```

### Troubleshooting

**Box not appearing?**
- Check filename format: `boxid_property_.extension`
- Verify extension is supported (see table above)
- Wait 5-10 seconds for detection
- Check Field's console for errors

**Changes not syncing?**
- Ensure Field is running
- Check file completed writing
- Look for errors in Field's console
- Verify the 15-second grace period hasn't triggered

### Full Documentation

The files above are the manual, on-disk way to edit a workspace. To *drive* Field
programmatically — run code, capture output/errors, take screenshots, build and
save documents, or read and rewrite saved macros — use the agent automation path:
- **`dev_docs/agents/README.md`** - the map of the agent path (in the Field repo)
- **`dev_docs/agents/FIELD_BACKDOOR.md`** - the low-level run/capture/screenshot harness
- **`dev_docs/agents/OPERATION_TRANSCRIPT.md`** - the canonical build/edit/launch/save automation format
- **Field4 Console** - check logs for real-time feedback

### Need Help?

- Check Field's console output for detailed logs
- Look for messages like: `info,file-watch: Auto-created box...`
- See `dev_docs/agents/` in the Field repo for the full automation reference
- Check the `FileWatchManager.kt` source code for file-sync implementation details

---

**Auto-Management Features:**
- ✅ Orphan file detection (every 5 seconds)
- ✅ Auto-box creation with smart naming
- ✅ Non-overlapping frame positioning
- ✅ Live file synchronization
- ✅ Metadata reconciliation
- ✅ 15-second grace period for safety

Happy coding! 🚀
