# Reset sample count synchronously in reloadIntegrator

- **Handoff:** `69e9d8b5-e6db-4eeb-9742-fda8f171e89c` (project)
- **Filed from:** https://openendedgroup.com/field4/path_tracing/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 69e9d8b5 "what happened"`

Sharp edge (rule 25) on public/docs/path_tracing.md, end of "Replacing the integrator on a running tracer":

> The swap takes effect a frame after it is requested ... So waiting on a sample count immediately after a reload is satisfied by the count the **old** integrator left behind. Wait for `samples` to fall first, or for `lastResetReason` to change.

Fix in code: `reloadIntegrator` should reset `samples`/advance `resetGeneration` synchronously when it reports RELOADED (or return a handle/generation to wait on), so a wait on the sample count cannot be satisfied by the old integrator. Delete the paragraph in the same change.
