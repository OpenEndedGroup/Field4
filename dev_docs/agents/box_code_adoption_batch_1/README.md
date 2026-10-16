# Helper adoption: first edit batch

Branch: `codex/box-code-helper-adoption`, baseline `32744aa1d`.
This starts the second, editing pass. It is a representative pilot, not a completed corpus sweep.

## Changes

| Transcript | Edits |
| --- | --- |
| `spin_glass` | `bindPush`, scalar `Int` suppliers, `computeBarrier` and `fullscreenTriangle`. Explicit compute-to-graphics publication after the second half-sweep. Named captured buffers avoid binding-name shadowing. Positive/even lattice validation, rebuild guidance and live-temperature guidance. Unsigned seed conversion remains explicit so integer wrap preserves its bit pattern. |
| `frames_lifecycle` | `.f1`/`.f0` for readouts and bounded `markdownAppend` for cleanup history across runs. Preserve `.end`/`finally`, clock gating and the separate readout channel. |
| `mesh_geometry_examples` | `compare`, `integrate`, seeded `randomPoints`, and `breakNext`. Correct radial noise to ±9%, Taubin to reduced shrinkage, Gauss–Bonnet to a global consistency check, and BVH wording to spatial pruning. |
| `loris_partial_explorer` | First analysis box uses `runAsync` with guarded progress/result/error publication and native cancellation. All five boxes receive mechanical import/ktfmt formatting. The remaining four async chains retain their semantics for a later bounded edit. |

The formatter ran on all four transcripts, including their GLSL properties. GLSL was already a
formatting fixed point and remains unchanged. There are **14 changed code bodies in four
transcripts containing 20 boxes**. Only code properties changed: descriptions, graph, frames,
operation order and existing fragment markers compare equal with the baseline. There are no new
fragment boundaries, raw-property backdoors or helper API changes.

[Reviewable source diff](sources.diff). The full before/after programs are retained in the evidence
archive. Prose mismatches are filed separately as handoff `14a871f3`, following the code-only pass
contract.

## Verification

- The selected `checkBoxCodeCompiles` pass succeeded for all four transcripts. The mesh transcript
  also executed through its normal classification. The final graphics edits compiled and ran in
  the native rehearsal. The four-file idiom check is clean.
- All seven mesh boxes replayed before and after. The helix has one MoveTo and 120 LineTo nodes;
  tube volume is exactly `0.23488297253462` in both. All 48 Voronoi cells are closed. Their summed
  volumes match their clip boxes within `1e-5` relative error. Seeded coordinates differ slightly
  because the helper's explicit bounds are floats; the total changed from `81.83948629100902` to
  `81.83949564336191`. This is numerical agreement, not bit-for-bit equivalence.
- Native spin-glass runs at side lengths 128 and 130 reported exactly 16,384 and 16,900 finite
  probe samples. Live field/mode/temperature edits were made in the owning update box. The 130
  run was also rebuilt with Gaussian bonds. Zero and 129 were rejected before spin allocation.
- Cleanup was exercised through stop, normal return and rerun. Stop/rerun adds `.end`, then
  `finally`; normal return adds `returned`, then `finally`. One owned job remains after relaunch,
  zero after stop. The bounded history retains previous cleanup across launches.
- The first Loris box completed a real native analysis (3,687 partials), displayed actual source/
  reconstruction waveforms and trajectories, and reran from cache. A fresh private-cache run was
  stopped after its native child appeared: the child exited, request directories disappeared and
  no provenance was published by that cancelled run. This does not claim audition or execution
  of the other four Loris transforms.
- Genuine native and separate headless browser captures were inspected. Scratch mesh boxes were
  moved apart in the harness so other output panels could not cover the captured box. The user's
  Electron/document was not involved. Captures are verification artifacts, not docs replacements.

## What the rehearsal caught

`L = { L }` inside a `bindPush` receiver compiled through the union-like `Any` supplier, but
returned the binding itself at runtime. `spins` had the same receiver-name hazard. Captured setup
values are now `latticeSide`, `spinData`, `rightBonds` and `downBonds`. This keeps both the creative
control and the binding readable. A mistaken draw-box `L` reference was also corrected.

The first live-control harness wrote inherited properties from the measure box, which creates
child overrides rather than editing their owner. It now performs the gesture in the update box.
These failures are retained as diagnostics and are not counted as successful runs.

## Continue the pass

Use the first sweep's [findings](../box_code_first_pass/findings.json) as the worklist. This batch
addresses all five mesh findings and both spin-glass findings. The Loris lifecycle finding is
**partially addressed: one of five async bodies**; its whirlpool-control finding is still open.
Other groups are untouched. The next bounded batch should handle the remaining Loris chains and
Field Audio adapters with cancellation at every stage, then move to the other approved-helper
families. Mechanical formatting alone does not resolve their lifecycle findings.

Run the private session and then the two drivers from the repository root:

```sh
uv run --project . --group backdoor python tools/field_session.py serve --port 9697 --with-playwright --timeout 120
uv run --project . --group backdoor python dev_docs/agents/box_code_adoption_batch_1/rehearse.py
uv run --project . --group backdoor python dev_docs/agents/box_code_adoption_batch_1/extra_checks.py
```

[Evidence record](verification.json) binds source hashes, verdicts and the preserved structure.
[Evidence archive](evidence.tar.gz) contains actual captures, baseline sources and raw verdicts.
Public starting-point regeneration and the full guard belong to the landing queue.
