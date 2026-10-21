# Let OpenDlssNrStudy.reload rebuild on shader interface change

- **Handoff:** `378f4d19-a2ce-40e1-b0d1-c1caa2f574dd` (project)
- **Filed from:** https://openendedgroup.com/field4/open_dlss_nr/
- **Filed by:** human, 2026-10-20 11:10
- **Close with:** `python tools/docs_notes.py handoff-close 378f4d19 "what happened"`

Rule 25 sharp edge in `public/docs/open_dlss_nr.md` ("The cube study code"):

> Keep both stages together when changing their interface; adding or removing a varying needs a full box rerun rather than the shader reload button.

Workaround paragraph. Fix in code: make `OpenDlssNrStudy.reload(vertex, fragment)` rebuild the pipeline (or the whole target) when the stage interface changes, instead of requiring a box rerun. Delete the sentence in the same change.
