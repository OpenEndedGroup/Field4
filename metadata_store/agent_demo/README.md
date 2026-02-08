# Agent Demo Workspace

This is a demonstration workspace for Field4's self-healing metadata system. It's designed to help AI agents learn how to work with Field4.

## About This Demo

This workspace showcases:
- ✅ Auto-box creation from external files
- ✅ Live file synchronization
- ✅ Metadata reconciliation
- ✅ Grid-based frame positioning
- ✅ Smart naming from filenames

### Included Boxes

This workspace comes with 3 example boxes:
1. **welcome** - Introduction to the agent demo
2. **instructions** - Step-by-step usage guide
3. **example-shader** - Sample shader demonstrating graphics

## For AI Agents: Quick Start

Field4 has a **self-healing metadata system** that makes it easy for you to work without manually managing metadata.

### Create a New Box

```bash
# Just create a file with this pattern: <boxId>_<property>_.<extension>
echo 'println("Hello from AI!")' > my-test_code_.kt
```

Within 5-10 seconds, Field will:
- Detect your new file
- Auto-create a box named "My Test"
- Position it in the UI
- Load your code
- Save to metadata.edn

### Try These Examples

**1. Add Kotlin Code:**
```bash
echo 'println("Demo box created by agent")
println("Time: ${System.currentTimeMillis()}")' > agent-test_code_.kt
```

**2. Add a Shader:**
```bash
cat > rainbow-shader_fragment_.glsl << 'GLSL'
void main() {
    vec2 uv = gl_FragCoord.xy / vec2(1920.0, 1080.0);
    gl_FragColor = vec4(uv.x, uv.y, 0.5, 1.0);
}
GLSL
```

**3. Add Metadata:**
```bash
cat > my-test_metadata_.yaml << 'YAML'
tags:
  - demo
  - agent-created
description: Created by an AI agent
YAML
```

**4. Bulk Create:**
```bash
for i in {1..3}; do
  echo "println(\"Particle $i\")" > particle-$i_code_.kt
done
# Wait 10 seconds - 3 boxes appear in grid!
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

### Expected Logs

Check Field's console for:
```
info,file-watch: FileWatchManager started with self-healing enabled
info,file-watch: Found orphaned sidecar file: agent-test_code_.kt
info,file-watch: Auto-created box: Agent Test (agent-test)
```

### Troubleshooting

**Box not appearing?**
- Check filename format: `boxid_property_.extension`
- Verify extension is supported
- Wait 5-10 seconds for detection

**Changes not syncing?**
- Ensure Field is running
- Check file write completed
- Look for errors in Field's console

### More Information

- **AGENT_QUICK_REF.md** - Quick reference (in this directory)
- **Full documentation** - See repository root

## Cleanup

To remove test files:
```bash
rm agent-test_code_.kt rainbow-shader_fragment_.glsl my-test_* particle-*
```

---

**Happy Experimenting!** 🚀
