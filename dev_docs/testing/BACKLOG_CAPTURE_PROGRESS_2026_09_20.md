# Native capture progress and budgets: 2026-09-20

Follow-up to backlog #107; addresses #111's detection/queue leak and #112's native
RPC budget propagation. Based on `af9aa20fd67287b6bc303ff960502a362fddb32a` in the
isolated `codex/backlog-renderer-errors-2026-09-20` worktree.

## Contract

- Capture requests record their own window's capture-point counter. After five
  seconds still queued at that same counter, the server returns a typed stall
  error with the window index and frame. Another window's progress cannot hide it.
- The watchdog runs off the render dispatcher. Once the frame dequeues a request,
  slow readback/encoding is governed by the total budget, not labelled a frame stall.
- Timeout and cancellation remove queued requests and cancel their completion.
  Dequeued GPU resources still follow the normal fence/readback cleanup; inactive
  requests skip readback/encoding when possible. This is not a promise to interrupt
  an encoder that has already started writing.
- The native screenshot RPC carries `captureTimeoutMs`, default 15000 for old
  callers. The Python client forwards the authored timeout and reserves one second
  of reply grace. One server budget covers readiness, settling and capture.
- A typed stall is `terminal: true`; the readiness loop does not retry it.

## Tests and live evidence

The six `WindowCaptureQueueTest` cases passed, as did the tree-write guard. They
cover per-window counters, an encode outlasting the watchdog interval, eight
successive expiries without queue growth, selective caller cancellation, invalid
budgets, and backward-compatible RPC decoding. The 81-test backdoor suite passed,
including new budget-forwarding and no-retry-on-terminal-response checks.

The first JVM gate failed **before running tests** because the reused Gradle process
could not start `uv`. `./gradlew --no-daemon jvmTest --tests
afield.four.WindowCaptureQueueTest` passed in 59 seconds using a fresh build process
and the same caches. An existing `CpuSkinnerTest.kt:173` unnecessary-safe-call
warning was emitted; it was not changed as part of this repair.

Live verification used port 9573, a scratch document, and the canonical renderer
launch transcript. To retain the window while stopping its frame pump, a connected
control box set `renderer.running = false`; relaunching the renderer resumed it.

| Check | Observed result |
| --- | --- |
| Paused renderer, 30-second readiness budget | Terminal stall in **5.007 s**, **one attempt**, `windowIndex=0 frame=183 unchanged for 5000ms` |
| Paused renderer, 250 ms capture budget | Server reported `Timed out waiting for 250 ms`; client received it in **0.260 s** |
| Queue cleanup | Next capture request still logged `queueSize=1`, not a growing backlog |
| Resume same renderer | Readiness and a new native PNG capture passed; expired capture file remained absent |
| Authored budgets in server log | `timeoutMs=30000`, `250`, and `20000`, rather than every request receiving 15000 |

The first live fixture used `stop` on the renderer box, which correctly disposed
its window. That tested missing-window startup, not a stalled renderer, and timed
out after 30 seconds. It is retained as a failed fixture, not counted as a pass.
The corrected fixture only stopped frame pumping and passed the assertions above.

Evidence retained locally:

- `/tmp/field4-backlog-capture-jvm.log` (initial environment failure)
- `/tmp/field4-backlog-capture-jvm-fresh.log` (six tests plus tree-write guard)
- `/tmp/field4-backlog-capture-python-focused.log`
- `/tmp/field4-backlog-capture-live.jsonl` (incorrect stop-box fixture)
- `/tmp/field4-backlog-capture-live-paused.jsonl` (corrected live verification)
- `.field-backdoor/logs/20260920-160428-pid16502-b6b4be02.jsonl`
- `build/capture-watchdog-20260920-160428-pid16502-b6b4be02/recovered.png`
- `build/verify_capture_watchdog.py` (local integration driver)

The default-scene **docs-generated** invocation also passed all eight commands,
including Playwright attachment, renderer macro, readiness, the authored `launch`
code, and the fresh source-image wait. Only its output path was redirected into
`build/`; no committed capture was overwritten. Inspected the actual 2048 x 1257
PNG containing the three rendered torii:
`build/fabricate-docs-capture-1789938294772758000.png`. Invocation/results are in
`/tmp/field4-backlog-fabricate-docs.json`; the local driver is
`build/verify_fabricate_docs_capture.py`.

Final source/documentation gates passed: `generateCodebaseReference`,
`checkCodebaseReference`, `checkKDocAttachment`, `citationGates`, and the citation
test tree-write guard, in 3m 40s. Generated reference content remained unchanged.
Log: `/tmp/field4-backlog-capture-guards.log`.

Final Python suite: **548 passed / 549 run, one skipped**, exit 0. The skipped
check requires a built docs website, absent in this worktree. Log:
`/tmp/field4-backlog-capture-python-final.log`. `git diff --check` passed. Both
automation listener ports (9573 and 19573) were free after live verification.
Changes remain unstaged/uncommitted in the isolated worktree; no merge or push.

## What is not closed

The historical Fabricate launch stall has **not been reproduced or assigned a
root cause**. The current default-scene smoke passed before this repair in 7615 ms
(`/tmp/field4-backlog-capture-baseline.log`). Current Fabricate figures use the
post-stack texture readback harness, not the native-window screenshot RPC. This
repair does not silently reinterpret every docs fence timeout as a budget for
arbitrary Kotlin code inside that harness.

No full smoke catalog, full docs recapture, or repository-wide `check` is claimed.
