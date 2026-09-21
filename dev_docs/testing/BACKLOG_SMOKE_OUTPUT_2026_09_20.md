# Smoke output evidence after reboot: 2026-09-20

Continued on `codex/backlog-renderer-errors-2026-09-20` at
`af9aa20fd67287b6bc303ff960502a362fddb32a`. The preceding capture repairs survived
the reboot unchanged. Main had advanced to `890a84b55`; this work did not modify,
merge into, or commit on main.

## #101: a false green, not a demonstrated annotation-rendering race

The journal correctly identified the bad `"muted"` expectation in
`docs.logging.mute_is_exact`, but its explanation does not match the renderer:
`Table.expect(..., because = ...)` emits the reason as a tooltip only for a failed
cell. This example checks `it is Boolean`, so all cells pass and the reason is
absent. `Table.toHTML()` builds the table synchronously; there is no later
annotation render to wait for here.

A fresh post-reboot run of the unchanged expectation **passed in 5079 ms**. Its
retained events reveal why: `"muted"` appeared in the `set-string` source echo and
its enclosing `message-batch`, but in neither the `output` nor `set-output` value.
The smoke checker serialized unrecognized event payloads into its output search,
allowing the snippet to satisfy its own assertions without emitting the claimed
text. A timing-dependent source echo could explain intermittent results, but this
run does not establish the exact event sequence of the September 12 failure.

The repair:

- Output substring assertions now inspect only `output` / `peer-output` HTML,
  `set-output` values, and notification text. Batch envelopes, source echoes,
  topology, controls, unknown events, and malformed payloads contribute no text.
- The backdoor already expands each batch into child events, so ignoring the
  envelope does not discard its actual output. `eventKinds` still sees all events.
- The logging expectation checks the case-variant channel and emitted true/false
  table cells. Its 1.5-second wait and snippet are unchanged; no capture changed.
- A JVM test asserts the complete five-spelling Boolean vector, rather than
  pretending independent substring matches establish which value belongs to which
  row. A Table test pins the absence of a satisfied check's failure explanation.

The six new Python regression tests failed against the old checker (10 failing
subcases), then passed after repair. Re-evaluating the **same retained live run**
with the corrected checker gives `output missing substring: muted` for the old
expectation and no failures for the new one. This separates evidence selection
from a second execution or a longer wait.

## #113: the reported transcript failure already has a landed fix

Commit `0d28e36506c9178966b843c173f60295bbce2662` corrected the mega-texture
transcript on September 18. It declares `val renderer: Renderer = fromContext()`
and assigns camera uniforms through `m.push["p"]` and `m.push["m"]`. Thus the
reported failure was not all one unresolved-parent cascade: two assignments were
also wrong. Both the isolated worktree and current main contain these corrections.

The real transcript compiler passed again after reboot, explicitly filtered to
`mega_texture_wall`: one box compiled, no evaluation. This confirms the concrete
case was repaired rather than removed from the corpus. It does **not** add
cross-transcript prerequisite support to the harness, prove shader execution, or
claim a new live rendering check of the private photograph directory. No new
dependency mechanism was needed to fix this already-corrected example.

## Verification

- Focused Python runner suites: **64 passed**.
- `jvmTest` for `ConsoleMuteTest`, `TableTest`, `NativeCaptureTargetTest`, and
  `WindowCaptureQueueTest`: **46 passed** in 11s; tree-write guard passed.
- Fresh smoke sweep: **56 passed, zero failed**. Selected every one of the 55
  catalog entries using `outputContains` or `outputNotContains`, plus
  `examples.demo-transcript.exec` with `DEMO_TRANSCRIPT_ONLY=mega_texture_wall`.
  This is not a full smoke catalog or a full transcript corpus run.
- All six logging smokes passed; `mute_is_exact` took 2289 ms. These are observed
  run timings, not a performance claim across the post-reboot load change.
- Full Python tool suite: **557 passed / 558 run, one skipped** because the
  built-site integration test has no built site in this worktree.
- `checkCodebaseReference`, `checkKDocAttachment`, and `citationGates` passed in
  2m 41s, including the tree-write guard. `git diff --check` passed; nothing is staged.
- The owned Field on port 9573 was stopped by the smoke runner. Ports 9573 and
  19573 had no remaining listeners. Another checkout's JVM was left alone.

Local evidence:

- `/tmp/field4-backlog-logging-before.log`
- `build/field-tests/results.json` (retained before-fix smoke events)
- `/tmp/field4-backlog-output-live-recheck.log`
- `/tmp/field4-backlog-output-red.log`
- `/tmp/field4-backlog-output-focused.log`
- `/tmp/field4-backlog-output-jvm.log`
- `/tmp/field4-backlog-output-smokes.log`
- `build/field-tests/backlog-output-results.json`
- `/tmp/field4-backlog-output-python-all.log`
- `/tmp/field4-backlog-output-guards.log`

The code and verification notes remain uncommitted in the isolated worktree.
The journal's historical prose has not been overwritten and the repair is not
claimed landed on main.
