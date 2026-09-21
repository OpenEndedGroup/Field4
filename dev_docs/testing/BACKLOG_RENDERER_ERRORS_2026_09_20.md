# Renderer error reporting: 2026-09-20

Repair for nightly CI backlog #107, based on `af9aa20fd67287b6bc303ff960502a362fddb32a`.
This is a Python backdoor change, not a change to the server's operation-counting
contract or the interactive macro panel.

## What changed

- `wait-native-renderer <uid>` returns the target's active execution diagnostic
  immediately, even while a native screenshot request is pending. Pending client
  requests are cancelled and awaited. Empty error-clear packets and errors from
  other boxes do not abort the wait. A uid-less wait does not guess ownership.
- `macro run` / `macro undo` and the `transcript` aliases grade returned box output
  as well as operation success. The original operation verdict is retained in
  `operationSuccess` when execution errors are found. `--no-fail-on-error` retains
  diagnostics and waives only the execution verdict, never an operation failure.

## Live verification

All three sessions used dedicated port 9573 and scratch documents in this
worktree's `build/field-automation-workspace`. Each backdoor process owned its
server and shut it down on exit. Ports 9573 and 19573 were free afterward.

1. Real renderer: ran the unmodified
   `dev_docs/agents/transcripts/graphics_renderer_launch.yaml`, then
   `wait-native-renderer agent_graphics_context --frames 3`. Macro and wait both
   succeeded. The wait took 453 ms, one capture attempt. Inspected the actual
   4112 x 2524 PNG: the expected black, empty renderer framebuffer. This checks
   native bring-up/capture, not scene rendering. Process exit: 0.
2. Real execution error: a three-operation create/launch/wait transcript launched
   `error("BACKLOG_INTENTIONAL_EXECUTION_FAILURE")`. The server reported three
   applied operations, but the backdoor returned `ok: false`, `success: false`,
   `operationSuccess: true`, and the original diagnostic in `errors` and
   `executionErrors`. Process exit: 1, as required.
3. Explicit waiver plus renderer wait: ran the same transcript with
   `macro run deliberate --no-fail-on-error`, then
   `wait-native-renderer backlog_failure --frames 3 --timeout 90`. The macro
   retained its diagnostic and `executionErrorsIgnored: true`. The wait failed
   immediately with `terminal: true`, `timedOut: false`, zero capture attempts,
   and the original diagnostic. Start and failure timestamps were both
   `2026-09-20T18:58:18.360Z` (millisecond log resolution). Process exit: 1.

Local evidence (not committed/generated documentation assets):

- `/tmp/field4-backlog-20260920-live-renderer.json`
- `/tmp/field4-backlog-20260920-live-macro-error.json`
- `/tmp/field4-backlog-20260920-live-wait-error.json`
- `.field-backdoor/logs/20260920-135721-pid71975-328b58e8.jsonl`
- `.field-backdoor/logs/20260920-135721-pid71975-328b58e8-native-renderer-ready.png`
- `.field-backdoor/logs/20260920-135809-pid72397-368fa572.jsonl`

The live negative test deliberately threw a runtime exception. It did **not**
reproduce a physically disconnected display. The recorded no-primary-monitor
diagnostic is covered by unit tests, including arrival during a pending capture.

## Build and regression coverage

`./gradlew jvmJar generateLocalKeystore` passed in 2m 16s, with all 22 tasks
executed. This built both JVM and browser code in the fresh worktree. Package
manager deprecation warnings remain; this repair does not alter dependencies.

The Python regression tests cover existing and in-flight terminal errors,
cleared/unrelated errors, uid-less readiness, transient retry, deadline cancellation,
macro/transcript verdicts, undo, explicit waiver, and preservation of operation
failures. Run the complete tool suite with:

```bash
uv run --project . --group backdoor python tools/run_python_tests.py
```

The verification here reused `/Users/marc/field4`'s frozen uv environment with
`--project /Users/marc/field4 --frozen`, while executing/importing the changed
Python files from the isolated worktree.

Final result: **546 passed / 547 run, 1 skipped**, process exit 0. The skipped
check requires a built `public/field4` website, absent in this fresh worktree.
The changed backdoor suite passed all 79 tests. Full output:
`/tmp/field4-backlog-20260920-python-final.log`. `git diff --check` also passed.

Not run: full `check`, smoke catalog, UI suite, or full docs recapture. Backlog
#111 (renderer frame-loop stall) and #112 (capture-budget propagation) remain
separate, unresolved work. Cancelling the Python request is not a claim that a
server-side capture already in flight has been cancelled.
