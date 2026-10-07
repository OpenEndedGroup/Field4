# Make the Loris partial explorer a starting point

- **Handoff:** `6f9b5a2d-6c66-4a43-bd10-508fc7d2ce47` (project)
- **Filed from:** https://openendedgroup.com/field4/loris/
- **Filed by:** human, 2026-10-07 21:10
- **Close with:** `python tools/docs_notes.py handoff-close 6f9b5a2d "what happened"`

`loris.md` ("A document to explore") links the Loris partial explorer as
`offline.html?file=assets/agent_transcripts/loris_partial_explorer/metadata.edn`, a read-only
viewer. Rule 19: a runnable document is a starting point on the welcome screen. Publish the
explorer as a starting point and link that instead. Also check whether
`model.spectralWhirlpool(...)` really needs the `import afield.stdlib.loris.spectralWhirlpool`
the page tells readers to write (rule 7: no `afield.stdlib.*` imports in box code); if so, add it
to the box preamble's imports and drop the sentence's import.
