# A Field workspace — the `agent_demo` example

This folder is one of the example documents under `metadata_store/`, and the one that shows the
sidecar mechanism rather than describing it. Three boxes: `welcome` and `instructions`, which
print what to try, and `example-shader`, whose `fragment` property lives on disk as
`example-shader_fragment_.glsl`. Editing that file is editing the box.

## How a Field workspace works on disk

- **`metadata.edn`** is the document: it lists the boxes and their properties. Field manages it
  for you — you rarely need to edit it by hand.
- **`<box>_<property>_.{kt,glsl,yaml,js,…}`** are *sidecar* files: the larger text properties of
  each box kept as editable files on disk. The extension follows the property: `code` is `.kt`,
  `metadata` is `.yaml`, `customization` is `.js`, `notes` is `.md`. A shader stage takes the
  extension of its own language: `.glsl` for `fragment`, `vertex`, `compute` and the ray-tracing
  stages, `.slang` for their Slang twins, `.metal` for `msl`, `.stacksl` for `stackSL`.
  `Box.ioSeparateProperties` in the Field source is the whole list.
- **Editing a sidecar is enough.** Field's file watcher picks a new or changed file up within a
  few seconds, creates the box if it did not exist, positions it, loads the property and saves to
  `metadata.edn`. The underscore before the dot (`_.`) is required.

```bash
# create a box by creating a file named <boxId>_<property>_.<ext>
echo 'println("hello from a new box")' > hello_code_.kt
```

Field's console logs every file operation (`info,file-watch: Auto-created box: …`), which is the
first place to look when something does not sync.

## The full reference

The manual file mechanics are in `dev_docs/FileWatchManager.md`. To *run* code, capture output and
errors, take screenshots, and build and save documents, use the agent automation path, documented
in `dev_docs/agents/` in the Field repository:

- `dev_docs/agents/README.md` — the map of the agent path
- `dev_docs/agents/OPERATION_TRANSCRIPT.md` — build / edit / launch / save documents
- `dev_docs/agents/FIELD_BACKDOOR.md` — run code, capture output, screenshots, video
- `dev_docs/agents/AGENT_QUICK_REF.md` — the current one-page card
