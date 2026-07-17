# Agent Transcript Corpus

This directory is a small, curated corpus of `OperationTranscript` YAML macros for agents. These are examples to copy, run, and adapt when automating Field documents through the backdoor.
For the consolidated testing strategy, start with `dev_docs/testing/TESTING_STRATEGY.md`.

Server macros are the one semantic-automation layer: they change Field state — boxes, properties, frames, graph connections, execution, launch, stop, and undo. Capture (screenshots, output-event collection) is a transport concern, so it lives in the backdoor's own commands (`wait-output`, `output`, `screenshot-box`, `native-screenshot`), run *after* the macro against the boxes it created. The backdoor never grows a second durable op language of its own.

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

## Capturing output and screenshots

Capture is not a macro op. Run the macro, then chain the backdoor's own capture commands against the boxes it created (referenced by their stable ids). Use `--with-playwright` so the automation browser is attached before capture, making display output visible to the screenshot step:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright --port 9477 \
  --command 'macro write agent_hello_display_capture --file dev_docs/agents/transcripts/hello_display_capture.yaml' \
  --command 'macro run agent_hello_display_capture' \
  --command 'wait-output agent_hello_display_capture --kind display --contains "hello from OperationTranscript"' \
  --command 'output agent_hello_display_capture --kind all' \
  --command 'screenshot-box agent_hello_display_capture build/agent-transcripts/hello-display.png --padding 18'
```

`wait-output` blocks until the box emits matching output; `output` returns the collected events in the JSON result; `screenshot-box` writes a cropped PNG (`--no-pan` / `--no-open-outputs` tune the framing). For native graphics windows use `native-screenshot <path>` instead. Run `wait-output` before `screenshot-box` when the shot should include rendered display output — a `macro run` reply can arrive before the browser has painted the output panel.

## Supported Macro Ops

These are `OperationTranscript` ops, applied by `macro run` server-side. `dev_docs/agents/OPERATION_TRANSCRIPT.md` is the canonical vocabulary; the most useful ops for this corpus:

- `create`: create or revive a box by stable id, set `name`, `frame`, and optional `properties`.
- `delete` / `tombstone`: tombstone a box; undo revives it.
- `edit` / `set` / `set-string`: set text-like properties with `from` and `to`.
- `move` / `frame` / `set-rect`: update a box frame.
- `connect` / `disconnect`: change parent-child graph topology.
- `execute`: run a code fragment once and await it (output is captured into the run result). If the op carries no `code`, it falls back to the box's stored `code` (or the `property` you name), so a `create` that sets code followed by a bare `execute` runs that stored code.
- `launch`: **the primary execution verb** — launch code as a running job (renderer/compute/draw boxes are all launched); undo stops it. Like `execute`, a bare `launch` with no inline `code` runs the box's stored `code` (or named `property`), and startup output is captured into the run result. `launch` fails **loud**, never silently: if the target box is not present it reports `` `launch` target box `X` is not present in the document (present: …) `` (listing the boxes that _are_ loaded), and if the resolved property has no code it reports `` has no code to run ``. Either makes the run `success=false` with the reason in `errors`/`message` — so a wrong box id or a mis-resolved document path is obvious immediately rather than a quiet no-op.
- `stop`: stop a running job.
- `wait`: sleep for `millis`, `ms`, or `seconds`; `frames` waits for real `Runloop.frame` advancement.
- `await` / `sync` / `settle` / `wait-pending`: barrier — advance `Runloop` frames until no box has a currently-executing fragment (and launched jobs have reached their first frame), then settle a few extra frames. Write `wait: pending`. Use this after fire-and-forget `execute`/`launch` ops so their print output is captured and any published buffers are populated before a later box reads them or a screenshot is taken.
- `save` / `save-document` / `save-as`: persist the current (transient) document's live boxes to a `metadata.edn` document at `path` (aliases: `to`, `fn`, `document`). Tombstoned boxes and the synthetic root are excluded; box preambles are regenerated in the snapshot. Awaits the background write (30s cap) so the document folder exists on disk when the op returns. This is how an agent turns an assembled scene into a loadable document.

Capture (screenshots, output collection) is **not** a macro op — it is done with the backdoor's own commands after the macro runs. See "Capturing output and screenshots" above.

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
- `hello_display_capture.yaml`: same as `hello_display.yaml` but paired with the backdoor capture-command chain (`wait-output` / `output` / `screenshot-box`) documented under "Capturing output and screenshots" — the worked example of capturing a macro's output and a cropped screenshot.
- `two_box_context.yaml`: creates two boxes, connects them, and runs child code that depends on graph context.
- `graphics_renderer_launch.yaml`: creates the reusable `agent_graphics_context` renderer box, launches it with `start(renderer::run)`, prints `agent graphics renderer ready`, and waits three Runloop frames. Run this before child `afield.four` graphics transcripts.
- `afield_four_traditional_triangle.yaml`: creates a graphics child box for the stable backdoor graphics parent `agent_graphics_context` and draws a native Vulkan triangle.
- `linalg_thumbnails_demo.yaml`: four blank-canvas boxes that evaluate a `vec2`, `vec3`, `mat3`, and `mat4`; on execute each box's output shows the terse text head plus the inline SVG thumbnail `ValueVisualizer` draws (auto-scaled arrow for vectors, isometric for `vec3`, diverging heatmap grid for matrices).
- `threebox.yaml`: self-contained capstone. One transcript builds a three-box particle system on a blank canvas — a renderer box (owns `Window` + `Renderer`, pumps the loop), a compute box (simulates particles into a `buffer_reference` buffer and probes speed via `{% include "fieldProbe" %}`, publishing the buffer as `prop<FrameBufferGroup>(outputPositions)`), and a draw box (reads `outputPositions` and renders). Requires a GPU/Vulkan window. Demonstrates cross-box property publish/read and the `await` barrier between the three launches; the transcript itself produces no image artifact (no screenshot step) — capture its native window with a follow-on `native-screenshot <path>` backdoor command (`--with-playwright`).

## Growth Rules

Keep examples small and agent-useful:

- prefer stable ids over generated ids;
- use event waits such as `wait-output` where possible, and reserve fixed `wait` entries for launch-dependent timing;
- for native graphics, launch `graphics_renderer_launch.yaml` first, run `wait-native-renderer agent_graphics_context --frames 3`, and then launch child boxes;
- keep browser capture in the backdoor's capture commands (`wait-output` / `output` / `screenshot-box` / `native-screenshot`), run after the macro — never as macro ops;
- add a short comment in this README when an example depends on hardware, Vulkan, audio, or external services.
