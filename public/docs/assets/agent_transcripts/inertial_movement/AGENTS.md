# You're in a Field workspace — here's how to drive it

Hello, agent. This folder is a **Field document**: a live-coding canvas of *boxes*
(Kotlin, GLSL shaders, and other small languages) that Field runs on the fly. Field is
an art-oriented live-coding IDE — graphics, sound, data, motion. Working here should be
*fun and dependable*. This guide gets you productive fast.

## What you're looking at

- **`metadata.edn`** — the document: the list of boxes and their properties. Field owns
  it. **Don't hand-edit it to make things happen** — drive Field instead (below).
- **`<box>_<property>_.{kt,glsl,yaml,js}`** — sidecar files holding each box's larger
  text properties (`code`, `fragment`, `vertex`, `compute`, …). Editing these on disk is
  fine for quick manual changes; Field's file watcher syncs them within a few seconds.
- **`README.md`** — the human-facing tour of this folder.

## The golden path: drive Field, don't poke files

To *run* code, see its output and errors, capture a screenshot, or build and save a new
document, talk to the running Field through its **backdoor** and the **OperationTranscript**
format. This is the dependable path — it gives you real output, real errors, and real
pictures, not guesses.

The tooling lives in the Field repository (`tools/field_backdoor.py`); run these from the
repo root. They attach to the running Field and operate on **this document** (its name is
this folder's name, relative to the workspace root).

**Run a box and read its output:**

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --document THIS_DOC \
  --command 'macro write run_it --content "operations: [ {launch: {box: BOX_ID}} ]"' \
  --command 'macro run run_it'
```

The result JSON carries `success`, `applied`/`skipped`/`errors`, and `output` — the
`println`/display output of every box that ran, tagged by box and line. That `output`
array is your proof the code ran (and where compile errors and exceptions surface).

**Build something from scratch** (on a disposable scratch document), then save it here:

```yaml
# scene.yaml
operations:
  - create: { box: hello, name: Hello, frame: {x: 40.0, y: 40.0, w: 200.0, h: 120.0},
              properties: { code: 'println("built by an agent")' } }
  - launch: { box: hello, property: code }
  - await:  { wait: pending }
  - save:   { path: /ABSOLUTE/path/under/your/workspace/hello_doc }
```

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch \
  --command 'macro write scene --file scene.yaml' \
  --command 'macro run scene'
```

**Capture a picture** (attach the browser first with `--with-playwright`):

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright --document THIS_DOC \
  --command 'screenshot-box BOX_ID /tmp/box.png --padding 16'
```

For native `afield.four` graphics, use `native-screenshot` after the renderer has advanced
a few frames (`wait-native-renderer … --frames 3`).

## The operations you'll use

`create` · `edit` · `move` · `connect` (parent→child graph) · `execute` (run once) ·
`launch` (start a job — the main execution verb) · `stop` · `wait` · `await` (barrier:
drain launches so their output is captured) · `save` (write a reopenable `metadata.edn`) ·
`delete`. A bare `launch`/`execute` with no inline `code:` runs the box's **stored** code.

`launch` fails **loud**: if a box is missing or has no code, the run reports
`success=false` and names the box (and lists which boxes *are* present) — so a typo is
obvious, never a silent no-op.

## Read and remix what's already here

```text
macro list            # macros saved on this document
macro read <name>     # print a macro's YAML to understand it
macro write <name>    # write/replace your own
macro run <name>      # apply it
```

Read a user's saved macro, adapt the YAML for your ends, write it under a new name, run it.

## Dependability notes (things that bite)

- **`--document` is relative to the workspace root.** An absolute path loads an *empty*
  document. To reopen something you saved, save it *into* a workspace dir and open it by
  its relative name.
- **CLI `run`/`launch <uid>` overrides stored code** with whatever `--value` you pass
  (empty if omitted). To run a box's stored code, use a transcript `launch`/`execute` op
  with no inline `code:`.
- **Always read `output`/`events`** from the JSON result rather than assuming success.
- Every run logs to `.field-backdoor/logs/` in the repo; `--command status` prints the paths.

## Go deeper

The full reference lives in the Field repo under `dev_docs/agents/`:

- `dev_docs/agents/README.md` — the map of the agent path
- `dev_docs/agents/OPERATION_TRANSCRIPT.md` — the complete op vocabulary + build/save lifecycle
- `dev_docs/agents/FIELD_BACKDOOR.md` — the run/capture/screenshot/video harness
- `dev_docs/agents/transcripts/` — curated, runnable example transcripts to copy

Have fun. Make something that moves.
