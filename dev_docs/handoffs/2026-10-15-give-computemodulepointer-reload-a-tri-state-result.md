# Give ComputeModulePointer.reload a tri-state result

- **Handoff:** `bcc58652-3064-4bf9-aa47-9d1122e1d83f` (project)
- **Filed from:** https://openendedgroup.com/field4/path_tracing/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close bcc58652 "what happened"`

Sharp edge (rule 25) on public/docs/path_tracing.md, "Replacing the integrator on a running tracer":

> (The underlying `ComputeModulePointer.reload` answers `false` both for "this source is already compiled" and for "this source does not compile", so code wrapping it in `check(…)` throws on a no-op.)

Fix in code: make `ComputeModulePointer.reload` return a tri-state result (unchanged / reloaded / failed, like `IntegratorReload`) instead of an ambiguous Boolean. Delete the parenthetical in the same change.
