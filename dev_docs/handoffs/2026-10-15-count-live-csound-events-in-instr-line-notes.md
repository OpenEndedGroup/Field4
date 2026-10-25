# Count live Csound events in instr line notes

- **Handoff:** `6899745e-cc72-4293-a909-90daa2c6a4e1` (project)
- **Filed from:** https://openendedgroup.com/field4/csound/
- **Filed by:** human, 2026-10-15 13:46
- **Close with:** `python tools/docs_notes.py handoff-close 6899745e "what happened"`

Rule 25 sharp edge on `public/docs/csound.md` ("Line notes"):

> The event count comes from the score text, so events sent later with alt/option-Enter or from Kotlin are not counted.

Fix in code: have the `instr` line note count events the running Csound has actually received (score text plus live `csoundReadScore`/`event` calls), or label the count "in the score" in the note itself. Delete the paragraph in the same change.
