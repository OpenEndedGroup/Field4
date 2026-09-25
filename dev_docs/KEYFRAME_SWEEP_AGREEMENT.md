# Keyframe sweep: what the CPU layer proves, and what only a GPU can

Why `keyframeSweep.kt` is verified twice. `public/docs/keyframe_autosweep.md` names both entry
points; the argument for there being two is here.

## The CPU half

`afield.stdlib.KeyframeSweepTest` recomputes `Σ wᵢ·vᵢ` from each manifest and requires it to
reproduce the `key.json` beside it, which is the whole provenance contract. It then pins the
reproducibility, the resume, the three lane policies, the failure isolation, the disk floor and the
sampling distribution.

Every one of those assertions would still hold if applying a blend reached no uniform at all. The
blend is arithmetic over a manifest; nothing in the CPU layer observes whether the number it
computed ever arrived at a shader.

## The GPU half

So the claim that the *picture* is a function of the blend is checked separately, by
`tools/run_field_tests.py --id keyframe.sweep.smoke`. It runs a real six-render sweep and requires
pixel distance to correlate with key distance across every pair of renders, at better than 0.6.

What it deliberately does **not** require is that consecutive renders differ. Two independent draws
can land next to each other in the space legitimately, and a test that forbade that would fail on
correct behaviour.

The same run re-applies a saved key and re-renders it. On the runs observed so far that has
reproduced the sweep's own still exactly, which is more than the test asks for, given that the
renderer carries a frame of temporal history. The test asserts the correlation, not the exact
reproduction, so that surplus is an observation and not a guarantee.

## Open

Nothing asserts the exact re-render, so a regression that made it approximate would pass. Pinning
it would mean deciding what "exactly" survives a frame of temporal history, which is why it is an
observation here rather than an assertion in the smoke.
