# Agent Quick Reference

A one-page cheat-sheet for driving Field from an agent. For the full story see
[`README.md`](./README.md) (the map), [`OPERATION_TRANSCRIPT.md`](./OPERATION_TRANSCRIPT.md)
(build/edit/launch/save), and [`FIELD_BACKDOOR.md`](./FIELD_BACKDOOR.md) (the harness).

All commands run from the repo root through `uv`. Use a custom high `--port` (e.g. 9477)
so you get an isolated agent-owned Field, not a stale dev server on 9092.

## Run code, read output

```bash
# quickest: one box, run it, read the output event
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
  --command 'new-box --name Hello' \
  --command 'run @last --value "println(\"hello world\")"'
```

```bash
# no shell escaping: pipe the code in
cat <<'EOF' | uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
  --command 'new-box --name Hello' \
  --command 'run @last --file -'
println("hello from stdin")
EOF
```

- `run` executes once and waits; `launch` starts a job and waits for first frame; `stop` ends it.
- `@last` = the most recently created box this session.
- Read the `events` array (and `data`) from the JSON. Compile errors and exceptions show up there.
- `--value`/`--file` are **optional** on `run`/`launch <uid>`: supply one to override the box's code, omit both to run the box's currently *stored* code (same stored-code fallback as the transcript `execute`/`launch` ops).

## Build / edit / save a document (OperationTranscript)

The canonical semantic layer. Write a YAML macro, run it:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch --port 9477 \
  --command 'macro write my_scene --file my_scene.yaml' \
  --command 'macro run my_scene'
```

```yaml
operations:
  - create: { box: a, name: A, frame: {x: 40.0, y: 40.0, w: 180.0, h: 100.0},
              properties: { code: 'println("built")' } }
  - connect: { parent: a, child: b }     # graph topology (parent → child)
  - launch:  { box: a, property: code }   # runs stored code; fails loud if missing/empty
  - await:   { wait: pending }            # barrier: drain launches/fragments
  - save:    { path: /abs/workspace/dir/newDoc }   # → a reopenable metadata.edn
```

Read the run result: `success`, `applied`/`skipped`/`errors`, and `output` (`println`
etc. tagged by box + line). Ops: `create edit move connect execute launch stop wait
await save delete`. See [`OPERATION_TRANSCRIPT.md`](./OPERATION_TRANSCRIPT.md).

## Read / rewrite a user's macros

```text
macro list                 # what's saved on this document
macro read <name>          # print a macro's YAML to understand it
macro write <name> --file  # write/replace
macro run <name>           # apply
macro undo <name>          # reverse supported ops
```

## Screenshots & video

Attach the browser with `--with-playwright` *before* the run that creates output.

```text
screenshot-window <path> [--uid <box>] [--with-editor] [--open-outputs]
screenshot-box <box> <path> [--padding 16]
screenshot-editor <box> <path> [--padding 10]     # inline Monaco visualizers
native-screenshot <path> [--wait-frames 4]        # native afield.four Vulkan window
record-window / record-box / record-editor <path> [--duration 5] [--fps 30]
```

Inspect the actual PNG/MP4 — clips are CSS pixels; output files may be device-scale.

## Native graphics (afield.four)

A graphics snippet needs a `Renderer` inherited from a launched renderer parent.

```bash
# quick one-off: --graphics-context creates+launches a `renderer` parent and
# auto-connects later new-box boxes beneath it
uv run --project . --group backdoor python tools/field_backdoor.py --json --graphics-context --port 9477 \
  --command 'new-box --name GfxSmoke' \
  --command 'run @last --value "println(renderer)"'
```

For transcripts: run [`transcripts/graphics_renderer_launch.yaml`](./transcripts/graphics_renderer_launch.yaml),
then `wait-native-renderer agent_graphics_context --frames 3`, then create+connect the
example box beneath `agent_graphics_context` and launch it. Capture context before any
async boundary: `val renderer: Renderer = fromContext()` at top level, before `start(...)`.

## Inspect a live value at runtime (debug probe)

Ask the running JVM "what is this variable right now?" via the MCP tool
`field_debug_probe` — a non-suspending JDWP probe. Source mode targets any loaded
`src/` code; box mode targets a running box. Backdoor-launched Field enables the
required JDWP port (5005) automatically.

```jsonc
// source mode — a symbol at a src/ file + real line (line numbers illustrative):
{"file": "Server.kt", "line": 1761, "symbol": "now"}
// box mode — a symbol in a running box's code:
{"box": "MyBox", "line": 12, "symbol": "count"}
```

Full contract + the "no JDWP" error in [`FIELD_MCP.md`](./FIELD_MCP.md#runtime-debug-probe).

## Sharp edges

- `--document <name>` is resolved **relative to the workspace root** — an absolute path loads an empty doc. Save into a workspace dir and open by relative name.
- Prefer `--file -`/`--file <path>` over `--value` for anything nontrivial (no shell escaping).
- Fresh scratch session / fresh port per final asset so stale boxes don't ghost into captures.
- Every run writes `.field-backdoor/logs/<session>.jsonl`; `status` reports the paths.

## First command to run

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 --command status
```

Tells you whether Field was already running, whether this session started its own server,
the log paths, and whether you're in scratch mode.
