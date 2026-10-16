# Keep unsubmitted Klecks strokes across a box re-run

- **Handoff:** `f3cd1d8d-1315-46f5-aaba-4022d459a770` (project)
- **Filed from:** https://openendedgroup.com/field4/klecks/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close f3cd1d8d "what happened"`

Sharp edge (rule 25) on public/docs/klecks.md:

> Unsaved brush strokes remain in the open Klecks panel and will be lost if the box is run again or the page closes.

Re-running the box should not discard the open panel's unsubmitted work: either reuse the live panel when the channel is unchanged, or auto-submit (or stash a draft PSD) before the panel is replaced/unloaded. Delete the "will be lost" clause in the same change.
