# Light infrastructure: review and verification

Worktree: `codex/light-infrastructure-promotions-2026-09-21`, based on `bec29b99d`.
Implementation and the live checks below used the isolated worktree. This work adds a
public playground and reusable primitives; it does not silently migrate `light_four`
or edit the user's saved Light documents when landed on main.

## Entry points

- Public walkthrough: `public/docs/flow_grain.md`.
- Installer: `dev_docs/agents/transcripts/flow_grain.yaml`.
- Readable box sources: `dev_docs/agents/demos/flow_grain/`.
- Materialized starting point: `public/docs/assets/agent_transcripts/flow_grain/`.
- Agent handoff and contracts: `AGENT_WORKFLOW.md` beside this file.

The renderer, resources, compute stages, grain surface, resolve and strokes each have
ordinary Launch/Stop ownership. Execute Parameters or Reset; launch Profile to record a
fixed scenario and open Field's existing frame-budget timeline. Start the numbered graph
before launching Profile. A saved document is not proof that its runtime resources exist.

## Verified on this machine, 2026-09-22

- 73 targeted JVM tests in 11 suites: execution receipts, reset, residency, parameters,
  motion history, existing FrameHistory, shared tools and transcript layout.
- Kotlin/JS development build.
- 106 documentation/citation assertions in 14 suites, including personal boundaries.
- Transcript index/layout checks; all 140 public documents materialize without drift.
- Codebase reference, completion fixtures, KDoc attachment and test-tree-write guards.
- 87 Python tests for backdoor behavior, execution receipt correlation and tool images.
- Existing OperationTranscript macro and promotion JVM smoke entrypoints.
- The `flow_grain` compile-only transcript check plus actual native Vulkan execution.

`tools/verify_light_infrastructure.py --port PORT` performs ten live checks, including
stale-edit refusal, successful/failed source identity, macro failure preserving prior
work, nested graph/guide export roundtrip, editor selection/centering, plain frontend
JSON and the explicit unverified-completion result for a guest-language macro.

`tools/verify_flow_playground.py --port PORT` performs nine GPU/lifecycle checks:
clean replay, intentional bad shader and recovery with an actual image, strict capture
targeting, stage Stop/relaunch, effective parameter bounds, motion attachment views,
forced GC with live pipelines and repeated reset/profiling. Add `--server-log PATH`
to check document shutdown as a tenth check: it waits for the renderer's disposal
receipt and rejects Vulkan validation errors in that run's server log. The supplied
log must be inside this checkout's `.field-backdoor/logs`; no other client can keep
`flow_grain_verified` open during the shutdown test. Both scripts require an already-running server whose
workspace is under this checkout's `build/`; they refuse the user's workspace.

The reset test completed **32 resets, each within one rendered frame**, with the same
buffer, render target, pipeline-stage keys and frame hooks. Captures show the moving
marker's motion differing from the camera-only world. The displacement is small at
120 Hz; the verification artifacts include an amplified image difference for inspection.

The final two fixed 180-frame scenarios, after 60 warm-up frames, produced 179 interval
records each. Percentiles below use the nearest rank at or above the requested fraction:

| Measurement | Capture 1 | Capture 2 |
| --- | ---: | ---: |
| Frame interval p50 / p95 / max | 8.337 / 9.069 / 11.308 ms | 8.350 / 9.188 / 18.469 ms |
| Measured thread CPU p95 / max | 0.624 / 1.058 ms | 0.556 / 2.168 ms |
| Allocated per interval p95 / max | 19.555 / 143.867 KiB | 19.562 / 114.602 KiB |
| Whole-JVM GC collections during capture | 0 | 0 |
| JFR samples | 220 | 212 |

