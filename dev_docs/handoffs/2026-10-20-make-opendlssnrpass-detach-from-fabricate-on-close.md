# Make OpenDlssNrPass detach from Fabricate on close

- **Handoff:** `58765a88-696e-489e-a7a3-5d53fbf0cd26` (project)
- **Filed from:** https://openendedgroup.com/field4/open_dlss_nr/
- **Filed by:** human, 2026-10-20 11:10
- **Close with:** `python tools/docs_notes.py handoff-close 58765a88 "what happened"`

Rule 25 sharp edge in `public/docs/open_dlss_nr.md` ("Put the pass in your own pipeline"):

> Insert it before grading and tone mapping; reattach the graph when changing its order, and detach it before closing the pass.

Ordering trap with Fabricate `pass.postEffect(...)`. Fix in code: let `OpenDlssNrPass.close()` detach itself from any Fabricate graph, and have reordering re-attach automatically. Delete the clause in the same change.

Done 2026-10-29: OpenDlssNrPass.close() removes its effect from every FabricatePostStack it was added to (FabricatePostStack.retire), which re-attaches each attached bundle; a frame graph still naming the closed target skips it. Reordering a bundle post stack already re-attached automatically. Verified in four.open-dlss-nr.live-edit. Doc clause deleted.
