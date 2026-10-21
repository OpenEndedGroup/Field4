# Detect gaps in pre-2026-08 keyframe .versions directories

- **Handoff:** `6d95aec8-9949-4223-a6fc-093c736ef61f` (project)
- **Filed from:** https://openendedgroup.com/field4/keyframer_examples/
- **Filed by:** human, 2026-10-20 11:10
- **Close with:** `python tools/docs_notes.py handoff-close 6d95aec8 "what happened"`

`public/docs/keyframer_examples.md` ("Agents: tests, and gaps in older version directories" aside) says:

> A `.versions` directory written before 2026-08 can have gaps. An unparseable manifest read as no history at all back then, so the next save called itself version 0 and wrote over `000000.json`. Whatever is on disk is still readable; what was overwritten is gone.

This is a warning about known-damaged data. Fix in code: have `keyVersions` (keyframer.kt) detect a gap or a pre-2026-08 manifest and report it on `error,keyframer` (or the browser marks the gap), or write a one-off scan that finds such directories. Delete the paragraph in the same change.