These are local playground measurements, with the 960×720 RTT, a 1920×1440 native
framebuffer, 16,000 finite particles, stack sampling enabled and GPU queries disabled.
They are not `light_four` performance numbers. JFR processing completed before the next
capture started. Capture 2 had one interval over the 16.67 ms guide. The normal Profile
box also ran and opened a populated frame-budget
panel. GPU pass timings use the existing renderer profiler; enabling its queries changes
the measurement conditions and should be done consistently for comparisons.

Artifacts from the live scripts are under `build/light-infrastructure/`: source/tool
verdicts, reload and motion pictures, reset receipt, two JSON profiles and a frontend
profile-panel capture. These are reproducible build artifacts, not committed fixtures.

The public docs build regenerated the native capture through the normal runner.
Browser review confirmed the image and the 15-box offline starting point. The built-site
check resolved all 1,620 distinct local file references; its one missing theme fragment
is an existing registered exception. The asset check passed after staging the new capture
image, with four existing registered ray-query placeholder exceptions unchanged.

## Renderer lifetime follow-up, 2026-09-22

The earlier child-buffer teardown warnings are resolved for this playground. Renderer
shutdown drains recent retirement batches, then releases its device-owned resources and
the empty VMA allocator. Disposal tokens are one-shot across explicit cleanup, GC and
shutdown. Different renderers drain their own devices' GC work. Pipeline callbacks
capture handles rather than retaining their module, and retain descriptor-layout wrappers
for the pipeline's entire life, including reloads.

The first full check caught premature collection of descriptor layouts; that was repaired
by giving the pipeline explicit ownership. The repeatable verifier now forces GC while
the full graph is live, captures its output, and verifies shutdown afterwards. All ten
checks passed on the final code with the validation layer loaded and no Vulkan VUIDs.
The positive shutdown receipt reports the native window count changing from one to zero.

`four.renderer-cleanup.smoke` also passed. It retains buffers, RTT images, a sampler and
fence through shutdown, exercises recent buffer replacement and chained cleanup, checks
zero owned tokens and zero remaining VMA allocations, and tries late explicit cleanup and
a repeated shutdown. This test now requires an active validation messenger: an earlier
standalone run requested validation without loading the layer and was not counted as
validation evidence. Its SDK startup was corrected before the final passing run.

Follow-up checks: 69 targeted JVM tests in 14 suites, 106 documentation/citation assertions,
reference and KDoc guards, and the tree-write guard. The documentation ledger correctly
flagged `setDefaultSampler` as newly executable coverage; its obsolete compile-only entry
was removed. The final descriptor-retention repair was then compiled and exercised by
the ten live checks above. Logs: `/tmp/light-infrastructure-ownership-final-gates-2.log`,
`/tmp/renderer-ownership-final-smoke.log`, `/tmp/flow-playground-ownership-final-2.log`.
The native smoke result and post-GC image are also in `build/light-infrastructure/`.

## Limits and follow-up boundaries

- Exact setup receipts cover Kotlin. Direct awaited external commands refuse other
  engines before execution. Guest-language macros retain dispatch behavior and return
  `setupVerified: false`; dispatch is not a successful compilation claim.
- Successful source history is runtime evidence and is absent after a restart until
  execution occurs. Shader reload diagnostics/images do not yet maintain a separate
  last-loaded shader source revision in `DocumentSourceReadRequest`.
- Export includes graph ancestors and nested children. It warns about root preambles,
  external assets, source-only dependencies and complex properties it cannot serialize.
- Motion correspondence covers the world and rotating marker. The finite stroke overlay
  is outside that attachment and would need particle histories for exact reprojection.
- The public shader math and tile mapping are reusable; the demo's fluid solver, mask,
  lighting and artistic choices remain editable in its boxes.
- Native lifetime validation covers this playground on the local Vulkan device. Mesh
  shader and hardware ray-tracing disposal paths compile but were not run on hardware
  supporting those features. OpenXR and external-memory integrations were not exercised.
  Arbitrary native handles outside the disposal registry still need explicit ownership;
  this is not a complete audit of every renderer integration.
