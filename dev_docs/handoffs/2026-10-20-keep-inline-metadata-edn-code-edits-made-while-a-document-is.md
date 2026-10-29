# Keep inline metadata.edn code edits made while a document is closed

- **Handoff:** `dd063768-4977-4fd6-8f68-327edd1ef442` (project)
- **Filed from:** https://openendedgroup.com/field4/editing_from_outside/
- **Filed by:** human, 2026-10-20 11:09
- **Close with:** `python tools/docs_notes.py handoff-close dd063768 "what happened"`

Rule 25 sharp edge on `public/docs/editing_from_outside.md` ("When your edit loses"):

> When the document is closed, the box's file is what counts and an inline edit is lost on the next open, so editing the file is the safer habit.

Fix in the loader: when the inline copy in `metadata.edn` is newer than the sidecar (or differs and metadata.edn is newer by mtime), keep the inline edit, or at least warn and back it up. Delete the sentence in the same change.
