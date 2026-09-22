# Live graphics workflow: source, execution and evidence

This is the handoff for the infrastructure extracted from the Light experiments.
The public starting point is `dev_docs/agents/transcripts/flow_grain.yaml`; readable
box sources live in `dev_docs/agents/demos/flow_grain/`. Regenerate its YAML with
`uv run --group backdoor python tools/demos/build_flow_grain.py` after editing those
sources. A live document remains authoritative while working with its user: do not
regenerate or replay an installer over their edits without choosing that explicitly.

## First establish the session

Read status and verify `serverWorkspace`, `documentName`, `sessionKey` and `boxCount`.
Use `open NAME --must-exist` for an existing document. A new scratch verification must
use a separate port/workspace; a saved main document must not become your scratchpad.
Use `help` or `help native-screenshot` in the command language for JSON-friendly help.
The shell entry point also retains ordinary `--help`.

## Read, change, execute, observe

1. `DocumentSourceReadRequest(uid, property)` returns live source and its SHA-256
   revision, saved revision/location when readable, latest execution attempt and
   latest successful whole-property setup. Missing evidence is null, not success.
2. `DocumentSourcePatchRequest(uid, property, revision, contents)` compares against
   the live revision on the document owner thread. A stale edit is refused. Re-read
   and reconcile; never retry by blindly replacing the newer content. This edit uses
   normal document persistence, but does not execute or reload.
3. Launch Kotlin through normal box controls, a macro or backdoor `launch`. Correlated
   `execution-verdict` receipts include UID, property, source revision, request ID,
   mode, stage and error. A pending `is-running` stripe is only a queue observation.
   A successful receipt says setup completed, not that the animation stayed healthy.
4. For shader-only edits, use shared `ReloadShadersRequest`; for a picture without
   reloading, use `CaptureRenderWindowRequest`. Explicit box/window targets do not
   fall back to an unrelated window. A post-reload capture is queued on the actual
   renderer, rather than guessed from a fixed sleep.
5. Read subsequent errors and capture actual pixels. External MCP now carries the
   same tool images as in-Field AI4, as image blocks rather than base64 text.

`DocumentBoxNavigateRequest` uses the same frontend select/center/editor command as
Markdown links. Its response says navigation was *requested*; use a UI observation
when visual placement itself is the thing under test.

Execution receipts currently cover Kotlin. Direct awaited backdoor run/launch/continue
refuse unsupported engines before executing; they do not invent a successful receipt.
Ordinary frontend execution and macros retain other languages' existing dispatch path.
A macro containing those operations reports `setupVerified: false` and an explicit
message: its `success` covers dispatch, not completion. Observe subsequent outputs and
the appropriate runtime before relying on their effects.

## Macro failures preserve evidence

Dependent Kotlin macro launches are awaited in order. A failed setup stops subsequent do
operations and returns diagnostics and accurate applied/skipped counts. Prior work
is preserved for inspection. AI4 no longer silently undoes a partial run. Explicit
undo remains a separate, best-effort action. The frontend's `macroPaletteRun` result
is plain JSON, so agents should inspect `success` and named fields, not generated
Kotlin/JS property names.

The transport succeeding and the operation succeeding are different facts. MCP marks
`success: false` / `ok: false` tool results as errors even when the RPC itself completed.
A shader failure can leave the last good pipeline visible; that picture is not proof
that the attempted revision compiled.

## Export deliberately

`DocumentSourceExportRequest` writes a new directory with `install.yaml`, selected
Markdown guides and `provenance.yaml`. It snapshots selected boxes, ancestor graph
dependencies and nested children. Source hashes identify the exported live text.
It neither saves over the live document nor launches exported code.

Review omitted-property warnings, root preambles, references made only in source,
external assets and personal imports. Graph traversal cannot infer every semantic
dependency. The destination must be new; an existing export is never overwritten.
The installer is an editable snapshot, not a promise of public portability.

## Reset and performance

Keep renderer, audio and buffer construction in normal setup. Queue `SimulationReset`
requests from controls; consume once at the simulation/render boundary. Clear state,
invalidate temporal history and acknowledge the ticket there. Keep cleanup in ordinary
box cancellation. Do not add another service to keep the simulation alive.

Renderer shutdown is a separate boundary from stopping a stage. It waits for the GPU,
drains deferred releases, releases device-owned disposal tokens and destroys the empty
VMA allocator before the device. Native wrappers use one-shot tokens so explicit cleanup,
GC retirement and shutdown cannot free the same handles twice. A custom native resource
must still register its device ownership or provide explicit teardown; closing a renderer
does not discover arbitrary handles. Disposer callbacks capture handles, never their
registered wrapper. GC queues are drained by the owning renderer, not another device.

`tools/run_field_tests.py --id four.renderer-cleanup.smoke --port PORT` checks this with
retained buffers, a render target, sampler and fence, recent deferred releases, late
explicit cleanup and a repeated shutdown. It requires an active Vulkan validation layer
and rejects validation errors; do not weaken that requirement to obtain a pass.

Use named `EffectiveParameter` values where the effective value differs from a request.
Expose units, bounds and snapshots in the document. Read effective values at the frame
boundary, not from a forest of independently changing push-constant closures.

Use the existing profiling timeline and `frameProfile` for complete-frame CPU and
allocation budgets. Use JFR for sampled attribution and the renderer profiler for GPU
work. Capture a fixed scenario: reset, warm-up, prescribed movement, fixed rendered-frame
count. Record parameters, renderer size and whether stack sampling was enabled. A headless
CPU test cannot certify a live fluid/shader pipeline.

## Public extraction boundaries

- `afield.stdlib.TileResidency`: bounded world tile identity, recycling and generations.
- `afield.four.flow.TileAtlasLayout`: GPU atlas layout, with explicit halo responsibility.
- `afield.four.targets.MotionItems`: stable object correspondence alongside `FrameHistory`.
- `afield.four.flow.FlowShaders`: small grain/stroke/motion/tile shader functions.

Keep game rules, lighting geometry and aesthetic tuning in the personal game. Keep one
shader (or shader plus resource) per box, comments on inputs/push constants, and visible
wiring. Promotion should make the next experiment easier to edit, not hide it in a class.
