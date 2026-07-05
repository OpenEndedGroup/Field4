# Agent Transcript Corpus

This directory is a small, curated corpus of `OperationTranscript` YAML macros for agents. These are examples to copy, run, and adapt when automating Field documents through the backdoor.
For the consolidated testing strategy, start with `dev_docs/testing/TESTING_STRATEGY.md`.

Use server macros when the automation changes Field state semantically: boxes, properties, frames, graph connections, execution, launch, stop, and undo. Use backdoor replay transcripts when the procedure also needs agent-side capture steps such as screenshots or output-event collection.

## Running One

From the repository root:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
  --command 'macro write agent_hello_display --file dev_docs/agents/transcripts/hello_display.yaml' \
  --command 'macro run agent_hello_display'
```

For multi-box construction macros, run without `--selection`. The macro runner maps the first source box to a selection target, which is useful for single-box edit macros but not for blank-canvas construction. These corpus files use stable box ids intentionally.

Use `macro undo <name>` to reverse supported operations:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
  --command 'macro undo agent_hello_display'
```

Backdoor replay transcripts run directly from the YAML and can include capture operations:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright --port 9477 \
  --command 'replay-operations dev_docs/agents/transcripts/hello_display_capture.yaml'
```

Use `--with-playwright` for replay transcripts that capture browser output; this opens the automation browser before the transcript runs so display output emitted during execution is visible to the capture step.

## Supported Replay Verbs

Current useful verbs:

- `create`: create or revive a box by stable id, set `name`, `frame`, and optional `properties`.
- `delete` / `tombstone`: tombstone a box; undo revives it.
- `edit` / `set` / `set-string`: set text-like properties with `from` and `to`.
- `move` / `frame` / `set-rect`: update a box frame.
- `connect` / `disconnect`: change parent-child graph topology.
- `execute`: run a code fragment once.
- `launch`: launch code as a running job; undo stops it.
- `stop`: stop a running job.
- `wait`: sleep for `millis`, `ms`, or `seconds`; `frames` waits for real `Runloop.frame` advancement.

Backdoor replay also supports capture verbs:

- `wait-output`: wait until a box emits inline/display output, optionally filtering by `kind`, `channel`, and `contains`.
- `screenshot`: capture `mode: box`, `mode: editor`, or `mode: window` to `path`.
- `native-screenshot`: capture the active native graphics window to `path`.
- `capture-output`: collect inline/display output events for a box, optionally binding them with `as` and writing JSON to `path`.

Use `wait-output` before `screenshot` when the screenshot is meant to include display output. A macro/run reply can arrive before the browser has rendered the output panel.

## Graphics Topology Rule

`afield.four` examples get their `Renderer` from graph topology, not from global state. The renderer box must be launched first, and each graphics example box must be connected as a child of that renderer box before it runs.

Use this sequence for new graphics automation:

1. Run `graphics_renderer_launch.yaml`.
2. Wait for renderer liveness with `wait-native-renderer agent_graphics_context --frames 3`.
3. Create the example box with a stable id.
4. Connect `agent_graphics_context` as parent and the example box as child.
5. In example code, resolve `val renderer: Renderer = fromContext()` at top level before calling `start(...)` or launching suspend work.
6. Pass that renderer explicitly into async helpers that render or capture.

If a graphics example reports `must pass in a value for context parameter afield.four.Renderer`, treat it as a broken parent-child setup or a renderer lookup that happened after coroutine context was lost. Fix the transcript topology or capture the renderer before `start(...)`; do not add sleeps or window-system screenshot fallbacks.

For renderer boxes, keep `start(renderer::run)` as the final expression of the launched code. Field uses the returned `Frames` object as the running job; adding logging or setup after `start(...)` can leave a constructed renderer whose frame counter never advances.

## Files

- `hello_display.yaml`: smallest useful blank-canvas transcript; creates one box, displays HTML, and can be undone.
- `hello_display_capture.yaml`: backdoor replay transcript; creates one display box, captures output events, and writes a cropped screenshot.
- `two_box_context.yaml`: creates two boxes, connects them, and runs child code that depends on graph context.
- `graphics_renderer_launch.yaml`: creates the reusable `agent_graphics_context` renderer box, launches it with `start(renderer::run)`, prints `agent graphics renderer ready`, and waits three Runloop frames. Run this before child `afield.four` graphics transcripts.
- `afield_four_traditional_triangle.yaml`: creates a graphics child box for the stable backdoor graphics parent `agent_graphics_context` and draws a native Vulkan triangle.
- `graphics_context_bootstrap.yaml`: older combined renderer/child smoke transcript; prefer composing `graphics_renderer_launch.yaml` with a focused child transcript for new docs/tests.

## Growth Rules

Keep examples small and agent-useful:

- prefer stable ids over generated ids;
- use event waits such as `wait-output` where possible, and reserve fixed `wait` entries for launch-dependent timing;
- for native graphics, launch `graphics_renderer_launch.yaml` first, run `wait-native-renderer agent_graphics_context --frames 3`, and then launch child boxes;
- keep browser capture operations in backdoor replay transcripts, not server macros;
- add a short comment in this README when an example depends on hardware, Vulkan, audio, or external services.
