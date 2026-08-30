# Agent Quick Reference

A one-page cheat-sheet for driving Field from an agent. For the full story see
[`README.md`](./README.md) (the map), [`OPERATION_TRANSCRIPT.md`](./OPERATION_TRANSCRIPT.md)
(build/edit/launch/save), and [`FIELD_BACKDOOR.md`](./FIELD_BACKDOOR.md) (the harness).

All commands run from the repo root through `uv`. Use a custom high `--port` (e.g. 9477)
for isolated verification. Port 9092 is appropriate only for explicitly coordinated
live coding in the user's visible, identified Field session.

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

- Backdoor `run` executes once and waits; backdoor `launch` starts a job and waits for its
  initial running signal, which may be transient. Use
  `wait-running <uid> --state running --settle 0.5` when the box must keep owning an
  animation/render loop. Transcript Kotlin-only launch groups additionally use the awaited batch
  path; mixed/non-Kotlin transcript groups do not promise the same first-frame barrier.
- `launch-batch <parent> <child> <draw>` sends one ordered chord through the same
  `launch-batch` path as a multi-box canvas gesture. The server queues evaluation and gives each
  preceding box runloop turns before evaluating the next; another batch extends the queue.
- `@last` = the most recently created box this session.
- Read the `events` array (and `data`) from the JSON. Compile errors and exceptions show up there.
- `--value`/`--file` are **optional** on `run`/`launch <uid>`: supply one to override the box's code, omit both to run the box's currently *stored* code (same stored-code fallback as the transcript `execute`/`launch` ops).
- `action <uid> moduleHere_reload --property vertex` invokes the box's existing live
  shader action. Empty line-minus-one `error` packets clear old errors and are not failures.

## Build / edit / save a document (OperationTranscript)

The canonical semantic layer. Write a YAML macro, run it:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch --port 9477 \
  --command 'macro write my_scene --file my_scene.yaml' \
  --command 'macro run my_scene'
```

```yaml
operations:
  - create: { box: a, name: A, frame: {x: 40.0, y: 40.0, w: 200.0, h: 50.0},
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
wait-native-renderer [<box>] [--frames 3]         # before any native-screenshot
record-window <path> [--uid <box>] [--duration 5] [--fps 30]
record-box <box> <path> [--duration 5] [--fps 30]
record-editor <box> <path> [--duration 5] [--fps 30]
  ... the record-* commands also take [--frame-synchronous] [--restart-target]
```

The output path is a **positional** on all of these; there is no `--output`. The
`-window` pair takes the box through `--uid`, the `-box`/`-editor` pairs take it as the
first positional. You cannot check by hand — the subparsers are `add_help=False`, so
`<command> --help` prints the top-level usage and an error that looks like it is about
your arguments. `tools/test_field_backdoor.py::CaptureSignatureDocTests` holds these
lines to the real parser.

Inspect the actual PNG/MP4 — clips are CSS pixels; output files may be device-scale.
Docs videos use both frame-synchronous flags: one Field frame becomes one encoded
frame, and `duration × fps` is the exact frame count. Leave them off for live UI capture.

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

`fromContext` searches this box's *properties* and then, breadth-first, its ancestors' properties
and top-level variables. It deliberately does **not** see this box's own top-level variables, so
re-running the box above re-resolves from the parent instead of finding the `renderer` the previous
run left behind. Pass `fromContext(includeLocalVariables = true)` for the old, self-pinning
behaviour.

The one shape that costs you: a box holding the *only* copy of a value in a bare top-level
`val`/`var`, with nothing above it holding one, cannot reach it through `fromContext` at all — it
throws `IllegalArgumentException: must pass in a value for context parameter …`. Publish it as a
property instead (`prop<Renderer>(renderer)`, which is what a renderer box does), pass it
explicitly, or use the opt-in. All four cases are staged live and asserted in
`examples.context.search-order-ledger`, with the measured output recorded in
[`../stdlib_behaviour_changes.md`](../stdlib_behaviour_changes.md).

## Inspect a live value at runtime (debug probe)

Ask the running JVM "what is this variable right now?" via the MCP tool
`field_debug_probe` — a JDWP probe that suspends only the hitting event thread
while reading the value, then immediately resumes it. Source mode targets loaded
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

- For a real document, pass **both** `--workspace <parent>` and the relative
  `--document <name>`. An attached server does not donate its launcher workspace to the
  backdoor; `--document dust_three` alone still targets
  `build/field-automation-workspace/dust_three`.
- For user-authorized live coding against an already-running Field, add
  `--attach-existing`. It bypasses source freshness only; checkout identity is still
  required, and it will not start a missing server.
- `--document <name>` is resolved **relative to the workspace root** — an absolute path loads an empty doc. Save into a workspace dir and open by relative name.
- Prefer `--file -`/`--file <path>` over `--value` for anything nontrivial (no shell escaping).
- Fresh scratch session / fresh port per final asset so stale boxes don't ghost into captures.
- Every run writes `.field-backdoor/logs/<session>.jsonl`; `status` reports the paths.

## First command to run

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 --command status
```

Tells you whether Field was already running, whether this session started its own server,
the resolved workspace, the log paths, and whether you're in scratch mode. For live work
in a user's document, add `--workspace <parent> --document <name>` to this first command
plus `--attach-existing`, then verify `workspace`, `documentName`, `sessionKey`, and
`boxCount` before editing.
