# Save Patchy's own Save/Save As into the Field document

- **Handoff:** `db16218d-8196-4767-97ad-00ef2c36f848` (project)
- **Filed from:** https://openendedgroup.com/field4/patchy/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close db16218d "what happened"`

Sharp edge (rule 25) on public/docs/patchy.md, "Saving in the Field document":

> A changed PSD on disk is reported as a conflict in the output status line rather than silently overwritten. Patchy's own Save/Save As still downloads through the browser.

Inside Field, Patchy's Save/Save As menu items should write to the session's `documentFile` (or prompt for a document-relative file) via the existing bridge, instead of triggering a browser download that bypasses Field's autosave. Delete the "still downloads" sentence in the same change.
