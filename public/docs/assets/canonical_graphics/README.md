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

## How a Field workspace works on disk

- **`metadata.edn`** is the document: it lists the boxes and their properties. Field manages it for
  you — you rarely need to edit it by hand.
- **`<box>_<property>_.{kt,glsl,yaml,js,…}`** are *sidecar* files: the larger text properties of each
  box kept as editable files on disk. The extension follows the property: `code` is `.kt`,
  `metadata` is `.yaml`, `customization` is `.js`, `notes` is `.md`. A shader stage takes the
  extension of its own language: `.glsl` for `fragment`, `vertex`, `compute` and the ray-tracing
  stages, `.slang` for their Slang twins, `.metal` for `msl`, `.stacksl` for `stackSL`.
  `Box.ioSeparateProperties` in the Field source is the whole list.
- **Editing a sidecar is enough.** Field's file watcher picks a new or changed file up within a few
  seconds, creates the box if it did not exist, positions it, loads the property and saves to
  `metadata.edn`. The underscore before the dot (`_.`) is required.

```bash
# create a box by creating a file named <boxId>_<property>_.<ext>
echo 'println("hello from a new box")' > hello_code_.kt
```

Field's console logs every file operation (`info,file-watch: Auto-created box: …`), which is the
first place to look when something does not sync.

## The full reference

The manual file mechanics are in `dev_docs/FileWatchManager.md`. To *run* code, capture output and
errors, take screenshots, and build and save documents, use the agent automation path, documented in
`dev_docs/agents/` in the Field repository:

- `dev_docs/agents/README.md` — the map of the agent path
- `dev_docs/agents/OPERATION_TRANSCRIPT.md` — build / edit / launch / save documents
- `dev_docs/agents/FIELD_BACKDOOR.md` — run code, capture output, screenshots, video
