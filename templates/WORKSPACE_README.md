# A Field workspace

Welcome. This folder is a **Field document** — a live-coding canvas made of *boxes*
(units of Kotlin, GLSL shaders, or other small languages) that you can edit and run
while Field is running.

- **Open it in Field**: launch Field and open this document by name, or point Field's
  `--workspace` at the folder that contains it.
- **`metadata.edn`** is the document: it lists the boxes and their properties. Field
  manages it for you — you rarely need to edit it by hand.
- **`<box>_<property>_.{kt,glsl,yaml,js}`** are *sidecar* files: the larger text
  properties of each box (code, shaders, metadata) kept as editable files on disk.

## Two ways to work here

**1. Edit files directly.** Create or edit a sidecar file and Field's file watcher picks
it up within a few seconds — handy for quick manual edits.

```bash
# create a box by creating a file named <boxId>_<property>_.<ext>
echo 'println("hello from a new box")' > hello_code_.kt
```

Field detects `hello_code_.kt`, creates a box `hello`, positions it, loads the code, and
saves to `metadata.edn`. The underscore before the dot (`_.`) is required. Supported
properties: `code` (`.kt`), `fragment`/`vertex`/`compute` (`.glsl`), `metadata`
(`.yaml`), `customization` (`.js`).

**2. Drive Field programmatically.** To *run* code, capture output and errors, take
screenshots, build documents, and save them — use the agent automation path. If you are
an AI agent, **read [`AGENTS.md`](./AGENTS.md) in this folder** — it onboards you to that
path. Humans can read it too; it's the fast way to script Field.

## The full reference

The manual file mechanics live in Field's `dev_docs/FileWatchManager.md`. The automation
path is documented in `dev_docs/agents/` in the Field repository:

- `dev_docs/agents/README.md` — the map of the agent path
- `dev_docs/agents/OPERATION_TRANSCRIPT.md` — build / edit / launch / save documents
- `dev_docs/agents/FIELD_BACKDOOR.md` — run code, capture output, screenshots, video

Field's console logs every file operation (`info,file-watch: Auto-created box: …`) — a
good first place to look when something doesn't sync.
