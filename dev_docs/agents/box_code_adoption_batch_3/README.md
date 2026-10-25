# Helper adoption: traces, snapshots, timing and spatial grids

Branch `codex/box-code-helper-adoption-3`; baseline `72cdc6dcc`.
Four source groups contain 23 Kotlin code states: eight states in the interpolants page,
five interaction-net examples, three imaging boxes and seven spatial boxes.

## Changes

- Interpolants call `startAt` directly. Explicit resets remain at initialization and retargeting.
  The duration control drives both the actual interpolant and its guide/legend. `breakNext`
  removes first-point branching in six traces; keyframes use `Key` vararg pairs. The position
  comment now describes its linear interpolation. Two existing collection-overload ambiguities
  are repaired with explicit element concatenation and `lines(path, gain, code, ...)`.
- All five interaction-net examples use captured `stage` values and `showInteractionStages`.
  Construction, relaxation, individual reactions and full reduction remain visible. Summaries
  are captured at their own stage, before later mutations. Stage cards now precede the extra
  rewrite card; the three-stage example retains explicit smaller panels and padding.
- Imaging examples use `bestTimeMs` with their original repetition and warm-up counts. Blur
  settings are near the top, numeric readouts use `.fN`, and the timing explanation reports the
  observed winner. The comparison radius is named, with no fixed hardware crossover claim.
- The spatial transcript uses `FLine.grid`, collection literals and explicit FLine concatenation.
  Euler counts are described as necessary count checks, with their limits. Clustering comments
  distinguish Ward's squared Euclidean costs from the default L1 objectives.

[Source diff](sources.diff). Mechanical imports/ktfmt cover every state, including both halves of
the documentation diff. The normal formatter does not handle Markdown: [format_docs.py](format_docs.py)
extracts independent states, carries fold markers through formatting, and restores them. Its final
`--check` is clean, as is the normal transcript formatter's `--check --imports`. The explicit
collection-literal audit passes. No GLSL properties occur in these four groups.

Only code states changed. Transcript descriptions, graph/frames/operation order, Markdown prose,
runner metadata and existing fragment/fold markers are preserved. There are no library API changes.
Handoff `921abe5f` covers the old interaction alt text and imaging description.

## Verification

- The selected two-transcript compile/replay guard passed. Every Markdown state also compiled and
  executed in the actual Field box compiler; both documentation diff states were exercised.
- **84 successful runtime batches / 175 commands**, with **25 genuine captures**. The private
  Field and headless browser ran on 9699; the user's Electron/document was not involved. The
  session and its managed server were shut down after verification.
- **19 deterministic fingerprints match before/after:** six interpolation/trace states, five
  interaction-net examples, three image computations and five spatial computations. Comparisons
  hash actual float-array values, line-node types/endpoints, contours, cluster labels/objectives,
  and net node/edge/energy measurements. They do not compare machine-dependent timing values.
- The two random correlation examples retain finite output and matching dimensions/metric keys;
  their random samples are not claimed identical. Both animated sweeps produced actual output,
  then stopped with no live run resources. A same-box sweep relaunch and stop also passed.
- At duration 0.8, the time curve first reaches its target at 0.804 seconds under the example's
  uneven cadence; its guide is at x = 530 and its legend reports 0.8. At radius 24 the measured
  running sum was 6.325 ms versus 14.676 ms for the kernel, and the printed winner agrees. These
  are observations from this run, not performance thresholds.
- All five interaction-net captures, the three imaging grids, and representative interpolation
  and spatial figures were inspected. The before/after stage drawings remain distinct, with
  readable cards and the expected reaction counts. Existing long title/caption truncation is
  recorded in the prose handoff; no public capture was replaced by these verification pictures.

The baseline interpolant curve-guide state and keyframe state did not compile unchanged under
current collection overloads. Their private baseline copies receive only the same two explicit
concatenation/vararg repairs described above before comparison. The original compiler failures are
retained; this is not a claim that those two original programs ran unmodified.

The harness also needed two repairs: `Close` nodes have no `to`, so comparison uses `toOrNull`;
and `animate` must be launched, not merely evaluated, to advance frames. Four failed diagnostic
verdicts remain in the archive, separate from the successful counts.

[Verification record](verification.json) binds final source hashes to results. [Evidence archive](evidence.tar.gz)
retains exact rehearsal programs, compact verdicts, failures, unedited PNGs and gate logs. Large
verdict strings are represented by byte length and SHA-256; exact programs and captures are separate.

## Continue

This batch addresses eight saved findings: `0abf058ae10d-01` through `-04`, `bb71d7b005b0-01`,
`293b7bec8185-01`, and `bc7141d7966c-01` / `-02`. The second-pass total is **17 of 405 addressed**,
with one separate Field Audio finding still awaiting fresh-service verification. All unlisted
findings remain open. No new helper request arose in this batch.

Next helper families: Faust render/measurement, run-owned OSC, midpoint solid slices, RectSL
projection/gallery and named Syphon reuse. Then continue through the remaining finding groups,
including the separate spatial documentation examples.

Run the private session, then the driver, from the repository root:

```sh
uv run --project . --group backdoor python tools/field_session.py serve --port 9699 --with-playwright --timeout 120
uv run --project . --group backdoor python dev_docs/agents/box_code_adoption_batch_3/rehearse.py
```

The landing queue regenerates starting points/completion fixtures and performs its full guard.
