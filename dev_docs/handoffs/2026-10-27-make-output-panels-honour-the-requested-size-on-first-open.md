# Make output panels honour the requested size on first open

- **Handoff:** `b1b26424-ba66-4dd9-a0f0-ee576d5c5cc3` (project)
- **Filed from:** https://openendedgroup.com/field4/agent_transcripts/
- **Filed by:** human, 2026-10-27 18:21
- **Close with:** `python tools/docs_notes.py handoff-close b1b26424 "what happened"`

Rule 25 sharp edge on `public/docs/agent_transcripts.md`, entry `confluence_grid`:

> its output panel opens at 480 x 174 and ignores the size the viewer asks for, a known defect described in `dev_docs/OUTPUT_PANEL_SIZING.md`. Drag the panel once and the size sticks.

Fix in code: make the output panel honour the requested size on first open (see `dev_docs/OUTPUT_PANEL_SIZING.md`), so the `confluence_grid` panel opens at the size it asks for without a drag. Delete this sentence from the `confluence_grid` entry in the same change.
