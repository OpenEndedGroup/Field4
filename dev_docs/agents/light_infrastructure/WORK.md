# Light infrastructure and public promotions

Accepted scope, 2026-09-21. Worktree `codex/light-infrastructure-promotions-2026-09-21`,
starting at `bec29b99d`. Preserve the live `light_four` on main/9745.

## Delivery checklist

- [x] 1. Shared truthful execution verdicts; failure stops dependent macro operations;
      source identity and bounded diagnostics; ordinary frontend/AI4/backdoor agree.
- [x] 2. Owner-thread simulation reset without recreating graphics/audio; ordinary
      Launch/Stop ownership and repeatable reset test/demo.
- [x] 3. Shared AI4 providers for shader reload/navigation and external tool access;
      plain JS result objects and discoverable command help.
- [x] 4. Live/disk/running source revisions and deliberate graph+guide export;
      preserve concurrent editing and record provenance.
- [x] 5. Requested/effective parameter values and deterministic profiling scenarios
      through the existing frame/timeline profiler (CPU, allocation, GPU evidence).
- [x] Promotion 1: bounded scrolling tile residency plus GPU layout helpers.
- [x] Promotion 2: motion correspondence using existing FrameHistory, stable IDs,
      topology invalidation and shader helpers.
- [x] Promotion 3: flow-oriented grain and particle strokes; editable shaders and
      wiring in a normal Field starting point, not hidden in an engine class.
- [x] Public docs, demo operation transcript and real capture.
- [x] Targeted tests, appropriate broader checks, final evidence and limitations.

## Boundaries

No new launcher or profiler. Keep game rules, optical light tessellation, and
personal tuning private. Do not overwrite live shader edits. Do not claim that
compilation or coroutine installation proves a rendered frame. Keep graphics
verification separate and explicit. No changes to the user's live session.

## Progress and evidence

- Initial clean main confirmed. Worktree creation needed `/opt/homebrew/bin` on
  PATH for git-lfs; retry succeeded without changing main.
- Confirmed macro output contains diagnostics but success only counts thrown
  operation errors. Macro launch groups also continue after compilation errors.

### Implemented and checked so far (2026-09-22)

- Shared ExecutionTrace/ExecutionReport receipts with exact authored source hashes;
  Kotlin launch/run/continue and macro setup grading. Sequential dependent macro
  launch, bounded output, no implicit AI4 rollback. Unsupported awaited engines
  explicitly refuse rather than guessing from running stripes.
- Shared AI4 runtime providers; MCP image transport; strict capture targeting;
  JSON-friendly command help; plain JS macro results.
- Live/saved/executed source inspection, revision-checked patches, graph/guide export
  with provenance and omitted-property warnings; standard frontend navigation.
- SimulationReset, EffectiveParameter, TileResidency/TileAtlasLayout, MotionItems,
  reusable GLSL grain/stroke/motion/tile includes. Existing FrameHistory retained.
- Targeted JVM tests + tree-write guard passed in `/tmp/light-infrastructure-check-8.log`.
- 86 Python tests passed in `/tmp/light-infrastructure-python-2.log`.
- Isolated Field on 9786 (HTTP19786), separate build workspace. Main's 9745 untouched.
- First actual GPU capture inspected: `/tmp/flow-grain-third.png`. Fluid, obstacles,
  shadows, marker and velocity strokes render. Fixed explicit parent bindings,
  helper box name resolution, RTT attachment configuration and Mesh.resize usage.
- Live source read verified matching live/saved/executed hashes. External capture
  returned one image; nonexistent UID returned failure with no fallback image.
- First isolated server shut down via its owned session socket, then restarted
  with final receipt serialization/cancellation changes (server-2 log in /tmp).

### Final delivery

All checklist items are implemented. The public walkthrough is served at
`http://localhost:8012/field4/flow_grain/`; its offline starting-point link loads all
15 boxes. The native docs capture was generated through the normal docs runner and
inspected, and the built-site link and asset checks passed (existing registered theme
fragment / ray-query placeholder gaps remain unchanged).

See [VERIFICATION.md](VERIFICATION.md) for the exact live checks, measurements and
limitations, and [AGENT_WORKFLOW.md](AGENT_WORKFLOW.md) for the next agent's working
contract. Final core gate: `/tmp/light-infrastructure-final-gates.log`; final generated
bundle/fixture check: `/tmp/light-infrastructure-final-generated.log`. The latter ran no
unit tests; the test-tree-write check belongs to the preceding core gate that did.

Live integration: ten checks passed. Live GPU/lifecycle: eight checks passed, including
32 resets completed within one rendered frame with resource identities unchanged. The
public Profile box was separately launched and its populated timeline inspected.
A first standalone materializer call hit the backdoor's default 20-second timeout;
rerunning with its documented larger timeout completed. A Profile launch against a
saved but no-longer-initialized runtime correctly failed; replaying the numbered graph
before launch passed. Missing resource errors are now explicit.

A final refinement gives the Profile box cancellation cleanup, restores its previous
travel setting, and records effective parameter snapshots. The generated YAML, public
bundle and completion fixtures were regenerated and checked afterwards.

Implementation and live verification took place in the isolated worktree. The capture
image was staged for the docs asset-ownership check. The user subsequently authorized
landing this work on main; saved Light-document migration remains outside this change.

### Renderer lifetime follow-up

Resolved the device child-resource warnings found during document close. Shutdown now
drains deferred releases and device-owned disposal tokens before destroying the empty
VMA allocator and device. Explicit cleanup, GC and shutdown share one-shot ownership.
Pipeline disposer snapshots avoid retaining their module and keep descriptor-layout
wrappers alive until the pipeline is retired. The first full test caught premature
layout collection; the corrected verifier now forces GC with a live graph.

The native cleanup smoke requires an active validation messenger and passed. The full
playground passed ten checks, including rendered evidence after GC and a completed
document close with no Vulkan validation errors. The preview was then replayed for
handoff. Follow-up gates passed 69 JVM tests and 106 documentation assertions; see
VERIFICATION.md for exact scope, artifacts, current timings and hardware limitations.
