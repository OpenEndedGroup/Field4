# Native capture ownership: 2026-09-20

Follow-up to the #111 stall investigation and the progress/budget repair. The
remaining investigation found a reproducible **wrong-window** failure, not a
Fabricate scene failure.

## Reproduction and repair

The old `wait-native-renderer <uid>` checked the target's errors but did not send
the target uid in its screenshot RPC. The handler used process-global
`lastWindowToOpen`. A different document could therefore redirect the capture,
while the reply still labelled it with the requesting document's session key.

Two live scratch documents in one owned Field JVM reproduced it. Each had the
canonical renderer box named `agent_graphics_context`; A kept rendering and B's
frame pump was paused without disposing its window. **A advanced from frame 421
to 1088 while its readiness request failed on B's unchanged frame 140.**

The repair:

- The screenshot RPC has optional `uid`; readiness forwards its uid, and
  `native-screenshot` accepts `--uid`. Docs-generated native captures target their
  own example box.
- The handler resolves window bindings only in the requesting document. A target
  uses its nearest parent layer; unknown targets and ambiguous layers fail
  explicitly. No global last-window fallback remains in this handler.
- Without a uid, the document must have one distinct live bound window. An empty
  document cannot borrow another document's window. Closed windows are excluded.
- Existing box properties and already-created script engines are inspected;
  resolution does not initialize REPL engines. Foreign parent objects are excluded.

After the repair, the same A request succeeded in one capture attempt (frames
413 → 505), while B still correctly failed on its own paused frame 150. Additional
live checks passed for A's untargeted capture and a connected child target. A raw
RPC with B's private control-box id was rejected from A, and a third empty
document was rejected; neither rejection wrote an image.

## Historical evidence: what it does and does not show

The surviving `~/.afield/logs/save_reload.log` records the September 17 failure at
`13:47:06.719376Z` against a path ending in
`20260917-084704-pid39707-6117b255-native-renderer-ready.png`. This was the renderer
readiness stage, **before the docs command sequence launches the Fabricate scene**.

The journal's `windowIndex=0` is a per-renderer index, not a global window identity.
Its nearby successful captures belonged to a different checkout/process: their
paths were `/tmp/claude-501/-Users-marc-field4/51b38845-6a0c-4db0-8413-c42f524d8a20/scratchpad/q-*.png`,
not the CI worktree. The machine-wide log alone does not establish that they
captured the same renderer. The original CI worktree/client logs have since been
removed, so this repair is **not claimed as proven attribution of that incident**.
It does reproduce and remove a concrete cause of the same false-stall symptom.

## Verification

- `./gradlew --no-daemon jvmTest --tests afield.NativeCaptureTargetTest --tests
  afield.four.WindowCaptureQueueTest`: **12 passed**, tree-write guard passed,
  2m 38s. Includes nearest-parent selection, cyclic graphs, ambiguity, unknown uid,
  duplicate window bindings, and identical box ids in separate documents.
- Focused Python suites: **106 passed**, including RPC uid forwarding and the docs
  capture command. The first pass caught the newly added option missing from the
  command table; the table was corrected before the passing run.
- Live before/after ownership checks: passed their expected broken/fixed outcomes
  on dedicated port 9573 with scratch documents. The first reproduction fixture
  had a quoting error in its frame probe; it was corrected with structured argument
  quoting before collecting the frame evidence above.
- The actual docs-generated Fabricate default-scene sequence passed all eight
  commands after the repair. The resulting PNG was opened and visually inspected:
  all three colored toruses rendered. Only the output path was redirected into
  `build/`; no committed capture was replaced.
- `./gradlew --no-daemon checkCodebaseReference checkKDocAttachment citationGates`:
  passed in 2m 27s, including the tree-write guard.
- Full Python tool suite: **551 passed / 552 run, one skipped** (the built-site
  integration test has no built site in this worktree).
- `git diff --check` passed; ports 9573 and 19573 had no remaining listeners after
  verification. The branch remains `codex/backlog-renderer-errors-2026-09-20` at
  `af9aa20fd67287b6bc303ff960502a362fddb32a`, with nothing staged.

Local evidence:

- `/tmp/field4-backlog-ownership-before-fixed-fixture.jsonl`
- `/tmp/field4-backlog-ownership-after.jsonl`
- `/tmp/field4-backlog-ownership-jvm.log`
- `/tmp/field4-backlog-ownership-python-focused-final.log`
- `/tmp/field4-backlog-ownership-python-final.log`
- `/tmp/field4-backlog-ownership-guards.log`
- `/tmp/field4-backlog-ownership-fabricate.json`
- `build/fabricate-docs-capture-1789940155079363000.png`
- `build/verify_capture_ownership.py` (live integration driver)
- `build/capture-ownership-20260920-163448-pid31599-35044f1d/`

These changes remain isolated with the preceding backlog repairs. No commit,
merge, push, full smoke catalog, or full docs recapture is implied by these checks.
