# Field4 Workspace — the `afield.four` graphics corpus

This particular workspace is not a blank one. It is a snapshot of `canonicalGraphics`: the
`afield.four` graphics curriculum, ~33 worked lessons that run against a live Vulkan renderer,
from a first triangle through cameras, compute shaders, texturing, glTF, render-to-texture,
ping-pong buffers, blur kernels, shader `#include`, printf and texture-probe debugging, a GPU
radix sort, Slang, and a diffusion-limited-aggregation system.

**If you are writing `afield.four` code, this is the reference corpus to read first.** Two
entry points:

- **[LESSONS.md](LESSONS.md)** — the index. Every lesson by name, the parent chain it needs in
  order to run, its Kotlin and shader files, and a reverse index from Field API symbol
  (`RenderToTexture`, `LoadedTexture`, `computeModuleHere`, `GpuRadixSort`, …) to the lessons
  that demonstrate it. Generated, so it cannot drift from the boxes.
- **[afield.four Graphics](../../afield_four_graphics.md)** — the prose walkthrough of the same
  lessons, with captured native-window images.

Every file here is named by box uid, which is why the index exists: `grep RenderToTexture` in
this directory alone lands you in shader source with no idea which lesson it belongs to.

These boxes are **read-only reference**. Field resolves a box's `Renderer` through graph
topology, so to run one, copy its Kotlin and shader properties into a box of your own beneath a
renderer parent — see the graphics topology rule in `dev_docs/agents/transcripts/README.md`.

The rest of this file is the generic workspace guide, which applies to any Field workspace.

## For AI Agents Working Here

Field4 has a **self-healing metadata system** that makes it easy for you to work on files in this workspace without manually managing metadata.

### Quick Start

**Create a new box:**
```diffstatic {data-lang=bash}
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
```diffstatic {data-lang=bash}
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

```diffstatic {data-lang=text}
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
```diffstatic {data-lang=bash}
echo 'void main() { gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0); }' > my-box_fragment_.glsl
```

**Add metadata/tags:**
```diffstatic {data-lang=bash}
cat > my-box_metadata_.yaml << 'EOF'
tags:
  - graphics
  - demo
description: My awesome box
EOF
```

**Create multiple related boxes:**
```diffstatic {data-lang=bash}
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
